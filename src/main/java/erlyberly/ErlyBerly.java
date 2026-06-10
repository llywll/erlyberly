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

import erlyberly.format.ErlangFormatter;
import erlyberly.format.ElixirFormatter;
import erlyberly.format.LFEFormatter;
import erlyberly.format.TermFormatter;
import erlyberly.node.NodeAPI;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.control.SplitPane.Divider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

public class ErlyBerly extends Application {

    private static final NodeAPI NODE_API = new NodeAPI();

    /**
     * 首选项选项卡，当用户按下顶部栏中的"首选项"按钮时添加。静态是因为只能有一个。
     */
    private static Tab prefstab;

    private SplitPane splitPane;

    private Region entopPane;

    private double entopDivPosition;

    private static TabPane tabPane;

    private static TermFormatter termFormatter;

    public static void main(String[] args) throws Exception {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            PrefBind.setup();
        } catch (IOException e) {
            e.printStackTrace();
        }
        termFormatter = formatterFromConfig();

        FxmlLoadable topBarFxml;
        topBarFxml = new FxmlLoadable("/erlyberly/topbar.fxml");
        topBarFxml.load();

        FxmlLoadable dbgFxml;
        dbgFxml = new FxmlLoadable("/erlyberly/dbg.fxml");
        dbgFxml.load();
        DbgView dbgView = (DbgView)dbgFxml.controller;
        tabPane = dbgView.getTabPane();

        splitPane = new SplitPane();
        entopPane = (Region) loadEntopPane();
        splitPane.getItems().add(entopPane);
        splitPane.getItems().add(dbgFxml.load());

        setupProcPaneHiding(topBarFxml, dbgFxml);

        VBox rootView;
        rootView = new VBox(topBarFxml.fxmlNode, splitPane);
        rootView.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        Scene scene;
        scene = new Scene(rootView);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, new EventHandler<KeyEvent>() {
            @Override
            public void handle(KeyEvent t) {
                if (KeyCode.W.equals(t.getCode()) && t.isShortcutDown()) {
                    Tab selectedItem = tabPane.getSelectionModel().getSelectedItem();
                    if(selectedItem != null && selectedItem.isClosable()) {
                        tabPane.getTabs().remove(selectedItem);
                        t.consume();
                    }
                }
            }
        });

        // 窗口大小需要在添加场景之前设置，否则会被计为窗口调整大小，这也会调整拆分窗格的大小
        final double windowWidth = PrefBind.getOrDefaultDouble("windowWidth", 800D);
        primaryStage.setWidth(windowWidth);
        primaryStage.widthProperty().addListener((o, ov, nv) -> {
            PrefBind.set("windowWidth", nv);
        });
        final double windowHeight = PrefBind.getOrDefaultDouble("windowHeight", 600D);
        primaryStage.setHeight(windowHeight);
        primaryStage.heightProperty().addListener((o, ov, nv) -> {
            PrefBind.set("windowHeight", nv);
        });


        applyCssToWIndow(scene);
        primaryStage.setScene(scene);
        primaryStage.titleProperty().bind(NODE_API.summaryProperty());
        primaryStage.setResizable(true);
        primaryStage.show();

        displayConnectionPopup(primaryStage);

        FilterFocusManager.init(scene);

        primaryStage.setOnCloseRequest(new EventHandler<WindowEvent>() {
            @Override
            public void handle(WindowEvent t) {
                try {
                    nodeAPI().manuallyDisconnect();
                } catch(Exception e) {
                    System.out.println(e);
                }
                Platform.exit();
                System.exit(0);
            }
        });
        // 稍后运行这个，因为它需要控件的场景已设置，而这可能尚未发生。
        Platform.runLater(() -> {
            sizeSplitPanes(splitPane);
            dbgView.sizeSplitPanes();
        });
    }

    private static TermFormatter formatterFromConfig() {
        String formattingPref = PrefBind.getOrDefault("termFormatting", "erlang").toString();
        if("erlang".equals(formattingPref))
            return new ErlangFormatter();
        else if("elixir".equals(formattingPref))
            return new ElixirFormatter();
        else if("lfe".equals(formattingPref))
            return new LFEFormatter();
        else
            throw new RuntimeException("Invalid configuration for property 'termFormatting' it must be 'erlang' or 'lfe' but was " + formattingPref);
    }

    public static void applyCssToWIndow(Scene scene) {
        scene.getStylesheets().add(ErlyBerly.class.getResource("/floatyfield/floaty-field.css").toExternalForm());
        scene.getStylesheets().add(ErlyBerly.class.getResource("/erlyberly/erlyberly.css").toString());
    }

    private void setupProcPaneHiding(FxmlLoadable topBarFxml, FxmlLoadable dbgFxml) {

        TopBarView topView;
        DbgView dbgView;

        topView = (TopBarView) topBarFxml.controller;
        dbgView = (DbgView) dbgFxml.controller;

        topView.hideProcsProperty().addListener((ObservableValue<? extends Boolean> o, Boolean ob, Boolean nb) -> {
            if(!nb) {
                showProcsPane();
            }
            else {
                hideProcsPane();
            }
        });

        topView.hideFunctionsProperty().addListener((ObservableValue<? extends Boolean> o, Boolean ob, Boolean nb) -> {
            dbgView.setFunctionsVisibility(nb);
        });

        boolean hideProcs = PrefBind.getOrDefaultBoolean("hideProcesses", false);
        if(hideProcs){
            hideProcsPane();
        }
        boolean hideMods = PrefBind.getOrDefaultBoolean("hideModules", false);
        if(hideMods){
            dbgView.setFunctionsVisibility(true);
        }

        topView.setOnRefreshModules(dbgView::onRefreshModules);

        Platform.runLater(() -> { topView.addAccelerators(); });
    }

    private void showProcsPane(){
        splitPane.getItems().add(0, entopPane);

        Divider div = splitPane.getDividers().get(0);
        div.setPosition(entopDivPosition);
    }

    private void hideProcsPane(){
        Divider div = splitPane.getDividers().get(0);

        entopDivPosition = div.getPosition();

        div.setPosition(0d);
        splitPane.getItems().remove(0);
    }

    private Parent loadEntopPane() {
        Parent entopPane = new FxmlLoadable("/erlyberly/entop.fxml").load();
        SplitPane.setResizableWithParent(entopPane, Boolean.FALSE);

        return entopPane;
    }

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
                if(!NODE_API.connectedProperty().get()) {
                    Platform.exit();
                }

                Platform.runLater(() -> { primaryStage.setResizable(true); });
            }});

        connectStage.show();
    }

    public static NodeAPI nodeAPI() {
        return NODE_API;
    }

    /**
     * 在选项卡面板中显示一个新的控件。该选项卡可关闭。
     */
    public static void showPane(String title, Pane parentControl) {
        assert Platform.isFxApplicationThread();
        Tab newTab;
        newTab = new Tab(title);
        newTab.setContent(parentControl);
        addAndSelectTab(newTab);
    }

    private static void addAndSelectTab(Tab newTab) {
        tabPane.getTabs().add(newTab);
        tabPane.getSelectionModel().select(newTab);
    }


    public static void showPreferencesPane() {
        if(prefstab == null) {
            FxmlLoadable fxmlLoadable = new FxmlLoadable("/erlyberly/preferences.fxml");
            Parent parent = fxmlLoadable.load();
            Pane tabPane = ErlyBerly.wrapInPane(parent);
            prefstab = new Tab("首选项");
            prefstab.setContent(tabPane);
        }

        if(tabPane.getTabs().contains(prefstab)) {
            tabPane.getSelectionModel().select(prefstab);
        }
        else {
            addAndSelectTab(prefstab);
        }
    }

    /**
     * 我只知道 Pane。
     */
    public static Pane wrapInPane(Node node) {
        if(node instanceof Pane)
            return (Pane) node;
        VBox.setVgrow(node, Priority.ALWAYS);
        VBox vBox = new VBox(node);
        return vBox;
    }

    public static TermFormatter getTermFormatter() {
        return termFormatter;
    }

    public static void setTermFormatter(TermFormatter aTermFormatter) {
        termFormatter = aTermFormatter;
    }

    public void sizeSplitPanes(SplitPane splitpane) {
        assert splitpane.getScene() != null;
        assert splitpane.getScene().getWidth() > 0.0d;
        try {
            double configuredProcessesWidth = configuredProcessesWidth();
            double sceneWidth = splitpane.getScene().getWidth();
            double percent = (configuredProcessesWidth / sceneWidth);
            // the split pane divider position can only be set as a percentage of the split pane
            splitpane.setDividerPosition(0, percent);
            splitpane.setDividerPosition(1, 1D - percent);
        }
        catch (NumberFormatException e) {
            e.printStackTrace();
        }
        // 每当窗格的宽度改变时，将其写入配置
        // 这是缓冲的，所以快速写入不会导致快速写入磁盘
        entopPane.widthProperty().addListener((o, ov, nv) -> {
            PrefBind.set("processesWidth", nv);
        });
    }

    private double configuredProcessesWidth() {
        double w = PrefBind.getOrDefaultDouble("processesWidth", 300D);
        return w;
    }
}
