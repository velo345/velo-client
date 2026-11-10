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
import net.veloclient.launcher.data.KillFxCatalog;
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

	public enum Slot { SKIN, CAPE, WINGS, AURA, NAME, EMOTES, KILLFX, WEAR, PETS }

	private static Slot slot = Slot.CAPE;
	/** Set by a slot click: -1 / 1 = the next build slides its panel in from above / below (the slots are a column). */
	private static int pendingSlide;
	private static String selectedCape;
	/*
	 * 2026-11 interaction: hover = the big preview shows it, left click = wear it, right click = a menu (wear / take off,
	 * edit, delete). The old "selected item + Equip bar" is gone. A click selects the item (rebuild) and asks the rebuild
	 * to run its action right away; a right click asks it for the menu instead.
	 */
	private static boolean equipRequested;
	private static double[] menuRequested;
	private static boolean equipNow;
	private static double[] menuNow;
	private static StackPane liveViewerBox;
	private static HBox liveBottom;
	private static Host liveHost;
	/** What the mouse is over in the catalog (shown on the preview instead of what you wear); "" = "none" cell. */
	private static String previewCape;
	private static String previewWings;
	private static String previewAura;
	private static final javafx.animation.PauseTransition HOVER_DEBOUNCE = new javafx.animation.PauseTransition(javafx.util.Duration.millis(170));
	private static String selectedWings;
	private static String selectedAura;
	/** The kill effect picked in the Kill FX tab ("" = none), null = the equipped one. */
	private static String selectedKillFx;
	/** The Bodywear tab: the slot being browsed and the item picked ("" = none, null = what's worn there). */
	private static String wearSlot = "head";
	private static String selectedWear;
	/** The emote playing on the stage, and the wheel slot (0-7) a click on an emote puts it in. */
	private static String selectedEmote;
	private static int selectedWheelSlot;
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

	public static void showNameStyleTab() {
		slot = Slot.NAME;
	}

	public static void showEmotesTab() {
		slot = Slot.EMOTES;
	}

	public static void showWearTab() {
		slot = Slot.WEAR;
	}

	public static void showKillFxTab() {
		slot = Slot.KILLFX;
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
		equipNow = equipRequested;
		menuNow = menuRequested;
		equipRequested = false;
		menuRequested = null;
		previewCape = null;
		previewWings = null;
		previewAura = null;
		if (slot != Slot.WEAR) {
			// Bodywear try-on only lives in its own tab.
			net.veloclient.launcher.data.WearCatalog.clearPreview();
		}
		Node slots = slotsGrid(host, refresh);
		String playerName = host.session() == null ? null : host.session().username();
		StackPane stage = slot == Slot.NAME ? NameStyleEditor.stage(playerName) : stage(host, refresh);
		HBox.setHgrow(stage, Priority.ALWAYS);
		Node panel = switch (slot) {
			case SKIN -> skinsPanel(owner, host, refresh);
			case CAPE -> capesPanel(owner, host, refresh);
			case WINGS -> wingsPanel(host, refresh);
			case AURA -> aurasPanel(host, refresh);
			case NAME -> NameStyleEditor.panel(playerName, refresh);
			case EMOTES -> emotesPanel(host, refresh);
			case KILLFX -> killFxPanel(host, refresh);
			case WEAR -> wearPanel(host, refresh);
			case PETS -> petsPanel(refresh);
		};
		if (pendingSlide != 0) {
			slideIn(panel, pendingSlide);
			pendingSlide = 0;
		}
		// 2026-11 layout: left = title, the big preview and the loadouts under it; right = the kinds on top, the catalog below.
		Label title = new Label("Locker");
		title.getStyleClass().add("page-title");
		VBox.setVgrow(stage, Priority.ALWAYS);
		HBox.setHgrow(stage, Priority.NEVER);
		stage.setMinHeight(220);
		Node loadouts = loadoutsCard(host, refresh);
		if (loadouts instanceof Region region) {
			region.setMinHeight(Region.USE_PREF_SIZE);
		}
		VBox left = new VBox(14, title, stage, loadouts);
		left.setMinWidth(320);
		left.setPrefWidth(380);
		left.setMaxWidth(420);
		VBox.setVgrow(panel, Priority.ALWAYS);
		if (panel instanceof Region region) {
			region.setMaxWidth(Double.MAX_VALUE);
		}
		VBox right = new VBox(14, slots, panel);
		right.setPadding(new Insets(50, 0, 0, 0));
		HBox.setHgrow(right, Priority.ALWAYS);
		HBox body = new HBox(22, left, right);
		body.getStyleClass().add("page");
		VBox.setVgrow(body, Priority.ALWAYS);
		return body;
	}

	// ---- Left: slots + loadouts ----

	/** The kinds as slim horizontal tabs in two rows above the catalog (2026-11, like the redesign). */
	private static Node slotsGrid(Host host, Runnable refresh) {
		List<Node> column = new ArrayList<>();

		CapeEntry cape = CapeLibrary.equippedCapeId().flatMap(id -> CapeLibrary.listAll().stream().filter(c -> c.id().equals(id)).findFirst()).orElse(null);
		WingsCatalog.WingType wings = WingsCatalog.byId(WingsCatalog.equipped() == null ? "" : WingsCatalog.equipped()).orElse(null);
		AuraCatalog.AuraType aura = AuraCatalog.byId(AuraCatalog.equipped() == null ? "" : AuraCatalog.equipped()).orElse(null);
		MinecraftSession session = host.session();
		column.add(slotButton(Slot.SKIN, "SKIN", session == null ? "Sign in" : session.username(),
				session == null ? Icons.icon("skin", 22, Color.web("#8A8A95")) : Heads.head(session.uuid(), session.username(), 38), refresh));
		column.add(slotButton(Slot.CAPE, "CLOAK", cape == null ? "None" : cape.name(), capeThumb(cape, 38), refresh));
		column.add(slotButton(Slot.WINGS, "WINGS", wings == null ? "None" : wings.name(), wingsThumb(wings, 38), refresh));
		column.add(slotButton(Slot.AURA, "AURA", aura == null ? "None" : aura.name(), auraThumb(aura, 38), refresh));
		net.veloclient.velo.client.namestyle.NameStyle nameStyle = net.veloclient.launcher.data.NameStyleSetting.style();
		boolean nameOn = net.veloclient.launcher.data.NameStyleSetting.enabled();
		Node nameThumb = new NamePreview("Aa", () -> nameStyle, 13, 26); // fits the 26 px tab picture (46 wide was cut off)
		column.add(slotButton(Slot.NAME, "NAME STYLE", nameOn ? nameStyle.effect().label : "None", nameThumb, refresh));
		int onWheel = 0;
		net.veloclient.launcher.data.EmoteCatalog.EmoteType firstOnWheel = null;
		for (int i = 0; i < net.veloclient.launcher.data.EmoteCatalog.SLOTS; i++) {
			String id = net.veloclient.launcher.data.EmoteCatalog.slot(i);
			if (id != null) {
				onWheel++;
				if (firstOnWheel == null) {
					firstOnWheel = net.veloclient.launcher.data.EmoteCatalog.byId(id).orElse(null);
				}
			}
		}
		Node emoteThumb = firstOnWheel == null ? Icons.icon("sparkles", 22, Color.web("#5A5A63")) : EmoteFigureFx.create(firstOnWheel, 38, 38);
		column.add(slotButton(Slot.EMOTES, "EMOTES", onWheel == 0 ? "None" : onWheel + " on your wheel", emoteThumb, refresh));
		String killFx = KillFxCatalog.equipped();
		Node killFxThumb = killFx != null && KillFxCatalog.IDS.contains(killFx)
				? new ImageView(AuraFx.killFxSnapshot(KillFxCatalog.byId(killFx).orElseThrow().kind(), KillFxCatalog.settings(killFx), 38, 38))
				: Icons.icon("skull", 22, Color.web(killFx == null ? "#5A5A63" : "#8A8A95"));
		column.add(slotButton(Slot.KILLFX, "KILL EFFECT", KillFxCatalog.name(killFx), killFxThumb, refresh));
		int worn = 0;
		for (String s : net.veloclient.launcher.data.WearCatalog.SLOTS) {
			if (net.veloclient.launcher.data.WearCatalog.equipped(s) != null) {
				worn++;
			}
		}
		column.add(slotButton(Slot.WEAR, "BODYWEAR", worn == 0 ? "None" : worn + (worn == 1 ? " item" : " items"),
				Icons.icon("hat", 22, Color.web(worn == 0 ? "#5A5A63" : "#8A8A95")), refresh));
		String pet = net.veloclient.launcher.data.PetsSetting.equipped();
		column.add(slotButton(Slot.PETS, "PET", net.veloclient.launcher.data.PetsSetting.name(pet),
				pet == null ? Icons.icon("sparkles", 22, Color.web("#5A5A63")) : petImage(pet, 38), refresh));
		javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
		grid.setHgap(8);
		grid.setVgap(8);
		for (int c = 0; c < 5; c++) {
			javafx.scene.layout.ColumnConstraints fifth = new javafx.scene.layout.ColumnConstraints();
			fifth.setPercentWidth(100.0 / 5);
			grid.getColumnConstraints().add(fifth);
		}
		for (int i = 0; i < column.size(); i++) {
			Node cell = column.get(i);
			if (cell instanceof Region region) {
				region.setMaxWidth(Double.MAX_VALUE);
			}
			grid.add(cell, i % 5, i / 5);
		}
		return grid;
	}

	private static Node slotButton(Slot target, String kind, String value, Node thumb, Runnable refresh) {
		// 2026-11: a slim tab - small picture + the kind; what's equipped shows on hover (the catalog below says it too).
		StackPane thumbBox = new StackPane(thumb);
		thumbBox.setMinSize(26, 26);
		thumbBox.setMaxSize(26, 26);
		thumbBox.setStyle("-fx-background-color: #0f0c0c; -fx-background-radius: 6;");
		if (thumb instanceof javafx.scene.image.ImageView image) {
			image.setFitWidth(26);
			image.setFitHeight(26);
			image.setPreserveRatio(true);
		} else if (thumb instanceof Region region) {
			region.setMaxSize(26, 26);
			region.setScaleX(Math.min(1, 26 / Math.max(26, region.prefWidth(-1))));
			region.setScaleY(region.getScaleX());
		}
		Rectangle clip = new Rectangle(26, 26);
		clip.setArcWidth(12);
		clip.setArcHeight(12);
		thumbBox.setClip(clip);
		String name = kind.charAt(0) + kind.substring(1).toLowerCase(java.util.Locale.ROOT);
		Label kindLabel = new Label(name);
		kindLabel.getStyleClass().add("locker-tab-name");
		kindLabel.setMinWidth(0);
		HBox row = new HBox(9, thumbBox, kindLabel);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(0, 10, 0, 7));
		row.setMinHeight(42);
		row.setPrefHeight(42);
		row.setMaxHeight(42);
		javafx.scene.control.Tooltip.install(row, new javafx.scene.control.Tooltip(name + ": " + value));
		boolean active = slot == target;
		// 2026-11: calm horizontal cards - the active one is lifted and outlined, no accent bar on the side.
		row.getStyleClass().add(active ? "locker-slot-active" : "locker-slot");
		row.getStyleClass().add("motion-card");
		row.setOnMouseClicked(e -> {
			if (slot == target) {
				return;
			}
			// The slots are listed in enum order: a slot further down slides the new panel up from below, and back.
			pendingSlide = Integer.signum(target.ordinal() - slot.ordinal());
			slot = target;
			UiSounds.tab();
			refresh.run();
		});
		return row;
	}

	/**
	 * The new slot's panel glides in vertically (with the list's direction) and fades up, instead of a hard cut. Opacity
	 * only on the panel - never on the 3D stage, where it can blank the SubScene.
	 */
	private static void slideIn(Node panel, int direction) {
		panel.setOpacity(0);
		panel.setTranslateY(28 * direction);
		javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(200), panel);
		fade.setToValue(1);
		javafx.animation.TranslateTransition glide = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(300), panel);
		glide.setToY(0);
		glide.setInterpolator(javafx.animation.Interpolator.SPLINE(0.16, 1, 0.3, 1));
		new javafx.animation.ParallelTransition(fade, glide).play();
	}

	private static Node capeThumb(CapeEntry cape, double size) {
		if (cape == null) {
			return Icons.icon("cape", 22, Color.web("#5A5A63"));
		}
		StackPane holder = new StackPane();
		CosmeticUi.cardPicture(holder, holder, "lib:" + cape.id(), CosmeticUi.version(cape), 120, 120, size,
				done -> CosmeticUi.framesAsync(cape, done), false);
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
	 * speed and density - and the Velo+ name style, so switching looks brings each one back exactly (older loadouts have
	 * no settings or name style: kept as they are). Same file as the game's Loadouts.
	 */
	private record Loadout(String name, String cape, String wings, String aura, WingsCatalog.Settings wingsSettings,
			AuraEffects.Settings auraSettings, String nameStyle, Boolean nameStyleOn, java.util.Map<String, String> wear,
			java.util.Map<String, Integer> wearColors, java.util.Map<String, Integer> wearOffsets, java.util.Map<String, Integer> wearDepths,
			String killFx, String skin, java.util.Map<String, String> extras) {
		static Loadout current(String name) {
			String wings = WingsCatalog.equipped();
			String aura = AuraCatalog.equipped();
			java.util.Map<String, String> wear = equippedWear();
			java.util.Map<String, Integer> colors = new java.util.TreeMap<>();
			java.util.Map<String, Integer> offsets = new java.util.TreeMap<>();
			java.util.Map<String, Integer> depths = new java.util.TreeMap<>();
			for (String id : wear.values()) {
				putNonZero(colors, id, net.veloclient.launcher.data.WearCatalog.color(id));
				putNonZero(offsets, id, net.veloclient.launcher.data.WearCatalog.offset(id));
				putNonZero(depths, id, net.veloclient.launcher.data.WearCatalog.depth(id));
			}
			String killFx = KillFxCatalog.equipped();
			return new Loadout(name, CapeLibrary.equippedCapeId().orElse(null), wings, aura,
					wings == null ? null : WingsCatalog.settings(wings), aura == null ? null : AuraCatalog.settings(aura),
					net.veloclient.launcher.data.NameStyleSetting.style().encode(), net.veloclient.launcher.data.NameStyleSetting.enabled(),
					wear, colors, offsets, depths, killFx == null ? "" : killFx, currentSkin(), currentExtras());
		}

		/** What looks keep beyond the built-in slots: the pet. */
		private static java.util.Map<String, String> currentExtras() {
			return new java.util.TreeMap<>(java.util.Map.of("pet", java.util.Objects.requireNonNullElse(net.veloclient.launcher.data.PetsSetting.equipped(), "")));
		}

		private static void putNonZero(java.util.Map<String, Integer> map, String id, int value) {
			if (value != 0) {
				map.put(id, value);
			}
		}

		boolean isWorn() {
			String wornWings = WingsCatalog.equipped();
			String wornAura = AuraCatalog.equipped();
			return java.util.Objects.equals(cape, CapeLibrary.equippedCapeId().orElse(null)) && java.util.Objects.equals(wings, wornWings)
					&& java.util.Objects.equals(aura, wornAura)
					&& (wingsSettings == null || wings == null || wingsSettings.equals(WingsCatalog.settings(wings)))
					&& (auraSettings == null || aura == null || auraSettings.equals(AuraCatalog.settings(aura)))
					&& (nameStyleOn == null || nameStyleOn == net.veloclient.launcher.data.NameStyleSetting.enabled()
							&& (!nameStyleOn || java.util.Objects.equals(nameStyle, net.veloclient.launcher.data.NameStyleSetting.style().encode())))
					&& (wear == null || wear.equals(equippedWear()))
					&& (killFx == null || killFx.equals(java.util.Objects.requireNonNullElse(KillFxCatalog.equipped(), "")))
					&& (skin == null || skin.equals(currentSkin()))
					&& (extras == null || extras.get("pet") == null
							|| extras.get("pet").equals(java.util.Objects.requireNonNullElse(net.veloclient.launcher.data.PetsSetting.equipped(), "")));
		}

		void wear(Host host) {
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
			if (nameStyleOn != null) {
				var style = net.veloclient.velo.client.namestyle.NameStyle.decode(nameStyle);
				net.veloclient.launcher.data.NameStyleSetting.save(style == null ? net.veloclient.launcher.data.NameStyleSetting.style() : style,
						nameStyleOn && net.veloclient.launcher.social.StoreApi.isPlusCached());
				NameStyleEditor.forget();
			}
			if (wear != null) {
				net.veloclient.launcher.data.WearCatalog.clearPreview();
				for (String s : net.veloclient.launcher.data.WearCatalog.SLOTS) {
					String id = wear.get(s);
					if (id != null) {
						net.veloclient.launcher.data.WearCatalog.setColor(id, wearColors == null ? 0 : wearColors.getOrDefault(id, 0));
						net.veloclient.launcher.data.WearCatalog.setPlacement(id, wearOffsets == null ? 0 : wearOffsets.getOrDefault(id, 0), wearDepths == null ? 0 : wearDepths.getOrDefault(id, 0));
					}
					net.veloclient.launcher.data.WearCatalog.equip(s, id);
				}
			}
			if (killFx != null) {
				KillFxCatalog.equip(killFx.isEmpty() ? null : killFx);
			}
			if (extras != null && extras.get("pet") != null) {
				net.veloclient.launcher.data.PetsSetting.equip(extras.get("pet").isEmpty() ? null : extras.get("pet"));
			}
			if (skin != null && !skin.equals(SkinLibrary.equippedId()) && host.session() != null) {
				SkinLibrary.Skin chosen = SkinLibrary.all().stream().filter(s -> s.id().equals(skin)).findFirst().orElse(null);
				if (chosen != null) {
					// Uploading to Mojang takes a moment (and can be refused for changing too often).
					wantedSkin = chosen.id();
					host.withFreshSession(fresh -> SkinsSection.run(() -> {
						SkinLibrary.equip(chosen, fresh.minecraftAccessToken(), fresh.uuid());
						return chosen;
					}, done -> CosmeticUi.forgetSkin(), error -> {
					}));
				}
			}
		}
	}

	/** What's in every bodywear slot: slot -> item id. */
	private static java.util.Map<String, String> equippedWear() {
		java.util.Map<String, String> out = new java.util.TreeMap<>();
		for (String s : net.veloclient.launcher.data.WearCatalog.SLOTS) {
			String id = net.veloclient.launcher.data.WearCatalog.equipped(s);
			if (id != null) {
				out.put(s, id);
			}
		}
		return out;
	}

	/** The skin a loadout asked for while its upload runs (or was refused - it stays the loadout's skin). */
	private static String wantedSkin;

	private static String currentSkin() {
		return wantedSkin != null ? wantedSkin : SkinLibrary.equippedId();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The loadout being worn (picked or saved last); changes to its items' settings are saved into it. */
	private static String activeLoadout;
	private static String activeLoadoutAccount;
	private static boolean syncInstalled;

	private static Path activeLoadoutFile() {
		return VeloPaths.accountConfig("loadout-active.txt");
	}

	private static String activeLoadout() {
		if (activeLoadout == null || !java.util.Objects.equals(activeLoadoutAccount, VeloPaths.account())) {
			activeLoadoutAccount = VeloPaths.account();
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
	 * Something you wear changed (an item, a colour, a size, the name style): the loadout you're wearing takes it
	 * straight away - so every loadout keeps its own items and settings, and switching brings them back.
	 */
	static void syncActiveLoadout() {
		String name = activeLoadout();
		if (name.isEmpty()) {
			return;
		}
		List<Loadout> list = loadouts();
		for (int i = 0; i < list.size(); i++) {
			Loadout l = list.get(i);
			if (l.name().equals(name)) {
				Loadout now = Loadout.current(name);
				if (!now.equals(l)) {
					list.set(i, now);
					saveLoadouts(list);
				}
				return;
			}
		}
	}

	/** Keeps the worn loadout in step with every change, wherever it's made (Locker, Store pages) - from launcher start. */
	public static void installLoadoutSync() {
		if (!syncInstalled) {
			syncInstalled = true;
			WingsCatalog.SETTINGS_LISTENERS.add(CosmeticsView::syncActiveLoadout);
			AuraCatalog.SETTINGS_LISTENERS.add(CosmeticsView::syncActiveLoadout);
			CapeLibrary.EQUIP_LISTENERS.add(CosmeticsView::syncActiveLoadout);
			net.veloclient.launcher.data.WearCatalog.LISTENERS.add(CosmeticsView::syncActiveLoadout);
			KillFxCatalog.LISTENERS.add(CosmeticsView::syncActiveLoadout);
			net.veloclient.launcher.data.PetsSetting.LISTENERS.add(CosmeticsView::syncActiveLoadout);
			SkinLibrary.LISTENERS.add(() -> {
				wantedSkin = null;
				syncActiveLoadout();
			});
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

	private static Node loadoutsCard(Host host, Runnable refresh) {
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
				loadout.wear(host);
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
			dialog.setHeaderText("Name this look - cloak, wings, aura, bodywear, kill effect, skin and name style, with their colours and settings");
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
			case SKIN, NAME -> "#bca6ff";
			case CAPE -> "#5ac8ff";
			case EMOTES -> net.veloclient.launcher.data.EmoteCatalog.byId(selectedEmoteId()).map(net.veloclient.launcher.data.EmoteCatalog.EmoteType::accent).orElse("#8b7cf6");
			case KILLFX -> KillFxCatalog.byId(selectedKillFxId()).map(KillFxCatalog.KillFxType::accent).orElse("#ff5c5c");
			case WEAR -> "#ffb13b";
			case PETS -> net.veloclient.launcher.data.PetsSetting.byId(selectedPetId()).map(net.veloclient.launcher.data.PetsSetting.Pet::accent).orElse("#ff3a2a");
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
			case CAPE -> "Cloak only";
			case WINGS -> "Wings only";
			case AURA -> "Aura only";
			case SKIN, NAME -> "Skin only";
			case EMOTES -> "Emote";
			case KILLFX -> "Kill effect";
			case WEAR -> "Bodywear";
			case PETS -> "Pet";
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
		if (host.session() != null && slot != Slot.EMOTES && slot != Slot.KILLFX && slot != Slot.PETS) {
			stage.getChildren().add(lookToggle);
		}

		liveViewerBox = viewerBox;
		liveBottom = bottom;
		liveHost = host;
		if (slot == Slot.PETS) {
			viewerBox.getChildren().setAll(petStage());
		} else if (slot == Slot.KILLFX) {
			viewerBox.getChildren().setAll(killFxStage());
		} else if (slot == Slot.EMOTES) {
			// you, doing the emote you picked (with its particles)
			net.veloclient.launcher.data.EmoteCatalog.EmoteType emote = net.veloclient.launcher.data.EmoteCatalog.byId(selectedEmoteId()).orElse(null);
			if (emote != null) {
				Node emoteStage = EmoteDetailView.stage(host.session(), emote, 340, 420);
				if (emoteStage instanceof Region region) {
					region.getStyleClass().removeAll("cape-stage", "showcase-stage");
					region.setStyle("-fx-background-color: transparent;");
				}
				viewerBox.getChildren().setAll(emoteStage);
			}
		} else if (fullLook && host.session() != null) {
			outfitViewer(host, viewerBox, bottom);
		} else switch (slot) {
			case WEAR -> {
				if (host.session() != null) {
					viewerBox.getChildren().setAll(CosmeticUi.spinner(30));
					CosmeticUi.skin(host.session(), data -> {
						double yaw = "back".equals(wearSlot) ? 160 : 25;
						Node viewer = data == null ? null : PlayerSkin3DView.createViewer(data.pngBytes(), data.slim(), null, true, yaw, false);
						if (viewer != null) {
							viewerBox.getChildren().setAll(viewer);
						}
					});
				}
			}
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
		if (bottom.getChildren().isEmpty() && slot != Slot.PETS) { // pets are a picture - nothing to rotate
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
		String capeId = slot == Slot.CAPE ? (previewCape != null ? (previewCape.isEmpty() ? null : previewCape) : CapeLibrary.equippedCapeId().orElse(null))
				: CapeLibrary.equippedCapeId().orElse(null);
		String wingsId = slot == Slot.WINGS ? (previewWings != null ? (previewWings.isEmpty() ? null : previewWings) : WingsCatalog.equipped())
				: WingsCatalog.equipped();
		if (wingsId != null && (wingsId.isEmpty() || WingsCatalog.byId(wingsId).isEmpty())) {
			wingsId = null;
		}
		String auraId = slot == Slot.AURA ? (previewAura != null ? (previewAura.isEmpty() ? null : previewAura) : AuraCatalog.equipped())
				: AuraCatalog.equipped();
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
			CosmeticUi.viewerFramesAsync(cape, f -> {
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
		// the preview rebuilds on every hover: put the motion chips back once, never next to the old ones
		bottom.getChildren().removeIf(n -> n.getStyleClass().contains("segment-bar"));
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
			CosmeticUi.viewerFramesAsync(selected, f -> {
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

	/** A compact item cell: art, name, a check when worn. Left click wears it, right click opens its menu. */
	private static VBox cell(Node art, String accent, String name, boolean equipped, boolean selected, Runnable onClick) {
		return cell(art, accent, name, equipped, selected, onClick, null);
	}

	/** {@code onHover}: shows this item on the big preview (null = no hover preview for this kind). */
	private static VBox cell(Node art, String accent, String name, boolean equipped, boolean selected, Runnable onClick, Runnable onHover) {
		StackPane artBox = new StackPane(art);
		artBox.setMinHeight(104);
		artBox.setPrefHeight(104);
		// The art never makes the card wider than its column: aura stills and emote/bodywear pictures are drawn 150 wide
		// while a column can be ~118 - they pushed past the card's edge. Pictures shrink to fit, anything else is clipped.
		artBox.setMinWidth(0);
		artBox.setPrefWidth(0);
		artBox.setMaxWidth(Double.MAX_VALUE);
		if (art instanceof ImageView picture && picture.getFitWidth() > 0) {
			double fitW = picture.getFitWidth();
			picture.setPreserveRatio(true);
			picture.fitWidthProperty().bind(javafx.beans.binding.Bindings.min(fitW, artBox.widthProperty().subtract(10)));
		} else if (art instanceof Region region) {
			region.setMinWidth(0);
		}
		artBox.setStyle("-fx-background-color: radial-gradient(center 50% 65%, radius 70%, " + accent + "40 0%, transparent 100%), #171213;"
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
			Label worn = new Label("WORN");
			worn.getStyleClass().add("locker-worn");
			footer.getChildren().add(worn);
		}
		footer.setPadding(new Insets(8, 10, 10, 10));
		VBox cell = new VBox(artBox, footer);
		cell.getStyleClass().addAll(equipped ? "locker-cell-worn" : "locker-cell", "motion-card");
		cell.setOnMouseClicked(e -> {
			if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
				menuRequested = new double[] {e.getScreenX(), e.getScreenY()};
			} else if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
				equipRequested = true;
			} else {
				return;
			}
			onClick.run();
		});
		if (onHover != null) {
			cell.setOnMouseEntered(e -> {
				HOVER_DEBOUNCE.setOnFinished(x -> {
					onHover.run();
					refreshViewer();
				});
				HOVER_DEBOUNCE.playFromStart();
			});
		}
		return cell;
	}

	/** The catalog lost the mouse: the preview goes back to what you wear. */
	private static void installHoverReset(Node grid) {
		grid.setOnMouseExited(e -> {
			HOVER_DEBOUNCE.setOnFinished(x -> {
				previewCape = null;
				previewWings = null;
				previewAura = null;
				refreshViewer();
			});
			HOVER_DEBOUNCE.playFromStart();
		});
	}

	/** Rebuilds only the 3D preview (not the page) for the item under the mouse. */
	private static void refreshViewer() {
		if (liveViewerBox == null || liveViewerBox.getScene() == null || liveHost == null || liveHost.session() == null || !fullLook) {
			return;
		}
		outfitViewer(liveHost, liveViewerBox, liveBottom);
	}

	/**
	 * What used to be the Equip bar (2026-11: removed). The item that was just clicked is the selected one in this build:
	 * a left click wears it (unless it's worn already), a right click opens its menu. Returns an empty placeholder.
	 */
	private static Node actionBar(String name, boolean equipped, Runnable toggle, Runnable delete) {
		if (equipNow) {
			equipNow = false;
			if (!equipped) {
				javafx.application.Platform.runLater(toggle);
			}
		}
		if (menuNow != null) {
			double[] at = menuNow;
			menuNow = null;
			javafx.application.Platform.runLater(() -> {
				javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu();
				javafx.scene.control.MenuItem wear = new javafx.scene.control.MenuItem(equipped ? "Take off" : "Wear " + name);
				wear.setOnAction(e -> toggle.run());
				menu.getItems().add(wear);
				if (slot == Slot.WINGS || slot == Slot.AURA || slot == Slot.KILLFX || slot == Slot.WEAR) {
					javafx.scene.control.MenuItem edit = new javafx.scene.control.MenuItem("Edit properties");
					// the item is selected now: its settings are open in the panel
					edit.setOnAction(e -> { });
					menu.getItems().add(edit);
				}
				if (delete != null) {
					menu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
					javafx.scene.control.MenuItem remove = new javafx.scene.control.MenuItem("Delete");
					remove.setStyle("-fx-text-fill: #ff7a7e;");
					remove.setOnAction(e -> delete.run());
					menu.getItems().add(remove);
				}
				if (liveViewerBox != null && liveViewerBox.getScene() != null) {
					menu.show(liveViewerBox.getScene().getWindow(), at[0], at[1]);
				}
			});
		}
		Region none = new Region();
		none.setManaged(false);
		none.setVisible(false);
		return none;
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
		Runnable upload = () -> CapeImportDialog.show(owner).ifPresent(result -> {
			Toasts.show(owner.getScene().getRoot(), "Making your cloak...");
			Thread.ofVirtual().start(() -> {
				try {
					CapeImportDialog.Prepared prepared = result.prepare().call();
					if (prepared.gif() != null) {
						CapeLibrary.importAnimatedGif(result.name(), new java.io.ByteArrayInputStream(prepared.gif()), result.preset(), null);
					} else {
						CapeLibrary.importTexture(result.name(), prepared.texture(), result.preset());
					}
					javafx.application.Platform.runLater(() -> {
						Toasts.show(owner.getScene().getRoot(), "\"" + result.name() + "\" added to your cloaks");
						refresh.run();
					});
				} catch (Exception ex) {
					javafx.application.Platform.runLater(() -> error(owner, "Upload failed", ex.getMessage()));
				}
			});
		});
		Button importButton = new Button("Upload");
		importButton.setGraphic(Icons.icon("upload", 14, Color.web("#E4E4E8")));
		importButton.getStyleClass().add("button-sm");
		importButton.setTooltip(new javafx.scene.control.Tooltip("Upload your own cloak: a cloak texture, or any picture or GIF"));
		importButton.setOnAction(e -> upload.run());
		VBox panel = panelShell("Cloaks", capes.size(), importButton, storeLink(host, "Get more"));
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
		// Your own cloak, up front: any picture works, it's turned into a cloak (elytra included).
		Label uploadHint = new Label("Any picture or GIF");
		uploadHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #8A8A96;");
		VBox uploadArt = new VBox(6, Icons.icon("upload", 26, Color.web("#5ac8ff")), uploadHint);
		uploadArt.setAlignment(Pos.CENTER);
		grid.getChildren().add(cell(uploadArt, "#5ac8ff", "Your own cloak", false, false, upload));
		grid.getChildren().add(cell(Icons.icon("close", 26, Color.web("#5A5A63")), "#000000", "No cloak", equipped == null, selected == null, () -> {
			equipRequested = false;
			menuRequested = null;
			CapeLibrary.unequip();
			selectedCape = null;
			refresh.run();
		}, () -> previewCape = ""));
		installHoverReset(grid);
		for (CapeEntry cape : capes) {
			if (capeFilter == 1 && !cape.animated() || capeFilter == 2 && cape.animated()) {
				continue;
			}
			StackPane art = new StackPane(CosmeticUi.spinner(20));
			VBox cellNode = cell(art, "#5ac8ff", cape.name(), cape.id().equals(equipped), cape.id().equals(selected), () -> {
				selectedCape = cape.id();
				refresh.run();
			}, () -> previewCape = cape.id());
			CosmeticUi.cardPicture(art, cellNode, "lib:" + cape.id(), CosmeticUi.version(cape), 120, 120, 96,
					done -> CosmeticUi.framesAsync(cape, done), cape.animated());
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
					Toasts.show(panel, "Cloak equipped");
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
		installHoverReset(grid);
		for (WingsCatalog.WingType type : owned) {
			ImageView picture = new ImageView(UiImages.wingPreview(type.id()));
			picture.setFitHeight(92);
			picture.setPreserveRatio(true);
			grid.getChildren().add(cell(picture, type.accent(), type.name(), type.id().equals(equipped), type.id().equals(selected), () -> {
				selectedWings = type.id();
				refresh.run();
			}, () -> previewWings = type.id()));
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
						field == 3 ? v : s.spread(), field == 4 ? v : s.flapSpeed(), field == 5 ? v : s.flapStrength(), s.color());
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
		if (net.veloclient.velo.client.cosmetics.wings.WingRigs.recolorable(type.id())) {
			// Colour: the hues round the wheel, then white, then a cycling rainbow.
			int white = net.veloclient.velo.client.cosmetics.wings.WingRigs.TINT_WHITE;
			int rainbow = net.veloclient.velo.client.cosmetics.wings.WingRigs.TINT_RAINBOW;
			// Pairs with their own colour (grey art, e.g. Chain Wings) start the slider at "Own".
			boolean own = net.veloclient.velo.client.cosmetics.wings.WingRigs.ownTint(type.id()) >= 0;
			Slider colour = new Slider(own ? 0 : 1, rainbow, current[0].color() <= 0 ? own ? 0 : white : current[0].color());
			colour.setBlockIncrement(1);
			box.getChildren().add(sliderRow("Colour", colour, () -> colour.getValue() >= rainbow - 0.5 ? "Rainbow"
					: colour.getValue() >= white - 0.5 ? "White" : colour.getValue() < 0.5 ? "Own" : Math.round(colour.getValue() - 1) + "°"));
			colour.valueProperty().addListener((obs, o, n) -> {
				current[0] = current[0].withColor((int) Math.round(n.doubleValue()));
				if (wingsModel[0] != null) {
					wingsModel[0].settings(current[0]);
				}
			});
			colour.valueChangingProperty().addListener((obs, was, now) -> {
				if (!now) {
					WingsCatalog.setSettings(type.id(), current[0]);
				}
			});
			colour.setOnMouseReleased(e -> WingsCatalog.setSettings(type.id(), current[0]));
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
		installHoverReset(grid);
		for (AuraCatalog.AuraType aura : owned) {
			ImageView art = new ImageView(AuraFx.snapshot(aura.kind(), AuraCatalog.settings(aura.id()), 150, 104));
			art.setFitWidth(150);
			art.setFitHeight(104);
			grid.getChildren().add(cell(art, aura.accent(), aura.name(), aura.id().equals(equipped), aura.id().equals(selected), () -> {
				selectedAura = aura.id();
				refresh.run();
			}, () -> previewAura = aura.id()));
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

	// Kill effects

	private static String selectedKillFxId() {
		if (selectedKillFx == null) {
			String equipped = KillFxCatalog.equipped();
			return equipped == null ? "" : equipped;
		}
		return selectedKillFx;
	}

	/** The stage's kill effect card (Store effects), the sound starting with it when one was just picked. */
	private static final boolean[] killFxSound = {false};
	private static Node killFxCard;

	// ---- Pets ----

	private static String selectedPet;

	private static String selectedPetId() {
		if (selectedPet == null) {
			String equipped = net.veloclient.launcher.data.PetsSetting.equipped();
			selectedPet = equipped == null ? "" : equipped;
		}
		return selectedPet;
	}

	private static ImageView petImage(String id, double size) {
		var in = CosmeticsView.class.getResourceAsStream("/net/veloclient/launcher/pets/" + id + ".jpg");
		ImageView view = new ImageView(in == null ? null : new javafx.scene.image.Image(in, size * 2, size * 2, true, true));
		view.setFitWidth(size);
		view.setFitHeight(size);
		javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(size, size);
		clip.setArcWidth(size * 0.35);
		clip.setArcHeight(size * 0.35);
		view.setClip(clip);
		return view;
	}

	/** The picked pet, big. */
	private static Node petStage() {
		String id = selectedPetId();
		if (id.isEmpty()) {
			Label none = new Label("No pet - pick one on the right");
			none.getStyleClass().add("t-muted");
			return none;
		}
		VBox box = new VBox(14, petImage(id, 340));
		Label name = new Label(net.veloclient.launcher.data.PetsSetting.name(id));
		name.getStyleClass().add("t-heading");
		box.getChildren().add(name);
		box.setAlignment(Pos.CENTER);
		return box;
	}

	private static Node petsPanel(Runnable refresh) {
		String equipped = net.veloclient.launcher.data.PetsSetting.equipped();
		String selected = selectedPetId();
		VBox panel = panelShell("Pets", net.veloclient.launcher.data.PetsSetting.ALL.size());
		FlowPane grid = StoreTiles.grid(118, 10);
		grid.getChildren().add(cell(Icons.icon("close", 26, Color.web("#5A5A63")), "#8a8a95", "None", equipped == null, selected.isEmpty(), () -> {
			selectedPet = "";
			refresh.run();
		}));
		for (var pet : net.veloclient.launcher.data.PetsSetting.ALL) {
			Node cell = cell(petImage(pet.id(), 104), pet.accent(), pet.name(), pet.id().equals(equipped), pet.id().equals(selected), () -> {
				selectedPet = pet.id();
				refresh.run();
			});
			if (!net.veloclient.launcher.data.PetsSetting.owns(pet.id())) {
				cell.setOpacity(0.45);   // not yours yet - in the Store
			}
			grid.getChildren().add(cell);
		}
		VBox content = new VBox(16, grid);
		boolean selectedOwned = selected.isEmpty() || net.veloclient.launcher.data.PetsSetting.owns(selected);
		Label hint = new Label(selectedOwned ? "Your pet follows you in game. Tricks, size and when it reacts to hits are the Pets module's settings in game."
				: net.veloclient.launcher.data.PetsSetting.name(selected) + " is in the Store - "
						+ net.veloclient.launcher.data.PetsSetting.storePet(selected).map(p -> String.format(java.util.Locale.ROOT, "%,d Velo Coins", StoreApi.price(p.itemId(), p.price()))).orElse("open the Store")
						+ ". Buy it there and it's yours to take out here.");
		hint.getStyleClass().add("stage-hint");
		hint.setWrapText(true);
		content.getChildren().add(hint);
		panel.getChildren().add(scroll(content));
		boolean isEquipped = selected.isEmpty() ? equipped == null : selected.equals(equipped);
		if (!isEquipped && selectedOwned) {
			panel.getChildren().add(actionBar(net.veloclient.launcher.data.PetsSetting.name(selected.isEmpty() ? null : selected), false, () -> {
				net.veloclient.launcher.data.PetsSetting.equip(selected.isEmpty() ? null : selected);
				Toasts.show(panel, selected.isEmpty() ? "Pet put away" : "Pet picked - it joins you in your next game");
				refresh.run();
			}, null));
		}
		return panel;
	}

	private static Node killFxStage() {
		String id = selectedKillFxId();
		if (!KillFxCatalog.IDS.contains(id)) {
			Label line = new Label(id.isEmpty() ? "No kill effect" : KillFxCatalog.name(id));
			line.getStyleClass().add("t-strong");
			Label hint = new Label(id.isEmpty() ? "Pick one on the right" : "Free · vanilla particles and sounds - see it in game");
			hint.getStyleClass().add("t-muted");
			VBox box = new VBox(8, Icons.icon("skull", 34, Color.web("#5A5A63")), line, hint);
			box.setAlignment(Pos.CENTER);
			killFxCard = null;
			return box;
		}
		KillFxCatalog.KillFxType type = KillFxCatalog.byId(id).orElseThrow();
		killFxCard = AuraFx.killFxCard(type.kind(), () -> KillFxCatalog.settings(id), 340, 420, () -> {
			if (killFxSound[0]) {
				killFxSound[0] = false;
				KillFxSound.play(id);
			}
		});
		return killFxCard;
	}

	private static Node killFxPanel(Host host, Runnable refresh) {
		String equipped = KillFxCatalog.equipped();
		String selected = selectedKillFxId();
		List<KillFxCatalog.KillFxType> owned = KillFxCatalog.all().stream().filter(k -> KillFxCatalog.owns(k.id())).toList();
		VBox panel = panelShell("Kill effects", owned.size() + KillFxCatalog.FREE.size(), storeLink(host, "Get more"));
		FlowPane grid = StoreTiles.grid(118, 10);
		Runnable pick = refresh;
		java.util.function.BiConsumer<String, Node> add = (id, art) -> grid.getChildren().add(cell(art,
				KillFxCatalog.byId(id).map(KillFxCatalog.KillFxType::accent).orElse("#8a8a95"), KillFxCatalog.name(id.isEmpty() ? null : id),
				id.isEmpty() ? equipped == null : id.equals(equipped), id.equals(selected), () -> {
					selectedKillFx = id;
					killFxSound[0] = KillFxCatalog.IDS.contains(id);
					KillFxSound.stop();
					pick.run();
				}));
		add.accept("", Icons.icon("close", 26, Color.web("#5A5A63")));
		for (String id : KillFxCatalog.FREE.keySet()) {
			Label free = new Label("FREE");
			free.setStyle("-fx-text-fill: #7be07b; -fx-font-weight: bold; -fx-font-size: 11px;");
			VBox art = new VBox(6, Icons.icon("skull", 26, Color.web("#C9C9D1")), free);
			art.setAlignment(Pos.CENTER);
			art.setMinSize(150, 104);
			add.accept(id, art);
		}
		for (KillFxCatalog.KillFxType type : owned) {
			ImageView art = new ImageView(AuraFx.killFxSnapshot(type.kind(), KillFxCatalog.settings(type.id()), 150, 104));
			art.setFitWidth(150);
			art.setFitHeight(104);
			add.accept(type.id(), art);
		}
		VBox content = new VBox(16, grid);
		// Settings: how loud, which colour, play it again with its sound.
		VBox settings = new VBox(10);
		Label title = new Label("Settings");
		title.getStyleClass().add("t-heading");
		settings.getChildren().addAll(title, KillFxDetailView.volume());
		if (KillFxCatalog.IDS.contains(selected)) {
			KillFxCatalog.KillFxType type = KillFxCatalog.byId(selected).orElseThrow();
			Button replay = new Button("Play with sound");
			replay.getStyleClass().add("secondary-button");
			replay.setOnAction(e -> {
				KillFxSound.stop();
				killFxSound[0] = true;
				AuraFx.restartKillFx(killFxCard);
			});
			settings.getChildren().addAll(replay, KillFxDetailView.colours(type, s -> { }));
		} else if (selected.equals("free_burst") || selected.equals("free_confetti")) {
			settings.getChildren().add(freeColours());
		}
		Label when = new Label("When it plays (only your kills, any player...) is the Kill Effects module's setting in game.");
		when.getStyleClass().add("stage-hint");
		when.setWrapText(true);
		settings.getChildren().add(when);
		settings.getStyleClass().add("card");
		settings.setPadding(new Insets(16));
		content.getChildren().add(settings);
		panel.getChildren().add(scroll(content));
		boolean isEquipped = selected.isEmpty() ? equipped == null : selected.equals(equipped);
		if (!isEquipped || !selected.isEmpty()) {
			panel.getChildren().add(actionBar(KillFxCatalog.name(selected.isEmpty() ? null : selected), isEquipped, () -> {
				KillFxCatalog.equip(isEquipped || selected.isEmpty() ? null : selected);
				if (!isEquipped && !selected.isEmpty()) {
					Toasts.show(panel, "Kill effect equipped - it plays in your next game");
				}
				refresh.run();
			}, null));
		}
		return panel;
	}

	/**
	 * The Bodywear tab: a slot at a time (hats, ears, glasses, watches, shoes, back); click an item you own to try it on,
	 * Wear / Take off, and a colour for recolourable ones. Every slot is worn at the same time.
	 */
	private static Node wearPanel(Host host, Runnable refresh) {
		String equipped = net.veloclient.launcher.data.WearCatalog.equipped(wearSlot);
		String selected = selectedWear == null ? (equipped == null ? "" : equipped) : selectedWear;
		net.veloclient.launcher.data.WearCatalog.setPreview(wearSlot, selected.isEmpty() ? null : selected);
		var all = net.veloclient.launcher.data.WearCatalog.all();
		List<net.veloclient.launcher.data.WearCatalog.WearType> owned = all.stream()
				.filter(w -> net.veloclient.launcher.data.WearCatalog.owns(w.id())).toList();
		VBox panel = panelShell("Bodywear", owned.size(), storeLink(host, "Get more"));
		// The slots as small icon chips that wrap onto a second row instead of being cut off.
		FlowPane chips = new FlowPane(4, 4);
		chips.getStyleClass().add("wear-slot-bar");
		for (int i = 0; i < net.veloclient.launcher.data.WearCatalog.SLOTS.size(); i++) {
			String s = net.veloclient.launcher.data.WearCatalog.SLOTS.get(i);
			boolean active = s.equals(wearSlot);
			Button chip = new Button(net.veloclient.launcher.data.WearCatalog.SLOT_NAMES.get(i),
					Icons.icon(net.veloclient.launcher.data.WearCatalog.SLOT_ICONS.get(i), 14, active ? Color.WHITE : Color.web("#A6A6B0"), 1.8));
			chip.getStyleClass().setAll("button", "wear-slot-chip", "no-motion");
			chip.setGraphicTextGap(5);
			chip.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
			if (active) {
				chip.getStyleClass().add("wear-slot-chip-active");
			}
			chip.setOnAction(e -> {
				net.veloclient.launcher.data.WearCatalog.clearPreview();
				wearSlot = s;
				selectedWear = null;
				refresh.run();
			});
			chips.getChildren().add(chip);
		}
		FlowPane grid = StoreTiles.grid(118, 10);
		java.util.function.BiConsumer<String, Node> add = (id, art) -> grid.getChildren().add(cell(art,
				id.isEmpty() ? "#8a8a95" : all.stream().filter(w -> w.id().equals(id)).findFirst().map(net.veloclient.launcher.data.WearCatalog.WearType::accent).orElse("#8a8a95"),
				id.isEmpty() ? "None" : all.stream().filter(w -> w.id().equals(id)).findFirst().map(net.veloclient.launcher.data.WearCatalog.WearType::name).orElse(id),
				id.isEmpty() ? equipped == null : id.equals(equipped), id.equals(selected), () -> {
					selectedWear = id;
					refresh.run();
				}));
		add.accept("", Icons.icon("close", 26, Color.web("#5A5A63")));
		for (var type : owned) {
			if (type.slot().equals(wearSlot)) {
				add.accept(type.id(), StoreTiles.wearArt(type, 150));
			}
		}
		VBox content = new VBox(16, chips, grid);
		var selectedType = all.stream().filter(w -> w.id().equals(selected)).findFirst().orElse(null);
		boolean height = selectedType != null && net.veloclient.launcher.data.WearCatalog.owns(selectedType.id())
				&& net.veloclient.launcher.data.WearCatalog.adjustable(selectedType.slot());
		if (selectedType != null && (selectedType.tintable() || height)) {
			VBox settings = new VBox(14);
			if (selectedType.tintable()) {
				settings.getChildren().add(WearDetailView.colour(selectedType));
			}
			if (height) {
				settings.getChildren().add(WearDetailView.placement(selectedType));
			}
			settings.getStyleClass().add("card");
			settings.setPadding(new Insets(16));
			content.getChildren().add(settings);
		}
		if (owned.stream().noneMatch(w -> w.slot().equals(wearSlot))) {
			Label none = new Label("Nothing for this slot yet - the Store's Bodywear tab has hats, ears, glasses, masks, chains, watches, shoes and backpacks.");
			none.getStyleClass().add("t-muted");
			none.setWrapText(true);
			content.getChildren().add(none);
		}
		panel.getChildren().add(scroll(content));
		boolean isEquipped = selected.isEmpty() ? equipped == null : selected.equals(equipped);
		if (!selected.isEmpty()) {
			panel.getChildren().add(actionBar(selectedType == null ? selected : selectedType.name(), isEquipped, () -> {
				net.veloclient.launcher.data.WearCatalog.equip(wearSlot, isEquipped ? null : selected);
				net.veloclient.launcher.data.WearCatalog.clearPreview();
				selectedWear = null;
				refresh.run();
			}, null));
		}
		return panel;
	}

	/** The free Color Burst / Confetti Rain colour. */
	private static Node freeColours() {
		Label label = new Label("Colour (Color Burst, Confetti Rain)");
		label.getStyleClass().add("page-subtitle");
		HBox swatches = new HBox(6);
		int current = KillFxCatalog.freeColor() & 0xFFFFFF;
		for (int i = 0; i < AuraEffects.PALETTE.length; i++) {
			int rgb = AuraEffects.PALETTE[i];
			Region cell = new Region();
			cell.setMinSize(22, 22);
			cell.setMaxSize(22, 22);
			cell.setStyle(String.format("-fx-background-color: #%06x; -fx-background-radius: 6; -fx-border-radius: 6; -fx-border-width: 2; -fx-cursor: hand;"
					+ " -fx-border-color: %s;", rgb, rgb == current ? "white" : "transparent"));
			javafx.scene.control.Tooltip.install(cell, new javafx.scene.control.Tooltip(AuraEffects.PALETTE_NAMES[i]));
			cell.setOnMouseClicked(e -> {
				KillFxCatalog.setFreeColor(0xFF000000 | rgb);
				for (Node other : swatches.getChildren()) {
					other.setStyle(other.getStyle().replaceAll("-fx-border-color: [^;]*;", "-fx-border-color: transparent;"));
				}
				cell.setStyle(cell.getStyle().replaceAll("-fx-border-color: [^;]*;", "-fx-border-color: white;"));
			});
			swatches.getChildren().add(cell);
		}
		return new VBox(6, label, swatches);
	}

	// Emotes

	private static String selectedEmoteId() {
		if (selectedEmote == null || !net.veloclient.launcher.data.EmoteCatalog.owns(selectedEmote)) {
			String onSlot = net.veloclient.launcher.data.EmoteCatalog.slot(selectedWheelSlot);
			selectedEmote = onSlot != null ? onSlot : net.veloclient.launcher.data.EmoteCatalog.owned().stream().findFirst().map(net.veloclient.launcher.data.EmoteCatalog.EmoteType::id).orElse(null);
		}
		return selectedEmote == null ? "" : selectedEmote;
	}

	/** Your emote wheel: pick a slot (1-8), then one of your emotes to put there. The game shows it when you hold B. */
	private static Node emotesPanel(Host host, Runnable refresh) {
		List<net.veloclient.launcher.data.EmoteCatalog.EmoteType> owned = net.veloclient.launcher.data.EmoteCatalog.owned();
		String selected = selectedEmoteId();
		VBox panel = panelShell("Emotes", owned.size(), storeLink(host, "Get more"));
		Label hint = new Label("Your emote wheel (hold B in game) - pick a slot, then an emote.");
		hint.getStyleClass().add("t-muted");
		hint.setWrapText(true);
		FlowPane wheel = new FlowPane(8, 8);
		for (int i = 0; i < net.veloclient.launcher.data.EmoteCatalog.SLOTS; i++) {
			int index = i;
			String id = net.veloclient.launcher.data.EmoteCatalog.slot(i);
			String name = id == null ? "Empty" : net.veloclient.launcher.data.EmoteCatalog.byId(id).map(net.veloclient.launcher.data.EmoteCatalog.EmoteType::name).orElse(id);
			Button b = new Button((i + 1) + "  " + name);
			b.getStyleClass().addAll("button-sm", index == selectedWheelSlot ? "primary-button" : "ghost-button");
			b.setMinWidth(112);
			b.setOnAction(e -> {
				selectedWheelSlot = index;
				if (id != null) {
					selectedEmote = id;
				}
				refresh.run();
			});
			wheel.getChildren().add(b);
		}
		FlowPane grid = StoreTiles.grid(118, 10);
		for (net.veloclient.launcher.data.EmoteCatalog.EmoteType emote : owned) {
			int onSlot = net.veloclient.launcher.data.EmoteCatalog.slotOf(emote.id());
			grid.getChildren().add(cell(EmoteFigureFx.create(emote, 150, 104), emote.accent(),
					onSlot >= 0 ? (onSlot + 1) + " · " + emote.name() : emote.name(), onSlot >= 0, emote.id().equals(selected), () -> {
						selectedEmote = emote.id();
						net.veloclient.launcher.data.EmoteCatalog.setSlot(selectedWheelSlot, emote.id());
						Toasts.show(grid, emote.name() + " is on slot " + (selectedWheelSlot + 1) + " - in your next game");
						refresh.run();
					}));
		}
		VBox content = new VBox(16, hint, wheel, grid);
		if (owned.isEmpty()) {
			content.getChildren().setAll(emptyState("No emotes yet", "Wave, dance, flex and celebrate - each emote with its own particles and sounds.", host,
					"Browse emotes"));
		}
		panel.getChildren().add(scroll(content));
		if (net.veloclient.launcher.data.EmoteCatalog.slot(selectedWheelSlot) != null) {
			String name = net.veloclient.launcher.data.EmoteCatalog.byId(net.veloclient.launcher.data.EmoteCatalog.slot(selectedWheelSlot)).map(net.veloclient.launcher.data.EmoteCatalog.EmoteType::name).orElse("");
			panel.getChildren().add(actionBar("Slot " + (selectedWheelSlot + 1) + ": " + name, true, () -> {
				net.veloclient.launcher.data.EmoteCatalog.setSlot(selectedWheelSlot, null);
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
		MinecraftSession session = host.session();
		// 2026-11: no separate "On your account" tile - the account's skin is a normal entry (auto-copied below),
		// marked as worn
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
			Node bar = actionBar(chosen.name() + (chosen.slim() ? "  ·  slim" : "  ·  classic"), isEquipped, () -> {
				if (isEquipped) {
					return;
				}
				if (host.session() == null) {
					status.setText("Sign in first.");
					return;
				}
				status.setText("Uploading " + chosen.name() + " to your Minecraft account...");
				host.withFreshSession(fresh -> SkinsSection.run(() -> {
					SkinLibrary.equip(chosen, fresh.minecraftAccessToken(), fresh.uuid());
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
			panel.getChildren().add(bar);
		}
		// 2026-11: the skin on your account is kept in the list by itself - added once, and only if it isn't there yet.
		autoCopyAccountSkin(session, refresh);
		return panel;
	}

	/** Account uuids whose skin was already checked against the library in this launcher run. */
	private static final java.util.Set<String> SKIN_COPY_CHECKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static void autoCopyAccountSkin(MinecraftSession session, Runnable refresh) {
		if (session == null || !SKIN_COPY_CHECKED.add(session.uuid())) {
			return;
		}
		Thread.ofVirtual().name("velo-skin-autocopy").start(() -> {
			try {
				net.veloclient.launcher.auth.SkinFetcher.SkinData data = net.veloclient.launcher.auth.SkinFetcher.fetch(session);
				if (data == null) {
					return;
				}
				SkinLibrary.Skin same = SkinLibrary.findSame(data.pngBytes());
				if (same == null) {
					same = SkinLibrary.addPng(session.username(), data.pngBytes(), data.slim());
				} else if (same.id().equals(SkinLibrary.equippedId())) {
					return;
				}
				SkinLibrary.markEquipped(same); // the one on the account is the one worn
				javafx.application.Platform.runLater(refresh);
			} catch (Exception ignored) {
				// offline / unreadable: try again next launcher start
				SKIN_COPY_CHECKED.remove(session.uuid());
			}
		});
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
