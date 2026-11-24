package com.erlyberly.web.util;

import com.ericsson.otp.erlang.*;
import erlyberly.ErlyBerly;
import erlyberly.format.TermFormatter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OtpErlangObject → JSON 友好结构。
 * 每个节点输出: { t: 类型, v: 显示字符串, c: [子节点] }（叶子节点省略 c）。
 */
public final class OtpJson {

    private OtpJson() {
    }

    public static TermFormatter fmt() {
        return ErlyBerly.getTermFormatter();
    }

    public static Map<String, Object> toJson(OtpErlangObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o == null) {
            m.put("t", "undefined");
            m.put("v", "undefined");
            return m;
        }
        if (o instanceof OtpErlangAtom a) {
            m.put("t", "atom");
            m.put("v", a.atomValue());
        } else if (o instanceof OtpErlangLong l) {
            m.put("t", "int");
            m.put("v", l.longValue());
        } else if (o instanceof OtpErlangDouble d) {
            m.put("t", "float");
            m.put("v", d.doubleValue());
        } else if (o instanceof OtpErlangString s) {
            m.put("t", "string");
            m.put("v", s.stringValue());
        } else if (o instanceof OtpErlangBinary b) {
            m.put("t", "binary");
            m.put("v", binaryToString(b));
            m.put("size", b.size());
        } else if (o instanceof OtpErlangPid p) {
            m.put("t", "pid");
            m.put("v", p.toString());
        } else if (o instanceof OtpErlangPort p) {
            m.put("t", "port");
            m.put("v", p.toString());
        } else if (o instanceof OtpErlangRef r) {
            m.put("t", "ref");
            m.put("v", r.toString());
        } else if (o instanceof OtpErlangFun f) {
            m.put("t", "fun");
            m.put("v", f.toString());
        } else if (o instanceof OtpErlangMap mp) {
            m.put("t", "map");
            m.put("v", fmt().toString(o));
            List<Object> pairs = new ArrayList<>();
            for (java.util.Map.Entry<OtpErlangObject, OtpErlangObject> entry : mp.entrySet()) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("k", toJson(entry.getKey()));
                pair.put("v2", toJson(entry.getValue()));
                pairs.add(pair);
            }
            m.put("c", pairs);
        } else if (o instanceof OtpErlangList l) {
            m.put("t", "list");
            m.put("v", fmt().toString(o));
            List<Object> children = new ArrayList<>();
            for (OtpErlangObject e : l.elements()) {
                children.add(toJson(e));
            }
            m.put("c", children);
        } else if (o instanceof OtpErlangTuple t) {
            m.put("t", "tuple");
            m.put("v", fmt().toString(o));
            List<Object> children = new ArrayList<>();
            for (OtpErlangObject e : t.elements()) {
                children.add(toJson(e));
            }
            m.put("c", children);
        } else {
            m.put("t", "term");
            m.put("v", o.toString());
        }
        return m;
    }

    private static String binaryToString(OtpErlangBinary b) {
        byte[] bytes = b.binaryValue();
        boolean printable = bytes.length > 0;
        for (byte x : bytes) {
            if (x < 9 || (x > 13 && x < 32) || x < 0) {
                printable = false;
                break;
            }
        }
        if (printable) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        StringBuilder sb = new StringBuilder("<<");
        for (int i = 0; i < Math.min(bytes.length, 64); i++) {
            if (i > 0) sb.append(',');
            sb.append(bytes[i] & 0xFF);
        }
        if (bytes.length > 64) sb.append(",...");
        return sb.append(">>").toString();
    }
}
