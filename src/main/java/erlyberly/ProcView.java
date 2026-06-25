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

import java.text.DecimalFormat;
import java.net.URL;
import java.util.ResourceBundle;

import com.ericsson.otp.erlang.OtpErlangObject;
import com.ericsson.otp.erlang.OtpErlangString;

import de.jensd.fx.fontawesome.AwesomeIcon;
import floatyfield.FloatyFieldView;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.binding.BooleanBinding;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.Parent;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.PieChart.Data;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.Parent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.util.Callback;
import ui.FAIcon;

/**
 * 处理 UI 相关任务并将处理委托给 {@link ProcController}。
 */
public class ProcView implements Initializable {

    private final ProcController procController;

    @FXML
    private TableView<ProcInfo> processView;
    @FXML
    private Button refreshButton;
    @FXML
    private Button pollButton;
    @FXML
    private Button heapPieButton;
    @FXML
    private Button stackPieButton;
    @FXML
    private Button totalHeapPieButton;
    @FXML
    private HBox headerBox;

    /**
     * 最后打开的饼图的总计
     */
    private double total = 0d;

    /**
     * 仅在 JavaFX 线程上使用
     */
    private final DecimalFormat percentFormatter = new DecimalFormat("#.#");


    public ProcView() {
        procController = new ProcController();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initialize(URL url, ResourceBundle r) {
        MenuItem menuItem;

        menuItem = new MenuItem("获取进程状态 (R16B03 或更高版本)");
        menuItem.setOnAction(this::onShowProcessStateClicked);
        menuItem.disableProperty().bind(processView.getSelectionModel().selectedItemProperty().isNull());

        MenuItem dictMenuItem;
        dictMenuItem = new MenuItem("查看进程字典");
        dictMenuItem.setOnAction(this::onShowProcessDictionaryClicked);
        dictMenuItem.disableProperty().bind(processView.getSelectionModel().selectedItemProperty().isNull());

        MenuItem sendMsgMenuItem;
        sendMsgMenuItem = new MenuItem("发送消息 (Call/Cast/Info)");
        sendMsgMenuItem.setOnAction(this::onSendMsgClicked);
        sendMsgMenuItem.disableProperty().bind(processView.getSelectionModel().selectedItemProperty().isNull());

        MenuItem mailboxMenuItem;
        mailboxMenuItem = new MenuItem("查看进程信箱");
        mailboxMenuItem.setOnAction(this::onShowProcessMailboxClicked);
        mailboxMenuItem.disableProperty().bind(processView.getSelectionModel().selectedItemProperty().isNull());

        ContextMenu contextMenu = new ContextMenu(menuItem, dictMenuItem, sendMsgMenuItem, mailboxMenuItem);

        processView.setContextMenu(contextMenu);

        // #23 当显示上下文菜单时，暂时挂起轮询，轮询会失去选择，使得右键单击上下文菜单不再启用，因为没有选择任何进程
        processView
            .getContextMenu()
            .showingProperty()
            .addListener((o, oldv, newv) -> { procController.setTemporarilySuspendPolling(newv); });

        final BooleanBinding notConnected = ErlyBerly.nodeAPI().connectedProperty().not();

        ErlyBerly.nodeAPI().connectedProperty().addListener(this::onConnected);

        heapPieButton.setGraphic(FAIcon.create().icon(AwesomeIcon.PIE_CHART));
        heapPieButton.getStyleClass().add("erlyberly-icon-button");
        heapPieButton.setStyle("-fx-background-color: transparent;");
        heapPieButton.setText("");
        heapPieButton.disableProperty().bind(notConnected);

        stackPieButton.setGraphic(FAIcon.create().icon(AwesomeIcon.PIE_CHART));
        stackPieButton.setStyle("-fx-background-color: transparent;");
        stackPieButton.setText("");
        stackPieButton.disableProperty().bind(notConnected);

        totalHeapPieButton.setGraphic(FAIcon.create().icon(AwesomeIcon.PIE_CHART));
        totalHeapPieButton.setStyle("-fx-background-color: transparent;");
        totalHeapPieButton.setText("");
        totalHeapPieButton.disableProperty().bind(notConnected);

        refreshButton.setGraphic(FAIcon.create().icon(AwesomeIcon.ROTATE_LEFT));
        refreshButton.setGraphicTextGap(8d);
        refreshButton.disableProperty().bind(procController.pollingProperty().or(notConnected));

        pollButton.setGraphic(FAIcon.create().icon(AwesomeIcon.REFRESH));
        pollButton.setGraphicTextGap(9d);
        pollButton.disableProperty().bind(notConnected);

        procController.pollingProperty().addListener(this::onPollingChange);
        procController.setListComparator(processView.comparatorProperty());

        onPollingChange(null);

        TableColumn<ProcInfo, String> pidColumn = (TableColumn<ProcInfo, String>) processView.getColumns().get(0);
        TableColumn<ProcInfo, String> procColumn = (TableColumn<ProcInfo, String>) processView.getColumns().get(1);
        TableColumn<ProcInfo, Long> reducColumn = (TableColumn<ProcInfo, Long>) processView.getColumns().get(2);
        TableColumn<ProcInfo, Long> mQueueLenColumn = (TableColumn<ProcInfo, Long>) processView.getColumns().get(3);
        TableColumn<ProcInfo, Long> heapSizeColumn = (TableColumn<ProcInfo, Long>) processView.getColumns().get(4);
        TableColumn<ProcInfo, Long> stackSizeColumn = (TableColumn<ProcInfo, Long>) processView.getColumns().get(5);
        TableColumn<ProcInfo, Long> totalHeapSizeColumn = (TableColumn<ProcInfo, Long>) processView.getColumns().get(6);

        pidColumn.setCellValueFactory(new PropertyValueFactory<ProcInfo, String>("pid"));
        pidColumn.setId("pid");

        procColumn.setCellValueFactory(new PropertyValueFactory<ProcInfo, String>("processName"));
        procColumn.setId("proc");

        reducColumn.setCellValueFactory(new PropertyValueFactory<ProcInfo, Long>("reductions"));
        reducColumn.setId("reduc");

        mQueueLenColumn.setCellValueFactory(new PropertyValueFactory<ProcInfo, Long>("msgQueueLen"));
        mQueueLenColumn.setId("mqueue");

        heapSizeColumn.setCellValueFactory(new PropertyValueFactory<ProcInfo, Long>("heapSize"));
        heapSizeColumn.setId("heapsize");

        stackSizeColumn.setCellValueFactory(new PropertyValueFactory<ProcInfo, Long>("stackSize"));
        stackSizeColumn.setId("stacksize");

        totalHeapSizeColumn.setCellValueFactory(new PropertyValueFactory<ProcInfo, Long>("totalHeapSize"));
        totalHeapSizeColumn.setId("totalheapsize");

        processView.setItems(procController.getProcs());
        processView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        addFloatySearchControl();

        initialiseProcessSorting();
    }

    private FxmlLoadable addFloatySearchControl() {
        FxmlLoadable loader = new FxmlLoadable("/floatyfield/floaty-field.fxml");

        loader.load();

        FloatyFieldView ffView;

        ffView = (FloatyFieldView) loader.controller;
        ffView.promptTextProperty().set("按进程 ID 和注册名称过滤");

        HBox.setHgrow(loader.fxmlNode, Priority.ALWAYS);

        headerBox.getChildren().add(0, new Separator(Orientation.VERTICAL));
        headerBox.getChildren().add(0, loader.fxmlNode);

        procController.filterProperty().bind(ffView.textProperty());

        // 历史记录下拉：防抖后若过滤出非空结果则记录
        javafx.scene.control.TextField filterTextField =
            (javafx.scene.control.TextField) loader.fxmlNode.getChildrenUnmodifiable().get(1);
        final InputHistoryDropdown procFilterHistory =
            InputHistoryDropdown.install(filterTextField, "historyProcFilter", 50);
        final javafx.animation.PauseTransition[] historyDebounce = {null};
        ffView.textProperty().addListener((o, ov, text) -> {
            if (historyDebounce[0] != null) {
                historyDebounce[0].stop();
            }
            historyDebounce[0] = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
            historyDebounce[0].setOnFinished(ev -> {
                if (text != null && !text.trim().isEmpty() && !procController.getProcs().isEmpty()) {
                    procFilterHistory.record(text);
                }
            });
            historyDebounce[0].play();
        });

        Platform.runLater(() -> {
            FilterFocusManager.addFilter((Control) loader.fxmlNode.getChildrenUnmodifiable().get(1), 0);
        });

        return loader;
    }

    private void onShowProcessStateClicked(ActionEvent e) {
        ProcInfo proc = processView.getSelectionModel().getSelectedItem();

        if(proc == null)
            return;

        procController.processState(proc, (eobj) -> {showProcessStateInWindow(proc, eobj); });
    }

    /**
     * 显示进程字典
     */
    @FXML
    private void onShowProcessDictionaryClicked(ActionEvent e) {
        ProcInfo proc = processView.getSelectionModel().getSelectedItem();

        if(proc == null)
            return;

        procController.processDictionary(proc, (eobj) -> {showProcessDictionaryInWindow(proc, eobj); });
    }

    /**
     * 发送消息到进程
     */
    private void onSendMsgClicked(ActionEvent e) {
        ProcInfo proc = processView.getSelectionModel().getSelectedItem();

        if(proc == null)
            return;

        SendMsgView sendMsgView = new SendMsgView(proc);
        sendMsgView.showPane();
    }

    /**
     * 查看进程信箱
     */
    private void onShowProcessMailboxClicked(ActionEvent e) {
        ProcInfo proc = processView.getSelectionModel().getSelectedItem();

        if(proc == null)
            return;

        procController.processMessages(proc, (eobj) -> {showProcessMailboxInWindow(proc, eobj); });
    }

    private void showProcessStateInWindow(ProcInfo procInfo, OtpErlangObject obj) {
        if(obj == null)
            obj = new OtpErlangString("Error, erlyberly cannot get process state. Probably not OTP compliant process");

        TermTreeView termTreeView;

        termTreeView = new TermTreeView();
        termTreeView.setMaxHeight(Integer.MAX_VALUE);
        VBox.setVgrow(termTreeView, Priority.ALWAYS);
        termTreeView.populateFromTerm(obj);

        ErlyBerly.showPane("Process State for " + procInfo.getShortName(), ErlyBerly.wrapInPane(termTreeView));
    }

    /**
     * 在窗口中显示进程字典
     */
    @SuppressWarnings("unchecked")
    private void showProcessDictionaryInWindow(ProcInfo procInfo, OtpErlangObject obj) {
        if(obj == null) {
            showNotification("该进程没有字典数据或无法获取", NotificationType.ERROR);
            return;
        }

        // 使用表格展示进程字典，与 ETS 表保持一致
        javafx.scene.control.TableView<DictTableRow> tableView = new javafx.scene.control.TableView<>();
        tableView.setMaxHeight(Integer.MAX_VALUE);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // 创建列
        javafx.scene.control.TableColumn<DictTableRow, Number> seqCol = new javafx.scene.control.TableColumn<>("序号");
        seqCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("seq"));
        seqCol.setPrefWidth(60);
        seqCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        javafx.scene.control.TableColumn<DictTableRow, String> keyCol = new javafx.scene.control.TableColumn<>("键");
        keyCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("key"));
        keyCol.setPrefWidth(300);
        keyCol.setSortable(true);
        keyCol.setComparator((a, b) -> a.compareTo(b));

        javafx.scene.control.TableColumn<DictTableRow, String> valueCol = new javafx.scene.control.TableColumn<>("值");
        valueCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("value"));
        valueCol.setPrefWidth(500);

        tableView.getColumns().addAll(seqCol, keyCol, valueCol);

        // 解析数据并填充表格
        javafx.collections.ObservableList<DictTableRow> tableData = javafx.collections.FXCollections.observableArrayList();
        parseProcessDictionary(obj, tableData);

        // 创建排序列表包装过滤列表，使列头排序生效
        FilteredList<DictTableRow> filteredData = new FilteredList<>(tableData);
        SortedList<DictTableRow> sortedData = new SortedList<>(filteredData);
        sortedData.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedData);

        // 添加右键菜单和复制功能
        addDictTableContextMenu(tableView, procInfo, filteredData, tableData, obj);

        // 创建刷新按钮
        javafx.scene.control.Button refreshButton = new javafx.scene.control.Button("刷新");
        refreshButton.setGraphic(FAIcon.create().icon(AwesomeIcon.ROTATE_LEFT));
        refreshButton.setGraphicTextGap(5d);
        final ProcInfo[] procInfoRef = {procInfo};
        refreshButton.setOnAction(e -> {
            refreshProcessDictionary(procInfoRef[0], tableView, tableData, filteredData);
        });
        
        HBox buttonBox = new HBox(10, refreshButton);
        buttonBox.setPadding(new Insets(5, 0, 5, 0));

        // 添加过滤搜索框
        Parent filterBox = addDictTableFilter(tableView, filteredData, tableData);

        // 创建布局，包含刷新按钮、搜索框和表格
        VBox root = new VBox(5, buttonBox, filterBox, tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        ErlyBerly.showPane("进程字典 - " + procInfo.getShortName() + " (" + filteredData.size() + " 条记录)", ErlyBerly.wrapInPane(root));
    }

    /**
     * 在窗口中显示进程信箱信息
     */
    @SuppressWarnings("unchecked")
    private void showProcessMailboxInWindow(ProcInfo procInfo, OtpErlangObject obj) {
        if(obj == null) {
            showNotification("无法获取进程信箱信息", NotificationType.ERROR);
            return;
        }

        // 使用表格展示进程信箱信息
        javafx.scene.control.TableView<MailboxTableRow> tableView = new javafx.scene.control.TableView<>();
        tableView.setMaxHeight(Integer.MAX_VALUE);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // 创建列
        javafx.scene.control.TableColumn<MailboxTableRow, String> keyCol = new javafx.scene.control.TableColumn<>("属性");
        keyCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("key"));
        keyCol.setPrefWidth(200);

        javafx.scene.control.TableColumn<MailboxTableRow, String> valueCol = new javafx.scene.control.TableColumn<>("值");
        valueCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("value"));
        valueCol.setPrefWidth(500);

        tableView.getColumns().addAll(keyCol, valueCol);

        // 解析数据并填充表格
        javafx.collections.ObservableList<MailboxTableRow> tableData = javafx.collections.FXCollections.observableArrayList();
        parseProcessMailbox(obj, tableData);

        tableView.setItems(tableData);

        // 创建刷新和轮询按钮
        javafx.scene.control.Button refreshButton = new javafx.scene.control.Button("刷新");
        refreshButton.setGraphic(FAIcon.create().icon(AwesomeIcon.ROTATE_LEFT));
        refreshButton.setGraphicTextGap(5d);
        
        javafx.scene.control.ToggleButton pollButton = new javafx.scene.control.ToggleButton("开始轮询");
        pollButton.setGraphic(FAIcon.create().icon(AwesomeIcon.REFRESH));
        pollButton.setGraphicTextGap(5d);
        
        HBox buttonBox = new HBox(10, refreshButton, pollButton);
        buttonBox.setPadding(new Insets(5, 0, 5, 0));
        
        // 刷新按钮事件
        final javafx.animation.PauseTransition[] pollTimer = {null};
        refreshButton.setOnAction(e -> {
            procController.processMessages(procInfo, (eobj) -> {
                Platform.runLater(() -> {
                    if (eobj != null) {
                        tableData.clear();
                        parseProcessMailbox(eobj, tableData);
                    }
                });
            });
        });
        
        // 轮询按钮事件
        pollButton.setOnAction(e -> {
            if (pollButton.isSelected()) {
                pollButton.setText("停止轮询");
                // 启动定时器，每2秒刷新一次
                javafx.animation.PauseTransition timer = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(2));
                timer.setOnFinished(event -> {
                    if (pollButton.isSelected() && ErlyBerly.nodeAPI().isConnected()) {
                        procController.processMessages(procInfo, (eobj) -> {
                            Platform.runLater(() -> {
                                if (eobj != null) {
                                    tableData.clear();
                                    parseProcessMailbox(eobj, tableData);
                                }
                                // 继续轮询
                                timer.playFromStart();
                            });
                        });
                    }
                });
                pollTimer[0] = timer;
                timer.play();
            } else {
                pollButton.setText("开始轮询");
                if (pollTimer[0] != null) {
                    pollTimer[0].stop();
                    pollTimer[0] = null;
                }
            }
        });
        
        // 当窗口关闭时停止轮询
        tableView.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null && pollTimer[0] != null) {
                pollTimer[0].stop();
            }
        });

        // 创建布局，包含按钮和表格
        VBox root = new VBox(5, buttonBox, tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        ErlyBerly.showPane("进程信箱 - " + procInfo.getShortName(), ErlyBerly.wrapInPane(root));
    }

    @FXML
    private void onHeapPie() {
        ObservableList<PieChart.Data> data = buildData(chartableProcs(), (p) -> {return p.getHeapSize(); });

        showPieChart("Process Heap", data);
    }

    @FXML
    private void onStackPie() {
        ObservableList<PieChart.Data> data = buildData(chartableProcs(), (p) -> {return p.getStackSize(); });

        showPieChart("Process Stack", data);
    }

    @FXML
    private void onTotalHeapPie() {
        ObservableList<PieChart.Data> data = buildData(chartableProcs(), (p) -> {return p.getTotalHeapSize(); });

        showPieChart("Total Heap", data);
    }

    private ObservableList<PieChart.Data> buildData(ObservableList<ProcInfo> procs, Callback<ProcInfo, Long> extractor) {

        total = 0;

        for (ProcInfo proc : procs) {
            total += extractor.call(proc);
        }

        // threshold is 0.5%, this is a limit on how many segments are added to
        // the pie chart too many seems to crash the process
        double threshold = total / 200;

        double other = 0;

        ObservableList<PieChart.Data> data = FXCollections.observableArrayList();

        for (ProcInfo proc : procs) {
            double value = extractor.call(proc);

            if(value >= threshold)
                data.add(new Data(procDescription(proc), extractor.call(proc)));
            else
                other += value;
        }

        if(other > 0)
            data.add(new Data("All processes less than 0.5% of total", other));

        return data;
    }

    private ObservableList<ProcInfo> chartableProcs() {
        ObservableList<ProcInfo> procs = processView.getSelectionModel().getSelectedItems();

        if(procs.isEmpty() || procs.size() == 1) {
            procs = procController.getProcs();
        }
        return procs;
    }

    private void showPieChart(String title, ObservableList<PieChart.Data> data) {
        PieChart pieChart;
        pieChart = new PieChart(data);
        pieChart.setTitle(title);
        pieChart.getData().stream().forEach(d -> {
            Tooltip tooltip;
            tooltip = new Tooltip();
            String percent = percentFormatter.format((d.getPieValue()/total)*100);
            tooltip.setText(d.getName() + " " + percent + "%");
            Tooltip.install(d.getNode(), tooltip);
        });
        ErlyBerly.showPane(title, ErlyBerly.wrapInPane(pieChart));
    }

    private String procDescription(ProcInfo proc) {
        String pid = proc.getProcessName();
        if(pid == null || "".equals(pid)) {
            pid = proc.getPid();
        }
        if(pid == null || "".equals(pid)) {
            pid = "unknown pid";
        }
        return pid;
    }

    private void onPollingChange(Observable o) {
        if(procController.pollingProperty().get())
            pollButton.setText("停止轮询");
        else
            pollButton.setText("开始轮询");
    }

    @FXML
    private void onRefresh() {
        procController.refreshOnce();
    }

    @FXML
    private void onTogglePolling() {
        procController.togglePolling();
    }

    private void onConnected(Observable o) {

        boolean connected = ErlyBerly.nodeAPI().connectedProperty().get();

        if(connected) {
            procController.refreshOnce();
        } else {
            procController.clearProcesses();
        }
    }

    private void initialiseProcessSorting() {
        InvalidationListener invalidationListener = new ProcSortUpdater();

        for (TableColumn<ProcInfo, ?> col : processView.getColumns()) {
            col.sortTypeProperty().addListener(invalidationListener);
        }

        processView.getSortOrder().addListener(invalidationListener);
    }

    private final class ProcSortUpdater implements InvalidationListener {
        @Override
        public void invalidated(Observable ob) {
            ProcSort procSort = null;

            if(!processView.getSortOrder().isEmpty()) {
                TableColumn<ProcInfo, ?> tableColumn = processView.getSortOrder().get(0);

                procSort = new ProcSort(tableColumn.getId(), tableColumn.getSortType());
            }
            procController.procSortProperty().set(procSort);
        }
    }

    // ==================== 进程字典表格功能 ====================

    /**
     * 通知类型枚举
     */
    public enum NotificationType {
        SUCCESS,
        ERROR,
        INFO
    }

    /**
     * 显示界面提示
     */
    private void showNotification(String message, NotificationType type) {
        System.out.println("[" + type + "] " + message);
        try {
            javafx.scene.control.Label notificationLabel = new javafx.scene.control.Label(message);
            notificationLabel.setStyle(getNotificationStyle(type));
            notificationLabel.setPadding(new javafx.geometry.Insets(10, 20, 10, 20));
            
            javafx.stage.Popup popup = new javafx.stage.Popup();
            popup.getContent().add(notificationLabel);
            
            javafx.stage.Stage stage = getActiveStage();
            if (stage != null) {
                popup.show(stage);
                javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
                delay.setOnFinished(event -> popup.hide());
                delay.play();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String getNotificationStyle(NotificationType type) {
        switch (type) {
            case SUCCESS:
                return "-fx-background-color: #4caf50; -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 5px;";
            case ERROR:
                return "-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 5px;";
            case INFO:
                return "-fx-background-color: #2196f3; -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 5px;";
            default:
                return "-fx-background-color: #757575; -fx-text-fill: white; -fx-font-size: 14px; -fx-background-radius: 5px;";
        }
    }

    private javafx.stage.Stage getActiveStage() {
        try {
            javafx.collections.ObservableList<javafx.stage.Window> windows = javafx.stage.Window.getWindows();
            for (javafx.stage.Window window : windows) {
                if (window.isShowing() && window instanceof javafx.stage.Stage) {
                    return (javafx.stage.Stage) window;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 解析进程字典为表格行
     */
    private void parseProcessDictionary(OtpErlangObject dictObj, javafx.collections.ObservableList<DictTableRow> tableData) {
        if (dictObj instanceof com.ericsson.otp.erlang.OtpErlangList) {
            com.ericsson.otp.erlang.OtpErlangList list = (com.ericsson.otp.erlang.OtpErlangList) dictObj;
            int index = 0;
            for (OtpErlangObject item : list.elements()) {
                if (item instanceof com.ericsson.otp.erlang.OtpErlangTuple) {
                    com.ericsson.otp.erlang.OtpErlangTuple tuple = (com.ericsson.otp.erlang.OtpErlangTuple) item;
                    if (tuple.arity() >= 2) {
                        OtpErlangObject rawKey = tuple.elementAt(0);
                        OtpErlangObject rawValue = tuple.elementAt(1);
                        String key = formatErlangTerm(rawKey);
                        String value = formatErlangTerm(rawValue);
                        tableData.add(new DictTableRow(key, value, index++, rawKey, rawValue));
                    }
                }
            }
        }
    }

    /**
     * 格式化 Erlang 术语为字符串
     */
    private String formatErlangTerm(OtpErlangObject obj) {
        if (obj == null) {
            return "undefined";
        }
        String result = obj.toString();
        if (result.length() > 200) {
            result = result.substring(0, 200) + "...";
        }
        return result;
    }

    /**
     * 刷新进程字典
     */
    @SuppressWarnings("unchecked")
    private void refreshProcessDictionary(ProcInfo procInfo,
                                         javafx.scene.control.TableView<DictTableRow> tableView,
                                         javafx.collections.ObservableList<DictTableRow> tableData,
                                         FilteredList<DictTableRow> filteredData) {
        procController.processDictionary(procInfo, (eobj) -> {
            Platform.runLater(() -> {
                if (eobj != null) {
                    // 清空现有数据
                    tableData.clear();
                    
                    // 重新填充数据
                    parseProcessDictionary(eobj, tableData);
                    
                    showNotification("进程字典已刷新", NotificationType.SUCCESS);
                } else {
                    showNotification("无法获取进程字典", NotificationType.ERROR);
                }
            });
        });
    }

    /**
     * 为进程字典添加过滤搜索框
     */
    @SuppressWarnings("unchecked")
    private Parent addDictTableFilter(javafx.scene.control.TableView<DictTableRow> tableView,
                                      FilteredList<DictTableRow> filteredData,
                                      javafx.collections.ObservableList<DictTableRow> tableData) {
        FxmlLoadable loader = new FxmlLoadable("/floatyfield/floaty-field.fxml");
        loader.load();

        FloatyFieldView ffView = (FloatyFieldView) loader.controller;
        ffView.promptTextProperty().set("过滤进程字典数据（按 PID、注册名或模块名）");

        // 历史记录下拉
        javafx.scene.control.TextField dictFilterTextField =
            (javafx.scene.control.TextField) loader.fxmlNode.getChildrenUnmodifiable().get(1);
        final InputHistoryDropdown dictFilterHistory =
            InputHistoryDropdown.install(dictFilterTextField, "historyProcDictFilter", 50);

        // 绑定过滤逻辑（带防抖）
        final javafx.animation.PauseTransition[] debounceTimer = {null};
        ffView.textProperty().addListener((o, oldValue, searchText) -> {
            // 取消之前的定时器
            if (debounceTimer[0] != null) {
                debounceTimer[0].stop();
            }
            // 创建新的防抖定时器，延迟 300ms 执行过滤
            debounceTimer[0] = new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
            debounceTimer[0].setOnFinished(event -> {
                filterDictData(filteredData, tableData, searchText);
                // 过滤后若有非空结果则记录历史
                if (searchText != null && !searchText.trim().isEmpty() && !filteredData.isEmpty()) {
                    dictFilterHistory.record(searchText);
                }
            });
            debounceTimer[0].play();
        });

        Region fxmlNode = (Region) loader.fxmlNode;
        fxmlNode.setPadding(new Insets(5, 5, 0, 5));

        // 注册到 FilterFocusManager
        Platform.runLater(() -> {
            FilterFocusManager.addFilter((javafx.scene.control.Control) loader.fxmlNode.getChildrenUnmodifiable().get(1), 3);
        });

        return fxmlNode;
    }

    /**
     * 过滤进程字典数据
     */
    private void filterDictData(FilteredList<DictTableRow> filteredData,
                                javafx.collections.ObservableList<DictTableRow> tableData,
                                String searchText) {
        if (searchText == null || searchText.trim().isEmpty()) {
            filteredData.setPredicate(item -> true);
        } else {
            BasicSearch basicSearch = new BasicSearch(searchText);
            filteredData.setPredicate(item -> {
                // 搜索键和值
                String key = item.getKey();
                String value = item.getValue();
                return basicSearch.matches(key, value);
            });
        }
    }

    /**
     * 为进程字典表格添加右键菜单和复制功能
     */
    @SuppressWarnings("unchecked")
    private void addDictTableContextMenu(javafx.scene.control.TableView<DictTableRow> tableView,
                                         ProcInfo procInfo,
                                         FilteredList<DictTableRow> filteredData,
                                         javafx.collections.ObservableList<DictTableRow> tableData,
                                         OtpErlangObject rawContentObj) {
        String procName = procInfo.getShortName();
        javafx.scene.control.ContextMenu contextMenu = new javafx.scene.control.ContextMenu();

        // 复制选中行
        javafx.scene.control.MenuItem copySelectedMenuItem = new javafx.scene.control.MenuItem("复制选中行");
        copySelectedMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+c"));
        copySelectedMenuItem.setOnAction(e -> copyDictSelectedRows(tableView));
        contextMenu.getItems().add(copySelectedMenuItem);

        // 复制全部（使用原始数据，不是过滤后的）
        javafx.scene.control.MenuItem copyAllMenuItem = new javafx.scene.control.MenuItem("复制全部");
        copyAllMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+shift+c"));
        copyAllMenuItem.setOnAction(e -> copyDictAllRows(tableView, procName, tableData));
        contextMenu.getItems().add(copyAllMenuItem);

        // 分隔线
        contextMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

        // 复制选中行原始数据
        javafx.scene.control.MenuItem copyRawSelectedMenuItem = new javafx.scene.control.MenuItem("复制选中行原始数据");
        copyRawSelectedMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+r"));
        copyRawSelectedMenuItem.setOnAction(e -> copyDictRawSelectedRows(tableView, rawContentObj));
        contextMenu.getItems().add(copyRawSelectedMenuItem);

        // 复制全部原始数据
        javafx.scene.control.MenuItem copyRawAllMenuItem = new javafx.scene.control.MenuItem("复制全部原始数据");
        copyRawAllMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+shift+r"));
        copyRawAllMenuItem.setOnAction(e -> copyDictRawData(rawContentObj));
        contextMenu.getItems().add(copyRawAllMenuItem);

        // 查看详细值菜单项
        javafx.scene.control.MenuItem viewDetailMenuItem = new javafx.scene.control.MenuItem("查看详细值");
        viewDetailMenuItem.setOnAction(e -> showDictValueDetail(tableView, procInfo));
        contextMenu.getItems().add(viewDetailMenuItem);

        // 分隔线
        contextMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

        // 导出到文件（使用原始数据，不是过滤后的）
        javafx.scene.control.MenuItem exportMenuItem = new javafx.scene.control.MenuItem("导出到文件");
        exportMenuItem.setOnAction(e -> exportDictToFile(tableView, procName, tableData));
        contextMenu.getItems().add(exportMenuItem);

        // 设置菜单可用状态
        copySelectedMenuItem.disableProperty().bind(
            tableView.getSelectionModel().selectedItemProperty().isNull()
        );
        copyRawSelectedMenuItem.disableProperty().bind(
            tableView.getSelectionModel().selectedItemProperty().isNull()
        );
        copyAllMenuItem.disableProperty().bind(
            javafx.beans.binding.Bindings.isEmpty(tableView.getItems())
        );
        copyRawAllMenuItem.disableProperty().bind(
            javafx.beans.binding.Bindings.isEmpty(tableView.getItems())
        );

        viewDetailMenuItem.disableProperty().bind(
            tableView.getSelectionModel().selectedItemProperty().isNull()
        );

        tableView.setContextMenu(contextMenu);

        // 双击查看详细值
        tableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                showDictValueDetail(tableView, procInfo);
            }
        });
    }

    /**
     * 弹出窗口显示进程字典键或值的详细 Erlang 数据结构
     */
    private void showDictValueDetail(javafx.scene.control.TableView<DictTableRow> tableView, ProcInfo procInfo) {
        DictTableRow selectedRow = tableView.getSelectionModel().getSelectedItem();
        if (selectedRow == null) {
            return;
        }
        OtpErlangObject rawValue = selectedRow.getRawValue();
        if (rawValue == null) {
            return;
        }
        // 刷新时按键重新向节点拉取进程字典，取该键最新的值
        final OtpErlangObject rawKey = selectedRow.getRawKey();
        showTermDetailPopup(selectedRow.getKey(), rawValue,
            onResult -> procController.processDictionary(procInfo,
                eobj -> onResult.accept(findRawValueByKey(eobj, rawKey))));
    }

    /**
     * 在 {key, value, ...} 元组列表中按原始键查找对应的原始值，找不到返回 null
     */
    private OtpErlangObject findRawValueByKey(OtpErlangObject contentObj, OtpErlangObject rawKey) {
        if (contentObj instanceof com.ericsson.otp.erlang.OtpErlangList && rawKey != null) {
            for (OtpErlangObject item : ((com.ericsson.otp.erlang.OtpErlangList) contentObj).elements()) {
                if (item instanceof com.ericsson.otp.erlang.OtpErlangTuple) {
                    com.ericsson.otp.erlang.OtpErlangTuple t = (com.ericsson.otp.erlang.OtpErlangTuple) item;
                    if (t.arity() >= 2 && rawKey.equals(t.elementAt(0))) {
                        return t.elementAt(1);
                    }
                }
            }
        }
        return null;
    }

    /**
     * 使用 TermTreeView 弹窗显示 Erlang 数据结构的详细视图
     */
    private void showTermDetailPopup(String title, OtpErlangObject term) {
        showTermDetailPopup(title, term, null);
    }

    /**
     * 使用 TermTreeView 弹窗显示 Erlang 数据结构的详细视图（支持刷新）
     * @param title 标题
     * @param term 初始显示的术语
     * @param valueReloader 刷新回调：accept(onResult) 应异步向节点取该键最新值后调用 onResult.accept(最新值)，
     *                      取不到则传 null；为 null 时表示不支持刷新
     */
    @SuppressWarnings("unchecked")
    private void showTermDetailPopup(String title, OtpErlangObject term,
                                     java.util.function.Consumer<java.util.function.Consumer<OtpErlangObject>> valueReloader) {
        TermTreeView termTreeView = new TermTreeView();
        termTreeView.populateFromTerm(term);
        termTreeView.setPrefSize(800, 600);

        // 保存当前显示的术语对象，用于复制和刷新
        final OtpErlangObject[] currentTerm = {term};

        // 创建一个带复制和刷新按钮的工具栏
        javafx.scene.control.Button copyButton = new javafx.scene.control.Button("复制原始数据");
        copyButton.setOnAction(e -> {
            // 复制当前显示的完整数据，不会被截断
            String fullData = currentTerm[0].toString();
            copyToClipboard(fullData);
            showCopyNotification(1, "已复制原始数据到剪贴板（" + fullData.length() + " 字符）");
        });

        javafx.scene.control.Button refreshButton = new javafx.scene.control.Button("刷新");
        refreshButton.setGraphic(FAIcon.create().icon(AwesomeIcon.ROTATE_LEFT));
        refreshButton.setGraphicTextGap(5d);

        refreshButton.setOnAction(e -> {
            if (valueReloader == null) {
                showNotification("无法重新加载数据", NotificationType.INFO);
                return;
            }
            refreshButton.setDisable(true);
            valueReloader.accept(latest -> Platform.runLater(() -> {
                refreshButton.setDisable(false);
                if (latest != null) {
                    currentTerm[0] = latest;
                    // 先清空树视图，再重新填充从节点取回的新数据
                    termTreeView.getRoot().getChildren().clear();
                    termTreeView.populateFromTerm(latest);
                    showNotification("已从节点重新加载数据", NotificationType.SUCCESS);
                } else {
                    showNotification("该键已不存在或无法获取最新值", NotificationType.INFO);
                }
            }));
        });

        javafx.scene.layout.HBox toolbar = new javafx.scene.layout.HBox(5, copyButton, refreshButton);
        toolbar.setPadding(new javafx.geometry.Insets(5));

        VBox root = new VBox(5, toolbar, termTreeView);
        VBox.setVgrow(termTreeView, javafx.scene.layout.Priority.ALWAYS);

        ErlyBerly.showPane("详细值 - " + title, ErlyBerly.wrapInPane(root));
    }

    /**
     * 复制选中的行
     */
    private void copyDictSelectedRows(javafx.scene.control.TableView<DictTableRow> tableView) {
        javafx.collections.ObservableList<DictTableRow> selectedRows = tableView.getSelectionModel().getSelectedItems();
        if (selectedRows == null || selectedRows.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (DictTableRow row : selectedRows) {
            sb.append(row.getKey()).append("\t").append(row.getValue()).append("\n");
        }
        copyToClipboard(sb.toString());
        showCopyNotification(selectedRows.size(), "已复制选中行到剪贴板");
    }

    /**
     * 复制所有行
     */
    private void copyDictAllRows(javafx.scene.control.TableView<DictTableRow> tableView, String procName, javafx.collections.ObservableList<DictTableRow> allRows) {
        if (allRows == null || allRows.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("进程字典: ").append(procName).append("\n");
        sb.append("键\t值\n");
        sb.append("---\t---\n");
        for (DictTableRow row : allRows) {
            sb.append(row.getKey()).append("\t").append(row.getValue()).append("\n");
        }
        copyToClipboard(sb.toString());
        showCopyNotification(allRows.size(), "已复制全部数据到剪贴板");
    }

    /**
     * 复制选中行的原始 Erlang 数据
     */
    @SuppressWarnings("unchecked")
    private void copyDictRawSelectedRows(javafx.scene.control.TableView<DictTableRow> tableView, 
                                         OtpErlangObject rawContentObj) {
        javafx.collections.ObservableList<DictTableRow> selectedRows = tableView.getSelectionModel().getSelectedItems();
        if (selectedRows == null || selectedRows.isEmpty()) {
            showCopyNotification(0, "没有选中的行");
            return;
        }
        if (rawContentObj == null || !(rawContentObj instanceof com.ericsson.otp.erlang.OtpErlangList)) {
            showCopyNotification(0, "没有可复制的原始数据");
            return;
        }
        com.ericsson.otp.erlang.OtpErlangList originalList = (com.ericsson.otp.erlang.OtpErlangList) rawContentObj;
        java.util.Set<Integer> selectedIndexSet = new java.util.HashSet<>();
        for (DictTableRow row : selectedRows) {
            selectedIndexSet.add(row.getIndex());
        }
        java.util.List<OtpErlangObject> selectedItems = new java.util.ArrayList<>();
        int currentIndex = 0;
        for (OtpErlangObject item : originalList.elements()) {
            if (selectedIndexSet.contains(currentIndex)) {
                selectedItems.add(item);
            }
            currentIndex++;
        }
        StringBuilder sb = new StringBuilder();
        if (selectedItems.size() == 1) {
            sb.append(selectedItems.get(0).toString());
        } else {
            sb.append("[");
            for (int i = 0; i < selectedItems.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(selectedItems.get(i).toString());
            }
            sb.append("]");
        }
        copyToClipboard(sb.toString());
        showCopyNotification(selectedItems.size(), "已复制原始 Erlang 数据到剪贴板");
    }

    /**
     * 复制原始 Erlang 数据
     */
    private void copyDictRawData(OtpErlangObject rawContentObj) {
        if (rawContentObj == null) {
            showCopyNotification(0, "没有可复制的原始数据");
            return;
        }
        String rawData = rawContentObj.toString();
        copyToClipboard(rawData);
        showCopyNotification(1, "已复制原始 Erlang 数据到剪贴板（" + rawData.length() + " 字符）");
    }

    /**
     * 复制到剪贴板
     */
    private void copyToClipboard(String text) {
        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        clipboard.setContent(content);
    }

    /**
     * 显示复制成功提示
     */
    private void showCopyNotification(int count, String message) {
        if (count == 0) {
            showNotification(message, NotificationType.ERROR);
        } else {
            String fullMessage = message + " （" + count + " 行）";
            showNotification(fullMessage, NotificationType.SUCCESS);
        }
    }

    /**
     * 导出到文件
     */
    private void exportDictToFile(javafx.scene.control.TableView<DictTableRow> tableView, String procName, javafx.collections.ObservableList<DictTableRow> allRows) {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("导出进程字典数据");
        fileChooser.setInitialFileName(procName + "_dict_data.txt");
        fileChooser.getExtensionFilters().addAll(
            new javafx.stage.FileChooser.ExtensionFilter("文本文件", "*.txt"),
            new javafx.stage.FileChooser.ExtensionFilter("CSV 文件", "*.csv"),
            new javafx.stage.FileChooser.ExtensionFilter("所有文件", "*.*")
        );
        javafx.stage.Stage stage = (javafx.stage.Stage) tableView.getScene().getWindow();
        java.io.File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try (java.io.PrintWriter writer = new java.io.PrintWriter(file, "UTF-8")) {
                writer.println("进程字典: " + procName);
                writer.println("总记录数: " + allRows.size());
                writer.println();
                writer.println("键\t值");
                writer.println("---\t---");
                for (DictTableRow row : allRows) {
                    writer.println(row.getKey() + "\t" + row.getValue());
                }
                showNotification("数据已导出到: " + file.getAbsolutePath(), NotificationType.SUCCESS);
            } catch (Exception e) {
                e.printStackTrace();
                showNotification("导出失败: " + e.getMessage(), NotificationType.ERROR);
            }
        }
    }

    /**
     * 进程字典行数据类
     */
    public static class DictTableRow {
        private final int seq;
        private final String key;
        private final String value;
        private final int index;
        private final OtpErlangObject rawKey;
        private final OtpErlangObject rawValue;

        public DictTableRow(String key, String value, int index,
                            OtpErlangObject rawKey,
                            OtpErlangObject rawValue) {
            this.seq = index + 1;
            this.key = key;
            this.value = value;
            this.index = index;
            this.rawKey = rawKey;
            this.rawValue = rawValue;
        }

        public int getSeq() { return seq; }
        public String getKey() { return key; }
        public String getValue() { return value; }
        public int getIndex() { return index; }
        public OtpErlangObject getRawKey() { return rawKey; }
        public OtpErlangObject getRawValue() { return rawValue; }
    }

    /**
     * 解析进程信箱信息
     */
    private void parseProcessMailbox(OtpErlangObject obj, javafx.collections.ObservableList<MailboxTableRow> tableData) {
        if (obj instanceof com.ericsson.otp.erlang.OtpErlangList) {
            com.ericsson.otp.erlang.OtpErlangList list = (com.ericsson.otp.erlang.OtpErlangList) obj;
            for (OtpErlangObject item : list.elements()) {
                if (item instanceof com.ericsson.otp.erlang.OtpErlangTuple) {
                    com.ericsson.otp.erlang.OtpErlangTuple tuple = (com.ericsson.otp.erlang.OtpErlangTuple) item;
                    if (tuple.arity() == 2) {
                        String key = formatErlangTerm(tuple.elementAt(0));
                        String value = formatErlangTerm(tuple.elementAt(1));
                        tableData.add(new MailboxTableRow(key, value));
                    }
                }
            }
        }
    }

    /**
     * 进程信箱行数据类
     */
    public static class MailboxTableRow {
        private final String key;
        private final String value;

        public MailboxTableRow(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() { return key; }
        public String getValue() { return value; }
    }
}
