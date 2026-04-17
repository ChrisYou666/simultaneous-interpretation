package com.simultaneousinterpretation.integration;

import com.simultaneousinterpretation.common.BizException;
import com.simultaneousinterpretation.domain.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LLM 大模型集成层
 * <p>
 * 负责与外部 AI 大模型服务（如 OpenAI GPT、DeepSeek 等）通信
 * <p>
 * 使用单例缓存机制优化模型配置加载
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@Component
public class LlmIntegration {

    /**
     * OpenAI API URL
     */
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    /**
     * DeepSeek API URL
     */
    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions";

    /**
     * 默认模型
     */
    private static final String DEFAULT_MODEL = "gpt-4o-mini";

    /**
     * 最大 Token 数
     */
    private static final int MAX_TOKENS = 2000;

    /**
     * 默认温度
     */
    private static final double DEFAULT_TEMPERATURE = 0.7;

    /**
     * 请求超时时间（毫秒）
     */
    private static final int TIMEOUT_MS = 60000;

    /**
     * 缓存的模型名称
     */
    private volatile String cachedModel;

    /**
     * 模型版本号（用于缓存失效）
     */
    private final AtomicInteger modelVersion = new AtomicInteger(0);

    /**
     * API Key 缓存（Key: apiKey hash, Value: validated）
     */
    private final ConcurrentHashMap<String, Boolean> apiKeyCache = new ConcurrentHashMap<>();

    @Value("${ai.model:#{null}}")
    private String configuredModel;

    @Value("${ai.base-url:#{null}}")
    private String configuredBaseUrl;

    /**
     * 调用 LLM 进行对话
     *
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @param apiKey      API Key
     * @return LLM 回复内容
     */
    public String chat(String systemPrompt, String userMessage, String apiKey) {
        log.info("LLM调用开始，系统提示词长度={}, 用户消息长度={}", 
                 systemPrompt != null ? systemPrompt.length() : 0,
                 userMessage != null ? userMessage.length() : 0);
        long startTime = System.currentTimeMillis();

        try {
            String model = getEffectiveModel();
            String baseUrl = getEffectiveBaseUrl();

            String requestBody = buildRequestBody(model, systemPrompt, userMessage);
            String response = sendRequest(baseUrl, requestBody, apiKey);

            long elapsedTime = System.currentTimeMillis() - startTime;
            log.info("LLM调用成功，模型={}, 耗时={}ms, 响应长度={}", 
                     model, elapsedTime, response != null ? response.length() : 0);

            return parseResponse(response);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("LLM调用失败，耗时={}ms, 错误类型={}, 错误信息={}", 
                     elapsedTime, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new BizException(ErrorCode.TRANSLATE_FAILED, "AI服务调用失败: " + e.getMessage());
        }
    }

    /**
     * 简单对话（无系统提示词）
     *
     * @param userMessage 用户消息
     * @param apiKey     API Key
     * @return LLM 回复内容
     */
    public String chat(String userMessage, String apiKey) {
        return chat(null, userMessage, apiKey);
    }

    /**
     * 获取有效的模型名称
     *
     * @return 模型名称
     */
    private String getEffectiveModel() {
        if (cachedModel == null) {
            synchronized (this) {
                if (cachedModel == null) {
                    cachedModel = (configuredModel != null && !configuredModel.isBlank())
                            ? configuredModel
                            : DEFAULT_MODEL;
                    log.info("LLM模型已缓存，当前模型={}", cachedModel);
                }
            }
        }
        return cachedModel;
    }

    /**
     * 获取有效的 Base URL
     *
     * @return Base URL
     */
    private String getEffectiveBaseUrl() {
        if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
            return configuredBaseUrl;
        }
        return OPENAI_API_URL;
    }

    /**
     * 构建请求体
     *
     * @param model        模型名称
     * @param systemPrompt 系统提示词
     * @param userMessage  用户消息
     * @return JSON 请求体
     */
    private String buildRequestBody(String model, String systemPrompt, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(model).append("\",");
        sb.append("\"messages\":[");

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("{\"role\":\"system\",\"content\":\"")
              .append(escapeJson(systemPrompt))
              .append("\"},");
        }

        sb.append("{\"role\":\"user\",\"content\":\"")
          .append(escapeJson(userMessage))
          .append("\"}],");

        sb.append("\"max_tokens\":").append(MAX_TOKENS).append(",");
        sb.append("\"temperature\":").append(DEFAULT_TEMPERATURE);
        sb.append("}");

        return sb.toString();
    }

    /**
     * 发送 HTTP 请求
     *
     * @param url         请求 URL
     * @param requestBody 请求体
     * @param apiKey      API Key
     * @return 响应内容
     */
    private String sendRequest(String url, String requestBody, String apiKey) throws Exception {
        log.debug("发送LLM请求，URL={}, 请求体长度={}", url, requestBody.length());

        HttpURLConnection conn = null;
        try {
            URL requestUrl = new URL(url);
            conn = (HttpURLConnection) requestUrl.openConnection();

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            // 写入请求体
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String errorResponse = readResponse(conn);
                log.error("LLM API调用失败，响应码={}, 错误信息={}", responseCode, errorResponse);
                throw new BizException(ErrorCode.TRANSLATE_FAILED, 
                        String.format("AI服务返回错误码: %d", responseCode));
            }

            return readResponse(conn);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 读取响应内容
     *
     * @param conn HTTP 连接
     * @return 响应内容
     */
    private String readResponse(HttpURLConnection conn) throws Exception {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                        conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                        StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    /**
     * 解析响应内容
     *
     * @param response JSON 响应
     * @return 解析后的文本
     */
    private String parseResponse(String response) {
        if (response == null || response.isBlank()) {
            throw new BizException(ErrorCode.TRANSLATE_FAILED, "AI服务返回空响应");
        }

        try {
            // 简单的 JSON 解析（避免引入额外依赖）
            // {"choices":[{"message":{"content":"xxx"}}]}
            int contentIndex = response.indexOf("\"content\":\"");
            if (contentIndex == -1) {
                throw new BizException(ErrorCode.TRANSLATE_FAILED, "响应格式异常");
            }

            int startIndex = contentIndex + 10;
            int endIndex = response.indexOf("\"", startIndex);
            if (endIndex == -1) {
                throw new BizException(ErrorCode.TRANSLATE_FAILED, "响应格式异常");
            }

            String content = response.substring(startIndex, endIndex);
            // 处理转义字符
            content = content.replace("\\\"", "\"")
                           .replace("\\n", "\n")
                           .replace("\\\\", "\\");

            return content;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析LLM响应失败，响应内容={}", response, e);
            throw new BizException(ErrorCode.TRANSLATE_FAILED, "解析AI响应失败");
        }
    }

    /**
     * JSON 字符串转义
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * 验证 API Key 格式
     *
     * @param apiKey API Key
     * @return true 表示格式有效
     */
    public boolean validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        // OpenAI API Key 格式: sk-xxx
        // DeepSeek API Key 格式: sk-xxx
        return apiKey.startsWith("sk-") && apiKey.length() > 10;
    }

    /**
     * 清除模型缓存
     */
    public void clearModelCache() {
        log.info("清除LLM模型缓存");
        this.cachedModel = null;
        this.modelVersion.incrementAndGet();
    }

    /**
     * 获取当前模型版本
     *
     * @return 模型版本号
     */
    public int getModelVersion() {
        return modelVersion.get();
    }
}
