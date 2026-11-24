import axios from 'axios'

/**
 * 每个浏览器标签页一个独立的连接会话（sessionStorage 按标签隔离），
 * 支持多开：不同标签可以连接不同节点互不干扰。
 */
const SID_KEY = 'erlyberly-session-id'
let sessionId: string = sessionStorage.getItem(SID_KEY) ?? ''
if (!sessionId) {
  sessionId =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID().slice(0, 8)
      : Math.random().toString(36).slice(2, 10)
  sessionStorage.setItem(SID_KEY, sessionId)
}

const http = axios.create({ baseURL: '/api', timeout: 45000 })

http.interceptors.request.use(cfg => {
  cfg.params = { ...(cfg.params ?? {}), session: sessionId }
  return cfg
})

export interface TraceReq {
  module: string
  function: string
  arity: number
  exported: boolean
}

export const api = {
  status: () => http.get('/status').then(r => r.data),
  connect: (localNodeName: string, remoteNodeName: string, cookie: string) =>
    http.post('/connect', { localNodeName, remoteNodeName, cookie }).then(r => r.data),
  disconnect: () => http.post('/disconnect').then(r => r.data),
  functions: (mode: 'loaded' | 'all') => http.get('/functions', { params: { mode }, timeout: 45000 }).then(r => r.data),
  startTrace: (req: TraceReq) => http.post('/trace/start', req).then(r => r.data),
  stopTrace: (req: TraceReq) => http.post('/trace/stop', req).then(r => r.data),
  stopAllTraces: () => http.post('/trace/stop-all').then(r => r.data),
  activeTraces: () => http.get('/trace/active').then(r => r.data),
  traceLogs: () => http.get('/trace/logs').then(r => r.data),
  clearTraces: () => http.delete('/trace/logs').then(r => r.data),
  toggleSuspend: () => http.post('/trace/suspend').then(r => r.data),
  processes: () => http.get('/processes').then(r => r.data),
  setProcPolling: (on: boolean) => http.post('/processes/polling', null, { params: { on } }).then(r => r.data),
  processState: (pid: string) => http.get(`/process/${encodeURIComponent(pid)}/state`).then(r => r.data),
  processDict: (pid: string) => http.get(`/process/${encodeURIComponent(pid)}/dictionary`).then(r => r.data),
  processMsgs: (pid: string) => http.get(`/process/${encodeURIComponent(pid)}/messages`).then(r => r.data),
  sendMessage: (pid: string, type: string, msg: string) =>
    http.post(`/process/${encodeURIComponent(pid)}/message`, { type, msg }).then(r => r.data),
  seqTrace: (req: TraceReq) => http.post('/seqtrace', req).then(r => r.data),
  seqTraceLogs: () => http.get('/seqtrace/logs').then(r => r.data),
  etsTables: () => http.get('/ets').then(r => r.data),
  etsTableInfo: (name: string) => http.get(`/ets/${encodeURIComponent(name)}`).then(r => r.data),
  memory: () => http.get('/memory').then(r => r.data),
  startXref: () => http.post('/xref/start').then(r => r.data),
  callGraph: (module: string, fn: string, arity: number) =>
    http.get('/callgraph', { params: { module, function: fn, arity }, timeout: 60000 }).then(r => r.data),
  source: (module: string, fn?: string, arity?: number) =>
    http.get('/source', { params: { module, function: fn, arity }, timeout: 60000 }).then(r => r.data),
  abstractCode: (module: string) => http.get('/abstract-code', { params: { module }, timeout: 60000 }).then(r => r.data),
  crashReports: () => http.get('/crash-reports').then(r => r.data),
  clearCrashReports: () => http.delete('/crash-reports').then(r => r.data),
  preferences: () => http.get('/preferences').then(r => r.data),
  shellStart: () => http.post('/shell/start', {}, { timeout: 60000 }).then(r => r.data),
  shellEval: (input: string) => http.post('/shell/eval', { input }).then(r => r.data),
  shellStop: () => http.post('/shell/stop').then(r => r.data),
  setPreference: (key: string, value: unknown) => http.put('/preferences', { key, value }).then(r => r.data)
}

/** WebSocket 单例：接收 status/trace_logs/processes/crash_report/module_loaded 事件 */
export type WsHandler = (type: string, data: any) => void

class WsClient {
  private ws: WebSocket | null = null
  private handlers: WsHandler[] = []
  private retryTimer: number | null = null

  connect() {
    if (this.ws && (this.ws.readyState === WebSocket.OPEN || this.ws.readyState === WebSocket.CONNECTING)) return
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    this.ws = new WebSocket(`${proto}://${location.host}/ws?session=${encodeURIComponent(sessionId)}`)
    this.ws.onmessage = ev => {
      try {
        const msg = JSON.parse(ev.data)
        this.handlers.forEach(h => h(msg.type, msg.data))
      } catch {
        /* ignore */
      }
    }
    this.ws.onclose = () => {
      if (this.retryTimer) window.clearTimeout(this.retryTimer)
      this.retryTimer = window.setTimeout(() => this.connect(), 2000)
    }
  }

  on(h: WsHandler) {
    this.handlers.push(h)
    return () => {
      this.handlers = this.handlers.filter(x => x !== h)
    }
  }

  /** 客户端 → 服务端消息（如控制台 shell_in 按键流） */
  send(type: string, data: unknown) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type, data }))
    }
  }
}

export const ws = new WsClient()

/** 会话级标记：自动连接只在应用加载后的首次进入连接页时执行一次 */
export const sessionFlags = {
  autoConnectRan: false
}

/** 照搬 BasicSearch 语义：`a|b` 多条件、`!x` 排除，全部子串匹配 */
export function basicSearch(text: string, target: string | null | undefined): boolean {
  if (!text) return true
  const t = target ?? ''
  let includes = false
  let found = false
  for (const term of text.split('|')) {
    const trimmed = term.trim()
    if (!trimmed) continue
    found = true
    if (trimmed.startsWith('!')) {
      const excl = trimmed.substring(1)
      if (excl && t.includes(excl)) return false
      includes = true
    } else if (t.includes(trimmed)) {
      includes = true
    }
  }
  return found ? includes : true
}

/** 防抖（照搬 300ms 行为） */
export function debounce<T extends (...args: any[]) => void>(fn: T, ms = 300) {
  let timer: number | null = null
  return (...args: Parameters<T>) => {
    if (timer) window.clearTimeout(timer)
    timer = window.setTimeout(() => fn(...args), ms)
  }
}
