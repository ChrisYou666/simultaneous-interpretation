package com.simultaneousinterpretation.audio;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.simultaneousinterpretation.service.TtsConnectionPool;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 每种语言一个 TTS 顺序播放队列。
 *
 * <p>核心设计：翻译和 TTS 合成并行，但播放严格按 segmentTs 顺序。
 *
 * <pre>
 * Segment N  ──translate──▶  CompletableFuture
 *                                   │
 *                                   ▼ synthesise (parallel across all langs)
 *                                         │
 *                                         ▼ enqueue  [TtsTask{segmentTs=N, pcm}]
 *                                               │
 * Per-Lang Player Thread:  wait → poll → play(N) → wait → poll → play(N+1) ...
 * </pre>
 *
 * <p>不同语言的 Player Thread 完全独立（zh/en/id 并行播放）。
 * 同一语言内：合成并行（synthExecutor 线程池），播放串行（Player Thread）。
 */
@Component
public class TtsSequentialQueue {

    private static final Logger log = LoggerFactory.getLogger(TtsSequentialQueue.class);

    private final TtsConnectionPool ttsConnectionPool;
    private final VbCableAudioOutput audioOutput;
    private final Map<TtsConnectionPool.Lang, LangQueue> langQueues = new ConcurrentHashMap<>();

    public TtsSequentialQueue(TtsConnectionPool ttsConnectionPool, VbCableAudioOutput audioOutput) {
        this.ttsConnectionPool = ttsConnectionPool;
        this.audioOutput = audioOutput;
    }

    @PostConstruct
    public void init() {
        for (TtsConnectionPool.Lang lang : TtsConnectionPool.Lang.values()) {
            LangQueue queue = new LangQueue(lang, ttsConnectionPool, audioOutput);
            langQueues.put(lang, queue);
            queue.startPlayer();
        }
        log.info("[TSQ] TtsSequentialQueue 初始化完成，语言={}", langQueues.keySet());
    }

    @PreDestroy
    public void shutdown() {
        langQueues.values().forEach(LangQueue::shutdown);
        log.info("[TSQ] TtsSequentialQueue 已关闭");
    }

    /**
     * 异步合成一段文字并将 PCM 入队，按 segmentTs 顺序播放。
     *
     * <p>此方法立即返回，实际合成在后台线程完成。
     * 播放保证 segmentTs 递增，不会乱序。
     *
     * @param lang        目标语言
     * @param segmentTs   段落序号（全局递增，用于排序）
     * @param text         要合成的内容
     * @param voiceParams  语音参数
     */
    public void enqueue(TtsConnectionPool.Lang lang, long segmentTs, String text, SpeechSynthesisParam voiceParams) {
        LangQueue queue = langQueues.get(lang);
        if (queue == null) {
            log.warn("[TSQ] 未找到语言队列 lang={}", lang);
            return;
        }
        queue.submit(segmentTs, text, voiceParams);
    }

    /**
     * 语言切换时调用：停止接受新任务（已入队的继续播完），并重置序列号。
     * 与 resetAll 的区别：不清空已入队的任务，让播放器自然播完。
     *
     * @param seq 切换后的新序列号，播完旧任务后从此号开始
     */
    public void stopAcceptingOthers(String newLang, long newSeq) {
        for (Map.Entry<TtsConnectionPool.Lang, LangQueue> e : langQueues.entrySet()) {
            if (!e.getKey().name().equalsIgnoreCase(newLang)) {
                e.getValue().stopAndReset(newSeq);
            } else {
                e.getValue().resumeAccepting(newSeq);
            }
        }
        log.info("[TSQ] stopAcceptingOthers → newLang={} newSeq={}", newLang, newSeq);
    }

    /**
     * 语言切换时调用：清空所有语言队列的待播任务，并将 nextExpectedSeq 对齐到 newSeq。
     * 防止旧语言的 TTS 与新语言的 passthrough 音频同时在同一设备上播放。
     */
    public void resetAll(long newSeq) {
        langQueues.forEach((lang, queue) -> queue.resetTo(newSeq));
        log.info("[TSQ] resetAll → nextExpectedSeq={}", newSeq);
    }

    // ─── Per-Language Queue ────────────────────────────────────────────────────

    private static class LangQueue {

        private static final Logger log = LoggerFactory.getLogger(LangQueue.class);

        private final TtsConnectionPool.Lang lang;
        private final TtsConnectionPool ttsPool;
        private final VbCableAudioOutput audioOutput;

        /** segmentTs → TtsTask，按到达顺序排列（乱序合成，顺序播放） */
        private final ConcurrentHashMap<Long, TtsTask> pendingTasks = new ConcurrentHashMap<>();
        /** Player Thread 等待下一个 segmentTs */
        private final AtomicLong nextExpectedSeq = new AtomicLong(1);
        /** 每个语言有自己的合成线程池（并行合成），播放线程独立 */
        private final ExecutorService synthExecutor;

        /** 控制 Player Thread 等待/唤醒 */
        private final ReentrantLock playerLock = new ReentrantLock();
        private final Condition playerCondition = playerLock.newCondition();

        private volatile boolean stopped = false;
        /** 语言切换时标记：停止接受新任务（已入队的继续播完），播完后等待新序列号 */
        private volatile boolean acceptingOthers = true;
        /** 语言切换时标记：已停止接受新任务，等待队列排空 */
        private volatile boolean draining = false;
        private Thread playerThread;

        LangQueue(TtsConnectionPool.Lang lang, TtsConnectionPool ttsPool, VbCableAudioOutput audioOutput) {
            this.lang = lang;
            this.ttsPool = ttsPool;
            this.audioOutput = audioOutput;

            // 在构造函数中捕获 lang，避免 lambda 中的 blank final 问题
            TtsConnectionPool.Lang capturedLang = lang;
            this.synthExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "tsq-synth-" + capturedLang);
                t.setDaemon(true);
                return t;
            });
        }

        void startPlayer() {
            TtsConnectionPool.Lang capturedLang = lang;
            playerThread = new Thread(() -> {
                Thread.currentThread().setName("tsq-player-" + capturedLang);
                log.info("[TSQ-PLAYER] lang={} 播放器线程启动", capturedLang);
                while (!stopped) {
                    long expected = nextExpectedSeq.get();
                    TtsTask task = pendingTasks.remove(expected);

                    if (task == null) {
                        playerLock.lock();
                        try {
                            if (stopped) break;
                            if (draining && pendingTasks.isEmpty()) {
                                // 切换状态：队列已排空，等待源语言切回时的新任务（无限等待）
                                log.info("[TSQ-PLAYER] lang={} 队列已排空，等待源语言切回", capturedLang);
                                playerCondition.await();
                                continue;
                            }
                            boolean ok = playerCondition.awaitUntil(
                                    new java.util.Date(System.currentTimeMillis() + 10_000));
                            if (!ok && pendingTasks.isEmpty() && !draining) {
                                // 普通状态超时且队列为空，退出循环避免忙等待
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } finally {
                            playerLock.unlock();
                        }
                    } else {
                        playTask(task);
                        nextExpectedSeq.incrementAndGet();
                    }
                }
                log.info("[TSQ-PLAYER] lang={} 播放器线程退出", capturedLang);
            }, "tsq-player-" + capturedLang);
            playerThread.setDaemon(true);
            playerThread.start();
        }

        /**
         * 合成并入队。合成本身是并行的，但任务会按 segmentTs 排序后才播放。
         */
        private static final ResultCallback<SpeechSynthesisResult> NOOP_CB =
                new ResultCallback<>() {
                    @Override public void onEvent(SpeechSynthesisResult r) {}
                    @Override public void onComplete() {}
                    @Override public void onError(Exception e) {}
                };

        void submit(long segmentTs, String text, SpeechSynthesisParam voiceParams) {
            if (stopped) return;
            if (!acceptingOthers && segmentTs >= 0) {
                // 已在切换状态，不再接受新任务（由其他语言接管）
                log.debug("[TSQ-SUBMIT] lang={} segTs={} 已在切换，拒绝", lang, segmentTs);
                return;
            }

            TtsConnectionPool.Lang capturedLang = lang;

            synthExecutor.submit(() -> {
                if (stopped || text == null || text.isBlank()) return;

                SpeechSynthesizer synth = null;
                try {
                    synth = ttsPool.borrow(capturedLang);

                    // Collect PCM frames directly from Flowable.
                    // updateParamAndCallback's onComplete() is NOT called by streamingCallAsFlowable,
                    // so we must gather audio from each Flowable item instead.
                    java.util.List<byte[]> chunks = new java.util.ArrayList<>();
                    synth.updateParamAndCallback(voiceParams, NOOP_CB);
                    synth.streamingCallAsFlowable(Flowable.just(text))
                            .blockingForEach(result -> {
                                ByteBuffer frame = result.getAudioFrame();
                                if (frame != null && frame.hasRemaining()) {
                                    byte[] buf = new byte[frame.remaining()];
                                    frame.get(buf);
                                    chunks.add(buf);
                                }
                            });

                    if (chunks.isEmpty()) {
                        log.warn("[TSQ-SYNTH] lang={} segTs={} 合成结果为空", capturedLang, segmentTs);
                        return;
                    }

                    int total = chunks.stream().mapToInt(c -> c.length).sum();
                    byte[] pcm = new byte[total];
                    int off = 0;
                    for (byte[] c : chunks) { System.arraycopy(c, 0, pcm, off, c.length); off += c.length; }

                    TtsTask task = new TtsTask(segmentTs, text);
                    task.setPcm(pcm);
                    enqueueTask(task);
                    log.debug("[TSQ-SYNTH] lang={} segTs={} 合成完成 pcmBytes={}", capturedLang, segmentTs, pcm.length);

                } catch (Exception e) {
                    log.error("[TSQ-SYNTH] lang={} segTs={} 合成失败: {}", capturedLang, segmentTs, e.getMessage());
                } finally {
                    if (synth != null) ttsPool.returnObject(capturedLang, synth);
                }
            });
        }

        private void enqueueTask(TtsTask task) {
            pendingTasks.put(task.segmentTs, task);
            playerLock.lock();
            try {
                playerCondition.signalAll();
            } finally {
                playerLock.unlock();
            }
            log.debug("[TSQ-ENQ] lang={} segTs={} 入队, pending={}",
                    lang, task.segmentTs, pendingTasks.size());
        }

        private static final double MIN_SPEED = 1.0;
        private static final double MAX_SPEED = 1.5;
        /** 积压段数达到此值时触发最高倍速 */
        private static final int MAX_PENDING_THRESHOLD = 4;

        private void playTask(TtsTask task) {
            if (task.pcm == null || task.pcm.length == 0) return;

            int pending = pendingTasks.size(); // 已合成、排在此段之后等待播放的段数
            double speed = computeSpeed(pending);
            byte[] pcm = speed > 1.001 ? resamplePcm(task.pcm, speed) : task.pcm;

            long startNs = System.nanoTime();
            audioOutput.write(lang.name().toLowerCase(), pcm);
            long durMs = (System.nanoTime() - startNs) / 1_000_000;
            log.debug("[TSQ-PLAY] lang={} segTs={} pending={} speed={} pcmSize={} playMs={}",
                    lang, task.segmentTs, pending, String.format("%.2f", speed), pcm.length, durMs);
        }

        /** pending 段数线性映射到 [MIN_SPEED, MAX_SPEED]。 */
        private static double computeSpeed(int pending) {
            if (pending <= 0) return MIN_SPEED;
            double ratio = Math.min(1.0, (double) pending / MAX_PENDING_THRESHOLD);
            return MIN_SPEED + ratio * (MAX_SPEED - MIN_SPEED);
        }

        /**
         * 对 16-bit LE Mono PCM 做线性插值重采样，输出时长 = 输入 / speed。
         * speed > 1 → 输出更短（快放）；pitch 会略微升高，同传场景可接受。
         */
        private static byte[] resamplePcm(byte[] input, double speed) {
            int inSamples = input.length / 2;
            int outSamples = (int) (inSamples / speed);
            byte[] output = new byte[outSamples * 2];
            for (int i = 0; i < outSamples; i++) {
                double srcPos = i * speed;
                int lo = (int) srcPos;
                int hi = Math.min(lo + 1, inSamples - 1);
                double frac = srcPos - lo;
                short sLo = (short) ((input[lo * 2] & 0xFF) | (input[lo * 2 + 1] << 8));
                short sHi = (short) ((input[hi * 2] & 0xFF) | (input[hi * 2 + 1] << 8));
                short sample = (short) Math.round(sLo * (1.0 - frac) + sHi * frac);
                output[i * 2]     = (byte) (sample & 0xFF);
                output[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
            }
            return output;
        }

        void resetTo(long seq) {
            int cleared = pendingTasks.size();
            pendingTasks.clear();
            nextExpectedSeq.set(seq);
            playerLock.lock();
            try {
                playerCondition.signalAll();
            } finally {
                playerLock.unlock();
            }
            log.info("[TSQ-RESET] lang={} nextExpectedSeq→{} cleared={}", lang, seq, cleared);
        }

        /**
         * 语言切换时调用：停止接受新任务，等待已入队的任务播完后重置序列号。
         * 不清空 pendingTasks，由播放器自然播完后再推进到新序列号。
         */
        void stopAndReset(long newSeq) {
            acceptingOthers = false;
            draining = true;
            nextExpectedSeq.set(newSeq);
            playerLock.lock();
            try {
                playerCondition.signalAll();
            } finally {
                playerLock.unlock();
            }
            log.info("[TSQ-STOP] lang={} 停止接受新任务, draining=true, 待播={}", lang, pendingTasks.size());
        }

        /**
         * 当某语言重新成为源语言时调用：恢复接受新任务，重新对齐序列号。
         * 与 stopAndReset 配合，防止该语言被永久"冻结"。
         */
        void resumeAccepting(long newSeq) {
            acceptingOthers = true;
            draining = false;
            nextExpectedSeq.set(newSeq);
            playerLock.lock();
            try {
                playerCondition.signalAll();
            } finally {
                playerLock.unlock();
            }
            log.info("[TSQ-RESUME] lang={} 恢复接受新任务, draining=false, nextExpectedSeq={}", lang, newSeq);
        }

        void shutdown() {
            stopped = true;
            playerLock.lock();
            try {
                playerCondition.signalAll();
            } finally {
                playerLock.unlock();
            }
            if (playerThread != null) {
                playerThread.interrupt();
                try {
                    playerThread.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synthExecutor.shutdown();
            try {
                if (!synthExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    synthExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                synthExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // ─── TtsTask ───────────────────────────────────────────────────────────────

    private static class TtsTask {
        final long segmentTs;
        final String text;
        byte[] pcm;

        TtsTask(long segmentTs, String text) {
            this.segmentTs = segmentTs;
            this.text = text;
        }

        void setPcm(byte[] pcm) {
            this.pcm = pcm;
        }
    }
}
