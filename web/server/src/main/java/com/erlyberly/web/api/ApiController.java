package com.erlyberly.web.api;

import com.erlyberly.web.node.NodeSession;
import com.erlyberly.web.node.SessionContextHolder;
import com.erlyberly.web.node.SessionManager;
import com.erlyberly.web.util.OtpJson;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API。所有实时事件走 /ws WebSocket，这里只做请求/响应式操作。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final SessionManager sessionManager;

    public ApiController(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /** 当前请求所属的连接会话（由 SessionContextFilter 解析 ?session= / X-Session-Id） */
    private NodeSession node() {
        return sessionManager.getOrCreate(SessionContextHolder.current());
    }

    public record ConnectRequest(String localNodeName, String remoteNodeName, String cookie) {
    }

    public record TraceRequest(String module, String function, int arity, boolean exported) {
    }

    public record SendMessageRequest(String type, String msg) {
    }

    public record PrefRequest(String key, Object value) {
    }

    // ---------- 连接 ----------

    @PostMapping("/connect")
    public Map<String, Object> connect(@RequestBody ConnectRequest req) throws Exception {
        node().connect(req.localNodeName(), req.remoteNodeName(), req.cookie() == null ? "" : req.cookie());
        return node().status();
    }

    @PostMapping("/disconnect")
    public Map<String, Object> disconnect() {
        node().disconnect();
        return node().status();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return node().status();
    }

    // ---------- 进程 ----------

    @GetMapping("/processes")
    public Map<String, Object> processes(@RequestParam(required = false, defaultValue = "false") boolean poll) throws Exception {
        if (poll) {
            node().setProcPolling(true);
        }
        List<Map<String, Object>> procs = node().processes();
        return Map.of("processes", procs, "count", procs.size());
    }

    @PostMapping("/processes/polling")
    public Map<String, Object> procPolling(@RequestParam boolean on) {
        node().setProcPolling(on);
        return Map.of("polling", node().isProcPolling());
    }

    @GetMapping("/process/{pid}/state")
    public Map<String, Object> processState(@PathVariable String pid) throws Exception {
        return node().processState(pid);
    }

    @GetMapping("/process/{pid}/dictionary")
    public Map<String, Object> processDictionary(@PathVariable String pid) throws Exception {
        return node().processDictionary(pid);
    }

    @GetMapping("/process/{pid}/messages")
    public Map<String, Object> processMessages(@PathVariable String pid) throws Exception {
        return node().processMessages(pid);
    }

    @PostMapping("/process/{pid}/message")
    public Map<String, Object> sendMessage(@PathVariable String pid, @RequestBody SendMessageRequest req) throws Exception {
        return node().sendProcessMessage(pid, req.type(), req.msg());
    }

    // ---------- 模块/函数 ----------

    @GetMapping("/functions")
    public Map<String, Object> functions(@RequestParam(required = false, defaultValue = "loaded") String mode) throws Exception {
        return Map.of("modules", node().functions(mode));
    }

    // ---------- 跟踪 ----------

    @PostMapping("/trace/start")
    public Map<String, Object> startTrace(@RequestBody TraceRequest req) throws Exception {
        node().startTrace(req.module(), req.function(), req.arity(), req.exported());
        return Map.of("ok", true, "traces", node().activeTraces());
    }

    @PostMapping("/trace/stop")
    public Map<String, Object> stopTrace(@RequestBody TraceRequest req) throws Exception {
        node().stopTrace(req.module(), req.function(), req.arity(), req.exported());
        return Map.of("ok", true, "traces", node().activeTraces());
    }

    @PostMapping("/trace/stop-all")
    public Map<String, Object> stopAllTraces() throws Exception {
        node().stopAllTraces();
        return Map.of("ok", true);
    }

    @GetMapping("/trace/active")
    public Map<String, Object> activeTraces() {
        return Map.of("traces", node().activeTraces());
    }

    @GetMapping("/trace/logs")
    public Map<String, Object> traceLogs() {
        return Map.of("logs", node().traceBuffer());
    }

    @DeleteMapping("/trace/logs")
    public Map<String, Object> clearTraces() {
        node().clearTraces();
        return Map.of("ok", true);
    }

    @PostMapping("/trace/suspend")
    public Map<String, Object> toggleSuspend() throws Exception {
        return Map.of("suspended", node().toggleSuspended());
    }

    // ---------- 顺序跟踪 ----------

    @PostMapping("/seqtrace")
    public Map<String, Object> seqTrace(@RequestBody TraceRequest req) throws Exception {
        node().seqTrace(req.module(), req.function(), req.arity(), req.exported());
        return Map.of("ok", true);
    }

    @GetMapping("/seqtrace/logs")
    public Map<String, Object> seqTraceLogs() throws Exception {
        return Map.of("logs", node().seqTraceLogs());
    }

    // ---------- ETS ----------

    @GetMapping("/ets")
    public Map<String, Object> etsTables() throws Exception {
        return Map.of("tables", node().etsTables());
    }

    @GetMapping("/ets/{name}")
    public Map<String, Object> etsTableInfo(@PathVariable String name) throws Exception {
        return Map.of("table", node().etsTableInfo(name));
    }

    // ---------- 内存 ----------

    @GetMapping("/memory")
    public Map<String, Object> memory() throws Exception {
        return Map.of("memory", node().erlangMemory());
    }

    // ---------- xref / 调用图 ----------

    @PostMapping("/xref/start")
    public Map<String, Object> startXref() throws Exception {
        node().startXref();
        return Map.of("ok", true);
    }

    @GetMapping("/callgraph")
    public Map<String, Object> callGraph(@RequestParam String module, @RequestParam String function,
                                         @RequestParam int arity) throws Exception {
        return Map.of("graph", node().callGraph(module, function, arity));
    }

    // ---------- 源码 ----------

    @GetMapping("/source")
    public Map<String, Object> source(@RequestParam String module,
                                      @RequestParam(required = false) String function,
                                      @RequestParam(required = false) Integer arity) throws Exception {
        return Map.of("source", node().sourceCode(module, function, arity));
    }

    @GetMapping("/abstract-code")
    public Map<String, Object> abstractCode(@RequestParam String module) throws Exception {
        return Map.of("code", node().abstractCode(module));
    }

    // ---------- 崩溃报告 ----------

    @GetMapping("/crash-reports")
    public Map<String, Object> crashReports() {
        return Map.of("reports", node().crashReports());
    }

    @DeleteMapping("/crash-reports")
    public Map<String, Object> clearCrashReports() {
        node().clearCrashReports();
        return Map.of("ok", true);
    }

    // ---------- Erlang 控制台 ----------

    public record ShellEvalRequest(String input) {
    }

    @PostMapping("/shell/start")
    public Map<String, Object> shellStart() throws Exception {
        return node().shellStart();
    }

    /** 提交一行输入（输出经 WS shell_out 流式返回） */
    @PostMapping("/shell/eval")
    public Map<String, Object> shellEval(@RequestBody ShellEvalRequest req) throws Exception {
        node().shellEval(req.input());
        return Map.of("ok", true);
    }

    @PostMapping("/shell/stop")
    public Map<String, Object> shellStop() {
        node().shellStop();
        return Map.of("ok", true);
    }

    // ---------- 首选项 ----------

    @GetMapping("/preferences")
    public Map<String, Object> preferences() {
        return node().preferences();
    }

    @PutMapping("/preferences")
    public Map<String, Object> setPreference(@RequestBody PrefRequest req) {
        node().setPreference(req.key(), req.value());
        return Map.of("ok", true);
    }

    // ---------- 异常统一处理 ----------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onError(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
