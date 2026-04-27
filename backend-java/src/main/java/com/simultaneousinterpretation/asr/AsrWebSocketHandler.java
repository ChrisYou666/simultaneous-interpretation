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
import com.simultaneousinterpretation.audio.VoiceMeeterAudioOutput;
import com.simultaneousinterpretation.config.AsrProperties;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.config.TtsProperties;
import com.simultaneousinterpretation.meeting.RoomSegmentRegistry;
import com.simultaneousinterpretation.meeting.RoomWebSocketHandler;
import com.simultaneousinterpretation.service.LanguageDetectionService;
import com.simultaneousinterpretation.service.TtsConnectionPool;
import com.simultaneousinterpretation.service.TranslateService;
import com.simultaneousinterpretation.api.dto.TranslateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全链路处理：
 *   PCM → ASR → 语言检测
 *     ├─ 原始音频实时写入 VoiceMeeter 源语言声道（直通）
 *     ├─ 翻译×2 → TTS → VoiceMeeter 目标语言声道
 *     └─ 文字（原文+译文）→ WebSocket → 前端字幕
 */
@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

    private static final String ATTR_CURRENT_SRC_LANG = "asr.currentSrcLang";
    private static final String ATTR_LANG_GEN         = "asr.langSwitchGeneration";

    private static final List<String> ALL_LANGS = List.of("zh", "en", "id");

    private static final Set<String> ROOM_FORWARD_EVENTS =
            Set.of("segment", "transcript", "translation", "error", "ready");

    private final AsrProperties asrProperties;
    private final DashScopeSdkWrapper sdkWrapper;
    private final ObjectMapper objectMapper;
    private final RoomWebSocketHandler roomWebSocketHandler;
    private final RoomSegmentRegistry segmentRegistry;
    private final LanguageDetectionService languageDetectionService;
    private final TtsConnectionPool ttsConnectionPool;
    private final TranslateService translateService;
    private final TtsProperties ttsProperties;
    private final DashScopeProperties dashScopeProperties;
    private final VoiceMeeterAudioOutput voiceMeeterAudioOutput;

    /** 每种语言独立单线程执行器，保证同一语言的 TTS 串行写入 VoiceMeeter，不交叉 */
    private final Map<String, ExecutorService> langExecutors = Map.of(
            "zh", Executors.newSingleThreadExecutor(r -> new Thread(r, "tts-zh")),
            "en", Executors.newSingleThreadExecutor(r -> new Thread(r, "tts-en")),
            "id", Executors.newSingleThreadExecutor(r -> new Thread(r, "tts-id"))
    );

    /** 翻译并行线程池（翻译 API 调用耗时，多语言并行） */
    private final ExecutorService translateExecutor = Executors.newFixedThreadPool(6);

    /** roomId → 主持端 ASR session，用于向主持端推送文字事件 */
    private final Map<String, WebSocketSession> hostAsrSessions =
            new java.util.concurrent.ConcurrentHashMap<>();

    public AsrWebSocketHandler(
            AsrProperties asrProperties,
            DashScopeSdkWrapper sdkWrapper,
            ObjectMapper objectMapper,
            RoomWebSocketHandler roomWebSocketHandler,
            RoomSegmentRegistry segmentRegistry,
            LanguageDetectionService languageDetectionService,
            TtsConnectionPool ttsConnectionPool,
            TranslateService translateService,
            TtsProperties ttsProperties,
            DashScopeProperties dashScopeProperties,
            VoiceMeeterAudioOutput voiceMeeterAudioOutput) {
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
        this.voiceMeeterAudioOutput = voiceMeeterAudioOutput;
    }

    // ─── 连接建立 ─────────────────────────────────────────────────────────────

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        long startMs = System.currentTimeMillis();
        String sessionId = session.getId();

        String user   = (String)  session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USERNAME);
        int    floor  = (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);
        String roomId = (String)  session.getAttributes().get(JwtHandshakeInterceptor.ATTR_ROOM_ID);

        log.info("[ASR-CONNECT] sessionId={} user={} floor={} roomId={}", sessionId, user, floor, roomId);

        if (roomId != null) {
            hostAsrSessions.put(roomId, session);
        }

        AsrClientTextOutboundQueue.start(session, this::relayToRoom);

        session.getAttributes().put("asr.framesReceived",  new AtomicLong(0));
        session.getAttributes().put("asr.segmentsEmitted", new AtomicLong(0));
        session.getAttributes().put(ATTR_CURRENT_SRC_LANG, "zh");
        session.getAttributes().put(ATTR_LANG_GEN, new AtomicInteger(0));

        boolean started = sdkWrapper.start(
                sessionId,
                result -> handleSdkResult(session, result),
                error  -> {
                    try { sendError(session, "ASR 服务错误", error.getMessage()); }
                    catch (IOException ignored) {}
                },
                () -> log.info("[SDK] sessionId={} 识别完成 持续={}ms",
                        sessionId, System.currentTimeMillis() - startMs)
        );

        if (!started) {
            log.error("[ASR-ERROR] sessionId={} SDK 启动失败", sessionId);
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        Map<String, Object> ready = new HashMap<>();
        ready.put("event", "ready");
        ready.put("floor", floor);
        ready.put("sampleRate", 16000);
        ready.put("serverTimeMs", System.currentTimeMillis());
        sendPayload(session, ready);

        log.info("[ASR-CONNECT] sessionId={} 完成 耗时={}ms", sessionId, System.currentTimeMillis() - startMs);
    }

    // ─── PCM 帧：直通到 VoiceMeeter 源语言声道 ────────────────────────────────

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
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
        if (frameCount <= 10 || frameCount % 100 == 0) {
            log.info("[WS-BINARY] sessionId={} frame#{} pcmSize={}", sessionId, frameCount, pcm.length);
        }

        // 1. 送 ASR 识别
        sdkWrapper.sendAudioFrame(sessionId, pcm);

        // 2. 实时直通：原声写入当前源语言 VoiceMeeter 声道
        String srcLang = (String) session.getAttributes().get(ATTR_CURRENT_SRC_LANG);
        voiceMeeterAudioOutput.writePassthrough(srcLang, pcm);
    }

    // ─── ASR 回调：文字处理 ───────────────────────────────────────────────────

    private void handleSdkResult(WebSocketSession session, TranslationRecognizerResult result) {
        String sessionId = session.getId();
        int    floor  = (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);
        String roomId = (String)  session.getAttributes().get(JwtHandshakeInterceptor.ATTR_ROOM_ID);

        TranscriptionResult transcription = result.getTranscriptionResult();
        if (transcription == null) return;

        String text = transcription.getText();
        if (!StringUtils.hasText(text)) return;

        boolean isFinal = result.isSentenceEnd();
        String  srcLang = (String) session.getAttributes().get(ATTR_CURRENT_SRC_LANG);

        // partial / final 都推给前端显示实时字幕
        Map<String, Object> transcriptEv = new HashMap<>();
        transcriptEv.put("event",    "transcript");
        transcriptEv.put("partial",  !isFinal);
        transcriptEv.put("text",     text);
        transcriptEv.put("language", srcLang);
        transcriptEv.put("floor",    floor);
        try { sendPayload(session, transcriptEv); }
        catch (IOException e) {
            log.error("[ASR] sessionId={} 发送 transcript 失败", sessionId, e);
            return;
        }

        if (!isFinal) return;

        // ── Final：语言检测 ──────────────────────────────────────────────────
        String detectedLang = languageDetectionService.detect(text);
        if (detectedLang == null || detectedLang.isBlank()) detectedLang = "zh";

        AtomicInteger langGen = (AtomicInteger) session.getAttributes()
                .computeIfAbsent(ATTR_LANG_GEN, k -> new AtomicInteger(0));

        String prevLang = (String) session.getAttributes().get(ATTR_CURRENT_SRC_LANG);
        if (!detectedLang.equals(prevLang)) {
            int gen = langGen.incrementAndGet();
            log.info("[PIPE-SWITCH] {} → {} gen={}", prevLang, detectedLang, gen);
        }
        session.getAttributes().put(ATTR_CURRENT_SRC_LANG, detectedLang);

        // ── 注册段、推送 segment 事件 ─────────────────────────────────────────
        long serverTs = System.currentTimeMillis();
        int segIdx = -1;
        if (roomId != null) {
            RoomSegmentRegistry.RoomRegistry reg = segmentRegistry.getOrCreate(roomId);
            segIdx = reg.registerSegment(text, detectedLang, 1.0, floor, serverTs, serverTs).getSegIndex();
        }
        ((AtomicLong) session.getAttributes()
                .computeIfAbsent("asr.segmentsEmitted", k -> new AtomicLong(0))).incrementAndGet();

        Map<String, Object> segEv = new HashMap<>();
        segEv.put("event",       "segment");
        segEv.put("index",       segIdx);
        segEv.put("sequence",    segIdx);
        segEv.put("text",        text);
        segEv.put("sourceLang",  detectedLang);
        segEv.put("lang",        detectedLang);
        segEv.put("isSourceText", true);
        segEv.put("floor",       floor);
        segEv.put("serverTs",    serverTs);
        segEv.put("segmentTs",   serverTs);
        try { sendPayload(session, segEv); }
        catch (IOException e) { log.debug("[ASR] 发送 segment 失败", e); }

        log.info("[PIPE-1] segIdx={} lang={} len={} text=\"{}\"",
                segIdx, detectedLang, text.length(), truncate(text, 40));

        // ── 翻译 + TTS → VoiceMeeter 目标声道 ─────────────────────────────────
        if (roomId != null && segIdx >= 0) {
            final String finalLang = detectedLang;
            final int    finalSegIdx = segIdx;
            final int    myGen = langGen.get();
            translateAndPlay(text, finalLang, finalSegIdx, serverTs, roomId, floor, langGen, myGen);
        }
    }

    // ─── 翻译 → TTS → VoiceMeeter ────────────────────────────────────────────

    private void translateAndPlay(String text, String sourceLang, int segIdx,
                                  long segmentTs, String roomId, int floor,
                                  AtomicInteger langGen, int myGen) {
        for (String tgt : ALL_LANGS) {
            if (tgt.equals(sourceLang)) continue;

            ExecutorService tgtExec = langExecutors.get(tgt);

            CompletableFuture.supplyAsync(() -> {
                if (langGen.get() != myGen) {
                    log.info("[XLAT-SKIP] segIdx={} tgt={} gen stale", segIdx, tgt);
                    return null;
                }
                TranslateRequest req = new TranslateRequest();
                req.setSegment(text);
                req.setSourceLang(sourceLang);
                req.setTargetLang(tgt);
                req.setKbEnabled(false);

                long t0 = System.currentTimeMillis();
                try {
                    var resp = translateService.translate(req);
                    String translated = resp.translation();
                    if (translated == null || translated.isBlank()) {
                        log.warn("[XLAT-EMPTY] segIdx={} tgt={}", segIdx, tgt);
                        return null;
                    }
                    log.info("[XLAT] segIdx={} tgt={} ms={} len={} ratio={}",
                            segIdx, tgt, System.currentTimeMillis() - t0,
                            translated.length(), String.format("%.2f", resp.compressionRatio()));
                    return new Object[]{translated, resp.compressionRatio()};
                } catch (Exception e) {
                    log.error("[XLAT-ERR] segIdx={} tgt={} error={}", segIdx, tgt, e.getMessage());
                    return null;
                }
            }, translateExecutor).thenAcceptAsync(result -> {
                if (result == null) return;
                if (langGen.get() != myGen) {
                    log.info("[TTS-SKIP] segIdx={} tgt={} gen stale", segIdx, tgt);
                    return;
                }
                String translated = (String) result[0];
                double compRatio  = (Double) result[1];

                // 推译文文字给前端字幕
                pushTranslation(roomId, segIdx, text, translated, sourceLang, tgt, floor, segmentTs, compRatio);

                // TTS 合成 → VoiceMeeter 目标声道
                TtsConnectionPool.Lang poolLang = toPoolLang(tgt);
                SpeechSynthesizer synth = null;
                try {
                    synth = ttsConnectionPool.borrow(poolLang);
                    doTts(synth, buildTtsParam(tgt), translated, segIdx, tgt);
                } catch (Exception e) {
                    log.error("[TTS-ERR] segIdx={} tgt={} error={}", segIdx, tgt, e.getMessage());
                } finally {
                    if (synth != null) ttsConnectionPool.returnObject(poolLang, synth);
                }
            }, tgtExec);
        }
    }

    /** TTS 合成，逐帧写入 VoiceMeeter 对应声道 */
    private void doTts(SpeechSynthesizer synth, SpeechSynthesisParam param,
                       String text, int segIdx, String lang) throws Exception {
        synth.updateParamAndCallback(param, NOOP_CB);
        synth.streamingCallAsFlowable(Flowable.just(text))
                .blockingForEach(r -> {
                    ByteBuffer frame = r.getAudioFrame();
                    if (frame != null && frame.hasRemaining()) {
                        byte[] pcm = new byte[frame.remaining()];
                        frame.get(pcm);
                        voiceMeeterAudioOutput.write(lang, pcm);
                    }
                });
        log.info("[TTS-DONE] segIdx={} tgt={}", segIdx, lang);
    }

    private static final ResultCallback<SpeechSynthesisResult> NOOP_CB =
            new ResultCallback<>() {
                @Override public void onEvent(SpeechSynthesisResult r) {}
                @Override public void onComplete() {}
                @Override public void onError(Exception e) {}
            };

    // ─── 译文推送前端 ─────────────────────────────────────────────────────────

    private void pushTranslation(String roomId, int segIdx, String sourceText,
                                 String translatedText, String sourceLang, String targetLang,
                                 int floor, long segmentTs, double compressionRatio) {
        try {
            Map<String, Object> ev = new HashMap<>();
            ev.put("event",           "translation");
            ev.put("index",           segIdx);
            ev.put("sequence",        segIdx);
            ev.put("sourceText",      sourceText);
            ev.put("translatedText",  translatedText);
            ev.put("sourceLang",      sourceLang);
            ev.put("targetLang",      targetLang);
            ev.put("lang",            targetLang);
            ev.put("isSourceText",    false);
            ev.put("compressionRatio", compressionRatio);
            ev.put("floor",           floor);
            ev.put("segmentTs",       segmentTs);
            ev.put("serverTs",        System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(ev);
            sendToHost(roomId, json);
            roomWebSocketHandler.broadcastJsonToAll(roomId, json);
        } catch (JsonProcessingException e) {
            log.error("[PUSH-XLAT-ERR] segIdx={} tgt={}", segIdx, targetLang, e);
        }
    }

    // ─── 连接关闭 ─────────────────────────────────────────────────────────────

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        String roomId    = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_ROOM_ID);

        AtomicLong frames = (AtomicLong) session.getAttributes().get("asr.framesReceived");
        AtomicLong segs   = (AtomicLong) session.getAttributes().get("asr.segmentsEmitted");
        log.info("[ASR-CLOSE] sessionId={} status={} frames={} segs={}",
                sessionId, status,
                frames != null ? frames.get() : 0,
                segs   != null ? segs.get()   : 0);

        sdkWrapper.stop(sessionId);
        AsrClientTextOutboundQueue.stop(session);

        if (StringUtils.hasText(roomId)) {
            hostAsrSessions.remove(roomId);
            roomWebSocketHandler.notifyListenersHostAsrStatus(roomId, "stopped");
        }

        super.afterConnectionClosed(session, status);
    }

    // ─── 发送到主持端 ─────────────────────────────────────────────────────────

    public boolean sendToHost(String roomId, String json) {
        WebSocketSession session = hostAsrSessions.get(roomId);
        if (session == null || !session.isOpen()) {
            if (session != null) hostAsrSessions.remove(roomId);
            return false;
        }
        try {
            AsrClientTextOutboundQueue.enqueue(session, json);
            return true;
        } catch (IOException e) {
            log.error("[SEND-HOST-ERR] roomId={}: {}", roomId, e.getMessage());
            return false;
        }
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

    private void relayToRoom(WebSocketSession session, String json) {
        if (roomWebSocketHandler == null) return;
        String roomId = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_ROOM_ID);
        if (!StringUtils.hasText(roomId)) return;
        try {
            JsonNode root = objectMapper.readTree(json);
            String event = root.path("event").asText("");
            if (!ROOM_FORWARD_EVENTS.contains(event)) return;
            roomWebSocketHandler.broadcastJsonToListeners(roomId, json);
            if ("ready".equals(event)) {
                roomWebSocketHandler.notifyListenersHostAsrStatus(roomId, "started");
            }
        } catch (IOException e) {
            log.warn("[RELAY-ERR] {}", e.getMessage());
        }
    }

    private void sendPayload(WebSocketSession session, Map<String, Object> payload) throws IOException {
        if (!session.isOpen()) return;
        try {
            AsrClientTextOutboundQueue.enqueue(session, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
    }

    private void sendError(WebSocketSession session, String message, String detail) throws IOException {
        Map<String, Object> m = new HashMap<>();
        m.put("event",   "error");
        m.put("message", message);
        if (StringUtils.hasText(detail)) m.put("detail", detail);
        sendPayload(session, m);
    }

    private SpeechSynthesisParam buildTtsParam(String lang) {
        String key = ttsProperties.getApiKey();
        if (key == null || key.isEmpty()) key = dashScopeProperties.getApiKey();
        return SpeechSynthesisParam.builder()
                .model(ttsProperties.getModel())
                .voice(ttsProperties.getVoice(lang))
                .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                .apiKey(key)
                .build();
    }

    private TtsConnectionPool.Lang toPoolLang(String lang) {
        return switch (lang.toLowerCase()) {
            case "zh" -> TtsConnectionPool.Lang.ZH;
            case "en" -> TtsConnectionPool.Lang.EN;
            default   -> TtsConnectionPool.Lang.ID;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
