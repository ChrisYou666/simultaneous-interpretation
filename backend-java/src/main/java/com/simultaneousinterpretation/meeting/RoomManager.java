package com.simultaneousinterpretation.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会议房间管理器
 *
 * 管理会议房间的创建、加入、离开和状态维护。
 *
 * 两种角色:
 * - host: 会议主持人，发送音频流，配置翻译参数
 * - listener: 听众，只接收翻译后的音频和文本
 */
@Component
public class RoomManager {

  private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

  /** 房间 ID -> 会议房间 */
  private final Map<String, MeetingRoom> rooms = new ConcurrentHashMap<>();

  /** 用户 ID -> 所在房间 */
  private final Map<String, String> userToRoom = new ConcurrentHashMap<>();

  /** 房间 ID 生成器 */
  private final AtomicInteger roomIdCounter = new AtomicInteger(1000);

  /**
   * 确保房间存在（幂等创建）。当主持人带着旧 roomId 重连 ASR 时，
   * 当前 JVM 进程里可能没有该房间（后端重启过），此时自动补登记，
   * 避免出现「主持人 ASR 正常、听众无法加入」的不一致。
   *
   * @param roomId 由前端指定的房间号（如 room-1001）
   * @param hostUserId 主持人 ID
   */
  /**
   * 创建新房间
   *
   * @param hostUserId 主持人用户ID
   * @param roomName 房间名称
   * @param asrProvider ASR 提供者 (dashscope/deepgram/openai)
   * @param listenLang 默认收听语言
   * @return 房间ID
   */
  public String createRoom(String hostUserId, String roomName, String asrProvider, String listenLang) {
    String roomId = "room-" + roomIdCounter.incrementAndGet();
    MeetingRoom room = new MeetingRoom(roomId, hostUserId, roomName, asrProvider, listenLang);
    rooms.put(roomId, room);
    userToRoom.put(hostUserId, roomId);

    log.info("[房间] 创建房间 roomId={} host={} name={}", roomId, hostUserId, roomName);
    return roomId;
  }

  /**
   * 加入房间
   *
   * @param roomId 房间ID
   * @param userId 用户ID
   * @param role 角色 (host/listener)
   * @param listenLang 收听语言
   * @return 是否成功
   */
  public boolean joinRoom(String roomId, String userId, String role, String listenLang) {
    MeetingRoom room = rooms.get(roomId);
    if (room == null) {
      log.warn("[房间] 加入失败，房间不存在 roomId={} user={}", roomId, userId);
      return false;
    }

    if ("host".equals(role)) {
      if (!room.getHostUserId().equals(userId)) {
        log.warn("[房间] 加入失败，非主持人尝试以 host 身份加入 roomId={} user={}", roomId, userId);
        return false;
      }
    } else {
      room.addListener(userId, listenLang);
    }

    userToRoom.put(userId, roomId);
    log.info("[房间] 用户加入 roomId={} user={} role={}", roomId, userId, role);

    return true;
  }

  /**
   * 离开房间
   *
   * @param userId 用户ID
   */
  public void leaveRoom(String userId) {
    String roomId = userToRoom.remove(userId);
    if (roomId == null) {
      return;
    }

    MeetingRoom room = rooms.get(roomId);
    if (room == null) {
      return;
    }

    if (room.getHostUserId().equals(userId)) {
      // 主持人离开，解散房间
      closeRoom(roomId);
      log.info("[房间] 主持人离开，解散房间 roomId={}", roomId);
    } else {
      room.removeListener(userId);
      log.info("[房间] 听众离开 roomId={} user={}", roomId, userId);
    }
  }

  /**
   * 关闭房间
   *
   * @param roomId 房间ID
   */
  public void closeRoom(String roomId) {
    MeetingRoom room = rooms.remove(roomId);
    if (room == null) {
      return;
    }

    // 清理所有用户
    userToRoom.remove(room.getHostUserId());
    for (String listenerId : room.getListeners()) {
      userToRoom.remove(listenerId);
    }

    log.info("[房间] 关闭房间 roomId={}", roomId);
  }

  /**
   * 获取房间信息
   *
   * @param roomId 房间ID
   * @return 房间信息，不存在则返回 null
   */
  public RoomInfo getRoomInfo(String roomId) {
    MeetingRoom room = rooms.get(roomId);
    if (room == null) {
      return null;
    }
    return new RoomInfo(room);
  }

  /**
   * 获取用户的房间ID
   *
   * @param userId 用户ID
   * @return 房间ID，不在房间中则返回 null
   */
  public String getUserRoom(String userId) {
    return userToRoom.get(userId);
  }

  /**
   * 获取所有活跃房间
   */
  public List<RoomInfo> getActiveRooms() {
    List<RoomInfo> result = new ArrayList<>();
    for (MeetingRoom room : rooms.values()) {
      result.add(new RoomInfo(room));
    }
    return result;
  }

  /**
   * 检查用户是否是房间主持人
   */
  public boolean isHost(String roomId, String userId) {
    MeetingRoom room = rooms.get(roomId);
    return room != null && room.getHostUserId().equals(userId);
  }

  /**
   * 获取房间中的听众列表
   */
  public List<String> getListeners(String roomId) {
    MeetingRoom room = rooms.get(roomId);
    if (room == null) {
      return Collections.emptyList();
    }
    return new ArrayList<>(room.getListeners());
  }

  /**
   * 会议房间
   */
  public static class MeetingRoom {
    private final String roomId;
    private final String hostUserId;
    private final String roomName;
    private final String asrProvider;
    private final String defaultListenLang;
    private final List<String> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean active = true;

    public MeetingRoom(String roomId, String hostUserId, String roomName, String asrProvider, String defaultListenLang) {
      this.roomId = roomId;
      this.hostUserId = hostUserId;
      this.roomName = roomName;
      this.asrProvider = asrProvider;
      this.defaultListenLang = defaultListenLang;
    }

    public String getRoomId() {
      return roomId;
    }

    public String getHostUserId() {
      return hostUserId;
    }

    public String getRoomName() {
      return roomName;
    }

    public String getAsrProvider() {
      return asrProvider;
    }

    public String getDefaultListenLang() {
      return defaultListenLang;
    }

    public List<String> getListeners() {
      return listeners;
    }

    public void addListener(String userId, String listenLang) {
      if (!listeners.contains(userId)) {
        listeners.add(userId);
      }
    }

    public void removeListener(String userId) {
      listeners.remove(userId);
    }

    public boolean isActive() {
      return active;
    }

    public void deactivate() {
      this.active = false;
    }
  }

  /**
   * 房间信息（用于API返回）
   */
  public static class RoomInfo {
    private final String roomId;
    private final String roomName;
    private final String hostUserId;
    private final String asrProvider;
    private final String defaultListenLang;
    private final int listenerCount;
    private final boolean active;

    public RoomInfo(MeetingRoom room) {
      this.roomId = room.getRoomId();
      this.roomName = room.getRoomName();
      this.hostUserId = room.getHostUserId();
      this.asrProvider = room.getAsrProvider();
      this.defaultListenLang = room.getDefaultListenLang();
      this.listenerCount = room.getListeners().size();
      this.active = room.isActive();
    }

    public String getRoomId() {
      return roomId;
    }

    public String getRoomName() {
      return roomName;
    }

    public String getHostUserId() {
      return hostUserId;
    }

    public String getAsrProvider() {
      return asrProvider;
    }

    public String getDefaultListenLang() {
      return defaultListenLang;
    }

    public int getListenerCount() {
      return listenerCount;
    }

    public boolean isActive() {
      return active;
    }
  }
}
