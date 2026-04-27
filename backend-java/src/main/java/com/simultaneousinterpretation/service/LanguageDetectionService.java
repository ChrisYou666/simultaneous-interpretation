package com.simultaneousinterpretation.service;

import com.simultaneousinterpretation.common.Constants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 语言检测服务 (低延迟版)
 *
 * 策略:
 * 1. 中文: CJK Unicode 范围检测，<1ms
 * 2. 印尼语: 高频词匹配 + ngram 分析
 * 3. 英语: 高频词匹配 + ngram 分析
 * 4. 兜底: 字母频率分布区分
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LanguageDetectionService {

    private final com.simultaneousinterpretation.config.LangDetectProperties props;

    // 高频印尼语词 (Top 80 常用词，补充短词和语气词)
    private final Set<String> INDONESIAN_COMMON_WORDS = Set.of(
        "yang", "dan", "di", "ke", "dari", "dengan", "untuk", "adalah", "ini", "itu",
        "pada", "dalam", "tidak", "akan", "atau", "juga", "sebagai", "oleh", "ada",
        "sudah", "saya", "kami", "kita", "mereka", "anda", "dia", "nya", "lah", "kan",
        "pun", "serta", "bisa", "harus", "lebih", "sangat", "sekali", "tersebut",
        "masih", "lagi", "karena", "jadi", "bukan", "apa", "siapa", "mana", "bagaimana",
        "mengapa", "bilang", "pergi", "datang", "lihat", "buat", "ambil", "beri", "dengar",
        // 补充短词和常用词
        "ya", "si", "ni", "tu", "de", "lo", "ko", "do", "na", "ka",
        "iya", "tak", "gak", "nggak", "aja", "banget", "nih", "tuh", "dong"
    );

    // 高频英语词 (Top 50 常用词)
    private final Set<String> ENGLISH_COMMON_WORDS = Set.of(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
        "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
        "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
        "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
        "so", "up", "out", "if", "about", "who", "get", "which", "go", "me"
    );

    // 印尼语特征 bigram (排除与英语高度重叠的)
    private final Set<String> INDONESIAN_NGRAM = Set.of(
        "ng", "ah", "pe", "me", "da", "ny", "sy", "mu", "nu",
        "lu", "pu", "ru", "ja", "ku", "tu", "su", "bu", "du", "ba", "ha",
        "er", "an", "en", "in", "un", "ke", "ka", "ki", "ko", "nj"
    );

    // 英语特征 bigram (排除与印尼语重叠的)
    private final Set<String> ENGLISH_NGRAM = Set.of(
        "th", "he", "in", "re", "ed", "is", "at", "on", "st", "nt",
        "ee", "of", "or", "nd", "ti", "es", "te", "er", "al", "le", "ly",
        "qu", "ck", "gh", "wh", "wr", "sc"
    );

    @PostConstruct
    public void init() {
        log.info("LanguageDetectionService 初始化完成，支持语言: zh/en/id");
    }

    public String detect(String text) {
        if (!props.isEnabled()) {
            return "";
        }
        if (text == null || text.isBlank()) {
            return "";
        }

        try {
            String lang = detectImpl(text.trim());
            // 增强日志：记录检测过程和得分
            if (log.isInfoEnabled() || shouldLogForDebug(text)) {
                log.info("[LANG-DETECT] text=\"{}\" textLen={} detectedLang={}", 
                    text.length() > 50 ? text.substring(0, 50) + "..." : text,
                    text.length(), lang);
            }
            return lang;
        } catch (Exception e) {
            log.warn("语言检测异常: {}", e.getMessage());
            return "";
        }
    }

    // 短文本或包含印尼语特征的文本需要详细日志
    private boolean shouldLogForDebug(String text) {
        if (text.length() < 30) return true;
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("yang") || lower.contains("dan") || lower.contains("di") 
            || lower.contains("ini") || lower.contains("itu") || lower.contains("ada");
    }

    private String detectImpl(String text) {
        int cjkCount = countCjkChars(text);
        int latinCount = countLatinLetters(text);

        // 1. 中文检测
        if (cjkCount >= 1) {
            if (latinCount == 0 || cjkCount >= latinCount) {
                return Constants.LANG_ZH;
            }
            // 混合文本: 中文字符更多或相当
            if (cjkCount >= latinCount * 0.5) {
                return Constants.LANG_ZH;
            }
        }

        // 2. 纯拉丁文本
        if (latinCount >= 3) {
            return detectLatin(text.toLowerCase(Locale.ROOT));
        }

        // 兜底：尝试返回印尼语
        return Constants.LANG_ID;
    }

    private String detectLatin(String text) {
        // 移除标点和空格
        String clean = text.replaceAll("[^a-z]", "");
        if (clean.length() < 2) return "";

        // 词级匹配
        String[] words = clean.split("\\s+");
        int idWordScore = 0;
        int enWordScore = 0;
        for (String word : words) {
            if (INDONESIAN_COMMON_WORDS.contains(word)) idWordScore++;
            if (ENGLISH_COMMON_WORDS.contains(word)) enWordScore++;
        }

        // 单词得分权重更高（降低门槛）
        if (idWordScore > 0 && idWordScore >= enWordScore) {
            return Constants.LANG_ID;
        }
        if (enWordScore > 0 && enWordScore > idWordScore) {
            return Constants.LANG_EN;
        }

        // Ngram 得分
        int idScore = scoreNgrams(text, INDONESIAN_NGRAM);
        int enScore = scoreNgrams(text, ENGLISH_NGRAM);

        // 使用相对比率（印尼语阈值更低因为它更独特）
        double idRatio = (double) idScore / Math.max(clean.length() - 1, 1);
        double enRatio = (double) enScore / Math.max(clean.length() - 1, 1);

        // 印尼语特征 bigram 更独特，降低阈值
        if (idScore > 0 && idScore >= enScore && idRatio > 0.05) {
            return Constants.LANG_ID;
        }
        if (enScore > 0 && enScore > idScore && enRatio > 0.06) {
            return Constants.LANG_EN;
        }

        // 字母频率分布兜底
        return detectByLetterFrequency(clean);
    }

    private int scoreNgrams(String text, Set<String> ngrams) {
        int score = 0;
        for (int i = 0; i < text.length() - 1; i++) {
            String bigram = text.substring(i, i + 2);
            if (ngrams.contains(bigram)) {
                score++;
            }
        }
        return score;
    }

    private String detectByLetterFrequency(String text) {
        // 印尼语特征字母: q, x 罕见，v 少用
        // 英语特征字母: v, w 较常见

        long vCount = text.chars().filter(c -> c == 'v' || c == 'w').count();
        double foreignRatio = (double) vCount / text.length();

        if (foreignRatio > 0.05) {
            return Constants.LANG_EN;
        }

        return Constants.LANG_ID; // 默认倾向印尼语 (项目背景)
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
