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
import net.veloclient.launcher.auth.SkinFetcher;
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
	/** Auras tab open. */
	private static boolean aurasTab;
	private static String selectedAura;

	private CosmeticsView() {
	}

	/** Opens on the Wings tab next time (the launcher tour). */
	public static void showWingsTab() {
		wingsTab = true;
		aurasTab = false;
	}

	/** Opens on the Auras tab next time (the launcher tour). */
	public static void showAurasTab() {
		aurasTab = true;
		wingsTab = false;
	}

	public static Node build(Stage owner, Host host) {
		// Selecting/equipping refreshes this page in place (no full page transition per click).
		StackPane page = new StackPane();
		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> ScrollKeeper.setAll(page, buildContent(owner, host, refresh[0], false));
		page.getChildren().setAll(buildContent(owner, host, refresh[0], true));
		return page;
	}

	/** Capes / Wings / Auras switch. */
	private static HBox tabs(Runnable refresh) {
		HBox tabs = new HBox(6);
		String[] names = {"Capes", "Wings", "Auras"};
		int active = aurasTab ? 2 : wingsTab ? 1 : 0;
		for (int i = 0; i < names.length; i++) {
			int index = i;
			Button tab = new Button(names[i]);
			tab.setMinWidth(Region.USE_PREF_SIZE);
			tab.getStyleClass().add("segmented-button");
			if (i == active) {
				tab.getStyleClass().add("segmented-button-active");
			}
			tab.setOnAction(e -> {
				wingsTab = index == 1;
				aurasTab = index == 2;
				refresh.run();
			});
			tabs.getChildren().add(tab);
		}
		tabs.setMinWidth(Region.USE_PREF_SIZE);
		return tabs;
	}

	/**
	 * The page header, the same on every tab: title and subtitle (with {@code extra} - e.g. Import - on
	 * the right), then the tab row under them. The tabs used to sit beside the title, so the Import button
	 * on the Capes tab squeezed them (cut off to "A...") and moved everything between tabs.
	 */
	private static Node header(String subtitle, Runnable refresh, Node extra) {
		Label heading = new Label("Cosmetics");
		heading.getStyleClass().add("page-title");
		Label sub = new Label(subtitle);
		sub.getStyleClass().add("page-subtitle");
		VBox titles = new VBox(2, heading, sub);
		titles.setMinWidth(0);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox top = extra == null ? new HBox(12, titles) : new HBox(12, titles, spacer, extra);
		top.setAlignment(Pos.CENTER_LEFT);
		return new VBox(12, top, tabs(refresh));
	}

	// ---- Auras ----

	private static Node buildAuras(Host host, Runnable refresh, boolean firstShow) {
		if (!net.veloclient.launcher.social.StoreApi.walletLoaded()) {
			java.util.concurrent.CompletableFuture.runAsync(() -> {
				try {
					net.veloclient.launcher.social.StoreApi.wallet();
					javafx.application.Platform.runLater(refresh);
				} catch (net.veloclient.launcher.social.StoreApi.StoreError ignored) {
					// Signed out / offline: nothing owned to show.
				}
			}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		}
		List<net.veloclient.launcher.data.AuraCatalog.AuraType> owned = net.veloclient.launcher.data.AuraCatalog.all().stream()
				.filter(a -> net.veloclient.launcher.data.AuraCatalog.owns(a.id())).toList();
		String equipped = net.veloclient.launcher.data.AuraCatalog.equipped();
		if (selectedAura == null || owned.stream().noneMatch(a -> a.id().equals(selectedAura))) {
			selectedAura = equipped != null && owned.stream().anyMatch(a -> a.id().equals(equipped)) ? equipped
					: owned.isEmpty() ? null : owned.get(0).id();
		}
		var selected = owned.stream().filter(a -> a.id().equals(selectedAura)).findFirst().orElse(null);

		Node header = header("Your auras. The equipped one shows in-game for you and every other Velo player.", refresh, null);

		FlowPane grid = new FlowPane(14, 14);
		grid.getStyleClass().add("cape-grid");
		for (var aura : owned) {
			boolean isEquipped = aura.id().equals(equipped);
			var settings = net.veloclient.launcher.data.AuraCatalog.settings(aura.id());
			ImageView art = new ImageView(AuraFx.snapshot(aura.kind(), settings, 164, 124));
			art.setFitWidth(164);
			art.setFitHeight(124);
			art.setPreserveRatio(false);
			StackPane artBox = new StackPane(art);
			artBox.setStyle("-fx-background-radius: 12; -fx-background-color: radial-gradient(center 50% 60%, radius 70%, " + aura.accent()
					+ "44 0%, #0f0f1400 100%), #101015;");
			Label name = new Label(aura.name());
			name.getStyleClass().add("cape-card-title");
			VBox card = new VBox(8, artBox, name, isEquipped ? CosmeticUi.badge("Equipped", "chip-owned") : CosmeticUi.badge("Owned", null));
			card.getStyleClass().addAll("cape-card", "motion-card");
			boolean isSelected = aura.id().equals(selectedAura);
			card.setStyle("-fx-border-color: " + aura.accent() + (isSelected ? "" : "55") + "; -fx-border-radius: 14; -fx-border-width: "
					+ (isSelected ? "1.8" : "1.2") + ";");
			card.setOnMouseClicked(e -> {
				selectedAura = aura.id();
				refresh.run();
			});
			grid.getChildren().add(card);
		}
		if (owned.isEmpty()) {
			Label empty = new Label("No auras yet - get one in the Store.");
			empty.getStyleClass().add("empty-state");
			grid.getChildren().add(empty);
		}
		Label title = new Label("Your auras  ·  " + owned.size());
		title.getStyleClass().add("section-label");
		ScrollPane scroll = new ScrollPane(new VBox(12, title, grid));
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		HBox.setHgrow(scroll, Priority.ALWAYS);
		if (firstShow) {
			UiMotion.stagger(grid, 24);
		}

		VBox panel = new VBox(10);
		if (selected != null) {
			net.veloclient.velo.client.cosmetics.aura.AuraEffects.Settings[] current = {net.veloclient.launcher.data.AuraCatalog.settings(selected.id())};
			panel.getChildren().add(AuraDetailView.stage(host.session(), selected.kind(), () -> new AuraFx.Shown(selected.id(), current[0]), 300, 330));
			Label name = new Label(selected.name());
			name.getStyleClass().add("showcase-title");
			boolean isEquipped = selected.id().equals(equipped);
			Button equip = new Button(isEquipped ? "Take it off" : "Equip");
			if (!isEquipped) {
				equip.getStyleClass().add("primary-button");
			}
			equip.setMaxWidth(Double.MAX_VALUE);
			equip.setOnAction(e -> {
				net.veloclient.launcher.data.AuraCatalog.equip(isEquipped ? null : selected.id());
				refresh.run();
			});
			panel.getChildren().addAll(name, equip, AuraDetailView.settingsPanel(selected.id(), selected.kind(), s -> current[0] = s));
		} else {
			Label none = new Label("No aura");
			none.getStyleClass().add("showcase-title");
			panel.getChildren().add(none);
		}
		panel.getStyleClass().add("showcase-panel");
		panel.setMinWidth(324);
		panel.setMaxWidth(324);
		ScrollPane left = new ScrollPane(panel);
		left.setFitToWidth(true);
		left.getStyleClass().add("scroll-pane");
		left.setMinWidth(340);
		left.setMaxWidth(340);
		HBox body = new HBox(22, left, scroll);
		VBox.setVgrow(body, Priority.ALWAYS);
		VBox root = new VBox(18, header, body);
		VBox.setVgrow(root, Priority.ALWAYS);
		return root;
	}

	private static Node buildContent(Stage owner, Host host, Runnable refresh, boolean firstShow) {
		if (aurasTab) {
			return buildAuras(host, refresh, firstShow);
		}
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
		Node header = header("Your capes. The equipped one shows in-game for you and every other Velo player.", refresh, importButton);

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
			boolean equipped = cape.id().equals(equippedId);
			Node footer = equipped ? CosmeticUi.badge("Equipped", "chip-owned")
					: CosmeticUi.badge(cape.animated() ? "Animated" : "Static", null);
			// The card shows at once with a spinner; its 3D preview arrives when the frames are decoded (off the UI thread).
			grid.getChildren().add(CosmeticUi.capeCardLazy("lib:" + cape.id(), done -> CosmeticUi.framesAsync(cape, done),
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
		if (host.session() == null) {
			Label signIn = new Label("Sign in to see capes on your own skin");
			signIn.getStyleClass().add("page-subtitle");
			signIn.setWrapText(true);
			stage.getChildren().add(signIn);
		} else {
			stage.getChildren().add(CosmeticUi.spinner(34));
		}
		// The skin and the cape's frames both load off the UI thread; the 3D viewer is built once both are in.
		List<GifFrames.Frame>[] frames = new List[1];
		SkinFetcher.SkinData[] skinData = new SkinFetcher.SkinData[1];
		boolean[] arrived = new boolean[2];
		Runnable build = () -> {
			if (!arrived[0] || !arrived[1]) {
				return;
			}
			List<GifFrames.Frame> f = frames[0] == null || frames[0].isEmpty() ? null : frames[0];
			Node viewer = skinData[0] == null ? null : PlayerSkin3DView.createShowcase(skinData[0].pngBytes(), skinData[0].slim(), f);
			if (viewer != null) {
				viewer.setOpacity(0);
				stage.getChildren().setAll(viewer);
				javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), viewer);
				fade.setToValue(1);
				fade.play();
			} else if (f != null) {
				stage.getChildren().setAll(new ImageView(CosmeticUi.thumbnail("lib:" + selected.id(), f, 260, 300)));
			} else if (host.session() != null) {
				stage.getChildren().clear();
			}
		};
		if (selected == null) {
			arrived[1] = true;
		} else {
			CosmeticUi.framesAsync(selected, f -> {
				frames[0] = f;
				arrived[1] = true;
				build.run();
			});
		}
		CosmeticUi.skin(host.session(), data -> {
			skinData[0] = data;
			arrived[0] = true;
			build.run();
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

		Node header = header("Your wings. The equipped pair shows in-game for you and every other Velo player.", refresh, null);

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
