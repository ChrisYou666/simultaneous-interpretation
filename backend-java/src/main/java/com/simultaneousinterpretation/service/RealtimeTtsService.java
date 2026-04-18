package com.simultaneousinterpretation.service;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.common.ResultCallback;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.config.TtsProperties;
import com.simultaneousinterpretation.meeting.RoomWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;

/**
 * CosyVoice 流式 TTS 服务：将文本合成为 WAV 音频并逐帧广播给监听端。
 */
@Service
public class RealtimeTtsService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeTtsService.class);

    private final TtsProperties ttsProperties;
    private final DashScopeProperties dashScopeProperties;

    public RealtimeTtsService(TtsProperties ttsProperties, DashScopeProperties dashScopeProperties) {
        this.ttsProperties = ttsProperties;
        this.dashScopeProperties = dashScopeProperties;
    }

    /**
     * 同步调用 CosyVoice TTS，将音频帧流式广播到监听端。
     * 此方法阻塞直到 TTS 完成（或出错），应在独立线程中调用。
     *
     * @param text         合成文本
     * @param lang         语言（zh/en/id），用于选择音色
     * @param segIdx       段序号，写入二进制帧头供前端对齐
     * @param roomId       房间 ID
     * @param roomWsHandler 房间 WS 处理器（用于广播二进制帧）
     */
    public void synthesizeAndStream(String text, String lang, int segIdx,
                                    String roomId, RoomWebSocketHandler roomWsHandler) {
        if (!StringUtils.hasText(text)) {
            roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, lang);
            return;
        }

        String apiKey = StringUtils.hasText(ttsProperties.getApiKey())
                ? ttsProperties.getApiKey()
                : dashScopeProperties.getApiKey();

        if (!StringUtils.hasText(apiKey)) {
            log.warn("[TTS] API Key 未配置，跳过 lang={} segIdx={}", lang, segIdx);
            roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, lang);
            return;
        }

        String voice = ttsProperties.getVoice(lang);
        String model = ttsProperties.getModel();
        long startMs = System.currentTimeMillis();
        int[] chunkCount = {0};

        log.info("[PIPE-4-START] segIdx={} lang={} textLen={} model={} voice={} wallMs={}",
                segIdx, lang, text.length(), model, voice, startMs);

        long timeoutMs = ttsProperties.getTimeoutSec() * 1000L;
        SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .model(model)
                .voice(voice)
                .format(SpeechSynthesisAudioFormat.PCM_16000HZ_MONO_16BIT)
                .apiKey(apiKey)
                .firstPackageTimeout(timeoutMs)
                .connectionTimeout(timeoutMs)
                .build();

        SpeechSynthesizer synthesizer = new SpeechSynthesizer(param,
                new ResultCallback<SpeechSynthesisResult>() {
                    @Override
                    public void onEvent(SpeechSynthesisResult result) {
                        ByteBuffer audio = result.getAudioFrame();
                        if (audio == null || !audio.hasRemaining()) return;
                        byte[] bytes = new byte[audio.remaining()];
                        audio.get(bytes);
                        chunkCount[0]++;
                        if (chunkCount[0] == 1) {
                            long firstFrameMs = System.currentTimeMillis() - startMs;
                            log.info("[PIPE-4-FIRST-FRAME] segIdx={} lang={} firstFrameMs={}ms chunkBytes={} wallMs={}",
                                    segIdx, lang, firstFrameMs, bytes.length, System.currentTimeMillis());
                        } else {
                            log.debug("[PIPE-4-CHUNK] segIdx={} lang={} chunkIdx={} chunkBytes={}",
                                    segIdx, lang, chunkCount[0], bytes.length);
                        }
                        roomWsHandler.broadcastFramedAudioChunk(roomId, segIdx, lang, bytes);
                    }

                    @Override
                    public void onComplete() {
                        long totalMs = System.currentTimeMillis() - startMs;
                        log.info("[PIPE-4-DONE] segIdx={} lang={} chunks={} totalMs={}ms wallMs={}",
                                segIdx, lang, chunkCount[0], totalMs, System.currentTimeMillis());
                        roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, lang);
                    }

                    @Override
                    public void onError(Exception e) {
                        long durationMs = System.currentTimeMillis() - startMs;
                        log.error("[PIPE-4-ERROR] segIdx={} lang={} durationMs={}ms chunks={} error={} wallMs={}",
                                segIdx, lang, durationMs, chunkCount[0], e.getMessage(), System.currentTimeMillis());
                        roomWsHandler.broadcastFramedAudioEnd(roomId, segIdx, lang);
                    }
                });

        synthesizer.streamingCall(text);
        synthesizer.streamingComplete(ttsProperties.getTimeoutSec() * 1000L + 10_000L);
    }
}
