package com.simultaneousinterpretation.asr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simultaneousinterpretation.config.AsrProperties;
import com.simultaneousinterpretation.config.PipelineTuningParams;
import com.simultaneousinterpretation.meeting.RoomSegmentRegistry;
import com.simultaneousinterpretation.meeting.RoomWebSocketHandler;
import com.simultaneousinterpretation.service.LanguageDetectionService;
import com.simultaneousinterpretation.service.RealtimeTranslateService;
import com.simultaneousinterpretation.service.RealtimeTtsService;
import com.alibaba.dashscope.audio.asr.translation.results.TranslationRecognizerResult;
import com.alibaba.dashscope.audio.asr.translation.results.TranscriptionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ASR WebSocket 处理器 - 使用官方 DashScope SDK
 *
 * <p>架构变化：
 * <ul>
 *   <li>旧方案：后端手写 WebSocket → 攒批(512ms) → 发送 → 解析 JSON</li>
 *   <li>新方案：后端 → 官方 SDK 逐帧发送 → 回调返回 TranscriptionResult</li>
 * </ul>
 *
 * <p>保留的后续处理：
 * <ul>
 *   <li>SegmentationEngine 切段</li>
 *   <li>三语翻译 (RealtimeTranslateService)</li>
 *   <li>三语 TTS (RealtimeTtsService)</li>
 *   <li>房间广播 (RoomSegmentRegistry, RoomWebSocketHandler)</li>
 * </ul>
 */
@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

    // ─── Session 属性常量 ───────────────────────────────────────────────────────

    private static final String ATTR_SEG_ENGINE = "asr.segEngine";
    private static final String ATTR_SEG_BATCH_COUNTER = "asr.segBatchCounter";
    private static final String ATTR_SEG_TIMER = "asr.segTimer";
    private static final String ATTR_GLOSSARY = "asr.glossary";
    private static final String ATTR_CONTEXT = "asr.context";
    private static final String ATTR_LISTEN_LANG = "asr.listenLang";
    private static final String ATTR_LAST_ASR_LANG = "asr.lastAsrLang";
    private static final String ATTR_LANG_LOCK = "asr.langLock";
    private static final String ATTR_LANG_CANDIDATE = "asr.langCandidate";
    private static final String ATTR_LANG_STREAK = "asr.langStreak";
    private static final String ATTR_PREV_SEG_LANG = "asr.prevSegLang";
    private static final String ATTR_SEG_LANG_SWITCHED = "asr.segLangSwitched";
    private static final String ATTR_LISTEN_TTS_CHAIN = "asr.listenTtsChain";
    private static final String ATTR_LISTEN_TTS_STARTED_CHAIN = "asr.listenTtsStartedChain";
    private static final String ATTR_ROOM_ID = "asr.roomId";
    private static final String ATTR_CURRENT_SRC_LANG = "asr.currentSrcLang";
    private static final String ATTR_LATEST_SEG_IDX = "asr.latestSegIdx";
    private static final List<String> ALL_LANGS = List.of("zh", "en", "id");

    /** 转发给房间听众的事件类型 */
    private static final Set<String> ROOM_FORWARD_EVENTS = Set.of(
            "segment", "translation", "tts_skip", "transcript", "error", "ready",
            "playback_start", "playback_end");

    // ─── 依赖 ─────────────────────────────────────────────────────────────────

    private final ScheduledExecutorService segScheduler = Executors.newScheduledThreadPool(2);

    private static final int PER_LANG_POOL_SIZE = 4;
    private static final java.util.concurrent.ConcurrentHashMap<String, ExecutorService> langPairPools =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static ExecutorService langPairPool(String srcLang, String tgtLang) {
        String key = srcLang + "->" + tgtLang;
        return langPairPools.computeIfAbsent(key, k ->
                Executors.newFixedThreadPool(PER_LANG_POOL_SIZE, r -> {
                    Thread t = new Thread(r, "trans-" + k);
                    t.setDaemon(true);
                    return t;
                }));
    }

    private final AsrProperties asrProperties;
    private final DashScopeSdkWrapper sdkWrapper;
    private final ObjectMapper objectMapper;
    private final RealtimeTranslateService translateService;
    private final RealtimeTtsService ttsService;
    private final PipelineTuningParams tuningParams;
    private final RoomWebSocketHandler roomWebSocketHandler;
    private final RoomSegmentRegistry segmentRegistry;
    private final LanguageDetectionService languageDetectionService;

    public AsrWebSocketHandler(
            AsrProperties asrProperties,
            DashScopeSdkWrapper sdkWrapper,
            ObjectMapper objectMapper,
            RealtimeTranslateService translateService,
            RealtimeTtsService ttsService,
            PipelineTuningParams tuningParams,
            RoomWebSocketHandler roomWebSocketHandler,
            RoomSegmentRegistry segmentRegistry,
            LanguageDetectionService languageDetectionService) {
        this.asrProperties = asrProperties;
        this.sdkWrapper = sdkWrapper;
        this.objectMapper = objectMapper;
        this.translateService = translateService;
        this.ttsService = ttsService;
        this.tuningParams = tuningParams;
        this.roomWebSocketHandler = roomWebSocketHandler;
        this.segmentRegistry = segmentRegistry;
        this.languageDetectionService = languageDetectionService;
    }

    // ─── 连接建立 ──────────────────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        long connectionStartMs = System.currentTimeMillis();
        long connectionStartNs = System.nanoTime();
        String sessionId = session.getId();

        String user = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USERNAME);
        int floor = (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);
        String roomId = (String) session.getAttributes().get("roomId");

        log.info("[ASR-CONNECT] ===========================================");
        log.info("[ASR-CONNECT] sessionId={} WebSocket 连接建立", sessionId);
        log.info("[ASR-CONNECT] sessionId={} user={} floor={} roomId={}", sessionId, user, floor, roomId);
        log.info("[ASR-CONNECT] sessionId={} remoteAddress={}", sessionId, session.getRemoteAddress());
        log.info("[ASS-CONNECT] sessionId={} localAddress={}", sessionId, session.getLocalAddress());
        log.info("[ASR-CONNECT] ===========================================");

        // 初始化队列
        long queueStartNs = System.nanoTime();
        AsrClientTextOutboundQueue.start(session, this::relayOutboundJsonAfterHostSent);
        long queueNs = System.nanoTime() - queueStartNs;
        log.info("[ASR-INIT] sessionId={} 队列初始化耗时={}ns", sessionId, queueNs);

        // 初始化切段引擎
        long segEngineStartNs = System.nanoTime();
        SegmentationEngine segEngine = new SegmentationEngine(
                tuningParams.getSegMaxChars(),
                tuningParams.getSegSoftBreakChars(),
                tuningParams.getSegFlushTimeoutMs(),
                tuningParams.getSegEnMaxCharsMultiplier(),
                tuningParams.getSegEnMaxCharsMin(),
                tuningParams.getSegEnMaxCharsMax(),
                tuningParams.getSegIdMaxCharsMultiplier(),
                tuningParams.getSegIdMaxCharsMin(),
                tuningParams.getSegIdMaxCharsMax()
        );
        long segEngineNs = System.nanoTime() - segEngineStartNs;
        log.info("[ASR-INIT] sessionId={} 切段引擎初始化耗时={}ns", sessionId, segEngineNs);

        session.getAttributes().put(ATTR_SEG_ENGINE, segEngine);
        session.getAttributes().put(ATTR_SEG_BATCH_COUNTER, new AtomicLong(System.nanoTime()));
        session.getAttributes().put(ATTR_LANG_LOCK, "");
        session.getAttributes().put(ATTR_LANG_CANDIDATE, "");
        session.getAttributes().put(ATTR_LANG_STREAK, 0);
        session.getAttributes().put(ATTR_LISTEN_TTS_CHAIN, CompletableFuture.completedFuture(null));
        session.getAttributes().put(ATTR_LISTEN_TTS_STARTED_CHAIN, CompletableFuture.completedFuture(null));
        session.getAttributes().put(ATTR_LATEST_SEG_IDX, new AtomicInteger(0));
        session.getAttributes().put(ATTR_CURRENT_SRC_LANG, "en");

        // 统计初始化
        session.getAttributes().put("asr.framesReceived", new AtomicLong(0));
        session.getAttributes().put("asr.bytesReceived", new AtomicLong(0));
        session.getAttributes().put("asr.resultsReceived", new AtomicLong(0));
        session.getAttributes().put("asr.segmentsEmitted", new AtomicLong(0));

        // 流水线日志追踪器
        PipelineStageLogger.get(session);

        // 切段 tick
        AtomicLong batchCounter = (AtomicLong) session.getAttributes().get(ATTR_SEG_BATCH_COUNTER);
        ScheduledFuture<?> timer = segScheduler.scheduleAtFixedRate(() -> {
            try {
                long tickStartNs = System.nanoTime();
                List<SegmentationEngine.Segment> segs = segEngine.tick("", floor);
                if (segs.isEmpty()) return;
                long segmentBatchId = batchCounter.incrementAndGet();
                for (SegmentationEngine.Segment seg : segs) {
                    sendSegmentEvent(session, seg, segmentBatchId);
                }
                long tickNs = System.nanoTime() - tickStartNs;
                log.debug("[SEG-TICK] sessionId={} tickNs={} segCount={}", sessionId, tickNs, segs.size());
            } catch (Exception e) {
                log.debug("[切段] tick 异常: {}", e.getMessage());
            }
        }, 200, 400, TimeUnit.MILLISECONDS);
        session.getAttributes().put(ATTR_SEG_TIMER, timer);
        log.info("[ASR-INIT] sessionId={} 切段定时器已启动", sessionId);

        // 启动官方 SDK
        long sdkStartNs = System.nanoTime();
        boolean started = sdkWrapper.start(
                sessionId,
                // 识别结果回调
                result -> handleSdkResult(session, result),
                // 错误回调
                error -> {
                    try {
                        log.error("[ASR-ERROR] sessionId={} SDK 错误回调", sessionId);
                        sendAsrError(session, "SDK_ERROR", "ASR 服务错误", error.getMessage());
                    } catch (IOException ignored) {}
                },
                // 完成回调
                () -> {
                    long durationMs = System.currentTimeMillis() - connectionStartMs;
                    log.info("[SDK] sessionId={} 识别完成 会话持续={}ms", sessionId, durationMs);
                }
        );
        long sdkStartNs2 = System.nanoTime() - sdkStartNs;
        log.info("[ASR-SDK] sessionId={} SDK 启动耗时={}ns ({:.3f}ms) result={}",
                sessionId, sdkStartNs2, sdkStartNs2 / 1_000_000.0, started);

        if (!started) {
            log.error("[ASR-ERROR] sessionId={} SDK 启动失败，关闭连接", sessionId);
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        // 发送 ready 事件
        long sendReadyStartNs = System.nanoTime();
        Map<String, Object> ready = new HashMap<>();
        ready.put("event", "ready");
        ready.put("floor", floor);
        ready.put("sampleRate", 16000);
        ready.put("provider", "dashscope-sdk");
        ready.put("currentSeq", 0);
        ready.put("serverTimeMs", System.currentTimeMillis());
        try {
            sendPayload(session, ready);
            long sendReadyNs = System.nanoTime() - sendReadyStartNs;
            log.info("[ASR-READY] sessionId={} ready 事件已发送耗时={}ns", sessionId, sendReadyNs);
        } catch (IOException e) {
            log.error("[ASR-READY] sessionId={} ready 事件发送失败", sessionId, e);
        }

        long totalConnectionMs = System.currentTimeMillis() - connectionStartMs;
        long totalConnectionNs = System.nanoTime() - connectionStartNs;
        log.info("[ASR-CONNECT-COMPLETE] ===========================================");
        log.info("[ASR-CONNECT-COMPLETE] sessionId={} 连接建立完成", sessionId);
        log.info("[ASR-CONNECT-COMPLETE] sessionId={} 总耗时={}ns ({:.3f}ms)", sessionId, totalConnectionNs, totalConnectionMs);
        log.info("[ASR-CONNECT-COMPLETE] ===========================================");
    }

    /**
     * 处理官方 SDK 返回的识别结果
     */
    private void handleSdkResult(WebSocketSession session, TranslationRecognizerResult result) {
        long handlerEntryNs = System.nanoTime();
        long handlerEntryMs = System.currentTimeMillis();
        String sessionId = session.getId();

        int floor = (Integer) session.getAttributes().getOrDefault(
                JwtHandshakeInterceptor.ATTR_FLOOR, 1);

        TranscriptionResult transcription = result.getTranscriptionResult();
        if (transcription == null) {
            log.debug("[SDK-RESULT] sessionId={} transcription 为空，跳过", sessionId);
            return;
        }

        String text = transcription.getText();
        boolean isFinal = result.isSentenceEnd();
        Long sentenceId = transcription.getSentenceId();
        Long beginTime = transcription.getBeginTime();
        Long endTime = transcription.getEndTime();

        // 统计
        AtomicLong resultsReceived = (AtomicLong) session.getAttributes().computeIfAbsent("asr.resultsReceived",
                k -> new AtomicLong(0));
        long resultCount = resultsReceived.incrementAndGet();

        log.info("[SDK-RESULT] ==============================");
        log.info("[SDK-RESULT] sessionId={} result#{}", sessionId, resultCount);
        log.info("[SDK-RESULT] sessionId={} isFinal={}", sessionId, isFinal);
        log.info("[SDK-RESULT] sessionId={} text=\"{}\"", sessionId, truncate(text, 80));
        log.info("[SDK-RESULT] sessionId={} sentenceId={} beginTime={}ms endTime={}ms duration={}ms",
                sessionId, sentenceId, beginTime, endTime, (endTime != null && beginTime != null) ? (endTime - beginTime) : "N/A");
        log.info("[SDK-RESULT] sessionId={} requestId={}", sessionId, result.getRequestId());
        log.info("[SDK-RESULT] sessionId={} handlerEntryMs={}", sessionId, handlerEntryMs);
        log.info("[SDK-RESULT] ==============================");

        // 语言检测
        long langDetectStartNs = System.nanoTime();
        String asrLang = "";
        String hint = (String) session.getAttributes().getOrDefault(ATTR_LAST_ASR_LANG, "");
        String detectedLang = resolveDetectedLang(asrLang, text, hint);
        if (StringUtils.hasText(detectedLang)) {
            session.getAttributes().put(ATTR_LAST_ASR_LANG, detectedLang);
        }
        long langDetectNs = System.nanoTime() - langDetectStartNs;

        log.info("[SDK-RESULT] sessionId={} detectedLang={} hint={} langDetectNs={}",
                sessionId, detectedLang, hint, langDetectNs);

        // 发送 transcript 事件（实时）
        long sendTranscriptStartNs = System.nanoTime();
        Map<String, Object> payload = new HashMap<>();
        payload.put("event", "transcript");
        payload.put("partial", !isFinal);
        payload.put("text", text);
        payload.put("language", detectedLang);
        payload.put("confidence", 1.0);
        payload.put("floor", floor);
        payload.put("batchId", session.getAttributes().getOrDefault("asr.currentBatchId", -1L));
        payload.put("handlerReceiveMs", handlerEntryMs);
        try {
            sendPayload(session, payload);
            long sendNs = System.nanoTime() - sendTranscriptStartNs;
            log.debug("[SDK-RESULT] sessionId={} transcript 发送耗时={}ns ({:.3f}ms)",
                    sessionId, sendNs, sendNs / 1_000_000.0);
        } catch (IOException e) {
            log.debug("[SDK-RESULT] sessionId={} 发送 transcript 失败", sessionId, e);
        }

        // final 结果触发切段
        if (isFinal) {
            long segStartNs = System.nanoTime();
            SegmentationEngine segEngine = (SegmentationEngine) session.getAttributes().get(ATTR_SEG_ENGINE);
            if (segEngine != null) {
                AtomicLong batchCounter = (AtomicLong) session.getAttributes().get(ATTR_SEG_BATCH_COUNTER);
                long segmentBatchId = batchCounter.incrementAndGet();
                session.getAttributes().put("asr.currentBatchId", segmentBatchId);

                log.info("[SDK-RESULT-SEG] ==============================");
                log.info("[SDK-RESULT-SEG] sessionId={} final 结果触发切段", sessionId);
                log.info("[SDK-RESULT-SEG] sessionId={} text=\"{}\"", sessionId, truncate(text, 80));
                log.info("[SDK-RESULT-SEG] sessionId={} detectedLang={}", sessionId, detectedLang);
                log.info("[SDK-RESULT-SEG] sessionId={} segmentBatchId={}", sessionId, segmentBatchId);
                log.info("[SDK-RESULT-SEG] ==============================");

                // 流式回调：每切出一句立即处理
                segEngine.onFinalTranscriptStreaming(text, detectedLang, 1.0, floor,
                        segmentBatchId, seg -> processSegmentStreaming(session, seg, segmentBatchId));

                long segNs = System.nanoTime() - segStartNs;
                log.info("[SDK-RESULT-SEG] sessionId={} 切段处理完成耗时={}ns ({:.3f}ms)",
                        sessionId, segNs, segNs / 1_000_000.0);
            } else {
                log.warn("[SDK-RESULT-SEG] sessionId={} segEngine 为空，无法切段", sessionId);
            }
        }

        long totalHandlerNs = System.nanoTime() - handlerEntryNs;
        log.info("[SDK-RESULT] sessionId={} handler 总耗时={}ns ({:.3f}ms)",
                sessionId, totalHandlerNs, totalHandlerNs / 1_000_000.0);
    }

    // ─── 二进制消息（音频数据）─────────────────────────────────────────────────

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        long receiveTimeNs = System.nanoTime();
        long receiveTimeMs = System.currentTimeMillis();

        ByteBuffer payload = message.getPayload();
        byte[] raw = new byte[payload.remaining()];
        payload.get(raw);

        // 解析 PCM（跳过 8 字节时间戳前缀）
        long parseStartNs = System.nanoTime();
        byte[] pcm;
        long startSamplePos = 0;
        if (raw.length >= 10) {
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
            startSamplePos = bb.getLong(); // 提取起始采样位置
            pcm = new byte[raw.length - 8];
            bb.get(pcm);
        } else {
            pcm = raw;
        }
        long parseNs = System.nanoTime() - parseStartNs;
        long totalParseNs = System.nanoTime() - receiveTimeNs;

        // 记录音频帧统计
        AtomicLong framesReceived = (AtomicLong) session.getAttributes().computeIfAbsent("asr.framesReceived",
                k -> new AtomicLong(0));
        long frameCount = framesReceived.incrementAndGet();
        AtomicLong bytesReceived = (AtomicLong) session.getAttributes().computeIfAbsent("asr.bytesReceived",
                k -> new AtomicLong(0));
        long totalBytes = bytesReceived.addAndGet(pcm.length);

        // 帧间间隔
        Long lastFrameTime = (Long) session.getAttributes().get("asr.lastBinaryReceiveTime");
        long intervalMs = lastFrameTime != null ? receiveTimeMs - lastFrameTime : -1;
        session.getAttributes().put("asr.lastBinaryReceiveTime", receiveTimeMs);

        String sessionId = session.getId();

        // 每帧或前10帧打印详细信息
        if (frameCount <= 10 || frameCount % 100 == 0) {
            log.info("[WS-BINARY] ============================");
            log.info("[WS-BINARY] sessionId={} frame#{}", sessionId, frameCount);
            log.info("[WS-BINARY] sessionId={} rawSize={}bytes pcmSize={}bytes startSample={}",
                    sessionId, raw.length, pcm.length, startSamplePos);
            log.info("[WS-BINARY] sessionId={} receiveTime={}ms totalBytes={}",
                    sessionId, totalBytes, totalBytes);
            log.info("[WS-BINARY] sessionId={} intervalSinceLast={}ms", sessionId, intervalMs);
            log.info("[WS-BINARY] sessionId={} parseNs={} totalNs={}", sessionId, parseNs, totalParseNs);
            log.info("[WS-BINARY] ============================");
        } else {
            log.debug("[WS-BINARY] sessionId={} frame#{} pcmSize={} parseNs={}",
                    sessionId, frameCount, pcm.length, totalParseNs);
        }

        // 直接发送给 SDK，无攒批
        long beforeSdkNs = System.nanoTime();
        sdkWrapper.sendAudioFrame(sessionId, pcm);
        long afterSdkNs = System.nanoTime();
        long sdkSendNs = afterSdkNs - beforeSdkNs;

        log.debug("[WS-BINARY] sessionId={} frame#{} SDK调用耗时={}ns ({:.3f}ms)",
                sessionId, frameCount, sdkSendNs, sdkSendNs / 1_000_000.0);
    }

    // ─── 文本消息（术语表等）─────────────────────────────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String action = root.path("action").asText("");
            if ("setGlossary".equals(action)) {
                String glossary = root.path("glossary").asText("");
                String context = root.path("context").asText("");
                session.getAttributes().put(ATTR_GLOSSARY, glossary);
                session.getAttributes().put(ATTR_CONTEXT, context);
                log.info("[增强] 收到术语表({}字) 上下文({}字)", glossary.length(), context.length());
            } else if ("setListenLang".equals(action)) {
                String lang = root.path("lang").asText("").trim().toLowerCase();
                if (ALL_LANGS.contains(lang)) {
                    session.getAttributes().put(ATTR_LISTEN_LANG, lang);
                    log.info("[收听] 优先翻译/TTS 语言={}", lang);
                }
            } else if ("broadcastPlayback".equals(action)) {
                String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);
                Integer segIdx = root.path("segIdx").asInt(-1);
                String lang = root.path("lang").asText("");
                Integer floor = root.path("floor").asInt(1);
                if (roomId != null && segIdx >= 0 && lang != null && roomWebSocketHandler != null) {
                    roomWebSocketHandler.broadcastPlaybackEvent(roomId, "playback_sync", segIdx, lang, floor);
                }
            }
        } catch (Exception e) {
            log.debug("[WS] 文本消息解析失败: {}", e.getMessage());
        }
    }

    // ─── 连接关闭 ──────────────────────────────────────────────────────────────

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        long closeStartMs = System.currentTimeMillis();
        long closeStartNs = System.nanoTime();
        String sessionId = session.getId();

        String user = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USERNAME);
        int floor = (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);
        String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);

        // 统计信息
        AtomicLong framesReceived = (AtomicLong) session.getAttributes().get("asr.framesReceived");
        AtomicLong bytesReceived = (AtomicLong) session.getAttributes().get("asr.bytesReceived");
        AtomicLong resultsReceived = (AtomicLong) session.getAttributes().get("asr.resultsReceived");
        AtomicLong segmentsEmitted = (AtomicLong) session.getAttributes().get("asr.segmentsEmitted");

        log.info("[ASR-CLOSE] ===========================================");
        log.info("[ASR-CLOSE] sessionId={} WebSocket 连接关闭", sessionId);
        log.info("[ASR-CLOSE] sessionId={} user={} floor={} roomId={}", sessionId, user, floor, roomId);
        log.info("[ASR-CLOSE] sessionId={} closeStatus={}", sessionId, status.toString());
        log.info("[ASR-STATS] sessionId={} framesReceived={} bytesReceived={}", sessionId,
                framesReceived != null ? framesReceived.get() : "N/A",
                bytesReceived != null ? bytesReceived.get() : "N/A");
        log.info("[ASR-STATS] sessionId={} resultsReceived={} segmentsEmitted={}", sessionId,
                resultsReceived != null ? resultsReceived.get() : "N/A",
                segmentsEmitted != null ? segmentsEmitted.get() : "N/A");
        log.info("[ASR-CLOSE] ===========================================");

        // 停止 SDK
        long sdkStopStartNs = System.nanoTime();
        sdkWrapper.stop(sessionId);
        long sdkStopNs = System.nanoTime() - sdkStopStartNs;
        log.info("[ASR-CLOSE] sessionId={} SDK 停止耗时={}ns", sessionId, sdkStopNs);

        // 取消切段定时器
        long timerCancelStartNs = System.nanoTime();
        ScheduledFuture<?> timer = (ScheduledFuture<?>) session.getAttributes().remove(ATTR_SEG_TIMER);
        if (timer != null) {
            boolean cancelled = timer.cancel(false);
            log.info("[ASR-CLOSE] sessionId={} 切段定时器已取消 cancelled={}", sessionId, cancelled);
        }
        long timerCancelNs = System.nanoTime() - timerCancelStartNs;
        log.info("[ASR-CLOSE] sessionId={} 定时器取消耗时={}ns", sessionId, timerCancelNs);

        // 刷新剩余 segment
        long segFlushStartNs = System.nanoTime();
        SegmentationEngine segEngine = (SegmentationEngine) session.getAttributes().remove(ATTR_SEG_ENGINE);
        AtomicLong batchCounter = (AtomicLong) session.getAttributes().remove(ATTR_SEG_BATCH_COUNTER);
        int flushedCount = 0;
        if (segEngine != null) {
            List<SegmentationEngine.Segment> remaining = segEngine.flushRemaining("", floor);
            flushedCount = remaining.size();
            if (flushedCount > 0) {
                long segmentBatchId = batchCounter != null ? batchCounter.incrementAndGet() : System.nanoTime();
                log.info("[ASR-CLOSE] sessionId={} 刷新剩余 {} 个 segment", sessionId, flushedCount);
                for (SegmentationEngine.Segment s : remaining) {
                    sendSegmentEvent(session, s, segmentBatchId);
                }
            }
        }
        long segFlushNs = System.nanoTime() - segFlushStartNs;
        log.info("[ASR-CLOSE] sessionId={} 刷新 segment 耗时={}ns count={}", sessionId, segFlushNs, flushedCount);

        // 清理队列
        long queueStopStartNs = System.nanoTime();
        AsrClientTextOutboundQueue.stop(session);
        long queueStopNs = System.nanoTime() - queueStopStartNs;
        log.info("[ASR-CLOSE] sessionId={} 队列停止耗时={}ns", sessionId, queueStopNs);

        // 清理其他属性
        session.getAttributes().remove(ATTR_LISTEN_TTS_CHAIN);
        session.getAttributes().remove(ATTR_LISTEN_TTS_STARTED_CHAIN);

        // 通知房间
        if (StringUtils.hasText(roomId) && roomWebSocketHandler != null) {
            log.info("[ASR-CLOSE] sessionId={} 通知房间 hostAsrStatus=stopped roomId={}", sessionId, roomId);
            roomWebSocketHandler.notifyListenersHostAsrStatus(roomId, "stopped");
        }

        long totalCloseNs = System.nanoTime() - closeStartNs;
        long totalCloseMs = System.currentTimeMillis() - closeStartMs;
        log.info("[ASR-CLOSE-COMPLETE] ===========================================");
        log.info("[ASR-CLOSE-COMPLETE] sessionId={} 连接关闭完成", sessionId);
        log.info("[ASR-CLOSE-COMPLETE] sessionId={} 耗时 breakdown:", sessionId);
        log.info("[ASR-CLOSE-COMPLETE] sessionId={}   SDK停止={}ns", sessionId, sdkStopNs);
        log.info("[ASR-CLOSE-COMPLETE] sessionId={}   定时器取消={}ns", sessionId, timerCancelNs);
        log.info("[ASR-CLOSE-COMPLETE] sessionId={}   Segment刷新={}ns", sessionId, segFlushNs);
        log.info("[ASR-CLOSE-COMPLETE] sessionId={}   队列停止={}ns", sessionId, queueStopNs);
        log.info("[ASR-CLOSE-COMPLETE] sessionId={}   总耗时={}ns ({:.3f}ms)", sessionId, totalCloseNs, totalCloseMs);
        log.info("[ASR-CLOSE-COMPLETE] ===========================================");

        super.afterConnectionClosed(session, status);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 以下为保留的原有逻辑：切段、翻译、TTS、房间广播
    // ═══════════════════════════════════════════════════════════════════════════════

    // ─── 语言规范化 ────────────────────────────────────────────────────────────

    private static String normalizeLang(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String lower = raw.trim().toLowerCase();
        if (lower.startsWith("zh") || "cn".equals(lower) || "chinese".equals(lower)) return "zh";
        if (lower.startsWith("en") || "english".equals(lower)) return "en";
        if (lower.startsWith("id") || lower.startsWith("in") || "indonesian".equals(lower) || "malay".equals(lower)) return "id";
        return lower;
    }

    private String resolveDetectedLang(String rawAsrLang, String text, String sessionHintLang) {
        String a = normalizeLang(rawAsrLang);
        if (!a.isEmpty()) return a;
        String fastTextLang = languageDetectionService.detect(text);
        if (!fastTextLang.isEmpty()) return fastTextLang;
        String h = normalizeLang(sessionHintLang);
        if (!h.isEmpty()) return h;
        return inferTrilingualFromText(text);
    }

    private static int countCjkChars(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf)) n++;
        }
        return n;
    }

    private static int countLatinLetters(String s) {
        if (s == null || s.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) n++;
        }
        return n;
    }

    private static boolean looksIndonesianHeuristic(String t) {
        if (t == null || t.isBlank()) return false;
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        String padded = " " + lower + " ";
        String[] hints = {" yang ", " dan ", " tidak ", " ada ", " dengan ", " untuk ", " dari ", " ini ", " itu ",
                " atau ", " juga ", " kita ", " mereka ", " anda ", " telah ", " sudah ", " akan ",
                " pada ", " oleh ", " seperti ", " lebih ", " banyak ", " antara "};
        for (String h : hints) {
            if (padded.contains(h)) return true;
        }
        return lower.startsWith("di ") || lower.startsWith("ke ") || lower.startsWith("yang ") || lower.startsWith("ada ");
    }

    private static String inferTrilingualFromText(String text) {
        if (text == null || text.isBlank()) return "zh";
        String t = text.trim();
        int cjk = countCjkChars(t);
        int latin = countLatinLetters(t);
        if (cjk >= 1 && (cjk >= 2 || latin == 0 || cjk * 3 >= latin)) return "zh";
        if (latin >= 2 && cjk == 0) return looksIndonesianHeuristic(t) ? "id" : "en";
        return "zh";
    }

    private static String smoothDetectedLang(WebSocketSession session, String candidate, String text, String prevDetectedLang) {
        String lock = normalizeLang((String) session.getAttributes().getOrDefault(ATTR_LANG_LOCK, ""));
        String cand = normalizeLang(candidate);
        if (cand.isEmpty()) cand = "zh";

        if (lock.isEmpty()) {
            session.getAttributes().put(ATTR_LANG_LOCK, cand);
            session.getAttributes().put(ATTR_PREV_SEG_LANG, cand);
            session.getAttributes().put(ATTR_SEG_LANG_SWITCHED, false);
            return cand;
        }

        if (lock.equals(cand)) {
            session.getAttributes().put(ATTR_PREV_SEG_LANG, cand);
            session.getAttributes().put(ATTR_SEG_LANG_SWITCHED, false);
            return lock;
        }

        String t = text == null ? "" : text.trim();
        if (t.length() <= 2) return lock;

        Boolean alreadySwitched = (Boolean) session.getAttributes().getOrDefault(ATTR_SEG_LANG_SWITCHED, false);
        if (Boolean.TRUE.equals(alreadySwitched)) {
            session.getAttributes().put(ATTR_PREV_SEG_LANG, cand);
            return lock;
        }

        String prevLang = normalizeLang((String) session.getAttributes().getOrDefault(ATTR_PREV_SEG_LANG, lock));
        if (!cand.equals(prevLang)) {
            session.getAttributes().put(ATTR_LANG_LOCK, cand);
            session.getAttributes().put(ATTR_PREV_SEG_LANG, cand);
            session.getAttributes().put(ATTR_SEG_LANG_SWITCHED, true);
            return cand;
        }

        return lock;
    }

    private static String canonicalizeTrilingual(String detectedLang) {
        return normalizeLang(detectedLang);
    }

    private int inputSampleRateForSession(WebSocketSession session) {
        return asrProperties.getDashscope().getSampleRate();
    }

    // ─── 流式处理入口 ──────────────────────────────────────────────────────────

    private void processSegmentStreaming(WebSocketSession session, SegmentationEngine.Segment seg, long segmentBatchId) {
        sendSegmentEvent(session, seg, segmentBatchId);
    }

    // ─── 发送 Segment 事件 ─────────────────────────────────────────────────────

    private void sendSegmentEvent(WebSocketSession session, SegmentationEngine.Segment seg, long segmentBatchId) {
        String sessionId = session.getId();
        long eventStartNs = System.nanoTime();
        String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);

        // 统计
        AtomicLong segsEmitted = (AtomicLong) session.getAttributes().computeIfAbsent("asr.segmentsEmitted",
                k -> new AtomicLong(0));
        long segCount = segsEmitted.incrementAndGet();

        try {
            session.getAttributes().put(ATTR_SEG_LANG_SWITCHED, false);

            PipelineStageLogger.get(session).stage(session, -1, PipelineStageLogger.Stage.SEG_EMIT);

            log.info("[SEG-EMIT] ============================");
            log.info("[SEG-EMIT] sessionId={} segment#{}", sessionId, segCount);
            log.info("[SEG-EMIT] sessionId={} engineIdx={} text=\"{}\"", sessionId, seg.index(), truncate(seg.text(), 80));
            log.info("[SEG-EMIT] sessionId={} lang={} floor={} confidence={}", sessionId, seg.language(), seg.floor(), seg.confidence());
            log.info("[SEG-EMIT] sessionId={} batchId={}", sessionId, segmentBatchId);
            log.info("[SEG-EMIT] ============================");

            String hint = (String) session.getAttributes().getOrDefault(ATTR_LAST_ASR_LANG, "");
            String candidateLang = resolveDetectedLang(seg.language(), seg.text(), hint);
            String detectedLang = smoothDetectedLang(session, candidateLang, seg.text(), null);
            String canonicalSource = canonicalizeTrilingual(detectedLang);

            long serverTs = System.currentTimeMillis();
            Map<String, Object> m = new HashMap<>();
            m.put("event", "segment");
            m.put("index", -1);
            m.put("text", seg.text());
            m.put("detectedLang", detectedLang);
            m.put("sourceLang", canonicalSource);
            m.put("lang", canonicalSource);
            m.put("textRole", "source");
            m.put("isSourceText", true);
            m.put("language", seg.language());
            m.put("confidence", seg.confidence());
            m.put("floor", seg.floor());
            m.put("serverTs", serverTs);
            m.put("segmentTs", segmentBatchId);
            m.put("inputSampleRate", inputSampleRateForSession(session));

            Map<String, Object> tuningSnapshot = new HashMap<>();
            tuningSnapshot.put("segMaxChars", tuningParams.getSegMaxChars());
            tuningSnapshot.put("segEnMaxCharsMultiplier", tuningParams.getSegEnMaxCharsMultiplier());
            tuningSnapshot.put("segEnMaxCharsMin", tuningParams.getSegEnMaxCharsMin());
            tuningSnapshot.put("segEnMaxCharsMax", tuningParams.getSegEnMaxCharsMax());
            tuningSnapshot.put("segSoftBreakChars", tuningParams.getSegSoftBreakChars());
            tuningSnapshot.put("segFlushTimeoutMs", tuningParams.getSegFlushTimeoutMs());
            m.put("tuning", tuningSnapshot);

            // 写入房间注册表
            int regSegIdx;
            if (roomId != null && segmentRegistry != null) {
                RoomSegmentRegistry.RoomRegistry reg = segmentRegistry.getOrCreate(roomId);
                RoomSegmentRegistry.SegmentRecord rec = reg.registerSegment(
                        seg.text(), canonicalSource, seg.confidence(), seg.floor(), serverTs, segmentBatchId);
                regSegIdx = rec.getSegIndex();

                log.info("[SEG-REGISTER] time={} segIdx={} text=\"{}\" detectedLang={} canonical={} floor={} batchId={}",
                        serverTs, regSegIdx, truncate(seg.text(), 60), detectedLang, canonicalSource, seg.floor(), segmentBatchId);

                AtomicInteger latestSeg = (AtomicInteger) session.getAttributes().get(ATTR_LATEST_SEG_IDX);
                if (latestSeg != null) latestSeg.set(regSegIdx);

                session.getAttributes().put("asr.currentBatchId", segmentBatchId);
                session.getAttributes().put(ATTR_CURRENT_SRC_LANG, canonicalSource);

                log.info("[SEG-SENT] time={} segIdx={} text=\"{}\" detectedLang={} canonical={} floor={} batchId={}",
                        serverTs, regSegIdx, truncate(seg.text(), 60), detectedLang, canonicalSource, seg.floor(), segmentBatchId);
            } else {
                regSegIdx = -1;
                log.info("[SEG-SENT] time={} segIdx={} text=\"{}\" detectedLang={} canonical={} floor={} [no-room]",
                        serverTs, regSegIdx, truncate(seg.text(), 60), detectedLang, canonicalSource, seg.floor());
            }

            m.put("sequence", regSegIdx);
            m.put("index", regSegIdx);
            sendPayload(session, m);

            // 三语翻译
            String glossary = (String) session.getAttributes().getOrDefault(ATTR_GLOSSARY, "");
            String context = (String) session.getAttributes().getOrDefault(ATTR_CONTEXT, "");
            final String srcLang = canonicalSource;
            final int finalRegSegIdx = regSegIdx;

            List<String> allLangs = new ArrayList<>(ALL_LANGS);
            Map<String, CompletableFuture<String>> transFutures = new HashMap<>();

            for (String tgtLang : allLangs) {
                int finalSegIdx = finalRegSegIdx;
                String finalTgtLang = tgtLang;
                boolean isSameLang = tgtLang.equals(srcLang);
                if (!isSameLang) {
                    log.info("[TRANS-REQ] time={} segIdx={} srcLang={} → tgtLang={} text=\"{}\"",
                            System.currentTimeMillis(), finalSegIdx, srcLang, finalTgtLang, truncate(seg.text(), 50));
                }
                PipelineStageLogger.get(session).setTargetLangs(finalSegIdx, String.join(",", allLangs));
                PipelineStageLogger.get(session).stage(session, finalSegIdx, PipelineStageLogger.Stage.TRANSLATE_REQ);

                CompletableFuture<String> tf = CompletableFuture.supplyAsync(() -> {
                    if (isSameLang) {
                        log.info("[TRANS-SAME-LANG] segIdx={} srcLang={} → tgtLang={} 直接返回原文",
                                finalSegIdx, srcLang, finalTgtLang);
                        return seg.text();
                    }
                    AsrLatencyTrace.get(session).translateBegin(session, finalSegIdx, finalTgtLang);
                    try {
                        long llmStartNano = System.nanoTime();
                        String result = translateService.translate(seg.text(), srcLang, tgtLang, glossary, context);
                        long llmMs = (System.nanoTime() - llmStartNano) / 1_000_000L;
                        PipelineStageLogger.get(session).stage(session, finalSegIdx, PipelineStageLogger.Stage.TRANSLATE_DONE);
                        log.info("[TRANS-DONE] time={} segIdx={} srcLang={} → tgtLang={} elapsed={}ms original=\"{}\" translated=\"{}\"",
                                System.currentTimeMillis(), finalSegIdx, srcLang, finalTgtLang, llmMs,
                                truncate(seg.text(), 60), truncate(result, 60));
                        AsrLatencyTrace.get(session).translateEndListen(session, finalSegIdx, llmStartNano, result != null);
                        return result;
                    } catch (Exception e) {
                        log.error("[TRANS-ERR] time={} segIdx={} srcLang={} → tgtLang={} error={}",
                                System.currentTimeMillis(), finalSegIdx, srcLang, finalTgtLang, e.getMessage());
                        return null;
                    }
                }, langPairPool(srcLang, tgtLang));

                transFutures.put(tgtLang, tf);
            }

            // 三语 TTS
            final CompletableFuture<Void> nextTtsStartedSignal = new CompletableFuture<>();

            for (String tgtLang : allLangs) {
                final String finalTgtLang = tgtLang;
                boolean isSameLang = srcLang.equals(tgtLang);
                log.info("[TTS-Chain-START] regSegIdx={} srcLang={} tgtLang={} ★翻译链路={}",
                        finalRegSegIdx, srcLang, finalTgtLang, isSameLang ? "同源(直接TTS原文)" : "翻译(需翻译后再TTS)");
                CompletableFuture<String> trFuture = transFutures.get(tgtLang);
                if (trFuture == null) {
                    log.warn("[TTS-Chain-NULL] regSegIdx={} →{} 无翻译Future，跳过", finalRegSegIdx, finalTgtLang);
                    continue;
                }

                CompletableFuture<Void> langTtsChain = trFuture.thenComposeAsync(translated -> {
                    if (!session.isOpen()) {
                        log.debug("[TTS-Chain] regSegIdx={} →{} session已关闭，跳过", finalRegSegIdx, finalTgtLang);
                        return CompletableFuture.completedFuture(null);
                    }
                    if (translated == null || translated.isBlank()) {
                        try {
                            log.warn("[TTS-SKIP] regSegIdx={} →{} 翻译失败，跳过TTS", finalRegSegIdx, finalTgtLang);
                            if (session.isOpen()) {
                                if (roomId != null && segmentRegistry != null) {
                                    segmentRegistry.getOrCreate(roomId).onTtsSkip(finalRegSegIdx, finalTgtLang, "translate_failed");
                                    segmentRegistry.getOrCreate(roomId).onAudioEnd(finalRegSegIdx, finalTgtLang, "translate_failed");
                                }
                            }
                        } catch (Exception ignored) {}
                        return CompletableFuture.completedFuture(null);
                    }

                    log.info("[TRANS-SENT] time={} segIdx={} srcLang={} → tgtLang={} text=\"{}\"",
                            System.currentTimeMillis(), finalRegSegIdx, srcLang, tgtLang, truncate(translated, 50));

                    Map<String, Object> tm = new HashMap<>();
                    tm.put("event", "translation");
                    tm.put("index", finalRegSegIdx);
                    tm.put("sequence", finalRegSegIdx);
                    tm.put("textRole", "translation");
                    tm.put("isSourceText", false);
                    tm.put("lang", tgtLang);
                    tm.put("detectedLang", tgtLang);
                    tm.put("sourceText", seg.text());
                    tm.put("translatedText", translated);
                    tm.put("sourceLang", srcLang);
                    tm.put("targetLang", tgtLang);
                    tm.put("floor", seg.floor());
                    long transSentWallMs = System.currentTimeMillis();
                    tm.put("serverTs", transSentWallMs);
                    tm.put("segmentTs", segmentBatchId);
                    try {
                        sendPayload(session, tm);
                    } catch (IOException e) {
                        log.debug("[翻译] 发送 translation 事件失败: {}", e.getMessage());
                    }

                    long ttsReqTs = System.currentTimeMillis();
                    PipelineStageLogger.get(session).setTtsLang(finalRegSegIdx, finalTgtLang);
                    PipelineStageLogger.get(session).stage(session, finalRegSegIdx, PipelineStageLogger.Stage.TTS_REQ);
                    log.info("[TTS-REQ] time={} segIdx={} srcLang={} → tgtLang={} text=\"{}\"",
                            ttsReqTs, finalRegSegIdx, srcLang, finalTgtLang, truncate(translated, 50));

                    double rate = tuningParams.getTtsRate();
                    AtomicInteger latest = (AtomicInteger) session.getAttributes().get(ATTR_LATEST_SEG_IDX);
                    if (latest != null) {
                        int backlog = Math.max(0, latest.get() - finalRegSegIdx);
                        double backlogIncrease = backlog * tuningParams.getTtsBacklogCoeff();
                        double cap = tuningParams.getTtsBacklogCap();
                        if (cap > 0) backlogIncrease = Math.min(backlogIncrease, cap);
                        rate = tuningParams.getTtsRate() + backlogIncrease;
                    }
                    rate = Math.max(0.5, Math.min(2.0, rate));

                    log.info("[TTS-START] time={} segIdx={} srcLang={} → tgtLang={} rate={}",
                            ttsReqTs, finalRegSegIdx, srcLang, finalTgtLang, rate);

                    AsrLatencyTrace.get(session).onListenTtsInvoke(session, finalRegSegIdx);

                    boolean queued = ttsService.synthesizeStreaming(
                            translated, finalTgtLang, rate, finalRegSegIdx,
                            new RealtimeTtsService.AudioChunkCallback() {
                                private boolean firstChunk = true;
                                private int chunkCount = 0;
                                @Override
                                public void onChunk(int segIdx, String tgtLang, byte[] chunk) throws IOException {
                                    long now = System.currentTimeMillis();
                                    if (firstChunk) {
                                        firstChunk = false;
                                        long ttsFirstDelay = now - ttsReqTs;
                                        long totalDelay = now - serverTs;
                                        log.info("[TTS-FIRST-CHUNK] time={} segIdx={} srcLang={} → tgtLang={} " +
                                                "ttsDelay={}ms totalDelay={}ms size={} format=wav",
                                                now, segIdx, srcLang, tgtLang, ttsFirstDelay, totalDelay, chunk.length);
                                        PipelineStageLogger.get(session).stage(
                                                session, segIdx, PipelineStageLogger.Stage.TTS_FIRST_CHUNK, chunk.length);
                                        nextTtsStartedSignal.complete(null);
                                    }
                                    chunkCount++;
                                    log.info("[TTS-CHUNK] time={} segIdx={} srcLang={} → tgtLang={} chunk#={} size={}",
                                            now, segIdx, srcLang, tgtLang, chunkCount, chunk.length);
                                    if (roomId != null && segmentRegistry != null) {
                                        segmentRegistry.getOrCreate(roomId).onTtsChunk(segIdx, tgtLang, chunk);
                                    }
                                }
                            },
                            () -> {
                                if (roomId != null && segmentRegistry != null) {
                                    log.info("[TTS-END-AFTER-WAV] time={} segIdx={} srcLang={} → tgtLang={} ★TTS完成，发送END帧 sessionOpen={}",
                                            System.currentTimeMillis(), finalRegSegIdx, srcLang, finalTgtLang, session.isOpen());
                                    segmentRegistry.getOrCreate(roomId).onAudioEnd(finalRegSegIdx, finalTgtLang, "done");
                                }
                            });
                    long ttsEndTs = System.currentTimeMillis();
                    if (!queued && session.isOpen()) {
                        log.warn("[TTS-SKIP] time={} segIdx={} srcLang={} → tgtLang={} reason=no_audio",
                                ttsEndTs, finalRegSegIdx, srcLang, finalTgtLang);
                        if (roomId != null && segmentRegistry != null) {
                            segmentRegistry.getOrCreate(roomId).onTtsSkip(finalRegSegIdx, finalTgtLang, "no_audio");
                            segmentRegistry.getOrCreate(roomId).onAudioEnd(finalRegSegIdx, finalTgtLang, "no_audio");
                        }
                    }
                    PipelineStageLogger.get(session).stage(session, finalRegSegIdx, PipelineStageLogger.Stage.TTS_DONE);
                    return CompletableFuture.completedFuture(null);
                }, langPairPool(srcLang, tgtLang));

                langTtsChain.exceptionally(ex -> {
                    long excTs = System.currentTimeMillis();
                    log.error("[TTS-EXCEPTION] time={} segIdx={} srcLang={} → tgtLang={} exception={}",
                            excTs, finalRegSegIdx, srcLang, finalTgtLang, ex.getMessage());
                    try {
                        if (session.isOpen()) {
                            if (roomId != null && segmentRegistry != null) {
                                segmentRegistry.getOrCreate(roomId).onTtsSkip(finalRegSegIdx, finalTgtLang, "error");
                                segmentRegistry.getOrCreate(roomId).onAudioEnd(finalRegSegIdx, finalTgtLang, "error");
                            }
                        }
                    } catch (Exception ignored) {}
                    return null;
                });
            }

            session.getAttributes().put(ATTR_LISTEN_TTS_STARTED_CHAIN, nextTtsStartedSignal);
        } catch (IOException e) {
            log.debug("[切段] 发送 segment 事件失败: {}", e.getMessage());
        }
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void sendPayload(WebSocketSession session, Map<String, Object> payload) throws IOException {
        if (!session.isOpen()) return;
        final String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
        AsrClientTextOutboundQueue.enqueue(session, json);
    }

    private void relayOutboundJsonAfterHostSent(WebSocketSession session, String json) {
        try {
            forwardOutboundJsonToRoom(session, json);
        } catch (Exception e) {
            log.warn("[WS] 房间转发异常: {}", e.getMessage());
        }
    }

    private void forwardOutboundJsonToRoom(WebSocketSession session, String json) throws IOException {
        if (roomWebSocketHandler == null) return;
        String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);
        if (!StringUtils.hasText(roomId)) return;
        JsonNode root = objectMapper.readTree(json);
        String event = root.path("event").asText("");
        if (event.isEmpty() || !ROOM_FORWARD_EVENTS.contains(event)) return;
        log.info("[ForwardToRoom] event={} index={} targetLang={} roomId={}",
                event, root.path("index").toString(), root.path("targetLang").asText(""), roomId);
        roomWebSocketHandler.broadcastJsonToListeners(roomId, json);
        if ("ready".equals(event)) {
            roomWebSocketHandler.notifyListenersHostAsrStatus(roomId, "started");
        }
        if ("playback_start".equals(event) && root.hasNonNull("segIdx") && root.hasNonNull("lang")) {
            int segIdx = root.path("segIdx").asInt();
            String lang = root.path("lang").asText("");
            int floor = root.path("floor").asInt(1);
            if (!lang.isEmpty()) {
                roomWebSocketHandler.broadcastPlaybackEvent(roomId, "playback_start", segIdx, lang, floor);
            }
        } else if ("playback_end".equals(event) && root.hasNonNull("segIdx") && root.hasNonNull("lang")) {
            int segIdx = root.path("segIdx").asInt();
            String lang = root.path("lang").asText("");
            int floor = root.path("floor").asInt(1);
            if (!lang.isEmpty()) {
                roomWebSocketHandler.broadcastPlaybackEvent(roomId, "playback_end", segIdx, lang, floor);
            }
        }
    }

    private void sendAsrError(WebSocketSession session, String code, String message, String detail) throws IOException {
        Map<String, Object> m = new HashMap<>();
        m.put("event", "error");
        m.put("code", code);
        m.put("message", message);
        if (StringUtils.hasText(detail)) {
            m.put("detail", detail);
        }
        log.warn("[ASR] 发送错误到客户端: code={} message={}", code, message);
        sendPayload(session, m);
    }
}
