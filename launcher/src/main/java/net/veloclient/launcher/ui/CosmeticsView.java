package net.veloclient.launcher.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.auth.SkinFetcher;
import net.veloclient.launcher.data.AuraCatalog;
import net.veloclient.launcher.data.CapeEntry;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.GifFrames;
import net.veloclient.launcher.data.SkinLibrary;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.data.WingsCatalog;
import net.veloclient.launcher.social.StoreApi;
import net.veloclient.launcher.theme.LauncherTheme;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The Locker: everything you wear. Slots on the left (skin, cape, wings, aura) with what's on now, you in 3D in
 * the middle wearing the selection, and the slot's items on the right - pick one to preview it, then Equip.
 * Wings and auras keep their wear settings here; skins are added by player name or PNG and uploaded to your
 * Minecraft account. Loadouts save a whole look to switch back to in one click.
 */
public final class CosmeticsView {

	public interface Host {
		LauncherTheme activeTheme();

		void rebuild();

		default MinecraftSession session() {
			return null;
		}

		default void openStore() {
		}

		/** Runs {@code action} with a session whose Minecraft token is still valid (refreshing it first if needed). */
		default void withFreshSession(Consumer<MinecraftSession> action) {
			action.accept(session());
		}
	}

	public enum Slot { SKIN, CAPE, WINGS, AURA }

	private static Slot slot = Slot.CAPE;
	private static String selectedCape;
	private static String selectedWings;
	private static String selectedAura;
	/** Selected saved skin id; null = the skin on your account right now. */
	private static String selectedSkin;
	private static int capeFilter;
	private static final List<Wings3D.Motion> MOTION = new ArrayList<>(List.of(Wings3D.Motion.IDLE));

	private CosmeticsView() {
	}

	public static void showWingsTab() {
		slot = Slot.WINGS;
	}

	public static void showAurasTab() {
		slot = Slot.AURA;
	}

	public static void showSkinsTab() {
		slot = Slot.SKIN;
	}

	public static Node build(Stage owner, Host host) {
		installLoadoutSync();
		StackPane page = new StackPane();
		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> ScrollKeeper.setAll(page, content(owner, host, refresh[0]));
		page.getChildren().setAll(content(owner, host, refresh[0]));
		ensureWallet(refresh[0]);
		return page;
	}

	/** What you own comes from the server - fetch it once, then redraw. */
	private static void ensureWallet(Runnable refresh) {
		if (StoreApi.walletLoaded()) {
			return;
		}
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				StoreApi.wallet();
				javafx.application.Platform.runLater(refresh);
			} catch (StoreApi.StoreError ignored) {
				// Signed out / offline: nothing owned to show.
			}
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
	}

	private static Node content(Stage owner, Host host, Runnable refresh) {
		VBox slots = slotsColumn(host, refresh);
		StackPane stage = stage(host, refresh);
		HBox.setHgrow(stage, Priority.ALWAYS);
		Node panel = switch (slot) {
			case SKIN -> skinsPanel(owner, host, refresh);
			case CAPE -> capesPanel(owner, host, refresh);
			case WINGS -> wingsPanel(host, refresh);
			case AURA -> aurasPanel(host, refresh);
		};
		HBox body = new HBox(0, slots, stage, panel);
		body.getStyleClass().add("page");
		VBox.setVgrow(body, Priority.ALWAYS);
		return body;
	}

	// ---- Left: slots + loadouts ----

	private static VBox slotsColumn(Host host, Runnable refresh) {
		Label title = new Label("Locker");
		title.getStyleClass().add("page-title");
		VBox column = new VBox(8, title);
		VBox.setMargin(title, new Insets(0, 0, 10, 4));
		column.setMinWidth(250);
		column.setPrefWidth(270);
		column.setMaxWidth(290);
		column.setPadding(new Insets(0, 22, 0, 0));

		CapeEntry cape = CapeLibrary.equippedCapeId().flatMap(id -> CapeLibrary.listAll().stream().filter(c -> c.id().equals(id)).findFirst()).orElse(null);
		WingsCatalog.WingType wings = WingsCatalog.byId(WingsCatalog.equipped() == null ? "" : WingsCatalog.equipped()).orElse(null);
		AuraCatalog.AuraType aura = AuraCatalog.byId(AuraCatalog.equipped() == null ? "" : AuraCatalog.equipped()).orElse(null);
		MinecraftSession session = host.session();
		column.getChildren().add(slotButton(Slot.SKIN, "SKIN", session == null ? "Sign in" : session.username(),
				session == null ? Icons.icon("skin", 22, Color.web("#8A8A95")) : Heads.head(session.uuid(), session.username(), 48), refresh));
		column.getChildren().add(slotButton(Slot.CAPE, "CAPE", cape == null ? "None" : cape.name(), capeThumb(cape, 48), refresh));
		column.getChildren().add(slotButton(Slot.WINGS, "WINGS", wings == null ? "None" : wings.name(), wingsThumb(wings, 48), refresh));
		column.getChildren().add(slotButton(Slot.AURA, "AURA", aura == null ? "None" : aura.name(), auraThumb(aura, 48), refresh));
		Region grow = new Region();
		VBox.setVgrow(grow, Priority.ALWAYS);
		column.getChildren().addAll(grow, loadoutsCard(refresh));
		return column;
	}

	private static Node slotButton(Slot target, String kind, String value, Node thumb, Runnable refresh) {
		StackPane thumbBox = new StackPane(thumb);
		thumbBox.setMinSize(52, 52);
		thumbBox.setMaxSize(52, 52);
		thumbBox.setStyle("-fx-background-color: -velo-surface-2; -fx-background-radius: 11;");
		Rectangle clip = new Rectangle(52, 52);
		clip.setArcWidth(22);
		clip.setArcHeight(22);
		thumbBox.setClip(clip);
		Label kindLabel = new Label(kind);
		kindLabel.setStyle("-fx-text-fill: -velo-text-3; -fx-font-family: 'Inter'; -fx-font-weight: bold; -fx-font-size: 10px;");
		Label valueLabel = new Label(value);
		valueLabel.getStyleClass().add("t-strong");
		valueLabel.setStyle("-fx-font-size: 14px;" + (value.equals("None") ? " -fx-text-fill: -velo-text-3;" : ""));
		VBox text = new VBox(3, kindLabel, valueLabel);
		text.setMinWidth(0);
		HBox row = new HBox(14, thumbBox, text);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(9));
		boolean active = slot == target;
		row.setStyle("-fx-background-radius: 14; -fx-border-radius: 14; -fx-cursor: hand; " + (active
				? "-fx-background-color: -velo-surface-2; -fx-border-color: -velo-accent-line; -fx-border-width: 1;"
				: "-fx-background-color: transparent; -fx-border-color: -velo-hairline;"));
		if (active) {
			Region bar = new Region();
			bar.setMinWidth(3);
			bar.setMaxWidth(3);
			bar.setMinHeight(32);
			bar.setStyle("-fx-background-color: -velo-accent-text; -fx-background-radius: 2;");
			row.getChildren().add(0, bar);
			HBox.setMargin(bar, new Insets(0, -6, 0, -6));
		}
		row.getStyleClass().add("motion-card");
		row.setOnMouseClicked(e -> {
			slot = target;
			refresh.run();
		});
		return row;
	}

	private static Node capeThumb(CapeEntry cape, double size) {
		if (cape == null) {
			return Icons.icon("cape", 22, Color.web("#5A5A63"));
		}
		StackPane holder = new StackPane();
		CosmeticUi.framesAsync(cape, frames -> {
			if (!frames.isEmpty()) {
				ImageView view = new ImageView(CosmeticUi.thumbnail("lib:" + cape.id(), frames, 120, 120));
				view.setFitHeight(size);
				view.setPreserveRatio(true);
				holder.getChildren().setAll(view);
			}
		});
		return holder;
	}

	private static Node wingsThumb(WingsCatalog.WingType wings, double size) {
		if (wings == null) {
			return Icons.icon("wings", 22, Color.web("#5A5A63"));
		}
		ImageView view = new ImageView(UiImages.wingPreview(wings.id()));
		view.setFitHeight(size - 6);
		view.setPreserveRatio(true);
		return view;
	}

	private static Node auraThumb(AuraCatalog.AuraType aura, double size) {
		if (aura == null) {
			return Icons.icon("aura", 22, Color.web("#5A5A63"));
		}
		ImageView view = new ImageView(AuraFx.snapshot(aura.kind(), AuraCatalog.settings(aura.id()), size * 1.3, size));
		view.setFitWidth(size * 1.3);
		view.setFitHeight(size);
		return view;
	}

	// ---- Loadouts: whole looks to switch back to ----

	/**
	 * A saved look: what's worn in every slot plus how - the wings' size/position/flap and the aura's colour, size,
	 * speed and density - so switching looks brings each one back exactly (older loadouts have no settings: kept as they are).
	 */
	private record Loadout(String name, String cape, String wings, String aura, WingsCatalog.Settings wingsSettings,
			AuraEffects.Settings auraSettings) {
		static Loadout current(String name) {
			String wings = WingsCatalog.equipped();
			String aura = AuraCatalog.equipped();
			return new Loadout(name, CapeLibrary.equippedCapeId().orElse(null), wings, aura,
					wings == null ? null : WingsCatalog.settings(wings), aura == null ? null : AuraCatalog.settings(aura));
		}

		boolean isWorn() {
			String wornWings = WingsCatalog.equipped();
			String wornAura = AuraCatalog.equipped();
			return java.util.Objects.equals(cape, CapeLibrary.equippedCapeId().orElse(null)) && java.util.Objects.equals(wings, wornWings)
					&& java.util.Objects.equals(aura, wornAura)
					&& (wingsSettings == null || wings == null || wingsSettings.equals(WingsCatalog.settings(wings)))
					&& (auraSettings == null || aura == null || auraSettings.equals(AuraCatalog.settings(aura)));
		}

		void wear() {
			if (cape == null) {
				CapeLibrary.unequip();
			} else {
				CapeLibrary.equip(cape);
			}
			WingsCatalog.equip(wings);
			AuraCatalog.equip(aura);
			if (wings != null && wingsSettings != null) {
				WingsCatalog.setSettings(wings, wingsSettings);
			}
			if (aura != null && auraSettings != null) {
				AuraCatalog.setSettings(aura, auraSettings);
			}
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The loadout being worn (picked or saved last); changes to its items' settings are saved into it. */
	private static String activeLoadout;
	private static boolean syncInstalled;

	private static Path activeLoadoutFile() {
		return VeloPaths.config().resolve("loadout-active.txt");
	}

	private static String activeLoadout() {
		if (activeLoadout == null) {
			try {
				activeLoadout = Files.exists(activeLoadoutFile()) ? Files.readString(activeLoadoutFile(), StandardCharsets.UTF_8).strip() : "";
			} catch (java.io.IOException e) {
				activeLoadout = "";
			}
		}
		return activeLoadout;
	}

	private static void setActiveLoadout(String name) {
		activeLoadout = name == null ? "" : name;
		try {
			Files.writeString(activeLoadoutFile(), activeLoadout, StandardCharsets.UTF_8);
		} catch (java.io.IOException ignored) {
			// Only the live sync misses it after a restart.
		}
	}

	/**
	 * A colour, size or speed changed: if it belongs to an item of the loadout you're wearing, that loadout takes the
	 * new value - so every loadout keeps its own colours and settings, and switching brings them back.
	 */
	private static void syncActiveLoadout() {
		String name = activeLoadout();
		if (name.isEmpty()) {
			return;
		}
		List<Loadout> list = loadouts();
		for (int i = 0; i < list.size(); i++) {
			Loadout l = list.get(i);
			if (l.name().equals(name)) {
				boolean sameItems = java.util.Objects.equals(l.cape(), CapeLibrary.equippedCapeId().orElse(null))
						&& java.util.Objects.equals(l.wings(), WingsCatalog.equipped()) && java.util.Objects.equals(l.aura(), AuraCatalog.equipped());
				if (sameItems) {
					list.set(i, Loadout.current(name));
					saveLoadouts(list);
				}
				return;
			}
		}
	}

	private static void installLoadoutSync() {
		if (!syncInstalled) {
			syncInstalled = true;
			WingsCatalog.SETTINGS_LISTENERS.add(CosmeticsView::syncActiveLoadout);
			AuraCatalog.SETTINGS_LISTENERS.add(CosmeticsView::syncActiveLoadout);
		}
	}

	private static Path loadoutsFile() {
		return VeloPaths.config().resolve("loadouts.json");
	}

	private static List<Loadout> loadouts() {
		try {
			if (Files.exists(loadoutsFile())) {
				List<Loadout> list = GSON.fromJson(Files.readString(loadoutsFile(), StandardCharsets.UTF_8), new TypeToken<List<Loadout>>() {
				}.getType());
				return list == null ? new ArrayList<>() : new ArrayList<>(list);
			}
		} catch (Exception ignored) {
			// Unreadable: start over.
		}
		return new ArrayList<>();
	}

	private static void saveLoadouts(List<Loadout> list) {
		try {
			Files.writeString(loadoutsFile(), GSON.toJson(list), StandardCharsets.UTF_8);
		} catch (java.io.IOException ignored) {
			// Not fatal.
		}
	}

	private static Node loadoutsCard(Runnable refresh) {
		Label title = new Label("Loadouts");
		title.getStyleClass().add("t-strong");
		FlowPane chips = new FlowPane(6, 6);
		List<Loadout> list = loadouts();
		for (Loadout loadout : list) {
			boolean current = loadout.isWorn();
			Button chip = new Button(loadout.name());
			chip.getStyleClass().setAll("button", "fchip");
			if (current) {
				chip.getStyleClass().add("fchip-active");
			}
			chip.setTooltip(new Tooltip("Wear this look · right-click to update or delete"));
			chip.setOnAction(e -> {
				// Not synced while switching: applying B's colours mustn't overwrite A when both use the same items.
				setActiveLoadout("");
				loadout.wear();
				setActiveLoadout(loadout.name());
				// Show the look just put on, not what was being browsed before.
				selectedCape = null;
				selectedWings = null;
				selectedAura = null;
				Toasts.show(chip, "\"" + loadout.name() + "\" equipped");
				refresh.run();
			});
			javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
			javafx.scene.control.MenuItem delete = new javafx.scene.control.MenuItem("Delete \"" + loadout.name() + "\"");
			delete.setOnAction(e -> {
				List<Loadout> next = loadouts();
				next.removeIf(l -> l.name().equals(loadout.name()));
				saveLoadouts(next);
				refresh.run();
			});
			javafx.scene.control.MenuItem update = new javafx.scene.control.MenuItem("Update with what I'm wearing now");
			update.setOnAction(e -> {
				List<Loadout> next = loadouts();
				next.replaceAll(l -> l.name().equals(loadout.name()) ? Loadout.current(loadout.name()) : l);
				saveLoadouts(next);
				Toasts.show(chip, "\"" + loadout.name() + "\" updated");
				refresh.run();
			});
			menu.getItems().addAll(update, delete);
			chip.setContextMenu(menu);
			chips.getChildren().add(chip);
		}
		Button save = new Button("Save current");
		save.getStyleClass().setAll("button", "fchip");
		save.setGraphic(Icons.icon("plus", 12, Color.web("#A9A9B3"), 2.4));
		save.setOnAction(e -> {
			TextInputDialog dialog = new TextInputDialog("Look " + (list.size() + 1));
			dialog.setTitle("Save loadout");
			dialog.setHeaderText("Name this look - cape, wings and aura, with their sizes, colours and motion");
			DialogStyling.apply(dialog);
			dialog.showAndWait().map(String::strip).filter(n -> !n.isEmpty()).ifPresent(name -> {
				List<Loadout> next = loadouts();
				next.removeIf(l -> l.name().equalsIgnoreCase(name));
				next.add(Loadout.current(name));
				setActiveLoadout(name);
				saveLoadouts(next);
				refresh.run();
			});
		});
		chips.getChildren().add(save);
		VBox card = new VBox(10, title, chips);
		card.getStyleClass().add("card");
		card.setPadding(new Insets(14));
		return card;
	}

	// ---- Middle: you, wearing the selection ----

	private static StackPane stage(Host host, Runnable refresh) {
		String glow = switch (slot) {
			case WINGS -> WingsCatalog.byId(selectedWingsId()).map(WingsCatalog.WingType::accent).orElse("#5aa9ff");
			case AURA -> AuraCatalog.byId(selectedAuraId()).map(AuraCatalog.AuraType::accent).orElse("#bca6ff");
			case SKIN -> "#bca6ff";
			case CAPE -> "#5ac8ff";
		};
		StackPane stage = new StackPane();
		stage.setMinWidth(300);
		stage.setStyle("-fx-background-color: radial-gradient(center 50% 58%, radius 50%, " + glow + "30 0%, transparent 100%), derive(-velo-background, 3%);"
				+ " -fx-border-color: transparent -velo-hairline transparent -velo-hairline; -fx-border-width: 0 1 0 1;");
		StackPane viewerBox = new StackPane();
		viewerBox.setMinSize(260, 360);
		StackPane.setMargin(viewerBox, new Insets(40, 0, 70, 0));
		stage.getChildren().add(viewerBox);
		javafx.scene.shape.Ellipse floor = new javafx.scene.shape.Ellipse(140, 22);
		floor.setFill(new javafx.scene.paint.RadialGradient(0, 0, 0.5, 0.5, 0.5, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
				new javafx.scene.paint.Stop(0, Color.web(glow, 0.3)), new javafx.scene.paint.Stop(1, Color.web(glow, 0))));
		floor.setMouseTransparent(true);
		StackPane.setAlignment(floor, Pos.BOTTOM_CENTER);
		StackPane.setMargin(floor, new Insets(0, 0, 76, 0));
		stage.getChildren().add(0, floor);

		if (host.session() == null) {
			Label signIn = new Label("Sign in to see everything on your own skin");
			signIn.getStyleClass().add("t-muted");
			viewerBox.getChildren().add(signIn);
		} else {
			Label synced = new Label("Synced · other Velo players see this");
			synced.getStyleClass().addAll("pill", "pill-green");
			synced.setMinWidth(Region.USE_PREF_SIZE);
			// On a narrow stage the look toggle needs the room: the pill only shows when both fit.
			synced.visibleProperty().bind(stage.widthProperty().greaterThan(560));
			StackPane.setAlignment(synced, Pos.TOP_CENTER);
			StackPane.setMargin(synced, new Insets(18, 0, 0, 0));
			stage.getChildren().add(synced);
		}
		HBox bottom = new HBox(10);
		bottom.setAlignment(Pos.CENTER);
		bottom.setPickOnBounds(false);
		bottom.setMaxHeight(Region.USE_PREF_SIZE);
		StackPane.setAlignment(bottom, Pos.BOTTOM_CENTER);
		StackPane.setMargin(bottom, new Insets(0, 0, 20, 0));
		stage.getChildren().add(bottom);

		// Full look (default): everything you wear, with what you're browsing in this tab swapped in.
		HBox lookToggle = new HBox(2);
		lookToggle.getStyleClass().add("segment-bar");
		String only = switch (slot) {
			case CAPE -> "Cape only";
			case WINGS -> "Wings only";
			case AURA -> "Aura only";
			case SKIN -> "Skin only";
		};
		for (boolean full : new boolean[] {true, false}) {
			Button b = new Button(full ? "Full look" : only);
			b.getStyleClass().setAll("button", "segmented-button", "no-motion");
			b.setMinWidth(Region.USE_PREF_SIZE);
			b.setStyle("-fx-padding: 6 12 6 12;");
			if (full == fullLook) {
				b.getStyleClass().add("segmented-button-active");
			}
			b.setOnAction(e -> {
				if (fullLook != full) {
					fullLook = full;
					refresh.run();
				}
			});
			lookToggle.getChildren().add(b);
		}
		lookToggle.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		StackPane.setAlignment(lookToggle, Pos.TOP_LEFT);
		StackPane.setMargin(lookToggle, new Insets(14, 0, 0, 14));
		if (host.session() != null) {
			stage.getChildren().add(lookToggle);
		}

		if (fullLook && host.session() != null) {
			outfitViewer(host, viewerBox, bottom);
		} else switch (slot) {
			case CAPE -> capeViewer(host, viewerBox);
			case WINGS -> wingsViewer(host, viewerBox, bottom);
			case AURA -> {
				AuraCatalog.AuraType aura = AuraCatalog.byId(selectedAuraId()).orElse(null);
				if (aura != null) {
					AuraEffects.Settings[] current = {AuraCatalog.settings(aura.id())};
					auraLive[0] = s -> current[0] = s;
					Node auraStage = AuraDetailView.stage(host.session(), aura.kind(), () -> new AuraFx.Shown(aura.id(), current[0]), 340, 420);
					if (auraStage instanceof Region region) {
						region.getStyleClass().removeAll("cape-stage", "showcase-stage");
						region.setStyle("-fx-background-color: transparent;");
					}
					viewerBox.getChildren().setAll(auraStage);
				}
			}
			case SKIN -> {
				SkinLibrary.Skin skin = SkinLibrary.all().stream().filter(s -> s.id().equals(selectedSkin)).findFirst().orElse(null);
				if (skin != null) {
					Node viewer = SkinsSection.viewer(skin);
					if (viewer != null) {
						viewerBox.getChildren().setAll(viewer);
					}
				} else if (host.session() != null) {
					viewerBox.getChildren().setAll(CosmeticUi.spinner(30));
					CosmeticUi.skin(host.session(), data -> {
						Node viewer = data == null ? null : PlayerSkin3DView.createViewer(data.pngBytes(), data.slim(), null, true, 25, false);
						if (viewer != null) {
							viewerBox.getChildren().setAll(viewer);
						}
					});
				}
				Label hint = new Label(selectedSkin == null ? "The skin on your account now" : "Preview - Equip uploads it to your account");
				hint.getStyleClass().add("t-muted");
				bottom.getChildren().add(hint);
			}
		}
		if (bottom.getChildren().isEmpty()) {
			Label hint = new Label("Drag to rotate");
			hint.getStyleClass().add("t-muted");
			bottom.getChildren().add(hint);
		}
		return stage;
	}

	/** Whether the preview shows the whole look (the default) or only the open tab's item. */
	private static boolean fullLook = true;

	/**
	 * The whole look on your skin: the equipped cape, wings and aura, with the item you're looking at in the open
	 * tab in place of that slot's (and the picked library skin on the Skin tab).
	 */
	private static void outfitViewer(Host host, StackPane box, HBox bottom) {
		String capeId = slot == Slot.CAPE ? selectedCapeId() : CapeLibrary.equippedCapeId().orElse(null);
		String wingsId = slot == Slot.WINGS ? selectedWingsId() : WingsCatalog.equipped();
		if (wingsId != null && (wingsId.isEmpty() || WingsCatalog.byId(wingsId).isEmpty())) {
			wingsId = null;
		}
		String auraId = slot == Slot.AURA ? selectedAuraId() : AuraCatalog.equipped();
		if (auraId != null && (auraId.isEmpty() || AuraCatalog.byId(auraId).isEmpty())) {
			auraId = null;
		}
		CapeEntry cape = capeId == null ? null : CapeLibrary.listAll().stream().filter(c -> c.id().equals(capeId)).findFirst().orElse(null);
		SkinLibrary.Skin librarySkin = slot == Slot.SKIN && selectedSkin != null
				? SkinLibrary.all().stream().filter(s -> s.id().equals(selectedSkin)).findFirst().orElse(null) : null;
		wingsModel[0] = null;
		box.getChildren().setAll(CosmeticUi.spinner(30));

		List<GifFrames.Frame>[] frames = new List[1];
		byte[][] png = new byte[1][];
		boolean[] slim = new boolean[1];
		boolean[] arrived = {cape == null, false};
		String wings = wingsId;
		String aura = auraId;
		Runnable build = () -> {
			if (!arrived[0] || !arrived[1]) {
				return;
			}
			if (png[0] == null) {
				box.getChildren().clear();
				return;
			}
			List<GifFrames.Frame> f = frames[0] == null || frames[0].isEmpty() ? null : frames[0];
			// Facing away a little when wings or a cape are on, so the back is in view.
			double yaw = wings != null || f != null ? 155 : 25;
			Node viewer = PlayerSkin3DView.createViewer(png[0], slim[0], f, true, yaw, true, wings == null ? null : model -> {
				Wings3D w = new Wings3D(model);
				wingsModel[0] = w.settings(WingsCatalog.settings(wings)).motion(MOTION.get(0));
				w.show(wings);
			});
			if (viewer == null) {
				box.getChildren().clear();
				return;
			}
			if (aura != null) {
				AuraEffects.Settings[] current = {AuraCatalog.settings(aura)};
				auraLive[0] = s -> current[0] = s;
				AuraFx.attach(viewer, () -> new AuraFx.Shown(aura, current[0]));
			}
			viewer.setOpacity(0);
			box.getChildren().setAll(viewer);
			javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), viewer);
			fade.setToValue(1);
			fade.play();
		};
		if (cape != null) {
			CosmeticUi.framesAsync(cape, f -> {
				frames[0] = f;
				arrived[0] = true;
				build.run();
			});
		}
		if (librarySkin != null) {
			java.util.concurrent.CompletableFuture.supplyAsync(() -> SkinsSection.pngBytes(librarySkin),
					java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()).thenAccept(bytes -> javafx.application.Platform.runLater(() -> {
						png[0] = bytes;
						slim[0] = librarySkin.slim();
						arrived[1] = true;
						build.run();
					}));
		} else {
			CosmeticUi.skin(host.session(), data -> {
				png[0] = data == null ? null : data.pngBytes();
				slim[0] = data != null && data.slim();
				arrived[1] = true;
				build.run();
			});
		}
		if (wings != null) {
			bottom.getChildren().add(motionChips());
		}
	}

	/** Idle / flap / glide... for the wings in the preview. */
	private static Node motionChips() {
		HBox motions = new HBox(2);
		motions.getStyleClass().add("segment-bar");
		List<Button> chips = new ArrayList<>();
		for (Wings3D.Motion m : Wings3D.Motion.values()) {
			Button chip = new Button(m.name().charAt(0) + m.name().substring(1).toLowerCase(Locale.ROOT));
			chip.getStyleClass().setAll("button", "segmented-button");
			chip.setMinWidth(Region.USE_PREF_SIZE);
			chip.setStyle("-fx-padding: 6 10 6 10;");
			if (m == MOTION.get(0)) {
				chip.getStyleClass().add("segmented-button-active");
			}
			chip.setOnAction(e -> {
				MOTION.set(0, m);
				chips.forEach(c -> c.getStyleClass().remove("segmented-button-active"));
				chip.getStyleClass().add("segmented-button-active");
				if (wingsModel[0] != null) {
					wingsModel[0].motion(m);
				}
			});
			chips.add(chip);
			motions.getChildren().add(chip);
		}
		return motions;
	}

	/** Lets the aura settings sliders move the live preview. */
	private static final Consumer<AuraEffects.Settings>[] auraLive = new Consumer[] {s -> {
	}};
	private static final Wings3D[] wingsModel = new Wings3D[1];

	private static void capeViewer(Host host, StackPane box) {
		CapeEntry selected = CapeLibrary.listAll().stream().filter(c -> c.id().equals(selectedCapeId())).findFirst().orElse(null);
		if (host.session() == null) {
			return;
		}
		box.getChildren().setAll(CosmeticUi.spinner(30));
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
				box.getChildren().setAll(viewer);
				javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), viewer);
				fade.setToValue(1);
				fade.play();
			} else {
				box.getChildren().clear();
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
	}

	private static void wingsViewer(Host host, StackPane box, HBox bottom) {
		WingsCatalog.WingType selected = WingsCatalog.byId(selectedWingsId()).orElse(null);
		wingsModel[0] = null;
		if (selected == null) {
			return;
		}
		ImageView still = new ImageView(UiImages.wingPreview(selected.id()));
		still.setFitHeight(300);
		still.setPreserveRatio(true);
		box.getChildren().setAll(still);
		CosmeticUi.skin(host.session(), skin -> {
			Node viewer = skin == null ? null : PlayerSkin3DView.createWingsShowcase(skin.pngBytes(), skin.slim(), w -> {
				wingsModel[0] = w.settings(WingsCatalog.settings(selected.id())).motion(MOTION.get(0));
				w.show(selected.id());
			});
			if (viewer != null) {
				box.getChildren().setAll(viewer);
			}
		});
		bottom.getChildren().add(motionChips());
	}

	// ---- Right: the slot's items ----

	private static VBox panelShell(String title, int count, Node... actions) {
		Label heading = new Label(title);
		heading.getStyleClass().add("t-title-sm");
		Label countLabel = new Label(String.valueOf(count));
		countLabel.getStyleClass().add("t-muted");
		countLabel.setStyle("-fx-font-size: 14px;");
		HBox head = new HBox(8, heading, countLabel);
		head.setAlignment(Pos.BASELINE_LEFT);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		head.getChildren().add(spacer);
		head.getChildren().addAll(actions);
		VBox panel = new VBox(14, head);
		panel.setMinWidth(380);
		panel.setPrefWidth(520);
		panel.setMaxWidth(560);
		panel.setPadding(new Insets(0, 0, 0, 24));
		return panel;
	}

	private static ScrollPane scroll(Node content) {
		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		return scroll;
	}

	/** A compact item cell: art, name, a check when worn, a ring when selected. */
	private static VBox cell(Node art, String accent, String name, boolean equipped, boolean selected, Runnable onClick) {
		StackPane artBox = new StackPane(art);
		artBox.setMinHeight(104);
		artBox.setPrefHeight(104);
		artBox.setStyle("-fx-background-color: radial-gradient(center 50% 65%, radius 70%, " + accent + "40 0%, transparent 100%), -velo-surface-2;"
				+ " -fx-background-radius: 10 10 0 0;");
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(artBox.widthProperty());
		clip.heightProperty().bind(artBox.heightProperty());
		artBox.setClip(clip);
		Label label = new Label(name);
		label.getStyleClass().add("t-strong");
		label.setStyle("-fx-font-size: 12px;");
		label.setMinWidth(0);
		HBox.setHgrow(label, Priority.ALWAYS);
		label.setMaxWidth(Double.MAX_VALUE);
		HBox footer = new HBox(6, label);
		footer.setAlignment(Pos.CENTER_LEFT);
		if (equipped) {
			footer.getChildren().add(Icons.icon("check", 14, Color.web("#3DDC97"), 2.8));
		}
		footer.setPadding(new Insets(8, 10, 10, 10));
		VBox cell = new VBox(artBox, footer);
		cell.setStyle("-fx-background-color: -velo-surface; -fx-background-radius: 12; -fx-border-radius: 12; -fx-cursor: hand; -fx-border-width: 2;"
				+ " -fx-border-color: " + (equipped ? "-velo-green" : selected ? "-velo-text-2" : "rgba(255,255,255,0.06)") + ";");
		cell.getStyleClass().add("motion-card");
		cell.setOnMouseClicked(e -> onClick.run());
		return cell;
	}

	/** The selected item's name with Equip / Take off (and Delete when that applies). */
	private static HBox actionBar(String name, boolean equipped, Runnable toggle, Runnable delete) {
		Label label = new Label(name);
		label.getStyleClass().add("t-heading");
		label.setMinWidth(0);
		HBox.setHgrow(label, Priority.ALWAYS);
		label.setMaxWidth(Double.MAX_VALUE);
		Button equip = new Button(equipped ? "Take off" : "Equip");
		equip.getStyleClass().add(equipped ? "glass-button" : "primary-button");
		equip.setMinWidth(Region.USE_PREF_SIZE);
		equip.setOnAction(e -> toggle.run());
		HBox bar = new HBox(10, label, equip);
		if (delete != null) {
			Button remove = new Button();
			remove.setGraphic(Icons.icon("trash", 16, Color.web("#FF7A7E")));
			remove.getStyleClass().addAll("danger-button", "icon-button");
			remove.setTooltip(new Tooltip("Delete"));
			remove.setOnAction(e -> delete.run());
			bar.getChildren().add(remove);
		}
		bar.setAlignment(Pos.CENTER_LEFT);
		bar.getStyleClass().add("card-2");
		bar.setPadding(new Insets(12, 12, 12, 16));
		return bar;
	}

	private static Button storeLink(Host host, String text) {
		Button get = new Button(text);
		get.getStyleClass().addAll("ghost-button", "button-sm");
		get.setGraphic(Icons.icon("store", 14, Color.web("#A9A9B3")));
		get.setOnAction(e -> host.openStore());
		return get;
	}

	// Capes

	private static String selectedCapeId() {
		List<CapeEntry> capes = CapeLibrary.listAll();
		String equipped = CapeLibrary.equippedCapeId().orElse(null);
		if (selectedCape == null || capes.stream().noneMatch(c -> c.id().equals(selectedCape))) {
			selectedCape = equipped != null ? equipped : capes.isEmpty() ? null : capes.get(0).id();
		}
		return selectedCape;
	}

	private static Node capesPanel(Stage owner, Host host, Runnable refresh) {
		List<CapeEntry> capes = CapeLibrary.listAll();
		String equipped = CapeLibrary.equippedCapeId().orElse(null);
		String selected = selectedCapeId();
		Button importButton = new Button("Import");
		importButton.setGraphic(Icons.icon("upload", 14, Color.web("#E4E4E8")));
		importButton.getStyleClass().add("button-sm");
		importButton.setOnAction(e -> CapeImportDialog.show(owner).ifPresent(result -> {
			try {
				CapeLibrary.importPng(result.name(), result.pngFile(), result.preset());
				refresh.run();
			} catch (Exception ex) {
				error(owner, "Import failed", ex.getMessage());
			}
		}));
		VBox panel = panelShell("Capes", capes.size(), importButton, storeLink(host, "Get more"));
		HBox filters = new HBox(6);
		String[] names = {"All", "Animated", "Static"};
		for (int i = 0; i < names.length; i++) {
			int index = i;
			Button chip = new Button(names[i]);
			chip.getStyleClass().setAll("button", "fchip");
			if (capeFilter == i) {
				chip.getStyleClass().add("fchip-active");
			}
			chip.setOnAction(e -> {
				capeFilter = index;
				refresh.run();
			});
			filters.getChildren().add(chip);
		}
		FlowPane grid = StoreTiles.grid(118, 10);
		grid.getChildren().add(cell(Icons.icon("close", 26, Color.web("#5A5A63")), "#000000", "No cape", equipped == null, selected == null, () -> {
			CapeLibrary.unequip();
			selectedCape = null;
			refresh.run();
		}));
		for (CapeEntry cape : capes) {
			if (capeFilter == 1 && !cape.animated() || capeFilter == 2 && cape.animated()) {
				continue;
			}
			StackPane art = new StackPane(CosmeticUi.spinner(20));
			VBox cellNode = cell(art, "#5ac8ff", cape.name(), cape.id().equals(equipped), cape.id().equals(selected), () -> {
				selectedCape = cape.id();
				refresh.run();
			});
			CosmeticUi.framesAsync(cape, frames -> {
				if (frames.isEmpty()) {
					art.getChildren().clear();
					return;
				}
				ImageView view = new ImageView(CosmeticUi.thumbnail("lib:" + cape.id(), frames, 120, 120));
				view.setFitHeight(96);
				view.setPreserveRatio(true);
				art.getChildren().setAll(view);
				CosmeticUi.animate(cellNode, view, "lib:" + cape.id(), frames, 120, 120, false);
			});
			grid.getChildren().add(cellNode);
		}
		panel.getChildren().addAll(filters, scroll(grid));
		CapeEntry chosen = capes.stream().filter(c -> c.id().equals(selected)).findFirst().orElse(null);
		if (chosen != null) {
			boolean isEquipped = chosen.id().equals(equipped);
			panel.getChildren().add(actionBar(chosen.name(), isEquipped, () -> {
				if (isEquipped) {
					CapeLibrary.unequip();
				} else {
					CapeLibrary.equip(chosen.id());
					Toasts.show(panel, "Cape equipped");
				}
				refresh.run();
			}, () -> {
				try {
					CapeLibrary.delete(chosen);
					selectedCape = null;
					refresh.run();
				} catch (Exception ex) {
					error(owner, "Delete failed", ex.getMessage());
				}
			}));
		}
		return panel;
	}

	// Wings

	private static String selectedWingsId() {
		List<WingsCatalog.WingType> owned = WingsCatalog.all().stream().filter(w -> StoreOwnership.owns(w.itemId())).toList();
		String equipped = WingsCatalog.equipped();
		if (selectedWings == null || owned.stream().noneMatch(w -> w.id().equals(selectedWings))) {
			selectedWings = equipped != null && owned.stream().anyMatch(w -> w.id().equals(equipped)) ? equipped
					: owned.isEmpty() ? null : owned.get(0).id();
		}
		return selectedWings == null ? "" : selectedWings;
	}

	private static Node wingsPanel(Host host, Runnable refresh) {
		List<WingsCatalog.WingType> owned = WingsCatalog.all().stream().filter(w -> StoreOwnership.owns(w.itemId())).toList();
		String equipped = WingsCatalog.equipped();
		String selected = selectedWingsId();
		VBox panel = panelShell("Wings", owned.size(), storeLink(host, "Get more"));
		FlowPane grid = StoreTiles.grid(118, 10);
		for (WingsCatalog.WingType type : owned) {
			ImageView picture = new ImageView(UiImages.wingPreview(type.id()));
			picture.setFitHeight(92);
			picture.setPreserveRatio(true);
			grid.getChildren().add(cell(picture, type.accent(), type.name(), type.id().equals(equipped), type.id().equals(selected), () -> {
				selectedWings = type.id();
				refresh.run();
			}));
		}
		VBox content = new VBox(16, grid);
		if (owned.isEmpty()) {
			content.getChildren().setAll(emptyState("No wings yet", "Every pair is animated and everyone with Velo sees them.", host, "Browse wings"));
		}
		WingsCatalog.WingType chosen = owned.stream().filter(w -> w.id().equals(selected)).findFirst().orElse(null);
		if (chosen != null) {
			content.getChildren().add(wingsSettings(chosen, refresh));
		}
		panel.getChildren().add(scroll(content));
		if (chosen != null) {
			boolean isEquipped = chosen.id().equals(equipped);
			panel.getChildren().add(actionBar(chosen.name(), isEquipped, () -> {
				WingsCatalog.equip(isEquipped ? null : chosen.id());
				if (!isEquipped) {
					Toasts.show(panel, "Wings equipped - they show in your next game");
				}
				refresh.run();
			}, null));
		}
		return panel;
	}

	/** The game's wear settings for this pair: changes show on the 3D model right away and are saved when you let go. */
	private static Node wingsSettings(WingsCatalog.WingType type, Runnable refresh) {
		WingsCatalog.Settings[] current = {WingsCatalog.settings(type.id())};
		VBox box = new VBox(10);
		Label title = new Label("How you wear them");
		title.getStyleClass().add("t-strong");
		Button reset = new Button("Reset");
		reset.getStyleClass().addAll("ghost-button", "button-sm");
		reset.setOnAction(e -> {
			WingsCatalog.setSettings(type.id(), null);
			refresh.run();
		});
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox head = new HBox(title, spacer, reset);
		head.setAlignment(Pos.CENTER_LEFT);
		box.getChildren().add(head);
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
			Slider slider = new Slider((double) row[1], (double) row[2], (double) row[3]);
			box.getChildren().add(sliderRow((String) row[0], slider, () -> String.format(Locale.ROOT, (String) row[4], slider.getValue() * (double) row[5])));
			slider.valueProperty().addListener((obs, o, n) -> {
				WingsCatalog.Settings s = current[0];
				float v = n.floatValue();
				current[0] = new WingsCatalog.Settings(field == 0 ? v : s.scale(), field == 1 ? -v : s.offsetY(), field == 2 ? v : s.offsetZ(),
						field == 3 ? v : s.spread(), field == 4 ? v : s.flapSpeed(), field == 5 ? v : s.flapStrength());
				if (wingsModel[0] != null) {
					wingsModel[0].settings(current[0]);
				}
			});
			slider.valueChangingProperty().addListener((obs, was, now) -> {
				if (!now) {
					WingsCatalog.setSettings(type.id(), current[0]);
				}
			});
			slider.setOnMouseReleased(e -> WingsCatalog.setSettings(type.id(), current[0]));
		}
		Label note = new Label("Saved for the game - it picks them up the next time it starts.");
		note.getStyleClass().add("t-muted");
		note.setWrapText(true);
		box.getChildren().add(note);
		box.getStyleClass().add("card");
		box.setPadding(new Insets(16));
		return box;
	}

	private static Node sliderRow(String name, Slider slider, java.util.function.Supplier<String> value) {
		Label label = new Label(name);
		label.getStyleClass().add("t-body");
		label.setMinWidth(100);
		Label number = new Label(value.get());
		number.getStyleClass().add("t-mono");
		number.setMinWidth(56);
		number.setAlignment(Pos.CENTER_RIGHT);
		slider.valueProperty().addListener((o, a, b) -> number.setText(value.get()));
		HBox.setHgrow(slider, Priority.ALWAYS);
		HBox row = new HBox(12, label, slider, number);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	// Auras

	private static String selectedAuraId() {
		List<AuraCatalog.AuraType> owned = AuraCatalog.all().stream().filter(a -> AuraCatalog.owns(a.id())).toList();
		String equipped = AuraCatalog.equipped();
		if (selectedAura == null || owned.stream().noneMatch(a -> a.id().equals(selectedAura))) {
			selectedAura = equipped != null && owned.stream().anyMatch(a -> a.id().equals(equipped)) ? equipped
					: owned.isEmpty() ? null : owned.get(0).id();
		}
		return selectedAura == null ? "" : selectedAura;
	}

	private static Node aurasPanel(Host host, Runnable refresh) {
		List<AuraCatalog.AuraType> owned = AuraCatalog.all().stream().filter(a -> AuraCatalog.owns(a.id())).toList();
		String equipped = AuraCatalog.equipped();
		String selected = selectedAuraId();
		VBox panel = panelShell("Auras", owned.size(), storeLink(host, "Get more"));
		FlowPane grid = StoreTiles.grid(118, 10);
		for (AuraCatalog.AuraType aura : owned) {
			ImageView art = new ImageView(AuraFx.snapshot(aura.kind(), AuraCatalog.settings(aura.id()), 150, 104));
			art.setFitWidth(150);
			art.setFitHeight(104);
			grid.getChildren().add(cell(art, aura.accent(), aura.name(), aura.id().equals(equipped), aura.id().equals(selected), () -> {
				selectedAura = aura.id();
				refresh.run();
			}));
		}
		VBox content = new VBox(16, grid);
		if (owned.isEmpty()) {
			content.getChildren().setAll(emptyState("No auras yet", "Animated effects around you - your colour, size and speed.", host, "Browse auras"));
		}
		AuraCatalog.AuraType chosen = owned.stream().filter(a -> a.id().equals(selected)).findFirst().orElse(null);
		if (chosen != null) {
			Node settings = AuraDetailView.settingsPanel(chosen.id(), chosen.kind(), s -> auraLive[0].accept(s));
			VBox card = new VBox(settings);
			card.getStyleClass().add("card");
			card.setPadding(new Insets(16));
			content.getChildren().add(card);
		}
		panel.getChildren().add(scroll(content));
		if (chosen != null) {
			boolean isEquipped = chosen.id().equals(equipped);
			panel.getChildren().add(actionBar(chosen.name(), isEquipped, () -> {
				AuraCatalog.equip(isEquipped ? null : chosen.id());
				if (!isEquipped) {
					Toasts.show(panel, "Aura equipped - it shows in your next game");
				}
				refresh.run();
			}, null));
		}
		return panel;
	}

	// Skins

	private static Node skinsPanel(Stage owner, Host host, Runnable refresh) {
		List<SkinLibrary.Skin> skins = SkinLibrary.all();
		String equippedSkin = SkinLibrary.equippedId();
		VBox panel = panelShell("Skins", skins.size() + 1);
		Label status = new Label("Skins change on your Minecraft account. Rejoin a server to see the new one.");
		status.getStyleClass().add("t-muted");
		status.setWrapText(true);

		TextField player = new TextField();
		player.setPromptText("Copy a player's skin: username");
		HBox.setHgrow(player, Priority.ALWAYS);
		Button add = new Button("Add");
		add.getStyleClass().add("button-sm");
		Runnable addPlayer = () -> {
			String query = player.getText().strip();
			if (query.isEmpty()) {
				return;
			}
			status.setText("Looking up " + query + "...");
			SkinsSection.run(() -> SkinLibrary.addFromPlayer(query), skin -> {
				player.clear();
				selectedSkin = skin.id();
				refresh.run();
			}, status::setText);
		};
		add.setOnAction(e -> addPlayer.run());
		player.setOnAction(e -> addPlayer.run());
		CheckBox slim = new CheckBox("Slim");
		Button upload = new Button("Upload PNG");
		upload.setGraphic(Icons.icon("upload", 14, Color.web("#E4E4E8")));
		upload.getStyleClass().add("button-sm");
		upload.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Choose a skin PNG (64x64)");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Skin PNG", "*.png"));
			File file = chooser.showOpenDialog(owner);
			if (file != null) {
				String name = file.getName().replaceFirst("\\.png$", "");
				boolean isSlim = slim.isSelected();
				SkinsSection.run(() -> SkinLibrary.addPng(name, Files.readAllBytes(file.toPath()), isSlim), skin -> {
					selectedSkin = skin.id();
					refresh.run();
				}, status::setText);
			}
		});
		HBox addRow = new HBox(8, player, add, upload, slim);
		addRow.setAlignment(Pos.CENTER_LEFT);

		FlowPane grid = StoreTiles.grid(118, 10);
		StackPane currentArt = new StackPane();
		MinecraftSession session = host.session();
		if (session != null) {
			currentArt.getChildren().add(Heads.head(session.uuid(), session.username(), 64));
		}
		grid.getChildren().add(cell(currentArt, "#3ddc97", "On your account", equippedSkin == null, selectedSkin == null, () -> {
			selectedSkin = null;
			refresh.run();
		}));
		for (SkinLibrary.Skin skin : skins) {
			Image front = SkinsSection.front(skin);
			Node art = front == null ? Icons.icon("skin", 26, Color.web("#5A5A63")) : new ImageView(front);
			if (art instanceof ImageView view) {
				view.setFitHeight(96);
				view.setPreserveRatio(true);
			}
			grid.getChildren().add(cell(art, "#bca6ff", skin.name(), skin.id().equals(equippedSkin), skin.id().equals(selectedSkin), () -> {
				selectedSkin = skin.id();
				refresh.run();
			}));
		}
		panel.getChildren().addAll(addRow, scroll(grid), status);
		SkinLibrary.Skin chosen = skins.stream().filter(s -> s.id().equals(selectedSkin)).findFirst().orElse(null);
		if (chosen != null) {
			boolean isEquipped = chosen.id().equals(equippedSkin);
			HBox bar = actionBar(chosen.name() + (chosen.slim() ? "  ·  slim" : "  ·  classic"), isEquipped, () -> {
				if (isEquipped) {
					return;
				}
				if (host.session() == null) {
					status.setText("Sign in first.");
					return;
				}
				status.setText("Uploading " + chosen.name() + " to your Minecraft account...");
				host.withFreshSession(fresh -> SkinsSection.run(() -> {
					SkinLibrary.equip(chosen, fresh.minecraftAccessToken());
					return chosen;
				}, done -> {
					CosmeticUi.forgetSkin();
					Toasts.show(panel, "Skin changed to " + chosen.name());
					refresh.run();
				}, status::setText));
			}, () -> {
				try {
					SkinLibrary.delete(chosen);
					selectedSkin = null;
					refresh.run();
				} catch (Exception ex) {
					status.setText("Couldn't delete: " + ex.getMessage());
				}
			});
			if (isEquipped) {
				((Button) bar.getChildren().get(1)).setText("Wearing it");
				((Button) bar.getChildren().get(1)).setDisable(true);
			}
			panel.getChildren().add(bar);
		} else if (session != null) {
			Button save = new Button("Save a copy");
			save.getStyleClass().add("glass-button");
			save.setOnAction(e -> {
				save.setDisable(true);
				SkinsSection.run(() -> SkinLibrary.addFromPlayer(session.username()), skin -> {
					selectedSkin = skin.id();
					refresh.run();
				}, status::setText);
			});
			Label label = new Label("The skin on your account");
			label.getStyleClass().add("t-heading");
			HBox.setHgrow(label, Priority.ALWAYS);
			label.setMaxWidth(Double.MAX_VALUE);
			HBox bar = new HBox(10, label, save);
			bar.setAlignment(Pos.CENTER_LEFT);
			bar.getStyleClass().add("card-2");
			bar.setPadding(new Insets(12, 12, 12, 16));
			panel.getChildren().add(bar);
		}
		return panel;
	}

	private static Node emptyState(String title, String text, Host host, String button) {
		Label t = new Label(title);
		t.getStyleClass().add("t-heading");
		Label d = new Label(text);
		d.getStyleClass().add("t-muted");
		d.setWrapText(true);
		Button open = new Button(button);
		open.getStyleClass().add("primary-button");
		open.setOnAction(e -> host.openStore());
		VBox box = new VBox(10, t, d, open);
		box.setPadding(new Insets(28, 20, 28, 20));
		box.getStyleClass().add("dashed-tile");
		box.setAlignment(Pos.CENTER);
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
