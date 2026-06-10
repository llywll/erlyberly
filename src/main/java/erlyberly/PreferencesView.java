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

import java.net.URL;
import java.util.ResourceBundle;

import erlyberly.format.ErlangFormatter;
import erlyberly.format.ElixirFormatter;
import erlyberly.format.LFEFormatter;
import javafx.beans.Observable;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;

public class PreferencesView implements Initializable {

    public TextField localNodeNameField;
    @FXML
    private TextField nodeNameField;
    @FXML
    private TextField cookieField;
    @FXML
    private CheckBox autoConnectField;
    @FXML
    private CheckBox showSourceInSystemEditorBox;
    @FXML
    private CheckBox hideProcesses;
    @FXML
    private CheckBox hideModules;
    @FXML
    private RadioButton erlangTermsButton;
    @FXML
    private RadioButton elixirTermsButton;
    @FXML
    private RadioButton lfeTermsButton;

    @Override
    public void initialize(URL url, ResourceBundle r) {
        selectFormattingButton();

        final ToggleGroup group;
        group = new ToggleGroup();
        group.selectedToggleProperty().addListener((Observable o) -> {
            storeFormattingPreferenceChange();
        });
        erlangTermsButton.setToggleGroup(group);
        elixirTermsButton.setToggleGroup(group);
        lfeTermsButton.setToggleGroup(group);

        PrefBind.bind("localNodeNameField", localNodeNameField.textProperty());
        PrefBind.bind("targetNodeName", nodeNameField.textProperty());
        PrefBind.bind("cookieName", cookieField.textProperty());
        PrefBind.bindBoolean("autoConnect", autoConnectField.selectedProperty());
        PrefBind.bindBoolean("hideProcesses", hideProcesses.selectedProperty());
        PrefBind.bindBoolean("hideModules", hideModules.selectedProperty());
        PrefBind.bindBoolean("showSourceInSystemEditor", showSourceInSystemEditorBox.selectedProperty());
    }

    private void storeFormattingPreferenceChange() {
        if(erlangTermsButton.isSelected()) {
            PrefBind.set("termFormatting", "erlang");
            ErlyBerly.setTermFormatter(new ErlangFormatter());
        }
        else if(elixirTermsButton.isSelected()) {
            PrefBind.set("termFormatting", "elixir");
            ErlyBerly.setTermFormatter(new ElixirFormatter());
        }
        else if(lfeTermsButton.isSelected()) {
            PrefBind.set("termFormatting", "lfe");
            ErlyBerly.setTermFormatter(new LFEFormatter());
        }
        selectFormattingButton();
    }

    private void selectFormattingButton() {
        String formattingPref = PrefBind.getOrDefault("termFormatting", "erlang").toString();
        if("erlang".equals(formattingPref)) {
            erlangTermsButton.setSelected(true);
        }
        else if("elixir".equals(formattingPref)) {
            elixirTermsButton.setSelected(true);
        }
        else if("lfe".equals(formattingPref)) {
            lfeTermsButton.setSelected(true);
        }
        else
            throw new RuntimeException("Invalid configuration for property 'termFormatting' it must be 'erlang' or 'lfe' but was " + formattingPref);
    }
}
