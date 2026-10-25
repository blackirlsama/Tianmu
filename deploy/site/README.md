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

`business-schema.sql` 是当前实体对应的新库初始化结构，不是既有业务库的升级迁移。`--initialize-db` 会创建验收账号、两个不同归属的视频和 videoId=1001 的 2,400 条模拟弹幕，并刷新这些专用模拟弹幕的时间分布；不应指向其他业务库。验收账号密码仅写入私有 `acceptance.json`。

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

使用真实网站密码登录，打开视频 1001 的详情页，点击「分析弹幕」，选择私有 `acceptance.json` 中记录的模拟数据日期并提交。验证实际模型生成、SSE 进度、HTML 图表、样本去重、刷新恢复以及视频 1002 的跨账号拒绝。

按返回的 `runId` / `traceId` 查询 ClickHouse `tianmu_trace.trace_spans_current`，核对节点类型、父子关系、耗时、工具调用、模型 token 用量及私有明细引用。Trace 明细需通过带网站身份鉴权的接口读取，不应由 Nginx 直接公开对象桶。

`inspect_run.py --run-id UUID --output 私有目录` 可在服务器导出该验收账号的报告、span 和经过服务端脱敏的明细，并逐份校验 SHA-256。失败任务诊断可加 `--allow-failed`。导出脚本复用该验收账号现有的 Redis 登录会话，不打印凭据。

数据库事件约束通过 `V3__artifact_ready_event.sql` 增加 `ARTIFACT_READY`；既有迁移保持不变。报告保存后的通知、终态和重建后的回放由 `MysqlStorageIntegrationTest` 覆盖。可用 Dockerfile 的 `storage-verify` target 在独立的 `_it` 库和无密码测试 Redis 上运行；凭据通过 BuildKit `storage-it-env` secret 临时注入，不拷入镜像。不要将集成测试指向网站或 Agent 的正式库。

本次针对报告保存与事件回放使用 `--build-arg STORAGE_TESTS=MysqlStorageIntegrationTest#liveEvidenceAndHtmlReportSurviveReloadAndAreDeletedWithSession`。完整存储测试还含创建触发器的故障注入用例，需要专用测试实例提供相应权限；网站部署账号不需要获得这些权限。
