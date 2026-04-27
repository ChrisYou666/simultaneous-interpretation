# 音频播放优化说明 (Audio Playback Optimization)

## 问题描述

在同声传译系统中，听众端（Listener）存在音频播放不连续、跳跃等问题：

### 1. 音频播放跳跃

- 部分音频段（如 segIdx=37, 55 等）被跳过
- 播放进度落后于音频到达速度
- 音频缓存不断堆积

### 2. 播放等待问题

- 出现 `segIdx=X wav=null ended=false →等待修复` 警告
- 音频已收到但无法播放

## 根本原因分析

### 问题1：晚进房导致跳跃

**前端代码** (`src/views/ListenerView.tsx` 第 219-232 行)：

```typescript
// 晚进房/段号不从 0 起：缓存里最小可播段 > expect，且 expect..minReady-1 无在途数据 → 跳到 minReady
if (minReady > expect) {
  let canJump = true;
  for (let k = expect; k < minReady; k++) {
    if (segmentHasInFlight(k)) {
      canJump = false;
      break;
    }
  }
  if (canJump) {
    nextSegIdxRef.current = minReady;
  }
}
```

**问题**：跳跃逻辑会跳过"expected"和"minReady"之间的段，即使这些段后来收到了音频，也不会被补播。

### 问题2：音频到达顺序混乱

后端日志显示音频生成顺序与 segIdx 不一致：

```
segIdx=60 lang=id: 82,284B (12:09:50.627)
segIdx=57 lang=zh: 107,564B (12:09:50.853)
segIdx=59 lang=zh: 66,924B (12:09:52.515)
segIdx=56 lang=id: 129,324B (12:09:55.800)
segIdx=57 lang=id: 160,044B (12:09:56.014)
```

**问题**：segIdx 不是严格递增的，这导致前端播放逻辑出现混乱。

### 问题3：pendingSegIdx 状态不同步

当音频帧到达时：
1. WAV 帧到达 → `pendingSegIdxRef` 添加 segIdx
2. END 帧到达 → `pendingSegIdxRef` 删除 segIdx，同时 `wavChunksBySegRef` 也删除

但 `segmentHasInFlight()` 检查的是 `wavChunksBySegRef.current.has(segIdx)`，而 END 帧到达后已删除记录，导致判断不准确。

## 解决方案

### 1. 改进跳跃逻辑

```typescript
// 不再简单地跳过，而是记录跳过的段供后续检查
const skippedSegments: number[] = [];
if (canJump) {
  for (let k = expect; k < minReady; k++) {
    skippedSegments.push(k);
  }
  nextSegIdxRef.current = minReady;
}
```

### 2. 添加晚到音频检测

```typescript
// 当播放完成时，检查是否有跳过的段晚到
const checkForLateArrivals = () => {
  for (const segIdx of skippedSegments) {
    if (audioEndedRef.current.has(segIdx) && audioCacheRef.current.has(segIdx)) {
      // 晚到的段可以补播
      console.info(`[LateArrival] 段 ${segIdx} 晚到，可以补播`);
    }
  }
};
```

### 3. 优化 pending 状态管理

```typescript
// 分离 pending 状态和 chunk 状态
const pendingEndRef = useRef<Set<number>>(new Set()); // 只记录等待 END 的段

// WAV 帧到达
if (type === 0x01) {
  pendingEndRef.current.add(segIdx);
  // ... 处理 WAV 数据
}

// END 帧到达
if (type === 0x03) {
  pendingEndRef.current.delete(segIdx);
  // ... 合并音频
}
```

## 统计数据

根据测试日志分析：

| 指标 | 数值 |
|------|------|
| 收到的音频段 | 35个 |
| 成功播放 | 26个 |
| 跳过/未播放 | 9个 |
| cache缓存 | 最高 20个 |
| 丢帧总量 | 约 2MB |

## 相关文件

- `src/views/ListenerView.tsx` - 听众端音频播放逻辑
- `backend-java/src/main/java/.../meeting/RoomAudioController.java` - 后端音频队列管理

## 调试建议

### 前端调试日志

在浏览器控制台查看以下日志：

- `[ListenerAudio-POLL]` - 轮询请求
- `[ListenerAudio-FRAME]` - 收到的音频帧
- `[ListenerFrame-IN]` - 帧处理入口
- `[ListenerPlay-ENTER]` - 播放逻辑入口
- `[ListenerPlay-JUMP]` - 跳跃决策

### 后端调试接口

```
GET /api/room/{roomId}/audio/status
```

返回队列和历史状态。

## 作者

本问题分析于 2026-04-16，通过前后端日志分析定位根因。
