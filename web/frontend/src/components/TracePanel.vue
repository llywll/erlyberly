<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  NDataTable, NInput, NButton, NSpace, NModal, NTag, NDropdown, useMessage
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api, basicSearch, debounce } from '../api/client'
import { useNodeStore, type TraceRow } from '../stores/node'
import TermViewer from './TermViewer.vue'
import TermModal from './TermModal.vue'

const store = useNodeStore()
const message = useMessage()

const filterText = ref('')
const appliedFilter = ref('')
const applyFilter = debounce((v: string) => (appliedFilter.value = v), 300)
import { watch } from 'vue'
watch(filterText, v => applyFilter(v))

const filtered = computed(() => {
  if (!appliedFilter.value) return store.traces
  return store.traces.filter(t => {
    const haystacks = [t.summary, t.pid, t.regName, t.function, t.args, t.result]
    return haystacks.some(hs => basicSearch(appliedFilter.value, hs))
  })
})

const detailShow = ref(false)
const detailRow = ref<TraceRow | null>(null)
const termShow = ref(false)
const termTitle = ref('')
const termNode = ref<any>(null)

function openDetail(row: TraceRow) {
  detailRow.value = row
  detailShow.value = true
}

function showTerm(title: string, node: any) {
  termTitle.value = title
  termNode.value = node
  termShow.value = true
}

async function copy(text: string | null | undefined) {
  try {
    await navigator.clipboard.writeText(text ?? '')
    message.success('已复制')
  } catch {
    message.error('复制失败')
  }
}

async function deleteRow(row: TraceRow) {
  const idx = store.traces.findIndex(t => t.instanceNum === row.instanceNum)
  if (idx >= 0) store.traces.splice(idx, 1)
}

async function clearAll() {
  await api.clearTraces()
  store.traces = []
}

// ---------- 右键菜单（照搬 TraceContextMenu） ----------
const menuShow = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const menuRow = ref<TraceRow | null>(null)

function openMenu(e: MouseEvent, row: TraceRow) {
  e.preventDefault()
  menuRow.value = row
  menuX.value = e.clientX
  menuY.value = e.clientY
  menuShow.value = true
}

function menuOptions() {
  return [
    { label: '复制函数调用', key: 'copyCall' },
    { label: '复制全部', key: 'copyAll' },
    { label: '删除', key: 'delete' },
    { label: '删除全部', key: 'deleteAll' },
    { label: '添加分隔行', key: 'breaker' }
  ]
}

function onMenuSelect(key: string) {
  const row = menuRow.value
  menuShow.value = false
  if (!row) return
  if (key === 'copyCall') copy(row.summary)
  else if (key === 'copyAll') copy(filtered.value.map(t => t.summary).join('\n'))
  else if (key === 'delete') deleteRow(row)
  else if (key === 'deleteAll') clearAll()
  else if (key === 'breaker') {
    store.traces.push({
      instanceNum: -Date.now(),
      pid: '', regName: '', duration: 0, function: '', args: '', result: '',
      summary: 'BREAK', complete: true, cssClass: 'breaker-row', exception: false,
      stackTrace: null, modFunc: null, argsTerm: null, resultTerm: null
    })
  }
}

function rowClassName(row: TraceRow) {
  if (row.cssClass === 'breaker-row') return 'eb-row-breaker'
  if (row.exception) return 'eb-row-exception'
  return ''
}

const columns = computed<DataTableColumns<TraceRow>>(() => [
  { title: '#', key: 'instanceNum', width: 70, sorter: (a, b) => a.instanceNum - b.instanceNum },
  { title: '进程', key: 'pid', width: 130, ellipsis: true },
  { title: '注册名', key: 'regName', width: 110, ellipsis: true },
  { title: '耗时(us)', key: 'duration', width: 100, sorter: (a, b) => a.duration - b.duration, render: r => (r.complete ? String(r.duration) : '…') },
  { title: '函数', key: 'function', width: 230, ellipsis: { tooltip: true } },
  { title: '参数', key: 'args', ellipsis: { tooltip: true } },
  { title: '结果', key: 'result', width: 220, ellipsis: { tooltip: true }, render: r => (r.complete ? r.result : '…') }
])
</script>

<template>
  <div style="display: flex; flex-direction: column; height: 100%">
    <n-space align="center" style="margin-bottom: 10px" :size="8">
      <n-input
        v-model:value="filterText"
        placeholder="过滤跟踪日志（支持 a|b 多条件、!x 排除）"
        class="eb-filter-input"
        clearable
        style="width: 360px"
      />
      <n-tag :bordered="false" size="small">{{ filtered.length }} 条</n-tag>
      <n-tag v-if="store.status.suspended" type="warning" :bordered="false" size="small">跟踪已挂起（过载保护）</n-tag>
      <n-button size="small" @click="clearAll">清空</n-button>
    </n-space>

    <n-data-table
      :columns="columns"
      :data="filtered"
      :row-class-name="rowClassName"
      flex-height
      size="small"
      style="flex: 1; min-height: 0"
      @update:sorter="() => {}"
      :row-props="(row: TraceRow) => ({
        style: 'cursor: pointer',
        onClick: () => openDetail(row),
        onContextmenu: (e: MouseEvent) => openMenu(e, row)
      })"
    />

    <n-dropdown
      placement="bottom-start"
      trigger="manual"
      :x="menuX"
      :y="menuY"
      :options="menuOptions()"
      :show="menuShow"
      :on-clickoutside="() => (menuShow = false)"
      @select="onMenuSelect"
    />

    <!-- 详情：参数/结果术语树 + 堆栈 -->
    <n-modal v-model:show="detailShow">
      <div class="eb-card" style="width: 780px; max-width: 94vw; max-height: 80vh; display: flex; flex-direction: column">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px">
          <h3 class="eb-card-title" style="margin: 0; font-size: 16px">{{ detailRow?.function }}</h3>
          <n-button size="small" @click="detailShow = false">关闭</n-button>
        </div>
        <p class="eb-card-sub" style="margin-bottom: 10px">
          {{ detailRow?.summary }}
        </p>
        <div class="eb-scroll" style="flex: 1; font-size: 13px; line-height: 1.7; display: flex; flex-direction: column">
          <template v-if="detailRow">
            <div style="font-weight: 600; margin: 6px 0">参数</div>
            <div style="flex: 1; min-height: 120px; display: flex; flex-direction: column">
              <TermViewer v-if="detailRow.argsTerm" :node="detailRow.argsTerm" />
              <div v-else>{{ detailRow.args }}</div>
            </div>
            <div style="font-weight: 600; margin: 10px 0 6px">
              {{ detailRow.exception ? '异常' : '结果' }}
              <n-button size="tiny" quaternary style="margin-left: 8px" @click="showTerm('详细值', detailRow?.resultTerm)">查看详细值</n-button>
            </div>
            <div style="flex: 1; min-height: 120px; display: flex; flex-direction: column">
              <TermViewer v-if="detailRow.resultTerm" :node="detailRow.resultTerm" />
              <div v-else>{{ detailRow.result || '（等待返回）' }}</div>
            </div>
            <div v-if="detailRow.stackTrace && detailRow.stackTrace.c && detailRow.stackTrace.c.length" style="margin-top: 10px">
              <div style="font-weight: 600; margin-bottom: 4px">堆栈跟踪</div>
              <div style="max-height: 200px; display: flex; flex-direction: column">
                <TermViewer :node="detailRow.stackTrace" />
              </div>
            </div>
          </template>
        </div>
      </div>
    </n-modal>

    <TermModal v-model:show="termShow" :title="termTitle" :node="termNode" />
  </div>
</template>
