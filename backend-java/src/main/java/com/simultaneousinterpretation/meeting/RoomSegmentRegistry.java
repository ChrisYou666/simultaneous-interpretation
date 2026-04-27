package com.simultaneousinterpretation.meeting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 房间级段注册表：为每个切出的段分配全局自增 segIdx，主持人和听众共享同一编号体系。
 */
@Component
public class RoomSegmentRegistry {

  private static final Logger log = LoggerFactory.getLogger(RoomSegmentRegistry.class);

  private final Map<String, RoomRegistry> registries = new ConcurrentHashMap<>();

  public RoomRegistry getOrCreate(String roomId) {
    return registries.computeIfAbsent(roomId, RoomRegistry::new);
  }

  public void removeRoom(String roomId) {
    registries.remove(roomId);
    log.info("[Registry] 移除房间 roomId={}", roomId);
  }

  public int getRoomCount() {
    return registries.size();
  }

  // ─── 房间级注册表 ─────────────────────────────────────────────────────────

  public static class RoomRegistry {
    private static final Logger log = LoggerFactory.getLogger(RoomRegistry.class);

    private final String roomId;
    private final AtomicInteger nextSegIndex = new AtomicInteger(0);
    private final Map<Integer, SegmentRecord> segments = new ConcurrentHashMap<>();

    RoomRegistry(String roomId) {
      this.roomId = roomId;
      log.info("[Registry] 创建房间注册表 roomId={}", roomId);
    }

    public String getRoomId() { return roomId; }
    public int getSegmentCount() { return segments.size(); }

    public synchronized SegmentRecord registerSegment(String text, String sourceLang,
                                                      double confidence, int floor,
                                                      long serverTs, long segmentBatchTs) {
      int segIdx = nextSegIndex.getAndIncrement();
      SegmentRecord seg = new SegmentRecord(segIdx, text, sourceLang, confidence, floor, serverTs, segmentBatchTs);
      segments.put(segIdx, seg);
      log.info("[Registry] roomId={} segIdx={} lang={} text=\"{}\"",
          roomId, segIdx, sourceLang,
          text.length() > 50 ? text.substring(0, 50) + "..." : text);
      return seg;
    }

    public SegmentRecord getSegment(int segIdx) {
      return segments.get(segIdx);
    }
  }

  // ─── Segment 记录 ─────────────────────────────────────────────────────────

  public static class SegmentRecord {
    private final int segIndex;
    private final String text;
    private final String sourceLang;
    private final double confidence;
    private final int floor;
    private final long serverTs;
    private final long segmentBatchTs;

    public SegmentRecord(int segIndex, String text, String sourceLang,
                         double confidence, int floor, long serverTs, long segmentBatchTs) {
      this.segIndex = segIndex;
      this.text = text;
      this.sourceLang = sourceLang;
      this.confidence = confidence;
      this.floor = floor;
      this.serverTs = serverTs;
      this.segmentBatchTs = segmentBatchTs;
    }

    public int getSegIndex() { return segIndex; }
    public String getText() { return text; }
    public String getSourceLang() { return sourceLang; }
    public double getConfidence() { return confidence; }
    public int getFloor() { return floor; }
    public long getServerTs() { return serverTs; }
    public long getSegmentBatchTs() { return segmentBatchTs; }
  }
}
