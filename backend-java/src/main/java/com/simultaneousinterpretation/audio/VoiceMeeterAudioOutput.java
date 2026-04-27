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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 TTS PCM 音频直接写入 VoiceMeeter 虚拟音频设备。
 *
 * 设备名通过 application.yml app.audio-output.device-zh/en/id 配置，子串匹配。
 * 每种语言独立的 SourceDataLine，保证各语言音频不交叉。
 *
 * 静音保活：后台线程每 20ms 向所有已打开的 line 写一帧静音，
 * 防止 Teams VAD 因长时间静默而切断音频流，避免下一句 TTS 开头被截断。
 *
 * 音频格式：24kHz PCM Signed 16-bit Mono Little-Endian（与 TTS 输出一致）。
 */
@Service
public class VoiceMeeterAudioOutput {

    private static final Logger log = LoggerFactory.getLogger(VoiceMeeterAudioOutput.class);

    private static final AudioFormat FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            24000f,
            16,
            1,
            2,
            24000f,
            false
    );

    // 20ms 保活帧：±16 振幅（约 -66dB），让 Teams VAD 认为通道有信号，防止自动恢复原始音频
    private static final byte[] SILENCE_FRAME = generateKeepAliveFrame();

    private static byte[] generateKeepAliveFrame() {
        byte[] frame = new byte[480]; // 20ms × 24000Hz × 2字节
        java.util.Random rng = new java.util.Random(12345L);
        int samples = frame.length / 2;
        for (int i = 0; i < samples; i++) {
            short s = (short) (rng.nextInt(33) - 16); // ±16 ≈ -66dB
            frame[i * 2]     = (byte) (s & 0xFF);
            frame[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return frame;
    }

    private final AudioOutputProperties props;

    /** lang → open SourceDataLine */
    private final Map<String, SourceDataLine> lines = new ConcurrentHashMap<>();

    /** 是否正在写 TTS 数据（写 TTS 时跳过静音帧，避免竞争） */
    private final Map<String, Boolean> writing = new ConcurrentHashMap<>();

    private final AtomicLong totalWrittenBytes = new AtomicLong(0);
    private final AtomicLong totalWriteCalls = new AtomicLong(0);

    private final ScheduledExecutorService keepAliveExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "audio-keepalive");
                t.setDaemon(true);
                return t;
            });

    public VoiceMeeterAudioOutput(AudioOutputProperties props) {
        this.props = props;
        log.info("[AUDIO-OUT] 初始化完成，音频设备配置: zh=\"{}\" en=\"{}\" id=\"{}\"",
                props.getDevice("zh"), props.getDevice("en"), props.getDevice("id"));
        dumpAvailableMixers();
        keepAliveExecutor.scheduleAtFixedRate(this::writeKeepAlive, 100, 20, TimeUnit.MILLISECONDS);
    }

    /** 启动时打印所有可用的混音器（帮助诊断设备匹配问题） */
    private void dumpAvailableMixers() {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        log.info("[AUDIO-OUT] ===== 可用混音器列表（{} 个）=====", infos.length);
        for (Mixer.Info info : infos) {
            log.info("[AUDIO-OUT]   Mixer: name=\"{}\" desc=\"{}\"",
                    info.getName(), info.getDescription());
        }
        log.info("[AUDIO-OUT] ===========================================");
    }

    /**
     * 写入直通音频（原始说话人声音）。
     * 输入为 16kHz PCM 16-bit mono（ASR 上行格式），上采样至 24kHz 后写入源语言设备。
     * 非阻塞：TTS 占用通道时跳过本帧，避免阻塞 ASR 音频流导致丢帧。
     */
    public void writePassthrough(String lang, byte[] pcm16k) {
        if (pcm16k == null || pcm16k.length == 0) return;
        byte[] pcm24k = upsample16to24(pcm16k);
        String targetLang = (lang != null && !lang.isBlank()) ? lang : "zh";
        writeNonBlocking(targetLang, pcm24k);
    }

    /**
     * 非阻塞写入：TTS 占用通道时跳过本帧，避免阻塞调用方（ASR 音频帧处理线程）。
     * 与阻塞 write() 的区别：不在缓冲区满时等，直接丢弃本帧。
     */
    private void writeNonBlocking(String lang, byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        SourceDataLine line = lines.get(lang);
        if (line == null) return;  // 通道未打开（设备初始化时），静默跳过
        if (Boolean.TRUE.equals(writing.get(lang))) return;  // TTS 正在写，跳过
        int freeBytes = line.available();
        if (freeBytes < pcm.length) return;  // 缓冲区空间不足，跳过本帧（比阻塞更好）
        int written = line.write(pcm, 0, pcm.length);
        if (written < pcm.length) {
            log.warn("[AUDIO-OUT] passthrough lang={} buffer overflow: tried={} wrote={}",
                    lang, pcm.length, written);
        }
    }

    /** 线性插值上采样：16kHz → 24kHz（ratio = 1.5） */
    private static byte[] upsample16to24(byte[] input) {
        int inSamples = input.length / 2;
        int outSamples = (int) Math.round(inSamples * 1.5);
        byte[] output = new byte[outSamples * 2];
        for (int i = 0; i < outSamples; i++) {
            double srcPos = i / 1.5;
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

    /** 写入指定语言的 TTS PCM 音频。同一语言的 TTS 线程天然串行，无需额外同步。 */
    public void write(String lang, byte[] pcm) {
        if (pcm == null || pcm.length == 0) {
            log.warn("[AUDIO-OUT] write lang={} skipped: null or empty pcm", lang);
            return;
        }
        SourceDataLine line = lines.computeIfAbsent(lang, this::openLine);
        if (line == null) {
            log.warn("[AUDIO-OUT] write lang={} skipped: line is null (device unavailable)", lang);
            return;
        }
        writing.put(lang, true);
        try {
            int written = line.write(pcm, 0, pcm.length);
            totalWrittenBytes.addAndGet(written);
            totalWriteCalls.incrementAndGet();
            if (totalWriteCalls.get() % 50 == 1) {
                log.info("[AUDIO-OUT] write lang={} bytes={} totalCalls={} totalBytes={}",
                        lang, pcm.length, totalWriteCalls.get(), totalWrittenBytes.get());
            }
        } catch (Exception e) {
            log.error("[AUDIO-OUT] write lang={} failed: {}", lang, e.getMessage(), e);
        } finally {
            writing.put(lang, false);
        }
    }

    private void writeKeepAlive() {
        if (lines.isEmpty()) return;
        int totalSilentFrames = 0;
        for (Map.Entry<String, SourceDataLine> e : lines.entrySet()) {
            String lang = e.getKey();
            SourceDataLine line = e.getValue();
            if (Boolean.TRUE.equals(writing.get(lang))) continue;  // TTS 正在写，跳过
            if (line.isOpen()) {
                try {
                    line.write(SILENCE_FRAME, 0, SILENCE_FRAME.length);
                    totalSilentFrames++;
                } catch (Exception ex) {
                    log.warn("[AUDIO-OUT] keepalive write lang={} failed: {}", lang, ex.getMessage());
                }
            }
        }
        if (totalSilentFrames > 0 && log.isDebugEnabled()) {
            log.debug("[AUDIO-OUT] keepalive sent to {} lines, {} active lines={}",
                    totalSilentFrames, lines.size(), lines.keySet());
        }
    }

    @PreDestroy
    public void shutdown() {
        keepAliveExecutor.shutdownNow();
        lines.forEach((lang, line) -> {
            try {
                line.drain();
                line.close();
            } catch (Exception e) {
                log.warn("[AUDIO-OUT] close failed lang={}: {}", lang, e.getMessage());
            }
        });
        lines.clear();
    }

    private SourceDataLine openLine(String lang) {
        String keyword = props.getDevice(lang);
        Mixer.Info target = findMixer(keyword);

        try {
            SourceDataLine line;
            if (target != null) {
                line = AudioSystem.getSourceDataLine(FORMAT, target);
                log.info("[AUDIO-OUT] lang={} → device=\"{}\"", lang, target.getName());
            } else {
                line = AudioSystem.getSourceDataLine(FORMAT);
                log.warn("[AUDIO-OUT] lang={} 未找到设备（关键字=\"{}\"），使用系统默认输出", lang, keyword);
            }
            line.open(FORMAT, 48000);  // 2秒缓冲区（24000Hz × 2字节 × 2秒）
            line.start();
            return line;
        } catch (LineUnavailableException e) {
            log.error("[AUDIO-OUT] lang={} 打开设备失败: {}", lang, e.getMessage());
            return null;
        }
    }

    /**
     * 找第一个名称包含 keyword（不区分大小写）且支持 PCM 播放的设备。
     * 跳过 "Port " 前缀的 Port Mixer（音量控制设备，不能写 PCM 数据）。
     */
    private Mixer.Info findMixer(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        String kw = keyword.toLowerCase();
        Mixer.Info[] infos = AudioSystem.getMixerInfo();
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, FORMAT);
        List<String> candidates = new ArrayList<>();

        for (Mixer.Info info : infos) {
            String name = info.getName();
            if (name == null) continue;
            if (name.startsWith("Port ")) continue;
            if (!name.toLowerCase().contains(kw)) continue;
            candidates.add(name);
            Mixer mixer = AudioSystem.getMixer(info);
            if (mixer.isLineSupported(lineInfo)) {
                return info;
            }
        }
        log.warn("[AUDIO-OUT] 未找到匹配\"{}\"的播放设备，候选: {}", keyword, candidates);
        return null;
    }
}
