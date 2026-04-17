# 版本 v3（快照）

本文件记录 **v3** 对应的项目状态、相对 v1 的主要增量与回档方式，便于在后续大改或试验后回到本快照。

## 标记信息

| 项 | 值 |
|----|-----|
| Git 标签 | `v3` |
| 说明 | 在 v1 基线之上：百炼 LiveTranslate 端到端可选、同传链路稳定性与本地开发体验增强；默认流水线仍为 Fun-ASR → 切段 → Qwen 翻译 → CosyVoice TTS |
| 创建日期 | 以打标签当日为准（见 `git log -1 v3`） |

## 相对 v1 的主要能力增量（摘要）

- **ASR 提供方可选 `livetranslate`**：上行可走 `qwen3-livetranslate-flash-realtime`（识别 + 收听语翻译 + 部分语种模型内音频）；印尼语收听仍可能走 CosyVoice；副目标语种栏仍走 Qwen 文本翻译。配置见 `application.yml` 中 `app.asr.livetranslate` 与 `app.asr.provider`。
- **LiveTranslate 与百炼协议对齐**：`session.update` 仅在会话进入 `session.updated` 之前发送**一次**；通过延迟/防抖合并浏览器下发的 `setListenLang`、`setGlossary`；前端在 WebSocket `onopen` 后即下发收听语与术语表，避免「会话已启动后再 update」导致的 `invalid_value` 错误。
- **本地配置模板**：`application-local.example.yml`（MySQL 密码、可选百炼 Key 说明）；`application-local.yml` 仍 **gitignore**，避免密钥入库。
- **开发脚本**：`run-dev.cmd`（`backend-java` 与仓库根目录）— UTF-8 控制台、MySQL/百炼说明、在未设置 `DASHSCOPE_API_KEY` 时若检测到 `application-local.yml` 中含典型 `api-key: sk-` 则不再刷屏提示。
- **架构与文档**：`docs/COMPARISON_SI.md`（与业界端到端/流水线方案对照）；切段参数外置 `app.asr.segmentation` 与 `SegmentationEngine.fromConfig`；ASR 出站文本队列等实现以当时代码为准。
- **数据库**：含与 ASR 出站流水相关的 Flyway 迁移演进（以 `db/migration` 中版本号为准）。

## 功能范围（v3 时仍包含的 v1 能力）

- 实时语音识别（默认 DashScope `fun-asr-realtime` 或可选 Deepgram）、规则切段、翻译（默认 `qwen-turbo` 等 OpenAI 兼容端）、TTS（`cosyvoice-v3-flash`）。
- 三语（中/英/印尼）、收听语选择、原文与翻译列展示、WebSocket `/ws/asr` 与后端健康检查等。

## 回档方式

在项目根目录执行（需已安装 Git）：

```bash
git tag -l v3
git show v3:README.md

# 整个工作区回到 v3 时的提交（会丢弃未提交修改，请先备份）
git checkout v3

# 在新分支上基于 v3 继续开发
git checkout -b restore-from-v3 v3

# 危险：强制当前分支回到 v3（丢失未备份提交）
git reset --hard v3
```

从远端拉取标签（若已推送）：

```bash
git fetch origin tag v3
git checkout v3
```

## 运行提示（回档后）

- 后端：`backend-java`，JDK 21 + Maven；MySQL 库 `si_assistant`；百炼 Key 与 DB 密码见 `application-local.example.yml` 与 `README`。
- 前端：根目录 `npm install` 与 `npm run dev`（端口以 `README` 为准）。

## 注意事项

- **密钥**：勿将真实 API Key、数据库密码提交到公开仓库；`application-local.yml` 仅保留在本地。
- **LiveTranslate**：收听语若在会话已开始后切换，百炼侧可能不接受再次 `session.update`，需停止录音后重新开始（以当时实现与厂商文档为准）。
- **依赖**：`node_modules`、`backend-java/target` 需重新安装/构建。

## 后续版本

复制本文件为 `VERSION_v4.md`（或更新本节），并打新标签，例如：

```bash
git tag -a v4 -m "v4 说明摘要"
```
