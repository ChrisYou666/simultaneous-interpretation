package com.simultaneousinterpretation.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simultaneousinterpretation.api.dto.TranslateRequest;
import com.simultaneousinterpretation.asr.AsrWebSocketHandler;
import com.simultaneousinterpretation.meeting.RoomWebSocketHandler;
import com.simultaneousinterpretation.service.AiTranslateService;
import com.simultaneousinterpretation.service.RealtimeTtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * ASR final 后的异步流水线：
 * <ol>
 *   <li>立即对源文本做 TTS（不等翻译）</li>
 *   <li>并行翻译到另外两种语言 → 广播 translation 事件 → 各自做 TTS</li>
 * </ol>
 *
 * <p>TTS 并发控制：每种语言独立 Semaphore(1)，确保同一时刻每语言只有 1 路 TTS 请求在飞，
 * 总并发 3 路，匹配 DashScope CosyVoice 3 RPS 限额，防止限流和超时堆积。
 */
@Component
public class PostAsrPipeline {

    private static final Logger log = LoggerFactory.getLogger(PostAsrPipeline.class);
    private static final List<String> ALL_LANGS = List.of("zh", "en", "id");

    private final AiTranslateService translateService;
    private final RealtimeTtsService ttsService;
    private final RoomWebSocketHandler roomWsHandler;
    private final ObjectMapper objectMapper;
    private final AsrWebSocketHandler asrWsHandler;

    /**
     * 翻译 + TTS 任务共享线程池：翻译并发高，TTS 由 Semaphore 控制，线程数留足余量。
     * 翻译任务：3 路并行，每路约 3-8s
     * TTS 任务：3 路并行（每语言 1 路），每路约 2-10s
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(12);

    /**
     * 每种语言独立 Semaphore(1)：同一语言的 TTS 串行执行，三种语言彼此并行。
     * 保证 DashScope 同时收到的 TTS 请求 ≤ 3，不触发 Throttling.RateQuota。
     */
    private final Map<String, Semaphore> ttsSemaphores = Map.of(
            "zh", new Semaphore(1),
            "en", new Semaphore(1),
            "id", new Semaphore(1)
    );

    public PostAsrPipeline(AiTranslateService translateService,
                           RealtimeTtsService ttsService,
                           RoomWebSocketHandler roomWsHandler,
                           ObjectMapper objectMapper,
                           @Lazy AsrWebSocketHandler asrWsHandler) {
        this.translateService = translateService;
        this.ttsService = ttsService;
        this.roomWsHandler = roomWsHandler;
        this.objectMapper = objectMapper;
        this.asrWsHandler = asrWsHandler;
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
        final long srcSubmitMs = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            long queueWaitMs = System.currentTimeMillis() - srcSubmitMs;
            log.info("[PIPE-3-TTS-START] segIdx={} lang={} type=source queueWaitMs={}ms wallMs={}",
                    segIdx, sourceLang, queueWaitMs, System.currentTimeMillis());
            Semaphore sem = ttsSemaphores.get(sourceLang);
            long semWaitStart = System.currentTimeMillis();
            try {
                sem.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[PIPE-3-SEM-INTERRUPTED] segIdx={} lang={}", segIdx, sourceLang);
                roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, sourceLang);
                return;
            }
            long semWaitMs = System.currentTimeMillis() - semWaitStart;
            if (semWaitMs > 10) {
                log.info("[PIPE-3-SEM-WAIT] segIdx={} lang={} semWaitMs={}ms wallMs={}",
                        segIdx, sourceLang, semWaitMs, System.currentTimeMillis());
            }
            try {
                ttsService.synthesizeAndStream(text, sourceLang, segIdx, roomId, roomWsHandler);
            } catch (Exception e) {
                log.error("[PIPE-3-ERROR] segIdx={} lang={} type=source error={} wallMs={}",
                        segIdx, sourceLang, e.getMessage(), System.currentTimeMillis());
                roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, sourceLang);
            } finally {
                sem.release();
            }
        }, executor);

        // ── 2. 并行翻译另外两种语言 → 广播 translation 事件 → TTS ──────────
        for (String targetLang : ALL_LANGS) {
            if (targetLang.equals(sourceLang)) continue;
            final String tgt = targetLang;
            final long tgtSubmitMs = System.currentTimeMillis();

            CompletableFuture.runAsync(() -> {
                long queueWaitMs = System.currentTimeMillis() - tgtSubmitMs;
                log.info("[PIPE-3-XLAT-START] segIdx={} src={} tgt={} queueWaitMs={}ms wallMs={}",
                        segIdx, sourceLang, tgt, queueWaitMs, System.currentTimeMillis());
                try {
                    TranslateRequest req = new TranslateRequest();
                    req.setSegment(text);
                    req.setSourceLang(sourceLang);
                    req.setTargetLang(tgt);
                    req.setKbEnabled(false);

                    long xlStartMs = System.currentTimeMillis();
                    String translated = translateService.translate(req).translation();
                    long xlDurationMs = System.currentTimeMillis() - xlStartMs;

                    if (translated == null || translated.isBlank()) {
                        log.warn("[PIPE-3-XLAT-EMPTY] segIdx={} src={} tgt={} durationMs={}ms wallMs={}",
                                segIdx, sourceLang, tgt, xlDurationMs, System.currentTimeMillis());
                        roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, tgt);
                        return;
                    }

                    log.info("[PIPE-3-XLAT-DONE] segIdx={} src={} tgt={} durationMs={}ms textLen={} wallMs={}",
                            segIdx, sourceLang, tgt, xlDurationMs, translated.length(), System.currentTimeMillis());

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

                    String evJson = objectMapper.writeValueAsString(ev);
                    asrWsHandler.sendToHost(roomId, evJson);
                    roomWsHandler.broadcastJsonToAll(roomId, evJson);

                    // 获取该语言 TTS 许可（同语言串行，不同语言并行）
                    Semaphore sem = ttsSemaphores.get(tgt);
                    long semWaitStart = System.currentTimeMillis();
                    try {
                        sem.acquire();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("[PIPE-3-SEM-INTERRUPTED] segIdx={} lang={}", segIdx, tgt);
                        roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, tgt);
                        return;
                    }
                    long semWaitMs = System.currentTimeMillis() - semWaitStart;
                    if (semWaitMs > 10) {
                        log.info("[PIPE-3-SEM-WAIT] segIdx={} lang={} semWaitMs={}ms wallMs={}",
                                segIdx, tgt, semWaitMs, System.currentTimeMillis());
                    }
                    try {
                        ttsService.synthesizeAndStream(translated, tgt, segIdx, roomId, roomWsHandler);
                    } finally {
                        sem.release();
                    }

                } catch (Exception e) {
                    log.error("[PIPE-3-ERROR] segIdx={} src={} tgt={} error={} wallMs={}",
                            segIdx, sourceLang, tgt, e.getMessage(), System.currentTimeMillis());
                    roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, tgt);
                }
            }, executor);
        }
    }
}
