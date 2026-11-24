import { defineStore } from 'pinia'
import { api, ws } from '../api/client'

export interface NodeStatus {
  connected: boolean
  summary: string
  suspended: boolean
  xrefStarted: boolean
  manuallyDisconnected: boolean
  processCount: number
  remoteNode: string | null
}

export interface TraceRow {
  instanceNum: number
  pid: string
  regName: string
  duration: number
  function: string
  args: string
  result: string
  summary: string
  complete: boolean
  cssClass: string | null
  exception: boolean
  stackTrace: any
  modFunc: { module: string; function: string; arity: number } | null
  argsTerm: any
  resultTerm: any
}

export const useNodeStore = defineStore('node', {
  state: () => ({
    status: {
      connected: false,
      summary: 'erlyberly not connected',
      suspended: false,
      xrefStarted: false,
      manuallyDisconnected: false,
      processCount: 0,
      remoteNode: null
    } as NodeStatus,
    traces: [] as TraceRow[],
    processes: [] as any[],
    procUpdateTime: '' as string,
    unreadCrashReports: 0,
    moduleReloadCounter: 0,
    wsConnected: false
  }),
  getters: {
    isConnected: s => s.status.connected
  },
  actions: {
    async refreshStatus() {
      this.status = await api.status()
    },
    async refreshTraces() {
      const r = await api.traceLogs()
      this.traces = r.logs
    },
    bindWs() {
      ws.connect()
      ws.on((type, data) => {
        switch (type) {
          case 'status':
            this.status = data
            break
          case 'trace_logs':
            for (const log of data as TraceRow[]) {
              const idx = this.traces.findIndex(t => t.instanceNum === log.instanceNum)
              if (idx >= 0) this.traces[idx] = log
              else {
                this.traces.push(log)
                if (this.traces.length > 1000) this.traces.shift()
              }
            }
            break
          case 'trace_cleared':
            this.traces = []
            break
          case 'processes':
            this.processes = data.processes
            this.procUpdateTime = data.time
            break
          case 'crash_report':
            this.unreadCrashReports++
            break
          case 'module_loaded':
            this.moduleReloadCounter++
            break
        }
      })
    },
    async connect(local: string, remote: string, cookie: string) {
      await api.connect(local, remote, cookie)
      await this.refreshStatus()
    },
    async disconnect() {
      await api.disconnect()
      await this.refreshStatus()
    }
  }
})
