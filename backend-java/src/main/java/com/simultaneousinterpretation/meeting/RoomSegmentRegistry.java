package com.simultaneousinterpretation.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 房间级段注册表：主持人和所有听众共享同一个 segIdx 编号体系。
 *
 * <p>核心职责：
 * <ol>
 *   <li>为每个切出的段分配全局自增 segIdx（主持人和听众一致）</li>
 *   <li>存储该段的各语言 TTS 音频块</li>
 * </ol>
 *
 * <p>生命周期：随房间创建，随房间关闭销毁。
 */
@Component
public class RoomSegmentRegistry {

  private static final Logger log = LoggerFactory.getLogger(RoomSegmentRegistry.class);

  /** TTS 二进制下发监听器（如 RoomAudioDispatcher） */
  public interface SegmentListener {
    void onTtsChunk(String roomId, int segIdx, String tgtLang, byte[] audioData);
    void onAudioEnd(String roomId, int segIdx, String tgtLang, String reason);
    void onTtsSkip(String roomId, int segIdx, String tgtLang, String reason);
  }

  /** roomId → 注册表实例 */
  private final Map<String, RoomRegistry> registries = new ConcurrentHashMap<>();

  /** 之后创建的每个 RoomRegistry 都会自动附加这些监听器（如 RoomAudioDispatcher） */
  private final CopyOnWriteArrayList<SegmentListener> globalSegmentListeners = new CopyOnWriteArrayList<>();

  /** 房间关闭时清理 */
  public void removeRoom(String roomId) {
    registries.remove(roomId);
    log.info("[Registry] 移除房间 roomId={}", roomId);
  }

  /**
   * 注册全局段监听器：追加到当前已有房间，并在之后 {@link #getOrCreate} 的新房间上自动注册。
   */
  public void registerSegmentListener(SegmentListener l) {
    if (globalSegmentListeners.contains(l)) {
      return;
    }
    globalSegmentListeners.add(l);
    for (RoomRegistry r : registries.values()) {
      r.addListener(l);
    }
  }

  /** 获取或创建房间注册表 */
  public RoomRegistry getOrCreate(String roomId) {
    return registries.computeIfAbsent(roomId, k -> {
      RoomRegistry r = new RoomRegistry(k);
      for (SegmentListener listener : globalSegmentListeners) {
        r.addListener(listener);
      }
      return r;
    });
  }

  /** 获取所有注册表（用于 dispatcher 初始化时注册监听器） */
  public Collection<RoomRegistry> getAllRegistries() {
    return registries.values();
  }

  /** 获取房间数量（调试用） */
  public int getRoomCount() {
    return registries.size();
  }

  // ─── 房间级注册表 ────────────────────────────────────────────────────────

  public static class RoomRegistry {
    private static final Logger log = LoggerFactory.getLogger(RoomRegistry.class);

    private final String roomId;
    /** 全局自增 segment 序号 */
    private final AtomicInteger nextSegIndex = new AtomicInteger(0);
    /** segment index → 段记录 */
    private final Map<Integer, SegmentRecord> segments = new ConcurrentHashMap<>();

    /** 分发监听器（RoomAudioDispatcher 注册） */
    private final List<SegmentListener> listeners = new CopyOnWriteArrayList<>();

    RoomRegistry(String roomId) {
      this.roomId = roomId;
      log.info("[Registry-Create] 房间注册表创建 roomId={}", roomId);
    }

    public String getRoomId() { return roomId; }
    public int getSegmentCount() { return segments.size(); }

    // ── 注册新段（ASR 切段时调用） ────────────────────────────────────────

    /**
     * 注册一个新段，返回分配好的 SegmentRecord。
     * 由 SegmentationEngine 在 emit 前调用。
     */
    public synchronized SegmentRecord registerSegment(String text, String detectedLang,
                                                      double confidence, int floor,
                                                      long serverTs, long segmentBatchTs) {
      int segIdx = nextSegIndex.getAndIncrement();
      SegmentRecord seg = new SegmentRecord(segIdx, text, detectedLang, confidence, floor, serverTs, segmentBatchTs);
      segments.put(segIdx, seg);
      log.info("[Registry-REGISTER] roomId={} segIdx={} lang={} floor={} text=\"{}\"",
          roomId, segIdx, detectedLang, floor, text.length() > 50 ? text.substring(0, 50) + "..." : text);
      return seg;
    }

    // ── TTS chunk ─────────────────────────────────────────────────────────

    public void onTtsChunk(int segIdx, String tgtLang, byte[] audioData) {
      long now = System.currentTimeMillis();
      SegmentRecord seg = segments.get(segIdx);
      if (seg == null) {
        log.warn("[Registry-TtsChunk-NULL] ★★★ segIdx={} 不存在 roomId={} timestamp={}", segIdx, roomId, now);
        return;
      }
      int chunkCountBefore = seg.getAudioChunks().getOrDefault(tgtLang, java.util.Collections.emptyList()).size();
      seg.addAudioChunk(tgtLang, audioData);
      int chunkCountAfter = seg.getAudioChunks().getOrDefault(tgtLang, java.util.Collections.emptyList()).size();
      log.info("[Registry-TtsChunk-STASH] ★★ STASHED roomId={} segIdx={} lang={} chunk#={}→{} size={}B totalAudioChunks={}",
          roomId, segIdx, tgtLang, chunkCountBefore, chunkCountAfter, audioData.length, seg.getAudioChunks().size());
      notifyListeners(l -> l.onTtsChunk(roomId, segIdx, tgtLang, audioData));
    }

    // ── TTS 结束 ──────────────────────────────────────────────────────────

    public void onAudioEnd(int segIdx, String tgtLang, String reason) {
      SegmentRecord seg = segments.get(segIdx);
      if (seg == null) {
        log.warn("[Registry-AudioEnd] segIdx={} 不存在 roomId={}", segIdx, roomId);
        return;
      }
      seg.markAudioEndSent(tgtLang);
      log.info("[Registry-AudioEnd] roomId={} segIdx={} →{} reason={}",
          roomId, segIdx, tgtLang, reason);
      notifyListeners(l -> l.onAudioEnd(roomId, segIdx, tgtLang, reason));
    }

    // ── 同源语言 TTS 跳过（不合成 TTS，直接标记 skip） ─────────────────────

    public void onSameLangSkip(int segIdx, String srcLang, String reason) {
      SegmentRecord seg = segments.get(segIdx);
      if (seg == null) {
        log.warn("[Registry-SameLangSkip] segIdx={} 不存在 roomId={}", segIdx, roomId);
        return;
      }
      seg.skipTts(srcLang);
      seg.markAudioEndSent(srcLang);
      log.info("[Registry-SameLangSkip] roomId={} segIdx={} srcLang={} reason={}",
          roomId, segIdx, srcLang, reason);
      // 同源听众不通过队列收音频，无需通知 Dispatcher
    }

    /** 通用 TTS 跳过（用于 translate_failed / no_audio / error 等原因） */
    public void onTtsSkip(int segIdx, String tgtLang, String reason) {
      SegmentRecord seg = segments.get(segIdx);
      if (seg == null) {
        log.warn("[Registry-TtsSkip] segIdx={} 不存在 roomId={}", segIdx, roomId);
        return;
      }
      seg.skipTts(tgtLang);
      log.info("[Registry-TtsSkip] roomId={} segIdx={} →{} reason={}",
          roomId, segIdx, tgtLang, reason);
      notifyListeners(l -> l.onTtsSkip(roomId, segIdx, tgtLang, reason));
    }

    // ── 监听器注册 ────────────────────────────────────────────────────────

    public void addListener(SegmentListener l) {
      // 必须幂等：broadcastSegment / sendCatchup 每段都会 ensureListenerRegistered，
      // 若重复 add，同一 dispatcher 会被 notify N 次 → 翻译/TTS 重复下发、听众端同句连播多遍。
      if (listeners.contains(l)) {
        return;
      }
      listeners.add(l);
      log.info("[Registry-Listener] roomId={} added={} total={}", roomId, l.getClass().getSimpleName(), listeners.size());
    }

    public void removeListener(SegmentListener l) {
      listeners.remove(l);
      log.info("[Registry-Listener] roomId={} removed={} remaining={}", roomId, l.getClass().getSimpleName(), listeners.size());
    }

    private void notifyListeners(java.util.function.Consumer<SegmentListener> action) {
      for (SegmentListener l : listeners) {
        try {
          action.accept(l);
        } catch (Exception e) {
          log.debug("[Registry-Notify] error: {}", e.getMessage());
        }
      }
    }
  }

  // ─── Segment 记录 ─────────────────────────────────────────────────────────

  public static class SegmentRecord {
    private final int segIndex;
    private final String text;
    private final String detectedLang;
    private final double confidence;
    private final int floor;
    private final long serverTs;
    private final long segmentBatchTs;

    /** tgtLang → List of audio chunks */
    private final Map<String, List<byte[]>> audioChunks = new ConcurrentHashMap<>();
    /** tgtLang → audio_end 已发送 */
    private final Set<String> audioEndSent = ConcurrentHashMap.newKeySet();
    /** tgtLang → 跳过 TTS（同源语言） */
    private final Set<String> ttsSkipped = ConcurrentHashMap.newKeySet();

    public SegmentRecord(int segIndex, String text, String detectedLang,
                         double confidence, int floor, long serverTs, long segmentBatchTs) {
      this.segIndex = segIndex;
      this.text = text;
      this.detectedLang = detectedLang;
      this.confidence = confidence;
      this.floor = floor;
      this.serverTs = serverTs;
      this.segmentBatchTs = segmentBatchTs;
    }

    public int getSegIndex() { return segIndex; }
    public String getText() { return text; }
    public String getDetectedLang() { return detectedLang; }
    public double getConfidence() { return confidence; }
    public int getFloor() { return floor; }
    public long getServerTs() { return serverTs; }
    public long getSegmentBatchTs() { return segmentBatchTs; }
    public Map<String, List<byte[]>> getAudioChunks() { return Collections.unmodifiableMap(audioChunks); }
    public boolean isTtsSkipped(String lang) { return ttsSkipped.contains(lang); }
    public boolean isAudioEndSent(String lang) { return audioEndSent.contains(lang); }

    public void addAudioChunk(String tgtLang, byte[] chunk) {
      audioChunks.computeIfAbsent(tgtLang, k -> Collections.synchronizedList(new ArrayList<>())).add(chunk);
    }

    public void markAudioEndSent(String tgtLang) {
      audioEndSent.add(tgtLang);
    }

    public void skipTts(String tgtLang) {
      ttsSkipped.add(tgtLang);
      markAudioEndSent(tgtLang);
    }
  }
}
