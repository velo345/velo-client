package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import net.veloclient.launcher.auth.MinecraftSession;
import net.veloclient.launcher.data.CurrencyStore;
import net.veloclient.launcher.data.StoreCatalog;
import net.veloclient.launcher.data.StoreItem;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.theme.LauncherTheme;

import java.io.ByteArrayInputStream;

/**
 * The cosmetics store, like the in-game one: Featured (the newest wings collection - your skin
 * wearing each of its pairs in turn, in 3D, flapping, tinted in the pair's colour - and its pairs),
 * Wings and Capes tabs, "My cosmetics" and the Velo Coins balance top right.
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
		default void openWings(net.veloclient.launcher.data.WingsCatalog.WingType type) {
		}

		/** Opens Cosmetics (equip what you own). */
		default void openCosmetics() {
		}

		/** Opens the owner panel (owners only). */
		default void openOwner() {
		}

		/** Opens an aura's page. */
		default void openAura(net.veloclient.launcher.data.AuraCatalog.AuraType type) {
		}
	}

	private StoreView() {
	}

	private enum Page { FEATURED, WINGS, CAPES, AURAS, PLUS }

	/** Which tab was open last (kept while the launcher runs). */
	private static Page page = Page.FEATURED;

	/** Opens on the Auras tab next time (the launcher tour). */
	public static void showAurasTab() {
		page = Page.AURAS;
	}

	/** Each pair's own colour ({@code accent} in wings.json) - the same accents the in-game Store uses. */
	static String accent(String wingId) {
		return net.veloclient.launcher.data.WingsCatalog.byId(wingId).map(net.veloclient.launcher.data.WingsCatalog.WingType::accent).orElse("#ff4d4d");
	}

	public static Node build(Host host) {
		Label heading = new Label("Store");
		heading.getStyleClass().add("page-title");
		Label sub = new Label("Wings and capes for your Velo profile - try them on your own skin before buying.");
		sub.getStyleClass().add("page-subtitle");
		VBox titles = new VBox(2, heading, sub);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		// "My cosmetics": straight to equipping what you own.
		Button mine = new Button("✦  My cosmetics");
		mine.getStyleClass().add("ghost-button");
		mine.setStyle("-fx-border-color: #ff4d4d; -fx-border-radius: 18; -fx-background-radius: 18; -fx-padding: 8 16;");
		mine.setOnAction(e -> host.openCosmetics());
		HBox headerRow = new HBox(10, titles, spacer, mine, buildBalancePill(host));
		titles.setMinWidth(0);
		mine.setMinWidth(Region.USE_PREF_SIZE);
		if (net.veloclient.launcher.social.StoreApi.isOwner()) {
			Button ownerPanel = new Button("Owner panel");
			ownerPanel.setMinWidth(Region.USE_PREF_SIZE);
			ownerPanel.getStyleClass().add("ghost-button");
			ownerPanel.setOnAction(e -> host.openOwner());
			headerRow.getChildren().add(2, ownerPanel);
		}
		headerRow.setAlignment(Pos.CENTER_LEFT);

		HBox tabs = new HBox(6);
		String[] labels = {"Featured", "Wings", "Capes", "Auras", "Velo+"};
		for (Page p : Page.values()) {
			Button tab = new Button(labels[p.ordinal()]);
			tab.getStyleClass().add("segmented-button");
			if (p == page) {
				tab.getStyleClass().add("segmented-button-active");
			}
			tab.setOnAction(e -> {
				page = p;
				host.rebuild();
			});
			if (p == Page.AURAS) {
				Label badge = new Label("NEW");
				badge.setStyle("-fx-background-color: #ff4d4d; -fx-text-fill: white; -fx-font-size: 10px; -fx-font-weight: 800;"
						+ " -fx-padding: 1 6; -fx-background-radius: 6;");
				tab.setGraphic(badge);
				tab.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
				tab.setGraphicTextGap(8);
			}
			tabs.getChildren().add(tab);
		}

		VBox root = new VBox(20, headerRow, tabs);
		var wings = net.veloclient.launcher.data.WingsCatalog.forSale();
		var featured = net.veloclient.launcher.data.WingsCatalog.featured().orElse(null);
		var featuredWings = featured == null ? wings : net.veloclient.launcher.data.WingsCatalog.inCollection(featured.id());
		if (featuredWings.isEmpty()) {
			featuredWings = wings;
		}
		switch (page) {
			case FEATURED -> {
				root.getChildren().add(discordBanner());
				if (!featuredWings.isEmpty()) {
					root.getChildren().add(buildWingsHero(host, featured, featuredWings));
					Label title = new Label("The collection");
					title.getStyleClass().add("section-label");
					root.getChildren().addAll(title, wingGrid(host, featuredWings));
				}
			}
			case WINGS -> {
				// One section per collection, newest first; pairs without one at the end.
				for (var collection : net.veloclient.launcher.data.WingsCatalog.collections()) {
					var pairs = net.veloclient.launcher.data.WingsCatalog.inCollection(collection.id());
					if (!pairs.isEmpty()) {
						Label title = new Label(collection.name() + "  ·  " + pairs.size() + " pairs of animated 3D wings");
						title.getStyleClass().add("section-label");
						root.getChildren().addAll(title, wingGrid(host, pairs));
					}
				}
				var loose = wings.stream().filter(w -> net.veloclient.launcher.data.WingsCatalog.collection(w.collection()).isEmpty()).toList();
				if (!loose.isEmpty()) {
					Label title = new Label("More wings  ·  " + loose.size());
					title.getStyleClass().add("section-label");
					root.getChildren().addAll(title, wingGrid(host, loose));
				}
			}
			case PLUS -> root.getChildren().addAll(plusPage(), discordBanner());
			case AURAS -> {
				// For sale, plus any you own that aren't (given to you while testing, or a drop that ended).
				var auras = net.veloclient.launcher.data.AuraCatalog.all().stream()
						.filter(a -> a.inStore() || net.veloclient.launcher.data.AuraCatalog.owns(a.id())).toList();
				Label title = new Label("Auras  ·  animated effects around you - your colour, size and speed");
				title.getStyleClass().add("section-label");
				FlowPane grid = new FlowPane(14, 14);
				for (var aura : auras) {
					grid.getChildren().add(auraCard(host, aura));
				}
				if (auras.isEmpty()) {
					Label empty = new Label("No auras in the Store right now - check back soon.");
					empty.getStyleClass().add("empty-state");
					grid.getChildren().add(empty);
				}
				UiMotion.stagger(grid, 24);
				root.getChildren().addAll(title, grid);
			}
			case CAPES -> {
				var items = StoreCatalog.all().stream().filter(i -> i.inStore() || StoreOwnership.owns(i.id())).toList();
				Label title = new Label("Animated capes  ·  " + items.size());
				title.getStyleClass().add("section-label");
				root.getChildren().add(discordBanner());
				FlowPane grid = new FlowPane(14, 14);
				for (StoreItem item : items) {
					boolean owned = StoreOwnership.owns(item.id());
					grid.getChildren().add(CosmeticUi.capeCardLazy("store:" + item.id(), done -> CosmeticUi.framesAsync(item, done),
							item.name(), CosmeticUi.priceChip(item.priceCoins(), owned), false, () -> host.openItem(item)));
				}
				UiMotion.stagger(grid, 24);
				root.getChildren().addAll(title, grid);
			}
		}

		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox wrapper = new VBox(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox.setVgrow(wrapper, Priority.ALWAYS);
		return wrapper;
	}

	private static FlowPane wingGrid(Host host, java.util.List<net.veloclient.launcher.data.WingsCatalog.WingType> wings) {
		FlowPane grid = new FlowPane(14, 14);
		for (var type : wings) {
			grid.getChildren().add(wingCard(host, type));
		}
		UiMotion.stagger(grid, 24);
		return grid;
	}

	/** Opens on the Velo+ tab next time (the launcher tour). */
	public static void showPlusTab() {
		page = Page.PLUS;
	}

	/**
	 * Velo+: what it includes and what it costs, your membership, and the subscribe button (opens the payment
	 * page). The price, the monthly coins and your status come from the server, off the UI thread.
	 */
	private static Node plusPage() {
		Label tag = new Label("SUBSCRIPTION");
		tag.getStyleClass().add("featured-tag");
		Label title = new Label("Velo+");
		title.getStyleClass().add("featured-title");
		title.setStyle("-fx-font-size: 44px;");
		ImageView badge = new ImageView(new Image(StoreView.class.getResourceAsStream("/net/veloclient/launcher/images/velo-plus-badge.png"), 96, 64, true, true));
		HBox heading = new HBox(14, title, badge);
		heading.setAlignment(Pos.CENTER_LEFT);
		Label price = new Label("...");
		price.getStyleClass().add("page-subtitle");
		Label status = new Label();
		status.setStyle("-fx-font-weight: 700; -fx-text-fill: #8fe3a8;");

		VBox perks = new VBox(10);
		String[][] list = {
				{"+", "The Velo+ badge", "Your Velo logo with a red plus behind it - next to your name for every Velo player: in chat, the tab list and above your head."},
				{"★", "VIP role in the Discord", "The VIP role and its channels - given automatically once you've linked your Discord, and taken away again when Velo+ ends."},
				{"V", "Velo Coins every month", "...  spend them on wings, capes and auras in the Store."},
				{"◆", "The Velo Client Addons", "Cinematic Camera, Fly Boat, Auto Eat, Storage Finder, Entity Finder, Auto Tunnel Miner, Ender Chest Farmer, Auto Excavator and Sound Radar - "
						+ "for Velo+ only. The launcher installs and updates them for you."},
				{"♥", "Supporting Velo Client", "Velo Client is built by a small team - Velo+ keeps it going."}};
		Label[] descriptions = new Label[list.length];
		for (int i = 0; i < list.length; i++) {
			// A red disc with a glyph (the launcher's font has no colour emoji).
			Label glyph = new Label(list[i][0]);
			glyph.setStyle("-fx-text-fill: white; -fx-font-weight: 800; -fx-font-size: 15px;");
			StackPane icon = new StackPane(glyph);
			icon.setMinSize(32, 32);
			icon.setMaxSize(32, 32);
			icon.setStyle("-fx-background-color: #ff2d3c40; -fx-background-radius: 16; -fx-border-color: #ff2d3c; -fx-border-radius: 16;");
			Label name = new Label(list[i][1]);
			name.getStyleClass().add("settings-card-title");
			Label text = new Label(list[i][2]);
			text.getStyleClass().add("page-subtitle");
			text.setWrapText(true);
			descriptions[i] = text;
			VBox words = new VBox(2, name, text);
			HBox row = new HBox(12, icon, words);
			row.setAlignment(Pos.TOP_LEFT);
			HBox.setHgrow(words, Priority.ALWAYS);
			perks.getChildren().add(row);
		}

		Button subscribe = new Button("Subscribe");
		subscribe.getStyleClass().add("primary-button");
		subscribe.setMinHeight(46);
		subscribe.setPrefWidth(300);
		subscribe.setDisable(true);
		Label message = new Label();
		message.getStyleClass().add("purchase-status");
		message.setWrapText(true);
		VBox info = new VBox(12, tag, heading, price, status, perks, subscribe, message);
		info.getStyleClass().add("featured-banner");
		info.setPadding(new javafx.geometry.Insets(26, 30, 26, 30));
		info.setStyle("-fx-background-color: radial-gradient(center 85% 20%, radius 70%, #ff2d3c55 0%, #00000000 100%), linear-gradient(to right, #1b1416, #121014);"
				+ " -fx-border-color: #ff2d3c88; -fx-border-radius: 18; -fx-background-radius: 18;");
		java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return net.veloclient.launcher.social.StoreApi.plusStatus();
			} catch (net.veloclient.launcher.social.StoreApi.StoreError e) {
				return null;
			}
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> javafx.application.Platform.runLater(() -> {
			if (result == null) {
				price.setText("Sign in to see Velo+.");
				return;
			}
			double amount = result.get("price").getAsDouble();
			String currency = result.get("currency").getAsString();
			int coins = result.get("monthlyCoins").getAsInt();
			price.setText(String.format(java.util.Locale.ROOT, "%.2f %s per month  ·  cancel any time", amount, currency));
			descriptions[2].setText(String.format(java.util.Locale.ROOT, "%,d Velo Coins land in your wallet every month - spend them on wings, capes and auras in the Store.", coins));
			if (result.get("plus").getAsBoolean()) {
				status.setText("✓ You're Velo+  ·  active until " + java.time.LocalDate.ofInstant(java.time.Instant.ofEpochMilli(result.get("until").getAsLong()),
						java.time.ZoneId.systemDefault()));
				subscribe.setText("Extend / manage");
			} else {
				subscribe.setText(String.format(java.util.Locale.ROOT, "Subscribe  ·  %.2f %s / month", amount, currency));
			}
			subscribe.setDisable(!result.get("checkoutEnabled").getAsBoolean());
			if (!result.get("checkoutEnabled").getAsBoolean()) {
				message.setText("Subscriptions aren't open on this server yet.");
			}
		}));
		subscribe.setOnAction(e -> {
			subscribe.setDisable(true);
			message.setText("Opening the payment page...");
			java.util.concurrent.CompletableFuture.supplyAsync(() -> {
				try {
					return (Object) net.veloclient.launcher.social.StoreApi.plusCheckout();
				} catch (net.veloclient.launcher.social.StoreApi.StoreError error) {
					return error.getMessage();
				}
			}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> javafx.application.Platform.runLater(() -> {
				subscribe.setDisable(false);
				if (result instanceof com.google.gson.JsonObject json) {
					CoinsView.openUrl(json.get("checkoutUrl").getAsString());
					message.setText("Finish the payment in your browser - Velo+ switches on within a minute.");
				} else {
					message.setText(String.valueOf(result));
				}
			}));
		});
		return info;
	}

	/**
	 * "Velo Neon - free: join our Discord and link your account". Shows your link status (loaded off the UI
	 * thread); the button copies a code to paste in the Discord's configured link channel.
	 */
	private static Node discordBanner() {
		Label title = new Label("Velo Neon cape  ·  free with Discord");
		title.getStyleClass().add("settings-card-title");
		Label text = new Label("Join the Velo Discord and link your Minecraft account to unlock it - it isn't sold in the Store.");
		text.getStyleClass().add("page-subtitle");
		text.setWrapText(true);
		Label status = new Label("Checking...");
		status.getStyleClass().add("page-subtitle");
		Button getCode = new Button("Copy link code");
		getCode.getStyleClass().add("primary-button");
		getCode.setDisable(true);
		Button join = new Button("Join the Discord");
		join.getStyleClass().add("ghost-button");
		join.setDisable(true);
		String[] invite = {""};
		HBox actions = new HBox(8, getCode, join, status);
		actions.setAlignment(Pos.CENTER_LEFT);
		VBox box = new VBox(8, title, text, actions);
		box.getStyleClass().add("settings-card");
		join.setOnAction(e -> CoinsView.openUrl(invite[0]));
		java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return net.veloclient.launcher.social.StoreApi.discordStatus();
			} catch (net.veloclient.launcher.social.StoreApi.StoreError e) {
				return null;
			}
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> javafx.application.Platform.runLater(() -> {
			if (result == null) {
				status.setText("Sign in to link your Discord.");
				return;
			}
			invite[0] = result.has("inviteUrl") && !result.get("inviteUrl").isJsonNull() ? result.get("inviteUrl").getAsString() : "";
			join.setDisable(invite[0].isBlank());
			if (result.has("linked") && result.get("linked").getAsBoolean()) {
				status.setText("✓ Linked as " + result.get("discordName").getAsString() + " - the cape is in your Cosmetics.");
				getCode.setDisable(true);
				getCode.setText("Linked");
			} else {
				status.setText("");
				getCode.setDisable(false);
			}
		}));
		getCode.setOnAction(e -> {
			getCode.setDisable(true);
			status.setText("Getting a code...");
			java.util.concurrent.CompletableFuture.supplyAsync(() -> {
				try {
					return (Object) net.veloclient.launcher.social.StoreApi.discordCode();
				} catch (net.veloclient.launcher.social.StoreApi.StoreError error) {
					return error.getMessage();
				}
			}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> javafx.application.Platform.runLater(() -> {
				getCode.setDisable(false);
				if (result instanceof com.google.gson.JsonObject json) {
					String code = json.get("code").getAsString();
					javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
					content.putString(code);
					javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
					status.setText("Copied! Paste the code in the Discord link channel (valid "
							+ json.get("expiresInSeconds").getAsLong() / 60 + " min).");
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

	/** An aura: itself, live, around a dim player shape; name; price / Owned / Equipped. */
	private static Node auraCard(Host host, net.veloclient.launcher.data.AuraCatalog.AuraType type) {
		String accent = type.accent();
		boolean owned = net.veloclient.launcher.data.AuraCatalog.owns(type.id());
		var settings = owned ? net.veloclient.launcher.data.AuraCatalog.settings(type.id())
				: net.veloclient.velo.client.cosmetics.aura.AuraEffects.Settings.DEFAULT;
		ImageView canvas = new ImageView(AuraFx.snapshot(type.kind(), settings, 190, 150));
		canvas.setFitWidth(190);
		canvas.setFitHeight(150);
		canvas.setPreserveRatio(false);
		StackPane art = new StackPane(canvas);
		art.setPrefSize(190, 150);
		art.setMinSize(190, 150);
		art.setStyle("-fx-background-radius: 12; -fx-background-color: radial-gradient(center 50% 60%, radius 70%, " + accent
				+ "44 0%, #0f0f1400 100%), linear-gradient(to bottom, #15151c, #0e0e12);");
		Label name = new Label(type.name());
		name.getStyleClass().add("cape-card-title");
		boolean equipped = type.id().equals(net.veloclient.launcher.data.AuraCatalog.equipped());
		Node footer = equipped ? CosmeticUi.badge("Equipped", "chip-owned")
				: CosmeticUi.priceChip(net.veloclient.launcher.social.StoreApi.price(type.itemId(), type.price()), owned);
		VBox card = new VBox(8, art, name, footer);
		card.getStyleClass().addAll("cape-card", "motion-card");
		card.setStyle("-fx-border-color: " + accent + "66; -fx-border-radius: 14; -fx-border-width: 1.2;");
		card.setOnMouseEntered(e -> card.setStyle("-fx-border-color: " + accent + "; -fx-border-radius: 14; -fx-border-width: 1.6;"
				+ " -fx-effect: dropshadow(gaussian, " + accent + "88, 22, 0.2, 0, 4);"));
		card.setOnMouseExited(e -> card.setStyle("-fx-border-color: " + accent + "66; -fx-border-radius: 14; -fx-border-width: 1.2;"));
		card.setOnMouseClicked(e -> host.openAura(type));
		return card;
	}

	/** A pair of wings: its picture floating on a glow in its own colour, name, and price / Owned. */
	private static Node wingCard(Host host, net.veloclient.launcher.data.WingsCatalog.WingType type) {
		String accent = accent(type.id());
		ImageView picture = new ImageView(new Image(net.veloclient.launcher.data.WingsCatalog.openPreview(type.id())));
		picture.setFitWidth(176);
		picture.setPreserveRatio(true);
		StackPane art = new StackPane(picture);
		art.setPrefSize(190, 150);
		art.setMinSize(190, 150);
		art.setStyle("-fx-background-radius: 12; -fx-background-color: radial-gradient(center 50% 60%, radius 70%, " + accent
				+ "99 0%, #16161d00 100%), linear-gradient(to bottom, #1d1d25, #131318);");
		// A slow float, each card a little out of step.
		javafx.animation.TranslateTransition bob = new javafx.animation.TranslateTransition(javafx.util.Duration.seconds(2.6), picture);
		bob.setFromY(2);
		bob.setToY(-4);
		bob.setAutoReverse(true);
		bob.setCycleCount(javafx.animation.Animation.INDEFINITE);
		bob.setDelay(javafx.util.Duration.millis((type.id().hashCode() & 0x3FF)));
		art.sceneProperty().addListener((obs, o, n) -> {
			if (n == null) {
				bob.stop();
			} else {
				bob.play();
			}
		});
		Label name = new Label(type.name());
		name.getStyleClass().add("cape-card-title");
		boolean owned = StoreOwnership.owns(type.itemId());
		boolean equipped = type.id().equals(net.veloclient.launcher.data.WingsCatalog.equipped());
		Node footer = equipped ? CosmeticUi.badge("Equipped", "chip-owned")
				: CosmeticUi.priceChip(net.veloclient.launcher.social.StoreApi.price(type.itemId(), type.price()), owned);
		VBox card = new VBox(8, art, name, footer);
		card.getStyleClass().addAll("cape-card", "motion-card");
		card.setStyle("-fx-border-color: " + accent + "66; -fx-border-radius: 14; -fx-border-width: 1.2;");
		card.setOnMouseEntered(e -> card.setStyle("-fx-border-color: " + accent + "; -fx-border-radius: 14; -fx-border-width: 1.6;"
				+ " -fx-effect: dropshadow(gaussian, " + accent + "88, 22, 0.2, 0, 4);"));
		card.setOnMouseExited(e -> card.setStyle("-fx-border-color: " + accent + "66; -fx-border-radius: 14; -fx-border-width: 1.2;"));
		card.setOnMouseClicked(e -> host.openWings(type));
		return card;
	}

	/**
	 * The banner on top: "New - <collection>", your skin in 3D wearing each of its pairs in
	 * turn (a few seconds each), flapping and slowly turning, the banner tinted in that pair's colour.
	 * Not signed in: the pairs' pictures take turns.
	 */
	private static Node buildWingsHero(Host host, net.veloclient.launcher.data.WingsCatalog.Collection collection,
			java.util.List<net.veloclient.launcher.data.WingsCatalog.WingType> wings) {
		Long until = wings.stream().map(net.veloclient.launcher.data.WingsCatalog.WingType::availableUntil)
				.filter(java.util.Objects::nonNull).min(Long::compare).orElse(null);
		long daysLeft = until == null ? -1 : Math.max(0, (until - System.currentTimeMillis()) / 86_400_000L);
		// Limited drops (e.g. Halloween) say how long they're left in the store.
		Label tag = new Label((collection == null ? "NEW COLLECTION" : until != null ? collection.tag() : "NEW  ·  " + collection.tag())
				+ (until == null ? "" : daysLeft >= 1 ? "  ·  " + daysLeft + (daysLeft == 1 ? " DAY LEFT" : " DAYS LEFT") : "  ·  LAST DAY"));
		tag.getStyleClass().add("featured-tag");
		Label name = new Label(collection == null ? "Wings" : collection.name());
		name.getStyleClass().add("featured-title");
		name.setStyle("-fx-font-size: 40px;");
		Label description = new Label((collection == null ? "" : collection.description() + " ") + "Every Velo Client player sees them.");
		description.getStyleClass().add("page-subtitle");
		description.setWrapText(true);
		description.setMaxWidth(420);
		Label showing = new Label("Now showing: " + wings.get(0).name());
		showing.setStyle("-fx-font-weight: 700; -fx-text-fill: " + accent(wings.get(0).id()) + ";");
		int[] index = {0};
		Button view = new Button("Try them on");
		view.getStyleClass().add("primary-button");
		view.setOnAction(e -> host.openWings(wings.get(index[0])));
		Button shop = new Button("Shop the collection");
		shop.getStyleClass().add("ghost-button");
		shop.setOnAction(e -> {
			page = Page.WINGS;
			host.rebuild();
		});
		int from = wings.stream().mapToInt(w -> net.veloclient.launcher.social.StoreApi.price(w.itemId(), w.price())).min().orElse(0);
		Label price = new Label(String.format(java.util.Locale.ROOT, "from %,d coins", from));
		price.getStyleClass().add("page-subtitle");
		HBox actions = new HBox(12, view, shop, price);
		actions.setAlignment(Pos.CENTER_LEFT);
		VBox text = new VBox(10, tag, name, description, showing, actions);
		text.setAlignment(Pos.CENTER_LEFT);
		HBox.setHgrow(text, Priority.ALWAYS);

		StackPane art = CosmeticUi.stage(300, 320);
		art.getStyleClass().add("showcase-stage");
		ImageView still = new ImageView(new Image(net.veloclient.launcher.data.WingsCatalog.openPreview(wings.get(0).id())));
		still.setFitWidth(280);
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
		// Next pair every few seconds while the Store is open.
		HBox banner = new HBox(20, text, art);
		java.util.function.Consumer<String> tint = id -> banner.setStyle("-fx-background-color: radial-gradient(center 85% 50%, radius 75%, "
				+ accent(id) + "70 0%, #00000000 100%), linear-gradient(to right, #18181f, #121217);"
				+ " -fx-border-color: " + accent(id) + "88; -fx-border-radius: 18; -fx-background-radius: 18;");
		tint.accept(wings.get(0).id());
		javafx.animation.Timeline cycle = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(4.5), e -> {
			index[0] = (index[0] + 1) % wings.size();
			var type = wings.get(index[0]);
			showing.setText("Now showing: " + type.name());
			showing.setStyle("-fx-font-weight: 700; -fx-text-fill: " + accent(type.id()) + ";");
			tint.accept(type.id());
			if (model[0] != null) {
				model[0].show(type.id());
			} else {
				still.setImage(new Image(net.veloclient.launcher.data.WingsCatalog.openPreview(type.id())));
			}
		}));
		cycle.setCycleCount(javafx.animation.Animation.INDEFINITE);
		art.sceneProperty().addListener((obs, oldScene, newScene) -> {
			if (newScene == null) {
				cycle.stop();
			} else {
				cycle.play();
			}
		});

		banner.setAlignment(Pos.CENTER_LEFT);
		banner.getStyleClass().addAll("featured-banner", "motion-card");
		return banner;
	}

	private static String balanceText() {
		int balance = CurrencyStore.balance();
		return balance < 0 ? "..." : String.format(java.util.Locale.ROOT, "%,d", balance);
	}

	private static Node buildBalancePill(Host host) {
		Label balance = new Label(balanceText());
		balance.getStyleClass().add("balance-amount");
		// The balance lives on the server: show the cached value, then refresh it.
		boolean wasOwner = net.veloclient.launcher.social.StoreApi.isOwner();
		java.util.Set<String> ownedBefore = net.veloclient.launcher.social.StoreApi.owned();
		java.util.concurrent.CompletableFuture.runAsync(() -> {
			try {
				net.veloclient.launcher.social.StoreApi.wallet();
				net.veloclient.launcher.data.StorePurchase.restoreMissing();
			} catch (net.veloclient.launcher.social.StoreApi.StoreError ignored) {
				// Shown as "..." until it works.
			}
			javafx.application.Platform.runLater(() -> {
				balance.setText(balanceText());
				// Turned out to be the owner (Owner panel button) or owns something new: show the page again.
				boolean changed = !wasOwner && net.veloclient.launcher.social.StoreApi.isOwner()
						|| !net.veloclient.launcher.social.StoreApi.owned().equals(ownedBefore);
				if (changed && balance.getScene() != null) {
					host.rebuild();
				}
			});
		}, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		Button getCoins = new Button("Get coins");
		getCoins.getStyleClass().add("ghost-button");
		getCoins.setOnAction(e -> host.openCoins());
		HBox pill = new HBox(8, CosmeticUi.coin(20), balance, getCoins);
		pill.setAlignment(Pos.CENTER_LEFT);
		pill.getStyleClass().add("balance-pill");
		pill.setMinWidth(Region.USE_PREF_SIZE);
		return pill;
	}

	private static Color accent(LauncherTheme t) {
		return Color.rgb((t.accentStart() >> 16) & 0xFF, (t.accentStart() >> 8) & 0xFF, t.accentStart() & 0xFF);
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}
}
