package com.simultaneousinterpretation.asr;

/**
 * 发给浏览器 ASR 统一事件（Deepgram / DashScope 共用）。
 * beginTimeMs / endTimeMs 由 Gummy 返回的毫秒级时间戳填充；
 * 其他 ASR 厂商（Deepgram 等）若不支持则传 0。
 */
public record AsrClientEvent(
    String event,
    boolean partial,
    String text,
    String language,
    double confidence,
    int floor,
    /** 句子在音频流中的起始时间（毫秒），Gummy 返回 begin_time */
    long beginTimeMs,
    /** 句子在音频流中的结束时间（毫秒），Gummy 返回 end_time */
    long endTimeMs) {

  public static AsrClientEvent error(String message) {
    return new AsrClientEvent("error", false, message, "", 0, 0, 0, 0);
  }

  public boolean isError() {
    return "error".equals(event);
  }

  /** 无时间戳的简便构造（兼容旧调用方） */
  public static AsrClientEvent of(String event, boolean partial, String text,
      String language, double confidence, int floor) {
    return new AsrClientEvent(event, partial, text, language, confidence, floor, 0, 0);
  }
}
