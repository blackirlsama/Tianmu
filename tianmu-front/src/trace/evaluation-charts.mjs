import { accuracyCells, modelSummaries } from './evaluation-data.mjs'

export const MODEL_COLORS = ['#6ba7ff', '#64d4bc', '#ba9aff', '#efb676', '#ee91b0', '#79cce6']
export const TASK_SYMBOLS = ['circle', 'diamond', 'triangle', 'rect', 'roundRect', 'pin']
const escape = (text) => String(text).replace(/[&<>"']/g, (char) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]))
const axis = { axisLine: { lineStyle: { color: '#31425a' } }, axisTick: { show: false }, axisLabel: { color: '#8198b7', fontSize: 10 }, splitLine: { lineStyle: { color: '#26354a', type: 'dashed' } }, nameTextStyle: { color: '#728baa', fontSize: 10 } }
const tooltip = { backgroundColor: '#152237', borderColor: '#3c5475', textStyle: { color: '#c9d9ef', fontSize: 11 }, extraCssText: 'max-width:320px;white-space:normal;line-height:1.8' }
const base = { animationDuration: 250, textStyle: { fontFamily: 'Inter, Microsoft YaHei, sans-serif' }, aria: { enabled: true }, backgroundColor: 'transparent' }
export const modelColor = (model, allModels) => MODEL_COLORS[Math.max(0, allModels.indexOf(model)) % MODEL_COLORS.length]

export function heatmapOption(runs) {
  const { models, tasks, cells } = accuracyCells(runs)
  const names = tasks.map((task) => runs.find((run) => run.taskId === task)?.taskName || task)
  return { ...base, tooltip: { ...tooltip, formatter: (p) => `${escape(p.data.model)} · ${escape(p.data.taskName)}<br>正确率：${p.data.accuracy == null ? '未评测' : (p.data.accuracy * 100).toFixed(1) + '%'}<br>正确 / 已评测：${p.data.correct} / ${p.data.evaluated}<br>运行样本：${p.data.count}` },
    grid: { left: 105, right: 20, top: 12, bottom: 70 },
    xAxis: { ...axis, type: 'category', data: names, splitArea: { show: false }, axisLabel: { ...axis.axisLabel, interval: 0, width: 105, overflow: 'truncate' } },
    yAxis: { ...axis, type: 'category', data: models, inverse: true, axisLabel: { ...axis.axisLabel, width: 92, overflow: 'truncate' } },
    visualMap: { min: 0, max: 100, calculable: false, orient: 'horizontal', left: 'center', bottom: 0, itemHeight: 150, itemWidth: 8, text: ['100%', '0%'], textStyle: { color: '#7b94b5', fontSize: 9 }, inRange: { color: ['#253451', '#3868a3', '#62c9ba'] }, outOfRange: { color: '#1d293b' } },
    series: [{ type: 'heatmap', data: cells.map((cell) => ({ ...cell, taskName: names[cell.x], value: [cell.x, cell.y, cell.accuracy == null ? -1 : cell.accuracy * 100] })),
      label: { show: true, color: '#e2edf9', fontSize: 11, formatter: (p) => p.data.accuracy == null ? `未评测\nn=${p.data.count}` : `${(p.data.accuracy * 100).toFixed(0)}%\nn=${p.data.evaluated}` },
      itemStyle: { borderWidth: 5, borderColor: '#111c2c', borderRadius: 7 }, emphasis: { itemStyle: { borderColor: '#91c6ef', borderWidth: 2 } } }] }
}

export function boxplotOption(runs, allModels) {
  const groups = modelSummaries(runs)
  return { ...base, tooltip: { ...tooltip, formatter: (p) => p.seriesType === 'scatter' ? runTooltip(p.data.run) : `${escape(p.data.model)}<br>耗时样本 n=${p.data.count}<br>下须 / Q1 / 中位数 / Q3 / 上须（s）<br>${p.data.value.map((n) => typeof n === 'number' ? n.toFixed(2) : '未提供').join(' / ')}` },
    grid: { left: 48, right: 20, top: 35, bottom: 38 },
    xAxis: { ...axis, type: 'category', data: groups.map((g) => g.model), axisLabel: { ...axis.axisLabel, interval: 0, width: 110, overflow: 'truncate' } },
    yAxis: { ...axis, type: 'value', name: '端到端耗时 / s' },
    series: [{ name: '箱线统计', type: 'boxplot', boxWidth: [18, 44], data: groups.map((g) => ({ model: g.model, count: g.durationCount, value: g.box ? g.box.values.map((v) => v / 1000) : ['-', '-', '-', '-', '-'], itemStyle: { color: modelColor(g.model, allModels) + '30', borderColor: modelColor(g.model, allModels), borderWidth: 1.5 } })) },
      { name: '逐次运行', type: 'scatter', symbolSize: 6, z: 4, data: runs.filter((r) => r.durationMs != null).map((run) => ({ value: [groups.findIndex((g) => g.model === run.modelLabel), run.durationMs / 1000], run, itemStyle: { color: modelColor(run.modelLabel, allModels), opacity: 0.7 } })) }] }
}

function runTooltip(run) {
  return `${escape(run.runId)}<br>${escape(run.modelLabel)} · ${escape(run.taskName)}<br>耗时：${run.durationMs == null ? '未提供' : (run.durationMs / 1000).toFixed(2) + ' s'}<br>Token：${run.totalTokens ?? '未提供'}${run.usageComplete ? '' : '（部分 / 未知）'}<br>工具调用：${run.toolCalls ?? '未提供'}<br>评测：${run.correct == null ? '未评测' : run.correct ? '正确' : '不正确'}<br>点击查看调用详情`
}

export function scatterOption(runs, allModels, allTasks) {
  const models = [...new Set(runs.map((run) => run.modelLabel))]
  return { ...base, tooltip: { ...tooltip, formatter: (p) => runTooltip(p.data.run) },
    grid: { left: 54, right: 24, top: 40, bottom: 42 },
    xAxis: { ...axis, type: 'value', name: '耗时 / s', nameLocation: 'middle', nameGap: 27 },
    yAxis: { ...axis, type: 'value', name: '完整 Token / 个' },
    series: models.map((model) => ({ type: 'scatter', name: model, symbolSize: (value, p) => 9 + Math.min(p.data.run.toolCalls || 0, 12) * 1.5,
      data: runs.filter((run) => run.modelLabel === model && run.durationMs != null && run.totalTokens != null && run.usageComplete).map((run) => ({
        value: [run.durationMs / 1000, run.totalTokens], run, symbol: TASK_SYMBOLS[Math.max(0, allTasks.indexOf(run.taskId)) % TASK_SYMBOLS.length],
        itemStyle: { color: modelColor(model, allModels), opacity: 0.8, borderWidth: run.correct === false ? 2 : 0.8, borderColor: run.correct === false ? '#fa9caa' : '#d6e9ff55' },
      })), emphasis: { scale: 1.35, itemStyle: { opacity: 1, shadowBlur: 12, shadowColor: modelColor(model, allModels) } } })) }
}
