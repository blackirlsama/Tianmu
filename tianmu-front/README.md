# 天幕前端

Vue 3 视频平台与天幕运营助手，与仓库根目录的业务后端、`tianmu-agent/` 协作。

## Trace Studio：后端运行接入

安装依赖并运行 `npm run serve` 后，打开开发服务器的 `/trace`（默认 `http://localhost:8080/trace`）。页面默认等待后端连接，不会自动用演示数据替代读取失败的运行。

### 接入实际运行

1. 部署或本地启动包含 `GET /api/agent/runs/{runId}/trace` 的最新 Agent 后端。
2. 前端 `.env.local` 中设置 `TIANMU_AGENT_PROXY_TARGET=http://你的Agent地址:8201`，修改后重启 `npm run serve`。生产环境通过已有 Nginx 同源代理 `/api/agent/`。
3. 正常登录天幕网站，在 Trace 页面选择“使用天幕网站登录态”，输入该账号的运行 ID 加载。运营助手每次回复的“查看运行追踪”也会携带 runId 打开页面。
4. 页面轮询读取实际已结束 Span；点击节点后，通过已有的鉴权 `trace-payloads` 接口按需加载脱敏输入输出。身份变化、切换来源和离开页面时会停止请求并清空详情缓存。

### 免 Key 本地联调

在仓库根目录执行（Java 17）：

```powershell
.\mvnw.cmd -B -f tianmu-agent/pom.xml package
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
```

保持前端代理指向 `http://127.0.0.1:8201`。在 `/trace` 明确选择“本地免 Key 联调（demo-alice）”，点击“执行并追踪”。这会发出实际 HTTP 请求、创建后端任务并显示本次执行的真实日志，但默认模型和业务数据仍是模拟适配器，不计入场景 C 的真实模型评测。连接真实模型和业务数据需要队员在服务器配置 `deepseek,business,persistent` 等 Profile；模型密钥只放服务端。

### 数据来源与边界

- 深色三栏布局：运行信息、可缩放平移的调用图、节点详情。
- 点击调用图、节点列表或时间线中的节点，联动查看输入、输出、耗时、状态、Token 和原始属性。
- 下拉切换正常完成、工具超时两份**合成演示日志**；演示数据不作为场景 C 的实验结果。
- 支持导入 Tianmu TraceEnvelope JSON 数组、JSONL，以及 `{ "spans": [...], "details": { "spanId": { "input": ..., "output": ... } } }` 格式。日志在浏览器本地解析，不上传。
- 原始 JSONL 只有元数据和私有详情引用时，页面明确显示“未提供”，不会加载其他运行的演示输入输出。可用“下载示例日志”查看包含详情的格式。
- 后端查询近期本实例已结束 Span（上限 20,000，保留 1 小时），每次返回至多 2,000 节点；file 模式可回读受轮转限制的文件归档。Kafka/OTLP 模式近期运行也可查询，跨实例和重启后的 Kafka 历史需另接 ClickHouse 查询适配器。无记录、截断或缺失详情均明确展示。
- 运行级 Token 使用汇总口径，不与子节点用量重复相加；缺失 Token 不记为 0，OTel `UNSET` 不记为业务成功。

核心代码：`src/views/trace/TraceWorkspace.vue`、`src/components/trace/` 和 `src/trace/`。数据解析检查包含在 `npm run test:agent` 中。

## 启动

使用 Node.js 20+ 和 npm：

```bash
cd tianmu-front
npm ci
npm run serve
```

视频详情页右侧的「和助手聊聊」打开运营助手抽屉，复用网站登录凭证并自动携带当前 `videoId`。主界面使用连续对话，查询、澄清、执行进度以及报告预览和下载都在消息中展示。普通提问不自动附加生成报告的要求；页面标题不会作为查询条件注入。首页导航和创作中心不展示助手入口；`/agent` 保留为可独立打开的兼容页面。

对话通过 `sessionId` 连续衔接；抽屉关闭后任务继续执行，重新打开保留当前消息。`assistantRunId` 查询参数可在刷新视频页面时恢复任务。输入框 Enter 发送、Shift + Enter 换行，并避开输入法组词回车。当前业务能力由后端决定，前端不伪造数据或工具结果。

创建任务使用 `{ message, context: { videoId }, sessionId?, replyToRunId? }`。`message` 保留用户原话，视频选择单独传递，避免影响 FAQ 和意图。通用聊天和运营建议可直接回复；数据查询与报告继续由后端校验授权、条件和证据。数据口径与更新时间通过 `answer.dataNotes` 放入折叠的“数据说明”，不重复拼进正文。

最近任务只在当前浏览器按登录用户保存最多 30 条索引，不保存 token 或报告正文。打开任务时会重新向后端鉴权查询；清除浏览器数据后索引会消失。任务和报告是否仍可读取取决于后端保留时间。

## 联调配置

将 `.env.example` 复制为 `.env.local` 按需修改，并重启开发服务器：

| 配置 | 默认值 | 用途 |
| --- | --- | --- |
| `TIANMU_API_PROXY_TARGET` | `http://127.0.0.1:8101` | `/api` 转发至业务后端，保留 `/api` 前缀 |
| `TIANMU_AGENT_PROXY_TARGET` | `http://127.0.0.1:8201` | `/api/agent` 优先转发至 Agent，支持 SSE |
| `VUE_APP_DANMU_WS_BASE_URL` | 开发时为当前主机 `9101`；生产时使用网站同源地址 | 完整弹幕 WebSocket 基址，需带 `/ws/bulletScreen/` 尾路径 |

真实网站登录需配合 Agent 的 `business` 身份适配器使用，后端继续校验用户、视频范围和报告归属。默认 `demo` Agent 仅接受自己的 demo 凭证，不能校验网站 JWT。模型密钥和对象存储密钥配置在服务端。

工作台调用现有接口：`POST /api/agent/runs`、`GET /api/agent/runs/{id}`、`GET /api/agent/runs/{id}/events`、`POST /api/agent/runs/{id}/cancel`、`GET /api/agent/runs/{id}/reports/{artifactId}`。SSE 使用带 Authorization 的 fetch，并以任务快照轮询兜底；报告通过鉴权后放入禁用脚本的 iframe。用户页面只显示执行进度，不加载 Trace 原始输入输出。

## 构建与检查

```bash
npm run test:agent
npm run build
```

产物输出到 `dist/`。`node_modules/` 和 `dist/` 不提交到 Git。

## 生产接入

Vue 开发代理不进入生产产物。生产 Web 服务器需分别代理 `/api/agent/` 和 `/api/`，并为 `/agent` 配置 SPA 回退。可参考 [`deploy/nginx.frontend.conf`](../deploy/nginx.frontend.conf)：复制构建产物到 Nginx 静态目录，将 upstream 地址改成实际业务和 Agent 地址。

SSE 代理需关闭缓存和缓冲，读取超时应大于服务端 60 秒的订阅周期。生产 WebSocket 默认使用网站同源的 `/ws/bulletScreen/`，跟随页面选择 `ws` 或 `wss`。连接独立 WebSocket 域名时，可在构建前设置 `VUE_APP_DANMU_WS_BASE_URL`。后端代理地址改变不需要重新编译；`VUE_APP_` 变量在构建时写入产物。
