<script setup lang="ts">
import { NConfigProvider, NMessageProvider, type GlobalThemeOverrides } from 'naive-ui'
import { useNodeStore } from './stores/node'
import { onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'

const store = useNodeStore()
const router = useRouter()

// Apple 官网风格的 Naive UI 主题覆盖
const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#0071e3',
    primaryColorHover: '#0077ed',
    primaryColorPressed: '#0062c4',
    primaryColorSuppl: '#0071e3',
    borderRadius: '10px',
    fontSize: '14px',
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Helvetica Neue', 'PingFang SC', 'Microsoft YaHei', sans-serif"
  },
  Button: { borderRadiusMedium: '980px', borderRadiusSmall: '980px' },
  Card: { borderRadius: '18px' },
  DataTable: { thColor: '#fafafa', tdColor: '#ffffff' }
}

onMounted(async () => {
  store.bindWs()
  try {
    await store.refreshStatus()
  } catch {
    /* 后端未就绪时静默 */
  }
  router.push(store.isConnected ? '/main' : '/')
})

// 连接后浏览器标题显示目标节点名
watch(
  () => [store.status.connected, store.status.remoteNode] as const,
  ([connected, remoteNode]) => {
    document.title = connected && remoteNode ? `${remoteNode} · erlyberly` : 'erlyberly Web'
  },
  { immediate: true }
)
</script>

<template>
  <n-config-provider :theme-overrides="themeOverrides" :theme="null" style="height: 100%">
    <n-message-provider>
      <router-view v-slot="{ Component }">
        <transition name="eb-fade" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </n-message-provider>
  </n-config-provider>
</template>
