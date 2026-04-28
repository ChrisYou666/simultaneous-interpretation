package com.simultaneousinterpretation.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 浏览器下行 JSON 双队列顺序发送。
 *
 * <p>缓解点：原先所有事件（transcript/translation/segment）共享单个队列，
 * partial transcript 每 ~100ms 一个高频涌入，导致 translation 译文被压在后面
 * 产生数百毫秒的感知延迟。
 *
 * <p>改进：拆分为两个队列，transcript 走 fast-path 直接发送（无顺序保证），
 * translation/segment 走 ordered-path 严格按序出队，保证译文及时到达。
 * 两个消费线程互不阻塞。
 *
 * <p>无法保证：客户端断线、浏览器杀页、TCP 已断后的消息；跨进程/持久化需另接 MQ 与 ACK。
 */
final class AsrClientTextOutboundQueue {

  private static final Logger log = LoggerFactory.getLogger(AsrClientTextOutboundQueue.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  static final String ATTR_Q       = "asr.clientJsonOutQ";         // translation/segment ordered queue
  static final String ATTR_Q_FAST = "asr.clientJsonOutQFast";    // transcript fast-path queue
  static final String ATTR_EX     = "asr.clientJsonOutExec";
  static final String ATTR_EX_FAST = "asr.clientJsonOutExecFast";

  private static final String POISON = "\u0001__SI_JSON_OUT_CLOSE__\u0001";

  private AsrClientTextOutboundQueue() {}

  static void start(WebSocketSession session) {
    if (session.getAttributes().containsKey(ATTR_Q)) {
      return;
    }
    LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
    LinkedBlockingQueue<String> qFast = new LinkedBlockingQueue<>();
    session.getAttributes().put(ATTR_Q, q);
    session.getAttributes().put(ATTR_Q_FAST, qFast);

    ExecutorService ex = newSingleThreadExecutor(session, "si-asr-json-out");
    ExecutorService exFast = newSingleThreadExecutor(session, "si-asr-json-out-fast");
    session.getAttributes().put(ATTR_EX, ex);
    session.getAttributes().put(ATTR_EX_FAST, exFast);
    ex.submit(() -> runDrain(session, q));
    exFast.submit(() -> runDrainFast(session, qFast));
  }

  private static ExecutorService newSingleThreadExecutor(WebSocketSession session, String namePrefix) {
    return Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, namePrefix + "-" + session.getId());
      t.setDaemon(true);
      return t;
    });
  }

  private static void runDrain(WebSocketSession session, LinkedBlockingQueue<String> q) {
    String sessionId = session.getId();
    log.info("[LAT-THREAD] ★ 队列消费线程启动 sessionId={}", sessionId);

    try {
      while (true) {
        long beforeTakeNs = System.nanoTime();
        String msg = q.take();
        long afterTakeNs = System.nanoTime();
        long queueWaitNs = afterTakeNs - beforeTakeNs;
        int queueSizeBefore = q.size() + 1;

        if (POISON.equals(msg)) {
          log.info("[LAT-POISON] sessionId={} 收到停止信号，退出队列消费", sessionId);
          break;
        }

        String eventType = "";
        int segIdx = -1;
        String tgtLang = "";
        String textPreview = "";
        try {
          com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(msg);
          eventType = node.path("event").asText("");
          segIdx = node.path("index").asInt(-1);
          tgtLang = node.path("targetLang").asText("");
          String text = node.path("text").asText("");
          textPreview = text.length() > 30 ? text.substring(0, 30) + "..." : text;
        } catch (Exception ignored) {}

        long beforeSendNs = System.nanoTime();
        IOException lastError = null;
        boolean sent = false;
        synchronized (session) {
          if (!session.isOpen()) {
            log.warn("[LAT-SEND] sessionId={} session已关闭，跳过发送 event={}", sessionId, eventType);
            continue;
          }
          for (int retry = 0; retry < 3; retry++) {
            try {
              session.sendMessage(new TextMessage(msg));
              sent = true;
              break;
            } catch (Exception e) {
              lastError = e instanceof IOException ? (IOException) e
                  : new IOException(e.getMessage(), e);
              String errMsg = e.getMessage();
              if (errMsg != null && errMsg.contains("PARTIAL_WRITING")) {
                log.warn("[LAT-SEND-RETRY] sessionId={} retry={} 等待PARTIAL_WRITING恢复: {}", sessionId, retry, errMsg);
                try { Thread.sleep(20); } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt(); break;
                }
              } else {
                break;
              }
            }
          }
        }
        long afterSendNs = System.nanoTime();
        long sendNs = afterSendNs - beforeSendNs;

        if (!sent) {
          log.warn("[LAT-SEND-FAIL] sessionId={} event={} segIdx={} 发送失败: {}",
              sessionId, eventType, segIdx, lastError != null ? lastError.getMessage() : "unknown");
        }

        if ("segment".equals(eventType) || "translation".equals(eventType)) {
          log.info("[LAT-WEBSOCKET-SEND] ★★★ sessionId={} 发送成功: " +
              "event={} segIdx={} text=\"{}\" " +
              "queueWaitNs={} sendNs={} queueSize={} wallMs={}",
              sessionId, eventType, segIdx, textPreview,
              queueWaitNs, sendNs, queueSizeBefore, System.currentTimeMillis());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.info("[LAT-INTERRUPT] sessionId={} 队列消费线程被中断", sessionId);
    } catch (Exception e) {
      log.warn("[LAT-EXCEPTION] sessionId={} 队列消费异常: {}", sessionId, e.getMessage());
    }
    log.info("[LAT-THREAD] sessionId={} 队列消费线程退出", sessionId);
  }

  /** transcript 快速通道：尽力发送，不阻塞，不等待 ACK，不计日志 */
  private static void runDrainFast(WebSocketSession session, LinkedBlockingQueue<String> qFast) {
    String sessionId = session.getId();
    log.info("[LAT-FAST] ★ transcript快速通道启动 sessionId={}", sessionId);

    try {
      while (true) {
        String msg = qFast.poll(500, TimeUnit.MILLISECONDS);
        if (msg == null) continue;

        if (POISON.equals(msg)) {
          log.info("[LAT-FAST-POISON] sessionId={} 收到停止信号", sessionId);
          break;
        }

        String eventType = "";
        try {
          eventType = objectMapper.readTree(msg).path("event").asText("");
        } catch (Exception ignored) {}

        if (!"transcript".equals(eventType)) {
          // 意外走错队列，重路由到主队列
          @SuppressWarnings("unchecked")
          LinkedBlockingQueue<String> q = (LinkedBlockingQueue<String>) session.getAttributes().get(ATTR_Q);
          if (q != null) { try { q.put(msg); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; } }
          continue;
        }

        try {
          session.sendMessage(new TextMessage(msg));
        } catch (Exception e) {
          log.debug("[LAT-FAST] sessionId={} transcript发送失败: {}", sessionId, e.getMessage());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn("[LAT-FAST-EXCEPTION] sessionId={}: {}", sessionId, e.getMessage());
    }
    log.info("[LAT-FAST] sessionId={} transcript快速通道退出", sessionId);
  }

  /**
   * 入队（translation/segment 等需要有序的事件）。
   * 使用 {@link #enqueueFast} 发送 transcript。
   */
  @SuppressWarnings("unchecked")
  static void enqueue(WebSocketSession session, String json) throws IOException {
    LinkedBlockingQueue<String> q = (LinkedBlockingQueue<String>) session.getAttributes().get(ATTR_Q);
    if (q == null) {
      synchronized (session) {
        if (session.isOpen()) {
          session.sendMessage(new TextMessage(json));
        }
      }
      return;
    }
    synchronized (session) {
      if (!session.isOpen()) {
        log.debug("[LAT] json_dropped sessionId={} session_closed", session.getId());
        return;
      }
    }
    try {
      q.put(json);
      log.debug("[LAT] json_enqueue sessionId={} queueSize={}", session.getId(), q.size());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  /**
   * 快速入队（仅用于 transcript 事件）。
   * 不计日志，不等待，直接放入 fast 队列。
   */
  @SuppressWarnings("unchecked")
  static void enqueueFast(WebSocketSession session, String json) throws IOException {
    LinkedBlockingQueue<String> qFast = (LinkedBlockingQueue<String>) session.getAttributes().get(ATTR_Q_FAST);
    if (qFast == null) {
      enqueue(session, json);
      return;
    }
    try {
      qFast.put(json);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  @SuppressWarnings("unchecked")
  static void stop(WebSocketSession session) {
    LinkedBlockingQueue<String> q = (LinkedBlockingQueue<String>) session.getAttributes().remove(ATTR_Q);
    LinkedBlockingQueue<String> qFast = (LinkedBlockingQueue<String>) session.getAttributes().remove(ATTR_Q_FAST);
    ExecutorService ex = (ExecutorService) session.getAttributes().remove(ATTR_EX);
    ExecutorService exFast = (ExecutorService) session.getAttributes().remove(ATTR_EX_FAST);

    for (LinkedBlockingQueue<String> qx : List.of(q, qFast)) {
      if (qx != null) {
        try { qx.put(POISON); } catch (InterruptedException ignored) {}
      }
    }
    for (ExecutorService e : List.of(ex, exFast)) {
      if (e != null) {
        e.shutdown();
        try {
          if (!e.awaitTermination(8, TimeUnit.SECONDS)) e.shutdownNow();
        } catch (InterruptedException e2) {
          Thread.currentThread().interrupt();
          e.shutdownNow();
        }
      }
    }
  }
}
