package com.simultaneousinterpretation.config;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 同传流水线统一调参配置。
 *
 * <p>支持两种模式：
 * <ol>
 *   <li>启动时从 application.yml 读取（静态配置）</li>
 *   <li>运行时通过 {@code /api/tuning/params} REST 接口动态修改（立即生效，无需重启）</li>
 * </ol>
 *
 * <p>参数分组说明：
 * <ul>
 *   <li><b>ASR 阶段</b>：采样率、语义断句等 ASR 本身参数（需重启会话）</li>
 *   <li><b>切段阶段</b>：maxChars、softBreakChars、flushTimeoutMs（立即生效）</li>
 *   <li><b>翻译阶段</b>：模型、maxTokens、temperature、timeout（缓存模型需重建）</li>
 *   <li><b>TTS 阶段</b>：语速、chunk阈值、积压系数（立即生效）</li>
 *   <li><b>播放阶段</b>：背压系数、积压上限（立即生效）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.tuning")
public class PipelineTuningParams {

  private static final Logger log = LoggerFactory.getLogger(PipelineTuningParams.class);

  // ─── ASR 阶段（需重启会话才生效）──────────────────────────────

  /** ASR 提供商：dashscope / deepgram */
  private String asrProvider = "dashscope";

  /** DashScope WebSocket URL */
  private String dashscopeWsUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";

  /** DashScope 模型名 */
  private String dashscopeModel = "fun-asr-realtime";

  /** DashScope 采样率 */
  private int dashscopeSampleRate = 16000;

  /** DashScope 语义断句（会议模式） */
  private boolean dashscopeSemanticPunctuation = false;

  /** Deepgram 采样率 */
  private int deepgramSampleRate = 48000;

  /** Deepgram 模型 */
  private String deepgramModel = "nova-2";

  // ─── 切段阶段（立即生效）────────────────────────────

  /** 单段最大字符数（中文基准） */
  private int segMaxChars = 50;

  /** 英语句子 maxChars 倍数（避免短句过度切分） */
  private double segEnMaxCharsMultiplier = 2.0;

  /** 英语句子 maxChars 最小值 */
  private int segEnMaxCharsMin = 10;

  /** 英语句子 maxChars 最大值 */
  private int segEnMaxCharsMax = 40;

  /** 印尼语 maxChars 倍数（印尼语词汇平均较长，句式结构与英语相似但虚词更多） */
  private double segIdMaxCharsMultiplier = 1.6;

  /** 印尼语 maxChars 最小值 */
  private int segIdMaxCharsMin = 10;

  /** 印尼语 maxChars 最大值 */
  private int segIdMaxCharsMax = 60;

  /** 至少积累该长度后才考虑在逗号等处软切 */
  private int segSoftBreakChars = 15;

  /** 距上次切出超过该毫秒则强制刷出（降低尾句等待） */
  private long segFlushTimeoutMs = 700L;

  /** 切段 tick 初始延迟（ms） */
  private long segTickInitialMs = 200L;

  /** 切段 tick 周期间隔（ms） */
  private long segTickPeriodMs = 400L;

  // ─── 翻译阶段 ─────────────────────────────

  /** 翻译模型（与 AiProperties.model 一致） */
  private String translateModel = "qwen-turbo";

  /** 翻译 baseUrl */
  private String translateBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** 翻译最大 token 数（影响生成延迟） */
  private int translateMaxTokens = 320;

  /** 翻译温度 */
  private double translateTemperature = 0.1;

  /** 翻译超时秒数 */
  private int translateTimeoutSec = 25;

  /** 翻译最大重试次数 */
  private int translateMaxRetries = 1;

  // ─── TTS 阶段 ─────────────────────────────

  /**
   * TTS 流中途切分阈值（字节）。{@code <=0} 表示不切分，仅在 SSE 结束后整段回调（完整 WAV，听众端最稳）。
   * {@code >0} 时首包更早，但除首包外往往不是独立 WAV。
   */
  private int ttsChunkThreshold = 0;

  /** TTS 最大并发数（参考商用同传方案，支持多语言并行） */
  private int ttsMaxConcurrent = 8;

  /** TTS 模型 */
  private String ttsModel = "cosyvoice-v3-flash";

  /** TTS 音色 */
  private String ttsVoice = "longanyang";

  /** TTS 语速（0.5-2.0） */
  private double ttsRate = 1.0;

  // ─── 播放/背压阶段 ─────────────────────────────

  /** 积压时每积压一段 TTS 语速增加系数（每积压段增加 rate += backlogCoeff） */
  private double ttsBacklogCoeff = 0.12;

  /** TTS 积压时语速增加上限（0 表示无上限） */
  private double ttsBacklogCap = 0.0;

  // ─── 运行时覆盖（内存，非持久化）────────────────────────────

  private final ConcurrentHashMap<String, Object> runtimeOverrides = new ConcurrentHashMap<>();

  @PostConstruct
  public void init() {
    log.info("[Tuning] 流水线参数初始化完成，当前配置：");
    logParams();
  }

  public void logParams() {
    log.info("[Tuning] === ASR ===");
    log.info("[Tuning]   asrProvider={}", asrProvider);
    log.info("[Tuning]   dashscopeWsUrl={}", dashscopeWsUrl);
    log.info("[Tuning]   dashscopeModel={}", dashscopeModel);
    log.info("[Tuning]   dashscopeSampleRate={}", dashscopeSampleRate);
    log.info("[Tuning]   dashscopeSemanticPunctuation={}", dashscopeSemanticPunctuation);
    log.info("[Tuning] === 切段 ===");
    log.info("[Tuning]   segMaxChars={}", segMaxChars);
    log.info("[Tuning]   segEnMaxCharsMultiplier={}", segEnMaxCharsMultiplier);
    log.info("[Tuning]   segEnMaxCharsMin={}", segEnMaxCharsMin);
    log.info("[Tuning]   segEnMaxCharsMax={}", segEnMaxCharsMax);
    log.info("[Tuning]   segIdMaxCharsMultiplier={}", segIdMaxCharsMultiplier);
    log.info("[Tuning]   segIdMaxCharsMin={}", segIdMaxCharsMin);
    log.info("[Tuning]   segIdMaxCharsMax={}", segIdMaxCharsMax);
    log.info("[Tuning]   segSoftBreakChars={}", segSoftBreakChars);
    log.info("[Tuning]   segFlushTimeoutMs={}", segFlushTimeoutMs);
    log.info("[Tuning]   segTickInitialMs={}", segTickInitialMs);
    log.info("[Tuning]   segTickPeriodMs={}", segTickPeriodMs);
    log.info("[Tuning] === 翻译 ===");
    log.info("[Tuning]   translateModel={}", translateModel);
    log.info("[Tuning]   translateBaseUrl={}", translateBaseUrl);
    log.info("[Tuning]   translateMaxTokens={}", translateMaxTokens);
    log.info("[Tuning]   translateTemperature={}", translateTemperature);
    log.info("[Tuning]   translateTimeoutSec={}", translateTimeoutSec);
    log.info("[Tuning] === TTS ===");
    log.info("[Tuning]   ttsRate={}", ttsRate);
    log.info("[Tuning]   ttsChunkThreshold={}", ttsChunkThreshold);
    log.info("[Tuning]   ttsMaxConcurrent={}", ttsMaxConcurrent);
    log.info("[Tuning]   ttsModel={}", ttsModel);
    log.info("[Tuning]   ttsVoice={}", ttsVoice);
    log.info("[Tuning] === 播放/背压 ===");
    log.info("[Tuning]   ttsBacklogCoeff={}", ttsBacklogCoeff);
    log.info("[Tuning]   ttsBacklogCap={}", ttsBacklogCap);
  }

  // ─── 动态更新接口 ─────────────────────────────

  /**
   * 动态更新参数，立即生效（覆盖启动时的 yml 配置）。
   * @param params key-value 对，key 必须是本类的属性名
   * @return 受影响的参数列表
   */
  public java.util.List<String> apply(Map<String, Object> params) {
    java.util.List<String> affected = new java.util.ArrayList<>();
    for (Map.Entry<String, Object> e : params.entrySet()) {
      String k = e.getKey();
      Object v = e.getValue();
      boolean ok = switch (k) {
        case "segMaxChars" -> setIfInt("segMaxChars", v, 8, 512, x -> segMaxChars = x);
        case "segEnMaxCharsMultiplier" -> setIfDouble("segEnMaxCharsMultiplier", v, 1.0, 4.0, x -> segEnMaxCharsMultiplier = x);
        case "segEnMaxCharsMin" -> setIfInt("segEnMaxCharsMin", v, 10, 200, x -> segEnMaxCharsMin = x);
        case "segEnMaxCharsMax" -> setIfInt("segEnMaxCharsMax", v, 20, 200, x -> segEnMaxCharsMax = x);
        case "segIdMaxCharsMultiplier" -> setIfDouble("segIdMaxCharsMultiplier", v, 1.0, 4.0, x -> segIdMaxCharsMultiplier = x);
        case "segIdMaxCharsMin" -> setIfInt("segIdMaxCharsMin", v, 10, 200, x -> segIdMaxCharsMin = x);
        case "segIdMaxCharsMax" -> setIfInt("segIdMaxCharsMax", v, 20, 200, x -> segIdMaxCharsMax = x);
        case "segSoftBreakChars" -> setIfInt("segSoftBreakChars", v, 4, 256, x -> segSoftBreakChars = x);
        case "segFlushTimeoutMs" -> setIfLong("segFlushTimeoutMs", v, 50, 10_000, x -> segFlushTimeoutMs = x);
        case "segTickInitialMs" -> setIfLong("segTickInitialMs", v, 10, 5000, x -> segTickInitialMs = x);
        case "segTickPeriodMs" -> setIfLong("segTickPeriodMs", v, 50, 5000, x -> segTickPeriodMs = x);
        case "translateMaxTokens" -> setIfInt("translateMaxTokens", v, 32, 2048, x -> translateMaxTokens = x);
        case "translateTemperature" -> setIfDouble("translateTemperature", v, 0.0, 2.0, x -> translateTemperature = x);
        case "translateTimeoutSec" -> setIfInt("translateTimeoutSec", v, 5, 120, x -> translateTimeoutSec = x);
        case "translateMaxRetries" -> setIfInt("translateMaxRetries", v, 0, 5, x -> translateMaxRetries = x);
        case "translateModel" -> setIfString("translateModel", v, x -> translateModel = x);
        case "ttsChunkThreshold" -> setIfInt("ttsChunkThreshold", v, 0, 65536, x -> ttsChunkThreshold = x);
        case "ttsMaxConcurrent" -> setIfInt("ttsMaxConcurrent", v, 1, 32, x -> ttsMaxConcurrent = x);
        case "ttsModel" -> setIfString("ttsModel", v, x -> ttsModel = x);
        case "ttsVoice" -> setIfString("ttsVoice", v, x -> ttsVoice = x);
        case "ttsRate" -> setIfDouble("ttsRate", v, 0.5, 2.0, x -> ttsRate = x);
        case "ttsBacklogCoeff" -> setIfDouble("ttsBacklogCoeff", v, 0.0, 0.5, x -> ttsBacklogCoeff = x);
        case "ttsBacklogCap" -> setIfDouble("ttsBacklogCap", v, 0.0, 2.0, x -> ttsBacklogCap = x);
        case "dashscopeSemanticPunctuation" -> setIfBool("dashscopeSemanticPunctuation", v, x -> dashscopeSemanticPunctuation = x);
        default -> false;
      };
      if (ok) {
        runtimeOverrides.put(k, v);
        affected.add(k);
      }
    }
    if (!affected.isEmpty()) {
      log.info("[Tuning] 动态更新参数: {}", affected);
      logParams();
    }
    return affected;
  }

  private boolean setIfInt(String name, Object v, int min, int max, java.util.function.IntConsumer setter) {
    if (v instanceof Number n) {
      int val = n.intValue();
      if (val < min || val > max) {
        log.warn("[Tuning] {}={} 超出范围 [{}, {}]，忽略", name, val, min, max);
        return false;
      }
      setter.accept(val);
      return true;
    }
    log.warn("[Tuning] {} 类型应为 Number，实为 {}", name, v.getClass().getSimpleName());
    return false;
  }

  private boolean setIfLong(String name, Object v, long min, long max, java.util.function.LongConsumer setter) {
    if (v instanceof Number n) {
      long val = n.longValue();
      if (val < min || val > max) {
        log.warn("[Tuning] {}={} 超出范围 [{}, {}]，忽略", name, val, min, max);
        return false;
      }
      setter.accept(val);
      return true;
    }
    log.warn("[Tuning] {} 类型应为 Number，实为 {}", name, v.getClass().getSimpleName());
    return false;
  }

  private boolean setIfDouble(String name, Object v, double min, double max, java.util.function.DoubleConsumer setter) {
    if (v instanceof Number n) {
      double val = n.doubleValue();
      if (val < min || val > max) {
        log.warn("[Tuning] {}={} 超出范围 [{}, {}]，忽略", name, val, min, max);
        return false;
      }
      setter.accept(val);
      return true;
    }
    log.warn("[Tuning] {} 类型应为 Number，实为 {}", name, v.getClass().getSimpleName());
    return false;
  }

  private boolean setIfString(String name, Object v, java.util.function.Consumer<String> setter) {
    if (v instanceof String s) {
      setter.accept(s);
      return true;
    }
    log.warn("[Tuning] {} 类型应为 String，实为 {}", name, v.getClass().getSimpleName());
    return false;
  }

  private boolean setIfBool(String name, Object v, java.util.function.Consumer<Boolean> setter) {
    if (v instanceof Boolean b) {
      setter.accept(b);
      return true;
    }
    if (v instanceof String s) {
      setter.accept(Boolean.parseBoolean(s));
      return true;
    }
    log.warn("[Tuning] {} 类型应为 Boolean，实为 {}", name, v.getClass().getSimpleName());
    return false;
  }

  // ─── 获取完整配置快照（供前端展示）────────────────────────────

  public Map<String, Object> snapshot() {
    return Map.ofEntries(
        // ASR
        Map.entry("asrProvider", asrProvider),
        Map.entry("dashscopeWsUrl", dashscopeWsUrl),
        Map.entry("dashscopeModel", dashscopeModel),
        Map.entry("dashscopeSampleRate", dashscopeSampleRate),
        Map.entry("dashscopeSemanticPunctuation", dashscopeSemanticPunctuation),
        Map.entry("deepgramSampleRate", deepgramSampleRate),
        Map.entry("deepgramModel", deepgramModel),
        // 切段
        Map.entry("segMaxChars", segMaxChars),
        Map.entry("segEnMaxCharsMultiplier", segEnMaxCharsMultiplier),
        Map.entry("segEnMaxCharsMin", segEnMaxCharsMin),
        Map.entry("segEnMaxCharsMax", segEnMaxCharsMax),
        Map.entry("segIdMaxCharsMultiplier", segIdMaxCharsMultiplier),
        Map.entry("segIdMaxCharsMin", segIdMaxCharsMin),
        Map.entry("segIdMaxCharsMax", segIdMaxCharsMax),
        Map.entry("segSoftBreakChars", segSoftBreakChars),
        Map.entry("segFlushTimeoutMs", segFlushTimeoutMs),
        Map.entry("segTickInitialMs", segTickInitialMs),
        Map.entry("segTickPeriodMs", segTickPeriodMs),
        // 翻译
        Map.entry("translateModel", translateModel),
        Map.entry("translateBaseUrl", translateBaseUrl),
        Map.entry("translateMaxTokens", translateMaxTokens),
        Map.entry("translateTemperature", translateTemperature),
        Map.entry("translateTimeoutSec", translateTimeoutSec),
        Map.entry("translateMaxRetries", translateMaxRetries),
        // TTS
        Map.entry("ttsRate", ttsRate),
        Map.entry("ttsChunkThreshold", ttsChunkThreshold),
        Map.entry("ttsMaxConcurrent", ttsMaxConcurrent),
        Map.entry("ttsModel", ttsModel),
        Map.entry("ttsVoice", ttsVoice),
        // 背压
        Map.entry("ttsBacklogCoeff", ttsBacklogCoeff),
        Map.entry("ttsBacklogCap", ttsBacklogCap)
    );
  }

  // Getters
  public String getAsrProvider() { return asrProvider; }
  public String getDashscopeWsUrl() { return dashscopeWsUrl; }
  public String getDashscopeModel() { return dashscopeModel; }
  public int getDashscopeSampleRate() { return dashscopeSampleRate; }
  public boolean isDashscopeSemanticPunctuation() { return dashscopeSemanticPunctuation; }
  public int getDeepgramSampleRate() { return deepgramSampleRate; }
  public String getDeepgramModel() { return deepgramModel; }
  public int getSegMaxChars() { return segMaxChars; }
  public double getSegEnMaxCharsMultiplier() { return segEnMaxCharsMultiplier; }
  public int getSegEnMaxCharsMin() { return segEnMaxCharsMin; }
  public int getSegEnMaxCharsMax() { return segEnMaxCharsMax; }
  public double getSegIdMaxCharsMultiplier() { return segIdMaxCharsMultiplier; }
  public int getSegIdMaxCharsMin() { return segIdMaxCharsMin; }
  public int getSegIdMaxCharsMax() { return segIdMaxCharsMax; }
  public int getSegSoftBreakChars() { return segSoftBreakChars; }
  public long getSegFlushTimeoutMs() { return segFlushTimeoutMs; }
  public long getSegTickInitialMs() { return segTickInitialMs; }
  public long getSegTickPeriodMs() { return segTickPeriodMs; }
  public String getTranslateModel() { return translateModel; }
  public String getTranslateBaseUrl() { return translateBaseUrl; }
  public int getTranslateMaxTokens() { return translateMaxTokens; }
  public double getTranslateTemperature() { return translateTemperature; }
  public int getTranslateTimeoutSec() { return translateTimeoutSec; }
  public int getTranslateMaxRetries() { return translateMaxRetries; }
  public int getTtsChunkThreshold() { return ttsChunkThreshold; }
  public int getTtsMaxConcurrent() { return ttsMaxConcurrent; }
  public String getTtsModel() { return ttsModel; }
  public String getTtsVoice() { return ttsVoice; }
  public double getTtsRate() { return ttsRate; }
  public double getTtsBacklogCoeff() { return ttsBacklogCoeff; }
  public double getTtsBacklogCap() { return ttsBacklogCap; }

  // Setters
  public void setAsrProvider(String v) { this.asrProvider = v; }
  public void setDashscopeWsUrl(String v) { this.dashscopeWsUrl = v; }
  public void setDashscopeModel(String v) { this.dashscopeModel = v; }
  public void setDashscopeSampleRate(int v) { this.dashscopeSampleRate = v; }
  public void setDashscopeSemanticPunctuation(boolean v) { this.dashscopeSemanticPunctuation = v; }
  public void setDeepgramSampleRate(int v) { this.deepgramSampleRate = v; }
  public void setDeepgramModel(String v) { this.deepgramModel = v; }
  public void setSegMaxChars(int v) { this.segMaxChars = v; }
  public void setSegEnMaxCharsMultiplier(double v) { this.segEnMaxCharsMultiplier = v; }
  public void setSegEnMaxCharsMin(int v) { this.segEnMaxCharsMin = v; }
  public void setSegEnMaxCharsMax(int v) { this.segEnMaxCharsMax = v; }
  public void setSegIdMaxCharsMultiplier(double v) { this.segIdMaxCharsMultiplier = v; }
  public void setSegIdMaxCharsMin(int v) { this.segIdMaxCharsMin = v; }
  public void setSegIdMaxCharsMax(int v) { this.segIdMaxCharsMax = v; }
  public void setSegSoftBreakChars(int v) { this.segSoftBreakChars = v; }
  public void setSegFlushTimeoutMs(long v) { this.segFlushTimeoutMs = v; }
  public void setSegTickInitialMs(long v) { this.segTickInitialMs = v; }
  public void setSegTickPeriodMs(long v) { this.segTickPeriodMs = v; }
  public void setTranslateModel(String v) { this.translateModel = v; }
  public void setTranslateBaseUrl(String v) { this.translateBaseUrl = v; }
  public void setTranslateMaxTokens(int v) { this.translateMaxTokens = v; }
  public void setTranslateTemperature(double v) { this.translateTemperature = v; }
  public void setTranslateTimeoutSec(int v) { this.translateTimeoutSec = v; }
  public void setTranslateMaxRetries(int v) { this.translateMaxRetries = v; }
  public void setTtsChunkThreshold(int v) { this.ttsChunkThreshold = v; }
  public void setTtsMaxConcurrent(int v) { this.ttsMaxConcurrent = v; }
  public void setTtsModel(String v) { this.ttsModel = v; }
  public void setTtsVoice(String v) { this.ttsVoice = v; }
  public void setTtsRate(double v) { this.ttsRate = v; }
  public void setTtsBacklogCoeff(double v) { this.ttsBacklogCoeff = v; }
  public void setTtsBacklogCap(double v) { this.ttsBacklogCap = v; }
}
