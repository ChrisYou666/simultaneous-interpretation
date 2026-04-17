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
    try {
      while (true) {
        String msg = q.take();
        if (POISON.equals(msg)) {
          break;
        }
        synchronized (session) {
          if (!session.isOpen()) {
            break;
          }
          session.sendMessage(new TextMessage(msg));
        }
        long hostSendWallMs = System.currentTimeMillis();
        // 解析 event 类型，为 segment/translation 事件记录主持人 WebSocket 发送成功时刻
        String eventType = "";
        int segIdx = -1;
        String tgtLang = "";
        try {
          com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(msg);
          eventType = node.path("event").asText("");
          segIdx = node.path("index").asInt(-1);
          tgtLang = node.path("targetLang").asText("");
        } catch (Exception ignored) {}
        if ("segment".equals(eventType) || "translation".equals(eventType) || "tts_skip".equals(eventType) || "transcript".equals(eventType)) {
          log.info("[LAT] host_ws_send sessionId={} event={} segIdx={} targetLang=\"{}\" wallMs={}",
              session.getId(), eventType, segIdx, tgtLang, hostSendWallMs);
        }
        @SuppressWarnings("unchecked")
        BiConsumer<WebSocketSession, String> after =
            (BiConsumer<WebSocketSession, String>) session.getAttributes().get(ATTR_AFTER_HOST_SENT);
        if (after != null && session.isOpen()) {
          try {
            after.accept(session, msg);
          } catch (Exception ex) {
            log.warn("[ASR] 主持下行后房间转发回调异常: {}", ex.getMessage());
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.warn("[ASR] 客户端 JSON 下行异常: {}", e.getMessage());
    }
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
    try {
      q.put(json);
      log.debug("[LAT] json_enqueue sessionId={} queueSize={}", session.getId(), q.size());
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
