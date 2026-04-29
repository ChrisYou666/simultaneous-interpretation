package com.simultaneousinterpretation.service;

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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranslateService {

    /** 翻译指令：Qwen-MT 不支持 system message，指令内嵌于 user message */
    private static final String MT_INSTRUCTION =
            "You are a professional interpreter. Translate the following {src} text into {tgt}.\n"
            + "Output only the concise, idiomatic {tgt} translation, no explanation.\n\n";

    /** 印尼语目标：压缩至 60%~70%，严格约束禁止改写 */
    private static final String COMPRESSION_SYSTEM_PROMPT_ID =
            "You are a simultaneous interpretation compression model.\n\n"
            + "Task: Compress the already-translated Indonesian text into a concise real-time interpretation version.\n\n"
            + "【Input Rules】\n"
            + "- The input text is already in Indonesian. Do NOT translate again.\n"
            + "- Preserve all proper nouns unchanged.\n\n"
            + "【Strict Constraints (Must Follow)】\n"
            + "1. Do NOT rephrase or rewrite the original meaning.\n"
            + "2. Do NOT add any information not present in the input.\n"
            + "3. Do NOT add explanations, summaries, or conclusions.\n"
            + "4. Do NOT add emotional or dramatic expressions.\n"
            + "5. The following words/phrases are strictly forbidden:\n"
            + "   - \"artinya\" (meaning)\n"
            + "   - \"intinya\" (in summary)\n"
            + "   - \"berarti\" (means)\n"
            + "   - \"ini menunjukkan\" (this shows)\n"
            + "   - \"jadi\" (so / therefore)\n"
            + "6. Do not output any podcast promotion, sponsorship, or call-to-action text.\n"
            + "   Specifically forbidden: \"dukung podcast\", \"jadilah sponsor\", \"jadi patron\",\n"
            + "   \"menjadi sponsor/patron\", \"di Patreon\", \"support this podcast\", \"don't forget to support\",\n"
            + "   \"Sampai jumpa\", \"Daaah\", \"subscribe\", \"rating di Spotify\".\n"
            + "   These phrases must be DELETED, never kept.\n"
            + "7. Do not change tone or expression style.\n\n"
            + "【Compression Rules】\n"
            + "7. Only deletion is allowed for:\n"
            + "   - Fillers\n"
            + "   - Repetition\n"
            + "   - Weak modifiers\n"
            + "   - Podcast/content promotion, sponsorship, and call-to-action sentences\n"
            + "     (e.g. \"dukung podcast ini dengan menjadi\", \"jangan lupa\",\n"
            + "      \"Sampai jumpa di episode berikutnya\", subscription/promotion reminders)\n"
            + "8. Preserve all facts, actions, and results.\n"
            + "9. Keep the original order.\n\n"
            + "【Length Control】\n"
            + "10. Target: 60%~70% of the original length.\n"
            + "11. Do NOT delete key actions or events.\n\n"
            + "【Style (Strict)】\n"
            + "12. Use very simple sentence structures.\n"
            + "13. One sentence, one action.\n"
            + "14. Recommended: ≤12 words per sentence.\n"
            + "15. Avoid descriptive or literary expressions.\n\n"
            + "【Output Requirements】\n"
            + "- Output must be objective, direct, and neutral.\n"
            + "- Do not use narrative style.\n"
            + "- Do not polish or enhance expressions.\n\n"
            + "Output only the compressed text, no explanation.";

    private static final Map<String, String> LANG_CODE_MAP = Map.of(
            "zh", "Chinese",
            "id", "Indonesian"
    );

    private final DashScopeProperties dashScopeProperties;
    private final AiProperties aiProperties;
    private final LlmIntegration llmIntegration;
    private final Map<String, String> modelCache = new ConcurrentHashMap<>();

    public TranslateResponse translate(TranslateRequest request) {
        return translateThenCompress(request);
    }

    /**
     * 两步管道：步骤一 Qwen-MT 忠实翻译 → 步骤二 Qwen3-Max 意译压缩（仅 id 目标执行）。
     */
    public TranslateResponse translateThenCompress(TranslateRequest request) {
        log.info("翻译请求开始，源语言={}, 目标语言={}, 文本长度={}",
                 request.getSourceLang(), request.getTargetLang(),
                 request.getSegment() != null ? request.getSegment().length() : 0);
        long startTime = System.currentTimeMillis();

        try {
            validateTranslateRequest(request);

            String apiKey = getEffectiveApiKey();
            if (!StringUtils.hasText(apiKey)) {
                throw new BizException(ErrorCode.TRANSLATE_SERVICE_UNAVAILABLE, "未配置翻译 API Key");
            }

            String baseUrl = getEffectiveBaseUrl();
            String srcText = request.getSegment().trim();
            int srcLen = srcText.length();
            String srcLangName = toLangName(request.getSourceLang());
            String tgtLangName = toLangName(request.getTargetLang());
            String mtModel = getEffectiveModel();
            String compModel = dashScopeProperties.getCompressionModel();

            // ── 步骤一：Qwen-MT 翻译 ────────────────────────────────────────
            long mtStart = System.currentTimeMillis();
            String mtUserMessage = buildMTUserMessage(srcText, srcLangName, tgtLangName);
            String mtResult = llmIntegration.chatForTranslation(
                    mtUserMessage, apiKey, baseUrl, mtModel, srcLangName, tgtLangName);
            long mtMs = System.currentTimeMillis() - mtStart;
            if (mtResult == null || mtResult.isBlank()) {
                throw new BizException(ErrorCode.TRANSLATE_FAILED, "Qwen-MT 翻译返回为空");
            }
            int mtLen = mtResult.trim().length();
            log.info("[PIPE-a-XLAT] model={} srcLen={} mtLen={} ratio={}% ms={} text={}",
                     mtModel, srcLen, mtLen,
                     srcLen > 0 ? String.format("%.1f", (double) mtLen / srcLen * 100) : "0",
                     mtMs, truncate(srcText, 40));

            // ── 步骤二：zh 目标不压缩，id 目标压缩至 60%~70% ─────────────────
            String finalText;
            String finalModel;
            int finalLen;
            double compRatio;

            if ("zh".equals(request.getTargetLang())) {
                log.info("[PIPE-b-SKIP] targetLang=zh, 跳过压缩");
                finalText = mtResult.trim();
                finalModel = mtModel;
                finalLen = mtLen;
                compRatio = srcLen > 0 ? (double) mtLen / srcLen : 0;
            } else {
                log.info("[PIPE-b-COMP] targetLang={}, 压缩目标 60%~70%", request.getTargetLang());
                long compStart = System.currentTimeMillis();
                String compResult = llmIntegration.chat(
                        COMPRESSION_SYSTEM_PROMPT_ID, mtResult.trim(), apiKey, baseUrl, compModel);
                long compMs = System.currentTimeMillis() - compStart;

                if (compResult == null || compResult.isBlank()) {
                    log.warn("[PIPE-b-COMP-FAIL] 回退 MT 结果");
                    finalText = mtResult.trim();
                    finalModel = mtModel;
                    finalLen = mtLen;
                    compRatio = srcLen > 0 ? (double) mtLen / srcLen : 0;
                } else {
                    finalText = compResult.trim();
                    finalModel = compModel;
                    finalLen = finalText.length();
                    compRatio = srcLen > 0 ? (double) finalLen / srcLen : 0;
                    log.info("[PIPE-b-COMP] model={} mtLen={} compLen={} compRatio={}% ms={}",
                             compModel, mtLen, finalLen,
                             String.format("%.1f", compRatio * 100), compMs);
                }
            }

            long totalMs = System.currentTimeMillis() - startTime;
            log.info("[PIPE-COMBINED] totalMs={} srcLen={} finalLen={} finalRatio={}%",
                     totalMs, srcLen, finalLen,
                     String.format("%.1f", srcLen > 0 ? (double) finalLen / srcLen * 100 : 0));

            return new TranslateResponse(finalText, finalModel, false, false, compRatio);

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            long elapsedTime = System.currentTimeMillis() - startTime;
            log.error("翻译异常 源={} 目标={} 耗时={}ms error={}",
                     request.getSourceLang(), request.getTargetLang(), elapsedTime, e.getMessage(), e);
            throw new BizException(ErrorCode.TRANSLATE_FAILED, "翻译服务调用失败: " + e.getMessage());
        }
    }

    private void validateTranslateRequest(TranslateRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "翻译请求不能为空");
        }
        if (!StringUtils.hasText(request.getSegment())) {
            throw new BizException(ErrorCode.TRANSLATE_TEXT_EMPTY);
        }
        if (request.getSegment().length() > Constants.MAX_TRANSLATE_TEXT_LENGTH) {
            throw new BizException(ErrorCode.TRANSLATE_TEXT_TOO_LONG,
                    "翻译文本不能超过 " + Constants.MAX_TRANSLATE_TEXT_LENGTH + " 个字符");
        }
        if (!StringUtils.hasText(request.getTargetLang())) {
            throw new BizException(ErrorCode.PARAM_MISSING, "目标语言不能为空");
        }
    }

    private String buildMTUserMessage(String srcText, String srcLangName, String tgtLangName) {
        return MT_INSTRUCTION
                .replace("{src}", srcLangName)
                .replace("{tgt}", tgtLangName) + srcText;
    }

    private String toLangName(String code) {
        if (code == null) return "auto";
        String name = LANG_CODE_MAP.get(code.toLowerCase());
        return name != null ? name : code;
    }

    private String getEffectiveApiKey() {
        return dashScopeProperties.getEffectiveApiKey(aiProperties.getApiKey());
    }

    private String getEffectiveBaseUrl() {
        String baseUrl = !dashScopeProperties.getTranslateBaseUrl().isBlank()
                ? dashScopeProperties.getTranslateBaseUrl().trim()
                : (aiProperties.getBaseUrl() != null ? aiProperties.getBaseUrl().trim() : "");
        if (!StringUtils.hasText(baseUrl)) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        return baseUrl;
    }

    private String getEffectiveModel() {
        String model = !dashScopeProperties.getTranslateModel().isBlank()
                ? dashScopeProperties.getTranslateModel().trim()
                : (StringUtils.hasText(aiProperties.getModel()) ? aiProperties.getModel().trim() : "qwen-mt-plus");
        return model;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
