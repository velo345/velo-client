package net.veloclient.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.util.Duration;
import net.veloclient.launcher.data.InstalledAsset;
import net.veloclient.launcher.data.InstalledAssetStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.modrinth.ModrinthClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The version chip on an installed mod / pack row ("v0.6.2 ▾"): opens a list of every version made for this profile's
 * Minecraft version, newest first, the installed one ticked - pick one to switch to it (up or down).
 */
final class VersionDropdown {

	private VersionDropdown() {
	}

	static Button chip(Instance instance, InstalledAssetStore.Kind kind, InstalledAsset asset, Consumer<ModrinthClient.ProjectVersion> onPick) {
		Button chip = new Button("v" + asset.versionNumber());
		chip.setGraphic(Icons.icon("chevron-down", 12, Color.web("#A9A9B3"), 2.2));
		chip.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
		chip.getStyleClass().add("version-chip");
		chip.setOnAction(e -> open(chip, instance, kind, asset, onPick));
		return chip;
	}

	private static void open(Button chip, Instance instance, InstalledAssetStore.Kind kind, InstalledAsset asset,
			Consumer<ModrinthClient.ProjectVersion> onPick) {
		VBox list = new VBox(2);
		list.getChildren().add(Skeleton.list(4));
		Label header = new Label("Versions for Minecraft " + instance.mcVersion());
		header.getStyleClass().add("version-menu-header");
		ScrollPane scroll = new ScrollPane(list);
		scroll.setFitToWidth(true);
		scroll.setMaxHeight(320);
		scroll.getStyleClass().add("scroll-pane");
		VBox content = new VBox(6, header, scroll);
		Popup popup = Popover.show(chip, content, Popover.Side.BELOW_RIGHT, 320);
		CompletableFuture.supplyAsync(() -> {
			try {
				return ModrinthClient.versions(asset.projectId(), instance.mcVersion(), kind.modrinthProjectType()).stream()
						.sorted(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished).reversed()).toList();
			} catch (Exception ex) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(versions -> Platform.runLater(() -> {
			list.getChildren().clear();
			if (versions == null || versions.isEmpty()) {
				Label none = new Label(versions == null ? "Couldn't reach Modrinth - try again" : "No versions for Minecraft " + instance.mcVersion());
				none.getStyleClass().add("t-muted");
				list.getChildren().add(none);
				return;
			}
			for (int i = 0; i < versions.size(); i++) {
				ModrinthClient.ProjectVersion version = versions.get(i);
				boolean current = version.id().equals(asset.versionId());
				Node row = row(version, current, i == 0);
				row.setOnMouseClicked(e -> {
					popup.hide();
					if (!current) {
						onPick.accept(version);
					}
				});
				list.getChildren().add(row);
				// Rows slide in one after another - a short cascade, capped so long lists don't take ages.
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

	private static Node row(ModrinthClient.ProjectVersion version, boolean current, boolean newest) {
		Label number = new Label(version.versionNumber());
		number.getStyleClass().add("version-menu-number");
		Label when = new Label(age(version.datePublished()));
		when.getStyleClass().add("t-muted");
		VBox text = new VBox(1, number, when);
		HBox.setHgrow(text, Priority.ALWAYS);
		HBox row = new HBox(8, text);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("version-menu-row");
		if (current) {
			row.getStyleClass().add("version-menu-row-current");
		}
		String type = version.versionType() == null ? "release" : version.versionType();
		if (!"release".equals(type)) {
			row.getChildren().add(tag(type.equals("beta") ? "Beta" : "Alpha", "version-tag-beta"));
		}
		if (newest) {
			row.getChildren().add(tag("Newest", "version-tag-newest"));
		}
		Region check = new Region();
		check.setMinWidth(16);
		row.getChildren().add(current ? Icons.icon("check", 16, Color.web("#7dd3a0"), 2.4) : check);
		String notes = ChangelogView.preview(version.changelog(), 420);
		if (!notes.isEmpty()) {
			javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(notes);
			tip.setWrapText(true);
			tip.setMaxWidth(380);
			tip.setShowDelay(Duration.millis(250));
			javafx.scene.control.Tooltip.install(row, tip);
		}
		return row;
	}

	private static Label tag(String text, String style) {
		Label tag = new Label(text);
		tag.getStyleClass().addAll("version-tag-pill", style);
		return tag;
	}

	static String age(String iso) {
		try {
			long days = ChronoUnit.DAYS.between(Instant.parse(iso), Instant.now());
			return days <= 0 ? "today" : days == 1 ? "yesterday" : days < 30 ? days + " days ago"
					: days < 365 ? days / 30 + (days / 30 == 1 ? " month ago" : " months ago") : days / 365 + (days / 365 == 1 ? " year ago" : " years ago");
		} catch (Exception e) {
			return "";
		}
	}

	/** For callers that only need the list (migration): every version for {@code mcVersion}, newest first. */
	static List<ModrinthClient.ProjectVersion> versionsFor(String projectId, String mcVersion, InstalledAssetStore.Kind kind) throws java.io.IOException {
		return ModrinthClient.versions(projectId, mcVersion, kind.modrinthProjectType()).stream()
				.sorted(Comparator.comparing(ModrinthClient.ProjectVersion::datePublished).reversed()).toList();
	}
}
