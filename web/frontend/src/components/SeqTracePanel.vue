<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { NDataTable, NButton, NSpace, NModal, useMessage, NTag, NEmpty } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api } from '../api/client'
import TermTree from './TermTree.vue'

const message = useMessage()

const logs = ref<any[]>([])
const polling = ref(false)
let timer: number | null = null

const columns: DataTableColumns<any> = [
  { title: 'Msg Type', key: 'msgType', width: 90 },
  { title: 'Timestamp', key: 'timestamp', width: 160 },
  { title: 'Serial', key: 'serial', width: 90 },
  { title: 'From', key: 'from', width: 130, ellipsis: true },
  { title: 'To', key: 'to', width: 130, ellipsis: true },
  { title: 'Message', key: 'message', ellipsis: { tooltip: true }, render: r => r.message?.v ?? '' }
]

async function refresh() {
  try {
    const r = await api.seqTraceLogs()
    logs.value = r.logs
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  }
}

function togglePoll() {
  polling.value = !polling.value
  if (polling.value) {
    refresh()
    timer = window.setInterval(refresh, 1000)
    message.success('开始轮询顺序跟踪日志')
  } else if (timer) {
    window.clearInterval(timer)
    timer = null
  }
}

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})

const detailShow = ref(false)
const detailNode = ref<any>(null)

function openDetail(row: any) {
  detailNode.value = row.message
  detailShow.value = true
}
</script>

<template>
  <div style="display: flex; flex-direction: column; height: 100%">
    <n-space align="center" style="margin-bottom: 10px" :size="8">
      <n-tag :bordered="false" size="small">{{ logs.length }} 条</n-tag>
      <n-tag type="warning" :bordered="false" size="small">实验性</n-tag>
      <n-button size="small" @click="refresh">刷新</n-button>
      <n-button size="small" :type="polling ? 'warning' : 'primary'" @click="togglePoll">
        {{ polling ? '停止轮询' : '开始轮询' }}
      </n-button>
    </n-space>

    <n-data-table
      v-if="logs.length"
      :columns="columns"
      :data="logs"
      flex-height
      style="flex: 1; min-height: 0"
      size="small"
      :row-props="(row: any) => ({
        style: 'cursor: pointer',
        onDblclick: () => openDetail(row)
      })"
    />
    <n-empty description="无顺序跟踪日志 — 在「模块与函数」页右键函数选择顺序跟踪启动" style="margin-top: 60px" />

    <n-modal v-model:show="detailShow">
      <div class="eb-card" style="width: 640px; max-width: 92vw; max-height: 70vh; overflow: auto">
        <div style="display: flex; justify-content: space-between">
          <h3 class="eb-card-title">消息详情</h3>
          <n-button size="small" @click="detailShow = false">关闭</n-button>
        </div>
        <TermTree v-if="detailNode" :node="detailNode" style="font-size: 13px" />
      </div>
    </n-modal>
  </div>
</template>
