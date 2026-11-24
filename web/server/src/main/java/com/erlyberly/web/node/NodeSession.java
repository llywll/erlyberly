package com.erlyberly.web.node;

import static erlyberly.node.OtpUtil.OK_ATOM;
import static erlyberly.node.OtpUtil.atom;
import static erlyberly.node.OtpUtil.isTupleTagged;
import static erlyberly.node.OtpUtil.list;

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangString;
import com.ericsson.otp.erlang.OtpErlangTuple;
import erlyberly.CrashReport;
import erlyberly.ModFunc;
import erlyberly.ProcInfo;
import erlyberly.TraceLog;
import com.erlyberly.web.util.OtpJson;
import erlyberly.node.NodeAPI;

import java.util.function.BiConsumer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个连接会话：持有独立的 NodeAPI 实例，事件通过 broadcaster 按会话推送（支持多开）。
 */
public class NodeSession {

    private final String sessionId;
    private final NodeAPI nodeAPI;
    /** (type, data) → SessionManager 按会话路由到对应 WebSocket 连接 */
    private final BiConsumer<String, Object> broadcaster;
    private final ScheduledExecutorService scheduler;

    /** trace 日志推送队列（FX 线程产生，定时批量刷出） */
    private final ConcurrentLinkedDeque<Map<String, Object>> traceQueue = new ConcurrentLinkedDeque<>();
    /** 环形缓冲（按 instanceNum 去重，保持插入序），保存最近 trace 日志供 REST 拉取 */
    private final LinkedHashMap<Long, Map<String, Object>> traceBuffer = new LinkedHashMap<>();
    private final Object traceBufferLock = new Object();
    private static final int TRACE_BUFFER_MAX = 1000;
    private static final int TRACE_BATCH_MAX = 500;

    private final AtomicBoolean procPolling = new AtomicBoolean(false);
    private final AtomicBoolean seqPolling = new AtomicBoolean(false);

    /** Erlang 端跟踪状态：重连后需要重挂（照搬 reapplyTraces） */
    private final List<Map<String, Object>> activeTraces = new ArrayList<>();
    private final Object traceLock = new Object();

    public NodeSession(String sessionId, BiConsumer<String, Object> broadcaster) {
        this.sessionId = sessionId;
        this.broadcaster = broadcaster;
        this.nodeAPI = new NodeAPI();
        this.scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "erlyberly-web-" + sessionId + "-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        init();
    }

    public String getSessionId() {
        return sessionId;
    }

    public void init() {
        // 连接状态变化 → status 事件
        nodeAPI.connectedProperty().addListener((o, ov, nv) -> publishStatus());
        nodeAPI.xrefStartedProperty().addListener((o, ov, nv) -> publishStatus());
        nodeAPI.suspendedProperty().addListener((o, ov, nv) -> publishStatus());

        // 崩溃报告 → crash_report 事件
        nodeAPI.getCrashReports().addListener((javafx.collections.ListChangeListener<OtpErlangObject>) change -> {
            while (change.next()) {
                for (OtpErlangObject obj : change.getAddedSubList()) {
                    Map<String, Object> dto = crashReportDto(new CrashReport(obj));
                    broadcaster.accept("crash_report", dto);
                }
            }
        });

        // 模块重载 → module_loaded 事件
        nodeAPI.setModuleLoadedCallback(tuple -> broadcaster.accept("module_loaded", moduleLoadedDto(tuple)));

        // trace 日志回调 → 队列，由定时器批量推送
        nodeAPI.setTraceLogCallback(this::enqueueTrace);
        // 调用完成（return/exception）事件也推一份（照搬桌面版属性联动）。
        // complete() 通过 Platform.runLater 异步设置标志，这里也排到 FX 线程队列之后，
        // 保证快照时 complete/summary 已更新。
        nodeAPI.setTraceCompletionListener(log ->
                javafx.application.Platform.runLater(() -> enqueueTrace(log)));

        // trace 日志批量刷送（150ms）
        scheduler.scheduleWithFixedDelay(this::flushTraces, 150, 150, TimeUnit.MILLISECONDS);

        // 连接后自动开始进程轮询（照搬原应用行为：连接即显示进程列表）
        scheduler.scheduleWithFixedDelay(this::pollProcessesIfConnected, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
        try {
            shellStop();
        } catch (Exception ignored) {
        }
        try {
            nodeAPI.disconnect();
        } catch (Exception ignored) {
        }
    }

    // ---------- 连接管理 ----------

    public synchronized void connect(String localNodeName, String remoteNodeName, String cookie) throws Exception {
        nodeAPI.connectionInfo(localNodeName, remoteNodeName, cookie);
        nodeAPI.manualConnect();
        // 连接成功后重挂之前的跟踪（照搬 reapplyTraces）
        synchronized (traceLock) {
            for (Map<String, Object> mf : activeTraces) {
                try {
                    nodeAPI.startTrace(toModFunc(mf), maxTraceQueueLength());
                } catch (Exception e) {
                    broadcaster.accept("trace_error", e.getMessage());
                }
            }
        }
        // 进程列表不默认轮询（用户在进程页手动开启）
        // 连接时重启中转 shell（目标节点可能重启过，旧 shell 已失效）
        try {
            shellStop();
            shellStart();
        } catch (Exception e) {
            System.out.println("[shell] 自动启动失败: " + e.getMessage());
        }
        publishStatus();
    }

    public synchronized void disconnect() {
        shellStop();
        try {
            nodeAPI.manuallyDisconnect();
        } catch (Exception e) {
            // 照搬原实现：手动断开时的关闭异常可以忽略
        }
        nodeAPI.disconnect();
        publishStatus();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connected", nodeAPI.isConnected());
        m.put("summary", nodeAPI.summaryProperty().getValue());
        m.put("suspended", nodeAPI.suspendedProperty().get());
        m.put("xrefStarted", nodeAPI.xrefStartedProperty().get());
        m.put("manuallyDisconnected", nodeAPI.manuallyDisconnected());
        m.put("remoteNode", nodeAPI.getRemoteNodeName());
        erlyberly.node.AppProcs procs = nodeAPI.appProcsProperty().get();
        m.put("processCount", procs != null ? procs.getProcCount() : 0);
        m.put("procUpdateTime", procs != null ? procs.getDateTime().toString() : null);
        return m;
    }

    private void publishStatus() {
        broadcaster.accept("status", status());
    }

    // ---------- 进程 ----------

    public List<Map<String, Object>> processes() throws Exception {
        List<ProcInfo> infos = new ArrayList<>();
        nodeAPI.retrieveProcessInfo(infos);
        List<Map<String, Object>> out = new ArrayList<>(infos.size());
        for (ProcInfo p : infos) {
            out.add(procDto(p));
        }
        return out;
    }

    public void setProcPolling(boolean on) {
        procPolling.set(on);
    }

    public boolean isProcPolling() {
        return procPolling.get();
    }

    private void pollProcessesIfConnected() {
        if (!procPolling.get() || !nodeAPI.isConnected()) {
            return;
        }
        try {
            List<Map<String, Object>> procs = processes();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("processes", procs);
            data.put("count", procs.size());
            data.put("time", LocalDateTime.now().toString());
            broadcaster.accept("processes", data);
        } catch (Exception e) {
            // 探活/重连期间可能失败，静默跳过
        }
    }

    private Map<String, Object> procDto(ProcInfo p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", p.getPid());
        m.put("name", p.getProcessName());
        m.put("shortName", p.getShortName());
        m.put("reductions", p.getReductions());
        m.put("msgQueueLen", p.getMsgQueueLen());
        m.put("heapSize", p.getHeapSize());
        m.put("stackSize", p.getStackSize());
        m.put("totalHeapSize", p.getTotalHeapSize());
        return m;
    }

    // ---------- 进程操作 ----------

    public Map<String, Object> processState(String pid) throws Exception {
        return OtpJson.toJson(nodeAPI.getProcessState(pid));
    }

    public Map<String, Object> processDictionary(String pid) throws Exception {
        return OtpJson.toJson(nodeAPI.getProcessDictionary(pid));
    }

    public Map<String, Object> processMessages(String pid) throws Exception {
        return OtpJson.toJson(nodeAPI.getProcessMessages(pid));
    }

    public Map<String, Object> sendProcessMessage(String pid, String type, String msg) throws Exception {
        OtpErlangObject result = nodeAPI.sendMessage(pid, type, msg);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("term", OtpJson.toJson(result));
        m.put("text", result != null ? OtpJson.fmt().toString(result) : "ok");
        // 友好错误提示（照搬 SendMsgView）
        if (result instanceof OtpErlangTuple t && t.arity() == 2 && t.elementAt(0) instanceof OtpErlangAtom a) {
            String tag = a.atomValue();
            if ("error".equals(tag) && t.elementAt(1) instanceof OtpErlangAtom reason) {
                m.put("error", switch (reason.atomValue()) {
                    case "timeout" -> "超时：目标进程没有在规定时间内回复";
                    case "noproc" -> "目标进程不存在";
                    default -> reason.atomValue();
                });
            } else if ("ok".equals(tag)) {
                m.put("ok", true);
            }
        } else if (result instanceof OtpErlangAtom a && ("cast_sent".equals(a.atomValue()) || "info_sent".equals(a.atomValue()))) {
            m.put("ok", true);
        }
        return m;
    }

    // ---------- 模块/函数 ----------

    public List<Map<String, Object>> functions(String mode) throws Exception {
        OtpErlangList result = switch (mode == null ? "loaded" : mode) {
            case "all" -> nodeAPI.requestFunctionsAll();
            default -> nodeAPI.requestFunctions();
        };
        List<Map<String, Object>> out = new ArrayList<>();
        for (OtpErlangObject e : result) {
            OtpErlangTuple tuple = (OtpErlangTuple) e;
            String module = ((OtpErlangAtom) tuple.elementAt(0)).atomValue();
            List<Map<String, Object>> funcs = new ArrayList<>();
            addFuncs(funcs, (OtpErlangList) tuple.elementAt(1), true);
            addFuncs(funcs, (OtpErlangList) tuple.elementAt(2), false);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", module);
            m.put("funcs", funcs);
            out.add(m);
        }
        return out;
    }

    private void addFuncs(List<Map<String, Object>> out, OtpErlangList funcs, boolean exported) {
        for (OtpErlangObject o : funcs) {
            try {
                OtpErlangTuple t = (OtpErlangTuple) o;
                String name = ((OtpErlangAtom) t.elementAt(0)).atomValue();
                int arity = ((OtpErlangLong) t.elementAt(1)).intValue();
                ModFunc mf = new ModFunc("", name, arity, exported, false);
                if (mf.isSynthetic()) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("arity", arity);
                m.put("exported", exported);
                out.add(m);
            } catch (Exception ignored) {
                // 跳过无法解析的函数项
            }
        }
    }

    // ---------- 跟踪 ----------

    public synchronized void startTrace(String module, String function, int arity, boolean exported) throws Exception {
        ModFunc mf = new ModFunc(module, function, arity, exported, false);
        nodeAPI.startTrace(mf, maxTraceQueueLength());
        synchronized (traceLock) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("module", module);
            m.put("function", function);
            m.put("arity", arity);
            m.put("exported", exported);
            activeTraces.add(m);
        }
    }

    public synchronized void stopTrace(String module, String function, int arity, boolean exported) throws Exception {
        ModFunc mf = new ModFunc(module, function, arity, exported, false);
        nodeAPI.stopTrace(mf);
        synchronized (traceLock) {
            activeTraces.removeIf(m ->
                    module.equals(m.get("module")) && function.equals(m.get("function"))
                            && (Integer) m.get("arity") == arity);
        }
    }

    public synchronized void stopAllTraces() throws Exception {
        nodeAPI.stopAllTraces();
        synchronized (traceLock) {
            activeTraces.clear();
        }
    }

    public List<Map<String, Object>> activeTraces() {
        synchronized (traceLock) {
            return new ArrayList<>(activeTraces);
        }
    }

    public synchronized void seqTrace(String module, String function, int arity, boolean exported) throws Exception {
        nodeAPI.seqTrace(new ModFunc(module, function, arity, exported, false));
    }

    public void setSeqPolling(boolean on) {
        seqPolling.set(on);
    }

    private void enqueueTrace(TraceLog log) {
        traceQueue.add(traceDto(log));
    }

    private void flushTraces() {
        if (traceQueue.isEmpty()) {
            return;
        }
        List<Map<String, Object>> batch = new ArrayList<>();
        Map<String, Object> first = traceQueue.poll();
        if (first == null) {
            return;
        }
        batch.add(first);
        Map<String, Object> item;
        while (batch.size() < TRACE_BATCH_MAX && (item = traceQueue.poll()) != null) {
            batch.add(item);
        }
        // 维护环形缓冲（同一 instanceNum 的完成事件覆盖旧快照）
        synchronized (traceBufferLock) {
            for (Map<String, Object> t : batch) {
                long num = (Long) t.get("instanceNum");
                traceBuffer.remove(num);
                traceBuffer.put(num, t);
            }
            while (traceBuffer.size() > TRACE_BUFFER_MAX) {
                Long firstKey = traceBuffer.keySet().iterator().next();
                traceBuffer.remove(firstKey);
            }
        }
        broadcaster.accept("trace_logs", batch);
    }

    public List<Map<String, Object>> traceBuffer() {
        synchronized (traceBufferLock) {
            return new ArrayList<>(traceBuffer.values());
        }
    }

    public void clearTraces() {
        synchronized (traceBufferLock) {
            traceBuffer.clear();
        }
        broadcaster.accept("trace_cleared", null);
    }

    private Map<String, Object> traceDto(TraceLog log) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("instanceNum", log.getInstanceNum());
        m.put("pid", safeGet(() -> log.getPid()));
        m.put("regName", log.getRegName());
        m.put("duration", log.getDuration());
        m.put("function", log.getFunction());
        m.put("args", log.getArgs());
        m.put("result", log.getResult());
        m.put("summary", log.toString());
        m.put("complete", log.isComplete());
        m.put("cssClass", log.getCssClass());
        m.put("exception", log.isExceptionThrower());
        try {
            m.put("stackTrace", OtpJson.toJson(log.getStackTrace()));
        } catch (Exception e) {
            m.put("stackTrace", List.of());
        }
        try {
            ModFunc mf = log.getModFunc();
            m.put("modFunc", Map.of("module", mf.getModuleName(), "function", mf.getFuncName(), "arity", mf.getArity()));
        } catch (Exception e) {
            m.put("modFunc", null);
        }
        // 详细参数与结果（术语树）
        try {
            m.put("argsTerm", OtpJson.toJson(log.getArgsList()));
        } catch (Exception e) {
            m.put("argsTerm", null);
        }
        try {
            m.put("resultTerm", OtpJson.toJson(log.getResultFromMap()));
        } catch (Exception e) {
            m.put("resultTerm", null);
        }
        return m;
    }

    private <T> T safeGet(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 顺序跟踪 ----------

    public List<Map<String, Object>> seqTraceLogs() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (erlyberly.SeqTraceLog log : nodeAPI.collectSeqTraceLogs()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("msgType", String.valueOf(log.getMsgType()));
            m.put("timestamp", String.valueOf(log.getTimestamp()));
            m.put("serial", String.valueOf(log.getSerial()));
            m.put("from", String.valueOf(log.getFrom()));
            m.put("to", String.valueOf(log.getTo()));
            m.put("message", OtpJson.toJson(log.getMessage()));
            out.add(m);
        }
        return out;
    }

    // ---------- ETS ----------

    /** ETS 表列表 → 结构化行：{name, type, size, memory, owner} */
    public List<Map<String, Object>> etsTables() throws Exception {
        OtpErlangObject tables = nodeAPI.getEtsTables();
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(tables instanceof OtpErlangList list)) {
            return out;
        }
        OtpErlangAtom nameKey = new OtpErlangAtom("name");
        OtpErlangAtom typeKey = new OtpErlangAtom("type");
        OtpErlangAtom sizeKey = new OtpErlangAtom("size");
        OtpErlangAtom memoryKey = new OtpErlangAtom("memory");
        OtpErlangAtom ownerKey = new OtpErlangAtom("owner");
        for (OtpErlangObject elem : list.elements()) {
            try {
                Map<Object, Object> props = erlyberly.node.OtpUtil.propsToMap((OtpErlangList) elem);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", String.valueOf(props.get(nameKey)));
                m.put("type", String.valueOf(props.get(typeKey)));
                m.put("size", String.valueOf(props.get(sizeKey)));
                m.put("memory", String.valueOf(props.get(memoryKey)));
                // owner 是 {owner_pid, Pid, owner_name, Name} 四元组
                Object owner = props.get(ownerKey);
                if (owner instanceof OtpErlangTuple t && t.arity() == 4) {
                    Object pid = t.elementAt(1);
                    Object name = t.elementAt(3);
                    m.put("owner", (name instanceof OtpErlangAtom na && !"undefined".equals(na.atomValue())
                            ? na.atomValue() + " " : "") + pid);
                } else {
                    m.put("owner", owner != null ? owner.toString() : "");
                }
                out.add(m);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    public Map<String, Object> etsTableInfo(String name) throws Exception {
        return OtpJson.toJson(nodeAPI.getEtsTableInfo(name));
    }

    // ---------- 内存 ----------

    public Map<String, Object> erlangMemory() throws Exception {
        Map<Object, Object> mem = nodeAPI.erlangMemory();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> e : mem.entrySet()) {
            String key = e.getKey().toString();
            // 照搬 TopBarView：剔除汇总类指标
            if ("maximum".equals(key) || "total".equals(key) || "system".equals(key)) {
                continue;
            }
            if (e.getValue() instanceof OtpErlangLong l) {
                out.put(key, l.longValue());
            } else {
                out.put(key, e.getValue().toString());
            }
        }
        return out;
    }

    // ---------- xref / 调用图 ----------

    public void startXref() throws IOException {
        nodeAPI.asyncEnsureXRefStarted();
    }

    public Map<String, Object> callGraph(String module, String function, int arity) throws Exception {
        String skipped = erlyberly.PrefBind.getOrDefault("xrefSkippedModules", "").toString();
        List<OtpErlangAtom> skippedAtoms = new ArrayList<>();
        for (String s : skipped.split(",")) {
            if (!s.isBlank()) {
                skippedAtoms.add(new OtpErlangAtom(s.trim()));
            }
        }
        OtpErlangObject result = nodeAPI.callGraph(
                new OtpErlangList(skippedAtoms.toArray(new OtpErlangObject[0])),
                new OtpErlangAtom(module),
                new OtpErlangAtom(function),
                new OtpErlangLong(arity));
        return OtpJson.toJson(result);
    }

    // ---------- 源码 / 抽象代码 ----------

    public String sourceCode(String module, String function, Integer arity) throws Exception {
        if (function != null) {
            return nodeAPI.moduleFunctionSourceCode(module, function, arity);
        }
        return nodeAPI.moduleFunctionSourceCode(module);
    }

    public String abstractCode(String module) throws Exception {
        return nodeAPI.moduleFunctionAbstCode(module);
    }

    // ---------- 崩溃报告 ----------

    public List<Map<String, Object>> crashReports() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (OtpErlangObject obj : nodeAPI.getCrashReports()) {
            out.add(crashReportDto(new CrashReport(obj)));
        }
        return out;
    }

    public void clearCrashReports() {
        nodeAPI.getCrashReports().clear();
    }

    private Map<String, Object> crashReportDto(CrashReport cr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", cr.getPid() != null ? cr.getPid().toString() : null);
        m.put("registeredName", cr.getRegisteredName());
        m.put("errorClass", cr.getErrorClass() != null ? cr.getErrorClass().atomValue() : null);
        m.put("errorReason", OtpJson.toJson(cr.getErrorReason()));
        m.put("dateTime", cr.getDateTime() != null ? cr.getDateTime().toString() : null);
        m.put("props", OtpJson.toJson(cr.getProps()));
        m.put("initialCall", OtpJson.toJson((com.ericsson.otp.erlang.OtpErlangObject) cr.getProcessInitialCall()));
        return m;
    }

    private Map<String, Object> moduleLoadedDto(OtpErlangTuple tuple) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("module", String.valueOf(tuple.elementAt(0)));
        m.put("funcs", funcsFromTupleElement(tuple, 1, true));
        return m;
    }

    private List<Map<String, Object>> funcsFromTupleElement(OtpErlangTuple tuple, int idx, boolean exported) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            addFuncs(out, (OtpErlangList) tuple.elementAt(idx), exported);
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------- 挂起 ----------

    public synchronized boolean toggleSuspended() throws Exception {
        nodeAPI.toggleSuspended();
        return nodeAPI.suspendedProperty().get();
    }

    public boolean isSuspended() {
        return nodeAPI.suspendedProperty().get();
    }

    // ---------- Erlang 控制台（pty + erl -remsh 真终端：web 转发 IO，remsh 客户端连目标节点） ----------

    /** script(pty) 包装的 erl -remsh 客户端进程 */
    private Process shellProc;
    private java.io.OutputStream shellStdin;
    /** 客户端节点名唯一标记，shellStop 时 pkill 兜底清理 script 的 erl 子进程 */
    private volatile String shellProcTag;
    private volatile boolean shellProcRunning = false;
    /** 终端输出回放缓冲（页面刷新/重开后重放） */
    private final StringBuilder shellReplay = new StringBuilder();
    private static final int SHELL_REPLAY_MAX = 64 * 1024;

    /** 启动 pty remsh 控制台（真实 shell 在 remsh 客户端进程内，经 pty 转发浏览器 IO） */
    public synchronized Map<String, Object> shellStart() throws Exception {
        if (!nodeAPI.isConnected()) {
            throw new Exception("未连接节点");
        }
        if (shellProcRunning) {
            String replay;
            synchronized (shellReplay) {
                replay = shellReplay.toString();
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("running", true);
            m.put("already", true);
            m.put("replay", replay);
            return m;
        }
        String remote = nodeAPI.getRemoteNodeName();
        if (remote == null || remote.isEmpty()) {
            throw new Exception("未连接节点");
        }
        // 目标带点号主机名 → 长名节点（jinterface/remsh 均要求长名对长名）
        boolean longName = remote.contains("@") && remote.substring(remote.indexOf('@') + 1).contains(".");
        String tag = "ebpty" + Long.toHexString(System.currentTimeMillis())
                + "_" + Math.abs(sessionId.hashCode() % 997);
        List<String> cmd = new ArrayList<>();
        cmd.add("script");
        cmd.add("-q");
        cmd.add("/dev/null"); // typescript 弃用，输出走 script 的 stdout
        cmd.add("erl");
        if (longName) {
            cmd.add("-name");
            cmd.add(tag + "@127.0.0.1");
        } else {
            cmd.add("-sname");
            cmd.add(tag);
        }
        // cookie 为空必须省略 -setcookie（-setcookie "" 会让 erl 秒退）
        String cookie = nodeAPI.getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            cmd.add("-setcookie");
            cmd.add(cookie);
        }
        cmd.add("-remsh");
        cmd.add(remote);

        shellProc = new ProcessBuilder(cmd).start();
        shellStdin = shellProc.getOutputStream();
        shellProcTag = tag;
        synchronized (shellReplay) {
            shellReplay.setLength(0);
        }
        shellProcRunning = true;

        // 本机 -name 交互节点 user_drv 启动极慢（banner 可达 30~40 秒），提前告知前端
        broadcaster.accept("shell_out", "— 正在启动 remsh 真终端（本机交互节点启动较慢，提示符出现约需 30~40 秒）—\n");

        Thread outT = new Thread(() -> readShellStream(shellProc.getInputStream()), "shellout-" + sessionId);
        outT.setDaemon(true);
        outT.start();
        Thread errT = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(shellProc.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    System.out.println("[shell stderr] " + l);
                }
            } catch (Exception ignored) {
            }
        }, "shellerr-" + sessionId);
        errT.setDaemon(true);
        errT.start();

        // remsh 附着后首个提示符有时不主动 flush（实测卡 15+ 分钟，发一个换行即出）。
        // 8 秒后回放里仍无远程提示符就发空行催一下；对已完成附着的 shell 只是多个空行，无害。
        String promptMark = "(" + remote + ")";
        scheduler.schedule(() -> {
            if (!shellProcRunning) {
                return;
            }
            boolean hasPrompt;
            synchronized (shellReplay) {
                hasPrompt = shellReplay.toString().contains(promptMark);
            }
            if (!hasPrompt) {
                try {
                    shellWrite("\n");
                } catch (Exception ignored) {
                }
            }
        }, 8, TimeUnit.SECONDS);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("running", true);
        return m;
    }

    /** 提交一行输入到 remsh 客户端 pty（REST /shell/eval 用，自动补 \n）。运行期间 stdin 必须保持常开（EOF 会杀掉 shell 作业） */
    public void shellEval(String line) throws IOException {
        shellWrite(line + "\n");
    }

    /** 原始按键流直通 pty（xterm.js shell_in 用：回显/提示符/Tab 补全/^G 全由 pty 内的真 shell 处理） */
    public void shellWrite(String raw) throws IOException {
        if (!shellProcRunning || shellStdin == null) {
            throw new IllegalStateException("控制台未运行");
        }
        synchronized (shellStdin) {
            shellStdin.write(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            shellStdin.flush();
        }
    }

    public synchronized void shellStop() {
        shellProcRunning = false;
        try {
            if (shellStdin != null) {
                shellStdin.close(); // 停止时主动 EOF 结束 shell 作业
            }
        } catch (Exception ignored) {
        }
        if (shellProc != null) {
            shellProc.destroy();
            shellProc.destroyForcibly();
            shellProc = null;
        }
        if (shellProcTag != null) {
            try {
                // 同步等待 pkill 完成：JVM 退出时 fire-and-forget 的子进程可能没机会跑，会留孤儿 remsh
                Process pk = new ProcessBuilder("pkill", "-9", "-f", shellProcTag).start();
                pk.waitFor(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            shellProcTag = null;
        }
        shellStdin = null;
        broadcaster.accept("shell_exit", Map.of("reason", "stopped"));
    }

    /** shell 输出流 → shell_out 事件 + 回放缓冲。按原始块推送（xterm 直通）：行缓冲会吃掉逐键回显，严禁按行攒 */
    private void readShellStream(java.io.InputStream in) {
        try (java.io.Reader r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buf = new char[512];
            int n;
            while ((n = r.read(buf)) != -1) {
                emitShellText(new String(buf, 0, n));
            }
        } catch (Exception ignored) {
        }
        if (shellProcRunning) {
            shellProcRunning = false;
            broadcaster.accept("shell_exit", Map.of("reason", "remsh client exited"));
        }
    }

    private void emitShellText(String text) {
        synchronized (shellReplay) {
            shellReplay.append(text);
            if (shellReplay.length() > SHELL_REPLAY_MAX) {
                shellReplay.delete(0, shellReplay.length() - SHELL_REPLAY_MAX);
            }
        }
        broadcaster.accept("shell_out", text);
    }

    // ---------- 配置 ----------

    public Map<String, Object> preferences() {
        Map<String, Object> m = new LinkedHashMap<>();
        String[] keys = {"termFormatting", "maxTraceLogs", "maxTraceQueueLength", "loadModulesRegex",
                "xrefSkippedModules", "autoConnect", "targetNodeName", "localNodeName", "cookieName",
                "showSourceInSystemEditor"};
        for (String k : keys) {
            Object v = erlyberly.PrefBind.getOrDefault(k, null);
            if (v != null) {
                m.put(k, v);
            }
        }
        return m;
    }

    public void setPreference(String key, Object value) {
        erlyberly.PrefBind.set(key, value);
    }

    private int maxTraceQueueLength() {
        return erlyberly.PrefBind.getMaxTraceQueueLengthConfig();
    }

    private ModFunc toModFunc(Map<String, Object> m) {
        return new ModFunc(
                (String) m.get("module"),
                (String) m.get("function"),
                (Integer) m.get("arity"),
                (Boolean) m.get("exported"),
                false);
    }
}
