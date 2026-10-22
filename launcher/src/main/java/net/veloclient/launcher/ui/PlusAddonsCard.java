package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.launch.GameVersion;
import net.veloclient.launcher.launch.PlusAddons;
import net.veloclient.launcher.social.StoreApi;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The "Addons" card at the top of a profile's Mods tab - only for the players the owner gave the Addons to (the server
 * says so in {@code /v1/plus/status}); for everyone else it never appears, not even as a teaser. They add the Addons
 * to this profile with one button (and switch them off, on or remove them again); the launcher keeps them up to date at
 * every launch (see PlusAddons).
 */
final class PlusAddonsCard {

	private PlusAddonsCard() {
	}

	/** @param onChange called after the profile's mods folder changed (refresh the list below). */
	static Node build(Instance instance, Runnable onChange) {
		Path modsDir = InstancePaths.modsDir(instance.id());
		Label badge = new Label("+");
		badge.setMinSize(48, 48);
		badge.setMaxSize(48, 48);
		badge.setAlignment(Pos.CENTER);
		badge.setStyle("-fx-background-color: linear-gradient(to bottom right, #ff4d4d, #c2182b); -fx-background-radius: 12; -fx-text-fill: white;"
				+ " -fx-font-size: 28px; -fx-font-weight: 900;");
		Label title = new Label("Addons");
		title.setStyle("-fx-font-weight: 800; -fx-font-size: 14px; -fx-text-fill: white;");
		HBox titleRow = new HBox(8, title);
		titleRow.setAlignment(Pos.CENTER_LEFT);
		Label detail = new Label("");
		detail.getStyleClass().add("settings-row-hint");
		detail.setWrapText(true);
		VBox text = new VBox(2, titleRow, detail);
		HBox.setHgrow(text, Priority.ALWAYS);

		Button primary = new Button();
		primary.getStyleClass().add("primary-button");
		primary.setVisible(false);
		primary.setManaged(false);
		Button toggle = new Button();
		toggle.getStyleClass().add("ghost-button");
		toggle.setVisible(false);
		toggle.setManaged(false);
		Button remove = new Button("Remove");
		remove.getStyleClass().add("ghost-button");
		remove.setVisible(false);
		remove.setManaged(false);

		boolean[] member = {false};
		String[] lastProblem = {null};
		Runnable[] update = new Runnable[1];
		update[0] = () -> {
			PlusAddons.State state = PlusAddons.state(modsDir);
			if (!member[0]) {
				return;
			}
			boolean installed = state != PlusAddons.State.NOT_INSTALLED;
			detail.setText(switch (state) {
				case NOT_INSTALLED -> "Not in this profile - Addons are opt-in: add them with one click (then kept up to date automatically).";
				case ENABLED -> "In this profile and switched on.";
				case DISABLED -> "In this profile, switched off.";
				case MISMATCH -> "Waiting for Addons that fit this profile's Velo Client - they switch on by themselves once they're out.";
			} + (lastProblem[0] == null ? "" : "\n" + lastProblem[0]));
			if (state == PlusAddons.State.MISMATCH) {
				CompletableFuture.supplyAsync(() -> PlusAddons.mismatch(modsDir), Executors.newVirtualThreadPerTaskExecutor())
						.thenAccept(m -> Platform.runLater(() -> {
							if (m != null && PlusAddons.state(modsDir) == PlusAddons.State.MISMATCH) {
								detail.setText("Waiting: the newest Addons are built for Velo Client " + m[0] + ", this profile has " + m[1]
										+ ". They switch on by themselves once matching Addons are out.");
							}
						}));
			}
			primary.setText("Add to this profile");
			primary.setVisible(!installed);
			primary.setManaged(!installed);
			toggle.setText(state == PlusAddons.State.ENABLED ? "Switch off" : state == PlusAddons.State.MISMATCH ? "Check again" : "Switch on");
			toggle.setVisible(installed);
			toggle.setManaged(installed);
			remove.setVisible(installed);
			remove.setManaged(installed);
		};
		primary.setOnAction(e -> {
			primary.setDisable(true);
			primary.setText("Adding...");
			CompletableFuture.supplyAsync(() -> PlusAddons.installNow(modsDir, GameVersion.byId(instance.mcVersion())),
					Executors.newVirtualThreadPerTaskExecutor()).whenComplete((problem, error) -> Platform.runLater(() -> {
				lastProblem[0] = error != null ? "Couldn't add the Addons: " + error.getMessage() : problem;
				primary.setDisable(false);
				update[0].run();
				onChange.run();
			}));
		});
		toggle.setOnAction(e -> {
			PlusAddons.State state = PlusAddons.state(modsDir);
			if (state == PlusAddons.State.ENABLED) {
				PlusAddons.setEnabled(modsDir, false);
				update[0].run();
				onChange.run();
				return;
			}
			// Switching on also asks the server for the newest build: a parked (mismatched) jar may have a fitting update by now.
			toggle.setDisable(true);
			toggle.setText("Switching on...");
			CompletableFuture.supplyAsync(() -> PlusAddons.installNow(modsDir, GameVersion.byId(instance.mcVersion())),
					Executors.newVirtualThreadPerTaskExecutor()).whenComplete((problem, error) -> Platform.runLater(() -> {
				lastProblem[0] = error != null ? "Couldn't add the Addons: " + error.getMessage() : problem;
				toggle.setDisable(false);
				update[0].run();
				onChange.run();
			}));
		});
		remove.setOnAction(e -> {
			PlusAddons.remove(modsDir);
			update[0].run();
			onChange.run();
		});

		HBox actions = new HBox(8, primary, toggle, remove);
		actions.setAlignment(Pos.CENTER_RIGHT);
		HBox top = new HBox(12, badge, text, actions);
		top.setAlignment(Pos.CENTER_LEFT);
		VBox card = new VBox(8, top);
		card.getStyleClass().add("mod-row");
		card.setPadding(new Insets(10, 12, 10, 12));
		card.setStyle("-fx-border-color: rgba(255,77,77,0.45); -fx-border-radius: 12; -fx-background-radius: 12;");
		StackPane holder = new StackPane();
		holder.setVisible(false);
		holder.setManaged(false);

		CompletableFuture.supplyAsync(() -> {
			try {
				var status = StoreApi.plusStatus();
				return status.has("addons") && status.get("addons").getAsBoolean();
			} catch (Exception e) {
				return false;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			member[0] = result;
			if (result) {
				holder.getChildren().setAll(card);
				holder.setVisible(true);
				holder.setManaged(true);
				update[0].run();
			}
		}));
		return holder;
	}
}
