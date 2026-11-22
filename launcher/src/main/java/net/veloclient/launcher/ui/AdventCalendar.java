package net.veloclient.launcher.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.PerspectiveTransform;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Box;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;

/**
 * The Advent calendar window (dev launcher, 2026-11): 24 pixel-art doors over the animated snowy village. Today's door
 * swings open, a 3D present drops in, shakes and bursts open, and what's inside rises out of the light - name, amount
 * and art. The server decides which door is today and what's behind it ({@code /v1/advent}).
 */
public final class AdventCalendar {

	private static final String PIXEL = "-fx-font-family: 'Monocraft';";
	private static final String DISPLAY = "-fx-font-family: 'Archivo SemiExpanded ExtraBold', 'Archivo SemiExpanded', 'Geist';";
	private static final int[] DOOR_COLORS = {0xFF9C1F2A, 0xFF1F5A3D, 0xFF213066, 0xFF7E2A5A, 0xFF8A6420, 0xFF1F4F5E};

	/** What the calendar may ask of the launcher. */
	public interface Host {
		/** Opens the Locker (after a cosmetic came out of a door). */
		void openLocker();

		/** The wallet / Velo+ changed - refresh what shows them. */
		void rewardsChanged();
	}

	private final StackPane layers;
	private final Host host;
	private final StackPane root = new StackPane();
	private final StackPane card = new StackPane();
	private final GridPane grid = new GridPane();
	private final Label subtitle = new Label();
	private final List<Animation> running = new ArrayList<>();
	private RidgelineView backdrop;
	private Runnable onClosed;
	private JsonObject calendar;
	private boolean busy;

	private AdventCalendar(StackPane layers, Host host) {
		this.layers = layers;
		this.host = host;
	}

	/** Shows the calendar over the launcher; {@code onClosed} runs once it's gone. */
	public static void show(StackPane layers, JsonObject calendar, Host host, Runnable onClosed) {
		AdventCalendar view = new AdventCalendar(layers, host);
		view.onClosed = onClosed;
		view.calendar = calendar;
		last = view;
		view.build();
	}

	// Screenshot tour hooks: the last calendar shown, today's door and the present's click.
	private static AdventCalendar last;
	private Runnable tourDoor;
	private Runnable tourGift;

	public static void tourOpenToday() {
		if (last != null && last.tourDoor != null) {
			last.tourDoor.run();
		}
	}

	public static void tourClickGift() {
		if (last != null && last.tourGift != null) {
			last.tourGift.run();
		}
	}

	/** Whether the calendar wants to show itself on its own: running and today's door still closed. */
	public static boolean wantsAttention(JsonObject calendar) {
		return calendar != null && bool(calendar, "running") && !bool(calendar, "openedToday");
	}

	// ------------------------------------------------------------------ the window

	private void build() {
		Region scrim = new Region();
		scrim.setStyle("-fx-background-color: rgba(3,5,14,0.8);");
		scrim.setOnMouseClicked(e -> {
			if (!busy) {
				close();
			}
		});

		card.setMaxSize(860, 590);
		card.setPrefSize(860, 590);
		card.setMinSize(0, 0);
		Rectangle clip = new Rectangle();
		clip.setArcWidth(26);
		clip.setArcHeight(26);
		clip.widthProperty().bind(card.widthProperty());
		clip.heightProperty().bind(card.heightProperty());
		card.setClip(clip);
		card.setStyle("-fx-background-color: #070B1E; -fx-border-color: rgba(255,214,120,0.28); -fx-border-width: 1; -fx-border-radius: 13;");

		backdrop = new RidgelineView();
		backdrop.setWinter(true);
		Region shade = new Region();
		shade.setMouseTransparent(true);
		shade.setStyle("-fx-background-color: linear-gradient(to bottom, rgba(5,8,22,0.55) 0%, rgba(5,8,22,0.78) 30%, rgba(5,8,22,0.62) 72%, rgba(5,8,22,0.25) 100%);");

		Label over = new Label("DECEMBER " + (calendar.has("year") ? calendar.get("year").getAsInt() : 2026)
				+ (bool(calendar, "preview") ? "  ·  OWNER PREVIEW" : ""));
		over.setStyle(PIXEL + "-fx-font-size: 12px; -fx-text-fill: #FFD27A;");
		Label title = new Label("Advent Calendar");
		title.setStyle(DISPLAY + "-fx-font-size: 34px; -fx-text-fill: #FFFFFF; -fx-font-weight: 800;");
		title.setEffect(new DropShadow(javafx.scene.effect.BlurType.ONE_PASS_BOX, Color.rgb(0, 0, 0, 0.6), 0, 0, 3, 3));
		subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #C9D3F2;");
		updateSubtitle();
		VBox head = new VBox(4, over, title, subtitle);

		Button closeButton = new Button();
		closeButton.setGraphic(Icons.icon("close", 15, Color.web("#F4EFEE"), 2.2));
		closeButton.getStyleClass().addAll("icon-button", "update-close");
		closeButton.setOnAction(e -> {
			if (!busy) {
				close();
			}
		});
		Region push = new Region();
		HBox.setHgrow(push, Priority.ALWAYS);
		HBox top = new HBox(head, push, closeButton);
		top.setAlignment(Pos.TOP_LEFT);

		grid.setHgap(12);
		grid.setVgap(14);
		grid.setAlignment(Pos.CENTER);
		fillGrid(true);

		Label foot = new Label(bool(calendar, "preview")
				? "Test calendar - nothing is granted. Doors close again with Reset."
				: "One door a day until Christmas Eve. Open every door for a bonus behind the last one.");
		foot.setStyle("-fx-font-size: 12px; -fx-text-fill: #8E9AC4;");
		HBox footer = new HBox(10, foot);
		footer.setAlignment(Pos.CENTER_LEFT);
		if (bool(calendar, "preview")) {
			Region footPush = new Region();
			HBox.setHgrow(footPush, Priority.ALWAYS);
			Button reset = new Button("Reset test doors");
			reset.getStyleClass().add("ghost-button");
			reset.setOnAction(e -> CompletableFuture.supplyAsync(() -> {
				try {
					return net.veloclient.launcher.social.StoreApi.resetAdvent();
				} catch (Exception ex) {
					return null;
				}
			}).thenAccept(fresh -> Platform.runLater(() -> {
				if (fresh != null) {
					calendar = fresh;
					updateSubtitle();
					fillGrid(false);
				}
			})));
			footer.getChildren().addAll(footPush, reset);
		}

		VBox content = new VBox(22, top, grid, footer);
		content.setPadding(new Insets(28, 32, 24, 34));
		VBox.setVgrow(grid, Priority.ALWAYS);
		card.getChildren().addAll(backdrop, shade, content);

		// Smaller windows scale the whole calendar down instead of cutting doors off.
		javafx.beans.value.ChangeListener<Number> fit = (o, a, b) -> {
			double s = Math.min(1, Math.min((layers.getWidth() - 40) / 860, (layers.getHeight() - 40) / 590));
			card.setScaleX(Math.max(0.5, s));
			card.setScaleY(Math.max(0.5, s));
		};
		layers.widthProperty().addListener(fit);
		layers.heightProperty().addListener(fit);
		fit.changed(null, null, null);

		root.getChildren().addAll(scrim, card);
		root.setOnKeyPressed(e -> {
			if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE && !busy) {
				close();
			}
		});
		layers.getChildren().add(root);
		root.requestFocus();

		scrim.setOpacity(0);
		FadeTransition scrimIn = new FadeTransition(Duration.millis(260), scrim);
		scrimIn.setToValue(1);
		card.setOpacity(0);
		card.setTranslateY(-26);
		FadeTransition cardFade = new FadeTransition(Duration.millis(360), card);
		cardFade.setToValue(1);
		TranslateTransition cardDrop = new TranslateTransition(Duration.millis(520), card);
		cardDrop.setToY(0);
		cardDrop.setInterpolator(UiMotion.BACK_OUT_SOFT);
		new ParallelTransition(scrimIn, cardFade, cardDrop).play();
	}

	private void updateSubtitle() {
		int today = calendar.has("today") ? calendar.get("today").getAsInt() : 0;
		boolean opened = bool(calendar, "openedToday");
		int days = 24 - today;
		subtitle.setText(opened ? (days > 0 ? "Door " + today + " is open. The next one waits for you tomorrow." : "Merry Christmas!")
				: "Door " + today + " is ready. " + (days == 0 ? "It's Christmas Eve!" : days + (days == 1 ? " day" : " days") + " until Christmas Eve."));
	}

	private void fillGrid(boolean animate) {
		grid.getChildren().clear();
		List<JsonObject> days = new ArrayList<>();
		if (calendar.has("days")) {
			for (JsonElement e : calendar.getAsJsonArray("days")) {
				days.add(e.getAsJsonObject());
			}
		}
		// Doors in a shuffled order, like a real calendar (the same every year: seeded).
		List<JsonObject> order = new ArrayList<>(days);
		java.util.Collections.shuffle(order, new java.util.Random(24));
		for (int i = 0; i < order.size(); i++) {
			StackPane door = door(order.get(i));
			grid.add(door, i % 8, i / 8);
			if (animate) {
				door.setOpacity(0);
				door.setTranslateY(-18);
				FadeTransition fade = new FadeTransition(Duration.millis(260), door);
				fade.setToValue(1);
				TranslateTransition drop = new TranslateTransition(Duration.millis(420), door);
				drop.setToY(0);
				drop.setInterpolator(UiMotion.BACK_OUT);
				ParallelTransition in = new ParallelTransition(fade, drop);
				in.setDelay(Duration.millis(180 + i * 28));
				in.play();
			}
		}
	}

	// ------------------------------------------------------------------ doors

	private StackPane door(JsonObject day) {
		int number = day.get("day").getAsInt();
		String state = day.has("state") ? day.get("state").getAsString() : "locked";
		double dw = 84;
		double dh = 96;
		StackPane holder = new StackPane();
		holder.setMinSize(dw, dh);
		holder.setPrefSize(dw, dh);
		holder.setMaxSize(dw, dh);

		// Behind the door: a dark little room, with the reward once it's open.
		StackPane inside = new StackPane();
		inside.setStyle("-fx-background-color: linear-gradient(to bottom, #04060F, #0E1430); -fx-border-color: #2A3260; -fx-border-width: 2;");
		if ("opened".equals(state) && day.has("reward")) {
			JsonObject reward = day.getAsJsonObject("reward");
			Node art = rewardArt(reward, 46);
			Label name = new Label(shortName(reward));
			name.setStyle(PIXEL + "-fx-font-size: 9px; -fx-text-fill: #E9EDFF;");
			name.setMaxWidth(dw - 10);
			VBox box = new VBox(4, art, name);
			box.setAlignment(Pos.CENTER);
			box.setPadding(new Insets(0, 0, 0, 10)); // clear of the swung-open door
			inside.getChildren().add(box);
			Tooltip.install(holder, new Tooltip("Door " + number + ": " + reward.get("name").getAsString()));
		}

		Pane front = doorFront(number, state, dw, dh);
		holder.getChildren().addAll(inside, front);
		switch (state) {
			case "opened" -> {
				swing(front, dw, dh, 1);
				front.setMouseTransparent(true);
			}
			case "today" -> {
				holder.setCursor(javafx.scene.Cursor.HAND);
				holder.setOnMouseEntered(e -> lift(holder, true));
				holder.setOnMouseExited(e -> lift(holder, false));
				holder.setOnMouseClicked(e -> openToday(holder, front, dw, dh));
				tourDoor = () -> openToday(holder, front, dw, dh);
				if (bool(calendar, "openedToday")) {
					holder.setOnMouseClicked(null);
				}
			}
			case "missed" -> Tooltip.install(holder, new Tooltip("Door " + number + " stayed closed"));
			default -> Tooltip.install(holder, new Tooltip("Opens on December " + number));
		}
		return holder;
	}

	private Pane doorFront(int number, String state, double dw, double dh) {
		boolean today = "today".equals(state);
		boolean missed = "missed".equals(state);
		int base = DOOR_COLORS[(number * 7) % DOOR_COLORS.length];
		Pane front = new Pane();
		front.setMinSize(dw, dh);
		front.setPrefSize(dw, dh);
		front.setMaxSize(dw, dh);
		String fill = hex(base);
		String dark = hex(ChristmasScene.mix(0xFF000000, base, 0.62));
		String light = hex(ChristmasScene.mix(base, 0xFFFFFFFF, 0.18));
		front.setStyle("-fx-background-color: " + dark + ", " + fill + ", linear-gradient(to bottom, " + light + ", " + fill + ");"
				+ "-fx-background-insets: 0, 3, 7;");

		// Snow lying on the door's top edge, a little uneven.
		front.getChildren().add(pixelArt(new String[] {
				"WWWWWWWWWWWWWWWWWWWWWWWWWWWW",
				"WWWWWWWWWWWWWWWWWWWWWWWWWWWW",
				"wWWWW.WWWWWWw.WWWWWW.wWWWWWw",
				".wWw...wWWw....wWw....wWWw..",
				"..w.....ww......w......ww..."}, 3, 0, 0));

		Label num = new Label(String.valueOf(number));
		num.setStyle(PIXEL + "-fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: " + (missed ? "#8E93A8" : today ? "#FFE6A0" : "#FFFFFF") + ";");
		num.setEffect(new DropShadow(javafx.scene.effect.BlurType.ONE_PASS_BOX, Color.rgb(0, 0, 0, 0.55), 0, 0, 3, 3));
		num.setMinWidth(dw);
		num.setAlignment(Pos.CENTER);
		num.setLayoutY(28);
		front.getChildren().add(num);

		// A tiny ornament under the number and a brass knob.
		String[][] ornaments = {
				{"..y..", ".yYy.", "yYYYy", ".yYy.", "..y.."},
				{".g.g.", "ggggg", ".grg.", "..r.."},
				{"..b..", ".bWb.", "bWbWb", ".bWb.", "..b.."},
				{".rr.", "rWWr", "rrrr", ".rr."}};
		ImageView ornament = pixelArt(ornaments[number % ornaments.length], 3, 0, 0);
		ornament.setLayoutX(Math.round((dw - ornament.getImage().getWidth()) / 2));
		ornament.setLayoutY(70);
		front.getChildren().add(ornament);
		Rectangle knob = new Rectangle(dw - 14, dh / 2 + 2, 4, 4);
		knob.setFill(Color.web("#E8C060"));
		front.getChildren().add(knob);

		if (today && !bool(calendar, "openedToday")) {
			front.setStyle(front.getStyle() + "-fx-border-color: #FFD24A; -fx-border-width: 3;");
			// A sheen that sweeps across the door now and then (no pulsing).
			Rectangle sheen = new Rectangle(-40, 0, 26, dh);
			sheen.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, new Stop(0, Color.TRANSPARENT),
					new Stop(0.5, Color.rgb(255, 245, 210, 0.35)), new Stop(1, Color.TRANSPARENT)));
			sheen.setRotate(14);
			sheen.setMouseTransparent(true);
			Rectangle sheenClip = new Rectangle(dw, dh);
			front.setClip(sheenClip);
			front.getChildren().add(sheen);
			TranslateTransition sweep = new TranslateTransition(Duration.millis(900), sheen);
			sweep.setFromX(0);
			sweep.setToX(dw + 60);
			sweep.setInterpolator(Interpolator.EASE_BOTH);
			PauseTransition rest = new PauseTransition(Duration.millis(1800));
			javafx.animation.SequentialTransition loop = new javafx.animation.SequentialTransition(sweep, rest);
			loop.setCycleCount(Animation.INDEFINITE);
			loop.play();
			running.add(loop);
			Label open = new Label("OPEN");
			open.setStyle(PIXEL + "-fx-font-size: 9px; -fx-text-fill: #FFE6A0;");
			open.setMinWidth(dw);
			open.setAlignment(Pos.CENTER);
			open.setLayoutY(17);
			front.getChildren().add(open);
		} else if (missed) {
			front.setOpacity(0.5);
			Label gone = new Label("MISSED");
			gone.setStyle(PIXEL + "-fx-font-size: 9px; -fx-text-fill: #C9CEE0;");
			gone.setMinWidth(dw);
			gone.setAlignment(Pos.CENTER);
			gone.setLayoutY(17);
			front.getChildren().add(gone);
		} else if (!today) {
			front.setOpacity(0.92);
		}
		return front;
	}

	private static void lift(Node door, boolean up) {
		ScaleTransition scale = new ScaleTransition(Duration.millis(160), door);
		scale.setToX(up ? 1.06 : 1);
		scale.setToY(up ? 1.06 : 1);
		scale.play();
	}

	/** Swings {@code front} open around its left edge: 0 = closed, 1 = open (a real perspective, not a squash). */
	private static void swing(Pane front, double dw, double dh, double amount) {
		double angle = Math.toRadians(amount * 83);
		PerspectiveTransform p = new PerspectiveTransform();
		double grow = Math.sin(angle) * dw * 0.2;
		p.setUlx(0);
		p.setUly(0);
		p.setLlx(0);
		p.setLly(dh);
		p.setUrx(dw * Math.cos(angle));
		p.setUry(-grow);
		p.setLrx(dw * Math.cos(angle));
		p.setLry(dh + grow);
		javafx.scene.effect.ColorAdjust dim = new javafx.scene.effect.ColorAdjust(0, 0, -0.45 * amount, 0);
		p.setInput(dim);
		front.setEffect(amount <= 0 ? null : p);
	}

	private void openToday(StackPane holder, Pane front, double dw, double dh) {
		if (busy) {
			return;
		}
		busy = true;
		holder.setOnMouseClicked(null);
		UiSounds.tab();
		CompletableFuture<JsonObject> claim = CompletableFuture.supplyAsync(() -> {
			try {
				return net.veloclient.launcher.social.StoreApi.openAdvent();
			} catch (net.veloclient.launcher.social.StoreApi.StoreError e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
		DoubleProperty open = new SimpleDoubleProperty(0);
		open.addListener((o, a, v) -> swing(front, dw, dh, v.doubleValue()));
		Timeline swingOpen = new Timeline(new KeyFrame(Duration.millis(620), new KeyValue(open, 1, UiMotion.EASE_OUT)));
		swingOpen.setOnFinished(e -> claim.whenComplete((result, error) -> Platform.runLater(() -> {
			if (error != null || result == null || !result.has("reward")) {
				busy = false;
				Timeline back = new Timeline(new KeyFrame(Duration.millis(380), new KeyValue(open, 0, Interpolator.EASE_IN)));
				back.play();
				holder.setOnMouseClicked(ev -> openToday(holder, front, dw, dh));
				String message = error == null ? "Couldn't open the door" : rootMessage(error);
				subtitle.setText(message);
				return;
			}
			calendar = result;
			host.rewardsChanged();
			reveal(result.getAsJsonObject("reward"));
		})));
		swingOpen.play();
	}

	private static String rootMessage(Throwable error) {
		Throwable t = error;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage() == null ? "Couldn't open the door" : t.getMessage();
	}

	// ------------------------------------------------------------------ the present

	private void reveal(JsonObject reward) {
		Color glow = rewardColor(reward);
		StackPane stage = new StackPane();
		stage.setStyle("-fx-background-color: rgba(4,6,16,0.9);");
		stage.setOpacity(0);
		card.getChildren().add(stage);
		FadeTransition in = new FadeTransition(Duration.millis(260), stage);
		in.setToValue(1);
		in.play();

		Region spotlight = new Region();
		spotlight.setMouseTransparent(true);
		spotlight.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(new RadialGradient(0, 0, 0.5, 0.46, 0.5, true,
				CycleMethod.NO_CYCLE, new Stop(0, glow.deriveColor(0, 1, 1, 0.42)), new Stop(0.45, glow.deriveColor(0, 1, 0.6, 0.12)),
				new Stop(1, Color.TRANSPARENT)), null, null)));
		spotlight.setOpacity(0.35);

		Group rays = rays(glow);
		rays.setOpacity(0);
		RotateTransition spin = new RotateTransition(Duration.seconds(28), rays);
		spin.setByAngle(360);
		spin.setInterpolator(Interpolator.LINEAR);
		spin.setCycleCount(Animation.INDEFINITE);
		spin.play();
		running.add(spin);
		StackPane raysHolder = new StackPane(rays);
		raysHolder.setMouseTransparent(true);
		raysHolder.setTranslateY(-26);

		Pane particles = new Pane();
		particles.setMouseTransparent(true);
		Circle flash = new Circle(12, Color.WHITE);
		flash.setOpacity(0);
		flash.setMouseTransparent(true);
		flash.setTranslateY(-20);

		Label hint = new Label("CLICK THE PRESENT");
		hint.setStyle(PIXEL + "-fx-font-size: 12px; -fx-text-fill: #FFD27A;");
		hint.setOpacity(0);
		StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
		StackPane.setMargin(hint, new Insets(0, 0, 70, 0));

		Present present = Platform.isSupported(ConditionalFeature.SCENE3D) ? new Present3D() : new Present2D();
		Node gift = present.node();
		gift.setCursor(javafx.scene.Cursor.HAND);
		stage.getChildren().addAll(spotlight, raysHolder, particles, gift, flash, hint);

		present.drop(() -> {
			FadeTransition showHint = new FadeTransition(Duration.millis(400), hint);
			showHint.setToValue(1);
			showHint.play();
		});
		boolean[] opened = {false};
		Runnable open = () -> {
			if (opened[0]) {
				return;
			}
			opened[0] = true;
			hint.setVisible(false);
			present.burst(() -> {
				// The flash, the rays and confetti - then the reward rises out of the light.
				flash.setOpacity(1);
				ScaleTransition grow = new ScaleTransition(Duration.millis(520), flash);
				grow.setToX(42);
				grow.setToY(42);
				FadeTransition fadeFlash = new FadeTransition(Duration.millis(620), flash);
				fadeFlash.setToValue(0);
				new ParallelTransition(grow, fadeFlash).play();
				FadeTransition raysIn = new FadeTransition(Duration.millis(700), rays);
				raysIn.setToValue(1);
				raysIn.play();
				FadeTransition lightUp = new FadeTransition(Duration.millis(700), spotlight);
				lightUp.setToValue(1);
				lightUp.play();
				confetti(particles, stage.getWidth() / 2, stage.getHeight() / 2 - 20);
				stage.getChildren().remove(gift);
				present.dispose();
				Node panel = rewardPanel(reward, stage);
				stage.getChildren().add(panel);
				panel.setOpacity(0);
				panel.setTranslateY(40);
				panel.setScaleX(0.86);
				panel.setScaleY(0.86);
				FadeTransition fade = new FadeTransition(Duration.millis(420), panel);
				fade.setToValue(1);
				TranslateTransition rise = new TranslateTransition(Duration.millis(620), panel);
				rise.setToY(0);
				rise.setInterpolator(UiMotion.BACK_OUT_SOFT);
				ScaleTransition pop = new ScaleTransition(Duration.millis(620), panel);
				pop.setToX(1);
				pop.setToY(1);
				pop.setInterpolator(UiMotion.BACK_OUT);
				ParallelTransition up = new ParallelTransition(fade, rise, pop);
				up.setDelay(Duration.millis(140));
				up.play();
			});
		};
		gift.setOnMouseClicked(e -> open.run());
		tourGift = open;
		// Nobody clicks? It opens on its own after a while.
		PauseTransition auto = new PauseTransition(Duration.seconds(7));
		auto.setOnFinished(e -> open.run());
		auto.play();
	}

	private Node rewardPanel(JsonObject reward, StackPane stage) {
		String type = str(reward, "type");
		int amount = reward.has("amount") ? reward.get("amount").getAsInt() : 1;
		int day = reward.has("day") ? reward.get("day").getAsInt() : 0;
		Color glow = rewardColor(reward);

		Label over = new Label(("DOOR " + day + "  ·  " + kindLabel(reward)).toUpperCase());
		over.setStyle(PIXEL + "-fx-font-size: 12px; -fx-text-fill: " + web(glow.deriveColor(0, 0.7, 1.4, 1)) + ";");

		StackPane plate = new StackPane(rewardArt(reward, 168));
		plate.setMinSize(230, 210);
		plate.setPrefSize(230, 210);
		plate.setMaxSize(230, 210);

		Label name = new Label(str(reward, "name"));
		name.setStyle(DISPLAY + "-fx-font-size: 32px; -fx-text-fill: white; -fx-font-weight: 800;");
		name.setEffect(new DropShadow(javafx.scene.effect.BlurType.GAUSSIAN, glow.deriveColor(0, 1, 1, 0.55), 22, 0.2, 0, 0));
		String amountText = switch (type) {
			case "coins" -> "+" + StoreTiles.format(amount) + " COINS";
			case "plus" -> "+" + amount + (amount == 1 ? " DAY" : " DAYS") + " OF VELO+";
			default -> "×1  ·  YOURS FOREVER";
		};
		Label amountLabel = new Label(amountText);
		amountLabel.setStyle(PIXEL + "-fx-font-size: 15px; -fx-text-fill: #FFE6A0;");
		VBox info = new VBox(8, over, plate, name, amountLabel);
		info.setAlignment(Pos.CENTER);
		if (reward.has("instead")) {
			Label instead = new Label("You already own " + str(reward, "instead") + " - so it's coins instead.");
			instead.setStyle("-fx-font-size: 13px; -fx-text-fill: #AEB8DE;");
			info.getChildren().add(instead);
		}
		if (bool(reward, "mystery")) {
			Label mystery = new Label("Mystery door - everyone gets a different amount.");
			mystery.setStyle("-fx-font-size: 13px; -fx-text-fill: #AEB8DE;");
			info.getChildren().add(mystery);
		}
		if (reward.has("bonus")) {
			JsonObject bonus = reward.getAsJsonObject("bonus");
			Label perfect = new Label("PERFECT CALENDAR!  +  " + str(bonus, "name"));
			perfect.setStyle(PIXEL + "-fx-font-size: 13px; -fx-text-fill: #7DFFB4;");
			info.getChildren().add(perfect);
		}
		if (bool(calendar, "preview")) {
			Label preview = new Label("Owner preview - nothing was granted.");
			preview.setStyle("-fx-font-size: 12px; -fx-text-fill: #8E9AC4;");
			info.getChildren().add(preview);
		}

		Button done = new Button("Awesome!");
		done.getStyleClass().addAll("primary-button", "button-lg");
		done.setOnAction(e -> {
			FadeTransition out = new FadeTransition(Duration.millis(240), stage);
			out.setToValue(0);
			out.setOnFinished(ev -> {
				card.getChildren().remove(stage);
				busy = false;
				updateSubtitle();
				fillGrid(false);
			});
			out.play();
		});
		HBox buttons = new HBox(10);
		buttons.setAlignment(Pos.CENTER);
		if ("item".equals(type) && !bool(calendar, "preview")) {
			Button locker = new Button("Open Locker");
			locker.getStyleClass().add("button-lg");
			locker.setOnAction(e -> {
				busy = false;
				close();
				host.openLocker();
			});
			buttons.getChildren().add(locker);
		}
		buttons.getChildren().add(done);
		VBox panel = new VBox(18, info, buttons);
		panel.setAlignment(Pos.CENTER);
		panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		return panel;
	}

	/** Light rays fanning out from the centre (they turn slowly behind the reward). */
	private static Group rays(Color glow) {
		Group group = new Group();
		int count = 16;
		double length = 520;
		for (int i = 0; i < count; i++) {
			double a = i * Math.PI * 2 / count;
			double spread = Math.PI / count * 0.55;
			Polygon ray = new Polygon(0, 0, Math.cos(a - spread) * length, Math.sin(a - spread) * length,
					Math.cos(a + spread) * length, Math.sin(a + spread) * length);
			ray.setFill(new RadialGradient(0, 0, 0, 0, length, false, CycleMethod.NO_CYCLE,
					new Stop(0, glow.deriveColor(0, 0.8, 1.3, 0.34)), new Stop(1, Color.TRANSPARENT)));
			group.getChildren().add(ray);
		}
		return group;
	}

	/** Pixel confetti and sparkles bursting out of the present, falling with gravity. */
	private void confetti(Pane layer, double cx, double cy) {
		SplittableRandom random = new SplittableRandom();
		Color[] colors = {Color.web("#FF4A4A"), Color.web("#46F08A"), Color.web("#FFD24A"), Color.web("#55B8FF"), Color.WHITE, Color.web("#FF7AD9")};
		List<double[]> state = new ArrayList<>();
		List<Rectangle> bits = new ArrayList<>();
		for (int i = 0; i < 90; i++) {
			double size = random.nextDouble() < 0.3 ? 6 : 3 + random.nextInt(2);
			Rectangle bit = new Rectangle(size, size, colors[random.nextInt(colors.length)]);
			double angle = random.nextDouble() * Math.PI * 2;
			double speed = 180 + random.nextDouble() * 420;
			state.add(new double[] {cx, cy, Math.cos(angle) * speed, Math.sin(angle) * speed - 160, random.nextDouble() * 360, 0});
			bits.add(bit);
			layer.getChildren().add(bit);
		}
		long start = System.nanoTime();
		long[] last = {start};
		Timeline physics = new Timeline();
		physics.getKeyFrames().add(new KeyFrame(Duration.millis(16), e -> {
			long now = System.nanoTime();
			double dt = Math.min(0.05, (now - last[0]) / 1e9);
			last[0] = now;
			double age = (now - start) / 1e9;
			for (int i = 0; i < bits.size(); i++) {
				double[] s = state.get(i);
				s[2] *= 0.985;
				s[3] = s[3] * 0.985 + 420 * dt;
				s[0] += s[2] * dt;
				s[1] += s[3] * dt;
				Rectangle bit = bits.get(i);
				bit.setX(Math.round(s[0] / 3) * 3);
				bit.setY(Math.round(s[1] / 3) * 3);
				bit.setOpacity(Math.max(0, 1 - age / 2.6));
			}
			if (age > 2.7) {
				layer.getChildren().removeAll(bits);
			}
		}));
		physics.setCycleCount(170);
		physics.play();
	}

	private interface Present {
		Node node();

		void drop(Runnable landed);

		void burst(Runnable opened);

		void dispose();
	}

	/** The present in real 3D: a wrapped box with a ribbon and a bow, the lid flies off when it opens. */
	private final class Present3D implements Present {

		private final SubScene scene;
		private final Group gift = new Group();
		private final Group lid = new Group();
		private final Rotate spinY = new Rotate(0, Rotate.Y_AXIS);
		private final Rotate tiltZ = new Rotate(0, Rotate.Z_AXIS);
		private final Rotate lidX = new Rotate(0, Rotate.X_AXIS);
		private final Timeline idle;

		Present3D() {
			PhongMaterial paper = material(Color.web("#C8202E"));
			PhongMaterial paperLight = material(Color.web("#E0303E"));
			PhongMaterial ribbon = material(Color.web("#F2C24A"));
			Box body = new Box(1.6, 1.3, 1.6);
			body.setMaterial(paper);
			Box ribbonA = new Box(0.3, 1.32, 1.62);
			ribbonA.setMaterial(ribbon);
			Box ribbonB = new Box(1.62, 1.32, 0.3);
			ribbonB.setMaterial(ribbon);
			Box lidBox = new Box(1.76, 0.34, 1.76);
			lidBox.setMaterial(paperLight);
			Box lidA = new Box(0.32, 0.36, 1.78);
			lidA.setMaterial(ribbon);
			Box lidB = new Box(1.78, 0.36, 0.32);
			lidB.setMaterial(ribbon);
			Box bowLeft = new Box(0.56, 0.3, 0.2);
			bowLeft.setMaterial(ribbon);
			bowLeft.setTranslateX(-0.26);
			bowLeft.setTranslateY(-0.3);
			bowLeft.setRotate(-32);
			Box bowRight = new Box(0.56, 0.3, 0.2);
			bowRight.setMaterial(ribbon);
			bowRight.setTranslateX(0.26);
			bowRight.setTranslateY(-0.3);
			bowRight.setRotate(32);
			Box knot = new Box(0.22, 0.22, 0.24);
			knot.setMaterial(material(Color.web("#D9A633")));
			knot.setTranslateY(-0.26);
			lid.getChildren().addAll(lidBox, lidA, lidB, bowLeft, bowRight, knot);
			lid.setTranslateY(-0.82);
			lid.getTransforms().add(lidX);
			gift.getChildren().addAll(body, ribbonA, ribbonB, lid);
			gift.getTransforms().addAll(tiltZ, spinY);

			AmbientLight ambient = new AmbientLight(Color.rgb(120, 120, 140));
			PointLight key = new PointLight(Color.rgb(255, 240, 220));
			key.setTranslateX(-3);
			key.setTranslateY(-4);
			key.setTranslateZ(-5);
			PointLight rim = new PointLight(Color.rgb(90, 140, 255));
			rim.setTranslateX(4);
			rim.setTranslateY(-1);
			rim.setTranslateZ(3);
			Group world = new Group(gift, ambient, key, rim);
			PerspectiveCamera camera = new PerspectiveCamera(true);
			camera.setFieldOfView(32);
			camera.setNearClip(0.1);
			camera.setFarClip(100);
			camera.getTransforms().addAll(new Rotate(-22, Rotate.X_AXIS), new javafx.scene.transform.Translate(0, 0, -7.4));
			scene = new SubScene(world, 320, 320, true, SceneAntialiasing.BALANCED);
			scene.setFill(Color.TRANSPARENT);
			scene.setCamera(camera);
			scene.setTranslateY(-20);
			spinY.setAngle(-28);
			idle = new Timeline(new KeyFrame(Duration.seconds(9), new KeyValue(spinY.angleProperty(), 332, Interpolator.LINEAR)));
			idle.setCycleCount(Animation.INDEFINITE);
		}

		private static PhongMaterial material(Color color) {
			PhongMaterial m = new PhongMaterial(color);
			m.setSpecularColor(Color.rgb(255, 255, 255, 0.35));
			m.setSpecularPower(18);
			return m;
		}

		@Override
		public Node node() {
			return scene;
		}

		@Override
		public void drop(Runnable landed) {
			gift.setTranslateY(-5);
			Timeline fall = new Timeline(
					new KeyFrame(Duration.millis(560), new KeyValue(gift.translateYProperty(), 0, Interpolator.SPLINE(0.55, 0, 0.9, 0.6))),
					new KeyFrame(Duration.millis(560), new KeyValue(gift.scaleYProperty(), 1)),
					new KeyFrame(Duration.millis(660), new KeyValue(gift.scaleYProperty(), 0.82, Interpolator.EASE_OUT),
							new KeyValue(gift.scaleXProperty(), 1.1, Interpolator.EASE_OUT), new KeyValue(gift.scaleZProperty(), 1.1, Interpolator.EASE_OUT)),
					new KeyFrame(Duration.millis(900), new KeyValue(gift.scaleYProperty(), 1, UiMotion.BACK_OUT),
							new KeyValue(gift.scaleXProperty(), 1, UiMotion.BACK_OUT), new KeyValue(gift.scaleZProperty(), 1, UiMotion.BACK_OUT)));
			fall.setOnFinished(e -> {
				idle.play();
				running.add(idle);
				landed.run();
			});
			fall.play();
		}

		@Override
		public void burst(Runnable opened) {
			idle.pause();
			// Shakes, harder and faster, swelling a little...
			Timeline shake = new Timeline();
			double t = 0;
			for (int i = 0; i < 9; i++) {
				double step = 120 - i * 9;
				t += step;
				double angle = (i % 2 == 0 ? 1 : -1) * (5 + i * 1.6);
				shake.getKeyFrames().add(new KeyFrame(Duration.millis(t), new KeyValue(tiltZ.angleProperty(), angle, Interpolator.EASE_BOTH)));
			}
			t += 70;
			shake.getKeyFrames().addAll(new KeyFrame(Duration.millis(t), new KeyValue(tiltZ.angleProperty(), 0)),
					new KeyFrame(Duration.millis(t), new KeyValue(gift.scaleXProperty(), 1.12), new KeyValue(gift.scaleYProperty(), 1.12),
							new KeyValue(gift.scaleZProperty(), 1.12)));
			// ...then the lid shoots up and tumbles away, and the box sinks into the light.
			Timeline pop = new Timeline(
					new KeyFrame(Duration.millis(520), new KeyValue(lid.translateYProperty(), -4.6, Interpolator.SPLINE(0.1, 0.7, 0.3, 1)),
							new KeyValue(lidX.angleProperty(), -70, Interpolator.EASE_OUT), new KeyValue(lid.translateZProperty(), 1.4, Interpolator.EASE_OUT)),
					new KeyFrame(Duration.millis(460), new KeyValue(gift.translateYProperty(), 0.2)),
					new KeyFrame(Duration.millis(700), new KeyValue(gift.translateYProperty(), 2.6, Interpolator.EASE_IN),
							new KeyValue(gift.scaleXProperty(), 0.2, Interpolator.EASE_IN), new KeyValue(gift.scaleYProperty(), 0.2, Interpolator.EASE_IN),
							new KeyValue(gift.scaleZProperty(), 0.2, Interpolator.EASE_IN)));
			PauseTransition beat = new PauseTransition(Duration.millis(180));
			beat.setOnFinished(e -> opened.run());
			javafx.animation.SequentialTransition all = new javafx.animation.SequentialTransition(shake, new ParallelTransition(pop, beat));
			all.play();
		}

		@Override
		public void dispose() {
			idle.stop();
		}
	}

	/** Where JavaFX can't do 3D: the same present as pixel art, with the same shake and pop. */
	private static final class Present2D implements Present {

		private final StackPane holder = new StackPane();
		private final ImageView box;
		private final ImageView lid;

		Present2D() {
			lid = pixelArt(new String[] {
					"....yy..yy....",
					".....yyyy.....",
					"RRRRRRyyRRRRRR",
					"RRRRRRyyRRRRRR"}, 12, 0, 0);
			box = pixelArt(new String[] {
					".rrrrryyrrrrr.",
					".rrrrryyrrrrr.",
					".rrrrryyrrrrr.",
					".yyyyyyyyyyyy.",
					".rrrrryyrrrrr.",
					".rrrrryyrrrrr.",
					".rrrrryyrrrrr."}, 12, 0, 0);
			VBox stack = new VBox(0, lid, box);
			stack.setAlignment(Pos.CENTER);
			stack.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
			holder.getChildren().add(stack);
			holder.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
		}

		@Override
		public Node node() {
			return holder;
		}

		@Override
		public void drop(Runnable landed) {
			holder.setTranslateY(-400);
			TranslateTransition fall = new TranslateTransition(Duration.millis(620), holder);
			fall.setToY(0);
			fall.setInterpolator(UiMotion.BACK_OUT_SOFT);
			fall.setOnFinished(e -> landed.run());
			fall.play();
		}

		@Override
		public void burst(Runnable opened) {
			RotateTransition wobble = new RotateTransition(Duration.millis(90), holder);
			wobble.setFromAngle(-7);
			wobble.setToAngle(7);
			wobble.setAutoReverse(true);
			wobble.setCycleCount(8);
			TranslateTransition lidUp = new TranslateTransition(Duration.millis(420), lid);
			lidUp.setByY(-260);
			FadeTransition boxOut = new FadeTransition(Duration.millis(420), box);
			boxOut.setToValue(0);
			javafx.animation.SequentialTransition all = new javafx.animation.SequentialTransition(wobble, new ParallelTransition(lidUp, boxOut));
			all.setOnFinished(e -> opened.run());
			all.play();
		}

		@Override
		public void dispose() {
		}
	}

	// ------------------------------------------------------------------ reward art

	/** The reward's picture: the real cosmetic where the launcher knows it, a pixel coin pile, or the Velo+ crown. */
	static Node rewardArt(JsonObject reward, double size) {
		String type = str(reward, "type");
		if ("coins".equals(type)) {
			int unit = Math.max(2, (int) Math.round(size / 16));
			return pixelArt(new String[] {
					".....oooooo.....",
					"...ooYYYYYYoo...",
					"..oYYyyyyyyYYo..",
					".oYyyYYYYYYyyYo.",
					".oYyYYyyyyYYyYo.",
					"oYyYYy.oo.yYYyYo",
					"oYyYy..YY..yYyYo",
					"oYyYy..YY..yYyYo",
					"oYyYy..YY..yYyYo",
					"oYyYYy.YY.yYYyYo",
					".oYyYYyyyyYYyYo.",
					".oYyyYYYYYYyyYo.",
					"..oYYyyyyyyYYo..",
					"...ooYYYYYYoo...",
					".....oooooo....."}, unit, 0, 0);
		}
		if ("plus".equals(type)) {
			int unit = Math.max(2, (int) Math.round(size / 18));
			ImageView crown = pixelArt(new String[] {
					"..y.....y.....y..",
					".yYy...yYy...yYy.",
					".yYYy.yYYYy.yYYy.",
					".yYYYyYYYYYyYYYy.",
					".yYYYYYYrYYYYYYy.",
					".yYYYYYrRrYYYYYy.",
					".yYYYYYYrYYYYYYy.",
					".yyyyyyyyyyyyyyy.",
					".oooooooooooooooo"}, unit, 0, 0);
			Label plus = new Label("VELO+");
			plus.setStyle(PIXEL + "-fx-font-size: " + Math.max(9, Math.round(size / 6)) + "px; -fx-font-weight: bold; -fx-text-fill: #FFD24A;");
			VBox box = new VBox(size / 14, crown, plus);
			box.setAlignment(Pos.CENTER);
			return box;
		}
		String id = str(reward, "id");
		String itemType = str(reward, "itemType");
		try {
			switch (itemType) {
				case "cape" -> {
					var item = net.veloclient.launcher.data.StoreCatalog.byId(id);
					if (item.isPresent()) {
						return StoreTiles.capeArt(item.get(), null, size);
					}
				}
				case "wings" -> {
					for (var wings : net.veloclient.launcher.data.WingsCatalog.all()) {
						if (wings.itemId().equals(id)) {
							return StoreTiles.wingsArt(wings, size);
						}
					}
				}
				case "aura" -> {
					for (var aura : net.veloclient.launcher.data.AuraCatalog.all()) {
						if (aura.itemId().equals(id)) {
							return StoreTiles.auraArt(aura, size, size);
						}
					}
				}
				case "killfx" -> {
					for (var fx : net.veloclient.launcher.data.KillFxCatalog.all()) {
						if (fx.itemId().equals(id)) {
							return StoreTiles.killFxArt(fx, size, size);
						}
					}
				}
				case "wear" -> {
					for (var wear : net.veloclient.launcher.data.WearCatalog.all()) {
						if (wear.itemId().equals(id)) {
							return StoreTiles.wearArt(wear, size);
						}
					}
				}
				case "pet" -> {
					for (var pet : net.veloclient.launcher.data.PetsSetting.storePets()) {
						if (pet.itemId().equals(id)) {
							return new PetSheetView(pet, size, true);
						}
					}
				}
				default -> {
				}
			}
		} catch (RuntimeException ignored) {
			// fall through to the icon
		}
		String icon = switch (itemType) {
			case "cape" -> "cape";
			case "wings" -> "wings";
			case "aura" -> "aura";
			case "killfx" -> "skull";
			case "wear" -> "hat";
			case "pet" -> "heart";
			default -> "gift";
		};
		return Icons.icon(icon, size * 0.62, rewardColor(reward).deriveColor(0, 0.8, 1.4, 1), 1.6);
	}

	private static Color rewardColor(JsonObject reward) {
		String type = str(reward, "type");
		if (reward.has("accent")) {
			try {
				return Color.web(reward.get("accent").getAsString());
			} catch (RuntimeException ignored) {
				// default below
			}
		}
		return switch (type) {
			case "coins" -> Color.web("#FFC83A");
			case "plus" -> Color.web("#FF5A5F");
			default -> Color.web("#7AB8FF");
		};
	}

	private static String kindLabel(JsonObject reward) {
		return switch (str(reward, "type")) {
			case "coins" -> bool(reward, "mystery") ? "Mystery coins" : "Coins";
			case "plus" -> "Velo+";
			default -> switch (str(reward, "itemType")) {
				case "cape" -> "Cloak";
				case "wings" -> "Wings";
				case "aura" -> "Aura";
				case "killfx" -> "Kill effect";
				case "wear" -> "Bodywear";
				case "pet" -> "Pet";
				case "emote" -> "Emote";
				default -> "Cosmetic";
			};
		};
	}

	private static String shortName(JsonObject reward) {
		return switch (str(reward, "type")) {
			case "coins" -> "+" + reward.get("amount").getAsInt();
			case "plus" -> reward.get("amount").getAsInt() + "D VELO+";
			default -> {
				String name = str(reward, "name");
				yield name.length() > 11 ? name.substring(0, 10) + "." : name;
			}
		};
	}

	private static final Map<Character, Integer> PALETTE = Map.ofEntries(
			Map.entry('W', 0xFFF4F7FF), Map.entry('w', 0xFFC6D2F0), Map.entry('y', 0xFFE8A82A), Map.entry('Y', 0xFFFFD84A),
			Map.entry('o', 0xFF9A6418), Map.entry('r', 0xFFC8202E), Map.entry('R', 0xFFE0303E), Map.entry('g', 0xFF2F9A5A),
			Map.entry('b', 0xFF55B8FF));

	/** A tiny sprite from text rows ({@link #PALETTE} letters, '.' = clear), each pixel {@code unit} screen pixels. */
	static ImageView pixelArt(String[] rows, int unit, double x, double y) {
		int cols = 0;
		for (String row : rows) {
			cols = Math.max(cols, row.length());
		}
		WritableImage img = new WritableImage(cols * unit, rows.length * unit);
		PixelWriter writer = img.getPixelWriter();
		int[] line = new int[cols * unit];
		for (int r = 0; r < rows.length; r++) {
			java.util.Arrays.fill(line, 0);
			for (int c = 0; c < rows[r].length(); c++) {
				Integer argb = PALETTE.get(rows[r].charAt(c));
				if (argb != null) {
					java.util.Arrays.fill(line, c * unit, c * unit + unit, argb);
				}
			}
			for (int dy = 0; dy < unit; dy++) {
				writer.setPixels(0, r * unit + dy, line.length, 1, javafx.scene.image.PixelFormat.getIntArgbInstance(), line, 0, line.length);
			}
		}
		ImageView view = new ImageView(img);
		view.setSmooth(false);
		view.setLayoutX(x);
		view.setLayoutY(y);
		return view;
	}

	// ------------------------------------------------------------------ helpers

	private void close() {
		for (Animation animation : running) {
			animation.stop();
		}
		running.clear();
		FadeTransition out = new FadeTransition(Duration.millis(220), root);
		out.setToValue(0);
		out.setOnFinished(e -> {
			layers.getChildren().remove(root);
			card.getChildren().clear(); // lets the backdrop (and its frame loop) go
			if (onClosed != null) {
				onClosed.run();
			}
		});
		out.play();
	}

	private static boolean bool(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private static String hex(int argb) {
		return String.format("#%06X", argb & 0xFFFFFF);
	}

	private static String web(Color c) {
		return String.format("#%02X%02X%02X", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
	}
}
