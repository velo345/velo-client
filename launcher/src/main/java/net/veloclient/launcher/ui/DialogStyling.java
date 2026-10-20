package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Every launcher popup (edit profile, add server, confirmations, errors...) goes through here: it gets the
 * launcher's stylesheet and the active theme's colors, loses the OS title bar to become a rounded card that can
 * be dragged, pops in, and dims the window behind it while open.
 */
public final class DialogStyling {

	private DialogStyling() {
	}

	public static void apply(Dialog<?> dialog) {
		DialogPane pane = dialog.getDialogPane();
		pane.getStylesheets().add(DialogStyling.class.getResource("/net/veloclient/launcher/launcher.css").toExternalForm());
		pane.getStyleClass().add("velo-dialog");
		if (dialog instanceof javafx.scene.control.Alert alert) {
			// Our own line icon instead of the OS's blue question mark / red cross.
			String[] icon = switch (alert.getAlertType()) {
				case ERROR -> new String[] {"warning", "#FF7A7E"};
				case WARNING -> new String[] {"warning", "#F7C04A"};
				case CONFIRMATION -> new String[] {"info", "#A9A9B3"};
				default -> new String[] {"info", "#5AA9FF"};
			};
			StackPane badge = new StackPane(Icons.icon(icon[0], 20, Color.web(icon[1]), 2.2));
			badge.setMinSize(40, 40);
			badge.setMaxSize(40, 40);
			badge.setStyle("-fx-background-color: " + icon[1] + "22; -fx-background-radius: 12;");
			alert.setGraphic(badge);
		}
		try {
			dialog.initStyle(StageStyle.TRANSPARENT);
		} catch (IllegalStateException ignored) {
			// Already showing: keep the normal window.
		}
		Window owner = dialog.getOwner();
		if (owner != null && owner.getScene() != null) {
			// The window's theme colors live in an inline style on its root; the dialog is its own scene.
			String style = owner.getScene().getRoot().getStyle().replace("-fx-background-color: -velo-background;", "");
			pane.setStyle(style + pane.getStyle());
		}
		Scene scene = pane.getScene();
		if (scene != null) {
			scene.setFill(Color.TRANSPARENT);
		}
		// No title bar to grab: drag anywhere on the card that isn't a control.
		double[] grab = new double[2];
		pane.setOnMousePressed(e -> {
			grab[0] = e.getScreenX() - dialog.getX();
			grab[1] = e.getScreenY() - dialog.getY();
		});
		pane.setOnMouseDragged(e -> {
			dialog.setX(e.getScreenX() - grab[0]);
			dialog.setY(e.getScreenY() - grab[1]);
		});
		Region[] scrim = new Region[1];
		var previousShowing = dialog.getOnShowing();
		var previousHidden = dialog.getOnHidden();
		dialog.setOnShowing(e -> {
			if (previousShowing != null) {
				previousShowing.handle(e);
			}
			// The launcher is in English everywhere - JavaFX would label its standard buttons in the system language.
			for (javafx.scene.control.ButtonType type : pane.getButtonTypes()) {
				if (pane.lookupButton(type) instanceof javafx.scene.control.Button button) {
					String english = type == javafx.scene.control.ButtonType.CANCEL ? "Cancel" : type == javafx.scene.control.ButtonType.OK ? "OK"
							: type == javafx.scene.control.ButtonType.YES ? "Yes" : type == javafx.scene.control.ButtonType.NO ? "No"
							: type == javafx.scene.control.ButtonType.CLOSE ? "Close" : type == javafx.scene.control.ButtonType.APPLY ? "Apply" : null;
					if (english != null) {
						button.setText(english);
					}
				}
			}
			pane.applyCss();
			pane.layout();
			if (owner != null) {
				// JavaFX's own owner-centering is unreliable on multi-monitor setups - center over the owner explicitly.
				double width = pane.prefWidth(-1);
				double height = pane.prefHeight(width);
				dialog.setX(owner.getX() + (owner.getWidth() - width) / 2);
				dialog.setY(owner.getY() + (owner.getHeight() - height) / 2);
				if (owner.getScene() != null && owner.getScene().getRoot() instanceof StackPane layers) {
					Region dim = new Region();
					dim.getStyleClass().add("modal-scrim");
					dim.setOpacity(0);
					layers.getChildren().add(dim);
					FadeTransition fade = new FadeTransition(Duration.millis(180), dim);
					fade.setToValue(1);
					fade.play();
					scrim[0] = dim;
				}
			}
			pane.setOpacity(0);
			pane.setScaleX(0.96);
			pane.setScaleY(0.96);
			FadeTransition fade = new FadeTransition(Duration.millis(180), pane);
			fade.setToValue(1);
			ScaleTransition pop = new ScaleTransition(Duration.millis(220), pane);
			pop.setToX(1);
			pop.setToY(1);
			pop.setInterpolator(UiMotion.EASE_OUT);
			new ParallelTransition(fade, pop).play();
		});
		dialog.setOnHidden(e -> {
			if (previousHidden != null) {
				previousHidden.handle(e);
			}
			if (scrim[0] != null && scrim[0].getParent() instanceof StackPane layers) {
				layers.getChildren().remove(scrim[0]);
			}
		});
	}
}
