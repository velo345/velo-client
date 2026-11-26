package net.veloclient.launcher.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
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
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Real-money sales (Black Friday, 2026-11): everything here reads the catalog's {@code sale} block from the server, so a
 * sale starts and ends without a launcher update. Before it starts it's a teaser (countdown), while it runs the
 * prices in the catalog are already the sale prices ({@code fullPrice} = before).
 */
public final class SaleUi {

	private static final String MONO = "-fx-font-family: 'Geist Mono';";
	private static final String DISPLAY = "-fx-font-family: 'Archivo SemiExpanded ExtraBold', 'Archivo SemiExpanded', 'Geist';";

	private SaleUi() {
	}

	/** The catalog's sale (teaser or running), or null. */
	public static JsonObject sale(JsonObject catalog) {
		if (catalog == null || !catalog.has("sale") || !catalog.get("sale").isJsonObject()) {
			return null;
		}
		JsonObject sale = catalog.getAsJsonObject("sale");
		return System.currentTimeMillis() < sale.get("until").getAsLong() ? sale : null;
	}

	private static boolean active(JsonObject sale) {
		return sale.get("active").getAsBoolean() || System.currentTimeMillis() >= sale.get("from").getAsLong();
	}

	/** The sale while it takes money off the coin packs right now, or null. */
	public static JsonObject liveCoins(JsonObject catalog) {
		JsonObject sale = sale(catalog);
		return sale != null && sale.get("active").getAsBoolean() && sale.has("coins") && sale.get("coins").getAsBoolean() ? sale : null;
	}

	/** A price crossed out (what it cost before the sale). */
	public static Node struck(String text, double size) {
		javafx.scene.text.Text was = new javafx.scene.text.Text(text);
		// inline style: the root's CSS font beats setFont (see the launcher's font trap)
		was.setStyle("-fx-font-size: " + size + "px; -fx-fill: #8E8E98; -fx-strikethrough: true;");
		return was;
	}

	private static String what(JsonObject sale) {
		boolean coins = !sale.has("coins") || sale.get("coins").getAsBoolean();
		boolean plus = !sale.has("plus") || sale.get("plus").getAsBoolean();
		return coins && plus ? "Velo Coins & Velo+" : coins ? "every coin pack" : "Velo+";
	}

	private static String clock(long millis) {
		long s = Math.max(0, millis / 1000);
		long d = s / 86400;
		return (d > 0 ? d + "d " : "") + String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600 % 24, s / 60 % 60, s % 60);
	}

	/**
	 * Home's sale strip (2026-11-25 redesign): one slim glass bar across the top of Home - the sale's name in display
	 * type with a slow light sweep over the bar, the deal, a tiled countdown (days / hours / minutes / seconds) and the
	 * button. Accent colours from the theme.
	 */
	public static Node banner(JsonObject sale, Runnable open) {
		return banner(sale, open, 52);
	}

	/** {@code height}: 52 on a page, 40 in the top bar. */
	public static Node banner(JsonObject sale, Runnable open, double height) {
		boolean live = active(sale);
		boolean slim = height < 48;
		int percent = sale.get("percent").getAsInt();
		Label name = new Label(sale.get("name").getAsString().toUpperCase(Locale.ROOT));
		name.setStyle(DISPLAY + "-fx-font-size: 15px; -fx-text-fill: white;");
		name.setMinWidth(Region.USE_PREF_SIZE);
		Label off = new Label(percent + "% OFF");
		off.setStyle(DISPLAY + "-fx-font-size: 12px; -fx-text-fill: -velo-on-accent; -fx-background-color: -velo-accent;"
				+ "-fx-background-radius: 999; -fx-padding: 4 10 4 10;");
		off.setMinWidth(Region.USE_PREF_SIZE);
		Label text = new Label(live ? "on " + what(sale) : "starts " + startsLabel(sale.get("from").getAsLong()) + " on " + what(sale));
		text.setStyle("-fx-text-fill: rgba(255,255,255,0.72); -fx-font-size: 13px;");
		text.setMinWidth(0);
		HBox.setHgrow(text, Priority.ALWAYS);
		text.setMaxWidth(Double.MAX_VALUE);

		HBox clock = new HBox(4);
		clock.setAlignment(Pos.CENTER);
		clock.setMinWidth(Region.USE_PREF_SIZE);
		Label[] parts = new Label[4];
		String[] units = {"D", "H", "M", "S"};
		for (int i = 0; i < 4; i++) {
			Label value = new Label("00");
			value.setStyle(MONO + "-fx-font-size: 13px; -fx-text-fill: white;");
			Label unit = new Label(units[i]);
			unit.setStyle(MONO + "-fx-font-size: 9px; -fx-text-fill: rgba(255,255,255,0.5);");
			HBox tile = new HBox(2, value, unit);
			tile.setAlignment(Pos.BASELINE_CENTER);
			tile.setStyle("-fx-background-color: rgba(255,255,255,0.08); -fx-background-radius: 999; -fx-padding: 6 9 2 9;");
			parts[i] = value;
			clock.getChildren().add(tile);
		}
		Runnable updateClock = () -> {
			long now = System.currentTimeMillis();
			long s = Math.max(0, (live ? sale.get("until").getAsLong() : sale.get("from").getAsLong()) - now) / 1000;
			long[] v = {s / 86400, s / 3600 % 24, s / 60 % 60, s % 60};
			for (int i = 0; i < 4; i++) {
				parts[i].setText(String.format(Locale.ROOT, "%02d", v[i]));
			}
			// no days left: hide the day tile
			clock.getChildren().getFirst().setVisible(v[0] > 0);
			clock.getChildren().getFirst().setManaged(v[0] > 0);
		};
		updateClock.run();
		Label endsIn = new Label(live ? "ENDS IN" : "STARTS IN");
		endsIn.setStyle(MONO + "-fx-font-size: 10px; -fx-text-fill: rgba(255,255,255,0.5);");
		endsIn.setMinWidth(Region.USE_PREF_SIZE);

		Button go = new Button(live ? "Get the deal" : "See the deals");
		go.getStyleClass().add("primary-button");
		go.setMinWidth(Region.USE_PREF_SIZE);
		go.setOnAction(e -> open.run());
		HBox row = new HBox(12, name, off, text, endsIn, clock, go);
		row.setAlignment(Pos.CENTER_LEFT);
		// the button is a pill inside the pill, with the same gap above, below and at the end (it sat off in the round end,
		// 2026-11-26): the gap is half of what's left of the bar's height, minus the 1 px border
		double buttonHeight = slim ? 30 : 36;
		double gap = (height - buttonHeight) / 2 - 1;
		go.setMinHeight(buttonHeight);
		go.setPrefHeight(buttonHeight);
		go.setMaxHeight(buttonHeight);
		go.setStyle("-fx-background-radius: 999; -fx-border-radius: 999; -fx-background-insets: 0; -fx-padding: 0 15 0 15;"
				+ (slim ? " -fx-font-size: 12px;" : ""));
		row.setPadding(new Insets(gap, gap, gap, slim ? 14 : 18));
		row.setMinHeight(height);
		row.setMaxHeight(height);
		if (slim) {
			name.setStyle(DISPLAY + "-fx-font-size: 13px; -fx-text-fill: white;");
			row.setSpacing(10);
		}
		row.setMinWidth(0);
		row.setStyle("-fx-background-color: linear-gradient(to right, rgba(0,0,0,0.55), rgba(0,0,0,0.25) 60%, transparent),"
				+ " radial-gradient(center 100% 50%, radius 70%, -velo-accent-soft, transparent), -velo-glass-tint;"
				+ "-fx-background-radius: 999; -fx-border-color: -velo-accent-line; -fx-border-radius: 999; -fx-border-width: 1;");
		row.getStyleClass().add("sale-strip");

		// a soft light sweep across the strip every few seconds
		Region sweep = new Region();
		sweep.setMouseTransparent(true);
		sweep.setManaged(false);
		sweep.setStyle("-fx-background-color: linear-gradient(to right, transparent, rgba(255,255,255,0.10), transparent);");
		StackPane strip = new StackPane(row, sweep);
		strip.setMinWidth(0);
		strip.setMaxHeight(height);
		Rectangle clip = new Rectangle();
		clip.setArcWidth(height);
		clip.setArcHeight(height);
		clip.widthProperty().bind(strip.widthProperty());
		clip.heightProperty().bind(strip.heightProperty());
		strip.setClip(clip);
		sweep.resize(140, height);
		sweep.setTranslateX(-200);
		TranslateTransition move = new TranslateTransition(Duration.seconds(1.6), sweep);
		move.setFromX(-200);
		move.toXProperty().bind(strip.widthProperty().add(60));
		Timeline loop = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateClock.run()));
		loop.setCycleCount(Animation.INDEFINITE);
		Timeline sweepLoop = new Timeline(new KeyFrame(Duration.seconds(6), e -> move.playFromStart()));
		sweepLoop.setCycleCount(Animation.INDEFINITE);
		net.veloclient.launcher.ui.RenderGate.gate(sweepLoop);
		strip.sceneProperty().addListener((o, a, scene) -> {
			if (scene == null) {
				loop.stop();
				sweepLoop.stop();
				move.stop();
			} else {
				loop.play();
				sweepLoop.play();
			}
		});
		strip.setCursor(javafx.scene.Cursor.HAND);
		row.setOnMouseClicked(e -> {
			if (e.getTarget() == row || e.getTarget() instanceof Label) {
				open.run();
			}
		});
		return strip;
	}

	private static String countdownText(JsonObject sale) {
		long now = System.currentTimeMillis();
		return active(sale) ? "ENDS IN " + clock(sale.get("until").getAsLong() - now) : "IN " + clock(sale.get("from").getAsLong() - now);
	}

	private static String startsLabel(long from) {
		java.time.LocalDate day = java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
		long days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), day);
		return days <= 0 ? "today" : days == 1 ? "tomorrow" : day.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
	}

	// ---- the pop-up, once per sale once it's running ----

	private static Path seenFile() {
		return net.veloclient.launcher.data.VeloPaths.config().resolve("sales-seen.txt");
	}

	private static boolean seen(String id) {
		try {
			return Files.exists(seenFile()) && Files.readAllLines(seenFile()).contains(id);
		} catch (Exception e) {
			return true;
		}
	}

	private static void markSeen(String id) {
		try {
			Files.writeString(seenFile(), id + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (Exception ignored) {
			// shown again next start - harmless
		}
	}

	/** Shows the sale pop-up when the sale runs and this launcher hasn't shown it yet; true if it did. */
	public static boolean maybePopup(StackPane layers, JsonObject catalog, Runnable openCoins, Runnable openPlus) {
		JsonObject sale = sale(catalog);
		if (sale == null || !sale.get("active").getAsBoolean() || layers.getChildren().size() > 1) {
			return false;
		}
		String id = sale.get("id").getAsString();
		if (seen(id) || System.getProperty("velo.launcherTour") != null && !Boolean.getBoolean("velo.tourSale")) {
			return false;
		}
		if (System.getProperty("velo.launcherTour") == null) {
			markSeen(id);
		}
		popup(layers, catalog, sale, openCoins, openPlus);
		return true;
	}

	static void popup(StackPane layers, JsonObject catalog, JsonObject sale, Runnable openCoins, Runnable openPlus) {
		StackPane root = new StackPane();
		Region scrim = new Region();
		scrim.setStyle("-fx-background-color: rgba(0,0,0,0.78);");
		int percent = sale.get("percent").getAsInt();

		Label kicker = new Label(sale.get("name").getAsString().toUpperCase(Locale.ROOT) + "  ·  LIVE NOW");
		kicker.setStyle(MONO + "-fx-font-size: 12px; -fx-text-fill: -velo-accent-text; -fx-padding: 6 9 6 9; -fx-border-color: -velo-accent; -fx-border-radius: 6;");
		Label num = new Label(percent + "%");
		num.setStyle(DISPLAY + "-fx-font-size: 104px; -fx-text-fill: linear-gradient(to bottom, #FFFFFF 35%, derive(-velo-accent-text, 30%) 80%, -velo-accent 100%);");
		Label off = new Label("OFF");
		off.setStyle(DISPLAY + "-fx-font-size: 44px; -fx-text-fill: -velo-accent;");
		num.setMinWidth(Region.USE_PREF_SIZE);
		off.setMinWidth(Region.USE_PREF_SIZE);
		HBox big = new HBox(12, num, off);
		big.setAlignment(Pos.BOTTOM_LEFT);
		Label sub = new Label("Half price on " + what(sale) + " - the biggest deal Velo runs this year. Ends with Cyber Monday.");
		sub.setWrapText(true);
		sub.setStyle("-fx-text-fill: #C9C9D1; -fx-font-size: 15px;");
		Label countdown = new Label(countdownText(sale));
		countdown.setStyle(MONO + "-fx-font-size: 13px; -fx-text-fill: white;");
		VBox left = new VBox(10, kicker, big, sub, countdown);
		left.setPrefWidth(420);
		left.setMinWidth(380);

		VBox deals = new VBox(10);
		String currency = catalog.has("currency") ? catalog.get("currency").getAsString() : "EUR";
		String symbol = currency.equals("EUR") ? "€" : currency.equals("USD") ? "$" : currency;
		JsonArray packs = catalog.has("coinPacks") ? catalog.getAsJsonArray("coinPacks") : new JsonArray();
		JsonObject biggest = null;
		for (JsonElement e : packs) {
			JsonObject p = e.getAsJsonObject();
			if (biggest == null || p.get("price").getAsDouble() > biggest.get("price").getAsDouble()) {
				biggest = p;
			}
		}
		Runnable[] close = new Runnable[1];
		if (biggest != null && biggest.has("fullPrice") && !biggest.get("fullPrice").isJsonNull()) {
			int coins = biggest.get("coins").getAsInt() + biggest.get("bonus").getAsInt();
			deals.getChildren().add(deal("sparkles", String.format(Locale.ROOT, "%,d Velo Coins", coins), biggest.get("fullPrice").getAsDouble(),
					biggest.get("price").getAsDouble(), symbol, () -> {
						close[0].run();
						openCoins.run();
					}));
		}
		JsonObject plus = catalog.has("plus") && catalog.get("plus").isJsonObject() ? catalog.getAsJsonObject("plus") : null;
		if (plus != null && plus.has("fullPrice") && !plus.get("fullPrice").isJsonNull()) {
			deals.getChildren().add(deal("crown", "Velo+ · your first month", plus.get("fullPrice").getAsDouble(), plus.get("price").getAsDouble(), symbol, () -> {
				close[0].run();
				openPlus.run();
			}));
		}
		Button later = new Button("Maybe later");
		later.getStyleClass().add("ghost-button");
		Button shop = new Button("Get the deal");
		shop.getStyleClass().addAll("primary-button", "button-lg");
		HBox buttons = new HBox(10, later, shop);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		deals.getChildren().add(buttons);
		deals.setAlignment(Pos.CENTER);
		deals.setPrefWidth(330);

		HBox body = new HBox(34, left, deals);
		body.setAlignment(Pos.CENTER_LEFT);
		body.setPadding(new Insets(34, 36, 32, 38));
		StackPane card = new StackPane(body);
		card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		card.setStyle("-fx-background-color: radial-gradient(center 90% 0%, radius 80%, -velo-accent-line, transparent), -velo-glass-tint;"
				+ "-fx-background-radius: 22; -fx-border-color: rgba(224,38,46,0.45); -fx-border-radius: 22; -fx-border-width: 1;");
		Rectangle clip = new Rectangle();
		clip.setArcWidth(44);
		clip.setArcHeight(44);
		clip.widthProperty().bind(card.widthProperty());
		clip.heightProperty().bind(card.heightProperty());
		card.setClip(clip);
		root.getChildren().addAll(scrim, card);
		layers.getChildren().add(root);

		Timeline tick = new Timeline(new KeyFrame(Duration.seconds(1), e -> countdown.setText(countdownText(sale))));
		tick.setCycleCount(Animation.INDEFINITE);
		tick.play();
		close[0] = () -> {
			tick.stop();
			FadeTransition out = new FadeTransition(Duration.millis(200), root);
			out.setToValue(0);
			out.setOnFinished(e -> layers.getChildren().remove(root));
			out.play();
		};
		later.setOnAction(e -> close[0].run());
		scrim.setOnMouseClicked(e -> close[0].run());
		shop.setOnAction(e -> {
			close[0].run();
			openCoins.run();
		});

		root.setOpacity(0);
		card.setTranslateY(24);
		FadeTransition in = new FadeTransition(Duration.millis(240), root);
		in.setToValue(1);
		TranslateTransition rise = new TranslateTransition(Duration.millis(460), card);
		rise.setToY(0);
		rise.setInterpolator(UiMotion.BACK_OUT_SOFT);
		new ParallelTransition(in, rise).play();
	}

	private static Node deal(String icon, String name, double full, double now, String symbol, Runnable open) {
		Label title = new Label(name);
		title.setStyle("-fx-text-fill: white; -fx-font-family: 'Geist SemiBold'; -fx-font-size: 14px;");
		Node was = struck(String.format(Locale.ROOT, "%.2f %s", full, symbol), 12);
		Label price = new Label(String.format(Locale.ROOT, "%.2f %s", now, symbol));
		price.setStyle(DISPLAY + "-fx-font-size: 20px; -fx-text-fill: white;");
		VBox text = new VBox(2, title, new HBox(8, was, price));
		((HBox) text.getChildren().get(1)).setAlignment(Pos.BASELINE_LEFT);
		HBox row = new HBox(12, Icons.icon(icon, 22, Color.web(icon.equals("sparkles") ? "#F7C04A" : "#FF6B6F")), text);
		row.setAlignment(Pos.CENTER_LEFT);
		row.setPadding(new Insets(12, 14, 12, 14));
		row.setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 12; -fx-border-color: rgba(255,255,255,0.1); -fx-border-radius: 12;");
		row.setCursor(javafx.scene.Cursor.HAND);
		row.setOnMouseClicked(e -> open.run());
		return row;
	}
}
