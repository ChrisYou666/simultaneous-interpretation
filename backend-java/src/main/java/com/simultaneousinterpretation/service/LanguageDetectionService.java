package com.simultaneousinterpretation.service;

import com.simultaneousinterpretation.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语言检测服务
 * <p>
 * 基于字符 N-gram 频率分析的语言检测，支持 zh/en/id 三语
 *
 * @author System
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LanguageDetectionService {

    /**
     * 支持的语言列表
     */
    private static final java.util.List<String> SUPPORTED_LANGS = java.util.List.of(
            Constants.LANG_ZH, 
            Constants.LANG_EN, 
            Constants.LANG_ID
    );

    private final com.simultaneousinterpretation.config.LangDetectProperties props;

    /**
     * 检测文本语种
     *
     * @param text 待检测文本
     * @return 三语码（zh/en/id）或空字符串（无法确认）
     */
    public String detect(String text) {
        if (!props.isEnabled()) {
            log.debug("语言检测未启用，直接返回空");
            return "";
        }
        if (text == null || text.isBlank()) {
            log.debug("语言检测参数为空，返回空");
            return "";
        }

        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        try {
            String lang = detectImpl(trimmed);
            log.debug("语言检测完成，文本长度={}, 检测结果={}", trimmed.length(), lang);

            if (!SUPPORTED_LANGS.contains(lang)) {
                log.debug("检测结果不在支持列表中，返回空");
                return "";
            }

            return lang;
        } catch (Exception e) {
            log.warn("语言检测异常，文本长度={}, error={}", trimmed.length(), e.getMessage());
            return "";
        }
    }

    /**
     * 检测实现
     */
    private String detectImpl(String text) {
        int cjkCount = countCjkChars(text);
        int latinCount = countLatinLetters(text);

        // 中文判定
        if (cjkCount >= 1) {
            if (latinCount == 0 || cjkCount >= latinCount * 0.5) {
                return Constants.LANG_ZH;
            }
            return Constants.LANG_ZH;
        }

        // 纯拉丁字母文本：区分英语和印尼语
        if (latinCount >= 2) {
            double idScore = scoreIndonesian(text);
            double enScore = scoreEnglish(text);
            
            if (log.isDebugEnabled()) {
                log.debug("印尼语评分={}, 英语评分={}", 
                         String.format("%.3f", idScore), 
                         String.format("%.3f", enScore));
            }
            
            if (idScore > enScore && idScore > 0.15) {
                return Constants.LANG_ID;
            }
            if (enScore > 0.15) {
                return Constants.LANG_EN;
            }
            return "";
        }

        return "";
    }

    /**
     * 印尼语评分
     */
    private double scoreIndonesian(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int bigrams = 0;
        for (int i = 0; i < lower.length() - 1; i++) {
            String bg = lower.substring(i, i + 2);
            if (isIndonesianBigram(bg)) {
                bigrams++;
            }
        }
        return (double) bigrams / Math.max(lower.length() - 1, 1);
    }

    /**
     * 英语评分
     */
    private double scoreEnglish(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int bigrams = 0;
        for (int i = 0; i < lower.length() - 1; i++) {
            String bg = lower.substring(i, i + 2);
            if (isEnglishBigram(bg)) {
                bigrams++;
            }
        }
        return (double) bigrams / Math.max(lower.length() - 1, 1);
    }

    private boolean isIndonesianBigram(String bg) {
        return bg.equals("ng") || bg.equals("er") || bg.equals("te") || bg.equals("ah")
            || bg.equals("an") || bg.equals("pe") || bg.equals("ke") || bg.equals("me")
            || bg.equals("da") || bg.equals("ra") || bg.equals("di") || bg.equals("ka")
            || bg.equals("pa") || bg.equals("sa") || bg.equals("la") || bg.equals("ma")
            || bg.equals("ba") || bg.equals("ha") || bg.equals("ja") || bg.equals("na")
            || bg.equals("ny") || bg.equals("sy") || bg.equals("mu") || bg.equals("nu")
            || bg.equals("ku") || bg.equals("tu") || bg.equals("su") || bg.equals("bu")
            || bg.equals("lu") || bg.equals("pu") || bg.equals("du") || bg.equals("ru");
    }

    private boolean isEnglishBigram(String bg) {
        return bg.equals("th") || bg.equals("he") || bg.equals("in") || bg.equals("er")
            || bg.equals("re") || bg.equals("ed") || bg.equals("is") || bg.equals("at")
            || bg.equals("on") || bg.equals("st") || bg.equals("nt") || bg.equals("ee")
            || bg.equals("of") || bg.equals("to") || bg.equals("or") || bg.equals("en")
            || bg.equals("nd") || bg.equals("ti") || bg.equals("es") || bg.equals("te");
    }

    private static int countCjkChars(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf)) {
                n++;
            }
        }
        return n;
    }

    private static int countLatinLetters(String s) {
        if (s == null) return 0;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                n++;
            }
        }
        return n;
    }
}
