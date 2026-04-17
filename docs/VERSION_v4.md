# 版本 v4（提交快照说明）

本文件说明 **v4** 在代码中的含义、与流水线三项方案的关系，以及回档方式。

## 标记信息

| 项 | 值 |
|----|-----|
| Git 提交 | `f5faedd`（提交信息：`feat(release): v4 - 修复 segment 合并乱序与跨语言误拼`） |
| Git 标签 | 当前仓库**未**打 `v4` 标签；`git tag -l` 仍可能只有 `v1`、`v3`。v4 以 **master 顶端提交** 为准。 |
| 分支 | 可从该提交拉分支继续开发，例如：`git checkout -b release/v4-pipeline f5faedd` |

## v4 相对前序版本的主要增量（摘要）

- 修复 **segment 合并乱序** 与 **跨语言误拼**（以提交 `f5faedd` 的改动为准）。
- 与下文「流水线三项方案」相关的实现仍以 `AsrWebSocketHandler`、`SegmentationEngine` 为准。

## 流水线三项方案：v4 提交（`f5faedd`）代码核对

以下描述针对 **`git show f5faedd`** 中的 `AsrWebSocketHandler` / `SegmentationEngine`。若你本地 `master` 或工作区已继续演进（例如改为 `onFinalTranscriptStreaming`），以当前文件为准。

### 方案一：切段流式处理（边接收边切，不等上游攒满再切）

- **在 v4 提交中的实现方式**：DashScope 在 `result-generated` 且非 partial 时，调用 `SegmentationEngine.onFinalTranscript(...)` **一次**得到本句 final 对应的 `List<Segment>`，再 **`for` 循环**对每个 segment 调用 `sendSegmentEvent`。  
  即：仍以 **ASR 句级 final** 为边界；在同一句 final 内，切段是批处理出列表后顺序下发，**不是** `onFinalTranscriptStreaming` 那种「切出一句立刻回调」的流式 API（若后续提交已改为 streaming，见工作区差异）。
- **OpenAI / Deepgram**：以 v4 时文件为准；Deepgram 路径历史上可能仅下发 `transcript` 而未走完整切段链路，部署前请对照当前 `app.asr.provider`。

### 方案二：统一 batchId（`segmentTs`）合并同批次 segment

- **在 v4 提交中已实现**：同一 ASR final 内只生成 **一次** `segmentBatchId = System.currentTimeMillis()`，随后对该 final 切出的**所有** segment 循环 `sendSegmentEvent(session, s, segmentBatchId)`，故这些句子的 `segmentTs` 相同，便于前端合并同批展示。

### 方案三：翻译与切段并行（切出一句即启动翻译）

- **在 v4 提交中已实现**：每个 `sendSegmentEvent` 内对两种非源语言使用线程池异步翻译；`translation` 在 TTS 前下发；各目标语互不阻塞对方翻译完成。

### ASR 提供方差异（部署时自查）

| 提供方 | 说明 |
|--------|------|
| **DashScope** | v4：`result-generated` 非 final 走 transcript；final 走 `onFinalTranscript` + 统一 batchId + `sendSegmentEvent`。 |
| **OpenAI / Deepgram** | 以 v4 提交中对应分支为准；若仅转发 transcript 而未调用切段，则三项方案不完整。 |

## 回档方式

基于 v4 提交创建分支（不依赖标签）：

```bash
git fetch
git checkout -b release/v4-pipeline f5faedd
```

若日后要打标签：

```bash
git tag -a v4 -m "v4 - segment 合并与跨语言修复等" f5faedd
git push origin v4
```

## 后续版本

可复制本文件为 `VERSION_v5.md` 并更新提交哈希与变更摘要。
