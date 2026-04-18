package com.simultaneousinterpretation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simultaneousinterpretation.api.dto.TranslateRequest;
import com.simultaneousinterpretation.meeting.RoomWebSocketHandler;
import com.simultaneousinterpretation.service.AiTranslateService;
import com.simultaneousinterpretation.service.RealtimeTtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ASR final 后的异步流水线：
 * <ol>
 *   <li>立即对源文本做 TTS（不等翻译）</li>
 *   <li>并行翻译到另外两种语言 → 广播 translation 事件 → 各自做 TTS</li>
 * </ol>
 */
@Component
public class PostAsrPipeline {

    private static final Logger log = LoggerFactory.getLogger(PostAsrPipeline.class);
    private static final List<String> ALL_LANGS = List.of("zh", "en", "id");

    private final AiTranslateService translateService;
    private final RealtimeTtsService ttsService;
    private final RoomWebSocketHandler roomWsHandler;
    private final ObjectMapper objectMapper;

    // 8 线程：最多 3 路 TTS + 2 路翻译并行，留余量
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public PostAsrPipeline(AiTranslateService translateService,
                           RealtimeTtsService ttsService,
                           RoomWebSocketHandler roomWsHandler,
                           ObjectMapper objectMapper) {
        this.translateService = translateService;
        this.ttsService = ttsService;
        this.roomWsHandler = roomWsHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * 触发 ASR final 后的翻译 + TTS 流水线（非阻塞）。
     *
     * @param text       ASR 原文
     * @param sourceLang 检测到的源语言（zh/en/id）
     * @param segIdx     本段全局序号
     * @param segmentTs  本段时间戳（前端用于找到对应 card）
     * @param roomId     房间 ID
     * @param floor      发言人 floor
     */
    public void triggerAsync(String text, String sourceLang, int segIdx, long segmentTs,
                             String roomId, int floor) {
        if (roomId == null) return;

        // ── 1. 源语言 TTS（立即开始，不等翻译） ──────────────────────────────
        CompletableFuture.runAsync(() -> {
            try {
                ttsService.synthesizeAndStream(text, sourceLang, segIdx, roomId, roomWsHandler);
            } catch (Exception e) {
                log.error("[Pipeline] 源语言TTS失败 lang={} segIdx={}: {}", sourceLang, segIdx, e.getMessage());
                roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, sourceLang);
            }
        }, executor);

        // ── 2. 并行翻译另外两种语言 → 广播 translation 事件 → TTS ──────────
        for (String targetLang : ALL_LANGS) {
            if (targetLang.equals(sourceLang)) continue;
            final String tgt = targetLang;

            CompletableFuture.runAsync(() -> {
                try {
                    TranslateRequest req = new TranslateRequest();
                    req.setSegment(text);
                    req.setSourceLang(sourceLang);
                    req.setTargetLang(tgt);
                    req.setKbEnabled(false);

                    String translated = translateService.translate(req).translation();
                    if (translated == null || translated.isBlank()) {
                        log.warn("[Pipeline] 翻译结果为空 src={} tgt={} segIdx={}", sourceLang, tgt, segIdx);
                        roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, tgt);
                        return;
                    }

                    // 广播 translation 事件（主持端 + 所有听众）
                    Map<String, Object> ev = new HashMap<>();
                    ev.put("event", "translation");
                    ev.put("index", segIdx);
                    ev.put("sequence", segIdx);
                    ev.put("sourceText", text);
                    ev.put("translatedText", translated);
                    ev.put("sourceLang", sourceLang);
                    ev.put("targetLang", tgt);
                    ev.put("lang", tgt);
                    ev.put("detectedLang", tgt);
                    ev.put("textRole", "translation");
                    ev.put("isSourceText", false);
                    ev.put("floor", floor);
                    ev.put("segmentTs", segmentTs);
                    ev.put("serverTs", System.currentTimeMillis());

                    roomWsHandler.broadcastJsonToAll(roomId, objectMapper.writeValueAsString(ev));
                    log.info("[Pipeline] 翻译广播 src={} tgt={} segIdx={} len={}",
                            sourceLang, tgt, segIdx, translated.length());

                    // 翻译语言 TTS
                    ttsService.synthesizeAndStream(translated, tgt, segIdx, roomId, roomWsHandler);

                } catch (Exception e) {
                    log.error("[Pipeline] 翻译/TTS失败 src={} tgt={} segIdx={}: {}",
                            sourceLang, tgt, segIdx, e.getMessage());
                    roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, tgt);
                }
            }, executor);
        }
    }
}
