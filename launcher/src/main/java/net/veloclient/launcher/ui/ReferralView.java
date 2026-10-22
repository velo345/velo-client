package net.veloclient.launcher.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import net.veloclient.launcher.social.StoreApi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Invite friends: your code, the reward ladder (the more friends you bring, the more each one pays - up to
 * {@code maxInvites}), everyone you invited with their play time towards the 2 hours that pay out, and - in the
 * first days - a field to enter a friend's code yourself. Everything comes from the server; nothing is counted here.
 */
public final class ReferralView {

	public static final String SITE = "https://velo-client.com";

	private ReferralView() {
	}

	public static Node build(Runnable openCoins) {
		VBox content = new VBox(22);
		content.setPadding(new Insets(4, 24, 28, 4));
		content.setMaxWidth(1040);
		content.getChildren().add(Skeleton.detail());
		load(content, openCoins);
		ScrollPane scroll = new ScrollPane(content);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		VBox page = new VBox(scroll);
		page.getStyleClass().add("page");
		VBox.setVgrow(scroll, Priority.ALWAYS);
		return page;
	}

	private static void load(VBox content, Runnable openCoins) {
		CompletableFuture.supplyAsync(() -> {
			try {
				return (Object) StoreApi.referral();
			} catch (StoreApi.StoreError e) {
				return e.getMessage();
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
			if (result instanceof JsonObject data) {
				content.getChildren().setAll(page(data, content, openCoins));
			} else {
				Label error = new Label(String.valueOf(result));
				error.getStyleClass().add("t-muted");
				content.getChildren().setAll(error);
			}
		}));
	}

	private static java.util.List<Node> page(JsonObject data, VBox content, Runnable openCoins) {
		java.util.List<Node> out = new java.util.ArrayList<>();
		String code = data.get("code").getAsString();
		int friendCoins = data.get("friendCoins").getAsInt();
		long target = data.get("targetSeconds").getAsLong();
		int max = data.has("maxInvites") ? data.get("maxInvites").getAsInt() : 10;
		int paid = data.get("paidInvites").getAsInt();
		int top = 0;
		for (JsonElement step : data.getAsJsonArray("ladder")) {
			top = Math.max(top, step.getAsJsonObject().get("coins").getAsInt());
		}

		// ---- Hero: code + share ----
		Label overline = new Label("INVITE FRIENDS");
		overline.getStyleClass().add("t-overline");
		Label title = new Label("Up to " + top + " coins for every friend");
		title.getStyleClass().add("t-title");
		Label sub = new Label("Your friend gets " + friendCoins + " coins too, once they've played " + hours(target) + ".");
		sub.getStyleClass().add("t-body");
		sub.setWrapText(true);

		Label codeLabel = new Label(code);
		codeLabel.setStyle("-fx-font-family: 'JetBrains Mono Bold'; -fx-font-size: 30px; -fx-text-fill: -velo-text;");
		StackPane codeBox = new StackPane(codeLabel);
		codeBox.setPadding(new Insets(10, 22, 10, 22));
		codeBox.setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 12; -fx-border-color: -velo-hairline-2;"
				+ " -fx-border-radius: 12; -fx-border-style: segments(6, 5);");
		Button copy = new Button("Copy code", Icons.icon("copy", 14, Color.WHITE));
		copy.getStyleClass().add("primary-button");
		copy.setOnAction(e -> {
			copyText(code);
			Toasts.show(copy, "Code copied");
		});
		Button share = new Button("Copy invite message", Icons.icon("share", 14, Color.WHITE));
		share.getStyleClass().add("ghost-button");
		share.setOnAction(e -> {
			copyText("Play with me on Velo Client! Enter my code " + code + " in the launcher and you get " + friendCoins
					+ " Velo Coins: " + SITE);
			Toasts.show(share, "Invite message copied - paste it anywhere");
		});
		HBox codeRow = new HBox(12, codeBox, copy, share);
		codeRow.setAlignment(Pos.CENTER_LEFT);
		VBox hero = new VBox(10, overline, title, sub, new Region(), codeRow);
		VBox.setMargin(codeRow, new Insets(8, 0, 0, 0));
		hero.getStyleClass().add("card");
		hero.setPadding(new Insets(28, 30, 28, 30));
		hero.setStyle("-fx-background-color: radial-gradient(center 90% 0%, radius 70%, -velo-accent-soft 0%, transparent 100%), -velo-surface;");
		out.add(hero);

		// ---- Stats ----
		HBox stats = new HBox(12, stat("Earned", StoreTiles.format(data.get("earned").getAsInt()), true),
				stat("Friends paid out", paid + " / " + max, false),
				stat("Next friend pays", paid >= max ? "Limit reached" : StoreTiles.format(data.get("nextReward").getAsInt()), paid < max));
		out.add(stats);

		// ---- Ladder ----
		HBox ladder = new HBox(10);
		for (JsonElement element : data.getAsJsonArray("ladder")) {
			JsonObject step = element.getAsJsonObject();
			int from = step.get("from").getAsInt();
			int to = step.get("to").getAsInt();
			boolean current = paid + 1 >= from && (to == 0 || paid + 1 <= to) && paid < max;
			boolean done = to != 0 && paid >= to;
			Label range = new Label(to == 0 ? "Friend " + from + "+" : from == to ? "Friend " + from : "Friends " + from + "-" + to);
			range.getStyleClass().add(current ? "t-strong" : "t-muted");
			HBox reward = StoreTiles.price(step.get("coins").getAsInt(), null, 18);
			Label each = new Label("each");
			each.getStyleClass().add("t-muted");
			HBox rewardRow = new HBox(6, reward, each);
			rewardRow.setAlignment(Pos.CENTER_LEFT);
			VBox cell = new VBox(8, range, rewardRow);
			if (done) {
				Label check = new Label("Done");
				check.getStyleClass().addAll("badge", "badge-green");
				cell.getChildren().add(check);
			} else if (current) {
				Label now = new Label("You're here");
				now.getStyleClass().addAll("badge", "badge-blue");
				cell.getChildren().add(now);
			}
			cell.getStyleClass().addAll("card-2");
			if (current) {
				cell.getStyleClass().add("card-selected");
			}
			cell.setPadding(new Insets(14, 16, 14, 16));
			HBox.setHgrow(cell, Priority.ALWAYS);
			cell.setMaxWidth(Double.MAX_VALUE);
			ladder.getChildren().add(cell);
		}
		out.add(section("The more friends, the more each pays", "Up to " + max + " friends count. Each one pays out once they've played "
				+ hours(target) + ".", ladder));

		// ---- Got a code? / invited by ----
		JsonObject invitedBy = data.has("invitedBy") && data.get("invitedBy").isJsonObject() ? data.getAsJsonObject("invitedBy") : null;
		if (invitedBy != null) {
			boolean rewarded = invitedBy.get("rewarded").getAsBoolean();
			long played = invitedBy.get("playedSeconds").getAsLong();
			Label text = new Label("Invited by " + invitedBy.get("username").getAsString());
			text.getStyleClass().add("t-strong");
			Label detail = new Label(rewarded ? "You both got your coins."
					: "You get " + friendCoins + " coins after " + hours(target) + " in game - " + minutes(played) + " so far.");
			detail.getStyleClass().add("t-muted");
			HBox row = new HBox(14, avatar(invitedBy.get("username").getAsString()), new VBox(3, text, detail));
			row.setAlignment(Pos.CENTER_LEFT);
			row.getStyleClass().add("card");
			row.setPadding(new Insets(16, 18, 16, 18));
			out.add(row);
		} else if (data.get("canRedeem").getAsBoolean()) {
			out.add(redeemCard(friendCoins, target, data, content, openCoins));
		}

		// ---- Invites ----
		VBox list = new VBox(0);
		list.getStyleClass().add("card");
		if (data.getAsJsonArray("invites").isEmpty()) {
			Label empty = new Label("Nobody yet. Send your code to a friend who plays Minecraft.");
			empty.getStyleClass().add("t-muted");
			empty.setPadding(new Insets(18));
			list.getChildren().add(empty);
		}
		boolean first = true;
		for (JsonElement element : data.getAsJsonArray("invites")) {
			if (!first) {
				Region line = new Region();
				line.getStyleClass().add("hairline-h");
				list.getChildren().add(line);
			}
			first = false;
			list.getChildren().add(inviteRow(element.getAsJsonObject(), target));
		}
		out.add(section("Your invites", null, list));

		// ---- Fair play ----
		VBox rules = new VBox(8);
		for (String rule : new String[] {
				"Your friend enters your code in the launcher within their first " + 7 + " days.",
				"Play time counts in game only, the same time daily quests use.",
				"Every account can have one inviter. Codes can't be swapped back and forth.",
				"Invites past " + max + " don't pay out, and coins farmed with alt accounts can be taken back."}) {
			HBox row = new HBox(10, Icons.icon("check", 14, Color.web("#3DDC97"), 2.2), wrap(rule));
			row.setAlignment(Pos.TOP_LEFT);
			rules.getChildren().add(row);
		}
		out.add(section("Fair play", null, rules));
		return out;
	}

	private static Node redeemCard(int friendCoins, long target, JsonObject data, VBox content, Runnable openCoins) {
		Label text = new Label("Got a friend's code?");
		text.getStyleClass().add("t-heading");
		String deadline = data.has("redeemUntil") && !data.get("redeemUntil").isJsonNull()
				? " until " + java.time.Instant.ofEpochMilli(data.get("redeemUntil").getAsLong()).atZone(java.time.ZoneId.systemDefault())
						.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH))
				: "";
		Label detail = new Label("Enter it" + deadline + " and get " + friendCoins + " coins after " + hours(target) + " of play.");
		detail.getStyleClass().add("t-muted");
		TextField field = new TextField();
		field.setPromptText("Code or friend's name");
		field.setPrefWidth(220);
		Button redeem = new Button("Redeem");
		redeem.getStyleClass().add("primary-button");
		Label result = new Label();
		result.getStyleClass().add("t-muted");
		redeem.disableProperty().bind(field.textProperty().isEmpty());
		Runnable go = () -> {
			redeem.disableProperty().unbind();
			redeem.setDisable(true);
			String code = field.getText().trim();
			CompletableFuture.supplyAsync(() -> {
				try {
					StoreApi.redeemReferral(code);
					return (String) null;
				} catch (StoreApi.StoreError e) {
					return e.getMessage();
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(error -> Platform.runLater(() -> {
				if (error == null) {
					Toasts.show(redeem, "Code saved - play " + hours(target) + " to get your coins");
					load(content, openCoins);
				} else {
					redeem.disableProperty().bind(field.textProperty().isEmpty());
					result.setText(error);
				}
			}));
		};
		redeem.setOnAction(e -> go.run());
		field.setOnAction(e -> {
			if (!field.getText().isBlank()) {
				go.run();
			}
		});
		VBox texts = new VBox(3, text, detail);
		HBox.setHgrow(texts, Priority.ALWAYS);
		HBox form = new HBox(8, field, redeem);
		form.setAlignment(Pos.CENTER_RIGHT);
		HBox row = new HBox(16, Icons.icon("gift", 22, Color.web("#F7C04A")), texts, form);
		row.setAlignment(Pos.CENTER_LEFT);
		result.managedProperty().bind(result.textProperty().isNotEmpty());
		result.visibleProperty().bind(result.managedProperty());
		VBox card = new VBox(6, row, result);
		card.getStyleClass().add("card");
		card.setPadding(new Insets(18, 20, 18, 20));
		return card;
	}

	private static Node inviteRow(JsonObject invite, long target) {
		String name = invite.get("username").getAsString();
		boolean rewarded = invite.get("rewarded").getAsBoolean();
		long played = invite.get("playedSeconds").getAsLong();
		Label nameLabel = new Label(name);
		nameLabel.getStyleClass().add("t-strong");
		Label when = new Label("Joined " + java.time.Instant.ofEpochMilli(invite.get("joinedAt").getAsLong()).atZone(java.time.ZoneId.systemDefault())
				.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)));
		when.getStyleClass().add("t-muted");
		VBox names = new VBox(2, nameLabel, when);
		HBox.setHgrow(names, Priority.ALWAYS);
		Node right;
		if (rewarded) {
			int coins = invite.get("coins").getAsInt();
			Label plus = new Label(coins > 0 ? "+" + StoreTiles.format(coins) : "Over the limit");
			plus.getStyleClass().add(coins > 0 ? "t-gold" : "t-muted");
			plus.setStyle("-fx-font-family: 'JetBrains Mono Bold'; -fx-font-size: 14px;");
			right = plus;
		} else {
			// Real progress: play time towards the payout.
			Region fill = new Region();
			fill.setStyle("-fx-background-color: -velo-accent; -fx-background-radius: 3;");
			fill.setPrefWidth(120 * Math.min(1, played / (double) target));
			fill.setMinHeight(6);
			StackPane track = new StackPane(fill);
			StackPane.setAlignment(fill, Pos.CENTER_LEFT);
			track.setPrefSize(120, 6);
			track.setMaxSize(120, 6);
			track.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-background-radius: 3;");
			Label time = new Label(minutes(played) + " / " + hours(target));
			time.getStyleClass().add("t-mono");
			HBox box = new HBox(10, track, time);
			box.setAlignment(Pos.CENTER_RIGHT);
			right = box;
		}
		Label state = new Label(rewarded ? "Paid" : "Playing");
		state.getStyleClass().addAll("badge", rewarded ? "badge-green" : "badge-grey");
		HBox row = new HBox(14, avatar(name), names, right, state);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(12, 16, 12, 16));
		return row;
	}

	private static Node avatar(String name) {
		Label letter = new Label(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
		letter.setStyle("-fx-font-family: 'Sora'; -fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: -velo-text;");
		StackPane avatar = new StackPane(letter);
		avatar.setMinSize(36, 36);
		avatar.setMaxSize(36, 36);
		avatar.setStyle("-fx-background-color: -velo-accent-soft; -fx-background-radius: 10;");
		return avatar;
	}

	private static Node stat(String label, String value, boolean coin) {
		Label name = new Label(label);
		name.getStyleClass().add("t-muted");
		Label number = new Label(value);
		number.getStyleClass().add("t-title-sm");
		HBox valueRow = new HBox(8, number);
		valueRow.setAlignment(Pos.CENTER_LEFT);
		if (coin) {
			valueRow.getChildren().add(0, StoreTiles.coin(18));
		}
		VBox card = new VBox(6, name, valueRow);
		card.getStyleClass().add("card");
		card.setPadding(new Insets(14, 18, 14, 18));
		HBox.setHgrow(card, Priority.ALWAYS);
		card.setMaxWidth(Double.MAX_VALUE);
		return card;
	}

	private static Node section(String heading, String subtitle, Node body) {
		Label title = new Label(heading);
		title.getStyleClass().add("t-heading");
		VBox box = new VBox(4, title);
		if (subtitle != null) {
			Label sub = new Label(subtitle);
			sub.getStyleClass().add("t-muted");
			sub.setWrapText(true);
			box.getChildren().add(sub);
		}
		VBox.setMargin(body, new Insets(10, 0, 0, 0));
		box.getChildren().add(body);
		return box;
	}

	private static Label wrap(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("t-body");
		label.setWrapText(true);
		return label;
	}

	private static void copyText(String text) {
		ClipboardContent clip = new ClipboardContent();
		clip.putString(text);
		Clipboard.getSystemClipboard().setContent(clip);
	}

	static String hours(long seconds) {
		long h = seconds / 3600;
		return h + (h == 1 ? " hour" : " hours");
	}

	static String minutes(long seconds) {
		long m = seconds / 60;
		return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
	}
}
