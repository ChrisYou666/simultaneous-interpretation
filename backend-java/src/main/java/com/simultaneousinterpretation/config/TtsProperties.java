package com.simultaneousinterpretation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CosyVoice TTS 配置。
 */
@ConfigurationProperties(prefix = "app.tts")
public class TtsProperties {

  private String apiKey = "";
  private String model = "cosyvoice-v3-flash";
  private String voiceZh = "longanyang";
  private String voiceId = "longanhuan";
  private int sampleRate = 24000;
  private int timeoutSec = 23;
  private int maxRetry = 2;
  private int maxConcurrent = 8;

  public String getApiKey() { return apiKey; }
  public void setApiKey(String apiKey) { this.apiKey = apiKey; }

  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }

  public String getVoiceZh() { return voiceZh; }
  public void setVoiceZh(String voiceZh) { this.voiceZh = voiceZh; }

  public String getVoiceId() { return voiceId; }
  public void setVoiceId(String voiceId) { this.voiceId = voiceId; }

  public int getSampleRate() { return sampleRate; }
  public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

  public int getTimeoutSec() { return timeoutSec; }
  public void setTimeoutSec(int timeoutSec) { this.timeoutSec = timeoutSec; }

  public int getMaxRetry() { return maxRetry; }
  public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }

  public int getMaxConcurrent() { return maxConcurrent; }
  public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }

  public String getVoice(String lang) {
    return switch (lang) {
      case "zh" -> voiceZh;
      default   -> voiceId;
    };
  }
}
