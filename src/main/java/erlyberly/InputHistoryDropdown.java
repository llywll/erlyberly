/**
 * ErlyBerly，Erlang 跟踪调试器
 * 版权所有 (C) 2016 Andy Till
 *
 * 本程序是自由软件：您可以根据自由软件基金会发布的 GNU 通用公共许可证进行 redistribut 和/或修改，
 * 使用许可证的第 3 版，或（根据您的选择）任何更高版本。
 *
 * 本程序的发布是希望它能有用，但不提供任何保证；甚至不包括对适销性或特定用途适用性的默示保证。
 * 有关更多详细信息，请参阅 GNU 通用公共许可证。
 *
 * 您应该已经收到了 GNU 通用公共许可证的副本。如果没有，请参见 <http://www.gnu.org/licenses/>。
 */
package erlyberly;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

/**
 * 为任意 {@link TextField} 附加“历史记录下拉框”。
 * <p>
 * 输入框获得焦点或被点击时，会在其正下方弹出历史记录列表：默认展示最近 10 条，
 * 超过 10 条时底部出现“展示更多”行，点击展开全部；每条记录右侧有删除按钮（✕），
 * 点击某条记录会回填到输入框。
 * <p>
 * 本组件只负责“存储 + 下拉 UI”，<b>何时记录历史由调用方通过 {@link #record(String)} 决定</b>
 * （例如连接成功后、过滤出非空结果后）。
 * <p>
 * 历史记录通过 {@link PrefBind} 持久化。<b>所有方法都必须在 JavaFX 线程调用。</b>
 */
public final class InputHistoryDropdown {

    /** 折叠状态下最多展示的记录条数 */
    private static final int COLLAPSED_LIMIT = 10;

    /** 持久化时连接多条历史记录的分隔符（历史项本身均为单行文本，不含换行） */
    private static final String SEP = "\n";

    private final TextField textField;
    private final String storageKey;
    private final int maxEntries;

    private final ObservableList<String> history = FXCollections.observableArrayList();
    private final Popup popup = new Popup();
    private final VBox content = new VBox();

    /** “展示更多”是否已展开。每次重新弹出都重置为折叠态。 */
    private boolean expanded = false;

    public InputHistoryDropdown(TextField textField, String storageKey, int maxEntries) {
        this.textField = textField;
        this.storageKey = storageKey;
        this.maxEntries = maxEntries > 0 ? maxEntries : 50;
    }

    /** 便捷工厂：new + install 一步完成。 */
    public static InputHistoryDropdown install(TextField tf, String storageKey, int maxEntries) {
        return new InputHistoryDropdown(tf, storageKey, maxEntries).install();
    }

    /** 安装监听器并从偏好中加载已存历史。必须在 FX 线程调用。 */
    public InputHistoryDropdown install() {
        loadFromPrefs();

        content.setStyle("-fx-background-color: white; -fx-border-color: #b0b0b0; -fx-border-width: 1; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 8, 0, 0, 2);");
        popup.getContent().add(content);
        // 点击弹窗外部自动隐藏；删除按钮在弹窗内部，不会触发 autoHide
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        // 获得焦点时弹出（false -> true 只触发一次）
        textField.focusedProperty().addListener((o, was, isNow) -> {
            if (isNow) {
                showPopup();
            }
        });
        // 已聚焦状态下再次点击也要弹出
        textField.setOnMouseClicked(e -> showPopup());
        // 一旦文本变化（用户打字或回填）就收起下拉，避免遮挡过滤结果
        textField.textProperty().addListener((o, ov, nv) -> {
            if (popup.isShowing()) {
                hidePopup();
            }
        });

        return this;
    }

    /**
     * 记录一条历史：去重置顶、超出上限截断、并持久化。空白串忽略。必须在 FX 线程调用。
     */
    public void record(String value) {
        if (value == null) {
            return;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return;
        }
        history.remove(v);      // 已存在则先移除，实现“移到最前”
        history.add(0, v);      // 置顶
        while (history.size() > maxEntries) {
            history.remove(history.size() - 1);
        }
        persist();
    }

    private void showPopup() {
        if (history.isEmpty()) {
            return;
        }
        if (popup.isShowing()) {
            return;
        }
        Bounds b = textField.localToScreen(textField.getBoundsInLocal());
        if (b == null) {
            // 尚未加入场景
            return;
        }
        expanded = false;
        rebuildContent();
        // 宽度对齐输入框
        content.setMinWidth(b.getWidth());
        content.setPrefWidth(b.getWidth());
        // 显示在输入框正下方、左缘对齐；传 textField 作为 owner，随其窗口关闭而销毁
        popup.show(textField, b.getMinX(), b.getMaxY());
    }

    private void hidePopup() {
        popup.hide();
    }

    /** 根据 expanded 状态重建可见行。 */
    private void rebuildContent() {
        content.getChildren().clear();
        int limit = expanded ? history.size() : Math.min(COLLAPSED_LIMIT, history.size());
        for (int i = 0; i < limit; i++) {
            content.getChildren().add(buildRow(history.get(i)));
        }
        if (!expanded && history.size() > COLLAPSED_LIMIT) {
            content.getChildren().add(buildMoreRow(history.size() - COLLAPSED_LIMIT));
        }
    }

    private HBox buildRow(String value) {
        Label label = new Label(value);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setPadding(new Insets(4, 8, 4, 8));
        HBox.setHgrow(label, Priority.ALWAYS);

        Button deleteButton = new Button("✕");
        deleteButton.setFocusTraversable(false);
        deleteButton.setStyle("-fx-background-color: transparent; -fx-text-fill: #999; -fx-padding: 2 6 2 6; -fx-cursor: hand;");
        deleteButton.setOnAction(e -> onRecordDeleted(value));
        // 阻止删除按钮的点击事件冒泡到行（否则会触发回填），同时不影响按钮自身的 action
        deleteButton.addEventFilter(MouseEvent.MOUSE_CLICKED, Event::consume);

        HBox row = new HBox(label, deleteButton);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-cursor: hand;");
        row.setOnMouseEntered(e -> row.setStyle("-fx-cursor: hand; -fx-background-color: #e8f0fe;"));
        row.setOnMouseExited(e -> row.setStyle("-fx-cursor: hand; -fx-background-color: transparent;"));
        row.setOnMouseClicked(e -> onRecordSelected(value));
        return row;
    }

    private Label buildMoreRow(int remaining) {
        Label more = new Label("展示更多 (剩余 " + remaining + " 条)");
        more.setMaxWidth(Double.MAX_VALUE);
        more.setAlignment(Pos.CENTER);
        more.setPadding(new Insets(5, 8, 5, 8));
        String base = "-fx-text-fill: #1a73e8; -fx-cursor: hand; -fx-border-color: #e0e0e0; -fx-border-width: 1 0 0 0;";
        more.setStyle(base);
        more.setOnMouseEntered(e -> more.setStyle(base + " -fx-background-color: #f5f5f5;"));
        more.setOnMouseExited(e -> more.setStyle(base));
        more.setOnMouseClicked(e -> {
            expanded = true;
            rebuildContent();
        });
        return more;
    }

    private void onRecordSelected(String value) {
        textField.setText(value);
        textField.positionCaret(value.length());
        hidePopup();
    }

    private void onRecordDeleted(String value) {
        history.remove(value);
        persist();
        if (history.isEmpty()) {
            hidePopup();
        } else {
            rebuildContent();
        }
    }

    private void persist() {
        // 序列化为单个字符串，规避 PrefBind 降级路径无法写入 List 的问题
        PrefBind.set(storageKey, String.join(SEP, history));
    }

    private void loadFromPrefs() {
        Object raw = PrefBind.get(storageKey);
        if (raw == null) {
            return;
        }
        String s = raw.toString();
        if (s.isEmpty()) {
            return;
        }
        for (String part : s.split("\\n", -1)) {
            String p = part.trim();
            if (!p.isEmpty() && !history.contains(p)) {
                history.add(p);
            }
        }
    }
}
