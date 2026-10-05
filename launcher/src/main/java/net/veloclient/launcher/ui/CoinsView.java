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

	public static Node build() {
		StackPane page = new StackPane();
		Label loading = new Label("Loading your coins...");
		loading.getStyleClass().add("page-subtitle");
		page.getChildren().add(loading);
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
			page.getChildren().setAll(content((JsonObject) result[0], (JsonObject) result[1], reload[0]));
		}));
		reload[0].run();
		return page;
	}

	private static Node content(JsonObject catalog, JsonObject rewards, Runnable reload) {
		Label status = new Label();
		status.getStyleClass().add("purchase-status");
		status.setWrapText(true);

		// Header: big balance.
		Label title = new Label("Velo Coins");
		title.getStyleClass().add("page-title");
		Label sub = new Label("Buy coins for cosmetics - or earn some every day for free.");
		sub.getStyleClass().add("page-subtitle");
		VBox titles = new VBox(2, title, sub);
		Label balance = new Label(String.format(Locale.ROOT, "%,d", StoreApi.balance()));
		balance.getStyleClass().add("coins-balance");
		HBox balanceBox = new HBox(10, CosmeticUi.coin(30), balance);
		balanceBox.setAlignment(Pos.CENTER_RIGHT);
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox header = new HBox(12, titles, spacer, balanceBox);
		header.setAlignment(Pos.CENTER_LEFT);

		VBox left = new VBox(12, sectionLabel("Get coins"), packs(catalog, status, reload));
		HBox.setHgrow(left, Priority.ALWAYS);
		VBox right = new VBox(12, sectionLabel("Free coins"), rewards == null ? note("Sign in to earn free coins.") : freeCoins(rewards, status, reload));
		right.setMinWidth(340);
		right.setMaxWidth(380);
		HBox columns = new HBox(22, left, right);

		VBox root = new VBox(18, header, status, columns);
		if (StoreApi.isOwner()) {
			root.getChildren().add(ownerTools(catalog, reload));
		}
		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		return scroll;
	}

	// ---- Coin packs ----

	private static Node packs(JsonObject catalog, Label status, Runnable reload) {
		FlowPane grid = new FlowPane(14, 14);
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
			art.getStyleClass().add("coin-art");
			art.setPrefSize(200, 92);
			Label amount = new Label(String.format(Locale.ROOT, "%,d", coins + bonus));
			amount.getStyleClass().add("pack-amount");
			Label coinsWord = new Label("coins");
			coinsWord.getStyleClass().add("page-subtitle");
			HBox amountRow = new HBox(6, amount, coinsWord);
			amountRow.setAlignment(Pos.BASELINE_LEFT);
			VBox body = new VBox(4, art, amountRow);
			if (bonus > 0) {
				body.getChildren().add(CosmeticUi.badge("+" + String.format(Locale.ROOT, "%,d", bonus) + " bonus", "chip-owned"));
			}
			Button buy = new Button(enabled ? "Buy for " + formatPrice(price, currency) : "Coming soon");
			buy.getStyleClass().add("primary-button");
			buy.setMaxWidth(Double.MAX_VALUE);
			buy.setDisable(!enabled);
			String packId = pack.get("id").getAsString();
			buy.setOnAction(e -> startCheckout(packId, buy, status, reload));
			body.getChildren().add(buy);
			body.getStyleClass().addAll("pack-card", "motion-card");
			if (i == best) {
				body.getStyleClass().add("pack-card-best");
				Label ribbon = new Label("BEST VALUE");
				ribbon.getStyleClass().add("best-ribbon");
				StackPane.setAlignment(ribbon, Pos.TOP_RIGHT);
				art.getChildren().add(ribbon);
			}
			body.setPrefWidth(220);
			grid.getChildren().add(body);
		}
		UiMotion.stagger(grid, 8);
		Label secure = note(enabled ? "Secure checkout by Tebex - cards, PayPal, paysafecard and more. Opens in your browser."
				: "Buying coins isn't open yet.");
		return new VBox(10, grid, secure);
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
		VBox box = new VBox(10);

		// Daily reward + streak.
		int streak = r.get("streak").getAsInt();
		boolean claimed = r.get("loginClaimed").getAsBoolean();
		boolean claimable = r.get("loginClaimable").getAsBoolean();
		Label dailyTitle = new Label("Daily reward");
		dailyTitle.getStyleClass().add("reward-title");
		HBox dots = new HBox(5);
		int days = streak + (claimed ? 1 : 0);
		int litInWeek = days == 0 ? 0 : (days - 1) % 7 + 1;
		for (int d = 0; d < 7; d++) {
			Region dot = new Region();
			dot.getStyleClass().add(d < litInWeek ? "streak-dot-on" : "streak-dot");
			if (d == 6) {
				dot.getStyleClass().add("streak-dot-week");
			}
			dots.getChildren().add(dot);
		}
		// Every 7th day in a row pays a weekly bonus on top.
		int weekly = r.has("weeklyBonus") ? r.get("weeklyBonus").getAsInt() : 0;
		boolean weeklyToday = r.has("weeklyBonusToday") && r.get("weeklyBonusToday").getAsBoolean();
		Label streakLabel = new Label(weekly <= 0 ? "" : weeklyToday ? "Week bonus!" : "Day 7 +" + weekly);
		streakLabel.getStyleClass().add("streak-week-label");
		HBox streakRow = new HBox(8, dots, streakLabel);
		streakRow.setAlignment(Pos.CENTER_LEFT);
		Button claim = new Button(claimed ? "Claimed" : claimable ? "Claim +" + r.get("loginCoins").getAsInt() : "Play 5 min in game");
		claim.setDisable(!claimable);
		if (claimable) {
			claim.getStyleClass().add("primary-button");
		}
		claim.setOnAction(e -> run(StoreApi::claimLogin, ok -> {
			status.setText("Daily reward claimed!");
			reload.run();
		}, status::setText));
		box.getChildren().add(rewardCard(new VBox(6, dailyTitle, streakRow), claim));

		// Today's quests.
		for (var element : r.getAsJsonArray("quests")) {
			JsonObject q = element.getAsJsonObject();
			long progress = q.get("progress").getAsLong();
			long target = q.get("target").getAsLong();
			boolean done = q.get("claimed").getAsBoolean();
			boolean canClaim = q.get("claimable").getAsBoolean();
			Label name = new Label(q.get("title").getAsString());
			name.getStyleClass().add("reward-title");
			ProgressBar bar = new ProgressBar(target == 0 ? 1 : Math.min(1, progress / (double) target));
			bar.setMaxWidth(Double.MAX_VALUE);
			bar.getStyleClass().add("quest-bar");
			if (done) {
				bar.getStyleClass().add("quest-bar-done");
			}
			String hint = q.has("hint") && !q.get("hint").isJsonNull() ? q.get("hint").getAsString()
					: compact(progress) + " / " + compact(target);
			Label hintLabel = new Label(hint);
			hintLabel.getStyleClass().add("settings-row-hint");
			Button button = new Button(done ? "Done" : "+" + q.get("coins").getAsInt());
			button.setDisable(!canClaim);
			if (canClaim) {
				button.getStyleClass().add("update-all-button");
			}
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
		box.getChildren().add(note("New rewards in " + resets / 3600 + "h " + (resets % 3600) / 60 + "m"));
		return box;
	}

	private static Node adCard(JsonObject r, Label status, Runnable reload) {
		int watched = r.get("adsWatched").getAsInt();
		int perDay = r.get("adsPerDay").getAsInt();
		Label adTitle = new Label("Watch an ad  +" + r.get("adCoins").getAsInt());
		adTitle.getStyleClass().add("reward-title");
		Label adCount = new Label(watched + " / " + perDay + " today");
		adCount.getStyleClass().add("settings-row-hint");
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
		action.setMinWidth(92);
		HBox card = new HBox(12, left, action);
		card.setAlignment(Pos.CENTER_LEFT);
		card.getStyleClass().add("reward-card");
		return card;
	}

	// ---- Owner tools ----

	private static Node ownerTools(JsonObject catalog, Runnable reload) {
		Label title = new Label("Owner tools");
		title.getStyleClass().add("settings-card-title");
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
		StoreCatalog.all().forEach(i -> item.getItems().add(i.id()));
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

		HBox row1 = new HBox(8, player, amount, note, give);
		row1.setAlignment(Pos.CENTER_LEFT);
		HBox row2 = new HBox(8, item, grant, revoke, lookup);
		row2.setAlignment(Pos.CENTER_LEFT);
		VBox card = new VBox(10, title, row1, row2, result);
		card.getStyleClass().add("settings-card");
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
		label.getStyleClass().add("settings-row-hint");
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
