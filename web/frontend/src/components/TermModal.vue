<script setup lang="ts">
import { computed } from 'vue'
import { NModal, NButton, NSpace, useMessage } from 'naive-ui'
import TermViewer from './TermViewer.vue'

const props = defineProps<{ show: boolean; title: string; node: any; refreshable?: boolean }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void; (e: 'refresh'): void }>()
const message = useMessage()

const rawText = computed(() => props.node?.v ?? '')

async function copyTerm() {
  try {
    await navigator.clipboard.writeText(rawText.value)
    message.success('已复制原始数据')
  } catch {
    message.error('复制失败')
  }
}
</script>

<template>
  <n-modal :show="show" @update:show="(v: boolean) => emit('update:show', v)">
    <div class="eb-card" style="width: 680px; max-width: 92vw; max-height: 78vh; display: flex; flex-direction: column">
      <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px">
        <h3 class="eb-card-title" style="margin: 0">{{ title }}</h3>
        <n-space>
          <n-button v-if="refreshable" size="small" @click="emit('refresh')">刷新</n-button>
          <n-button size="small" @click="copyTerm">复制原始数据</n-button>
          <n-button size="small" @click="emit('update:show', false)">关闭</n-button>
        </n-space>
      </div>
      <TermViewer :node="node" />
    </div>
  </n-modal>
</template>
