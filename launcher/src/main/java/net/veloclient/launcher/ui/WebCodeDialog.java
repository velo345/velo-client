package net.veloclient.launcher.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * velo-client.com asked to sign in ("Send code to my launcher"): the 6-digit code, big, already copied, with a
 * countdown - type it on the website. Shown whether the launcher was already open or opened afterwards.
 */
public final class WebCodeDialog {

	private static Dialog<Void> open;

	private WebCodeDialog() {
	}

	public static void show(Stage owner, String code) {
		show(owner, code, "Sign in on velo-client.com", "Your website sign-in code",
				"Type it on velo-client.com to sign in to the Store. It works once, for 5 minutes.\n"
						+ "Didn't ask for it? Just close this - nobody can use it without seeing it here.",
				"Expired - ask for a new code on the website");
	}

	/** The staff panel's first sign-in on a new device (staff.velo-client.com). */
	public static void showStaff(Stage owner, String code) {
		show(owner, code, "Velo Staff panel", "Staff panel sign-in code",
				"Type it on staff.velo-client.com, then choose a passkey, or a password or pattern with an authenticator app. "
						+ "It works once, for 5 minutes.\nDidn't ask for it? Close this and tell a manager - nobody can use it without seeing it here.",
				"Expired - ask for a new code on staff.velo-client.com");
	}

	private static void show(Stage owner, String code, String windowTitle, String heading, String text, String expired) {
		if (open != null) {
			open.close();
		}
		if (owner != null) {
			owner.setIconified(false);
			owner.toFront();
			owner.requestFocus();
		}
		Dialog<Void> dialog = new Dialog<>();
		open = dialog;
		dialog.initOwner(owner);
		dialog.setTitle(windowTitle);
		DialogStyling.apply(dialog);
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

		Label title = new Label(heading);
		title.getStyleClass().add("error-dialog-title");
		Label pretty = new Label(code.substring(0, 3) + " " + code.substring(3));
		pretty.setStyle("-fx-font-family: 'JetBrains Mono'; -fx-font-size: 44px; -fx-font-weight: bold; -fx-text-fill: -velo-text; -fx-letter-spacing: 6px;");
		Label hint = new Label(text);
		hint.getStyleClass().add("error-dialog-message");
		hint.setWrapText(true);
		Label left = new Label();
		left.getStyleClass().add("error-dialog-message");
		Button copy = new Button("Copy code");
		copy.getStyleClass().add("primary-button");
		copy.setOnAction(e -> {
			copyToClipboard(code);
			copy.setText("Copied");
		});
		copyToClipboard(code);

		long until = System.currentTimeMillis() + 5 * 60_000L;
		Timeline countdown = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
			long ms = until - System.currentTimeMillis();
			left.setText(ms <= 0 ? expired : String.format("Copied to your clipboard · expires in %d:%02d", ms / 60_000, ms / 1000 % 60));
		}));
		countdown.setCycleCount(Timeline.INDEFINITE);
		countdown.play();
		// An extra handler: DialogStyling's own setOnHidden removes the dimmed backdrop and must not be replaced.
		dialog.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_HIDDEN, e -> {
			countdown.stop();
			if (open == dialog) {
				open = null;
			}
		});

		VBox root = new VBox(12, title, pretty, left, copy, hint);
		root.setAlignment(Pos.CENTER_LEFT);
		root.setPadding(new Insets(6));
		root.setPrefWidth(460);
		dialog.getDialogPane().setContent(root);
		dialog.show();
	}

	private static void copyToClipboard(String code) {
		ClipboardContent content = new ClipboardContent();
		content.putString(code);
		Clipboard.getSystemClipboard().setContent(content);
	}
}
