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

import com.ericsson.otp.erlang.OtpErlangAtom;
import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangTuple;
import erlyberly.node.OtpUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/**
 * 给进程发送消息的对话框视图。
 * 支持三种消息类型：
 * - call: gen_server:call（同步调用，等待回复）
 * - cast: gen_server:cast（异步发送，不等待回复）
 * - info: 进程直接发送消息（Pid ! Msg）
 */
public class SendMsgView {

    private final ProcInfo procInfo;

    private ComboBox<String> msgTypeCombo;
    private TextArea msgContentArea;
    private TextField timeoutField;
    private TextArea resultArea;
    private Button sendButton;
    private Button closeButton;
    private Label statusLabel;
    private CheckBox wrapTextCheckBox;  // 自动换行复选框
    private CheckBox removeNewlineCheckBox;  // 去除\n换行符复选框

    public SendMsgView(ProcInfo procInfo) {
        this.procInfo = procInfo;
    }

    /**
     * 以 Tab 方式显示发送消息面板，嵌入主窗口，不独占。
     */
    public void showPane() {
        VBox root = createDialogContent();
        root.setPadding(new Insets(10));

        Pane pane = ErlyBerly.wrapInPane(root);
        ErlyBerly.showPane("发送消息 - " + procInfo.getShortName(), pane);
    }

    private VBox createDialogContent() {
        VBox root = new VBox(10);

        // --- 目标进程信息 ---
        Label targetLabel = new Label("目标进程: " + procInfo.getPid() + " (" + procInfo.getShortName() + ")");
        targetLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        root.getChildren().add(targetLabel);

        // --- 消息类型选择 ---
        HBox msgTypeBox = new HBox(10);
        msgTypeBox.setAlignment(Pos.CENTER_LEFT);
        Label msgTypeLabel = new Label("消息类型:");
        msgTypeCombo = new ComboBox<>();
        msgTypeCombo.getItems().addAll("call", "cast", "info");
        msgTypeCombo.setValue("info");
        msgTypeCombo.setPrefWidth(120);
        msgTypeCombo.setOnAction(e -> onMsgTypeChanged());
        msgTypeBox.getChildren().addAll(msgTypeLabel, msgTypeCombo);
        root.getChildren().add(msgTypeBox);

        // --- 超时设置（仅 call 类型有效） ---
        HBox timeoutBox = new HBox(10);
        timeoutBox.setAlignment(Pos.CENTER_LEFT);
        Label timeoutLabel = new Label("超时(ms):");
        timeoutField = new TextField("5000");
        timeoutField.setPrefWidth(100);
        timeoutBox.getChildren().addAll(timeoutLabel, timeoutField);
        timeoutBox.setVisible(false);
        timeoutBox.setManaged(false);
        root.getChildren().add(timeoutBox);

        // --- 消息内容输入 ---
        Label contentLabel = new Label("消息内容 (Erlang 术语，如 {hello, world} 或 ping):");
        root.getChildren().add(contentLabel);

        msgContentArea = new TextArea();
        msgContentArea.setPromptText("输入 Erlang 术语，例如:\n  ping\n  {hello, world}\n  {request, 123, \"data\"}\n  [1, 2, 3]");
        msgContentArea.setPrefRowCount(5);
        msgContentArea.setStyle("-fx-font-family: 'Source Code Pro Medium', monospace; -fx-font-size: 13px;");
        msgContentArea.setWrapText(false);  // 默认不自动换行
        VBox.setVgrow(msgContentArea, Priority.ALWAYS);
        root.getChildren().add(msgContentArea);

        // --- 显示选项 ---
        HBox optionsBox = new HBox(15);
        optionsBox.setAlignment(Pos.CENTER_LEFT);
        
        wrapTextCheckBox = new CheckBox("自动换行显示");
        wrapTextCheckBox.setSelected(false);
        wrapTextCheckBox.setOnAction(e -> {
            msgContentArea.setWrapText(wrapTextCheckBox.isSelected());
        });
        
        removeNewlineCheckBox = new CheckBox("发送时去除\\n换行符");
        removeNewlineCheckBox.setSelected(false);
        removeNewlineCheckBox.setTooltip(new Tooltip("勾选后，发送消息前会将文本中的\\n替换为空字符串"));
        
        optionsBox.getChildren().addAll(wrapTextCheckBox, removeNewlineCheckBox);
        root.getChildren().add(optionsBox);

        // --- 消息类型说明 ---
        Label hintLabel = new Label();
        hintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666666;");
        updateHintLabel(hintLabel);
        msgTypeCombo.setOnAction(e -> {
            onMsgTypeChanged();
            updateHintLabel(hintLabel);
        });
        root.getChildren().add(hintLabel);

        // --- 发送按钮 ---
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        sendButton = new Button("发送");
        sendButton.setStyle("-fx-background-color: #4caf50; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 20;");
        sendButton.setOnAction(e -> onSendMessage());

        closeButton = new Button("关闭此标签页");
        closeButton.setOnAction(e -> {
            // 关闭当前所在的 Tab
            javafx.scene.control.TabPane tabPane = (javafx.scene.control.TabPane) closeButton.getParent().getParent().getParent();
            for (javafx.scene.control.Tab tab : tabPane.getTabs()) {
                if (tab.getContent() == closeButton.getParent().getParent() || tab.getContent().equals(closeButton.getParent().getParent())) {
                    tabPane.getTabs().remove(tab);
                    break;
                }
            }
        });

        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 12px;");

        buttonBox.getChildren().addAll(statusLabel, sendButton, closeButton);
        root.getChildren().add(buttonBox);

        // --- 结果显示区域 ---
        Label resultLabel = new Label("返回结果:");
        root.getChildren().add(resultLabel);

        resultArea = new TextArea();
        resultArea.setEditable(false);
        resultArea.setPrefRowCount(6);
        resultArea.setStyle("-fx-font-family: 'Source Code Pro Medium', monospace; -fx-font-size: 13px;");
        VBox.setVgrow(resultArea, Priority.ALWAYS);
        root.getChildren().add(resultArea);

        return root;
    }

    private void onMsgTypeChanged() {
        String selected = msgTypeCombo.getValue();
        boolean isCall = "call".equals(selected);
        // 显示/隐藏超时字段
        HBox timeoutBox = (HBox) timeoutField.getParent();
        timeoutBox.setVisible(isCall);
        timeoutBox.setManaged(isCall);
    }

    private void updateHintLabel(Label hintLabel) {
        String selected = msgTypeCombo.getValue();
        switch (selected) {
            case "call":
                hintLabel.setText("call: 同步调用 gen_server:call(Pid, Msg, Timeout)，等待进程回复。适用于 gen_server 进程。");
                break;
            case "cast":
                hintLabel.setText("cast: 异步发送 gen_server:cast(Pid, Msg)，不等待回复。适用于 gen_server 进程。");
                break;
            case "info":
                hintLabel.setText("info: 直接发送消息 Pid ! Msg，适用于任何进程，不等待回复。");
                break;
        }
    }

    private void onSendMessage() {
        String msgType = msgTypeCombo.getValue();
        String msgContent = msgContentArea.getText().trim();

        if (msgContent.isEmpty()) {
            statusLabel.setText("请输入消息内容！");
            statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #f44336;");
            return;
        }

        if (!ErlyBerly.nodeAPI().isConnected()) {
            statusLabel.setText("未连接到 Erlang 节点！");
            statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #f44336;");
            return;
        }

        // 如果勾选了去除换行符选项，则移除所有的\n
        if (removeNewlineCheckBox.isSelected()) {
            msgContent = msgContent.replace("\n", "").replace("\r", "");
        }

        sendButton.setDisable(true);
        statusLabel.setText("发送中...");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #2196f3;");
        resultArea.clear();

        String pidString = procInfo.getPid();
        String finalMsgType = msgType;
        String finalMsgContent = msgContent;

        new Thread(() -> {
            try {
                OtpErlangObject result = ErlyBerly.nodeAPI().sendMessage(pidString, finalMsgType, finalMsgContent);

                Platform.runLater(() -> {
                    sendButton.setDisable(false);
                    displayResult(result);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    sendButton.setDisable(false);
                    statusLabel.setText("发送失败: " + e.getMessage());
                    statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #f44336;");
                    resultArea.setText("异常: " + e.getClass().getName() + "\n" + e.getMessage());
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void displayResult(OtpErlangObject result) {
        if (result == null) {
            statusLabel.setText("无返回结果（超时或连接断开）");
            statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #f44336;");
            resultArea.setText("无返回结果");
            return;
        }

        if (OtpUtil.isTupleTagged(OtpUtil.OK_ATOM, result)) {
            OtpErlangObject value = ((OtpErlangTuple) result).elementAt(1);
            statusLabel.setText("发送成功！");
            statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #4caf50;");

            // cast 和 info 返回的是原子 cast_sent / info_sent
            if (value instanceof OtpErlangAtom) {
                String atomValue = ((OtpErlangAtom) value).atomValue();
                if ("cast_sent".equals(atomValue)) {
                    resultArea.setText("cast 消息已发送（异步，无回复）");
                    return;
                } else if ("info_sent".equals(atomValue)) {
                    resultArea.setText("info 消息已发送（异步，无回复）");
                    return;
                }
            }

            // call 类型返回实际的回复内容
            String formatted = ErlyBerly.getTermFormatter().toString(value);
            resultArea.setText(formatted);
        } else if (OtpUtil.isTupleTagged(new OtpErlangAtom("error"), result)) {
            OtpErlangObject errorValue = ((OtpErlangTuple) result).elementAt(1);
            statusLabel.setText("发送失败！");
            statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #f44336;");

            String formatted;
            if (errorValue instanceof OtpErlangTuple) {
                formatted = ErlyBerly.getTermFormatter().toString(errorValue);
            } else if (errorValue instanceof OtpErlangAtom) {
                String atomValue = ((OtpErlangAtom) errorValue).atomValue();
                if ("timeout".equals(atomValue)) {
                    formatted = "超时: gen_server:call 在指定时间内未收到回复";
                } else if ("noproc".equals(atomValue)) {
                    formatted = "进程不存在: 目标进程已退出或不是 gen_server";
                } else {
                    formatted = atomValue;
                }
            } else {
                formatted = ErlyBerly.getTermFormatter().toString(errorValue);
            }
            resultArea.setText(formatted);
        } else {
            statusLabel.setText("收到未知格式的响应");
            statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #ff9800;");
            resultArea.setText(result.toString());
        }
    }
}