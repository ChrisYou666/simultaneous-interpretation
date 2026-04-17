package com.simultaneousinterpretation.service;

import com.simultaneousinterpretation.api.dto.ImagePayload;
import com.simultaneousinterpretation.api.dto.TranslateRequest;
import com.simultaneousinterpretation.api.dto.TranslateResponse;
import com.simultaneousinterpretation.common.BizException;
import com.simultaneousinterpretation.common.Constants;
import com.simultaneousinterpretation.config.AiProperties;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.domain.enums.ErrorCode;
import com.simultaneousinterpretation.integration.LlmIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 翻译服务
 * <p>
 * 核心翻译业务逻辑，调用 AI 大模型进行文本翻译
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslateService {

    /**
     * 最大图片数量
     */
    private static final int MAX_IMAGES = 8;

    /**
     * 单张图片最大字节数
     */
    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    /**
     * 系统提示词模板
     */
    private static final String SYSTEM_PROMPT_TEMPLATE = 
            "你是专业同声传译助手。只输出译文正文，不要解释、不要加引号或「译文：」等前缀。\n";

    private final DashScopeProperties dashScopeProperties;
    private final AiProperties aiProperties;
    private final LlmIntegration llmIntegration;
    private final LanguageDetectionService languageDetectionService;
    private final Map<String, String> modelCache = new ConcurrentHashMap<>();

    /**
     * 翻译文本
     *
     * @param request 翻译请求
     * @return 翻译响应
     */
    public TranslateResponse translate(TranslateRequest request) {
        log.info("翻译请求开始，源语言={}, 目标语言={}, 文本长度={}, 启用知识库={}", 
                 request.getSourceLang(), request.getTargetLang(),
                 request.getSegment() != null ? request.getSegment().length() : 0,
                 request.isKbEnabled());
        long startTime = System.currentTimeMillis();

        try {
            // 参数校验
            validateTranslateRequest(request);

            // 获取 API Key
            String apiKey = getEffectiveApiKey();
            if (!StringUtils.hasText(apiKey)) {
                log.error("翻译失败，API Key 未配置");
                throw new BizException(ErrorCode.TRANSLATE_SERVICE_UNAVAILABLE, 
                        "未配置翻译 API Key，请在配置中设置");
            }

            // 获取 Base URL
            String baseUrl = getEffectiveBaseUrl();

            // 获取模型名称
            String modelName = getEffectiveModel();

            // 验证图片
            validateImages(request.getImages());

            boolean usedImages = request.getImages() != null && !request.getImages().isEmpty();
            boolean usedMeeting = request.isKbEnabled() && StringUtils.hasText(request.getMeetingMaterialsText());

            // 构建提示词
            String systemPrompt = buildSystemPrompt(request, usedImages);
            String userMessage = buildUserMessage(request);

            // 调用 AI 模型
            String translatedText = llmIntegration.chat(systemPrompt, userMessage, apiKey);

            if (translatedText == null || translatedText.isBlank()) {
                log.warn("翻译结果为空，请求参数：源语言={}, 目标语言={}", 
                         request.getSourceLang(), request.getTargetLang());
                throw new BizException(ErrorCode.TRANSLATE_FAILED, "翻译结果为空");
            }

            long elapsedTime = System.currentTimeMillis() - startTime;
            log.info("翻译成功，源语言={}, 目标语言={}, 译文长度={}, 耗时={}ms", 
                     request.getSourceLang(), request.getTargetLang(),
                     translatedText.length(), elapsedTime);

            return new TranslateResponse(translatedText.trim(), modelName, usedImages, usedMeeting);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("翻译异常，源语言={}, 目标语言={}, 耗时={}ms, error={}", 
                     request.getSourceLang(), request.getTargetLang(), elapsedTime, e.getMessage(), e);
            throw new BizException(ErrorCode.TRANSLATE_FAILED, "翻译服务调用失败: " + e.getMessage());
        }
    }

    /**
     * 校验翻译请求
     */
    private void validateTranslateRequest(TranslateRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "翻译请求不能为空");
        }
        if (!StringUtils.hasText(request.getSegment())) {
            throw new BizException(ErrorCode.TRANSLATE_TEXT_EMPTY);
        }
        if (request.getSegment().length() > Constants.MAX_TRANSLATE_TEXT_LENGTH) {
            log.warn("翻译文本过长，长度={}, 最大长度={}", 
                     request.getSegment().length(), Constants.MAX_TRANSLATE_TEXT_LENGTH);
            throw new BizException(ErrorCode.TRANSLATE_TEXT_TOO_LONG, 
                    String.format("翻译文本不能超过 %d 个字符", Constants.MAX_TRANSLATE_TEXT_LENGTH));
        }
        if (!StringUtils.hasText(request.getTargetLang())) {
            throw new BizException(ErrorCode.PARAM_MISSING, "目标语言不能为空");
        }
    }

    /**
     * 校验图片
     */
    private void validateImages(List<ImagePayload> images) {
        if (images == null || images.isEmpty()) {
            return;
        }
        if (images.size() > MAX_IMAGES) {
            log.warn("图片数量超限，数量={}, 最大={}", images.size(), MAX_IMAGES);
            throw new BizException(ErrorCode.IMAGE_COUNT_EXCEEDED, 
                    String.format("单次最多上传 %d 张图片", MAX_IMAGES));
        }
        for (ImagePayload img : images) {
            if (img == null) {
                throw new BizException(ErrorCode.PARAM_EMPTY, "图片数据不能为空");
            }
            if (!StringUtils.hasText(img.base64()) || !StringUtils.hasText(img.mimeType())) {
                throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "图片需包含 mimeType 与 base64");
            }
            try {
                byte[] raw = Base64.getDecoder().decode(img.base64().trim());
                if (raw.length > MAX_IMAGE_BYTES) {
                    log.warn("图片过大，大小={}bytes, 最大={}bytes", raw.length, MAX_IMAGE_BYTES);
                    throw new BizException(ErrorCode.IMAGE_TOO_LARGE, 
                            String.format("单张图片不能超过 %d MB", MAX_IMAGE_BYTES / 1024 / 1024));
                }
            } catch (IllegalArgumentException e) {
                throw new BizException(ErrorCode.PARAM_FORMAT_ERROR, "图片 Base64 无效");
            }
        }
    }

    /**
     * 构建系统提示词
     */
    private String buildSystemPrompt(TranslateRequest request, boolean hasImages) {
        StringBuilder sb = new StringBuilder();
        sb.append(SYSTEM_PROMPT_TEMPLATE);

        if (request.isKbEnabled()) {
            if (StringUtils.hasText(request.getKeywordsText())) {
                sb.append("\n【术语 / 关键词表】\n").append(request.getKeywordsText().trim()).append('\n');
            }
            if (StringUtils.hasText(request.getContextText())) {
                sb.append("\n【场景与背景】\n").append(request.getContextText().trim()).append('\n');
            }
            if (StringUtils.hasText(request.getMeetingMaterialsText())) {
                sb.append("\n【会议材料（含 PDF 抽取文本；可能截断）】\n")
                  .append(request.getMeetingMaterialsText().trim())
                  .append('\n');
            }
        } else {
            sb.append("\n（知识库未启用：未注入术语表、手写上下文与会议报告文本。）\n");
        }

        if (hasImages) {
            sb.append("\n【图像】用户附上了与参会人员/身份相关的图片。若图中可见姓名、职务、机构、胸牌文字等，请在译文中与术语表及会议材料保持一致并避免误译人名。\n");
        }

        return sb.toString();
    }

    /**
     * 构建用户消息
     */
    private String buildUserMessage(TranslateRequest request) {
        String src = StringUtils.hasText(request.getSourceLang()) ? request.getSourceLang() : "auto";
        String tgt = StringUtils.hasText(request.getTargetLang()) ? request.getTargetLang() : Constants.LANG_ZH;
        
        return String.format("请将下面片段从 %s 译为 %s，语气适合会议口译现场。原文：\n%s",
                src, tgt, request.getSegment().trim());
    }

    /**
     * 获取有效的 API Key
     */
    private String getEffectiveApiKey() {
        return dashScopeProperties.getEffectiveApiKey(aiProperties.getApiKey());
    }

    /**
     * 获取有效的 Base URL
     */
    private String getEffectiveBaseUrl() {
        String baseUrl = !dashScopeProperties.getTranslateBaseUrl().isBlank()
                ? dashScopeProperties.getTranslateBaseUrl().trim()
                : (aiProperties.getBaseUrl() != null ? aiProperties.getBaseUrl().trim() : "");
        
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        return baseUrl;
    }

    /**
     * 获取有效的模型名称
     */
    private String getEffectiveModel() {
        String model = !dashScopeProperties.getTranslateModel().isBlank()
                ? dashScopeProperties.getTranslateModel().trim()
                : (StringUtils.hasText(aiProperties.getModel()) ? aiProperties.getModel().trim() : "qwen-turbo");
        
        // 缓存模型名称
        modelCache.putIfAbsent("model", model);
        return model;
    }
}
