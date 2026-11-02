package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.util.Duration;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.launch.GameVersion;
import net.veloclient.launcher.launch.LoaderVersions;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The "Fabric 0.19.5" chip in the profile banner: click it to run the profile on another Fabric Loader build (newest
 * first, Velo's tested one marked). Applies on the next start; the Doctor checks mods against the chosen one.
 */
final class LoaderPicker {

	private LoaderPicker() {
	}

	static Label chip(Instance instance, Runnable changed) {
		GameVersion version = GameVersion.byId(instance.mcVersion());
		Label chip = new Label("Fabric " + LoaderVersions.forInstance(instance.id(), version));
		chip.getStyleClass().addAll("badge", "badge-grey", "badge-mono", "badge-clickable");
		chip.setGraphic(Icons.icon("chevron-down", 11, Color.web("#A9A9B3"), 2.2));
		chip.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
		Tooltip.install(chip, new Tooltip("Fabric Loader for this profile - click to pick another build"));
		chip.setOnMouseClicked(e -> open(chip, instance, version, changed));
		return chip;
	}

	private static void open(Label chip, Instance instance, GameVersion version, Runnable changed) {
		VBox list = new VBox(2);
		list.getChildren().add(Skeleton.list(4));
		Label header = new Label("Fabric Loader for Minecraft " + instance.mcVersion());
		header.getStyleClass().add("version-menu-header");
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.setMaxHeight(320);
		scroll.getStyleClass().add("scroll-pane");
		Label note = new Label("Applies on the next start. Velo is tested with " + version.fabricLoaderVersion() + ".");
		note.getStyleClass().add("t-muted");
		note.setWrapText(true);
		VBox content = new VBox(6, header, scroll, note);
		Popup popup = Popover.show(chip, content, Popover.Side.BELOW_RIGHT, 300);
		String current = LoaderVersions.forInstance(instance.id(), version);
		CompletableFuture.supplyAsync(() -> {
			try {
				return LoaderVersions.available(instance.mcVersion());
			} catch (Exception ex) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(builds -> Platform.runLater(() -> {
			list.getChildren().clear();
			if (builds == null || builds.isEmpty()) {
				Label none = new Label(builds == null ? "Couldn't reach Fabric - try again" : "No loader builds found");
				none.getStyleClass().add("t-muted");
				list.getChildren().add(none);
				return;
			}
			List<LoaderVersions.Build> shown = builds.stream().limit(40).toList();
			for (int i = 0; i < shown.size(); i++) {
				LoaderVersions.Build build = shown.get(i);
				boolean isCurrent = build.version().equals(current);
				Node row = row(build, isCurrent, build.version().equals(version.fabricLoaderVersion()));
				row.setOnMouseClicked(e -> {
					popup.hide();
					if (isCurrent) {
						return;
					}
					try {
						LoaderVersions.set(instance.id(), version, build.version());
						chip.setText("Fabric " + build.version());
						Toasts.show(chip, "Fabric Loader " + build.version() + " - used from the next start");
						if (changed != null) {
							changed.run();
						}
					} catch (Exception ex) {
						Toasts.show(chip, "Couldn't change the loader: " + ex.getMessage());
					}
				});
				list.getChildren().add(row);
				if (i < 12) {
					row.setOpacity(0);
					FadeTransition fade = new FadeTransition(Duration.millis(180), row);
					fade.setToValue(1);
					TranslateTransition slide = new TranslateTransition(Duration.millis(220), row);
					slide.setFromY(6);
					slide.setToY(0);
					slide.setInterpolator(UiMotion.EASE_OUT);
					ParallelTransition in = new ParallelTransition(fade, slide);
					in.setDelay(Duration.millis(i * 22L));
					in.play();
				}
			}
		}));
	}

	private static Node row(LoaderVersions.Build build, boolean current, boolean recommended) {
		Label number = new Label(build.version());
		number.getStyleClass().add("version-menu-number");
		HBox.setHgrow(number, Priority.ALWAYS);
		number.setMaxWidth(Double.MAX_VALUE);
		HBox row = new HBox(8, number);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("version-menu-row");
		if (current) {
			row.getStyleClass().add("version-menu-row-current");
		}
		if (!build.stable()) {
			row.getChildren().add(tag("Beta", "version-tag-beta"));
		}
		if (recommended) {
			row.getChildren().add(tag("Recommended", "version-tag-newest"));
		}
		Region check = new Region();
		check.setMinWidth(16);
		row.getChildren().add(current ? Icons.icon("check", 16, Color.web("#7dd3a0"), 2.4) : check);
		return row;
	}

	private static Label tag(String text, String style) {
		Label tag = new Label(text);
		tag.getStyleClass().addAll("version-tag-pill", style);
		return tag;
	}
}
