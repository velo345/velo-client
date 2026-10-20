package net.veloclient.launcher.ui;

import javafx.animation.Animation;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import net.veloclient.launcher.data.AuraCatalog;
import net.veloclient.launcher.data.StoreItem;
import net.veloclient.launcher.data.StoreOwnership;
import net.veloclient.launcher.data.WingsCatalog;
import net.veloclient.launcher.social.StoreApi;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * The one tile shape every cosmetic uses in the Store and the Locker: the item's art on a glow in its color, a
 * rarity line, name, and the price (or Owned / Equipped). Hovering lifts the tile, plays animated capes and
 * shows a "Try on" hint.
 */
public final class StoreTiles {

	/** Rarity tiers, shown as a colored line and label. The server can set one per item; otherwise the price decides. */
	public enum Rarity {
		COMMON("COMMON", "#C9CDD4"), RARE("RARE", "#7DBBFF"), EPIC("EPIC", "#BCA6FF"), LEGENDARY("LEGENDARY", "#F7C04A"), MYTHIC("MYTHIC", "#FF7A7E");

		public final String label;
		public final String color;

		Rarity(String label, String color) {
			this.label = label;
			this.color = color;
		}

		public static Rarity of(String itemId, int price) {
			String fromServer = StoreApi.rarity(itemId);
			if (fromServer != null) {
				for (Rarity r : values()) {
					if (r.name().equalsIgnoreCase(fromServer)) {
						return r;
					}
				}
			}
			if (price <= 600) {
				return COMMON;
			}
			if (price <= 1500) {
				return RARE;
			}
			if (price <= 2500) {
				return EPIC;
			}
			return price <= 3000 ? LEGENDARY : MYTHIC;
		}
	}

	/** What the tile says under the name. */
	public enum State { FOR_SALE, OWNED, EQUIPPED, LOCKED }

	private StoreTiles() {
	}

	public static String format(int coins) {
		return NumberFormat.getIntegerInstance(Locale.US).format(coins);
	}

	/** The small gold "V" coin. */
	public static Label coin(double size) {
		Label coin = new Label("V");
		coin.getStyleClass().add("coin");
		coin.setMinSize(size, size);
		coin.setMaxSize(size, size);
		coin.setStyle("-fx-font-size: " + Math.round(size * 0.55) + "px;");
		return coin;
	}

	/** Price with coin, optionally with the full price struck through next to it (a deal). */
	public static HBox price(int coins, Integer fullPrice, double size) {
		Label amount = new Label(format(coins));
		amount.setStyle("-fx-text-fill: -velo-gold; -fx-font-family: 'Inter'; -fx-font-weight: bold; -fx-font-size: " + size + "px;");
		HBox box = new HBox(6, coin(size + 3), amount);
		box.setAlignment(Pos.CENTER_LEFT);
		if (fullPrice != null && fullPrice > coins) {
			Label was = new Label(format(fullPrice));
			was.setStyle("-fx-text-fill: -velo-text-3; -fx-strikethrough: true; -fx-font-size: " + (size - 2) + "px;");
			box.getChildren().add(was);
		}
		return box;
	}

	/** A generic tile around {@code art}. {@code width} 0 = let the grid size it. */
	public static VBox tile(Node art, String accent, Rarity rarity, String kind, String name, State state, int price, Integer fullPrice,
			String dealLabel, Runnable onClick) {
		StackPane stage = new StackPane();
		stage.getStyleClass().add("tile-stage");
		stage.setMinHeight(170);
		stage.setPrefHeight(190);
		stage.setStyle("-fx-background-color: radial-gradient(center 50% 72%, radius 70%, " + accent + "55 0%, transparent 100%), -velo-surface-2;"
				+ " -fx-background-radius: 14 14 0 0;");
		stage.getChildren().add(art);
		Rectangle clip = new Rectangle();
		clip.widthProperty().bind(stage.widthProperty());
		clip.heightProperty().bind(stage.heightProperty());
		stage.setClip(clip);
		Region line = new Region();
		line.setMinHeight(3);
		line.setMaxHeight(3);
		line.setStyle("-fx-background-color: " + rarity.color + ";");
		StackPane.setAlignment(line, Pos.BOTTOM_CENTER);
		stage.getChildren().add(line);
		if (dealLabel != null) {
			Label deal = new Label(dealLabel);
			deal.getStyleClass().addAll("badge", "badge-new");
			deal.setStyle("-fx-font-size: 11px;");
			StackPane.setAlignment(deal, Pos.TOP_LEFT);
			StackPane.setMargin(deal, new Insets(10));
			stage.getChildren().add(deal);
		}
		Label hint = new Label("Try on");
		hint.setStyle("-fx-background-color: rgba(11,11,14,0.82); -fx-background-radius: 8; -fx-border-color: rgba(255,255,255,0.14);"
				+ " -fx-border-radius: 8; -fx-padding: 6 11 6 11; -fx-text-fill: white; -fx-font-family: 'Inter SemiBold'; -fx-font-size: 12px;");
		hint.setGraphic(Icons.icon("eye", 13, Color.WHITE));
		hint.setOpacity(0);
		hint.setMouseTransparent(true);
		StackPane.setAlignment(hint, Pos.TOP_RIGHT);
		StackPane.setMargin(hint, new Insets(10));
		stage.getChildren().add(hint);

		Label over = new Label(rarity.label + "  ·  " + kind.toUpperCase(Locale.ROOT));
		over.setStyle("-fx-text-fill: " + rarity.color + "; -fx-font-family: 'Inter'; -fx-font-weight: bold; -fx-font-size: 10px;");
		Label title = new Label(name);
		title.getStyleClass().add("t-strong");
		title.setStyle("-fx-font-size: 15px;");
		Node footer = switch (state) {
			case EQUIPPED -> pill("check", "Equipped", "pill-green");
			case OWNED -> pill("check", "Owned", "pill-green");
			case LOCKED -> pill("lock", "Not for sale", "pill-grey");
			case FOR_SALE -> price(price, fullPrice, 14);
		};
		VBox text = new VBox(5, over, title, footer);
		text.setPadding(new Insets(12, 14, 14, 14));
		VBox tile = new VBox(stage, text);
		tile.getStyleClass().addAll("tile", "motion-card");
		Rectangle tileClip = new Rectangle();
		tileClip.setArcWidth(28);
		tileClip.setArcHeight(28);
		tileClip.widthProperty().bind(tile.widthProperty());
		tileClip.heightProperty().bind(tile.heightProperty());
		tile.setClip(tileClip);
		tile.setOnMouseEntered(e -> fade(hint, 1));
		tile.setOnMouseExited(e -> fade(hint, 0));
		tile.setOnMouseClicked(e -> onClick.run());
		return tile;
	}

	private static Label pill(String icon, String text, String style) {
		Label pill = new Label(text);
		pill.getStyleClass().addAll("pill", style);
		pill.setGraphic(Icons.icon(icon, 12, Color.web(style.equals("pill-green") ? "#3DDC97" : "#A9A9B3"), 2.6));
		return pill;
	}

	private static void fade(Node node, double to) {
		javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(Duration.millis(160), node);
		fade.setToValue(to);
		fade.play();
	}

	// ---- Art for each kind of cosmetic ----

	/** A pair of wings' preview picture, floating gently. */
	public static Node wingsArt(WingsCatalog.WingType type, double height) {
		ImageView picture = new ImageView(UiImages.wingPreview(type.id()));
		picture.setFitHeight(height);
		picture.setPreserveRatio(true);
		TranslateTransition bob = new TranslateTransition(Duration.seconds(2.6), picture);
		bob.setFromY(3);
		bob.setToY(-4);
		bob.setAutoReverse(true);
		bob.setCycleCount(Animation.INDEFINITE);
		bob.setDelay(Duration.millis(type.id().hashCode() & 0x3FF));
		picture.sceneProperty().addListener((obs, o, n) -> {
			if (n == null) {
				bob.stop();
			} else {
				bob.play();
			}
		});
		return picture;
	}

	/** An animated cape as a 3D thumbnail (decoded off the UI thread; plays while {@code hoverTarget} is hovered). */
	public static Node capeArt(StoreItem item, Node[] hoverTargetHolder, double size) {
		StackPane holder = new StackPane(CosmeticUi.spinner(24));
		holder.setMinSize(size, size);
		CosmeticUi.framesAsync(item, frames -> {
			if (frames.isEmpty()) {
				holder.getChildren().clear();
				return;
			}
			String key = "store:" + item.id();
			ImageView view = new ImageView(CosmeticUi.thumbnail(key, frames, size, size));
			view.setFitHeight(size - 8);
			view.setPreserveRatio(true);
			holder.getChildren().setAll(view);
			Node hover = hoverTargetHolder != null && hoverTargetHolder[0] != null ? hoverTargetHolder[0] : holder;
			CosmeticUi.animate(hover, view, key, frames, size, size, false);
		});
		return holder;
	}

	/** An aura, live-rendered once around a dim player shape. */
	public static Node auraArt(AuraCatalog.AuraType type, double width, double height) {
		boolean owned = AuraCatalog.owns(type.id());
		var settings = owned ? AuraCatalog.settings(type.id()) : net.veloclient.velo.client.cosmetics.aura.AuraEffects.Settings.DEFAULT;
		ImageView canvas = new ImageView(AuraFx.snapshot(type.kind(), settings, (int) width, (int) height));
		canvas.setFitWidth(width);
		canvas.setFitHeight(height);
		return canvas;
	}

	// ---- Ready-made tiles ----

	public static VBox wingsTile(WingsCatalog.WingType type, Runnable onClick) {
		int price = StoreApi.price(type.itemId(), type.price());
		boolean owned = StoreOwnership.owns(type.itemId());
		boolean equipped = type.id().equals(WingsCatalog.equipped());
		State state = equipped ? State.EQUIPPED : owned ? State.OWNED : type.inStore() ? State.FOR_SALE : State.LOCKED;
		Integer full = StoreApi.fullPrice(type.itemId());
		return tile(wingsArt(type, 170), type.accent(), Rarity.of(type.itemId(), price), "Wings", type.name(), state,
				price, full, dealLabel(price, full), onClick);
	}

	public static VBox capeTile(StoreItem item, Runnable onClick) {
		int price = StoreApi.price(item.id(), item.priceCoins());
		boolean owned = StoreOwnership.owns(item.id());
		Node[] hover = new Node[1];
		Node art = capeArt(item, hover, 170);
		Integer full = StoreApi.fullPrice(item.id());
		VBox tile = tile(art, "#5aa9ff", Rarity.of(item.id(), price), "Animated cape", item.name(),
				owned ? State.OWNED : item.inStore() ? State.FOR_SALE : State.LOCKED, price, full, dealLabel(price, full), onClick);
		hover[0] = tile;
		return tile;
	}

	public static VBox auraTile(AuraCatalog.AuraType type, Runnable onClick) {
		int price = StoreApi.price(type.itemId(), type.price());
		boolean owned = AuraCatalog.owns(type.id());
		boolean equipped = type.id().equals(AuraCatalog.equipped());
		Integer full = StoreApi.fullPrice(type.itemId());
		return tile(auraArt(type, 220, 170), type.accent(), Rarity.of(type.itemId(), price), "Aura", type.name(),
				equipped ? State.EQUIPPED : owned ? State.OWNED : type.inStore() ? State.FOR_SALE : State.LOCKED, price, full,
				dealLabel(price, full), onClick);
	}

	private static String dealLabel(int price, Integer full) {
		if (full == null || full <= price || full == 0) {
			return null;
		}
		return "−" + Math.round(100.0 * (full - price) / full) + "%";
	}

	/** A FlowPane whose tiles stretch so each row is filled evenly ({@code minTile} wide at least). */
	public static FlowPane grid(double minTile, double gap) {
		FlowPane grid = new FlowPane(gap, gap);
		grid.widthProperty().addListener((o, a, w) -> fit(grid, w.doubleValue(), minTile, gap));
		grid.getChildren().addListener((javafx.collections.ListChangeListener<Node>) c -> fit(grid, grid.getWidth(), minTile, gap));
		return grid;
	}

	private static void fit(FlowPane grid, double width, double minTile, double gap) {
		if (width <= 0) {
			return;
		}
		int columns = Math.max(1, (int) ((width + gap) / (minTile + gap)));
		double tileWidth = Math.floor((width - gap * (columns - 1)) / columns) - 0.5;
		for (Node tile : grid.getChildren()) {
			if (tile instanceof Region region) {
				region.setPrefWidth(tileWidth);
				region.setMinWidth(tileWidth);
				region.setMaxWidth(tileWidth);
			}
		}
	}
}
