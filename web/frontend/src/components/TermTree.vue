<script setup lang="ts">
import { computed, ref } from 'vue'

// 术语树节点: { t: 类型, v: 显示值, c?: [子节点] }
// map 节点的子项为 { k: key节点, v2: value节点 }
// 根元素为 span（行内），使 "k => #{ … }" 挂在同一行，展开后的子项才换行缩进
const props = defineProps<{ node: any; search?: string; expandFirst?: boolean }>()

const expanded = ref(props.expandFirst === true)

function cls(t: string) {
  if (t === 'atom') return 'eb-term-atom'
  if (t === 'string') return 'eb-term-string'
  if (t === 'int' || t === 'float') return 'eb-term-number'
  if (t === 'pid' || t === 'ref' || t === 'port') return 'eb-term-pid'
  return ''
}

/** 容器类型只显示头部，不重复显示完整格式化串 */
function isContainer(t: string) {
  return t === 'list' || t === 'tuple' || t === 'map'
}

function headerText(node: any) {
  const n = node.c?.length ?? 0
  if (node.t === 'list') return `[ … ] (${n})`
  if (node.t === 'tuple') return `{ … } (${n})`
  return `#{ … } (${n})`
}

function openBracket(t: string) {
  if (t === 'list') return '['
  if (t === 'tuple') return '{'
  return '#{'
}

function closeBracket(t: string) {
  if (t === 'list') return ']'
  return '}'
}

const selfMatch = computed(() => {
  const s = props.search
  return !!s && s.length > 0 && String(props.node.v ?? '').includes(s)
})

function walkChildren(node: any): any[] {
  return node.t === 'map' && node.c ? node.c : (node.c ?? [])
}

const hasMatchDeep = computed(() => {
  if (selfMatch.value) return true
  const s = props.search
  if (!s) return false
  return walkChildren(props.node).some((child: any) => {
    if (child.k) {
      return String(child.k.v ?? '').includes(s) || deepMatch(child.v2)
    }
    return deepMatch(child)
  })
})

function deepMatch(node: any): boolean {
  const s = props.search
  if (!s) return false
  if (String(node?.v ?? '').includes(s)) return true
  return walkChildren(node).some((c: any) => {
    if (c.k) {
      return String(c.k.v ?? '').includes(s) || deepMatch(c.v2)
    }
    return deepMatch(c)
  })
}

const effectiveExpanded = computed(() => (props.search ? hasMatchDeep.value : expanded.value))

function toggle() {
  expanded.value = !expanded.value
}
</script>

<template>
  <span style="line-height: 1.75">
    <span
      v-if="node.c && node.c.length"
      style="cursor: pointer; display: inline-block; width: 14px; color: var(--eb-text-secondary); user-select: none"
      @click="toggle"
    >{{ effectiveExpanded ? '▾' : '▸' }}</span>
    <span v-else style="display: inline-block; width: 14px"></span>

    <!-- 容器 -->
    <template v-if="isContainer(node.t) && node.c && node.c.length">
      <!-- 折叠：单行摘要 -->
      <span
        v-if="!effectiveExpanded"
        :style="selfMatch ? 'background: #ffe58f; border-radius: 4px' : ''"
        style="color: var(--eb-text-secondary)"
      >{{ headerText(node) }}</span>
      <!-- 展开：首行开括号，子项缩进，末行闭括号 -->
      <template v-else>
        <span style="color: var(--eb-text-secondary)">{{ openBracket(node.t) }}</span>
        <div class="eb-term-children">
          <div v-for="(child, i) in node.c" :key="i">
            <!-- map: k/v2，k 与 v 行内 -->
            <template v-if="node.t === 'map' && child.k">
              <span
                class="eb-term-key"
                :style="search && String(child.k.v ?? '').includes(search) ? 'background: #ffe58f; border-radius: 4px' : ''"
              >{{ child.k.v }} =&gt; </span>
              <TermTree :node="child.v2" :search="search" />
            </template>
            <TermTree v-else :node="child" :search="search" />
          </div>
        </div>
        <span style="color: var(--eb-text-secondary); margin-left: 14px">{{ closeBracket(node.t) }}</span>
      </template>
    </template>
    <!-- 空容器：单行显示 -->
    <span
      v-else-if="isContainer(node.t)"
      style="color: var(--eb-text-secondary)"
    >{{ node.t === 'list' ? '[]' : node.t === 'tuple' ? '{}' : '#{}' }}</span>
    <!-- 叶子：显示值 -->
    <span v-else :class="cls(node.t)" :style="selfMatch ? 'background: #ffe58f; border-radius: 4px' : ''">
      {{ node.v }}
    </span>
  </span>
</template>
