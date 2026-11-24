<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  NDataTable, NInput, NButton, NSpace, NTag, NTabs, NTabPane, NSelect,
  NInputNumber, NCheckbox, NDropdown, NModal, NForm, NFormItem, useMessage, NEmpty
} from 'naive-ui'
import type { DataTableColumns } from 'naive-ui'
import { api, basicSearch, debounce } from '../api/client'
import { useNodeStore } from '../stores/node'
import TermViewer from './TermViewer.vue'

const store = useNodeStore()
const message = useMessage()

// ============ 左 1/3：进程列表 ============
const filterText = ref('')
const appliedFilter = ref('')
const applyFilter = debounce((v: string) => (appliedFilter.value = v), 300)
watch(filterText, v => applyFilter(v))
const polling = ref(false)
const refreshOne = ref(false)

const filtered = computed(() => {
  if (!appliedFilter.value) return store.processes
  return store.processes.filter(
    p => basicSearch(appliedFilter.value, p.pid) || basicSearch(appliedFilter.value, p.name)
  )
})

function fmtBytes(n: number) {
  if (n > 1048576) return (n / 1048576).toFixed(1) + 'M'
  if (n > 1024) return (n / 1024).toFixed(1) + 'K'
  return String(n)
}

const columns = computed<DataTableColumns<any>>(() => [
  { title: '进程ID', key: 'pid', width: 130, ellipsis: true, sorter: (a, b) => a.pid.localeCompare(b.pid) },
  { title: '名称', key: 'name', width: 170, ellipsis: { tooltip: true }, sorter: (a, b) => String(a.name).localeCompare(String(b.name)) },
  { title: '归约数', key: 'reductions', width: 110, sorter: (a, b) => a.reductions - b.reductions },
  { title: '消息队列', key: 'msgQueueLen', width: 95, sorter: (a, b) => a.msgQueueLen - b.msgQueueLen },
  { title: '堆大小', key: 'heapSize', width: 90, sorter: (a, b) => a.heapSize - b.heapSize, render: r => fmtBytes(r.heapSize) },
  { title: '栈大小', key: 'stackSize', width: 90, sorter: (a, b) => a.stackSize - b.stackSize, render: r => fmtBytes(r.stackSize) },
  { title: '总堆', key: 'totalHeapSize', width: 90, sorter: (a, b) => a.totalHeapSize - b.totalHeapSize, render: r => fmtBytes(r.totalHeapSize) }
])

async function togglePolling() {
  polling.value = !polling.value
  await api.setProcPolling(polling.value)
  if (polling.value) message.success('开始轮询进程列表（1s）')
  else message.success('已停止轮询')
}

async function manualRefresh() {
  refreshOne.value = true
  try {
    const r = await api.processes()
    store.processes = r.processes
    store.procUpdateTime = new Date().toLocaleTimeString()
    if (!r.processes.length && !store.isConnected) {
      message.warning('当前未连接 Erlang 节点，请先连接')
    }
  } catch (e: any) {
    message.error('获取进程失败：' + (e?.response?.data?.error ?? String(e)))
  } finally {
    refreshOne.value = false
  }
}

onMounted(async () => {
  try {
    const r = await api.processes()
    store.processes = r.processes
    store.procUpdateTime = new Date().toLocaleTimeString()
  } catch (e: any) {
    message.error('获取进程失败：' + (e?.response?.data?.error ?? String(e)))
  }
})

// ============ 每个进程一个会话（缓存中/右两栏内容） ============
interface DictEntry {
  idx: number
  key: string
  keyNode: any
  valueNode: any
}
interface DictTab {
  key: string
  node: any
}
interface ProcSession {
  pid: string
  stateNode: any
  dictEntries: DictEntry[]
  dictFilter: string
  dictAppliedFilter: string
  openedTabs: DictTab[]
  activeTab: string
}

const sessions = ref<Record<string, ProcSession>>({})
const selectedPid = ref('')
const dictLoading = ref(false)

const session = computed(() => (selectedPid.value ? sessions.value[selectedPid.value] : null))

const dictFiltered = computed(() => {
  const s = session.value
  if (!s) return []
  if (!s.dictAppliedFilter) return s.dictEntries
  return s.dictEntries.filter(e =>
    basicSearch(s.dictAppliedFilter, e.key) || basicSearch(s.dictAppliedFilter, e.valueNode?.v ?? '')
  )
})

const dictColumns: DataTableColumns<DictEntry> = [
  { title: '#', key: 'idx', width: 60 },
  { title: '键', key: 'key', ellipsis: { tooltip: true } },
  { title: '值', key: 'value', ellipsis: { tooltip: true }, render: r => r.valueNode?.v ?? '' }
]

function applyDictFilterDebounced(s: ProcSession, v: string) {
  window.clearTimeout((s as any)._filterTimer)
  ;(s as any)._filterTimer = window.setTimeout(() => {
    s.dictAppliedFilter = v
  }, 300)
}

async function fetchDict(pid: string): Promise<DictEntry[]> {
  const node = await api.processDict(pid)
  const out: DictEntry[] = []
  const children = node?.c ?? []
  children.forEach((child: any, i: number) => {
    if (child.k) {
      // proplist 序列化为 {k, v2} 对（防御性兼容）
      out.push({ idx: i + 1, key: String(child.k.v ?? ''), keyNode: child.k, valueNode: child.v2 })
    } else if (child.c && child.c.length >= 2) {
      out.push({ idx: i + 1, key: String(child.c[0]?.v ?? ''), keyNode: child.c[0], valueNode: child.c[1] })
    } else {
      out.push({ idx: i + 1, key: String(child.v ?? ''), keyNode: child, valueNode: null })
    }
  })
  return out
}

async function selectProcess(pid: string) {
  selectedPid.value = pid
  if (!sessions.value[pid]) {
    sessions.value[pid] = {
      pid,
      stateNode: null,
      dictEntries: [],
      dictFilter: '',
      dictAppliedFilter: '',
      openedTabs: [],
      activeTab: ''
    }
    await refreshDict()
  }
}

async function refreshDict() {
  const s = session.value
  if (!s) return
  dictLoading.value = true
  try {
    s.dictEntries = await fetchDict(s.pid)
  } catch (e: any) {
    message.error('获取进程字典失败：' + (e?.response?.data?.error ?? String(e)))
  } finally {
    dictLoading.value = false
  }
}

// 点击字典条目 → 右侧标签组增加/切换该键的详细值
function openDictTab(entry: DictEntry) {
  const s = session.value
  if (!s) return
  const existing = s.openedTabs.find(t => t.key === entry.key)
  if (existing) {
    s.activeTab = entry.key
    return
  }
  s.openedTabs.push({ key: entry.key, node: entry.valueNode })
  s.activeTab = entry.key
}

async function closeDictTab(key: string) {
  const s = session.value
  if (!s) return
  s.openedTabs = s.openedTabs.filter(t => t.key !== key)
  if (s.activeTab === key && s.openedTabs.length) {
    s.activeTab = s.openedTabs[s.openedTabs.length - 1].key
  }
}

async function refreshDictTab(tab: DictTab) {
  const s = session.value
  if (!s) return
  try {
    const entries = await fetchDict(s.pid)
    s.dictEntries = entries
    const fresh = entries.find(e => e.key === tab.key)
    if (fresh) {
      tab.node = fresh.valueNode
      message.success('已刷新为最新值')
    } else {
      message.warning('该键已不存在于进程字典中')
    }
  } catch (e: any) {
    message.error('刷新失败：' + (e?.response?.data?.error ?? String(e)))
  }
}

// ============ 进程操作（保留原功能） ============
const stateShow = ref(false)
const stateNode = ref<any>(null)

const msgsShow = ref(false)
const msgsNode = ref<any>(null)
const msgsPid = ref('')

const sendShow = ref(false)
const sendPid = ref('')
const sendType = ref('info')
const sendTimeout = ref(5000)
const sendMsg = ref('')
const sendResult = ref('')
const sendError = ref('')
const sending = ref(false)
const stripNewlines = ref(false)

async function doSend() {
  sending.value = true
  sendResult.value = ''
  sendError.value = ''
  try {
    let body = sendMsg.value
    if (stripNewlines.value) body = body.replace(/\n/g, '')
    const r = await api.sendMessage(sendPid.value, sendType.value, body)
    if (r.ok) {
      sendResult.value = r.text
      message.success('发送成功')
    } else if (r.error) {
      sendError.value = r.error
    } else {
      sendResult.value = r.text
    }
  } catch (e: any) {
    sendError.value = e?.response?.data?.error ?? String(e)
  } finally {
    sending.value = false
  }
}

const menuShow = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const menuPid = ref('')

function openMenu(e: MouseEvent, pid: string) {
  e.preventDefault()
  menuPid.value = pid
  menuX.value = e.clientX
  menuY.value = e.clientY
  menuShow.value = true
}

function menuOptions() {
  return [
    { label: '获取进程状态', key: 'state' },
    { label: '发送消息', key: 'send' },
    { label: '查看进程信箱', key: 'msgs' }
  ]
}

async function copyText(t: string) {
  try {
    await navigator.clipboard.writeText(t)
    message.success('已复制')
  } catch {
    message.error('复制失败')
  }
}

async function onMenuSelect(key: string) {
  const pid = menuPid.value
  menuShow.value = false
  if (!pid) return
  try {
    if (key === 'state') {
      const r = await api.processState(pid)
      stateNode.value = r
      stateShow.value = true
    } else if (key === 'send') {
      sendPid.value = pid
      sendResult.value = ''
      sendError.value = ''
      sendShow.value = true
    } else if (key === 'msgs') {
      const r = await api.processMsgs(pid)
      msgsNode.value = r
      msgsPid.value = pid
      msgsShow.value = true
    }
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  }
}
</script>

<template>
  <div style="display: flex; gap: 14px; height: calc(100vh - 180px)">
    <!-- 左 1/3：进程列表 -->
    <div class="eb-card" style="width: 33%; min-width: 320px; padding: 14px; display: flex; flex-direction: column">
      <n-space vertical :size="8" style="margin-bottom: 8px">
        <n-input v-model:value="filterText" placeholder="按 PID / 注册名过滤（a|b、!x）" clearable size="small" class="eb-filter-input" />
        <n-space :size="6">
          <n-tag :bordered="false" size="small">{{ filtered.length }} 个进程</n-tag>
          <n-button size="small" :loading="refreshOne" @click="manualRefresh">刷新</n-button>
          <n-button size="small" :type="polling ? 'warning' : 'primary'" @click="togglePolling">
            {{ polling ? '停止轮询' : '开始轮询' }}
          </n-button>
        </n-space>
      </n-space>
      <div class="eb-scroll" style="flex: 1; min-height: 0">
        <n-data-table
          :columns="columns"
          :data="filtered"
          size="small"
          :row-props="(row: any) => ({
            style: 'cursor: pointer',
            onClick: () => selectProcess(row.pid),
            onContextmenu: (e: MouseEvent) => openMenu(e, row.pid)
          })"
        />
      </div>
    </div>

    <!-- 中 1/3：进程字典列表 -->
    <div class="eb-card" style="width: 33%; min-width: 320px; padding: 14px; display: flex; flex-direction: column">
      <n-space vertical :size="8" style="margin-bottom: 8px">
        <n-input
          :value="session?.dictFilter ?? ''"
          placeholder="过滤字典键/值（a|b、!x）"
          clearable
          size="small"
          @update:value="(v: string) => session && (session.dictFilter = v) && applyDictFilterDebounced(session, v)"
        />
        <n-space :size="6">
          <n-tag :bordered="false" size="small">{{ selectedPid || '未选择进程' }}</n-tag>
          <n-button size="small" :loading="dictLoading" :disabled="!selectedPid" @click="refreshDict">刷新</n-button>
        </n-space>
      </n-space>
      <div class="eb-scroll" style="flex: 1; min-height: 0">
        <n-data-table
          v-if="selectedPid"
          :columns="dictColumns"
          :data="dictFiltered"
          size="small"
          :row-props="(row: DictEntry) => ({
            style: 'cursor: pointer',
            onClick: () => openDictTab(row)
          })"
        />
        <n-empty v-else description="点击左侧进程查看其字典" style="margin-top: 40px" />
      </div>
    </div>

    <!-- 右 1/3：字典详细值标签组 -->
    <div class="eb-card" style="flex: 1; padding: 14px; min-width: 0; display: flex; flex-direction: column">
      <n-tabs
        v-if="session && session.openedTabs.length"
        v-model:value="session.activeTab"
        type="card"
        closable
        style="height: 100%"
        @close="closeDictTab"
      >
        <n-tab-pane v-for="tab in session.openedTabs" :key="tab.key" :name="tab.key" :tab="tab.key" style="height: 100%">
          <div style="display: flex; flex-direction: column; height: 100%">
            <n-space :size="6" style="margin-bottom: 8px">
              <n-button size="small" @click="refreshDictTab(tab)">刷新</n-button>
              <n-button size="small" @click="copyText(tab.node?.v ?? '')">复制原始数据</n-button>
            </n-space>
            <TermViewer :node="tab.node" style="flex: 1; min-height: 0" />
          </div>
        </n-tab-pane>
      </n-tabs>
      <n-empty v-else description="点击中间字典条目，在此查看详细值" style="margin: auto" />
    </div>

    <!-- 进程状态 -->
    <n-modal v-model:show="stateShow">
      <div class="eb-card" style="width: 640px; max-width: 92vw; height: 70vh; display: flex; flex-direction: column">
        <div style="display: flex; justify-content: space-between">
          <h3 class="eb-card-title">进程状态</h3>
          <n-button size="small" @click="stateShow = false">关闭</n-button>
        </div>
        <TermViewer v-if="stateNode" :node="stateNode" style="flex: 1; min-height: 0" />
      </div>
    </n-modal>

    <!-- 进程信箱 -->
    <n-modal v-model:show="msgsShow">
      <div class="eb-card" style="width: 640px; max-width: 92vw; height: 70vh; display: flex; flex-direction: column">
        <div style="display: flex; justify-content: space-between">
          <h3 class="eb-card-title">{{ msgsPid }} 进程信箱</h3>
          <n-button size="small" @click="msgsShow = false">关闭</n-button>
        </div>
        <TermViewer v-if="msgsNode" :node="msgsNode" style="flex: 1; min-height: 0" />
      </div>
    </n-modal>

    <!-- 发送消息 -->
    <n-modal v-model:show="sendShow">
      <div class="eb-card" style="width: 620px; max-width: 92vw">
        <h3 class="eb-card-title">发送消息</h3>
        <p class="eb-card-sub">目标 {{ sendPid }}</p>
        <n-form label-placement="left" label-width="90">
          <n-form-item label="消息类型">
            <n-select
              v-model:value="sendType"
              :options="[
                { label: 'info', value: 'info' },
                { label: 'cast', value: 'cast' },
                { label: 'call', value: 'call' }
              ]"
              style="width: 160px"
            />
          </n-form-item>
          <n-form-item v-if="sendType === 'call'" label="超时(ms)">
            <n-input-number v-model:value="sendTimeout" :min="100" style="width: 160px" />
          </n-form-item>
          <n-form-item label="消息内容">
            <n-input
              v-model:value="sendMsg"
              type="textarea"
              :rows="5"
              placeholder="输入 Erlang 术语，如 {hello, world} 或 ok"
              style="font-family: 'SF Mono', Menlo, monospace"
            />
          </n-form-item>
          <n-form-item label=" ">
            <n-space>
              <n-checkbox v-model:checked="stripNewlines">发送时去除换行符</n-checkbox>
              <n-button type="primary" :loading="sending" :disabled="!sendMsg" @click="doSend">发送</n-button>
            </n-space>
          </n-form-item>
        </n-form>
        <div v-if="sendResult" style="margin-top: 10px; padding: 10px; background: rgba(52,199,89,0.1); border-radius: 10px; font-size: 13px">
          <b>结果：</b><span style="font-family: 'SF Mono', Menlo, monospace">{{ sendResult }}</span>
        </div>
        <div v-if="sendError" style="margin-top: 10px; padding: 10px; background: rgba(227,0,0,0.08); border-radius: 10px; font-size: 13px; color: var(--eb-danger)">
          {{ sendError }}
        </div>
      </div>
    </n-modal>

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
  </div>
</template>
