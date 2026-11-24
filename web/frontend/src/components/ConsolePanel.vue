<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { NButton, NSpace, NTag, useMessage } from 'naive-ui'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { api, ws } from '../api/client'
import { useNodeStore } from '../stores/node'

const store = useNodeStore()
const message = useMessage()

const termEl = ref<HTMLElement | null>(null)
let term: Terminal | null = null
let fit: FitAddon | null = null
let unsub: (() => void) | null = null
let resizeObs: ResizeObserver | null = null
let attached = false

/** 后端按行归一为 \n；xterm 需要 \r\n 才换行回车 */
function crlf(s: string) {
  return s.replace(/\r?\n/g, '\r\n')
}

onMounted(async () => {
  term = new Terminal({
    fontFamily: "'SF Mono', Menlo, Consolas, monospace",
    fontSize: 13,
    lineHeight: 1.25,
    cursorBlink: true,
    scrollback: 5000,
    theme: {
      background: '#1d1d1f',
      foreground: '#e6edf3',
      cursor: '#7ee787',
      selectionBackground: '#3b4261'
    }
  })
  fit = new FitAddon()
  term.loadAddon(fit)
  term.open(termEl.value!)
  fit.fit()

  // 按键原样进 pty：回显、提示符、Tab 补全、^G 任务控制全由目标节点真 shell 处理
  term.onData(d => {
    ws.send('shell_in', d)
  })

  resizeObs = new ResizeObserver(() => {
    try { fit?.fit() } catch { /* ignore */ }
  })
  resizeObs.observe(termEl.value!)

  term.writeln('\x1b[90mErlang 控制台 — remsh 真终端。Tab 补全 / 上下历史 / ^G 任务控制可用，提示符出现前请稍候。\x1b[0m')

  unsub = ws.on((type, data) => {
    if (type === 'shell_out') {
      term?.write(crlf(String(data)))
    } else if (type === 'shell_exit') {
      term?.write('\r\n\x1b[33m— shell 已退出: ' + (data?.reason ?? '') + ' —\x1b[0m\r\n')
      attached = false
    }
  })

  if (store.isConnected) {
    await attachShell()
  }
  watch(() => store.isConnected, v => {
    if (v && !attached) attachShell()
  })
})

onUnmounted(() => {
  // 页签切走不停 shell（会话级维持），仅解除订阅并释放终端视图
  unsub?.()
  resizeObs?.disconnect()
  term?.dispose()
  term = null
})

async function attachShell() {
  if (!term) return
  try {
    const r = await api.shellStart()
    if (r.replay) {
      term.write(crlf(r.replay))
      term.write('\r\n\x1b[90m— 已附着到运行中的 shell —\x1b[0m\r\n')
    } else if (r.running) {
      term.write('\x1b[90m— remsh 客户端已启动，等待目标节点 shell 附着 —\x1b[0m\r\n')
    }
    attached = true
  } catch (e: any) {
    term.writeln('\x1b[31m启动失败: ' + (e?.response?.data?.error ?? String(e)) + '\x1b[0m')
  }
}

async function restartShell() {
  try {
    await api.shellStop()
    await new Promise(r => setTimeout(r, 300))
    await attachShell()
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  }
}
</script>

<template>
  <div style="display: flex; flex-direction: column; height: calc(100vh - 180px)">
    <n-space :size="8" style="margin-bottom: 8px" align="center">
      <n-button size="small" @click="restartShell">重启 shell</n-button>
      <n-button size="small" @click="term?.clear()">清屏</n-button>
      <n-tag :bordered="false" size="small">{{ store.status.remoteNode ?? '未连接' }}</n-tag>
      <n-tag type="success" :bordered="false" size="small">remsh 真终端 · pty 直通</n-tag>
    </n-space>

    <div
      ref="termEl"
      class="eb-scroll"
      style="flex: 1; min-height: 0; background: #1d1d1f; border-radius: 12px; padding: 8px 10px"
    />
  </div>
</template>
