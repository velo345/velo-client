package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.data.ServerProfiles;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Before joining a server with known mod rules: which installed mods it doesn't allow (and which it recommends).
 * Only a heads-up - "Play anyway" always works, and "Don't warn me for this server" hides it for good.
 */
public final class ServerRulesDialog {

	private ServerRulesDialog() {
	}

	/** True = start. "Turn them off & play" renames the not-allowed mods to .disabled first. */
	public static boolean confirm(Stage owner, ServerProfiles.Check check) {
		ServerProfiles.Profile p = check.profile();
		Dialog<ButtonType> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle(p.name() + " - mod rules");
		DialogStyling.apply(dialog);
		ButtonType turnOff = new ButtonType("Turn them off & play", ButtonBar.ButtonData.OK_DONE);
		ButtonType anyway = new ButtonType("Play anyway", ButtonBar.ButtonData.OTHER);
		dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, anyway);
		if (!check.forbidden().isEmpty()) {
			dialog.getDialogPane().getButtonTypes().add(turnOff);
		}
		Label title = new Label(check.forbidden().isEmpty() ? p.name() + " recommends some mods" : p.name() + " doesn't allow some of your mods");
		title.getStyleClass().add("error-dialog-title");
		VBox body = new VBox(8, title);
		if (!check.forbidden().isEmpty()) {
			Label head = new Label("Not allowed there - you could get banned:");
			head.getStyleClass().add("rules-head");
			body.getChildren().add(head);
			for (ServerProfiles.Hit hit : check.forbidden()) {
				Label l = new Label("•  " + hit.modName() + (hit.rule().equalsIgnoreCase(hit.modName()) ? "" : "  (" + hit.rule() + ")"));
				l.getStyleClass().add("rules-bad");
				body.getChildren().add(l);
			}
		}
		if (!check.missing().isEmpty()) {
			Label head = new Label("Recommended there:");
			head.getStyleClass().add("rules-head");
			body.getChildren().add(head);
			for (ServerProfiles.Rule rule : check.missing()) {
				Label l = new Label("•  " + rule.label());
				l.getStyleClass().add("rules-good");
				body.getChildren().add(l);
			}
		}
		if (p.note() != null && !p.note().isBlank()) {
			Label note = new Label(p.note());
			note.getStyleClass().add("t-muted");
			note.setWrapText(true);
			body.getChildren().add(note);
		}
		Label disclaimer = new Label("These rules are collected by Velo and can be wrong - you can always play anyway.");
		disclaimer.getStyleClass().add("t-muted");
		disclaimer.setWrapText(true);
		CheckBox hide = new CheckBox("Don't warn me for " + p.name() + " again");
		body.getChildren().addAll(disclaimer, hide);
		body.setPadding(new Insets(6));
		body.setPrefWidth(460);
		dialog.getDialogPane().setContent(body);
		ButtonType answer = dialog.showAndWait().orElse(ButtonType.CANCEL);
		if (hide.isSelected()) {
			ServerProfiles.hide(p.id());
		}
		if (answer == turnOff) {
			for (ServerProfiles.Hit hit : check.forbidden()) {
				try {
					Files.move(hit.file(), hit.file().resolveSibling(hit.file().getFileName() + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
				} catch (Exception ignored) {
					// Stays on; the game starts anyway.
				}
			}
			return true;
		}
		return answer == anyway;
	}
}
