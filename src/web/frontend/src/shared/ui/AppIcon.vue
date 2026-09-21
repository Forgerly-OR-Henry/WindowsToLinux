<script setup lang="ts">
defineProps<{ name: string }>()
const paths: Record<string, string> = {
  deploy: 'M12 3v12m-4-4 4 4 4-4M4 15v5h16v-5',
  applications: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  servers: 'M3 3h18v7H3zM3 14h18v7H3zM6 6h2M6 17h2',
  ai: 'm12 2 3 7 7 3-7 3-3 7-3-7-7-3 7-3z',
  backup: 'M4 7v13h16V7M2 3h20v4H2zM9 11h6',
  tasks: 'M8 5h13M8 12h13M8 19h13M3 5h1M3 12h1M3 19h1',
  settings: 'M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1zM9 12a3 3 0 1 0 6 0 3 3 0 0 0-6 0',
  menu: 'M4 6h16M4 12h16M4 18h16', plus: 'M12 5v14M5 12h14', close: 'm6 6 12 12M6 18 18 6',
  arrow: 'M5 12h14m-5-5 5 5-5 5', folder: 'M3 6h7l2 2h9v12H3zM3 6V4h6l2 2', refresh: 'M20 7v5h-5M4 17v-5h5M5 7a8 8 0 0 1 13-2l2 3M4 16l2 3a8 8 0 0 0 13-2',
}
</script>
<template><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] ?? paths.applications" /></svg></template>
