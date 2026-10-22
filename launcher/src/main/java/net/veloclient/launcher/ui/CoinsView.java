package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import net.veloclient.launcher.data.StoreCatalog;
import net.veloclient.launcher.data.StorePurchase;
import net.veloclient.launcher.social.StoreApi;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Velo Coins: buy coin packs (Tebex checkout in the browser) and earn free coins (daily reward
 * streak, daily quests, rewarded ads). Owners also get tools to give/take coins and items. All
 * numbers come from the Velo server.
 */
public final class CoinsView {

	private CoinsView() {
	}

	@FunctionalInterface
	private interface Call {
		JsonObject run() throws StoreApi.StoreError;
	}

	/** Where the page's shortcuts go (Store, invite friends, Velo+). */
	public interface Links {
		void openStore();

		void openReferrals();

		void openPlus();
	}

	private static Links links;

	public static Node build() {
		return build(null);
	}

	public static Node build(Links shortcuts) {
		links = shortcuts;
		StackPane page = new StackPane();
		page.getChildren().add(Skeleton.grid(6, 220, 200));
		Runnable[] reload = new Runnable[1];
		reload[0] = () -> CompletableFuture.supplyAsync(() -> {
			try {
				JsonObject catalog = StoreApi.catalog();
				StoreApi.wallet();
				StorePurchase.restoreMissing();
				JsonObject rewards = StoreApi.connected() ? StoreApi.rewards() : null;
				return new Object[] {catalog, rewards, null};
			} catch (StoreApi.StoreError e) {
				return new Object[] {null, null, e.getMessage()};
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result[0] == null) {
				Label error = new Label(result[2] + "");
				error.getStyleClass().add("empty-state");
				page.getChildren().setAll(error);
				return;
			}
			ScrollKeeper.setAll(page, content((JsonObject) result[0], (JsonObject) result[1], reload[0]));
		}));
		reload[0].run();
		return page;
	}

	private static Node content(JsonObject catalog, JsonObject rewards, Runnable reload) {
		Label status = new Label();
		status.getStyleClass().add("t-strong");
		status.setWrapText(true);
		status.managedProperty().bind(status.textProperty().isNotEmpty());
		status.visibleProperty().bind(status.managedProperty());

		VBox left = new VBox(28, hero(), section("Buy coins", "One-time packs, no subscription", packs(catalog, status, reload)), waysToEarn());
		HBox.setHgrow(left, Priority.ALWAYS);
		VBox right = new VBox(12, section("Free every day", null, rewards == null ? note("Sign in to earn free coins.") : freeCoins(rewards, status, reload)));
		right.setMinWidth(340);
		right.setMaxWidth(380);
		HBox columns = new HBox(28, left, right);

		VBox root = new VBox(18, status, columns);
		if (StoreApi.isOwner()) {
			root.getChildren().add(ownerTools(catalog, reload));
		}
		root.setPadding(new javafx.geometry.Insets(4, 24, 28, 4));
		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("scroll-pane");
		VBox page = new VBox(scroll);
		page.getStyleClass().add("page");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		return page;
	}

	/** Your balance, big, with where to spend it. */
	private static Node hero() {
		Label overline = new Label("VELO COINS");
		overline.getStyleClass().add("t-overline");
		Label balance = new Label(String.format(Locale.ROOT, "%,d", Math.max(0, StoreApi.balance())));
		balance.getStyleClass().add("t-display");
		balance.setStyle("-fx-text-fill: -velo-gold;");
		HBox amount = new HBox(14, CosmeticUi.coin(44), balance);
		amount.setAlignment(Pos.CENTER_LEFT);
		Label sub = new Label("Spend them on wings, capes, auras and bundles. Earn more for free every day.");
		sub.getStyleClass().add("t-body");
		sub.setWrapText(true);
		HBox actions = new HBox(10);
		if (links != null) {
			Button store = new Button("Browse the Store", Icons.icon("store", 15, javafx.scene.paint.Color.WHITE));
			store.getStyleClass().add("primary-button");
			store.setOnAction(e -> links.openStore());
			Button invite = new Button("Invite friends", Icons.icon("gift", 15, javafx.scene.paint.Color.web("#F7C04A")));
			invite.getStyleClass().add("ghost-button");
			invite.setOnAction(e -> links.openReferrals());
			actions.getChildren().addAll(store, invite);
		}
		VBox hero = new VBox(10, overline, amount, sub, actions);
		VBox.setMargin(actions, new javafx.geometry.Insets(8, 0, 0, 0));
		hero.getStyleClass().add("card");
		hero.setPadding(new javafx.geometry.Insets(26, 30, 26, 30));
		hero.setStyle("-fx-background-color: radial-gradient(center 85% 20%, radius 65%, rgba(247,192,74,0.16) 0%, transparent 100%), -velo-surface;"
				+ " -fx-border-color: rgba(247,192,74,0.22);");
		return hero;
	}

	/** Other ways to get coins: inviting friends and Velo+. */
	private static Node waysToEarn() {
		if (links == null) {
			return new Region();
		}
		HBox row = new HBox(14,
				earnCard("gift", "#F7C04A", "Invite friends", "Up to 500 coins for each friend who plays 2 hours. They get 300 too.", "Get your code",
						links::openReferrals),
				earnCard("crown", "#FF7A7E", "Velo+", "World Hosting, Cloud Sync, Clips Pro, Name Styles, your own Home background, a monthly coin drop and the + badge.", "See Velo+", links::openPlus));
		row.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));
		return section("More ways to earn", null, row);
	}

	private static Node earnCard(String icon, String color, String title, String text, String action, Runnable onAction) {
		StackPane badge = new StackPane(Icons.icon(icon, 20, javafx.scene.paint.Color.web(color)));
		badge.setMinSize(42, 42);
		badge.setMaxSize(42, 42);
		badge.setStyle("-fx-background-color: " + color + "22; -fx-background-radius: 12;");
		Label name = new Label(title);
		name.getStyleClass().add("t-heading");
		Label body = new Label(text);
		body.getStyleClass().add("t-muted");
		body.setWrapText(true);
		Button go = new Button(action);
		go.getStyleClass().addAll("ghost-button", "button-sm");
		go.setOnAction(e -> onAction.run());
		VBox card = new VBox(10, badge, name, body, go);
		card.getStyleClass().addAll("card", "card-hover");
		card.setPadding(new javafx.geometry.Insets(18));
		card.setMaxWidth(Double.MAX_VALUE);
		card.setOnMouseClicked(e -> onAction.run());
		return card;
	}

	private static Node section(String title, String subtitle, Node body) {
		Label heading = new Label(title);
		heading.getStyleClass().add("t-title-sm");
		HBox head = new HBox(12, heading);
		head.setAlignment(Pos.BASELINE_LEFT);
		if (subtitle != null) {
			Label sub = new Label(subtitle);
			sub.getStyleClass().add("t-muted");
			head.getChildren().add(sub);
		}
		return new VBox(14, head, body);
	}

	// ---- Coin packs ----

	private static Node packs(JsonObject catalog, Label status, Runnable reload) {
		FlowPane grid = StoreTiles.grid(190, 14);
		JsonArray packs = catalog.getAsJsonArray("coinPacks");
		String currency = catalog.get("currency").getAsString();
		boolean enabled = catalog.get("checkoutEnabled").getAsBoolean();
		int best = Math.min(2, packs.size() - 1);
		for (int i = 0; i < packs.size(); i++) {
			JsonObject pack = packs.get(i).getAsJsonObject();
			int coins = pack.get("coins").getAsInt();
			int bonus = pack.get("bonus").getAsInt();
			double price = pack.get("price").getAsDouble();

			StackPane art = new StackPane(coinPile(2 + i * 2));
			art.setPrefHeight(104);
			art.setMinHeight(104);
			art.setStyle("-fx-background-color: radial-gradient(center 50% 60%, radius 60%, rgba(247,192,74," + (0.12 + i * 0.05)
					+ ") 0%, transparent 100%), -velo-surface-2; -fx-background-radius: 12;");
			Label amount = new Label(String.format(Locale.ROOT, "%,d", coins + bonus));
			amount.getStyleClass().add("t-title-sm");
			amount.setStyle("-fx-text-fill: -velo-gold;");
			Label coinsWord = new Label("coins");
			coinsWord.getStyleClass().add("t-muted");
			HBox amountRow = new HBox(6, amount, coinsWord);
			amountRow.setAlignment(Pos.BASELINE_LEFT);
			Label extra = new Label(bonus > 0 ? "+" + String.format(Locale.ROOT, "%,d", bonus) + " bonus" : "Starter pack");
			extra.getStyleClass().addAll("badge", bonus > 0 ? "badge-green" : "badge-grey");
			Button buy = new Button(enabled ? formatPrice(price, currency) : "Coming soon");
			buy.getStyleClass().add(i == best ? "buy-button" : "primary-button");
			buy.setMaxWidth(Double.MAX_VALUE);
			buy.setDisable(!enabled);
			String packId = pack.get("id").getAsString();
			buy.setOnAction(e -> startCheckout(packId, buy, status, reload));
			VBox body = new VBox(10, art, amountRow, extra, buy);
			body.getStyleClass().add("card");
			body.setPadding(new javafx.geometry.Insets(12, 12, 14, 12));
			if (i == best) {
				body.setStyle("-fx-border-color: rgba(247,192,74,0.55); -fx-border-width: 1.5;");
				Label ribbon = new Label("BEST VALUE");
				ribbon.getStyleClass().addAll("badge", "badge-gold");
				StackPane.setAlignment(ribbon, Pos.TOP_RIGHT);
				StackPane.setMargin(ribbon, new javafx.geometry.Insets(8));
				art.getChildren().add(ribbon);
			}
			grid.getChildren().add(body);
		}
		UiMotion.stagger(grid, 8);
		HBox secure = new HBox(8, Icons.icon("lock", 13, javafx.scene.paint.Color.web("#A9A9B3")),
				note(enabled ? "Secure checkout by Tebex: cards, PayPal, paysafecard and more. Opens in your browser."
						: "Buying coins isn't open yet."));
		secure.setAlignment(Pos.CENTER_LEFT);
		return new VBox(12, grid, secure);
	}

	private static void startCheckout(String packId, Button button, Label status, Runnable reload) {
		button.setDisable(true);
		status.setText("Opening secure checkout...");
		run(() -> StoreApi.checkout(packId), result -> {
			String orderId = result.get("orderId").getAsString();
			openUrl(result.get("checkoutUrl").getAsString());
			status.setText("Finish the payment in your browser - your coins appear here automatically.");
			// Poll the order until the payment webhook arrives (up to 30 minutes).
			Timeline poll = new Timeline();
			long started = System.currentTimeMillis();
			poll.getKeyFrames().add(new KeyFrame(Duration.seconds(4), ev -> run(() -> StoreApi.order(orderId), order -> {
				String state = order.get("status").getAsString();
				if ("paid".equals(state)) {
					poll.stop();
					status.setText("Payment received - " + order.get("coins").getAsInt() + " coins added. Thank you!");
					reload.run();
				} else if ("review".equals(state)) {
					poll.stop();
					status.setText("Your payment is being checked - the coins arrive once it's confirmed.");
				} else if (System.currentTimeMillis() - started > 30 * 60_000L) {
					poll.stop();
					button.setDisable(false);
				}
			}, error -> { })));
			poll.setCycleCount(Timeline.INDEFINITE);
			poll.play();
			button.setDisable(false);
		}, error -> {
			status.setText(error);
			button.setDisable(false);
		});
	}

	/** A small heap of gold coins - bigger packs, bigger heap. */
	private static Node coinPile(int count) {
		Pane pile = new Pane();
		pile.setPrefSize(120, 80);
		pile.setMaxSize(120, 80);
		for (int i = 0; i < Math.min(count, 9); i++) {
			Node coin = CosmeticUi.coin(34);
			int column = i % 3;
			int row = i / 3;
			coin.setLayoutX(10 + column * 30 + (row % 2) * 12);
			coin.setLayoutY(42 - row * 18 + (column == 1 ? -4 : 0));
			pile.getChildren().add(coin);
		}
		return pile;
	}

	// ---- Free coins ----

	private static Node freeCoins(JsonObject r, Label status, Runnable reload) {
		VBox box = new VBox(0);
		box.getStyleClass().add("card");

		// Daily reward + streak.
		int streak = r.get("streak").getAsInt();
		boolean claimed = r.get("loginClaimed").getAsBoolean();
		boolean claimable = r.get("loginClaimable").getAsBoolean();
		Label dailyTitle = new Label("Daily reward");
		dailyTitle.getStyleClass().add("t-strong");
		HBox dots = new HBox(5);
		int days = streak + (claimed ? 1 : 0);
		int litInWeek = days == 0 ? 0 : (days - 1) % 7 + 1;
		for (int d = 0; d < 7; d++) {
			boolean lit = d < litInWeek;
			Label day = new Label(d == 6 ? "★" : String.valueOf(d + 1));
			day.setStyle("-fx-font-family: 'JetBrains Mono Medium'; -fx-font-size: 10px; -fx-text-fill: "
					+ (lit ? "#1a1406" : d == 6 ? "-velo-gold" : "-velo-text-3") + ";");
			StackPane cell = new StackPane(day);
			cell.setMinSize(24, 24);
			cell.setMaxSize(24, 24);
			cell.setStyle("-fx-background-radius: 7; -fx-background-color: " + (lit ? "-velo-gold" : "rgba(255,255,255,0.06)") + ";"
					+ (d == 6 && !lit ? " -fx-border-color: rgba(247,192,74,0.5); -fx-border-radius: 7;" : ""));
			dots.getChildren().add(cell);
		}
		// Every 7th day in a row pays a weekly bonus on top.
		int weekly = r.has("weeklyBonus") ? r.get("weeklyBonus").getAsInt() : 0;
		boolean weeklyToday = r.has("weeklyBonusToday") && r.get("weeklyBonusToday").getAsBoolean();
		Label streakLabel = new Label(weekly <= 0 ? "" : weeklyToday ? "Week bonus!" : "Day 7 pays +" + weekly + " extra");
		streakLabel.getStyleClass().add("t-muted");
		streakLabel.setMinWidth(Region.USE_PREF_SIZE);
		streakLabel.setManaged(!streakLabel.getText().isEmpty());
		VBox streakRow = new VBox(6, dots, streakLabel);
		Button claim = new Button(claimed ? "Claimed" : claimable ? "Claim +" + r.get("loginCoins").getAsInt() : "Play 5 min");
		claim.setDisable(!claimable);
		claim.getStyleClass().add(claimable ? "buy-button" : "ghost-button");
		claim.setOnAction(e -> run(StoreApi::claimLogin, ok -> {
			status.setText("Daily reward claimed!");
			reload.run();
		}, status::setText));
		box.getChildren().add(rewardCard(new VBox(8, dailyTitle, streakRow), claim));
		Label questsHead = new Label("TODAY'S QUESTS");
		questsHead.getStyleClass().add("field-label");
		questsHead.setPadding(new javafx.geometry.Insets(14, 16, 4, 16));
		box.getChildren().addAll(hairline(), questsHead);

		// Today's quests.
		for (var element : r.getAsJsonArray("quests")) {
			JsonObject q = element.getAsJsonObject();
			long progress = q.get("progress").getAsLong();
			long target = q.get("target").getAsLong();
			boolean done = q.get("claimed").getAsBoolean();
			boolean canClaim = q.get("claimable").getAsBoolean();
			Label name = new Label(q.get("title").getAsString());
			name.getStyleClass().add("t-strong");
			name.setWrapText(true);
			Region bar = progressLine(target == 0 ? 1 : Math.min(1, progress / (double) target), done);
			String hint = q.has("hint") && !q.get("hint").isJsonNull() ? q.get("hint").getAsString()
					: compact(progress) + " / " + compact(target);
			Label hintLabel = new Label(hint);
			hintLabel.getStyleClass().add("t-mono");
			Button button = new Button(done ? "Done" : canClaim ? "Claim +" + q.get("coins").getAsInt() : "+" + q.get("coins").getAsInt());
			button.setDisable(!canClaim);
			button.getStyleClass().add(canClaim ? "buy-button" : "ghost-button");
			String id = q.get("id").getAsString();
			button.setOnAction(e -> run(() -> StoreApi.claimQuest(id), ok -> {
				status.setText("Quest reward claimed!");
				reload.run();
			}, status::setText));
			box.getChildren().add(rewardCard(new VBox(5, name, bar, hintLabel), button));
		}

		// Rewarded ads - only shown once the server has an ad network set up.
		boolean adsEnabled = r.get("adsEnabled").getAsBoolean();
		if (adsEnabled) {
			box.getChildren().add(adCard(r, status, reload));
		}

		long resets = r.get("resetsInSeconds").getAsLong();
		Label resetsLabel = note("New quests in " + resets / 3600 + "h " + (resets % 3600) / 60 + "m");
		resetsLabel.setPadding(new javafx.geometry.Insets(10, 16, 14, 16));
		box.getChildren().addAll(hairline(), resetsLabel);
		return box;
	}

	private static Node adCard(JsonObject r, Label status, Runnable reload) {
		int watched = r.get("adsWatched").getAsInt();
		int perDay = r.get("adsPerDay").getAsInt();
		Label adTitle = new Label("Watch an ad  +" + r.get("adCoins").getAsInt());
		adTitle.getStyleClass().add("t-strong");
		Label adCount = new Label(watched + " / " + perDay + " today");
		adCount.getStyleClass().add("t-mono");
		boolean adsLeft = watched < perDay;
		Button watch = new Button(adsLeft ? "Watch" : "Done");
		watch.setDisable(!adsLeft);
		if (adsLeft) {
			watch.getStyleClass().add("primary-button");
		}
		watch.setOnAction(e -> run(() -> {
			JsonObject wrap = new JsonObject();
			wrap.addProperty("url", StoreApi.startAd());
			return wrap;
		}, result -> {
			openUrl(result.get("url").getAsString());
			status.setText("The ad opened in your browser - coins arrive right after it ends.");
			Timeline later = new Timeline(new KeyFrame(Duration.seconds(40), ev -> reload.run()));
			later.play();
		}, status::setText));
		return rewardCard(new VBox(4, adTitle, adCount), watch);
	}

	private static Node rewardCard(Node left, Button action) {
		HBox.setHgrow(left, Priority.ALWAYS);
		action.setMinWidth(Region.USE_PREF_SIZE);
		action.getStyleClass().add("button-sm");
		HBox card = new HBox(12, left, action);
		card.setAlignment(Pos.CENTER_LEFT);
		card.setPadding(new javafx.geometry.Insets(12, 16, 12, 16));
		return card;
	}

	private static Region hairline() {
		Region line = new Region();
		line.getStyleClass().add("hairline-h");
		return line;
	}

	/** A thin progress line (real progress: how far a quest is). */
	private static Region progressLine(double fraction, boolean done) {
		Region fill = new Region();
		fill.setStyle("-fx-background-color: " + (done ? "-velo-green" : "-velo-gold") + "; -fx-background-radius: 2;");
		fill.setMinHeight(4);
		fill.setMaxHeight(4);
		StackPane track = new StackPane(fill);
		StackPane.setAlignment(fill, Pos.CENTER_LEFT);
		track.setMinHeight(4);
		track.setMaxHeight(4);
		track.setStyle("-fx-background-color: rgba(255,255,255,0.07); -fx-background-radius: 2;");
		fill.maxWidthProperty().bind(track.widthProperty().multiply(fraction));
		return track;
	}

	// ---- Owner tools ----

	private static Node ownerTools(JsonObject catalog, Runnable reload) {
		Label title = new Label("Owner tools");
		title.getStyleClass().add("t-heading");
		TextField player = new TextField();
		player.setPromptText("Player name or UUID");
		player.setPrefWidth(200);
		TextField amount = new TextField();
		amount.setPromptText("Amount, e.g. 500 or -200");
		amount.setPrefWidth(170);
		TextField note = new TextField();
		note.setPromptText("Note (optional)");
		HBox.setHgrow(note, Priority.ALWAYS);
		Label result = new Label();
		result.getStyleClass().add("settings-row-hint");
		result.setWrapText(true);

		Button give = new Button("Apply coins");
		give.getStyleClass().add("primary-button");
		give.setOnAction(e -> {
			int value;
			try {
				value = Integer.parseInt(amount.getText().trim().replace("+", ""));
			} catch (NumberFormatException ex) {
				result.setText("Enter a whole number, e.g. 500 or -200");
				return;
			}
			run(() -> StoreApi.adminCoins(player.getText().trim(), value, note.getText().trim()),
					view -> {
						result.setText(describe(view));
						reload.run();
					}, result::setText);
		});

		ComboBox<String> item = new ComboBox<>();
		// Every cosmetic the server has - testing and hidden ones too (the full panel: Store > Owner panel).
		StoreCatalog.all().forEach(i -> item.getItems().add(i.id()));
		run(StoreApi::adminCosmetics, all -> {
			if (!all.has("items") || !all.get("items").isJsonArray()) {
				return;
			}
			item.getItems().clear();
			all.getAsJsonArray("items").forEach(i -> item.getItems().add(i.getAsJsonObject().get("id").getAsString()));
		}, error -> { });
		item.setPromptText("Cosmetic");
		Button grant = new Button("Give item");
		grant.setOnAction(e -> run(() -> StoreApi.adminItem(player.getText().trim(), item.getValue(), true),
				view -> result.setText(describe(view)), result::setText));
		Button revoke = new Button("Remove item");
		revoke.getStyleClass().add("danger-ghost-button");
		revoke.setOnAction(e -> run(() -> StoreApi.adminItem(player.getText().trim(), item.getValue(), false),
				view -> result.setText(describe(view)), result::setText));
		Button lookup = new Button("Look up");
		lookup.getStyleClass().add("ghost-button");
		lookup.setOnAction(e -> run(() -> StoreApi.adminLookup(player.getText().trim()),
				view -> result.setText(describe(view)), result::setText));

		grant.getStyleClass().add("ghost-button");
		HBox row1 = new HBox(8, player, amount, note, give);
		row1.setAlignment(Pos.CENTER_LEFT);
		HBox row2 = new HBox(8, item, grant, revoke, lookup);
		row2.setAlignment(Pos.CENTER_LEFT);
		Label hint = note("Full catalog, bundles and players live in Store > Owner panel.");
		result.getStyleClass().setAll("label", "t-mono");
		VBox card = new VBox(12, new VBox(2, title, hint), row1, row2, result);
		card.getStyleClass().add("card");
		card.setPadding(new javafx.geometry.Insets(18, 20, 18, 20));
		return card;
	}

	private static String describe(JsonObject view) {
		StringBuilder text = new StringBuilder(view.get("username").getAsString() + ": " + view.get("balance").getAsInt()
				+ " coins, owns " + view.getAsJsonArray("owned").size() + " item(s)");
		JsonArray ledger = view.getAsJsonArray("ledger");
		for (int i = 0; i < Math.min(6, ledger.size()); i++) {
			JsonObject entry = ledger.get(i).getAsJsonObject();
			text.append("\n  ").append(entry.get("delta").getAsInt() >= 0 ? "+" : "").append(entry.get("delta").getAsInt())
					.append("  ").append(entry.get("reason").getAsString());
			if (entry.has("ref") && !entry.get("ref").isJsonNull()) {
				text.append("  (").append(entry.get("ref").getAsString()).append(")");
			}
		}
		return text.toString();
	}

	// ---- Helpers ----

	private static Label sectionLabel(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("section-label");
		return label;
	}

	private static Label note(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("t-muted");
		label.setWrapText(true);
		return label;
	}

	private static String compact(long n) {
		return n >= 10_000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : String.format(Locale.ROOT, "%,d", n);
	}

	private static String formatPrice(double price, String currency) {
		String symbol = switch (currency) {
			case "EUR" -> "€";
			case "USD" -> "$";
			case "GBP" -> "£";
			default -> currency + " ";
		};
		return symbol + String.format(Locale.ROOT, "%.2f", price);
	}

	private static void run(Call call, java.util.function.Consumer<JsonObject> onOk, java.util.function.Consumer<String> onError) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return (Object) call.run();
			} catch (StoreApi.StoreError e) {
				return e;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result instanceof StoreApi.StoreError e) {
				onError.accept(e.getMessage());
			} else {
				onOk.accept((JsonObject) result);
			}
		}));
	}

	static void openUrl(String url) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String[] command = os.contains("win") ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
					: os.contains("mac") ? new String[] {"open", url} : new String[] {"xdg-open", url};
			new ProcessBuilder(command).start();
		} catch (Exception ignored) {
			// Nothing else to try.
		}
	}
}
