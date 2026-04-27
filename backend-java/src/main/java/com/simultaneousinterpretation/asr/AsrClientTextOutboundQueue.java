package com.simultaneousinterpretation.asr;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 浏览器下行 JSON 单线程顺序发送。
 *
 * <p>缓解点：原先多线程（翻译/TTS/ASR 回调）并发 {@link WebSocketSession#sendMessage}，在 Tomcat
 * 缓冲区紧张或 TCP 反压时可能乱序、失败被吞。本队列用 {@link LinkedBlockingQueue#put} 阻塞生产方直至入队，
 * 由单消费者按 FIFO 写出，保证同一会话内事件顺序与尽量不丢（会话仍打开时）。
 *
 * <p>无法保证：客户端断线、浏览器杀页、TCP 已断后的消息；跨进程/持久化需另接 MQ 与 ACK。
 */
final class AsrClientTextOutboundQueue {

  private static final Logger log = LoggerFactory.getLogger(AsrClientTextOutboundQueue.class);
  private static final ObjectMapper objectMapper = new ObjectMapper();

  static final String ATTR_Q = "asr.clientJsonOutQ";
  static final String ATTR_EX = "asr.clientJsonOutExec";
  /** 每条 JSON 已成功写给主持人浏览器之后调用（用于向听众转发同一条、同顺序，避免与主持出站队列交错丢段） */
  static final String ATTR_AFTER_HOST_SENT = "asr.clientJsonAfterHostSent";

  private static final String POISON = "\u0001__SI_JSON_OUT_CLOSE__\u0001";

  private AsrClientTextOutboundQueue() {}

  static void start(WebSocketSession session, BiConsumer<WebSocketSession, String> afterHostSent) {
    if (session.getAttributes().containsKey(ATTR_Q)) {
      return;
    }
    LinkedBlockingQueue<String> q = new LinkedBlockingQueue<>();
    session.getAttributes().put(ATTR_Q, q);
    if (afterHostSent != null) {
      session.getAttributes().put(ATTR_AFTER_HOST_SENT, afterHostSent);
    }
    ExecutorService ex =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "si-asr-json-out-" + session.getId());
              t.setDaemon(true);
              return t;
            });
    session.getAttributes().put(ATTR_EX, ex);
    ex.submit(() -> runDrain(session, q));
  }

  private static void runDrain(WebSocketSession session, LinkedBlockingQueue<String> q) {
    String sessionId = session.getId();
    long threadId = Thread.currentThread().getId();
    log.info("[LAT-THREAD] ★★★ sessionId={} 队列消费线程启动 threadId={}", sessionId, threadId);

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

        // 解析消息类型
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
          // 重试发送，处理 *_PARTIAL_WRITING 临时冲突（可能是 IOException 或 RuntimeException）
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
                try {
                  Thread.sleep(20);
                } catch (InterruptedException ie) {
                  Thread.currentThread().interrupt();
                  break;
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

        long hostSendWallMs = System.currentTimeMillis();

        if ("transcript".equals(eventType) || "segment".equals(eventType) || "translation".equals(eventType)) {
          log.info("[LAT-WEBSOCKET-SEND] ★★★ sessionId={} 发送成功: " +
              "event={} segIdx={} text=\"{}\" " +
              "queueWaitNs={} sendNs={} totalNs={} " +
              "queueSize={} msgLen={} wallMs={}",
              sessionId, eventType, segIdx, textPreview,
              queueWaitNs, sendNs, queueWaitNs + sendNs,
              queueSizeBefore, msg.length(), hostSendWallMs);
        }

        // 转发给房间听众
        @SuppressWarnings("unchecked")
        BiConsumer<WebSocketSession, String> after =
            (BiConsumer<WebSocketSession, String>) session.getAttributes().get(ATTR_AFTER_HOST_SENT);
        if (after != null && session.isOpen()) {
          long beforeForwardNs = System.nanoTime();
          try {
            after.accept(session, msg);
          } catch (Exception ex) {
            log.warn("[ASR-FORWARD] sessionId={} 房间转发异常: {}", sessionId, ex.getMessage());
          }
          long afterForwardNs = System.nanoTime();
          long forwardNs = afterForwardNs - beforeForwardNs;
          if ("transcript".equals(eventType)) {
            log.info("[LAT-FORWARD] ★ sessionId={} 房间转发完成 forwardNs={}", sessionId, forwardNs);
          }
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
    // 会话已关闭时直接丢弃，避免入队后发送失败
    synchronized (session) {
      if (!session.isOpen()) {
        log.debug("[LAT] json_dropped sessionId={} session_closed", session.getId());
        return;
      }
    }
    try {
      q.put(json);
      // 解析消息类型用于日志
      String eventType = "";
      try {
        eventType = objectMapper.readTree(json).path("event").asText("");
      } catch (Exception ignored) {}
      log.debug("[LAT] json_enqueue sessionId={} event={} queueSize={}", session.getId(), eventType, q.size());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  @SuppressWarnings("unchecked")
  static void stop(WebSocketSession session) {
    LinkedBlockingQueue<String> q = (LinkedBlockingQueue<String>) session.getAttributes().remove(ATTR_Q);
    ExecutorService ex = (ExecutorService) session.getAttributes().remove(ATTR_EX);
    if (q != null) {
      try {
        q.put(POISON);
      } catch (InterruptedException ignored) {
      }
    }
    if (ex != null) {
      ex.shutdown();
      try {
        if (!ex.awaitTermination(8, TimeUnit.SECONDS)) {
          ex.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        ex.shutdownNow();
      }
    }
  }
}
