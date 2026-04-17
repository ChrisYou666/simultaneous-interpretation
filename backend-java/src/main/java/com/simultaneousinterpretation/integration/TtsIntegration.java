package com.simultaneousinterpretation.integration;

import com.simultaneousinterpretation.common.BizException;
import com.simultaneousinterpretation.domain.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * TTS 语音合成集成层
 * <p>
 * 负责与外部 TTS 服务（如 DashScope 等）通信
 * <p>
 * 使用信号量控制并发数量，避免服务过载
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@Component
public class TtsIntegration {

    /**
     * DashScope TTS API URL
     */
    private static final String TTS_API_URL = "https://dashscope自己做.com/api/v1/services/tts/text-to-speech";

    /**
     * 默认超时时间（毫秒）
     */
    private static final int DEFAULT_TIMEOUT_MS = 30000;

    /**
     * 最大并发数
     */
    private static final int MAX_CONCURRENT_REQUESTS = 10;

    /**
     * 默认语速
     */
    private static final double DEFAULT_RATE = 1.0;

    /**
     * 默认音调
     */
    private static final double DEFAULT_PITCH = 1.0;

    /**
     * 默认音量
     */
    private static final double DEFAULT_VOLUME = 1.0;

    /**
     * 默认采样率
     */
    private static final int DEFAULT_SAMPLE_RATE = 16000;

    /**
     * 默认音频格式
     */
    private static final String DEFAULT_FORMAT = "mp3";

    /**
     * 并发控制信号量
     */
    private final Semaphore semaphore = new Semaphore(MAX_CONCURRENT_REQUESTS, true);

    @Value("${tts.api-url:#{null}}")
    private String configuredApiUrl;

    @Value("${tts.api-key:#{null}}")
    private String configuredApiKey;

    @Value("${tts.model:#{null}}")
    private String configuredModel;

    @Value("${tts.voice:#{null}}")
    private String configuredVoice;

    /**
     * 语音合成
     *
     * @param text   文本内容
     * @param apiKey API Key
     * @return 音频数据（Base64 编码）
     */
    public byte[] synthesize(String text, String apiKey) {
        log.info("TTS合成开始，文本长度={}", text != null ? text.length() : 0);
        long startTime = System.currentTimeMillis();

        // 获取信号量
        try {
            boolean acquired = semaphore.tryAcquire(5, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("TTS请求被限流，当前等待信号量超时");
                throw new BizException(ErrorCode.AUDIO_SYNTHESIS_TIMEOUT, "TTS服务繁忙，请稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AUDIO_SERVICE_UNAVAILABLE, "TTS服务被中断");
        }

        try {
            String effectiveApiUrl = getEffectiveApiUrl(apiKey);
            String effectiveApiKey = getEffectiveApiKey(apiKey);
            String effectiveModel = getEffectiveModel();
            String effectiveVoice = getEffectiveVoice();

            byte[] audioData = sendSynthesisRequest(text, effectiveApiUrl, effectiveApiKey, effectiveModel, effectiveVoice);

            long elapsedTime = System.currentTimeMillis() - startTime;
            log.info("TTS合成成功，耗时={}ms, 音频大小={}bytes", elapsedTime, audioData.length);

            return audioData;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("TTS合成失败，耗时={}ms, 错误类型={}, 错误信息={}", 
                     elapsedTime, e.getClass().getSimpleName(), e.getMessage(), e);
            throw new BizException(ErrorCode.TTS_FAILED, "TTS服务调用失败: " + e.getMessage());
        } finally {
            semaphore.release();
        }
    }

    /**
     * 流式语音合成（写入输出流）
     *
     * @param text       文本内容
     * @param apiKey     API Key
     * @param outputStream 输出流
     */
    public void synthesizeToStream(String text, String apiKey, OutputStream outputStream) {
        log.info("TTS流式合成开始，文本长度={}", text != null ? text.length() : 0);
        long startTime = System.currentTimeMillis();

        try {
            byte[] audioData = synthesize(text, apiKey);
            outputStream.write(audioData);
            outputStream.flush();

            long elapsedTime = System.currentTimeMillis() - startTime;
            log.info("TTS流式合成完成，耗时={}ms, 音频大小={}bytes", elapsedTime, audioData.length);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS流式合成失败，错误={}", e.getMessage(), e);
            throw new BizException(ErrorCode.TTS_FAILED, "TTS流式合成失败: " + e.getMessage());
        }
    }

    /**
     * 获取有效的 API URL
     */
    private String getEffectiveApiUrl(String apiKey) {
        if (configuredApiUrl != null && !configuredApiUrl.isBlank()) {
            return configuredApiUrl;
        }
        return TTS_API_URL;
    }

    /**
     * 获取有效的 API Key
     */
    private String getEffectiveApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey;
        }
        if (configuredApiKey != null && !configuredApiKey.isBlank()) {
            return configuredApiKey;
        }
        throw new BizException(ErrorCode.AUDIO_SERVICE_UNAVAILABLE, "未配置TTS API Key");
    }

    /**
     * 获取有效的模型
     */
    private String getEffectiveModel() {
        if (configuredModel != null && !configuredModel.isBlank()) {
            return configuredModel;
        }
        return "paraformer-zh";
    }

    /**
     * 获取有效的语音
     */
    private String getEffectiveVoice() {
        if (configuredVoice != null && !configuredVoice.isBlank()) {
            return configuredVoice;
        }
        return "aixia";
    }

    /**
     * 发送合成请求
     */
    private byte[] sendSynthesisRequest(String text, String apiUrl, String apiKey, String model, String voice) 
            throws Exception {
        log.debug("发送TTS请求，URL={}, 模型={}, 语音={}", apiUrl, model, voice);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(DEFAULT_TIMEOUT_MS);
            conn.setReadTimeout(DEFAULT_TIMEOUT_MS + 10000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            String requestBody = buildRequestBody(text, model, voice);
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                String errorResponse = readErrorResponse(conn);
                log.error("TTS API调用失败，响应码={}, 错误信息={}", responseCode, errorResponse);
                throw new BizException(ErrorCode.TTS_FAILED, 
                        String.format("TTS服务返回错误码: %d", responseCode));
            }

            // 读取音频数据
            try (java.io.InputStream is = conn.getInputStream()) {
                return is.readAllBytes();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 构建请求体
     */
    private String buildRequestBody(String text, String model, String voice) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(model).append("\",");
        sb.append("\"input\":{");
        sb.append("\"text\":\"").append(escapeJson(text)).append("\"");
        sb.append("},");
        sb.append("\"parameters\":{");
        sb.append("\"voice\":\"").append(voice).append("\",");
        sb.append("\"rate\":").append(DEFAULT_RATE).append(",");
        sb.append("\"pitch\":").append(DEFAULT_PITCH).append(",");
        sb.append("\"volume\":").append(DEFAULT_VOLUME).append(",");
        sb.append("\"sample_rate\":").append(DEFAULT_SAMPLE_RATE).append(",");
        sb.append("\"format\":\"").append(DEFAULT_FORMAT).append("\"");
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 读取错误响应
     */
    private String readErrorResponse(HttpURLConnection conn) {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } catch (Exception e) {
            return "无法读取错误响应";
        }
    }

    /**
     * JSON 字符串转义
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
     * 获取当前并发数
     *
     * @return 可用并发数
     */
    public int getAvailablePermits() {
        return semaphore.availablePermits();
    }

    /**
     * 获取最大并发数
     *
     * @return 最大并发数
     */
    public int getMaxConcurrentRequests() {
        return MAX_CONCURRENT_REQUESTS;
    }
}
