package com.simultaneousinterpretation.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 HTTP 聊天调用封装（供 TranslateService 使用）。
 * 直接使用 OkHttp 调用 DashScope OpenAI 兼容接口，绕过 langchain4j 的响应解析。
 */
@Component
public class LlmIntegration {

    private static final Logger log = LoggerFactory.getLogger(LlmIntegration.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Qwen-MT 系列模型名称前缀 */
    private static final String MT_MODEL_PREFIX = "qwen-mt";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LlmIntegration(ObjectMapper objectMapper) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * 通用聊天调用（用于非 Qwen-MT 模型，如通用对话模型）。
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @param apiKey       API Key
     * @param baseUrl      DashScope base URL
     * @param modelName    模型名称
     * @return 模型回复文本
     */
    public String chat(String systemPrompt, String userMessage, String apiKey,
                       String baseUrl, String modelName) throws IOException {
        String endpoint = buildEndpoint(baseUrl, "chat/completions");
        log.info("[LLM] POST {} model={}", endpoint, modelName);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.putPOJO("messages", List.of(
                objectMapper.createObjectNode()
                        .put("role", "system")
                        .put("content", systemPrompt),
                objectMapper.createObjectNode()
                        .put("role", "user")
                        .put("content", userMessage)
        ));

        return doCall(endpoint, apiKey, root);
    }

    /**
     * Qwen-MT 专用翻译调用。
     * <p>
     * Qwen-MT 不支持 system message，语种通过 translation_options 参数指定，
     * 翻译指令（意译压缩）作为 user message 的前缀引导。
     *
     * @param userMessage  用户消息（包含意译压缩指令 + 待译文本）
     * @param apiKey       API Key
     * @param baseUrl      DashScope base URL
     * @param modelName    固定为 qwen-mt-flash / qwen-mt-plus
     * @param sourceLang   源语言英文名（如 "English", "Chinese"）
     * @param targetLang   目标语言英文名（如 "Chinese", "English"）
     * @return 译文文本
     */
    public String chatForTranslation(String userMessage, String apiKey,
                                      String baseUrl, String modelName,
                                      String sourceLang, String targetLang) throws IOException {
        String endpoint = buildEndpoint(baseUrl, "chat/completions");
        log.info("[LLM-MT] POST {} model={} source={} target={}", endpoint, modelName, sourceLang, targetLang);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        // Qwen-MT 只接受一个 user 消息，content 为待译文本（指令已在 userMessage 中）
        root.putPOJO("messages", List.of(
                objectMapper.createObjectNode()
                        .put("role", "user")
                        .put("content", userMessage)
        ));
        // translation_options：Qwen-MT 专用参数，直接放 body（非 extra_body）
        root.putPOJO("translation_options", objectMapper.createObjectNode()
                .put("source_lang", sourceLang)
                .put("target_lang", targetLang));

        return doCall(endpoint, apiKey, root);
    }

    private String buildEndpoint(String baseUrl, String path) {
        if (baseUrl.endsWith("/")) {
            return baseUrl + path;
        }
        return baseUrl + "/" + path;
    }

    private String doCall(String endpoint, String apiKey, ObjectNode body) throws IOException {
        String requestBody = objectMapper.writeValueAsString(body);
        log.info("[LLM-REQ] POST {} model={} bodyLen={}", endpoint, body.get("model").asText(), requestBody.length());

        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            int httpCode = response.code();
            String contentType = response.header("Content-Type", "");

            log.info("[LLM-RSP] HTTP {} contentType={} bodyLen={} body={}",
                    httpCode, contentType, responseBody.length(),
                    responseBody.length() <= 500 ? responseBody : responseBody.substring(0, 500) + "...[truncated]");

            if (!response.isSuccessful()) {
                log.error("[LLM] HTTP {} body={}", httpCode, truncate(responseBody, 300));
                throw new IOException("LLM call failed with HTTP " + httpCode + ": " + truncate(responseBody, 300));
            }

            try {
                return parseResponse(responseBody);
            } catch (IOException e) {
                log.error("[LLM-PARSE-FAIL] model={} error={} rawBody={}",
                        body.get("model").asText(), e.getMessage(),
                        truncate(responseBody, 1000));
                throw e;
            }
        }
    }

    private String parseResponse(String body) throws IOException {
        JsonNode root = objectMapper.readTree(body);

        // 检查 error 字段
        if (root.has("error")) {
            JsonNode err = root.get("error");
            String message = err.has("message") ? err.get("message").asText() : err.toString();
            log.error("[LLM] API error: {}", message);
            throw new IOException("LLM API error: " + message);
        }

        // 提取 choices[0].message.content
        if (!root.has("choices") || root.get("choices").isEmpty()) {
            log.error("[LLM] No choices in response: {}", truncate(body, 300));
            throw new IOException("No choices in LLM response: " + truncate(body, 300));
        }

        JsonNode content = root.path("choices").get(0).path("message").path("content");
        if (content.isMissingNode()) {
            log.error("[LLM] Missing content field, full response: {}", truncate(body, 500));
            throw new IOException("Missing 'content' field in LLM response");
        }

        String text = content.asText();
        log.info("[LLM] raw_response length={}", text.length());
        return text;
    }

    /**
     * 判断是否为 Qwen-MT 翻译模型
     */
    public boolean isMTModel(String modelName) {
        return modelName != null && modelName.startsWith(MT_MODEL_PREFIX);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
