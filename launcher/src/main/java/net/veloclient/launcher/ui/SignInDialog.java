package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import net.veloclient.launcher.auth.AuthException;
import net.veloclient.launcher.auth.MicrosoftAuth;
import net.veloclient.launcher.auth.MinecraftSession;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Modal "device code" sign-in dialog: shows the code, opens the verification page, and polls in the background. */
public final class SignInDialog {

	private SignInDialog() {
	}

	public static void show(Stage owner, String clientId, Consumer<MinecraftSession> onSuccess, Consumer<String> onError) {
		Stage stage = new Stage(StageStyle.TRANSPARENT);
		stage.initOwner(owner);
		stage.initModality(Modality.WINDOW_MODAL);

		Label heading = new Label("Sign in with Microsoft");
		heading.setTextFill(Color.WHITE);
		heading.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

		Label status = new Label("Requesting a sign-in code...");
		status.setTextFill(Color.web("#CCCCCC"));

		Label codeLabel = new Label();
		codeLabel.getStyleClass().add("dialog-code");
		codeLabel.setTextFill(Color.WHITE);

		ProgressIndicator spinner = new ProgressIndicator();
		spinner.setPrefSize(24, 24);

		Button openBrowser = new Button("Open verification page");
		openBrowser.setVisible(false);
		Button copyCode = new Button("Copy code");
		copyCode.setVisible(false);
		Button cancel = new Button("Cancel");

		HBox actions = new HBox(10, openBrowser, copyCode, cancel);
		actions.setAlignment(Pos.CENTER);

		VBox box = new VBox(14, heading, codeLabel, status, spinner, actions);
		box.setAlignment(Pos.CENTER);
		box.setPadding(new Insets(28));
		box.setStyle("-fx-background-color: #14141ecc; -fx-background-radius: 16; -fx-border-radius: 16; -fx-border-color: #ffffff22; -fx-border-width: 1;");
		box.setPrefWidth(380);

		Scene scene = new Scene(box);
		scene.setFill(Color.TRANSPARENT);
		stage.setScene(scene);
		// This is a raw Stage (needed for StageStyle.TRANSPARENT), not a
		// Dialog, so it doesn't get DialogStyling's owner-centering - without
		// this it was landing at a fixed position on whatever the OS
		// considers the "primary" screen rather than over the actual main
		// window on a multi-monitor setup.
		stage.setOnShown(e -> {
			stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
			stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
		});

		var executor = Executors.newVirtualThreadPerTaskExecutor();
		var future = executor.submit(() -> {
			try {
				MicrosoftAuth auth = new MicrosoftAuth(clientId);
				MinecraftSession session = auth.signInWithDeviceCode(deviceCode -> Platform.runLater(() -> {
					codeLabel.setText(deviceCode.userCode());
					status.setText("Enter this code at " + deviceCode.verificationUri());
					spinner.setVisible(true);
					openBrowser.setVisible(true);
					copyCode.setVisible(true);
					openBrowser.setOnAction(e -> openInBrowser(deviceCode.verificationUri(), status));
					copyCode.setOnAction(e -> copyToClipboard(deviceCode.userCode()));
					// Try to open it automatically so the user doesn't have to click through.
					openInBrowser(deviceCode.verificationUri(), status);
				}));
				Platform.runLater(() -> {
					stage.close();
					onSuccess.accept(session);
				});
			} catch (AuthException e) {
				Platform.runLater(() -> {
					stage.close();
					onError.accept(e.getMessage());
				});
			} catch (Exception e) {
				Platform.runLater(() -> {
					stage.close();
					onError.accept("Unexpected error: " + e.getMessage());
				});
			}
		});

		cancel.setOnAction(e -> {
			future.cancel(true);
			stage.close();
		});
		stage.setOnCloseRequest(e -> future.cancel(true));

		stage.show();
	}

	/**
	 * Tries several ways to open a URL, since AWT's {@code Desktop} is often
	 * unsupported/misconfigured on Linux even when a real browser is
	 * installed. Falls back to shelling out to the OS's own "open a URL"
	 * command, and only silently gives up after all of them fail (the code
	 * is still shown/copyable either way).
	 */
	private static void openInBrowser(String url, Label status) {
		if (tryAwtDesktop(url) || tryOsCommand(url)) {
			return;
		}
		status.setText("Couldn't open a browser automatically - copy the code and open " + url + " yourself.");
	}

	private static boolean tryAwtDesktop(String url) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(url));
				return true;
			}
		} catch (Exception ignored) {
			// Fall through to the OS-command fallback.
		}
		return false;
	}

	private static boolean tryOsCommand(String url) {
		String os = System.getProperty("os.name", "").toLowerCase();
		String[] command;
		if (os.contains("win")) {
			command = new String[] {"rundll32", "url.dll,FileProtocolHandler", url};
		} else if (os.contains("mac")) {
			command = new String[] {"open", url};
		} else {
			command = new String[] {"xdg-open", url};
		}
		try {
			new ProcessBuilder(command).start();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private static void copyToClipboard(String text) {
		ClipboardContent content = new ClipboardContent();
		content.putString(text);
		Clipboard.getSystemClipboard().setContent(content);
	}
}
