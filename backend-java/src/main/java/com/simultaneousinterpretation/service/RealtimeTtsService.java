package com.simultaneousinterpretation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simultaneousinterpretation.config.AiProperties;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.config.PipelineTuningParams;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * DashScope CosyVoice TTS — SSE 流式 + 增量回调。
 * 默认不在流中途切分：整段 CosyVoice 输出为一条完整 WAV，避免听众端按块 decode 失败或把 RIFF 头误当 PCM。
 * 若 {@code ttsChunkThreshold > 0}，则每累积该字节数回调一次（首包更快，但后续块往往不是独立 WAV，需听众端合并或按 PCM 处理）。
 *
 * <p>并发控制：使用 tryAcquire 非阻塞获取槽位，不在调用线程上同步等待；
 * 槽位满时将请求排队，等某次合成结束时自动出队执行，彻底避免线程阻塞导致的翻译延迟。
 */
@Service
public class RealtimeTtsService {

  private static final Logger log = LoggerFactory.getLogger(RealtimeTtsService.class);

  private static final String TTS_URL =
      "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer";

  /** 429 重试参数：最大重试次数和退避延迟(毫秒) */
  private static final int MAX_RETRIES = 3;
  private static final long[] RETRY_DELAYS_MS = {500, 1500, 4000};

  /** DashScope CosyVoice RPS 限流：两次调度之间最小间隔（毫秒）。实测约 3 RPS 触发 429，300ms ≈ 3.3 RPS。 */
  private static final long MIN_DISPATCH_INTERVAL_MS = 300;

  private final DashScopeProperties dashScopeProperties;
  private final AiProperties aiProperties;
  private final PipelineTuningParams tuningParams;
  private final int maxConcurrent;
  private final Queue<PendingTask> pendingQueue = new ConcurrentLinkedQueue<>();
  private final AtomicInteger activeCount = new AtomicInteger(0);
  private final AtomicLong lastDispatchTimeMs = new AtomicLong(0);
  private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final ExecutorService ttsExecutor;
  private final ScheduledExecutorService rateLimitScheduler;

  public RealtimeTtsService(AiProperties aiProperties, DashScopeProperties dashScopeProperties,
                           PipelineTuningParams tuningParams, ObjectMapper objectMapper) {
    this.aiProperties = aiProperties;
    this.dashScopeProperties = dashScopeProperties;
    this.tuningParams = tuningParams;
    this.objectMapper = objectMapper;
    this.maxConcurrent = tuningParams.getTtsMaxConcurrent();
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    this.ttsExecutor = Executors.newFixedThreadPool(maxConcurrent);
    this.rateLimitScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "tts-rate-limiter");
      t.setDaemon(true);
      return t;
    });
    log.info("[TTS] 服务初始化: maxConcurrent={} chunkThreshold={} model={} voice={}",
        maxConcurrent, tuningParams.getTtsChunkThreshold(),
        dashScopeProperties.getTtsModel(), dashScopeProperties.getTtsVoice());
  }

  @FunctionalInterface
  public interface AudioChunkCallback {
    void onChunk(int segIdx, String tgtLang, byte[] audioData) throws IOException;
  }

  /**
   * 旧版回调接口（兼容）- 内部包装后调用新版
   */
  @FunctionalInterface
  public interface LegacyAudioChunkCallback {
    void onChunk(byte[] audioData) throws IOException;
  }

  // ── 槽位管理 ──────────────────────────────────────────────────────────────

  private void enqueueTask(PendingTask task) {
    pendingQueue.offer(task);
    log.debug("[TTS-QUEUE] 入队 segIdx={} tgtLang={} queueSize={}", task.segIdx, task.lang, pendingQueue.size());
    drainQueue();
  }

  /**
   * 限流出队：每次调度间隔不低于 {@link #MIN_DISPATCH_INTERVAL_MS}，避免瞬时突发触发 DashScope 429。
   * 若当前距上次调度不足最小间隔，则通过 rateLimitScheduler 在剩余时间后自动重试一次；
   * 用 drainScheduled CAS 保证同一时刻只有一个待执行的 scheduled drain，防止积压时产生大量定时任务。
   *
   * <p>synchronized 保证多线程同时调用时（如多个 onTaskDone 并发触发）check-and-dispatch 是原子的，
   * 防止多线程同时通过 elapsed 检查、瞬间批量提交导致 DashScope 429。
   */
  private synchronized void drainQueue() {
    while (activeCount.get() < maxConcurrent) {
      if (pendingQueue.peek() == null) break;

      long now = System.currentTimeMillis();
      long elapsed = now - lastDispatchTimeMs.get();
      if (elapsed < MIN_DISPATCH_INTERVAL_MS) {
        long delay = MIN_DISPATCH_INTERVAL_MS - elapsed + 5;
        if (drainScheduled.compareAndSet(false, true)) {
          log.debug("[TTS-RATE-LIMIT] 下次调度在 {}ms 后（elapsed={}ms < limit={}ms）", delay, elapsed, MIN_DISPATCH_INTERVAL_MS);
          rateLimitScheduler.schedule(() -> {
            drainScheduled.set(false);
            drainQueue();
          }, delay, TimeUnit.MILLISECONDS);
        }
        break;
      }

      PendingTask next = pendingQueue.poll();
      if (next == null) break; // concurrent removal
      lastDispatchTimeMs.set(System.currentTimeMillis());
      activeCount.incrementAndGet();
      dispatchTask(next);
    }
  }

  private void onTaskDone() {
    int remaining = activeCount.decrementAndGet();
    log.debug("[TTS-SLOT] 释放，active={} queueSize={}", remaining, pendingQueue.size());
    drainQueue();
  }

  private void dispatchTask(PendingTask task) {
    ttsExecutor.execute(() -> doSynthesize(task));
  }

  private void doSynthesize(PendingTask task) {
    try {
      long t0 = System.currentTimeMillis();
      String body = buildRequestBody(task.text, task.lang, task.model, task.voice);
      String apiKey = dashScopeProperties.getEffectiveTtsApiKey();

      log.info("[TTS] 请求发起 segIdx={} tgtLang={} text=\"{}\"",
          task.segIdx, task.lang, truncate(task.text, 50));

      // ─── 429 重试循环 ───────────────────────────────────────────────
      int attempt = 0;
      HttpResponse<InputStream> resp = null;
      Exception lastException = null;

      while (attempt <= MAX_RETRIES) {
        attempt++;
        if (attempt > 1) {
          long delay = RETRY_DELAYS_MS[attempt - 2];
          log.info("[TTS-RETRY] segIdx={} tgtLang={} 重试 #{}，等待 {}ms",
              task.segIdx, task.lang, attempt, delay);
          try {
            Thread.sleep(delay);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            task.fail();
            return;
          }
        }

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(TTS_URL))
            .header("Authorization", "Bearer " + apiKey.trim())
            .header("Content-Type", "application/json")
            .header("X-DashScope-SSE", "enable")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(30))
            .build();

        try {
          resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
          lastException = e;
          log.warn("[TTS-RETRY] segIdx={} tgtLang={} 请求失败 #{}: {}",
              task.segIdx, task.lang, attempt, e.getMessage());
          if (attempt > MAX_RETRIES) {
            throw e;
          }
          continue;
        }

        int statusCode = resp.statusCode();
        log.info("[TTS-RESPONSE] segIdx={} tgtLang={} attempt={} status={}",
            task.segIdx, task.lang, attempt, statusCode);

        if (statusCode == 200) {
          break; // 成功，跳出重试循环
        }

        if (statusCode == 429) {
          String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
          log.warn("[TTS-429] segIdx={} tgtLang={} attempt={}/{} HTTP 429，err={}",
              task.segIdx, task.lang, attempt, MAX_RETRIES + 1, truncate(errBody, 200));
          if (attempt > MAX_RETRIES) {
            log.error("[TTS-429-EXHAUSTED] segIdx={} tgtLang={} 重试 {} 次后仍失败",
                task.segIdx, task.lang, MAX_RETRIES);
            task.fail();
            return;
          }
          continue; // 继续重试
        }

        // 其他非 200 错误
        String errBody = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
        log.error("[TTS] HTTP {} segIdx={} tgtLang={} err={}",
            statusCode, task.segIdx, task.lang, truncate(errBody, 300));
        task.fail();
        return;
      }

      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      int totalBytes = 0;
      int chunks = 0;
      boolean anyAudio = false;
      long firstChunkTs = 0;
      long lastChunkTs = 0;

      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data:")) continue;
          String json = line.substring(5).trim();
          if (json.isEmpty()) continue;
          try {
            JsonNode node = objectMapper.readTree(json);
            String b64 = node.path("output").path("audio").path("data").asText("");
            if (!b64.isEmpty()) {
              byte[] decoded = Base64.getDecoder().decode(b64);
              buffer.write(decoded);
              totalBytes += decoded.length;
              log.debug("[TTS-SSE-AUDIO] segIdx={} tgtLang={} sseChunkBytes={} bufferSoFar={} totalSoFar={}",
                  task.segIdx, task.lang, decoded.length, buffer.size(), totalBytes);

              long now = System.currentTimeMillis();
              if (task.chunkThreshold > 0 && buffer.size() >= task.chunkThreshold) {
                if (firstChunkTs == 0) firstChunkTs = now;
                lastChunkTs = now;
                log.info("[TTS-CB-INVOKE] segIdx={} tgtLang={} chunkNum={} chunkBytes={} elapsedMs={}",
                    task.segIdx, task.lang, chunks + 1, buffer.size(), now - t0);
                task.callback.onChunk(task.segIdx, task.lang, buffer.toByteArray());
                anyAudio = true;
                chunks++;
                buffer.reset();
              }
            }
          } catch (Exception parseEx) {
            log.debug("[TTS] SSE parse skip: {}", truncate(json, 80));
          }
        }
      }

      if (buffer.size() > 0) {
        long now = System.currentTimeMillis();
        if (firstChunkTs == 0) firstChunkTs = now;
        lastChunkTs = now;
        log.info("[TTS-CB-INVOKE-FINAL] segIdx={} tgtLang={} chunkNum={} chunkBytes={} elapsedMs={}",
            task.segIdx, task.lang, chunks + 1, buffer.size(), now - t0);
        task.callback.onChunk(task.segIdx, task.lang, buffer.toByteArray());
        anyAudio = true;
        chunks++;
      } else {
        log.warn("[TTS-NO-AUDIO] segIdx={} tgtLang={} buffer为空，SSE流未包含任何音频数据 elapsedMs={}",
            task.segIdx, task.lang, System.currentTimeMillis() - t0);
      }

      long elapsed = System.currentTimeMillis() - t0;
      long firstDelay = firstChunkTs > 0 ? firstChunkTs - t0 : elapsed;
      log.info("[TTS-DONE] segIdx={} tgtLang={} total={}ms firstDelay={}ms chunks={} bytes={}",
          task.segIdx, task.lang, elapsed, firstDelay, chunks, totalBytes);

      task.done();
    } catch (Exception e) {
      log.error("[TTS] 调用失败 segIdx={} tgtLang={}: {}", task.segIdx, task.lang, e.getMessage(), e);
      task.fail();
    } finally {
      onTaskDone();
    }
  }

  // ── 公开 API ─────────────────────────────────────────────────────────────

  /**
   * 增量流式 TTS：每攒够 CHUNK_THRESHOLD 字节就立即回调，不等全部合成完。
   * 槽位满时自动排队，不阻塞调用线程。
   *
   * @return 是否已入队（true=入队，false=立即执行）
   */
  public boolean synthesizeStreaming(String text, String lang, AudioChunkCallback callback) {
    return synthesizeStreamingImpl(text, lang, 1.0, -1, callback, null);
  }

  public boolean synthesizeStreaming(String text, String lang, double rate, AudioChunkCallback callback) {
    return synthesizeStreamingImpl(text, lang, rate, -1, callback, null);
  }

  public boolean synthesizeStreaming(String text, String lang, double rate, int segIdx, AudioChunkCallback callback) {
    return synthesizeStreamingImpl(text, lang, rate, segIdx, callback, null);
  }

  /**
   * 带完成回调的重载：onComplete 在全部 WAV chunk 发送完毕后由 TTS 线程调用，
   * 保证调用方可在此时机安全触发 onAudioEnd，不会早于 WAV 帧入队。
   */
  public boolean synthesizeStreaming(String text, String lang, double rate, int segIdx,
                                     AudioChunkCallback callback, Runnable onComplete) {
    return synthesizeStreamingImpl(text, lang, rate, segIdx, callback, onComplete);
  }

  /**
   * 兼容旧接口
   */
  public boolean synthesizeStreaming(String text, String lang, double rate, LegacyAudioChunkCallback legacyCallback) {
    AudioChunkCallback wrapped = (segIdx, tgtLang, data) -> legacyCallback.onChunk(data);
    return synthesizeStreamingImpl(text, lang, rate, -1, wrapped, null);
  }

  private boolean synthesizeStreamingImpl(String text, String lang, double rate,
                                          int segIdx, AudioChunkCallback callback,
                                          Runnable onComplete) {
    String apiKey = dashScopeProperties.getEffectiveTtsApiKey();
    if (!StringUtils.hasText(apiKey)) {
      log.warn("[TTS] API Key 未配置，请在 app.dashscope.api-key 或环境变量 DASHSCOPE_API_KEY 中配置");
      return false;
    }

    PendingTask task = new PendingTask(text, lang, rate, segIdx, callback,
        tuningParams.getTtsChunkThreshold(),
        tuningParams.getTtsModel(),
        tuningParams.getTtsVoice(),
        onComplete);

    int curActive = activeCount.get();
    boolean backpressure = curActive >= maxConcurrent;
    log.info("[TTS-SUBMIT] segIdx={} tgtLang={} text=\"{}\" active={}/{} pending={} -> {}",
        segIdx, lang, truncate(text, 50),
        curActive, maxConcurrent, pendingQueue.size(),
        backpressure ? "QUEUE(背压)" : "ENQUEUE(限流)");

    // 始终经由队列+限流调度，避免多路翻译同时完成时瞬间突发请求触发 DashScope 429
    enqueueTask(task);
    return true;
  }

  /**
   * 同步 TTS（兼容旧接口）：内部调用流式合成并拼合，等待完成。
   * 注意：会阻塞调用线程，请仅用于非延迟敏感的同步场景。
   */
  public byte[] synthesize(String text, String lang) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AtomicBoolean hasAudio = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    synthesizeStreaming(text, lang, 1.0, (sIdx, tgtLang, data) -> {
      out.write(data);
      hasAudio.set(true);
    });
    try {
      boolean finished = latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    byte[] result = out.toByteArray();
    return result.length > 0 ? result : null;
  }

  private String buildRequestBody(String text, String lang, String model, String voice) throws Exception {
    ObjectNode root = objectMapper.createObjectNode();
    root.put("model", model != null ? model : "cosyvoice-v3-flash");
    ObjectNode input = root.putObject("input");
    input.put("text", text);
    input.put("voice", voice != null ? voice : "longanyang");
    input.put("format", "wav");
    input.put("sample_rate", 16000);
    input.put("rate", 1.0);
    if (lang != null) {
      ArrayNode hints = input.putArray("language_hints");
      hints.add(lang);
    }
    return objectMapper.writeValueAsString(root);
  }

  private static double clampRate(double rate) {
    if (rate < 0.5) return 0.5;
    if (rate > 2.0) return 2.0;
    if (Double.isNaN(rate)) return 1.0;
    return rate;
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max) + "...";
  }

  // ── 内部类型 ─────────────────────────────────────────────────────────────

  private static class PendingTask {
    final String text;
    final String lang;
    final double rate;
    final int segIdx;
    final AudioChunkCallback callback;
    final int chunkThreshold;
    final String model;
    final String voice;
    /** 全部 WAV chunk 发完后回调（用于在正确时机触发 onAudioEnd） */
    final Runnable onComplete;
    final CountDownLatch latch = new CountDownLatch(1);
    volatile boolean failed = false;

    PendingTask(String text, String lang, double rate, int segIdx,
               AudioChunkCallback callback, int chunkThreshold, String model, String voice,
               Runnable onComplete) {
      this.text = text;
      this.lang = lang;
      this.rate = rate;
      this.segIdx = segIdx;
      this.callback = callback;
      this.chunkThreshold = chunkThreshold;
      this.model = model;
      this.voice = voice;
      this.onComplete = onComplete;
    }

    void done() {
      if (onComplete != null) { try { onComplete.run(); } catch (Exception ignored) {} }
      latch.countDown();
    }
    void fail() {
      failed = true;
      if (onComplete != null) { try { onComplete.run(); } catch (Exception ignored) {} }
      latch.countDown();
    }
  }

} // end class RealtimeTtsService
