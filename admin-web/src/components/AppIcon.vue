<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.grid" /></svg>
</template>
<script setup>
defineProps({ name: { type: String, default: 'grid' }, size: { type: [Number, String], default: 20 } })
const paths = {
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  orders: 'M7 3h10l3 4v14H4V7l3-4z M4 7h16 M9 11h6 M9 15h6',
  riders: 'M5 15a4 4 0 1 0 0 .01 M19 15a4 4 0 1 0 0 .01 M5 15l5-8 5 8H5 M9 7h4 M15 4h3l1 11',
  wallet: 'M20 8V5H5a2 2 0 0 0 0 4h16v11H5a2 2 0 0 1-2-2V7 M21 12h-6v5h6 M17 14.5h.01',
  withdrawal: 'M5 3h14v18H5z M8 7h8 M8 11h4 M9 16l2 2 5-5',
  marketing: 'M4 9h4l12-5v16L8 15H4V9z M8 15l2 6H6l-2-6 M8 9v6',
  content: 'M21 11a9 9 0 0 1-9 9H7l-5 2 2-5v-6a9 9 0 0 1 17 0z M8 10h8 M8 14h5',
  shield: 'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3z M8 12l3 3 5-6',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1 1-3z',
  users: 'M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M2 21v-3a6 6 0 0 1 12 0v3 M16 4a4 4 0 0 1 0 8 M18 15a5 5 0 0 1 4 5v1',
  arrow: 'M4 12h16 M14 6l6 6-6 6',
  refresh: 'M20 7v5h-5 M20 12a8 8 0 1 0-2 6',
  logout: 'M9 3H4v18h5 M9 12h12 M17 8l4 4-4 4',
  menu: 'M4 6h16 M4 12h16 M4 18h16',
  close: 'M5 5l14 14 M5 19L19 5',
  plus: 'M12 4v16 M4 12h16',
  search: 'M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15 M16 16l5 5',
  check: 'M5 12l4 4L19 6',
  clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v5l3 2',
  spark: 'M12 2l2.5 7.5L22 12l-7.5 2.5L12 22l-2.5-7.5L2 12l7.5-2.5L12 2z',
  lock: 'M7 10V7a5 5 0 0 1 10 0v3 M5 10h14v11H5z M12 14v3'
}
</script>
