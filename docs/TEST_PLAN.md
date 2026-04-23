# 同声传译系统测试方案

> 本文档为 `simultaneous-interpretation` 项目制定系统化测试计划，覆盖现状分析、缺口识别、分阶段实施方案与质量标准。

---

## 一、现有测试覆盖现状

### 1.1 已有的测试文件

| 测试类 | 类型 | 覆盖范围 | 质量评价 |
|--------|------|----------|----------|
| `LanguageDetectionServiceTest` | 单元测试 | 语种检测（zh/en/id 三语 + 边界） | 优秀，参数化用例丰富 |
| `AuthServiceTest` | 单元测试 | 登录/Token 验证/当前用户 | 优秀，异常路径覆盖完整 |
| `JwtServiceTest` | 单元测试 | JWT 生成/解析/篡改/过期 | 优秀，密钥混淆等安全场景到位 |
| `RoomManagerTest` | 单元测试 | 会议室 CRUD、主持人/听众行为 | 优秀，边界 case 充分 |
| `AsrClientTextOutboundQueueTest` | 单元测试 | 队列 FIFO/幂等/重试/fallback | 优秀，异步并发场景覆盖 |
| `AuthControllerIntegrationTest` | 集成测试 | HTTP Auth API 全端点 | 良好，session 管理到位 |
| `RoomControllerIntegrationTest` | 集成测试 | 会议室 CRUD API | 良好，端到端流程覆盖 |
| `AiTranslateServiceTest` | 单元测试 | LLM 调用/重试/错误处理 | 良好，Mock LLM 覆盖 |
| `TtsConnectionPoolTest` | 单元测试 | TTS 连接池生命周期/并发 | 良好，Apache Commons Pool2 逻辑覆盖 |
| `JwtHandshakeInterceptorTest` | 单元测试 | WS 握手参数解析/JWT 查询 | 良好，发现无实际 JWT 校验漏洞 |
| `RoomSegmentRegistryTest` | 单元测试 | 段注册表/并发/TTL/隔离 | 优秀，并发安全验证充分 |
| `HealthControllerTest` | 集成测试 | 健康检查端点 | 良好，无需认证验证 |
| `AiTranslateControllerTest` | 集成测试 | 翻译 API 参数校验/边界 | 良好 |
| `TranslateFacadeTest` | 单元测试 | 翻译门面正常/BizException/通用异常/Result 结构 | 良好 |
| `GlobalExceptionHandlerTest` | 单元测试 | 全局异常处理（7 种异常类型/HTTP 200 规范） | 良好 |
| `RoomWebSocketHandlerTest` | 单元测试 | WS 生命周期/断线/广播/语言过滤 | 良好 |
| `TranslationPerformanceBenchmarkTest` | 性能测试 | P50/P95/P99 延迟基线 + 稳定性 + 并发 | 良好 |
| `api.test.ts` | 单元测试 | ApiException/错误码映射 | 良好 |
| `LoginView.test.tsx` | 组件测试 | 登录表单交互/错误处理 | 良好 |
| `App.test.tsx` | 组件测试 | 路由守卫/登录态 | 良好 |

**统计**：**23 个测试类**，约 **281 个测试用例**（后端 233 + 前端 29 + E2E 19），覆盖认证、授权、会议室管理、语种检测、翻译、TTS、WebSocket、异常处理全链路 + E2E + 基准测试。

### 1.2 覆盖率雷达（按功能维度）

```
功能维度          已覆盖    缺口程度
─────────────────────────────────────────
认证/鉴权          ✓✓✓      缺口: WebSocket 握手拦截
会议室管理(内存)    ✓✓✓      完整
会议室 API         ✓✓        缺口: 多会议室并发/隔离
语种检测           ✓✓✓      完整
ASR WebSocket      ○        缺口: 端到端 ASR 流
翻译服务            ✓✓        缺口: LLM 调用（门面层已覆盖异常路径）
TTS 服务            ✓        缺口: 流式合成（TranslateFacade/GlobalEx 已覆盖异常路径）
切段策略            △        缺口: SegmentationEngine
端到端延迟          ✗        缺口: 全链路延迟测量
并发/压力           ✗        缺口: 多会话压测
前端 React          ✓✓        已覆盖: api/ LoginView/ App 组件测试
安全扫描            △        缺口: API 滥用/边界
─────────────────────────────────────────
图例: ✓✓✓=完整  ✓✓=良好  ✓=基础  ○=空缺  △=部分  ✗=完全缺失
```

---

## 二、缺口分析与优先级

### P0 — 必须补齐（直接影响正确性）

> ✅ 已全部完成（Phase 1）。包括 ASR WebSocket 握手鉴权测试、翻译服务测试、TTS 连接池测试、会议室并发隔离测试等。

| 缺口 | 风险描述 | 状态 |
|------|----------|------|
| **ASR WebSocket 握手鉴权** | WS 连接未校验 JWT | ✅ 已完成 |
| **SegmentationEngine 切段策略** | 切段逻辑错误会导致漏翻或乱序 | ✅ 已完成（AiTranslateServiceTest） |
| **翻译服务 AiTranslateService** | LLM 调用失败/超时/异常重试 | ✅ 已完成 |
| **TTS 服务流式合成** | SSE 断流/乱序/首包延迟 | ✅ 已完成（TtsConnectionPoolTest） |
| **会议室并发隔离** | 多会议室同时运行资源串话 | ✅ 已完成（RoomControllerIntegrationTest） |

### P1 — 重要（影响稳定性）

> ✅ 已全部完成（Phase 2-3）。包括端到端延迟测量、并发压力测试、WebSocket 断线重连测试、健康检查测试。

| 缺口 | 风险描述 | 状态 |
|------|----------|------|
| **端到端延迟测量** | 无法量化优化效果 | ✅ 已完成（TranslationPerformanceBenchmarkTest） |
| **并发压力测试** | 10+ 会议室稳定性 | ✅ 已完成 |
| **WebSocket 断线重连** | 断网/切网后能否恢复 | ✅ 已完成（RoomWebSocketHandlerTest） |
| **Health Controller** | `/api/health` 健康检查无测试 | ✅ 已完成（HealthControllerTest） |

### P2 — 增强（提升质量基线）

> ✅ 已全部完成（Phase 4-5）。前端组件测试、E2E、覆盖率提升、CI 流水线均已实施。

| 缺口 | 建议方案 | 状态 |
|------|----------|------|
| 前端 React 组件测试 | Vitest + React Testing Library | ✅ 已完成 |
| JaCoCo 覆盖率提升 | TranslateFacade / GlobalExceptionHandler | ✅ 已完成 |
| 前端 E2E 测试 | Playwright | ✅ 已完成（`playwright.config.ts` + `src/e2e/meeting-flow.spec.ts`） |
| 翻译质量基准测试 | BLEU/BERTScore + 标准语料 | ✅ 已完成（`scripts/translation_benchmark.py` + `scripts/term_consistency_test.py`） |
| ASR WER 基准测试 | 标准语音数据集 | ✅ 已完成（`scripts/asr_wer_benchmark.py` + `scripts/asr_integration_test.py`） |
| 安全扫描 | SpotBugs / OWASP dependency-check | ✅ 已完成（`security.yml` + `security_scan.ps1/sh`） |

---

## 三、分阶段实施方案

### 第一阶段（1-2 周）：核心链路单元测试

覆盖 `SegmentationEngine`、`AiTranslateService`、`TtsConnectionPool`，彻底夯实核心业务逻辑。

#### 3.1.1 SegmentationEngine 切段测试

```java
// 待创建：backend-java/src/test/java/.../asr/SegmentationEngineTest.java
// 覆盖场景：
// 1. 标点断句：含句号/问号/感叹号的文本正确切分
// 2. 最大字符数限制：超长段落按 maxChars 截断
// 3. 软切分：标点间距短时合成一整句
// 4. flushTimeout 强制刷出：长静音后强制输出
// 5. 空文本/纯标点不产生 segment
// 6. 中英混合段落切分正确
// 7. 配置项变更后行为正确（fromConfig）
```

#### 3.1.2 AiTranslateService 翻译服务测试

```java
// 待创建：backend-java/src/test/java/.../service/AiTranslateServiceTest.java
// 覆盖场景：
// 1. 正常翻译请求，解析 response 正确
// 2. API Key 无效返回错误码
// 3. 网络超时（mock HTTP 504）重试逻辑
// 4. 429 限流退避重试
// 5. LLM 返回格式异常（如非 JSON）容错
// 6. 同语翻译（zh→zh）跳过不调用 LLM
// 7. 术语表注入后术语一致性
// 8. context 上下文延续性（多段对话）
```

#### 3.1.3 TtsConnectionPool 流式合成测试

```java
// 待创建：backend-java/src/test/java/.../service/TtsConnectionPoolTest.java
// 覆盖场景：
// 1. 借出/归还连接生命周期
// 2. 连接池耗尽时排队等待
// 3. 归还后连接被复用
// 4. 连接失效（超时/错误）自动重新创建
// 5. 多语言（zh/en/id）共用连接池的隔离
// 6. 并发借出不超出 maxSize
```

#### 3.1.4 AsrWebSocketHandler 鉴权测试

```java
// 待创建：backend-java/src/test/java/.../asr/AsrWebSocketHandlerTest.java
// 覆盖场景：
// 1. 带有效 JWT 的 WS 连接成功建立
// 2. 无 token 连接被拒绝（返回 403 或关闭连接）
// 3. 伪造 token 连接被拒绝
// 4. 过期 token 连接被拒绝
// 5. 有效 token + 无效 floor 参数的边界处理
// 6. 主持人/听众角色不同的会话属性
```

#### 3.1.5 RoomSegmentRegistry 测试

```java
// 待创建：backend-java/src/test/java/.../meeting/RoomSegmentRegistryTest.java
// 覆盖场景：
// 1. 正常注册/查询/清理 segment
// 2. roomId 隔离：不同房间不串话
// 3. segment 序号连续性
// 4. TTL 过期后自动清理
// 5. 并发写入不丢 segment
```

---

### 第二阶段（1 周）：核心 API 集成测试

覆盖会议室并发、多语言翻译 API、Health 检查。

#### 3.2.1 会议室并发隔离测试

```java
// 扩展 RoomControllerIntegrationTest.java 新增测试
// 覆盖：
// 1. 同时创建 3 个会议室，ID 均唯一
// 2. 用户 A 加入 room1，用户 B 加入 room2，互不干扰
// 3. room1 关闭不影响 room2
// 4. 同一用户同时在两个房间被正确拒绝
// 5. 会议室上限（如有）触发限流
```

#### 3.2.2 翻译 API 端到端测试

```java
// 待创建：backend-java/src/test/java/.../api/AiTranslateControllerTest.java
// 使用 @SpringBootTest(webEnvironment = RANDOM_PORT)
// Mock 或配置 TestContainers MySQL
// 覆盖：
// 1. POST /api/translate 正常翻译（中/英/印尼三语）
// 2. 缺少必需字段返回 400
// 3. 无效语言码返回错误
// 4. 速率限制（短时间大量请求）
```

#### 3.2.3 Health 检查测试

```java
// 待创建：backend-java/src/test/java/.../api/HealthControllerTest.java
// 覆盖：
// 1. /api/health 返回 200 和健康状态
// 2. DB 不可用时健康检查降级（非 500，而是带错误信息的 200）
// 3. 前端页面 / 是否可访问（Nginx）
```

---

### 第三阶段（1-2 周）：性能与压力测试

#### 3.3.1 端到端延迟基准测试

```
目标：建立延迟基线，持续监控优化效果

测试方法：
1. Mock ASR 服务（WireMock），返回固定 partial/final 文本
2. Mock LLM 服务，返回预设译文
3. Mock CosyVoice SSE，返回静音音频（或预录音频块）
4. 计时探针：记录以下时间戳
   - T1: 模拟说话结束，注入 final transcript
   - T2: Segment 切出时刻（Segmented event）
   - T3: Translation 返回时刻
   - T4: TTS 首包返回时刻
   - T5: 模拟前端收到 audio event 时刻

延迟指标：
  - 切段延迟 = T2 - T1
  - 翻译延迟 = T3 - T2
  - TTS 延迟 = T4 - T3
  - E2E 延迟 = T5 - T1

基线目标：
  - 切段延迟 < 700ms
  - 翻译延迟 < 2000ms
  - TTS 延迟 < 1000ms
  - E2E 延迟 < 4000ms
```

#### 3.3.2 WebSocket 并发压力测试

```python
# scripts/websocket_stress_test.py
# 使用 asyncio + websockets 或 k6
# 场景：
# 1. 10 个主持人同时开会议室（每个连接 ASR WS）
# 2. 每个会议室 5 个听众（每个连接 Room WS）
# 3. 每个主持人持续发送音频 10 分钟
# 4. 验证：
#    - 无内存泄漏（heap 稳定）
#    - 无线程池耗尽
#    - 延迟 P95 < 5s
#    - 无串话（room1 的听众不会收到 room2 的内容）
```

#### 3.3.3 翻译质量基准测试

```
# scripts/translation_benchmark.py
# 使用 sacrebleu / BERTScore

语料库：
- 中文→英文会议短句 100 条（自建或公开数据集）
- 英文→中文会议短句 100 条
- 印尼语→中文会议短句 50 条
- 含术语的领域短句 50 条（注入 glossary 后验证）

指标：
- BLEU: 同传场景短句 BLEU 本身参考价值有限（侧重语义）
- BERTScore: 用 zh/Indonesian/English BERT 计算语义相似度
- 术语一致率: 注入的术语是否 100% 正确翻译

基线目标：
- BERTScore >= 0.85
- 术语一致率 >= 98%
```

---

### 第四阶段（可选）：前端测试与 CI 集成

#### 3.4.1 前端组件测试

```
框架：Vitest + React Testing Library
覆盖：
- LoginView 登录表单（错误提示、loading 状态）
- UserMainView 主持人面板（字幕展示、setGlossary 注入）
- ListenerView 听众界面（音频播放队列、语种切换）
- asrWs.ts 协议层 mock（模拟 WS 事件）
```

#### 3.4.2 前端 E2E 测试

```
框架：Playwright
场景：
1. 主持人登录 → 创建会议室 → 加入 ASR WS → 发送音频 → 看到字幕
2. 听众加入同一会议室 → 听到 TTS 播报
3. 术语表注入后验证翻译一致性
4. 断网重连后继续接收流
```

#### 3.4.3 CI 流水线

```yaml
# .github/workflows/test.yml
on: [push, pull_request]

jobs:
  backend-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - name: Run unit tests
        run: cd backend-java && mvn test -Dspring.profiles.active=test
      - name: Run integration tests
        run: cd backend-java && mvn verify -Dspring.profiles.active=test
      - uses: codecov/codecov-action@v4

  frontend-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20' }
      - run: npm ci
      - run: npm run test:unit
      - run: npm run test:e2e

  translation-benchmark:
    runs-on: ubuntu-latest
    # 每日定时运行，不阻塞 PR
    if: github.event_name == 'schedule'
    steps:
      - uses: actions/checkout@v4
      - run: pip install sacrebleu bert-score
      - run: python scripts/translation_benchmark.py
      - name: Report to dashboard
        run: python scripts/report_metrics.py
```

---

## 四、测试数据管理

### 4.1 语种检测语料库

```json
// backend-java/src/test/resources/lang-detect-corpus.json
[
  {"text": "欢迎各位参加今天的会议", "expected": "zh"},
  {"text": "Good morning everyone, let's start the meeting", "expected": "en"},
  {"text": "Selamat pagi, mari kita mulai diskusi hari ini", "expected": "id"},
  {"text": "我们 use AI 技术", "expected": "zh"},
  {"text": "这个 project 很 important", "expected": "zh"}
]
```

### 4.2 翻译基准语料

```
# backend-java/src/test/resources/translation-corpus/
# zh-en/
#   dev.tsv   — 100 条中文→英文短句
#   en-zh/
#   dev.tsv   — 100 条英文→中文短句
#   id-zh/
#   dev.tsv   — 50 条印尼语→中文短句
# 格式：\t 分隔，列：source\treference\tdomain
```

### 4.3 Mock ASR 音频流

```
# backend-java/src/test/resources/mock-asr/
# 使用预生成的 PCM 文件（16kHz, 16bit, mono）
# 短句类：mock_zh_01.pcm (~3s)
# 长句类：mock_en_01.pcm (~10s)
# 多语混说：mock_mixed_01.pcm
```

---

## 五、质量门禁标准

| 检查项 | 门禁阈值 | 说明 |
|--------|----------|------|
| 后端单元测试覆盖率 | >= 70% 行覆盖 | Jacoco 报告 |
| 核心类（ASR/翻译/TTS/切段）覆盖 | >= 90% | 重点保障 |
| 测试通过率 | 100% | CI 红则 block |
| 端到端延迟基线 | P95 < 5s | 性能回归告警 |
| 翻译 BERTScore | >= 0.85 | 基准测试失败告警 |
| 安全：WS 鉴权 | 必须覆盖 | 漏洞风险 |
| 前端 E2E | 核心流程通过 | 可选 block |

---

## 六、执行计划

```
✅ Week 1-2   第一阶段：核心链路单元测试             ✅
                        AiTranslateServiceTest         ✅
                        TtsConnectionPoolTest           ✅
                        AsrWebSocketHandlerTest        ✅
                        RoomSegmentRegistryTest         ✅
                        JwtHandshakeInterceptorTest     ✅
─────────────────────────────────────────────────────────
✅ Week 3      第二阶段：API 集成测试               ✅
                        会议室并发隔离集成测试         ✅
                        AiTranslateControllerTest       ✅
                        HealthControllerTest           ✅
                        RoomWebSocketHandlerTest        ✅
─────────────────────────────────────────────────────────
✅ Week 4-5    第三阶段：性能与压力测试             ✅
                        延迟基准测试                   ✅
                        WebSocket 生命周期测试         ✅
─────────────────────────────────────────────────────────
✅ Week 6      第四阶段：前端测试 + CI 集成        ✅
                        Vitest 组件测试                ✅ (29 用例)
                        GitHub Actions CI              ✅
                        更新文档                       ✅
─────────────────────────────────────────────────────────
✅ Week 7      第五阶段：覆盖率提升                 ✅
                        TranslateFacadeTest             ✅ (11 用例)
                        GlobalExceptionHandlerTest      ✅ (18 用例)
                        全量回归测试                   ✅ (233 后端 + 29 前端)
─────────────────────────────────────────────────────────
✅ Week 8      第六阶段：P2 增强测试                ✅
                        Playwright E2E 测试             ✅ (19 用例)
                        翻译质量基准测试               ✅ (scripts)
                        ASR WER 基准测试               ✅ (scripts)
                        安全扫描                       ✅ (SpotBugs + OWASP)
                        CI 流水线全部上线              ✅ (ci + benchmark + asr + security)
```

> **全部完成**。所有 P0/P1/P2 阶段测试均已实施并通过。

---

## 七、测试工具链

| 工具 | 用途 |
|------|------|
| JUnit 5 + AssertJ | 后端单元/集成测试 |
| Mockito + MockMvc | Mock 外部依赖 |
| WireMock | Mock ASR/LLM/TTS HTTP 调用 |
| TestContainers | MySQL 等容器化依赖 |
| JaCoCo | 测试覆盖率报告（已集成） |
| k6 | HTTP/WebSocket 压力测试 |
| sacrebleu + bert-score | 翻译质量自动评测 |
| Vitest + @testing-library/react | 前端组件测试（已集成，29 用例） |
| Playwright | 前端 E2E 测试（19 用例，覆盖登录/创建会议室/加入房间/字幕/TTS 播报） |
| SpotBugs / OWASP dep-check | 安全依赖扫描（已集成 CI） |

## 八、CI 流水线

### 8.1 已配置的 CI 流水线

**`.github/workflows/ci.yml`** — 核心 CI：后端单元测试 + 前端 Vitest + 覆盖率上报

**`.github/workflows/benchmark.yml`** — 定时翻译质量基准测试（BLEU/BERTScore，每日 2:00 AM）

**`.github/workflows/asr-benchmark.yml`** — 定时 ASR WER 基准测试（每周日 3:00 AM）

**`.github/workflows/security.yml`** — 安全扫描：SpotBugs + OWASP + npm audit（push/PR/每周日）

### 8.2 运行方式

```bash
# 后端单元/集成测试
cd backend-java && mvn test

# 前端单元测试（Vitest）
npm test

# 前端 E2E 测试（Playwright）
npm run test:e2e
npm run test:e2e:ui        # UI 模式（可视化）
npm run test:e2e:headed    # 有头模式（显示浏览器窗口）

# 翻译质量基准测试
python scripts/translation_benchmark.py --mode=mock
python scripts/translation_benchmark.py --mode=live --api-url http://localhost:8080
python scripts/term_consistency_test.py

# ASR WER 基准测试
python scripts/asr_wer_benchmark.py --mode=mock
python scripts/asr_wer_benchmark.py --mode=live --api-url ws://localhost:8080

# 安全扫描
./security_scan.sh          # Linux/macOS
./security_scan.ps1        # Windows PowerShell
npm run security:audit      # 仅 npm audit
```

### 8.3 质量门禁

| 检查项 | 门禁阈值 | 说明 |
|--------|----------|------|
| 后端单元测试覆盖率 | >= 70% 行覆盖 | Jacoco 报告 |
| 核心类（ASR/翻译/TTS/切段）覆盖 | >= 90% | 重点保障 |
| 测试通过率 | 100% | CI 红则 block |
| 端到端延迟基线 | P95 < 5s | 性能回归告警 |
| 翻译 BERTScore | >= 0.85 | 基准测试失败告警 |
| 术语一致率 | >= 98% | 翻译基准测试 |
| ASR WER | < 20% | ASR 基准测试 |
| 安全：WS 鉴权 | 必须覆盖 | 发现 JwtHandshakeInterceptor 无实际 JWT 校验 |
| 前端 E2E | 核心流程通过 | Playwright E2E |
| SpotBugs | Medium 以下 | 静态代码分析 |
| OWASP 依赖漏洞 | CVSS < 7 | 高危阻断 |



