# 版本 v1（基线快照）

本文件记录 **v1** 对应的项目状态与回档方式，便于后续实验、大改或换模型后需要回到当前可用基线时使用。

## 标记信息

| 项 | 值 |
|----|-----|
| Git 标签 | `v1` |
| 说明 | 实时同传基线：三语流程、DashScope ASR/TTS、Spring Boot 后端 + Vite 前端 |
| 创建日期 | 以打标签当日为准（见 `git log -1 v1`） |

## 功能范围（v1 时大致包含）

- 实时语音识别（默认 DashScope `fun-asr-realtime`）、切段、翻译（Qwen）、TTS（CosyVoice v3-flash 流式）
- 收听语言选择与中途切换、前端双列「原文 / 翻译」展示
- 发言语言选择（自动 / 中 / 英 / 印尼）等会话侧逻辑（以当时代码为准）

## 回档方式

在项目根目录执行（需已安装 Git）：

```bash
# 查看 v1 标签是否存在
git tag -l v1

# 仅查看 v1 时某文件（不改动工作区）
git show v1:path/to/file

# 整个工作区回到 v1 时的提交（会丢弃未提交修改，请先备份）
git checkout v1

# 若希望在新分支上基于 v1 继续开发
git checkout -b restore-from-v1 v1

# 若当前分支已乱，强制回到 v1（危险：丢失未推送/未备份的提交）
git reset --hard v1
```

从远端拉取标签（若将来把仓库推到远端并推送了 tag）：

```bash
git fetch origin tag v1
git checkout v1
```

## 运行提示（回档后）

- 后端：`backend-java`，需 JDK、Maven，配置 `application.yml` / 环境变量（如 `DASHSCOPE_API_KEY`）与数据库等。
- 前端：根目录 `npm install` 后 `npm run dev`。

具体端口与命令以当时 `README` 或脚本为准。

## 注意事项

- **密钥**：请勿将真实 API Key 提交到公开仓库；若 v1 快照中含占位或本地密钥，回档后请自行检查 `application.yml` 与环境变量。
- **依赖**：`node_modules`、`backend-java/target` 通常不纳入版本库，回档后需重新构建/安装。

## 新增版本时

复制本文件为 `VERSION_v2.md`（或更新本文件），并打新标签，例如：

```bash
git tag -a v2 -m "v2 说明摘要"
```
