package com.simultaneousinterpretation.asr;

import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

/**
 * 同传流水线延迟追踪：统一前缀 {@code [LAT]}，便于日志检索与后续分析。
 */
public final class AsrLatencyTrace {

  private static final Logger LOG = LoggerFactory.getLogger(AsrLatencyTrace.class);

  public static final String ATTR = "asr.latencyTrace";

  public static AsrLatencyTrace get(WebSocketSession session) {
    Object o = session.getAttributes().get(ATTR);
    if (o instanceof AsrLatencyTrace t) {
      return t;
    }
    synchronized (session) {
      return (AsrLatencyTrace)
          session.getAttributes().computeIfAbsent(ATTR, k -> new AsrLatencyTrace());
    }
  }

  public void translateBegin(
      WebSocketSession session, int segIdx, String tgtLang) {
    LOG.info(
        "[LAT] translate_begin sessionId={} seg={} tgt={}",
        session.getId(), segIdx, tgtLang);
  }

  public void translateEndListen(
      WebSocketSession session, int segIdx, long startNano, boolean success) {
    long ms = startNano > 0 ? (System.nanoTime() - startNano) / 1_000_000L : -1;
    LOG.info(
        "[LAT] translate_end sessionId={} seg={} tgt=listen ok={} durationMs={}",
        session.getId(), segIdx, success, ms);
  }

  public void onListenTtsInvoke(WebSocketSession session, int segIdx) {
    LOG.info("[LAT] listen_tts_invoke sessionId={} seg={}", session.getId(), segIdx);
  }
}
