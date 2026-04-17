package com.simultaneousinterpretation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simultaneousinterpretation.common.BizException;
import com.simultaneousinterpretation.common.Constants;
import com.simultaneousinterpretation.config.AiProperties;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.config.PipelineTuningParams;
import com.simultaneousinterpretation.domain.enums.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时 TTS 语音合成服务
 * <p>
 * DashScope CosyVoice SSE 流式合成，支持增量回调
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@Service
public class TtsService {

    /**
     * TTS API URL
     */
    private static final String TTS_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";

    /**
     * 请求超时时间（秒）
     */
    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    /**
     * 默认采样率
     */
    private static final int DEFAULT_SAMPLE_RATE = 16000;

    /**
     * 默认音频格式
     */
    private static final String DEFAULT_FORMAT = "wav";

    private final DashScopeProperties dashScopeProperties;
    private final AiProperties aiProperties;
    private final PipelineTuningParams tuningParams;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * TTS 信号量（控制并发）
     */
    private final Semaphore ttsSlots;

    /**
     * 构造函数
     */
    public TtsService(DashScopeProperties dashScopeProperties, AiProperties aiProperties,
                      PipelineTuningParams tuningParams, ObjectMapper objectMapper) {
        this.dashScopeProperties = dashScopeProperties;
        this.aiProperties = aiProperties;
        this.tuningParams = tuningParams;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.ttsSlots = new Semaphore(tuningParams.getTtsMaxConcurrent());
        log.info("TTS服务初始化完成，maxConcurrent={}, model={}, voice={}",
                tuningParams.getTtsMaxConcurrent(), tuningParams.getTtsModel(), tuningParams.getTtsVoice());
    }

    /**
     * 音频块回调接口
     */
    @FunctionalInterface
    public interface AudioChunkCallback {
        void onChunk(int segIdx, String tgtLang, byte[] audioData) throws IOException;
    }

    /**
     * 旧版回调接口（兼容）
     */
    @FunctionalInterface
    public interface LegacyAudioChunkCallback {
        void onChunk(byte[] audioData) throws IOException;
    }

    /**
     * 增量流式 TTS
     *
     * @param text     文本内容
     * @param lang     语言
     * @param callback 音频回调
     * @return 是否有音频输出
     */
    public boolean synthesizeStreaming(String text, String lang, AudioChunkCallback callback) {
        return synthesizeStreaming(text, lang, Constants.DEFAULT_TTS_RATE, callback);
    }

    /**
     * 带语速的流式 TTS
     *
     * @param text     文本内容
     * @param lang     语言
     * @param rate     语速
     * @param callback 音频回调
     * @return 是否有音频输出
     */
    public boolean synthesizeStreaming(String text, String lang, double rate, AudioChunkCallback callback) {
        return synthesizeStreamingImpl(text, lang, rate, callback, null);
    }

    /**
     * 带段索引的流式 TTS
     *
     * @param text     文本内容
     * @param lang     语言
     * @param rate     语速
     * @param segIdx   段索引
     * @param callback 音频回调
     * @return 是否有音频输出
     */
    public boolean synthesizeStreaming(String text, String lang, double rate, int segIdx, AudioChunkCallback callback) {
        return synthesizeStreamingImpl(text, lang, rate, callback, segIdx);
    }

    /**
     * 兼容旧接口
     */
    public boolean synthesizeStreaming(String text, String lang, double rate, LegacyAudioChunkCallback legacyCallback) {
        AudioChunkCallback wrapped = (segIdx, tgtLang, data) -> legacyCallback.onChunk(data);
        return synthesizeStreamingImpl(text, lang, rate, wrapped, null);
    }

    /**
     * 流式 TTS 实现
     */
    private boolean synthesizeStreamingImpl(String text, String lang, double rate,
                                              AudioChunkCallback callback, Integer forcedSegIdx) {
        log.info("TTS合成开始，文本长度={}, 语言={}, 语速={}, 段索引={}", 
                 text != null ? text.length() : 0, lang, rate, 
                 forcedSegIdx != null ? forcedSegIdx : -1);
        long startTime = System.currentTimeMillis();

        // 获取 API Key
        String apiKey = dashScopeProperties.getEffectiveApiKey(aiProperties.getApiKey());
        if (!StringUtils.hasText(apiKey)) {
            log.error("TTS合成失败，API Key 未配置");
            throw new BizException(ErrorCode.AUDIO_SERVICE_UNAVAILABLE, 
                    "未配置TTS API Key，请在配置中设置");
        }

        // 获取运行时参数
        int chunkThreshold = tuningParams.getTtsChunkThreshold();
        String model = tuningParams.getTtsModel();
        String voice = tuningParams.getTtsVoice();

        AtomicBoolean anyAudio = new AtomicBoolean(false);
        ttsSlots.acquireUninterruptibly();

        try {
            String body = buildRequestBody(text, lang, model, voice, rate);
            log.debug("TTS请求体构建完成，文本长度={}", text.length());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TTS_URL))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .header("X-DashScope-SSE", "enable")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("TTS HTTP错误，状态码={}, 错误信息={}", response.statusCode(), truncate(errorBody, 300));
                throw new BizException(ErrorCode.TTS_FAILED, "TTS服务返回错误: " + response.statusCode());
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            int totalBytes = 0;
            int chunks = 0;
            long firstChunkTs = 0;
            long lastChunkTs = 0;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String json = line.substring(5).trim();
                    if (json.isEmpty()) {
                        continue;
                    }
                    try {
                        JsonNode node = objectMapper.readTree(json);
                        String b64 = node.path("output").path("audio").path("data").asText("");
                        if (!b64.isEmpty()) {
                            byte[] decoded = Base64.getDecoder().decode(b64);
                            buffer.write(decoded);
                            totalBytes += decoded.length;

                            long now = System.currentTimeMillis();
                            if (chunkThreshold > 0 && buffer.size() >= chunkThreshold) {
                                if (firstChunkTs == 0) {
                                    firstChunkTs = now;
                                    long firstDelay = firstChunkTs - startTime;
                                    log.info("TTS首块输出，段索引={}, 语言={}, 首块延迟={}ms, 大小={}bytes", 
                                             forcedSegIdx != null ? forcedSegIdx : -1, lang, firstDelay, buffer.size());
                                }
                                lastChunkTs = now;
                                callback.onChunk(forcedSegIdx != null ? forcedSegIdx : -1, lang, buffer.toByteArray());
                                anyAudio.set(true);
                                chunks++;
                                buffer.reset();
                            }
                        }
                    } catch (Exception parseEx) {
                        log.debug("TTS SSE解析跳过: {}", truncate(json, 80));
                    }
                }
            }

            // 输出剩余数据
            if (buffer.size() > 0) {
                long now = System.currentTimeMillis();
                if (firstChunkTs == 0) {
                    firstChunkTs = now;
                    log.info("TTS首块输出(最终)，段索引={}, 语言={}, 延迟={}ms, 大小={}bytes", 
                             forcedSegIdx != null ? forcedSegIdx : -1, lang, firstChunkTs - startTime, buffer.size());
                }
                lastChunkTs = now;
                callback.onChunk(forcedSegIdx != null ? forcedSegIdx : -1, lang, buffer.toByteArray());
                anyAudio.set(true);
                chunks++;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            long firstDelay = firstChunkTs > 0 ? firstChunkTs - startTime : elapsed;
            long totalDuration = lastChunkTs > 0 ? lastChunkTs - startTime : elapsed;
            
            log.info("TTS合成完成，段索引={}, 语言={}, 总耗时={}ms, 首块延迟={}ms, "
                     + "总时长={}ms, 块数={}, 总字节={}", 
                     forcedSegIdx != null ? forcedSegIdx : -1, lang, elapsed, 
                     firstDelay, totalDuration, chunks, totalBytes);

            return anyAudio.get();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("TTS合成异常，段索引={}, 语言={}, error={}", 
                     forcedSegIdx != null ? forcedSegIdx : -1, lang, e.getMessage(), e);
            throw new BizException(ErrorCode.TTS_FAILED, "TTS服务调用失败: " + e.getMessage());
        } finally {
            ttsSlots.release();
        }
    }

    /**
     * 同步 TTS
     */
    public byte[] synthesize(String text, String lang) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicBoolean hasAudio = new AtomicBoolean(false);
        synthesizeStreaming(text, lang, Constants.DEFAULT_TTS_RATE, (segIdx, tgtLang, data) -> {
            out.write(data);
            hasAudio.set(true);
        });
        byte[] result = out.toByteArray();
        return result.length > 0 ? result : null;
    }

    /**
     * 构建请求体
     */
    private String buildRequestBody(String text, String lang, String model, String voice, double rate) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("model", model != null ? model : "cosyvoice-v3-flash");
        
        ObjectNode input = root.putObject("input");
        input.put("text", text);
        input.put("voice", voice != null ? voice : "longanyang");
        input.put("format", DEFAULT_FORMAT);
        input.put("sample_rate", DEFAULT_SAMPLE_RATE);
        input.put("rate", clampRate(rate)); // 语速控制
        
        if (lang != null) {
            ArrayNode hints = input.putArray("language_hints");
            hints.add(lang);
        }
        
        return mapper.writeValueAsString(root);
    }

    /**
     * 限制语速范围
     */
    private static double clampRate(double rate) {
        if (rate < 0.5) return 0.5;
        if (rate > 2.0) return 2.0;
        if (Double.isNaN(rate)) return 1.0;
        return rate;
    }

    /**
     * 截断字符串
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
