%% eb_console v5 — 中转站架构（按注册名通信，与 rex 路径一致）
%% Proxy：目标节点上的完整 io server（shell server 的 GL=Proxy）。
%% 输出：{chars, Text} → Java 邮箱 → WS → 浏览器。
%% 输入：Java 按注册名发送 {input, Line} → Proxy 收集多行表达式 → 回复 shell。
-module(eb_console).
-export([start/2, start_safe/2]).

-define(LOG_PATH, "/tmp/eb_console.log").

dbg_log(Text) ->
    file:write_file(?LOG_PATH, Text, [append]).

start_safe(MboxPid, RegNameAtom) when is_atom(RegNameAtom) ->
    try
        start(MboxPid, RegNameAtom)
    catch C:R:ST ->
        dbg_log("[start 崩溃] " ++ io_lib:format("~p ~p~n~p", [C, R, ST]) ++ "\n"),
        MboxPid ! {chars, "[start 崩溃] " ++ io_lib:format("~p ~p", [C, R]) ++ "\n"},
        MboxPid ! {exited, io_lib:format("~p", [R])}
    end.

start(MboxPid, RegNameAtom) when is_atom(RegNameAtom) ->
    file:write_file(?LOG_PATH, "[start] " ++ io_lib:format("~p", [erlang:localtime()]) ++ "\n"),
    Proxy = spawn(fun() -> proxy_loop(MboxPid, queue:new()) end),
    register(RegNameAtom, Proxy),
    register(RegNameAtom, Proxy),
    dbg_log("[proxy registered as " ++ atom_to_list(RegNameAtom) ++ "] " ++ io_lib:format("~p", [Proxy]) ++ "\n"),
    %% 上报 io 服务器（proxy）的 pid —— Java 的输入按注册名发送
    MboxPid ! {proxy_pid, Proxy},
    MboxPid ! {started},
    %% shell 派生器：GL=Proxy 后启动真 shell（server 继承 GL=Proxy）
    spawn(fun() ->
        erlang:group_leader(Proxy, self()),
        Pid = shell:start(),
        dbg_log("[shell server started] " ++ io_lib:format("~p", [Pid]) ++ "\n"),
        %% 保持存活以维持与 shell server 的上下文
        receive
            stop -> dbg_log("[spawner] 收到停止\n")
        end
    end),
    ok.

%% ---------- io 服务器 ----------
proxy_loop(Mbox, Q) ->
    receive
        {io_request, From, ReplyAs, Req} ->
            Q1 = handle_req(Mbox, From, ReplyAs, Req, Q),
            proxy_loop(Mbox, Q1);
        {input, Line} ->
            proxy_loop(Mbox, queue:in(Line, Q));
        {exit} ->
            dbg_log("[proxy 收到 exit]\n"),
            ok;
        _ ->
            proxy_loop(Mbox, Q)
    end.

handle_req(Mbox, From, ReplyAs, Req, Q) ->
    case Req of
        {put_chars, Enc, Chars} ->
            case Chars of [] -> ok; _ -> send_chars(Mbox, Enc, Chars) end,
            reply(From, ReplyAs, ok), Q;
        {put_chars, Enc, Mod, Fun, Args} ->
            try
                case erlang:apply(Mod, Fun, Args) of [] -> ok; C -> send_chars(Mbox, Enc, C) end,
                reply(From, ReplyAs, ok)
            catch _C:_R -> reply(From, ReplyAs, {error, format_failed}) end, Q;
        {get_until, _Enc, Prompt, _Mod, _Fun, _Args} ->
            send_prompt(Mbox, Prompt),
            {Text, Q1} = collect_expr(Mbox, Q, []),
            ScanRes = erl_scan:tokens([], Text, 1),
            reply(From, ReplyAs, ScanRes),
            {Text, Q1};
        {get_line, _Enc, Prompt} ->
            send_prompt(Mbox, Prompt),
            {Line, Q1} = take_line(Q, Mbox),
            reply(From, ReplyAs, Line), Q1;
        {get_chars, _Enc, Prompt, _N} ->
            send_prompt(Mbox, Prompt),
            {Line, Q1} = take_line(Q, Mbox),
            reply(From, ReplyAs, Line), Q1;
        {getopts, _Enc} ->
            reply(From, ReplyAs, [{binary, false}, {echo, true}, {unicode, true}]), Q;
        _Other ->
            reply(From, ReplyAs, {error, enotsup}), Q
    end.

%% ---------- 表达式收集（多行） ----------
collect_expr(Mbox, Q, Acc) ->
    case queue:out(Q) of
        {{value, Line}, Q1} ->
            Text = ensure_nl(Line), send_echo(Mbox, Text),
            case erl_scan:tokens([], Text, 1) of
                {done, _R, _Rest} -> {Text, Q1};
                {more, Cont} -> collect_more(Mbox, Cont, Q1, [Text])
            end;
        {empty, Q1} ->
            receive
                {input, Line} -> collect_expr(Mbox, queue:in(Line, Q1), Acc);
                {exit} -> exit(normal);
                Other -> collect_expr(Mbox, Q1, Acc)
            end
    end.

collect_more(Mbox, Cont, Q, Acc) ->
    case queue:out(Q) of
        {{value, Line}, Q1} ->
            Text = ensure_nl(Line), send_echo(Mbox, Text),
            case erl_scan:tokens(Cont, Text, 1) of
                {done, _R, _Rest} -> {lists:flatten(lists:reverse([Text | Acc])), Q1};
                {more, Cont2} -> collect_more(Mbox, Cont2, Q1, [Text | Acc])
            end;
        {empty, Q1} ->
            receive
                {input, Line} -> collect_more(Mbox, Cont, queue:in(Line, Q), Acc);
                {exit} -> exit(normal);
                Other -> collect_more(Mbox, Cont, Q, Acc)
            end
    end.

take_line(Q, Mbox) ->
    case queue:out(Q) of
        {{value, Line}, Q1} -> {ensure_nl(Line), Q1};
        {empty, Q1} ->
            receive
                {input, Line} -> {ensure_nl(Line), queue:in(Line, Q1)};
                {exit} -> exit(normal)
            end
    end.

send_echo(Mbox, Text) -> Mbox ! {chars, Text}.

ensure_nl(Line) ->
    case lists:nthtail(max(0, length(Line) - 1), Line) of
        "\n" -> Line;
        _ -> Line ++ "\n"
    end.

send_prompt(Mbox, Prompt) -> send_chars(Mbox, unicode, Prompt).

send_chars(Mbox, _Enc, Chars) when is_binary(Chars) ->
    Mbox ! {chars, binary_to_list(Chars)};
send_chars(Mbox, latin1, Chars) when is_list(Chars) ->
    Mbox ! {chars, Chars};
send_chars(Mbox, _Enc, Chars) when is_list(Chars) ->
    case unicode:characters_to_list(Chars) of
        [] -> ok;
        L -> Mbox ! {chars, L}
    end;
send_chars(Mbox, _Enc, Other) ->
    Mbox ! {chars, io_lib:format("~p", [Other])}.

reply(From, ReplyAs, Result) ->
    From ! {io_reply, ReplyAs, Result}.
