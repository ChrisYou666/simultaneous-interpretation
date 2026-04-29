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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simultaneousinterpretation.audio.VoiceMeeterAudioOutput;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.config.TtsProperties;
import com.simultaneousinterpretation.meeting.RoomSegmentRegistry;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全链路处理：
 *   PCM → ASR → 语言检测
 *     ├─ 原始音频实时写入 VoiceMeeter 源语言声道（16kHz 直通，独立 line）
 *     ├─ 翻译 → TTS → VoiceMeeter 目标语言声道（24kHz，按 segIdx 排序）
 *     └─ 文字（原文+译文）→ WebSocket → 前端字幕
 *
 * 日志标记（可 grep 定位各阶段）：
 *   [F-RECV]      音频帧到达 WS
 *   [F-THRU]      直通写入 VoiceMeeter
 *   [CB-PARTIAL]  ASR partial 回调
 *   [CB-FINAL]    ASR final 回调（句子结束）
 *   [DETECT]      语言检测结果
 *   [SWITCH]      语言切换
 *   [SEG]         段落注册
 *   [PUSH-SEG]    segment 事件推送 WS
 *   [PUSH-XLAT]   translation 事件推送 WS
 *   [XLAT-START]  翻译开始
 *   [XLAT-DONE]   翻译完成
 *   [TTS-START]   TTS 合成开始
 *   [TTS-CHUNK]   TTS 分块到达
 *   [TTS-DONE]    TTS 合成完成 + 所有分块入队
 */
@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

    private static final String ATTR_CURRENT_SRC_LANG = "asr.currentSrcLang";
    private static final String ATTR_LANG_GEN         = "asr.langSwitchGeneration";

    private static final List<String> ALL_LANGS = List.of("zh", "id");

    private final DashScopeSdkWrapper sdkWrapper;
    private final ObjectMapper objectMapper;
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
            "id", Executors.newSingleThreadExecutor(r -> new Thread(r, "tts-id"))
    );

    /** 翻译并行线程池（翻译 API 调用耗时，多语言并行） */
    private final ExecutorService translateExecutor = Executors.newFixedThreadPool(4);

    /** sessionId → ASR session */
    private final Map<String, WebSocketSession> asrSessions =
            new java.util.concurrent.ConcurrentHashMap<>();

    public AsrWebSocketHandler(
            DashScopeSdkWrapper sdkWrapper,
            ObjectMapper objectMapper,
            RoomSegmentRegistry segmentRegistry,
            LanguageDetectionService languageDetectionService,
            TtsConnectionPool ttsConnectionPool,
            TranslateService translateService,
            TtsProperties ttsProperties,
            DashScopeProperties dashScopeProperties,
            VoiceMeeterAudioOutput voiceMeeterAudioOutput) {
        this.sdkWrapper = sdkWrapper;
        this.objectMapper = objectMapper;
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
        String user  = (String)  session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USERNAME);
        int    floor = (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);

        log.info("[ASR-CONNECT] sessionId={} user={} floor={}", sessionId, user, floor);

        asrSessions.put(sessionId, session);
        AsrClientTextOutboundQueue.start(session);

        session.getAttributes().put("asr.framesReceived",  new AtomicLong(0));
        session.getAttributes().put("asr.segmentsEmitted", new AtomicLong(0));
        session.getAttributes().put(ATTR_CURRENT_SRC_LANG, "zh");
        session.getAttributes().put(ATTR_LANG_GEN, new AtomicInteger(0));

        // 等待 DashScope WebSocket 真正就绪（首帧成功发送）后再向浏览器发 ready，
        // 避免 frame#1 因 SDK 处于 idle 状态被丢弃。
        AtomicBoolean readyEmitted = new AtomicBoolean(false);
        session.getAttributes().put("asr.readyEmitted", readyEmitted);

        boolean started = sdkWrapper.start(
                sessionId,
                result -> handleSdkResult(session, result),
                error  -> {
                    try { sendError(session, "ASR 服务错误", error.getMessage()); }
                    catch (IOException ignored) {}
                },
                () -> log.info("[ASR-COMPLETE] sessionId={} SDK 识别任务结束 持续={}ms",
                        sessionId, System.currentTimeMillis() - startMs)
        );

        if (!started) {
            log.error("[ASR-CONNECT-FAIL] sessionId={} SDK 启动失败", sessionId);
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }

        Map<String, Object> ready = new HashMap<>();
        ready.put("event", "ready");
        ready.put("floor", floor);
        ready.put("sampleRate", 16000);
        ready.put("serverTimeMs", System.currentTimeMillis());
        sendPayload(session, ready);

        log.info("[ASR-CONNECT-OK] sessionId={} 就绪 耗时={}ms", sessionId, System.currentTimeMillis() - startMs);
    }

    // ─── PCM 帧到达 ───────────────────────────────────────────────────────────

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        long frameArrivalMs = System.currentTimeMillis();
        String sessionId = session.getId();
        ByteBuffer payload = message.getPayload();
        byte[] raw = new byte[payload.remaining()];
        payload.get(raw);

        // 去除 8 字节时间戳前缀
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

        // 采样日志：前 5 帧 + 每 200 帧（约每 4 秒）
        boolean logFrame = frameCount <= 5 || frameCount % 200 == 0;
        if (logFrame) {
            log.info("[F-RECV] sid={} frame#{} rawBytes={} pcmBytes={} arrivalMs={}",
                    sessionId, frameCount, raw.length, pcm.length, frameArrivalMs);
        }

        // ① 送 ASR
        long asrSendStart = System.nanoTime();
        sdkWrapper.sendAudioFrame(sessionId, pcm);
        long asrSendNs = System.nanoTime() - asrSendStart;
        if (logFrame) {
            log.info("[F-ASR] sid={} frame#{} sendNs={}", sessionId, frameCount, asrSendNs);
        }

        // ② 直通：异步放入 passthrough 队列，由专用线程写入 SourceDataLine，不阻塞 IO 线程
        // ② 直通：非阻塞写，writePassthrough 内部上采样并检查 available()，放不下直接丢帧
        String srcLang = (String) session.getAttributes().get(ATTR_CURRENT_SRC_LANG);
        voiceMeeterAudioOutput.writePassthrough(srcLang, pcm);
        if (logFrame) {
            log.info("[F-THRU] sid={} frame#{} lang={} bytes={}", sessionId, frameCount, srcLang, pcm.length);
        }
    }

    // ─── ASR 回调 ─────────────────────────────────────────────────────────────

    private void handleSdkResult(WebSocketSession session, TranslationRecognizerResult result) {
        long cbArrivalMs = System.currentTimeMillis();
        String sessionId = session.getId();
        int    floor     = (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);

        TranscriptionResult transcription = result.getTranscriptionResult();
        if (transcription == null) return;

        String text    = transcription.getText();
        boolean isFinal = result.isSentenceEnd();
        if (!StringUtils.hasText(text)) return;

        String srcLang = (String) session.getAttributes().get(ATTR_CURRENT_SRC_LANG);

        if (isFinal) {
            log.info("[CB-FINAL] sid={} sentenceId={} lang={} textLen={} text=\"{}\" cbMs={}",
                    sessionId, transcription.getSentenceId(), srcLang,
                    text.length(), truncate(text, 60), cbArrivalMs);
        } else {
            log.debug("[CB-PARTIAL] sid={} sentenceId={} textLen={} text=\"{}\"",
                    sessionId, transcription.getSentenceId(), text.length(), truncate(text, 40));
        }

        // partial + final 都推 transcript 给前端实时字幕
        Map<String, Object> transcriptEv = new HashMap<>();
        transcriptEv.put("event",    "transcript");
        transcriptEv.put("partial",  !isFinal);
        transcriptEv.put("text",     text);
        transcriptEv.put("language", srcLang);
        transcriptEv.put("floor",    floor);
        try { sendPayloadFast(session, transcriptEv); }
        catch (IOException e) {
            log.error("[CB-PUSH-ERR] sid={} transcript 推送失败", sessionId, e);
            return;
        }

        if (!isFinal) return;

        // ── 语言检测 ──────────────────────────────────────────────────────────
        long detectStart = System.nanoTime();
        String detectedLang = languageDetectionService.detect(text);
        long detectNs = System.nanoTime() - detectStart;
        if (detectedLang == null || detectedLang.isBlank()) detectedLang = "zh";

        log.info("[DETECT] sid={} result={} detectNs={} text=\"{}\"",
                sessionId, detectedLang, detectNs, truncate(text, 40));

        AtomicInteger langGen = (AtomicInteger) session.getAttributes()
                .computeIfAbsent(ATTR_LANG_GEN, k -> new AtomicInteger(0));
        String prevLang = (String) session.getAttributes().get(ATTR_CURRENT_SRC_LANG);
        if (!detectedLang.equals(prevLang)) {
            int gen = langGen.incrementAndGet();
            log.info("[SWITCH] sid={} {}→{} gen={} — 旧任务将被废弃", sessionId, prevLang, detectedLang, gen);
        }
        session.getAttributes().put(ATTR_CURRENT_SRC_LANG, detectedLang);

        // ── 段落注册 ──────────────────────────────────────────────────────────
        long serverTs = cbArrivalMs;
        RoomSegmentRegistry.RoomRegistry reg = segmentRegistry.getOrCreate(sessionId);
        int segIdx = reg.registerSegment(text, detectedLang, 1.0, floor, serverTs, serverTs).getSegIndex();
        ((AtomicLong) session.getAttributes()
                .computeIfAbsent("asr.segmentsEmitted", k -> new AtomicLong(0))).incrementAndGet();

        log.info("[SEG] sid={} segIdx={} lang={} textLen={} text=\"{}\"",
                sessionId, segIdx, detectedLang, text.length(), truncate(text, 60));

        // ── segment 事件 → 前端 ───────────────────────────────────────────────
        Map<String, Object> segEv = new HashMap<>();
        segEv.put("event",        "segment");
        segEv.put("index",        segIdx);
        segEv.put("sequence",     segIdx);
        segEv.put("text",         text);
        segEv.put("sourceLang",   detectedLang);
        segEv.put("lang",         detectedLang);
        segEv.put("isSourceText", true);
        segEv.put("floor",        floor);
        segEv.put("serverTs",     serverTs);
        segEv.put("segmentTs",    serverTs);
        try {
            sendPayload(session, segEv);
            log.info("[PUSH-SEG] sid={} segIdx={} lang={}", sessionId, segIdx, detectedLang);
        } catch (IOException e) {
            log.warn("[PUSH-SEG-ERR] sid={} segIdx={} 推送失败: {}", sessionId, segIdx, e.getMessage());
        }

        // ── 翻译 + TTS → VoiceMeeter ──────────────────────────────────────────
        final String finalLang   = detectedLang;
        final int    finalSegIdx = segIdx;
        final int    myGen       = langGen.get();
        final long   asrFinalMs  = cbArrivalMs;
        translateAndPlay(session, text, finalLang, finalSegIdx, serverTs, floor, langGen, myGen, asrFinalMs);
    }

    // ─── 翻译 → TTS → VoiceMeeter ────────────────────────────────────────────

    private void translateAndPlay(WebSocketSession session, String text, String sourceLang,
                                  int segIdx, long segmentTs, int floor,
                                  AtomicInteger langGen, int myGen, long asrFinalMs) {
        String sessionId = session.getId();
        for (String tgt : ALL_LANGS) {
            if (tgt.equals(sourceLang)) continue;
            ExecutorService tgtExec = langExecutors.get(tgt);

            log.info("[XLAT-START] sid={} segIdx={} {}→{} gen={} asrFinalMs={}",
                    sessionId, segIdx, sourceLang, tgt, myGen, asrFinalMs);

            CompletableFuture.supplyAsync(() -> {
                // gen 检查：语言切换后废弃旧任务
                if (langGen.get() != myGen) {
                    log.info("[XLAT-SKIP] sid={} segIdx={} tgt={} gen stale ({} != {})",
                            sessionId, segIdx, tgt, langGen.get(), myGen);
                    return null;
                }

                TranslateRequest req = new TranslateRequest();
                req.setSegment(text);
                req.setSourceLang(sourceLang);
                req.setTargetLang(tgt);
                req.setKbEnabled(false);

                long xlatStart = System.currentTimeMillis();
                try {
                    var resp = translateService.translate(req);
                    String translated = resp.translation();
                    long xlatMs = System.currentTimeMillis() - xlatStart;
                    long sinceAsrMs = System.currentTimeMillis() - asrFinalMs;

                    if (translated == null || translated.isBlank()) {
                        log.warn("[XLAT-EMPTY] sid={} segIdx={} tgt={} xlatMs={}", sessionId, segIdx, tgt, xlatMs);
                        return null;
                    }
                    log.info("[XLAT-DONE] sid={} segIdx={} {}→{} xlatMs={} sinceAsrMs={} srcLen={} dstLen={} ratio={}",
                            sessionId, segIdx, sourceLang, tgt,
                            xlatMs, sinceAsrMs,
                            text.length(), translated.length(),
                            String.format("%.2f", resp.compressionRatio()));
                    return new Object[]{translated, resp.compressionRatio()};
                } catch (Exception e) {
                    log.error("[XLAT-ERR] sid={} segIdx={} tgt={} xlatMs={} error={}",
                            sessionId, segIdx, tgt, System.currentTimeMillis() - xlatStart, e.getMessage());
                    return null;
                }
            }, translateExecutor).thenAcceptAsync(result -> {
                if (result == null) return;
                if (langGen.get() != myGen) {
                    log.info("[TTS-SKIP] sid={} segIdx={} tgt={} gen stale ({} != {})",
                            sessionId, segIdx, tgt, langGen.get(), myGen);
                    return;
                }
                String translated = (String) result[0];
                double compRatio  = (Double) result[1];
                long xlatDoneMs   = System.currentTimeMillis();

                // 译文 → 前端字幕
                pushTranslation(session, segIdx, text, translated, sourceLang, tgt, floor, segmentTs, compRatio);

                // TTS → VoiceMeeter
                TtsConnectionPool.Lang poolLang = toPoolLang(tgt);
                SpeechSynthesizer synth = null;
                try {
                    synth = ttsConnectionPool.borrow(poolLang);
                    log.info("[TTS-START] sid={} segIdx={} tgt={} textLen={} sinceAsrMs={} sinceXlatMs={}",
                            sessionId, segIdx, tgt, translated.length(),
                            System.currentTimeMillis() - asrFinalMs,
                            System.currentTimeMillis() - xlatDoneMs);
                    doTts(synth, buildTtsParam(tgt), translated, segIdx, tgt, asrFinalMs, sessionId);
                } catch (Exception e) {
                    log.error("[TTS-ERR] sid={} segIdx={} tgt={} sinceAsrMs={} error={}",
                            sessionId, segIdx, tgt, System.currentTimeMillis() - asrFinalMs, e.getMessage());
                } finally {
                    if (synth != null) ttsConnectionPool.returnObject(poolLang, synth);
                }
            }, tgtExec);
        }
    }

    /** TTS 合成，收集所有分块后按 segIdx 排序入队 VoiceMeeter */
    private void doTts(SpeechSynthesizer synth, SpeechSynthesisParam param,
                       String text, int segIdx, String lang,
                       long asrFinalMs, String sessionId) throws Exception {
        synth.updateParamAndCallback(param, NOOP_CB);

        List<byte[]> chunks = new ArrayList<>();
        long[] chunkCount = {0};
        long ttsCallStart = System.currentTimeMillis();

        synth.streamingCallAsFlowable(Flowable.just(text))
                .blockingForEach(r -> {
                    ByteBuffer frame = r.getAudioFrame();
                    if (frame != null && frame.hasRemaining()) {
                        byte[] pcm = new byte[frame.remaining()];
                        frame.get(pcm);
                        chunks.add(pcm);
                        chunkCount[0]++;
                        log.debug("[TTS-CHUNK] sid={} segIdx={} tgt={} chunk#{} bytes={}",
                                sessionId, segIdx, lang, chunkCount[0], pcm.length);
                    }
                });

        long ttsMs = System.currentTimeMillis() - ttsCallStart;
        long enqueueMs = System.currentTimeMillis();
        int totalBytes = chunks.stream().mapToInt(b -> b.length).sum();

        log.info("[TTS-DONE] sid={} segIdx={} tgt={} chunks={} totalBytes={} ttsMs={} sinceAsrMs={}",
                sessionId, segIdx, lang, chunks.size(), totalBytes, ttsMs,
                enqueueMs - asrFinalMs);

        // Concatenate all chunks: ConcurrentSkipListMap.put with same segIdx overwrites,
        // so enqueueTts must be called exactly once per segIdx.
        byte[] combined = new byte[totalBytes];
        int off = 0;
        for (byte[] c : chunks) { System.arraycopy(c, 0, combined, off, c.length); off += c.length; }
        voiceMeeterAudioOutput.enqueueTts(sessionId, lang, segIdx, combined, enqueueMs, asrFinalMs);

        log.info("[TTS-ENQUEUE] sid={} segIdx={} tgt={} chunks={} totalBytes={} sinceAsrMs={}",
                sessionId, segIdx, lang, chunks.size(), totalBytes,
                System.currentTimeMillis() - asrFinalMs);
    }

    private static final ResultCallback<SpeechSynthesisResult> NOOP_CB =
            new ResultCallback<>() {
                @Override public void onEvent(SpeechSynthesisResult r) {}
                @Override public void onComplete() {}
                @Override public void onError(Exception e) {}
            };

    // ─── 译文推送前端 ─────────────────────────────────────────────────────────

    private void pushTranslation(WebSocketSession session, int segIdx, String sourceText,
                                 String translatedText, String sourceLang, String targetLang,
                                 int floor, long segmentTs, double compressionRatio) {
        String sessionId = session.getId();
        try {
            Map<String, Object> ev = new HashMap<>();
            ev.put("event",            "translation");
            ev.put("index",            segIdx);
            ev.put("sequence",         segIdx);
            ev.put("sourceText",       sourceText);
            ev.put("translatedText",   translatedText);
            ev.put("sourceLang",       sourceLang);
            ev.put("targetLang",       targetLang);
            ev.put("lang",             targetLang);
            ev.put("isSourceText",     false);
            ev.put("compressionRatio", compressionRatio);
            ev.put("floor",            floor);
            ev.put("segmentTs",        segmentTs);
            ev.put("serverTs",         System.currentTimeMillis());

            String json = objectMapper.writeValueAsString(ev);
            sendToSession(sessionId, json);
            log.info("[PUSH-XLAT] sid={} segIdx={} {}→{} dstLen={}",
                    sessionId, segIdx, sourceLang, targetLang, translatedText.length());
        } catch (JsonProcessingException e) {
            log.error("[PUSH-XLAT-ERR] sid={} segIdx={} tgt={}", sessionId, segIdx, targetLang, e);
        }
    }

    // ─── 连接关闭 ─────────────────────────────────────────────────────────────

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String sessionId = session.getId();
        AtomicLong frames = (AtomicLong) session.getAttributes().get("asr.framesReceived");
        AtomicLong segs   = (AtomicLong) session.getAttributes().get("asr.segmentsEmitted");
        log.info("[ASR-CLOSE] sid={} status={} totalFrames={} totalSegs={}",
                sessionId, status,
                frames != null ? frames.get() : 0,
                segs   != null ? segs.get()   : 0);

        sdkWrapper.stop(sessionId);
        AsrClientTextOutboundQueue.stop(session);
        asrSessions.remove(sessionId);
        segmentRegistry.removeRoom(sessionId);
        voiceMeeterAudioOutput.stopSession(sessionId);

        super.afterConnectionClosed(session, status);
    }

    // ─── 发送到 session ───────────────────────────────────────────────────────

    public boolean sendToSession(String sessionId, String json) {
        WebSocketSession session = asrSessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            if (session != null) asrSessions.remove(sessionId);
            return false;
        }
        try {
            AsrClientTextOutboundQueue.enqueue(session, json);
            return true;
        } catch (IOException e) {
            log.error("[SEND-ERR] sid={}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    // ─── 工具方法 ─────────────────────────────────────────────────────────────

    private void sendPayload(WebSocketSession session, Map<String, Object> payload) throws IOException {
        if (!session.isOpen()) return;
        try {
            AsrClientTextOutboundQueue.enqueue(session, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
    }

    private void sendPayloadFast(WebSocketSession session, Map<String, Object> payload) throws IOException {
        if (!session.isOpen()) return;
        try {
            AsrClientTextOutboundQueue.enqueueFast(session, objectMapper.writeValueAsString(payload));
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
        SpeechSynthesisParam.Builder builder = SpeechSynthesisParam.builder()
                .model(ttsProperties.getModel())
                .voice(ttsProperties.getVoice(lang))
                .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
                .apiKey(key);
        // 印尼语以最快语速合成（阿里云官方推荐方式：通过 instruction 指令控制）
        if ("id".equalsIgnoreCase(lang)) {
            builder.parameters(new HashMap<>(Map.of("instruction", "请用尽可能快地语速说一句话。")));
        }
        return builder.build();
    }

    private TtsConnectionPool.Lang toPoolLang(String lang) {
        return switch (lang.toLowerCase()) {
            case "zh" -> TtsConnectionPool.Lang.ZH;
            default   -> TtsConnectionPool.Lang.ID;
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
