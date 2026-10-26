# 第一轮：工程基线与真实质量标注

这里沿用 JUnit 参数化测试，不增加评测服务或自定义执行框架。

## 文件

- `scenarios.json`：24条业务场景清单，均仍为 draft；不是已通过的真实模型问题集。
- `engineering-cases.json`：其中7条场景的工程 fixture 绑定，逐条声明实际覆盖和未评测内容。
- `manual-case.example.json`：真实素材的标注模板。没有绑定授权素材、数据版本和人工/SQL 对照时，保持 draft。
- `baselines/engineering-v1.json`：2026-10-26 实际运行并核对源码指纹的首份参考结果，7个工程绑定通过；不是业务语义质量成绩。
- `baselines/real-video-v1.md`：服务器现有版本的一次真实视频观测；素材与 SQL 已核对，内容由助手预标注，等待人工复核，不作为候选代码的在线验收。
- `baselines/real-video-v2.md`：架构迭代版本上线后的真实验收；功能链路通过，记录了视频连续运动推断问题，语义状态未通过人工验收。
- `../src/test/java/com/tianmu/agent/evaluation/EvaluationBaselineTest.java`：真实 HTTP Controller/任务执行/SSE 链路中的确定性回归。

## 运行

仓库根目录执行，使用 JDK 17 与现有 Maven：

```sh
mvn -B -ntp -f tianmu-agent/pom.xml -Dtest=EvaluationBaselineTest test
```

或通过原有 `mvn -B -ntp -f tianmu-agent/pom.xml verify` 一起运行。默认不需要模型 Key、MySQL、Redis 或测试视频，不调用付费模型。测试强制使用 demo profile，并校验身份作用域与绑定声明一致。

报告生成在 `tianmu-agent/target/evaluation/engineering-baseline.json`。CI 在 Agent job 中留存该文件14天，失败用例也会记录。若编译或 Spring 初始化失败导致报告不存在，应以 CI/JUnit 失败为准，不能沿用上一次报告作为成功结果。即使这7条通过，其他回归失败时整个候选基线仍不合格。

## 实际验证范围

7个绑定覆盖：明确日期的数量、空结果、未指定日期时默认过去7天、页面视频上下文、越权查询、跨账号/跨任务证据隔离，以及受控查询在途取消。终态请求检查 SSE 唯一 DONE。`metric_missing_date` 保留历史场景 ID，当前行为改为 `default_range`；首份 engineering-v1 历史结果不改写。

数字 fixture 只对测试生效：默认值128，评测日的前两天为0。真实 query 适配器通过 `@Primary` 的测试 fixture 替换，生产代码和真实数据库不增加这些分支。日期按本次报告的 `calendar_anchor`（Asia/Shanghai）绑定，避免固定日期在30天窗口外失效。

每条运行记录包含输入、检查范围、未评测项、实际任务条件、终态、trace ID、编排模型次数和耗时。报告保存场景/绑定/测试实现/配置/资源索引的 SHA-256，并记录生产源码/资源/POM 的完整树指纹及 Java/操作系统版本，便于判断两次结果是否来自同一候选基线。耗时是本次 fixture 运行的观测，不是生产容量结论。

`semantic_quality=NOT_ASSESSED`、`provider_usage_state=NOT_APPLICABLE_SCRIPTED_MODEL`：这些测试不衡量真实模型的理解或建议质量，也不把脚本模型的调用次数当成供应商 Token。`passed_checks` 指通过的工程绑定数，不是24条业务场景的完整通过数。

暂未在这7条中执行的项目包括：真实 SQL 对账、持久化澄清续接、重启恢复、排队取消、远端模型取消、视频时间定位、采样偏差解释、完整报告语义和建议有效性。不能通过改写场景状态把这些项目算作已验收。

## 真实样例的下一步

使用模板为场景绑定专用测试素材，至少记录：内容哈希/版本、测试账号作用域引用、数据快照时间与口径、问题原文、正确事实或 SQL 对照、可核对片段时间戳、允许误差、禁止结论和人工复核状态。

真实素材、账号登录凭据与用户原始数据不提交到公共仓库；仓库保存脱敏引用和必要的标注。只有真实运行与复核完成后，才新增独立的真实质量结果，不能覆盖工程 fixture 报告。尚未设置任何自动付费评测入口。
