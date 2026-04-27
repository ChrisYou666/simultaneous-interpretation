# 同声传译系统（Simultaneous Interpretation System）

## 项目概述

这是一个基于 Web 的实时同声传译系统，支持主持人语音实时识别、多语言翻译合成，并通过 WebSocket 向听众端推送音频流。

## 技术架构

| 层级 | 技术选型 |
|------|----------|
| **前端** | Vite 5 + React 18 + TypeScript 5 |
| **后端** | Spring Boot 3.2 + Spring WebSocket + JDBC |
| **数据层** | MySQL 8 + Flyway 数据库迁移 |
| **LLM / 翻译** | OpenAI 兼容客户端（默认接入阿里云百炼 Qwen） |
| **ASR 语音识别** | DashScope Fun-ASR 实时 / Deepgram / OpenAI Realtime（可配置切换） |
| **TTS 语音合成** | CosyVoice（HTTP SSE 流式） |

## 核心流程

```
麦克风音频 → ASR流式识别 → 语义切段 → LLM翻译 → TTS合成 → WebSocket推送 → 听众端播放
```

## 核心功能

### 1. 主持人端（需登录）
- 实时语音识别显示（源语言 + 目标语言双语对照）
- 支持会议材料上传（PDF 文本提取）
- AI 辅助翻译助手
- 实时流式字幕面板

### 2. 听众端（无需登录）
- 实时接收翻译后的语音流
- 支持多种目标语言
- 同源语言自动识别（无需翻译）

### 3. 权限控制
- JWT Token 认证
- 内存用户存储（开发模式）/ MySQL 持久化（生产模式）

## 项目结构

```
simultaneous-interpretation/
├── src/                          # React 前端
│   ├── api/                      # API 客户端
│   ├── components/               # UI 组件
│   ├── lib/                     # 工具库
│   └── views/                   # 页面视图
│       ├── LoginView.tsx         # 登录页
│       ├── UserMainView.tsx      # 主持人主界面
│       └── ListenerView.tsx      # 听众界面
│
├── backend-java/                 # Spring Boot 后端
│   ├── src/main/java/com/simultaneousinterpretation/
│   │   ├── api/                  # REST API 控制器
│   │   ├── asr/                  # ASR WebSocket 处理
│   │   ├── config/               # 配置类
│   │   ├── domain/                # 实体与枚举
│   │   ├── facade/                # 门面层
│   │   ├── integration/           # 第三方集成
│   │   ├── meeting/               # 会议室核心逻辑
│   │   ├── security/              # JWT 安全
│   │   └── service/               # 业务服务
│   └── src/main/resources/
│       ├── application.yml        # 主配置
│       └── db/migration/          # Flyway 迁移脚本
│
├── docs/                         # 技术文档
├── Dockerfile                    # 容器化部署
├── docker-compose.yml            # 编排配置
└── nginx.conf                    # 反向代理配置
```

## 快速开始

### 前置条件
- JDK 21+
- Maven 3.8+
- Node.js 18+
- MySQL 8+（可选，开发模式可使用内存存储）
- 阿里云百炼 API Key

### 启动后端

```bash
# 设置环境变量
set DASHSCOPE_API_KEY=你的百炼密钥

# Windows
run-dev.cmd

# 或手动启动
cd backend-java
mvn spring-boot:run
```

### 启动前端

```bash
npm install
npm run dev
```

访问 `http://localhost:5174/`

## 配置说明

### ASR 提供商切换

在 `application.yml` 中配置：

```yaml
app:
  asr:
    provider: dashscope  # dashscope | deepgram | openai
```

### 切段参数调优

影响翻译/TTS 触发粒度与端到端延迟：

```yaml
app:
  asr:
    segmentation:
      max-chars: 50           # 最大字符数
      soft-break-chars: 15    # 软切分阈值
      flush-timeout-ms: 500   # 超时强制输出
```

## 接口说明

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/health` | GET | 健康检查 |
| `/api/auth/login` | POST | 用户登录 |
| `/api/rooms` | POST | 创建会议室 |
| `/api/rooms/{id}/join` | POST | 加入会议室 |
| `/api/translate` | POST | 文本翻译 |
| `/ws/asr` | WebSocket | ASR 实时语音流 |
| `/ws/room/{roomId}` | WebSocket | 会议室音频流 |

## 与业界方案对比

- **开源端到端方案**（Meta SeamlessStreaming、StreamSpeech 等）：单一流式模型，易用但难换云厂商、难加术语表
- **商业同传服务**（讯飞同传、DeepL Voice 等）：带视频会议集成，本项目为自建 WebSocket 同传面板
- **本项目**：流水线架构（ASR → 切段 → LLM → TTS），易换云厂商、易加术语表，当前聚焦中英印尼三语

## 默认端口

- 前端开发服务器：**5174**
- 后端 HTTP：**8100**

## 部署

支持 Docker 一键部署：

```bash
docker-compose up -d
```

或使用 Dockerfile 单独构建镜像。
