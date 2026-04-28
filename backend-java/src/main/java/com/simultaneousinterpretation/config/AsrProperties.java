package com.simultaneousinterpretation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ASR 配置（仅 DashScope gummy-realtime-v1）。
 */
@ConfigurationProperties(prefix = "app.asr")
public class AsrProperties {

  private String provider = "dashscope";
  private DashScope dashscope = new DashScope();

  public String getProvider() { return provider; }
  public void setProvider(String provider) { this.provider = provider; }

  public DashScope getDashscope() { return dashscope; }
  public void setDashscope(DashScope dashscope) { this.dashscope = dashscope; }

  public static class DashScope {

    private String apiKey = "";
    private String wsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";
    private String model = "gummy-realtime-v1";
    private int sampleRate = 16000;
    private String format = "pcm";
    private boolean semanticPunctuationEnabled = false;

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getWsUrl() { return wsUrl; }
    public void setWsUrl(String wsUrl) { this.wsUrl = wsUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getSampleRate() { return sampleRate; }
    public void setSampleRate(int sampleRate) { this.sampleRate = sampleRate; }

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public boolean isSemanticPunctuationEnabled() { return semanticPunctuationEnabled; }
    public void setSemanticPunctuationEnabled(boolean v) { this.semanticPunctuationEnabled = v; }

    public boolean isGummyModel() {
      return model != null && model.toLowerCase().contains("gummy");
    }
  }
}
