package com.simultaneousinterpretation.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;

/**
 * 音频分发器（HTTP 轮询模式）：
 *
 * <p>废弃 WebSocket 二进制传输，改用 AudioController 队列。
 * 每个语言的音频帧入队到对应房间+语言的队列，听众端 HTTP 轮询拉取。
 *
 * <p>二进制帧格式（与原 WebSocket 二进制一致）：
 * [4-byte segIdx][1-byte 0x01][1-byte langLen][lang bytes][...WAV data...]
 * [4-byte segIdx][1-byte 0x03][1-byte langLen][lang bytes][1-byte reasonLen][reason bytes]
 */
@Component
public class RoomAudioDispatcher implements RoomSegmentRegistry.SegmentListener {

  private static final Logger log = LoggerFactory.getLogger(RoomAudioDispatcher.class);

  private final RoomSegmentRegistry registry;
  private RoomAudioController audioController;

  public RoomAudioDispatcher(RoomSegmentRegistry registry) {
    this.registry = registry;
  }

  /** 由 Spring 在所有 bean 初始化完成后注入 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setAudioController(RoomAudioController audioController) {
    this.audioController = audioController;
    registry.registerSegmentListener(this);
    log.info("[Dispatcher-Init] ★HTTP模式★ RoomAudioController 已注入，音频将入队到轮询队列");
  }

  // ─── SegmentListener 实现 ────────────────────────────────────────────────

  @Override
  public void onTtsChunk(String roomId, int segIdx, String tgtLang, byte[] audioData) {
    long now = System.currentTimeMillis();
    byte[] payload = buildWavPayload(segIdx, tgtLang, audioData);
    log.info("[DISPATCH-TTS-QUEUE] ★ENQ★ roomId={} segIdx={} tgtLang={} wavSize={} payloadSize={} ts={}",
        roomId, segIdx, tgtLang, audioData.length, payload.length, now);
    enqueueAudio(roomId, tgtLang, payload);
  }

  @Override
  public void onAudioEnd(String roomId, int segIdx, String tgtLang, String reason) {
    long now = System.currentTimeMillis();
    byte[] payload = buildEndPayload(segIdx, tgtLang, reason);
    log.info("[DISPATCH-AUDIO-END-QUEUE] ★ENQ-END★ roomId={} segIdx={} tgtLang={} reason={} payloadSize={} ts={}",
        roomId, segIdx, tgtLang, reason, payload.length, now);
    enqueueAudio(roomId, tgtLang, payload);
  }

  @Override
  public void onTtsSkip(String roomId, int segIdx, String tgtLang, String reason) {
    long now = System.currentTimeMillis();
    byte[] payload = buildEndPayload(segIdx, tgtLang, reason);
    log.info("[DISPATCH-TTS-SKIP-QUEUE] ★ENQ-SKIP★ roomId={} segIdx={} tgtLang={} reason={} payloadSize={} ts={}",
        roomId, segIdx, tgtLang, reason, payload.length, now);
    enqueueAudio(roomId, tgtLang, payload);
  }

  // ─── 音频入队 ───────────────────────────────────────────────────────────

  private void enqueueAudio(String roomId, String lang, byte[] payload) {
    if (audioController == null) {
      log.error("[DISPATCH-ENQ-FAIL] ★NULL★ audioController 为 null！roomId={} lang={} payloadSize={}",
          roomId, lang, payload.length);
      return;
    }
    audioController.enqueueAudio(roomId, lang, payload);
    log.info("[DISPATCH-ENQ-OK] ★QUEUED★ roomId={} lang={} payloadSize={} ts={}",
        roomId, lang, payload.length, System.currentTimeMillis());
  }

  // ─── 二进制消息头部 ────────────────────────────────────────────────────

  private static final byte HEADER_TYPE_WAV = 0x01;
  private static final byte HEADER_TYPE_END = 0x03;

  private byte[] buildWavPayload(int segIdx, String lang, byte[] wavData) {
    byte[] langBytes = lang.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] header = new byte[4 + 1 + 1 + langBytes.length];
    ByteBuffer bb = ByteBuffer.wrap(header);
    bb.putInt(segIdx);
    bb.put(HEADER_TYPE_WAV);
    bb.put((byte) langBytes.length);
    bb.put(langBytes);
    byte[] payload = new byte[header.length + wavData.length];
    System.arraycopy(header, 0, payload, 0, header.length);
    System.arraycopy(wavData, 0, payload, header.length, wavData.length);
    return payload;
  }

  private byte[] buildEndPayload(int segIdx, String lang, String reason) {
    byte[] langBytes = lang.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] reasonBytes = (reason != null ? reason : "").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ByteBuffer bb = ByteBuffer.allocate(4 + 1 + 1 + langBytes.length + 1 + reasonBytes.length);
    bb.putInt(segIdx);
    bb.put(HEADER_TYPE_END);
    bb.put((byte) langBytes.length);
    bb.put(langBytes);
    bb.put((byte) reasonBytes.length);
    bb.put(reasonBytes);
    return bb.array();
  }
}
