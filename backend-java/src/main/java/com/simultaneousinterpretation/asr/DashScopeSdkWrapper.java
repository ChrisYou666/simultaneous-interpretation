package com.simultaneousinterpretation.asr;

import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerParam;
import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerRealtime;
import com.alibaba.dashscope.audio.asr.translation.results.TranslationRecognizerResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.simultaneousinterpretation.config.DashScopeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 官方 DashScope SDK 封装 - 详细日志版本
 *
 * <p>全流程日志追踪：
 * <ul>
 *   <li>音频帧：接收时间 → 发送时间 → SDK 回调时间</li>
 *   <li>连接状态：创建、活跃、停止</li>
 *   <li>性能指标：首包延迟、末包延迟、吞吐量</li>
 *   <li>错误追踪：完整堆栈和上下文</li>
 * </ul>
 */
@Component
public class DashScopeSdkWrapper {

    private static final Logger log = LoggerFactory.getLogger(DashScopeSdkWrapper.class);
    private final DashScopeProperties dashScopeProperties;

    // sessionId → SdkInstance 映射
    private final Map<String, SdkInstance> instances = new ConcurrentHashMap<>();

    // 全局统计
    private static final AtomicLong totalFramesSent = new AtomicLong(0);
    private static final AtomicLong totalBytesSent = new AtomicLong(0);

    public DashScopeSdkWrapper(DashScopeProperties dashScopeProperties) {
        this.dashScopeProperties = dashScopeProperties;
    }

    /**
     * SDK 实例 - 包含详细状态追踪
     */
    public static class SdkInstance {
        public final TranslationRecognizerRealtime translator;
        public final Thread callThread;
        public final AtomicBoolean stopped = new AtomicBoolean(false);
        public final String sessionId;
        public final long createdAtMs;

        // 音频帧统计
        public final AtomicLong framesSent = new AtomicLong(0);
        public final AtomicLong bytesSent = new AtomicLong(0);
        public final AtomicLong lastFrameSentAt = new AtomicLong(0);
        public final AtomicLong firstFrameSentAt = new AtomicLong(0);

        // SDK 性能指标（由回调填充）
        public volatile String lastRequestId = "";
        public volatile Long firstPackageDelayMs = null;
        public volatile Long lastPackageDelayMs = null;

        public SdkInstance(TranslationRecognizerRealtime translator, Thread callThread, String sessionId) {
            this.translator = translator;
            this.callThread = callThread;
            this.sessionId = sessionId;
            this.createdAtMs = System.currentTimeMillis();
        }
    }

    /**
     * 启动 ASR 任务
     *
     * @param sessionId 会话ID（用于多会话管理）
     * @param callback 识别结果回调
     * @param onError 错误回调
     * @param onComplete 完成回调
     * @return true 启动成功
     */
    public boolean start(String sessionId,
                         Consumer<TranslationRecognizerResult> callback,
                         Consumer<Exception> onError,
                         Runnable onComplete) {
        long startTimeNs = System.nanoTime();

        log.info("[SDK-START] ===========================================");
        log.info("[SDK-START] sessionId={} 尝试启动 ASR", sessionId);
        log.info("[SDK-START] 当前活跃实例数: {}", instances.size());

        // 停止已有实例
        stop(sessionId);

        // 创建参数：只启用识别，关闭翻译（使用我们自己的翻译服务）
        long paramBuildStart = System.nanoTime();
        TranslationRecognizerParam param = TranslationRecognizerParam.builder()
                .model("gummy-realtime-v1")
                .format("pcm")
                .sampleRate(16000)
                .transcriptionEnabled(true)
                .translationEnabled(false)
                .sourceLanguage("auto")
                .semanticPunctationEnabled(false)  // 关闭语义标点等待，减少缓冲延迟（旧 WebSocket 实现已明确关闭此项）
                .maxEndSilence(400)                // VAD 静音超时 300ms，加快尾句触发
                .build();
        long paramBuildNs = System.nanoTime() - paramBuildStart;

        log.info("[SDK-PARAM] sessionId={} 参数构建耗时={}ns", sessionId, paramBuildNs);
        log.info("[SDK-PARAM] sessionId={} model={} format={} sampleRate={} transcriptionEnabled={} translationEnabled={} sourceLanguage={} semanticPunctation={} maxEndSilence={}",
                sessionId, "gummy-realtime-v1", "pcm", 16000, true, false, "auto", false, 300);

        // 创建识别器
        long translatorCreateStart = System.nanoTime();
        final TranslationRecognizerRealtime translator;
        try {
            String apiKey = dashScopeProperties.getEffectiveApiKey(null);
            if (apiKey != null && !apiKey.isBlank()) {
                System.setProperty("DASHSCOPE_API_KEY", apiKey);
                log.info("[SDK-START] sessionId={} 已将 API Key 注入系统属性", sessionId);
            }
            translator = new TranslationRecognizerRealtime();
        } catch (Exception e) {
            log.error("[SDK-START-FAIL] sessionId={} 初始化失败", sessionId, e);
            if (e instanceof NoApiKeyException) {
                onError.accept((NoApiKeyException) e);
            } else {
                onError.accept(e);
            }
            return false;
        }
        long translatorCreateNs = System.nanoTime() - translatorCreateStart;
        log.info("[SDK-INIT] sessionId={} TranslationRecognizerRealtime 创建耗时={}ns", sessionId, translatorCreateNs);

        // 保存 sessionId 用于回调（lambda 中无法直接引用外部非 final 变量）
        final String sid = sessionId;

        // 创建回调 - 添加详细日志
        ResultCallback<TranslationRecognizerResult> sdkCallback = new ResultCallback<TranslationRecognizerResult>() {
            @Override
            public void onEvent(TranslationRecognizerResult result) {
                long callbackEntryTimeNs = System.nanoTime();
                SdkInstance inst = getInstance(sid);
                long sessionAgeMs = inst != null ? System.currentTimeMillis() - inst.createdAtMs : 0;

                String requestId = result.getRequestId();
                boolean isSentenceEnd = result.isSentenceEnd();
                var transcription = result.getTranscriptionResult();
                String text = transcription != null ? transcription.getText() : "";
                Long beginTime = transcription != null ? transcription.getBeginTime() : null;
                Long endTime = transcription != null ? transcription.getEndTime() : null;
                Long sentenceId = transcription != null ? transcription.getSentenceId() : null;

                log.info("[SDK-CALLBACK] =============================");
                log.info("[SDK-CALLBACK] sessionId={} sessionAge={}ms", sid, sessionAgeMs);
                log.info("[SDK-CALLBACK] sessionId={} requestId={}", sid, requestId);
                log.info("[SDK-CALLBACK] sessionId={} isSentenceEnd={}", sid, isSentenceEnd);
                log.info("[SDK-CALLBACK] sessionId={} text=\"{}\"", sid, truncate(text, 80));
                log.info("[SDK-CALLBACK] sessionId={} beginTime={}ms endTime={}ms sentenceId={}",
                        sid, beginTime, endTime, sentenceId);

                // 回调处理耗时
                callback.accept(result);

                long callbackExitTimeNs = System.nanoTime();
                long callbackDurationNs = callbackExitTimeNs - callbackEntryTimeNs;
                log.info("[SDK-CALLBACK] sessionId={} 回调处理耗时={}ns ({:.3f}ms)",
                        sid, callbackDurationNs, callbackDurationNs / 1_000_000.0);
            }

            @Override
            public void onComplete() {
                long completeTimeMs = System.currentTimeMillis();
                SdkInstance inst = getInstance(sid);
                long sessionDurationMs = inst != null ? completeTimeMs - inst.createdAtMs : 0;

                log.info("[SDK-COMPLETE] ===========================================");
                log.info("[SDK-COMPLETE] sessionId={} 识别完成", sid);
                log.info("[SDK-COMPLETE] sessionId={} 会话持续时间={}ms", sid, sessionDurationMs);
                if (inst != null) {
                    log.info("[SDK-COMPLETE] sessionId={} 总发送帧数={}", sid, inst.framesSent.get());
                    log.info("[SDK-COMPLETE] sessionId={} 总发送字节={}", sid, inst.bytesSent.get());
                    log.info("[SDK-COMPLETE] sessionId={} lastRequestId={}", sid, inst.lastRequestId);
                    log.info("[SDK-COMPLETE] sessionId={} firstPackageDelay={}ms", sid, inst.firstPackageDelayMs);
                    log.info("[SDK-COMPLETE] sessionId={} lastPackageDelay={}ms", sid, inst.lastPackageDelayMs);
                }
                log.info("[SDK-COMPLETE] ===========================================");

                if (onComplete != null) {
                    onComplete.run();
                }
            }

            @Override
            public void onError(Exception e) {
                log.error("[SDK-ERROR] ===========================================");
                log.error("[SDK-ERROR] sessionId={} 识别错误", sid, e);
                log.error("[SDK-ERROR] sessionId={} errorType={}", sid, e.getClass().getName());
                log.error("[SDK-ERROR] sessionId={} errorMessage={}", sid, e.getMessage());
                if (e.getCause() != null) {
                    log.error("[SDK-ERROR] sessionId={} causeType={} causeMsg={}",
                            sid, e.getCause().getClass().getName(), e.getCause().getMessage());
                }
                log.error("[SDK-ERROR] ===========================================");
                onError.accept(e);
            }
        };

        // 在虚拟线程中启动 call（阻塞等待识别完成）
        long threadStartTimeNs = System.nanoTime();
        Thread vt = Thread.ofVirtual()
                .name("sdk-asr-" + sessionId)
                .start(() -> {
                    long callStartTimeNs = System.nanoTime();
                    log.info("[SDK-THREAD] sessionId={} 虚拟线程启动 thread={}", sid, Thread.currentThread().getName());

                    try {
                        translator.call(param, sdkCallback);

                        long callDurationNs = System.nanoTime() - callStartTimeNs;
                        SdkInstance inst = getInstance(sid);
                        if (inst != null) {
                            inst.lastRequestId = translator.getLastRequestId();
                            inst.firstPackageDelayMs = translator.getFirstPackageDelay();
                            inst.lastPackageDelayMs = translator.getLastPackageDelay();
                        }

                        log.info("[SDK-CALL] sessionId={} call() 返回，会话正常结束", sid);
                        log.info("[SDK-CALL] sessionId={} call 持续时间={}ns ({:.3f}s)", sid, callDurationNs, callDurationNs / 1_000_000_000.0);
                        log.info("[SDK-CALL] sessionId={} lastRequestId={}", sid, translator.getLastRequestId());
                        log.info("[SDK-CALL] sessionId={} firstPackageDelay={}ms", sid, translator.getFirstPackageDelay());
                        log.info("[SDK-CALL] sessionId={} lastPackageDelay={}ms", sid, translator.getLastPackageDelay());

                    } catch (Exception e) {
                        long callDurationNs = System.nanoTime() - callStartTimeNs;
                        if (e instanceof NoApiKeyException) {
                            log.error("[SDK-CALL-FAIL] sessionId={} API Key 未设置 callDuration={:.3f}s",
                                    sid, callDurationNs / 1_000_000_000.0, e);
                        } else {
                            SdkInstance inst = instances.get(sid);
                            if (inst != null && !inst.stopped.get()) {
                                log.error("[SDK-CALL-FAIL] sessionId={} call 执行异常 callDuration={:.3f}s",
                                        sid, callDurationNs / 1_000_000_000.0, e);
                            } else {
                                log.info("[SDK-CALL-STOPPED] sessionId={} call 被安全停止", sid);
                            }
                        }
                        onError.accept(e);
                    }
                });

        long threadCreateNs = System.nanoTime() - threadStartTimeNs;
        log.info("[SDK-THREAD] sessionId={} 虚拟线程创建耗时={}ns", sessionId, threadCreateNs);

        // 存储实例
        SdkInstance instance = new SdkInstance(translator, vt, sessionId);
        instances.put(sessionId, instance);

        long totalStartNs = System.nanoTime() - startTimeNs;
        log.info("[SDK-START-SUCCESS] sessionId={} 启动成功", sessionId);
        log.info("[SDK-START-SUCCESS] sessionId={} 总启动耗时={}ns ({:.3f}ms)", sessionId, totalStartNs, totalStartNs / 1_000_000.0);
        log.info("[SDK-START-SUCCESS] sessionId={} 虚拟线程={}", sessionId, vt.getName());
        log.info("[SDK-START-SUCCESS] ===========================================");

        return true;
    }

    /**
     * 发送 PCM 音频帧（逐帧发送，无攒批）
     *
     * <p>详细日志包括：
     * <ul>
     *   <li>帧大小和时间戳</li>
     *   <li>发送前后延迟</li>
     *   <li>累积统计（帧数、字节数）</li>
     * </ul>
     *
     * @param sessionId 会话ID
     * @param pcmData PCM 音频数据
     */
    public void sendAudioFrame(String sessionId, byte[] pcmData) {
        long entryTimeNs = System.nanoTime();

        SdkInstance instance = instances.get(sessionId);
        if (instance == null) {
            log.warn("[SDK-SEND] sessionId={} 实例不存在，跳过帧 size={}bytes", sessionId, pcmData.length);
            return;
        }
        if (instance.stopped.get()) {
            // 静默忽略，避免日志刷屏
            return;
        }

        long beforeSendTimeNs = System.nanoTime();
        long framesSoFar = instance.framesSent.incrementAndGet();
        long bytesSoFar = instance.bytesSent.addAndGet(pcmData.length);
        long timeSinceLastFrame = instance.lastFrameSentAt.get() > 0
                ? System.currentTimeMillis() - instance.lastFrameSentAt.get()
                : -1;

        try {
            ByteBuffer buffer = ByteBuffer.wrap(pcmData);
            instance.translator.sendAudioFrame(buffer);

            long afterSendTimeNs = System.nanoTime();
            long sendDurationNs = afterSendTimeNs - beforeSendTimeNs;
            long totalElapsedNs = afterSendTimeNs - entryTimeNs;
            long intervalSinceLast = System.currentTimeMillis() - instance.lastFrameSentAt.getAndSet(System.currentTimeMillis());

            if (framesSoFar <= 10 || framesSoFar % 100 == 0) {
                // 前10帧和每100帧打印详细信息
                log.info("[SDK-SEND] frame#{} sessionId={} size={}bytes totalBytes={} " +
                        "sendNs={} intervalSinceLast={}ms",
                        framesSoFar, sessionId, pcmData.length, bytesSoFar,
                        sendDurationNs, intervalSinceLast);
            } else {
                // 其余帧用 debug 级别
                log.debug("[SDK-SEND] frame#{} sessionId={} size={}bytes sendNs={}",
                        framesSoFar, sessionId, pcmData.length, sendDurationNs);
            }

            // 更新首帧时间戳
            if (instance.firstFrameSentAt.get() == 0) {
                instance.firstFrameSentAt.set(System.currentTimeMillis());
                log.info("[SDK-SEND] sessionId={} 首帧发送时间={}", sessionId, instance.firstFrameSentAt.get());
            }

        } catch (Exception e) {
            if (!instance.stopped.get()) {
                log.error("[SDK-SEND-FAIL] sessionId={} frame#{} size={}bytes error={}",
                        sessionId, framesSoFar, pcmData.length, e.getMessage(), e);
            }
        }
    }

    /**
     * 停止 ASR 任务
     *
     * @param sessionId 会话ID
     */
    public void stop(String sessionId) {
        long stopStartTimeNs = System.nanoTime();

        SdkInstance instance = instances.remove(sessionId);
        if (instance != null) {
            instance.stopped.set(true);

            long sessionDurationMs = System.currentTimeMillis() - instance.createdAtMs;
            long framesSent = instance.framesSent.get();
            long bytesSent = instance.bytesSent.get();
            long firstFrameMs = instance.firstFrameSentAt.get();
            long lastFrameMs = instance.lastFrameSentAt.get();

            log.info("[SDK-STOP] ===========================================");
            log.info("[SDK-STOP] sessionId={} 正在停止", sessionId);
            log.info("[SDK-STOP] sessionId={} 会话持续时间={}ms ({:.3f}s)", sessionId, sessionDurationMs, sessionDurationMs / 1000.0);
            log.info("[SDK-STOP] sessionId={} 总发送帧数={}", sessionId, framesSent);
            log.info("[SDK-STOP] sessionId={} 总发送字节={} ({:.3f}MB)", sessionId, bytesSent, bytesSent / 1_000_000.0);
            log.info("[SDK-STOP] sessionId={} 吞吐量={:.2f} bytes/s",
                    sessionId, sessionDurationMs > 0 ? (bytesSent * 1000.0 / sessionDurationMs) : 0);

            if (firstFrameMs > 0) {
                log.info("[SDK-STOP] sessionId={} 首帧发送时间={}", sessionId, firstFrameMs);
            }
            if (lastFrameMs > 0) {
                log.info("[SDK-STOP] sessionId={} 末帧发送时间={}", sessionId, lastFrameMs);
            }
            log.info("[SDK-STOP] sessionId={} lastRequestId={}", sessionId, instance.lastRequestId);
            log.info("[SDK-STOP] sessionId={} firstPackageDelay={}ms", sessionId, instance.firstPackageDelayMs);
            log.info("[SDK-STOP] sessionId={} lastPackageDelay={}ms", sessionId, instance.lastPackageDelayMs);

            try {
                long stopCallStart = System.nanoTime();
                instance.translator.stop();
                log.info("[SDK-STOP] sessionId={} translator.stop() 耗时={}ns", sessionId, System.nanoTime() - stopCallStart);

                long closeStart = System.nanoTime();
                instance.translator.getDuplexApi().close(1000, "stopped");
                log.info("[SDK-STOP] sessionId={} getDuplexApi().close() 耗时={}ns", sessionId, System.nanoTime() - closeStart);

            } catch (Exception e) {
                log.warn("[SDK-STOP-ERROR] sessionId={} 停止时出错: {}", sessionId, e.getMessage());
            }

            long totalStopNs = System.nanoTime() - stopStartTimeNs;
            log.info("[SDK-STOP] sessionId={} 总停止耗时={}ns ({:.3f}ms)", sessionId, totalStopNs, totalStopNs / 1_000_000.0);
            log.info("[SDK-STOP] ===========================================");
        } else {
            log.info("[SDK-STOP] sessionId={} 实例不存在或已停止", sessionId);
        }
    }

    /**
     * 检查实例是否存在且未停止
     */
    public boolean hasActiveInstance(String sessionId) {
        SdkInstance instance = instances.get(sessionId);
        boolean active = instance != null && !instance.stopped.get();
        log.debug("[SDK-CHECK] sessionId={} active={}", sessionId, active);
        return active;
    }

    /**
     * 获取实例
     */
    public SdkInstance getInstance(String sessionId) {
        return instances.get(sessionId);
    }

    /**
     * 获取全局统计信息
     */
    public Map<String, Object> getGlobalStats() {
        return Map.of(
                "totalFramesSent", totalFramesSent.get(),
                "totalBytesSent", totalBytesSent.get(),
                "activeInstances", instances.size()
        );
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
