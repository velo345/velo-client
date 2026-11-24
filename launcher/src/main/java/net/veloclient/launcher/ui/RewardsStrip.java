package net.veloclient.launcher.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import net.veloclient.launcher.social.StoreApi;

import java.util.concurrent.CompletableFuture;

/**
 * Free coins, right where people shop (owner 2026-11-24: "nobody finds the daily rewards"): a strip at the top of the
 * Store with today's login reward and the three quests - progress and Claim buttons right there - and a link to the
 * Velo Coins page for the rest. Loads in the background; hidden when signed out.
 */
public final class RewardsStrip {

	private RewardsStrip() {
	}

	public static Node build(Runnable openCoins) {
		VBox holder = new VBox();
		holder.setManaged(false);
		holder.setVisible(false);
		load(holder, openCoins);
		return holder;
	}

	private static void load(VBox holder, Runnable openCoins) {
		if (!StoreApi.connected()) {
			return;
		}
		CompletableFuture.supplyAsync(() -> {
			try {
				return StoreApi.rewards();
			} catch (StoreApi.StoreError e) {
				return null;
			}
		}).thenAccept(r -> Platform.runLater(() -> {
			if (r == null) {
				return;
			}
			holder.getChildren().setAll(strip(r, holder, openCoins));
			holder.setManaged(true);
			holder.setVisible(true);
		}));
	}

	private static Node strip(JsonObject r, VBox holder, Runnable openCoins) {
		int claimable = 0;
		HBox row = new HBox(10);
		row.setAlignment(Pos.CENTER_LEFT);

		// login reward
		boolean loginClaimed = r.get("loginClaimed").getAsBoolean();
		boolean loginClaimable = r.get("loginClaimable").getAsBoolean();
		int loginCoins = r.get("loginCoins").getAsInt();
		if (loginClaimable) {
			claimable++;
		}
		Button login = button(loginClaimed ? "Claimed" : loginClaimable ? "Claim +" + loginCoins : "Play 5 min", loginClaimable);
		login.setOnAction(e -> claim(login, StoreApi::claimLogin, holder, openCoins));
		row.getChildren().add(tile("Daily reward", "Day " + (r.get("streak").getAsInt() + (loginClaimed ? 1 : 0)) + " in a row", 1.0, login));

		// today's quests
		for (JsonElement element : r.getAsJsonArray("quests")) {
			JsonObject q = element.getAsJsonObject();
			long progress = q.get("progress").getAsLong();
			long target = Math.max(1, q.get("target").getAsLong());
			boolean done = q.get("claimed").getAsBoolean();
			boolean canClaim = q.get("claimable").getAsBoolean();
			if (canClaim) {
				claimable++;
			}
			String id = q.get("id").getAsString();
			Button b = button(done ? "Done" : canClaim ? "Claim +" + q.get("coins").getAsInt() : "+" + q.get("coins").getAsInt(), canClaim);
			b.setOnAction(e -> claim(b, () -> StoreApi.claimQuest(id), holder, openCoins));
			String sub = q.has("hint") && !q.get("hint").isJsonNull() ? q.get("hint").getAsString() : compact(progress) + " / " + compact(target);
			row.getChildren().add(tile(q.get("title").getAsString(), sub, Math.min(1, progress / (double) target), b));
		}

		Label title = new Label(claimable > 0 ? "Free coins waiting: " + claimable : "Free coins every day");
		title.setStyle("-fx-text-fill: -velo-text; -fx-font-size: 14px; -fx-font-weight: bold;");
		long resets = r.get("resetsInSeconds").getAsLong();
		Label sub = new Label("Daily reward + 3 quests, new ones in " + resets / 3600 + "h " + (resets % 3600) / 60 + "m");
		sub.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		Button more = new Button("All free coins  ›");
		more.getStyleClass().add("ghost-button");
		more.setOnAction(e -> openCoins.run());
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		HBox head = new HBox(10, new VBox(2, title, sub), grow, more);
		head.setAlignment(Pos.CENTER_LEFT);

		VBox box = new VBox(10, head, row);
		box.setPadding(new Insets(14, 16, 14, 16));
		box.setStyle("-fx-background-color: linear-gradient(to right, rgba(247,192,74,0.12), rgba(247,192,74,0.03)); -fx-background-radius: 14;"
				+ " -fx-border-color: rgba(247,192,74," + (claimable > 0 ? "0.55" : "0.22") + "); -fx-border-radius: 14;");
		return box;
	}

	private static Node tile(String name, String sub, double progress, Button action) {
		Label n = new Label(name);
		n.setStyle("-fx-text-fill: -velo-text; -fx-font-size: 12px; -fx-font-weight: bold;");
		n.setMaxWidth(150);
		Label s = new Label(sub);
		s.setStyle("-fx-text-fill: -velo-text-3; -fx-font-size: 11px;");
		Region track = new Region();
		track.setPrefHeight(4);
		track.setMaxHeight(4);
		track.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-background-radius: 2;");
		Region fill = new Region();
		fill.setPrefHeight(4);
		fill.setMaxHeight(4);
		fill.setStyle("-fx-background-color: -velo-gold; -fx-background-radius: 2;");
		StackPane bar = new StackPane(track, fill);
		bar.setAlignment(Pos.CENTER_LEFT);
		fill.maxWidthProperty().bind(bar.widthProperty().multiply(Math.max(0, Math.min(1, progress))));
		VBox text = new VBox(4, n, s, bar);
		HBox.setHgrow(text, Priority.ALWAYS);
		action.setMinWidth(Region.USE_PREF_SIZE);
		HBox t = new HBox(10, text, action);
		t.setAlignment(Pos.CENTER_LEFT);
		t.setPadding(new Insets(10, 12, 10, 12));
		t.setStyle("-fx-background-color: rgba(0,0,0,0.25); -fx-background-radius: 10;");
		HBox.setHgrow(t, Priority.ALWAYS);
		t.setMinWidth(0);
		return t;
	}

	private static Button button(String text, boolean primary) {
		Button b = new Button(text);
		b.getStyleClass().add(primary ? "buy-button" : "ghost-button");
		b.setDisable(!primary);
		return b;
	}

	private interface Call {
		JsonObject run() throws StoreApi.StoreError;
	}

	private static void claim(Button b, Call call, VBox holder, Runnable openCoins) {
		b.setDisable(true);
		CompletableFuture.runAsync(() -> {
			try {
				call.run();
				Platform.runLater(() -> load(holder, openCoins));
			} catch (StoreApi.StoreError e) {
				Platform.runLater(() -> {
					b.setText(e.getMessage().length() > 24 ? "Try later" : e.getMessage());
					b.setDisable(false);
				});
			}
		});
	}

	private static String compact(long n) {
		return n >= 10_000 ? String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
	}
}
