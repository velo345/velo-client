package net.veloclient.launcher.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.CapeEntry;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.GifFrames;
import net.veloclient.launcher.data.WingsCatalog;
import net.veloclient.launcher.theme.LauncherTheme;

import java.util.List;

/**
 * Cosmetics: a big live 3D showcase of your own skin wearing the selected cape or wings (left) and
 * your collection as cards (right), switched with Capes / Wings. Selecting a card previews it; Equip
 * makes it what other Velo players see on you. Wings also have their wear settings (size, position,
 * spread, flap) - the same ones the game has, saved for the game's next start.
 */
public final class CosmeticsView {

	public interface Host {
		LauncherTheme activeTheme();

		void rebuild();

		default MinecraftSession session() {
			return null;
		}
	}

	/** The card currently previewed - survives rebuilds (equip/delete/import rebuild the page). */
	private static String selectedId;
	/** Wings tab open (instead of capes). */
	private static boolean wingsTab;
	private static String selectedWings;

	private CosmeticsView() {
	}

	/** Opens on the Wings tab next time (the launcher tour). */
	public static void showWingsTab() {
		wingsTab = true;
	}

	public static Node build(Stage owner, Host host) {
		// Selecting/equipping refreshes this page in place (no full page transition per click).
		StackPane page = new StackPane();
		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> page.getChildren().setAll(buildContent(owner, host, refresh[0], false));
		page.getChildren().setAll(buildContent(owner, host, refresh[0], true));
		return page;
	}

	/** Capes / Wings switch. */
	private static HBox tabs(Runnable refresh) {
		HBox tabs = new HBox(6);
		for (boolean wings : new boolean[] {false, true}) {
			Button tab = new Button(wings ? "Wings" : "Capes");
			tab.getStyleClass().add("segmented-button");
			if (wings == wingsTab) {
				tab.getStyleClass().add("segmented-button-active");
			}
			tab.setOnAction(e -> {
				wingsTab = wings;
				refresh.run();
			});
			tabs.getChildren().add(tab);
		}
		return tabs;
	}

	private static Node buildContent(Stage owner, Host host, Runnable refresh, boolean firstShow) {
		if (wingsTab) {
			return buildWings(host, refresh, firstShow);
		}
		List<CapeEntry> capes = CapeLibrary.listAll();
		String equippedId = CapeLibrary.equippedCapeId().orElse(null);
		if (selectedId == null || capes.stream().noneMatch(c -> c.id().equals(selectedId))) {
			selectedId = equippedId != null ? equippedId : capes.isEmpty() ? null : capes.get(0).id();
		}
		CapeEntry selected = capes.stream().filter(c -> c.id().equals(selectedId)).findFirst().orElse(null);

		// Header.
		Label heading = new Label("Cosmetics");
		heading.getStyleClass().add("page-title");
		Label sub = new Label("Your capes. The equipped one shows in-game for you and every other Velo player.");
		sub.getStyleClass().add("page-subtitle");
		VBox titles = new VBox(2, heading, sub);
		Button importButton = new Button("+  Import cape");
		importButton.getStyleClass().add("primary-button");
		importButton.setOnAction(e -> CapeImportDialog.show(owner).ifPresent(result -> {
			try {
				CapeLibrary.importPng(result.name(), result.pngFile(), result.preset());
				refresh.run();
			} catch (Exception ex) {
				error(owner, "Import failed", ex.getMessage());
			}
		}));
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(12, titles, spacer, tabs(refresh), importButton);
		header.setAlignment(Pos.CENTER_LEFT);

		// Left: showcase.
		Node showcase = buildShowcase(owner, host, refresh, selected, selected != null && selected.id().equals(equippedId));

		// Right: collection.
		FlowPane grid = new FlowPane(14, 14);
		grid.getStyleClass().add("cape-grid");
		grid.getChildren().add(CosmeticUi.capeCard(null, "No cape", CosmeticUi.badge(equippedId == null ? "Equipped" : "Off",
				equippedId == null ? "chip-owned" : null), selected == null && equippedId == null, () -> {
					CapeLibrary.unequip();
					selectedId = null;
					refresh.run();
				}));
		for (CapeEntry cape : capes) {
			List<GifFrames.Frame> frames = CosmeticUi.frames(cape);
			boolean equipped = cape.id().equals(equippedId);
			Node footer = equipped ? CosmeticUi.badge("Equipped", "chip-owned")
					: CosmeticUi.badge(cape.animated() ? "Animated" : "Static", null);
			grid.getChildren().add(CosmeticUi.capeCard("lib:" + cape.id(), frames,
					cape.name(), footer, cape.id().equals(selectedId), () -> {
						selectedId = cape.id();
						refresh.run();
					}));
		}
		if (capes.isEmpty()) {
			Label empty = new Label("No capes yet - import a cape texture (" + CapeLibrary.TEXTURE_WIDTH + "x"
					+ CapeLibrary.TEXTURE_HEIGHT + " up to " + CapeLibrary.MAX_TEXTURE_WIDTH + "x" + CapeLibrary.MAX_TEXTURE_HEIGHT
					+ ") or get one in the Store.");
			empty.getStyleClass().add("empty-state");
			empty.setWrapText(true);
			grid.getChildren().add(empty);
		}
		Label collectionTitle = new Label("Collection  ·  " + capes.size());
		collectionTitle.getStyleClass().add("section-label");
		VBox collection = new VBox(12, collectionTitle, grid);
		ScrollPane scroll = new ScrollPane(collection);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		HBox.setHgrow(scroll, Priority.ALWAYS);
		if (firstShow) {
			UiMotion.stagger(grid, 24);
		}

		HBox body = new HBox(22, showcase, scroll);
		VBox.setVgrow(body, Priority.ALWAYS);
		VBox root = new VBox(18, header, body);
		VBox.setVgrow(root, Priority.ALWAYS);
		return root;
	}

	private static Node buildShowcase(Stage owner, Host host, Runnable refresh, CapeEntry selected, boolean equipped) {
		StackPane stage = CosmeticUi.stage(300, 380);
		stage.getStyleClass().add("showcase-stage");
		List<GifFrames.Frame> frames = selected == null ? null : CosmeticUi.frames(selected);
		Label loading = new Label(host.session() == null ? "Sign in to see capes on your own skin" : "Loading your skin...");
		loading.getStyleClass().add("page-subtitle");
		loading.setWrapText(true);
		stage.getChildren().add(loading);
		CosmeticUi.skin(host.session(), skin -> {
			Node viewer = skin == null ? null : PlayerSkin3DView.createShowcase(skin.pngBytes(), skin.slim(),
					frames == null || frames.isEmpty() ? null : frames);
			if (viewer != null) {
				stage.getChildren().setAll(viewer);
			} else if (frames != null && !frames.isEmpty()) {
				ImageView big = new ImageView(CosmeticUi.thumbnail("lib:" + selected.id(), frames, 260, 300));
				stage.getChildren().setAll(big);
			}
		});
		Label hint = new Label("Drag to rotate");
		hint.getStyleClass().add("stage-hint");
		StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
		StackPane stageWithHint = new StackPane(stage, hint);
		hint.setTranslateY(-10);

		Label name = new Label(selected == null ? "No cape" : selected.name());
		name.getStyleClass().add("showcase-title");
		HBox badges = new HBox(6);
		if (selected != null) {
			badges.getChildren().add(CosmeticUi.badge(selected.animated() ? "Animated" : "Static", null));
			if (equipped) {
				badges.getChildren().add(CosmeticUi.badge("Equipped", "chip-owned"));
			}
		}

		HBox actions = new HBox(8);
		if (selected != null) {
			Button equip = new Button(equipped ? "Unequip" : "Equip");
			if (!equipped) {
				equip.getStyleClass().add("primary-button");
			}
			equip.setMaxWidth(Double.MAX_VALUE);
			HBox.setHgrow(equip, Priority.ALWAYS);
			equip.setOnAction(e -> {
				if (equipped) {
					CapeLibrary.unequip();
				} else {
					CapeLibrary.equip(selected.id());
				}
				refresh.run();
			});
			Button delete = new Button("Delete");
			delete.getStyleClass().add("danger-ghost-button");
			delete.setOnAction(e -> {
				try {
					CapeLibrary.delete(selected);
					selectedId = null;
					refresh.run();
				} catch (Exception ex) {
					error(owner, "Delete failed", ex.getMessage());
				}
			});
			actions.getChildren().addAll(equip, delete);
		}

		VBox panel = new VBox(12, stageWithHint, name, badges, actions);
		panel.getStyleClass().add("showcase-panel");
		panel.setMinWidth(324);
		panel.setMaxWidth(324);
		return panel;
	}

	// ---- Wings ----

	private static Node buildWings(Host host, Runnable refresh, boolean firstShow) {
		if (!net.veloclient.launcher.social.StoreApi.walletLoaded()) {
			// What you own comes from the server - fetch it, then show the page again.
			java.util.concurrent.CompletableFuture.runAsync(() -> {
				try {
					net.veloclient.launcher.social.StoreApi.wallet();
					javafx.application.Platform.runLater(refresh);
				} catch (net.veloclient.launcher.social.StoreApi.StoreError ignored) {
					// Signed out / offline: nothing owned to show.
				}
			}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		}
		List<WingsCatalog.WingType> owned = WingsCatalog.all().stream()
				.filter(w -> net.veloclient.launcher.data.StoreOwnership.owns(w.itemId())).toList();
		String equipped = WingsCatalog.equipped();
		if (selectedWings == null || owned.stream().noneMatch(w -> w.id().equals(selectedWings))) {
			selectedWings = equipped != null && owned.stream().anyMatch(w -> w.id().equals(equipped)) ? equipped
					: owned.isEmpty() ? null : owned.get(0).id();
		}
		WingsCatalog.WingType selected = owned.stream().filter(w -> w.id().equals(selectedWings)).findFirst().orElse(null);

		Label heading = new Label("Cosmetics");
		heading.getStyleClass().add("page-title");
		Label sub = new Label("Your wings. The equipped pair shows in-game for you and every other Velo player.");
		sub.getStyleClass().add("page-subtitle");
		VBox titles = new VBox(2, heading, sub);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(12, titles, spacer, tabs(refresh));
		header.setAlignment(Pos.CENTER_LEFT);

		FlowPane grid = new FlowPane(14, 14);
		grid.getStyleClass().add("cape-grid");
		for (WingsCatalog.WingType type : owned) {
			grid.getChildren().add(wingCard(type, type.id().equals(equipped), type.id().equals(selectedWings), () -> {
				selectedWings = type.id();
				refresh.run();
			}));
		}
		if (owned.isEmpty()) {
			Label empty = new Label("No wings yet - get a pair in the Store.");
			empty.getStyleClass().add("empty-state");
			grid.getChildren().add(empty);
		}
		Label title = new Label("Your wings  ·  " + owned.size());
		title.getStyleClass().add("section-label");
		ScrollPane scroll = new ScrollPane(new VBox(12, title, grid));
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		HBox.setHgrow(scroll, Priority.ALWAYS);
		if (firstShow) {
			UiMotion.stagger(grid, 24);
		}
		HBox body = new HBox(22, wingsShowcase(host, refresh, selected, selected != null && selected.id().equals(equipped)), scroll);
		VBox.setVgrow(body, Priority.ALWAYS);
		VBox root = new VBox(18, header, body);
		VBox.setVgrow(root, Priority.ALWAYS);
		return root;
	}

	private static Node wingCard(WingsCatalog.WingType type, boolean equipped, boolean selected, Runnable onClick) {
		ImageView picture = new ImageView(new javafx.scene.image.Image(WingsCatalog.openPreview(type.id())));
		picture.setFitWidth(150);
		picture.setPreserveRatio(true);
		StackPane art = new StackPane(picture);
		art.setPrefSize(164, 124);
		art.setMinSize(164, 124);
		art.setStyle("-fx-background-radius: 12; -fx-background-color: radial-gradient(center 50% 60%, radius 70%, " + type.accent()
				+ "88 0%, #16161d00 100%), linear-gradient(to bottom, #1d1d25, #131318);");
		Label name = new Label(type.name());
		name.getStyleClass().add("cape-card-title");
		VBox card = new VBox(8, art, name, equipped ? CosmeticUi.badge("Equipped", "chip-owned") : CosmeticUi.badge("Owned", null));
		card.getStyleClass().addAll("cape-card", "motion-card");
		card.setStyle("-fx-border-color: " + type.accent() + (selected ? "" : "55") + "; -fx-border-radius: 14; -fx-border-width: "
				+ (selected ? "1.8" : "1.2") + ";");
		card.setOnMouseClicked(e -> onClick.run());
		return card;
	}

	private static Node wingsShowcase(Host host, Runnable refresh, WingsCatalog.WingType selected, boolean equipped) {
		StackPane stage = CosmeticUi.stage(300, 330);
		stage.getStyleClass().add("showcase-stage");
		Wings3D[] model = new Wings3D[1];
		if (selected != null) {
			ImageView still = new ImageView(new javafx.scene.image.Image(WingsCatalog.openPreview(selected.id())));
			still.setFitWidth(270);
			still.setPreserveRatio(true);
			stage.getChildren().add(still);
			CosmeticUi.skin(host.session(), skin -> {
				Node viewer = skin == null ? null : PlayerSkin3DView.createWingsShowcase(skin.pngBytes(), skin.slim(), w -> {
					model[0] = w.settings(WingsCatalog.settings(selected.id()));
					w.show(selected.id());
				});
				if (viewer != null) {
					stage.getChildren().setAll(viewer);
				}
			});
		}
		Label name = new Label(selected == null ? "No wings" : selected.name());
		name.getStyleClass().add("showcase-title");
		VBox panel = new VBox(10, stage, name);
		if (selected != null) {
			Button equip = new Button(equipped ? "Take them off" : "Equip");
			if (!equipped) {
				equip.getStyleClass().add("primary-button");
			}
			equip.setMaxWidth(Double.MAX_VALUE);
			equip.setOnAction(e -> {
				WingsCatalog.equip(equipped ? null : selected.id());
				refresh.run();
			});
			panel.getChildren().add(equip);
			panel.getChildren().add(settingsPanel(selected, model, refresh));
		}
		panel.getStyleClass().add("showcase-panel");
		panel.setMinWidth(324);
		panel.setMaxWidth(324);
		ScrollPane scroll = new ScrollPane(panel);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		scroll.setMinWidth(340);
		scroll.setMaxWidth(340);
		return scroll;
	}

	/** The game's wear settings for this pair: changes show on the 3D model right away and are saved when you let go. */
	private static Node settingsPanel(WingsCatalog.WingType type, Wings3D[] model, Runnable refresh) {
		WingsCatalog.Settings[] current = {WingsCatalog.settings(type.id())};
		VBox box = new VBox(6);
		Label title = new Label("How you wear them (this pair only)");
		title.getStyleClass().add("section-label");
		box.getChildren().add(title);
		Object[][] rows = {
				{"Size", 0.4, 2.0, (double) current[0].scale(), "%.0f%%", 100.0},
				{"Height", -0.5, 0.5, (double) -current[0].offsetY(), "%+.2f", 1.0},
				{"Distance", -0.2, 0.5, (double) current[0].offsetZ(), "%+.2f", 1.0},
				{"Spread", -30.0, 60.0, (double) current[0].spread(), "%.0f°", 1.0},
				{"Flap speed", 0.25, 3.0, (double) current[0].flapSpeed(), "%.0f%%", 100.0},
				{"Flap strength", 0.0, 2.0, (double) current[0].flapStrength(), "%.0f%%", 100.0}};
		for (int i = 0; i < rows.length; i++) {
			int field = i;
			Object[] row = rows[i];
			javafx.scene.control.Slider slider = new javafx.scene.control.Slider((double) row[1], (double) row[2], (double) row[3]);
			Label label = new Label();
			label.getStyleClass().add("page-subtitle");
			Runnable text = () -> label.setText(row[0] + "  " + String.format(java.util.Locale.ROOT, (String) row[4], slider.getValue() * (double) row[5]));
			text.run();
			slider.valueProperty().addListener((obs, o, n) -> {
				text.run();
				WingsCatalog.Settings s = current[0];
				float v = n.floatValue();
				current[0] = new WingsCatalog.Settings(field == 0 ? v : s.scale(), field == 1 ? -v : s.offsetY(), field == 2 ? v : s.offsetZ(),
						field == 3 ? v : s.spread(), field == 4 ? v : s.flapSpeed(), field == 5 ? v : s.flapStrength());
				if (model[0] != null) {
					model[0].settings(current[0]);
				}
			});
			slider.valueChangingProperty().addListener((obs, was, now) -> {
				if (!now) {
					WingsCatalog.setSettings(type.id(), current[0]);
				}
			});
			slider.setOnMouseReleased(e -> WingsCatalog.setSettings(type.id(), current[0]));
			box.getChildren().addAll(label, slider);
		}
		Button reset = new Button("Reset");
		reset.getStyleClass().add("ghost-button");
		reset.setOnAction(e -> {
			WingsCatalog.setSettings(type.id(), null);
			refresh.run();
		});
		Label note = new Label("Saved for the game - it picks them up the next time it starts.");
		note.getStyleClass().add("stage-hint");
		note.setWrapText(true);
		box.getChildren().addAll(reset, note);
		return box;
	}

	private static void error(Stage owner, String title, String message) {
		Alert alert = new Alert(Alert.AlertType.ERROR);
		alert.initOwner(owner);
		alert.setTitle(title);
		alert.setHeaderText(title);
		alert.setContentText(message);
		DialogStyling.apply(alert);
		alert.showAndWait();
	}
}
