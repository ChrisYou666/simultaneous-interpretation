# 同声传译：业界方案与本项目对照

本文档概括 **开源 / 商业** 同声传译相关能力，并说明 **本仓库** 的定位、差距与可调参数。

## 一、代表性开源路线（端到端 / 统一模型）

| 项目 | 概要 | 与本项目差异 |
|------|------|----------------|
| [Meta Seamless / SeamlessM4T / SeamlessStreaming](https://github.com/facebookresearch/seamless_communication) | 多语种语音↔语音、语音↔文本；Streaming 面向 **低延迟流式** | 本仓库是 **ASR → 切段 → LLM 翻译 → TTS** 的 **流水线**，非单一神经网络；语种覆盖为 **中英印尼三语** 业务配置，而非百语种统一模型 |
| [StreamSpeech (ictnlp)](https://github.com/ictnlp/StreamSpeech) | 「一体化」流式识别 + 翻译 + 合成 | 同上：架构不同；本项目依赖 **DashScope/Deepgram + Qwen + CosyVoice** 等云服务组合 |
| [Kyutai Hibiki](https://github.com/kyutai-labs/hibiki) | 同步语音到语音翻译（当前以特定语对为主） | 强调 **直接生成目标语音**；本项目是 **文本翻译 + 独立 TTS**，韵律/说话人风格由 TTS 模型决定 |

**差距（架构层）**：端到端模型在 **理论延迟结构、声纹/韵律一致性** 上更强；流水线方案 **更易换供应商、加术语表与会议上下文**，但环节多、需自行调切段与排队策略。

## 二、代表性商业 / API

| 类型 | 示例 | 常见能力 |
|------|------|----------|
| 国内语音云 | [讯飞同声传译 API](https://www.xfyun.cn/doc/nlp/simultaneous-interpretation/API.html) 等 | 流式语音输入、多语输出、部分带 TTS |
| 企业翻译 | [DeepL Voice API](https://developers.deepl.com/api-reference/voice) 等 | 实时语音转写与翻译（企业订阅） |
| 会议场景 SaaS | InterpretCloud、InterpretWise、TransLinguist 等 | **Zoom/Teams 集成**、观众端二维码、**人机混合同传**、运维与计费 |

**差距（产品层）**：本项目是 **自建 Web 面板 + WebSocket**，不包含 **视频会议插件、观众多终端、人工译员席位、SLA/合规套餐** 等商业化封装。

## 三、本项目当前能力（摘要）

- **ASR**：DashScope Fun-ASR 实时或 Deepgram Live（可切换）。
- **翻译**：OpenAI 兼容 HTTP（默认接百炼 Qwen），支持 **术语表 / 上下文**。
- **TTS**：收听语言按段串行播报（CosyVoice 等，见后端实现）。
- **前端**：实时字幕式展示、按段有序播放、延迟展示。
- **可靠性**：同连接内 JSON 单线程下行队列、上行 PCM 有序发送与重试等（**不**包含断线重连补发，已移除流水表方案）。

## 四、可操作的「缩小差距」方向（工程上）

已在仓库中落实或与配置相关：

1. **切段可调**：`app.asr.segmentation.*`（`max-chars`、`soft-break-chars`、`flush-timeout-ms`）——向商用系统常见的 **延迟 / 流畅度** 权衡靠拢。
2. **密钥与脚本**：默认配置与 `run-dev.cmd` **不再内置真实 API Key / 数据库密码**，避免泄露；本地通过环境变量注入。

若需进一步向 **Seamless 类体验** 靠近，需要 **单独立项**：例如接入自托管 Streaming 模型、或采购带 **流式语音翻译** 的一体化 API，并改造音频路径与延迟指标。

## 五、参考链接（便于延伸阅读）

- Seamless: https://github.com/facebookresearch/seamless_communication  
- StreamSpeech: https://github.com/ictnlp/StreamSpeech  
- Hibiki: https://github.com/kyutai-labs/hibiki  

（商业链接随厂商更新，以官网文档为准。）
