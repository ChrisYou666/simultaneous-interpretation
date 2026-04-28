package com.simultaneousinterpretation.service;

import com.simultaneousinterpretation.common.Constants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语言检测服务：zh（汉字）vs id（其他所有拉丁文本）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LanguageDetectionService {

    private final com.simultaneousinterpretation.config.LangDetectProperties props;

    @PostConstruct
    public void init() {
        log.info("LanguageDetectionService 初始化完成，支持语言: zh/id");
    }

    public String detect(String text) {
        if (!props.isEnabled()) return "";
        if (text == null || text.isBlank()) return "";
        try {
            String lang = detectImpl(text.trim());
            log.info("[LANG-DETECT] text=\"{}\" textLen={} detectedLang={}",
                    text.length() > 50 ? text.substring(0, 50) + "..." : text,
                    text.length(), lang);
            return lang;
        } catch (Exception e) {
            log.warn("语言检测异常: {}", e.getMessage());
            return "";
        }
    }

    private String detectImpl(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3400 && c <= 0x4dbf)) {
                return Constants.LANG_ZH;
            }
        }
        return Constants.LANG_ID;
    }
}
