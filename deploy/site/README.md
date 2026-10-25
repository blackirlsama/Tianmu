# 网站、业务后端与真实 Agent 的单机验收部署

这套叠加部署复用既有 `tianmu-staging_default` 网络中的 Redis、Kafka 和 ClickHouse，另建业务 MySQL 与独立的业务／Agent 数据库。旧演示数据库和对象卷保留。适用于当前单机验收环境；资源有限时需要交换空间，不能据此推断生产容量。

## 目录与构建

源码发布目录为 `/opt/tianmu-site/releases/RELEASE`，私有配置为 `/opt/tianmu-site/config`。前端构建产物放在发布目录的 `tianmu-front/dist/`。这些服务器路径中的真实凭据不应提交到仓库。

```sh
docker build -f deploy/Dockerfile.business -t tianmu-business:RELEASE .
docker build -f deploy/Dockerfile -t tianmu-agent:RELEASE .
docker build -f deploy/site/Dockerfile.elasticsearch -t tianmu-elasticsearch:8.18.1-ik .
docker build -f deploy/site/Dockerfile.minio -t tianmu-minio:2025-10-15 .
```

业务镜像构建会运行默认测试；外部数据库集成测试仍由原测试开关控制。Elasticsearch 安装匹配版本的 IK 插件，MinIO 使用固定官方源码版本构建。部署时记录实际镜像 digest / image ID。

## 初始化与启动

先将真实模型 Key 私密写入 `config/secrets/model-api-key`，权限 `0400`、所有者 `10001:10001`。再运行：

```sh
python3 deploy/site/prepare.py --config /opt/tianmu-site/config --release RELEASE
docker compose --env-file /opt/tianmu-site/config/release.env -f deploy/site/compose.yaml up -d business-mysql
# 等待 MySQL 就绪后，仅初始化本部署专用数据库。
python3 deploy/site/prepare.py --config /opt/tianmu-site/config --release RELEASE --initialize-db
docker compose --env-file /opt/tianmu-site/config/release.env -f deploy/site/compose.yaml up -d
```

`business-schema.sql` 是当前实体对应的新库初始化结构，不是既有业务库的升级迁移。`--initialize-db` 只创建验收账号，不创建验收视频或模拟弹幕；需要验收时请从网站正常上传测试视频并导入测试弹幕。该脚本不应指向其他业务库。验收账号密码仅写入私有 `acceptance.json`。

业务与 Agent 分别使用 `tianmu_site`、`tianmu_site_agent`；网站登录使用 Redis database 1。业务文件与 Agent 报告共用同一 MinIO 实例，报告和 Trace 明细放在私有 `tianmu-agent-artifacts` 桶。

Agent 与业务容器共享网络命名空间，使鉴权桥接保持 `http://127.0.0.1:8101/api`，满足桥接仅允许 loopback HTTP 或外部 HTTPS 的规则。**更换或重建业务容器时，要同时重建 Agent：**

```sh
docker compose --env-file /opt/tianmu-site/config/release.env -f deploy/site/compose.yaml stop agent business
docker compose --env-file /opt/tianmu-site/config/release.env -f deploy/site/compose.yaml up -d --no-deps --force-recreate business agent
```

对外仅开放 Nginx 80/443；业务 HTTP 和 Agent 健康检查映射到本机 18101/18202，数据库、MinIO、消息队列和 Trace 存储不映射公网端口。Nginx 拦截公网 `/api/internal/`。

## HTTPS 与续期

初始化先生成临时自签证书，正式访问使用 Let's Encrypt 的 IP `shortlived` 证书。证书与 ACME 账户保存在私有配置目录，Nginx 为 `/.well-known/acme-challenge/` 提供 webroot 验证。

```sh
docker run --rm --network host \
  -v /opt/tianmu-site/config/letsencrypt:/etc/letsencrypt \
  -v /opt/tianmu-site/config/acme-webroot:/var/www/acme \
  certbot/certbot:v5.4.0 certonly --non-interactive --agree-tos \
  --register-unsafely-without-email --preferred-profile shortlived \
  --webroot --webroot-path /var/www/acme --ip-address 120.53.45.251
sh deploy/site/renew-tls.sh /opt/tianmu-site/config
```

验证发布后，将 `/opt/tianmu-site/current` 指向该发布目录，安装 `tianmu-site-tls.service` 和 `tianmu-site-tls.timer`，启用 timer。它每天检查两次续期，更新证书并重载 Nginx。证书文件通过目录挂载，续期替换文件会被 Nginx 看到。

## 验收

使用真实网站密码登录，先通过网站上传专用测试视频并准备测试弹幕，再点击「分析弹幕」提交。验证实际模型生成、SSE 进度、HTML 图表、样本去重和刷新恢复。跨账号访问应使用隔离测试账号上传的视频验证。

按返回的 `runId` / `traceId` 查询 ClickHouse `tianmu_trace.trace_spans_current`，核对节点类型、父子关系、耗时、工具调用、模型 token 用量及私有明细引用。Trace 明细需通过带网站身份鉴权的接口读取，不应由 Nginx 直接公开对象桶。

`inspect_run.py --run-id UUID --output 私有目录` 可在服务器导出该验收账号的报告、span 和经过服务端脱敏的明细，并逐份校验 SHA-256。失败任务诊断可加 `--allow-failed`。导出脚本复用该验收账号现有的 Redis 登录会话，不打印凭据。

数据库事件约束通过 `V3__artifact_ready_event.sql` 增加 `ARTIFACT_READY`；既有迁移保持不变。报告保存后的通知、终态和重建后的回放由 `MysqlStorageIntegrationTest` 覆盖。可用 Dockerfile 的 `storage-verify` target 在独立的 `_it` 库和无密码测试 Redis 上运行；凭据通过 BuildKit `storage-it-env` secret 临时注入，不拷入镜像。不要将集成测试指向网站或 Agent 的正式库。

本次针对报告保存与事件回放使用 `--build-arg STORAGE_TESTS=MysqlStorageIntegrationTest#liveEvidenceAndHtmlReportSurviveReloadAndAreDeletedWithSession`。完整存储测试还含创建触发器的故障注入用例，需要专用测试实例提供相应权限；网站部署账号不需要获得这些权限。


## 视频内容理解

Agent 仅新增 `analyze_video_content`。视频内容从业务桥接接口按当前登录用户和 videoId 解析，拒绝任意媒体 URL。业务镜像需带 FFmpeg，Agent 镜像带字体包用于给采样帧拼图添加播放时间标签。短视频读取全片，长视频概览均匀采样最多12帧；显式细查区间最多120秒。目前单个源文件限制64MiB、时长最多1小时。采样不保证捕获每个瞬时事件。

MiniMax 配置写入服务器私有 `agent.env`：

```dotenv
VIDEO_CONTENT_ENABLED=true
VIDEO_MODEL_PROVIDER=minimax
VIDEO_MODEL_BASE_URL=https://api.minimaxi.com
VIDEO_MODEL=minimax-vlm-service
```

中国区使用 `api.minimaxi.com`，国际区使用 `api.minimax.io`，必须与 Key 区域一致。Key 放私有 `secrets/video-model-api-key`，由 Docker secret 和入口脚本注入 `VIDEO_MODEL_API_KEY`，不提交进仓库。MiniMax VLM 接口不返回具体模型版本时，`minimax-vlm-service` 只是服务标识，不声称使用了某个具体模型。参考 MiniMax 官方 Coding Plan MCP 的 `/v1/coding_plan/vlm` 实现。

也可选 `VIDEO_MODEL_PROVIDER=compatible` 并配置支持图片输入的 Chat Completions 服务。可选 `VIDEO_TRANSCRIPTION_MODEL` 只适用于同时支持 `/audio/transcriptions` 的服务；未配置时明确返回 `SAMPLED_FRAMES`，不声称识别音频。MiniMax 当前部署仅启用画面理解；音轨提取不等于音频理解。

工具返回的结构化事实沿用既有 evidence → 私有 MinIO 产物持久化。摘要缓存保存在 Agent MySQL 的 `agent_video_content_cache`，按租户、用户、视频源版本、区间、关注点和模型配置隔离，24小时过期；缓存命中前仍重新校验视频权限和源版本。原始帧在本次请求中使用，临时文件随后清理，不持久化帧图片，也不写入 LLM 历史。后台预处理任务、长期帧资产和全量播放时间直方图不在此版范围内。

弹幕抽样 occurrences.playbackMs 为原始播放位置（毫秒，可空），bucket 仍是发送墙钟时间分箱，不能混用。报告可传入同一视频的 video_evidence_id 添加服务端生成的视频内容模块。Trace 包含 `video.source`、`video.extract`、`video.model.vision`；模型未报告 token 时标为未报告，整体用量标为部分统计，不估算成实际值。
