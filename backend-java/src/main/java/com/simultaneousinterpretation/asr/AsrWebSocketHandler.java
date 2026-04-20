package com.simultaneousinterpretation.asr;

import com.alibaba.dashscope.audio.asr.translation.results.TranscriptionResult;
import com.alibaba.dashscope.audio.asr.translation.results.TranslationRecognizerResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.common.ResultCallback;
import io.reactivex.Flowable;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simultaneousinterpretation.config.AsrProperties;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.config.TtsProperties;
import com.simultaneousinterpretation.meeting.RoomSegmentRegistry;
import com.simultaneousinterpretation.meeting.RoomWebSocketHandler;
import com.simultaneousinterpretation.service.LanguageDetectionService;
import com.simultaneousinterpretation.service.TtsConnectionPool;
import com.simultaneousinterpretation.service.AiTranslateService;
import com.simultaneousinterpretation.api.dto.TranslateRequest;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ASR WebSocket 处理器
 *
 * <p>流程：
 * <ol>
 *   <li>前端 PCM → DashScope SDK</li>
 *   <li>SDK partial 回调 → 广播 transcript{partial:true}</li>
 *   <li>SDK final 回调 → 检测语言 → 广播 segment 事件 → 翻译 → TTS（使用连接池）</li>
 * </ol>
 */
@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

    private static final String ATTR_GLOSSARY         = "asr.glossary";
    private static final String ATTR_CONTEXT          = "asr.context";
    private static final String ATTR_LISTEN_LANG      = "asr.listenLang";
    private static final String ATTR_CURRENT_SRC_LANG = "asr.currentSrcLang";

    private static final List<String> ALL_LANGS = List.of("zh", "en", "id");

    /** 转发给房间听众的事件类型 */
    private static final Set<String> ROOM_FORWARD_EVENTS =
            Set.of("segment", "transcript", "translation", "error", "ready");

    private final AsrProperties asrProperties;
    private final DashScopeSdkWrapper sdkWrapper;
    private final ObjectMapper objectMapper;
    private final RoomWebSocketHandler roomWebSocketHandler;
    private final RoomSegmentRegistry segmentRegistry;
    private final LanguageDetectionService languageDetectionService;
    private final TtsConnectionPool ttsConnectionPool;
    private final AiTranslateService translateService;
    private final TtsProperties ttsProperties;
    private final DashScopeProperties dashScopeProperties;

    /**
     * 每种语言独立的单线程执行器。
     *
     * <p>关键设计：同一语言的 TTS 任务天然串行执行，杜绝两段音频帧交错发送
     * （即"两个声音同时播放"问题的根因）。不同语言之间保持并发。
     */
    private final Map<String, ExecutorService> langExecutors = Map.of(
            "zh", Executors.newSingleThreadExecutor(r -> new Thread(r, "tts-zh")),
            "en", Executors.newSingleThreadExecutor(r -> new Thread(r, "tts-en")),
            "id", Executors.newSingleThreadExecutor(r -> new Thread(r, "tts-id"))
    );

    /** 翻译专用线程池（不含 TTS，用于并行调翻译 API） */
    private final ExecutorService translateExecutor = Executors.newFixedThreadPool(6);

    /** roomId -> 主持端 ASR WebSocket session（用于向主持端发送翻译事件） */
    private final Map<String, WebSocketSession> hostAsrSessions = new java.util.concurrent.ConcurrentHashMap<>();

    public AsrWebSocketHandler(
            AsrProperties asrProperties,
            DashScopeSdkWrapper sdkWrapper,
            ObjectMapper objectMapper,
            RoomWebSocketHandler roomWebSocketHandler,
            RoomSegmentRegistry segmentRegistry,
            LanguageDetectionService languageDetectionService,
            TtsConnectionPool ttsConnectionPool,
            AiTranslateService translateService,
            TtsProperties ttsProperties,
            DashScopeProperties dashScopeProperties) {
        this.asrProperties = asrProperties;
        this.sdkWrapper = sdkWrapper;
        this.objectMapper = objectMapper;
        this.roomWebSocketHandler = roomWebSocketHandler;
        this.segmentRegistry = segmentRegistry;
        this.languageDetectionService = languageDetectionService;
        this.ttsConnectionPool = ttsConnectionPool;
        this.translateService = translateService;
        this.ttsProperties = ttsProperties;
        this.dashScopeProperties = dashScopeProperties;
    }

    // ─── 连接建立 ─────────────────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        long startMs = System.currentTimeMillis();
        String sessionId = session.getId();

        String user  = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USERNAME);
        int floor    = (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);
        String roomId = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_ROOM_ID);

        log.info("[ASR-CONNECT] sessionId={} user={} floor={} roomId={}", sessionId, user, floor, roomId);

        // 保存主持端 session，用于向主持端发送翻译事件
        if (roomId != null) {
            hostAsrSessions.put(roomId, session);
            log.info("[ASR-SESSION] 保存主持端 session roomId={} sessionId={}", roomId, sessionId);
        }

        AsrClientTextOutboundQueue.start(session, this::relayOutboundJsonAfterHostSent);

        session.getAttributes().put("asr.framesReceived",  new AtomicLong(0));
        session.getAttributes().put("asr.bytesReceived",   new AtomicLong(0));
        session.getAttributes().put("asr.resultsReceived", new AtomicLong(0));
        session.getAttributes().put("asr.segmentsEmitted", new AtomicLong(0));

        boolean started = sdkWrapper.start(
                sessionId,
                result -> handleSdkResult(session, result),
                error -> {
                    try {
                        sendAsrError(session, "SDK_ERROR", "ASR 服务错误", error.getMessage());
                    } catch (IOException ignored) {}
                },
                () -> log.info("[SDK] sessionId={} 识别完成 持续={}ms",
                        sessionId, System.currentTimeMillis() - startMs)
        );

        if (!started) {
            log.error("[ASR-ERROR] sessionId={} SDK 启动失败，关闭连接", sessionId);
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        Map<String, Object> ready = new HashMap<>();
        ready.put("event", "ready");
        ready.put("floor", floor);
        ready.put("sampleRate", 16000);
        ready.put("provider", "dashscope-sdk");
        ready.put("serverTimeMs", System.currentTimeMillis());
        try {
            sendPayload(session, ready);
        } catch (IOException e) {
            log.error("[ASR-READY] ready 事件发送失败", e);
        }

        log.info("[ASR-CONNECT] sessionId={} 连接建立完成 耗时={}ms",
                sessionId, System.currentTimeMillis() - startMs);
    }

    // ─── SDK 回调 ─────────────────────────────────────────────────────────────

    private void handleSdkResult(WebSocketSession session, TranslationRecognizerResult result) {
        String sessionId = session.getId();
        int floor  = (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);
        String roomId = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_ROOM_ID);

        TranscriptionResult transcription = result.getTranscriptionResult();
        if (transcription == null) return;

        String text = transcription.getText();
        if (!StringUtils.hasText(text)) return;

        boolean isFinal = result.isSentenceEnd();

        // ── 始终广播 transcript（partial 或 final）给主持端 + 听众端 ──────────
        String cachedLang = (String) session.getAttributes().getOrDefault(ATTR_CURRENT_SRC_LANG, "zh");

        Map<String, Object> transcriptPayload = new HashMap<>();
        transcriptPayload.put("event", "transcript");
        transcriptPayload.put("partial", !isFinal);
        transcriptPayload.put("text", text);
        transcriptPayload.put("language", cachedLang);
        transcriptPayload.put("confidence", 1.0);
        transcriptPayload.put("floor", floor);

        try {
            sendPayload(session, transcriptPayload);
        } catch (IOException e) {
            log.error("[ASR] sessionId={} 发送 transcript 失败", sessionId, e);
            return;
        }

        if (!isFinal) return;

        // ── Final：语言检测 → 注册段 → 广播 segment 事件 → 翻译+TTS（使用连接池） ──
        long langDetectStartNs = System.nanoTime();
        String detectedLang = languageDetectionService.detect(text);
        if (detectedLang == null || detectedLang.isBlank()) detectedLang = "zh";
        long langDetectMs = (System.nanoTime() - langDetectStartNs) / 1_000_000;

        long serverTs = System.currentTimeMillis();
        session.getAttributes().put(ATTR_CURRENT_SRC_LANG, detectedLang);

        int segIdx = -1;
        if (roomId != null) {
            RoomSegmentRegistry.RoomRegistry reg = segmentRegistry.getOrCreate(roomId);
            RoomSegmentRegistry.SegmentRecord rec =
                    reg.registerSegment(text, detectedLang, 1.0, floor, serverTs, serverTs);
            segIdx = rec.getSegIndex();
        }

        ((AtomicLong) session.getAttributes()
                .computeIfAbsent("asr.segmentsEmitted", k -> new AtomicLong(0))).incrementAndGet();

        Map<String, Object> segPayload = new HashMap<>();
        segPayload.put("event", "segment");
        segPayload.put("index", segIdx);
        segPayload.put("sequence", segIdx);
        segPayload.put("text", text);
        segPayload.put("sourceLang", detectedLang);
        segPayload.put("lang", detectedLang);
        segPayload.put("detectedLang", detectedLang);
        segPayload.put("textRole", "source");
        segPayload.put("isSourceText", true);
        segPayload.put("confidence", 1.0);
        segPayload.put("floor", floor);
        segPayload.put("serverTs", serverTs);
        segPayload.put("segmentTs", serverTs);
        segPayload.put("inputSampleRate", asrProperties.getDashscope().getSampleRate());

        try {
            sendPayload(session, segPayload);
        } catch (IOException e) {
            log.debug("[ASR] sessionId={} 发送 segment 失败", sessionId, e);
        }

        // [PIPE-1] ASR final 文本已就绪，准备触发流水线
        log.info("[PIPE-1] segIdx={} lang={} textLen={} langDetectMs={}ms text=\"{}\" wallMs={}",
                segIdx, detectedLang, text.length(), langDetectMs, truncate(text, 40), serverTs);

        if (roomId != null && segIdx >= 0) {
            final String finalLang = detectedLang;
            final int finalSegIdx = segIdx;
            log.info("[PIPE-2] segIdx={} sourceLang={} roomId={} wallMs={}",
                    finalSegIdx, finalLang, roomId, System.currentTimeMillis());
            triggerTranslationAndTts(text, finalLang, finalSegIdx, serverTs, roomId, floor);
        }
    }

    /**
     * 触发翻译和 TTS（连接池 + streamingCallAsFlowable 流式发送）。
     *
     * <p>每种语言的 TTS 任务提交到该语言专属的单线程执行器 {@link #langExecutors}，
     * 保证同一语言的音频帧永远串行发送，彻底消除"两段音频同时播放"问题。
     */
    private void triggerTranslationAndTts(String text, String sourceLang, int segIdx,
                                          long segmentTs, String roomId, int floor) {
        // ── 源语链：直接 TTS（提交到源语的专属执行器） ────────────────────
        ExecutorService srcExec = langExecutors.getOrDefault(sourceLang, translateExecutor);
        srcExec.submit(() -> {
            TtsConnectionPool.Lang poolLang = toPoolLang(sourceLang);
            SpeechSynthesizer synthesizer = null;
            try {
                synthesizer = ttsConnectionPool.borrow(poolLang);
                doTts(synthesizer, buildTtsParam(sourceLang), text, segIdx, sourceLang, roomId);
                roomWebSocketHandler.broadcastFramedAudioEnd(roomId, segIdx, sourceLang);
            } catch (Exception e) {
                log.error("[TTS-SOURCE] segIdx={} lang={} error={}", segIdx, sourceLang, e.getMessage());
            } finally {
                if (synthesizer != null) ttsConnectionPool.returnObject(poolLang, synthesizer);
            }
        });

        // ── 目标语链：翻译（translateExecutor 并行）→ TTS（langExecutor 串行）──
        System.out.println("[PIPE-2-DEBUG] segIdx=" + segIdx + " sourceLang=" + sourceLang + " ALL_LANGS=" + ALL_LANGS + " will translate to:");
        for (String targetLang : ALL_LANGS) {
            if (targetLang.equals(sourceLang)) {
                System.out.println("[PIPE-2-DEBUG]   skip " + targetLang + " (same as source)");
                continue;
            }
            System.out.println("[PIPE-2-DEBUG]   will translate to: " + targetLang);

            final String tgt = targetLang;
            ExecutorService tgtExec = langExecutors.getOrDefault(tgt, translateExecutor);

            // 翻译在通用线程池并行执行，完成后把 TTS 任务提交到目标语专属执行器
            CompletableFuture.supplyAsync(() -> {
                System.out.println("[PIPE-3-START] segIdx=" + segIdx + " translating from " + sourceLang + " to " + tgt);
                TranslateRequest req = new TranslateRequest();
                req.setSegment(text);
                req.setSourceLang(sourceLang);
                req.setTargetLang(tgt);
                req.setKbEnabled(false);

                long xlStart = System.currentTimeMillis();
                try {
                    String translated = translateService.translate(req).translation();
                    long xlMs = System.currentTimeMillis() - xlStart;
                    if (translated == null || translated.isBlank()) {
                        log.warn("[PIPE-3-XLAT-EMPTY] segIdx={} tgt={} durationMs={}", segIdx, tgt, xlMs);
                        return null;
                    }
                    System.out.println("[PIPE-3-XLAT-DONE] segIdx=" + segIdx + " tgt=" + tgt + " durationMs=" + xlMs + " textLen=" + translated.length() + " translated=" + translated.substring(0, Math.min(50, translated.length())));
                    log.info("[PIPE-3-XLAT-DONE] segIdx={} tgt={} durationMs={} textLen={}",
                            segIdx, tgt, xlMs, translated.length());
                    return translated;
                } catch (Exception e) {
                    System.out.println("[PIPE-3-XLAT-ERROR] segIdx=" + segIdx + " tgt=" + tgt + " error=" + e.getMessage());
                    log.error("[PIPE-3-XLAT-ERROR] segIdx={} tgt={} error={}", segIdx, tgt, e.getMessage());
                    return null;
                }
            }, translateExecutor).thenAcceptAsync(translated -> {
                if (translated == null) return;
                broadcastTranslation(roomId, segIdx, text, translated, sourceLang, tgt, floor, segmentTs);

                TtsConnectionPool.Lang poolLang = toPoolLang(tgt);
                SpeechSynthesizer synthesizer = null;
                try {
                    synthesizer = ttsConnectionPool.borrow(poolLang);
                    doTts(synthesizer, buildTtsParam(tgt), translated, segIdx, tgt, roomId);
                    roomWebSocketHandler.broadcastFramedAudioEnd(roomId, segIdx, tgt);
                } catch (Exception e) {
                    log.error("[PIPE-3-TTS] segIdx={} tgt={} error={}", segIdx, tgt, e.getMessage());
                } finally {
                    if (synthesizer != null) ttsConnectionPool.returnObject(poolLang, synthesizer);
                }
            }, tgtExec);  // TTS 和广播在目标语专属单线程执行器上执行
        }
    }

    /**
     * 构建 TTS 参数（24kHz PCM，使用 TtsProperties 中的音色配置）
     */
    private SpeechSynthesisParam buildTtsParam(String lang) {
        return SpeechSynthesisParam.builder()
                .model(ttsProperties.getModel())
                .voice(ttsProperties.getVoice(lang))
                .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                .apiKey(getApiKey())
                .build();
    }

    /**
     * 使用 streamingCallAsFlowable 流式发送文本并接收音频。
     * 相比 streamingCall，允许按语义分块发送，降低首帧延迟。
     */
    private void doTts(SpeechSynthesizer synthesizer, SpeechSynthesisParam param,
                       String text, int segIdx, String lang, String roomId) throws Exception {
        synthesizer.updateParamAndCallback(param, NOOP_CALLBACK);
        synthesizer.streamingCallAsFlowable(Flowable.just(text))
                .blockingForEach(result -> {
                    ByteBuffer audio = result.getAudioFrame();
                    if (audio != null && audio.hasRemaining()) {
                        byte[] bytes = new byte[audio.remaining()];
                        audio.get(bytes);
                        roomWebSocketHandler.broadcastFramedAudioChunk(roomId, segIdx, 0, lang, bytes);
                    }
                });
    }

    /** streamingCallAsFlowable 不走 callback 路径，用 noop 避免 NPE */
    private static final ResultCallback<SpeechSynthesisResult> NOOP_CALLBACK =
            new ResultCallback<>() {
                @Override public void onEvent(SpeechSynthesisResult r) {}
                @Override public void onComplete() {}
                @Override public void onError(Exception e) {}
            };

    private TtsConnectionPool.Lang toPoolLang(String lang) {
        if ("zh".equalsIgnoreCase(lang)) return TtsConnectionPool.Lang.ZH;
        else if ("en".equalsIgnoreCase(lang)) return TtsConnectionPool.Lang.EN;
        else return TtsConnectionPool.Lang.ID;
    }

    private String getApiKey() {
        String key = ttsProperties.getApiKey();
        if (key == null || key.isEmpty()) {
            key = dashScopeProperties.getApiKey();
        }
        return key;
    }

    private void broadcastTranslation(String roomId, int segIdx, String sourceText,
                                     String translatedText, String sourceLang, String targetLang,
                                     int floor, long segmentTs) {
        try {
            Map<String, Object> ev = new HashMap<>();
            ev.put("event", "translation");
            ev.put("index", segIdx);
            ev.put("sequence", segIdx);
            ev.put("sourceText", sourceText);
            ev.put("translatedText", translatedText);
            ev.put("sourceLang", sourceLang);
            ev.put("targetLang", targetLang);
            ev.put("lang", targetLang);
            ev.put("detectedLang", targetLang);
            ev.put("textRole", "translation");
            ev.put("isSourceText", false);
            ev.put("floor", floor);
            ev.put("segmentTs", segmentTs);
            ev.put("serverTs", System.currentTimeMillis());

            String evJson = objectMapper.writeValueAsString(ev);
            sendToHost(roomId, evJson);
            roomWebSocketHandler.broadcastJsonToAll(roomId, evJson);
        } catch (JsonProcessingException e) {
            log.error("[PIPE-3-BROADCAST-ERR] segIdx={} tgt={} error={}", segIdx, targetLang, e.getMessage());
        }
    }

    // ─── 二进制消息（PCM 音频）────────────────────────────────────────────────

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        String sessionId = session.getId();
        ByteBuffer payload = message.getPayload();
        byte[] raw = new byte[payload.remaining()];
        payload.get(raw);

        // 跳过 8 字节时间戳前缀
        byte[] pcm;
        if (raw.length >= 10) {
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
            bb.getLong();
            pcm = new byte[raw.length - 8];
            bb.get(pcm);
        } else {
            pcm = raw;
        }

        AtomicLong frames = (AtomicLong) session.getAttributes()
                .computeIfAbsent("asr.framesReceived", k -> new AtomicLong(0));
        long frameCount = frames.incrementAndGet();
        ((AtomicLong) session.getAttributes()
                .computeIfAbsent("asr.bytesReceived", k -> new AtomicLong(0))).addAndGet(pcm.length);

        if (frameCount <= 10 || frameCount % 100 == 0) {
            log.info("[WS-BINARY] sessionId={} frame#{} pcmSize={}", sessionId, frameCount, pcm.length);
        }

        sdkWrapper.sendAudioFrame(sessionId, pcm);
    }

    // ─── 文本消息（控制指令）──────────────────────────────────────────────────

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String action = root.path("action").asText("");
            if ("setGlossary".equals(action)) {
                String glossary = root.path("glossary").asText("");
                String context  = root.path("context").asText("");
                session.getAttributes().put(ATTR_GLOSSARY, glossary);
                session.getAttributes().put(ATTR_CONTEXT, context);
                log.info("[增强] 术语表({}字) 上下文({}字)", glossary.length(), context.length());
            } else if ("setListenLang".equals(action)) {
                String lang = root.path("lang").asText("").trim().toLowerCase();
                if (ALL_LANGS.contains(lang)) {
                    session.getAttributes().put(ATTR_LISTEN_LANG, lang);
                    log.info("[收听] 优先语言={}", lang);
                }
            }
        } catch (Exception e) {
            log.debug("[WS] 文本消息解析失败: {}", e.getMessage());
        }
    }

    // ─── 连接关闭 ─────────────────────────────────────────────────────────────

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        String roomId = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_ROOM_ID);

        AtomicLong framesReceived = (AtomicLong) session.getAttributes().get("asr.framesReceived");
        AtomicLong segsEmitted    = (AtomicLong) session.getAttributes().get("asr.segmentsEmitted");
        log.info("[ASR-CLOSE] sessionId={} status={} frames={} segs={}",
                sessionId, status,
                framesReceived != null ? framesReceived.get() : 0,
                segsEmitted    != null ? segsEmitted.get()    : 0);

        sdkWrapper.stop(sessionId);
        AsrClientTextOutboundQueue.stop(session);

        // 清理主持端 session
        if (StringUtils.hasText(roomId)) {
            hostAsrSessions.remove(roomId);
            log.info("[ASR-SESSION] 清理主持端 session roomId={}", roomId);
        }

        if (roomWebSocketHandler != null) {
            roomWebSocketHandler.notifyListenersHostAsrStatus(roomId, "stopped");
        }

        super.afterConnectionClosed(session, status);
    }

    // ─── 公共方法 ─────────────────────────────────────────────────────────────

    /**
     * 向主持端的 ASR WebSocket 发送 JSON 消息。
     */
    public boolean sendToHost(String roomId, String json) {
        WebSocketSession session = hostAsrSessions.get(roomId);
        if (session == null) {
            log.warn("[ASR-SEND-TO-HOST] roomId={} 主持端 session 不存在", roomId);
            return false;
        }
        if (!session.isOpen()) {
            log.warn("[ASR-SEND-TO-HOST] roomId={} 主持端 session 已关闭", roomId);
            hostAsrSessions.remove(roomId);
            return false;
        }
        try {
            // 通过队列发送，避免多线程（tts-*、translateExecutor）与队列消费线程并发写同一 session
            // 直接调用 session.sendMessage() 会引发 TEXT_PARTIAL_WRITING，导致队列线程死亡
            AsrClientTextOutboundQueue.enqueue(session, json);
            log.info("[ASR-SEND-TO-HOST] ★ 已入队到主持端 roomId={} sessionId={} size={}",
                    roomId, session.getId(), json.length());
            return true;
        } catch (IOException e) {
            log.error("[ASR-SEND-TO-HOST] 入队失败 roomId={}: {}", roomId, e.getMessage());
            return false;
        }
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

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
        long entryNs = System.nanoTime();
        try {
            forwardOutboundJsonToRoom(session, json);
        } catch (Exception e) {
            log.warn("[WS-FORWARD] 房间转发异常: {}", e.getMessage());
        }
        long totalNs = System.nanoTime() - entryNs;
        try {
            String event = objectMapper.readTree(json).path("event").asText("");
            if ("transcript".equals(event)) {
                log.info("[WS-FORWARD] ★ sessionId={} forward完成 totalNs={} event={}", session.getId(), totalNs, event);
            }
        } catch (Exception ignored) {}
    }

    private void forwardOutboundJsonToRoom(WebSocketSession session, String json) throws IOException {
        long entryNs = System.nanoTime();
        if (roomWebSocketHandler == null) return;
        String roomId = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_ROOM_ID);
        if (!StringUtils.hasText(roomId)) return;
        JsonNode root = objectMapper.readTree(json);
        String event = root.path("event").asText("");
        if (event.isEmpty() || !ROOM_FORWARD_EVENTS.contains(event)) return;
        log.info("[ForwardToRoom] event={} index={} roomId={}",
                event, root.path("index").toString(), roomId);
        roomWebSocketHandler.broadcastJsonToListeners(roomId, json);
        if ("ready".equals(event)) {
            roomWebSocketHandler.notifyListenersHostAsrStatus(roomId, "started");
        }
    }

    private void sendAsrError(WebSocketSession session, String code, String message, String detail)
            throws IOException {
        Map<String, Object> m = new HashMap<>();
        m.put("event", "error");
        m.put("code", code);
        m.put("message", message);
        if (StringUtils.hasText(detail)) m.put("detail", detail);
        log.warn("[ASR] 发送错误: code={} message={}", code, message);
        sendPayload(session, m);
    }

}
