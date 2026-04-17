package com.simultaneousinterpretation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云百炼（DashScope / 阿里云 OpenAI 兼容 API）统一认证配置。
 *
 * <p>所有调用百炼服务的组件（ASR Fun-ASR、翻译、通义试译、TTS CosyVoice）共享同一个 API Key，
 * 只需在 {@code app.dashscope.api-key}（或环境变量 {@code DASHSCOPE_API_KEY}）配置一次，
 * 各服务通过各自的模型字段独立指定使用的模型，互不干扰。
 *
 * <p>各服务默认值：
 * <ul>
 *   <li>ASR：{@code fun-asr-realtime}（采样率 16000，WebSocket）</li>
 *   <li>翻译：{@code qwen-turbo}（兼容 OpenAI HTTP）</li>
 *   <li>TTS：{@code cosyvoice-v3-flash}（SSE HTTP）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.dashscope")
public class DashScopeProperties {

  // 日志在初始化完成后由 Spring 通过构造函数输出，若需要可在 PostConstruct 中使用

  // ─── 统一认证 ─────────────────────────────────────

  /**
   * 百炼 API Key。与阿里云控制台 / 百炼控制台 API Key 完全相同。
   * 优先级：显式配置 &gt; 环境变量 {@code DASHSCOPE_API_KEY}。
   */
  private String apiKey = "";

  // ─── ASR Fun-ASR ─────────────────────────────────

  /** ASR Fun-ASR 模型，默认 fun-asr-realtime */
  private String asrModel = "fun-asr-realtime";

  /** ASR 采样率，Fun-ASR 要求 16000 */
  private int asrSampleRate = 16000;

  /** ASR 语义断句（会议模式），默认 false（VAD 低延迟） */
  private boolean asrSemanticPunctuation = false;

  /** ASR WebSocket 地址（国际版需改为 dashscope-intl.aliyuncs.com） */
  private String asrWsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";

  // ─── 翻译 / 试译 ─────────────────────────────────

  /** 翻译模型，默认 qwen-turbo */
  private String translateModel = "qwen-turbo";

  /** 翻译 API base-url，默认百炼兼容模式 */
  private String translateBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

  // ─── TTS CosyVoice ────────────────────────────────

  /** TTS 模型，默认 cosyvoice-v3-flash */
  private String ttsModel = "cosyvoice-v3-flash";

  /** TTS 音色，默认 longanyang */
  private String ttsVoice = "longanyang";

  /** TTS 专用 API Key（可选，默认使用统一 apiKey） */
  private String ttsApiKey = "";

  // ─── 统一获取（兼容旧代码）────────────────────────

  /**
   * 兼容旧调用方式：如果 AsrProperties / AiProperties 等单独配置了 api-key，
   * 则返回该值；否则返回本类统一 api-key。
   * 新代码应优先注入本类直接使用。
   */
  public String getEffectiveApiKey(String fallbackKey) {
    return (fallbackKey != null && !fallbackKey.isBlank()) ? fallbackKey : apiKey;
  }

  // ─── Getters / Setters ────────────────────────────

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getAsrModel() {
    return asrModel;
  }

  public void setAsrModel(String asrModel) {
    this.asrModel = asrModel;
  }

  public int getAsrSampleRate() {
    return asrSampleRate;
  }

  public void setAsrSampleRate(int asrSampleRate) {
    this.asrSampleRate = asrSampleRate;
  }

  public boolean isAsrSemanticPunctuation() {
    return asrSemanticPunctuation;
  }

  public void setAsrSemanticPunctuation(boolean asrSemanticPunctuation) {
    this.asrSemanticPunctuation = asrSemanticPunctuation;
  }

  public String getAsrWsUrl() {
    return asrWsUrl;
  }

  public void setAsrWsUrl(String asrWsUrl) {
    this.asrWsUrl = asrWsUrl;
  }

  public String getTranslateModel() {
    return translateModel;
  }

  public void setTranslateModel(String translateModel) {
    this.translateModel = translateModel;
  }

  public String getTranslateBaseUrl() {
    return translateBaseUrl;
  }

  public void setTranslateBaseUrl(String translateBaseUrl) {
    this.translateBaseUrl = translateBaseUrl;
  }

  public String getTtsModel() {
    return ttsModel;
  }

  public void setTtsModel(String ttsModel) {
    this.ttsModel = ttsModel;
  }

  public String getTtsVoice() {
    return ttsVoice;
  }

  public void setTtsVoice(String ttsVoice) {
    this.ttsVoice = ttsVoice;
  }

  public String getTtsApiKey() {
    return ttsApiKey;
  }

  public void setTtsApiKey(String ttsApiKey) {
    this.ttsApiKey = ttsApiKey;
  }

  /**
   * 获取 TTS 专用 API Key（如果配置了的话），否则返回统一 apiKey。
   */
  public String getEffectiveTtsApiKey() {
    return (ttsApiKey != null && !ttsApiKey.isBlank()) ? ttsApiKey : apiKey;
  }
}
