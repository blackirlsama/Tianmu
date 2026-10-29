<div align="center">

# Tianmu · 天幕

**面向视频创作者的开源视频平台与 AI 运营助手**

连接视频内容、弹幕与评论，让创作者更好地理解观众，规划内容，开展互动。

[![Build and verify](https://github.com/blackirlsama/Tianmu/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/blackirlsama/Tianmu/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 17](https://img.shields.io/badge/Java-17-orange.svg)](pom.xml)
[![Vue 3](https://img.shields.io/badge/Vue-3-42b883.svg)](tianmu-front/package.json)
[![GitHub stars](https://img.shields.io/github/stars/blackirlsama/Tianmu?style=flat)](https://github.com/blackirlsama/Tianmu/stargazers)

[功能特性](#功能特性) · [后端架构](#后端架构) · [Agent 架构](#agent-架构) · [快速开始](#快速开始) · [文档](#文档) · [贡献](#参与贡献)

</div>

![天幕视频平台首页](images/3.png)

## 项目介绍

天幕是一套基于 **Spring Boot + Vue 3** 的视频平台，提供视频上传、播放、搜索、弹幕、评论和用户互动能力，并集成独立部署的 **Java Agent**，帮助创作者从观众反馈中发现内容方向。

视频后端负责业务与数据，Agent 负责理解需求、调用工具和生成结果。两者通过鉴权接口连接，可以分别开发、测试与部署。

> 当前为开发预览版本。可以先运行无需模型 Key 的 Agent Demo，或独立体验 Trace Studio，再接入完整网站和真实模型。

## 功能特性

| 模块 | 能力 |
| --- | --- |
| **视频平台** | 视频上传与播放、内容分类、检索、关注及互动 |
| **弹幕与评论** | 弹幕发送与展示、评论及回复、互动数据统计 |
| **观众反馈报告** | 联合分析弹幕与评论，结合画面生成可阅读的 HTML 报告 |
| **视频理解** | 画面概括、指定片段分析、内容选题建议 |
| **智能互动** | 生成作者评论与弹幕草稿，编辑确认后发布 |
| **对话助手** | 多轮沟通、任务进度、取消与结果查看 |
| **配置化问答** | 人工维护常见问题与标准回答 |
| **Trace Studio** | 调用关系图、时间线、节点详情与日志导入 |

在自己的视频页打开助手，可以这样开始：

```text
分析这个视频的弹幕和评论，结合画面生成观众反馈报告。
概括这个视频的画面内容，重点描述最后一个片段。
结合当前视频，给我几个下一条视频的选题方向。
生成几条引导观众讨论的作者评论，先给我草稿。
```

## 后端架构

视频后端采用模块化单体结构。网站通过 HTTP 读写业务数据，通过 WebSocket 收发弹幕；媒体文件、业务记录、缓存与搜索索引分别流向对应的存储服务。

```mermaid
flowchart TB
    Web["Vue 3 网站 / 播放器"] <-->|HTTP 请求 / 响应| Gateway["Nginx"]

    subgraph Services["业务服务 · Spring Boot"]
        API["用户 / 视频 / 评论接口"]
        Upload["文件上传服务"]
        Search["搜索服务"]
        WS["Netty 弹幕服务"]
        Consumer["弹幕消息消费者"]
    end

    Gateway <-->|业务请求 / JSON 结果| API
    Gateway -->|文件分片| Upload
    Gateway <-->|搜索词 / 查询结果| Search
    Web <-->|WebSocket：发送 / 广播| Gateway
    Gateway <-->|弹幕连接| WS

    API <-->|业务记录读写| DB[("MySQL")]
    API <-->|热点数据读写| Cache[("Redis")]
    Upload -->|媒体文件| Files[("MinIO")]
    Upload -->|文件元数据| DB
    Files -->|视频文件 / 播放读取| Web
    Search <-->|全文检索 / 命中结果| ES[("Elasticsearch")]

    WS -->|弹幕事件| MQ["RocketMQ"]
    MQ -->|消费消息| Consumer
    Consumer -->|幂等入库| DB
    DB -. Binlog 变更 .-> Canal["Canal 变更订阅"]
    Canal -->|更新弹幕与业务缓存| Cache
    Canal -->|更新用户与视频索引| ES

    classDef entry fill:#e8f1ff,stroke:#4f7db8,color:#1c3555
    classDef service fill:#f0ecff,stroke:#8971bc,color:#382b59
    classDef storage fill:#fff4de,stroke:#b99340,color:#58451d
    classDef event fill:#e6f5ef,stroke:#55977d,color:#234b3d
    class Web,Gateway entry
    class API,Upload,Search,WS,Consumer service
    class DB,Cache,Files,ES storage
    class MQ,Canal event
```

| 层次 | 技术与职责 |
| --- | --- |
| 业务请求 | 网站 → Nginx → 业务接口 → MySQL / Redis，查询结果返回网站 |
| 视频上传与播放 | 上传服务写入 MinIO，并保存文件元数据；播放器读取媒体文件 |
| 弹幕发送 | WebSocket → Netty → RocketMQ → 消费者 → MySQL；Netty 同时负责连接与广播 |
| 数据同步与搜索 | MySQL 变更经 Canal 更新 Redis 与 Elasticsearch，搜索服务读取索引返回结果 |

业务后端代码位于 [`src/`](src/)，部署说明见[完整网站部署](deploy/site/README.md)。

## Agent 架构

![天幕运营助手：视频页对话与观众反馈报告](images/agent-workspace.png)

Agent 是独立的 Spring Boot 服务。一次请求先完成准备与路由，再进入 Harness 执行循环：构造上下文、调用模型、执行工具、回收结果，直到生成回答或交付报告。Hook 围绕关键阶段提供前置检查与后置观察，不替代循环本身。

```mermaid
flowchart TB
    Web["作者助手"] -->|用户消息 / 当前视频| Entry["请求准备与路由"]
    Entry --> Route{"执行路径"}
    Route -->|直接回答 / 需要补充| Delivery["回答 / 澄清 / 报告交付"]
    Route -->|需要模型与工具| Context

    subgraph Loop["Harness · Agent Loop"]
        Context["构造本轮上下文<br/>历史 + 任务 + 可用工具 + 已有结果"]
        Model["模型调用<br/>原生 messages / tools"]
        Decision{"模型决策"}
        Tools["Tool Dispatcher<br/>校验参数与权限，执行工具"]
        Observation["接收工具结果<br/>更新证据与执行状态"]

        Context --> Model
        Model -->|回答 / 澄清 / tool_calls| Decision
        Decision -->|调用工具| Tools
        Tools -->|工具结果 / 可恢复错误| Observation
        Observation -->|携带结果，进入下一轮| Context
    end

    Decision -->|回答 / 澄清| Delivery
    Tools -->|报告等终态产物| Delivery
    Delivery -->|SSE 进度 / 答案 / 产物链接| Web
    Delivery --> End["请求结束"]

    Tools <-->|按需激活能力| Skills["Skill Catalog"]
    Tools <-->|查询数据 / 返回证据| Bridge["鉴权业务桥接"]
    Bridge <-->|授权业务数据| Backend["视频业务后端"]
    Context <-->|读取历史与状态| Store[("MySQL / Redis / 私有对象存储")]
    Observation -->|保存证据与进度| Store
    Delivery -->|保存最终结果| Store

    subgraph Extensions["执行控制与扩展"]
        Control["Loop Control<br/>轮次 / 时限 / 预算 / 取消"]
        Hooks["Hook Dispatcher<br/>前置 Gate / 后置 Observer"]
        Transform["Transform<br/>上下文与结果变换"]
    end

    Control -. 每轮检查，超限或取消则退出 .-> Context
    Hooks -. REQUEST_START / PRE_ROUTE .-> Entry
    Hooks -. PRE_MODEL / POST_MODEL .-> Model
    Hooks -. PRE_TOOL / POST_TOOL .-> Tools
    Hooks -. PRE_SKILL / POST_SKILL .-> Skills
    Hooks -. PRE_DELIVER / POST_DELIVER .-> Delivery
    Hooks -. REQUEST_END / ON_ERROR / ON_CANCEL .-> End
    Transform -. CONTEXT .-> Context
    Transform -. MODEL_RESULT .-> Decision
    Transform -. TOOL_RESULT .-> Observation

    Loop -. Span 与详情引用 .-> Trace["OpenTelemetry / JSONL"]
    Trace -. 可选采集链路 .-> Lake["Kafka / ClickHouse"]

    classDef core fill:#f0ecff,stroke:#8971bc,color:#382b59
    classDef tool fill:#e6f5ef,stroke:#55977d,color:#234b3d
    classDef extension fill:#fff4de,stroke:#b99340,color:#58451d
    classDef result fill:#e8f1ff,stroke:#4f7db8,color:#1c3555
    class Context,Model,Decision core
    class Tools,Observation,Skills,Bridge tool
    class Control,Hooks,Transform extension
    class Web,Entry,Route,Delivery,End,Trace,Lake result
```

| 组件 | 职责 |
| --- | --- |
| Agent Loop | 工具结果重新进入上下文，驱动下一轮模型决策；回答、澄清或终态产物结束循环 |
| Loop Control | 约束轮次、时限和调用预算，处理取消与终止 |
| Hook | 在模型、工具、Skill 和交付阶段前后插入检查或观察逻辑 |
| Transform | 在固定位置处理上下文和结果，保留权限与任务约束 |
| Skill / Tool | Skill 按需开放能力，Tool 执行操作并把证据或产物返回循环 |
| 存储与 Trace | 保存对话、任务与结果，记录每轮模型和工具的调用关系 |

图中实线表示请求、数据或结果流向，虚线表示扩展点、执行控制和追踪链路。前置 Hook 可阻止尚未执行的操作，后置 Hook 用于观察；可恢复的工具错误会反馈给模型，终止错误和取消则结束请求。

Agent 不直接修改业务数据库，发布操作由用户确认后交给业务后端执行。详细开发与配置说明见 [`tianmu-agent/README.md`](tianmu-agent/README.md)。

## 快速开始

### 环境准备

| 组件 | 环境 |
| --- | --- |
| 后端与 Agent | JDK 17、Maven 3.6.3+ |
| 前端 | Node.js 22、npm |
| 完整网站 | MySQL、Redis、MinIO、Elasticsearch、RocketMQ、Canal 等，见部署文档 |

```bash
git clone --depth 1 https://github.com/blackirlsama/Tianmu.git
cd Tianmu
```

仓库包含约 185 MB 的[视频素材](materials/README.md)，首次拉取需要下载这些文件。

### 方式一：体验 Agent Demo

**无需模型 Key、数据库或中间件。** 在仓库根目录执行：

```bash
mvn -B -f tianmu-agent/pom.xml package
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

服务默认运行在 `http://127.0.0.1:8201`。另开终端创建任务：

```bash
curl -s -X POST http://127.0.0.1:8201/api/agent/runs \
  -H 'Authorization: Bearer demo-alice' \
  -H 'Content-Type: application/json' \
  -d '{"message":"查询视频1001昨天的弹幕数"}'
```

将返回的 `runId` 替换到下方命令中，查看执行结果：

```bash
curl -s -H 'Authorization: Bearer demo-alice' \
  http://127.0.0.1:8201/api/agent/runs/RUN_ID
```

Demo 使用模拟身份和脚本模型，不调用付费服务。以上为 Bash 命令；PowerShell 可使用 `curl.exe`，并将请求命令写在一行。

### 方式二：体验 Trace Studio

```bash
cd tianmu-front
npm ci
npm run serve
```

打开终端显示的开发服务器地址，访问 `/trace`，通常为 `http://localhost:8080/trace`。页面内置演示日志，也支持导入真实的 JSON / JSONL 日志；文件在浏览器本地解析。

当前版本提供离线可视化，尚未连接在线 Trace 查询。更多说明见[前端文档](tianmu-front/README.md)。

### 方式三：运行完整网站

先按[网站部署说明](deploy/site/README.md)初始化业务库和中间件，再参考 [`.env.example`](.env.example) 注入配置。直接运行 Java 不会自动读取 `.env` 文件。

在仓库根目录构建后端与 Agent：

```bash
mvn -B package
mvn -B -f tianmu-agent/pom.xml package
```

分别在两个 Bash 终端启动服务：

```bash
# 视频业务后端
AGENT_BRIDGE_ENABLED=true java -jar target/tianmu-0.0.1-SNAPSHOT.jar
```

```bash
# 接入真实业务、模型和持久化存储的 Agent
SPRING_PROFILES_ACTIVE=business,deepseek,persistent \
  java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

然后启动前端：

```bash
cd tianmu-front
npm ci
npm run serve
```

| 服务 | 默认地址 |
| --- | --- |
| 业务 API | `http://127.0.0.1:8101` |
| Agent API | `http://127.0.0.1:8201` |
| 弹幕 WebSocket | 端口 `9101` |
| 前端 | 以开发服务器输出为准 |

真实模式需配置数据库、登录、中间件及模型凭据，模型调用可能产生费用。前端代理、外部 FAQ 和模型参数的配置分别见[前端说明](tianmu-front/README.md)、[Agent 说明](tianmu-agent/README.md)。容器部署可选择[独立 Agent 栈](deploy/README.md)或[完整网站栈](deploy/site/README.md)。

## 项目结构

```text
Tianmu/
├── src/                  视频业务后端
├── tianmu-front/         视频网站、作者助手与 Trace Studio
├── tianmu-agent/         独立 Agent 服务
├── materials/            视频素材与文件清单
├── deploy/               容器与部署配置
├── scripts/              测试、冒烟与发布检查
└── docs/                 专题文档与路线图
```

## 开发与测试

后端与 Agent：

```bash
mvn -B verify
mvn -B -f tianmu-agent/pom.xml verify
```

前端：

```bash
cd tianmu-front
npm ci
npm run test:agent
npm run build
```

[GitHub Actions](https://github.com/blackirlsama/Tianmu/actions/workflows/ci.yml) 检查前端、业务后端、Agent 与发布规范，并在隔离环境中运行数据库集成测试。测试不调用付费模型；完整验证流程见[贡献指南](CONTRIBUTING.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [前端开发](tianmu-front/README.md) | 页面、Trace Studio、代理与构建 |
| [Agent 开发](tianmu-agent/README.md) | 执行框架、模型、工具与配置 |
| [网站部署](deploy/site/README.md) | 完整网站与真实 Agent 接入 |
| [独立 Agent 部署](deploy/README.md) | 单独运行 Agent 与存储服务 |
| [部署参考](docs/DEPLOYMENT.md) | 环境与集成测试 |
| [项目路线图](docs/ROADMAP.md) | 开发方向与待完善能力 |

## 参与贡献

欢迎提交问题、改进体验、补充测试或完善文档。

1. 在 [Issue](https://github.com/blackirlsama/Tianmu/issues) 中说明问题或使用场景。
2. Fork 仓库，从最新 `master` 创建开发分支。
3. 完成修改与相关测试，提交 [Pull Request](https://github.com/blackirlsama/Tianmu/pulls)。

提交前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请通过 [SECURITY.md](SECURITY.md) 中的方式反馈。感谢每一位[贡献者](https://github.com/blackirlsama/Tianmu/graphs/contributors)，也欢迎用 Star 支持项目。

## 许可证

项目代码采用 [MIT License](LICENSE)。第三方组件与视频素材遵循各自的授权条款，详见 [NOTICE](NOTICE) 与[第三方说明](docs/THIRD_PARTY.md)。
