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

/**
 * Modal "device code" sign-in dialog, built as a guided 3-step flow: the code is shown big and
 * copied automatically FIRST, and the Microsoft page only opens when the user clicks "Open login
 * page" - opening it straight away (the old behaviour) meant many people never saw the code and
 * went looking for a 2FA code in their email/phone instead.
 */
public final class SignInDialog {

	private static final String LOGIN_URL_FALLBACK = "https://www.microsoft.com/link";

	private SignInDialog() {
	}

	/** Everything the flow updates once the real code arrives (or a demo code is filled in). */
	private static final class View {
		final Stage stage;
		final Label code = new Label("· · · · · · · ·");
		final Label copied = new Label();
		final Label status = new Label("Requesting a sign-in code...");
		final Button copy = new Button("Copy");
		final Button open = new Button("Open login page");
		final Button cancel = new Button("Cancel");
		final ProgressIndicator spinner = new ProgressIndicator();
		final Label[] steps = new Label[3];
		String url = LOGIN_URL_FALLBACK;

		View(Stage stage) {
			this.stage = stage;
		}

		void setStep(int active) {
			for (int i = 0; i < steps.length; i++) {
				steps[i].getStyleClass().removeAll("signin-step-active", "signin-step-done");
				if (i < active) {
					steps[i].getStyleClass().add("signin-step-done");
				} else if (i == active) {
					steps[i].getStyleClass().add("signin-step-active");
				}
			}
		}

		void fill(String userCode, String verificationUri) {
			url = verificationUri == null || verificationUri.isBlank() ? LOGIN_URL_FALLBACK : verificationUri;
			code.setText(userCode);
			copy.setDisable(false);
			open.setDisable(false);
			copyToClipboard(userCode);
			copied.setText("Copied to your clipboard");
			status.setText("Next: open the login page, paste the code, then sign in.");
			setStep(1);
			pop(code);
		}
	}

	public static void show(Stage owner, String clientId, Consumer<MinecraftSession> onSuccess, Consumer<String> onError) {
		View view = build(owner);

		var executor = Executors.newVirtualThreadPerTaskExecutor();
		var future = executor.submit(() -> {
			try {
				MicrosoftAuth auth = new MicrosoftAuth(clientId);
				MinecraftSession session = auth.signInWithDeviceCode(deviceCode -> Platform.runLater(() -> {
					view.fill(deviceCode.userCode(), deviceCode.verificationUri());
					view.copy.setOnAction(e -> {
						copyToClipboard(deviceCode.userCode());
						view.copied.setText("Copied again");
						pop(view.copied);
					});
				}));
				Platform.runLater(() -> {
					view.stage.close();
					onSuccess.accept(session);
				});
			} catch (AuthException e) {
				Platform.runLater(() -> {
					view.stage.close();
					onError.accept(e.getMessage());
				});
			} catch (Exception e) {
				Platform.runLater(() -> {
					view.stage.close();
					onError.accept("Unexpected error: " + e.getMessage());
				});
			}
		});

		view.cancel.setOnAction(e -> {
			future.cancel(true);
			view.stage.close();
		});
		view.stage.setOnCloseRequest(e -> future.cancel(true));
		view.stage.show();
	}

	/** Dev-only preview (launcher screenshot tour): the dialog with a sample code, no network. */
	public static void demo(Stage owner, Object ignoredTheme) {
		View view = build(owner);
		view.cancel.setOnAction(e -> view.stage.close());
		view.stage.show();
		javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));
		later.setOnFinished(e -> view.fill("H7KQ-2XRM", LOGIN_URL_FALLBACK));
		later.play();
	}

	private static View build(Stage owner) {
		Stage stage = new Stage(StageStyle.TRANSPARENT);
		stage.initOwner(owner);
		stage.initModality(Modality.WINDOW_MODAL);
		View view = new View(stage);

		Label heading = new Label("Sign in with Microsoft");
		heading.getStyleClass().add("signin-heading");
		Label sub = new Label("Use the Microsoft account that owns Minecraft.");
		sub.getStyleClass().add("signin-sub");

		String[] stepNames = {"1  Copy code", "2  Open login page", "3  Sign in"};
		HBox stepRow = new HBox(6);
		stepRow.setAlignment(Pos.CENTER);
		for (int i = 0; i < 3; i++) {
			view.steps[i] = new Label(stepNames[i]);
			view.steps[i].getStyleClass().add("signin-step");
			stepRow.getChildren().add(view.steps[i]);
		}
		view.setStep(0);

		Label codeCaption = new Label("YOUR SIGN-IN CODE");
		codeCaption.getStyleClass().add("signin-caption");
		view.code.getStyleClass().add("signin-code");
		view.copy.getStyleClass().add("signin-copy");
		view.copy.setDisable(true);
		HBox codeRow = new HBox(12, view.code, view.copy);
		codeRow.setAlignment(Pos.CENTER);
		view.copied.getStyleClass().add("signin-copied");
		VBox codeCard = new VBox(8, codeCaption, codeRow, view.copied);
		codeCard.setAlignment(Pos.CENTER);
		codeCard.getStyleClass().add("signin-code-card");

		Label note = new Label("This code comes from this window. It is not a code from your email, phone or authenticator app.");
		note.setWrapText(true);
		note.getStyleClass().add("signin-note");

		view.open.getStyleClass().add("primary-button");
		view.open.setDefaultButton(true);
		view.open.setDisable(true);
		view.open.setMaxWidth(Double.MAX_VALUE);
		view.open.setOnAction(e -> {
			openInBrowser(view.url, view.status);
			view.setStep(2);
			view.status.setText("Paste the code on the Microsoft page and sign in - this window finishes by itself.");
		});
		view.cancel.getStyleClass().add("ghost-button");
		HBox.setHgrow(view.open, javafx.scene.layout.Priority.ALWAYS);
		HBox actions = new HBox(10, view.cancel, view.open);
		actions.setAlignment(Pos.CENTER);

		view.spinner.setPrefSize(16, 16);
		view.spinner.setMaxSize(16, 16);
		view.status.getStyleClass().add("signin-status");
		view.status.setWrapText(true);
		HBox statusRow = new HBox(8, view.spinner, view.status);
		statusRow.setAlignment(Pos.CENTER_LEFT);

		VBox box = new VBox(16, heading, sub, stepRow, codeCard, note, actions, statusRow);
		box.setAlignment(Pos.TOP_CENTER);
		box.setPadding(new Insets(28));
		box.getStyleClass().add("signin-dialog");
		box.setPrefWidth(440);
		VBox.setMargin(sub, new Insets(-12, 0, 0, 0));
		// Same theme colors as the main window (its root carries them as inline lookups).
		box.setStyle(owner.getScene().getRoot().getStyle());

		Scene scene = new Scene(box);
		scene.setFill(Color.TRANSPARENT);
		scene.getStylesheets().addAll(owner.getScene().getStylesheets());
		stage.setScene(scene);
		// A raw Stage (needed for StageStyle.TRANSPARENT) doesn't get owner-centering on its own.
		stage.setOnShown(e -> {
			stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
			stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
			box.setOpacity(0);
			box.setScaleX(0.96);
			box.setScaleY(0.96);
			javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(180), box);
			fade.setToValue(1);
			javafx.animation.ScaleTransition grow = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(220), box);
			grow.setToX(1);
			grow.setToY(1);
			grow.setInterpolator(javafx.animation.Interpolator.SPLINE(0.2, 0.9, 0.3, 1));
			new javafx.animation.ParallelTransition(fade, grow).play();
		});
		return view;
	}

	/** A quick scale "pop" to draw the eye to something that just changed. */
	private static void pop(javafx.scene.Node node) {
		javafx.animation.ScaleTransition up = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(120), node);
		up.setFromX(1);
		up.setFromY(1);
		up.setToX(1.06);
		up.setToY(1.06);
		up.setAutoReverse(true);
		up.setCycleCount(2);
		up.play();
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
