package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.data.OgBadgeSetting;

/**
 * Shown once to a player from Velo's first two weeks: thank you, here's the OG badge - use it, or keep the normal one
 * (it's opt-in). Either way it can be switched later on the Account page or in the game (Velo Network settings).
 */
public final class OgBadgeDialog {

	private static boolean open;

	private OgBadgeDialog() {
	}

	public static Image badgeImage(double width) {
		return new Image(OgBadgeDialog.class.getResourceAsStream("/net/veloclient/launcher/images/badge_og.png"), width, width / 2, true, true);
	}

	public static void show(Stage owner) {
		if (open || OgBadgeSetting.thanked()) {
			return;
		}
		open = true;
		OgBadgeSetting.setThanked();
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("You're OG");
		DialogStyling.apply(dialog);

		ImageView badge = new ImageView(badgeImage(176));
		StackPane art = new StackPane(badge);
		art.setPadding(new Insets(26, 0, 22, 0));
		art.setStyle("-fx-background-color: radial-gradient(center 50% 50%, radius 70%, rgba(247,192,74,0.22), rgba(247,192,74,0.0));"
				+ "-fx-background-radius: 14;");

		Label title = new Label("Thank you for being here from the start");
		title.getStyleClass().add("error-dialog-title");
		title.setWrapText(true);
		Label text = new Label("You got Velo in its first two weeks - that makes you OG. As a thank-you, your account can show "
				+ "the gold OG badge next to your name instead of the normal one: in chat, the tab list and above your head, "
				+ "for every Velo player.");
		text.getStyleClass().add("error-dialog-message");
		text.setWrapText(true);
		Label hint = new Label("It's your choice - switch any time on the Account page or in the game (Velo Network settings).");
		hint.getStyleClass().add("t-muted");
		hint.setWrapText(true);

		Button use = new Button("Use the OG badge");
		use.getStyleClass().add("buy-button");
		use.setOnAction(e -> {
			OgBadgeSetting.setUse(true);
			dialog.setResult(null);
			dialog.close();
		});
		Button keep = new Button("Keep the normal badge");
		keep.getStyleClass().add("ghost-button");
		keep.setOnAction(e -> {
			OgBadgeSetting.setUse(false);
			dialog.setResult(null);
			dialog.close();
		});
		HBox buttons = new HBox(10, use, keep);
		buttons.setAlignment(Pos.CENTER_LEFT);

		VBox root = new VBox(14, art, title, text, buttons, hint);
		root.setPadding(new Insets(6));
		root.setPrefWidth(480);
		dialog.getDialogPane().setContent(root);
		// Closing with Esc keeps the default (normal badge); the buttons above are the real choice.
		dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
		javafx.scene.Node close = dialog.getDialogPane().lookupButton(javafx.scene.control.ButtonType.CLOSE);
		close.setVisible(false);
		close.setManaged(false);
		dialog.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_HIDDEN, e -> open = false);
		dialog.show();
	}
}
