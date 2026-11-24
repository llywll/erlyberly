<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NButton, NSpace, NList, NListItem, NTag, NModal, useMessage, NEmpty } from 'naive-ui'
import { api } from '../api/client'
import { useNodeStore } from '../stores/node'
import TermViewer from './TermViewer.vue'

const store = useNodeStore()
const message = useMessage()

const reports = ref<any[]>([])
const loading = ref(false)
const detailShow = ref(false)
const detail = ref<any>(null)

async function refresh() {
  loading.value = true
  try {
    const r = await api.crashReports()
    reports.value = r.reports
    store.unreadCrashReports = 0
  } finally {
    loading.value = false
  }
}
onMounted(refresh)

async function clearAll() {
  await api.clearCrashReports()
  reports.value = []
  message.success('已删除所有报告')
}

function open(r: any) {
  detail.value = r
  detailShow.value = true
}

function fmtTime(s: string | null) {
  return s ? s.replace('T', ' ').split('.')[0] : ''
}
</script>

<template>
  <div style="display: flex; flex-direction: column; height: calc(100vh - 180px)">
    <n-space align="center" style="margin-bottom: 10px" :size="8">
      <n-tag :bordered="false" size="small">{{ reports.length }} 条报告</n-tag>
      <n-button size="small" :loading="loading" @click="refresh">刷新</n-button>
      <n-button size="small" type="warning" @click="clearAll">删除所有报告</n-button>
    </n-space>

    <div class="eb-scroll" style="flex: 1">
      <n-list v-if="reports.length" hoverable clickable bordered>
        <n-list-item v-for="(r, i) in reports" :key="i" @click="open(r)" style="cursor: pointer">
          <n-space align="center" :size="10">
            <n-tag type="error" :bordered="false" size="small">{{ r.errorClass }}</n-tag>
            <b>{{ r.registeredName || r.pid }}</b>
            <span style="color: var(--eb-text-secondary); font-size: 12px">{{ fmtTime(r.dateTime) }}</span>
            <span style="font-size: 12px; color: var(--eb-text-secondary)">
              初始调用: {{ r.initialCall?.v }}
            </span>
          </n-space>
        </n-list-item>
      </n-list>
      <n-empty v-else description="没有崩溃报告" style="margin-top: 60px" />
    </div>

    <!-- 详情（照搬 CrashReportView 三个 Tab 合并为分区展示） -->
    <n-modal v-model:show="detailShow">
      <div class="eb-card" style="width: 780px; max-width: 94vw; max-height: 82vh; overflow: auto">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px">
          <h3 class="eb-card-title" style="margin: 0">
            {{ detail?.errorClass }} — {{ detail?.registeredName || detail?.pid }}
          </h3>
          <n-button size="small" @click="detailShow = false">关闭</n-button>
        </div>
        <p class="eb-card-sub">{{ fmtTime(detail?.dateTime) }}</p>
        <template v-if="detail">
          <div style="font-weight: 600; margin: 8px 0 4px">错误原因</div>
          <div style="max-height: 220px; display: flex; flex-direction: column">
            <TermViewer :node="detail.errorReason" />
          </div>
          <div style="font-weight: 600; margin: 12px 0 4px">初始调用</div>
          <div v-if="detail.initialCall" style="max-height: 180px; display: flex; flex-direction: column">
            <TermViewer :node="detail.initialCall" />
          </div>
          <div style="font-weight: 600; margin: 12px 0 4px">崩溃报告术语</div>
          <div style="max-height: 260px; display: flex; flex-direction: column">
            <TermViewer :node="detail.props" />
          </div>
        </template>
      </div>
    </n-modal>
  </div>
</template>
