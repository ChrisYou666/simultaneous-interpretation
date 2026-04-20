package com.simultaneousinterpretation.service;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import com.simultaneousinterpretation.common.Constants;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语言检测服务，基于 Lingua 高精度语言检测库。
 * 支持 75 种语言，内部仅返回 zh/en/id 三种。
 */
@Slf4j
@Service
public class LanguageDetectionService {

    private LanguageDetector detector;

    @PostConstruct
    public void init() {
        detector = LanguageDetectorBuilder
                .fromLanguages(
                        Language.ENGLISH,
                        Language.INDONESIAN,
                        Language.CHINESE,
                        Language.MALAY,
                        Language.JAPANESE,
                        Language.KOREAN
                )
                .withMinimumRelativeDistance(0.2)
                .withLowAccuracyMode()
                .build();

        log.info("LanguageDetectionService 初始化完成，支持语言: zh/en/id");
    }

    /**
     * 检测文本语言。
     *
     * @param text 待检测文本
     * @return zh / en / id；空串表示无法判定
     */
    public String detect(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        try {
            Language lang = detector.detectLanguageOf(text);
            String result = mapToProjectLang(lang);
            log.debug("[LANG-DETECT] text=\"{}\" detected={}", text.length() > 50
                    ? text.substring(0, 50) + "..." : text, result);
            return result;
        } catch (Exception e) {
            log.warn("语言检测异常: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 将 Lingua 的 Language 枚举映射到项目常量。
     * 未知/不可判定语言返回空串。
     */
    private String mapToProjectLang(Language lang) {
        if (lang == null) return "";

        return switch (lang) {
            case Language.CHINESE   -> Constants.LANG_ZH;
            case Language.ENGLISH  -> Constants.LANG_EN;
            case Language.INDONESIAN, Language.MALAY -> Constants.LANG_ID;
            default -> {
                // 兜底：某些文本 Lingua 可能返回日语/韩语
                // 不应发生在同传场景，返回空串让上游自行判断
                yield "";
            }
        };
    }
}
