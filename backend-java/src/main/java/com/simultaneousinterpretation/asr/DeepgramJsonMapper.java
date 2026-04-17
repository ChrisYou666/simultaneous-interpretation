package com.simultaneousinterpretation.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.springframework.stereotype.Component;

/** 将 Deepgram Live 的 JSON 行解析为发给前端的统一事件。 */
@Component
public class DeepgramJsonMapper {

  private final ObjectMapper objectMapper;

  public DeepgramJsonMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public AsrClientEvent parseLine(String line, int floor) throws IOException {
    if (line == null || line.isBlank()) {
      return null;
    }
    JsonNode root = objectMapper.readTree(line);
    String type = root.path("type").asText("");
    if ("Metadata".equals(type) || "SpeechStarted".equals(type) || "UtteranceEnd".equals(type)) {
      return null;
    }
    if (root.has("err_code") || root.has("err_msg")) {
      String msg =
          root.path("err_msg").asText(
              root.path("description").asText(root.path("message").asText("Deepgram error")));
      return AsrClientEvent.error(msg);
    }
    if (root.has("channel")) {
      JsonNode alts = root.path("channel").path("alternatives");
      if (!alts.isArray() || alts.size() == 0) {
        return null;
      }
      JsonNode alt = alts.get(0);
      String text = alt.path("transcript").asText("").trim();
      if (text.isEmpty()) {
        return null;
      }
      double confidence = alt.path("confidence").asDouble(0);
      boolean isFinal = root.path("is_final").asBoolean(false);
      String language = extractLanguage(root);
      return AsrClientEvent.of("transcript", !isFinal, text, language, confidence, floor);
    }
    return null;
  }

  private static String extractLanguage(JsonNode root) {
    String lang = root.path("channel").path("detected_language").asText("");
    if (!lang.isEmpty()) {
      return lang;
    }
    lang = root.path("metadata").path("language").asText("");
    if (!lang.isEmpty()) {
      return lang;
    }
    return root.path("metadata").path("model_info").path("language").asText("");
  }
}
