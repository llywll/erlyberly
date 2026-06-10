/**
 * erlyberly, erlang trace debugger
 * Copyright (C) 2016 Andy Till
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package erlyberly;

import com.ericsson.otp.erlang.OtpErlangTuple;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumn.CellDataFeatures;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Callback;

public class CrashReportView extends TabPane {

    private final TermTreeView termTreeView = new TermTreeView();
    private final TermTreeView argsTreeView = new TermTreeView();
    private final StackTraceView stackTraceView = new StackTraceView();
    private final TableView<Object[]> crashInfoTable = new TableView<>();

    public CrashReportView() {
        VBox.setVgrow(termTreeView, Priority.ALWAYS);

        Label label;
        label = new Label("堆栈跟踪");
        label.setStyle("-fx-padding: 5; -fx-font-size: 14;");

        Tab stackTraceTab, argsTermsTab, termsTab;
        termsTab = new Tab("崩溃报告术语");
        termsTab.setContent(termTreeView);
        argsTermsTab = new Tab("调用参数");
        argsTermsTab.setContent(argsTreeView);
        stackTraceTab = new Tab("堆栈跟踪");
        stackTraceTab.setContent(new VBox(crashInfoTable, label, stackTraceView));

        getTabs().addAll(stackTraceTab, argsTermsTab, termsTab);
    }

    public void setCrashReport(CrashReport crashReport) {
        stackTraceView.populateFromCrashReport(crashReport);
        termTreeView.populateFromTerm(crashReport.getProps());

        Object[][] crashProps = {
                {"进程ID", crashReport.getPid() },
                {"注册名称", crashReport.getRegisteredName() },
                {"错误", ErlyBerly.getTermFormatter().exceptionToString(crashReport.getErrorClass(), crashReport.getErrorReason()) },
                {"初始调用", ErlyBerly.getTermFormatter().modFuncArgsToString((OtpErlangTuple) crashReport.getProcessInitialCall()) }};

        TableColumn<Object[], Object> keyColumn = new TableColumn<>("键");
        TableColumn<Object[], Object> valueColumn = new TableColumn<>("值");

        keyColumn.setCellValueFactory(new Callback<CellDataFeatures<Object[], Object>, ObservableValue<Object>>() {
            @Override
            public ObservableValue<Object> call(CellDataFeatures<Object[], Object> p) {
                return new SimpleObjectProperty<>((p.getValue()[0]));
            }
        });

        valueColumn.setCellValueFactory(new Callback<CellDataFeatures<Object[], Object>, ObservableValue<Object>>() {
            @Override
            public ObservableValue<Object> call(CellDataFeatures<Object[], Object> p) {
                return new SimpleObjectProperty<>((p.getValue()[1]));
            }
        });

        crashInfoTable.getColumns().add(keyColumn);
        crashInfoTable.getColumns().add(valueColumn);

        crashInfoTable.getItems().addAll(crashProps);

        crashReport.getCallArgs().ifPresent((callArgs) -> {
            argsTreeView.populateFromListContents(callArgs);
        });
    }
}
