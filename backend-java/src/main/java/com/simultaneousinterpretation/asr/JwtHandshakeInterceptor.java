package com.simultaneousinterpretation.asr;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 握手拦截器：从查询参数提取 floor，始终放行。
 */
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

  private static final Logger log = LoggerFactory.getLogger(JwtHandshakeInterceptor.class);

  public static final String ATTR_USERNAME = "asr.username";
  public static final String ATTR_FLOOR = "asr.floor";

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {

    log.info("[WS握手] 收到请求 URI={}", request.getURI());

    String qs = null;
    if (request instanceof ServletServerHttpRequest servletReq) {
      qs = servletReq.getServletRequest().getQueryString();
    }

    int floor = parseFloor(queryParam(qs, "floor"));
    attributes.put(ATTR_FLOOR, floor);
    attributes.put(ATTR_USERNAME, "user");

    log.info("[WS握手] 通过：floor={}", floor);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {
    if (exception != null) {
      log.warn("[WS握手] afterHandshake 异常: {}", exception.getMessage());
    }
  }

  private static int parseFloor(String raw) {
    if (raw == null || raw.isEmpty()) return 1;
    try {
      int f = Integer.parseInt(raw.trim());
      return f >= 1 && f <= 16 ? f : 1;
    } catch (NumberFormatException e) {
      return 1;
    }
  }

  private static String queryParam(String queryString, String name) {
    if (queryString == null || queryString.isEmpty()) return null;
    for (String part : queryString.split("&")) {
      int i = part.indexOf('=');
      if (i <= 0) continue;
      if (name.equals(part.substring(0, i))) {
        return part.substring(i + 1);
      }
    }
    return null;
  }
}
