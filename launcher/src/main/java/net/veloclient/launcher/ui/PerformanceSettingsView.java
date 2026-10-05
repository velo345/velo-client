package net.veloclient.launcher.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import net.veloclient.launcher.data.LauncherSettings;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.launch.MemoryPlanner;
import net.veloclient.launcher.theme.LauncherTheme;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The launcher Settings cards for memory/GC and for the in-game Velo menu key. */
public final class PerformanceSettingsView {

	private PerformanceSettingsView() {
	}

	public static List<Node> build(LauncherTheme theme) {
		return List.of(memoryCard(theme), menuKeyCard(theme));
	}

	// ---- Memory ----

	private static Node memoryCard(LauncherTheme theme) {
		LauncherSettings.Data settings = LauncherSettings.get();
		VBox card = SettingsUi.card("Memory & performance");

		long totalMb = MemoryPlanner.totalSystemMb();
		long freeMb = MemoryPlanner.availableSystemMb();
		String pcInfo = freeMb > 0
				? String.format(Locale.ROOT, "This PC has %.0f GB, %.1f GB free", totalMb / 1024.0, freeMb / 1024.0)
				: String.format(Locale.ROOT, "This PC has %.0f GB", totalMb / 1024.0);

		ComboBox<String> mode = new ComboBox<>();
		mode.getItems().addAll("Automatic", "Fixed amount");
		mode.getSelectionModel().select(settings.memoryMode == LauncherSettings.MemoryMode.AUTO ? 0 : 1);

		int maxSlider = (int) Math.max(4096, totalMb - 2048);
		Slider fixed = new Slider(2048, maxSlider, Math.min(settings.fixedMemoryMb, maxSlider));
		fixed.setMajorTickUnit(1024);
		fixed.setBlockIncrement(512);
		fixed.setPrefWidth(200);
		Label fixedLabel = new Label(gb(settings.fixedMemoryMb));
		fixedLabel.getStyleClass().add("settings-value");
		fixedLabel.setMinWidth(56);

		ComboBox<String> gc = new ComboBox<>();
		gc.getItems().addAll("Automatic", "G1 (balanced)", "ZGC (low pauses)");
		gc.getSelectionModel().select(settings.gc.ordinal());

		CheckBox driverCache = new CheckBox();
		driverCache.setSelected(settings.driverShaderCache);

		VBox preview = new VBox(0);
		Runnable[] refresh = new Runnable[1];
		HBox[] fixedRow = new HBox[1];
		refresh[0] = () -> {
			boolean auto = mode.getSelectionModel().getSelectedIndex() == 0;
			if (fixedRow[0] != null) {
				fixedRow[0].setVisible(!auto);
				fixedRow[0].setManaged(!auto);
			}
			int fixedMb = (int) (Math.round(fixed.getValue() / 256.0) * 256);
			fixedLabel.setText(gb(fixedMb));
			LauncherSettings.Data updated = copy(LauncherSettings.get());
			updated.memoryMode = auto ? LauncherSettings.MemoryMode.AUTO : LauncherSettings.MemoryMode.FIXED;
			updated.fixedMemoryMb = fixedMb;
			updated.gc = LauncherSettings.GcMode.values()[Math.max(0, gc.getSelectionModel().getSelectedIndex())];
			updated.driverShaderCache = driverCache.isSelected();
			LauncherSettings.save(updated);
			preview.getChildren().clear();
			List<Instance> instances = InstanceStore.loadAll();
			for (Instance instance : instances.subList(0, Math.min(6, instances.size()))) {
				MemoryPlanner.Plan plan = MemoryPlanner.plan(instance);
				Label value = new Label(plan.summary());
				value.getStyleClass().add("settings-value");
				if (plan.warning() != null) {
					value.getStyleClass().add("settings-value-warn");
				}
				String why = String.join("\n", plan.reasons()) + (plan.warning() != null ? "\n\n" + plan.warning() : "");
				VBox holder = new VBox();
				SettingsUi.row(holder, instance.name(), null, why, value);
				preview.getChildren().addAll(holder.getChildren());
			}
		};
		mode.setOnAction(e -> refresh[0].run());
		gc.setOnAction(e -> refresh[0].run());
		fixed.valueChangingProperty().addListener((obs, was, is) -> {
			if (!is) {
				refresh[0].run();
			}
		});
		fixed.valueProperty().addListener((obs, o, n) -> fixedLabel.setText(gb((int) (Math.round(n.doubleValue() / 256.0) * 256))));
		driverCache.setOnAction(e -> refresh[0].run());

		SettingsUi.row(card, "RAM", pcInfo,
				"Automatic sizes each profile from this PC, its mods and settings, and how much the game really used last time. "
						+ "A profile's own setting (Home > ... > Memory & Java settings) always wins.", mode);
		fixedRow[0] = SettingsUi.row(card, "Amount", "Given to every profile", null, fixed, fixedLabel);
		SettingsUi.row(card, "Garbage collector", "Automatic picks the best for each launch",
				"G1 suits most setups. ZGC has the shortest pauses but wants 8 GB+ and 8+ CPU cores.", gc);
		if (MemoryPlanner.isLinux()) {
			SettingsUi.row(card, "Bigger GPU shader cache", "Fewer stutters after driver/game updates",
					"Linux, NVIDIA/Mesa: keeps a larger driver shader cache so shaders don't recompile as often.", driverCache);
		}
		if (MemoryPlanner.isLinux()) {
			guardRows(card);
		}
		Label perProfile = new Label("Next launch per profile  ·  hover a row for details");
		perProfile.getStyleClass().add("settings-subheading");
		card.getChildren().addAll(perProfile, preview);
		refresh[0].run();
		return card;
	}

	// ---- Memory guard ----

	private static LauncherSettings.Data copy(LauncherSettings.Data from) {
		LauncherSettings.Data to = new LauncherSettings.Data();
		to.memoryMode = from.memoryMode;
		to.fixedMemoryMb = from.fixedMemoryMb;
		to.gc = from.gc;
		to.driverShaderCache = from.driverShaderCache;
		to.memoryGuard = from.memoryGuard;
		to.guardAvailableMb = from.guardAvailableMb;
		to.guardSwapMb = from.guardSwapMb;
		to.guardSeconds = from.guardSeconds;
		return to;
	}

	/** On/off plus the three thresholds; changes apply to running games right away. */
	private static void guardRows(VBox card) {
		LauncherSettings.Data settings = LauncherSettings.get();
		CheckBox enabled = new CheckBox();
		enabled.setSelected(settings.memoryGuard);
		javafx.scene.control.Spinner<Integer> ram = new javafx.scene.control.Spinner<>(64, 4096, settings.guardAvailableMb, 20);
		javafx.scene.control.Spinner<Integer> swap = new javafx.scene.control.Spinner<>(0, 8192, settings.guardSwapMb, 20);
		javafx.scene.control.Spinner<Double> seconds = new javafx.scene.control.Spinner<>(0.5, 30.0, settings.guardSeconds, 0.5);
		for (javafx.scene.control.Spinner<?> spinner : List.of(ram, swap, seconds)) {
			spinner.setEditable(true);
			spinner.setPrefWidth(110);
		}
		Runnable save = () -> {
			LauncherSettings.Data updated = copy(LauncherSettings.get());
			updated.memoryGuard = enabled.isSelected();
			updated.guardAvailableMb = ram.getValue();
			updated.guardSwapMb = swap.getValue();
			updated.guardSeconds = seconds.getValue();
			LauncherSettings.save(updated);
			for (javafx.scene.control.Spinner<?> spinner : List.of(ram, swap, seconds)) {
				spinner.setDisable(!updated.memoryGuard);
			}
		};
		enabled.setOnAction(e -> save.run());
		ram.valueProperty().addListener((o, a, b) -> save.run());
		swap.valueProperty().addListener((o, a, b) -> save.run());
		seconds.valueProperty().addListener((o, a, b) -> save.run());
		javafx.scene.control.Button reset = new javafx.scene.control.Button("Defaults");
		reset.getStyleClass().add("ghost-button");
		reset.setOnAction(e -> {
			LauncherSettings.Data defaults = new LauncherSettings.Data();
			enabled.setSelected(defaults.memoryGuard);
			ram.getValueFactory().setValue(defaults.guardAvailableMb);
			swap.getValueFactory().setValue(defaults.guardSwapMb);
			seconds.getValueFactory().setValue(defaults.guardSeconds);
			save.run();
		});
		Label heading = new Label("Memory guard");
		heading.getStyleClass().add("settings-subheading");
		card.getChildren().add(heading);
		SettingsUi.row(card, "Stop a game before the PC freezes", "Recommended - only the most recently started game is stopped",
				"When RAM and swap stay critically low, the launcher stops the newest running game so your desktop doesn't lock up. "
						+ "With two games open, the one you were already playing keeps running.", enabled, reset);
		SettingsUi.row(card, "Free RAM below (MB)", "Default 220", null, ram);
		SettingsUi.row(card, "...and free swap below (MB)", "Default 160 (no swap counts as below)", null, swap);
		SettingsUi.row(card, "...for (seconds)", "Default 2 - short dips are ignored", null, seconds);
		save.run();
	}

	// ---- Menu key ----

	private static final Map<String, String> KEYS = new LinkedHashMap<>();

	static {
		KEYS.put("Right Shift", "key.keyboard.right.shift");
		KEYS.put("Left Shift", "key.keyboard.left.shift");
		KEYS.put("Right Ctrl", "key.keyboard.right.control");
		KEYS.put("Right Alt", "key.keyboard.right.alt");
		KEYS.put("Insert", "key.keyboard.insert");
		KEYS.put("Delete", "key.keyboard.delete");
		KEYS.put("Home", "key.keyboard.home");
		KEYS.put("End", "key.keyboard.end");
		KEYS.put("Page Up", "key.keyboard.page.up");
		KEYS.put("Page Down", "key.keyboard.page.down");
		KEYS.put("Tab", "key.keyboard.tab");
		KEYS.put("Grave (`)", "key.keyboard.grave.accent");
		for (int i = 1; i <= 12; i++) {
			KEYS.put("F" + i, "key.keyboard.f" + i);
		}
		for (char c = 'A'; c <= 'Z'; c++) {
			KEYS.put(String.valueOf(c), "key.keyboard." + Character.toLowerCase(c));
		}
	}

	private static Node menuKeyCard(LauncherTheme theme) {
		VBox card = SettingsUi.card("Controls");
		Path file = VeloPaths.config().resolve("menu-key.json");
		String current = "key.keyboard.right.shift";
		try {
			if (Files.exists(file)) {
				JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				if (json.has("key")) {
					current = json.get("key").getAsString();
				}
			}
		} catch (Exception ignored) {
			// Default below.
		}
		ComboBox<String> key = new ComboBox<>();
		key.getItems().addAll(KEYS.keySet());
		String currentKey = current;
		String label = KEYS.entrySet().stream().filter(e -> e.getValue().equals(currentKey)).map(Map.Entry::getKey).findFirst().orElse(null);
		if (label == null) {
			label = "Custom (" + current.replace("key.keyboard.", "") + ")";
			key.getItems().add(0, label);
		}
		key.getSelectionModel().select(label);
		Label saved = new Label("");
		saved.getStyleClass().add("settings-row-hint");
		key.setOnAction(e -> {
			String chosen = KEYS.get(key.getValue());
			if (chosen == null) {
				return;
			}
			try {
				Files.writeString(file, "{\n  \"key\": \"" + chosen + "\"\n}\n", StandardCharsets.UTF_8);
				saved.setText("Saved");
			} catch (Exception ex) {
				saved.setText("Couldn't save: " + ex.getMessage());
			}
		});
		SettingsUi.row(card, "Open Velo menu", "In game - also changeable there", "Default is Right Shift. Takes effect the next time the game starts.", saved, key);
		return card;
	}

	// ---- Helpers ----

	private static String gb(int mb) {
		return String.format(Locale.ROOT, "%.1f GB", mb / 1024.0);
	}

	private static VBox card(LauncherTheme theme, String heading, String subtitle) {
		VBox card = new VBox(10);
		card.getStyleClass().add("glass-panel");
		Label headingLabel = new Label(heading);
		headingLabel.setFont(Font.font("Inter", FontWeight.BOLD, 15));
		headingLabel.setTextFill(color(theme.accentStart()));
		Label sub = muted(theme, subtitle);
		card.getChildren().addAll(headingLabel, sub);
		return card;
	}

	private static Label text(LauncherTheme theme, String value) {
		Label label = new Label(value);
		label.setTextFill(color(theme.text()));
		return label;
	}

	private static Label muted(LauncherTheme theme, String value) {
		Label label = new Label(value);
		label.getStyleClass().add("section-subtitle");
		label.setTextFill(color(theme.text()));
		label.setWrapText(true);
		return label;
	}

	private static Color color(int argb) {
		return Color.rgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
	}
}
