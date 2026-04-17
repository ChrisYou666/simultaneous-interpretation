package com.simultaneousinterpretation.service;

import com.simultaneousinterpretation.config.AiProperties;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.config.PipelineTuningParams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 轻量实时翻译：接收切段文本 + 可选术语表/上下文，调用 Qwen 翻译，返回纯译文。
 */
@Service
public class RealtimeTranslateService {

  private static final Logger log = LoggerFactory.getLogger(RealtimeTranslateService.class);

  private static final String BASE_SYSTEM_PROMPT =
      "你是会议同声传译。按给定的源语、目标语代码只输出译文正文，勿解释、勿引号、勿前缀、勿重复原文；"
      + "准确自然。源语与目标语相同时输出原文。";

  private final DashScopeProperties dashScopeProperties;
  private final AiProperties aiProperties;
  private final PipelineTuningParams tuningParams;
  private volatile ChatLanguageModel cachedModel;

  public RealtimeTranslateService(AiProperties aiProperties, DashScopeProperties dashScopeProperties,
                                 PipelineTuningParams tuningParams) {
    this.aiProperties = aiProperties;
    this.dashScopeProperties = dashScopeProperties;
    this.tuningParams = tuningParams;
  }

  public String translate(String text, String sourceLang, String targetLang) {
    return translate(text, sourceLang, targetLang, null, null);
  }

  /**
   * @param text       源语文本
   * @param sourceLang 源语言代码（zh/en/id/auto）
   * @param targetLang 目标语言代码（zh/en/id）
   * @param glossary   术语表文本（可选，格式如 "AI=人工智能\nML=机器学习"）
   * @param context    场景上下文（可选）
   * @return 译文，或 null 表示不可用
   */
  public String translate(String text, String sourceLang, String targetLang,
                          String glossary, String context) {
    if (!StringUtils.hasText(text)) return null;
    long entryTs = System.currentTimeMillis();
    long entryNano = System.nanoTime();
    String src = StringUtils.hasText(sourceLang) ? sourceLang : "auto";
    String tgt = StringUtils.hasText(targetLang) ? targetLang : "zh";
    log.info("[Translate-ENTER] srcLang={} tgtLang={} text={} textLen={} glossary={} context={}",
        src, tgt, truncate(text, 80), text.length(),
        glossary != null ? truncate(glossary, 40) : "null",
        context != null ? truncate(context, 40) : "null");

    // 优先取 app.dashscope 统一 Key，回退至 app.openai（旧字段兼容）
    String apiKey = dashScopeProperties.getEffectiveApiKey(aiProperties.getApiKey());
    if (!StringUtils.hasText(apiKey)) {
      log.warn("[Translate-NO-KEY] srcLang={} tgtLang={} API Key 未配置", src, tgt);
      return null;
    }

    ChatLanguageModel model = getOrCreateModel();
    if (model == null) {
      log.warn("[Translate-NULL-MODEL] srcLang={} tgtLang={} 模型未初始化", src, tgt);
      return null;
    }

    String systemPrompt = buildSystemPrompt(glossary, context);
    String userPrompt = String.format("源语言: %s\n目标语言: %s\n原文: %s", src, tgt, text.trim());

    try {
      long t0 = System.currentTimeMillis();
      log.debug("[Translate-LLM-CALL] srcLang={} tgtLang={} promptLen={}", src, tgt, userPrompt.length());
      Response<AiMessage> response = model.generate(
          List.of(SystemMessage.from(systemPrompt), UserMessage.from(userPrompt)));
      String result = response.content().text();
      long elapsed = System.currentTimeMillis() - t0;
      long totalElapsed = entryNano > 0 ? (System.nanoTime() - entryNano) / 1_000_000L : elapsed;
      log.info("[Translate-DONE] srcLang={} tgtLang={} llmMs={} totalMs={} original=\"{}\" translated=\"{}\"",
          src, tgt, elapsed, totalElapsed, truncate(text, 60), truncate(result, 60));
      return result != null ? result.trim() : null;
    } catch (Exception e) {
      long totalElapsed = entryNano > 0 ? (System.nanoTime() - entryNano) / 1_000_000L : 0;
      log.error("[Translate-FAIL] srcLang={} tgtLang={} totalMs={} error={}",
          src, tgt, totalElapsed, e.getMessage());
      return null;
    }
  }

  private String buildSystemPrompt(String glossary, String context) {
    StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT);
    if (StringUtils.hasText(glossary)) {
      sb.append("\n\n【术语表/关键词】请严格遵循以下术语翻译：\n").append(glossary.trim());
    }
    if (StringUtils.hasText(context)) {
      sb.append("\n\n【场景与背景】\n").append(context.trim());
    }
    return sb.toString();
  }

  private ChatLanguageModel getOrCreateModel() {
    if (cachedModel != null) return cachedModel;
    synchronized (this) {
      if (cachedModel != null) return cachedModel;
      // 优先级：DashScopeProperties 统一配置 > tuningParams 运行时覆盖 > app.openai 旧字段
      String apiKey = dashScopeProperties.getEffectiveApiKey(aiProperties.getApiKey());
      String baseUrl = !dashScopeProperties.getTranslateBaseUrl().isBlank()
          ? dashScopeProperties.getTranslateBaseUrl().trim()
          : (StringUtils.hasText(tuningParams.getTranslateBaseUrl())
              ? tuningParams.getTranslateBaseUrl().trim()
              : (StringUtils.hasText(aiProperties.getBaseUrl())
                  ? aiProperties.getBaseUrl().trim()
                  : "https://dashscope.aliyuncs.com/compatible-mode/v1"));
      String modelName = !dashScopeProperties.getTranslateModel().isBlank()
          ? dashScopeProperties.getTranslateModel().trim()
          : (StringUtils.hasText(tuningParams.getTranslateModel())
              ? tuningParams.getTranslateModel().trim()
              : (StringUtils.hasText(aiProperties.getModel())
                  ? aiProperties.getModel().trim()
                  : "qwen-turbo"));

      int timeoutSec = tuningParams.getTranslateTimeoutSec();
      int maxTokens = tuningParams.getTranslateMaxTokens();
      int maxRetries = tuningParams.getTranslateMaxRetries();
      double temperature = tuningParams.getTranslateTemperature();

      cachedModel = OpenAiChatModel.builder()
          .apiKey(apiKey)
          .baseUrl(baseUrl)
          .modelName(modelName)
          .timeout(Duration.ofSeconds(timeoutSec))
          .maxRetries(maxRetries)
          /* 同传切段较短，压低 maxTokens 缩短生成延迟 */
          .maxTokens(maxTokens)
          .temperature(temperature)
          .build();
      log.info("[翻译] 模型初始化完成: base={} model={} timeout={}s maxTokens={} temperature={}",
          baseUrl, modelName, timeoutSec, maxTokens, temperature);
      return cachedModel;
    }
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }
}
