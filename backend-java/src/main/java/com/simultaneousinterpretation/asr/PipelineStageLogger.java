package com.simultaneousinterpretation.asr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

/**
 * 同传流水线细粒度日志与阶段耗时追踪。
 *
 * <p>统一前缀 {@code [PIPELINE]}，便于日志检索与监控。
 */
public final class PipelineStageLogger {

  private static final Logger LOG = LoggerFactory.getLogger(PipelineStageLogger.class);
  public static final String ATTR = "asr.pipelineLogger";

  private final int sessionSegIdxBase;
  private final ConcurrentHashMap<Integer, StageRecorder> bySeg = new ConcurrentHashMap<>();
  private final AtomicLong totalSegmentsEmitted = new AtomicLong(0);
  private final AtomicLong totalTranslations = new AtomicLong(0);
  private final AtomicLong totalTtsInvocations = new AtomicLong(0);

  /** 全局统计（所有会话共享） */
  private static final AtomicLong GLOBAL_SESSIONS = new AtomicLong(0);
  private static final AtomicLong GLOBAL_SEGMENTS = new AtomicLong(0);

  public PipelineStageLogger() {
    this.sessionSegIdxBase = (int) (System.currentTimeMillis() % 10000) * 100;
    GLOBAL_SESSIONS.incrementAndGet();
  }

  public static PipelineStageLogger get(WebSocketSession session) {
    Object o = session.getAttributes().get(ATTR);
    if (o instanceof PipelineStageLogger l) {
      return l;
    }
    synchronized (session) {
      return (PipelineStageLogger)
          session.getAttributes().computeIfAbsent(ATTR, k -> new PipelineStageLogger());
    }
  }

  // ─── Stage timestamps (wall ms) ─────────────────────────────

  public void stage(WebSocketSession session, int localSegIdx, Stage stage) {
    stage(session, localSegIdx, stage, 0);
  }

  public void stage(WebSocketSession session, int localSegIdx, Stage stage, int extraBytes) {
    long wallMs = System.currentTimeMillis();
    StageRecorder rec = bySeg.computeIfAbsent(localSegIdx, k -> new StageRecorder());
    rec.setTime(stage, wallMs);

    String prefix = String.format("[PIPELINE] sid=%s seg=%d", session.getId(), localSegIdx);
    switch (stage) {
      case ASR_FINAL -> {
        totalSegmentsEmitted.incrementAndGet();
        GLOBAL_SEGMENTS.incrementAndGet();
        long elapsed = rec.getElapsedFrom(Stage.ASR_UPSTREAM_SEND);
        LOG.info("{} {} segIdx={} wall={} asrUpstreamElapsed={}ms",
            prefix, stage, localSegIdx, wallMs, elapsed >= 0 ? elapsed : "n/a");
      }
      case SEG_EMIT -> {
        long sinceFinal = rec.getElapsedFrom(Stage.ASR_FINAL);
        LOG.info("{} {} segIdx={} wall={} sinceFinal={}ms",
            prefix, stage, localSegIdx, wallMs, sinceFinal >= 0 ? sinceFinal : "n/a");
      }
      case TRANSLATE_DONE -> {
        long sinceEmit = rec.getElapsedFrom(Stage.SEG_EMIT);
        long llmMs = rec.getElapsedFrom(Stage.TRANSLATE_REQ);
        LOG.info("{} {} langs={} segIdx={} wall={} sinceSegEmit={}ms llm={}ms",
            prefix, stage, rec.targetLangs, localSegIdx, wallMs,
            sinceEmit >= 0 ? sinceEmit : "n/a", llmMs >= 0 ? llmMs : "n/a");
      }
      case TTS_FIRST_CHUNK -> {
        long sinceTranslate = rec.getElapsedFrom(Stage.TRANSLATE_DONE);
        LOG.info("{} {} lang={} segIdx={} wall={} sinceTranslateDone={}ms",
            prefix, stage, rec.ttsLang, localSegIdx, wallMs,
            sinceTranslate >= 0 ? sinceTranslate : "n/a");
      }
      case TTS_DONE -> {
        long totalMs = rec.getElapsedFrom(Stage.ASR_FINAL);
        LOG.info("{} {} lang={} segIdx={} wall={} totalSinceFinal={}ms",
            prefix, stage, rec.ttsLang, localSegIdx, wallMs,
            totalMs >= 0 ? totalMs : "n/a");
      }
      case AUDIO_END -> {
        long totalMs = rec.getElapsedFrom(Stage.ASR_FINAL);
        long ttsMs = rec.getElapsedFrom(Stage.TTS_REQ);
        LOG.info("{} {} lang={} segIdx={} wall={} totalSinceFinal={}ms ttsElapsed={}ms",
            prefix, stage, rec.ttsLang, localSegIdx, wallMs,
            totalMs >= 0 ? totalMs : "n/a", ttsMs >= 0 ? ttsMs : "n/a");
      }
      default -> LOG.debug("{} {} wall={}", prefix, stage, wallMs);
    }
  }

  public void setTargetLangs(int localSegIdx, String langs) {
    bySeg.computeIfAbsent(localSegIdx, k -> new StageRecorder()).targetLangs = langs;
  }

  public void setTtsLang(int localSegIdx, String lang) {
    bySeg.computeIfAbsent(localSegIdx, k -> new StageRecorder()).ttsLang = lang;
  }

  public void setAsrUpstreamSendTime(int localSegIdx, long wallMs) {
    bySeg.computeIfAbsent(localSegIdx, k -> new StageRecorder())
        .setTime(Stage.ASR_UPSTREAM_SEND, wallMs);
  }

  /**
   * 获取本段各阶段耗时（毫秒），用于发送给前端展示。
   * 返回相对于 seg_emit 的差值，便于理解各阶段耗时占比。
   */
  public Map<String, Long> getStageDurations(int localSegIdx) {
    StageRecorder rec = bySeg.get(localSegIdx);
    if (rec == null || rec.getTime(Stage.SEG_EMIT) <= 0) {
      return Map.of();
    }
    long t0 = rec.getTime(Stage.SEG_EMIT);
    return rec.getDurationsFrom(t0);
  }

  /**
   * 获取完整阶段时间戳（用于调试）。
   */
  public Map<Stage, Long> getStageTimestamps(int localSegIdx) {
    StageRecorder rec = bySeg.get(localSegIdx);
    if (rec == null) {
      return Map.of();
    }
    return rec.getAllTimestamps();
  }

  // ─── 全局统计 ─────────────────────────────

  public Map<String, Object> globalStats() {
    return Map.of(
        "globalSessions", GLOBAL_SESSIONS.get(),
        "globalSegmentsEmitted", GLOBAL_SEGMENTS.get(),
        "sessionSegmentsEmitted", totalSegmentsEmitted.get(),
        "sessionTranslations", totalTranslations.get(),
        "sessionTtsInvocations", totalTtsInvocations.get()
    );
  }

  // ─── Enum ─────────────────────────────

  public enum Stage {
    ASR_UPSTREAM_SEND,  // PCM 发送到达上游
    ASR_PARTIAL,        // 上游 partial
    ASR_FINAL,          // 上游 final
    SEG_EMIT,           // 切段引擎切出
    TRANSLATE_REQ,      // 发起翻译
    TRANSLATE_DONE,     // 翻译完成
    TTS_REQ,            // 发起 TTS
    TTS_FIRST_CHUNK,    // TTS 首块
    TTS_DONE,           // TTS 完成
    WS_AUDIO_SENT,      // WS 发送音频
    AUDIO_END           // 播放完毕
  }

  // ─── Internal ─────────────────────────────

  private static final class StageRecorder {
    private static final int N = Stage.values().length;
    private final long[] timestamps = new long[N];
    private volatile String targetLangs = "";
    private volatile String ttsLang = "";

    void setTime(Stage s, long t) {
      timestamps[s.ordinal()] = t;
    }

    long getTime(Stage s) {
      return timestamps[s.ordinal()];
    }

    long getElapsedFrom(Stage from) {
      long fromTs = timestamps[from.ordinal()];
      long toTs = timestamps[Stage.ASR_FINAL.ordinal()];
      if (fromTs <= 0 || toTs <= 0) return -1;
      return toTs - fromTs;
    }

    Map<String, Long> getDurationsFrom(long t0) {
      java.util.HashMap<String, Long> m = new java.util.HashMap<>();
      for (Stage s : Stage.values()) {
        long ts = timestamps[s.ordinal()];
        if (ts > 0) {
          m.put(s.name(), ts - t0);
        }
      }
      return m;
    }

    Map<Stage, Long> getAllTimestamps() {
      java.util.HashMap<Stage, Long> m = new java.util.HashMap<>();
      for (Stage s : Stage.values()) {
        long ts = timestamps[s.ordinal()];
        if (ts > 0) {
          m.put(s, ts);
        }
      }
      return m;
    }
  }
}
