package com.simultaneousinterpretation.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会议房间 REST API
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

  private static final Logger log = LoggerFactory.getLogger(RoomController.class);

  private final RoomManager roomManager;

  public RoomController(RoomManager roomManager) {
    this.roomManager = roomManager;
  }

  /**
   * 创建新房间
   */
  @PostMapping
  public ResponseEntity<Map<String, Object>> createRoom(@RequestBody CreateRoomRequest request) {
    String userId = request.getUserId();
    String roomName = request.getRoomName();
    String asrProvider = request.getAsrProvider();
    String listenLang = request.getListenLang();

    if (userId == null || userId.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
    }
    if (roomName == null || roomName.isBlank()) {
      roomName = "会议";
    }
    if (asrProvider == null || asrProvider.isBlank()) {
      asrProvider = "dashscope";
    }
    if (listenLang == null || listenLang.isBlank()) {
      listenLang = "zh";
    }

    String roomId = roomManager.createRoom(userId, roomName, asrProvider, listenLang);

    log.info("[房间API] 创建房间: roomId={} host={}", roomId, userId);

    return ResponseEntity.ok(Map.of(
        "roomId", roomId,
        "roomName", roomName,
        "hostUserId", userId,
        "asrProvider", asrProvider,
        "listenLang", listenLang
    ));
  }

  /**
   * 获取房间信息
   */
  @GetMapping("/{roomId}")
  public ResponseEntity<?> getRoom(@PathVariable String roomId) {
    RoomManager.RoomInfo info = roomManager.getRoomInfo(roomId);
    if (info == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(info);
  }

  /**
   * 获取所有活跃房间列表
   */
  @GetMapping
  public ResponseEntity<List<RoomManager.RoomInfo>> listRooms() {
    return ResponseEntity.ok(roomManager.getActiveRooms());
  }

  /**
   * 加入房间
   */
  @PostMapping("/{roomId}/join")
  public ResponseEntity<Map<String, Object>> joinRoom(
      @PathVariable String roomId,
      @RequestBody JoinRoomRequest request) {

    String userId = request.getUserId();
    String role = request.getRole();
    String listenLang = request.getListenLang();

    if (userId == null || userId.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
    }
    if (role == null || role.isBlank()) {
      role = "listener";
    }
    if (listenLang == null || listenLang.isBlank()) {
      listenLang = "en";
    }

    boolean success = roomManager.joinRoom(roomId, userId, role, listenLang);
    if (!success) {
      return ResponseEntity.badRequest().body(Map.of("error", "Failed to join room"));
    }

    RoomManager.RoomInfo info = roomManager.getRoomInfo(roomId);

    log.info("[房间API] 用户加入房间: roomId={} user={} role={}", roomId, userId, role);

    return ResponseEntity.ok(Map.of(
        "roomId", roomId,
        "userId", userId,
        "role", role,
        "listenLang", listenLang,
        "roomName", info != null ? info.getRoomName() : "",
        "asrProvider", info != null ? info.getAsrProvider() : "",
        "listenerCount", info != null ? info.getListenerCount() : 0
    ));
  }

  /**
   * 离开房间
   */
  @PostMapping("/{roomId}/leave")
  public ResponseEntity<Map<String, String>> leaveRoom(
      @PathVariable String roomId,
      @RequestBody Map<String, String> request) {

    String userId = request.get("userId");
    if (userId == null || userId.isBlank()) {
      return ResponseEntity.badRequest().body(Map.of("error", "userId is required"));
    }

    roomManager.leaveRoom(userId);

    log.info("[房间API] 用户离开房间: roomId={} user={}", roomId, userId);

    return ResponseEntity.ok(Map.of("success", "true"));
  }

  /**
   * 关闭房间（仅主持人）
   */
  @DeleteMapping("/{roomId}")
  public ResponseEntity<Map<String, String>> closeRoom(@PathVariable String roomId) {
    roomManager.closeRoom(roomId);
    log.info("[房间API] 关闭房间: roomId={}", roomId);
    return ResponseEntity.ok(Map.of("success", "true"));
  }

  // 请求体类
  public static class CreateRoomRequest {
    private String userId;
    private String roomName;
    private String asrProvider;
    private String listenLang;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }
    public String getAsrProvider() { return asrProvider; }
    public void setAsrProvider(String asrProvider) { this.asrProvider = asrProvider; }
    public String getListenLang() { return listenLang; }
    public void setListenLang(String listenLang) { this.listenLang = listenLang; }
  }

  public static class JoinRoomRequest {
    private String userId;
    private String role;
    private String listenLang;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getListenLang() { return listenLang; }
    public void setListenLang(String listenLang) { this.listenLang = listenLang; }
  }
}
