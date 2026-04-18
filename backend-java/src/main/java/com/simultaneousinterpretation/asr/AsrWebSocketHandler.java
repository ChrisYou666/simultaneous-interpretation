package com.simultaneousinterpretation.asr;

import com.alibaba.dashscope.audio.asr.translation.results.TranscriptionResult;
import com.alibaba.dashscope.audio.asr.translation.results.TranslationRecognizerResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simultaneousinterpretation.config.AsrProperties;
import com.simultaneousinterpretation.meeting.RoomSegmentRegistry;
import com.simultaneousinterpretation.meeting.RoomWebSocketHandler;
import com.simultaneousinterpretation.pipeline.PostAsrPipeline;
import com.simultaneousinterpretation.service.LanguageDetectionService;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * ASR WebSocket 处理器
 *
 * <p>流程：
 * <ol>
 *   <li>前端 PCM → DashScope SDK</li>
 *   <li>SDK partial 回调 → 广播 transcript{partial:true}</li>
 *   <li>SDK final 回调 → 检测语言 → 广播 segment 事件 → 触发 PostAsrPipeline（翻译+TTS）</li>
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
    private final PostAsrPipeline postAsrPipeline;

    /** roomId -> 主持端 ASR WebSocket session（用于向主持端发送翻译事件） */
    private final Map<String, WebSocketSession> hostAsrSessions = new java.util.concurrent.ConcurrentHashMap<>();

    public AsrWebSocketHandler(
            AsrProperties asrProperties,
            DashScopeSdkWrapper sdkWrapper,
            ObjectMapper objectMapper,
            RoomWebSocketHandler roomWebSocketHandler,
            RoomSegmentRegistry segmentRegistry,
            LanguageDetectionService languageDetectionService,
            PostAsrPipeline postAsrPipeline) {
        this.asrProperties = asrProperties;
        this.sdkWrapper = sdkWrapper;
        this.objectMapper = objectMapper;
        this.roomWebSocketHandler = roomWebSocketHandler;
        this.segmentRegistry = segmentRegistry;
        this.languageDetectionService = languageDetectionService;
        this.postAsrPipeline = postAsrPipeline;
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

        // ── Final：语言检测 → 注册段 → 广播 segment 事件 → 触发翻译+TTS ──────
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
            postAsrPipeline.triggerAsync(text, finalLang, finalSegIdx, serverTs, roomId, floor);
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

    // ─── 公共方法（供 PostAsrPipeline 调用）────────────────────────────────────

    /**
     * 向主持端的 ASR WebSocket 发送 JSON 消息。
     * 用于发送 translation 事件给主持端。
     *
     * @param roomId 房间 ID
     * @param json   JSON 消息字符串
     * @return 是否发送成功
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
            session.sendMessage(new TextMessage(json));
            log.info("[ASR-SEND-TO-HOST] ★ 成功发送到主持端 roomId={} sessionId={} size={}",
                    roomId, session.getId(), json.length());
            return true;
        } catch (IOException e) {
            log.error("[ASR-SEND-TO-HOST] 发送失败 roomId={}: {}", roomId, e.getMessage());
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
        // 解析 event 类型
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
