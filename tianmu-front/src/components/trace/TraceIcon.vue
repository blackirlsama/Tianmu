<script setup>
/* global defineProps */
defineProps({ name: { type: String, default: 'trace' }, size: { type: [Number, String], default: 18 } })
const paths = {
  trace: 'M5 5h4v4H5z M15 15h4v4h-4z M7 9v6h8 M15 5h4v4h-4z M9 7h6',
  model: 'M9 3h6l4 4v10l-4 4H9l-4-4V7z M9 9h6v6H9z M12 1v2 M12 21v2 M1 12h4 M19 12h4',
  tool: 'm14 6 4-4 4 4-4 4 M3 21l12-12 M4 3l5 5 M3 4l2-2 4 4-2 2',
  stage: 'M5 5h14v14H5z M9 9h6 M9 13h4',
  clock: 'M12 8v5l3 2 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  token: 'm12 3 8 4v10l-8 4-8-4V7z M4 7l8 4 8-4 M12 11v10',
  chevron: 'm9 5 7 7-7 7',
  down: 'm6 9 6 6 6-6',
  upload: 'M12 16V3 M7 8l5-5 5 5 M4 15v5h16v-5',
  download: 'M12 3v13 M7 11l5 5 5-5 M4 16v5h16v-5',
  copy: 'M9 9h11v11H9z M15 9V3H3v12h6',
  check: 'm5 12 4 4L19 6',
  plus: 'M12 5v14 M5 12h14',
  minus: 'M5 12h14',
  fit: 'M8 3H3v5 M16 3h5v5 M3 16v5h5 M21 16v5h-5 M8 8h8v8H8z',
  info: 'M12 11v6 M12 7h.01 M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0',
  arrow: 'M4 12h16 M14 6l6 6-6 6',
  list: 'M9 6h12 M9 12h12 M9 18h12 M3 6h1 M3 12h1 M3 18h1',
}
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.stage" /></svg>
</template>
