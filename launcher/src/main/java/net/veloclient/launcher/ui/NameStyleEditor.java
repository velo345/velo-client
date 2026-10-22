package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import net.veloclient.launcher.data.NameStyleSetting;
import net.veloclient.launcher.social.StoreApi;
import net.veloclient.velo.client.namestyle.NameStyle;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Locker > Name Style in the launcher (Velo+): the same editor as in game - presets, effect, colours, speed, spread,
 * font, icon, letter style and where it shows - with live previews. Every change is written to the file the game
 * watches, so a running game shows it within a second. Without Velo+ you can try it, but it stays off.
 */
final class NameStyleEditor {

	private static NameStyle style;
	private static boolean enabled;
	private static Boolean plus;

	private NameStyleEditor() {
	}

	/** Rereads the file next time (a loadout just changed it). */
	static void forget() {
		style = null;
	}

	private static void load() {
		if (style == null) {
			style = NameStyleSetting.style();
			enabled = NameStyleSetting.enabled();
		}
	}

	private static boolean plus() {
		return Boolean.TRUE.equals(plus) || StoreApi.isPlusCached();
	}

	/** Saves and redraws the panel (the previews follow on their own every frame). */
	private static void commit(NameStyle changed, Runnable refresh) {
		style = changed;
		NameStyleSetting.save(style, enabled && plus());
		// The look you're wearing keeps its own name style, like its colours.
		CosmeticsView.syncActiveLoadout();
		if (refresh != null) {
			refresh.run();
		}
	}

	// ---- Middle: the live previews ----

	static StackPane stage(String playerName) {
		load();
		String name = playerName == null || playerName.isBlank() ? "Steve" : playerName;
		Label head = small("LIVE PREVIEW");

		NamePreview tag = new NamePreview(name, () -> style, 34, 300);
		StackPane tagBox = new StackPane(tag);
		tagBox.setPadding(new Insets(6, 14, 6, 14));
		tagBox.setMaxWidth(Region.USE_PREF_SIZE);
		tagBox.setStyle("-fx-background-color: rgba(0,0,0,0.42); -fx-background-radius: 8;");
		VBox tagBlock = new VBox(8, tagBox, small("ABOVE YOUR HEAD"));
		tagBlock.setAlignment(Pos.CENTER);

		VBox chat = new VBox(4,
				chatLine("<", name, "> gg, that was close"),
				chatLine("<Steve> clean", null, null),
				chatLine("<Alex> nice name, ", name, ""),
				chatLine("", name, " joined the game"));
		chat.setPadding(new Insets(10, 12, 10, 12));
		chat.setStyle("-fx-background-color: rgba(0,0,0,0.45); -fx-background-radius: 8;");
		VBox chatBlock = new VBox(8, chat, small("CHAT"));

		HBox tab = new HBox(8);
		Region headSquare = new Region();
		headSquare.setMinSize(16, 16);
		headSquare.setMaxSize(16, 16);
		headSquare.setStyle("-fx-background-color: #6B4A35; -fx-background-radius: 2;");
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		Label ping = new Label("▂▄▆█");
		ping.setStyle("-fx-text-fill: #3DDC97; -fx-font-size: 11px;");
		tab.getChildren().addAll(headSquare, new NamePreview(name, () -> style, 15, 200), grow, ping);
		tab.setAlignment(Pos.CENTER_LEFT);
		tab.setPadding(new Insets(5, 10, 5, 8));
		tab.setStyle("-fx-background-color: rgba(0,0,0,0.38); -fx-background-radius: 6;");
		VBox tabBlock = new VBox(8, tab, small("TAB LIST"));

		Label note = new Label();
		note.getStyleClass().add("t-muted");
		note.setWrapText(true);
		Runnable updateNote = () -> note.setText(!plus() ? "Name Styles are part of Velo+. Try anything here - it switches on once you're a member."
				: enabled ? "On. Every Velo player sees your name like this - changes reach your running game right away."
				: "Off. Switch it on at the top of the editor.");
		updateNote.run();
		noteUpdater = updateNote;

		VBox column = new VBox(26, head, tagBlock, chatBlock, tabBlock, note);
		column.setPadding(new Insets(28, 26, 28, 26));
		column.setMaxWidth(380);
		StackPane stage = new StackPane(column);
		StackPane.setAlignment(column, Pos.TOP_CENTER);
		stage.setMinWidth(320);
		stage.setStyle("-fx-background-color: radial-gradient(center 50% 30%, radius 60%, #ff2d3c22 0%, transparent 100%), derive(-velo-background, 3%);"
				+ " -fx-border-color: transparent -velo-hairline transparent -velo-hairline; -fx-border-width: 0 1 0 1;");
		return stage;
	}

	private static Runnable noteUpdater = () -> {
	};

	private static Node chatLine(String before, String name, String after) {
		HBox line = new HBox(0);
		line.setAlignment(Pos.CENTER_LEFT);
		if (!before.isEmpty()) {
			line.getChildren().add(mcLabel(before, "#FFFFFF"));
		}
		if (name != null) {
			line.getChildren().add(new NamePreview(name, () -> style, 15, NamePreview.width(name, javafx.scene.text.Font.font("JetBrains Mono",
					javafx.scene.text.FontWeight.BOLD, 15)) * 1.5 + 30));
		}
		if (after != null && !after.isEmpty()) {
			line.getChildren().add(mcLabel(after, before.isEmpty() ? "#FFFF55" : "#FFFFFF"));
		}
		return line;
	}

	private static Label mcLabel(String text, String color) {
		Label label = new Label(text);
		label.setStyle("-fx-text-fill: " + color + "; -fx-font-family: 'JetBrains Mono'; -fx-font-size: 13px;");
		return label;
	}

	// ---- Right: the editor ----

	static VBox panel(String playerName, Runnable refreshPage) {
		load();
		String name = playerName == null || playerName.isBlank() ? "Steve" : playerName;
		VBox body = new VBox(18);
		Runnable[] redraw = new Runnable[1];
		redraw[0] = () -> {
			body.getChildren().setAll(sections(name, redraw[0]));
			noteUpdater.run();
		};
		redraw[0].run();
		if (plus == null) {
			CompletableFuture.supplyAsync(() -> {
				try {
					return StoreApi.plusStatus().get("plus").getAsBoolean();
				} catch (Exception e) {
					return false;
				}
			}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> Platform.runLater(() -> {
				plus = result;
				if (!result && enabled) {
					// Not (or no longer) a member: keep the style, but it isn't shown.
					NameStyleSetting.save(style, false);
				}
				redraw[0].run();
			}));
		}

		ScrollPane scroll = new ScrollPane(body);
		scroll.setFitToWidth(true);
		scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
		VBox.setVgrow(scroll, Priority.ALWAYS);
		Label heading = new Label("Name Style");
		heading.getStyleClass().add("t-title-sm");
		Label tagLabel = new Label("VELO+");
		tagLabel.setStyle("-fx-background-color: #ff2d3c; -fx-background-radius: 6; -fx-padding: 1 6 1 6; -fx-font-size: 10px; -fx-font-weight: 900;"
				+ " -fx-text-fill: white;");
		HBox head = new HBox(8, heading, tagLabel);
		head.setAlignment(Pos.CENTER_LEFT);
		VBox panel = new VBox(14, head, scroll);
		panel.setMinWidth(380);
		panel.setPrefWidth(540);
		panel.setMaxWidth(580);
		panel.setPadding(new Insets(0, 0, 0, 24));
		return panel;
	}

	private static java.util.List<Node> sections(String name, Runnable redraw) {
		java.util.List<Node> out = new java.util.ArrayList<>();
		out.add(headerCard(redraw));
		out.add(section("Presets", presets(name, redraw)));
		Label blurb = new Label(style.effect().blurb);
		blurb.getStyleClass().add("t-muted");
		out.add(section("Effect", new VBox(10, effects(redraw), blurb)));
		out.add(section("Colours", colors(redraw)));
		out.add(section("Motion", motion(redraw)));
		out.add(section("Font", fonts(name, redraw)));
		out.add(section("Icon", icons(redraw)));
		out.add(section("Letters", chips(new String[] {"Bold", "Italic", "Underline", "Glow"},
				new boolean[] {style.bold(), style.italic(), style.underline(), style.glow()},
				i -> commit(style.withFlags(i == 0 != style.bold(), i == 1 != style.italic(), i == 2 != style.underline(), i == 3 != style.glow(),
						style.reverse()), redraw))));
		out.add(section("Show it in", chips(new String[] {"Chat", "Tab list", "Above your head"},
				new boolean[] {style.chat(), style.tab(), style.nameTag()},
				i -> commit(style.withPlaces(i == 0 != style.chat(), i == 1 != style.tab(), i == 2 != style.nameTag()), redraw))));
		Button reset = new Button("Reset to default");
		reset.getStyleClass().add("ghost-button");
		reset.setOnAction(e -> commit(NameStyle.defaults(), redraw));
		out.add(reset);
		Label hint = new Label("What you see of other players' name styles is set in game: Velo Network settings.");
		hint.getStyleClass().add("t-muted");
		hint.setWrapText(true);
		out.add(hint);
		return out;
	}

	private static Node headerCard(Runnable redraw) {
		Label title = new Label("Show my name style");
		title.getStyleClass().add("t-strong");
		Label sub = new Label(plus() ? style.effect().label + " · " + NameStyle.FONTS[style.font()][0] : "Part of Velo+ - subscribe in the Store");
		sub.getStyleClass().add("t-muted");
		VBox text = new VBox(2, title, sub);
		HBox.setHgrow(text, Priority.ALWAYS);
		Node action;
		if (plus()) {
			Button toggle = new Button(enabled ? "On" : "Off");
			toggle.getStyleClass().add(enabled ? "primary-button" : "ghost-button");
			toggle.setMinWidth(70);
			toggle.setOnAction(e -> {
				enabled = !enabled;
				commit(style, redraw);
			});
			action = toggle;
		} else {
			Label locked = new Label("Velo+ only");
			locked.setStyle("-fx-text-fill: #ff7a7e; -fx-font-weight: bold;");
			action = locked;
		}
		HBox card = new HBox(12, text, action);
		card.setAlignment(Pos.CENTER_LEFT);
		card.getStyleClass().add("card");
		card.setPadding(new Insets(14));
		return card;
	}

	private static Node presets(String name, Runnable redraw) {
		FlowPane grid = new FlowPane(8, 8);
		for (NameStyle.Preset preset : NameStyle.PRESETS) {
			NameStyle look = preset.style().withPlaces(style.chat(), style.tab(), style.nameTag());
			grid.getChildren().add(tile(new NamePreview(name, preset::style, 15, 140), preset.name(), look.equals(style),
					() -> commit(look, redraw)));
		}
		return grid;
	}

	private static Node effects(Runnable redraw) {
		FlowPane grid = new FlowPane(8, 8);
		for (NameStyle.Effect effect : NameStyle.Effect.values()) {
			NameStyle look = style.withEffect(effect).withIcon(0).withFlags(true, false, false, style.glow(), style.reverse());
			grid.getChildren().add(tile(new NamePreview(effect.label, () -> look, 14, 140), null, style.effect() == effect,
					() -> commit(style.withEffect(effect), redraw)));
		}
		return grid;
	}

	private static final int[] PALETTE = {0xFF2D3C, 0xFF7A00, 0xFFC93C, 0xFFF06B, 0x7CFF00, 0x3DDC97, 0x00E5FF, 0x2DD4BF, 0x5AA9FF, 0x3B4CFF,
			0x7B2FF7, 0xB46CFF, 0xFF4FD8, 0xFFB7C5, 0xFFFFFF, 0xA9A9B3, 0x55555F, 0x111111};
	/** The colour the picker edits. */
	private static int selectedColor;

	private static Color fx(int rgb) {
		return Color.rgb(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF);
	}

	private static int rgb(Color c) {
		return (int) Math.round(c.getRed() * 255) << 16 | (int) Math.round(c.getGreen() * 255) << 8 | (int) Math.round(c.getBlue() * 255);
	}

	/** Round swatches (click to edit, right-click to remove, + to add), an inline colour square + hue bar, quick colours. */
	private static Node colors(Runnable redraw) {
		int[] colors = style.colors();
		selectedColor = Math.max(0, Math.min(colors.length - 1, selectedColor));
		HBox row = new HBox(10);
		row.setAlignment(Pos.CENTER_LEFT);
		javafx.scene.shape.Circle[] swatches = new javafx.scene.shape.Circle[colors.length];
		for (int i = 0; i < colors.length; i++) {
			int index = i;
			javafx.scene.shape.Circle swatch = new javafx.scene.shape.Circle(15, fx(colors[i]));
			swatch.setStroke(i == selectedColor ? Color.WHITE : Color.rgb(255, 255, 255, 0.15));
			swatch.setStrokeWidth(i == selectedColor ? 3 : 1);
			swatch.setStyle("-fx-cursor: hand;");
			swatch.setOnMouseClicked(e -> {
				if (e.getButton() == MouseButton.SECONDARY && colors.length > 1) {
					int[] next = new int[colors.length - 1];
					for (int k = 0, j = 0; k < colors.length; k++) {
						if (k != index) {
							next[j++] = colors[k];
						}
					}
					selectedColor = Math.min(selectedColor, next.length - 1);
					commit(style.withColors(next), redraw);
				} else {
					selectedColor = index;
					redraw.run();
				}
			});
			swatches[i] = swatch;
			row.getChildren().add(swatch);
		}
		if (colors.length < NameStyle.MAX_COLORS) {
			Button add = new Button("+");
			add.getStyleClass().add("ghost-button");
			add.setStyle("-fx-background-radius: 16; -fx-border-radius: 16; -fx-min-width: 32px; -fx-min-height: 32px; -fx-padding: 0;");
			add.setOnAction(e -> {
				int[] next = java.util.Arrays.copyOf(colors, colors.length + 1);
				next[colors.length] = net.veloclient.velo.client.namestyle.NameStyleEffects.mix(colors[colors.length - 1], 0xFFFFFF, 0.5);
				selectedColor = colors.length;
				commit(style.withColors(next), redraw);
			});
			row.getChildren().add(add);
		}
		Region grow = new Region();
		HBox.setHgrow(grow, Priority.ALWAYS);
		javafx.scene.control.TextField hex = new javafx.scene.control.TextField(String.format(Locale.ROOT, "#%06X", colors[selectedColor]));
		hex.setPrefColumnCount(7);
		hex.setStyle("-fx-font-family: 'JetBrains Mono';");
		row.getChildren().addAll(grow, hex);

		// The square: saturation left-right, brightness top-bottom, in the selected colour's hue.
		Color current = fx(colors[selectedColor]);
		double[] hsv = {current.getHue() / 360.0, current.getSaturation(), current.getBrightness()};
		Region square = new Region();
		square.setPrefHeight(120);
		square.setMinHeight(120);
		Region shade = new Region();
		shade.setStyle("-fx-background-color: linear-gradient(to bottom, transparent, black); -fx-background-radius: 8;");
		shade.setMouseTransparent(true);
		javafx.scene.shape.Circle knob = new javafx.scene.shape.Circle(7, current);
		knob.setStroke(Color.WHITE);
		knob.setStrokeWidth(2.5);
		knob.setMouseTransparent(true);
		knob.setManaged(false);
		javafx.scene.layout.Pane squareBox = new javafx.scene.layout.Pane(square, shade, knob);
		square.prefWidthProperty().bind(squareBox.widthProperty());
		shade.prefWidthProperty().bind(squareBox.widthProperty());
		shade.prefHeightProperty().bind(square.heightProperty());
		Region hue = new Region();
		hue.setPrefHeight(12);
		hue.setMinHeight(12);
		hue.setStyle("-fx-background-radius: 6; -fx-cursor: hand; -fx-background-color: linear-gradient(to right, #ff0000, #ffff00, #00ff00, #00ffff, #0000ff,"
				+ " #ff00ff, #ff0000);");
		Region hueKnob = new Region();
		hueKnob.setPrefSize(6, 18);
		hueKnob.setStyle("-fx-background-color: white; -fx-background-radius: 3; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.6), 4, 0, 0, 1);");
		hueKnob.setMouseTransparent(true);
		hueKnob.setManaged(false);
		javafx.scene.layout.Pane hueBox = new javafx.scene.layout.Pane(hue, hueKnob);
		hue.prefWidthProperty().bind(hueBox.widthProperty());
		Runnable place = () -> {
			square.setStyle("-fx-background-radius: 8; -fx-cursor: crosshair; -fx-background-color: linear-gradient(to right, white, "
					+ toHex(Color.hsb(hsv[0] * 360, 1, 1)) + ");");
			knob.setCenterX(hsv[1] * squareBox.getWidth());
			knob.setCenterY((1 - hsv[2]) * square.getHeight());
			hueKnob.relocate(hsv[0] * hueBox.getWidth() - 3, -3);
		};
		squareBox.widthProperty().addListener((obs, o, n) -> place.run());
		Runnable apply = () -> {
			Color c = Color.hsb(hsv[0] * 360, hsv[1], hsv[2]);
			knob.setFill(c);
			swatches[selectedColor].setFill(c);
			hex.setText(toHex(c).toUpperCase(Locale.ROOT));
			place.run();
			int[] next = style.colors().clone();
			next[selectedColor] = rgb(c);
			commit(style.withColors(next), null);
		};
		javafx.event.EventHandler<javafx.scene.input.MouseEvent> onSquare = e -> {
			hsv[1] = Math.max(0, Math.min(1, e.getX() / Math.max(1, square.getWidth())));
			hsv[2] = Math.max(0, Math.min(1, 1 - e.getY() / Math.max(1, square.getHeight())));
			apply.run();
		};
		square.setOnMousePressed(onSquare);
		square.setOnMouseDragged(onSquare);
		javafx.event.EventHandler<javafx.scene.input.MouseEvent> onHue = e -> {
			hsv[0] = Math.max(0, Math.min(0.999, e.getX() / Math.max(1, hue.getWidth())));
			if (hsv[1] < 0.05) {
				hsv[1] = 1;
			}
			if (hsv[2] < 0.05) {
				hsv[2] = 1;
			}
			apply.run();
		};
		hue.setOnMousePressed(onHue);
		hue.setOnMouseDragged(onHue);
		hex.setOnAction(e -> {
			try {
				Color c = Color.web(hex.getText().trim());
				hsv[0] = c.getHue() / 360.0;
				hsv[1] = c.getSaturation();
				hsv[2] = c.getBrightness();
				apply.run();
			} catch (IllegalArgumentException ignored) {
				hex.setText(String.format(Locale.ROOT, "#%06X", style.colors()[selectedColor]));
			}
		});
		Platform.runLater(place);

		FlowPane palette = new FlowPane(8, 8);
		for (int color : PALETTE) {
			javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(10, fx(color));
			dot.setStroke(Color.rgb(255, 255, 255, 0.12));
			dot.setStyle("-fx-cursor: hand;");
			dot.setOnMouseClicked(e -> {
				Color c = fx(color);
				hsv[0] = c.getHue() / 360.0;
				hsv[1] = c.getSaturation();
				hsv[2] = c.getBrightness();
				apply.run();
			});
			palette.getChildren().add(dot);
		}

		Label hint = new Label(style.effect().usesColors() ? "Click a colour to edit it, right-click to remove it."
				: style.effect().label + " makes its own colours - these are used by the other effects.");
		hint.getStyleClass().add("t-muted");
		hint.setWrapText(true);
		return new VBox(12, row, squareBox, hueBox, palette, hint);
	}

	private static String toHex(Color c) {
		return String.format(Locale.ROOT, "#%02x%02x%02x", (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
				(int) Math.round(c.getBlue() * 255));
	}

	private static Node motion(Runnable redraw) {
		Slider speed = new Slider(NameStyle.MIN_SPEED, NameStyle.MAX_SPEED, style.speed());
		speed.setDisable(!style.effect().animated());
		Label speedValue = valueLabel(String.format(Locale.ROOT, "%.1fx", style.speed() / 10.0));
		speed.valueProperty().addListener((obs, old, value) -> {
			int v = (int) Math.round(value.doubleValue());
			speedValue.setText(String.format(Locale.ROOT, "%.1fx", v / 10.0));
			if (v != style.speed()) {
				commit(style.withSpeed(v), null);
			}
		});
		Slider spread = new Slider(1, 9, style.spread());
		spread.setMajorTickUnit(1);
		spread.setSnapToTicks(true);
		spread.setDisable(!style.effect().usesSpread());
		Label spreadValue = valueLabel(spreadName(style.spread()));
		spread.valueProperty().addListener((obs, old, value) -> {
			int v = (int) Math.round(value.doubleValue());
			spreadValue.setText(spreadName(v));
			if (v != style.spread()) {
				commit(style.withSpread(v), null);
			}
		});
		return new VBox(10, sliderRow("Speed", speed, speedValue), sliderRow("Spread", spread, spreadValue),
				chips(new String[] {"Reverse direction"}, new boolean[] {style.reverse()},
						i -> commit(style.withFlags(style.bold(), style.italic(), style.underline(), style.glow(), !style.reverse()), redraw)));
	}

	private static String spreadName(int spread) {
		return spread == 5 ? "Normal" : spread < 5 ? "Tight" : "Wide";
	}

	private static Node sliderRow(String name, Slider slider, Label value) {
		Label label = new Label(name);
		label.getStyleClass().add("t-strong");
		label.setMinWidth(70);
		HBox.setHgrow(slider, Priority.ALWAYS);
		value.setMinWidth(64);
		HBox row = new HBox(12, label, slider, value);
		row.setAlignment(Pos.CENTER_LEFT);
		return row;
	}

	private static Label valueLabel(String text) {
		Label label = new Label(text);
		label.getStyleClass().add("t-muted");
		return label;
	}

	private static Node fonts(String name, Runnable redraw) {
		FlowPane grid = new FlowPane(8, 8);
		for (int i = 0; i < NameStyle.FONTS.length; i++) {
			int font = i;
			NameStyle look = style.withFont(i).withIcon(0);
			grid.getChildren().add(tile(new NamePreview(name, () -> style.withFont(font).withIcon(0), 15, 140), NameStyle.FONTS[i][0],
					style.font() == i, () -> commit(style.withFont(font), redraw)));
		}
		return grid;
	}

	private static Node icons(Runnable redraw) {
		FlowPane grid = new FlowPane(6, 6);
		int first = style.colors()[0];
		String color = String.format(Locale.ROOT, "#%06x", first & 0xFFFFFF);
		for (int i = 0; i < NameStyle.ICONS.length; i++) {
			int icon = i;
			Button b = new Button(i == 0 ? "–" : NameStyle.ICONS[i]);
			b.getStyleClass().add("ghost-button");
			b.setMinSize(38, 38);
			b.setStyle("-fx-font-size: 16px; -fx-text-fill: " + (i == 0 ? "-velo-text-3" : color) + ";"
					+ (style.icon() == i ? " -fx-border-color: -velo-accent; -fx-border-radius: 8; -fx-background-color: -velo-accent-soft;" : ""));
			b.setOnAction(e -> commit(style.withIcon(icon), redraw));
			grid.getChildren().add(b);
		}
		return grid;
	}

	private static Node chips(String[] labels, boolean[] on, java.util.function.IntConsumer toggle) {
		FlowPane row = new FlowPane(8, 8);
		for (int i = 0; i < labels.length; i++) {
			int index = i;
			Button chip = new Button((on[i] ? "● " : "○ ") + labels[i]);
			chip.getStyleClass().setAll("button", "segmented-button");
			if (on[i]) {
				chip.getStyleClass().add("segmented-button-active");
			}
			chip.setStyle("-fx-padding: 7 14 7 12;");
			chip.setOnAction(e -> toggle.accept(index));
			row.getChildren().add(chip);
		}
		return row;
	}

	private static Node tile(Node preview, String caption, boolean selected, Runnable onClick) {
		VBox tile = new VBox(4, preview);
		if (caption != null) {
			tile.getChildren().add(small(caption.toUpperCase(Locale.ROOT)));
		}
		tile.setPadding(new Insets(8, 10, 8, 10));
		tile.setPrefWidth(160);
		tile.setStyle("-fx-background-radius: 10; -fx-border-radius: 10; -fx-cursor: hand; " + (selected
				? "-fx-background-color: -velo-accent-soft; -fx-border-color: -velo-accent;"
				: "-fx-background-color: -velo-surface-2; -fx-border-color: -velo-hairline;"));
		tile.setOnMouseClicked(e -> onClick.run());
		return tile;
	}

	private static Node section(String title, Node content) {
		return new VBox(10, small(title.toUpperCase(Locale.ROOT)), content);
	}

	private static Label small(String text) {
		Label label = new Label(text);
		label.setStyle("-fx-text-fill: -velo-text-3; -fx-font-family: 'Inter'; -fx-font-weight: bold; -fx-font-size: 10px;");
		return label;
	}
}
