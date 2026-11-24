<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { NForm, NFormItem, NSelect, NInput, NInputNumber, NButton, NSpace, useMessage, NSwitch } from 'naive-ui'
import { api } from '../api/client'

const message = useMessage()

const termFormatting = ref('erlang')
const maxTraceLogs = ref(1000)
const maxTraceQueueLength = ref(1000)
const loadModulesRegex = ref('')
const xrefSkippedModules = ref('')
const showSourceInSystemEditor = ref(false)

async function load() {
  const p = await api.preferences()
  termFormatting.value = p.termFormatting ?? 'erlang'
  maxTraceLogs.value = Number(p.maxTraceLogs ?? 1000)
  maxTraceQueueLength.value = Number(p.maxTraceQueueLength ?? 1000)
  loadModulesRegex.value = String(p.loadModulesRegex ?? '')
  xrefSkippedModules.value = String(p.xrefSkippedModules ?? '')
  showSourceInSystemEditor.value = Boolean(p.showSourceInSystemEditor)
}
onMounted(load)

async function save() {
  await api.setPreference('termFormatting', termFormatting.value)
  await api.setPreference('maxTraceLogs', maxTraceLogs.value)
  await api.setPreference('maxTraceQueueLength', maxTraceQueueLength.value)
  await api.setPreference('loadModulesRegex', loadModulesRegex.value)
  await api.setPreference('xrefSkippedModules', xrefSkippedModules.value)
  await api.setPreference('showSourceInSystemEditor', showSourceInSystemEditor.value)
  message.success('已保存（重启连接后部分配置生效）')
}
</script>

<template>
  <div class="eb-card" style="max-width: 640px; margin: 0 auto">
    <h3 class="eb-card-title">首选项</h3>
    <p class="eb-card-sub">配置保存在 ~/.config/.erlyberly2</p>
    <n-form label-placement="left" label-width="150">
      <n-form-item label="术语格式">
        <n-select
          v-model:value="termFormatting"
          :options="[
            { label: 'Erlang', value: 'erlang' },
            { label: 'Elixir', value: 'elixir' },
            { label: 'LFE', value: 'lfe' }
          ]"
          style="width: 220px"
        />
      </n-form-item>
      <n-form-item label="最大跟踪日志数">
        <n-input-number v-model:value="maxTraceLogs" :min="100" :step="100" style="width: 180px" />
      </n-form-item>
      <n-form-item label="最大跟踪队列长度">
        <n-input-number v-model:value="maxTraceQueueLength" :min="100" :step="100" style="width: 180px" />
      </n-form-item>
      <n-form-item label="预加载模块正则">
        <n-input v-model:value="loadModulesRegex" placeholder="如 ^gen_|^supervisor" />
      </n-form-item>
      <n-form-item label="xref 排除模块">
        <n-input v-model:value="xrefSkippedModules" placeholder="逗号分隔，如 lists,maps" />
      </n-form-item>
      <n-form-item label="系统编辑器显示源码">
        <n-switch v-model:value="showSourceInSystemEditor" />
      </n-form-item>
      <n-form-item label=" ">
        <n-space>
          <n-button type="primary" @click="save">保存</n-button>
          <n-button @click="load">重置</n-button>
        </n-space>
      </n-form-item>
    </n-form>
  </div>
</template>
