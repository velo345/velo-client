package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import net.veloclient.launcher.social.StoreApi;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One cosmetic's page, the same for wings, capes and auras: a big stage with you wearing it on the left (and
 * its controls under it), the purchase panel on the right - price, Buy / Equip / Get more coins, Gift and
 * Wishlist.
 */
public final class ItemPage {

	/** How the item is bought and worn. {@code buy} runs off the UI thread and returns an error message or null. */
	public record Purchase(String itemId, int price, BooleanSupplier owned, BooleanSupplier equipped, Runnable equip, Runnable unequip,
			Supplier<String> buy, String noun, boolean giftable) {
	}

	private ItemPage() {
	}

	/**
	 * @param back        back to the Store
	 * @param openCoins   opens the coins page (null hides "Get more coins")
	 * @param stage       the preview (fills the left side)
	 * @param stageBottom controls under the preview (animation chips...), or null
	 * @param extra       extra panel content under the description (settings...), or null
	 */
	public static Node build(Runnable back, Runnable openCoins, Node stage, Node stageBottom, String hint, String accent, String breadcrumb,
			StoreTiles.Rarity rarity, String title, String description, List<String> bullets, Node extra, Purchase purchase) {
		StackPane left = new StackPane();
		left.setMinWidth(0);
		left.setStyle("-fx-background-color: radial-gradient(center 50% 62%, radius 55%, " + accent + "38 0%, transparent 100%), derive(-velo-background, 3%);"
				+ " -fx-background-radius: 20;");
		Rectangle clip = new Rectangle();
		clip.setArcWidth(40);
		clip.setArcHeight(40);
		clip.widthProperty().bind(left.widthProperty());
		clip.heightProperty().bind(left.heightProperty());
		left.setClip(clip);
		javafx.scene.shape.Ellipse floor = new javafx.scene.shape.Ellipse(170, 26);
		floor.setFill(new javafx.scene.paint.RadialGradient(0, 0, 0.5, 0.5, 0.5, true, javafx.scene.paint.CycleMethod.NO_CYCLE,
				new javafx.scene.paint.Stop(0, Color.web(accent, 0.35)), new javafx.scene.paint.Stop(1, Color.web(accent, 0))));
		floor.setMouseTransparent(true);
		StackPane.setAlignment(floor, Pos.BOTTOM_CENTER);
		StackPane.setMargin(floor, new Insets(0, 0, 90, 0));
		left.getChildren().addAll(floor, stage);
		Button backButton = new Button("Store");
		backButton.setGraphic(Icons.icon("arrow-left", 15, Color.web("#E4E4E8"), 2.2));
		backButton.getStyleClass().add("button-sm");
		backButton.setStyle("-fx-background-color: rgba(11,11,14,0.6); -fx-background-radius: 999; -fx-border-radius: 999;");
		backButton.setOnAction(e -> back.run());
		StackPane.setAlignment(backButton, Pos.TOP_LEFT);
		StackPane.setMargin(backButton, new Insets(18));
		left.getChildren().add(backButton);
		VBox bottom = new VBox(10);
		bottom.setAlignment(Pos.CENTER);
		bottom.setPickOnBounds(false);
		bottom.setMaxHeight(Region.USE_PREF_SIZE);
		if (stageBottom != null) {
			bottom.getChildren().add(stageBottom);
		}
		if (hint != null) {
			Label hintLabel = new Label(hint);
			hintLabel.getStyleClass().add("t-muted");
			bottom.getChildren().add(hintLabel);
		}
		StackPane.setAlignment(bottom, Pos.BOTTOM_CENTER);
		StackPane.setMargin(bottom, new Insets(0, 0, 20, 0));
		left.getChildren().add(bottom);
		HBox.setHgrow(left, Priority.ALWAYS);

		Label crumb = new Label(breadcrumb);
		crumb.getStyleClass().add("t-muted");
		crumb.setStyle("-fx-font-size: 13px;");
		Label rarityBadge = new Label(rarity.label);
		rarityBadge.getStyleClass().add("badge");
		rarityBadge.setStyle("-fx-text-fill: " + rarity.color + "; -fx-background-color: " + rarity.color + "26;");
		Label name = new Label(title);
		name.getStyleClass().add("t-display");
		name.setStyle("-fx-font-size: 36px;");
		name.setWrapText(true);
		Label desc = new Label(description);
		desc.getStyleClass().add("t-body");
		desc.setStyle("-fx-font-size: 14px;");
		desc.setWrapText(true);
		VBox panel = new VBox(10, crumb, rarityBadge, name, desc);
		if (bullets != null && !bullets.isEmpty()) {
			VBox list = new VBox(8);
			list.setPadding(new Insets(6, 0, 0, 0));
			for (String bullet : bullets) {
				Label line = new Label(bullet);
				line.getStyleClass().add("t-body");
				line.setWrapText(true);
				line.setGraphic(Icons.icon("check", 15, Color.web("#3DDC97"), 2.5));
				line.setGraphicTextGap(10);
				list.getChildren().add(line);
			}
			panel.getChildren().add(list);
		}
		if (extra != null) {
			VBox.setMargin(extra, new Insets(8, 0, 0, 0));
			panel.getChildren().add(extra);
		}
		Region grow = new Region();
		VBox.setVgrow(grow, Priority.ALWAYS);
		panel.getChildren().addAll(grow, purchaseCard(left, openCoins, purchase));
		panel.setPadding(new Insets(4, 4, 4, 4));
		ScrollPane scroll = new ScrollPane(panel);
		scroll.setFitToWidth(true);
		scroll.setFitToHeight(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setMinWidth(380);
		scroll.setPrefWidth(440);
		scroll.setMaxWidth(460);
		HBox page = new HBox(28, left, scroll);
		page.getStyleClass().add("page");
		VBox.setVgrow(page, Priority.ALWAYS);
		return page;
	}

	private static Node purchaseCard(Node anyNode, Runnable openCoins, Purchase purchase) {
		HBox priceRow = new HBox();
		priceRow.setAlignment(Pos.CENTER_LEFT);
		Label balance = new Label();
		balance.getStyleClass().add("t-muted");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Button action = new Button();
		action.setMaxWidth(Double.MAX_VALUE);
		action.setMinHeight(50);
		Label status = new Label();
		status.getStyleClass().add("t-muted");
		status.setWrapText(true);
		Runnable[] refresh = new Runnable[1];
		refresh[0] = () -> {
			int coins = StoreApi.balance();
			boolean owned = purchase.owned().getAsBoolean();
			boolean equipped = purchase.equipped().getAsBoolean();
			priceRow.getChildren().setAll(owned ? ownedPill() : StoreTiles.price(purchase.price(), StoreApi.fullPrice(purchase.itemId()), 26), spacer, balance);
			balance.setText(coins >= 0 ? "You have " + StoreTiles.format(coins) : "");
			action.getStyleClass().removeAll("primary-button", "buy-button", "glass-button");
			if (equipped) {
				action.setText("Take " + (purchase.noun().endsWith("s") ? "them" : "it") + " off");
				action.getStyleClass().add("glass-button");
			} else if (owned) {
				action.setText("Equip " + purchase.noun());
				action.getStyleClass().add("primary-button");
			} else if (coins >= 0 && coins < purchase.price() && openCoins != null) {
				action.setText("Get " + StoreTiles.format(purchase.price() - coins) + " more coins");
				action.getStyleClass().add("buy-button");
			} else {
				action.setText("Buy for " + StoreTiles.format(purchase.price()));
				action.getStyleClass().add("buy-button");
			}
		};
		refresh[0].run();
		action.setOnAction(e -> {
			int coins = StoreApi.balance();
			if (purchase.equipped().getAsBoolean()) {
				purchase.unequip().run();
				status.setText("Taken off.");
				refresh[0].run();
				return;
			}
			if (purchase.owned().getAsBoolean()) {
				purchase.equip().run();
				Toasts.show(action, purchase.noun().substring(0, 1).toUpperCase() + purchase.noun().substring(1) + " equipped - shows in your next game");
				status.setText("");
				refresh[0].run();
				return;
			}
			if (coins >= 0 && coins < purchase.price() && openCoins != null) {
				openCoins.run();
				return;
			}
			action.setDisable(true);
			status.setText("Buying...");
			CompletableFuture.supplyAsync(purchase.buy(), Executors.newVirtualThreadPerTaskExecutor()).thenAccept(error -> Platform.runLater(() -> {
				action.setDisable(false);
				if (error == null) {
					purchase.equip().run();
					status.setText("");
					Toasts.show(action, "It's yours - added to your Locker and equipped");
				} else {
					status.setText(error);
				}
				refresh[0].run();
			}));
		});

		Button gift = new Button("Gift");
		gift.setGraphic(Icons.icon("gift", 15, Color.web("#E4E4E8")));
		gift.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(gift, Priority.ALWAYS);
		gift.setDisable(!purchase.giftable());
		gift.setOnAction(e -> giftPrompt(gift, purchase, status, refresh[0]));
		Button wish = new Button();
		wish.setMaxWidth(Double.MAX_VALUE);
		HBox.setHgrow(wish, Priority.ALWAYS);
		Runnable wishLabel = () -> {
			boolean on = StoreApi.wishlisted(purchase.itemId());
			wish.setText(on ? "On your wishlist" : "Wishlist");
			wish.setGraphic(Icons.icon("heart", 15, Color.web(on ? "#FF7A7E" : "#E4E4E8")));
		};
		wishLabel.run();
		wish.setOnAction(e -> {
			boolean on = StoreApi.toggleWishlist(purchase.itemId());
			wishLabel.run();
			if (on) {
				Toasts.show(wish, "heart", "#FF7A7E", "Added to your wishlist");
			}
		});
		HBox secondary = new HBox(10, gift, wish);
		VBox card = new VBox(14, priceRow, action, secondary, status);
		card.getStyleClass().add("card-2");
		card.setPadding(new Insets(20));
		return card;
	}

	private static Node ownedPill() {
		Label owned = new Label("Owned");
		owned.getStyleClass().addAll("pill", "pill-green");
		owned.setStyle("-fx-font-size: 15px; -fx-padding: 7 14 7 12;");
		owned.setGraphic(Icons.icon("check", 15, Color.web("#3DDC97"), 2.6));
		return owned;
	}

	/** Asks for the friend's name inline (under the buttons), then buys the item for them. */
	private static void giftPrompt(Button gift, Purchase purchase, Label status, Runnable refresh) {
		if (!(gift.getParent() instanceof HBox row) || !(row.getParent() instanceof VBox card)) {
			return;
		}
		if (card.getChildren().stream().anyMatch(n -> "gift-form".equals(n.getId()))) {
			return;
		}
		TextField name = new TextField();
		name.setPromptText("Friend's Minecraft name");
		HBox.setHgrow(name, Priority.ALWAYS);
		Button send = new Button("Send gift");
		send.getStyleClass().add("buy-button");
		Button cancel = new Button();
		cancel.setGraphic(Icons.icon("close", 14, Color.web("#A9A9B3")));
		cancel.getStyleClass().addAll("ghost-button", "icon-button");
		HBox form = new HBox(8, name, send, cancel);
		form.setId("gift-form");
		form.setAlignment(Pos.CENTER_LEFT);
		Label note = new Label("Costs " + StoreTiles.format(purchase.price()) + " coins. They get it straight away, with a message from you.");
		note.getStyleClass().add("t-muted");
		note.setWrapText(true);
		note.setId("gift-form");
		int at = card.getChildren().indexOf(row) + 1;
		card.getChildren().addAll(at, List.of(form, note));
		name.requestFocus();
		cancel.setOnAction(e -> card.getChildren().removeAll(form, note));
		Runnable sendGift = () -> {
			String to = name.getText().strip();
			if (to.isEmpty()) {
				return;
			}
			send.setDisable(true);
			status.setText("Sending...");
			CompletableFuture.supplyAsync(() -> {
				try {
					StoreApi.gift(purchase.itemId(), to, null);
					return (String) null;
				} catch (StoreApi.StoreError error) {
					return error.getMessage();
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(error -> Platform.runLater(() -> {
				send.setDisable(false);
				if (error == null) {
					card.getChildren().removeAll(form, note);
					status.setText("");
					Toasts.show(gift, "gift", "#F7C04A", "Gift sent to " + to);
					refresh.run();
				} else {
					status.setText(error);
				}
			}));
		};
		send.setOnAction(e -> sendGift.run());
		name.setOnAction(e -> sendGift.run());
	}
}
