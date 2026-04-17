package com.simultaneousinterpretation.asr;

import com.simultaneousinterpretation.config.AsrProperties;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 切段引擎：将 ASR 输出切成适合翻译 + TTS 的短片段。
 *
 * <p>策略（参考商用同传方案优化）：
 * <ol>
 *   <li>收到 final 片段时，先在句末标点处预切，再追加到缓冲区</li>
 *   <li>缓冲区超过 maxChars → 在语义切分点/软标点处切出</li>
 *   <li>缓冲区以句末标点结尾且长度合理 → 立即切出</li>
 *   <li>距上次切出超过 flushTimeoutMs → 超时刷出</li>
 * </ol>
 *
 * <p>语义切分（参考搜狗/讯飞方案）：
 * <ul>
 *   <li>在语义关键词后切分（如"但是"、"however"），保证句子语义完整</li>
 *   <li>关键词保留在当前段，避免将转折词/连接词截断到下一段</li>
 *   <li>语义切分点优先级低于句末标点，但高于软标点</li>
 * </ul>
 *
 * <p>语言自适应：英语句子平均词数约 8-15 词（平均 4-6 字符/词），
 * 相比中文（1-2 字符/词）需要更高的 maxChars 才能覆盖同等语义单元。
 *
 * <p>流式处理模式：
 * <ol>
 *   <li>支持 Callback 模式：每切出一句立即回调，实现低延迟</li>
 *   <li>兼容旧模式：返回 List&lt;Segment&gt;，等待所有句子处理完</li>
 * </ol>
 */
public class SegmentationEngine {

  private static final Pattern SENTENCE_END = Pattern.compile("[。？！.?!]$");
  private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.?!。？！])\\s*");
  private static final Pattern PUNCT_SPLIT = Pattern.compile("[.?!。？！]");

  /**
   * 语义切分关键词：转折/递进/话题转换点，在这些词后切分可保证语义相对完整。
   * 优先级低于句末标点，高于软标点。
   * 使用 List.of() 避免 Set.of() 在有重复元素时抛出异常。
   */
  private static final Set<String> SEMANTIC_BREAK_KEYWORDS;
  static {
    SEMANTIC_BREAK_KEYWORDS = Set.of(
      // 中文转折词
      "但是", "可是", "然而", "不过", "只是",
      // 中文递进/总结词
      "而且", "此外", "同时", "另外", "还有", "并且", "更重要的是",
      "所以", "因此", "总之", "综上所述", "于是",
      // 中文话题转换词
      "接下来", "现在", "关于", "说到", "谈到",
      // 英文转折词
      "but", "however", "actually", "well", "though", "yet", "still",
      // 英文递进/总结词
      "and", "also", "plus", "moreover", "furthermore", "in addition",
      "so", "therefore", "thus", "hence", "as a result", "all in all", "in conclusion",
      // 英文话题转换词
      "now", "next", "about", "regarding", "moving on", "speaking of", "as for",
      "first", "second", "third", "finally", "lastly",
      // 印尼语转折/递进词
      "tetapi", "namun", "jadi", "padahal", "maka", "karena itu",
      "dan", "juga", "selain itu", "lebih lanjut"
    );
  }

  /**
   * 切段回调接口：每切出一个 segment 时立即回调。
   * 用于实现流式处理，降低端到端延迟。
   */
  @FunctionalInterface
  public interface SegmentCallback {
    void onSegment(Segment segment);
  }

  private final int maxChars;
  private final int softBreakChars;
  private final long flushTimeoutMs;

  /** 语言自适应的 maxChars 参数 */
  private final double enMaxCharsMultiplier;
  private final int enMaxCharsMin;
  private final int enMaxCharsMax;
  /** 印尼语独立的切分参数（印尼语词汇更长，虚词更多） */
  private final double idMaxCharsMultiplier;
  private final int idMaxCharsMin;
  private final int idMaxCharsMax;

  private final StringBuilder buffer = new StringBuilder();
  private long lastEmitTime = System.currentTimeMillis();
  private int segmentIndex = 0;
  /** 当前活跃的 batchId；切换时清空 buffer，避免跨 utterance 累积导致切分延迟增大 */
  private long currentBatchId = -1;

  public SegmentationEngine(int maxChars, int softBreakChars, long flushTimeoutMs) {
    this(maxChars, softBreakChars, flushTimeoutMs, 2.0, 10, 40, 1.6, 10, 60);
  }

  public SegmentationEngine(
      int maxChars,
      int softBreakChars,
      long flushTimeoutMs,
      double enMaxCharsMultiplier,
      int enMaxCharsMin,
      int enMaxCharsMax) {
    this(maxChars, softBreakChars, flushTimeoutMs, enMaxCharsMultiplier, enMaxCharsMin, enMaxCharsMax, 1.6, 10, 60);
  }

  public SegmentationEngine(
      int maxChars,
      int softBreakChars,
      long flushTimeoutMs,
      double enMaxCharsMultiplier,
      int enMaxCharsMin,
      int enMaxCharsMax,
      double idMaxCharsMultiplier,
      int idMaxCharsMin,
      int idMaxCharsMax) {
    this.maxChars = Math.min(512, Math.max(8, maxChars));
    int soft = Math.max(4, softBreakChars);
    this.softBreakChars = Math.min(this.maxChars - 1, soft);
    this.flushTimeoutMs = Math.min(10_000L, Math.max(50L, flushTimeoutMs));
    this.enMaxCharsMultiplier = Math.max(1.0, Math.min(4.0, enMaxCharsMultiplier));
    this.enMaxCharsMin = Math.max(10, Math.min(200, enMaxCharsMin));
    this.enMaxCharsMax = Math.max(20, Math.min(200, enMaxCharsMax));
    this.idMaxCharsMultiplier = Math.max(1.0, Math.min(4.0, idMaxCharsMultiplier));
    this.idMaxCharsMin = Math.max(10, Math.min(200, idMaxCharsMin));
    this.idMaxCharsMax = Math.max(20, Math.min(200, idMaxCharsMax));
  }

  /**
   * 按 {@link AsrProperties#getSegmentation()} 构建；cfg 为 null 时用默认。
   */
  public static SegmentationEngine fromConfig(AsrProperties.Segmentation cfg) {
    if (cfg == null) {
      return new SegmentationEngine(50, 15, 700, 2.0, 10, 80, 1.6, 10, 60);
    }
    return new SegmentationEngine(cfg.getMaxChars(), cfg.getSoftBreakChars(), cfg.getFlushTimeoutMs(), 2.0, 10, 80, 1.6, 10, 60);
  }

  // ─── 流式处理模式（方案一）：每切出一句立即回调 ─────────────────────────────────

  /**
   * 流式处理：每切出一句就立即通过回调通知，实现低延迟。
   * 适用于对延迟敏感的场景，避免等待所有句子处理完。
   *
   * <p>流程：每遇到句末标点或缓冲区满 → 立即 emit → 回调 onSegment
   *
   * @param text ASR 返回的文本
   * @param language 检测到的语言
   * @param confidence 置信度
   * @param floor 发言人标识
   * @param batchId 同一批次（同一 final）的所有 segment 共用此 ID
   * @param callback 每切出一句立即调用的回调
   * @return 回调触发的 segment 数量
   */
  public synchronized int onFinalTranscriptStreaming(
      String text, String language, double confidence, int floor,
      long batchId, SegmentCallback callback) {
    if (!StringUtils.hasText(text)) {
      return 0;
    }
    // batchId 切换时清空 buffer，避免跨 utterance 累积导致切分效率下降
    if (batchId != this.currentBatchId) {
      if (this.buffer.length() > 0) {
        this.buffer.setLength(0);
      }
      this.currentBatchId = batchId;
    }
    int count = 0;
    String[] sentences = SENTENCE_SPLIT.split(text);
    for (String sent : sentences) {
      if (sent.isBlank()) continue;
      buffer.append(sent);
      List<Segment> emitted = tryEmit(language, confidence, floor);
      for (Segment seg : emitted) {
        // 每切出一句立即回调
        callback.onSegment(seg);
        count++;
      }
    }
    return count;
  }

  // ─── 兼容旧模式 ───────────────────────────────────────────────────────────────

  /**
   * 传统批处理模式：等所有句子处理完才返回结果。
   * 保持向后兼容，内部实现与流式模式共用 tryEmit。
   */
  public synchronized List<Segment> onFinalTranscript(String text, String language, double confidence, int floor) {
    List<Segment> result = new ArrayList<>();
    if (!StringUtils.hasText(text)) {
      return result;
    }
    // batchId 切换时清空 buffer（此方法无 batchId 参数，视为新批次）
    if (this.buffer.length() > 0) {
      this.buffer.setLength(0);
    }
    this.currentBatchId = -1;
    String[] sentences = SENTENCE_SPLIT.split(text);
    for (String sent : sentences) {
      if (sent.isBlank()) continue;
      buffer.append(sent);
      result.addAll(tryEmit(language, confidence, floor));
    }
    return result;
  }

  public synchronized List<Segment> tick(String language, int floor) {
    if (buffer.isEmpty()) {
      lastEmitTime = System.currentTimeMillis();
      return List.of();
    }
    if (System.currentTimeMillis() - lastEmitTime >= flushTimeoutMs) {
      return splitAndFlush(language, 0.0, floor);
    }
    return List.of();
  }

  public synchronized List<Segment> flushRemaining(String language, int floor) {
    return splitAndFlush(language, 0.0, floor);
  }

  /**
   * 计算语言自适应的 maxChars。
   * 英语、印尼语等拉丁字母语言平均更长，需要更高阈值避免过度切分。
   * 印尼语独立参数：印尼语词汇通常比英语更长（一个词往往包含多个音节），
   * 且虚词结构（如 yang、untuk、dari）较多，需要更宽松的阈值。
   */
  private int effectiveMaxChars(String language) {
    if ("en".equals(language)) {
      int calculated = (int) Math.round(maxChars * enMaxCharsMultiplier);
      return Math.min(enMaxCharsMax, Math.max(enMaxCharsMin, calculated));
    }
    if ("id".equals(language)) {
      int calculated = (int) Math.round(maxChars * idMaxCharsMultiplier);
      return Math.min(idMaxCharsMax, Math.max(idMaxCharsMin, calculated));
    }
    return maxChars;
  }

  private List<Segment> tryEmit(String language, double confidence, int floor) {
    List<Segment> result = new ArrayList<>();
    int effMax = effectiveMaxChars(language);
    // 方案A：增加缓冲量，只有超过 effMax + 缓冲 才强制切分
    int hardSplitThreshold = effMax + softBreakChars;

    while (!buffer.isEmpty()) {
      String current = buffer.toString();

      // 只有当缓冲区超过 hardSplitThreshold 时才强制切分
      if (current.length() >= hardSplitThreshold) {
        int splitAt = findBestSplit(current, effMax);
        if (splitAt > 0 && splitAt < current.length()) {
          result.add(emit(current.substring(0, splitAt).trim(), language, confidence, floor));
          buffer.delete(0, splitAt);
          trimLeadingSpaces();
        } else {
          result.add(emit(current.trim(), language, confidence, floor));
          buffer.setLength(0);
        }
        continue;
      }

      if (SENTENCE_END.matcher(current).find()) {
        result.add(emit(current.trim(), language, confidence, floor));
        buffer.setLength(0);
        continue;
      }

      break;
    }
    return result;
  }

  private List<Segment> splitAndFlush(String language, double confidence, int floor) {
    if (buffer.isEmpty()) return List.of();
    List<Segment> result = new ArrayList<>();
    int effMax = effectiveMaxChars(language);
    int hardSplitThreshold = effMax + softBreakChars;

    while (!buffer.isEmpty()) {
      String current = buffer.toString();
      if (current.length() >= hardSplitThreshold) {
        int splitAt = findBestSplit(current, effMax);
        if (splitAt > 0 && splitAt < current.length()) {
          result.add(emit(current.substring(0, splitAt).trim(), language, confidence, floor));
          buffer.delete(0, splitAt);
          trimLeadingSpaces();
          continue;
        }
      }
      String text = buffer.toString().trim();
      buffer.setLength(0);
      if (!text.isEmpty()) result.add(emit(text, language, confidence, floor));
      break;
    }
    return result;
  }

  /**
   * 切分优先级（从高到低）：
   * 1. 句末标点 - 最优先，确保句子完整
   * 2. 语义关键词 - 确保语义连贯（如"但是"、"however"）
   * 3. 软标点 - 逗号/分号等自然断点
   * 4. 单词边界 - 空格处切分，避免截断单词
   * 5. 强制在 effMax 处切分 - 最后兜底
   *
   * <p>搜索策略：从 effMax 向前回溯，找到最近的自然断点，
   * 保证切分后的内容长度尽量接近但不超过 effMax。
   */
  private int findBestSplit(String text, int effMax) {
    int textLen = text.length();
    // 向前回溯的最大范围：最多回退 softBreakChars 个字符
    int searchStart = Math.min(textLen - 1, effMax);
    int searchEnd = Math.max(0, searchStart - softBreakChars);

    // 1. 从 effMax 向前搜索，找到最近的句末标点
    for (int i = searchStart; i >= searchEnd; i--) {
      if (i < textLen && PUNCT_SPLIT.matcher(String.valueOf(text.charAt(i))).matches()) {
        return i + 1;  // 在标点后切分
      }
    }

    // 2. 从 effMax 向前搜索，找到最近的语义关键词
    int semanticBreak = findLastSemanticBreakBackward(text, effMax, searchEnd);
    if (semanticBreak > 0) return semanticBreak;

    // 3. 从 effMax 向前搜索，找到最近的软标点（逗号、分号、冒号等）
    for (int i = searchStart; i >= searchEnd; i--) {
      if (i < textLen) {
        char c = text.charAt(i);
        if (c == ',' || c == '，' || c == ';' || c == '；' || c == '、' || c == ':' || c == '：') {
          return i + 1;
        }
      }
    }

    // 4. 从 effMax 向前搜索，找到最近的空格（单词边界）
    for (int i = searchStart; i >= searchEnd; i--) {
      if (i < textLen && text.charAt(i) == ' ') {
        return i + 1;
      }
    }

    // 5. 搜索更早的空格作为兜底
    for (int i = Math.min(searchEnd - 1, textLen - 1); i >= 0; i--) {
      if (i < textLen && text.charAt(i) == ' ') {
        return i + 1;
      }
    }

    // 6. 最后兜底：在 effMax 处强制切分
    return effMax;
  }

  /**
   * 从 effMax 向前搜索语义关键词的位置（返回关键词后的位置）。
   */
  private int findLastSemanticBreakBackward(String text, int searchStart, int searchEnd) {
    String lower = text.toLowerCase();
    int bestPos = -1;
    for (String keyword : SEMANTIC_BREAK_KEYWORDS) {
      // 在搜索范围内向前查找
      for (int i = searchStart - keyword.length(); i >= searchEnd; i--) {
        if (i >= 0 && i + keyword.length() <= textLen(lower)) {
          int matchLen = keyword.length();
          boolean match = true;
          for (int j = 0; j < matchLen; j++) {
            if (lower.charAt(i + j) != keyword.charAt(j)) {
              match = false;
              break;
            }
          }
          if (match) {
            int breakPos = i + matchLen;
            if (breakPos > bestPos) {
              bestPos = breakPos;
            }
          }
        }
      }
    }
    return bestPos;
  }

  private int textLen(String s) { return s.length(); }

  private void trimLeadingSpaces() {
    while (!buffer.isEmpty() && buffer.charAt(0) == ' ') {
      buffer.deleteCharAt(0);
    }
  }

  /**
   * 创建 Segment，初始 index = -1，待外部 registry 分配后用 withIndex 注入。
   */
  private Segment emit(String text, String language, double confidence, int floor) {
    lastEmitTime = System.currentTimeMillis();
    return new Segment(-1, text, language, confidence, floor);
  }

  /**
   * Segment 支持从外部注入 registry 分配的全局 index。
   */
  public record Segment(int index, String text, String language, double confidence, int floor) {
    public Segment withIndex(int newIndex) {
      return new Segment(newIndex, text, language, confidence, floor);
    }
  }
}
