import { reactive, watch } from 'vue'

// Private payloads live in this tab's memory only. No browser persistence or upload.
export const evaluationStore = reactive({ dataset: null, filters: { models: [], tasks: [], status: '', verdict: '', condition: '', search: '' } })
export function setEvaluationDataset(dataset) {
  evaluationStore.dataset = dataset
  resetEvaluationFilters()
}
export function resetEvaluationFilters() {
  Object.assign(evaluationStore.filters, { models: [], tasks: [], status: '', verdict: '', condition: '', search: '' })
}
export function addEvaluationRun(run) {
  const dataset = evaluationStore.dataset
  if (!dataset || dataset.demo !== run.demo) {
    setEvaluationDataset({ title: '当前会话运行', source: run.source, demo: run.demo, runs: [run] })
    return
  }
  const index = dataset.runs.findIndex((item) => item.key === run.key)
  if (index < 0) dataset.runs.push(run)
  else dataset.runs[index] = run
}
window.addEventListener('storage', (event) => {
  if (event.key === 'TIANMU_USER' || event.key === null) setEvaluationDataset(null)
})
export function bindEvaluationIdentity(userStore) {
  watch(() => userStore.user?.token, () => setEvaluationDataset(null), { flush: 'sync' })
}
