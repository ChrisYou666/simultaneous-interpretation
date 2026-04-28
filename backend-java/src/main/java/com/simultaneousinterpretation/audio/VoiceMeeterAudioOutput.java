package com.simultaneousinterpretation.audio;

import com.simultaneousinterpretation.config.AudioOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 把直通音频和 TTS PCM 写入 VoiceMeeter 虚拟音频设备。
 *
 * 每种语言两条独立 SourceDataLine + 各自专属写线程：
 *   passthroughLine (16kHz): 说话人原声，passthrough 写队列 → 写线程 → line
 *   ttsLine        (24kHz):  TTS 合成音频，TTS 写队列 → 写线程 → line
 *
 * 写线程是各自 line 的唯一写入方，彻底消除并发写。
 * 写线程 poll 间隔 100ms，队列空时阻塞等待，不补静音帧。
 * 出现 underrun 时由 VoiceMeeter / 操作系统混音器处理，不主动注入静音。
 */
@Service
public class VoiceMeeterAudioOutput {

    private static final Logger log = LoggerFactory.getLogger(VoiceMeeterAudioOutput.class);

    private static final AudioFormat PASSTHROUGH_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 16000f, 16, 1, 2, 16000f, false);
    private static final AudioFormat TTS_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, 24000f, 16, 1, 2, 24000f, false);

    private static final int PASSTHROUGH_BUFFER_BYTES = 32000;  // 1s @ 16kHz
    private static final int TTS_BUFFER_BYTES         = 96000;  // 2s @ 24kHz

    private static final int TTS_CHUNK_BYTES = 960; // 20ms @ 24kHz，TTS 分块入队粒度

    private final AudioOutputProperties props;

    // ── passthrough 专用资源（16kHz）─────────────────────────────────────────

    /** lang → passthrough 写队列（capacity=200，约 20s 缓冲；满时丢帧） */
    private final Map<String, LinkedBlockingQueue<byte[]>> ptQueues  = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean>               ptRunning = new ConcurrentHashMap<>();

    // ── TTS 专用资源（24kHz）─────────────────────────────────────────────────

    /** lang → TTS 写队列（capacity=500，约 10s 缓冲；TTS doWrite 阻塞等空间，不丢帧） */
    private final Map<String, LinkedBlockingQueue<byte[]>> ttsQueues  = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean>               ttsRunning = new ConcurrentHashMap<>();

    /**
     * 每个写队列的淡入状态：队列排空后（队列 poll 超时）重新有数据写入时，
     * 对写入的第一个 chunk 做淡入处理，防止幅度跳变产生音爆。
     * 键为 queue 引用，一个 queue 只会对应一个写线程，安全地用普通引用比较。
     */
    private final Map<LinkedBlockingQueue<byte[]>, AtomicBoolean> needsFadeIn =
            new ConcurrentHashMap<>();

    // ── TTS 排序播放（session 隔离）──────────────────────────────────────────

    record TtsFrame(byte[] pcm, long enqueueMs, long asrFinalMs) {}

    private final Map<String, ConcurrentSkipListMap<Integer, TtsFrame>> sessionBufs    = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger>                             sessionExpected = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean>                             sessionReady    = new ConcurrentHashMap<>();
    private final Map<String, Object>                                    sessionLocks    = new ConcurrentHashMap<>();

    public VoiceMeeterAudioOutput(AudioOutputProperties props) {
        this.props = props;
        log.info("[AUDIO-OUT] 初始化完成，音频设备配置: zh=\"{}\" id=\"{}\"",
                props.getDevice("zh"), props.getDevice("id"));
        dumpAvailableMixers();
    }

    private void dumpAvailableMixers() {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        log.info("[AUDIO-OUT] ===== 可用混音器列表（{} 个）=====", infos.length);
        for (Mixer.Info info : infos) {
            log.info("[AUDIO-OUT]   Mixer: name=\"{}\" desc=\"{}\"", info.getName(), info.getDescription());
        }
        log.info("[AUDIO-OUT] ===========================================");
    }

    // ── 写线程（通用）────────────────────────────────────────────────────────

    private void startWriteThread(String label, LinkedBlockingQueue<byte[]> queue,
                                  AtomicBoolean running, SourceDataLine line) {
        // 初始化淡入状态：首次写入不需要淡入（冷启动时 line buffer 是空的，API 内部静音）
        AtomicBoolean fadeInFlag = needsFadeIn.computeIfAbsent(queue, k -> new AtomicBoolean(false));
        Thread t = new Thread(() -> {
            log.info("[AUDIO-OUT] 写线程启动 {}", label);
            while (running.get()) {
                try {
                    byte[] pcm = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (pcm != null) {
                        // 队列在 poll 超时期间排空：重新有数据时需要淡入
                        if (fadeInFlag.get()) {
                            pcm = fadeIn(pcm);
                            fadeInFlag.set(false);
                        }
                        line.write(pcm, 0, pcm.length);
                    }
                    // poll 超时说明队列空：标记需要淡入，下次写入时生效
                    // 不补静音帧，由 VoiceMeeter / OS 混音器处理 underrun
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("[AUDIO-OUT] 写线程异常 {}: {}", label, e.getMessage());
                }
            }
            try { line.drain(); line.close(); } catch (Exception ignored) {}
            log.info("[AUDIO-OUT] 写线程退出 {}", label);
        }, "audio-write-" + label);
        t.setDaemon(true);
        t.start();
    }

    // ── 直通（说话人原声，16kHz）─────────────────────────────────────────────

    /**
     * 写入直通音频（16kHz PCM），非阻塞放入写队列。
     * 可安全在 WS IO 线程调用；队列满时丢帧并记录警告。
     */
    public void writePassthrough(String lang, byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        String targetLang = (lang != null && !lang.isBlank()) ? lang : "zh";
        LinkedBlockingQueue<byte[]> queue = ptQueues.computeIfAbsent(targetLang, k -> {
            LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>(200);
            AtomicBoolean running = new AtomicBoolean(true);
            ptRunning.put(k, running);
            SourceDataLine line = openLine(k, PASSTHROUGH_FORMAT, PASSTHROUGH_BUFFER_BYTES);
            if (line != null) startWriteThread("pt-" + k, q, running, line);
            return q;
        });
        if (!queue.offer(pcm)) {
            log.warn("[F-THRU] passthrough队列满 lang={} bytes={}", targetLang, pcm.length);
        }
    }

    // ── TTS 按 segIdx 排序播放（session 隔离）────────────────────────────────

    public boolean enqueueTts(String sessionId, String lang, int segIdx, byte[] pcm,
                              long enqueueMs, long asrFinalMs) {
        if (pcm == null || pcm.length == 0) return true;
        String key = sessionId + ":" + lang;

        AtomicInteger expected = sessionExpected.computeIfAbsent(key, k -> new AtomicInteger(0));
        if (segIdx < expected.get() - 30) {
            log.warn("[TTS-ENQUEUE] key={} segIdx={} stale (expected≥{}), dropped sinceAsrMs={}",
                    key, segIdx, expected.get(), System.currentTimeMillis() - asrFinalMs);
            return false;
        }

        // computeIfAbsent 返回后 map entry 才对其他线程可见，
        // 必须在 lambda 外部启动 player 线程，否则线程拿到的 buf 引用为 null 并立即退出。
        ConcurrentSkipListMap<Integer, TtsFrame> buf =
                sessionBufs.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
        startPlayerThread(key, lang, buf);

        buf.put(segIdx, new TtsFrame(pcm, enqueueMs, asrFinalMs));
        log.debug("[TTS-ENQUEUE] key={} segIdx={} bytes={} bufSize={} sinceAsrMs={}",
                key, segIdx, pcm.length, buf.size(), System.currentTimeMillis() - asrFinalMs);

        sessionReady.computeIfAbsent(key, k -> new AtomicBoolean(false)).set(true);
        Object lock = sessionLocks.get(key);
        if (lock != null) { synchronized (lock) { lock.notify(); } }
        return true;
    }

    private void startPlayerThread(String key, String lang,
                                   ConcurrentSkipListMap<Integer, TtsFrame> buf) {
        // putIfAbsent 原子保证只启动一个 player 线程
        Object newLock = new Object();
        if (sessionLocks.putIfAbsent(key, newLock) != null) return; // 已启动
        sessionExpected.putIfAbsent(key, new AtomicInteger(0));
        sessionReady.put(key, new AtomicBoolean(false));
        // buf 直接传入，无需从 map 二次查找，避免 computeIfAbsent 内启动线程时的可见性竞争
        Thread t = new Thread(() -> playLoop(key, lang, buf, newLock), "tts-player-" + key);
        t.setDaemon(true);
        t.start();
        log.info("[AUDIO-OUT] 启动 TTS 播放线程 key={}", key);
    }

    private void playLoop(String key, String lang,
                          ConcurrentSkipListMap<Integer, TtsFrame> buf, Object lock) {
        AtomicInteger expected = sessionExpected.get(key);
        if (expected == null) return;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                synchronized (lock) {
                    while (sessionReady.get(key) == null || !sessionReady.get(key).get()) {
                        lock.wait(500);
                        if (Thread.currentThread().isInterrupted()) break;
                    }
                    AtomicBoolean ready = sessionReady.get(key);
                    if (ready != null) ready.set(false);
                }

                Map.Entry<Integer, TtsFrame> entry = buf.pollFirstEntry();
                if (entry == null) continue;

                int segIdx = entry.getKey();
                int exp    = expected.get();

                if (segIdx < exp) {
                    log.debug("[TTS-PLAY] key={} skip stale segIdx={} (expected={})", key, segIdx, exp);
                    continue;
                }

                if (segIdx > exp) {
                    buf.put(segIdx, entry.getValue());
                    AtomicBoolean ready = sessionReady.get(key);
                    if (ready != null) ready.set(true);
                    long deadline = System.currentTimeMillis() + 3000;
                    synchronized (lock) {
                        while (expected.get() < segIdx && System.currentTimeMillis() < deadline) {
                            lock.wait(100);
                        }
                    }
                    if (expected.get() < segIdx) {
                        log.warn("[TTS-PLAY] key={} timeout waiting segIdx={} (expected={}), forcing",
                                key, segIdx, expected.get());
                    } else {
                        entry = buf.pollFirstEntry();
                        if (entry == null || entry.getKey() != segIdx) continue;
                    }
                }

                TtsFrame frame = entry.getValue();
                long playMs    = System.currentTimeMillis();
                long sinceAsrMs = playMs - frame.asrFinalMs();
                long sinceEnqMs = playMs - frame.enqueueMs();

                if (sinceAsrMs > 10_000) {
                    log.warn("[TTS-PLAY] LAG key={} segIdx={} sinceAsrMs={}ms sinceEnqMs={}ms",
                            key, segIdx, sinceAsrMs, sinceEnqMs);
                }
                log.info("[TTS-PLAY] ★ key={} segIdx={} bytes={} sinceAsrMs={} sinceEnqMs={} wallMs={}",
                        key, segIdx, frame.pcm().length, sinceAsrMs, sinceEnqMs, playMs);
                doWrite(lang, frame.pcm());
                expected.updateAndGet(cur -> Math.max(cur, segIdx + 1));

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[TTS-PLAY] key={} error: {}", key, e.getMessage(), e);
            }
        }
        log.info("[TTS-PLAY] key={} 播放线程退出", key);
    }

    public void stopSession(String sessionId) {
        List<String> keys = sessionBufs.keySet().stream()
                .filter(k -> k.startsWith(sessionId + ":"))
                .collect(Collectors.toList());
        for (String key : keys) {
            Object lock = sessionLocks.remove(key);
            if (lock != null) { synchronized (lock) { lock.notifyAll(); } }
            sessionReady.remove(key);
            sessionExpected.remove(key);
            ConcurrentSkipListMap<Integer, TtsFrame> buf = sessionBufs.remove(key);
            if (buf != null) buf.clear();
            log.info("[AUDIO-OUT] stopSession sessionId={} key={} 已清理", sessionId, key);
        }
    }

    /**
     * 将 TTS PCM 分成 20ms 小块放入 TTS 写队列，阻塞直到全部入队（TTS 不丢帧）。
     * 分块使 passthrough 帧可在 TTS 分块间隙被写线程处理，但两者在独立的 line 上，互不影响。
     */
    private void doWrite(String lang, byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        LinkedBlockingQueue<byte[]> queue = ttsQueues.computeIfAbsent(lang, k -> {
            LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>(500);
            AtomicBoolean running = new AtomicBoolean(true);
            ttsRunning.put(k, running);
            SourceDataLine line = openLine(k, TTS_FORMAT, TTS_BUFFER_BYTES);
            if (line != null) startWriteThread("tts-" + k, q, running, line);
            return q;
        });
        try {
            for (int off = 0; off < pcm.length; off += TTS_CHUNK_BYTES) {
                int end   = Math.min(off + TTS_CHUNK_BYTES, pcm.length);
                byte[] chunk = new byte[end - off];
                System.arraycopy(pcm, off, chunk, 0, chunk.length);
                queue.put(chunk); // 阻塞等队列有空间
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[AUDIO-OUT] doWrite interrupted lang={}", lang);
        }
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    @PreDestroy
    public void shutdown() {
        ptRunning.values().forEach(r -> r.set(false));
        ttsRunning.values().forEach(r -> r.set(false));
        sessionLocks.forEach((key, lock) -> { synchronized (lock) { lock.notifyAll(); } });
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        sessionLocks.clear(); sessionReady.clear(); sessionBufs.clear(); sessionExpected.clear();
        ptQueues.clear(); ttsQueues.clear(); ptRunning.clear(); ttsRunning.clear();
        needsFadeIn.clear();
    }

    // ── 设备查找 ──────────────────────────────────────────────────────────────

    private SourceDataLine openLine(String lang, AudioFormat format, int bufferBytes) {
        String keyword = props.getDevice(lang);
        Mixer.Info target = findMixer(keyword, format);
        try {
            SourceDataLine line;
            if (target != null) {
                line = AudioSystem.getSourceDataLine(format, target);
                log.info("[AUDIO-OUT] lang={} {}Hz → device=\"{}\" bufferBytes={}",
                        lang, (int) format.getSampleRate(), target.getName(), bufferBytes);
            } else {
                line = AudioSystem.getSourceDataLine(format);
                log.warn("[AUDIO-OUT] lang={} 未找到设备（关键字=\"{}\"），使用系统默认输出", lang, keyword);
            }
            line.open(format, bufferBytes);
            line.start();
            return line;
        } catch (LineUnavailableException e) {
            log.error("[AUDIO-OUT] lang={} 打开设备失败: {}", lang, e.getMessage());
            return null;
        }
    }

    private Mixer.Info findMixer(String keyword, AudioFormat format) {
        if (keyword == null || keyword.isBlank()) return null;
        String kw = keyword.toLowerCase();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
        List<String> candidates = new ArrayList<>();
        for (Mixer.Info info : infos) {
            String name = info.getName();
            if (name == null || name.startsWith("Port ")) continue;
            if (!name.toLowerCase().contains(kw)) continue;
            candidates.add(name);
            if (AudioSystem.getMixer(info).isLineSupported(lineInfo)) return info;
        }
        log.warn("[AUDIO-OUT] 未找到匹配\"{}\"的播放设备，候选: {}", keyword, candidates);
        return null;
    }

    // ── 淡入处理 ──────────────────────────────────────────────────────────────

    /**
     * PCM 淡入：前 N 个采样从 0 线性渐变到原始幅度。
     * 在队列排空后首次写入时调用，防止从静音直接跳变到满幅产生音爆。
     *
     * @param pcm 原始 PCM 数据（16bit signed，little-endian）
     * @return 淡入处理后的新 PCM 数组（副本）
     */
    private static byte[] fadeIn(byte[] pcm) {
        if (pcm == null || pcm.length < 4) return pcm;
        // 淡入 20ms = 160 采样 @ 8kHz，对应淡入窗口在 passthrough 和 TTS 通道都足够平滑
        int fadeSamples = Math.min(160, pcm.length >> 1);
        byte[] out = pcm.clone();
        for (int i = 0; i < fadeSamples; i++) {
            double ratio = (double) i / fadeSamples; // 0.0 → 1.0
            int idx = i << 1; // i * 2（byte index）
            int sample = shortSample(out, idx);
            int faded = (int) (sample * ratio);
            setShortSample(out, idx, faded);
        }
        return out;
    }

    private static int shortSample(byte[] b, int idx) {
        int s = (b[idx] & 0xFF) | (b[idx + 1] << 8);
        return s >= 0x8000 ? s - 0x10000 : s;
    }

    private static void setShortSample(byte[] b, int idx, int value) {
        if (value < 0) value += 0x10000;
        b[idx]     = (byte) (value & 0xFF);
        b[idx + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
