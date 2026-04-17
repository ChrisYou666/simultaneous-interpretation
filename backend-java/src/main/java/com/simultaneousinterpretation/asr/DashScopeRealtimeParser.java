package com.simultaneousinterpretation.asr;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DashScope 实时 WebSocket 的 result-generated 解析。
 *
 * <p>Fun-ASR：{@code payload.output.sentence}；Gummy：{@code payload.output.transcription}（识别）/
 * {@code payload.output.translations[]}（翻译，本服务仅用 transcription）。
 */
public final class DashScopeRealtimeParser {

  private DashScopeRealtimeParser() {}

  /**
   * @return null 表示跳过（空文本、心跳包等）
   */
  public static AsrClientEvent parseResultGenerated(JsonNode root, int floor) {
    JsonNode output = root.path("payload").path("output");

    /*
     * Gummy：官方约定 output.transcription = 识别、translations[] = 翻译。
     * 若先读 sentence 再 fallback，部分 Gummy 响应会在 sentence 里带中文（翻译/展示）而
     * transcription 仍为英文识别，导致整条流水线把「译文」当初稿并标成 zh。
     * Fun-ASR：通常仅有 output.sentence，无 transcription 或 transcription 无 text。
     */
    JsonNode transcription = output.path("transcription");
    JsonNode sentence = output.path("sentence");
    String transText =
        transcription.isMissingNode() || transcription.isNull()
            ? ""
            : transcription.path("text").asText("").trim();
    String sentText =
        sentence.isMissingNode() || sentence.isNull()
            ? ""
            : sentence.path("text").asText("").trim();

    JsonNode chosen;
    if (!transText.isEmpty()) {
      chosen = transcription;
    } else if (!sentText.isEmpty()) {
      chosen = sentence;
    } else {
      return null;
    }

    if (chosen.path("heartbeat").asBoolean(false)) {
      return null;
    }
    String text = chosen.path("text").asText("").trim();
    if (text.isEmpty()) {
      return null;
    }
    boolean sentenceEnd = chosen.path("sentence_end").asBoolean(false);
    boolean partial = !sentenceEnd;
    String lang = chosen.path("language").asText("");
    if (lang.isEmpty()) {
      lang = chosen.path("lang").asText("");
    }
    double conf = chosen.path("confidence").asDouble(0);
    // 方案B：提取 Gummy 毫秒级时间戳，用于精确匹配 PCM
    long beginTimeMs = chosen.path("begin_time").asLong(0);
    long endTimeMs   = chosen.path("end_time").asLong(0);
    return new AsrClientEvent("transcript", partial, text, lang, conf, floor, beginTimeMs, endTimeMs);
  }

  public static String parseTaskFailedMessage(JsonNode root) {
    return root.path("header").path("error_message").asText("DashScope 任务失败");
  }
}
