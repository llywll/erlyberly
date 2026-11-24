<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { NInput, NCheckbox, NButton, NSpace, NDropdown } from 'naive-ui'
import { useNodeStore } from '../stores/node'

const store = useNodeStore()
const router = useRouter()

const localNodeName = ref(localStorage.getItem('localNodeName') ?? 'erlyberly_web')
const remoteNodeName = ref(localStorage.getItem('remoteNodeName') ?? '')
const cookie = ref(localStorage.getItem('cookie') ?? '')
const autoConnect = ref(localStorage.getItem('autoConnect') === 'true')
const connecting = ref(false)
const errorMsg = ref('')

function pushHistory(key: string, value: string) {
  if (!value) return
  const arr: string[] = JSON.parse(localStorage.getItem(key) ?? '[]')
  const next = [value, ...arr.filter(v => v !== value)].slice(0, 50)
  localStorage.setItem(key, JSON.stringify(next))
}

function historyOptions(key: string) {
  return (JSON.parse(localStorage.getItem(key) ?? '[]') as string[]).map(v => ({ label: v, value: v }))
}

async function onConnect() {
  connecting.value = true
  errorMsg.value = ''
  try {
    pushHistory('historyLocalNodeName', localNodeName.value)
    pushHistory('historyTargetNodeName', remoteNodeName.value)
    pushHistory('historyCookieName', cookie.value)
    localStorage.setItem('localNodeName', localNodeName.value)
    localStorage.setItem('remoteNodeName', remoteNodeName.value)
    localStorage.setItem('cookie', cookie.value)
    localStorage.setItem('autoConnect', String(autoConnect.value))
    await store.connect(localNodeName.value, remoteNodeName.value, cookie.value)
    router.push('/main')
  } catch (e: any) {
    errorMsg.value = e?.response?.data?.error ?? String(e)
  } finally {
    connecting.value = false
  }
}

// 自动连接：仅在应用启动后的首次进入时执行（照搬桌面版"启动自动连接"语义），
// 手动断开后再回到连接页不会自动重连（sessionFlags 为模块级状态）
import { sessionFlags } from '../api/client'
if (autoConnect.value && remoteNodeName.value && !sessionFlags.autoConnectRan) {
  sessionFlags.autoConnectRan = true
  onConnect()
}
</script>

<template>
  <div style="min-height: 100vh; display: flex; flex-direction: column; align-items: center; justify-content: center">
    <div class="eb-card" style="width: 480px; max-width: 92vw">
      <h1 class="eb-hero-title" style="font-size: 34px">erlyberly</h1>
      <p class="eb-hero-sub">Erlang 跟踪调试器 · Web 版</p>

      <n-space vertical :size="16">
        <n-dropdown trigger="click" :options="historyOptions('historyLocalNodeName')" @select="(v: string) => (localNodeName = v)">
          <n-input v-model:value="localNodeName" placeholder="本地节点名" class="eb-filter-input" />
        </n-dropdown>
        <n-dropdown trigger="click" :options="historyOptions('historyTargetNodeName')" @select="(v: string) => (remoteNodeName = v)">
          <n-input v-model:value="remoteNodeName" placeholder="目标节点名（如 mynode@127.0.0.1）" class="eb-filter-input" @keydown.enter="onConnect" />
        </n-dropdown>
        <n-dropdown trigger="click" :options="historyOptions('historyCookieName')" @select="(v: string) => (cookie = v)">
          <n-input v-model:value="cookie" placeholder="Cookie（留空使用默认）" class="eb-filter-input" @keydown.enter="onConnect" />
        </n-dropdown>
        <n-checkbox v-model:checked="autoConnect">自动连接？</n-checkbox>

        <n-button type="primary" size="large" block :loading="connecting" :disabled="!remoteNodeName" @click="onConnect">
          连接
        </n-button>

        <div v-if="errorMsg" style="color: var(--eb-danger); font-size: 13px; word-break: break-all">{{ errorMsg }}</div>
      </n-space>
    </div>
    <p style="color: var(--eb-text-secondary); font-size: 12px; margin-top: 20px">
      {{ store.status.summary }}
    </p>
  </div>
</template>
