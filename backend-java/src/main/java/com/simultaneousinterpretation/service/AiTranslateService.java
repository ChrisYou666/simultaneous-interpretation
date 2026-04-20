package com.simultaneousinterpretation.service;

import com.simultaneousinterpretation.api.dto.ImagePayload;
import com.simultaneousinterpretation.api.dto.TranslateRequest;
import com.simultaneousinterpretation.api.dto.TranslateResponse;
import com.simultaneousinterpretation.config.AiProperties;
import com.simultaneousinterpretation.config.DashScopeProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiTranslateService {

  private static final int MAX_RETRIES = 3;
  private static final long[] RETRY_DELAYS_MS = { 1_000, 2_000, 4_000 };

  private static final int MAX_IMAGES = 8;
  private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

  private final DashScopeProperties dashScopeProperties;
  private final AiProperties aiProperties;
  private final Map<String, ChatLanguageModel> modelCache = new ConcurrentHashMap<>();

  public AiTranslateService(AiProperties aiProperties, DashScopeProperties dashScopeProperties) {
    this.aiProperties = aiProperties;
    this.dashScopeProperties = dashScopeProperties;
  }

  public TranslateResponse translate(TranslateRequest req) {
    // 优先取 app.dashscope 统一 Key，回退至 app.openai（旧字段兼容）
    String apiKey = dashScopeProperties.getEffectiveApiKey(aiProperties.getApiKey());
    if (!StringUtils.hasText(apiKey)) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "未配置百炼 API Key，请在 app.dashscope.api-key 或环境变量 DASHSCOPE_API_KEY 中配置。");
    }
    // 优先取 app.dashscope 的翻译配置，回退至 app.openai
    String baseUrl = !dashScopeProperties.getTranslateBaseUrl().isBlank()
        ? dashScopeProperties.getTranslateBaseUrl().trim()
        : (aiProperties.getBaseUrl() != null ? aiProperties.getBaseUrl().trim() : "");
    if (!StringUtils.hasText(baseUrl)) {
      baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }
    String modelName = !dashScopeProperties.getTranslateModel().isBlank()
        ? dashScopeProperties.getTranslateModel().trim()
        : (StringUtils.hasText(aiProperties.getModel()) ? aiProperties.getModel().trim() : "qwen-turbo");

    OpenAiCredentialValidator.validateBaseUrlIfPresent(baseUrl);
    OpenAiCredentialValidator.validateApiKeyIfPresent(apiKey);

    ChatLanguageModel model = getOrCreateModel(apiKey, baseUrl, modelName);

    validateImages(req.getImages());

    boolean usedImages = req.getImages() != null && !req.getImages().isEmpty();
    boolean usedMeeting =
        req.isKbEnabled() && StringUtils.hasText(req.getMeetingMaterialsText());

    String srcLang = mapLangCode(req.getSourceLang());
    String tgtLang = mapLangCode(req.getTargetLang());
    System.out.println("[TRANSLATE-DEBUG] sourceLang=" + req.getSourceLang() + "->" + srcLang + " targetLang=" + req.getTargetLang() + "->" + tgtLang + " textLen=" + (req.getSegment() != null ? req.getSegment().length() : 0));

    String system = buildSystemPrompt(req, usedImages);
    UserMessage user = buildUserMessage(req, srcLang, tgtLang);

    String text = callWithRetry(model, system, user, req.getTargetLang());
    if (text == null || text.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型返回为空");
    }
    System.out.println("[TRANSLATE-RESULT] targetLang=" + req.getTargetLang() + " resultLen=" + text.length() + " result=" + text.substring(0, Math.min(100, text.length())));
    return new TranslateResponse(text.trim(), modelName, usedImages, usedMeeting);
  }

  /**
   * 带指数退避重试的 LLM 调用。
   * <p>
   * 遇到 429/503 限流错误时，按 1s → 2s → 4s 等待后重试，最多 3 次。
   * 消除"失败越快、重试越多"的正反馈崩溃。
   */
  private String callWithRetry(ChatLanguageModel model, String system, UserMessage user, String targetLang) {
    Exception lastException = null;
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
      try {
        Response<AiMessage> response = model.generate(List.of(SystemMessage.from(system), user));
        return response.content().text();
      } catch (Exception e) {
        lastException = e;
        if (attempt < MAX_RETRIES && isRetryable(e)) {
          long delayMs = RETRY_DELAYS_MS[attempt];
          System.out.println("[TRANSLATE-RETRY] targetLang=" + targetLang + " attempt=" + (attempt + 1) + "/" + (MAX_RETRIES + 1) + " 等待 " + delayMs + "ms 后重试，原因: " + e.getMessage());
          try {
            Thread.sleep(delayMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        } else {
          System.out.println("[TRANSLATE-ERROR] targetLang=" + targetLang + " attempt=" + (attempt + 1) + " 最终失败: " + e.getMessage());
          break;
        }
      }
    }
    throw lastException != null ? new ResponseStatusException(HttpStatus.BAD_GATEWAY, "翻译服务调用失败: " + lastException.getMessage()) : new ResponseStatusException(HttpStatus.BAD_GATEWAY, "翻译服务调用失败");
  }

  /** 判断异常是否为限流/临时性错误，需触发重试 */
  private boolean isRetryable(Exception e) {
    if (e instanceof ResponseStatusException rse) {
      int code = rse.getStatusCode().value();
      return code == 429 || code == 503;
    }
    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
    return msg.contains("429") || msg.contains("rate limit") || msg.contains("throttle") || msg.contains("503");
  }

  private ChatLanguageModel getOrCreateModel(String apiKey, String baseUrl, String modelName) {
    String cacheKey = apiKey + "|" + baseUrl + "|" + modelName;
    return modelCache.computeIfAbsent(
        cacheKey,
        k ->
            OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .maxRetries(1)
                .build());
  }

  private void validateImages(List<ImagePayload> images) {
    if (images == null || images.isEmpty()) {
      return;
    }
    if (images.size() > MAX_IMAGES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "单次最多上传 " + MAX_IMAGES + " 张图片");
    }
    for (ImagePayload img : images) {
      if (img == null || !StringUtils.hasText(img.base64()) || !StringUtils.hasText(img.mimeType())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片需包含 mimeType 与 base64");
      }
      byte[] raw;
      try {
        raw = Base64.getDecoder().decode(img.base64().trim());
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片 Base64 无效");
      }
      if (raw.length > MAX_IMAGE_BYTES) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张图片解码后不超过 5MB");
      }
    }
  }

  private String buildSystemPrompt(TranslateRequest req, boolean hasImages) {
    StringBuilder sb = new StringBuilder();
    sb.append("你是专业同声传译助手。只输出译文正文，不要解释、不要加引号或「译文：」等前缀。\n");

    if (req.isKbEnabled()) {
      if (StringUtils.hasText(req.getKeywordsText())) {
        sb.append("\n【术语 / 关键词表】\n").append(req.getKeywordsText().trim()).append('\n');
      }
      if (StringUtils.hasText(req.getContextText())) {
        sb.append("\n【场景与背景】\n").append(req.getContextText().trim()).append('\n');
      }
      if (StringUtils.hasText(req.getMeetingMaterialsText())) {
        sb.append("\n【会议材料（含 PDF 抽取文本；可能截断）】\n")
            .append(req.getMeetingMaterialsText().trim())
            .append('\n');
      }
    } else {
      sb.append("\n（知识库未启用：未注入术语表、手写上下文与会议报告文本。）\n");
    }

    if (hasImages) {
      sb.append(
          "\n【图像】用户附上了与参会人员/身份相关的图片。若图中可见姓名、职务、机构、胸牌文字等，请在译文中与术语表及会议材料保持一致并避免误译人名。\n");
    }

    return sb.toString();
  }

  private UserMessage buildUserMessage(TranslateRequest req, String srcLangName, String tgtLangName) {
    String userText =
        "请将下面片段从 "
            + srcLangName
            + " 译为 "
            + tgtLangName
            + "，语气适合会议口译现场。原文：\n"
            + req.getSegment().trim();

    List<Content> parts = new ArrayList<>();
    parts.add(TextContent.from(userText));

    if (req.getImages() != null) {
      for (ImagePayload img : req.getImages()) {
        if (img == null || !StringUtils.hasText(img.base64())) {
          continue;
        }
        String mime = img.mimeType().trim();
        parts.add(ImageContent.from(img.base64().trim(), mime, ImageContent.DetailLevel.HIGH));
      }
    }

    return UserMessage.from(parts.toArray(Content[]::new));
  }

  /**
   * 将语言代码转换为人类可读的语言名称，确保翻译模型正确理解。
   */
  private String mapLangCode(String code) {
    if (code == null || code.isBlank()) return "auto";
    return switch (code.toLowerCase()) {
      case "zh", "chinese" -> "中文";
      case "en", "english" -> "英文";
      case "in", "id", "indonesian", "bahasa" -> "印尼语";
      case "ja", "japanese" -> "日语";
      case "ko", "korean" -> "韩语";
      case "ms", "malay" -> "马来语";
      default -> code;
    };
  }
}
