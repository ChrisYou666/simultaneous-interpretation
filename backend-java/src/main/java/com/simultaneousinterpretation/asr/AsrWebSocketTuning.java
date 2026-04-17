package com.simultaneousinterpretation.asr;

/**
 * WebSocket 会话级 ASR/TTS 调参：握手查询串与 {@code setAsrTuning} 消息共用这些 attribute 键。
 */
public final class AsrWebSocketTuning {

  private AsrWebSocketTuning() {}

  /** 查询参数名：与前端 buildAsrWebSocketUrl 追加的字段一致 */
  public static final String Q_SEG_MAX_CHARS = "segMaxChars";
  public static final String Q_SEG_SOFT_BREAK = "segSoftBreakChars";
  public static final String Q_SEG_FLUSH_MS = "segFlushTimeoutMs";
  public static final String Q_SEMANTIC_PUNCT = "semanticPunctuation";
  public static final String Q_TTS_CHUNK = "ttsChunkBytes";
  public static final String Q_SEG_TICK_INITIAL_MS = "segTickInitialMs";
  public static final String Q_SEG_TICK_PERIOD_MS = "segTickPeriodMs";

  public static final String ATTR_SEG_MAX_CHARS = "asr.tuning.segMaxChars";
  public static final String ATTR_SEG_SOFT_BREAK = "asr.tuning.segSoftBreakChars";
  public static final String ATTR_SEG_FLUSH_MS = "asr.tuning.segFlushTimeoutMs";
  public static final String ATTR_SEMANTIC_PUNCT = "asr.tuning.semanticPunctuation";
  public static final String ATTR_TTS_CHUNK = "asr.tuning.ttsChunkBytes";
  public static final String ATTR_SEG_TICK_INITIAL_MS = "asr.tuning.segTickInitialMs";
  public static final String ATTR_SEG_TICK_PERIOD_MS = "asr.tuning.segTickPeriodMs";
}
