# 场景 C：模型对比数据契约

打开 `/analysis`。文件只在当前标签页内存中解析，不上传、不保存至 localStorage。演示数据需要显式点击加载，且与非演示数据隔离。

## 推荐导入格式

```json
{
  "schemaVersion": "tianmu-evaluation/1",
  "title": "同任务多模型实验",
  "demo": false,
  "runs": [
    {
      "runId": "实际后端返回的UUID或独立实验运行编号",
      "taskId": "metric-query-001",
      "taskName": "指标查询",
      "model": "实际模型名称",
      "modelVersion": "具体版本",
      "conditionId": "prompt-v1-tools-v1-budget-v1-input-v1",
      "status": "SUCCEEDED",
      "durationMs": null,
      "totalTokens": null,
      "usageComplete": false,
      "toolCalls": null,
      "evaluation": {
        "correct": null,
        "basis": "人工标注标准或数据库对账规则"
      },
      "trace": { "spans": [], "details": {} }
    }
  ]
}
```

以上是未填写模板，不代表实验结果。页面也提供模板下载。`runs` 最多 5,000 条、单文件最多 30 MB。`trace.spans` 使用项目现有 TraceEnvelope，单条运行至多 2,000 节点；`trace.details` 可按 spanId 提供脱敏 `{input, output}`，格式与已有 Trace 导入一致。只提供运行汇总也可绘图；无 Trace 且 runId 非 UUID 时详情入口禁用，有 UUID 时可打开后端鉴权追踪。

也可导入 TraceEnvelope JSON 数组或 JSONL（最多 20,000 节点）。回放重复节点按 spanId 去重，关联同一 run.id 的 SSE Trace 不额外计入实验次数。普通日志通常缺少 taskId、实验条件和正确性标注，需要在评测 JSON 中补充，前端不会从成功终态猜测回答正确性。无需把视频原文件放进前端。

## 字段口径

| 字段 | 含义与缺失处理 |
| --- | --- |
| runId | 独立的一次运行编号，重复编号拒绝导入 |
| taskId / taskName | 相同输入任务使用相同编号；名称用于展示。未标注时单独列为“未标注任务” |
| model / modelVersion | 被比较的模型及版本。缺少声明时尝试读取主循环 llm_inference 的 model.name；多个模型则显示为组合，不能误作单一模型 |
| conditionId | 提示词、工具权限、输入、预算和环境一致的实验组编号；可单独筛选。不记录时提示可比性限制 |
| status | 执行终态，与回答是否正确分开；UNKNOWN 不按成功处理 |
| durationMs | 从创建到业务终态的端到端耗时，单位 ms。缺失不计入耗时统计 |
| totalTokens / usageComplete | 只用完整非负 Token 参与平均值和散点图。部分用量可在列表查看，缺失不填 0 |
| toolCalls | 工具尝试次数；有 Trace 时按 tool_call / TOOL 节点数推导，read_skill 子阶段不重复累加 |
| evaluation.correct | 明确的 boolean；null 为未评测。正确率分母仅为已评测运行 |
| evaluation.basis | 人工评分或对账标准；缺失时提示证据不足，不伪造依据 |
| errorType / hasError / fixRounds | 可选错误信息及修复次数；未提供时不捏造错误分类或修复指标 |
| demo | 合成数据标识；同一数据集禁止混合演示与非演示运行 |

## 交互联动

- 模型、任务、实验条件、执行状态、回答评测和搜索共同筛选；所有统计、图表、观察提示和导出使用同一筛选范围。
- 热力图点击单元格 → 筛选对应模型与任务；箱线图点击箱体 → 筛选模型；散点／箱线原始点 → 打开对应运行。
- 运行列表点击 ID → 查看运行级评测依据；点击“调用详情” → 现有 `/trace` 调用树、时间线、输入输出。返回总览保留本标签页中的数据和筛选。
- 后端运行可在 `/trace` 填写任务、比较模型、条件及评测标签后“加入对比”。标签只保存在当前会话，不写回后端。模拟后端会显式提示，不能代替真实模型实验。
- 汇总 CSV 导出当前筛选的运行级指标，不包含节点正文。完整 JSON、JSONL 和脱敏快照应保留在私有实验资料中；页面刷新后需重新导入。

## 图表与统计

热力图：模型 × 任务 × 已评测正确率，标注 n。耗时箱线图：线性插值分位数、箱体 Q1–Q3、中位线 Q2，须为 1.5×IQR 范围内的极值；所有原始运行另绘散点。关系散点图：x=秒、y=完整 Token，颜色=模型、形状=任务、点大小=工具次数（绘图大小上限映射 12 次），评测不正确用粉色边框。

“观察与核查线索”只给当前筛选的描述性统计及核查入口，不自动声称完成报告要求的五条实验发现。真实报告仍需要充分样本、统一实验条件、明确评测标准，以及至少两条一／二维关系、两条三维以上关系和一条异常或局限讨论。执行成功率不能替代正确率，不能将相关性解释为因果关系。
