package com.simultaneousinterpretation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云百炼（DashScope）统一认证与模型配置。
 *
 * <p>ASR、翻译共用同一 API Key，只需在 {@code app.dashscope.api-key}
 * 或环境变量 {@code DASHSCOPE_API_KEY} 配置一次。
 */
@Component
@ConfigurationProperties(prefix = "app.dashscope")
public class DashScopeProperties {

  // ─── 统一认证 ─────────────────────────────────────────────────────────────

  private String apiKey = "";

  // ─── ASR ─────────────────────────────────────────────────────────────────

  private String asrModel = "gummy-realtime-v1";
  private int asrSampleRate = 16000;
  private boolean asrSemanticPunctuation = false;
  private String asrWsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";

  // ─── 翻译（OpenAI 兼容 HTTP，供 TranslateService 使用）──────────────────

  private String translateModel = "qwen-mt-plus";
  private String translateBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

  // ─── 意译压缩（步骤二）────────────────────────────────────────────────

  private String compressionModel = "qwen3-max";

  // ─── 工具方法 ─────────────────────────────────────────────────────────────

  /** 优先用 fallbackKey，为空则用本类统一 apiKey */
  public String getEffectiveApiKey(String fallbackKey) {
    return (fallbackKey != null && !fallbackKey.isBlank()) ? fallbackKey : apiKey;
  }

  // ─── Getters / Setters ────────────────────────────────────────────────────

  public String getApiKey() { return apiKey; }
  public void setApiKey(String apiKey) { this.apiKey = apiKey; }

  public String getAsrModel() { return asrModel; }
  public void setAsrModel(String asrModel) { this.asrModel = asrModel; }

  public int getAsrSampleRate() { return asrSampleRate; }
  public void setAsrSampleRate(int asrSampleRate) { this.asrSampleRate = asrSampleRate; }

  public boolean isAsrSemanticPunctuation() { return asrSemanticPunctuation; }
  public void setAsrSemanticPunctuation(boolean asrSemanticPunctuation) { this.asrSemanticPunctuation = asrSemanticPunctuation; }

  public String getAsrWsUrl() { return asrWsUrl; }
  public void setAsrWsUrl(String asrWsUrl) { this.asrWsUrl = asrWsUrl; }

  public String getTranslateModel() { return translateModel; }
  public void setTranslateModel(String translateModel) { this.translateModel = translateModel; }

  public String getTranslateBaseUrl() { return translateBaseUrl; }
  public void setTranslateBaseUrl(String translateBaseUrl) { this.translateBaseUrl = translateBaseUrl; }

  public String getCompressionModel() { return compressionModel; }
  public void setCompressionModel(String compressionModel) { this.compressionModel = compressionModel; }
}
