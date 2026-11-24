<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  NTabs, NTabPane, NButton, NSpace, NTag, NPopover, NBadge, useMessage, NSwitch
} from 'naive-ui'
import { useNodeStore } from '../stores/node'
import { api, sessionFlags } from '../api/client'
import TracePanel from '../components/TracePanel.vue'
import ModulesPanel from '../components/ModulesPanel.vue'
import ProcessesPanel from '../components/ProcessesPanel.vue'
import EtsPanel from '../components/EtsPanel.vue'
import CrashPanel from '../components/CrashPanel.vue'
import SeqTracePanel from '../components/SeqTracePanel.vue'
import ConsolePanel from '../components/ConsolePanel.vue'
import PreferencesPanel from '../components/PreferencesPanel.vue'

const store = useNodeStore()
const router = useRouter()
const message = useMessage()

const tab = ref('debug')
const debugTab = ref('trace')

// ---------- Erlang 内存（照搬 TopBarView 饼图 → 改为 Apple 风格条形图） ----------
const memoryShow = ref(false)
const memory = ref<Record<string, number>>({})

async function showMemory() {
  memoryShow.value = true
  try {
    const r = await api.memory()
    memory.value = r.memory
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  }
}

function fmtMB(bytes: number) {
  return (bytes / 1048576).toFixed(1) + ' MB'
}

async function toggleSuspend() {
  try {
    const r = await api.toggleSuspend()
    store.status.suspended = r.suspended
    message.success(r.suspended ? '跟踪已挂起（已停止全部跟踪）' : '跟踪已恢复')
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  }
}

async function disconnect() {
  // 手动断开后，本次会话不再自动连接
  sessionFlags.autoConnectRan = true
  await store.disconnect()
  router.push('/')
}

// 连接丢失提示（主动断开不提示）
watch(
  () => store.status.connected,
  (nv, ov) => {
    if (ov && !nv && !store.status.manuallyDisconnected) {
      message.warning('连接已断开，正在重连…')
    }
  }
)

onMounted(async () => {
  if (!store.isConnected) {
    await store.refreshStatus().catch(() => {})
    if (!store.isConnected) router.replace('/')
  }
})
</script>

<template>
  <div style="height: 100vh; display: flex; flex-direction: column">
    <!-- 毛玻璃顶栏（照搬 TopBarView） -->
    <div class="eb-glassbar" style="padding: 10px 22px; display: flex; align-items: center; gap: 14px">
      <span style="font-weight: 700; font-size: 15px">erlyberly</span>
      <span style="display: flex; align-items: center">
        <span class="eb-dot" :class="store.status.connected ? 'on' : 'off'"></span>
        <span style="font-size: 13px; color: var(--eb-text-secondary)">{{ store.status.summary }}</span>
      </span>

      <n-tag :bordered="false" size="small" round>进程数 {{ store.status.processCount }}</n-tag>
      <n-tag v-if="store.status.suspended" type="warning" :bordered="false" size="small" round>已挂起</n-tag>

      <div style="flex: 1"></div>

      <n-popover trigger="click" :show="memoryShow" @update:show="(v: boolean) => (memoryShow = v)">
        <template #trigger>
          <n-button size="small" quaternary @click="showMemory">内存</n-button>
        </template>
        <div style="width: 300px">
          <div v-for="(v, k) in memory" :key="k" style="margin-bottom: 8px">
            <div style="display: flex; justify-content: space-between; font-size: 12px">
              <span>{{ k }}</span><span style="color: var(--eb-text-secondary)">{{ fmtMB(v) }}</span>
            </div>
            <div style="height: 6px; background: rgba(0,0,0,0.06); border-radius: 3px; overflow: hidden">
              <div
                style="height: 100%; background: var(--eb-accent); border-radius: 3px"
                :style="{ width: Math.min(100, (v / Math.max(...Object.values(memory || { a: 1 }))) * 100) + '%' }"
              ></div>
            </div>
          </div>
        </div>
      </n-popover>

      <n-badge :value="store.unreadCrashReports" :show="store.unreadCrashReports > 0">
        <n-button size="small" quaternary @click="tab = 'crash'; store.unreadCrashReports = 0">崩溃报告</n-button>
      </n-badge>

      <n-space align="center" :size="6">
        <span style="font-size: 12px; color: var(--eb-text-secondary)">挂起</span>
        <n-switch size="small" :value="store.status.suspended" @update:value="toggleSuspend" />
      </n-space>

      <n-button size="small" type="error" secondary @click="disconnect">断开连接</n-button>
    </div>

    <!-- 主内容 Tabs -->
    <div style="flex: 1; min-height: 0; padding: 14px 22px 0">
      <n-tabs v-model:value="tab" type="line" style="height: 100%">
        <!-- 调试：左 1/3 模块与函数，右 2/3 跟踪/顺序跟踪 -->
        <n-tab-pane name="debug" tab="调试" display-directive="show:lazy" style="height: 100%; padding: 0">
          <div style="display: flex; gap: 14px; height: calc(100vh - 175px)">
            <div class="eb-card" style="width: 33%; min-width: 340px; padding: 14px; display: flex; flex-direction: column">
              <ModulesPanel />
            </div>
            <div class="eb-card" style="flex: 1; padding: 14px; display: flex; flex-direction: column; min-width: 0">
              <n-tabs v-model:value="debugTab" type="line" style="height: 100%">
                <n-tab-pane name="trace" tab="跟踪" display-directive="show:lazy" style="height: 100%">
                  <TracePanel />
                </n-tab-pane>
                <n-tab-pane name="seqtrace" tab="顺序跟踪" display-directive="show:lazy" style="height: 100%">
                  <SeqTracePanel />
                </n-tab-pane>
              </n-tabs>
            </div>
          </div>
        </n-tab-pane>
        <n-tab-pane name="processes" tab="进程" display-directive="show:lazy" style="height: 100%">
          <ProcessesPanel />
        </n-tab-pane>
        <n-tab-pane name="ets" tab="ETS 表" display-directive="show:lazy" style="height: 100%">
          <EtsPanel />
        </n-tab-pane>
        <n-tab-pane name="crash" tab="崩溃报告" display-directive="show:lazy" style="height: 100%">
          <CrashPanel />
        </n-tab-pane>
        <n-tab-pane name="shell" tab="控制台" display-directive="show:lazy" style="height: 100%">
          <ConsolePanel />
        </n-tab-pane>
        <n-tab-pane name="prefs" tab="首选项" display-directive="show:lazy" style="height: 100%">
          <PreferencesPanel />
        </n-tab-pane>
      </n-tabs>
    </div>
  </div>
</template>
