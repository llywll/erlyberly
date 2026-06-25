package erlyberly;

import javafx.application.Application;

/**
 * 独立启动类，本身不继承 {@link Application}。
 *
 * <p>当主类直接继承 Application 并作为可执行 jar 的 Main-Class 时，
 * 从 classpath（非模块路径）启动会触发 JVM 检查并报
 * "JavaFX runtime components are missing, and are required to run this application"。
 * 以本类作为 Main-Class 即可绕过该检查，再显式启动 {@link ErlyBerly}。
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(ErlyBerly.class, args);
    }
}