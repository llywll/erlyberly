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
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ResourceBundle;

import com.ericsson.otp.erlang.OtpErlangException;
import com.ericsson.otp.erlang.OtpErlangObject;

import de.jensd.fx.fontawesome.AwesomeIcon;
import erlyberly.node.AppProcs;
import erlyberly.node.OtpUtil;
import floatyfield.FloatyFieldView;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.ObservableMap;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.PieChart.Data;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import ui.FAIcon;

public class TopBarView implements Initializable {
    private static final KeyCodeCombination TOGGLE_HIDE_PROCESSES_SHORTCUT = new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN);

    private static final KeyCodeCombination TOGGLE_HIDE_MODULES_SHORTCUT = new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN);

    private static final KeyCodeCombination REFRESH_MODULES_SHORTCUT = new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN);

    private final SimpleIntegerProperty unreadCrashReportsProperty = new SimpleIntegerProperty(0);

    private final SimpleBooleanProperty isXrefAnalysing = new SimpleBooleanProperty();

    /**
     * ETS 表信息类
     */
    public static class EtsTableInfo {
        private final String name;
        private final String type;
        private final long size;
        private final long memory;
        private final String owner;

        public EtsTableInfo(String name, String type, long size, long memory, String owner) {
            this.name = name;
            this.type = type;
            this.size = size;
            this.memory = memory;
            this.owner = owner;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public long getSize() {
            return size;
        }

        public long getMemory() {
            return memory;
        }

        public String getOwner() {
            return owner;
        }
    }

    @FXML
    private ToggleButton hideProcessesButton;
    @FXML
    private ToggleButton hideFunctionsButton;
    @FXML
    private Button refreshModulesButton;
    @FXML
    private Button erlangMemoryButton;

    private MenuButton crashReportsButton = new MenuButton("崩溃报告");
    @FXML
    private Button xrefAnalysisButton;
    @FXML
    private Button disconnectButton;
    @FXML
    private Button prefButton;
    @FXML
    private Button suspendButton;
    @FXML
    private ToolBar topBox;

    private EventHandler<ActionEvent> refreshModulesAction;

    @Override
    public void initialize(URL url, ResourceBundle r) {
        topBox.getItems().add(crashReportsButton);

        // TODO: 我们应该在断开连接时隐藏这些按钮吗？
        hideProcessesButton.setGraphic(FAIcon.create().icon(AwesomeIcon.RANDOM));
        hideProcessesButton.setContentDisplay(ContentDisplay.TOP);
        hideProcessesButton.setGraphicTextGap(0d);
        hideProcessesButton.setTooltip(new Tooltip("显示/隐藏进程列表 (ctrl+p)"));

        // TODO: 我们应该在断开连接时隐藏这些按钮吗？
        hideFunctionsButton.setGraphic(FAIcon.create().icon(AwesomeIcon.CUBE));
        hideFunctionsButton.setContentDisplay(ContentDisplay.TOP);
        hideFunctionsButton.setGraphicTextGap(0d);
        hideFunctionsButton.setTooltip(new Tooltip("显示/隐藏模块列表 (ctrl+m)"));

        refreshModulesButton.setGraphic(FAIcon.create().icon(AwesomeIcon.ROTATE_LEFT));
        refreshModulesButton.setContentDisplay(ContentDisplay.TOP);
        refreshModulesButton.setGraphicTextGap(0d);
        refreshModulesButton.setTooltip(new Tooltip("刷新模块和函数以显示新的热加载代码 (ctrl+r)"));
        refreshModulesButton.disableProperty().bind(ErlyBerly.nodeAPI().connectedProperty().not());

        erlangMemoryButton.setGraphic(FAIcon.create().icon(AwesomeIcon.PIE_CHART));
        erlangMemoryButton.setContentDisplay(ContentDisplay.TOP);
        erlangMemoryButton.setGraphicTextGap(0d);
        erlangMemoryButton.setTooltip(new Tooltip("显示 Erlang 内存使用情况"));
        erlangMemoryButton.disableProperty().bind(ErlyBerly.nodeAPI().connectedProperty().not());
        erlangMemoryButton.setOnAction((e) -> { showErlangMemory(); });

        Button etsButton = new Button("ETS 表");
        etsButton.setGraphic(FAIcon.create().icon(AwesomeIcon.TABLE));
        etsButton.setContentDisplay(ContentDisplay.TOP);
        etsButton.setGraphicTextGap(0d);
        etsButton.setTooltip(new Tooltip("查看 ETS 表"));
        etsButton.disableProperty().bind(ErlyBerly.nodeAPI().connectedProperty().not());
        etsButton.setOnAction((e) -> { showEtsTables(); });
        topBox.getItems().add(etsButton);

        // TODO: 也许让这个按钮在某些时候可用，有时节点会因为崩溃而宕机，然后断开连接时就禁用了....(我想查看崩溃) :)
        crashReportsButton.setGraphic(crashReportsGraphic());
        crashReportsButton.setContentDisplay(ContentDisplay.LEFT);
        crashReportsButton.setGraphicTextGap(0d);
        crashReportsButton.setTooltip(new Tooltip("查看从连接节点收到的崩溃报告。"));
        // 如果未连接或没有崩溃报告菜单项，则禁用该按钮
        crashReportsButton.disableProperty().bind(
                ErlyBerly.nodeAPI().connectedProperty().not()
                .or(Bindings.size(crashReportsButton.getItems()).isEqualTo(2)));
        crashReportsButton.setStyle("-fx-font-size: 10; -fx-padding: 5 5 5 5;");
        crashReportsButton.getItems().addAll(removeCrashReportsMenuItem(), new SeparatorMenuItem());

        ErlyBerly.nodeAPI().getCrashReports()
            .addListener((ListChangeListener.Change<? extends OtpErlangObject> e) -> {
                while(e.next()) {
                    for (OtpErlangObject obj : e.getAddedSubList()) {
                        CrashReport crashReport = new  CrashReport(obj);
                        MenuItem menuItem;
                        menuItem = new MenuItem();
                        menuItem.setGraphic(new CrashReportGraphic(crashReport));
                        menuItem.setOnAction((action) -> {
                            unreadCrashReportsProperty.set(0);
                            ErlyBerly.showPane("Crash Report", ErlyBerly.wrapInPane(crashReportView(crashReport)));
                        });
                        crashReportsButton.getItems().add(menuItem);
                        }
                    }
                });

        xrefAnalysisButton.setGraphic(xrefAnalysisGraphic());
        xrefAnalysisButton.setContentDisplay(ContentDisplay.TOP);
        xrefAnalysisButton.setGraphicTextGap(0d);
        xrefAnalysisButton.setTooltip(new Tooltip("开始交叉引用分析。这可能需要一段时间，完成后会显示确定消息。"));
        xrefAnalysisButton.disableProperty().bind(
                ErlyBerly.nodeAPI().connectedProperty().not().or(isXrefAnalysing).or(ErlyBerly.nodeAPI().xrefStartedProperty()));
        xrefAnalysisButton.setOnAction((e) -> {
            try {
                ErlyBerly.nodeAPI().asyncEnsureXRefStarted();
                isXrefAnalysing.set(true);
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        });

        disconnectButton.setGraphic(FAIcon.create().icon(AwesomeIcon.EJECT));
        disconnectButton.setContentDisplay(ContentDisplay.TOP);
        disconnectButton.setGraphicTextGap(0d);
        disconnectButton.setTooltip(new Tooltip("断开连接"));
        disconnectButton.disableProperty().bind(ErlyBerly.nodeAPI().connectedProperty().not());
        disconnectButton.setOnAction((e) -> {
            try {
                disconnect();
            } catch (Exception e1) {
                e1.printStackTrace();
            }
        });

        prefButton.setGraphic(FAIcon.create().icon(AwesomeIcon.GEARS));
        prefButton.setContentDisplay(ContentDisplay.TOP);
        prefButton.setGraphicTextGap(0d);
        prefButton.setTooltip(new Tooltip("首选项"));
        prefButton.disableProperty().bind(ErlyBerly.nodeAPI().connectedProperty().not());
        prefButton.setOnAction((e) -> { displayPreferencesPane(); });

        suspendButton.setContentDisplay(ContentDisplay.TOP);
        suspendButton.setGraphicTextGap(0d);
        suspendButton.setTooltip(new Tooltip("Toggle Trace Suspension"));
        suspendButton.disableProperty().bind(ErlyBerly.nodeAPI().connectedProperty().not());
        suspendButton.setOnAction((e) -> { suspendTraces(); });
        // set the default text and icon
        onSuspendedStateChanged(false);
        // listen to when tracing is suspend or not, and update the button text and icon
        ErlyBerly.nodeAPI().suspendedProperty().addListener((o,oldv,suspended) -> {
            onSuspendedStateChanged(suspended);
        });

        hideProcsProperty().addListener((Observable o) -> { toggleHideProcs(); });
        hideFunctionsProperty().addListener((Observable o) -> { toggleHideFuncs(); });

        erlangMemoryButton.setOnAction((e) -> { showErlangMemory(); });

        FxmlLoadable loader = processCountStat();

        topBox.getItems().add(new Separator(Orientation.VERTICAL));
        topBox.getItems().add(loader.fxmlNode);

        // let's store the ui preferences, as the end user changes them...
        PrefBind.bindBoolean("hideProcesses", hideProcessesButton.selectedProperty());
        PrefBind.bindBoolean("hideModules", hideFunctionsButton.selectedProperty());

        boolean hideProcs = PrefBind.getOrDefaultBoolean("hideProcesses", false);
        boolean hideMods = PrefBind.getOrDefaultBoolean("hideModules", false);

        if(hideProcs){
            // click the hide button manually.
            hideProcessesButton.setSelected(true);
        }
        if(hideMods){
            // click the hide button manually.
            hideFunctionsButton.setSelected(true);
        }

        toggleHideProcs();
        toggleHideFuncs();

        ErlyBerly.nodeAPI()
            .getCrashReports()
            .addListener(this::traceLogsChanged);
        ErlyBerly.nodeAPI()
            .xrefStartedProperty()
            .addListener((e, oldv, newv) -> { if(newv) isXrefAnalysing.set(false); });
    }

    private void onSuspendedStateChanged(Boolean suspended) {
        if(suspended) {
            suspendButton.setText("取消挂起");
            suspendButton.getStyleClass().add("button-suspended");
        }
        else {
            suspendButton.setText("挂起");
            suspendButton.setGraphic(FAIcon.create().icon(AwesomeIcon.PAUSE));
            suspendButton.getStyleClass().remove("button-suspended");
        }
    }

    private void suspendTraces() {
        try {
            ErlyBerly.nodeAPI().toggleSuspended();
        } catch (OtpErlangException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private MenuItem removeCrashReportsMenuItem() {
        MenuItem menuItem;
        menuItem = new MenuItem("删除所有报告");
        menuItem.setOnAction((e) -> {
            ObservableList<MenuItem> items = crashReportsButton.getItems();
            if(items.size() == 2)
                return;
            // the first two items are this menu item and a separator, delete
            // everything after that
            items.remove(2, items.size());
            unreadCrashReportsProperty.set(0);
        });
        return menuItem;
    }

    public void traceLogsChanged(ListChangeListener.Change<? extends OtpErlangObject> e) {
        while(e.next()) {
            int size = e.getAddedSubList().size();
            unreadCrashReportsProperty.set(unreadCrashReportsProperty.get() + size);
        }
    }

    private CrashReportView crashReportView(CrashReport crashReport) {
        CrashReportView crashReportView;
        crashReportView = new CrashReportView();
        crashReportView.setCrashReport(crashReport);
        return crashReportView;
    }

    private Parent crashReportsGraphic() {
        FAIcon icon;

        icon = FAIcon.create().icon(AwesomeIcon.WARNING);
        icon.setPadding(new Insets(0, 5, 0, 5));

        Label reportCountLabel;

        reportCountLabel = new Label("122");
        reportCountLabel.setStyle("-fx-background-color:red; -fx-font-size:9; -fx-padding: 0 2 0 2; -fx-opacity:0.7");
        reportCountLabel.setTextFill(Color.WHITE);

        reportCountLabel.setText(unreadCrashReportsProperty.getValue().toString());
        unreadCrashReportsProperty.addListener((o, oldv, newv) -> { reportCountLabel.setText(newv.toString()); });
        reportCountLabel.visibleProperty().bind(unreadCrashReportsProperty.greaterThan(0));

        StackPane stackPane = new StackPane(icon, reportCountLabel);
        StackPane.setAlignment(reportCountLabel, Pos.TOP_RIGHT);
        return stackPane;
    }

    private Parent xrefAnalysisGraphic() {
        FAIcon icon;

        icon = FAIcon.create().icon(AwesomeIcon.TH_LARGE);
        icon.setPadding(new Insets(0, 5, 0, 5));
        icon.visibleProperty().bind(isXrefAnalysing.not());
        Label reportCountLabel;

        reportCountLabel = new Label("完成");
        reportCountLabel.setStyle("-fx-background-color:green; -fx-font-size:9; -fx-padding: 0 2 0 2; -fx-opacity:0.9");
        reportCountLabel.setTextFill(Color.WHITE);

        ProgressIndicator analysisProgressIndicator;
        analysisProgressIndicator = new ProgressIndicator();
        analysisProgressIndicator.visibleProperty().bind(isXrefAnalysing);
        analysisProgressIndicator.setPrefSize(10d, 10d);
        analysisProgressIndicator.setStyle("-fx-progress-color: black;");

        reportCountLabel.visibleProperty().bind(ErlyBerly.nodeAPI().xrefStartedProperty());

        StackPane stackPane = new StackPane(icon, reportCountLabel, analysisProgressIndicator);
        StackPane.setAlignment(reportCountLabel, Pos.TOP_RIGHT);
        return stackPane;
    }

    private void showErlangMemory() {
        ObservableList<PieChart.Data> data = FXCollections.observableArrayList();

        showPieChart(data);

        ErlangMemoryThread emThread;
        emThread = new ErlangMemoryThread(data);
        emThread.start();
    }

    /**
     * 显示 ETS 表列表
     */
    private void showEtsTables() {
        try {
            new Thread(() -> {
                try {
                    OtpErlangObject tablesObj = ErlyBerly.nodeAPI().getEtsTables();
                    
                    Platform.runLater(() -> {
                        if (tablesObj != null) {
                            showEtsTablesWindow(tablesObj);
                        } else {
                            System.out.println("无法获取 ETS 表信息");
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 显示 ETS 表列表窗口
     */
    @SuppressWarnings("unchecked")
    private void showEtsTablesWindow(OtpErlangObject tablesObj) {
        javafx.scene.control.TableView<EtsTableInfo> tableView = new javafx.scene.control.TableView<>();
        tableView.setMaxHeight(Integer.MAX_VALUE);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // 创建表格列
        javafx.scene.control.TableColumn<EtsTableInfo, String> nameCol = new javafx.scene.control.TableColumn<>("表名");
        nameCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(200);

        javafx.scene.control.TableColumn<EtsTableInfo, String> typeCol = new javafx.scene.control.TableColumn<>("类型");
        typeCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("type"));
        typeCol.setPrefWidth(100);

        javafx.scene.control.TableColumn<EtsTableInfo, Number> sizeCol = new javafx.scene.control.TableColumn<>("大小");
        sizeCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("size"));
        sizeCol.setPrefWidth(100);

        javafx.scene.control.TableColumn<EtsTableInfo, Number> memoryCol = new javafx.scene.control.TableColumn<>("内存");
        memoryCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("memory"));
        memoryCol.setPrefWidth(100);

        javafx.scene.control.TableColumn<EtsTableInfo, String> ownerCol = new javafx.scene.control.TableColumn<>("所有者");
        ownerCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("owner"));
        ownerCol.setPrefWidth(150);

        tableView.getColumns().addAll(nameCol, typeCol, sizeCol, memoryCol, ownerCol);

        // 解析数据
        ObservableList<EtsTableInfo> tableData = FXCollections.observableArrayList();
        if (tablesObj instanceof com.ericsson.otp.erlang.OtpErlangList) {
            com.ericsson.otp.erlang.OtpErlangList list = (com.ericsson.otp.erlang.OtpErlangList) tablesObj;
            for (OtpErlangObject item : list.elements()) {
                if (item instanceof com.ericsson.otp.erlang.OtpErlangList) {
                    EtsTableInfo info = parseEtsTableInfo((com.ericsson.otp.erlang.OtpErlangList) item);
                    if (info != null) {
                        tableData.add(info);
                    }
                }
            }
        }

        tableView.setItems(tableData);

        // 双击查看表内容
        tableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                EtsTableInfo selected = tableView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    showEtsTableContent(selected.getName());
                }
            }
        });

        ErlyBerly.showPane("ETS 表", ErlyBerly.wrapInPane(tableView));
    }

    /**
     * 解析 ETS 表信息
     */
    private EtsTableInfo parseEtsTableInfo(com.ericsson.otp.erlang.OtpErlangList infoList) {
        try {
            String name = "";
            String type = "";
            long size = 0;
            long memory = 0;
            String owner = "";

            for (OtpErlangObject item : infoList.elements()) {
                if (item instanceof com.ericsson.otp.erlang.OtpErlangTuple) {
                    com.ericsson.otp.erlang.OtpErlangTuple tuple = (com.ericsson.otp.erlang.OtpErlangTuple) item;
                    if (tuple.arity() == 2) {
                        OtpErlangObject key = tuple.elementAt(0);
                        OtpErlangObject value = tuple.elementAt(1);

                        if (key instanceof com.ericsson.otp.erlang.OtpErlangAtom) {
                            String keyStr = ((com.ericsson.otp.erlang.OtpErlangAtom) key).atomValue();

                            switch (keyStr) {
                                case "name":
                                    name = value.toString();
                                    break;
                                case "type":
                                    type = value.toString();
                                    break;
                                case "size":
                                    if (value instanceof com.ericsson.otp.erlang.OtpErlangLong) {
                                        size = ((com.ericsson.otp.erlang.OtpErlangLong) value).longValue();
                                    }
                                    break;
                                case "memory":
                                    if (value instanceof com.ericsson.otp.erlang.OtpErlangLong) {
                                        memory = ((com.ericsson.otp.erlang.OtpErlangLong) value).longValue();
                                    }
                                    break;
                                case "owner":
                                    // 解析新的所有者格式: {owner_pid, PidStr, owner_name, RegName}
                                    if (value instanceof com.ericsson.otp.erlang.OtpErlangTuple) {
                                        com.ericsson.otp.erlang.OtpErlangTuple ownerTuple = (com.ericsson.otp.erlang.OtpErlangTuple) value;
                                        if (ownerTuple.arity() == 4) {
                                            String pidStr = ownerTuple.elementAt(1).toString();
                                            String regName = ownerTuple.elementAt(3).toString();
                                            // 格式化为 "PID (注册名)" 或仅 "PID"
                                            if (regName != null && !regName.isEmpty() && !regName.equals("\"\"")) {
                                                owner = pidStr + " (" + regName + ")";
                                            } else {
                                                owner = pidStr;
                                            }
                                        } else {
                                            owner = value.toString();
                                        }
                                    } else {
                                        owner = value.toString();
                                    }
                                    break;
                            }
                        }
                    }
                }
            }

            return new EtsTableInfo(name, type, size, memory, owner);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 显示 ETS 表内容
     */
    @SuppressWarnings("unchecked")
    private void showEtsTableContent(String tableName) {
        try {
            new Thread(() -> {
                try {
                    OtpErlangObject contentObj = ErlyBerly.nodeAPI().getEtsTableInfo(tableName);

                    Platform.runLater(() -> {
                        if (contentObj != null) {
                            showEtsTableData(tableName, contentObj);
                        } else {
                            showNotification("无法获取表内容: " + tableName, NotificationType.ERROR);
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    Platform.runLater(() -> {
                        showNotification("获取表内容失败: " + e.getMessage(), NotificationType.ERROR);
                    });
                }
            }).start();
        } catch (Exception e) {
            e.printStackTrace();
            showNotification("操作失败: " + e.getMessage(), NotificationType.ERROR);
        }
    }

    /**
     * 使用表格显示 ETS 表数据
     */
    @SuppressWarnings("unchecked")
    private void showEtsTableData(String tableName, OtpErlangObject contentObj) {
        javafx.scene.control.TableView<EtsTableRow> tableView = new javafx.scene.control.TableView<>();
        tableView.setMaxHeight(Integer.MAX_VALUE);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        // 创建列
        javafx.scene.control.TableColumn<EtsTableRow, Number> seqCol = new javafx.scene.control.TableColumn<>("序号");
        seqCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("seq"));
        seqCol.setPrefWidth(60);
        seqCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        javafx.scene.control.TableColumn<EtsTableRow, String> keyCol = new javafx.scene.control.TableColumn<>("键");
        keyCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("key"));
        keyCol.setPrefWidth(300);
        keyCol.setSortable(true);
        keyCol.setComparator((a, b) -> a.compareTo(b));

        javafx.scene.control.TableColumn<EtsTableRow, String> valueCol = new javafx.scene.control.TableColumn<>("值");
        valueCol.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("value"));
        valueCol.setPrefWidth(500);

        tableView.getColumns().addAll(seqCol, keyCol, valueCol);

        // 解析数据并填充表格
        ObservableList<EtsTableRow> tableData = FXCollections.observableArrayList();
        parseEtsTableContent(contentObj, tableData);

        // 创建排序列表包装过滤列表，使列头排序生效
        FilteredList<EtsTableRow> filteredData = new FilteredList<>(tableData);
        SortedList<EtsTableRow> sortedData = new SortedList<>(filteredData);
        sortedData.comparatorProperty().bind(tableView.comparatorProperty());
        tableView.setItems(sortedData);

        // 添加右键菜单和复制功能（传递原始数据对象）
        addEtsTableContextMenu(tableView, tableName, filteredData, tableData, contentObj);

        // 添加过滤搜索框
        Parent filterBox = addEtsTableFilter(tableView, filteredData, tableData);

        // 创建布局，包含搜索框和表格
        VBox root = new VBox(5, filterBox, tableView);
        VBox.setVgrow(tableView, Priority.ALWAYS);

        ErlyBerly.showPane("ETS 表: " + tableName + " (" + filteredData.size() + " 条记录)", ErlyBerly.wrapInPane(root));
    }

    /**
     * 为 ETS 表表格添加右键菜单和复制功能
     */
    private void addEtsTableContextMenu(javafx.scene.control.TableView<EtsTableRow> tableView, 
                                        String tableName, 
                                        FilteredList<EtsTableRow> filteredData,
                                        ObservableList<EtsTableRow> tableData,
                                        OtpErlangObject rawContentObj) {
        // 创建右键菜单
        javafx.scene.control.ContextMenu contextMenu = new javafx.scene.control.ContextMenu();

        // 复制选中行菜单项
        javafx.scene.control.MenuItem copySelectedMenuItem = new javafx.scene.control.MenuItem("复制选中行");
        copySelectedMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+c"));
        copySelectedMenuItem.setOnAction(e -> copySelectedRows(tableView));
        contextMenu.getItems().add(copySelectedMenuItem);

        // 复制全部菜单项（使用原始数据，不是过滤后的）
        javafx.scene.control.MenuItem copyAllMenuItem = new javafx.scene.control.MenuItem("复制全部");
        copyAllMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+shift+c"));
        copyAllMenuItem.setOnAction(e -> copyAllRows(tableView, tableName, tableData));
        contextMenu.getItems().add(copyAllMenuItem);

        // 分隔线
        contextMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

        // 复制选中行原始数据菜单项
        javafx.scene.control.MenuItem copyRawSelectedMenuItem = new javafx.scene.control.MenuItem("复制选中行原始数据");
        copyRawSelectedMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+r"));
        copyRawSelectedMenuItem.setOnAction(e -> copyRawSelectedRows(tableView, rawContentObj));
        contextMenu.getItems().add(copyRawSelectedMenuItem);

        // 复制全部原始数据菜单项
        javafx.scene.control.MenuItem copyRawAllMenuItem = new javafx.scene.control.MenuItem("复制全部原始数据");
        copyRawAllMenuItem.setAccelerator(javafx.scene.input.KeyCombination.keyCombination("shortcut+shift+r"));
        copyRawAllMenuItem.setOnAction(e -> copyRawData(rawContentObj));
        contextMenu.getItems().add(copyRawAllMenuItem);

        // 分隔线
        contextMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());

        // 查看详细值菜单项
        javafx.scene.control.MenuItem viewDetailMenuItem = new javafx.scene.control.MenuItem("查看详细值");
        viewDetailMenuItem.setOnAction(e -> showEtsValueDetail(tableView));
        contextMenu.getItems().add(viewDetailMenuItem);

        // 导出到文件菜单项（使用原始数据，不是过滤后的）
        javafx.scene.control.MenuItem exportMenuItem = new javafx.scene.control.MenuItem("导出到文件");
        exportMenuItem.setOnAction(e -> exportToFile(tableView, tableName, tableData));
        contextMenu.getItems().add(exportMenuItem);

        // 设置菜单可用状态
        copySelectedMenuItem.disableProperty().bind(
            tableView.getSelectionModel().selectedItemProperty().isNull()
        );

        copyAllMenuItem.disableProperty().bind(
            javafx.beans.binding.Bindings.isEmpty(tableView.getItems())
        );

        copyRawSelectedMenuItem.disableProperty().bind(
            tableView.getSelectionModel().selectedItemProperty().isNull()
        );

        copyRawAllMenuItem.disableProperty().bind(
            javafx.beans.binding.Bindings.isEmpty(tableView.getItems())
        );

        viewDetailMenuItem.disableProperty().bind(
            tableView.getSelectionModel().selectedItemProperty().isNull()
        );

        // 设置右键菜单
        tableView.setContextMenu(contextMenu);

        // 添加双击查看详细值功能
        tableView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                showEtsValueDetail(tableView);
            }
        });
    }

    /**
     * 弹出窗口显示 ETS 表键或值的详细 Erlang 数据结构
     */
    private void showEtsValueDetail(javafx.scene.control.TableView<EtsTableRow> tableView) {
        EtsTableRow selectedRow = tableView.getSelectionModel().getSelectedItem();
        if (selectedRow == null) {
            return;
        }
        OtpErlangObject rawValue = selectedRow.getRawValue();
        if (rawValue == null) {
            return;
        }
        showTermDetailPopup(selectedRow.getKey(), rawValue);
    }

    /**
     * 使用 TermTreeView 弹窗显示 Erlang 数据结构的详细视图
     */
    private void showTermDetailPopup(String title, OtpErlangObject term) {
        TermTreeView termTreeView = new TermTreeView();
        termTreeView.populateFromTerm(term);
        termTreeView.setPrefSize(800, 600);

        // 创建一个带复制按钮的工具栏
        javafx.scene.control.Button copyButton = new javafx.scene.control.Button("复制原始数据");
        copyButton.setOnAction(e -> {
            copyToClipboard(term.toString());
            showCopyNotification(1, "已复制原始数据到剪贴板");
        });

        javafx.scene.layout.HBox toolbar = new javafx.scene.layout.HBox(5, copyButton);
        toolbar.setPadding(new javafx.geometry.Insets(5));

        VBox root = new VBox(5, toolbar, termTreeView);
        VBox.setVgrow(termTreeView, javafx.scene.layout.Priority.ALWAYS);

        ErlyBerly.showPane("详细值 - " + title, ErlyBerly.wrapInPane(root));
    }

    /**
     * 复制选中的行
     */
    private void copySelectedRows(javafx.scene.control.TableView<EtsTableRow> tableView) {
        ObservableList<EtsTableRow> selectedRows = tableView.getSelectionModel().getSelectedItems();
        if (selectedRows == null || selectedRows.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (EtsTableRow row : selectedRows) {
            sb.append(row.getKey()).append("\t").append(row.getValue()).append("\n");
        }

        copyToClipboard(sb.toString());
        showCopyNotification(selectedRows.size(), "已复制选中行到剪贴板");
    }

    /**
     * 复制所有行
     */
    private void copyAllRows(javafx.scene.control.TableView<EtsTableRow> tableView, String tableName, ObservableList<EtsTableRow> allRows) {
        if (allRows == null || allRows.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ETS 表: ").append(tableName).append("\n");
        sb.append("键\t值\n");
        sb.append("---\t---\n");

        for (EtsTableRow row : allRows) {
            sb.append(row.getKey()).append("\t").append(row.getValue()).append("\n");
        }

        copyToClipboard(sb.toString());
        showCopyNotification(allRows.size(), "已复制全部数据到剪贴板");
    }

    /**
     * 复制选中行的原始 Erlang 数据
     */
    @SuppressWarnings("unchecked")
    private void copyRawSelectedRows(javafx.scene.control.TableView<EtsTableRow> tableView, 
                                     OtpErlangObject rawContentObj) {
        ObservableList<EtsTableRow> selectedRows = tableView.getSelectionModel().getSelectedItems();
        if (selectedRows == null || selectedRows.isEmpty()) {
            showCopyNotification(0, "没有选中的行");
            return;
        }

        if (rawContentObj == null || !(rawContentObj instanceof com.ericsson.otp.erlang.OtpErlangList)) {
            showCopyNotification(0, "没有可复制的原始数据");
            return;
        }

        // 获取原始列表
        com.ericsson.otp.erlang.OtpErlangList originalList = (com.ericsson.otp.erlang.OtpErlangList) rawContentObj;
        
        // 构建选中行的索引集合，用于快速查找
        java.util.Set<Integer> selectedIndexSet = new java.util.HashSet<>();
        for (EtsTableRow row : selectedRows) {
            selectedIndexSet.add(row.getIndex());
        }

        // 从原始数据中提取选中行的原始数据
        java.util.List<OtpErlangObject> selectedItems = new java.util.ArrayList<>();
        int currentIndex = 0;
        for (OtpErlangObject item : originalList.elements()) {
            if (selectedIndexSet.contains(currentIndex)) {
                selectedItems.add(item);
            }
            currentIndex++;
        }

        // 构建结果字符串
        StringBuilder sb = new StringBuilder();
        if (selectedItems.size() == 1) {
            // 单个元素直接输出
            sb.append(selectedItems.get(0).toString());
        } else {
            // 多个元素用列表包裹
            sb.append("[");
            for (int i = 0; i < selectedItems.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
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
    private void copyRawData(OtpErlangObject rawContentObj) {
        if (rawContentObj == null) {
            showCopyNotification(0, "没有可复制的原始数据");
            return;
        }

        // 使用 OtpErlangObject 的 toString() 方法获取原始表示
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
     * 通知类型枚举
     */
    public enum NotificationType {
        SUCCESS,   // 成功提示（绿色）
        ERROR,     // 错误提示（红色）
        INFO       // 信息提示（蓝色）
    }

    /**
     * 显示界面提示（使用 Tooltip 方式）
     */
    private void showNotification(String message, NotificationType type) {
        // 在控制台输出日志
        System.out.println("[" + type + "] " + message);
        
        // 创建一个简单的浮动提示
        try {
            javafx.scene.control.Label notificationLabel = new javafx.scene.control.Label(message);
            notificationLabel.setStyle(getNotificationStyle(type));
            notificationLabel.setPadding(new javafx.geometry.Insets(10, 20, 10, 20));
            
            javafx.stage.Popup popup = new javafx.stage.Popup();
            popup.getContent().add(notificationLabel);
            
            // 获取当前活动窗口
            javafx.stage.Stage stage = getActiveStage();
            if (stage != null) {
                popup.show(stage);
                
                // 3秒后自动关闭
                javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(3));
                delay.setOnFinished(event -> popup.hide());
                delay.play();
            }
        } catch (Exception e) {
            // 如果创建提示失败，至少控制台有输出
            e.printStackTrace();
        }
    }

    /**
     * 获取通知样式
     */
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

    /**
     * 获取当前活动窗口
     */
    private javafx.stage.Stage getActiveStage() {
        try {
            // 尝试获取第一个显示的窗口
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
     * 显示复制成功提示
     */
    private void showCopyNotification(int count, String message) {
        if (count == 0) {
            // 错误提示
            showNotification(message, NotificationType.ERROR);
        } else {
            // 成功提示
            String fullMessage = message + " （" + count + " 行）";
            showNotification(fullMessage, NotificationType.SUCCESS);
        }
    }

    /**
     * 导出到文件
     */
    private void exportToFile(javafx.scene.control.TableView<EtsTableRow> tableView, String tableName, ObservableList<EtsTableRow> allRows) {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("导出 ETS 表数据");
        fileChooser.setInitialFileName(tableName + "_ets_data.txt");
        fileChooser.getExtensionFilters().addAll(
            new javafx.stage.FileChooser.ExtensionFilter("文本文件", "*.txt"),
            new javafx.stage.FileChooser.ExtensionFilter("CSV 文件", "*.csv"),
            new javafx.stage.FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        javafx.stage.Stage stage = (javafx.stage.Stage) tableView.getScene().getWindow();
        java.io.File file = fileChooser.showSaveDialog(stage);

        if (file != null) {
            try (java.io.PrintWriter writer = new java.io.PrintWriter(file, "UTF-8")) {
                // 写入标题
                writer.println("ETS 表: " + tableName);
                writer.println("总记录数: " + allRows.size());
                writer.println();
                writer.println("键\t值");
                writer.println("---\t---");

                // 写入数据
                for (EtsTableRow row : allRows) {
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
     * 解析 ETS 表内容为表格行
     */
    private void parseEtsTableContent(OtpErlangObject contentObj, ObservableList<EtsTableRow> tableData) {
        if (contentObj instanceof com.ericsson.otp.erlang.OtpErlangList) {
            com.ericsson.otp.erlang.OtpErlangList list = (com.ericsson.otp.erlang.OtpErlangList) contentObj;
            int index = 0;
            for (OtpErlangObject item : list.elements()) {
                if (item instanceof com.ericsson.otp.erlang.OtpErlangTuple) {
                    // 每个元组代表一行数据 {key, value, ...}
                    com.ericsson.otp.erlang.OtpErlangTuple tuple = (com.ericsson.otp.erlang.OtpErlangTuple) item;
                    if (tuple.arity() >= 2) {
                        OtpErlangObject rawKey = tuple.elementAt(0);
                        OtpErlangObject rawValue = tuple.elementAt(1);
                        String key = formatErlangTerm(rawKey);
                        String value = formatErlangTerm(rawValue);
                        tableData.add(new EtsTableRow(key, value, index++, rawKey, rawValue));
                    }
                } else if (item instanceof com.ericsson.otp.erlang.OtpErlangAtom) {
                    // 处理特殊标记，如 truncated
                    com.ericsson.otp.erlang.OtpErlangAtom atom = (com.ericsson.otp.erlang.OtpErlangAtom) item;
                    if (atom.atomValue().equals("truncated")) {
                        tableData.add(new EtsTableRow("...", "... 数据被截断，仅显示前100条记录 ...", index++, null, null));
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
        
        // 限制长度，避免显示过长
        if (result.length() > 200) {
            result = result.substring(0, 200) + "...";
        }
        
        return result;
    }

    /**
     * 为 ETS 表添加过滤搜索框
     */
    @SuppressWarnings("unchecked")
    private Parent addEtsTableFilter(javafx.scene.control.TableView<EtsTableRow> tableView,
                                     FilteredList<EtsTableRow> filteredData,
                                     ObservableList<EtsTableRow> tableData) {
        FxmlLoadable loader = new FxmlLoadable("/floatyfield/floaty-field.fxml");
        loader.load();

        FloatyFieldView ffView = (FloatyFieldView) loader.controller;
        ffView.promptTextProperty().set("过滤 ETS 表数据（按 PID、注册名或模块名）");

        // 绑定过滤逻辑
        ffView.textProperty().addListener((o, oldValue, searchText) -> {
            filterEtsTableData(filteredData, tableData, searchText);
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
     * 过滤 ETS 表数据
     */
    private void filterEtsTableData(FilteredList<EtsTableRow> filteredData,
                                    ObservableList<EtsTableRow> tableData,
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
     * ETS 表行数据类
     */
    public static class EtsTableRow {
        private final int seq;       // 序号（从1开始）
        private final String key;
        private final String value;
        private final int index;     // 原始索引（从0开始）
        private final OtpErlangObject rawKey;
        private final OtpErlangObject rawValue;

        public EtsTableRow(String key, String value, int index,
                           OtpErlangObject rawKey,
                           OtpErlangObject rawValue) {
            this.seq = index + 1;    // 序号从1开始显示
            this.key = key;
            this.value = value;
            this.index = index;
            this.rawKey = rawKey;
            this.rawValue = rawValue;
        }

        public int getSeq() {
            return seq;
        }

        public String getKey() {
            return key;
        }

        public String getValue() {
            return value;
        }

        public int getIndex() {
            return index;
        }

        public OtpErlangObject getRawKey() {
            return rawKey;
        }

        public OtpErlangObject getRawValue() {
            return rawValue;
        }
    }

    private void showPieChart(ObservableList<PieChart.Data> data) {
        String title = "Erlang 内存";

        PieChart pieChart;

        pieChart = new PieChart(data);
        pieChart.setTitle(title);
        ErlyBerly.showPane(title, ErlyBerly.wrapInPane(pieChart));
    }

    /**
     * these have to be run after initialisation is complete or an exception occurs
     */
    public void addAccelerators() {
        Platform.runLater(() -> {
            accelerators().put(TOGGLE_HIDE_PROCESSES_SHORTCUT, () -> { invertSelection(hideProcessesButton); });
        });
        Platform.runLater(() -> {
            accelerators().put(TOGGLE_HIDE_MODULES_SHORTCUT, () -> { invertSelection(hideFunctionsButton); });
        });
        Platform.runLater(() -> {
            accelerators().put(REFRESH_MODULES_SHORTCUT, () -> {
                if(refreshModulesAction != null)
                    refreshModulesAction.handle(null);
            });
        });
    }

    private FxmlLoadable processCountStat() {
        FxmlLoadable loader = new FxmlLoadable("/floatyfield/floaty-field.fxml");

        loader.load();

        Parent fxmlNode;

        fxmlNode = loader.fxmlNode;
        fxmlNode.getStyleClass().add("floaty-label");

        FloatyFieldView ffView;

        ffView = (FloatyFieldView) loader.controller;
        ffView.promptTextProperty().set("进程数");
        ffView.textProperty().set("0");
        ffView.disableProperty().set(true);

        ErlyBerly.nodeAPI().appProcsProperty().addListener((o, ov, nv) -> { upateProcsStat(ffView, nv); });

        return loader;
    }

    private void upateProcsStat(FloatyFieldView ffView, AppProcs nv) {
        String dateString = nv.getDateTime().format(DateTimeFormatter.ISO_TIME);

        ffView.textProperty().set(Integer.toString(nv.getProcCount()));
        ffView.promptTextProperty().set("进程数 @ " + dateString);
    }

    private ObservableMap<KeyCombination, Runnable> accelerators() {
        Scene scene = hideProcessesButton.getScene();

        assert scene != null : "button not added to scene";

        return scene.getAccelerators();
    }

    private void invertSelection(ToggleButton toggleButton) {
        toggleButton.setSelected(!toggleButton.isSelected());
    }

    public BooleanProperty hideProcsProperty() {
        return hideProcessesButton.selectedProperty();
    }

    public BooleanProperty hideFunctionsProperty() {
        return hideFunctionsButton.selectedProperty();
    }

    private void toggleHideProcs() {
        String buttonText = "";

        if(hideProcessesButton.isSelected())
            buttonText = "显示进程";
        else
            buttonText = "隐藏进程";

        hideProcessesButton.setText(buttonText);
    }

    private void toggleHideFuncs() {
        String buttonText = "";

        if(hideFunctionsButton.isSelected())
            buttonText = "显示模块";
        else
            buttonText = "隐藏模块";

        hideFunctionsButton.setText(buttonText);
    }

    public final void setOnRefreshModules(EventHandler<ActionEvent> e) {
        refreshModulesAction = e;

        refreshModulesButton.setOnAction(refreshModulesAction);
    }

    public void disconnect() throws IOException, OtpErlangException{
        ErlyBerly.nodeAPI().manuallyDisconnect();
        ErlyBerly.nodeAPI().disconnect();
        Stage s = new Stage();
        displayConnectionPopup(s);
    }

    // TODO: (improve) lazy copy paste
    // TODO: THIS was a ugly copy paste effort
    private void displayConnectionPopup(Stage primaryStage) {
        Stage connectStage;

        connectStage = new Stage();
        connectStage.initModality(Modality.WINDOW_MODAL);
        connectStage.setScene(new Scene(new FxmlLoadable("/erlyberly/connection.fxml").load()));
        connectStage.setAlwaysOnTop(true);

        // javafx vertical resizing is laughably ugly, lets just disallow it
        connectStage.setResizable(false);
        connectStage.setWidth(400);

        // if the user closes the window without connecting then close the app
        connectStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
            @Override
            public void handle(WindowEvent e) {
                if(!ErlyBerly.nodeAPI().connectedProperty().get()) {
                    Platform.exit();
                }

                Platform.runLater(() -> {
                    //primaryStage.setResizable(true);
                });
            }});

        connectStage.show();
    }

    private void displayPreferencesPane() {
        ErlyBerly.showPreferencesPane();
    }

    class ErlangMemoryThread extends Thread {
        private final ObservableList<Data> pieData;

        public ErlangMemoryThread(ObservableList<PieChart.Data> thePieData) {
            pieData = thePieData;

            setName("Erlang 内存线程");
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                final Map<Object, Object> erlangMemory = ErlyBerly.nodeAPI().erlangMemory();

                // remove stats which are combinations of other stats
                erlangMemory.remove(OtpUtil.atom("maximum"));
                erlangMemory.remove(OtpUtil.atom("total"));
                erlangMemory.remove(OtpUtil.atom("system"));
                erlangMemory.remove(OtpUtil.atom("processes_used"));
                erlangMemory.remove(OtpUtil.atom("atom_used"));

                Platform.runLater(() -> {
                    populatePieData(erlangMemory);
                });
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }

        private void populatePieData(final Map<Object, Object> erlangMemory) {
            for (Entry<Object, Object> entry : erlangMemory.entrySet()) {
                long kb = (long) (Double.parseDouble(entry.getValue().toString()) / 1024);
                String label = entry.getKey().toString() + " (" + kb + " KB)";
                pieData.add(new Data(label, kb));
            }
        }
    }
}
