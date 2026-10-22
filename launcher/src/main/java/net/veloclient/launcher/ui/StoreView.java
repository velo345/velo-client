package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.AuraCatalog;
import net.veloclient.launcher.data.StoreCatalog;
import net.veloclient.launcher.data.StoreItem;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.data.WingsCatalog;
import net.veloclient.launcher.social.StoreApi;
import net.veloclient.launcher.theme.LauncherTheme;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * The Store: Featured (the current collection on your own skin in 3D, the daily picks, Velo+ and coins, auras,
 * capes), then a tab per kind - Wings, Capes, Auras, Bundles - and Velo+. Every item is the same tile with its
 * rarity, price or Owned / Equipped.
 */
public final class StoreView {

	public interface Host {
		Stage owner();

		LauncherTheme theme();

		MinecraftSession session();

		void openItem(StoreItem item);

		void rebuild();

		/** Opens the Velo Coins page (buy coins / free coins). */
		default void openCoins() {
		}

		/** Opens a pair of wings' page. */
		default void openWings(WingsCatalog.WingType type) {
		}

		/** Opens the Locker (equip what you own). */
		default void openCosmetics() {
		}

		/** Opens the owner panel (owners only). */
		default void openOwner() {
		}

		/** Opens an aura's page. */
		default void openAura(AuraCatalog.AuraType type) {
		}
	}

	private StoreView() {
	}

	private enum Page { FEATURED, WINGS, CAPES, AURAS, BUNDLES, PLUS, WISHLIST }

	/** Which tab was open last (kept while the launcher runs). */
	private static Page page = Page.FEATURED;

	public static void showAurasTab() {
		openBundle = null;
		page = Page.AURAS;
	}

	public static void showPlusTab() {
		openBundle = null;
		page = Page.PLUS;
	}

	/** Dev (screenshot tour): the Bundles tab, or the first bundle's own page. */
	public static void showBundlesForTour(boolean openFirst) {
		page = Page.BUNDLES;
		openBundle = openFirst && !bundles().isEmpty() ? bundles().get(0).id() : null;
	}

	/** Back to the tabs (the top bar's Store button): closes an open bundle page. */
	public static void closeSubpage() {
		openBundle = null;
	}

	public static void showFeaturedTab() {
		openBundle = null;
		page = Page.FEATURED;
	}

	/** Each pair's own colour ({@code accent} in wings.json) - the same accents the in-game Store uses. */
	static String accent(String wingId) {
		return WingsCatalog.byId(wingId).map(WingsCatalog.WingType::accent).orElse("#ff4d4d");
	}

	public static Node build(Host host) {
		Label heading = new Label("Store");
		heading.getStyleClass().add("page-title");
		heading.setPadding(new Insets(0, 14, 10, 0));
		HBox tabs = new HBox(24);
		tabs.setAlignment(Pos.BOTTOM_LEFT);
		String[] labels = {"Featured", "Wings", "Capes", "Auras", "Bundles", "Velo+"};
		for (Page p : Page.values()) {
			if (p == Page.WISHLIST) {
				continue;
			}
			Button tab = new Button(labels[p.ordinal()]);
			tab.getStyleClass().setAll("button", "utab", "no-motion");
			if (p == page) {
				tab.getStyleClass().add("utab-active");
			}
			if (p == Page.PLUS) {
				tab.setStyle("-fx-text-fill: -velo-accent-text;");
			}
			if (p == Page.AURAS) {
				Label badge = new Label("NEW");
				badge.getStyleClass().addAll("badge", "badge-new");
				badge.setStyle("-fx-font-size: 9px;");
				tab.setGraphic(badge);
				tab.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
				tab.setGraphicTextGap(6);
			}
			tab.setOnAction(e -> {
				page = p;
				openBundle = null;
				host.rebuild();
			});
			tabs.getChildren().add(tab);
		}
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button locker = new Button("My Locker");
		locker.setGraphic(Icons.icon("locker", 15, Color.web("#C9C9D1")));
		locker.getStyleClass().addAll("ghost-button", "button-sm");
		locker.setOnAction(e -> host.openCosmetics());
		HBox header = new HBox(heading, tabs, spacer);
		header.setAlignment(Pos.BOTTOM_LEFT);
		header.getStyleClass().add("utab-row");
		if (StoreApi.isOwner()) {
			Button owner = new Button("Owner panel");
			owner.getStyleClass().addAll("ghost-button", "button-sm");
			owner.setOnAction(e -> host.openOwner());
			HBox.setMargin(owner, new Insets(0, 0, 8, 0));
			header.getChildren().add(owner);
		}
		// The wishlist: everything you hearted on an item page, in one place (with what it costs together).
		long wished = wishedItems().size();
		Button wishlist = new Button(wished > 0 ? "Wishlist  " + wished : "Wishlist");
		wishlist.setGraphic(Icons.icon("heart", 14, Color.web(page == Page.WISHLIST ? "#FF6B7A" : "#C9C9D1")));
		wishlist.getStyleClass().addAll("ghost-button", "button-sm");
		if (page == Page.WISHLIST) {
			wishlist.setStyle("-fx-border-color: rgba(255,107,122,0.6);");
		}
		wishlist.setOnAction(e -> {
			page = page == Page.WISHLIST ? Page.FEATURED : Page.WISHLIST;
			openBundle = null;
			host.rebuild();
		});
		HBox.setMargin(wishlist, new Insets(0, 8, 8, 0));
		header.getChildren().add(wishlist);
		HBox.setMargin(locker, new Insets(0, 0, 8, 0));
		header.getChildren().add(locker);

		VBox body = new VBox(26);
		body.setPadding(new Insets(4, 4, 24, 0));
		Bundle bundle = openBundle == null ? null : bundles().stream().filter(b -> b.id().equals(openBundle)).findFirst().orElse(null);
		if (bundle != null) {
			body.getChildren().add(bundlePage(host, bundle));
		} else switch (page) {
			case FEATURED -> buildFeatured(host, body);
			case WINGS -> buildWings(host, body);
			case CAPES -> buildCapes(host, body);
			case AURAS -> buildAuras(host, body);
			case BUNDLES -> buildBundles(host, body);
			case PLUS -> body.getChildren().add(plusPage());
			case WISHLIST -> buildWishlist(host, body);
		}
		refreshWallet(host, body);

		ScrollPane scroll = new ScrollPane(body);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox page = new VBox(18, header, scroll);
		page.getStyleClass().add("page");
		return page;
	}

	/** The wallet lives on the server: refresh it, and redraw when it turns out you own something new. */
	private static void refreshWallet(Host host, Node anchor) {
		boolean wasOwner = StoreApi.isOwner();
		java.util.Set<String> ownedBefore = StoreApi.owned();
		CompletableFuture.runAsync(() -> {
			try {
				StoreApi.wallet();
				net.veloclient.launcher.data.StorePurchase.restoreMissing();
				if (StoreApi.cachedCatalog() == null) {
					StoreApi.catalog();
				}
			} catch (StoreApi.StoreError ignored) {
				// Offline / signed out: cached values stay.
			}
			Platform.runLater(() -> {
				boolean changed = !wasOwner && StoreApi.isOwner() || !StoreApi.owned().equals(ownedBefore);
				if (changed && anchor.getScene() != null) {
					host.rebuild();
				}
			});
		}, Executors.newVirtualThreadPerTaskExecutor());
	}

	// ---- Featured ----

	private static void buildFeatured(Host host, VBox body) {
		var forSale = WingsCatalog.forSale();
		var collection = WingsCatalog.featured().orElse(null);
		var collectionWings = collection == null ? forSale : WingsCatalog.inCollection(collection.id());
		if (collectionWings.isEmpty()) {
			collectionWings = forSale;
		}
		if (!collectionWings.isEmpty()) {
			body.getChildren().add(featuredHero(host, collection, collectionWings));
		}
		body.getChildren().add(dailyPicks(host));
		Node bundles = featuredBundles(host);
		if (bundles != null) {
			body.getChildren().add(bundles);
		}
		body.getChildren().add(plusAndCoinsRow(host));
		List<AuraCatalog.AuraType> auras = AuraCatalog.all().stream().filter(AuraCatalog.AuraType::inStore).limit(6).toList();
		if (!auras.isEmpty()) {
			body.getChildren().add(section("Auras", "Animated effects around you · your colour, size and speed", "All auras", () -> {
				page = Page.AURAS;
				host.rebuild();
			}, auraGrid(host, auras, 180)));
		}
		List<StoreItem> capes = StoreCatalog.all().stream().filter(StoreItem::inStore).limit(6).toList();
		if (!capes.isEmpty()) {
			body.getChildren().add(section("Animated capes", null, "All capes", () -> {
				page = Page.CAPES;
				host.rebuild();
			}, capeGrid(host, capes, 180)));
		}
		body.getChildren().add(discordBanner());
	}

	private static Node section(String title, String subtitle, String linkText, Runnable onLink, Node content) {
		Label t = new Label(title);
		t.getStyleClass().add("t-title-sm");
		HBox head = new HBox(12, t);
		head.setAlignment(Pos.BASELINE_LEFT);
		if (subtitle != null) {
			Label sub = new Label(subtitle);
			sub.getStyleClass().add("t-muted");
			head.getChildren().add(sub);
		}
		if (linkText != null) {
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			Label link = new Label(linkText);
			link.getStyleClass().add("t-link");
			link.setOnMouseClicked(e -> onLink.run());
			head.getChildren().addAll(spacer, link);
		}
		return new VBox(14, head, content);
	}

	/**
	 * The banner: the collection's name, its countdown and description on the left; your skin in 3D wearing each
	 * pair in turn on the right, with the pairs as thumbnails to pick from.
	 */
	private static Node featuredHero(Host host, WingsCatalog.Collection collection, List<WingsCatalog.WingType> wings) {
		Long until = wings.stream().map(WingsCatalog.WingType::availableUntil).filter(Objects::nonNull).min(Long::compare).orElse(null);
		Label tag = new Label(collection == null ? "NEW COLLECTION" : collection.tag().toUpperCase(Locale.ROOT));
		tag.getStyleClass().addAll("badge", "badge-violet");
		Label countdown = new Label(until == null ? "" : "Ends in " + countdown(until));
		countdown.getStyleClass().add("t-mono");
		HBox tagRow = new HBox(10, tag, countdown);
		tagRow.setAlignment(Pos.CENTER_LEFT);
		Label name = new Label(collection == null ? "Wings" : collection.name());
		name.getStyleClass().add("t-display");
		name.setStyle("-fx-font-size: 56px;");
		Label description = new Label(collection == null ? "Animated 3D wings every Velo player sees." : collection.description());
		description.getStyleClass().add("t-body");
		description.setStyle("-fx-font-size: 15px;");
		description.setWrapText(true);
		description.setMaxWidth(440);
		int[] index = {0};
		Button shop = new Button("Shop the collection");
		shop.getStyleClass().addAll("primary-button", "button-lg");
		shop.setOnAction(e -> {
			page = Page.WINGS;
			host.rebuild();
		});
		Button tryOn = new Button("Try on");
		tryOn.setGraphic(Icons.icon("eye", 16, Color.web("#E4E4E8")));
		tryOn.getStyleClass().addAll("glass-button", "button-lg");
		tryOn.setOnAction(e -> host.openWings(wings.get(index[0])));
		int from = wings.stream().mapToInt(w -> StoreApi.price(w.itemId(), w.price())).min().orElse(0);
		Label fromLabel = new Label("from");
		fromLabel.getStyleClass().add("t-muted");
		HBox fromBox = new HBox(6, fromLabel, StoreTiles.price(from, null, 14));
		fromBox.setAlignment(Pos.CENTER_LEFT);
		HBox actions = new HBox(12, shop, tryOn, fromBox);
		actions.setAlignment(Pos.CENTER_LEFT);
		VBox text = new VBox(14, tagRow, name, description, actions);
		text.setAlignment(Pos.CENTER_LEFT);
		text.setPadding(new Insets(36, 20, 36, 44));
		text.setMinWidth(0);
		HBox.setHgrow(text, Priority.ALWAYS);

		StackPane art = new StackPane();
		art.setMinSize(320, 360);
		art.setPrefSize(360, 380);
		ImageView still = new ImageView(UiImages.wingPreview(wings.get(0).id()));
		still.setFitHeight(300);
		still.setPreserveRatio(true);
		art.getChildren().add(still);
		Wings3D[] model = new Wings3D[1];
		CosmeticUi.skin(host.session(), skin -> {
			Node viewer = skin == null ? null : PlayerSkin3DView.createWingsShowcase(skin.pngBytes(), skin.slim(), w -> {
				model[0] = w.motion(Wings3D.Motion.FLY);
				w.show(wings.get(index[0]).id());
			});
			if (viewer != null) {
				art.getChildren().setAll(viewer);
			}
		});

		VBox thumbs = new VBox(10);
		thumbs.setAlignment(Pos.CENTER);
		thumbs.setPadding(new Insets(0, 26, 0, 0));
		List<StackPane> thumbNodes = new ArrayList<>();
		HBox banner = new HBox(text, art, thumbs);
		banner.setAlignment(Pos.CENTER_LEFT);
		banner.setMinHeight(360);
		java.util.function.IntConsumer select = i -> {
			index[0] = i;
			var type = wings.get(i);
			banner.setStyle("-fx-background-color: radial-gradient(center 72% 58%, radius 55%, " + accent(type.id())
					+ "55 0%, transparent 100%), -velo-surface; -fx-background-radius: 20; -fx-border-color: -velo-hairline; -fx-border-radius: 20;");
			for (int t = 0; t < thumbNodes.size(); t++) {
				thumbNodes.get(t).setStyle(thumbStyle(t == i));
			}
			if (model[0] != null) {
				model[0].show(type.id());
			} else {
				still.setImage(UiImages.wingPreview(type.id()));
			}
		};
		for (int i = 0; i < Math.min(4, wings.size()); i++) {
			int at = i;
			ImageView picture = new ImageView(UiImages.wingPreview(wings.get(i).id()));
			picture.setFitWidth(62);
			picture.setFitHeight(62);
			picture.setPreserveRatio(true);
			StackPane thumb = new StackPane(picture);
			thumb.setMinSize(76, 76);
			thumb.setMaxSize(76, 76);
			thumb.setStyle(thumbStyle(i == 0));
			javafx.scene.control.Tooltip.install(thumb, new javafx.scene.control.Tooltip(wings.get(i).name()));
			thumb.setOnMouseClicked(e -> select.accept(at));
			thumbNodes.add(thumb);
			thumbs.getChildren().add(thumb);
		}
		select.accept(0);
		Timeline cycle = new Timeline(new KeyFrame(Duration.seconds(5), e -> select.accept((index[0] + 1) % wings.size())));
		cycle.setCycleCount(Animation.INDEFINITE);
		Timeline tick = new Timeline(new KeyFrame(Duration.seconds(30), e -> {
			if (until != null) {
				countdown.setText("Ends in " + countdown(until));
			}
		}));
		tick.setCycleCount(Animation.INDEFINITE);
		banner.sceneProperty().addListener((obs, o, scene) -> {
			if (scene == null) {
				cycle.stop();
				tick.stop();
			} else {
				cycle.play();
				tick.play();
			}
		});
		banner.widthProperty().addListener((o, a, w) -> {
			boolean wide = w.doubleValue() > 860;
			thumbs.setVisible(wide);
			thumbs.setManaged(wide);
		});
		Rectangle clip = new Rectangle();
		clip.setArcWidth(40);
		clip.setArcHeight(40);
		clip.widthProperty().bind(banner.widthProperty());
		clip.heightProperty().bind(banner.heightProperty());
		banner.setClip(clip);
		return banner;
	}

	private static String thumbStyle(boolean selected) {
		return "-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 12; -fx-border-radius: 12; -fx-cursor: hand;"
				+ " -fx-border-width: 2; -fx-border-color: " + (selected ? "-velo-text" : "rgba(255,255,255,0.12)") + ";";
	}

	static String countdown(long untilMillis) {
		long minutes = Math.max(0, (untilMillis - System.currentTimeMillis()) / 60_000);
		long days = minutes / 1440;
		long hours = (minutes % 1440) / 60;
		long mins = minutes % 60;
		return days > 0 ? String.format(Locale.ROOT, "%dd %02dh %02dm", days, hours, mins) : String.format(Locale.ROOT, "%02dh %02dm", hours, mins);
	}

	/**
	 * Four items a day. The server picks them (with a deal on some) when it sends "daily" in the catalog;
	 * otherwise the same four come out of today's date for everyone, without a discount.
	 */
	private static Node dailyPicks(Host host) {
		List<Node> tiles = new ArrayList<>();
		JsonObject catalog = StoreApi.cachedCatalog();
		long endsAt = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
		List<String> ids = new ArrayList<>();
		if (catalog != null && catalog.has("daily") && catalog.get("daily").isJsonObject()) {
			JsonObject daily = catalog.getAsJsonObject("daily");
			if (daily.has("items")) {
				daily.getAsJsonArray("items").forEach(e -> ids.add(e.getAsString()));
			}
			if (daily.has("endsAt")) {
				endsAt = daily.get("endsAt").getAsLong();
			}
		}
		if (ids.isEmpty()) {
			List<String> pool = new ArrayList<>();
			WingsCatalog.forSale().forEach(w -> pool.add(w.itemId()));
			StoreCatalog.forSale().forEach(i -> pool.add(i.id()));
			AuraCatalog.forSale().forEach(a -> pool.add(a.itemId()));
			java.util.Collections.sort(pool);
			java.util.Collections.shuffle(pool, new java.util.Random(LocalDate.now(ZoneOffset.UTC).toEpochDay() * 31L + 7));
			ids.addAll(pool.subList(0, Math.min(4, pool.size())));
		}
		for (String id : ids) {
			Node tile = tileFor(host, id);
			if (tile != null) {
				tiles.add(tile);
			}
		}
		FlowPane grid = StoreTiles.grid(220, 16);
		grid.getChildren().addAll(tiles);
		UiMotion.stagger(grid, 24);
		long ends = endsAt;
		Label refresh = new Label("refreshes in " + countdown(ends));
		refresh.getStyleClass().add("t-mono");
		Label title = new Label("Daily picks");
		title.getStyleClass().add("t-title-sm");
		HBox head = new HBox(12, title, refresh);
		head.setAlignment(Pos.BASELINE_LEFT);
		Timeline tick = new Timeline(new KeyFrame(Duration.seconds(30), e -> refresh.setText("refreshes in " + countdown(ends))));
		tick.setCycleCount(Animation.INDEFINITE);
		grid.sceneProperty().addListener((obs, o, scene) -> {
			if (scene == null) {
				tick.stop();
			} else {
				tick.play();
			}
		});
		return new VBox(14, head, grid);
	}

	/** The tile for any store item id ("wings:..", "aura:..", or a cape id), or null if it's unknown here. */
	private static Node tileFor(Host host, String itemId) {
		var wings = WingsCatalog.all().stream().filter(w -> w.itemId().equals(itemId)).findFirst();
		if (wings.isPresent()) {
			return StoreTiles.wingsTile(wings.get(), () -> host.openWings(wings.get()));
		}
		var aura = AuraCatalog.all().stream().filter(a -> a.itemId().equals(itemId)).findFirst();
		if (aura.isPresent()) {
			return StoreTiles.auraTile(aura.get(), () -> host.openAura(aura.get()));
		}
		var cape = StoreCatalog.all().stream().filter(i -> i.id().equals(itemId)).findFirst();
		return cape.<Node>map(item -> StoreTiles.capeTile(item, () -> host.openItem(item))).orElse(null);
	}

	/** Velo+ teaser on the left, coin packs on the right. */
	private static Node plusAndCoinsRow(Host host) {
		ImageView badge = new ImageView(UiImages.resource("/net/veloclient/launcher/images/velo-plus-badge.png", 96, 60));
		Label pitch = new Label("Monthly coins, the Addons and the + badge");
		pitch.getStyleClass().add("t-heading");
		pitch.setStyle("-fx-font-size: 20px;");
		pitch.setWrapText(true);
		Label sub = new Label("Plus the VIP Discord role. Cancel any time.");
		sub.getStyleClass().add("t-body");
		VBox pitchBox = new VBox(6, pitch, sub);
		HBox.setHgrow(pitchBox, Priority.ALWAYS);
		Button get = new Button("Get Velo+");
		get.getStyleClass().add("primary-button");
		get.setMinWidth(Region.USE_PREF_SIZE);
		get.setOnAction(e -> {
			page = Page.PLUS;
			host.rebuild();
		});
		HBox plus = new HBox(24, badge, pitchBox, get);
		plus.setAlignment(Pos.CENTER_LEFT);
		plus.setPadding(new Insets(24, 28, 24, 28));
		plus.setStyle("-fx-background-color: linear-gradient(to right, derive(-velo-accent, -78%), -velo-surface 70%); -fx-background-radius: 18;"
				+ " -fx-border-color: -velo-accent-line; -fx-border-radius: 18; -fx-cursor: hand;");
		plus.setOnMouseClicked(e -> {
			page = Page.PLUS;
			host.rebuild();
		});
		HBox.setHgrow(plus, Priority.ALWAYS);

		Label coinsTitle = new Label("Velo Coins");
		coinsTitle.getStyleClass().add("t-heading");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Label free = new Label("Free coins");
		free.getStyleClass().add("t-link");
		free.setOnMouseClicked(e -> host.openCoins());
		HBox coinsHead = new HBox(coinsTitle, spacer, free);
		coinsHead.setAlignment(Pos.BASELINE_LEFT);
		HBox packs = new HBox(10);
		VBox coins = new VBox(14, coinsHead, packs);
		coins.getStyleClass().add("card");
		coins.setPadding(new Insets(18, 20, 18, 20));
		coins.setMinWidth(380);
		coins.setMaxWidth(420);
		fillPacks(host, packs, StoreApi.cachedCatalog());
		if (StoreApi.cachedCatalog() == null) {
			CompletableFuture.supplyAsync(() -> {
				try {
					return StoreApi.catalog();
				} catch (StoreApi.StoreError e) {
					return null;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(catalog -> Platform.runLater(() -> fillPacks(host, packs, catalog)));
		}
		HBox row = new HBox(16, plus, coins);
		row.widthProperty().addListener((o, a, w) -> {
			boolean wide = w.doubleValue() > 900;
			coins.setVisible(wide);
			coins.setManaged(wide);
		});
		return row;
	}

	private static void fillPacks(Host host, HBox packs, JsonObject catalog) {
		packs.getChildren().clear();
		if (catalog == null || !catalog.has("coinPacks")) {
			Label hint = new Label("Sign in to buy coins.");
			hint.getStyleClass().add("t-muted");
			packs.getChildren().add(hint);
			return;
		}
		String currency = catalog.has("currency") ? catalog.get("currency").getAsString() : "EUR";
		String symbol = currency.equals("EUR") ? "€" : currency.equals("USD") ? "$" : currency;
		JsonArray list = catalog.getAsJsonArray("coinPacks");
		int shown = 0;
		int best = 0;
		for (int i = 0; i < Math.min(3, list.size()); i++) {
			if (list.get(i).getAsJsonObject().get("bonus").getAsInt() > list.get(best).getAsJsonObject().get("bonus").getAsInt()) {
				best = i;
			}
		}
		for (JsonElement element : list) {
			if (shown == 3) {
				break;
			}
			JsonObject pack = element.getAsJsonObject();
			int total = pack.get("coins").getAsInt() + pack.get("bonus").getAsInt();
			Label amount = new Label(StoreTiles.format(total));
			amount.setStyle("-fx-text-fill: -velo-gold; -fx-font-family: 'Inter'; -fx-font-weight: bold; -fx-font-size: 15px;");
			Label price = new Label(String.format(Locale.ROOT, "%.2f %s", pack.get("price").getAsDouble(), symbol));
			price.getStyleClass().add("t-muted");
			VBox content = new VBox(3, amount, price);
			content.setAlignment(Pos.CENTER);
			StackPane card = new StackPane(content);
			card.setMinHeight(70);
			HBox.setHgrow(card, Priority.ALWAYS);
			card.setMaxWidth(Double.MAX_VALUE);
			boolean highlight = shown == best && pack.get("bonus").getAsInt() > 0;
			card.setStyle("-fx-background-radius: 12; -fx-border-radius: 12; -fx-cursor: hand; -fx-background-color: "
					+ (highlight ? "rgba(247,192,74,0.08); -fx-border-color: rgba(247,192,74,0.4);" : "-velo-surface-2; -fx-border-color: -velo-hairline;"));
			if (highlight) {
				Label bonus = new Label("+" + StoreTiles.format(pack.get("bonus").getAsInt()) + " BONUS");
				bonus.setStyle("-fx-background-color: -velo-gold; -fx-text-fill: -velo-gold-ink; -fx-background-radius: 4; -fx-padding: 1 6 1 6;"
						+ " -fx-font-family: 'Inter'; -fx-font-weight: bold; -fx-font-size: 9px;");
				StackPane.setAlignment(bonus, Pos.TOP_CENTER);
				StackPane.setMargin(bonus, new Insets(-8, 0, 0, 0));
				card.getChildren().add(bonus);
			}
			card.setOnMouseClicked(e -> host.openCoins());
			card.getStyleClass().add("motion-card");
			packs.getChildren().add(card);
			shown++;
		}
	}

	// ---- Tabs ----

	private static void buildWings(Host host, VBox body) {
		var wings = WingsCatalog.forSale();
		for (var collection : WingsCatalog.collections()) {
			var pairs = WingsCatalog.inCollection(collection.id());
			if (!pairs.isEmpty()) {
				body.getChildren().add(section(collection.name(), pairs.size() + " pairs of animated 3D wings", null, null, wingGrid(host, pairs)));
			}
		}
		var loose = wings.stream().filter(w -> WingsCatalog.collection(w.collection()).isEmpty()).toList();
		if (!loose.isEmpty()) {
			body.getChildren().add(section("More wings", loose.size() + " pairs", null, null, wingGrid(host, loose)));
		}
	}

	private static void buildCapes(Host host, VBox body) {
		var items = StoreCatalog.all().stream().filter(i -> i.inStore() || StoreOwnership.owns(i.id())).toList();
		body.getChildren().add(section("Animated capes", items.size() + " capes", null, null, capeGrid(host, items, 200)));
		body.getChildren().add(discordBanner());
	}

	private static void buildAuras(Host host, VBox body) {
		var auras = AuraCatalog.all().stream().filter(a -> a.inStore() || AuraCatalog.owns(a.id())).toList();
		if (auras.isEmpty()) {
			Label empty = new Label("No auras in the Store right now - check back soon.");
			empty.getStyleClass().add("t-muted");
			body.getChildren().add(empty);
			return;
		}
		body.getChildren().add(section("Auras", "Animated effects around you · your colour, size and speed", null, null, auraGrid(host, auras, 200)));
	}

	/** Wishlisted items you don't own yet (owned ones drop off by themselves). */
	private static List<String> wishedItems() {
		return StoreApi.wishlist().stream().filter(id -> !StoreApi.owned().contains(id)).sorted().toList();
	}

	private static void buildWishlist(Host host, VBox body) {
		List<String> ids = wishedItems();
		if (ids.isEmpty()) {
			VBox empty = new VBox(8);
			empty.setAlignment(Pos.CENTER);
			empty.setPadding(new Insets(80, 0, 0, 0));
			StackPane chip = new StackPane(Icons.icon("heart", 22, Color.web("#C9C9D1")));
			chip.setStyle("-fx-background-color: -velo-surface-2; -fx-background-radius: 14;");
			chip.setMinSize(52, 52);
			chip.setMaxSize(52, 52);
			Label title = new Label("Your wishlist is empty");
			title.getStyleClass().add("section-title");
			Label hint = new Label("Press Wishlist on any wings, cape or aura to keep it here - and to show friends what to gift you.");
			hint.getStyleClass().add("t-muted");
			empty.getChildren().addAll(chip, title, hint);
			body.getChildren().add(empty);
			return;
		}
		int total = ids.stream().mapToInt(StoreView::itemPrice).sum();
		var wings = WingsCatalog.all().stream().filter(w -> ids.contains(w.itemId())).toList();
		var capes = StoreCatalog.all().stream().filter(i -> ids.contains(i.id())).toList();
		var auras = AuraCatalog.all().stream().filter(a -> ids.contains(a.itemId())).toList();
		String subtitle = ids.size() + (ids.size() == 1 ? " item" : " items") + " · " + String.format(java.util.Locale.ROOT, "%,d", total) + " coins together";
		boolean first = true;
		if (!wings.isEmpty()) {
			body.getChildren().add(section("Wishlist", subtitle, null, null, wingGrid(host, wings)));
			first = false;
		}
		if (!capes.isEmpty()) {
			body.getChildren().add(section(first ? "Wishlist" : "Capes", first ? subtitle : capes.size() + " capes", null, null, capeGrid(host, capes, 200)));
			first = false;
		}
		if (!auras.isEmpty()) {
			body.getChildren().add(section(first ? "Wishlist" : "Auras", first ? subtitle : auras.size() + " auras", null, null, auraGrid(host, auras, 200)));
		}
	}

	/** A bundle: the server's offer (one price), or offline a collection with what its items cost together ({@code price} -1). */
	record Bundle(String id, String name, String description, List<String> items, int price) {
	}

	/** The bundle whose page is open, or null for the store's tabs. */
	private static String openBundle;

	static List<Bundle> bundles() {
		List<Bundle> out = new ArrayList<>();
		JsonObject catalog = StoreApi.cachedCatalog();
		if (catalog != null && catalog.has("bundles") && catalog.get("bundles").isJsonArray()) {
			for (JsonElement element : catalog.getAsJsonArray("bundles")) {
				JsonObject b = element.getAsJsonObject();
				List<String> ids = new ArrayList<>();
				b.getAsJsonArray("items").forEach(e -> ids.add(e.getAsString()));
				out.add(new Bundle(b.get("id").getAsString(), b.get("name").getAsString(),
						b.has("description") && !b.get("description").isJsonNull() ? b.get("description").getAsString() : "", ids, b.get("price").getAsInt()));
			}
			return out;
		}
		for (var collection : WingsCatalog.collections()) {
			var pairs = WingsCatalog.inCollection(collection.id()).stream().filter(WingsCatalog.WingType::inStore).toList();
			if (pairs.size() > 1) {
				out.add(new Bundle(collection.id(), collection.name(), collection.description(),
						pairs.stream().map(WingsCatalog.WingType::itemId).toList(), -1));
			}
		}
		return out;
	}

	/** The regular price of any store item id, or 0 if it's unknown here. */
	private static int itemPrice(String itemId) {
		var wings = WingsCatalog.all().stream().filter(w -> w.itemId().equals(itemId)).findFirst();
		if (wings.isPresent()) {
			return StoreApi.price(itemId, wings.get().price());
		}
		var aura = AuraCatalog.all().stream().filter(a -> a.itemId().equals(itemId)).findFirst();
		if (aura.isPresent()) {
			return StoreApi.price(itemId, aura.get().price());
		}
		return StoreCatalog.byId(itemId).map(i -> StoreApi.price(itemId, i.priceCoins())).orElse(0);
	}

	/** A small square picture of any store item (wings, cape or aura), or null if it's unknown here. */
	private static Node itemArt(String itemId, double size) {
		var wings = WingsCatalog.all().stream().filter(w -> w.itemId().equals(itemId)).findFirst();
		if (wings.isPresent()) {
			return framed(StoreTiles.wingsArt(wings.get(), size * 0.78), wings.get().accent(), size);
		}
		var aura = AuraCatalog.all().stream().filter(a -> a.itemId().equals(itemId)).findFirst();
		if (aura.isPresent()) {
			return framed(StoreTiles.auraArt(aura.get(), size, size), aura.get().accent(), size);
		}
		var cape = StoreCatalog.byId(itemId);
		return cape.map(item -> framed(StoreTiles.capeArt(item, null, size * 0.8), "#8a7dff", size)).orElse(null);
	}

	private static Node framed(Node art, String accent, double size) {
		StackPane box = new StackPane(art);
		box.setStyle("-fx-background-color: radial-gradient(center 50% 60%, radius 70%, " + accent
				+ "44 0%, transparent 100%), -velo-surface-2; -fx-background-radius: 12;");
		box.setMinSize(size, size);
		box.setPrefSize(size, size);
		box.setMaxSize(size, size);
		Rectangle clip = new Rectangle(size, size);
		clip.setArcWidth(24);
		clip.setArcHeight(24);
		box.setClip(clip);
		return box;
	}

	private static void buildBundles(Host host, VBox body) {
		FlowPane grid = StoreTiles.grid(340, 16);
		for (Bundle bundle : bundles()) {
			grid.getChildren().add(bundleCard(host, bundle));
		}
		if (grid.getChildren().isEmpty()) {
			Label empty = new Label("No bundles right now.");
			empty.getStyleClass().add("t-muted");
			body.getChildren().add(empty);
			return;
		}
		body.getChildren().add(section("Bundles", "Whole sets for less", null, null, grid));
	}

	/** Bundles on Featured: up to three, with a link to the rest. */
	private static Node featuredBundles(Host host) {
		List<Bundle> all = bundles().stream().filter(b -> b.price() >= 0).toList();
		if (all.isEmpty()) {
			return null;
		}
		FlowPane grid = StoreTiles.grid(340, 16);
		all.stream().limit(3).forEach(b -> grid.getChildren().add(bundleCard(host, b)));
		return section("Bundles", "Whole sets for less", all.size() > 3 ? "All bundles" : null, () -> {
			page = Page.BUNDLES;
			host.rebuild();
		}, grid);
	}

	private static int separatePrice(Bundle bundle) {
		return bundle.items().stream().mapToInt(StoreView::itemPrice).sum();
	}

	/** A bundle: a strip of its items (three, then "+N"), name, price and saving; clicking it opens its page. */
	private static Node bundleCard(Host host, Bundle bundle) {
		HBox pictures = new HBox(8);
		int shown = 0;
		for (String id : bundle.items()) {
			if (shown == 3) {
				break;
			}
			Node art = itemArt(id, 70);
			if (art != null) {
				pictures.getChildren().add(art);
				shown++;
			}
		}
		int more = bundle.items().size() - shown;
		if (more > 0) {
			Label plus = new Label("+" + more);
			plus.getStyleClass().add("t-title-sm");
			StackPane box = new StackPane(plus);
			box.setMinSize(70, 70);
			box.setMaxSize(70, 70);
			box.setStyle("-fx-background-color: -velo-surface-2; -fx-background-radius: 12;");
			pictures.getChildren().add(box);
		}
		Label title = new Label(bundle.name());
		title.getStyleClass().add("t-heading");
		title.setWrapText(true);
		Label desc = new Label(bundle.description());
		desc.getStyleClass().add("t-muted");
		desc.setWrapText(true);
		desc.setManaged(!bundle.description().isBlank());
		long owned = bundle.items().stream().filter(StoreOwnership::owns).count();
		Label count = new Label(bundle.items().size() + " items" + (owned > 0 ? "  ·  you own " + owned : ""));
		count.getStyleClass().add("t-muted");
		int separate = separatePrice(bundle);
		HBox priceRow = new HBox(10);
		priceRow.setAlignment(Pos.CENTER_LEFT);
		if (bundle.price() >= 0) {
			priceRow.getChildren().add(StoreTiles.price(bundle.price(), separate > bundle.price() ? separate : null, 16));
			if (separate > bundle.price()) {
				Label save = new Label("save " + Math.round(100.0 * (separate - bundle.price()) / separate) + "%");
				save.getStyleClass().addAll("badge", "badge-green");
				priceRow.getChildren().add(save);
			}
		} else {
			Label together = new Label("Together");
			together.getStyleClass().add("t-muted");
			priceRow.getChildren().addAll(together, StoreTiles.price(separate, null, 15));
		}
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button view = new Button("View bundle");
		view.getStyleClass().add("ghost-button");
		view.setMinWidth(Region.USE_PREF_SIZE);
		view.setOnAction(e -> openBundlePage(host, bundle));
		priceRow.getChildren().addAll(spacer, view);
		pictures.setMinWidth(0);
		VBox card = new VBox(12, pictures, title, desc, count, priceRow);
		card.getStyleClass().addAll("card", "card-hover");
		card.setPadding(new Insets(18));
		card.setMinWidth(0);
		card.setOnMouseClicked(e -> openBundlePage(host, bundle));
		return card;
	}

	private static void openBundlePage(Host host, Bundle bundle) {
		openBundle = bundle.id();
		host.rebuild();
	}

	/** A bundle's own page: what it costs, the buy button, and only the items in it, each opening its own page. */
	private static Node bundlePage(Host host, Bundle bundle) {
		Button back = new Button("Bundles", Icons.icon("chevron-left", 14, Color.web("#C9C9D1")));
		back.getStyleClass().addAll("ghost-button", "button-sm");
		back.setOnAction(e -> {
			openBundle = null;
			host.rebuild();
		});
		Label overline = new Label("BUNDLE  ·  " + bundle.items().size() + " ITEMS");
		overline.getStyleClass().add("t-overline");
		Label title = new Label(bundle.name());
		title.getStyleClass().add("t-title");
		title.setWrapText(true);
		Label desc = new Label(bundle.description());
		desc.getStyleClass().add("t-body");
		desc.setWrapText(true);
		desc.setManaged(!bundle.description().isBlank());
		int separate = separatePrice(bundle);
		long owned = bundle.items().stream().filter(StoreOwnership::owns).count();
		HBox priceRow = new HBox(12);
		priceRow.setAlignment(Pos.CENTER_LEFT);
		Label status = new Label();
		status.getStyleClass().add("t-muted");
		status.setWrapText(true);
		if (bundle.price() >= 0) {
			priceRow.getChildren().add(StoreTiles.price(bundle.price(), separate > bundle.price() ? separate : null, 22));
			if (separate > bundle.price()) {
				Label save = new Label("save " + Math.round(100.0 * (separate - bundle.price()) / separate) + "%");
				save.getStyleClass().addAll("badge", "badge-green");
				priceRow.getChildren().add(save);
			}
			Button buy = new Button(owned == bundle.items().size() ? "Owned" : owned > 0 ? "Buy the rest" : "Buy bundle");
			buy.getStyleClass().addAll("buy-button", "button-lg");
			buy.setMinWidth(Region.USE_PREF_SIZE);
			buy.setDisable(owned == bundle.items().size());
			buy.setOnAction(e -> buyBundle(host, bundle, buy, status));
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			priceRow.getChildren().addAll(spacer, buy);
		} else {
			Label together = new Label("Together");
			together.getStyleClass().add("t-muted");
			priceRow.getChildren().addAll(together, StoreTiles.price(separate, null, 20));
		}
		if (owned > 0 && owned < bundle.items().size()) {
			status.setText("You own " + owned + " of these already - you only pay for the rest.");
		}
		VBox head = new VBox(8, back, overline, title, desc, priceRow, status);
		VBox.setMargin(priceRow, new Insets(8, 0, 0, 0));
		head.getStyleClass().add("card");
		head.setPadding(new Insets(22, 26, 22, 26));

		FlowPane grid = StoreTiles.grid(210, 16);
		for (String id : bundle.items()) {
			Node tile = tileFor(host, id);
			if (tile != null) {
				grid.getChildren().add(tile);
			}
		}
		UiMotion.stagger(grid, 24);
		return new VBox(24, head, section("In this bundle", "Click one to see it up close", null, null, grid));
	}

	private static void buyBundle(Host host, Bundle bundle, Button buy, Label status) {
		buy.setDisable(true);
		status.setText("Buying...");
		CompletableFuture.supplyAsync(() -> {
			try {
				StoreApi.buy("bundle:" + bundle.id());
				return (String) null;
			} catch (StoreApi.StoreError error) {
				return error.getMessage();
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(error -> Platform.runLater(() -> {
			if (error == null) {
				Toasts.show(buy, bundle.name() + " added to your Locker");
				host.rebuild();
			} else {
				buy.setDisable(false);
				status.setText(error);
			}
		}));
	}

	private static FlowPane wingGrid(Host host, List<WingsCatalog.WingType> wings) {
		FlowPane grid = StoreTiles.grid(210, 16);
		for (var type : wings) {
			grid.getChildren().add(StoreTiles.wingsTile(type, () -> host.openWings(type)));
		}
		UiMotion.stagger(grid, 24);
		return grid;
	}

	private static FlowPane capeGrid(Host host, List<StoreItem> items, double minTile) {
		FlowPane grid = StoreTiles.grid(minTile, 16);
		for (StoreItem item : items) {
			grid.getChildren().add(StoreTiles.capeTile(item, () -> host.openItem(item)));
		}
		UiMotion.stagger(grid, 24);
		return grid;
	}

	private static FlowPane auraGrid(Host host, List<AuraCatalog.AuraType> auras, double minTile) {
		FlowPane grid = StoreTiles.grid(minTile, 16);
		for (var aura : auras) {
			grid.getChildren().add(StoreTiles.auraTile(aura, () -> host.openAura(aura)));
		}
		UiMotion.stagger(grid, 24);
		return grid;
	}

	// ---- Velo+ ----

	/** What Velo+ adds, for the perk cards under the price: {icon, title, text}. */
	private static final String[][] PLUS_FEATURES = {
			{"globe", "World Hosting", "Play your singleplayer world with friends from anywhere - no port forwarding. Invite friends, share a code or go public in Discover. Friends join free."},
			{"download", "Cloud Sync & restore points", "Settings, HUD layouts, profiles, waypoints and keybinds on every PC. Roll back to any of 30 restore points."},
			{"clips", "Clips Pro", "A 2-5 minute replay buffer at 1080p60 with sound, automatic clips on kills and wins, share links (kept 60 days) and sending clips to friends."},
			{"sparkles", "Name Styles", "21 animated effects, your colours, 9 fonts and icons - on your name in chat, the tab list and above your head."},
			{"cape", "Animated custom capes", "Import your own GIF cape and every Velo player sees it move."},
			{"image", "Your Home background", "Put your own picture or animated GIF on the launcher's Home screen instead of the island - always scaled to fill the card."},
			{"gift", "Velo Coins every month", "Spend them on wings, capes and auras in the Store."},
			{"crown", "The + badge & VIP role", "A red plus next to your name for every Velo player, and the VIP role on Discord."}};

	/**
	 * Velo+: the pitch, price and subscribe button on the left, a Free vs Velo+ comparison on the right, the
	 * perks underneath. Price, monthly coins and your membership come from the server, off the UI thread.
	 */
	private static Node plusPage() {
		ImageView badge = new ImageView(UiImages.resource("/net/veloclient/launcher/images/velo-plus-badge.png", 120, 72));
		Label headline = new Label("Play more.\nUnlock everything.");
		headline.getStyleClass().add("t-display");
		headline.setStyle("-fx-font-size: 46px;");
		Label pitch = new Label("Host your world for friends, your setup in the cloud, pro clips, a name that moves and a monthly coin drop.");
		pitch.getStyleClass().add("t-body");
		pitch.setStyle("-fx-font-size: 15px;");
		pitch.setWrapText(true);
		Label price = new Label("...");
		price.getStyleClass().add("t-display");
		price.setStyle("-fx-font-size: 36px;");
		Label per = new Label("per month · cancel any time");
		per.getStyleClass().add("t-body");
		HBox priceRow = new HBox(10, price, per);
		priceRow.setAlignment(Pos.BASELINE_LEFT);
		Label status = new Label();
		status.setStyle("-fx-text-fill: -velo-green; -fx-font-family: 'Inter SemiBold';");
		Button subscribe = new Button("Subscribe to Velo+");
		subscribe.getStyleClass().addAll("primary-button", "button-lg");
		subscribe.setDisable(true);
		Label message = new Label("Secure checkout in your browser");
		message.getStyleClass().add("t-muted");
		message.setWrapText(true);
		VBox left = new VBox(18, badge, headline, pitch, priceRow, status, subscribe, message);
		left.setMinWidth(380);
		left.setPrefWidth(480);
		left.setMaxWidth(500);

		GridPane table = new GridPane();
		table.getStyleClass().add("card");
		table.setPadding(new Insets(8, 22, 8, 22));
		table.setVgap(0);
		table.setHgap(10);
		javafx.scene.layout.ColumnConstraints c0 = new javafx.scene.layout.ColumnConstraints();
		c0.setHgrow(Priority.ALWAYS);
		javafx.scene.layout.ColumnConstraints c1 = new javafx.scene.layout.ColumnConstraints(90);
		c1.setHalignment(javafx.geometry.HPos.CENTER);
		javafx.scene.layout.ColumnConstraints c2 = new javafx.scene.layout.ColumnConstraints(90);
		c2.setHalignment(javafx.geometry.HPos.CENTER);
		table.getColumnConstraints().addAll(c0, c1, c2);
		Label whatHead = new Label("What you get");
		whatHead.getStyleClass().add("t-strong");
		Label freeHead = new Label("Free");
		freeHead.getStyleClass().add("t-muted");
		Label plusHead = new Label("Velo+");
		plusHead.getStyleClass().addAll("badge", "badge-new");
		plusHead.setStyle("-fx-font-size: 11px; -fx-padding: 3 10 3 10;");
		table.addRow(0, padded(whatHead), freeHead, plusHead);
		String[][] rows = {
				{"Velo Client, launcher, free cosmetics", "Everything you have today", "y"},
				{"Velo Coins every month", "Spend on wings, capes and auras", "n"},
				{"World Hosting", "Your world for friends from anywhere, no port forwarding", "n"},
				{"Cloud Sync & Clips Pro", "Backups on every PC, 1080p60 clips with share links", "n"},
				{"Name Styles & animated capes", "Animated names, your own GIF cape", "n"},
				{"Your Home background", "Your own picture or GIF in the launcher", "n"},
				{"The Velo+ badge", "In chat, tab list and above your head", "n"},
				{"VIP role in the Discord", "Given automatically once linked", "n"},
				{"Keeps Velo independent", "Built by a small team", "n"}};
		Label coinsRow = null;
		for (int i = 0; i < rows.length; i++) {
			Label t = new Label(rows[i][0]);
			t.getStyleClass().add("t-strong");
			Label d = new Label(rows[i][1]);
			d.getStyleClass().add("t-muted");
			if (i == 1) {
				coinsRow = t;
			}
			VBox cell = new VBox(2, t, d);
			Node free = rows[i][2].equals("y") ? Icons.icon("check", 17, Color.web("#A9A9B3"), 2.4) : dash();
			table.addRow(i + 1, padded(cell), free, Icons.icon("check", 17, Color.web("#FF5A5F"), 2.6));
		}
		HBox.setHgrow(table, Priority.ALWAYS);
		HBox top = new HBox(48, left, table);
		top.setAlignment(Pos.TOP_LEFT);

		FlowPane perks = new FlowPane(14, 14);
		for (String[] feature : PLUS_FEATURES) {
			StackPane icon = new StackPane(Icons.icon(feature[0], 18, Color.web("#FF7A7E")));
			icon.setMinSize(38, 38);
			icon.setMaxSize(38, 38);
			icon.setStyle("-fx-background-color: -velo-accent-soft; -fx-background-radius: 10;");
			Label title = new Label(feature[1]);
			title.getStyleClass().add("t-strong");
			Label text = new Label(feature[2]);
			text.getStyleClass().add("t-muted");
			text.setWrapText(true);
			VBox card = new VBox(10, icon, title, text);
			card.getStyleClass().add("card");
			card.setPadding(new Insets(16));
			card.setPrefWidth(300);
			card.setMinHeight(150);
			perks.getChildren().add(card);
		}
		javafx.scene.control.CheckBox showPlus = new javafx.scene.control.CheckBox("Show the + of Velo+ on my badge");
		showPlus.setSelected(net.veloclient.launcher.data.PlusBadgeSetting.show());
		showPlus.setOnAction(e -> net.veloclient.launcher.data.PlusBadgeSetting.set(showPlus.isSelected()));
		showPlus.setVisible(false);
		showPlus.setManaged(false);
		Node addonSection = section("What's in Velo+", "Everything below switches on the moment you subscribe", null, null, new VBox(14, perks, showPlus));

		Label coinsLabel = coinsRow;
		CompletableFuture.supplyAsync(() -> {
			try {
				return StoreApi.plusStatus();
			} catch (StoreApi.StoreError e) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result == null) {
				price.setText("Velo+");
				per.setText("Sign in to see the price");
				return;
			}
			double amount = result.get("price").getAsDouble();
			String currency = result.get("currency").getAsString();
			String symbol = currency.equals("EUR") ? "€" : currency.equals("USD") ? "$" : currency;
			int coins = result.get("monthlyCoins").getAsInt();
			price.setText(String.format(Locale.ROOT, "%.2f %s", amount, symbol));
			if (coinsLabel != null) {
				coinsLabel.setText(StoreTiles.format(coins) + " Velo Coins every month");
			}
			if (result.get("plus").getAsBoolean()) {
				status.setText("You're Velo+  ·  active until " + LocalDate.ofInstant(java.time.Instant.ofEpochMilli(result.get("until").getAsLong()),
						java.time.ZoneId.systemDefault()));
				subscribe.setText("Extend / manage");
				showPlus.setVisible(true);
				showPlus.setManaged(true);
			}
			subscribe.setDisable(!result.get("checkoutEnabled").getAsBoolean());
			if (!result.get("checkoutEnabled").getAsBoolean()) {
				message.setText("Subscriptions aren't open on this server yet.");
			}
		}));
		subscribe.setOnAction(e -> {
			subscribe.setDisable(true);
			message.setText("Opening the payment page...");
			CompletableFuture.supplyAsync(() -> {
				try {
					return (Object) StoreApi.plusCheckout();
				} catch (StoreApi.StoreError error) {
					return error.getMessage();
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				subscribe.setDisable(false);
				if (result instanceof JsonObject json) {
					CoinsView.openUrl(json.get("checkoutUrl").getAsString());
					message.setText("Finish the payment in your browser - Velo+ switches on within a minute.");
				} else {
					message.setText(String.valueOf(result));
				}
			}));
		});
		VBox plusBody = new VBox(36, top, addonSection);
		plusBody.setPadding(new Insets(8, 0, 0, 0));
		return plusBody;
	}

	private static Node padded(Node node) {
		VBox box = new VBox(node);
		box.setPadding(new Insets(13, 0, 13, 0));
		box.setStyle("-fx-border-color: transparent transparent -velo-hairline transparent;");
		return box;
	}

	private static Node dash() {
		Region dash = new Region();
		dash.setMinSize(12, 2);
		dash.setMaxSize(12, 2);
		dash.setStyle("-fx-background-color: rgba(255,255,255,0.18);");
		return dash;
	}

	/**
	 * "Velo Neon - free: join our Discord and link your account". Shows your link status (loaded off the UI
	 * thread); the button copies a code to paste in the Discord's configured link channel.
	 */
	private static Node discordBanner() {
		StackPane icon = new StackPane(Icons.icon("link", 20, Color.web("#8EA1FF")));
		icon.setMinSize(46, 46);
		icon.setMaxSize(46, 46);
		icon.setStyle("-fx-background-color: rgba(88,101,242,0.18); -fx-background-radius: 12;");
		Label title = new Label("Velo Neon cape · free with Discord");
		title.getStyleClass().add("t-strong");
		title.setStyle("-fx-font-size: 15px;");
		Label status = new Label("Join the Velo Discord and link your Minecraft account. It isn't sold in the Store.");
		status.getStyleClass().add("t-muted");
		status.setWrapText(true);
		VBox text = new VBox(3, title, status);
		HBox.setHgrow(text, Priority.ALWAYS);
		Button getCode = new Button("Copy link code");
		getCode.getStyleClass().add("glass-button");
		getCode.setDisable(true);
		Button join = new Button("Join the Discord");
		join.setStyle("-fx-background-color: #5865F2; -fx-border-color: transparent; -fx-text-fill: white;");
		join.setDisable(true);
		String[] invite = {""};
		HBox box = new HBox(16, icon, text, getCode, join);
		box.setAlignment(Pos.CENTER_LEFT);
		box.getStyleClass().add("card");
		box.setPadding(new Insets(16, 18, 16, 16));
		join.setOnAction(e -> CoinsView.openUrl(invite[0]));
		CompletableFuture.supplyAsync(() -> {
			try {
				return StoreApi.discordStatus();
			} catch (StoreApi.StoreError e) {
				return null;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result == null) {
				status.setText("Sign in to link your Discord.");
				return;
			}
			invite[0] = result.has("inviteUrl") && !result.get("inviteUrl").isJsonNull() ? result.get("inviteUrl").getAsString() : "";
			join.setDisable(invite[0].isBlank());
			if (result.has("linked") && result.get("linked").getAsBoolean()) {
				status.setText("Linked as " + result.get("discordName").getAsString() + " - the cape is in your Locker.");
				status.setStyle("-fx-text-fill: -velo-green;");
				getCode.setDisable(true);
				getCode.setText("Linked");
			} else {
				getCode.setDisable(false);
			}
		}));
		getCode.setOnAction(e -> {
			getCode.setDisable(true);
			status.setText("Getting a code...");
			CompletableFuture.supplyAsync(() -> {
				try {
					return (Object) StoreApi.discordCode();
				} catch (StoreApi.StoreError error) {
					return error.getMessage();
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				getCode.setDisable(false);
				if (result instanceof JsonObject json) {
					String code = json.get("code").getAsString();
					javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
					content.putString(code);
					javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
					status.setText("Copied! Paste it in the Discord link channel (valid " + json.get("expiresInSeconds").getAsLong() / 60 + " min).");
					if (invite[0].isBlank() && json.has("inviteUrl")) {
						invite[0] = json.get("inviteUrl").getAsString();
						join.setDisable(invite[0].isBlank());
					}
				} else {
					status.setText(String.valueOf(result));
				}
			}));
		});
		return box;
	}
}
