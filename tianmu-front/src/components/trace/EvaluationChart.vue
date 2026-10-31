<script setup>
/* global defineProps, defineEmits */
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { init, use } from 'echarts/core'
import { HeatmapChart, BoxplotChart, ScatterChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, VisualMapComponent, AriaComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
use([HeatmapChart, BoxplotChart, ScatterChart, GridComponent, TooltipComponent, VisualMapComponent, AriaComponent, CanvasRenderer])
const props = defineProps({ option: { type: Object, required: true }, label: String })
const emit = defineEmits(['select'])
const element = ref(null)
let chart, observer
onMounted(() => {
  chart = init(element.value)
  chart.setOption(props.option)
  chart.on('click', (event) => emit('select', { type: event.seriesType, data: event.data }))
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(element.value)
})
watch(() => props.option, (option) => chart?.setOption(option, { notMerge: true }))
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose(); chart = null })
</script>

<template><div ref="element" class="evaluation-chart" role="img" :aria-label="label" /></template>
<style scoped>.evaluation-chart{width:100%;height:282px;min-width:0}</style>
