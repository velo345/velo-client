package net.veloclient.launcher.ui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import net.veloclient.launcher.data.VeloPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Velo+ Customize: the title screen, the pause menu and the loading screen made your own - a live preview on the
 * left (drag the title screen's parts to move them), every setting on the right. Saved in {@code config/screens.json}
 * on this PC (pictures in {@code backgrounds/}); the game picks changes up within half a second, even while it runs.
 * The game reads the same file with the same rules (net.veloclient.velo.client.gui.title.ScreenLook): an alignment plus
 * offsets as a fraction of the screen, so a layout fits every window size and GUI scale.
 */
public final class CustomizeView {

	/** What the page needs from the launcher. */
	public interface Host {
		boolean plus();

		/** The theme editor (the same one as in Settings), re-built on this page's Theme tab. */
		Node themeEditor();

		/** The theme's accent colour. */
		Color accent();

		void openPlus();

		Window window();

	}

	/** The scaled GUI size of a 1080p window at GUI scale 4 - what most players see. */
	private static final double GW = 480;
	private static final double GH = 270;
	private static final String[] FONTS = {"Archivo", "Sora", "Inter", "Poppins", "Space Grotesk", "Mono", "Audiowide", "Minecraft"};
	private static final Map<String, String> FONT_FILES = Map.of("Archivo", "ArchivoSemiExpanded-ExtraBold", "Sora", "Sora-Bold",
			"Inter", "Inter-SemiBold", "Poppins", "Poppins-Regular", "Space Grotesk", "SpaceGrotesk-Bold", "Mono", "JetBrainsMono-Medium",
			"Audiowide", "Audiowide-Regular", "Minecraft", "Monocraft-Bold");
	private static final Map<String, String> FONT_NAMES = new HashMap<>();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static String tab = "title";
	private static JsonObject state;

	private final Host host;
	private final StackPane previewHolder = new StackPane();
	private final Pane canvas = new Pane();
	private final VBox settings = new VBox(10);
	private final PauseTransition saveLater = new PauseTransition(Duration.millis(220));
	private final Map<String, Image> pictures = new HashMap<>();
	/** The part being dragged in the title preview, and where the drag started. */
	private String dragPart;
	private double dragStartX;
	private double dragStartY;
	private float[] dragFrom;

	private CustomizeView(Host host) {
		this.host = host;
		saveLater.setOnFinished(e -> save());
	}

	public static Node build(Host host) {
		return new CustomizeView(host).page();
	}

	// ------------------------------------------------------------------ page

	private Node page() {
		load();
		Label heading = new Label("Customize");
		heading.getStyleClass().add("page-title");
		Label chip = new Label("VELO+");
		chip.getStyleClass().add("t-overline");
		chip.setStyle("-fx-background-color: -velo-accent; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-background-radius: 6;");
		HBox titleRow = new HBox(12, heading, chip);
		titleRow.setAlignment(Pos.CENTER_LEFT);
		Label sub = new Label("Your title screen, pause menu and loading screen. Saved on this PC only - the game shows changes right away.");
		sub.getStyleClass().add("t-muted");
		sub.setWrapText(true);

		HBox tabs = new HBox();
		tabs.getStyleClass().add("segment-bar");
		tabs.setMaxWidth(Region.USE_PREF_SIZE);
		for (String[] t : new String[][] {{"title", "Title screen"}, {"menu", "R-Shift menu"}, {"pause", "Pause menu"}, {"loading", "Loading screen"}, {"theme", "Theme"},
				{"launcher", "Launcher"}}) {
			Button b = new Button(t[1]);
			b.getStyleClass().setAll("button", "segmented-button");
			if (t[0].equals(tab)) {
				b.getStyleClass().add("segmented-button-active");
			}
			b.setOnAction(e -> {
				tab = t[0];
				for (Node n : tabs.getChildren()) {
					n.getStyleClass().remove("segmented-button-active");
				}
				b.getStyleClass().add("segmented-button-active");
				refresh();
			});
			tabs.getChildren().add(b);
		}

		VBox top = new VBox(8, titleRow, sub);
		if (!host.plus()) {
			Label lock = new Label("Customize is part of Velo+. Try everything here - the game uses your look once you're a member.");
			lock.setWrapText(true);
			lock.getStyleClass().add("t-strong");
			Button get = new Button("Get Velo+");
			get.getStyleClass().add("primary-button");
			get.setOnAction(e -> host.openPlus());
			HBox banner = new HBox(14, Icons.icon("lock", 16, Color.web("#E4E4E8")), lock, get);
			HBox.setHgrow(lock, Priority.ALWAYS);
			banner.setAlignment(Pos.CENTER_LEFT);
			banner.setStyle("-fx-background-color: -velo-surface; -fx-background-radius: 12; -fx-border-color: -velo-hairline; "
					+ "-fx-border-radius: 12; -fx-padding: 12 16 12 16;");
			top.getChildren().add(banner);
		}
		top.getChildren().add(tabs);

		// preview: a 480 x 270 "screen" scaled to the space it gets
		canvas.setPrefSize(GW, GH);
		canvas.setMinSize(GW, GH);
		canvas.setMaxSize(GW, GH);
		Rectangle clip = new Rectangle(GW, GH);
		canvas.setClip(clip);
		Scale scale = new Scale(1, 1, 0, 0);
		canvas.getTransforms().add(scale);
		Group scaled = new Group(canvas);
		previewHolder.getChildren().add(scaled);
		previewHolder.setAlignment(Pos.TOP_LEFT);
		previewHolder.setMinSize(240, 135);
		previewHolder.setStyle("-fx-background-color: #000; -fx-background-radius: 14;");
		Rectangle holderClip = new Rectangle();
		holderClip.setArcWidth(24);
		holderClip.setArcHeight(24);
		holderClip.widthProperty().bind(previewHolder.widthProperty());
		holderClip.heightProperty().bind(previewHolder.heightProperty());
		previewHolder.setClip(holderClip);
		previewHolder.widthProperty().addListener((o, a, w) -> {
			double k = w.doubleValue() / GW;
			scale.setX(k);
			scale.setY(k);
			previewHolder.setPrefHeight(GH * k);
			previewHolder.setMinHeight(GH * k);
			previewHolder.setMaxHeight(GH * k);
		});
		Label hint = new Label();
		hint.getStyleClass().add("t-muted");
		hint.setStyle("-fx-font-size: 12px;");
		hint.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(() -> switch (tab) {
			case "title" -> "Drag the logo, the menu, the card, the bar at the bottom or the Store card to move them. Double-click one to put it back.";
			case "pause" -> "With Essential installed the menu always stays on the left - its character needs that side.";
			case "loading" -> "Shown when the game starts and when resource packs reload.";
			case "menu" -> "The Velo menu and every Velo window. Click the preview to play the animation again.";
			default -> "";
		}, previewHolder.widthProperty()));
		VBox previewColumn = new VBox(10, previewHolder, hint);
		HBox.setHgrow(previewColumn, Priority.ALWAYS);
		previewColumn.setMinWidth(260);

		ScrollPane scroll = new ScrollPane(settings);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.getStyleClass().add("edge-to-edge");
		scroll.setPrefWidth(380);
		scroll.setMinWidth(320);
		scroll.setMaxWidth(400);
		settings.setPadding(new Insets(0, 8, 24, 0));

		editorBody = new HBox(24, previewColumn, scroll);
		VBox.setVgrow(bodyHolder, Priority.ALWAYS);
		VBox page = new VBox(18, top, bodyHolder);
		page.getStyleClass().add("page");
		refresh();
		return page;
	}

	private HBox editorBody;
	private final StackPane bodyHolder = new StackPane();

	private void refresh() {
		menuAnim.stop();
		if (tab.equals("theme")) {
			// the theme editor brings its own preview
			bodyHolder.getChildren().setAll(host.themeEditor());
			return;
		}
		bodyHolder.getChildren().setAll(editorBody);
		settings.getChildren().setAll(switch (tab) {
			case "menu" -> menuSettings();
			case "pause" -> pauseSettings();
			case "loading" -> loadingSettings();
			case "launcher" -> launcherSettings();
			default -> titleSettings();
		});
		redraw();
	}

	private void redraw() {
		canvas.getChildren().clear();
		switch (tab) {
			case "menu" -> drawMenu();
			case "theme" -> {
			}
			case "pause" -> drawPause();
			case "loading" -> drawLoading();
			case "launcher" -> drawLauncher();
			default -> drawTitle();
		}
	}

	private void changed() {
		redraw();
		saveLater.playFromStart();
	}

	// ------------------------------------------------------------------ settings: title

	private List<Node> titleSettings() {
		JsonObject t = section("title");
		VBox bg = SettingsUi.card("Background");
		row(bg, "Background", null, null, segmented(t, "background", new String[][] {{"velo", "Velo"}, {"image", "Your picture"},
				{"vanilla", "Minecraft"}}, "velo"));
		if (str(t, "background", "velo").equals("image")) {
			row(bg, "Picture or GIF", "PNG, JPG or animated GIF", "Covers the whole screen - cropped evenly, never stretched. "
					+ "GIFs play in the game too.", pictureButtons("title"));
			row(bg, "Blur", null, null, slider(t, "blur", 0, 100, 0, "%"));
		}
		row(bg, "Darken", null, null, slider(t, "dim", 0, 100, 0, "%"));
		row(bg, "Shade behind the menu", "The dark fade on the menu's side", null, slider(t, "scrim", 0, 100, 100, "%"));

		VBox layout = SettingsUi.card("Layout");
		row(layout, "Menu position", "Drag parts in the preview to fine-tune", null,
				segmented(t, "align", new String[][] {{"left", "Left"}, {"center", "Center"}, {"right", "Right"}}, "left"));
		Button resetMoves = new Button("Put everything back");
		resetMoves.getStyleClass().add("ghost-button");
		resetMoves.setOnAction(e -> {
			t.remove("moved");
			changed();
		});
		row(layout, "Moved parts", null, null, resetMoves);
		row(layout, "Logo", null, null, toggle(t, "brand", true));
		row(layout, "\"Jump back in\" card", "Your last server", null, toggle(t, "jump", true));
		row(layout, "Bottom bar", "You, friends and the small buttons", null, toggle(t, "footer", true));
		row(layout, "Store card", "Hidden when Essential is installed", null, toggle(t, "store", true));

		VBox words = SettingsUi.card("Menu words");
		row(words, "Font", null, null, fontBox(t, "font"));
		row(words, "Size", null, null, sliderF(t, "scale", 60, 160, 100));
		row(words, "Buttons", null, null, segmented(t, "buttons", new String[][] {{"words", "Words"}, {"pills", "Pills"},
				{"glass", "Glass"}, {"outline", "Outline"}, {"underline", "Line"}}, "words"));
		row(words, "Colour", null, null, colorPicker(t, "color", "#EDE6E6"));
		row(words, "Hover colour", null, null, colorPicker(t, "hoverColor", "#FFFFFF"));
		row(words, "Accent", "Highlights, badges and button edges", null, colorPicker(t, "accent", "#E0262E"));
		row(words, "Shadow", null, null, toggle(t, "shadow", false));
		row(words, "Glow", null, null, slider(t, "glow", 0, 100, 0, "%"));
		return List.of(bg, layout, words, resetCard("title"));
	}

	// ------------------------------------------------------------------ settings: R-Shift menu

	private static final String[][] MENU_STYLES = {{"velo", "Velo", "Warm, calm, almost solid"}, {"glass", "Glass", "See-through with a sheen"},
			{"minimal", "Minimal", "Flat and quiet"}, {"glow", "Glow", "A soft halo in your accent"}, {"neon", "Neon", "Bright edges on black"},
			{"solid", "Solid", "Your theme colour, deep shadow"}};

	private List<Node> menuSettings() {
		JsonObject m = section("menu");
		VBox styles = SettingsUi.card("Style");
		javafx.scene.layout.TilePane grid = new javafx.scene.layout.TilePane(8, 8);
		grid.setPrefColumns(2);
		grid.setPadding(new Insets(4, 0, 8, 0));
		String current = str(m, "style", "velo");
		for (String[] st : MENU_STYLES) {
			grid.getChildren().add(styleCard(m, st, st[0].equals(current)));
		}
		styles.getChildren().add(grid);

		VBox motion = SettingsUi.card("Animation");
		ComboBox<String> open = choice(m, "open", "spring", new String[][] {{"spring", "Spring (Velo)"}, {"fade", "Fade"}, {"zoom", "Zoom"},
				{"pop", "Pop"}, {"slide-up", "Slide up"}, {"slide-down", "Slide down"}, {"slide-left", "From the right"},
				{"slide-right", "From the left"}, {"drop", "Drop and bounce"}, {"unfold", "Unfold"}, {"none", "None"}});
		row(motion, "Opening", null, null, open);
		ComboBox<String> close = choice(m, "close", "spring", new String[][] {{"spring", "Spring (Velo)"}, {"fade", "Fade"}, {"zoom", "Shrink"},
				{"slide-up", "Slide up"}, {"slide-down", "Slide down"}, {"slide-left", "To the left"}, {"slide-right", "To the right"},
				{"fold", "Fold"}, {"none", "None"}});
		row(motion, "Closing", null, null, close);
		row(motion, "Switching pages", null, null, segmented(m, "pages", new String[][] {{"slide", "Slide"}, {"fade", "Fade"}, {"none", "Instant"}},
				"slide"));
		row(motion, "Speed", "Slower ... faster", null, sliderF(m, "speed", 50, 200, 100));

		VBox window = SettingsUi.card("Window");
		row(window, "See-through", null, null, slider(m, "clear", 0, 100, 0, "%"));
		row(window, "Corners", null, null, slider(m, "radius", 0, 20, 10, " px"));
		row(window, "Darken the game", null, null, slider(m, "dim", 0, 100, 44, "%"));
		return List.of(styles, motion, window, resetCard("menu"));
	}

	/** A small picture of a menu style - click to pick it. */
	private Node styleCard(JsonObject m, String[] st, boolean active) {
		Pane art = new Pane();
		art.setPrefSize(150, 70);
		art.setMinSize(150, 70);
		JsonObject probe = new JsonObject();
		probe.addProperty("style", st[0]);
		art.getChildren().add(rect(0, 0, 150, 70, new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
				new Stop(0, Color.web("#2B3A2C")), new Stop(1, Color.web("#1A2230")))));
		Group mini = new Group();
		drawWindow(mini, probe, 16, 10, 118, 52, 0.42);
		art.getChildren().add(mini);
		Rectangle artClip = new Rectangle(150, 70);
		artClip.setArcWidth(16);
		artClip.setArcHeight(16);
		art.setClip(artClip);
		Label name = new Label(st[1]);
		name.getStyleClass().add("t-strong");
		Label what = new Label(st[2]);
		what.getStyleClass().add("t-muted");
		what.setStyle("-fx-font-size: 11px;");
		what.setWrapText(true);
		VBox card = new VBox(6, art, name, what);
		card.setPadding(new Insets(6));
		card.setPrefWidth(162);
		card.setCursor(Cursor.HAND);
		card.setStyle("-fx-background-radius: 12; -fx-border-radius: 12; -fx-border-width: 1.5; -fx-background-color: "
				+ (active ? "-velo-surface-3" : "-velo-surface") + "; -fx-border-color: " + (active ? "-velo-accent" : "-velo-hairline") + ";");
		card.setOnMouseClicked(e -> {
			m.addProperty("style", st[0]);
			saveLater.playFromStart();
			refresh();
		});
		return card;
	}

	private ComboBox<String> choice(JsonObject o, String key, String fallback, String[][] options) {
		ComboBox<String> box = new ComboBox<>();
		Map<String, String> byLabel = new HashMap<>();
		String current = str(o, key, fallback);
		for (String[] option : options) {
			box.getItems().add(option[1]);
			byLabel.put(option[1], option[0]);
			if (option[0].equals(current)) {
				box.setValue(option[1]);
			}
		}
		box.setPrefWidth(200);
		box.valueProperty().addListener((obs, a, v) -> {
			o.addProperty(key, byLabel.get(v));
			saveLater.playFromStart();
			playMenu(key.equals("close"));
		});
		return box;
	}

	// ------------------------------------------------------------------ preview: R-Shift menu

	private final javafx.animation.AnimationTimer menuAnim = new javafx.animation.AnimationTimer() {
		@Override
		public void handle(long now) {
			stepMenu(now);
		}
	};
	private long menuStart;
	private boolean menuClosing;
	private Group menuWindow;
	private Rectangle menuDim;

	private void drawMenu() {
		JsonObject m = section("menu");
		background(null, 0, "velo");
		menuDim = rect(0, 0, GW, GH, Color.BLACK);
		menuDim.setOpacity(num(m, "dim", 44) / 100.0);
		canvas.getChildren().add(menuDim);
		menuWindow = new Group();
		drawWindow(menuWindow, m, 60, 30, GW - 120, GH - 60, 1.0);
		canvas.getChildren().add(menuWindow);
		canvas.setOnMouseClicked(e -> playMenu(false));
		playMenu(false);
	}

	/** Plays the opening (or the closing, then the opening again) like the game does. */
	private void playMenu(boolean closingFirst) {
		if (menuWindow == null || !tab.equals("menu")) {
			return;
		}
		menuClosing = closingFirst;
		menuStart = System.nanoTime();
		menuAnim.start();
	}

	private void stepMenu(long now) {
		JsonObject m = section("menu");
		String kind = str(m, menuClosing ? "close" : "open", "spring");
		float speed = fnum(m, "speed", 1f);
		double ms = menuClosing ? (kind.equals("fold") ? 260 : kind.equals("spring") ? 170 : 200)
				: kind.equals("drop") ? 620 : kind.equals("pop") ? 460 : kind.equals("spring") ? 420 : 360;
		double t = kind.equals("none") ? 1 : Math.min(1, (now - menuStart) / (ms * 1e6 / speed));
		double k = menuClosing ? 1 - t * t * t : 1 - Math.pow(1 - t, 3);
		double opacity = kind.equals("drop") || kind.startsWith("slide") ? Math.min(1, k * 1.6) : k;
		double sx = 1;
		double sy = 1;
		double dx = 0;
		double dy = 0;
		double away = 1 - k;
		double dir = menuClosing ? -1 : 1;
		switch (kind) {
			case "spring" -> {
				if (menuClosing) {
					sx = sy = 1 - 0.05 * t * t * t;
					dy = -4 * t * t * t;
				} else {
					double c1 = 1.4;
					double c3 = c1 + 1;
					double back = 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
					sx = sy = 0.9 + 0.1 * back;
					opacity = 1 - Math.pow(1 - Math.min(1, t * 1.6), 3);
					dy = (1 - opacity) * 16;
				}
			}
			case "zoom" -> sx = sy = 0.82 + 0.18 * k;
			case "pop" -> {
				double c1 = 1.70158 * 1.4;
				double c3 = c1 + 1;
				double back = 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
				sx = sy = 0.6 + 0.4 * back;
			}
			case "slide-up" -> dy = away * 60 * dir;
			case "slide-down" -> dy = -away * 60 * dir;
			case "slide-left" -> dx = away * 90 * dir;
			case "slide-right" -> dx = -away * 90 * dir;
			case "drop" -> dy = -(1 - bounce(t)) * GH * 0.7;
			case "unfold", "fold" -> sy = Math.max(0.02, k);
			default -> {
			}
		}
		menuWindow.setOpacity(Math.max(0, opacity));
		menuWindow.setScaleX(sx);
		menuWindow.setScaleY(sy);
		menuWindow.setTranslateX(dx);
		menuWindow.setTranslateY(dy);
		menuDim.setOpacity(num(m, "dim", 44) / 100.0 * Math.max(0, Math.min(1, k)));
		if (t >= 1) {
			if (menuClosing) {
				// closed: open again after a short beat
				menuClosing = false;
				menuStart = now + 250_000_000L;
				return;
			}
			menuAnim.stop();
		}
	}

	private static double bounce(double t) {
		double n1 = 7.5625;
		double d1 = 2.75;
		if (t < 1 / d1) {
			return n1 * t * t;
		} else if (t < 2 / d1) {
			t -= 1.5 / d1;
			return n1 * t * t + 0.75;
		} else if (t < 2.5 / d1) {
			t -= 2.25 / d1;
			return n1 * t * t + 0.9375;
		}
		t -= 2.625 / d1;
		return n1 * t * t + 0.984375;
	}

	/** A Velo window (rail, header, a few module cards) in the chosen style, into {@code g} at x, y, w x h. */
	private void drawWindow(Group g, JsonObject m, double x, double y, double w, double h, double k) {
		String style = str(m, "style", "velo");
		Color accent = host.accent();
		int radiusSetting = num(m, "radius", -1);
		double r = (radiusSetting >= 0 ? radiusSetting : style.equals("minimal") ? 6 : 10) * 2 * k;
		double clear = num(m, "clear", 0) / 100.0;
		Color ground = switch (style) {
			case "glass" -> Color.web("#181620");
			case "solid" -> Color.web("#1C1A22");
			case "neon" -> Color.web("#07060A");
			case "glow" -> Color.web("#0C0A0A").interpolate(accent, 0.05);
			default -> Color.web("#0C0A0A");
		};
		double alpha = switch (style) {
			case "glass" -> 0xA8 / 255.0;
			case "solid" -> 1.0;
			default -> 0xF7 / 255.0;
		} * (1 - clear * 0.75);
		Rectangle body = new Rectangle(x, y, w, h);
		body.setArcWidth(r);
		body.setArcHeight(r);
		body.setFill(ground.deriveColor(0, 1, 1, alpha));
		switch (style) {
			case "minimal" -> body.setStroke(Color.rgb(255, 255, 255, 0.05));
			case "glass" -> body.setStroke(Color.rgb(255, 255, 255, 0.22));
			case "glow" -> body.setStroke(accent.deriveColor(0, 1, 1, 0.56));
			case "neon" -> body.setStroke(accent.interpolate(Color.WHITE, 0.25));
			default -> body.setStroke(Color.rgb(255, 255, 255, 0.09));
		}
		body.setStrokeWidth(style.equals("neon") ? 1.4 : 0.8);
		switch (style) {
			case "glow" -> body.setEffect(new javafx.scene.effect.DropShadow(26 * k, accent.deriveColor(0, 1, 1, 0.5)));
			case "neon" -> body.setEffect(new javafx.scene.effect.DropShadow(30 * k, accent.deriveColor(0, 1, 1, 0.75)));
			case "solid" -> body.setEffect(new javafx.scene.effect.DropShadow(24 * k, 0, 8 * k, Color.rgb(0, 0, 0, 0.7)));
			case "minimal" -> {
			}
			default -> body.setEffect(new javafx.scene.effect.DropShadow(18 * k, 0, 5 * k, Color.rgb(0, 0, 0, 0.55)));
		}
		g.getChildren().add(body);
		double header = 22 * k;
		if (style.equals("glass")) {
			Rectangle sheen = rect(x + 1, y + 1, w - 2, Math.min(h, 60 * k), new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
					new Stop(0, Color.rgb(255, 255, 255, 0.08)), new Stop(1, Color.TRANSPARENT)));
			sheen.setArcWidth(r);
			sheen.setArcHeight(r);
			g.getChildren().add(sheen);
		} else if (style.equals("glow") || style.equals("neon")) {
			Rectangle rise = rect(x + 1, y + 1, w - 2, header, new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
					new Stop(0, accent.deriveColor(0, 1, 1, style.equals("neon") ? 0.19 : 0.11)), new Stop(1, Color.TRANSPARENT)));
			rise.setArcWidth(r);
			rise.setArcHeight(r);
			g.getChildren().add(rise);
		}
		if (!style.equals("minimal")) {
			g.getChildren().add(rect(x + 1, y + header, w - 2, 0.6, style.equals("neon") ? accent.deriveColor(0, 1, 1, 0.5)
					: Color.rgb(255, 255, 255, 0.07)));
		}
		Text title = text("Modules", fontNamed("Inter", 8 * k), Color.web("#EDE6E6"));
		title.setX(x + 30 * k);
		title.setY(y + 14 * k);
		g.getChildren().add(title);
		// the rail
		for (int i = 0; i < 6; i++) {
			Color c = i == 0 ? accent.deriveColor(0, 1, 1, 0.85) : Color.rgb(255, 255, 255, 0.10);
			g.getChildren().add(round(x + 8 * k, y + header + 8 * k + i * 16 * k, 12 * k, 12 * k, 6 * k, c, null));
		}
		// cards
		double cx = x + 28 * k;
		double cy = y + header + 8 * k;
		double cw = (w - 28 * k - 8 * k - 12 * k) / 3;
		double ch = Math.min(46 * k, (h - header - 16 * k - 8 * k) / 2);
		for (int row = 0; row < 2; row++) {
			for (int col = 0; col < 3; col++) {
				double px = cx + col * (cw + 6 * k);
				double py = cy + row * (ch + 6 * k);
				Color fill = style.equals("minimal") ? Color.rgb(255, 255, 255, 0.03) : Color.rgb(255, 255, 255, style.equals("glass") ? 0.07 : 0.045);
				g.getChildren().add(round(px, py, cw, ch, 10 * k, fill, style.equals("neon") ? accent.deriveColor(0, 1, 1, 0.25) : null));
				g.getChildren().add(round(px + 6 * k, py + ch - 12 * k, cw * 0.5, 4 * k, 3 * k, Color.rgb(255, 255, 255, 0.25), null));
				boolean on = (row + col) % 2 == 0;
				g.getChildren().add(round(px + cw - 20 * k, py + 6 * k, 14 * k, 8 * k, 8 * k, on ? accent : Color.web("#3A3A42"), null));
			}
		}
	}

	// ------------------------------------------------------------------ settings: pause

	private List<Node> pauseSettings() {
		JsonObject p = section("pause");
		VBox look = SettingsUi.card("Over the game");
		row(look, "Darkness", "100 % is Velo's default", null, slider(p, "dim", 0, 200, 100, "%"));
		row(look, "Tint", null, null, colorPicker(p, "tint", "#08080B"));
		CheckBox own = new CheckBox();
		own.setSelected(num(p, "blur", -1) >= 0);
		Slider blur = new Slider(0, 10, Math.max(0, num(p, "blur", 5)));
		blur.setMajorTickUnit(1);
		blur.setSnapToTicks(true);
		blur.setPrefWidth(120);
		blur.setDisable(!own.isSelected());
		Label blurValue = new Label(own.isSelected() ? String.valueOf(num(p, "blur", 5)) : "Game");
		blurValue.setMinWidth(40);
		own.selectedProperty().addListener((o, a, on) -> {
			blur.setDisable(!on);
			p.addProperty("blur", on ? (int) Math.round(blur.getValue()) : -1);
			blurValue.setText(on ? String.valueOf((int) Math.round(blur.getValue())) : "Game");
			changed();
		});
		blur.valueProperty().addListener((o, a, v) -> {
			if (own.isSelected()) {
				p.addProperty("blur", (int) Math.round(v.doubleValue()));
				blurValue.setText(String.valueOf((int) Math.round(v.doubleValue())));
				changed();
			}
		});
		row(look, "Own blur", "Only the pause menu - your Minecraft setting stays", null, own, blur, blurValue);

		VBox layout = SettingsUi.card("Menu");
		row(layout, "Side", null, null, segmented(p, "align", new String[][] {{"left", "Left"}, {"right", "Right"}}, "left"));
		row(layout, "Panel", null, null, segmented(p, "panel", new String[][] {{"glass", "Glass"}, {"solid", "Solid"}, {"clear", "Clear"}},
				"glass"));
		row(layout, "Cards", "Server, quick toggles, party", null, toggle(p, "cards", true));
		row(layout, "Word colour", null, null, colorPicker(p, "color", "#E4E4E8"));
		row(layout, "Accent", null, null, colorPicker(p, "accent", "#E0262E"));
		return List.of(look, layout, resetCard("pause"));
	}

	// ------------------------------------------------------------------ settings: loading

	private List<Node> loadingSettings() {
		JsonObject l = section("loading");
		VBox bg = SettingsUi.card("Background");
		row(bg, "Background", null, null, segmented(l, "background", new String[][] {{"velo", "Velo"}, {"image", "Your picture"},
				{"color", "Colour"}}, "velo"));
		String mode = str(l, "background", "velo");
		if (mode.equals("image")) {
			row(bg, "Picture", "PNG or JPG (a GIF shows its first frame)", null, pictureButtons("loading"));
			row(bg, "Blur", null, null, slider(l, "blur", 0, 100, 40, "%"));
		} else if (mode.equals("color")) {
			row(bg, "Colour", null, null, colorPicker(l, "color", "#0B0B10"));
		}
		row(bg, "Darken", null, null, slider(l, "dim", 0, 100, 25, "%"));

		VBox middle = SettingsUi.card("Logo and words");
		row(middle, "Logo", null, null, toggle(l, "logo", true));
		row(middle, "Logo size", null, null, sliderF(l, "logoSize", 50, 200, 100));
		TextField caption = new TextField(str(l, "caption", ""));
		caption.setPromptText("Your own words (optional)");
		caption.setPrefWidth(200);
		caption.textProperty().addListener((o, a, v) -> {
			l.addProperty("caption", v.length() > 80 ? v.substring(0, 80) : v);
			changed();
		});
		row(middle, "Line under the logo", null, null, caption);
		row(middle, "Font", null, null, fontBox(l, "captionFont"));
		row(middle, "Size", null, null, sliderF(l, "captionSize", 50, 300, 100));
		row(middle, "Colour", null, null, colorPicker(l, "captionColor", "#FFFFFF"));

		VBox bar = SettingsUi.card("Progress bar");
		row(bar, "Style", null, null, segmented(l, "bar", new String[][] {{"line", "Line"}, {"thick", "Thick"}, {"hidden", "Hidden"}}, "line"));
		row(bar, "Colour", null, null, colorPicker(l, "barColor", "#FFFFFF"));
		return List.of(bg, middle, bar, resetCard("loading"));
	}

	// ------------------------------------------------------------------ settings: launcher

	private List<Node> launcherSettings() {
		VBox card = SettingsUi.card("Home background");
		Label hint = new Label("Your own picture or animated GIF behind the big card on Home. It always covers the whole card - "
				+ "cropped evenly, never stretched. PNG, JPG or GIF up to " + net.veloclient.launcher.data.HomeBackground.MAX_BYTES / 1024 / 1024 + " MB.");
		hint.getStyleClass().add("settings-row-hint");
		hint.setWrapText(true);
		Path current = net.veloclient.launcher.data.HomeBackground.file();
		Button pick = new Button(current == null ? "Choose image or GIF" : "Change");
		pick.getStyleClass().add("primary-button");
		pick.setGraphic(Icons.icon("image", 15, Color.web("#E4E4E8")));
		pick.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Home background");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Images and GIFs", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp", "*.bmp"));
			java.io.File file = chooser.showOpenDialog(host.window());
			if (file == null) {
				return;
			}
			try {
				net.veloclient.launcher.data.HomeBackground.set(file.toPath());
				Toasts.show(pick, "Home background set");
				refresh();
			} catch (Exception ex) {
				Toasts.show(pick, ex.getMessage());
			}
		});
		Button reset = new Button("Use Ridgeline");
		reset.getStyleClass().add("ghost-button");
		reset.setVisible(current != null);
		reset.setManaged(current != null);
		reset.setOnAction(e -> {
			try {
				net.veloclient.launcher.data.HomeBackground.clear();
			} catch (Exception ignored) {
				// nothing to remove
			}
			refresh();
		});
		HBox buttons = new HBox(8, pick, reset);
		buttons.setAlignment(Pos.CENTER_LEFT);
		VBox body = new VBox(12, hint, buttons);
		body.setPadding(new Insets(6, 0, 10, 0));
		card.getChildren().add(body);
		return List.of(card);
	}

	private Node resetCard(String section) {
		Button reset = new Button("Reset this screen");
		reset.getStyleClass().add("ghost-button");
		reset.setOnAction(e -> {
			state.add(section, new JsonObject());
			save();
			refresh();
		});
		HBox box = new HBox(reset);
		box.setAlignment(Pos.CENTER_RIGHT);
		return box;
	}

	/**
	 * A settings row that never squeezes its words: small controls (a switch, a colour) sit right of the label, wide ones
	 * (choices, sliders, a font list) go on their own line under it.
	 */
	private static void row(VBox card, String label, String hint, String details, Node... controls) {
		boolean wide = false;
		for (Node c : controls) {
			if (c.getStyleClass().contains("segment-bar") || c instanceof HBox h && h.getChildren().stream().anyMatch(n -> n instanceof Slider)
					|| c instanceof ComboBox || c instanceof TextField) {
				wide = true;
			}
		}
		if (!wide) {
			SettingsUi.row(card, label, hint, details, controls);
			return;
		}
		Label name = new Label(label);
		name.getStyleClass().add("settings-row-label");
		VBox box = new VBox(6, name);
		if (hint != null && !hint.isBlank()) {
			Label h = new Label(hint);
			h.getStyleClass().add("settings-row-hint");
			h.setWrapText(true);
			box.getChildren().add(h);
		}
		HBox line = new HBox(8, controls);
		line.setAlignment(Pos.CENTER_LEFT);
		for (Node c : controls) {
			if (c instanceof HBox h) {
				for (Node n : h.getChildren()) {
					if (n instanceof Slider slider) {
						slider.setPrefWidth(220);
						HBox.setHgrow(slider, Priority.ALWAYS);
						HBox.setHgrow(h, Priority.ALWAYS);
					}
				}
			}
		}
		box.getChildren().add(line);
		box.getStyleClass().add("settings-row");
		if (details != null) {
			javafx.scene.control.Tooltip tip = new javafx.scene.control.Tooltip(details);
			tip.setWrapText(true);
			tip.setMaxWidth(360);
			javafx.scene.control.Tooltip.install(box, tip);
		}
		card.getChildren().add(box);
	}

	// ------------------------------------------------------------------ controls

	private Node segmented(JsonObject o, String key, String[][] options, String fallback) {
		HBox bar = new HBox();
		bar.getStyleClass().add("segment-bar");
		String current = str(o, key, fallback);
		for (String[] option : options) {
			Button b = new Button(option[1]);
			b.getStyleClass().setAll("button", "segmented-button");
			b.setStyle("-fx-padding: 5 9 5 9; -fx-font-size: 12px;");
			if (option[0].equals(current)) {
				b.getStyleClass().add("segmented-button-active");
			}
			b.setOnAction(e -> {
				o.addProperty(key, option[0]);
				saveLater.playFromStart();
				refresh();
			});
			bar.getChildren().add(b);
		}
		return bar;
	}

	private Node slider(JsonObject o, String key, int min, int max, int fallback, String unit) {
		Slider s = new Slider(min, max, num(o, key, fallback));
		s.setPrefWidth(130);
		Label value = new Label(num(o, key, fallback) + unit);
		value.setMinWidth(44);
		s.valueProperty().addListener((obs, a, v) -> {
			int n = (int) Math.round(v.doubleValue());
			o.addProperty(key, n);
			value.setText(n + unit);
			changed();
		});
		return new HBox(8, s, value);
	}

	/** A slider in percent for a float setting (1.0 = 100 %). */
	private Node sliderF(JsonObject o, String key, int min, int max, int fallback) {
		int current = Math.round(fnum(o, key, fallback / 100f) * 100);
		Slider s = new Slider(min, max, current);
		s.setPrefWidth(130);
		Label value = new Label(current + "%");
		value.setMinWidth(44);
		s.valueProperty().addListener((obs, a, v) -> {
			int n = (int) Math.round(v.doubleValue() / 5) * 5;
			o.addProperty(key, n / 100f);
			value.setText(n + "%");
			changed();
		});
		return new HBox(8, s, value);
	}

	private Node toggle(JsonObject o, String key, boolean fallback) {
		CheckBox box = new CheckBox();
		box.setSelected(bool(o, key, fallback));
		box.selectedProperty().addListener((obs, a, v) -> {
			o.addProperty(key, v);
			changed();
		});
		return box;
	}

	private Node colorPicker(JsonObject o, String key, String fallback) {
		String current = str(o, key, "");
		ColorPicker picker = new VeloColorPicker(Color.web(current.isEmpty() ? fallback : current));
		picker.getStyleClass().add("button");
		picker.setPrefWidth(120);
		Button reset = new Button("Default");
		reset.getStyleClass().add("ghost-button");
		reset.setVisible(!current.isEmpty());
		reset.setManaged(!current.isEmpty());
		picker.valueProperty().addListener((obs, a, c) -> {
			o.addProperty(key, hex(c));
			reset.setVisible(true);
			reset.setManaged(true);
			changed();
		});
		reset.setOnAction(e -> {
			o.remove(key);
			saveLater.playFromStart();
			refresh();
		});
		return new HBox(6, picker, reset);
	}

	private Node fontBox(JsonObject o, String key) {
		ComboBox<String> box = new ComboBox<>();
		box.getItems().addAll(FONTS);
		String fallback = key.equals("captionFont") ? "Inter" : "Archivo";
		box.setValue(str(o, key, fallback));
		box.setPrefWidth(160);
		box.valueProperty().addListener((obs, a, v) -> {
			o.addProperty(key, v);
			changed();
		});
		return box;
	}

	private Node pictureButtons(String slot) {
		Path current = picture(slot);
		Button pick = new Button(current == null ? "Choose..." : "Change");
		pick.getStyleClass().add("primary-button");
		pick.setOnAction(e -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Background picture");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Pictures and GIFs", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
			java.io.File file = chooser.showOpenDialog(host.window());
			if (file == null) {
				return;
			}
			try {
				setPicture(slot, file.toPath());
				pictures.remove(slot);
				refresh();
			} catch (IOException ex) {
				Toasts.show(pick, ex.getMessage());
			}
		});
		return pick;
	}

	// ------------------------------------------------------------------ preview: title

	private void drawTitle() {
		JsonObject t = section("title");
		String align = str(t, "align", "left");
		String background = str(t, "background", "velo");
		background(background.equals("image") ? picture("title") : null, background.equals("vanilla") ? 0 : num(t, "blur", 0), background);
		if (num(t, "dim", 0) > 0) {
			canvas.getChildren().add(rect(0, 0, GW, GH, Color.rgb(0, 0, 0, num(t, "dim", 0) * 2.4 / 255)));
		}
		shade(align, num(t, "scrim", 100) / 100.0, background.equals("vanilla"));

		double margin = clamp(Math.round(GW * 0.065), 14, 72);
		double top = clamp(Math.round(GH * 0.1), 10, 80);
		double right = GW - margin;
		double sizeK = fnum(t, "scale", 1f);
		String font = str(t, "font", "Archivo");
		Color accent = color(t, "accent", Color.web("#E0262E"));
		double y = top;

		if (bool(t, "brand", true)) {
			Group brand = new Group();
			ImageView logo = new ImageView(UiImages.resource("/net/veloclient/launcher/images/logo.png", 44, 44));
			logo.setFitWidth(22);
			logo.setFitHeight(22);
			Text name = text("VELO CLIENT", fontNamed("Archivo", 9), Color.web("#EDE6E6"));
			name.setX(31);
			name.setY(10);
			Text version = text("1.4 · Minecraft 1.21.11", fontNamed("Mono", 5.5), Color.web("#8A8A95"));
			version.setX(31);
			version.setY(19);
			brand.getChildren().addAll(logo, name, version);
			double w = 31 + name.getLayoutBounds().getWidth() + 6;
			place(brand, "brand", alignX(align, w, margin, right), y, w, 22, t);
			y += 22 + 16;
		}
		// the same fitting as the game: rows get tighter, then the card goes, then the whole column shrinks
		double iconSizeF = 18;
		double footerTop = GH - clamp(Math.round(GH * 0.07), 10, 56) - iconSizeF;
		double rowHFit = Math.round(19 * fnum(t, "scale", 1f));
		double minRowH = Math.round(15 * Math.min(1f, fnum(t, "scale", 1f)));
		int mains = 8;
		boolean showCard = bool(t, "jump", true);
		double room = footerTop - 8 - y - (showCard ? 42 : 0);
		while (mains * (rowHFit + 2) > room && rowHFit > minRowH) {
			rowHFit--;
		}
		if (showCard && mains * (rowHFit + 2) > room) {
			showCard = false;
			room += 42;
		}
		double neededH = mains * (rowHFit + 2);
		double columnScale = neededH > room && room > 0 ? Math.max(0.55, room / neededH) : 1;
		if (showCard) {
			Group card = new Group();
			Rectangle box = new Rectangle(220, 30);
			box.setArcWidth(14);
			box.setArcHeight(14);
			box.setFill(accent.deriveColor(0, 1, 1, 0.14));
			box.setStroke(accent.deriveColor(0, 1, 1, 0.4));
			Text kicker = text("JUMP BACK IN", fontNamed("Mono", 5.5), accent.interpolate(Color.WHITE, 0.3));
			kicker.setX(30);
			kicker.setY(12);
			Text server = text("Hypixel Network", fontNamed("Inter", 7.5), Color.web("#EDE6E6"));
			server.setX(30);
			server.setY(22);
			ImageView logo = new ImageView(UiImages.resource("/net/veloclient/launcher/images/logo.png", 36, 36));
			logo.setFitWidth(18);
			logo.setFitHeight(18);
			logo.setX(6);
			logo.setY(6);
			javafx.scene.shape.Circle play = new javafx.scene.shape.Circle(205, 15, 9, accent);
			card.getChildren().addAll(box, logo, kicker, server, play);
			place(card, "card", alignX(align, 220, margin, right), y, 220, 30, t);
			y += 30 + 12;
		}

		// the menu
		String[][] rows = {{"Singleplayer", ""}, {"Multiplayer", ""}, {"Discover", "NEW"}, {"Minecraft Realms", ""}, {"Store", "NEW"},
				{"Mods", ""}, {"Options...", ""}, {"Quit Game", ""}};
		String style = str(t, "buttons", "words");
		boolean boxed = !style.equals("words") && !style.equals("underline");
		double fontSize = (font.equals("Archivo") ? 12 : 12.15) * sizeK;
		Font rowFont = fontNamed(font, fontSize);
		double rowH = rowHFit;
		double widest = 0;
		for (String[] row : rows) {
			widest = Math.max(widest, measure(row[0], rowFont) + (boxed ? 28 : 30) + (row[1].isEmpty() ? 0 : 34));
		}
		Group menu = new Group();
		double menuTop = y;
		double minX = Double.MAX_VALUE;
		double maxX = 0;
		Color own = color(t, "color", null);
		boolean shadow = bool(t, "shadow", false);
		double glow = num(t, "glow", 0) / 100.0;
		for (int i = 0; i < rows.length; i++) {
			String[] row = rows[i];
			double natural = measure(row[0], rowFont) + (boxed ? 28 : 30) + (row[1].isEmpty() ? 0 : 34);
			double w = boxed ? widest : natural;
			double cs = Math.min(columnScale, Math.min(GW - margin * 2, align.equals("center") ? GW - margin * 2 : GW / 2 - margin) / w);
			// each row is drawn at 0,0 in its own group, scaled like the game's column
			Group rowGroup = new Group();
			rowGroup.getTransforms().add(new Scale(cs, cs, 0, 0));
			rowGroup.setLayoutX(alignX(align, w * cs, margin, right) - margin);
			rowGroup.setLayoutY(y - menuTop);
			menu.getChildren().add(rowGroup);
			Group rowsTarget = rowGroup;
			double x = 0;
			double ry = 0;
			Color base = own != null ? own.deriveColor(0, 1, 1, i == 0 ? 1 : i == rows.length - 1 ? 0.6 : 0.8)
					: i == 0 ? Color.web("#EDE6E6") : i == rows.length - 1 ? Color.web("#8E8384") : Color.web("#B3A8A9");
			double r = Math.min(rowH / 2, 8);
			switch (style) {
				case "pills" -> rowsTarget.getChildren().add(round(x, ry, w, rowH, rowH, Color.rgb(255, 255, 255, 0.11), null));
				case "glass" -> rowsTarget.getChildren().add(round(x, ry, w, rowH, r * 2, Color.web("#0C0A0E", 0.72), Color.rgb(255, 255, 255, 0.09)));
				case "outline" -> rowsTarget.getChildren().add(round(x, ry, w, rowH, r * 2, Color.TRANSPARENT, Color.rgb(255, 255, 255, 0.25)));
				default -> {
				}
			}
			double labelW = measure(row[0], rowFont);
			double pad = boxed ? 14 : 0;
			double inner = w - pad * 2 - (row[1].isEmpty() ? 0 : 34);
			double tx = switch (align) {
				case "center" -> x + pad + (inner - labelW) / 2;
				case "right" -> boxed ? x + w - pad - (row[1].isEmpty() ? 0 : 34) - labelW : x + w - 12 - labelW;
				default -> x + pad;
			};
			double baseline = ry + rowH / 2 + fontSize * 0.36;
			Text label = text(row[0], rowFont, base);
			label.setX(tx);
			label.setY(baseline);
			if (glow > 0) {
				Text halo = text(row[0], rowFont, accent.deriveColor(0, 1, 1, Math.min(1, glow * 0.9)));
				halo.setX(tx);
				halo.setY(baseline);
				halo.setEffect(new GaussianBlur(2 + glow * 4));
				rowsTarget.getChildren().add(halo);
			}
			if (shadow) {
				Text drop = text(row[0], rowFont, Color.rgb(0, 0, 0, 0.55));
				drop.setX(tx + 1.2);
				drop.setY(baseline + 1.2);
				rowsTarget.getChildren().add(drop);
			}
			rowsTarget.getChildren().add(label);
			if (style.equals("underline")) {
				double lw = labelW * 0.25;
				double lx = align.equals("center") ? tx + (labelW - lw) / 2 : align.equals("right") ? tx + labelW - lw : tx;
				rowsTarget.getChildren().add(rect(lx, baseline + 3, lw, 1, accent.deriveColor(0, 1, 1, 0.5)));
			}
			if (!row[1].isEmpty()) {
				Text badge = text(row[1], fontNamed("Mono", 5.5), Color.WHITE);
				double bw = measure(row[1], badge.getFont()) + 8;
				double bx = align.equals("right") && !boxed ? tx - 8 - bw : tx + labelW + 8;
				double by = ry + (rowH - 10) / 2;
				rowsTarget.getChildren().add(round(bx, by, bw, 10, 6, accent, null));
				badge.setX(bx + 4);
				badge.setY(by + 7.5);
				rowsTarget.getChildren().add(badge);
			}
			minX = Math.min(minX, rowGroup.getLayoutX());
			maxX = Math.max(maxX, rowGroup.getLayoutX() + w * cs);
			y += Math.round((rowH + 2) * columnScale);
		}
		// the group starts where its widest row starts (so the drag outline hugs the menu)
		for (Node n : menu.getChildren()) {
			n.setTranslateX(-minX);
		}
		place(menu, "menu", margin + minX, menuTop, maxX - minX, y - menuTop, t);

		double footerRight = 0;
		if (bool(t, "footer", true)) {
			double iconSize = 18;
			double footerY = GH - clamp(Math.round(GH * 0.07), 10, 56) - iconSize;
			Group footer = new Group();
			footer.getChildren().add(round(0, 0, iconSize, iconSize, 8, Color.web("#3A7BD5"), accent));
			Text name = text("You", fontNamed("Inter", 7.5), Color.web("#EDE6E6"));
			name.setX(26);
			name.setY(8);
			Text online = text("2 friends online", fontNamed("Inter", 6.5), Color.web("#3DDC97"));
			online.setX(26);
			online.setY(17);
			footer.getChildren().addAll(name, online);
			double fx = 26 + measure("2 friends online", online.getFont()) + 16;
			for (int i = 0; i < 4; i++) {
				footer.getChildren().add(round(fx, 0, iconSize, iconSize, 10, Color.rgb(255, 255, 255, 0.08), Color.rgb(255, 255, 255, 0.09)));
				fx += iconSize + 5;
			}
			double fw = fx - 5;
			place(footer, "footer", alignX(align, fw, margin, right), footerY, fw, iconSize, t);
			footerRight = alignX(align, fw, margin, right) + fw;
		}
		if (bool(t, "store", true)) {
			double scW = 150;
			double scH = 34;
			Group store = new Group();
			store.getChildren().add(round(0, 0, scW, scH, 16, Color.web("#0C0A0A", 0.77), Color.rgb(255, 255, 255, 0.09)));
			store.getChildren().add(round(6, 6, scH - 12, scH - 12, 10, accent.deriveColor(0, 1, 1, 0.18), null));
			Text kicker = text("NEW IN STORE", fontNamed("Mono", 5.5), Color.web("#BCA6FF"));
			kicker.setX(scH + 2);
			kicker.setY(13);
			Text what = text("Wings, cloaks and auras", fontNamed("Inter", 7.5), Color.web("#EDE6E6"));
			what.setX(scH + 2);
			what.setY(24);
			store.getChildren().addAll(kicker, what);
			double sx = align.equals("right") ? margin : GW - margin - scW;
			double sy = GH - clamp(Math.round(GH * 0.07), 10, 56) - scH;
			double menuLeft = margin + minX;
			double menuRight = margin + maxX;
			boolean clear = align.equals("right") ? sx + scW + 20 < menuLeft : sx > Math.max(menuRight, footerRight) + 20;
			if (clear || moved(t, "store")[0] != 0 || moved(t, "store")[1] != 0) {
				place(store, "store", sx, sy, scW, scH, t);
			}
		}
	}

	private static double alignX(String align, double w, double left, double right) {
		return switch (align) {
			case "center" -> (GW - w) / 2;
			case "right" -> right - w;
			default -> left;
		};
	}

	/** Puts a part at its spot plus its saved offset, and makes it draggable. */
	private void place(Group part, String name, double x, double y, double w, double h, JsonObject title) {
		float[] moved = moved(title, name);
		part.setLayoutX(x + moved[0] * GW);
		part.setLayoutY(y + moved[1] * GH);
		Rectangle outline = new Rectangle(-3, -3, w + 6, h + 6);
		outline.setArcWidth(10);
		outline.setArcHeight(10);
		outline.setFill(Color.rgb(255, 255, 255, 0.001));
		outline.setStroke(Color.TRANSPARENT);
		outline.getStrokeDashArray().addAll(3.0, 3.0);
		part.getChildren().add(0, outline);
		part.setCursor(Cursor.MOVE);
		part.setOnMouseEntered(e -> outline.setStroke(Color.rgb(255, 255, 255, 0.55)));
		part.setOnMouseExited(e -> {
			if (dragPart == null) {
				outline.setStroke(Color.TRANSPARENT);
			}
		});
		part.setOnMousePressed(e -> {
			Point2D p = canvas.sceneToLocal(e.getSceneX(), e.getSceneY());
			dragPart = name;
			dragStartX = p.getX();
			dragStartY = p.getY();
			dragFrom = moved.clone();
			e.consume();
		});
		part.setOnMouseDragged(e -> {
			if (!name.equals(dragPart)) {
				return;
			}
			Point2D p = canvas.sceneToLocal(e.getSceneX(), e.getSceneY());
			double dx = dragFrom[0] * GW + p.getX() - dragStartX;
			double dy = dragFrom[1] * GH + p.getY() - dragStartY;
			// snaps back home when close to it
			if (Math.abs(dx) < 4) {
				dx = 0;
			}
			if (Math.abs(dy) < 4) {
				dy = 0;
			}
			part.setLayoutX(x + dx);
			part.setLayoutY(y + dy);
			e.consume();
		});
		part.setOnMouseReleased(e -> {
			if (!name.equals(dragPart)) {
				return;
			}
			dragPart = null;
			setMoved(title, name, (float) ((part.getLayoutX() - x) / GW), (float) ((part.getLayoutY() - y) / GH));
			changed();
		});
		part.setOnMouseClicked(e -> {
			if (e.getClickCount() == 2) {
				setMoved(title, name, 0, 0);
				changed();
			}
		});
		canvas.getChildren().add(part);
	}

	// ------------------------------------------------------------------ preview: pause

	private void drawPause() {
		JsonObject p = section("pause");
		// the "game" behind: the panorama, blurred like Minecraft's menu blur
		int blur = num(p, "blur", -1);
		background(null, blur < 0 ? 50 : blur * 10, "velo");
		boolean right = str(p, "align", "left").equals("right");
		double k = num(p, "dim", 100) / 100.0;
		Color tint = color(p, "tint", Color.web("#08080B"));
		double strong = Math.min(1, 0xD0 / 255.0 * k);
		double weak = Math.min(1, 0x59 / 255.0 * k * Math.max(1, k));
		double split = GW * 0.55;
		Stop[] stops = {new Stop(0, tint.deriveColor(0, 1, 1, right ? weak : strong)), new Stop(1, tint.deriveColor(0, 1, 1, right ? strong : weak))};
		Rectangle grad = rect(right ? GW - split : 0, 0, split, GH, null);
		grad.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE, stops));
		canvas.getChildren().addAll(grad, rect(right ? 0 : split, 0, GW - split, GH, tint.deriveColor(0, 1, 1, weak)));

		double margin = clamp(Math.round(GW * 0.05), 12, 48);
		double top = clamp(Math.round(GH * 0.07), 8, 48);
		double colW = clamp(Math.round(GW * 0.27), 150, 200);
		double colX = right ? GW - margin - colW : margin;
		Color accent = color(p, "accent", Color.web("#E0262E"));
		Color words = color(p, "color", Color.web("#E4E4E8"));
		ImageView logo = new ImageView(UiImages.resource("/net/veloclient/launcher/images/logo.png", 36, 36));
		logo.setFitWidth(18);
		logo.setFitHeight(18);
		logo.setX(colX);
		logo.setY(top);
		Text paused = text("Paused", fontNamed("Archivo", 17), Color.web("#EDE6E6"));
		paused.setX(colX + 24);
		paused.setY(top + 15);
		canvas.getChildren().addAll(logo, paused);
		double y = top + 24;
		canvas.getChildren().add(round(colX, y, colW, 24, 14, accent, null));
		Text back = text("Back to Game", fontNamed("Inter", 7.5), Color.WHITE);
		back.setX(colX + 25);
		back.setY(y + 15);
		canvas.getChildren().add(back);
		y += 32;
		String[] rows = {"Velo menu", "Advancements", "Statistics", "Store", "Options...", "Mods", "Friends", "Report a bug"};
		double h = 8 + rows.length * 16;
		switch (str(p, "panel", "glass")) {
			case "solid" -> canvas.getChildren().add(round(colX, y, colW, h, 18, Color.web("#0E0C10", 0.96), Color.rgb(255, 255, 255, 0.09)));
			case "clear" -> {
			}
			default -> canvas.getChildren().add(round(colX, y, colW, h, 18, Color.web("#0C0A0A", 0.77), Color.rgb(255, 255, 255, 0.09)));
		}
		double ry = y + 4;
		for (String row : rows) {
			canvas.getChildren().add(round(colX + 8, ry + 2, 12, 12, 6, Color.rgb(255, 255, 255, 0.06), null));
			Text label = text(row, fontNamed("Inter", 7.5), words);
			label.setX(colX + 26);
			label.setY(ry + 11);
			canvas.getChildren().add(label);
			ry += 16;
		}
		double leaveY = GH - top - 20;
		canvas.getChildren().add(round(colX, leaveY, colW, 20, 14, Color.TRANSPARENT, Color.web("#FF5A5F", 0.35)));
		Text leave = text("Disconnect", fontNamed("Inter", 7.5), Color.web("#FF7A7E"));
		leave.setX(colX + (colW - measure("Disconnect", leave.getFont())) / 2);
		leave.setY(leaveY + 13);
		canvas.getChildren().add(leave);
		if (bool(p, "cards", true)) {
			double cardW = clamp(Math.round(GW * 0.26), 160, 210);
			double cardX = right ? margin : GW - margin - cardW;
			canvas.getChildren().add(round(cardX, top, cardW, 64, 18, Color.web("#0C0A0A", 0.77), Color.rgb(255, 255, 255, 0.09)));
			Text server = text("Hypixel Network", fontNamed("Inter", 7.5), Color.web("#EDE6E6"));
			server.setX(cardX + 10);
			server.setY(top + 16);
			Text ping = text("32 ms · 12:04 · 240 FPS", fontNamed("Mono", 5.5), Color.web("#8E8384"));
			ping.setX(cardX + 10);
			ping.setY(top + 28);
			canvas.getChildren().addAll(server, ping);
			canvas.getChildren().add(round(cardX, top + 72, cardW, 90, 18, Color.web("#0C0A0A", 0.77), Color.rgb(255, 255, 255, 0.09)));
			for (int i = 0; i < 4; i++) {
				Text toggle = text(new String[] {"Fullbright", "Zoom", "Toggle Sprint", "Keystrokes"}[i], fontNamed("Inter", 7), Color.web("#B3A8A9"));
				toggle.setX(cardX + 10);
				toggle.setY(top + 90 + i * 18);
				canvas.getChildren().add(toggle);
				canvas.getChildren().add(round(cardX + cardW - 26, top + 83 + i * 18, 16, 9, 9, i % 2 == 0 ? accent : Color.web("#3A3A42"), null));
			}
		}
	}

	// ------------------------------------------------------------------ preview: loading

	private void drawLoading() {
		JsonObject l = section("loading");
		String mode = str(l, "background", "velo");
		if (mode.equals("color")) {
			canvas.getChildren().add(rect(0, 0, GW, GH, color(l, "color", Color.web("#0B0B10"))));
		} else if (mode.equals("image") && picture("loading") != null) {
			background(picture("loading"), num(l, "blur", 40), "image");
		} else {
			background(null, 100, "velo");
			canvas.getChildren().add(rect(0, 0, GW, GH, Color.rgb(0, 0, 0, 0.25)));
		}
		int dim = num(l, "dim", 25);
		double extra = mode.equals("velo") ? Math.max(0, dim - 25) : dim;
		if (extra > 0) {
			canvas.getChildren().add(rect(0, 0, GW, GH, Color.rgb(0, 0, 0, extra * 2.4 / 255)));
		}
		boolean logo = bool(l, "logo", true);
		double size = logo ? clamp(GH / 5, 40, 110) * fnum(l, "logoSize", 1f) : 0;
		double cy = logo ? GH / 2 - size / 3 : GH / 2;
		if (logo) {
			ImageView view = new ImageView(UiImages.resource("/net/veloclient/launcher/images/logo.png", 220, 220));
			view.setFitWidth(size);
			view.setFitHeight(size);
			view.setX(GW / 2 - size / 2);
			view.setY(cy - size / 2);
			canvas.getChildren().add(view);
		}
		double barW = clamp(GW / 4, 120, 220);
		double barY = cy + size / 2 + 18;
		String caption = str(l, "caption", "").strip();
		if (!caption.isEmpty()) {
			double px = 12 * fnum(l, "captionSize", 1f);
			Text words = text(caption, fontNamed(str(l, "captionFont", "Inter"), px), color(l, "captionColor", Color.WHITE));
			words.setX(GW / 2 - measure(caption, words.getFont()) / 2);
			words.setY(cy + size / 2 + 8 + px);
			canvas.getChildren().add(words);
			barY = Math.max(barY, cy + size / 2 + 8 + px * 1.4 + 8);
		}
		String bar = str(l, "bar", "line");
		if (!bar.equals("hidden")) {
			double thick = bar.equals("thick") ? 4 : 2;
			Color c = color(l, "barColor", Color.WHITE);
			canvas.getChildren().add(rect(GW / 2 - barW / 2, barY, barW, thick, c.deriveColor(0, 1, 1, 0.19)));
			canvas.getChildren().add(rect(GW / 2 - barW / 2, barY, barW * 0.62, thick, c));
		}
	}

	// ------------------------------------------------------------------ preview: launcher

	private void drawLauncher() {
		Path home = net.veloclient.launcher.data.HomeBackground.file();
		canvas.getChildren().add(rect(0, 0, GW, GH, Color.web("#0C0909")));
		if (home != null) {
			Image image = new Image(home.toUri().toString(), 960, 540, true, true, false);
			ImageView view = cover(image, 24, 24, GW - 48, GH - 48);
			canvas.getChildren().add(view);
		}
		Text hint = text(home == null ? "Velo's Ridgeline (no picture chosen)" : "Your Home background", fontNamed("Inter", 9), Color.web("#EDE6E6"));
		hint.setX(36);
		hint.setY(GH - 40);
		canvas.getChildren().add(hint);
	}

	// ------------------------------------------------------------------ preview helpers

	/** The background: your picture, or the Velo panorama ({@code blur} 0..100). */
	private void background(Path file, int blur, String mode) {
		Image image;
		if (file != null) {
			image = pictures.computeIfAbsent(file.toString() + file.toFile().lastModified(),
					k -> new Image(file.toUri().toString(), 960, 540, true, true, false));
		} else {
			image = pictures.computeIfAbsent("$pano", k -> new Image(CustomizeView.class.getResourceAsStream(
					"/net/veloclient/launcher/images/customize/title_pano.jpg")));
		}
		if (mode.equals("vanilla")) {
			// Minecraft's own panorama isn't shipped with the launcher: a calm stand-in
			canvas.getChildren().add(rect(0, 0, GW, GH, new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
					new Stop(0, Color.web("#6F9BD1")), new Stop(1, Color.web("#3E5A3A")))));
			return;
		}
		ImageView view = cover(image, 0, 0, GW, GH);
		if (blur > 0) {
			view.setEffect(new GaussianBlur(blur / 100.0 * 24));
		}
		canvas.getChildren().add(view);
	}

	private static ImageView cover(Image image, double x, double y, double w, double h) {
		ImageView view = new ImageView(image);
		double iw = Math.max(1, image.getWidth());
		double ih = Math.max(1, image.getHeight());
		double s = Math.max(w / iw, h / ih);
		double vw = w / s;
		double vh = h / s;
		view.setViewport(new javafx.geometry.Rectangle2D((iw - vw) / 2, (ih - vh) / 2, vw, vh));
		view.setFitWidth(w);
		view.setFitHeight(h);
		view.setX(x);
		view.setY(y);
		view.setSmooth(true);
		return view;
	}

	/** Edge fades and the shade behind the menu, on the side it is (like the game). */
	private void shade(String align, double strength, boolean vanilla) {
		canvas.getChildren().add(rect(0, 0, GW, 48, new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
				new Stop(0, Color.rgb(0, 0, 0, 0.33)), new Stop(1, Color.TRANSPARENT))));
		canvas.getChildren().add(rect(0, GH - 120, GW, 120, new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
				new Stop(0, Color.TRANSPARENT), new Stop(1, Color.rgb(0, 0, 0, 0.67)))));
		if (vanilla || strength <= 0) {
			return;
		}
		Color dark = Color.web("#0C0909", 0xD8 / 255.0 * strength);
		Color none = Color.web("#0C0909", 0);
		switch (align) {
			case "right" -> canvas.getChildren().add(rect(0, 0, GW, GH, new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
					new Stop(0.42, none), new Stop(0.78, dark), new Stop(1, dark))));
			case "center" -> canvas.getChildren().add(rect(0, 0, GW, GH, new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
					new Stop(0.16, none), new Stop(0.32, dark.deriveColor(0, 1, 1, 0.8)), new Stop(0.68, dark.deriveColor(0, 1, 1, 0.8)),
					new Stop(0.84, none))));
			default -> canvas.getChildren().add(rect(0, 0, GW, GH, new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
					new Stop(0, dark), new Stop(0.22, dark), new Stop(0.58, none))));
		}
	}

	private static Rectangle rect(double x, double y, double w, double h, javafx.scene.paint.Paint fill) {
		Rectangle r = new Rectangle(x, y, w, h);
		r.setFill(fill);
		return r;
	}

	private static Rectangle round(double x, double y, double w, double h, double arc, Color fill, Color stroke) {
		Rectangle r = new Rectangle(x, y, w, h);
		r.setArcWidth(arc);
		r.setArcHeight(arc);
		r.setFill(fill);
		if (stroke != null) {
			r.setStroke(stroke);
			r.setStrokeWidth(0.8);
		}
		return r;
	}

	private static Text text(String s, Font font, Color color) {
		Text t = new Text(s);
		t.setFont(font);
		t.setFill(color);
		return t;
	}

	private static double measure(String s, Font font) {
		Text t = new Text(s);
		t.setFont(font);
		return t.getLayoutBounds().getWidth();
	}

	/** The bundled font for a name, at {@code size} px. */
	private static Font fontNamed(String name, double size) {
		String file = FONT_FILES.getOrDefault(name, "Inter-SemiBold");
		String fontName = FONT_NAMES.computeIfAbsent(file, f -> {
			try (InputStream in = CustomizeView.class.getResourceAsStream("/net/veloclient/launcher/fonts/" + f + ".ttf")) {
				Font loaded = in == null ? null : Font.loadFont(in, 12);
				return loaded == null ? "System" : loaded.getName();
			} catch (IOException e) {
				return "System";
			}
		});
		return new Font(fontName, size);
	}

	private static double clamp(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}

	// ------------------------------------------------------------------ the file

	/** Dev tours: -Dvelo.screenLook=<file> edits that file (pictures next to it), like the game's flag. */
	private static final String TOUR_FILE = System.getProperty("velo.screenLook");

	private static Path file() {
		return TOUR_FILE != null ? Path.of(TOUR_FILE) : VeloPaths.config().resolve("screens.json");
	}

	private static Path pictureDir() {
		return TOUR_FILE != null ? Path.of(TOUR_FILE).toAbsolutePath().getParent() : VeloPaths.root().resolve("backgrounds");
	}

	/** Dev tour: opens the page on {@code which} tab. */
	public static void tourTab(String which) {
		tab = which;
	}

	private static final String[] TYPES = {"png", "jpg", "jpeg", "gif", "bmp"};

	public static Path picture(String slot) {
		for (String type : TYPES) {
			Path p = pictureDir().resolve(slot + "." + type);
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return null;
	}

	/** Copies a picture in as {@code backgrounds/<slot>.<ext>} (the original can move or go away). */
	private static void setPicture(String slot, Path source) throws IOException {
		String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
		String type = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
		if (!java.util.Arrays.asList(TYPES).contains(type)) {
			throw new IOException("Pick a PNG, JPG or GIF");
		}
		if (Files.size(source) > 40L * 1024 * 1024) {
			throw new IOException("That file is over 40 MB");
		}
		Files.createDirectories(pictureDir());
		for (String t : TYPES) {
			Files.deleteIfExists(pictureDir().resolve(slot + "." + t));
		}
		Files.copy(source, pictureDir().resolve(slot + "." + (type.equals("jpeg") ? "jpg" : type)), StandardCopyOption.REPLACE_EXISTING);
	}

	private static void load() {
		if (state != null) {
			return;
		}
		try {
			state = Files.exists(file()) ? JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
		} catch (Exception e) {
			state = new JsonObject();
		}
		state.addProperty("version", 1);
	}

	/** Written to a temporary file and moved over (the game never reads half a file). */
	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Path tmp = file().resolveSibling("screens.json.tmp");
			Files.writeString(tmp, GSON.toJson(state), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomic) {
				Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			net.veloclient.launcher.LauncherLog.warn("Couldn't save the Customize settings", e);
		}
	}

	private static JsonObject section(String name) {
		JsonElement e = state.get(name);
		if (e == null || !e.isJsonObject()) {
			JsonObject o = new JsonObject();
			state.add(name, o);
			return o;
		}
		return e.getAsJsonObject();
	}

	private static float[] moved(JsonObject title, String part) {
		try {
			JsonArray a = title.getAsJsonObject("moved").getAsJsonArray(part);
			return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat()};
		} catch (Exception e) {
			return new float[] {0, 0};
		}
	}

	private static void setMoved(JsonObject title, String part, float dx, float dy) {
		JsonObject moved = title.has("moved") && title.get("moved").isJsonObject() ? title.getAsJsonObject("moved") : new JsonObject();
		if (dx == 0 && dy == 0) {
			moved.remove(part);
		} else {
			JsonArray a = new JsonArray();
			a.add(Math.round(dx * 1000) / 1000f);
			a.add(Math.round(dy * 1000) / 1000f);
			moved.add(part, a);
		}
		title.add("moved", moved);
	}

	private static String str(JsonObject o, String key, String fallback) {
		try {
			return o.has(key) ? o.get(key).getAsString() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static int num(JsonObject o, String key, int fallback) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static float fnum(JsonObject o, String key, float fallback) {
		try {
			return o.has(key) ? o.get(key).getAsFloat() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static boolean bool(JsonObject o, String key, boolean fallback) {
		try {
			return o.has(key) ? o.get(key).getAsBoolean() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static Color color(JsonObject o, String key, Color fallback) {
		String s = str(o, key, "");
		try {
			return s.isEmpty() ? fallback : Color.web(s);
		} catch (Exception e) {
			return fallback;
		}
	}

	private static String hex(Color c) {
		return String.format(Locale.ROOT, "#%02X%02X%02X", Math.round(c.getRed() * 255), Math.round(c.getGreen() * 255), Math.round(c.getBlue() * 255));
	}
}
