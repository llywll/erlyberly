package com.erlyberly.web;

import erlyberly.ErlyBerly;
import erlyberly.PrefBind;
import erlyberly.format.ElixirFormatter;
import erlyberly.format.ErlangFormatter;
import erlyberly.format.LFEFormatter;
import javafx.application.Platform;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.logging.Logger;

/**
 * erlyberly Web 版入口。
 * <p>
 * 复用主项目 NodeAPI 及核心类（通过 build-helper add-source 引入源码编译）。
 * 启动时用 Platform.startup() 初始化一个无窗口的 JavaFX 线程：
 * NodeAPI/TraceLog 内部的 Platform.runLater 与 JavaFX 属性在无显示环境下也能正常工作，
 * 本服务不创建任何窗口，不做任何渲染。
 */
@SpringBootApplication
@EnableScheduling
public class WebApplication {

    private static final Logger LOG = Logger.getLogger(WebApplication.class.getName());

    public static void main(String[] args) {
        // 初始化无窗口的 JavaFX 平台线程（必须在任何 Platform.runLater 之前）
        Platform.startup(() -> LOG.info("headless JavaFX platform started"));
        // 加载配置文件（~/.config/.erlyberly2）
        try {
            PrefBind.setup();
        } catch (Exception e) {
            LOG.warning("failed to load preferences: " + e);
        }
        // 设置术语格式化器（NodeAPI/TraceLog 的 toString 依赖它）
        String fmt = PrefBind.getOrDefault("termFormatting", "erlang").toString();
        ErlyBerly.setTermFormatter(switch (fmt) {
            case "elixir" -> new ElixirFormatter();
            case "lfe" -> new LFEFormatter();
            default -> new ErlangFormatter();
        });
        SpringApplication.run(WebApplication.class, args);
    }
}
