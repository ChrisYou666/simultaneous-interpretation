package com.simultaneousinterpretation.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.nio.ByteBuffer;

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
    long entryNs = System.nanoTime();
    long entryMs = System.currentTimeMillis();

    Set<WebSocketSession> listeners = listenerSessions.get(roomId);
    if (listeners == null || listeners.isEmpty()) {
      log.debug("[房间WS-JSON-DRY] roomId={} reason=no_listeners", roomId);
      return;
    }

    String eventType = "unknown";
    String textPreview = "";
    try {
      JsonNode node = objectMapper.readTree(json);
      eventType = node.get("event").asText("unknown");
      String text = node.path("text").asText("");
      textPreview = text.length() > 30 ? text.substring(0, 30) + "..." : text;
    } catch (Exception ignored) {}

    int sent = 0;
    for (WebSocketSession s : listeners) {
      if (!s.isOpen()) continue;
      long beforeSendNs = System.nanoTime();
      try {
        s.sendMessage(new TextMessage(json));
        long afterSendNs = System.nanoTime();
        long sendNs = afterSendNs - beforeSendNs;
        sent++;
        if ("transcript".equals(eventType)) {
          log.info("[房间WS-LISTENER-SEND] ★ roomId={} listener={} event={} sendNs={} text=\"{}\"",
              roomId, s.getId(), eventType, sendNs, textPreview);
        }
      } catch (IOException e) {
        log.debug("[房间WS-JSON-SEND-FAIL] session={} err={}", s.getId(), e.getMessage());
      }
    }
    long totalNs = System.nanoTime() - entryNs;
    log.info("[房间WS-JSON-BROADCAST] ★SENT★ roomId={} event={} sent={}/{} totalNs={} size={} entryMs={}",
        roomId, eventType, sent, listeners.size(), totalNs, json.length(), entryMs);
  }

  /**
   * 向房间内所有用户（主持端 + 全部听众）广播 JSON 文本消息。
   */
  public void broadcastJsonToAll(String roomId, String json) {
    String eventType = "unknown";
    try {
      eventType = objectMapper.readTree(json).get("event").asText("unknown");
    } catch (Exception ignored) {}

    int sent = 0;

    // 发送给主持人
    WebSocketSession host = hostSessions.get(roomId);
    log.info("[房间WS-BROADCAST-ALL] ★ roomId={} event={} hostSession={} hostOpen={}",
        roomId, eventType, host != null ? "存在" : "不存在", host != null ? host.isOpen() : "N/A");
    if (host != null && host.isOpen()) {
      try {
        host.sendMessage(new TextMessage(json));
        sent++;
        log.info("[房间WS-BROADCAST-ALL] ★ 主持端发送成功 sessionId={}", host.getId());
      } catch (IOException | IllegalStateException e) {
        log.warn("[房间WS] 发送主持端失败 sessionId={}: {}", host.getId(), e.getMessage());
      }
    } else {
      log.warn("[房间WS-BROADCAST-ALL] ★ 主持端未连接，无法发送 event={} roomId={}", eventType, roomId);
    }

    // 发送给所有听众
    Set<WebSocketSession> listeners = listenerSessions.get(roomId);
    log.info("[房间WS-BROADCAST-ALL] ★ roomId={} 听众数量={}", roomId, listeners != null ? listeners.size() : 0);
    if (listeners != null && !listeners.isEmpty()) {
      for (WebSocketSession s : listeners) {
        if (!s.isOpen()) continue;
        try {
          s.sendMessage(new TextMessage(json));
          sent++;
        } catch (IOException | IllegalStateException e) {
          log.debug("[房间WS-JSON-SEND-FAIL] session={} err={}", s.getId(), e.getMessage());
        }
      }
    }

    log.info("[房间WS-BROADCAST-ALL] roomId={} event={} sent={}", roomId, eventType, sent);
  }

  /**
   * 广播带帧头的二进制音频块（WAV chunk）给订阅特定语言的听众。
   *
   * <p>帧格式：[4B segIdx big-endian][4B sentenceIdx big-endian][1B type=0x01][1B langLen][langLen bytes lang][audio bytes]
   */
  public void broadcastFramedAudioChunk(String roomId, int segIdx, int sentenceIdx, String lang, byte[] audioData) {
    byte[] frame = buildAudioFrame(segIdx, sentenceIdx, (byte) 0x01, lang, audioData);
    broadcastBinaryToListenersByLang(roomId, lang, frame);
  }

  /**
   * 广播带帧头的音频结束标记（END）给订阅特定语言的听众。
   *
   * <p>帧格式：[4B segIdx big-endian][4B sentenceIdx big-endian][1B type=0x03][1B langLen][langLen bytes lang]（无音频数据）
   */
  public void broadcastFramedAudioEnd(String roomId, int segIdx, int sentenceIdx, String lang) {
    byte[] frame = buildAudioFrame(segIdx, sentenceIdx, (byte) 0x03, lang, new byte[0]);
    broadcastBinaryToListenersByLang(roomId, lang, frame);
  }

  /**
   * 兼容旧格式的 END 广播（用于源语链，单句无 sentenceIdx）
   */
  public void broadcastFramedAudioEnd(String roomId, int segIdx, String lang) {
    byte[] frame = buildAudioFrameLegacy(segIdx, (byte) 0x03, lang, new byte[0]);
    broadcastBinaryToListenersByLang(roomId, lang, frame);
  }

  private byte[] buildAudioFrame(int segIdx, int sentenceIdx, byte type, String lang, byte[] audioData) {
    byte[] langBytes = lang.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 1 + 1 + langBytes.length + audioData.length);
    buf.putInt(segIdx);
    buf.putInt(sentenceIdx);
    buf.put(type);
    buf.put((byte) langBytes.length);
    buf.put(langBytes);
    if (audioData.length > 0) buf.put(audioData);
    return buf.array();
  }

  private byte[] buildAudioFrameLegacy(int segIdx, byte type, String lang, byte[] audioData) {
    byte[] langBytes = lang.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    ByteBuffer buf = ByteBuffer.allocate(4 + 1 + 1 + langBytes.length + audioData.length);
    buf.putInt(segIdx);
    buf.put(type);
    buf.put((byte) langBytes.length);
    buf.put(langBytes);
    if (audioData.length > 0) buf.put(audioData);
    return buf.array();
  }

  private void broadcastBinaryToListenersByLang(String roomId, String lang, byte[] frame) {
    Set<WebSocketSession> listeners = listenerSessions.get(roomId);
    if (listeners == null || listeners.isEmpty()) return;

    // 从帧头解析 segIdx 和类型用于日志
    ByteBuffer buf = ByteBuffer.wrap(frame);
    int segIdx = -1;
    int sentenceIdx = -1;
    String frameType = "UNKNOWN";
    try {
      segIdx = buf.getInt();
      // 检查帧格式：legacy (4+1+1+lang) vs new (4+4+1+1+lang)
      if (frame.length >= 9 && buf.remaining() > 0) {
        // 可能是新格式：尝试读取 sentenceIdx
        int savedPos = buf.position();
        int maybeSentenceIdx = buf.getInt();
        if (buf.remaining() > 0) {
          byte type = buf.get();
          sentenceIdx = maybeSentenceIdx;
          frameType = (type == (byte) 0x03) ? "END" : "CHUNK";
        } else {
          // legacy 格式，回退
          buf.position(savedPos);
          frameType = (frame.length >= 5 && frame[4] == (byte) 0x03) ? "END" : "CHUNK";
        }
      } else {
        frameType = (frame.length >= 5 && frame[4] == (byte) 0x03) ? "END" : "CHUNK";
      }
    } catch (Exception e) {
      log.debug("[PIPE-5] 帧头解析异常 frameLen={}", frame.length, e);
    }

    ByteBuffer sendBuf = ByteBuffer.wrap(frame);
    int sent = 0;
    int skippedLangMismatch = 0;
    for (WebSocketSession s : listeners) {
      if (!s.isOpen()) continue;
      SessionInfo info = sessionInfoMap.get(s.getId());
      if (info == null) {
        log.debug("[PIPE-5-SKIP] sessionId={} info=null", s.getId());
        continue;
      }
      if (!lang.equals(info.listenLang)) {
        skippedLangMismatch++;
        log.debug("[PIPE-5-SKIP] sessionId={} lang mismatch: audioLang={} listenerLang={}",
            s.getId(), lang, info.listenLang);
        continue;
      }
      try {
        s.sendMessage(new BinaryMessage(sendBuf.duplicate(), true));
        sent++;
      } catch (IOException e) {
        log.debug("[PIPE-5-SEND-FAIL] sessionId={} lang={} segIdx={} sentIdx={} error={}", s.getId(), lang, segIdx, sentenceIdx, e.getMessage());
      }
    }
    if (sent > 0) {
      log.info("[PIPE-5-AUDIO] segIdx={} sentIdx={} lang={} type={} sentListeners={} skippedMismatch={} frameBytes={} wallMs={}",
          segIdx, sentenceIdx, lang, frameType, sent, skippedLangMismatch, frame.length, System.currentTimeMillis());
    } else {
      log.warn("[PIPE-5-NO-LISTENERS] segIdx={} sentIdx={} lang={} type={} totalListeners={} skippedMismatch={}",
          segIdx, sentenceIdx, lang, frameType, listeners.size(), skippedLangMismatch);
    }
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
