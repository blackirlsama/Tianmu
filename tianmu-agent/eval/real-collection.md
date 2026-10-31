# 场景 C：从真实采集到前端总览

目前代码中的工程 fixture、视频素材和单模型语义样例都不能代替同任务多模型实验。此流程不默认发起收费调用，也不自动生成“正确”标签。

## 一、先固定实验条件

1. 选择至少两个模型，记录服务商实际返回的模型名和版本。每个模型对应一个已配置的 Agent 实例／服务，主循环模型使用各自的 `HARNESS_MODEL`，其他预处理、工具、数据、提示词、预算与版本保持一致。
2. 不要为了实验修改正在服务用户的模型。使用独立实验实例和授权测试账号。准备与真实任务对应的数据快照和评测依据，避免在其他账号的视频上测试。
3. 使用相同输入设计 2～3 类任务，例如指标查询、反馈分类、报告生成。任务内重复相同样本，跨模型共享输入、数据版本、温度和预算，轮换模型顺序减小时序偏差；解释缓存、平台和样本限制。
4. 首轮配置模板是 **2 模型 × 3 任务 × 3 次重复＝18 次运行**，仅作为试采集。课程要求的 150 条消息或 100 轮对话要由实际记录核对；运行数不等于对话轮数。Token 也不是消息条数。

模型 Key 只在服务器私有配置中设置，认证 token 通过采集进程环境变量提供，不写入计划或仓库。默认本地免 Key Agent 不是真实模型服务；当前线上若仍返回模型未配置，应先由管理员完成真实模型配置，再采集。

## 二、已有真实运行：直接读入网页

`/analysis` 的“读取后端已采集运行”接受当前登录账号的 1～50 个运行 UUID，纯 GET 查询，不创建新模型请求。当前页面使用同源 `/api/agent`；服务必须已部署 `GET /runs/{runId}/trace`。

页面显示日志中实际模型名与主循环调用，拒绝 FAQ 无模型调用、错误运行 ID 和截断数据；模拟运行会明确标为演示，不算真实实验。任务来自后端 task.specification.type，若需要细分到完全相同的题目编号，请通过评测 JSON 补充更精确的 taskId、taskName 和条件。回答正确性仍为空，不能把成功终态替代人工／SQL 评分。

点击运行继续通过现有鉴权接口读取节点详情。网站尚未登录、Agent 未配置、运行过期或不属于当前账号时，只显示错误，不回退到模拟结果。

## 三、准备新一轮采集（此步不收费）

将 `collection-plan.example.json` 复制到忽略目录，例如 `var/real-evaluation/plan.json`，填写真实模型、已配置 Agent 地址、版本、相同任务与条件编号。模板里的 `REPLACE_...` 必须替换；两个地址是示意端口，不会自动启动服务。

每个目标的 `baseUrl` 必须以 `/api/agent` 结尾。远程地址只允许有有效证书的 HTTPS；本机 HTTP 仅允许回环地址，远程私有 Agent 可先使用 SSH 隧道。网页及采集脚本都不直接持有服务商 Key。`tokenEnv` 是网站登录凭据的环境变量名称，不是模型 Key。

```powershell
python scripts/collect_evaluation.py plan --config var/real-evaluation/plan.json
```

输出模型数、任务数、重复数和计划运行数，`callsCreated=0`。`ready=false` 表示配置占位符或登录态未齐全。计划不是价格报价：一次 Agent 运行可能产生多次模型请求。先根据实际模型价格、任务数量和预算确认费用，再执行。

## 四、显式采集（会调用配置好的模型）

由操作者将已有授权网站登录态放入 `TIANMU_EVAL_TOKEN_A` / `TIANMU_EVAL_TOKEN_B` 等环境变量；不要把 token 放到命令行、计划、Git 或报告。确认 `plan` 后显式运行：

```powershell
python scripts/collect_evaluation.py collect --config var/real-evaluation/plan.json --output var/real-evaluation/batch-001 --execute
```

- 每次调用创建一个新 session，公共任务提示一致，按重复轮次交替各模型；POST 使用独立 Idempotency-Key。
- 只读取实际任务状态、实际模型名、已结束 Span 和由服务端脱敏的输入输出。用量与耗时由现有 Trace 记录，不捏造未上报的 Token。
- 服务返回的主循环模型必须与配置 expectedModel 相同，FAQ 直答和未知来源不当作模型性能记录。`--allow-demo` 仅用于明确的免 Key 联调，输出始终带 `demo=true`，不可作为真实实验。
- 逐次保存 `run-index.json`、`captures/<runId>.json` 和累计 `dataset.json`；不覆盖已有输出目录。异常时保留已完成记录与运行 ID 并停止，不填充假数据。网络超时不代表远端任务已取消，不要盲目重发。
- 如果部分调用失败但 Trace 完整且模型身份可验证，保留实际 FAILED 终态。日志丢失、截断或模型身份无法验证时，该次标为采集不完整，不能悄悄删除后只报告成功率。

上传 `dataset.json` 到总览中的本地文件选择器，浏览器只在当前标签页解析，不上传服务端。点击“保存评测 JSON”可保存筛选范围的可重导入数据包；从后端直接 GET 得到的 JSON 默认只含详情引用，完整离线输入输出使用上述采集包或本地私有快照导出。

## 五、给真实结果评分

采集后默认全部 `evaluation.correct=null`。指标查询可以按统一 SQL 快照对账；反馈分类和报告需要独立人工标注、评分规则及证据。准备私有 `reviews.json`：

```json
{
  "reviews": [
    {
      "runId": "实际运行UUID",
      "correct": false,
      "basis": "按评分规则 v1：SQL 数量一致，但报告引用错误，判为不正确",
      "reviewerRef": "匿名评审员01",
      "reviewedAt": "2026-10-31T20:00:00+08:00"
    }
  ]
}
```

此格式是评分示例，不是已发生的评分。执行合并不会调用模型：

```powershell
python scripts/collect_evaluation.py review --dataset var/real-evaluation/batch-001/dataset.json --reviews var/real-evaluation/reviews.json --output var/real-evaluation/batch-001/reviewed.json
```

再次导入 `reviewed.json`，正确率热力图才有评分依据。不要用另一模型未经人工复核的分数充当独立人工验收。

## 六、已有 JSONL 和私有快照：离线打包

`run-index.json` 使用 `{"runs":[...]}`，每条包含 runId、taskId、taskName、expectedModel、modelVersion、conditionId、modelMode（来源已核对为 PROVIDER 时才填写）、可选 traceId / status / businessMode。这是一份采集来源记录，不能靠填写 PROVIDER 把模拟数据变成真实实验。

```powershell
python scripts/collect_evaluation.py export --index var/real-evaluation/run-index.json --trace var/real-evaluation/spans.jsonl --details var/real-evaluation/details --reviews var/real-evaluation/reviews.json --output var/real-evaluation/import-ready.json
```

每个详情先核对引用中的字节数和 SHA-256，再从私有文件读取。原始文件不改写。只有日志而没有快照也可导出，前端会显示缺失详情。会遮盖已知凭据字段、隐藏推理、邮箱和手机号，但规则脱敏并非万能；发布或提交前仍需人工检查数据许可和个人信息。

当前还没有选择实际模型、没有 Key、没有人工标签时，只能完成工具及接口联调，不能宣称真实多模型实验已经采集完成。
