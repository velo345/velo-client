package net.veloclient.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import net.veloclient.launcher.theme.LauncherCustomThemeStore;
import net.veloclient.launcher.theme.LauncherTheme;
import net.veloclient.launcher.theme.LauncherThemePresets;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Themes: built-in and your own on the left, the controls in the middle (accent and background swatches, panel and
 * text colours, corners) and a live miniature launcher on the right. Changing anything on a built-in theme makes an
 * editable copy. Themes can be shared as a short code.
 */
public final class ThemeEditorView {

	/** Lets this view apply/persist a theme change without owning LauncherApp's state directly. */
	public interface Host {
		LauncherTheme activeTheme();

		void setActiveTheme(LauncherTheme theme);

		void rebuild();

		/** The glass launcher's panel darkness, 0..1 (Settings > Appearance has the same slider). */
		double glassDarkness();

		void setGlassDarkness(double value);
	}

	private static final int[] ACCENTS = {0xE0262E, 0xFF7A3D, 0xF7C04A, 0x22C17A, 0x2EC4B6, 0x4F7DFF, 0x8B6CFF, 0xF05AA8, 0xE6E6E6};
	private static final int[] GROUNDS = {0x0B0B0E, 0x090D18, 0x07110D, 0x130C0A, 0x2A303B, 0x0D0D0D, 0x15101C};

	private ThemeEditorView() {
	}

	public static Node build(Host host) {
		Node list = panel(themeList(host), 250);
		Node controls = panel(controls(host), 400);
		Node preview = panel(preview(host.activeTheme(), host.glassDarkness()), -1);
		HBox body = new HBox(16, list, controls, preview);
		HBox.setHgrow(preview, Priority.ALWAYS);
		body.getStyleClass().add("page");
		return body;
	}

	/** One column in a glass card, like every other page of the glass launcher. */
	private static Node panel(Node content, double width) {
		VBox card = new VBox(content);
		card.getStyleClass().add("glass-panel");
		card.setPadding(new Insets(18));
		if (width > 0) {
			card.setMinWidth(width);
			card.setPrefWidth(width);
			card.setMaxWidth(width);
		}
		VBox.setVgrow(content, Priority.ALWAYS);
		return card;
	}

	// ---- Left: the list ----

	private static Node themeList(Host host) {
		Label title = new Label("Themes");
		title.getStyleClass().add("page-title");
		VBox list = new VBox(2, title);
		VBox.setMargin(title, new Insets(0, 0, 12, 0));
		list.setMinWidth(220);
		list.setPrefWidth(230);
		list.getChildren().add(caption("BUILT-IN"));
		for (LauncherTheme t : LauncherThemePresets.all().values()) {
			list.getChildren().add(themeRow(host, t, false));
		}
		list.getChildren().add(caption("YOURS"));
		var customs = LauncherCustomThemeStore.load();
		if (customs.isEmpty()) {
			Label none = new Label("None yet - change anything to make one.");
			none.getStyleClass().add("t-muted");
			none.setWrapText(true);
			none.setPadding(new Insets(2, 10, 6, 10));
			list.getChildren().add(none);
		}
		for (LauncherTheme t : customs) {
			list.getChildren().add(themeRow(host, t, true));
		}
		Button create = new Button("New theme");
		create.setGraphic(Icons.icon("plus", 14, Color.web("#A9A9B3"), 2.2));
		create.getStyleClass().add("dashed-tile");
		create.setMaxWidth(Double.MAX_VALUE);
		create.setOnAction(e -> {
			LauncherCustomThemeStore.create(freeName("My theme"), host.activeTheme()).ifPresent(host::setActiveTheme);
			host.rebuild();
		});
		VBox.setMargin(create, new Insets(8, 0, 0, 0));
		Region grow = new Region();
		VBox.setVgrow(grow, Priority.ALWAYS);
		Button importCode = new Button("Import theme code");
		importCode.setGraphic(Icons.icon("download", 14, Color.web("#E4E4E8")));
		importCode.setMaxWidth(Double.MAX_VALUE);
		importCode.setOnAction(e -> importCode(host, importCode));
		list.getChildren().addAll(create, grow, importCode);
		return list;
	}

	private static Label caption(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("popover-section");
		return label;
	}

	private static Node themeRow(Host host, LauncherTheme t, boolean custom) {
		boolean active = t.name().equals(host.activeTheme().name());
		StackPane bg = dot(t.background(), 18);
		StackPane accent = dot(t.accentStart(), 18);
		accent.setTranslateX(-6);
		HBox palette = new HBox(bg, accent);
		Label name = new Label(t.name());
		name.getStyleClass().add("t-strong");
		name.setStyle("-fx-font-family: '" + (active ? "Geist SemiBold" : "Geist Medium") + "';");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox row = new HBox(8, palette, name, spacer);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("popover-row");
		if (active) {
			row.getStyleClass().add("popover-row-active");
		}
		if (custom) {
			Button delete = new Button();
			delete.setGraphic(Icons.icon("trash", 14, Color.web("#A9A9B3")));
			delete.getStyleClass().addAll("icon-button", "icon-button-flat");
			delete.setMinSize(28, 28);
			delete.setPrefSize(28, 28);
			delete.setTooltip(new Tooltip("Delete theme"));
			delete.setOnAction(e -> {
				LauncherCustomThemeStore.delete(t.name());
				if (active) {
					host.setActiveTheme(LauncherThemePresets.VELO_DARK);
				}
				host.rebuild();
				e.consume();
			});
			row.getChildren().add(delete);
		}
		row.setOnMouseClicked(e -> {
			host.setActiveTheme(t);
			host.rebuild();
		});
		return row;
	}

	private static StackPane dot(int argb, double size) {
		StackPane dot = new StackPane();
		dot.setMinSize(size, size);
		dot.setMaxSize(size, size);
		dot.setStyle("-fx-background-color: " + LauncherTheme.toCssRgba(argb | 0xFF000000) + "; -fx-background-radius: 99;"
				+ " -fx-border-color: rgba(255,255,255,0.18); -fx-border-radius: 99;");
		return dot;
	}

	// ---- Middle: the controls ----

	private static Node controls(Host host) {
		LauncherTheme t = host.activeTheme();
		boolean builtIn = LauncherCustomThemeStore.isBuiltIn(t.name());
		VBox box = new VBox(22);
		box.setPrefWidth(380);
		box.setMinWidth(340);
		box.setMaxWidth(400);

		Node nameRow;
		if (builtIn) {
			Label name = new Label(t.name());
			name.getStyleClass().add("t-title-sm");
			Label hint = new Label("Built-in. Change anything to get your own copy.");
			hint.getStyleClass().add("t-muted");
			nameRow = new VBox(4, name, hint);
		} else {
			TextField name = new TextField(t.name());
			Runnable rename = () -> {
				String wanted = name.getText().trim();
				LauncherTheme current = host.activeTheme();
				if (wanted.isEmpty() || wanted.equals(current.name()) || LauncherCustomThemeStore.isBuiltIn(wanted)
						|| LauncherCustomThemeStore.asMap(LauncherCustomThemeStore.load()).containsKey(wanted)) {
					name.setText(current.name());
					return;
				}
				LauncherCustomThemeStore.delete(current.name());
				LauncherCustomThemeStore.create(wanted, current).ifPresent(host::setActiveTheme);
				host.rebuild();
			};
			name.setOnAction(e -> rename.run());
			name.focusedProperty().addListener((obs, was, is) -> {
				if (!is) {
					rename.run();
				}
			});
			nameRow = labeled("NAME", name);
		}
		box.getChildren().add(nameRow);

		FlowPane accents = new FlowPane(10, 10);
		for (int accent : ACCENTS) {
			accents.getChildren().add(swatch(accent, (t.accentStart() & 0xFFFFFF) == accent, () -> {
				apply(host, x -> copy(x, null, null, 0xFF000000 | accent, 0xFF000000 | darker(accent), null, null, null, null, null));
				host.rebuild();
			}));
		}
		accents.getChildren().add(picker(t.accentStart(), c -> x -> copy(x, null, null, c, 0xFF000000 | darker(c & 0xFFFFFF), null, null, null, null, null), host));
		box.getChildren().add(labeled("ACCENT", accents, LauncherTheme.toCssHex(t.accentStart())));

		// The glass launcher draws its panels as tints of the picture behind them, so the theme's own background and panel
		// colours no longer show anywhere - what is left to choose: accent, text colour, glass darkness, corners, speed.
		box.getChildren().add(labeled("TEXT", colorField("Text colour", t.text(), c -> x -> copy(x, null, null, null, null, c, null, null, null, null), host)));

		Slider darkness = new Slider(0, 1, host.glassDarkness());
		Label darknessValue = new Label(Math.round(darkness.getValue() * 100) + " %");
		darknessValue.getStyleClass().add("t-mono");
		darkness.valueProperty().addListener((o, a1, v) -> darknessValue.setText(Math.round(v.doubleValue() * 100) + " %"));
		darkness.valueChangingProperty().addListener((obs, was, is) -> {
			if (!is) {
				host.setGlassDarkness(darkness.getValue());
				host.rebuild();
			}
		});
		HBox.setHgrow(darkness, Priority.ALWAYS);
		HBox darknessRow = new HBox(12, darkness, darknessValue);
		darknessRow.setAlignment(Pos.CENTER_LEFT);
		box.getChildren().add(labeled("GLASS", darknessRow));
		Label glassHint = new Label("How dark the panels are over the picture behind them. The picture itself is chosen in Settings > Appearance.");
		glassHint.getStyleClass().add("t-muted");
		glassHint.setWrapText(true);
		box.getChildren().add(glassHint);

		HBox corners = new HBox(2);
		corners.getStyleClass().add("segment-bar");
		corners.setMaxWidth(Region.USE_PREF_SIZE);
		Object[][] options = {{"Sharp", 4}, {"Soft", 10}, {"Round", 16}};
		for (Object[] option : options) {
			int radius = (int) option[1];
			Button b = new Button((String) option[0]);
			b.getStyleClass().setAll("button", "segmented-button");
			if (Math.abs(t.cornerRadius() - radius) <= 2) {
				b.getStyleClass().add("segmented-button-active");
			}
			b.setOnAction(e -> {
				apply(host, x -> copy(x, null, null, null, null, null, radius, null, null, null));
				host.rebuild();
			});
			corners.getChildren().add(b);
		}
		box.getChildren().add(labeled("CORNERS", corners, t.cornerRadius() + " px"));

		Slider speed = new Slider(0, 2, t.animationSpeed());
		Label speedValue = new Label(String.format(Locale.ROOT, "%.1fx", t.animationSpeed()));
		speedValue.getStyleClass().add("t-mono");
		speed.valueProperty().addListener((o, a, v) -> speedValue.setText(String.format(Locale.ROOT, "%.1fx", v.doubleValue())));
		speed.valueChangingProperty().addListener((obs, was, is) -> {
			if (!is) {
				apply(host, x -> copy(x, null, null, null, null, null, null, null, (float) speed.getValue(), null));
				host.rebuild();
			}
		});
		HBox.setHgrow(speed, Priority.ALWAYS);
		HBox speedRow = new HBox(12, speed, speedValue);
		speedRow.setAlignment(Pos.CENTER_LEFT);
		box.getChildren().add(labeled("ANIMATION SPEED", speedRow));

		Button share = new Button("Copy theme code");
		share.setGraphic(Icons.icon("share", 15, Color.web("#E4E4E8")));
		share.setOnAction(e -> {
			javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
			content.putString(code(host.activeTheme()));
			javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
			Toasts.show(share, "share", "#5AA9FF", "Theme code copied - send it to a friend");
		});
		box.getChildren().add(share);
		ScrollPane scroll = new ScrollPane(box);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		scroll.setMinWidth(360);
		scroll.setPrefWidth(400);
		scroll.setMaxWidth(420);
		return scroll;
	}

	private static Node labeled(String label, Node control) {
		return labeled(label, control, null);
	}

	private static Node labeled(String label, Node control, String value) {
		Label name = new Label(label);
		name.getStyleClass().add("popover-section");
		name.setPadding(Insets.EMPTY);
		HBox head = new HBox(name);
		if (value != null) {
			Region spacer = new Region();
			HBox.setHgrow(spacer, Priority.ALWAYS);
			Label shown = new Label(value);
			shown.getStyleClass().add("t-mono");
			head.getChildren().addAll(spacer, shown);
		}
		return new VBox(10, head, control);
	}

	private static Node swatch(int rgb, boolean selected, Runnable onClick) {
		StackPane swatch = new StackPane();
		swatch.setMinSize(32, 32);
		swatch.setMaxSize(32, 32);
		String color = String.format("#%06X", rgb);
		swatch.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 99; -fx-cursor: hand; -fx-border-radius: 99;"
				+ (selected ? " -fx-border-color: -velo-text; -fx-border-width: 2; -fx-border-insets: -4;" : " -fx-border-color: rgba(255,255,255,0.18);"));
		swatch.getStyleClass().add("motion-card");
		swatch.setOnMouseClicked(e -> onClick.run());
		return swatch;
	}

	private static Node picker(int argb, java.util.function.IntFunction<UnaryOperator<LauncherTheme>> change, Host host) {
		ColorPicker picker = new VeloColorPicker(Color.web(LauncherTheme.toCssHex(argb)));
		picker.getStyleClass().addAll("button", "swatch-picker");
		picker.setStyle("-fx-color-label-visible: false;");
		picker.setTooltip(new Tooltip("Any colour"));
		picker.setOnAction(e -> {
			apply(host, change.apply(toArgb(picker.getValue())));
			host.rebuild();
		});
		return picker;
	}

	private static Node colorField(String label, int argb, java.util.function.IntFunction<UnaryOperator<LauncherTheme>> change, Host host) {
		Label name = new Label(label);
		name.getStyleClass().add("t-body");
		Label hex = new Label(LauncherTheme.toCssHex(argb));
		hex.getStyleClass().add("t-mono");
		HBox row = new HBox(10, picker(argb, change, host), new VBox(1, name, hex));
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	// ---- Right: a miniature launcher in the theme ----

	/** The launcher as the glass design draws it: rail, hero, tiles and Play bar as see-through panels over a picture. */
	private static Node preview(LauncherTheme t, double darknessValue) {
		int radius = Math.max(6, t.cornerRadius() + 6);
		int accentRgb = t.accentStart() & 0xFFFFFF;
		String accent = css(t.accentStart());
		String text = css(t.text());
		String muted = LauncherTheme.toCssRgba((t.text() & 0xFFFFFF) | 0x99000000);
		String onAccent = t.onAccent() == 0xFFFFFFFF ? "white" : "#111111";
		// the picture: the accent's colour sinking into black (a real backdrop is a pixel scene or the player's own picture)
		int deep = mix(accentRgb, 0x000000, 0.80);
		int mid = mix(accentRgb, 0x000000, 0.90);
		double alpha = 0.18 + darknessValue * 0.5;
		String glass = String.format(Locale.ROOT, "rgba(%d,%d,%d,%.2f)", (mid >> 16) & 0xFF, (mid >> 8) & 0xFF, mid & 0xFF, Math.min(0.9, alpha + 0.2));
		String edge = "rgba(255,255,255,0.16)";

		VBox rail = new VBox(10);
		rail.setAlignment(Pos.TOP_CENTER);
		rail.setPadding(new Insets(12, 0, 12, 0));
		rail.setMinWidth(46);
		rail.setMaxWidth(46);
		rail.setStyle("-fx-background-color: " + glass + "; -fx-background-radius: " + radius + "; -fx-border-color: " + edge + "; -fx-border-radius: " + radius + ";");
		String[] icons = {"home", "layers", "hanger", "store", "friends"};
		for (int i = 0; i < icons.length; i++) {
			StackPane icon = new StackPane(Icons.icon(icons[i], 15, Color.web(i == 0 ? css6(t.text()) : "#9A9AA6"), 2.0));
			icon.setMinSize(32, 32);
			icon.setMaxSize(32, 32);
			if (i == 0) {
				icon.setStyle("-fx-background-color: " + LauncherTheme.toCssRgba(accentRgb | 0x38000000) + "; -fx-background-radius: 10;");
			}
			rail.getChildren().add(icon);
		}

		StackPane hero = new StackPane();
		hero.setMinHeight(130);
		hero.setStyle("-fx-background-color: " + glass + "; -fx-background-radius: " + radius + "; -fx-border-color: " + edge + "; -fx-border-radius: " + radius + ";");
		Label over = new Label("CONTINUE PLAYING");
		over.setStyle("-fx-text-fill: " + accent + "; -fx-font-size: 9px; -fx-font-weight: bold;");
		Label name = new Label("AsteriaSMP");
		name.setStyle("-fx-text-fill: " + text + "; -fx-font-family: 'Archivo SemiExpanded'; -fx-font-weight: bold; -fx-font-size: 22px;");
		VBox heroText = new VBox(4, over, name);
		heroText.setAlignment(Pos.BOTTOM_LEFT);
		heroText.setPadding(new Insets(14));
		hero.getChildren().add(heroText);

		HBox tiles = new HBox(10);
		for (int i = 0; i < 3; i++) {
			Region tile = new Region();
			tile.setMinHeight(60);
			HBox.setHgrow(tile, Priority.ALWAYS);
			tile.setStyle("-fx-background-color: " + glass + "; -fx-background-radius: " + radius + "; -fx-border-radius: " + radius + ";"
					+ " -fx-border-color: " + (i == 2 ? accent : edge) + ";");
			tiles.getChildren().add(tile);
		}
		Label chip = new Label("Velo+");
		chip.setStyle("-fx-background-color: " + LauncherTheme.toCssRgba(accentRgb | 0x2E000000) + "; -fx-text-fill: " + accent
				+ "; -fx-background-radius: 99; -fx-padding: 4 10 4 10; -fx-font-size: 11px; -fx-font-weight: bold;");
		Label chip2 = new Label("Fabric 26.2");
		chip2.setStyle("-fx-background-color: rgba(255,255,255,0.10); -fx-text-fill: " + muted + "; -fx-background-radius: 99; -fx-padding: 4 10 4 10; -fx-font-size: 11px;");

		Label play = new Label("PLAY");
		play.setStyle("-fx-background-color: " + accent + "; -fx-text-fill: " + onAccent + "; -fx-background-radius: 99"
				+ "; -fx-padding: 9 30 9 30; -fx-font-family: 'Archivo SemiExpanded'; -fx-font-weight: bold; -fx-font-size: 12px;");
		Label profile = new Label("AsteriaSMP");
		profile.setStyle("-fx-text-fill: " + text + "; -fx-font-size: 12px; -fx-font-weight: bold;");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox bar = new HBox(10, profile, spacer, play);
		bar.setAlignment(Pos.CENTER_LEFT);
		bar.setPadding(new Insets(8, 10, 8, 16));
		bar.setStyle("-fx-background-color: " + glass + "; -fx-background-radius: 99; -fx-border-color: " + edge + "; -fx-border-radius: 99;");

		VBox main = new VBox(12, hero, tiles, new HBox(8, chip, chip2));
		VBox.setVgrow(main, Priority.ALWAYS);
		VBox right = new VBox(12, main, bar);
		HBox.setHgrow(right, Priority.ALWAYS);
		HBox mock = new HBox(12, rail, right);
		mock.setPadding(new Insets(14));
		mock.setStyle("-fx-background-color: linear-gradient(to bottom right, " + hex(deep) + " 0%, " + hex(mid) + " 55%, #07060a 100%);"
				+ " -fx-background-radius: 16; -fx-border-color: rgba(255,255,255,0.10); -fx-border-radius: 16;");
		mock.setMinHeight(400);
		mock.setMaxHeight(470);
		Label title = new Label("LIVE PREVIEW");
		title.getStyleClass().add("popover-section");
		title.setPadding(Insets.EMPTY);
		VBox column = new VBox(12, title, mock);
		column.setMinWidth(320);
		return column;
	}

	private static String hex(int rgb) {
		return String.format("#%06X", rgb & 0xFFFFFF);
	}

	private static String css6(int argb) {
		return hex(argb);
	}

	private static String css(int argb) {
		return LauncherTheme.toCssRgba(argb | 0xFF000000);
	}

	// ---- Theme codes ----

	/** A compact, pasteable code: "VELO1:" + base64 of the theme's fields. */
	static String code(LauncherTheme t) {
		String raw = String.join(";", t.name().replace(";", ","), Integer.toHexString(t.background()), Integer.toHexString(t.surface()),
				Integer.toHexString(t.accentStart()), Integer.toHexString(t.accentEnd()), Integer.toHexString(t.text()),
				String.valueOf(t.cornerRadius()), String.valueOf(t.blurIntensity()), String.valueOf(t.animationSpeed()), String.valueOf(t.panelOpacity()));
		return "VELO1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	static LauncherTheme decode(String code) {
		String text = code.strip();
		if (!text.startsWith("VELO1:")) {
			return null;
		}
		try {
			String[] p = new String(Base64.getUrlDecoder().decode(text.substring(6)), StandardCharsets.UTF_8).split(";");
			return new LauncherTheme(p[0], (int) Long.parseLong(p[1], 16), (int) Long.parseLong(p[2], 16), (int) Long.parseLong(p[3], 16),
					(int) Long.parseLong(p[4], 16), (int) Long.parseLong(p[5], 16), Integer.parseInt(p[6]), Float.parseFloat(p[7]),
					Float.parseFloat(p[8]), Float.parseFloat(p[9]));
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void importCode(Host host, Node anchor) {
		TextInputDialog dialog = new TextInputDialog();
		dialog.setTitle("Import theme");
		dialog.setHeaderText("Paste a theme code (starts with VELO1:)");
		DialogStyling.apply(dialog);
		dialog.showAndWait().ifPresent(code -> {
			LauncherTheme decoded = decode(code);
			if (decoded == null) {
				Toasts.show(anchor, "warning", "#FF7A7E", "That isn't a theme code");
				return;
			}
			LauncherCustomThemeStore.create(freeName(decoded.name()), decoded).ifPresent(host::setActiveTheme);
			host.rebuild();
		});
	}

	// ---- Editing ----

	/** Applies an edit to the active theme - copying a built-in theme into an editable one first. */
	private static void apply(Host host, UnaryOperator<LauncherTheme> edit) {
		LauncherTheme current = host.activeTheme();
		if (LauncherCustomThemeStore.isBuiltIn(current.name())) {
			current = LauncherCustomThemeStore.create(freeName(current.name() + " (mine)"), current).orElse(current);
		}
		LauncherTheme updated = edit.apply(current);
		LauncherCustomThemeStore.update(updated);
		host.setActiveTheme(updated);
	}

	private static String freeName(String base) {
		var taken = LauncherCustomThemeStore.asMap(LauncherCustomThemeStore.load()).keySet();
		String name = base;
		for (int n = 2; taken.contains(name) || LauncherCustomThemeStore.isBuiltIn(name); n++) {
			name = base + " " + n;
		}
		return name;
	}

	private static int toArgb(Color c) {
		return 0xFF000000 | ((int) Math.round(c.getRed() * 255) << 16) | ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255);
	}

	private static int mix(int rgb, int other, double t) {
		int r = (int) Math.round(((rgb >> 16) & 0xFF) * (1 - t) + ((other >> 16) & 0xFF) * t);
		int g = (int) Math.round(((rgb >> 8) & 0xFF) * (1 - t) + ((other >> 8) & 0xFF) * t);
		int b = (int) Math.round((rgb & 0xFF) * (1 - t) + (other & 0xFF) * t);
		return (r << 16) | (g << 8) | b;
	}

	private static int darker(int rgb) {
		int r = (int) (((rgb >> 16) & 0xFF) * 0.86);
		int g = (int) (((rgb >> 8) & 0xFF) * 0.86);
		int b = (int) ((rgb & 0xFF) * 0.86);
		return (r << 16) | (g << 8) | b;
	}

	private static int lift(int rgb, int amount) {
		int r = Math.min(255, ((rgb >> 16) & 0xFF) + amount);
		int g = Math.min(255, ((rgb >> 8) & 0xFF) + amount);
		int b = Math.min(255, (rgb & 0xFF) + amount);
		return (r << 16) | (g << 8) | b;
	}

	private static LauncherTheme copy(LauncherTheme t, Integer background, Integer surface, Integer accentStart, Integer accentEnd,
			Integer text, Integer radius, Float blur, Float speed, Float opacity) {
		return new LauncherTheme(t.name(), background != null ? background : t.background(), surface != null ? surface : t.surface(),
				accentStart != null ? accentStart : t.accentStart(), accentEnd != null ? accentEnd : t.accentEnd(),
				text != null ? text : t.text(), radius != null ? radius : t.cornerRadius(), blur != null ? blur : t.blurIntensity(),
				speed != null ? speed : t.animationSpeed(), opacity != null ? opacity : t.panelOpacity());
	}
}
