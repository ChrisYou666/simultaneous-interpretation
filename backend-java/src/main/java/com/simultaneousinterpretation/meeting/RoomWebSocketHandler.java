package com.simultaneousinterpretation.meeting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会议房间 WebSocket 处理器（JSON 广播专用）：
 *
 * <p>URL 格式: /ws/room/{roomId}?role=listener&listenLang=zh&access_token=xxx
 *
 * <p>架构变更：
 * <ul>
 *   <li>JSON 事件（segment/translation/host_status）仍通过 WebSocket 广播</li>
 *   <li>二进制音频改用 HTTP 轮询 /api/room/{roomId}/audio/poll</li>
 * </ul>
 */
@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {

  private static final Logger log = LoggerFactory.getLogger(RoomWebSocketHandler.class);

  private final RoomManager roomManager;
  private final ObjectMapper objectMapper;

  /** roomId -> 主持人 session */
  private final Map<String, WebSocketSession> hostSessions = new ConcurrentHashMap<>();

  /** roomId -> 听众 sessions */
  private final Map<String, Set<WebSocketSession>> listenerSessions = new ConcurrentHashMap<>();

  /** sessionId -> 房间信息 */
  private final Map<String, SessionInfo> sessionInfoMap = new ConcurrentHashMap<>();

  public RoomWebSocketHandler(RoomManager roomManager, ObjectMapper objectMapper) {
    this.roomManager = roomManager;
    this.objectMapper = objectMapper;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    String path = session.getUri().getPath();
    String[] parts = path.split("/");
    if (parts.length < 4) {
      session.close(new CloseStatus(1003, "Invalid path"));
      return;
    }
    String roomId = parts[parts.length - 1];

    Map<String, String> params = parseQueryParams(session.getUri().getQuery());
    String role = params.getOrDefault("role", "listener");
    String listenLang = params.getOrDefault("listenLang", "zh");
    String userId = (String) session.getAttributes().get("username");
    if (userId == null) {
      userId = "user-" + session.getId();
    }

    RoomManager.RoomInfo roomInfo = roomManager.getRoomInfo(roomId);
    if (roomInfo == null) {
      log.warn("[房间WS] 房间不存在: {}", roomId);
      session.close(new CloseStatus(1003, "Room not found"));
      return;
    }

    SessionInfo info = new SessionInfo(roomId, userId, role, listenLang);
    sessionInfoMap.put(session.getId(), info);

    if ("host".equals(role)) {
      if (!roomManager.isHost(roomId, userId)) {
        session.close(new CloseStatus(1008, "Not host"));
        return;
      }
      hostSessions.put(roomId, session);
      log.info("[房间WS] 主持人加入 roomId={} user={}", roomId, userId);
      sendToSession(session, Map.of(
          "event", "room_joined",
          "roomId", roomId,
          "role", "host",
          "provider", roomInfo.getAsrProvider(),
          "sampleRate", getSampleRateForProvider(roomInfo.getAsrProvider())
      ));
    } else {
      listenerSessions.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
      roomManager.joinRoom(roomId, userId, "listener", listenLang);
      log.info("[房间WS] 听众加入 roomId={} user={} listenLang={}", roomId, userId, listenLang);

      sendToSession(session, Map.of(
          "event", "room_joined",
          "roomId", roomId,
          "role", "listener"
      ));
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    // 纯音频模式下，不处理文本消息
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    SessionInfo info = sessionInfoMap.remove(session.getId());
    if (info == null) return;

    if ("host".equals(info.role)) {
      hostSessions.remove(info.roomId);
      log.info("[房间WS] 主持人离开 roomId={}", info.roomId);
    } else {
      Set<WebSocketSession> listeners = listenerSessions.get(info.roomId);
      if (listeners != null) {
        listeners.remove(session);
      }
      roomManager.leaveRoom(info.userId);
      log.info("[房间WS] 听众离开 roomId={} user={}", info.roomId, info.userId);
    }
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    SessionInfo info = sessionInfoMap.get(session.getId());
    log.warn("[房间WS-ERROR] session={} roomId={} user={} error={}",
        session.getId(), info != null ? info.roomId : "?", info != null ? info.userId : "?", exception.getMessage());
  }

  // ─── JSON 广播（segment/translation 等文本事件）─────────────────────────

  /**
   * 向房间内所有听众广播 JSON 文本消息。
   */
  public void broadcastJsonToListeners(String roomId, String json) {
    Set<WebSocketSession> listeners = listenerSessions.get(roomId);
    if (listeners == null || listeners.isEmpty()) {
      log.debug("[房间WS-JSON-DRY] roomId={} reason=no_listeners", roomId);
      return;
    }

    String eventType = "unknown";
    try {
      eventType = objectMapper.readTree(json).get("event").asText("unknown");
    } catch (Exception ignored) {}

    int sent = 0;
    for (WebSocketSession s : listeners) {
      if (!s.isOpen()) continue;
      try {
        s.sendMessage(new TextMessage(json));
        sent++;
      } catch (IOException e) {
        log.debug("[房间WS-JSON-SEND-FAIL] session={} err={}", s.getId(), e.getMessage());
      }
    }
    log.info("[房间WS-JSON-BROADCAST] ★SENT★ roomId={} event={} sent={}/{} size={}",
        roomId, eventType, sent, listeners.size(), json.length());
  }

  /**
   * 通知所有听众主持人 ASR 状态变化。
   */
  public void notifyListenersHostAsrStatus(String roomId, String status) {
    Map<String, Object> msg = new HashMap<>();
    msg.put("event", "host_status");
    msg.put("status", status);
    String json;
    try {
      json = objectMapper.writeValueAsString(msg);
    } catch (Exception e) {
      return;
    }
    broadcastJsonToListeners(roomId, json);
  }

  /**
   * 广播播放进度同步事件。
   */
  public void broadcastPlaybackEvent(String roomId, String type, int segIdx, String lang, int floor) {
    Map<String, Object> msg = new HashMap<>();
    msg.put("event", type);
    msg.put("segIdx", segIdx);
    msg.put("lang", lang);
    msg.put("floor", floor);
    try {
      String json = objectMapper.writeValueAsString(msg);
      broadcastJsonToListeners(roomId, json);
    } catch (Exception e) {
      log.debug("[房间WS] broadcastPlaybackEvent: {}", e.getMessage());
    }
  }

  // ─── 内部工具 ──────────────────────────────────────────────────────────

  private void sendToSession(WebSocketSession session, Map<String, Object> data) {
    if (!session.isOpen()) return;
    try {
      String json = objectMapper.writeValueAsString(data);
      session.sendMessage(new TextMessage(json));
    } catch (IOException e) {
      log.debug("[房间WS] 发送消息失败: {}", e.getMessage());
    }
  }

  private Map<String, String> parseQueryParams(String query) {
    Map<String, String> params = new HashMap<>();
    if (query == null || query.isBlank()) return params;
    for (String pair : query.split("&")) {
      String[] kv = pair.split("=", 2);
      if (kv.length == 2) {
        params.put(kv[0].trim(), kv[1].trim());
      }
    }
    return params;
  }

  private int getSampleRateForProvider(String provider) {
    return switch (provider) {
      case "openai" -> 24000;
      case "deepgram" -> 48000;
      default -> 16000;
    };
  }

  private static class SessionInfo {
    final String roomId;
    final String userId;
    final String role;
    volatile String listenLang;

    SessionInfo(String roomId, String userId, String role, String listenLang) {
      this.roomId = roomId;
      this.userId = userId;
      this.role = role;
      this.listenLang = listenLang;
    }
  }
}
