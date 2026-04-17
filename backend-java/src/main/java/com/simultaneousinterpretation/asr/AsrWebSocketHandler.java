package com.simultaneousinterpretation.asr;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simultaneousinterpretation.config.AsrProperties;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.service.LanguageDetectionService;
import com.simultaneousinterpretation.service.RealtimeTranslateService;
import com.simultaneousinterpretation.service.RealtimeTtsService;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import com.simultaneousinterpretation.config.PipelineTuningParams;
import com.simultaneousinterpretation.meeting.RoomSegmentRegistry;
import com.simultaneousinterpretation.meeting.RoomWebSocketHandler;

@Component
public class AsrWebSocketHandler extends AbstractWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(AsrWebSocketHandler.class);

  public static final String ATTR_UPSTREAM_WS = "asr.upstreamWs";
  public static final String ATTR_PROVIDER = "asr.provider";
  public static final String ATTR_TASK_ID = "asr.taskId";
  private static final String ATTR_SEG_ENGINE = "asr.segEngine";
  private static final String ATTR_SEG_BATCH_COUNTER = "asr.segBatchCounter";
  private static final String ATTR_SEG_TIMER = "asr.segTimer";
  private static final String ATTR_GLOSSARY = "asr.glossary";
  private static final String ATTR_CONTEXT = "asr.context";
  /** 发往上游 ASR 的 PCM 入队缓冲；消费线程通过 CF 链异步串行发送，不阻塞本线程 */
  private static final String ATTR_UPSTREAM_PCM_Q = "asr.upstreamPcmQueue";
  private static final String ATTR_UPSTREAM_PCM_THREAD = "asr.upstreamPcmThread";
  private static final String ATTR_UPSTREAM_PCM_STARTED = "asr.upstreamPcmStarted";
  private static final String ATTR_LISTEN_LANG = "asr.listenLang";
  /** 最近一次 ASR 结果里的 language */
  private static final String ATTR_LAST_ASR_LANG = "asr.lastAsrLang";
  /** 语言切换状态机 */
  private static final String ATTR_LANG_LOCK = "asr.langLock";
  private static final String ATTR_LANG_CANDIDATE = "asr.langCandidate";
  private static final String ATTR_LANG_STREAK = "asr.langStreak";
  /** 上一 segment 的 detectedLang */
  private static final String ATTR_PREV_SEG_LANG = "asr.prevSegLang";
  /** 上一句是否已触发过语言切换 */
  private static final String ATTR_SEG_LANG_SWITCHED = "asr.segLangSwitched";
  /** 收听语言 TTS 串行排队 */
  private static final String ATTR_LISTEN_TTS_CHAIN = "asr.listenTtsChain";
  /** 上一段 TTS 已发出第一块音频 */
  private static final String ATTR_LISTEN_TTS_STARTED_CHAIN = "asr.listenTtsStartedChain";
  /** 上游 ASR WebSocket 未就绪时暂存的 PCM 帧（按序转发） */
  private static final String ATTR_PENDING_PCM = "asr.pendingPcmQueue";
  /** 关联的房间 ID */
  private static final String ATTR_ROOM_ID = "asr.roomId";
  /** 当前说话语言 */
  private static final String ATTR_CURRENT_SRC_LANG = "asr.currentSrcLang";
  /** 最新已注册 segment 的 segIdx */
  private static final String ATTR_LATEST_SEG_IDX = "asr.latestSegIdx";
  private static final List<String> ALL_LANGS = List.of("zh", "en", "id");
  /**
   * 需转发给 /ws/room 听众的 ASR 下行事件（与主持人出站队列同序写出后再转发）。
   * 必须包含 segment，否则听众只依赖 broadcastSegment 会与 translation 顺序/字段不一致。
   * TTS 的 WAV/audio_end 由 RoomAudioDispatcher 二进制下发。
   */
  private static final Set<String> ROOM_FORWARD_EVENTS =
      Set.of(
          "segment",
          "translation",
          "tts_skip",
          "transcript",
          "error",
          "ready",
          "playback_start",
          "playback_end");

  /** PCM 入队缓冲（HTTP 接收线程写入、虚拟线程消费），容量大于任何预期积压量 */
  private static final int UPSTREAM_PCM_QUEUE_CAPACITY = 8000;

  private final ScheduledExecutorService segScheduler = Executors.newScheduledThreadPool(2);

  /** 每个源→目标语言对独立线程池，彻底消除跨语言队列阻塞。容量 4 足够应对实时流，因为每对语言一次只处理一段 */
  private static final int PER_LANG_POOL_SIZE = 4;
  private static final ConcurrentHashMap<String, ExecutorService> langPairPools = new ConcurrentHashMap<>();

  private static ExecutorService langPairPool(String srcLang, String tgtLang) {
    String key = srcLang + "->" + tgtLang;
    return langPairPools.computeIfAbsent(key, k ->
        Executors.newFixedThreadPool(PER_LANG_POOL_SIZE, r -> {
          Thread t = new Thread(r, "trans-" + k);
          t.setDaemon(true);
          return t;
        }));
  }

  private static final String PROVIDER_DASHSCOPE = "dashscope";
  private static final String PROVIDER_DEEPGRAM = "deepgram";
  private static final String PROVIDER_OPENAI = "openai";

  private final AsrProperties asrProperties;
  private final DashScopeProperties dashScopeProperties;
  private final DeepgramJsonMapper deepgramJsonMapper;
  private final ObjectMapper objectMapper;
  private final RealtimeTranslateService translateService;
  private final RealtimeTtsService ttsService;
  private final PipelineTuningParams tuningParams;
  private final RoomWebSocketHandler roomWebSocketHandler;
  private final com.simultaneousinterpretation.meeting.RoomSegmentRegistry segmentRegistry;
  private final LanguageDetectionService languageDetectionService;

  public AsrWebSocketHandler(
      AsrProperties asrProperties,
      DashScopeProperties dashScopeProperties,
      DeepgramJsonMapper deepgramJsonMapper,
      ObjectMapper objectMapper,
      RealtimeTranslateService translateService,
      RealtimeTtsService ttsService,
      PipelineTuningParams tuningParams,
      RoomWebSocketHandler roomWebSocketHandler,
      com.simultaneousinterpretation.meeting.RoomSegmentRegistry segmentRegistry,
      LanguageDetectionService languageDetectionService) {
    this.asrProperties = asrProperties;
    this.dashScopeProperties = dashScopeProperties;
    this.deepgramJsonMapper = deepgramJsonMapper;
    this.objectMapper = objectMapper;
    this.translateService = translateService;
    this.ttsService = ttsService;
    this.tuningParams = tuningParams;
    this.roomWebSocketHandler = roomWebSocketHandler;
    this.segmentRegistry = segmentRegistry;
    this.languageDetectionService = languageDetectionService;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    String user = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USERNAME);
    int floor =
        (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);
    String provider =
        asrProperties.getProvider() != null
            ? asrProperties.getProvider().trim().toLowerCase()
            : PROVIDER_DASHSCOPE;

    log.info("[ASR] 连接建立：用户={} floor={} provider={} 三语模式(zh/en/id) sessionId={}",
        user, floor, provider, session.getId());

    AsrClientTextOutboundQueue.start(session, this::relayOutboundJsonAfterHostSent);

    // 使用运行时调参构建切段引擎（支持语言自适应 maxChars）
    SegmentationEngine segEngine = new SegmentationEngine(
        tuningParams.getSegMaxChars(),
        tuningParams.getSegSoftBreakChars(),
        tuningParams.getSegFlushTimeoutMs(),
        tuningParams.getSegEnMaxCharsMultiplier(),
        tuningParams.getSegEnMaxCharsMin(),
        tuningParams.getSegEnMaxCharsMax(),
        tuningParams.getSegIdMaxCharsMultiplier(),
        tuningParams.getSegIdMaxCharsMin(),
        tuningParams.getSegIdMaxCharsMax()
    );
    session.getAttributes().put(ATTR_SEG_ENGINE, segEngine);
    session.getAttributes().put(ATTR_SEG_BATCH_COUNTER, new java.util.concurrent.atomic.AtomicLong(System.nanoTime()));
    session.getAttributes().put(ATTR_LANG_LOCK, "");
    session.getAttributes().put(ATTR_LANG_CANDIDATE, "");
    session.getAttributes().put(ATTR_LANG_STREAK, 0);
    session.getAttributes().put(ATTR_LISTEN_TTS_CHAIN, CompletableFuture.completedFuture(null));
    session.getAttributes().put(ATTR_LISTEN_TTS_STARTED_CHAIN, CompletableFuture.completedFuture(null));
    session.getAttributes().put(ATTR_LATEST_SEG_IDX, new AtomicInteger(0));
    session.getAttributes().put(ATTR_CURRENT_SRC_LANG, "en"); // 默认英语

    // 初始化流水线日志追踪器
    PipelineStageLogger.get(session);

    // v1 切段 tick：须与流式 final 共用 incrementAndGet()，禁止 batchCounter.get()+序号（会与首条 final 的 batchId 碰撞）
    java.util.concurrent.atomic.AtomicLong batchCounter =
        (java.util.concurrent.atomic.AtomicLong) session.getAttributes().get(ATTR_SEG_BATCH_COUNTER);
    ScheduledFuture<?> timer = segScheduler.scheduleAtFixedRate(() -> {
      try {
        List<SegmentationEngine.Segment> segs = segEngine.tick("", floor);
        if (segs.isEmpty()) {
          return;
        }
        long segmentBatchId = batchCounter.incrementAndGet();
        for (SegmentationEngine.Segment seg : segs) {
          sendSegmentEvent(session, seg, segmentBatchId);
        }
      } catch (Exception e) {
        log.debug("[切段] tick 异常: {}", e.getMessage());
      }
    }, 200, 400, TimeUnit.MILLISECONDS);
    session.getAttributes().put(ATTR_SEG_TIMER, timer);

    if (PROVIDER_DEEPGRAM.equals(provider)) {
      connectDeepgram(session, floor);
    } else if (PROVIDER_OPENAI.equals(provider)) {
      connectOpenAi(session, floor);
    } else {
      connectDashScope(session, floor);
    }
  }

  // ─── 语言规范化 ───

  private static String normalizeLang(String raw) {
    if (raw == null || raw.isBlank()) return "";
    String lower = raw.trim().toLowerCase();
    if (lower.startsWith("zh") || "cn".equals(lower) || "chinese".equals(lower)) return "zh";
    if (lower.startsWith("en") || "english".equals(lower)) return "en";
    if (lower.startsWith("id") || lower.startsWith("in") || "indonesian".equals(lower) || "malay".equals(lower)) return "id";
    return lower;
  }

  /**
   * 将 ASR 的 language 字段 + 会话提示 + FastText 检测 + 文本字形归一为 zh/en/id。
   * Gummy 等模型常在 final 上省略 lang，旧逻辑默认 zh 会导致拉丁原文被标成中文，
   * 且与真实语种不一致时翻译/TTS 链路表现异常。
   *
   * <p>检测优先级：
   * 1. ASR 原生 language 字段
   * 2. FastText 语言检测（需 langid-service 运行中且 enabled=true）
   * 3. 会话级 ATTR_LAST_ASR_LANG（来自 partial 或前一句 final）
   * 4. 字形启发式推断（汉字→zh；拉丁+印尼功能词→id；其余拉丁→en）
   * 5. 最终兜底 zh
   */
  private String resolveDetectedLang(String rawAsrLang, String text, String sessionHintLang) {
    String a = normalizeLang(rawAsrLang);
    if (!a.isEmpty()) {
      return a;
    }
    // FastText 检测（同步调用，毫秒级，配置 enabled=true 时启用）
    String fastTextLang = languageDetectionService.detect(text);
    if (!fastTextLang.isEmpty()) {
      return fastTextLang;
    }
    // 会话历史语言兜底
    String h = normalizeLang(sessionHintLang);
    if (!h.isEmpty()) {
      return h;
    }
    return inferTrilingualFromText(text);
  }

  private static int countCjkChars(String s) {
    if (s == null || s.isEmpty()) {
      return 0;
    }
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
    if (s == null || s.isEmpty()) {
      return 0;
    }
    int n = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
        n++;
      }
    }
    return n;
  }

  /** 印尼语常见功能词（拉丁字母稿），用于与英语区分。 */
  private static boolean looksIndonesianHeuristic(String t) {
    if (t == null || t.isBlank()) {
      return false;
    }
    String lower = t.toLowerCase(java.util.Locale.ROOT);
    String padded = " " + lower + " ";
    String[] hints = {
        " yang ", " dan ", " tidak ", " ada ", " dengan ", " untuk ", " dari ", " ini ", " itu ",
        " atau ", " juga ", " kita ", " mereka ", " anda ", " telah ", " sudah ", " akan ",
        " pada ", " oleh ", " seperti ", " lebih ", " banyak ", " antara ", " variasi ", " berarti ",
    };
    for (String h : hints) {
      if (padded.contains(h)) {
        return true;
      }
    }
    return lower.startsWith("di ") || lower.startsWith("ke ") || lower.startsWith("yang ")
        || lower.startsWith("ada ");
  }

  private static String inferTrilingualFromText(String text) {
    if (text == null || text.isBlank()) {
      return "zh";
    }
    String t = text.trim();
    int cjk = countCjkChars(t);
    int latin = countLatinLetters(t);
    if (cjk >= 1 && (cjk >= 2 || latin == 0 || cjk * 3 >= latin)) {
      return "zh";
    }
    if (latin >= 2 && cjk == 0) {
      return looksIndonesianHeuristic(t) ? "id" : "en";
    }
    return "zh";
  }

  /**
   * 同传语言锁定策略：连续两句话不属于同一语言时，立即切换状态机。
   *
   * <p>与旧 streak 策略的区别：旧策略要求「连续出现 N 次相同语言才切换」，
   * 这会导致中间夹杂一两个词的其他语言（如"这个 product 很好"）被忽略，
   * 用户切换为英文汇报时需等 2 个完整英文句子才响应，体验迟钝。
   *
   * <p>新策略：当检测到的语言与上一句语言不同（即「连续两句话不属于同一语言」）时，
   * 立即切换状态机。这样中间夹杂一个词的混合句（如 product）仍视为与前句相同语言，
   * 但切换为纯英文汇报时，第一句英文就会触发切换。
   *
   * <p>segLangSwitched 仅用于单次 smooth 调用内部；每段 {@link #sendSegmentEvent} 入口会清零，
   * 避免「同一次终稿切出的多句」里第二句起被错误锁在上一句语种。
   */
  private static String smoothDetectedLang(
      WebSocketSession session, String candidate, String text, String prevDetectedLang) {
    String lock = normalizeLang((String) session.getAttributes().getOrDefault(ATTR_LANG_LOCK, ""));
    String cand = normalizeLang(candidate);
    if (cand.isEmpty()) cand = "zh";

    // 首次进入：直接锁定
    if (lock.isEmpty()) {
      session.getAttributes().put(ATTR_LANG_LOCK, cand);
      session.getAttributes().put(ATTR_PREV_SEG_LANG, cand);
      session.getAttributes().put(ATTR_SEG_LANG_SWITCHED, false);
      return cand;
    }

    // 与当前锁一致：重置，不切换
    if (lock.equals(cand)) {
      session.getAttributes().put(ATTR_PREV_SEG_LANG, cand);
      session.getAttributes().put(ATTR_SEG_LANG_SWITCHED, false);
      return lock;
    }

    // 特别短句（如"嗯""啊"）不触发切换
    String t = text == null ? "" : text.trim();
    if (t.length() <= 2) {
      return lock;
    }

    // 防止同 batch 多次 onFinal 重复触发切换
    Boolean alreadySwitched = (Boolean) session.getAttributes().getOrDefault(ATTR_SEG_LANG_SWITCHED, false);
    if (Boolean.TRUE.equals(alreadySwitched)) {
      session.getAttributes().put(ATTR_PREV_SEG_LANG, cand);
      return lock;
    }

    // 关键判断：当前句与上一句不属于同一语言 → 立即切换
    String prevLang = normalizeLang((String) session.getAttributes().getOrDefault(ATTR_PREV_SEG_LANG, lock));
    if (!cand.equals(prevLang)) {
      // 连续两句话不属于同一语言，立即切换
      session.getAttributes().put(ATTR_LANG_LOCK, cand);
      session.getAttributes().put(ATTR_PREV_SEG_LANG, cand);
      session.getAttributes().put(ATTR_SEG_LANG_SWITCHED, true);
      return cand;
    }

    return lock;
  }

  /**
   * 将 ASR 返回的语言归一化为三语之一 zh/en/id。
   */
  private static String canonicalizeTrilingual(String detectedLang) {
    return normalizeLang(detectedLang);
  }

  private int inputSampleRateForSession(WebSocketSession session) {
    String prov = (String) session.getAttributes().get(ATTR_PROVIDER);
    if (PROVIDER_DEEPGRAM.equals(prov)) {
      return asrProperties.getDeepgram().getSampleRate();
    } else if (PROVIDER_OPENAI.equals(prov)) {
      return asrProperties.getOpenai().getSampleRate();
    }
    return asrProperties.getDashscope().getSampleRate();
  }

  // ─── 切段 → 翻译（三语） ───

  /**
   * @param segmentBatchId 同一次 ASR 终稿/切段循环内切出的多句共用同一时间戳，供前端合并为一条展示
   */
  // ─── 流式处理入口（方案一+三）：每切出一句立即处理 ─────────────────────────────

  /**
   * 流式处理：每切出一句 segment 时立即处理。
   * 与 sendSegmentEvent 的区别：
   * 1. sendSegmentEvent 在 for 循环中顺序调用，每句等待前一句完成
   * 2. processSegmentStreaming 作为回调，每句立即触发，不等待其他句子
   *
   * <p>优势：
   * - 方案一：切出一句立即处理，无需等待整段话说完
   * - 方案三：翻译/TTS 并行启动，降低端到端延迟
   *
   * @param session WebSocket 会话
   * @param seg 切出的 segment
   * @param segmentBatchId 同一批次（同一 final）的所有 segment 共用此 ID
   */
  private void processSegmentStreaming(WebSocketSession session, SegmentationEngine.Segment seg, long segmentBatchId) {
    // 直接复用 sendSegmentEvent 的核心逻辑
    // 注意：sendSegmentEvent 本身是线程安全的，可以并发调用
    sendSegmentEvent(session, seg, segmentBatchId);
  }

  /**
   * 发送 segment 事件给前端 + 注册到 room registry + 触发翻译/TTS。
   * 支持并发调用（流式模式下多个 segment 可同时处理）。
   */
  private void sendSegmentEvent(WebSocketSession session, SegmentationEngine.Segment seg, long segmentBatchId) {
    String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);

    try {
      // 一次 ASR 终稿 / tick 会连续 emit 多段，每段都应独立做语种平滑。
      // 若保留「本批次已切换过语种」到下一 sendSegmentEvent，后续段会全部被标成上一段的 lock
      //（例如先出英文 "Sometimes," 再出中文，第二段仍标 en 或反过来），听众端再按 segmentTs+语言合并
      // 就会拼成 "Sometimes, 我有时…" 且三种收听语言界面表现一致。
      session.getAttributes().put(ATTR_SEG_LANG_SWITCHED, false);

      // 记录切段阶段
      PipelineStageLogger.get(session).stage(session, -1, PipelineStageLogger.Stage.SEG_EMIT);
      log.info("[切段-EMIT] engineIdx={} text=\"{}\" lang={} floor={}",
          seg.index(), truncate(seg.text(), 50), seg.language(), seg.floor());

      String hint = (String) session.getAttributes().getOrDefault(ATTR_LAST_ASR_LANG, "");
      String candidateLang = resolveDetectedLang(seg.language(), seg.text(), hint);
      String detectedLang = smoothDetectedLang(session, candidateLang, seg.text(), null);
      String canonicalSource = canonicalizeTrilingual(detectedLang);

      long serverTs = System.currentTimeMillis();
      Map<String, Object> m = new HashMap<>();
      m.put("event", "segment");
      m.put("index", -1); // 暂存，后续更新为 registry index
      m.put("text", seg.text());
      m.put("detectedLang", detectedLang);
      // 归一三语码，与 text 对应；前端优先用 sourceLang / lang 展示
      m.put("sourceLang", canonicalSource);
      m.put("lang", canonicalSource);
      m.put("textRole", "source");
      m.put("isSourceText", true);
      m.put("language", seg.language());
      m.put("confidence", seg.confidence());
      m.put("floor", seg.floor());
      m.put("serverTs", serverTs);
      m.put("segmentTs", segmentBatchId);
      m.put("inputSampleRate", inputSampleRateForSession(session));

      // 添加切段参数快照（供前端调试）
      Map<String, Object> tuningSnapshot = new HashMap<>();
      tuningSnapshot.put("segMaxChars", tuningParams.getSegMaxChars());
      tuningSnapshot.put("segEnMaxCharsMultiplier", tuningParams.getSegEnMaxCharsMultiplier());
      tuningSnapshot.put("segEnMaxCharsMin", tuningParams.getSegEnMaxCharsMin());
      tuningSnapshot.put("segEnMaxCharsMax", tuningParams.getSegEnMaxCharsMax());
      tuningSnapshot.put("segSoftBreakChars", tuningParams.getSegSoftBreakChars());
      tuningSnapshot.put("segFlushTimeoutMs", tuningParams.getSegFlushTimeoutMs());
      m.put("tuning", tuningSnapshot);

      // ─── 写入房间注册表（主持人和听众统一分发的核心） ───
      // Registry 分配全局唯一的 segment index
      int regSegIdx;
      if (roomId != null && segmentRegistry != null) {
        RoomSegmentRegistry.RoomRegistry reg = segmentRegistry.getOrCreate(roomId);
        RoomSegmentRegistry.SegmentRecord rec = reg.registerSegment(
            seg.text(), canonicalSource, seg.confidence(),
            seg.floor(), serverTs, segmentBatchId);
        regSegIdx = rec.getSegIndex();
        
        log.info("[SEG-REGISTER] time={} segIdx={} text=\"{}\" detectedLang={} canonical={} floor={} batchId={}",
            serverTs, regSegIdx, truncate(seg.text(), 60), detectedLang, canonicalSource, seg.floor(), segmentBatchId);

        // 更新最新 segIdx（供 backlog 计算使用）
        AtomicInteger latestSeg = (AtomicInteger) session.getAttributes().get(ATTR_LATEST_SEG_IDX);
        if (latestSeg != null) latestSeg.set(regSegIdx);

        // 记录当前 segment 的 batchId
        session.getAttributes().put("asr.currentBatchId", segmentBatchId);
        
        // segment JSON 仅通过 sendPayload → 出站队列 → relayOutboundJsonAfterHostSent 转发给听众（与 translation 同序）
        // 更新当前说话语言
        session.getAttributes().put(ATTR_CURRENT_SRC_LANG, canonicalSource);

        log.info("[SEG-SENT] time={} segIdx={} text=\"{}\" detectedLang={} canonical={} floor={} batchId={}",
            serverTs, regSegIdx, truncate(seg.text(), 60), detectedLang, canonicalSource, seg.floor(), segmentBatchId);
      } else {
        regSegIdx = -1;
        log.info("[SEG-SENT] time={} segIdx={} text=\"{}\" detectedLang={} canonical={} floor={} [no-room]",
            serverTs, regSegIdx, truncate(seg.text(), 60), detectedLang, canonicalSource, seg.floor());
      }

      m.put("sequence", regSegIdx);
      m.put("index", regSegIdx); // registry 分配的 segIdx，回写后再 sendPayload
      sendPayload(session, m);

      // ─── 三语翻译：始终对两种非源语言并行翻译 ───
      String glossary = (String) session.getAttributes().getOrDefault(ATTR_GLOSSARY, "");
      String context = (String) session.getAttributes().getOrDefault(ATTR_CONTEXT, "");
      final String srcLang = canonicalSource;
      final int finalRegSegIdx = regSegIdx; // 闭包中使用 registry 分配的 index

      // 所有三种语言：ASR 原文对每种语言均做翻译和 TTS
      List<String> allLangs = new ArrayList<>(ALL_LANGS);
      // 三种语言的翻译 Future
      Map<String, CompletableFuture<String>> transFutures = new HashMap<>();

      for (String tgtLang : allLangs) {
        int finalSegIdx = finalRegSegIdx;
        String finalTgtLang = tgtLang;
        boolean isSameLang = tgtLang.equals(srcLang);
        if (!isSameLang) {
          log.info("[TRANS-REQ] time={} segIdx={} srcLang={} → tgtLang={} text=\"{}\"",
              System.currentTimeMillis(), finalSegIdx, srcLang, finalTgtLang, truncate(seg.text(), 50));
        }
        PipelineStageLogger.get(session).setTargetLangs(finalSegIdx, String.join(",", allLangs));
        PipelineStageLogger.get(session).stage(session, finalSegIdx, PipelineStageLogger.Stage.TRANSLATE_REQ);

        CompletableFuture<String> tf = CompletableFuture.supplyAsync(() -> {
          if (isSameLang) {
            // 同源语言：直接使用 ASR 原文，不调用翻译服务
            log.info("[TRANS-SAME-LANG] segIdx={} srcLang={} → tgtLang={} 直接返回原文",
                finalSegIdx, srcLang, finalTgtLang);
            return seg.text();
          }
          AsrLatencyTrace.get(session).translateBegin(session, finalSegIdx, finalTgtLang);
          try {
            long llmStartNano = System.nanoTime();
            String result = translateService.translate(seg.text(), srcLang, tgtLang, glossary, context);
            long llmMs = (System.nanoTime() - llmStartNano) / 1_000_000L;
            PipelineStageLogger.get(session).stage(session, finalSegIdx, PipelineStageLogger.Stage.TRANSLATE_DONE);
            log.info("[TRANS-DONE] time={} segIdx={} srcLang={} → tgtLang={} elapsed={}ms original=\"{}\" translated=\"{}\"",
                System.currentTimeMillis(), finalSegIdx, srcLang, finalTgtLang, llmMs,
                truncate(seg.text(), 60), truncate(result, 60));
            AsrLatencyTrace.get(session).translateEndListen(session, finalSegIdx, llmStartNano, result != null);
            return result;
          } catch (Exception e) {
            log.error("[TRANS-ERR] time={} segIdx={} srcLang={} → tgtLang={} error={}",
                System.currentTimeMillis(), finalSegIdx, srcLang, finalTgtLang, e.getMessage());
            return null;
          }
        }, langPairPool(srcLang, tgtLang));

        transFutures.put(tgtLang, tf);
      }

      // ─── 三语 TTS：对每种非源语言各自独立合成，翻译完成即开始（不等其他语言） ───
      // 下一段各语言 TTS 的启动信号
      final CompletableFuture<Void> nextTtsStartedSignal = new CompletableFuture<>();

      for (String tgtLang : allLangs) {
        final String finalTgtLang = tgtLang;
        boolean isSameLang = srcLang.equals(tgtLang);
        log.info("[TTS-Chain-START] regSegIdx={} srcLang={} tgtLang={} ★翻译链路={}",
            finalRegSegIdx, srcLang, finalTgtLang, isSameLang ? "同源(直接TTS原文)" : "翻译(需翻译后再TTS)");
        CompletableFuture<String> trFuture = transFutures.get(tgtLang);
        if (trFuture == null) {
          log.warn("[TTS-Chain-NULL] regSegIdx={} →{} 无翻译Future，跳过", finalRegSegIdx, finalTgtLang);
          continue;
        }

        CompletableFuture<Void> langTtsChain = trFuture.thenComposeAsync(translated -> {
          if (!session.isOpen()) {
            log.debug("[TTS-Chain] regSegIdx={} →{} session已关闭，跳过", finalRegSegIdx, finalTgtLang);
            return CompletableFuture.completedFuture(null);
          }
          if (translated == null || translated.isBlank()) {
            try {
              log.warn("[TTS-SKIP] regSegIdx={} →{} 翻译失败，跳过TTS", finalRegSegIdx, finalTgtLang);
              if (session.isOpen()) {
                if (roomId != null && segmentRegistry != null) {
                  segmentRegistry.getOrCreate(roomId).onTtsSkip(finalRegSegIdx, finalTgtLang, "translate_failed");
                  segmentRegistry.getOrCreate(roomId).onAudioEnd(finalRegSegIdx, finalTgtLang, "translate_failed");
                }
              }
            } catch (Exception ignored) {}
            return CompletableFuture.completedFuture(null);
          }

          // 必须在发起 TTS 之前下发 translation，避免乱序
          log.info("[TRANS-SENT] time={} segIdx={} srcLang={} → tgtLang={} text=\"{}\"",
              System.currentTimeMillis(), finalRegSegIdx, srcLang, tgtLang, truncate(translated, 50));

          // 直接发送给主持人浏览器（显示文本+翻译对照）
          Map<String, Object> tm = new HashMap<>();
          tm.put("event", "translation");
          tm.put("index", finalRegSegIdx);
          tm.put("sequence", finalRegSegIdx);
          tm.put("textRole", "translation");
          tm.put("isSourceText", false);
          tm.put("lang", tgtLang);
          tm.put("detectedLang", tgtLang); // 目标语言，用于前端按语言合并翻译文本
          tm.put("sourceText", seg.text());
          tm.put("translatedText", translated);
          tm.put("sourceLang", srcLang);
          tm.put("targetLang", tgtLang);
          tm.put("floor", seg.floor());
          long transSentWallMs = System.currentTimeMillis();
          tm.put("serverTs", transSentWallMs);
          tm.put("segmentTs", segmentBatchId);
          try {
            sendPayload(session, tm);
          } catch (IOException e) {
            log.debug("[翻译] 发送 translation 事件失败: {}", e.getMessage());
          }

          // 发起 TTS（非阻塞入队，由 synthesizeStreaming 内部管理槽位）
          long ttsReqTs = System.currentTimeMillis();
          PipelineStageLogger.get(session).setTtsLang(finalRegSegIdx, finalTgtLang);
          PipelineStageLogger.get(session).stage(session, finalRegSegIdx, PipelineStageLogger.Stage.TTS_REQ);
          log.info("[TTS-REQ] time={} segIdx={} srcLang={} → tgtLang={} text=\"{}\"",
              ttsReqTs, finalRegSegIdx, srcLang, finalTgtLang, truncate(translated, 50));

          // 获取 TTS 语速：基准 + backlog 补偿
          double rate = tuningParams.getTtsRate();
          AtomicInteger latest = (AtomicInteger) session.getAttributes().get(ATTR_LATEST_SEG_IDX);
          if (latest != null) {
            int backlog = Math.max(0, latest.get() - finalRegSegIdx);
            double backlogIncrease = backlog * tuningParams.getTtsBacklogCoeff();
            double cap = tuningParams.getTtsBacklogCap();
            if (cap > 0) backlogIncrease = Math.min(backlogIncrease, cap);
            rate = tuningParams.getTtsRate() + backlogIncrease;
          }
          rate = Math.max(0.5, Math.min(2.0, rate));

          log.info("[TTS-START] time={} segIdx={} srcLang={} → tgtLang={} rate={}",
              ttsReqTs, finalRegSegIdx, srcLang, finalTgtLang, rate);

          // 记录 TTS 调用发起时刻，补全 toTtsInvokeMs
          AsrLatencyTrace.get(session).onListenTtsInvoke(session, finalRegSegIdx);

          boolean queued = ttsService.synthesizeStreaming(
              translated, finalTgtLang, rate, finalRegSegIdx,
              new RealtimeTtsService.AudioChunkCallback() {
                private boolean firstChunk = true;
                private int chunkCount = 0;
                @Override
                public void onChunk(int segIdx, String tgtLang, byte[] chunk) throws IOException {
                  // 不检查 session.isOpen()：主持人断开后 TTS 仍在合成，音频应继续下发给已连接的听众
                  long now = System.currentTimeMillis();
                  if (firstChunk) {
                    firstChunk = false;
                    long ttsFirstDelay = now - ttsReqTs;
                    long totalDelay = now - serverTs;
                    log.info("[TTS-FIRST-CHUNK] time={} segIdx={} srcLang={} → tgtLang={} " +
                        "ttsDelay={}ms totalDelay={}ms size={} format=wav",
                        now, segIdx, srcLang, tgtLang, ttsFirstDelay, totalDelay, chunk.length);
                    PipelineStageLogger.get(session).stage(
                        session, segIdx, PipelineStageLogger.Stage.TTS_FIRST_CHUNK, chunk.length);
                    nextTtsStartedSignal.complete(null);
                  }
                  chunkCount++;
                  log.info("[TTS-CHUNK] time={} segIdx={} srcLang={} → tgtLang={} chunk#={} size={}",
                      now, segIdx, srcLang, tgtLang, chunkCount, chunk.length);
                  if (roomId != null && segmentRegistry != null) {
                    log.info("[TTS-CHUNK-DISPATCH] time={} segIdx={} tgtLang={} chunk#={} size={} → onTtsChunk",
                        now, segIdx, tgtLang, chunkCount, chunk.length);
                    segmentRegistry.getOrCreate(roomId).onTtsChunk(segIdx, tgtLang, chunk);
                  } else {
                    log.warn("[TTS-CHUNK-NO-REGISTRY] segIdx={} tgtLang={} roomId={} segmentRegistry={}",
                        segIdx, tgtLang, roomId, segmentRegistry != null ? "OK" : "NULL");
                  }
                }
              },
              // onComplete：由 TTS 线程在最后一块 WAV 入队后调用，保证 END 帧晚于 WAV 帧。
              // 不检查 session.isOpen()：主持人断开后 TTS 仍在合成，END 帧必须下发给已连接的听众，
              // 否则听众端 nextSegIdxRef 会永久卡在此 segIdx 无法继续播放。
              () -> {
                if (roomId != null && segmentRegistry != null) {
                  log.info("[TTS-END-AFTER-WAV] time={} segIdx={} srcLang={} → tgtLang={} ★TTS完成，发送END帧 sessionOpen={}",
                      System.currentTimeMillis(), finalRegSegIdx, srcLang, finalTgtLang, session.isOpen());
                  segmentRegistry.getOrCreate(roomId).onAudioEnd(finalRegSegIdx, finalTgtLang, "done");
                }
              });
          long ttsEndTs = System.currentTimeMillis();
          if (!queued && session.isOpen()) {
            // API Key 未配置时 synthesizeStreaming 返回 false，同步补发 skip+end
            log.warn("[TTS-SKIP] time={} segIdx={} srcLang={} → tgtLang={} reason=no_audio",
                ttsEndTs, finalRegSegIdx, srcLang, finalTgtLang);
            if (roomId != null && segmentRegistry != null) {
              segmentRegistry.getOrCreate(roomId).onTtsSkip(finalRegSegIdx, finalTgtLang, "no_audio");
              segmentRegistry.getOrCreate(roomId).onAudioEnd(finalRegSegIdx, finalTgtLang, "no_audio");
            }
          }
          PipelineStageLogger.get(session).stage(session, finalRegSegIdx, PipelineStageLogger.Stage.TTS_DONE);
          return CompletableFuture.completedFuture(null);
        }, langPairPool(srcLang, tgtLang));


        // 每个语言的 TTS 完成时发 audio_end（不管成功还是异常）
        langTtsChain.exceptionally(ex -> {
          long excTs = System.currentTimeMillis();
          log.error("[TTS-EXCEPTION] time={} segIdx={} srcLang={} → tgtLang={} exception={}",
              excTs, finalRegSegIdx, srcLang, finalTgtLang, ex.getMessage());
          try {
            if (session.isOpen()) {
              if (roomId != null && segmentRegistry != null) {
                segmentRegistry.getOrCreate(roomId).onTtsSkip(finalRegSegIdx, finalTgtLang, "error");
                segmentRegistry.getOrCreate(roomId).onAudioEnd(finalRegSegIdx, finalTgtLang, "error");
              }
            }
          } catch (Exception ignored) {}
          return null;
        });
      }

      // 更新下一段 TTS 启动信号（取最大值：任意一种语言首个到达即解除）
      session.getAttributes().put(ATTR_LISTEN_TTS_STARTED_CHAIN, nextTtsStartedSignal);
    } catch (IOException e) {
      log.debug("[切段] 发送 segment 事件失败: {}", e.getMessage());
    }
  }

  // ─── DashScope ───

  private void connectDashScope(WebSocketSession session, int floor) throws IOException {
    AsrProperties.DashScope ds = asrProperties.getDashscope();
    // 优先取 app.dashscope 统一 Key，回退至 app.asr.dashscope（旧字段兼容）
    String apiKey = dashScopeProperties.getEffectiveApiKey(ds.getApiKey());
    if (!StringUtils.hasText(apiKey)) {
      String msg = "未配置百炼 API Key，请在 app.dashscope.api-key 或环境变量 DASHSCOPE_API_KEY 中配置。";
      log.error("[ASR] {}", msg);
      sendAsrError(session, "ASR_NOT_CONFIGURED", msg,
          "当前 app.asr.provider=dashscope，必须提供有效密钥后才能建立上游实时识别连接。");
      session.close(CloseStatus.POLICY_VIOLATION.withReason("ASR not configured"));
      return;
    }

    String taskId = UUID.randomUUID().toString().replace("-", "");
    session.getAttributes().put(ATTR_PROVIDER, PROVIDER_DASHSCOPE);
    session.getAttributes().put(ATTR_TASK_ID, taskId);

    String wsUrl = ds.getWsUrl().trim();
    log.info("[ASR] 连接 DashScope：taskId={} wsUrl={} model={} sampleRate={}",
        taskId, wsUrl, ds.getModel(), ds.getSampleRate());

    StringBuilder lineBuf = new StringBuilder();
    HttpClient.newHttpClient()
        .newWebSocketBuilder()
        .header("Authorization", "bearer " + apiKey.trim())
        .buildAsync(
            URI.create(wsUrl),
            new WebSocket.Listener() {
              @Override
              public void onOpen(WebSocket webSocket) {
                log.info("[ASR] DashScope WebSocket 已连接，发送 run-task taskId={}", taskId);
                webSocket.request(1);
                try {
                  webSocket.sendText(buildDashScopeRunTask(taskId, ds), true);
                } catch (JsonProcessingException e) {
                  log.error("[ASR] 构造 run-task JSON 失败", e);
                  try {
                    sendAsrError(session, "RUN_TASK_JSON",
                        "无法构造 DashScope run-task 指令。",
                        e.getClass().getSimpleName() + ": " + e.getMessage());
                    session.close(CloseStatus.SERVER_ERROR);
                  } catch (IOException ignored) {
                  }
                }
              }

              @Override
              public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                lineBuf.append(data);
                if (!last) {
                  return WebSocket.Listener.super.onText(webSocket, data, last);
                }
                String line = lineBuf.toString();
                lineBuf.setLength(0);
                try {
                  handleDashScopeLine(session, floor, webSocket, line);
                } catch (IOException e) {
                  log.debug("[ASR] DashScope 行处理异常: {}", line, e);
                }
                return WebSocket.Listener.super.onText(webSocket, data, last);
              }

              @Override
              public void onError(WebSocket webSocket, Throwable error) {
                log.error(
                    "[ASR] DashScope WebSocket 通信错误 taskId={} 摘要={}",
                    taskId,
                    throwableDetail(error),
                    error);
                try {
                  sendAsrError(session, "DASHSCOPE_WS_ERROR",
                      "与 DashScope 实时语音服务的连接出现异常。",
                      throwableDetail(error));
                } catch (IOException ignored) {
                }
                try {
                  session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
              }
            })
        .whenComplete(
            (ws, ex) -> {
              if (ex != null) {
                log.error("[ASR] DashScope 连接失败: {}", throwableDetail(ex));
                try {
                  sendAsrError(session, "DASHSCOPE_CONNECT_FAILED",
                      "无法连接到 DashScope WebSocket（请检查网络、ws-url、地域与 API Key 是否匹配）。",
                      throwableDetail(ex));
                  session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
              }
            });
  }

  private void handleDashScopeLine(WebSocketSession session, int floor, WebSocket webSocket, String line)
      throws IOException {
    JsonNode root = objectMapper.readTree(line);
    String event = root.path("header").path("event").asText("");
    log.debug("[ASR] DashScope 事件: {}", event);

    switch (event) {
      case "task-started":
        synchronized (session) {
          session.getAttributes().put(ATTR_UPSTREAM_WS, webSocket);
        }
        try {
          flushPendingToUpstreamSendQueue(session);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        AsrProperties.DashScope ds = asrProperties.getDashscope();
        log.info("[ASR] DashScope task-started，ASR 就绪 floor={} sampleRate={}", floor, ds.getSampleRate());
        Map<String, Object> ready = new HashMap<>();
        ready.put("event", "ready");
        ready.put("floor", floor);
        ready.put("sampleRate", ds.getSampleRate());
        ready.put("provider", PROVIDER_DASHSCOPE);
        ready.put("currentSeq", 0);
        sendPayload(session, ready);
        break;
      case "result-generated":
        log.info("[ASR-RAW] {}", line.length() > 500 ? line.substring(0, 500) + "..." : line);
        AsrClientEvent ev = DashScopeRealtimeParser.parseResultGenerated(root, floor);
        if (ev != null) {
          if (ev.isError()) {
            sendAsrError(session, "UPSTREAM_ERROR", ev.text(), null);
          } else {
            // 记录 ASR 阶段（partial 或 final）
            PipelineStageLogger.Stage stage = ev.partial()
                ? PipelineStageLogger.Stage.ASR_PARTIAL
                : PipelineStageLogger.Stage.ASR_FINAL;
            PipelineStageLogger.get(session).stage(session, ev.floor(), stage);

            if (StringUtils.hasText(ev.language())) {
              String nl = normalizeLang(ev.language());
              if (!nl.isEmpty()) {
                session.getAttributes().put(ATTR_LAST_ASR_LANG, nl);
              }
            }
            sendPayload(session, transcriptPayload(ev,
                (Long) session.getAttributes().getOrDefault("asr.currentBatchId", -1L)));
            long asrResultWallMs = System.currentTimeMillis();
            log.info("[LAT] upstream_asr_result sessionId={} floor={} partial={} text=\"{}\" wallMs={}",
                session.getId(), ev.floor(), ev.partial(), truncate(ev.text(), 60), asrResultWallMs);
            if (!ev.partial()) {
              SegmentationEngine segEngine = (SegmentationEngine) session.getAttributes().get(ATTR_SEG_ENGINE);
              if (segEngine != null) {
                java.util.concurrent.atomic.AtomicLong batchCounter =
                    (java.util.concurrent.atomic.AtomicLong) session.getAttributes().get(ATTR_SEG_BATCH_COUNTER);
                long segmentBatchId = batchCounter.incrementAndGet();
                log.info("[切段-STREAM] finalText=\"{}\" batchId={}", truncate(ev.text(), 80), segmentBatchId);

                // 流式回调：每切出一句立即处理
                segEngine.onFinalTranscriptStreaming(ev.text(), ev.language(), ev.confidence(), ev.floor(),
                    segmentBatchId, (SegmentationEngine.Segment seg) -> processSegmentStreaming(session, seg, segmentBatchId));
              }
            }
          }
        }
        break;
      case "task-finished":
        log.info("[ASR] DashScope task-finished");
        break;
      case "task-failed":
        String failMsg = DashScopeRealtimeParser.parseTaskFailedMessage(root);
        log.error("[ASR] DashScope task-failed: {}", failMsg);
        sendAsrError(session, "TASK_FAILED", failMsg,
            line.length() > 800 ? line.substring(0, 800) + "…" : line);
        try {
          session.close(CloseStatus.SERVER_ERROR);
        } catch (IOException ignored) {
        }
        break;
      default:
        log.debug("[ASR] DashScope 未处理事件: {} 内容: {}",
            event, line.length() > 200 ? line.substring(0, 200) + "…" : line);
        break;
    }
  }

  private String buildDashScopeRunTask(String taskId, AsrProperties.DashScope ds)
      throws JsonProcessingException {
    ObjectNode root = objectMapper.createObjectNode();
    ObjectNode header = root.putObject("header");
    header.put("action", "run-task");
    header.put("task_id", taskId);
    header.put("streaming", "duplex");
    ObjectNode payload = root.putObject("payload");
    payload.put("task_group", "audio");
    payload.put("task", "asr");
    payload.put("function", "recognition");
    payload.put("model", ds.getModel());
    ObjectNode parameters = payload.putObject("parameters");
    parameters.put("format", ds.getFormat());
    parameters.put("sample_rate", ds.getSampleRate());

    // Fun-ASR 参数
    parameters.put("semantic_punctuation_enabled", ds.isSemanticPunctuationEnabled());

    // Gummy 参数（source_language 不设即 auto 检测）
    // Fun-ASR 无此字段，留空（JsonNode NULL_OBJECT）不会影响 Fun-ASR 行为
    if (ds.isGummyModel()) {
      parameters.put("transcription_enabled", true);
    }

    payload.putObject("input");
    return objectMapper.writeValueAsString(root);
  }

  private String buildDashScopeFinishTask(String taskId) throws JsonProcessingException {
    ObjectNode root = objectMapper.createObjectNode();
    ObjectNode header = root.putObject("header");
    header.put("action", "finish-task");
    header.put("task_id", taskId);
    header.put("streaming", "duplex");
    root.putObject("payload").putObject("input");
    return objectMapper.writeValueAsString(root);
  }

  // ─── Deepgram ───

  private void connectDeepgram(WebSocketSession session, int floor) throws IOException {
    AsrProperties.Deepgram dg = asrProperties.getDeepgram();
    // 优先取独立 Deepgram Key，回退至 app.dashscope 统一 Key
    String apiKey = dashScopeProperties.getEffectiveApiKey(dg.getApiKey());
    if (!StringUtils.hasText(apiKey)) {
      String msg = "未配置 Deepgram API Key。请设置 app.asr.deepgram.api-key 或 DEEPGRAM_API_KEY，"
          + "也可使用 app.dashscope.api-key（DASHSCOPE_API_KEY）通过 dashscope provider。";
      log.error("[ASR] {}", msg);
      sendAsrError(session, "ASR_NOT_CONFIGURED", msg,
          "当前 app.asr.provider=deepgram，需要有效的 Deepgram Token。");
      session.close(CloseStatus.POLICY_VIOLATION.withReason("ASR not configured"));
      return;
    }

    session.getAttributes().put(ATTR_PROVIDER, PROVIDER_DEEPGRAM);

    StringBuilder dgLineBuf = new StringBuilder();
    String url = buildDeepgramListenUrl(dg);
    log.info("[ASR] 连接 Deepgram：url={}", url);

    HttpClient.newHttpClient()
        .newWebSocketBuilder()
        .header("Authorization", "Token " + apiKey.trim())
        .buildAsync(
            URI.create(url),
            new WebSocket.Listener() {
              @Override
              public void onOpen(WebSocket webSocket) {
                log.info("[ASR] Deepgram WebSocket 已连接 floor={}", floor);
                webSocket.request(1);
                synchronized (session) {
                  session.getAttributes().put(ATTR_UPSTREAM_WS, webSocket);
                }
                try {
                  flushPendingToUpstreamSendQueue(session);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                try {
                  Map<String, Object> ready = new HashMap<>();
                  ready.put("event", "ready");
                  ready.put("floor", floor);
                  ready.put("sampleRate", dg.getSampleRate());
                  ready.put("provider", PROVIDER_DEEPGRAM);
                  ready.put("currentSeq", 0);
                  sendPayload(session, ready);
                } catch (IOException e) {
                  log.warn("[ASR] 发送 ready 失败", e);
                }
              }

              @Override
              public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                dgLineBuf.append(data);
                if (!last) {
                  return WebSocket.Listener.super.onText(webSocket, data, last);
                }
                String line = dgLineBuf.toString();
                dgLineBuf.setLength(0);
                try {
                  AsrClientEvent ev = deepgramJsonMapper.parseLine(line, floor);
                  if (ev != null) {
                    if (ev.isError()) {
                      sendAsrError(session, "DEEPGRAM_ERROR",
                          ev.text() != null ? ev.text() : "Deepgram 返回错误", null);
                    } else {
                      sendPayload(session, transcriptPayload(ev,
                          (Long) session.getAttributes().getOrDefault("asr.currentBatchId", -1L)));
                      long asrResultWallMs = System.currentTimeMillis();
                      log.info("[LAT] upstream_asr_result sessionId={} provider=deepgram floor={} partial={} text=\"{}\" wallMs={}",
                          session.getId(), ev.floor(), ev.partial(), truncate(ev.text(), 60), asrResultWallMs);
                      if (!ev.partial()) {
                        SegmentationEngine segEngine = (SegmentationEngine) session.getAttributes().get(ATTR_SEG_ENGINE);
                        if (segEngine != null) {
                          java.util.concurrent.atomic.AtomicLong batchCounter =
                              (java.util.concurrent.atomic.AtomicLong) session.getAttributes().get(ATTR_SEG_BATCH_COUNTER);
                          long segmentBatchId = batchCounter.incrementAndGet();
                          log.info("[切段-STREAM] provider=deepgram finalText=\"{}\" batchId={}", truncate(ev.text(), 80), segmentBatchId);
                          segEngine.onFinalTranscriptStreaming(ev.text(), ev.language(), ev.confidence(), ev.floor(),
                              segmentBatchId, (SegmentationEngine.Segment seg) -> processSegmentStreaming(session, seg, segmentBatchId));
                        }
                      }
                    }
                  }
                } catch (IOException e) {
                  log.debug("[ASR] Deepgram 行处理: {}", line, e);
                }
                return WebSocket.Listener.super.onText(webSocket, data, last);
              }

              @Override
              public void onError(WebSocket webSocket, Throwable error) {
                log.error("[ASR] Deepgram WebSocket 错误", error);
                try {
                  sendAsrError(session, "DEEPGRAM_WS_ERROR",
                      "与 Deepgram 的连接出现异常。", throwableDetail(error));
                } catch (IOException ignored) {
                }
                try {
                  session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
              }
            })
        .whenComplete(
            (ws, ex) -> {
              if (ex != null) {
                log.error("[ASR] Deepgram 连接失败", ex);
                try {
                  sendAsrError(session, "DEEPGRAM_CONNECT_FAILED",
                      "无法连接到 Deepgram（请检查网络与 API Key）。",
                      throwableDetail(ex));
                  session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
              }
            });
  }

  // ─── OpenAI Realtime ───

  private void connectOpenAi(WebSocketSession session, int floor) throws IOException {
    AsrProperties.OpenAi oai = asrProperties.getOpenai();
    // 优先取独立 OpenAI Key，回退至 app.dashscope 统一 Key
    String apiKey = dashScopeProperties.getEffectiveApiKey(oai.getApiKey());
    if (!StringUtils.hasText(apiKey)) {
      String msg = "未配置 OpenAI API Key。请设置 app.asr.openai.api-key 或 OPENAI_ASR_API_KEY，"
          + "也可使用 app.dashscope.api-key（DASHSCOPE_API_KEY）通过 dashscope provider。";
      log.error("[ASR] {}", msg);
      sendAsrError(session, "ASR_NOT_CONFIGURED", msg,
          "当前 app.asr.provider=openai，需要有效的 OpenAI API Key。");
      session.close(CloseStatus.POLICY_VIOLATION.withReason("ASR not configured"));
      return;
    }

    session.getAttributes().put(ATTR_PROVIDER, PROVIDER_OPENAI);

    String encodedModel = URLEncoder.encode(oai.getModel(), StandardCharsets.UTF_8);
    String wsUrl = oai.getBaseUrl().replace("https://", "wss://").replace("http://", "wss://")
        + "/realtime?model=" + encodedModel;

    log.info("[ASR] 连接 OpenAI Realtime：url={} model={} sampleRate={}",
        wsUrl, oai.getModel(), oai.getSampleRate());

    StringBuilder oaiLineBuf = new StringBuilder();
    HttpClient.newHttpClient()
        .newWebSocketBuilder()
        .header("Authorization", "Bearer " + apiKey.trim())
        .header("OpenAI-Beta", "realtime=v1")
        .buildAsync(
            URI.create(wsUrl),
            new WebSocket.Listener() {
              @Override
              public void onOpen(WebSocket webSocket) {
                log.info("[ASR] OpenAI WebSocket 已连接 floor={}", floor);
                webSocket.request(1);
                try {
                  String sessionConfig = buildOpenAiSessionUpdate(oai);
                  webSocket.sendText(sessionConfig, true).get();
                } catch (Exception e) {
                  log.error("[ASR] OpenAI 发送 session.update 失败", e);
                }
              }

              @Override
              public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                oaiLineBuf.append(data);
                if (!last) {
                  return WebSocket.Listener.super.onText(webSocket, data, last);
                }
                String line = oaiLineBuf.toString();
                oaiLineBuf.setLength(0);
                try {
                  handleOpenAiLine(session, floor, webSocket, line);
                } catch (Exception e) {
                  log.debug("[ASR] OpenAI 行处理: {}", line, e);
                }
                return WebSocket.Listener.super.onText(webSocket, data, last);
              }

              @Override
              public void onError(WebSocket webSocket, Throwable error) {
                log.error("[ASR] OpenAI WebSocket 错误", error);
                try {
                  sendAsrError(session, "OPENAI_WS_ERROR",
                      "与 OpenAI Realtime API 的连接出现异常。", throwableDetail(error));
                } catch (IOException ignored) {
                }
                try {
                  session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
              }
            })
        .whenComplete(
            (ws, ex) -> {
              if (ex != null) {
                log.error("[ASR] OpenAI 连接失败", ex);
                try {
                  sendAsrError(session, "OPENAI_CONNECT_FAILED",
                      "无法连接到 OpenAI（请检查网络与 API Key）。", throwableDetail(ex));
                  session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                }
              }
            });
  }

  private String buildOpenAiSessionUpdate(AsrProperties.OpenAi oai)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("event", "session.update");
    root.put("type", "session.update");

    ObjectNode sessionNode = root.putObject("session");
    // 仅转录，不使用对话响应
    sessionNode.putPOJO("modalities", java.util.List.of());
    // 明确三语书写规则，减轻印尼语/马来语被音译成汉字或误判为英语的问题
    sessionNode.put(
        "instructions",
        "You are a speech-to-text transcription service. Output rules: "
            + "(1) Chinese speech → Chinese characters. "
            + "(2) English speech → Latin letters. "
            + "(3) Indonesian or Malay speech → Latin letters only (standard spelling); "
            + "never transcribe Indonesian or Malay using Chinese characters, Japanese kana, or Korean Hangul. "
            + "(4) Code-switching: keep each language in its correct script as above.");

    // 转录配置
    ObjectNode transcriptionNode = sessionNode.putObject("input_audio_transcription");
    transcriptionNode.put("model", oai.getModel());
    if (StringUtils.hasText(oai.getLanguage())) {
      transcriptionNode.put("language", oai.getLanguage());
    }

    // VAD 配置
    if (oai.isVadEnabled()) {
      ObjectNode vadNode = sessionNode.putObject("voice_activity_detection");
      vadNode.put("enabled", true);
    }

    // 音频输入配置
    ObjectNode audioNode = sessionNode.putObject("input_audio");
    audioNode.put("sample_rate", oai.getSampleRate());
    audioNode.put("channels", 1);
    audioNode.put("format", "pcm16");

    return objectMapper.writeValueAsString(root);
  }

  private void handleOpenAiLine(WebSocketSession session, int floor, WebSocket webSocket, String line)
      throws IOException {
    JsonNode root;
    try {
      root = objectMapper.readTree(line);
    } catch (Exception e) {
      log.debug("[ASR] OpenAI JSON 解析失败: {}", line);
      return;
    }

    String type = root.path("type").asText("");
    log.debug("[ASR] OpenAI 事件: type={}", type);

    switch (type) {
      case "session.created":
        log.info("[ASR] OpenAI Session 创建成功 floor={}", floor);
        synchronized (session) {
          session.getAttributes().put(ATTR_UPSTREAM_WS, webSocket);
        }
        try {
          flushPendingToUpstreamSendQueue(session);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        try {
          Map<String, Object> ready = new HashMap<>();
          ready.put("event", "ready");
          ready.put("floor", floor);
          ready.put("sampleRate", asrProperties.getOpenai().getSampleRate());
          ready.put("provider", PROVIDER_OPENAI);
          ready.put("currentSeq", 0);
          sendPayload(session, ready);
        } catch (IOException e) {
          log.warn("[ASR] 发送 ready 失败", e);
        }
        break;

      case "session.updated":
        log.debug("[ASR] OpenAI Session 配置更新完成");
        break;

      case "conversation.item.input_audio_transcription.completed":
        String text = root.path("transcript").asText("");
        if (StringUtils.hasText(text)) {
          String language = root.path("language").asText("");
          if (!StringUtils.hasText(language)) {
            language = asrProperties.getOpenai().getLanguage();
          }
          double confidence = 0.9;

          log.info("[ASR] OpenAI 转录完成: text=\"{}\" lang={}", text, language);
          AsrClientEvent ev = AsrClientEvent.of("transcript", false, text, language, confidence, floor);
          sendPayload(session, transcriptPayload(ev,
              (Long) session.getAttributes().getOrDefault("asr.currentBatchId", -1L)));
          long asrResultWallMs = System.currentTimeMillis();
          log.info("[LAT] upstream_asr_result sessionId={} provider=openai floor={} partial={} text=\"{}\" wallMs={}",
              session.getId(), floor, false, truncate(text, 60), asrResultWallMs);

          // 触发切段（方案一+三：流式处理，切出一句立即翻译）
          SegmentationEngine segEngine = (SegmentationEngine) session.getAttributes().get(ATTR_SEG_ENGINE);
          if (segEngine != null) {
            java.util.concurrent.atomic.AtomicLong batchCounter =
                (java.util.concurrent.atomic.AtomicLong) session.getAttributes().get(ATTR_SEG_BATCH_COUNTER);
            long segmentBatchId = batchCounter.incrementAndGet();
            log.info("[切段-STREAM] finalText=\"{}\" batchId={}", truncate(text, 80), segmentBatchId);

            // 流式回调：每切出一句立即处理（翻译+TTS），不等所有句子切完
            segEngine.onFinalTranscriptStreaming(text, language, confidence, floor, segmentBatchId,
                (SegmentationEngine.Segment seg) -> processSegmentStreaming(session, seg, segmentBatchId));
          }
        }
        break;

      case "error":
        String err = root.path("error").path("message").asText("Unknown error");
        log.error("[ASR] OpenAI 错误: {}", err);
        sendAsrError(session, "OPENAI_ERROR", err, line.length() > 500 ? line.substring(0, 500) + "…" : line);
        break;

      default:
        log.debug("[ASR] OpenAI 未处理事件: {}", type);
        break;
    }
  }

  // ─── 文本消息（术语表/上下文注入） ───

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    try {
      JsonNode root = objectMapper.readTree(message.getPayload());
      String action = root.path("action").asText("");
      if ("setGlossary".equals(action)) {
        String glossary = root.path("glossary").asText("");
        String context = root.path("context").asText("");
        session.getAttributes().put(ATTR_GLOSSARY, glossary);
        session.getAttributes().put(ATTR_CONTEXT, context);
        log.info("[增强] 收到术语表({}字) 上下文({}字)", glossary.length(), context.length());
      } else if ("setListenLang".equals(action)) {
        String lang = root.path("lang").asText("").trim().toLowerCase();
        if (ALL_LANGS.contains(lang)) {
          session.getAttributes().put(ATTR_LISTEN_LANG, lang);
          log.info("[收听] 优先翻译/TTS 语言={}", lang);
        }
      } else if ("broadcastPlayback".equals(action)) {
        // 主持人播放进度事件，转发给所有听众
        String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);
        Integer segIdx = root.path("segIdx").asInt(-1);
        String lang = root.path("lang").asText("");
        Integer floor = root.path("floor").asInt(1);
        if (roomId != null && segIdx >= 0 && lang != null && roomWebSocketHandler != null) {
          roomWebSocketHandler.broadcastPlaybackEvent(roomId, "playback_sync", segIdx, lang, floor);
        }
      }
    } catch (Exception e) {
      log.debug("[WS] 文本消息解析失败: {}", e.getMessage());
    }
  }

  // ─── 二进制消息（音频数据转发） ───

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
    ByteBuffer payload = message.getPayload();
    byte[] raw = new byte[payload.remaining()];
    payload.get(raw);

    // 方案B：解析 8 字节采样位置前缀（BigInt64 大端序）
    byte[] pcm;
    if (raw.length >= 10) {
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
      bb.getLong(); // 跳过起始采样位置
      pcm = new byte[raw.length - 8];
      bb.get(pcm);
    } else {
      pcm = raw;
    }

    log.debug("[PCM-RECV] session={} size={}", session.getId(), pcm.length);

    // ── 转发给上游 ASR ──────────────────────────────────────────────────────
    WebSocket upstream = (WebSocket) session.getAttributes().get(ATTR_UPSTREAM_WS);
    if (upstream == null) {
      ConcurrentLinkedQueue<byte[]> pending =
          (ConcurrentLinkedQueue<byte[]>)
              session.getAttributes().computeIfAbsent(ATTR_PENDING_PCM, k -> new ConcurrentLinkedQueue<>());
      pending.add(pcm);
      return;
    }
    synchronized (session) {
      upstream = (WebSocket) session.getAttributes().get(ATTR_UPSTREAM_WS);
      if (upstream == null) {
        ConcurrentLinkedQueue<byte[]> pending =
            (ConcurrentLinkedQueue<byte[]>)
                session.getAttributes().computeIfAbsent(ATTR_PENDING_PCM, k -> new ConcurrentLinkedQueue<>());
        pending.add(pcm);
        return;
      }
    }
    appendBinaryToUpstreamSendQueue(session, pcm);
  }

  private void ensureUpstreamPcmLoopStarted(WebSocketSession session) {
    synchronized (session) {
      if (Boolean.TRUE.equals(session.getAttributes().get(ATTR_UPSTREAM_PCM_STARTED))) {
        return;
      }
      LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>(UPSTREAM_PCM_QUEUE_CAPACITY);
      session.getAttributes().put(ATTR_UPSTREAM_PCM_Q, q);
      session.getAttributes().put(ATTR_UPSTREAM_PCM_STARTED, Boolean.TRUE);
      // 虚拟线程：q.poll() 挂起时自动卸载载体线程，不占 OS 线程资源
      Thread t = Thread.ofVirtual()
          .name("si-asr-upstream-pcm-" + session.getId())
          .start(() -> runUpstreamPcmLoop(session, q));
      session.getAttributes().put(ATTR_UPSTREAM_PCM_THREAD, t);
    }
  }

  /**
   * PCM 上游发送循环（虚拟线程 + 批量串行链路）。
   *
   * <p>设计要点：
   * <ul>
   *   <li><b>批量发送</b>：每批 8 帧（约 512ms 音频）合并为一次 sendBinary，
   *       只等一次 ACK，大幅减少往返延迟开销（8 帧从 ~600ms 降至 ~75ms）。</li>
   *   <li><b>不丢帧</b>：所有帧都追加到链路，无上限、无丢弃。</li>
   *   <li><b>不阻塞</b>：本线程只做"出队 + 链路追加"（O(1)），不调 .get()。
   *       遇到 TCP 背压时，sendBinary 的 CompletableFuture 在 HTTP client 内部挂起，
   *       本虚拟线程继续出队，OS 线程不被占用。</li>
   *   <li><b>有序串行</b>：thenCompose 保证第 N+1 批必须等第 N 批的 sendBinary Future
   *       完成后才发出，WebSocket 串行语义始终满足，不抛 IllegalStateException。</li>
   *   <li><b>自动追赶</b>：网络拥塞结束后，HTTP client 线程连续触发链路中所有积压批，
   *       吞吐远高于单线程轮询，延迟快速收敛至实时。</li>
   *   <li><b>链路不中断</b>：handle() 在成功/失败时均返回正常值，单批失败不影响后续批。</li>
   * </ul>
   */
  private void runUpstreamPcmLoop(WebSocketSession session, LinkedBlockingQueue<byte[]> q) {
    // 串行发送链起点：已完成的空 Future
    CompletableFuture<Void> sendChain = CompletableFuture.completedFuture(null);
    // 链路深度计数器（入批+1 / 发送完成-1），用于积压监控
    java.util.concurrent.atomic.AtomicInteger chainDepth =
        new java.util.concurrent.atomic.AtomicInteger(0);
    // 链路活跃度统计：连续空 poll 次数
    java.util.concurrent.atomic.AtomicInteger idlePollCount =
        new java.util.concurrent.atomic.AtomicInteger(0);
    // 最后一次活跃时刻（毫秒），用于检测服务端长时间沉默
    java.util.concurrent.atomic.AtomicLong lastActiveMs =
        new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    // 批量参数：每批帧数 / 最大等待时间（先到为准）
    final int BATCH_SIZE = 8;
    final long BATCH_TIMEOUT_MS = 80;

    try {
      while (true) {
        // 先取一帧（阻塞等待，有帧来就立即开始攒批）
        long batchStart = System.currentTimeMillis();
        byte[] first = q.poll(250, TimeUnit.MILLISECONDS);
        if (first == null) {
          if (!session.isOpen()) break;
          // 统计链路空闲次数（长时间无帧 → 正常；但若积压清空后仍持续空闲，说明 ASR 端没在持续请求音频）
          int idle = idlePollCount.incrementAndGet();
          long sinceActive = System.currentTimeMillis() - lastActiveMs.get();
          // 连续 5 次 poll 空（≈1.25s 无帧）且链路空闲超过 5s，打印一次告警
          if (idle >= 5 && idle % 5 == 0 && sinceActive > 5000) {
            log.warn("[PCM-IDLE] 链路持续空闲 idlePoll={} 连续无帧={}ms sessionId={}",
                idle, idle * 250L, session.getId());
          }
          continue;
        }

        List<byte[]> batch = new ArrayList<>(BATCH_SIZE);
        batch.add(first);
        idlePollCount.set(0);
        lastActiveMs.set(System.currentTimeMillis());

        // 在 BATCH_TIMEOUT 内尽量攒够 BATCH_SIZE 帧
        long deadline = batchStart + BATCH_TIMEOUT_MS;
        while (batch.size() < BATCH_SIZE) {
          long remain = deadline - System.currentTimeMillis();
          if (remain <= 0) break;
          byte[] next = q.poll(remain, TimeUnit.MILLISECONDS);
          if (next == null) break;
          batch.add(next);
        }

        // 合并为一个大 byte[]
        int totalBytes = batch.stream().mapToInt(b -> b.length).sum();
        byte[] combined = new byte[totalBytes];
        int pos = 0;
        for (byte[] f : batch) {
          System.arraycopy(f, 0, combined, pos, f.length);
          pos += f.length;
        }

        int depth = chainDepth.incrementAndGet();
        // 积压预警：超过 15 批（≈1.2s，80ms×15）说明持续拥塞，打印告警供排查
        if (depth > 15 && depth % 8 == 1) {
          log.warn("[PCM-BACKLOG] 链路积压 {} 批（≈{}ms），DashScope TCP背压持续 sessionId={}",
              depth, depth * BATCH_TIMEOUT_MS, session.getId());
        }

        // 追加到链尾：上一批 sendBinary 完成后自动触发，保证有序且无并发冲突
        final List<byte[]> finalBatch = batch;
        sendChain = sendChain.thenCompose(ignored -> {
          WebSocket up;
          synchronized (session) {
            up = (WebSocket) session.getAttributes().get(ATTR_UPSTREAM_WS);
          }
          if (up == null) {
            // 上游 WebSocket 尚未就绪（握手中），跳过此批继续链路
            log.debug("[PCM-SKIP-NO-WS] 上游 WebSocket 未就绪，跳过批 sessionId={}", session.getId());
            return CompletableFuture.completedFuture(null);
          }
          long sendStart = System.currentTimeMillis();
          long sendWallMs = sendStart; // 记录入队墙钟时刻（供 handle 使用）
            return up.sendBinary(ByteBuffer.wrap(combined), true)
                .handle((ws, ex) -> {
                  // handle 同时处理成功和失败，使链路始终正常完成，下一帧得以继续
                  chainDepth.decrementAndGet();
                  lastActiveMs.set(System.currentTimeMillis());
                  long dur = System.currentTimeMillis() - sendStart;
                  if (ex != null) {
                    log.warn("[PCM-SEND-FAIL] sendBinary 失败 dur={}ms frames={} err={} sessionId={}",
                        dur, finalBatch.size(), ex.getMessage(), session.getId());
                  } else {
                    // 按耗时区间打日志，区分服务端消费行为：
                    //  < 200ms  → 正常，服务端立即消费
                    //  200-1000ms → 轻度拥塞，服务端有短暂积压
                    //  1000-5000ms → 中度拥塞，服务端消费速度明显慢于发送
                    //  > 5000ms  → 严重阻塞，服务端长时间不消费（很可能触发了服务端流控/限流）
                    if (dur < 200) {
                      log.info("[LAT] upstream_batch_send sessionId={} frames={} bytes={} sendMs={} wallMs={} ★正常",
                          session.getId(), finalBatch.size(), totalBytes, dur, sendWallMs);
                    } else if (dur < 1000) {
                      log.warn("[LAT] upstream_batch_send sessionId={} frames={} bytes={} sendMs={} wallMs={} ⚠轻度拥塞",
                          session.getId(), finalBatch.size(), totalBytes, dur, sendWallMs);
                    } else if (dur < 5000) {
                      log.warn("[LAT] upstream_batch_send sessionId={} frames={} bytes={} sendMs={} wallMs={} ⚠⚠中度拥塞(服务端消费慢)",
                          session.getId(), finalBatch.size(), totalBytes, dur, sendWallMs);
                    } else {
                      log.error("[LAT] upstream_batch_send sessionId={} frames={} bytes={} sendMs={} wallMs={} 🔥严重阻塞(服务端超过{}s未消费)",
                          session.getId(), finalBatch.size(), totalBytes, dur, sendWallMs, dur / 1000);
                    }
                  }
                  return (Void) null;
                });
        });
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void flushPendingToUpstreamSendQueue(WebSocketSession session) throws InterruptedException {
    ArrayList<byte[]> batch = new ArrayList<>();
    LinkedBlockingQueue<byte[]> q;
    synchronized (session) {
      ensureUpstreamPcmLoopStarted(session);
      @SuppressWarnings("unchecked")
      ConcurrentLinkedQueue<byte[]> pending =
          (ConcurrentLinkedQueue<byte[]>) session.getAttributes().get(ATTR_PENDING_PCM);
      if (pending != null) {
        byte[] c;
        while ((c = pending.poll()) != null) {
          batch.add(c);
        }
      }
      q = (LinkedBlockingQueue<byte[]>) session.getAttributes().get(ATTR_UPSTREAM_PCM_Q);
    }
    if (q == null) {
      return;
    }
    for (byte[] c : batch) {
      q.put(c);
    }
  }

  private void appendBinaryToUpstreamSendQueue(WebSocketSession session, byte[] copy)
      throws InterruptedException {
    ArrayList<byte[]> batch = new ArrayList<>();
    LinkedBlockingQueue<byte[]> q;
    synchronized (session) {
      ensureUpstreamPcmLoopStarted(session);
      @SuppressWarnings("unchecked")
      ConcurrentLinkedQueue<byte[]> pending =
          (ConcurrentLinkedQueue<byte[]>) session.getAttributes().get(ATTR_PENDING_PCM);
      if (pending != null) {
        byte[] c;
        while ((c = pending.poll()) != null) {
          batch.add(c);
        }
      }
      batch.add(copy);
      q = (LinkedBlockingQueue<byte[]>) session.getAttributes().get(ATTR_UPSTREAM_PCM_Q);
    }
    if (q == null) {
      return;
    }
    long enqueueWallMs = System.currentTimeMillis();
    for (byte[] c : batch) {
      q.put(c);
    }
    long totalBytes = batch.stream().mapToInt(b -> b.length).sum();
    log.info("[LAT] pcm_enqueue sessionId={} frames={} totalBytes={} wallMs={}",
        session.getId(), batch.size(), totalBytes, enqueueWallMs);
  }

  // ─── 连接关闭 ───

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    String user = (String) session.getAttributes().get(JwtHandshakeInterceptor.ATTR_USERNAME);
    String prov = (String) session.getAttributes().get(ATTR_PROVIDER);
    int floor = (Integer) session.getAttributes().getOrDefault(JwtHandshakeInterceptor.ATTR_FLOOR, 1);
    log.info("[ASR] 连接关闭：用户={} provider={} status={} sessionId={}",
        user, prov, status, session.getId());

    session.getAttributes().remove(ATTR_UPSTREAM_PCM_STARTED);
    Thread pcmThread = (Thread) session.getAttributes().remove(ATTR_UPSTREAM_PCM_THREAD);
    @SuppressWarnings("unchecked")
    LinkedBlockingQueue<byte[]> pcmQ =
        (LinkedBlockingQueue<byte[]>) session.getAttributes().remove(ATTR_UPSTREAM_PCM_Q);
    if (pcmThread != null) {
      pcmThread.interrupt();
    }
    if (pcmQ != null) {
      pcmQ.clear();
    }

    ScheduledFuture<?> timer = (ScheduledFuture<?>) session.getAttributes().remove(ATTR_SEG_TIMER);
    if (timer != null) timer.cancel(false);

    SegmentationEngine segEngine = (SegmentationEngine) session.getAttributes().remove(ATTR_SEG_ENGINE);
    java.util.concurrent.atomic.AtomicLong batchCounter =
        (java.util.concurrent.atomic.AtomicLong) session.getAttributes().remove(ATTR_SEG_BATCH_COUNTER);
    if (segEngine != null) {
      List<SegmentationEngine.Segment> remaining = segEngine.flushRemaining("", floor);
      long segmentBatchId = batchCounter != null ? batchCounter.incrementAndGet() : System.nanoTime();
      for (SegmentationEngine.Segment s : remaining) {
        sendSegmentEvent(session, s, segmentBatchId);
      }
    }

    WebSocket upstream = (WebSocket) session.getAttributes().remove(ATTR_UPSTREAM_WS);

    if (PROVIDER_DASHSCOPE.equals(prov) && upstream != null) {
      String taskId = (String) session.getAttributes().remove(ATTR_TASK_ID);
      if (taskId != null) {
        try {
          upstream.sendText(buildDashScopeFinishTask(taskId), true);
          log.debug("[ASR] 已发送 DashScope finish-task taskId={}", taskId);
        } catch (Exception e) {
          log.debug("[ASR] finish-task 发送失败", e);
        }
      }
    }

    if (upstream != null) {
      try {
        upstream.sendClose(WebSocket.NORMAL_CLOSURE, "client left");
      } catch (Exception ignored) {
      }
    }
    session.getAttributes().remove(ATTR_PROVIDER);
    session.getAttributes().remove(ATTR_PENDING_PCM);
    session.getAttributes().remove(ATTR_LISTEN_TTS_CHAIN);
    session.getAttributes().remove(ATTR_LISTEN_TTS_STARTED_CHAIN);
    AsrClientTextOutboundQueue.stop(session);

    String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);
    if (StringUtils.hasText(roomId) && roomWebSocketHandler != null) {
      roomWebSocketHandler.notifyListenersHostAsrStatus(roomId, "stopped");
    }

    super.afterConnectionClosed(session, status);
  }

  // ─── 工具方法 ───

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }

  private Map<String, Object> transcriptPayload(AsrClientEvent ev, long batchId) {
    Map<String, Object> m = new HashMap<>();
    m.put("event", "transcript");
    m.put("partial", ev.partial());
    m.put("text", ev.text());
    m.put("language", ev.language() != null ? ev.language() : "");
    m.put("confidence", ev.confidence());
    m.put("floor", ev.floor());
    m.put("batchId", batchId);
    return m;
  }

  private void sendPayload(WebSocketSession session, Map<String, Object> payload) throws IOException {
    if (!session.isOpen()) {
      return;
    }
    final String json;
    try {
      json = objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new IOException(e);
    }
    AsrClientTextOutboundQueue.enqueue(session, json);
  }

  /**
   * 在主持人 WebSocket 单线程队列真正写出本条 JSON 之后调用，向房间听众转发<strong>同一条</strong> JSON，
   * 保证听众与主持人事件顺序一致，避免多线程 sendPayload 先转发、后入队导致的丢段/乱序。
   */
  private void relayOutboundJsonAfterHostSent(WebSocketSession session, String json) {
    try {
      forwardOutboundJsonToRoom(session, json);
    } catch (Exception e) {
      log.warn("[WS] 房间转发异常: {}", e.getMessage());
    }
  }

  /** 主持仅连 /ws/asr 时，将切段/翻译/TTS 等事件同步给 /ws/room 上的听众（与主持收到顺序一致） */
  private void forwardOutboundJsonToRoom(WebSocketSession session, String json) throws IOException {
    if (roomWebSocketHandler == null) {
      return;
    }
    String roomId = (String) session.getAttributes().get(ATTR_ROOM_ID);
    if (!StringUtils.hasText(roomId)) {
      return;
    }
    JsonNode root = objectMapper.readTree(json);
    String event = root.path("event").asText("");
    if (event.isEmpty() || !ROOM_FORWARD_EVENTS.contains(event)) {
      return;
    }
    log.info(
        "[ForwardToRoom] event={} index={} targetLang={} roomId={}",
        event,
        root.path("index").toString(),
        root.path("targetLang").asText(""),
        roomId);
    roomWebSocketHandler.broadcastJsonToListeners(roomId, json);
    if ("ready".equals(event)) {
      roomWebSocketHandler.notifyListenersHostAsrStatus(roomId, "started");
    }
    if ("playback_start".equals(event) && root.hasNonNull("segIdx") && root.hasNonNull("lang")) {
      int segIdx = root.path("segIdx").asInt();
      String lang = root.path("lang").asText("");
      int floor = root.path("floor").asInt(1);
      if (!lang.isEmpty()) {
        roomWebSocketHandler.broadcastPlaybackEvent(roomId, "playback_start", segIdx, lang, floor);
      }
    } else if ("playback_end".equals(event) && root.hasNonNull("segIdx") && root.hasNonNull("lang")) {
      int segIdx = root.path("segIdx").asInt();
      String lang = root.path("lang").asText("");
      int floor = root.path("floor").asInt(1);
      if (!lang.isEmpty()) {
        roomWebSocketHandler.broadcastPlaybackEvent(roomId, "playback_end", segIdx, lang, floor);
      }
    }
  }

  private void sendAsrError(WebSocketSession session, String code, String message, String detail)
      throws IOException {
    Map<String, Object> m = new HashMap<>();
    m.put("event", "error");
    m.put("code", code);
    m.put("message", message);
    if (StringUtils.hasText(detail)) {
      m.put("detail", detail);
    }
    log.warn("[ASR] 发送错误到客户端: code={} message={}", code, message);
    sendPayload(session, m);
  }

  /**
   * 异常链摘要（用于前端 detail 与日志一行摘要）；{@code Connection reset} 多由网络中断或上游主动断连引起。
   */
  private static String throwableDetail(Throwable t) {
    if (t == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    Throwable c = t;
    for (int i = 0; i < 8 && c != null; i++) {
      if (i > 0) {
        sb.append(" | caused by: ");
      }
      sb.append(c.getClass().getSimpleName());
      String m = c.getMessage();
      if (m != null && !m.isBlank()) {
        sb.append(": ").append(m);
      }
      c = c.getCause();
    }
    String s = sb.toString();
    return s.length() <= 1800 ? s : s.substring(0, 1800) + "…";
  }

  private String buildDeepgramListenUrl(AsrProperties.Deepgram dg) {
    String model = URLEncoder.encode(dg.getModel(), StandardCharsets.UTF_8);
    return "wss://api.deepgram.com/v1/listen?"
        + "encoding=linear16"
        + "&sample_rate=" + dg.getSampleRate()
        + "&channels=1"
        + "&interim_results=" + dg.isInterimResults()
        + "&model=" + model
        + "&detect_language=" + dg.isDetectLanguage()
        + "&punctuate=true";
  }
}
