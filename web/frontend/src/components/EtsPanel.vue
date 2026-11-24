<script setup lang="ts">
import { computed, ref } from 'vue'
import { NInput, NButton, NSpace, NDataTable, NTabs, NTabPane, useMessage, NEmpty, NTag } from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api, basicSearch, debounce } from '../api/client'
import TermViewer from './TermViewer.vue'

const message = useMessage()

// ============ 左 1/3：表列表 ============
interface EtsTable {
  name: string
  type: string
  size: string
  memory: string
  owner: string
}

const tables = ref<EtsTable[]>([])
const loading = ref(false)
const filterText = ref('')
const appliedFilter = ref('')
const applyFilter = debounce((v: string) => (appliedFilter.value = v), 300)
import { watch } from 'vue'
watch(filterText, v => applyFilter(v))

const filtered = computed(() =>
  appliedFilter.value
    ? tables.value.filter((t: any) => basicSearch(appliedFilter.value, t.name))
    : tables.value
)

const tableColumns: DataTableColumns<EtsTable> = [
  { title: '表名', key: 'name', ellipsis: { tooltip: true }, sorter: (a, b) => a.name.localeCompare(b.name) },
  { title: '类型', key: 'type', width: 90 },
  { title: '大小', key: 'size', width: 70, sorter: (a, b) => Number(a.size) - Number(b.size) },
  { title: '内存', key: 'memory', width: 80, sorter: (a, b) => Number(a.memory) - Number(b.memory) }
]

async function refresh() {
  loading.value = true
  try {
    const r = await api.etsTables()
    tables.value = r.tables
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  } finally {
    loading.value = false
  }
}
refresh()

// ============ 每张表一个会话（缓存中/右两栏） ============
interface RowEntry {
  idx: number
  key: string
  keyNode: any
  valueNode: any
  raw: any
}
interface DetailTab {
  // 标签键：表名 + 行 key，跨表唯一
  tabKey: string
  key: string
  node: any
}
interface EtsSession {
  name: string
  rows: RowEntry[]
  filter: string
  appliedFilter: string
  openedTabs: DetailTab[]
  activeTab: string
}

const sessions = ref<Record<string, EtsSession>>({})
const selectedTable = ref('')
const rowsLoading = ref(false)

const session = computed(() => (selectedTable.value ? sessions.value[selectedTable.value] : null))

function parseContentRows(node: any): RowEntry[] {
  // get_ets_table_info 返回 [{{K},V}...] 或 [{K,V}...] 形式的术语树
  const out: RowEntry[] = []
  const children = node?.c ?? []
  children.forEach((child: any, idx: number) => {
    const c = child.c ?? []
    if (c.length >= 2) {
      out.push({ idx: idx + 1, key: String(c[0]?.v ?? ''), keyNode: c[0], valueNode: c[1], raw: child })
    } else {
      out.push({ idx: idx + 1, key: String(child.v ?? ''), keyNode: child, valueNode: null, raw: child })
    }
  })
  return out
}

async function fetchRows(name: string): Promise<RowEntry[]> {
  const r = await api.etsTableInfo(name)
  return parseContentRows(r.table)
}

function applyRowsFilterDebounced(s: EtsSession, v: string) {
  window.clearTimeout((s as any)._filterTimer)
  ;(s as any)._filterTimer = window.setTimeout(() => {
    s.appliedFilter = v
  }, 300)
}

function onRowsFilter(s: EtsSession, v: string) {
  s.filter = v
  applyRowsFilterDebounced(s, v)
}

const rowsFiltered = computed(() => {
  const s = session.value
  if (!s) return []
  if (!s.appliedFilter) return s.rows
  return s.rows.filter(r => basicSearch(s.appliedFilter, `${r.key} ${r.valueNode?.v ?? ''}`))
})

const rowsColumns: DataTableColumns<RowEntry> = [
  { title: '#', key: 'idx', width: 55 },
  { title: '键', key: 'key', ellipsis: { tooltip: true } },
  { title: '值', key: 'value', ellipsis: { tooltip: true }, render: r => r.valueNode?.v ?? '' }
]

async function openTable(row: EtsTable) {
  selectedTable.value = row.name
  if (!sessions.value[row.name]) {
    sessions.value[row.name] = {
      name: row.name,
      rows: [],
      filter: '',
      appliedFilter: '',
      openedTabs: [],
      activeTab: ''
    }
    await refreshSession()
  }
}

async function refreshSession() {
  const s = session.value
  if (!s) return
  rowsLoading.value = true
  try {
    s.rows = await fetchRows(s.name)
  } catch (e: any) {
    message.error('获取表内容失败：' + (e?.response?.data?.error ?? String(e)))
  } finally {
    rowsLoading.value = false
  }
}

// ============ 中 1/3：表内容操作 ============
async function copySession() {
  const s = session.value
  if (!s) return
  try {
    await navigator.clipboard.writeText(rowsFiltered.value.map(r => `${r.key} = ${r.valueNode?.v ?? ''}`).join('\n'))
    message.success('已复制')
  } catch {
    message.error('复制失败')
  }
}

function exportRows(s: EtsSession, raw: boolean) {
  const rows = rowsFiltered.value
  const lines = rows.map(r => (raw ? r.raw?.v ?? '' : `${r.key}\t${r.valueNode?.v ?? ''}`))
  const blob = new Blob([lines.join('\n')], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${s.name}.${raw ? 'txt' : 'csv'}`
  a.click()
  URL.revokeObjectURL(a.href)
}

// ============ 右 1/3：行详细值标签组 ============
function openDetailTab(row: RowEntry) {
  const s = session.value
  if (!s) return
  const tabKey = `${s.name}::${row.key}`
  const existing = s.openedTabs.find(t => t.tabKey === tabKey)
  if (existing) {
    s.activeTab = tabKey
    return
  }
  s.openedTabs.push({ tabKey, key: row.key, node: row.valueNode ?? row.keyNode })
  s.activeTab = tabKey
}

function closeTab(tabKey: string) {
  const s = session.value
  if (!s) return
  s.openedTabs = s.openedTabs.filter(t => t.tabKey !== tabKey)
  if (s.activeTab === tabKey && s.openedTabs.length) {
    s.activeTab = s.openedTabs[s.openedTabs.length - 1].tabKey
  }
}

async function refreshTab(tab: DetailTab) {
  const s = session.value
  if (!s) return
  try {
    const rows = await fetchRows(s.name)
    s.rows = rows
    const fresh = rows.find(r => r.key === tab.key)
    if (fresh) {
      tab.node = fresh.valueNode ?? fresh.keyNode
      message.success('已刷新为最新值')
    } else {
      message.warning('该键已不存在于表中')
    }
  } catch (e: any) {
    message.error('刷新失败：' + (e?.response?.data?.error ?? String(e)))
  }
}

async function copyTab(tab: DetailTab) {
  try {
    await navigator.clipboard.writeText(tab.node?.v ?? '')
    message.success('已复制原始数据')
  } catch {
    message.error('复制失败')
  }
}
</script>

<template>
  <div style="display: flex; gap: 14px; height: calc(100vh - 180px)">
    <!-- 左 1/3：表列表 -->
    <div class="eb-card" style="width: 33%; min-width: 320px; padding: 14px; display: flex; flex-direction: column">
      <n-space vertical :size="8" style="margin-bottom: 8px">
        <n-input v-model:value="filterText" placeholder="过滤表名（a|b、!x）" clearable size="small" class="eb-filter-input" />
        <n-space :size="6">
          <n-tag :bordered="false" size="small">{{ filtered.length }} 张表</n-tag>
          <n-button size="small" :loading="loading" @click="refresh">刷新</n-button>
        </n-space>
      </n-space>
      <div class="eb-scroll" style="flex: 1; min-height: 0">
        <n-data-table
          :columns="tableColumns"
          :data="filtered"
          size="small"
          :row-props="(row: EtsTable) => ({
            style: 'cursor: pointer',
            onClick: () => openTable(row)
          })"
        />
        <n-empty v-if="!filtered.length && !loading" description="暂无 ETS 表数据（需已连接）" style="margin-top: 40px" />
      </div>
    </div>

    <!-- 中 1/3：表内容行 -->
    <div class="eb-card" style="width: 33%; min-width: 320px; padding: 14px; display: flex; flex-direction: column">
      <n-space vertical :size="8" style="margin-bottom: 8px">
        <n-input
          :value="session?.filter ?? ''"
          placeholder="过滤键/值（a|b、!x）"
          clearable
          size="small"
          @update:value="(v: string) => session && onRowsFilter(session, v)"
        />
        <n-space :size="6">
          <n-tag :bordered="false" size="small">{{ selectedTable || '未选择表' }}</n-tag>
          <n-button size="small" :loading="rowsLoading" :disabled="!selectedTable" @click="refreshSession">刷新</n-button>
          <n-button size="small" :disabled="!selectedTable" @click="copySession">复制全部</n-button>
          <n-button size="small" :disabled="!selectedTable" @click="session && exportRows(session, false)">导出 CSV</n-button>
          <n-button size="small" :disabled="!selectedTable" @click="session && exportRows(session, true)">导出原始数据</n-button>
        </n-space>
      </n-space>
      <div class="eb-scroll" style="flex: 1; min-height: 0">
        <n-data-table
          v-if="selectedTable"
          :columns="rowsColumns"
          :data="rowsFiltered"
          size="small"
          :row-props="(row: RowEntry) => ({
            style: 'cursor: pointer',
            onClick: () => openDetailTab(row)
          })"
        />
        <n-empty v-else description="点击左侧表名查看内容" style="margin-top: 40px" />
      </div>
    </div>

    <!-- 右 1/3：行详细值标签组 -->
    <div class="eb-card" style="flex: 1; padding: 14px; min-width: 0; display: flex; flex-direction: column">
      <n-tabs
        v-if="session && session.openedTabs.length"
        v-model:value="session.activeTab"
        type="card"
        closable
        style="height: 100%"
        @close="closeTab"
      >
        <n-tab-pane v-for="tab in session.openedTabs" :key="tab.tabKey" :name="tab.tabKey" :tab="tab.key" style="height: 100%">
          <div style="display: flex; flex-direction: column; height: 100%">
            <n-space :size="6" style="margin-bottom: 8px">
              <n-button size="small" @click="refreshTab(tab)">刷新</n-button>
              <n-button size="small" @click="copyTab(tab)">复制原始数据</n-button>
            </n-space>
            <TermViewer :node="tab.node" style="flex: 1; min-height: 0" />
          </div>
        </n-tab-pane>
      </n-tabs>
      <n-empty v-else description="点击中间表内容行，在此查看该行详细值" style="margin: auto" />
    </div>
  </div>
</template>
