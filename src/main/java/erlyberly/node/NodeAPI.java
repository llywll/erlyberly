/**
 * erlyberly, erlang trace debugger
 * Copyright (C) 2016 Andy Till
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package erlyberly.node;

import static erlyberly.node.OtpUtil.OK_ATOM;
import static erlyberly.node.OtpUtil.atom;
import static erlyberly.node.OtpUtil.isTupleTagged;
import static erlyberly.node.OtpUtil.list;
import static erlyberly.node.OtpUtil.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import com.ericsson.otp.erlang.OtpAuthException;
import com.ericsson.otp.erlang.OtpConn;
import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangBinary;
import com.ericsson.otp.erlang.OtpErlangDecodeException;
import com.ericsson.otp.erlang.OtpErlangException;
import com.ericsson.otp.erlang.OtpErlangExit;
import com.ericsson.otp.erlang.OtpErlangFun;
import com.ericsson.otp.erlang.OtpErlangInt;
import com.ericsson.otp.erlang.OtpErlangList;
import com.ericsson.otp.erlang.OtpErlangLong;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangPid;
import com.ericsson.otp.erlang.OtpErlangString;
import com.ericsson.otp.erlang.OtpErlangTuple;
import com.ericsson.otp.erlang.OtpMbox;
import com.ericsson.otp.erlang.OtpPeer;
import com.ericsson.otp.erlang.OtpSelfNode;

import erlyberly.ModFunc;
import erlyberly.PrefBind;
import erlyberly.ProcInfo;
import erlyberly.SeqTraceLog;
import erlyberly.TraceLog;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

public class NodeAPI {

    private static final OtpErlangAtom ERLYBERLY_TRACE_OVERLOAD_ATOM = atom("erlyberly_trace_overload");

    private static final String ERLYBERLY = "erlyberly";

    private static final String CANNOT_RUN_THIS_METHOD_FROM_THE_FX_THREAD = "cannot run this method from the FX thread";

    private static final OtpErlangAtom ERLYBERLY_TRACE_LOG = atom("erlyberly_trace_log");

    private static final OtpErlangAtom ERLYBERLY_XREF_STARTED_ATOM = atom("erlyberly_xref_started");

    private static final OtpErlangAtom ERLYBERLY_ERROR_REPORT_ATOM = atom("erlyberly_error_report");

    private static final OtpErlangAtom ERLYBERLY_MODULE_RELOADED_ATOM = atom("erlyberly_module_loaded");

    private static final OtpErlangAtom ERLYBERLY_ATOM = new OtpErlangAtom(ERLYBERLY);

    private static final OtpErlangAtom BET_SERVICES_MSG_ATOM = new OtpErlangAtom("add_locator");

    private static final OtpErlangAtom REX_ATOM = atom("rex");

    public static final OtpErlangAtom OK_ATOM = atom("ok");

    public interface RpcCallback<T> {
        void callback(T result);
    }

    private static final OtpErlangAtom MODULE_ATOM = new OtpErlangAtom("module");

    private static final OtpErlangAtom ERROR_ATOM = new OtpErlangAtom("error");

    private static final OtpErlangAtom ERROR_ALREADY_STARTED_ATOM = new OtpErlangAtom("already_started");

    private static final String ERLYBERLY_BEAM_PATH = "/erlyberly/beam/erlyberly.beam";

    private static final int BEAM_SIZE_LIMIT = 1024 * 50;

    private static final AtomicLong CHECK_ALIVE_THREAD_COUNTER = new AtomicLong();

    private final TraceManager traceManager;

    private final SimpleBooleanProperty connectedProperty;

    private final SimpleBooleanProperty xrefStartedProperty;

    private final SimpleStringProperty summary;

    private OtpConn connection;

    private OtpSelfNode self;

    private String remoteNodeName;

    private String localNodeName;

    private String cookie;

    private volatile Thread checkAliveThread;

    private final SimpleObjectProperty<AppProcs> appProcs;

    private OtpMbox mbox;

    private volatile boolean connected = false;

    private final ObservableList<OtpErlangObject> crashReports = FXCollections.observableArrayList();

    private boolean manuallyDisconnected = false;

    private RpcCallback<OtpErlangTuple> moduleLoadedCallback;

    /**
     * Called when a trace log is received.
     * <br/>
     * Should only accessed from the FX thread.
     */
    private RpcCallback<TraceLog> traceLogCallback;

    /**
     * When tracing is paused, NodeAPI will stop all traces. When tracing is un-suspended
     * the DbgController must reapply all the traces.
     */
    private final SimpleBooleanProperty suspendedProperty;

    public NodeAPI() {
        traceManager = new TraceManager();

        connectedProperty = new SimpleBooleanProperty();
        connectedProperty.addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> obv, Boolean o, Boolean n) {
                connected = n;
                if (!n) {
                    xrefStartedProperty.set(false);
                }
            }
        });
        summary = new SimpleStringProperty("erlyberly not connected");

        appProcs = new SimpleObjectProperty<AppProcs>(new AppProcs(0, LocalDateTime.now()));

        connectedProperty.addListener(this::summaryUpdater);

        xrefStartedProperty = new SimpleBooleanProperty(false);

        suspendedProperty = new SimpleBooleanProperty();
    }

    public NodeAPI connectionInfo(String localNodeName, String remoteNodeName, String cookie) {
        this.localNodeName = localNodeName;
        this.remoteNodeName = remoteNodeName;
        this.cookie = cookie;

        return this;
    }

    public ObservableList<OtpErlangObject> getCrashReports() {
        return crashReports;
    }

    public SimpleObjectProperty<AppProcs> appProcsProperty() {
        return appProcs;
    }

    public synchronized void manualConnect() throws IOException, OtpErlangException, OtpAuthException {
        // TODO: here, we've cleared (set to false) being Manually/intentionally disconnected.
        manuallyDisconnected = false;
        connect();
    }

    public synchronized void connect() throws IOException, OtpErlangException, OtpAuthException {
        assert !Platform.isFxApplicationThread() : CANNOT_RUN_THIS_METHOD_FROM_THE_FX_THREAD;

        // clear previous connections and threads if any, before we reconnect
        // TODO: investigate whether we need this ...
        disconnect();
        String localIp = getLocalIPv4Address();
        if (localIp != null) {
            self = new OtpSelfNode(localNodeName + System.currentTimeMillis() + "@" + localIp);
        } else {
            // 如果获取不到 IPv4 地址，回退到让 jinterface 自动解析主机名
            self = new OtpSelfNode(localNodeName + System.currentTimeMillis());
        }
        if (!cookie.isEmpty()) {
            self.setCookie(cookie);
        }

        // if the node name does not contain a host then assume it is on the
        // same machine
        if (!remoteNodeName.contains("@")) {
            String[] split = self.toString().split("\\@");

            remoteNodeName += "@" + split[1];
        }

        connection = self.connect(new OtpPeer(remoteNodeName));

        mbox = self.createMbox();

        loadRemoteErlyberly();

        // 清理目标节点上残留的旧 erlyberly_tcollector（上次会话遗留的 tracer 会把 trace 发往已死的节点）
        try {
            OtpErlangObject tp = rpc("erlang", "whereis", list(atom("erlyberly_tcollector")));
            if (tp instanceof OtpErlangPid) {
                rpc("erlang", "exit", list(tp, atom("kill")));
            }
        } catch (Exception ignored) {
        }

        addErrorLoggerHandler();

        // start dbg so we can listen for module loads
        ensureDbgStarted();

        loadModulesOnPath(PrefBind.getOrDefault("loadModulesRegex", "").toString());

        // 同步置位（Property 的 runLater 更新是异步的，REST 立即查询状态时会读到旧值）
        connected = true;
        Platform.runLater(() -> {
            connectedProperty.set(true);
        });

        if (checkAliveThread == null) {
            checkAliveThread = new CheckAliveThread();
            checkAliveThread.start();
        }
    }

    public void manuallyDisconnect() throws IOException, OtpErlangException {
        // TODO: have a look at this: ( How can we properly "Close", or is the below acceptable? )
        // com.ericsson.otp.erlang.OtpErlangExit: 'Remote has closed connection'
        // at com.ericsson.otp.erlang.AbstractConnection.run(AbstractConnection.java:733)
        manuallyDisconnected = true;
        stopAllTraces();
        removeErrorLoggerHandler();
        unloadRemoteErlyberly();
        mbox.close();
        connected = false;
        Platform.runLater(() -> {
            connectedProperty.set(false);
            // 手动断开时不要显示"connection lost, reconnecting..."，直接显示未连接
            summary.set(ERLYBERLY + " not connected");
            appProcs.set(new AppProcs(0, LocalDateTime.now()));
        });
    }

    public void disconnect() {
        try {
            if (connection != null)
                connection.close();
        } catch (Exception e) {
            System.out.println(e);
        }
        try {
            if (self != null)
                self.close();
        } catch (Exception e) {
            System.out.println(e);
        }
        connection = null;
        self = null;
        connected = false;
        Platform.runLater(() -> {
            suspendedProperty.set(false);
            summary.set(ERLYBERLY + " not connected");
            appProcs.set(new AppProcs(0, LocalDateTime.now()));
        });
    }

    private synchronized void ensureDbgStarted() throws IOException, OtpErlangException {
        sendRPC(
                ERLYBERLY, "ensure_dbg_started",
                list(tuple(atom(self.node()), mbox.self()), PrefBind.getMaxTraceQueueLengthConfig())
        );
        // the return should be {ok, TracerPid} or {error, already_started}
        // we don't need to store the pid because it is registered
        OtpErlangObject returnedObject = receiveRPC();
        if (OtpUtil.isTupleTagged(OK_ATOM, returnedObject) || isBareOk(returnedObject)) {
            // dbg 启动成功，正常返回（erlyberly.erl 定制版在 tcollector 已注册时返回裸 ok 原子）
            return;
        } else if (isTupleTagged(ERROR_ATOM, returnedObject)) {
            // dbg 已经启动，这也是可以接受的
            OtpErlangTuple errorTuple = (OtpErlangTuple) returnedObject;
            if (errorTuple.arity() >= 2 && ERROR_ALREADY_STARTED_ATOM.equals(errorTuple.elementAt(1))) {
                // already_started 是预期情况，忽略
                return;
            }
        }
        // 目标节点缺少 runtime_tools（dbg:start 为 undef）时，尝试自动救援后重试一次
        if (String.valueOf(returnedObject).contains("undef")) {
            rescueRuntimeTools();
            sendRPC(
                    ERLYBERLY, "ensure_dbg_started",
                    list(tuple(atom(self.node()), mbox.self()), PrefBind.getMaxTraceQueueLengthConfig())
            );
            OtpErlangObject retried = receiveRPC();
            if (OtpUtil.isTupleTagged(OK_ATOM, retried) || isBareOk(retried)) {
                return;
            } else if (isTupleTagged(ERROR_ATOM, retried)) {
                OtpErlangTuple errorTuple = (OtpErlangTuple) retried;
                if (errorTuple.arity() >= 2 && ERROR_ALREADY_STARTED_ATOM.equals(errorTuple.elementAt(1))) {
                    return;
                }
            }
            throw new RuntimeException("Failed to ensure dbg started (after runtime_tools rescue): " + retried);
        }
        // 其他错误情况才抛出异常
        throw new RuntimeException("Failed to ensure dbg started: " + returnedObject);
    }

    /**
     * 目标节点 code path 中没有 dbg/runtime_tools 时的自动救援：
     * 1. 优先把目标节点自己 OTP 根目录下的 runtime_tools ebin 目录加回 code path；
     * 2. 目标机器盘上也没有时，从本机 Erlang 安装读取 dbg.beam 用 code:load_binary 注入。
     */
    private void rescueRuntimeTools() throws IOException, OtpErlangException {
        // 目标节点上 dbg 是否真的缺失（code:which 缺失时返回 undefined 或 non_existing）
        sendRPC("code", "which", list(atom("dbg")));
        OtpErlangObject which = receiveRPC();
        boolean missing = which instanceof OtpErlangAtom a
                && ("undefined".equals(a.atomValue()) || "non_existing".equals(a.atomValue()));
        if (!missing) {
            // dbg 模块在目标节点存在，undef 来自别处，不做处理
            return;
        }

        // 1) 目标节点自己的 OTP 根目录里找 runtime_tools
        sendRPC("code", "root_dir", list());
        OtpErlangObject rootObj = receiveRPC();
        System.out.println("rescue: target OTP root_dir = " + rootObj);
        if (rootObj instanceof OtpErlangString rootDir) {
            String pattern = rootDir.stringValue() + "/lib/runtime_tools-*/ebin";
            sendRPC("filelib", "wildcard", list(new OtpErlangString(pattern)));
            OtpErlangObject dirsObj = receiveRPC();
            System.out.println("rescue: wildcard dirs = " + dirsObj);
            if (dirsObj instanceof OtpErlangList dirs) {
                for (OtpErlangObject dir : dirs.elements()) {
                    sendRPC("code", "add_patha", list(dir));
                    receiveRPC();
                }
            }
            sendRPC("code", "which", list(atom("dbg")));
            OtpErlangObject recheck = receiveRPC();
            System.out.println("rescue: recheck code:which(dbg) = " + recheck);
            if (!(recheck instanceof OtpErlangAtom ra
                    && ("undefined".equals(ra.atomValue()) || "non_existing".equals(ra.atomValue())))) {
                System.out.println("rescued runtime_tools from target OTP root: " + recheck);
                return;
            }
        }

        // 2) 从本机 Erlang 安装里找 dbg.beam 注入
        byte[] dbgBeam = findLocalDbgBeam();
        if (dbgBeam != null) {
            sendRPC("code", "load_binary",
                    list(atom("dbg"), new OtpErlangString(""), new OtpErlangBinary(dbgBeam)));
            receiveRPC();
            sendRPC("code", "which", list(atom("dbg")));
            OtpErlangObject recheck2 = receiveRPC();
            System.out.println("injected dbg.beam from local installation, code:which -> " + recheck2);
        } else {
            System.err.println("runtime_tools rescue failed: dbg.beam not found on target or local machine");
        }
    }

    /** 在本机常见 Erlang 安装位置搜索 dbg.beam */
    private static byte[] findLocalDbgBeam() {
        String home = System.getProperty("user.home");
        String[][] candidates = {
                // {glob 模式（绝对路径）, 搜索起始目录}
                {home + "/.vfox/cache/erlang/**/runtime_tools-*/ebin/dbg.beam", home + "/.vfox/cache/erlang"},
                {"/usr/local/lib/erlang/**/runtime_tools-*/ebin/dbg.beam", "/usr/local/lib/erlang"},
                {"/opt/homebrew/lib/erlang/**/runtime_tools-*/ebin/dbg.beam", "/opt/homebrew/lib/erlang"},
                {"/opt/local/lib/erlang/**/runtime_tools-*/ebin/dbg.beam", "/opt/local/lib/erlang"},
                {"/usr/lib/erlang/**/runtime_tools-*/ebin/dbg.beam", "/usr/lib/erlang"}
        };
        for (String[] c : candidates) {
            String glob = c[0];
            java.nio.file.Path base = java.nio.file.Paths.get(c[1]);
            if (!java.nio.file.Files.exists(base)) {
                continue;
            }
            try {
                java.nio.file.PathMatcher matcher =
                        java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + glob);
                try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(base, 12)) {
                    java.nio.file.Path found = stream
                            .filter(Files::isRegularFile)
                            .filter(matcher::matches)
                            .findFirst()
                            .orElse(null);
                    if (found != null) {
                        System.out.println("local dbg.beam found: " + found);
                        return java.nio.file.Files.readAllBytes(found);
                    }
                }
            } catch (Exception e) {
                System.out.println("search failed for " + glob + ": " + e);
            }
        }
        return null;
    }

    private void loadModulesOnPath(String regex) throws IOException, OtpErlangException {
        if (regex == null || "".equals(regex))
            return;
        sendRPC(
                ERLYBERLY, "load_modules_on_path",
                list(new OtpErlangString(regex))
        );
        // flush the return value
        receiveRPC();
    }

    private synchronized void addErrorLoggerHandler() throws IOException, OtpErlangException {
        OtpErlangList args = OtpUtil.list(mbox.self());
        sendRPC(
                "error_logger", "add_report_handler",
                OtpUtil.list(ERLYBERLY_ATOM, args)
        );

        // flush the return value
        receiveRPC();
    }

    private synchronized void removeErrorLoggerHandler() throws IOException, OtpErlangException {
        OtpErlangList args = OtpUtil.list(mbox.self());
        sendRPC(
                "error_logger", "delete_report_handler",
                OtpUtil.list(ERLYBERLY_ATOM, args)
        );

        // flush the return value
        receiveRPC();
    }

    class CheckAliveThread extends Thread {

        public CheckAliveThread() {
            setDaemon(true);
            setName("Erlyberly Check Alive " + CHECK_ALIVE_THREAD_COUNTER.incrementAndGet());
        }

        @Override
        public void run() {
            while (true) {
                if (!manuallyDisconnected) {
                    ensureAlive();
                }
                mySleep(150);
            }
        }

        private synchronized boolean ensureAlive() {
            try {
                receiveRPC(0);
                if (connection != null && connection.isAlive())
                    return true;
            } catch (OtpErlangExit oee) {
                // an exit is what we're checking for so no need to log it
            } catch (OtpErlangException | IOException e1) {
                e1.printStackTrace();
            }

            Platform.runLater(() -> {
                connectedProperty.set(false);
            });

            while (true) {
                // 手动断开后不再自动重连，退出探活循环（否则会无 sleep 空转占满 CPU）
                if (manuallyDisconnected) {
                    return true;
                }
                try {
                    connect();
                    break;
                } catch (Exception e) {
                    int millis = 50;
                    mySleep(millis);
                }
            }
            return true;
        }
    }

    private void loadRemoteErlyberly() throws IOException, OtpErlangException {

        OtpErlangBinary otpErlangBinary = new OtpErlangBinary(loadBeamFile());

        // 第二个参数是文件名，仅用于错误报告，传递空字符串避免文件验证问题
        sendRPC("code", "load_binary",
                list(
                        atom(ERLYBERLY),
                        new OtpErlangString(""),
                        otpErlangBinary));

        OtpErlangObject result = receiveRPC();

        if (result instanceof OtpErlangTuple) {
            OtpErlangObject e0 = ((OtpErlangTuple) result).elementAt(0);

            if (!MODULE_ATOM.equals(e0)) {
                throw new RuntimeException("error loading the erlyberly module, result was " + result);
            }
        } else {
            throw new RuntimeException("error loading the erlyberly module, result was " + result);
        }
    }

    private void unloadRemoteErlyberly() throws IOException, OtpErlangException {
        sendRPC("code", "purge", list(atom(ERLYBERLY)));
        receiveRPC();
        sendRPC("code", "delete", list(atom(ERLYBERLY)));
        receiveRPC();
        sendRPC("code", "soft_purge", list(atom(ERLYBERLY)));
        receiveRPC();
    }

    private OtpErlangObject receiveRPC() throws IOException, OtpErlangException {
        int timeout = 5000;
        return receiveRPC(timeout);
    }

    private OtpErlangObject receiveRPC(int timeout) throws OtpErlangExit,
            OtpErlangDecodeException, IOException, OtpErlangException {
        OtpErlangTuple receive = OtpUtil.receiveRPC(mbox, timeout);

        if (receive == null) {
            return null;
        } else if (isTupleTagged(ERLYBERLY_TRACE_LOG, receive)) {
            traceLogNotification(receive);
            return receiveRPC(timeout);
        } else if (isTupleTagged(ERLYBERLY_ERROR_REPORT_ATOM, receive)) {
            Platform.runLater(() -> {
                crashReports.add(receive.elementAt(1));
            });
            return receiveRPC(timeout);
        } else if (isTupleTagged(ERLYBERLY_MODULE_RELOADED_ATOM, receive)) {
            Platform.runLater(() -> {
                if (moduleLoadedCallback != null)
                    moduleLoadedCallback.callback((OtpErlangTuple) receive.elementAt(2));
            });
            return receiveRPC(timeout);
        } else if (isTupleTagged(ERLYBERLY_TRACE_OVERLOAD_ATOM, receive)) {
            Platform.runLater(() -> {
                suspendedProperty.set(true);
            });
        } else if (!isTupleTagged(REX_ATOM, receive)) {
            throw new RuntimeException("Expected tuple tagged with atom rex but got " + receive);
        }
        OtpErlangObject result = receive.elementAt(1);

        // hack to support certain projects, don't ask...
        if (isTupleTagged(BET_SERVICES_MSG_ATOM, result)) {
            result = receiveRPC(timeout);
        } else if (isTupleTagged(ERLYBERLY_XREF_STARTED_ATOM, result)) {
            Platform.runLater(() -> {
                xrefStartedProperty.set(true);
            });
            return receiveRPC(timeout);
        }

        return result;
    }

    private void traceLogNotification(OtpErlangTuple receive) {
        Platform.runLater(() -> {
            OtpErlangTuple traceLog = (OtpErlangTuple) receive.elementAt(1);
            List<TraceLog> collatedTraces = traceManager.collateTraceSingle(traceLog);
            if (traceLogCallback != null) {
                for (TraceLog log : collatedTraces) {
                    traceLogCallback.callback(log);
                }
            }
        });
    }

    private static byte[] loadBeamFile() throws IOException {
        InputStream resourceAsStream = OtpUtil.class.getResourceAsStream(ERLYBERLY_BEAM_PATH);

        byte[] b = new byte[BEAM_SIZE_LIMIT];
        int total = 0;
        int read = 0;

        do {
            total += read;
            read = resourceAsStream.read(b, total, BEAM_SIZE_LIMIT - total);
        } while (read != -1);

        if (total >= BEAM_SIZE_LIMIT) {
            throw new RuntimeException("erlyberly.beam file is too big");
        }

        return Arrays.copyOf(b, total);
    }

    public synchronized void retrieveProcessInfo(List<ProcInfo> processes) throws Exception {
        assert !Platform.isFxApplicationThread() : CANNOT_RUN_THIS_METHOD_FROM_THE_FX_THREAD;

        if (connection == null || !connected)
            return;

        OtpErlangObject receiveRPC = null;

        try {
            sendRPC(ERLYBERLY, "process_info", new OtpErlangList());
            receiveRPC = receiveRPC();
            OtpErlangList received = (OtpErlangList) receiveRPC;

            for (OtpErlangObject recv : received) {
                if (recv instanceof OtpErlangList) {
                    OtpErlangList pinfo = (OtpErlangList) recv;
                    Map<Object, Object> propsToMap = OtpUtil.propsToMap(pinfo);
                    processes.add(ProcInfo.toProcessInfo(propsToMap));
                }
            }
            Platform.runLater(() -> {
                appProcs.set(new AppProcs(processes.size(), LocalDateTime.now()));
            });
        } catch (ClassCastException e) {
            throw new RuntimeException("unexpected result: " + receiveRPC, e);
        }
    }

    private void mySleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }
    }

    /**
     * 获取所有可用模块的函数列表。
     * 默认只返回已加载的模块（速度更快）。
     * 如果需要加载所有模块，请使用 requestFunctionsAll()。
     */
    public synchronized OtpErlangList requestFunctions() throws Exception {
        assert !Platform.isFxApplicationThread() : CANNOT_RUN_THIS_METHOD_FROM_THE_FX_THREAD;

        // 默认只返回已加载的模块，避免长时间等待
        sendRPC(ERLYBERLY, "module_functions", list(atom("loaded")));
        return (OtpErlangList) receiveRPC();
    }

    /**
     * 获取所有可用模块的函数列表（包括未加载的模块，会先扫描代码路径并加载）。
     * 由于需要扫描和加载所有模块，可能耗时较长（最多30秒）。
     */
    public synchronized OtpErlangList requestFunctionsAll() throws Exception {
        assert !Platform.isFxApplicationThread() : CANNOT_RUN_THIS_METHOD_FROM_THE_FX_THREAD;

        sendRPC(ERLYBERLY, "module_functions", list(atom("all")));
        return (OtpErlangList) receiveRPC(30000);
    }

    /**
     * 仅获取已加载模块的函数列表（不扫描代码路径，速度更快但不全）。
     */
    public synchronized OtpErlangList requestFunctionsLoadedOnly() throws Exception {
        assert !Platform.isFxApplicationThread() : CANNOT_RUN_THIS_METHOD_FROM_THE_FX_THREAD;

        sendRPC(ERLYBERLY, "module_functions", list(atom("loaded")));
        return (OtpErlangList) receiveRPC();
    }

    public synchronized void startTrace(ModFunc mf, int maxQueueLen) throws Exception {
        assert mf.getFuncName() != null : "function name cannot be null";
        // if tracing is suspended, we can't apply a new trace because that will
        // leave us in a state where some traces are active and others are not
        if (isSuspended()) {
            throw new Exception("Tracing is suspended. Please resume tracing first.");
        }
        sendRPC(ERLYBERLY, "start_trace", toStartTraceFnArgs(mf, maxQueueLen));

        OtpErlangObject result = receiveRPC();
        if (!isTupleTagged(OK_ATOM, result)) {
            // 追踪设置失败，抛出异常通知用户
            String errorMsg = "Failed to start trace on " + mf.getModuleName() + ":" + mf.getFuncName() + "/" + mf.getArity() + ". Result: " + result;
            System.err.println(errorMsg);
            throw new Exception(errorMsg);
        }
    }

    public synchronized void stopTrace(ModFunc mf) throws Exception {
        assert mf.getFuncName() != null : "function name cannot be null";
        // if tracing is suspended, do not attempt to remove a trace, it should already
        // be removed
        if (isSuspended())
            return;
        sendRPC(ERLYBERLY, "stop_trace",
                list(
                        OtpUtil.atom(mf.getModuleName()),
                        OtpUtil.atom(mf.getFuncName()),
                        new OtpErlangInt(mf.getArity()),
                        new OtpErlangAtom(mf.isExported())
                ));
        receiveRPC();
    }

    public synchronized void stopAllTraces() throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "stop_traces", list());
        receiveRPC();
    }

    private OtpErlangList toStartTraceFnArgs(ModFunc mf, int maxQueueLen) {
        String node = self.node();
        OtpErlangPid self2 = mbox.self();
        return list(
                tuple(OtpUtil.atom(node), self2),
                atom(mf.getModuleName()),
                atom(mf.getFuncName()),
                mf.getArity(),
                maxQueueLen
        );
    }

    public SimpleBooleanProperty connectedProperty() {
        return connectedProperty;
    }

    public SimpleBooleanProperty xrefStartedProperty() {
        return xrefStartedProperty;
    }

    private void sendRPC(String module, String function, OtpErlangList args) throws IOException {
        OtpUtil.sendRPC(connection, mbox, atom(module), atom(function), args);
    }

    public synchronized List<TraceLog> collectTraceLogs() throws Exception {
        sendRPC(ERLYBERLY, "collect_trace_logs", new OtpErlangList());
        OtpErlangObject prcResult = receiveRPC();
        if (!isTupleTagged(OK_ATOM, prcResult)) {
            if (prcResult != null) {
                System.out.println(prcResult);
            }
            return new ArrayList<TraceLog>();
        }
        OtpErlangList traceLogs = (OtpErlangList) ((OtpErlangTuple) prcResult).elementAt(1);
        return traceManager.collateTraces(traceLogs);
    }

    public synchronized List<SeqTraceLog> collectSeqTraceLogs() throws Exception {
        sendRPC(ERLYBERLY, "collect_seq_trace_logs", new OtpErlangList());

        OtpErlangObject prcResult = receiveRPC();

        if (!isTupleTagged(OK_ATOM, prcResult)) {
            return new ArrayList<SeqTraceLog>();
        }

        ArrayList<SeqTraceLog> seqLogs = new ArrayList<SeqTraceLog>();

        try {
            OtpErlangList traceLogs = (OtpErlangList) ((OtpErlangTuple) prcResult)
                    .elementAt(1);
            for (OtpErlangObject otpErlangObject : traceLogs) {
                seqLogs.add(SeqTraceLog.build(OtpUtil
                        .propsToMap((OtpErlangList) otpErlangObject)));
            }
        } catch (ClassCastException e) {
            System.out.println("did not understand result from collect_seq_trace_logs " + prcResult);
            e.printStackTrace();
        }
        return seqLogs;
    }

    public ObservableValue<? extends String> summaryProperty() {
        return summary;
    }

    private void summaryUpdater(Observable o, Boolean wasConnected, Boolean isConnected) {
        String summaryText = ERLYBERLY;

        OtpSelfNode self2 = self;

        if (self2 != null && !wasConnected && isConnected)
            summaryText = self2.node() + " connected to " + this.remoteNodeName;
        else if (wasConnected && !isConnected)
            summaryText = "erlyberly, connection lost.  reconnecting...";

        summary.set(summaryText);
    }

    public synchronized void seqTrace(ModFunc mf) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "seq_trace",
                list(
                        tuple(OtpUtil.atom(self.node()), mbox.self()),
                        atom(mf.getModuleName()),
                        atom(mf.getFuncName()),
                        mf.getArity(),
                        new OtpErlangAtom(mf.isExported())
                ));

        OtpErlangObject result = receiveRPC();

        System.out.println(result);
    }

    public synchronized OtpErlangObject getProcessState(String pidString) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "get_process_state", list(pidString));

        OtpErlangObject result = receiveRPC();

        if (isTupleTagged(OK_ATOM, result)) {
            return ((OtpErlangTuple) result).elementAt(1);
        }
        return null;
    }

    /**
     * 获取进程字典
     */
    public synchronized OtpErlangObject getProcessDictionary(String pidString) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "get_process_dictionary", list(pidString));

        OtpErlangObject result = receiveRPC();

        if (isTupleTagged(OK_ATOM, result)) {
            return ((OtpErlangTuple) result).elementAt(1);
        }
        return null;
    }

    /**
     * 获取进程信箱信息（消息队列长度、状态等）
     */
    public synchronized OtpErlangObject getProcessMessages(String pidString) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "get_process_messages", list(pidString));

        OtpErlangObject result = receiveRPC();

        if (isTupleTagged(OK_ATOM, result)) {
            return ((OtpErlangTuple) result).elementAt(1);
        }
        return null;
    }

    /**
     * 给进程发送消息（call/cast/info）
     * @param pidString 进程PID字符串，如 "<0.1.0>"
     * @param msgType 消息类型："call"、"cast" 或 "info"
     * @param msgString 消息内容的Erlang术语字符串
     * @return 返回Erlang端的响应结果
     */
    public synchronized OtpErlangObject sendMessage(String pidString, String msgType, String msgString) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "send_message", list(pidString, atom(msgType), msgString));
        // call 类型需要更长超时，因为 gen_server:call 默认 5000ms
        OtpErlangObject result = receiveRPC("call".equals(msgType) ? 10000 : 5000);
        return result;
    }

    /**
     * 获取所有 ETS 表列表
     */
    public synchronized OtpErlangObject getEtsTables() throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "get_ets_tables", list());

        OtpErlangObject result = receiveRPC();

        if (isTupleTagged(OK_ATOM, result)) {
            return ((OtpErlangTuple) result).elementAt(1);
        }
        return null;
    }

    /**
     * 获取 ETS 表的详细信息
     */
    public synchronized OtpErlangObject getEtsTableInfo(String tableName) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "get_ets_table_info", list(atom(tableName)));

        OtpErlangObject result = receiveRPC();

        if (isTupleTagged(OK_ATOM, result)) {
            return ((OtpErlangTuple) result).elementAt(1);
        }
        return null;
    }

    public synchronized Map<Object, Object> erlangMemory() throws IOException, OtpErlangException {
        sendRPC("erlang", "memory", list());

        OtpErlangList result = (OtpErlangList) receiveRPC();

        return OtpUtil.propsToMap(result);
    }

    public boolean isConnected() {
        return connected;
    }

    /** 当前配置的目标节点名（连接前也有值），供 UI/接口展示 */
    public String getRemoteNodeName() {
        return remoteNodeName;
    }

    /** 判断结果是否为裸 ok 原子（定制版 erlyberly.erl 的 ensure_dbg_started 返回值） */
    private static boolean isBareOk(OtpErlangObject o) {
        return o instanceof OtpErlangAtom a && "ok".equals(a.atomValue());
    }

    /** 按注册名向已连接的节点发送消息（与 rex 路径一致，可靠） */
    public void sendToRegistered(String destRegName, OtpErlangObject msg) throws IOException {
        connection.send(mbox.self(), destRegName, msg);
    }

    /** 按注册名发送（重载：接受任意消息对象） */
    public void sendToRegistered(String destRegName, OtpErlangObject[] msgParts) throws IOException {
        connection.send(mbox.self(), destRegName, new OtpErlangTuple(msgParts));
    }

    /** 通用 RPC：供会话层扩展使用（如控制台的 erl_scan/erl_parse/erl_eval 求值链） */
    public OtpErlangObject rpc(String module, String function, OtpErlangList args)
            throws IOException, OtpErlangException {
        sendRPC(module, function, args);
        return receiveRPC();
    }

    /** 创建控制台专用邮箱（作为远程 shell 的 group leader） */
    public OtpMbox createShellMbox() {
        return self.createMbox();
    }

    /** 连接时设置的 cookie（供 pty 控制台启动 erl -remsh 使用） */
    public String getCookie() {
        return cookie;
    }

    public boolean manuallyDisconnected() {
        return manuallyDisconnected;
    }

    public synchronized OtpErlangObject callGraph(OtpErlangList skippedModuleAtoms, OtpErlangAtom module, OtpErlangAtom function, OtpErlangLong arity) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "xref_analysis", list(skippedModuleAtoms, module, function, arity));

        OtpErlangObject result = receiveRPC();

        return result;
    }

    /**
     * Start xref but
     */
    public synchronized void asyncEnsureXRefStarted() throws IOException {
        sendRPC(ERLYBERLY, "ensure_xref_started", list());
    }

    public synchronized String moduleFunctionSourceCode(String module, String function, Integer arity) throws IOException, OtpErlangException {
        OtpErlangInt otpArity = new OtpErlangInt(arity);
        sendRPC(ERLYBERLY, "get_source_code", list(tuple(atom(module), atom(function), otpArity)));
        OtpErlangObject result = receiveRPC();
        return returnCode(result, "Failed to get source code for " + module + ":" + function + "/" + arity.toString() + ".");
    }

    public synchronized String moduleFunctionSourceCode(String module) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "get_source_code", list(atom(module)));
        OtpErlangObject result = receiveRPC();
        return returnCode(result, "Failed to get source code for " + module + ".");
    }

    public synchronized String moduleFunctionAbstCode(String module) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "get_abstract_code", list(atom(module)));
        OtpErlangObject result = receiveRPC();
        return returnCode(result, "Failed to get abstract code for " + module + ".");
    }

    public synchronized String moduleFunctionAbstCode(String module, String function, Integer arity) throws IOException, OtpErlangException {
        OtpErlangInt otpArity = new OtpErlangInt(arity);
        sendRPC(ERLYBERLY, "get_abstract_code", list(tuple(atom(module), atom(function), otpArity)));
        OtpErlangObject result = receiveRPC();
        return returnCode(result, "Failed to get abstract code for " + module + ".");
    }

    public String returnCode(OtpErlangObject result, String errorResponse) {
        if (isTupleTagged(OK_ATOM, result)) {
            OtpErlangBinary bin = (OtpErlangBinary) ((OtpErlangTuple) result).elementAt(1);
            String ss = new String(bin.binaryValue());
            return ss;
        } else {
            OtpErlangBinary bin = (OtpErlangBinary) ((OtpErlangTuple) result).elementAt(1);
            String err = new String(bin.binaryValue());
            System.out.println(err);
            return errorResponse;
        }
    }

    public synchronized OtpErlangList dictToPropslist(OtpErlangObject dict) throws IOException, OtpErlangException {
        sendRPC("dict", "to_list", list(dict));
        return (OtpErlangList) receiveRPC(5000);
    }

    /**
     * Set the callback that is invoked when erlyberly receives a message that a
     * module has been loaded, or reloaded by the VM. The callback argument is in
     * the format {module(), ExportedFuncs, UnexportedFuncs}. A function is the
     * format {atom(), integer()}.
     */
    public void setModuleLoadedCallback(RpcCallback<OtpErlangTuple> aModuleLoadedCallback) {
        moduleLoadedCallback = aModuleLoadedCallback;
    }

    public synchronized String decompileFun(OtpErlangFun fun) throws IOException, OtpErlangException {
        sendRPC(ERLYBERLY, "saleyn_fun_src", list(fun));
        OtpErlangObject received = receiveRPC(5000);
        if (received instanceof OtpErlangString) {
            OtpErlangString otpString = (OtpErlangString) received;
            return otpString.stringValue();
        } else {
            throw new OtpErlangException(Objects.toString(received));
        }
    }

    public RpcCallback<TraceLog> getTraceLogCallback() {
        return traceLogCallback;
    }

    public void setTraceLogCallback(RpcCallback<TraceLog> traceLogCallback) {
        this.traceLogCallback = traceLogCallback;
    }

    /** 转发跟踪完成事件钩子到内部 TraceManager（实例级，支持多会话） */
    public void setTraceCompletionListener(TraceManager.TraceCompletionListener listener) {
        traceManager.setTraceCompletionListener(listener);
    }

    public void toggleSuspended() throws OtpErlangException, IOException {
        assert Platform.isFxApplicationThread();
        if (!isSuspended())
            stopAllTraces();
        suspendedProperty.set(!isSuspended());
    }

    public boolean isSuspended() {
        assert Platform.isFxApplicationThread();
        return suspendedProperty.get();
    }

    public SimpleBooleanProperty suspendedProperty() {
        assert Platform.isFxApplicationThread();
        return suspendedProperty;
    }

    /**
     * 获取本机第一个非回环的 IPv4 地址
     *
     * @return 本机 IPv4 地址字符串，如果未找到则返回 null
     */
    public static String getLocalIPv4Address() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();

                // 跳过回环接口、未激活的接口和虚拟接口
                if (networkInterface.isLoopback() ||
                        !networkInterface.isUp() ||
                        networkInterface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();

                    // 只返回 IPv4 地址，且不是回环地址
                    if (!inetAddress.isLoopbackAddress() &&
                            inetAddress instanceof java.net.Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.fillInStackTrace();
        }

        // 如果没找到合适的 IPv4 地址，尝试使用 InetAddress 获取
        try {
            InetAddress localHost = InetAddress.getLocalHost();
            if (!localHost.isLoopbackAddress() && localHost instanceof java.net.Inet4Address) {
                return localHost.getHostAddress();
            }
        } catch (Exception e) {
            e.fillInStackTrace();
        }

        return null;
    }

    /**
     * 获取本机所有 IPv4 地址
     *
     * @return IPv4 地址列表
     */
    public static List<String> getAllIPv4Addresses() {
        List<String> ipv4Addresses = new ArrayList<>();

        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();

                // 跳过回环接口、未激活的接口
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress inetAddress = inetAddresses.nextElement();

                    // 只添加 IPv4 地址，且不是回环地址
                    if (!inetAddress.isLoopbackAddress() &&
                            inetAddress instanceof java.net.Inet4Address) {
                        ipv4Addresses.add(inetAddress.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            e.fillInStackTrace();
        }

        return ipv4Addresses;
    }
}
