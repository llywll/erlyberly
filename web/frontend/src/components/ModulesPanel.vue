<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import {
  NInput, NButton, NSpace, NTree, NDropdown, NModal, NEmpty, useMessage
} from 'naive-ui'
import { api, debounce, type TraceReq } from '../api/client'
import { useNodeStore } from '../stores/node'
import TermViewer from './TermViewer.vue'

interface FuncNode {
  name: string
  arity: number
  exported: boolean
}
interface ModuleNode {
  module: string
  funcs: FuncNode[]
  /** 预计算的搜索索引（module:fun/arity 串接），过滤时一次子串匹配 */
  search?: string
}

const store = useNodeStore()
const message = useMessage()

const modules = ref<ModuleNode[]>([])
const loading = ref(false)
const filterText = ref('')
const appliedFilter = ref('')
const applyFilter = debounce((v: string) => (appliedFilter.value = v), 300)
watch(filterText, v => applyFilter(v))
const activeTraces = ref<TraceReq[]>([])

function isTraced(module: string, name: string, arity: number): boolean {
  return activeTraces.value.some(t => t.module === module && t.function === name && t.arity === arity)
}

async function refresh(m: 'loaded' | 'all' = 'loaded') {
  loading.value = true
  try {
    const r = await api.functions(m)
    // 预计算每个模块的搜索索引，过滤时避免逐函数匹配（性能关键）
    modules.value = (r.modules as ModuleNode[]).map(md => ({
      ...md,
      search: md.module + ':' + md.funcs.map(fn => `${md.module}:${fn.name}/${fn.arity}`).join(' ')
    }))
    const at = await api.activeTraces()
    activeTraces.value = at.traces
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  } finally {
    loading.value = false
  }
}
onMounted(() => refresh())

// 连接后重挂的跟踪状态、模块重载时自动刷新
watch(
  () => store.moduleReloadCounter,
  () => {
    if (store.isConnected) refresh()
  }
)

function fnMatches(m: ModuleNode, fn: FuncNode): boolean {
  if (!appliedFilter.value) return true
  if (appliedFilter.value === '#t') return isTraced(m.module, fn.name, fn.arity)
  return funcPasses(appliedFilter.value, fn)
}

/**
 * 过滤语义（级联）：
 * - 不含冒号：只过滤模块名
 * - 含冒号：冒号前继续过滤模块，冒号后在已过滤的模块中过滤函数
 * - `|` 分隔多条件（任一命中），`!x` 排除
 */
interface FilterTerm {
  excl: boolean
  mod: string
  func: string | null
}

function parseFilter(f: string): FilterTerm[] {
  return f
    .split('|')
    .map(t => t.trim())
    .filter(t => t !== '')
    .map(t => {
      const excl = t.startsWith('!')
      const text = excl ? t.substring(1) : t
      const idx = text.indexOf(':')
      if (idx >= 0) {
        return { excl, mod: text.slice(0, idx), func: text.slice(idx + 1) }
      }
      return { excl, mod: text, func: null }
    })
    .filter(t => t.mod !== '' || t.func !== '')
}

function moduleHit(t: FilterTerm, m: ModuleNode): boolean {
  if (!m.module.includes(t.mod)) return false
  // 含函数部分时，模块下至少要有一个匹配函数，否则该模块被过滤掉
  const funcPart = t.func
  if (funcPart !== null && !m.funcs.some(fn => fn.name.includes(funcPart))) return false
  return true
}

function modulePasses(f: string, m: ModuleNode): boolean {
  const terms = parseFilter(f)
  if (!terms.length) return true
  let include = false
  for (const t of terms) {
    const hit = moduleHit(t, m)
    if (t.excl && hit) return false
    if (!t.excl && hit) include = true
  }
  return include
}

/** 函数级过滤：只由冒号条件的函数部分决定；纯模块过滤时函数全放行 */
function funcPasses(f: string, fn: FuncNode): boolean {
  const colonTerms = parseFilter(f)
    .filter(t => t.func !== null && !t.excl)
    .map(t => t.func as string)
  if (!colonTerms.length) return true
  return colonTerms.some(func => fn.name.includes(func))
}

function hasFuncFilter(f: string): boolean {
  return parseFilter(f).some(t => t.func !== null && !t.excl)
}

function toFnNode(m: ModuleNode, fn: FuncNode) {
  return {
    key: `f:${m.module}:${fn.name}:${fn.arity}`,
    label: `${fn.name}/${fn.arity}${fn.exported ? '' : ' ·local'}${isTraced(m.module, fn.name, fn.arity) ? ' ★' : ''}`,
    isLeaf: true,
    fn,
    moduleRef: m
  }
}

const treeData = computed(() => {
  const f = appliedFilter.value
  return modules.value
    .filter(m => {
      if (!f) return true
      if (f === '#t') return m.funcs.some(fn => isTraced(m.module, fn.name, fn.arity))
      return modulePasses(f, m)
    })
    .map(m => {
      let children: any[] | undefined
      if (f && f !== '#t') {
        const matched = m.funcs.filter(fn => fnMatches(m, fn))
        if (hasFuncFilter(f)) {
          // 冒号过滤函数：无匹配函数的模块整体隐藏
          if (!matched.length) {
            return null
          }
          children = matched.map(fn => toFnNode(m, fn))
        } else {
          // 仅模块名过滤：函数懒加载
          children = undefined
        }
      } else if (f === '#t') {
        children = m.funcs.filter(fn => isTraced(m.module, fn.name, fn.arity)).map(fn => toFnNode(m, fn))
      }
      return {
        key: `m:${m.module}`,
        label: `${m.module}${f && !hasFuncFilter(f) ? ' (' + m.funcs.length + ')' : ''}`,
        isLeaf: false,
        moduleRef: m,
        children
      }
    })
    .filter(x => x !== null)
})

/** 展开模块时懒加载函数列表（无过滤时才走这里） */
async function loadChildren(node: any) {
  const m = node.moduleRef as ModuleNode
  return m ? m.funcs.map(fn => toFnNode(m, fn)) : []
}

async function doStopAll() {
  try {
    await api.stopAllTraces()
    message.success('已停止全部跟踪')
    await refresh()
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  }
}

async function toggleTrace(req: TraceReq) {
  try {
    if (isTraced(req.module, req.function, req.arity)) {
      await api.stopTrace(req)
      message.success('已取消跟踪')
    } else {
      await api.startTrace(req)
      message.success('已跟踪')
    }
    await refresh()
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  }
}

// ---------- 右键菜单（照搬 ModFuncContextMenu） ----------
const menuShow = ref(false)
const menuX = ref(0)
const menuY = ref(0)
const menuTarget = ref<TraceReq | null>(null)
const sourceModal = ref(false)
const sourceTitle = ref('')
const sourceText = ref('')
const graphShow = ref(false)
const graphNode = ref<any>(null)

function openMenu(e: MouseEvent, target: TraceReq) {
  e.preventDefault()
  menuTarget.value = target
  menuX.value = e.clientX
  menuY.value = e.clientY
  menuShow.value = true
}

function menuOptions() {
  const t = menuTarget.value
  if (!t) return []
  if (t.function === '') {
    return [
      { label: '查看源代码', key: 'source' },
      { label: '查看抽象代码', key: 'abst' }
    ]
  }
  const traced = isTraced(t.module, t.function, t.arity)
  return [
    { label: traced ? '取消跟踪' : '函数跟踪', key: 'toggleTrace' },
    { label: '顺序跟踪（实验性）', key: 'seqTrace' },
    { label: '查看调用图', key: 'callGraph' },
    { label: '查看源代码', key: 'source' },
    { label: '查看抽象代码', key: 'abst' }
  ]
}

async function onMenuSelect(key: string) {
  const t = menuTarget.value
  if (!t) return
  menuShow.value = false
  try {
    if (key === 'toggleTrace') await toggleTrace(t)
    else if (key === 'seqTrace') {
      await api.seqTrace(t)
      message.success('顺序跟踪已启动，切换到右侧"顺序跟踪"页查看')
    } else if (key === 'source') {
      const r = await api.source(t.module, t.function || undefined, t.function ? t.arity : undefined)
      sourceTitle.value = `${t.module}${t.function ? ':' + t.function + '/' + t.arity : ''} 源代码`
      sourceText.value = r.source
      sourceModal.value = true
    } else if (key === 'abst') {
      const r = await api.abstractCode(t.module)
      sourceTitle.value = `${t.module} 抽象代码`
      sourceText.value = r.code
      sourceModal.value = true
    } else if (key === 'callGraph') {
      if (!store.status.xrefStarted) await api.startXref()
      const r = await api.callGraph(t.module, t.function, t.arity)
      graphNode.value = r.graph
      graphShow.value = true
    }
  } catch (e: any) {
    message.error(e?.response?.data?.error ?? String(e))
  }
}
</script>

<template>
  <div style="display: flex; flex-direction: column; height: 100%; min-height: 0">
    <n-space vertical :size="8" style="margin-bottom: 10px">
      <n-input
        v-model:value="filterText"
        placeholder="过滤模块；输入 mod: 在其中过滤函数（a|b 多条件、!x 排除、#t 已跟踪）"
        class="eb-filter-input"
        clearable
        size="small"
      />
      <n-space :size="6">
        <n-button size="small" @click="refresh()">刷新</n-button>
        <n-button size="small" :loading="loading" @click="refresh('all')">加载全部模块</n-button>
        <n-button size="small" type="warning" @click="doStopAll">停止全部跟踪</n-button>
      </n-space>
    </n-space>

    <div class="eb-scroll" style="flex: 1; min-height: 0">
      <n-tree
        v-if="treeData.length"
        :data="treeData"
        block-line
        :virtual-scroll="true"
        :load="loadChildren"
        :default-expanded-keys="[]"
        :expand-on-click="true"
        style="height: 100%; font-size: 13px"
        :node-props="(info: any) => ({
          onContextmenu: (e: MouseEvent) => openMenu(
            e,
            info.option.isLeaf
              ? { module: info.option.moduleRef.module, function: info.option.fn.name, arity: info.option.fn.arity, exported: info.option.fn.exported }
              : { module: info.option.moduleRef.module, function: '', arity: 0, exported: false }
          ),
          onDblclick: () => {
            if (info.option.isLeaf) toggleTrace({ module: info.option.moduleRef.module, function: info.option.fn.name, arity: info.option.fn.arity, exported: info.option.fn.exported })
          }
        })"
      />
      <n-empty v-else description="无模块数据 — 请确认已连接并刷新" style="margin-top: 60px" />
    </div>

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

    <n-modal v-model:show="sourceModal">
      <div class="eb-card" style="width: 760px; max-width: 92vw">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px">
          <h3 class="eb-card-title" style="margin: 0">{{ sourceTitle }}</h3>
          <n-button size="small" @click="sourceModal = false">关闭</n-button>
        </div>
        <pre class="eb-scroll" style="max-height: 60vh; font-size: 12px; line-height: 1.55; margin: 0; white-space: pre-wrap; font-family: 'SF Mono', Menlo, monospace">{{ sourceText }}</pre>
      </div>
    </n-modal>

    <n-modal v-model:show="graphShow">
      <div class="eb-card" style="width: 640px; max-width: 92vw; max-height: 70vh; overflow: auto">
        <h3 class="eb-card-title">调用图</h3>
        <p class="eb-card-sub">由 xref 分析生成（首次分析需要一些时间）</p>
        <TermViewer v-if="graphNode" :node="graphNode" style="height: 55vh" />
      </div>
    </n-modal>
  </div>
</template>
