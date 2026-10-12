package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import net.veloclient.launcher.launch.SystemHealth;

import java.util.List;

/** Shows {@link SystemHealth} findings: all of them from Settings, only warnings before a launch. */
public final class SystemCheckDialog {

	private SystemCheckDialog() {
	}

	/** Before a launch: returns true to play anyway. */
	public static boolean confirmLaunch(Stage owner, List<SystemHealth.Finding> warnings) {
		Dialog<ButtonType> dialog = build(owner, "Before you play", "The system check found something that may freeze or crash the game:", warnings);
		ButtonType play = new ButtonType("Play anyway", ButtonBar.ButtonData.OK_DONE);
		ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
		dialog.getDialogPane().getButtonTypes().addAll(play, cancel);
		javafx.scene.control.CheckBox silence = new javafx.scene.control.CheckBox("Don't warn me about this again");
		((VBox) dialog.getDialogPane().getContent()).getChildren().add(silence);
		DialogStyling.apply(dialog);
		boolean go = dialog.showAndWait().filter(b -> b == play).isPresent();
		if (go && silence.isSelected()) {
			SystemHealth.silence(warnings);
		}
		return go;
	}

	public static void showAll(Stage owner, List<SystemHealth.Finding> findings) {
		long problems = findings.stream().filter(f -> f.level() == SystemHealth.Level.WARN).count();
		Dialog<ButtonType> dialog = build(owner, "System check",
				problems == 0 ? "Everything looks good for playing." : problems + (problems == 1 ? " thing needs" : " things need") + " attention:", findings);
		dialog.getDialogPane().getButtonTypes().add(new ButtonType("Close", ButtonBar.ButtonData.OK_DONE));
		DialogStyling.apply(dialog);
		dialog.showAndWait();
	}

	private static Dialog<ButtonType> build(Stage owner, String title, String header, List<SystemHealth.Finding> findings) {
		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle(title);
		dialog.setHeaderText(header);
		VBox list = new VBox(10);
		list.setPadding(new Insets(4, 0, 0, 0));
		list.setPrefWidth(480);
		for (SystemHealth.Finding finding : findings) {
			Color color = switch (finding.level()) {
				case OK -> Color.web("#6FE39A");
				case INFO -> Color.web("#7FB2FF");
				case WARN -> Color.web("#FFC53D");
			};
			Circle dot = new Circle(5, color);
			Label name = new Label(finding.title());
			name.getStyleClass().add("settings-row-label");
			Label detail = new Label(finding.detail());
			detail.getStyleClass().add("settings-row-hint");
			detail.setWrapText(true);
			VBox text = new VBox(2, name, detail);
			text.setMinWidth(0);
			HBox.setHgrow(text, Priority.ALWAYS);
			HBox row = new HBox(10, dot, text);
			row.setAlignment(Pos.TOP_LEFT);
			HBox.setMargin(dot, new Insets(5, 0, 0, 0));
			list.getChildren().add(row);
		}
		list.setMinHeight(Region.USE_PREF_SIZE);
		dialog.getDialogPane().setContent(list);
		return dialog;
	}
}
