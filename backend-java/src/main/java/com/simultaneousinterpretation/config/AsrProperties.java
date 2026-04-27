package com.simultaneousinterpretation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 流式 ASR：支持 DashScope Fun-ASR 或 Deepgram。
 */
@ConfigurationProperties(prefix = "app.asr")
public class AsrProperties {

  private String provider = "dashscope";

  private Deepgram deepgram = new Deepgram();
  private DashScope dashscope = new DashScope();
  private OpenAi openai = new OpenAi();
  /** 将 ASR final 文本切成翻译/TTS 段的策略（影响延迟与断句自然度） */
  private Segmentation segmentation = new Segmentation();

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public Deepgram getDeepgram() {
    return deepgram;
  }

  public void setDeepgram(Deepgram deepgram) {
    this.deepgram = deepgram;
  }

  public DashScope getDashscope() {
    return dashscope;
  }

  public void setDashscope(DashScope dashscope) {
    this.dashscope = dashscope;
  }

  public OpenAi getOpenai() {
    return openai;
  }

  public void setOpenai(OpenAi openai) {
    this.openai = openai;
  }

  public Segmentation getSegmentation() {
    return segmentation;
  }

  public void setSegmentation(Segmentation segmentation) {
    this.segmentation = segmentation;
  }

  public static class Segmentation {

    /** 单段最大字符数（达到后优先在标点处切分） */
    private int maxChars = 8;
    /** 至少积累该长度后才考虑在逗号等处软切 */
    private int softBreakChars = 4;
    /** 距上次切出超过该毫秒则强制刷出（降低尾句等待） */
    private long flushTimeoutMs = 400L;

    public int getMaxChars() {
      return maxChars;
    }

    public void setMaxChars(int maxChars) {
      this.maxChars = maxChars;
    }

    public int getSoftBreakChars() {
      return softBreakChars;
    }

    public void setSoftBreakChars(int softBreakChars) {
      this.softBreakChars = softBreakChars;
    }

    public long getFlushTimeoutMs() {
      return flushTimeoutMs;
    }

    public void setFlushTimeoutMs(long flushTimeoutMs) {
      this.flushTimeoutMs = flushTimeoutMs;
    }
  }

  public static class Deepgram {

    private String apiKey = "";
    private String model = "nova-2";
    private int sampleRate = 48000;
    private boolean interimResults = true;
    private boolean detectLanguage = true;

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public int getSampleRate() {
      return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
      this.sampleRate = sampleRate;
    }

    public boolean isInterimResults() {
      return interimResults;
    }

    public void setInterimResults(boolean interimResults) {
      this.interimResults = interimResults;
    }

    public boolean isDetectLanguage() {
      return detectLanguage;
    }

    public void setDetectLanguage(boolean detectLanguage) {
      this.detectLanguage = detectLanguage;
    }
  }

    /** 阿里云 DashScope Fun-ASR / Gummy 实时（WebSocket 协议，非 OpenAI 兼容路径） */
  public static class DashScope {

    /** 与百炼控制台 API Key 相同，建议 DASHSCOPE_API_KEY */
    private String apiKey = "";

    /**
     * 中国大陆：wss://dashscope.aliyuncs.com/api-ws/v1/inference/<br>
     * 国际：wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference/
     */
    private String wsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";

    /** Fun-ASR: fun-asr-realtime | Gummy: gummy-realtime-v1 */
    private String model = "gummy-realtime-v1";

    /** fun-asr-realtime 文档要求 16000Hz */
    private int sampleRate = 16000;

    /** pcm（麦克风 raw）或 wav */
    private String format = "pcm";

    /**
     * true：语义断句（会议）；false：VAD 断句（低延迟交互）
     */
    private boolean semanticPunctuationEnabled = false;

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getWsUrl() {
      return wsUrl;
    }

    public void setWsUrl(String wsUrl) {
      this.wsUrl = wsUrl;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public int getSampleRate() {
      return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
      this.sampleRate = sampleRate;
    }

    public String getFormat() {
      return format;
    }

    public void setFormat(String format) {
      this.format = format;
    }

    public boolean isSemanticPunctuationEnabled() {
      return semanticPunctuationEnabled;
    }

    public void setSemanticPunctuationEnabled(boolean semanticPunctuationEnabled) {
      this.semanticPunctuationEnabled = semanticPunctuationEnabled;
    }

    /** 判断当前配置的模型是否为 Gummy 系列（Gummy 在 result-generated 中有 language 字段但结构不同） */
    public boolean isGummyModel() {
      String m = (model != null) ? model.toLowerCase() : "";
      return m.contains("gummy");
    }
  }

  /**
   * OpenAI Realtime API 语音识别 (gpt-4o-transcribe 模型)
   */
  public static class OpenAi {

    /** OpenAI API Key (支持 OpenAI 官方或兼容 API 如 groq、azure openai 等) */
    private String apiKey = "";

    /** API 基础 URL，默认为 OpenAI 官方。也可设置为兼容的代理服务 */
    private String baseUrl = "https://api.openai.com/v1";

    /** 语音识别模型，支持 gpt-4o-transcribe、gpt-4o-mini-transcribe、whisper-1 */
    private String model = "gpt-4o-transcribe";

    /** OpenAI Realtime API 要求 24kHz */
    private int sampleRate = 24000;

    /** 输入语言，ISO-639-1 代码，如 zh、en、id。为空则自动检测 */
    private String language = "";

    /** 是否启用 VAD (Voice Activity Detection) */
    private boolean vadEnabled = true;

    /** VAD 前缀填充毫秒数 */
    private int vadPrefixPaddingMs = 300;

    /** VAD 后缀填充毫秒数 */
    private int vadSuffixPaddingMs = 300;

    /** VAD 静默检测阈值 0.0-1.0 */
    private double vadThreshold = 0.5;

    public String getApiKey() {
      return apiKey;
    }

    public void setApiKey(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public int getSampleRate() {
      return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
      this.sampleRate = sampleRate;
    }

    public String getLanguage() {
      return language;
    }

    public void setLanguage(String language) {
      this.language = language;
    }

    public boolean isVadEnabled() {
      return vadEnabled;
    }

    public void setVadEnabled(boolean vadEnabled) {
      this.vadEnabled = vadEnabled;
    }

    public int getVadPrefixPaddingMs() {
      return vadPrefixPaddingMs;
    }

    public void setVadPrefixPaddingMs(int vadPrefixPaddingMs) {
      this.vadPrefixPaddingMs = vadPrefixPaddingMs;
    }

    public int getVadSuffixPaddingMs() {
      return vadSuffixPaddingMs;
    }

    public void setVadSuffixPaddingMs(int vadSuffixPaddingMs) {
      this.vadSuffixPaddingMs = vadSuffixPaddingMs;
    }

    public double getVadThreshold() {
      return vadThreshold;
    }

    public void setVadThreshold(double vadThreshold) {
      this.vadThreshold = vadThreshold;
    }
  }
}
