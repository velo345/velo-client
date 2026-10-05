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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import net.veloclient.launcher.theme.LauncherCustomThemeStore;
import net.veloclient.launcher.theme.LauncherTheme;
import net.veloclient.launcher.theme.LauncherThemePresets;

import java.util.Locale;
import java.util.function.UnaryOperator;

/**
 * Launcher themes on one page, laid out like the in-game editor: the theme list on the left
 * (click to use it), and on the right a live preview plus every setting of the active theme -
 * colors as swatches with their hex code, and sliders. Changes apply to the whole launcher right
 * away. Changing a built-in theme makes an editable copy first, so presets are never lost.
 */
public final class ThemeEditorView {

	/** Lets this view apply/persist a theme change without owning LauncherApp's state directly. */
	public interface Host {
		LauncherTheme activeTheme();

		void setActiveTheme(LauncherTheme theme);

		void rebuild();
	}

	private ThemeEditorView() {
	}

	public static Node build(Host host) {
		Label title = new Label("Themes");
		title.getStyleClass().add("page-title");
		Label subtitle = new Label("Pick a theme, or tweak colors and see the whole launcher change live.");
		subtitle.getStyleClass().add("page-subtitle");
		VBox header = new VBox(4, title, subtitle);

		HBox body = new HBox(22, themeList(host), editor(host));
		HBox.setHgrow(body.getChildren().get(1), Priority.ALWAYS);

		VBox root = new VBox(20, header, body);
		root.setPadding(new Insets(0, 4, 20, 0));
		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox wrapper = new VBox(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		return wrapper;
	}

	// ---- Left: the list ----

	private static Node themeList(Host host) {
		VBox list = new VBox(6);
		list.getStyleClass().add("theme-list");
		list.setMinWidth(230);
		list.setPrefWidth(230);
		list.getChildren().add(caption("BUILT-IN"));
		for (LauncherTheme t : LauncherThemePresets.all().values()) {
			list.getChildren().add(themeRow(host, t, false));
		}
		list.getChildren().add(caption("YOUR THEMES"));
		var customs = LauncherCustomThemeStore.load();
		if (customs.isEmpty()) {
			Label none = new Label("None yet - change any color to make one.");
			none.getStyleClass().add("settings-row-hint");
			none.setWrapText(true);
			list.getChildren().add(none);
		}
		for (LauncherTheme t : customs) {
			list.getChildren().add(themeRow(host, t, true));
		}
		Button create = new Button("+  New theme");
		create.getStyleClass().add("primary-button");
		create.setMaxWidth(Double.MAX_VALUE);
		create.setOnAction(e -> {
			LauncherCustomThemeStore.create(freeName("My Theme"), host.activeTheme()).ifPresent(host::setActiveTheme);
			host.rebuild();
		});
		VBox.setMargin(create, new Insets(8, 0, 0, 0));
		list.getChildren().add(create);
		return list;
	}

	private static Label caption(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("theme-caption");
		return label;
	}

	private static Node themeRow(Host host, LauncherTheme t, boolean custom) {
		boolean active = t.name().equals(host.activeTheme().name());
		HBox palette = new HBox(0, chip(t.background(), "6 0 0 6"), chip(t.surface(), "0"), gradientChip(t));
		palette.setMinWidth(54);
		Label name = new Label(t.name());
		name.getStyleClass().add("theme-row-name");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox row = new HBox(10, palette, name, spacer);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("theme-row");
		if (active) {
			row.getStyleClass().add("theme-row-active");
		}
		if (custom) {
			Button delete = new Button("✕");
			delete.getStyleClass().add("theme-row-delete");
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

	private static Region chip(int argb, String radius) {
		Region r = new Region();
		r.setPrefSize(18, 18);
		r.setStyle("-fx-background-color: " + LauncherTheme.toCssRgba(argb | 0xFF000000) + "; -fx-background-radius: " + radius + ";");
		return r;
	}

	private static Region gradientChip(LauncherTheme t) {
		Region r = new Region();
		r.setPrefSize(18, 18);
		r.setStyle("-fx-background-color: linear-gradient(to bottom right, " + LauncherTheme.toCssRgba(t.accentStart() | 0xFF000000) + ", "
				+ LauncherTheme.toCssRgba(t.accentEnd() | 0xFF000000) + "); -fx-background-radius: 0 6 6 0;");
		return r;
	}

	// ---- Right: preview + settings ----

	private static Node editor(Host host) {
		LauncherTheme t = host.activeTheme();
		boolean builtIn = LauncherCustomThemeStore.isBuiltIn(t.name());

		Node nameRow;
		if (builtIn) {
			Label name = new Label(t.name());
			name.getStyleClass().add("section-label");
			Label hint = new Label("Built-in - change anything below to get your own editable copy.");
			hint.getStyleClass().add("settings-row-hint");
			nameRow = new VBox(2, name, hint);
		} else {
			TextField name = new TextField(t.name());
			name.getStyleClass().add("theme-name-field");
			name.setPrefWidth(260);
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
			nameRow = name;
		}

		GridPane colors = new GridPane();
		colors.setHgap(12);
		colors.setVgap(10);
		colors.add(colorRow(host, "Background", t.background(), c -> x -> copy(x, c, null, null, null, null, null, null, null, null)), 0, 0);
		colors.add(colorRow(host, "Panels", t.surface(), c -> x -> copy(x, null, c, null, null, null, null, null, null, null)), 1, 0);
		colors.add(colorRow(host, "Accent", t.accentStart(), c -> x -> copy(x, null, null, c, null, null, null, null, null, null)), 0, 1);
		colors.add(colorRow(host, "Accent fade", t.accentEnd(), c -> x -> copy(x, null, null, null, c, null, null, null, null, null)), 1, 1);
		colors.add(colorRow(host, "Font color", t.text(), c -> x -> copy(x, null, null, null, null, c, null, null, null, null)), 0, 2);

		GridPane sliders = new GridPane();
		sliders.setHgap(18);
		sliders.setVgap(10);
		sliders.add(sliderRow(host, "Corner radius", 0, 16, t.cornerRadius(), v -> Math.round(v) + " px",
				v -> x -> copy(x, null, null, null, null, null, (int) Math.round(v), null, null, null)), 0, 0);
		sliders.add(sliderRow(host, "Panel opacity", 0.3, 1, t.panelOpacity(), v -> Math.round(v * 100) + "%",
				v -> x -> copy(x, null, null, null, null, null, null, null, null, (float) v)), 1, 0);
		sliders.add(sliderRow(host, "Background blur", 0, 1, t.blurIntensity(), v -> Math.round(v * 100) + "%",
				v -> x -> copy(x, null, null, null, null, null, null, (float) v, null, null)), 0, 1);
		sliders.add(sliderRow(host, "Animation speed", 0, 2, t.animationSpeed(), v -> String.format(Locale.ROOT, "%.1fx", v),
				v -> x -> copy(x, null, null, null, null, null, null, null, (float) v, null)), 1, 1);
		for (GridPane grid : new GridPane[] {colors, sliders}) {
			for (int i = 0; i < 2; i++) {
				var column = new javafx.scene.layout.ColumnConstraints();
				column.setPercentWidth(50);
				grid.getColumnConstraints().add(column);
			}
		}

		VBox editor = new VBox(16, nameRow, preview(t), section("Colors"), colors, section("Look & feel"), sliders);
		editor.getStyleClass().add("glass-panel");
		editor.setPadding(new Insets(18));
		return editor;
	}

	private static Label section(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("section-label");
		return label;
	}

	/** A miniature launcher drawn in the theme's own colors. */
	private static Node preview(LauncherTheme t) {
		String radius = String.valueOf(Math.max(4, t.cornerRadius() + 2));
		String text = LauncherTheme.toCssRgba(t.text() | 0xFF000000);
		String muted = LauncherTheme.toCssRgba((t.text() & 0xFFFFFF) | 0x99000000);
		VBox sidebar = new VBox(6);
		sidebar.setPadding(new Insets(12));
		sidebar.setPrefWidth(120);
		sidebar.setStyle("-fx-background-color: " + LauncherTheme.toCssRgba(t.surface() | 0xFF000000) + "; -fx-background-radius: " + radius + " 0 0 " + radius + ";");
		String[] items = {"Home", "Servers", "Cosmetics", "Settings"};
		for (int i = 0; i < items.length; i++) {
			Label item = new Label(items[i]);
			item.setMaxWidth(Double.MAX_VALUE);
			item.setPadding(new Insets(5, 8, 5, 8));
			item.setStyle("-fx-text-fill: " + (i == 0 ? text : muted) + "; -fx-font-size: 12px;"
					+ (i == 0 ? "-fx-background-color: " + LauncherTheme.toCssRgba((t.accentStart() & 0xFFFFFF) | 0x33000000)
							+ "; -fx-background-radius: 6;" : ""));
			sidebar.getChildren().add(item);
		}
		Label heading = new Label("Welcome back");
		heading.setStyle("-fx-text-fill: " + text + "; -fx-font-size: 16px; -fx-font-weight: bold;");
		Label sub = new Label("This is how your launcher looks.");
		sub.setStyle("-fx-text-fill: " + muted + "; -fx-font-size: 12px;");
		Label card = new Label("Profile card");
		card.setMaxWidth(Double.MAX_VALUE);
		card.setPadding(new Insets(14));
		card.setStyle("-fx-text-fill: " + text + "; -fx-background-color: " + LauncherTheme.toCssRgba(t.surface() | 0xFF000000)
				+ "; -fx-background-radius: " + radius + ";");
		Label button = new Label("Play");
		button.setPadding(new Insets(8, 26, 8, 26));
		button.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: " + radius + "; -fx-background-color: linear-gradient(to right, "
				+ LauncherTheme.toCssRgba(t.accentStart() | 0xFF000000) + ", " + LauncherTheme.toCssRgba(t.accentEnd() | 0xFF000000) + ");");
		VBox main = new VBox(8, heading, sub, card, button);
		main.setPadding(new Insets(14));
		HBox.setHgrow(main, Priority.ALWAYS);
		HBox mock = new HBox(sidebar, main);
		mock.setMinHeight(170);
		mock.setStyle("-fx-background-color: " + LauncherTheme.toCssRgba(t.background() | 0xFF000000) + "; -fx-background-radius: " + radius + ";");
		return mock;
	}

	private static Node colorRow(Host host, String label, int argb, java.util.function.IntFunction<UnaryOperator<LauncherTheme>> change) {
		ColorPicker picker = new ColorPicker(Color.web(LauncherTheme.toCssHex(argb)));
		picker.getStyleClass().addAll("button", "swatch-picker");
		picker.setStyle("-fx-color-label-visible: false;");
		Label name = new Label(label);
		name.getStyleClass().add("theme-color-name");
		Label hex = new Label(String.format(Locale.ROOT, "#%06X", argb & 0xFFFFFF));
		hex.getStyleClass().add("theme-color-hex");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		HBox row = new HBox(10, picker, name, spacer, hex);
		row.setAlignment(Pos.CENTER_LEFT);
		row.getStyleClass().add("theme-color-row");
		picker.setOnAction(e -> {
			Color c = picker.getValue();
			int picked = 0xFF000000 | ((int) Math.round(c.getRed() * 255) << 16) | ((int) Math.round(c.getGreen() * 255) << 8)
					| (int) Math.round(c.getBlue() * 255);
			apply(host, change.apply(picked));
			host.rebuild();
		});
		row.setOnMouseClicked(e -> picker.show());
		return row;
	}

	private static Node sliderRow(Host host, String label, double min, double max, double value,
			java.util.function.DoubleFunction<String> format, java.util.function.DoubleFunction<UnaryOperator<LauncherTheme>> change) {
		Label name = new Label(label);
		name.getStyleClass().add("theme-color-name");
		Label shown = new Label(format.apply(value));
		shown.getStyleClass().add("theme-color-hex");
		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		Slider slider = new Slider(min, max, value);
		slider.setMaxWidth(Double.MAX_VALUE);
		slider.valueProperty().addListener((obs, was, now) -> shown.setText(format.apply(now.doubleValue())));
		slider.valueChangingProperty().addListener((obs, was, is) -> {
			if (!is) {
				apply(host, change.apply(slider.getValue()));
				host.rebuild();
			}
		});
		slider.setOnMouseReleased(e -> {
			if (!slider.isValueChanging()) {
				apply(host, change.apply(slider.getValue()));
				host.rebuild();
			}
		});
		return new VBox(4, new HBox(name, spacer, shown), slider);
	}

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

	private static LauncherTheme copy(LauncherTheme t, Integer background, Integer surface, Integer accentStart, Integer accentEnd,
			Integer text, Integer radius, Float blur, Float speed, Float opacity) {
		return new LauncherTheme(t.name(), background != null ? background : t.background(), surface != null ? surface : t.surface(),
				accentStart != null ? accentStart : t.accentStart(), accentEnd != null ? accentEnd : t.accentEnd(),
				text != null ? text : t.text(), radius != null ? radius : t.cornerRadius(), blur != null ? blur : t.blurIntensity(),
				speed != null ? speed : t.animationSpeed(), opacity != null ? opacity : t.panelOpacity());
	}
}
