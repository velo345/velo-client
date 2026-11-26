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
		// 2026-11-25 design chip: "FABRIC" small, the version, a chevron
		Label kind = new Label("FABRIC");
		kind.getStyleClass().add("chip-kind");
		Label number = new Label(LoaderVersions.forInstance(instance.id(), version));
		number.getStyleClass().add("chip-value");
		HBox content = new HBox(6, kind, number, Icons.icon("chevron-down", 12, Color.WHITE, 2.4));
		content.setAlignment(Pos.CENTER_LEFT);
		Label chip = new Label();
		chip.setGraphic(content);
		chip.getStyleClass().add("header-chip");
		chip.getProperties().put("velo.value", number);
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
		// Fabric API below the loader: the version in this profile, newer and older builds to switch to
		Label apiHeader = new Label("FABRIC API");
		apiHeader.getStyleClass().add("version-menu-header");
		VBox apiList = new VBox(2);
		apiList.getChildren().add(Skeleton.list(2));
		VBox content = new VBox(6, header, scroll, note, apiHeader, apiList);
		Popup popup = Popover.show(chip, content, Popover.Side.BELOW_LEFT, 320);
		java.nio.file.Path modsDir = net.veloclient.launcher.instance.InstancePaths.modsDir(instance.id());
		String installedApi = installedFabricApi(modsDir);
		CompletableFuture.supplyAsync(() -> {
			try {
				return net.veloclient.launcher.modrinth.ModrinthClient.versions("fabric-api", instance.mcVersion(), "mod");
			} catch (Exception ex) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(versions -> Platform.runLater(() -> {
			apiList.getChildren().clear();
			if (versions == null || versions.isEmpty()) {
				Label none = new Label(versions == null ? "Couldn't reach Modrinth" : "No Fabric API for " + instance.mcVersion());
				none.getStyleClass().add("t-muted");
				apiList.getChildren().add(none);
				return;
			}
			for (int i = 0; i < Math.min(5, versions.size()); i++) {
				var v = versions.get(i);
				String number = v.versionNumber();
				boolean isCurrent = installedApi != null && number.startsWith(installedApi.split("\\+")[0]) && installedApi.startsWith(number.split("\\+")[0]);
				Label label = new Label(number);
				label.getStyleClass().add("version-menu-number");
				HBox.setHgrow(label, Priority.ALWAYS);
				label.setMaxWidth(Double.MAX_VALUE);
				HBox row = new HBox(8, label);
				row.setAlignment(Pos.CENTER_LEFT);
				row.getStyleClass().add("version-menu-row");
				if (i == 0) {
					row.getChildren().add(tag("Newest", "version-tag-newest"));
				}
				if (isCurrent) {
					row.getStyleClass().add("version-menu-row-current");
					row.getChildren().add(Icons.icon("check", 16, Color.web("#7dd3a0"), 2.4));
				}
				row.setOnMouseClicked(e -> {
					if (isCurrent) {
						return;
					}
					popup.hide();
					Toasts.show(chip, "Getting Fabric API " + number + "...");
					Thread.ofVirtual().start(() -> {
						try {
							var file = v.primaryFile().orElseThrow();
							java.nio.file.Path temp = modsDir.resolve(".velo-download-" + file.filename() + ".part");
							java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
							client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(file.url())).build(), java.net.http.HttpResponse.BodyHandlers.ofFile(temp));
							try (var old = java.nio.file.Files.list(modsDir)) {
								for (java.nio.file.Path p : old.filter(f -> f.getFileName().toString().startsWith("fabric-api-") && f.getFileName().toString().endsWith(".jar")).toList()) {
									java.nio.file.Files.deleteIfExists(p);
								}
							}
							java.nio.file.Files.move(temp, modsDir.resolve(file.filename()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
							Platform.runLater(() -> Toasts.show(chip, "Fabric API " + number + " - used from the next start"));
						} catch (Exception ex) {
							Platform.runLater(() -> Toasts.show(chip, "Couldn't change Fabric API: " + ex.getMessage()));
						}
					});
				});
				apiList.getChildren().add(row);
			}
		}));
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
						if (chip.getProperties().get("velo.value") instanceof Label value) {
							value.setText(build.version());
						}
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

	/** The Fabric API version in a mods folder ("0.139.2+26.2"), from its file name; null when there is none. */
	private static String installedFabricApi(java.nio.file.Path modsDir) {
		try (var files = java.nio.file.Files.list(modsDir)) {
			return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("fabric-api-") && n.endsWith(".jar"))
					.map(n -> n.substring("fabric-api-".length(), n.length() - 4)).findFirst().orElse(null);
		} catch (java.io.IOException e) {
			return null;
		}
	}
}
