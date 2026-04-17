package com.simultaneousinterpretation.meeting;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 听众音频 REST 轮询接口：
 *
 * <p>废弃 /ws/room 的 WebSocket 二进制传输，改用 HTTP 轮询。
 * <p>每 300ms 轮询一次，拉取自上次 poll 以来的所有音频帧。
 *
 * <p>后加入的听众通过 /audio/history 接口追回最近 N 段的音频。
 *
 * <p>二进制帧格式（与原 WebSocket 二进制一致）：
 * [4-byte segIdx][1-byte type=0x01][1-byte langLen][lang bytes][...WAV...]
 * [4-byte segIdx][1-byte type=0x03][1-byte langLen][lang bytes][1-byte reasonLen][reason bytes]
 */
@RestController
@RequestMapping("/api/room")
public class RoomAudioController {

  private static final Logger log = LoggerFactory.getLogger(RoomAudioController.class);

  /** 服务器当前时间戳（毫秒），前端用它计算网络往返延迟） */
  public static final String HDR_SERVER_TS = "X-Server-Ts";
  
  private final RoomManager roomManager;

  /** 历史缓冲区：roomId/lang -> segIdx -> 完整帧 payload（含 header） */
  private final Map<String, Map<Integer, byte[]>> historyBuffer = new ConcurrentHashMap<>();

  public RoomAudioController(RoomManager roomManager) {
    this.roomManager = roomManager;
  }

  // ─── 音频轮询 ─────────────────────────────────────────────────────────────

  /**
   * 轮询音频帧：返回自 lastPollSeq 以来所有待发送的二进制音频帧。
   *
   * @param roomId     房间ID
   * @param listenLang 听众选择的语言
   * @param lastPollSeq 上次轮询序号（首次请求传 0 或空）
   * @return 二进制字节流，每帧 = [4-byte length][payload]，length=0 表示无新帧
   */
  public static final String HDR_AUDIO_LAST_SEQ = "X-Audio-Last-Seq";

  @GetMapping("/{roomId}/audio/poll")
  public ResponseEntity<byte[]> pollAudio(
      @PathVariable String roomId,
      @RequestParam String listenLang,
      @RequestParam(defaultValue = "0") long lastPollSeq) {
    long startTs = System.currentTimeMillis();
    String key = roomId + "/" + listenLang;
    long serverTs = System.currentTimeMillis();

    log.info("[AudioPoll-REQ] ★★ POLL START roomId={} listenLang={} lastPollSeq={} timestamp={} serverTs={}",
        roomId, listenLang, lastPollSeq, startTs, serverTs);

    HttpHeaders headers = new HttpHeaders();
    headers.setCacheControl(CacheControl.noStore().mustRevalidate());
    headers.set(HDR_SERVER_TS, Long.toString(serverTs));

    AudioQueue q = audioQueues.get(key);
    if (q == null) {
      log.info("[AudioPoll-EMPTY] ★空队列★ roomId={} listenLang={} lastPollSeq={} duration=0ms",
          roomId, listenLang, lastPollSeq);
      headers.set(HDR_AUDIO_LAST_SEQ, "0");
      return ResponseEntity.ok().headers(headers).body(new byte[]{0, 0, 0, 0});
    }

    List<byte[]> frames = q.drainSince(lastPollSeq);
    long seqAfter = q.currentSeq();
    headers.set(HDR_AUDIO_LAST_SEQ, Long.toString(seqAfter));

    if (frames.isEmpty()) {
      log.debug("[AudioPoll-NO-FRAME] roomId={} listenLang={} lastPollSeq={} newSeq={} duration={}ms",
          roomId, listenLang, lastPollSeq, seqAfter, System.currentTimeMillis() - startTs);
      return ResponseEntity.ok().headers(headers).body(new byte[]{0, 0, 0, 0});
    }

    int totalSize = frames.stream().mapToInt(f -> 4 + f.length).sum();
    ByteBuffer buf = ByteBuffer.allocate(totalSize);
    int frameIdx = 0;
    for (byte[] f : frames) {
      buf.putInt(f.length);
      buf.put(f);
      // 详细日志：每帧的 segIdx、type、大小
      byte frameType = f.length >= 5 ? f[4] : -1;
      String typeStr = frameType == 0x01 ? "WAV" : frameType == 0x03 ? "END" : String.format("0x%02x", frameType);
      if (f.length >= 8) {
        ByteBuffer fb = ByteBuffer.wrap(f);
        int segIdx = fb.getInt(0);
        log.info("[AudioPoll-FRAME-%d] ★ roomId={} segIdx={} type={} frameSize={}B",
            frameIdx, roomId, segIdx, typeStr, f.length);
      } else {
        log.info("[AudioPoll-FRAME-%d] ★ roomId={} type={} frameSize={}B (header too short)",
            frameIdx, roomId, typeStr, f.length);
      }
      frameIdx++;
    }

    long duration = System.currentTimeMillis() - startTs;
    log.info("[AudioPoll-SUCCESS] ★★ ★★ {}帧 roomId={} listenLang={} lastPollSeq={}→{} size={}B duration={}ms serverTs={}",
        frames.size(), roomId, listenLang, lastPollSeq, seqAfter, totalSize, duration, serverTs);

    return ResponseEntity.ok().headers(headers).body(buf.array());
  }

  /**
   * 听众首次加入时获取最近 N 段的音频历史，用于追回放。
   * 流程：join 后立即调用此接口，再用 poll 从 seq=0 开始轮询。
   *
   * @param roomId     房间ID
   * @param listenLang 听众选择的语言
   * @param limit      最多返回多少个段（默认 5，约够追最近 30 秒内容）
   */
  @GetMapping("/{roomId}/audio/history")
  public byte[] audioHistory(
      @PathVariable String roomId,
      @RequestParam String listenLang,
      @RequestParam(defaultValue = "5") int limit,
      HttpServletResponse response) {
    long startTs = System.currentTimeMillis();
    long serverTs = System.currentTimeMillis();
    String key = roomId + "/" + listenLang;

    log.info("[AudioHistory-REQ] ★★★ HISTORY START roomId={} listenLang={} limit={} timestamp={} serverTs={}",
        roomId, listenLang, limit, startTs, serverTs);
    response.setHeader(HDR_SERVER_TS, Long.toString(serverTs));

    Map<Integer, byte[]> hist = historyBuffer.get(key);
    if (hist == null || hist.isEmpty()) {
      log.info("[AudioHistory-EMPTY] ★空历史★ roomId={} listenLang={}", roomId, listenLang);
      return new byte[]{0, 0, 0, 0};
    }

    // 按 segIdx 降序取最近 limit 个
    List<Map.Entry<Integer, byte[]>> recent = hist.entrySet().stream()
        .sorted(Map.Entry.<Integer, byte[]>comparingByKey().reversed())
        .limit(limit)
        .toList();

    int totalSize = recent.stream().mapToInt(e -> 4 + e.getValue().length).sum();
    ByteBuffer buf = ByteBuffer.allocate(totalSize);
    int count = 0;
    for (Map.Entry<Integer, byte[]> e : recent) {
      byte[] payload = e.getValue();
      buf.putInt(payload.length);
      buf.put(payload);
      count++;
      // 详细日志
      byte frameType = payload.length >= 5 ? payload[4] : -1;
      String typeStr = frameType == 0x01 ? "WAV" : frameType == 0x03 ? "END" : String.format("0x%02x", frameType);
      if (payload.length >= 8) {
        ByteBuffer fb = ByteBuffer.wrap(payload);
        int segIdx = fb.getInt(0);
        log.info("[AudioHistory-FRAME-%d] ★ roomId={} segIdx={} type={} frameSize={}B",
            count - 1, roomId, segIdx, typeStr, payload.length);
      }
    }

    long duration = System.currentTimeMillis() - startTs;
    log.info("[AudioHistory-SUCCESS] ★★★ ★★★ {}段历史 roomId={} listenLang={} size={}B duration={}ms serverTs={}",
        count, roomId, listenLang, totalSize, duration, serverTs);

    return buf.array();
  }

  /**
   * 查询队列/历史状态（调试用）。
   */
  @GetMapping("/{roomId}/audio/status")
  public Map<String, Object> audioStatus(@PathVariable String roomId) {
    Map<String, Object> result = new HashMap<>();
    result.put("roomId", roomId);
    result.put("serverTs", System.currentTimeMillis());

    Map<String, Object> queues = new HashMap<>();
    for (Map.Entry<String, AudioQueue> e : audioQueues.entrySet()) {
      queues.put(e.getKey(), Map.of("size", e.getValue().size(), "seq", e.getValue().currentSeq()));
    }
    result.put("queues", queues);

    Map<String, Object> history = new HashMap<>();
    for (Map.Entry<String, Map<Integer, byte[]>> e : historyBuffer.entrySet()) {
      history.put(e.getKey(), Map.of("segCount", e.getValue().size(), "segIdxs", e.getValue().keySet()));
    }
    result.put("history", history);

    return result;
  }

  // ─── 队列管理 ───────────────────────────────────────────────────────────

  private final Map<String, AudioQueue> audioQueues = new ConcurrentHashMap<>();

  public AudioQueue getOrCreateQueue(String roomId, String listenLang) {
    String key = roomId + "/" + listenLang;
    AudioQueue q = audioQueues.get(key);
    if (q == null) {
      q = new AudioQueue();
      AudioQueue existing = audioQueues.putIfAbsent(key, q);
      if (existing != null) q = existing;
    }
    return q;
  }

  /**
   * 音频帧入队（由 Dispatcher 调用）。
   * 同时存入历史缓冲区。
   */
  public void enqueueAudio(String roomId, String listenLang, byte[] payload) {
    String qKey = roomId + "/" + listenLang;
    AudioQueue q = audioQueues.get(qKey);
    if (q == null) {
      q = getOrCreateQueue(roomId, listenLang);
    }
    long seqBefore = q.currentSeq();
    q.enqueue(payload);
    long seqAfter = q.currentSeq();

    // 解码 payload 头部以获取 segIdx 和 type
    byte frameType = payload.length >= 5 ? payload[4] : -1;
    String frameTypeStr = frameType == 0x01 ? "WAV" : frameType == 0x03 ? "END" : String.format("0x%02x", frameType);
    log.info("[AudioController-ENQ] ★★ roomId={} listenLang={} frameType={} payloadSize={}B seq={}→{} queueSize={}",
        roomId, listenLang, frameTypeStr, payload.length, seqBefore, seqAfter, q.size());

    // 解析 segIdx 并存入历史
    if (payload.length >= 5) {
      ByteBuffer bb = ByteBuffer.wrap(payload);
      int segIdx = bb.getInt(0);
      if (segIdx >= 0) {
        String hKey = roomId + "/" + listenLang;
        historyBuffer.computeIfAbsent(hKey, k -> new ConcurrentHashMap<>()).put(segIdx, payload);
        log.info("[AudioHistory-STASH] segIdx={} roomId={} listenLang={} historySize={}",
            segIdx, roomId, listenLang, historyBuffer.get(hKey).size());
      }
    }
  }

  public void clearRoom(String roomId) {
    audioQueues.entrySet().removeIf(e -> e.getKey().startsWith(roomId + "/"));
    historyBuffer.entrySet().removeIf(e -> e.getKey().startsWith(roomId + "/"));
    log.info("[AudioController] 清理房间队列+历史 roomId={}", roomId);
  }

  // ─── 内部队列 ─────────────────────────────────────────────────────────────

  public static class AudioQueue {
    private final List<AudioFrame> frames = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger(0);

    public void enqueue(byte[] payload) {
      int s = seq.incrementAndGet();
      frames.add(new AudioFrame(s, payload, System.currentTimeMillis()));
      log.info("[AudioQueue-ENQ] seq={} payloadBytes={} queueSize={}", s, payload.length, frames.size());
    }

    public long currentSeq() { return seq.get(); }
    public int size() { return frames.size(); }

    /**
     * 取出所有 seq > lastSeq 的帧，按顺序返回并从队列删除。
     */
    public List<byte[]> drainSince(long lastSeq) {
      List<byte[]> result = new ArrayList<>();
      List<AudioFrame> toRemove = new ArrayList<>();
      for (AudioFrame f : frames) {
        if (f.seq > lastSeq) {
          result.add(f.payload);
          toRemove.add(f);
        }
      }
      frames.removeAll(toRemove);
      if (!result.isEmpty()) {
        int totalBytes = result.stream().mapToInt(b -> b.length).sum();
        log.info("[AudioQueue-DRAIN] lastSeq={} drained={} totalBytes={} remaining={}",
            lastSeq, result.size(), totalBytes, frames.size());
      }
      return result;
    }

    private record AudioFrame(int seq, byte[] payload, long ts) {}
  }
}
