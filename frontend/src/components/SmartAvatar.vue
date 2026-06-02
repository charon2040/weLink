<template>
  <el-avatar :size="size" :src="src || null" :style="{ background: bg, color: '#fff', fontWeight: 500 }">
    <span v-if="!src">{{ initial }}</span>
  </el-avatar>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  src: { type: String, default: '' },
  name: { type: String, default: '' },
  size: { type: Number, default: 40 },
  // 'private' / 'group' — 群默认背景固定橙色 + 显示"群"字
  type: { type: String, default: 'private' }
})

const AVATAR_COLORS = [
  '#5b8def', '#69c0ff', '#73d13d', '#ffc53d', '#ff7a45',
  '#ff85c0', '#b37feb', '#36cfc9', '#9254de', '#ffa940'
]

const bg = computed(() => {
  if (props.src) return 'transparent'
  if (props.type === 'group') return 'linear-gradient(135deg,#ffa940,#fa8c16)'
  const n = props.name || ''
  if (!n) return '#909399'
  let h = 0
  for (let i = 0; i < n.length; i++) h = ((h << 5) - h + n.charCodeAt(i)) | 0
  return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length]
})

const initial = computed(() => {
  if (props.type === 'group') return '群'
  const n = props.name || ''
  return (n.charAt(0) || '?').toUpperCase()
})
</script>
