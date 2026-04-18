package com.simultaneousinterpretation.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * ASR final 后的异步流水线（两阶段架构）：
 * <ol>
 *   <li>将源文本按标点切句（超过 50 字时）</li>
 *   <li>Phase 1 - 翻译：无锁并行，每句译完立即广播累积译文</li>
 *   <li>Phase 2 - TTS：三条语言链各自持有 Semaphore(1)，保证同语言音频严格按 segIdx 顺序输出：
 *     <ul>
 *       <li>源语链：Semaphore → 逐句 TTS → END → 释放 Semaphore</li>
 *       <li>目标语链：Phase1 翻译（并行）→ Phase2 Semaphore → 逐句 TTS → END → 释放 Semaphore</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p><b>并发控制</b>：
 * <ul>
 *   <li>翻译：无锁，多个 segIdx 可同时翻译，TTS 无需排队</li>
 *   <li>TTS：Semaphore(1) per lang，整段（含所有子句）持锁，不同 segIdx 按到达顺序排队</li>
 *   <li>同语言音频 chunk 严格按 segIdx 顺序到达前端，不乱序，不触发 WebSocket 写冲突</li>
 * </ul>
 * 三种语言彼此并行，DashScope 并发 ≤ 3 路。
 */
@Component
public class PostAsrPipeline {

    private static final Logger log = LoggerFactory.getLogger(PostAsrPipeline.class);
    private static final List<String> ALL_LANGS = List.of("zh", "en", "id");

    /** 单句 TTS 安全长度上限（字符数）；超过此长度时触发切句 */
    static final int SENT_MAX_LEN = 50;
    /** 句末标点切句要求的最短缓冲区长度（避免单个标点独立成句） */
    private static final int SENT_SOFT_MIN = 15;
    /** 无标点时强制切句的兜底长度 */
    private static final int SENT_FORCE_LEN = 100;

    private final AiTranslateService translateService;
    private final RealtimeTtsService ttsService;
    private final RoomWebSocketHandler roomWsHandler;
    private final ObjectMapper objectMapper;
    private final AsrWebSocketHandler asrWsHandler;

    /**
     * 线程池：3 条语言链并行，每条链内阻塞（synthesizeAndStream 含 streamingComplete）。
     * 12 线程足够 3 语言 × 多段排队的场景。
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(12);

    /**
     * 每种语言独立 Semaphore(1)：整段（含所有子句）持锁，保证同语言严格串行。
     * 不同 segIdx 按到达顺序排队，音频 chunk 按 segIdx 顺序到达前端，不乱序。
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
     * <p>流水线分两阶段：
     * <ol>
     *   <li><b>Phase 1 - 翻译</b>：无锁并行，每句译完立即广播 accumulated 文本</li>
     *   <li><b>Phase 2 - TTS</b>：持 Semaphore(1)，保证同语言音频 chunk 按 segIdx 顺序到达前端</li>
     * </ol>
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

        List<String> sentences = splitSentences(text);
        int total = sentences.size();
        log.info("[PIPE-3-SPLIT] segIdx={} lang={} totalChars={} sentences={}",
                segIdx, sourceLang, text.length(), total);

        // ── 源语链：Semaphore → 逐句 TTS → END（无翻译阶段，直接 TTS） ──────────
        final long srcSubmitMs = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            log.info("[PIPE-3-SRC-QUEUE] segIdx={} lang={} queueWaitMs={}ms",
                    segIdx, sourceLang, System.currentTimeMillis() - srcSubmitMs);
            Semaphore sem = ttsSemaphores.get(sourceLang);
            long semWaitStart = System.currentTimeMillis();
            try {
                sem.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, sourceLang);
                return;
            }
            long semWaitMs = System.currentTimeMillis() - semWaitStart;
            if (semWaitMs > 50) {
                log.info("[PIPE-3-SEM-WAIT] segIdx={} lang={} semWaitMs={}ms",
                        segIdx, sourceLang, semWaitMs);
            }
            try {
                for (int i = 0; i < total; i++) {
                    String sent = sentences.get(i);
                    log.info("[PIPE-3-SRC-TTS] segIdx={} lang={} sent={}/{} len={}",
                            segIdx, sourceLang, i + 1, total, sent.length());
                    try {
                        ttsService.synthesizeAndStream(sent, sourceLang, segIdx, roomId, roomWsHandler);
                    } catch (Exception e) {
                        log.error("[PIPE-3-SRC-TTS-ERR] segIdx={} sent={}/{} error={}",
                                segIdx, i + 1, total, e.getMessage());
                    }
                }
            } finally {
                roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, sourceLang);
                sem.release();
            }
        }, executor);

        // ── 目标语链：Phase1 翻译（无锁）→ Phase2 TTS（持锁） ───────────────────
        for (String targetLang : ALL_LANGS) {
            if (targetLang.equals(sourceLang)) continue;
            final String tgt = targetLang;
            final long tgtSubmitMs = System.currentTimeMillis();

            CompletableFuture.runAsync(() -> {
                // ══ Phase 1: 翻译（无 Semaphore，并行执行）══
                StringBuilder accumulated = new StringBuilder();
                List<String> translatedList = new ArrayList<>();

                for (int i = 0; i < total; i++) {
                    String sent = sentences.get(i);
                    log.info("[PIPE-3-XLAT-START] segIdx={} src={} tgt={} sent={}/{}",
                            segIdx, sourceLang, tgt, i + 1, total);
                    try {
                        TranslateRequest req = new TranslateRequest();
                        req.setSegment(sent);
                        req.setSourceLang(sourceLang);
                        req.setTargetLang(tgt);
                        req.setKbEnabled(false);

                        long xlStart = System.currentTimeMillis();
                        String translated = translateService.translate(req).translation();
                        long xlMs = System.currentTimeMillis() - xlStart;

                        if (translated == null || translated.isBlank()) {
                            log.warn("[PIPE-3-XLAT-EMPTY] segIdx={} tgt={} sent={}/{} durationMs={}",
                                    segIdx, tgt, i + 1, total, xlMs);
                            continue;
                        }

                        log.info("[PIPE-3-XLAT-DONE] segIdx={} tgt={} sent={}/{} durationMs={} textLen={}",
                                segIdx, tgt, i + 1, total, xlMs, translated.length());

                        // 累积译文，广播越来越完整的翻译（前端覆盖写）
                        translatedList.add(translated);
                        if (!accumulated.isEmpty()) accumulated.append(" ");
                        accumulated.append(translated);
                        broadcastTranslation(roomId, segIdx, text, accumulated.toString(),
                                sourceLang, tgt, floor, segmentTs);

                    } catch (Exception e) {
                        log.error("[PIPE-3-XLAT-ERR] segIdx={} tgt={} sent={}/{} error={}",
                                segIdx, tgt, i + 1, total, e.getMessage());
                    }
                }

                // ══ Phase 2: TTS（持 Semaphore，串行保证音频顺序）══
                log.info("[PIPE-3-TTS-QUEUE] segIdx={} tgt={} queueWaitMs={}ms xlCount={}",
                        segIdx, tgt, System.currentTimeMillis() - tgtSubmitMs, translatedList.size());

                Semaphore sem = ttsSemaphores.get(tgt);
                long semWaitStart = System.currentTimeMillis();
                try {
                    sem.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, tgt);
                    return;
                }
                long semWaitMs = System.currentTimeMillis() - semWaitStart;
                if (semWaitMs > 50) {
                    log.info("[PIPE-3-SEM-WAIT] segIdx={} lang={} semWaitMs={}ms",
                            segIdx, tgt, semWaitMs);
                }
                try {
                    for (int i = 0; i < translatedList.size(); i++) {
                        String translated = translatedList.get(i);
                        log.info("[PIPE-3-TTS] segIdx={} tgt={} sent={}/{} len={}",
                                segIdx, tgt, i + 1, translatedList.size(), translated.length());
                        try {
                            ttsService.synthesizeAndStream(translated, tgt, segIdx, roomId, roomWsHandler);
                        } catch (Exception e) {
                            log.error("[PIPE-3-TTS-ERR] segIdx={} tgt={} sent={}/{} error={}",
                                    segIdx, tgt, i + 1, translatedList.size(), e.getMessage());
                        }
                    }
                } finally {
                    roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, tgt);
                    sem.release();
                }
            }, executor);
        }
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

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
            asrWsHandler.sendToHost(roomId, evJson);
            roomWsHandler.broadcastJsonToAll(roomId, evJson);
        } catch (JsonProcessingException e) {
            log.error("[PIPE-3-BROADCAST-ERR] segIdx={} tgt={} error={}", segIdx, targetLang, e.getMessage());
        }
    }

    /**
     * 按标点将文本切分为子句列表。
     *
     * <ul>
     *   <li>全文 ≤ SENT_MAX_LEN：直接返回单元素列表，不做任何切分</li>
     *   <li>句末标点（缓冲区 ≥ SENT_SOFT_MIN）：立即切</li>
     *   <li>逗号（缓冲区 ≥ SENT_MAX_LEN）：切（避免短句碎片化）</li>
     *   <li>缓冲区 ≥ SENT_FORCE_LEN：强制切，优先在末尾空格处切以保留词边界</li>
     * </ul>
     */
    static List<String> splitSentences(String text) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() <= SENT_MAX_LEN) return List.of(text);

        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);
            int len = buf.length();

            // 句末标点：满足最短长度后立即切
            if (len >= SENT_SOFT_MIN
                    && (c == '。' || c == '！' || c == '？' || c == '；'
                        || c == '.' || c == '!' || c == '?' || c == ';')) {
                flush(buf, result);
                continue;
            }

            // 逗号：超过最大长度才切
            if (len >= SENT_MAX_LEN && (c == '，' || c == ',')) {
                flush(buf, result);
                continue;
            }

            // 强制切兜底：优先在末尾空格处切（英语词边界），否则硬切
            if (len >= SENT_FORCE_LEN) {
                String s = buf.toString();
                int lastSpace = s.lastIndexOf(' ');
                if (lastSpace > SENT_SOFT_MIN) {
                    result.add(s.substring(0, lastSpace).strip());
                    buf.setLength(0);
                    buf.append(s.substring(lastSpace + 1));
                } else {
                    flush(buf, result);
                }
            }
        }
        flush(buf, result);
        return result;
    }

    private static void flush(StringBuilder buf, List<String> result) {
        String s = buf.toString().strip();
        if (!s.isBlank()) result.add(s);
        buf.setLength(0);
    }
}
