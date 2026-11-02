package net.veloclient.launcher.ui;

import com.google.gson.JsonObject;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import net.veloclient.launcher.data.ProfileShare;
import net.veloclient.launcher.instance.Instance;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * "Share as code" (a profile becomes VELO-7XK2Q9 - big, copied, ready to post) and "Add from code" (paste a code, see
 * what's in it, get the same profile).
 */
public final class ProfileCodeDialog {

	private ProfileCodeDialog() {
	}

	public static void showShare(Stage owner, Instance instance) {
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Share profile");
		DialogStyling.apply(dialog);
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
		Label title = new Label("Share \"" + instance.name() + "\"");
		title.getStyleClass().add("error-dialog-title");
		Label sub = new Label("Friends type the code in Library → Import → From a code and get the same mods, packs and shaders.");
		sub.getStyleClass().add("t-muted");
		sub.setWrapText(true);
		CheckBox settings = new CheckBox("Include my settings and keybinds");
		settings.setSelected(true);
		Button make = new Button("Make code");
		make.getStyleClass().add("primary-button");
		Label code = new Label();
		code.getStyleClass().add("share-code");
		code.setVisible(false);
		code.setManaged(false);
		Label note = new Label();
		note.getStyleClass().add("t-muted");
		note.setWrapText(true);
		Button copy = new Button("Copy");
		copy.setVisible(false);
		copy.setManaged(false);
		make.setOnAction(e -> {
			make.setDisable(true);
			make.setText("Making...");
			CompletableFuture.supplyAsync(() -> {
				try {
					return ProfileShare.share(instance, settings.isSelected());
				} catch (Exception ex) {
					throw new RuntimeException(ex.getMessage(), ex);
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).whenComplete((shared, error) -> Platform.runLater(() -> {
				if (shared == null) {
					make.setDisable(false);
					make.setText("Make code");
					note.setText("Couldn't make a code: " + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
					return;
				}
				make.setVisible(false);
				make.setManaged(false);
				settings.setDisable(true);
				code.setText(shared.code());
				code.setVisible(true);
				code.setManaged(true);
				copy.setVisible(true);
				copy.setManaged(true);
				boolean copied = Clipboards.copy(shared.code());
				copy.setText(copied ? "Copied" : "Copy");
				copy.setOnAction(ev -> copy.setText(Clipboards.copy(shared.code()) ? "Copied" : "Copy failed"));
				note.setText((shared.skipped() > 0 ? shared.skipped() + (shared.skipped() == 1 ? " file isn't" : " files aren't")
						+ " on Modrinth, so " + (shared.skipped() == 1 ? "it's" : "they're") + " not in the code. " : "") + "The code works for 180 days.");
				code.setScaleX(0.6);
				code.setScaleY(0.6);
				ScaleTransition pop = new ScaleTransition(Duration.millis(380), code);
				pop.setToX(1);
				pop.setToY(1);
				pop.setInterpolator(UiMotion.BACK_OUT);
				pop.play();
			}));
		});
		HBox codeRow = new HBox(12, code, copy);
		codeRow.setAlignment(Pos.CENTER_LEFT);
		VBox root = new VBox(12, title, sub, settings, make, codeRow, note);
		root.setPadding(new Insets(6));
		root.setPrefWidth(460);
		dialog.getDialogPane().setContent(root);
		dialog.show();
	}

	/** {@code onCreated} gets the new profile (on the FX thread) - open it, refresh the library... */
	public static void showJoin(Stage owner, Consumer<Instance> onCreated) {
		Dialog<Void> dialog = new Dialog<>();
		dialog.initOwner(owner);
		dialog.setTitle("Add a profile from a code");
		DialogStyling.apply(dialog);
		dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
		Label title = new Label("Add a profile from a code");
		title.getStyleClass().add("error-dialog-title");
		TextField field = new TextField();
		field.setPromptText("VELO-7XK2Q9");
		field.getStyleClass().add("share-code-field");
		Button look = new Button("Look up");
		look.getStyleClass().add("primary-button");
		HBox row = new HBox(8, field, look);
		HBox.setHgrow(field, Priority.ALWAYS);
		VBox preview = new VBox(6);
		Label status = new Label();
		status.getStyleClass().add("t-muted");
		status.setWrapText(true);
		VBox root = new VBox(12, title, row, preview, status);
		root.setPadding(new Insets(6));
		root.setPrefWidth(480);
		look.setOnAction(e -> {
			look.setDisable(true);
			status.setText("Looking it up...");
			preview.getChildren().clear();
			CompletableFuture.supplyAsync(() -> {
				try {
					return ProfileShare.fetch(field.getText());
				} catch (Exception ex) {
					throw new RuntimeException(ex.getMessage(), ex);
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).whenComplete((shared, error) -> Platform.runLater(() -> {
				look.setDisable(false);
				if (shared == null) {
					status.setText(error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
					return;
				}
				status.setText("");
				showPreview(owner, dialog, preview, status, shared, onCreated);
			}));
		});
		field.setOnAction(e -> look.fire());
		dialog.getDialogPane().setContent(root);
		dialog.show();
		field.requestFocus();
	}

	private static void showPreview(Stage owner, Dialog<Void> dialog, VBox preview, Label status, JsonObject shared, Consumer<Instance> onCreated) {
		int mods = size(shared, "mods");
		int packs = size(shared, "resourcepacks");
		int shaders = size(shared, "shaders");
		Label name = new Label(shared.get("name").getAsString());
		name.getStyleClass().add("share-preview-name");
		Label meta = new Label("Minecraft " + shared.get("minecraft").getAsString() + "  ·  " + mods + " mods" + (packs > 0 ? ", " + packs + " packs" : "")
				+ (shaders > 0 ? ", " + shaders + " shaders" : "") + (shared.has("options") ? "  ·  with settings" : "")
				+ (shared.has("by") ? "  ·  by " + shared.get("by").getAsString() : ""));
		meta.getStyleClass().add("t-muted");
		TextField newName = new TextField(shared.get("name").getAsString());
		Button add = new Button("Add profile");
		add.getStyleClass().add("primary-button");
		ProgressBar bar = new ProgressBar(0);
		bar.setMaxWidth(Double.MAX_VALUE);
		bar.setVisible(false);
		bar.setManaged(false);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox actions = new HBox(8, newName, add);
		HBox.setHgrow(newName, Priority.ALWAYS);
		VBox card = new VBox(6, name, meta, actions, bar);
		card.getStyleClass().add("share-preview");
		preview.getChildren().setAll(card);
		UiMotion.enter(card);
		add.setOnAction(e -> {
			add.setDisable(true);
			newName.setDisable(true);
			bar.setVisible(true);
			bar.setManaged(true);
			String chosen = newName.getText().isBlank() ? shared.get("name").getAsString() : newName.getText().strip();
			CompletableFuture.supplyAsync(() -> {
				try {
					return ProfileShare.install(shared, chosen, (text, fraction) -> Platform.runLater(() -> {
						status.setText(text);
						bar.setProgress(fraction);
					}));
				} catch (Exception ex) {
					throw new RuntimeException(ex.getMessage(), ex);
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).whenComplete((instance, error) -> Platform.runLater(() -> {
				if (instance == null) {
					add.setDisable(false);
					status.setText("Couldn't add it: " + (error.getCause() != null ? error.getCause().getMessage() : error.getMessage()));
					return;
				}
				dialog.close();
				Toasts.show(owner.getScene().getRoot(), "check", "#7dd3a0", "\"" + instance.name() + "\" added");
				onCreated.accept(instance);
			}));
		});
	}

	private static int size(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key).size() : 0;
	}
}
