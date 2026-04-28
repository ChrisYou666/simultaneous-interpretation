package com.simultaneousinterpretation.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会议房间管理器（无听众模式，简化版）
 */
@Component
public class RoomManager {

  private static final Logger log = LoggerFactory.getLogger(RoomManager.class);

  /** 房间 ID -> 会议房间 */
  private final Map<String, MeetingRoom> rooms = new ConcurrentHashMap<>();

  /** 房间 ID 生成器 */
  private final AtomicInteger roomIdCounter = new AtomicInteger(1000);

  /**
   * 创建新房间
   *
   * @param hostUserId 主持人用户ID
   * @param roomName 房间名称
   * @param asrProvider ASR 提供者
   * @return 房间ID
   */
  public String createRoom(String hostUserId, String roomName, String asrProvider) {
    String roomId = "room-" + roomIdCounter.incrementAndGet();
    MeetingRoom room = new MeetingRoom(roomId, hostUserId, roomName, asrProvider);
    rooms.put(roomId, room);
    log.info("[房间] 创建房间 roomId={} host={} name={}", roomId, hostUserId, roomName);
    return roomId;
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
   * 会议房间
   */
  public static class MeetingRoom {
    private final String roomId;
    private final String hostUserId;
    private final String roomName;
    private final String asrProvider;
    private volatile boolean active = true;

    public MeetingRoom(String roomId, String hostUserId, String roomName, String asrProvider) {
      this.roomId = roomId;
      this.hostUserId = hostUserId;
      this.roomName = roomName;
      this.asrProvider = asrProvider;
    }

    public String getRoomId() { return roomId; }
    public String getHostUserId() { return hostUserId; }
    public String getRoomName() { return roomName; }
    public String getAsrProvider() { return asrProvider; }
    public boolean isActive() { return active; }
    public void deactivate() { this.active = false; }
  }

  /**
   * 房间信息
   */
  public static class RoomInfo {
    private final String roomId;
    private final String roomName;
    private final String hostUserId;
    private final String asrProvider;
    private final boolean active;

    public RoomInfo(MeetingRoom room) {
      this.roomId = room.getRoomId();
      this.roomName = room.getRoomName();
      this.hostUserId = room.getHostUserId();
      this.asrProvider = room.getAsrProvider();
      this.active = room.isActive();
    }

    public String getRoomId() { return roomId; }
    public String getRoomName() { return roomName; }
    public String getHostUserId() { return hostUserId; }
    public String getAsrProvider() { return asrProvider; }
    public boolean isActive() { return active; }
  }
}
