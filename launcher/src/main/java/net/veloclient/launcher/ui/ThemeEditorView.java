package net.veloclient.launcher.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import net.veloclient.launcher.theme.LauncherCustomThemeStore;
import net.veloclient.launcher.theme.LauncherTheme;
import net.veloclient.launcher.theme.LauncherThemePresets;
import net.veloclient.launcher.theme.ThemeStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Its own sidebar tab (not folded into Cosmetics): browse built-in presets
 * and your own custom themes, create a new one by cloning whatever's active
 * (mirrors the in-game mod's {@code ThemeManager.createCustomTheme}), and
 * edit any custom theme's full color/slider set right here - persisted for
 * real via {@link LauncherCustomThemeStore} (previously, edits to the active
 * theme were silently discarded the moment a different theme was picked).
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
		VBox root = new VBox(20);

		Label heading = new Label("Theme Editor");
		heading.getStyleClass().add("section-heading");
		heading.setTextFill(accent(host.activeTheme()));
		root.getChildren().add(heading);

		HBox createRow = new HBox(8);
		createRow.setAlignment(Pos.CENTER_LEFT);
		TextField nameField = new TextField();
		nameField.setPromptText("New theme name");
		Button createButton = new Button("Create From Current");
		createButton.getStyleClass().add("title-menu-button");
		createButton.setOnAction(e -> LauncherCustomThemeStore.create(nameField.getText().trim(), host.activeTheme())
				.ifPresentOrElse(created -> {
					host.setActiveTheme(created);
					host.rebuild();
				}, () -> new Alert(Alert.AlertType.WARNING, "Give it a unique name that isn't already used.").showAndWait()));
		createRow.getChildren().addAll(nameField, createButton);
		root.getChildren().add(createRow);

		FlowPane grid = new FlowPane(14, 14);
		Map<String, LauncherTheme> all = new LinkedHashMap<>(LauncherThemePresets.all());
		all.putAll(LauncherCustomThemeStore.asMap(LauncherCustomThemeStore.load()));
		for (LauncherTheme t : all.values()) {
			grid.getChildren().add(buildThemeCard(host, t, LauncherCustomThemeStore.isBuiltIn(t.name())));
		}
		root.getChildren().add(grid);

		if (!LauncherCustomThemeStore.isBuiltIn(host.activeTheme().name())) {
			root.getChildren().add(buildThemeEditor(host));
		}

		ScrollPane scroll = new ScrollPane(root);
		scroll.setFitToWidth(true);
		scroll.getStyleClass().add("scroll-pane");
		VBox wrapper = new VBox(scroll);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		VBox.setVgrow(wrapper, Priority.ALWAYS);
		return wrapper;
	}

	private static VBox buildThemeCard(Host host, LauncherTheme t, boolean builtin) {
		boolean active = t.name().equals(host.activeTheme().name());
		VBox card = new VBox(8);
		card.getStyleClass().add("instance-card");
		card.setPrefWidth(150);
		card.setAlignment(Pos.TOP_CENTER);

		Region swatch = new Region();
		swatch.setPrefSize(70, 44);
		swatch.setStyle("-fx-background-radius: 8; -fx-background-color: linear-gradient(to bottom right, "
				+ LauncherTheme.toCssRgba(t.accentStart()) + ", " + LauncherTheme.toCssRgba(t.accentEnd()) + "), "
				+ LauncherTheme.toCssRgba(t.background()) + "; -fx-background-insets: 0, 6;");

		Label name = new Label(t.name() + (active ? "  ✓" : ""));
		name.setTextFill(active ? accent(host.activeTheme()) : text(host.activeTheme()));
		name.setFont(Font.font("System", FontWeight.BOLD, 13));
		name.setWrapText(true);

		card.getChildren().addAll(swatch, name);

		if (active) {
			Label activeLabel = new Label(builtin ? "Active" : "Active - editable below");
			activeLabel.getStyleClass().add("version-tag");
			activeLabel.setTextFill(text(host.activeTheme()));
			card.getChildren().add(activeLabel);
		} else {
			Button activateButton = new Button(builtin ? "Activate" : "Activate & Edit");
			activateButton.setMaxWidth(Double.MAX_VALUE);
			activateButton.setOnAction(e -> {
				host.setActiveTheme(t);
				ThemeStore.save(t);
				host.rebuild();
			});
			card.getChildren().add(activateButton);
		}

		if (!builtin) {
			Button deleteButton = new Button("Delete");
			deleteButton.setMaxWidth(Double.MAX_VALUE);
			deleteButton.setOnAction(e -> {
				LauncherCustomThemeStore.delete(t.name());
				if (active) {
					host.setActiveTheme(LauncherThemePresets.VELO_DARK);
					ThemeStore.save(LauncherThemePresets.VELO_DARK);
				}
				host.rebuild();
			});
			card.getChildren().add(deleteButton);
		}
		return card;
	}

	/** Full color + slider editor for the active theme - only shown when it's a custom (non-built-in) theme, matching the in-game rule that built-ins can't be edited. */
	private static VBox buildThemeEditor(Host host) {
		LauncherTheme t = host.activeTheme();
		VBox editor = new VBox(10);
		editor.getStyleClass().add("glass-panel");
		Label title = new Label("Edit \"" + t.name() + "\"");
		title.setFont(Font.font("System", FontWeight.BOLD, 14));
		title.setTextFill(text(t));
		editor.getChildren().add(title);

		editor.getChildren().add(colorRow("Background", t.background(), t, host, (theme, c) ->
				new LauncherTheme(theme.name(), c, theme.surface(), theme.accentStart(), theme.accentEnd(), theme.text(),
						theme.cornerRadius(), theme.blurIntensity(), theme.animationSpeed(), theme.panelOpacity())));
		editor.getChildren().add(colorRow("Surface", t.surface(), t, host, (theme, c) ->
				new LauncherTheme(theme.name(), theme.background(), c, theme.accentStart(), theme.accentEnd(), theme.text(),
						theme.cornerRadius(), theme.blurIntensity(), theme.animationSpeed(), theme.panelOpacity())));
		editor.getChildren().add(colorRow("Accent Start", t.accentStart(), t, host, (theme, c) ->
				new LauncherTheme(theme.name(), theme.background(), theme.surface(), c, theme.accentEnd(), theme.text(),
						theme.cornerRadius(), theme.blurIntensity(), theme.animationSpeed(), theme.panelOpacity())));
		editor.getChildren().add(colorRow("Accent End", t.accentEnd(), t, host, (theme, c) ->
				new LauncherTheme(theme.name(), theme.background(), theme.surface(), theme.accentStart(), c, theme.text(),
						theme.cornerRadius(), theme.blurIntensity(), theme.animationSpeed(), theme.panelOpacity())));
		editor.getChildren().add(colorRow("Text", t.text(), t, host, (theme, c) ->
				new LauncherTheme(theme.name(), theme.background(), theme.surface(), theme.accentStart(), theme.accentEnd(), c,
						theme.cornerRadius(), theme.blurIntensity(), theme.animationSpeed(), theme.panelOpacity())));

		editor.getChildren().add(sliderRow("Corner Radius", 0, 16, t.cornerRadius(), t, host, (theme, v) ->
				new LauncherTheme(theme.name(), theme.background(), theme.surface(), theme.accentStart(), theme.accentEnd(),
						theme.text(), (int) v, theme.blurIntensity(), theme.animationSpeed(), theme.panelOpacity())));
		editor.getChildren().add(sliderRow("Blur Intensity", 0, 1, t.blurIntensity(), t, host, (theme, v) ->
				new LauncherTheme(theme.name(), theme.background(), theme.surface(), theme.accentStart(), theme.accentEnd(),
						theme.text(), theme.cornerRadius(), (float) v, theme.animationSpeed(), theme.panelOpacity())));
		editor.getChildren().add(sliderRow("Animation Speed", 0, 2, t.animationSpeed(), t, host, (theme, v) ->
				new LauncherTheme(theme.name(), theme.background(), theme.surface(), theme.accentStart(), theme.accentEnd(),
						theme.text(), theme.cornerRadius(), theme.blurIntensity(), (float) v, theme.panelOpacity())));
		editor.getChildren().add(sliderRow("Panel Opacity", 0, 1, t.panelOpacity(), t, host, (theme, v) ->
				new LauncherTheme(theme.name(), theme.background(), theme.surface(), theme.accentStart(), theme.accentEnd(),
						theme.text(), theme.cornerRadius(), theme.blurIntensity(), theme.animationSpeed(), (float) v)));
		return editor;
	}

	private interface ColorEdit {
		LauncherTheme apply(LauncherTheme theme, int argb);
	}

	private interface SliderEdit {
		LauncherTheme apply(LauncherTheme theme, double value);
	}

	private static HBox colorRow(String label, int currentArgb, LauncherTheme t, Host host, ColorEdit edit) {
		Label l = new Label(label);
		l.setTextFill(text(t));
		l.setPrefWidth(110);
		ColorPicker picker = new ColorPicker(Color.web(LauncherTheme.toCssHex(currentArgb)));
		picker.setOnAction(e -> {
			Color c = picker.getValue();
			int argb = (0xFF << 24) | ((int) Math.round(c.getRed() * 255) << 16)
					| ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255);
			LauncherTheme updated = edit.apply(host.activeTheme(), argb);
			host.setActiveTheme(updated);
			ThemeStore.save(updated);
		});
		HBox row = new HBox(10, l, picker);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private static HBox sliderRow(String label, double min, double max, double value, LauncherTheme t, Host host, SliderEdit edit) {
		Label l = new Label(label + ": " + String.format("%.2f", value));
		l.setTextFill(text(t));
		l.setPrefWidth(160);
		Slider slider = new Slider(min, max, value);
		HBox.setHgrow(slider, Priority.ALWAYS);
		slider.valueProperty().addListener((obs, oldVal, newVal) -> {
			LauncherTheme updated = edit.apply(host.activeTheme(), newVal.doubleValue());
			host.setActiveTheme(updated);
			ThemeStore.save(updated);
			l.setText(label + ": " + String.format("%.2f", newVal.doubleValue()));
		});
		HBox row = new HBox(10, l, slider);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private static Color accent(LauncherTheme t) {
		return Color.rgb((t.accentStart() >> 16) & 0xFF, (t.accentStart() >> 8) & 0xFF, t.accentStart() & 0xFF);
	}

	private static Color text(LauncherTheme t) {
		return Color.rgb((t.text() >> 16) & 0xFF, (t.text() >> 8) & 0xFF, t.text() & 0xFF);
	}
}
