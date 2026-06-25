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

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

import com.ericsson.otp.erlang.OtpAuthException;
import com.ericsson.otp.erlang.OtpErlangException;

import de.jensd.fx.fontawesome.AwesomeIcon;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import ui.FAIcon;

/**
 * 连接详情控制界面，用于连接到远程节点。
 */
public class ConnectionView implements Initializable {

    private final SimpleBooleanProperty isConnecting = new SimpleBooleanProperty();

    @FXML
    private TextField nodeNameField;
    @FXML
    private TextField localNodeNameField;
    @FXML
    private TextField cookieField;
    @FXML
    private Button connectButton;
    @FXML
    private Label messageLabel;
    @FXML
    private CheckBox autoConnectField;

    private InputHistoryDropdown localNodeHistory;
    private InputHistoryDropdown targetNodeHistory;
    private InputHistoryDropdown cookieHistory;

    @Override
    public void initialize(URL url, ResourceBundle r) {
        PrefBind.bind("targetNodeName", nodeNameField.textProperty());
        PrefBind.bind("localNodeName", localNodeNameField.textProperty());
        PrefBind.bind("cookieName", cookieField.textProperty());
        PrefBind.bindBoolean("autoConnect", autoConnectField.selectedProperty());

        // 为连接输入框加历史记录下拉（在连接成功时记录，见 ConnectorThead.run）
        localNodeHistory = InputHistoryDropdown.install(localNodeNameField, "historyLocalNodeName", 50);
        targetNodeHistory = InputHistoryDropdown.install(nodeNameField, "historyTargetNodeName", 50);
        cookieHistory = InputHistoryDropdown.install(cookieField, "historyCookieName", 50);

        nodeNameField.disableProperty().bind(isConnecting);
        localNodeNameField.disableProperty().bind(isConnecting);
        cookieField.disableProperty().bind(isConnecting);
        connectButton.disableProperty().bind(isConnecting);
        autoConnectField.disableProperty().bind(isConnecting);

        // TODO: 或者当存在 CMDLINE 标志时立即开始连接...
        if (autoConnectField.isSelected() && !ErlyBerly.nodeAPI().manuallyDisconnected()) {
            try {
                // TODO: 这个 sleep/yield 允许 proc 控制器启动它的线程，并防止空指针异常。
                Thread.sleep(50);
            } catch(InterruptedException ex) {
                Thread.currentThread().interrupt();
            }

            this.onConnect();
        }else{
            // 显示连接对话框...
        }
    }

    @FXML
    public void onConnect() {
        String cookie;

        cookie = cookieField.getText();
        cookie = cookie.replaceAll("'", "");

        isConnecting.set(true);

        new ConnectorThead(localNodeNameField.getText(), nodeNameField.getText(), cookie).start();
    }

    private void connectionFailed(String message) {
        assert Platform.isFxApplicationThread();

        isConnecting.set(false);

        messageLabel.setGraphic(bannedIcon());
        messageLabel.setText(message);
    }

    private FAIcon bannedIcon() {
        return FAIcon.create()
                .icon(AwesomeIcon.BAN)
                .style("-fx-font-family: FontAwesome; -fx-font-size: 2em; -fx-text-fill: red;");
    }

    // TODO: 使其成为一个更通用的窗口处理函数。
    private void closeThisWindow() {
        Stage stage;
        stage = (Stage) connectButton.getScene().getWindow();
        stage.close();
    }

    /**
     * 守护线程用于连接到远程节点，这样就不会阻塞 UI。
     */
    class ConnectorThead extends Thread {
        private final String localNodeName;
        private final String remoteNodeName;
        private final String cookie;

        public ConnectorThead(String aLocalNodeName, String aRemoteNodeName, String aCookie) {
            localNodeName = aLocalNodeName;
            remoteNodeName = aRemoteNodeName;
            cookie = aCookie;

            setDaemon(true);
            setName("ErlyBerly 连接器线程");
        }

        @Override
        public void run() {
            try {
                ErlyBerly
                    .nodeAPI()
                    .connectionInfo(localNodeName, remoteNodeName, cookie)
                    .manualConnect();

                // 连接成功后记录历史，再关闭窗口（均在 FX 线程）
                Platform.runLater(() -> {
                    localNodeHistory.record(localNodeName);
                    targetNodeHistory.record(remoteNodeName);
                    cookieHistory.record(cookie);
                    closeThisWindow();
                });
            }
            catch (OtpErlangException | OtpAuthException | IOException e) {
                Platform.runLater(() -> { connectionFailed(e.getMessage()); });
            }
        }
    }
}
