<script setup lang="ts">
import { computed, ref } from 'vue'
import { NTabs, NTabPane, NInput } from 'naive-ui'
import TermTree from './TermTree.vue'

/**
 * 术语查看器：搜索框 + 渲染值/原始数据页签。
 * 被 TermModal 和进程字典页签复用。
 */
const props = defineProps<{ node: any }>()

const viewTab = ref('render')
const searchText = ref('')

const rawText = computed(() => props.node?.v ?? '')
</script>

<template>
  <n-tabs v-model:value="viewTab" type="segment" size="small" style="flex: 1; min-height: 0">
    <n-tab-pane name="render" tab="渲染值" style="height: 100%; display: flex; flex-direction: column">
      <n-input
        v-model:value="searchText"
        placeholder="搜索值（命中高亮并自动展开路径）"
        size="small"
        clearable
        style="margin-bottom: 8px"
      />
      <div class="eb-scroll" style="flex: 1; min-height: 0; font-size: 13px">
        <TermTree v-if="node" :node="node" :search="searchText" :expand-first="true" />
        <div v-else style="color: var(--eb-text-secondary)">（无数据）</div>
      </div>
    </n-tab-pane>
    <n-tab-pane name="raw" tab="原始数据" style="height: 100%">
      <pre class="eb-scroll" style="max-height: 55vh; font-size: 12px; line-height: 1.55; margin: 0; white-space: pre-wrap; font-family: 'SF Mono', Menlo, monospace">{{ rawText }}</pre>
    </n-tab-pane>
  </n-tabs>
</template>
