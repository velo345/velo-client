package net.veloclient.velo.client.modules.debug;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.util.WorldInfo;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Better F3: a cleaner debug screen. Two compact, see-through panels in the top corners instead
 * of walls of text - position (with the matching Nether/Overworld spot for portals), where you're
 * looking, light, biome, time, what's under your crosshair, world and server numbers, and your
 * system. Every row can be switched off in the module settings.
 */
public final class BetterF3Module extends AbstractModule implements Configurable {

	/** Rows, in display order: id -> label (also the settings toggle). */
	private static final String[][] ROWS = {
			{"fps", "FPS"}, {"position", "Position"}, {"block", "Block & Chunk"}, {"portal", "Nether / Overworld Coords"},
			{"facing", "Facing"}, {"biome", "Biome"}, {"light", "Light"}, {"dimension", "Dimension"},
			{"time", "Time & Weather"}, {"target", "Looking At"}, {"world", "Entities & Chunks"}, {"server", "Server"},
			{"system", "System Panel"}};

	private final Map<String, Boolean> shown = new LinkedHashMap<>();
	private double opacity = 0.55;
	private boolean coloredAxes = true;

	private int fpsMin = Integer.MAX_VALUE;
	private int fpsShownMin;
	private long fpsWindowStart;
	private String gpu;

	public BetterF3Module() {
		super("better-f3", "Better F3", "A cleaner F3 screen: compact see-through panels, Nether/Overworld coordinates for portals, "
				+ "and you choose which rows to show.", ModuleCategory.DEBUG, SafetyTag.ALWAYS_SAFE, false);
		for (String[] row : ROWS) {
			shown.put(row[0], true);
		}
	}

	private boolean on(String id) {
		return shown.getOrDefault(id, true);
	}

	/** Called instead of vanilla's debug screen while F3 is open. */
	public void render(DrawContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (gpu == null) {
			String described = net.veloclient.velo.client.util.GpuInfo.describe();
			gpu = described == null ? "" : described;
		}
		trackFps(client.getCurrentFps());
		int screenW = context.getScaledWindowWidth();
		int screenH = context.getScaledWindowHeight();
		mainPanel.refresh();
		systemPanel.refresh();
		layoutDefaults(screenW, screenH);
		net.veloclient.velo.client.hud.HudManager.renderScaled(context, mainPanel, screenW, screenH, 0f);
		if (systemPanel.isEnabled()) {
			net.veloclient.velo.client.hud.HudManager.renderScaled(context, systemPanel, screenW, screenH, 0f);
		}
	}

	/** Not moved by hand yet: System sits top right, or under the main panel when they'd overlap. */
	private void layoutDefaults(int screenW, int screenH) {
		if (systemPanel.position().placed() || mainPanel.position().placed()) {
			return;
		}
		int mainW = Math.round(mainPanel.width() * mainPanel.position().scale());
		int mainH = Math.round(mainPanel.height() * mainPanel.position().scale());
		int sysW = Math.round(systemPanel.width() * systemPanel.position().scale());
		int mainX = mainPanel.position().resolveX(screenW, mainW);
		int mainY = mainPanel.position().resolveY(screenH, mainH);
		int right = screenW - sysW - 4;
		if (right > mainX + mainW + 8) {
			systemPanel.position().setAuto(right, 4, screenW, screenH);
		} else {
			systemPanel.position().setAuto(mainX, mainY + mainH + 4, screenW, screenH);
		}
	}

	/** Both panels regardless of settings (for saving the layout). */
	public List<net.veloclient.velo.client.hud.HudModule> allPanels() {
		return List.of(mainPanel, systemPanel);
	}

	/** Whether F3 is open right now. */
	public static boolean f3Open() {
		var client = MinecraftClient.getInstance();
		//? if <26.1 {
		return client.getDebugHud().shouldShowDebugHud();
		//?} else {
		/*return client.debugEntries.isOverlayVisible();
		*///?}
	}

	public static void toggleF3() {
		var client = MinecraftClient.getInstance();
		//? if <26.1 {
		client.debugHudEntryList.toggleF3Enabled();
		//?} else {
		/*client.debugEntries.toggleDebugOverlay();
		*///?}
	}

	/** The two F3 panels, for the HUD editor (only while F3 is open with Better F3 on). */
	public List<net.veloclient.velo.client.hud.HudModule> panels() {
		mainPanel.refresh();
		systemPanel.refresh();
		return systemPanel.isEnabled() ? List.of(mainPanel, systemPanel) : List.of(mainPanel);
	}

	/** One F3 card as a HUD element: drag/scale it in the HUD editor, saved with the HUD layout. */
	private final class Panel extends AbstractModule implements net.veloclient.velo.client.hud.HudModule {

		private final net.veloclient.velo.client.hud.HudPosition position;
		private final boolean system;
		private List<String[]> rows = List.of();
		private String title = "";
		private String subtitle = "";

		Panel(String id, String name, boolean system, float x) {
			super(id, name, "Better F3 panel", ModuleCategory.DEBUG, SafetyTag.ALWAYS_SAFE, true);
			this.system = system;
			this.position = new net.veloclient.velo.client.hud.HudPosition(x, 0f);
		}

		void refresh() {
			if (system) {
				title = "System";
				rows = systemRows();
			} else {
				MinecraftClient client = MinecraftClient.getInstance();
				title = "Velo F3";
				subtitle = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft")
						.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
				rows = WorldInfo.inWorld() ? gameRows(client) : List.<String[]>of(new String[] {"", "Join a world for more"});
			}
		}

		@Override
		public boolean isEnabled() {
			return BetterF3Module.this.isEnabled() && (!system || on("system"));
		}

		@Override
		public net.veloclient.velo.client.hud.HudPosition position() {
			return position;
		}

		@Override
		public int width() {
			// Measured in the HUD font the panel is drawn with (the HUD editor itself uses the Velo UI font).
			net.veloclient.velo.client.gui.VeloFonts.beginHud();
			try {
				return panelWidth(title, subtitle, rows);
			} finally {
				net.veloclient.velo.client.gui.VeloFonts.endHud();
			}
		}

		@Override
		public int height() {
			return 22 + rows.size() * 10;
		}

		@Override
		public void render(DrawContext context, int x, int y, float tickDelta) {
			panel(context, x, y, title, subtitle, rows, width(), 0);
		}
	}

	private final Panel mainPanel = new Panel("better-f3-main", "F3: Game", false, 0f);
	private final Panel systemPanel = new Panel("better-f3-system", "F3: System", true, 1f);

	private void trackFps(int fps) {
		long now = System.currentTimeMillis();
		fpsMin = Math.min(fpsMin, fps);
		if (now - fpsWindowStart > 1000) {
			fpsShownMin = fpsMin == Integer.MAX_VALUE ? fps : fpsMin;
			fpsMin = Integer.MAX_VALUE;
			fpsWindowStart = now;
		}
	}

	private List<String[]> gameRows(MinecraftClient client) {
		List<String[]> rows = new ArrayList<>();
		double[] pos = WorldInfo.position();
		int[] block = WorldInfo.blockPos();
		String dim = WorldInfo.dimension();
		if (on("fps")) {
			rows.add(new String[] {"FPS", client.getCurrentFps() + "  §7(min " + fpsShownMin + ")"});
		}
		if (on("position")) {
			rows.add(new String[] {"XYZ", axes(String.format(Locale.ROOT, "%.3f", pos[0]), String.format(Locale.ROOT, "%.3f", pos[1]),
					String.format(Locale.ROOT, "%.3f", pos[2]))});
		}
		if (on("block")) {
			rows.add(new String[] {"Block", block[0] + " " + block[1] + " " + block[2] + "  §7chunk " + (block[0] >> 4) + " " + (block[2] >> 4)
					+ "  in " + (block[0] & 15) + " " + (block[2] & 15)});
		}
		if (on("portal")) {
			if (dim.equals("overworld")) {
				rows.add(new String[] {"Nether", axes(String.valueOf(Math.floorDiv(block[0], 8)), String.valueOf(block[1]),
						String.valueOf(Math.floorDiv(block[2], 8))) + "  §7portal here"});
			} else if (dim.equals("the_nether")) {
				rows.add(new String[] {"Overworld", axes(String.valueOf(block[0] * 8), String.valueOf(block[1]), String.valueOf(block[2] * 8))
						+ "  §7portal there"});
			}
		}
		if (on("facing")) {
			float[] rot = WorldInfo.rotation();
			String facing = WorldInfo.facing();
			String axis = switch (facing) {
				case "north" -> "-Z";
				case "south" -> "+Z";
				case "west" -> "-X";
				case "east" -> "+X";
				default -> "";
			};
			rows.add(new String[] {"Facing", cap(facing) + " (" + axis + ")  §7" + String.format(Locale.ROOT, "%.1f / %.1f", rot[0], rot[1])});
		}
		if (on("biome")) {
			String biome = biome(block);
			if (biome != null) {
				rows.add(new String[] {"Biome", biome});
			}
		}
		if (on("light")) {
			int[] light = WorldInfo.light();
			rows.add(new String[] {"Light", "sky " + light[0] + "  block " + light[1]});
		}
		if (on("dimension")) {
			rows.add(new String[] {"Dimension", cap(dim.replace('_', ' '))});
		}
		if (on("time")) {
			long time = WorldInfo.dayTime();
			long day = time / 24000L;
			long ticks = Math.floorMod(time, 24000L);
			int hours = (int) ((ticks / 1000 + 6) % 24);
			int minutes = (int) (ticks % 1000 * 60 / 1000);
			rows.add(new String[] {"Time", "Day " + (day + 1) + "  " + String.format(Locale.ROOT, "%02d:%02d", hours, minutes) + "  §7" + WorldInfo.weather()});
		}
		if (on("target")) {
			String[] target = WorldInfo.targetBlock();
			String entity = WorldInfo.targetEntity();
			if (entity != null) {
				rows.add(new String[] {"Looking at", entity});
			} else if (target != null) {
				rows.add(new String[] {"Looking at", target[0].replace("minecraft:", "") + "  §7" + target[1]});
			}
		}
		if (on("world")) {
			rows.add(new String[] {"World", WorldInfo.entityCount() + " entities  " + WorldInfo.loadedChunks() + " chunks"});
			rows.add(new String[] {"Distance", "render " + WorldInfo.renderDistance() + "  simulation " + WorldInfo.simulationDistance()});
		}
		if (on("server")) {
			String brand = WorldInfo.brand();
			int ping = WorldInfo.ping();
			String where = net.veloclient.velo.client.util.ClientCompat.isSingleplayer() ? "Singleplayer"
					: net.veloclient.velo.client.util.ClientCompat.currentServerAddress();
			rows.add(new String[] {"Server", (where == null ? "" : where) + (brand != null ? "  §7" + brand : "")
					+ (ping >= 0 && !net.veloclient.velo.client.util.ClientCompat.isSingleplayer() ? "  §7" + ping + " ms" : "")});
		}
		return rows;
	}

	private String biome(int[] block) {
		String name = net.veloclient.velo.client.worldmap.WorldMap.biomeAt(block[0], block[1], block[2]);
		if (name == null || name.isEmpty()) {
			return null;
		}
		name = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
		return cap(name.replace('_', ' '));
	}

	private List<String[]> systemRows() {
		List<String[]> rows = new ArrayList<>();
		Runtime rt = Runtime.getRuntime();
		long used = (rt.totalMemory() - rt.freeMemory()) / 1048576;
		long max = rt.maxMemory() / 1048576;
		rows.add(new String[] {"Memory", used + " / " + max + " MB  §7" + (used * 100 / Math.max(1, max)) + "%"});
		rows.add(new String[] {"Java", System.getProperty("java.version")});
		rows.add(new String[] {"CPU", Runtime.getRuntime().availableProcessors() + " threads"});
		if (gpu != null && !gpu.isEmpty()) {
			rows.add(new String[] {"GPU", gpu.length() > 44 ? gpu.substring(0, 44) + "..." : gpu});
		}
		var window = MinecraftClient.getInstance().getWindow();
		rows.add(new String[] {"Display", window.getWidth() + "x" + window.getHeight()});
		return rows;
	}

	/** "x y z" colored per axis (red/green/blue like the debug crosshair). */
	private String axes(String x, String y, String z) {
		return coloredAxes ? "§c" + x + " §a" + y + " §9" + z : x + " " + y + " " + z;
	}

	private static String cap(String text) {
		return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private static int panelWidth(String title, String subtitle, List<String[]> rows) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		int labelW = 0;
		int valueW = 0;
		for (String[] row : rows) {
			labelW = Math.max(labelW, renderer.getWidth(row[0]));
			valueW = Math.max(valueW, renderer.getWidth(row[1]));
		}
		int titleW = renderer.getWidth(title) + (subtitle.isEmpty() ? 0 : 6 + renderer.getWidth(subtitle));
		return Math.max(titleW + 14, labelW + valueW + 22);
	}

	/** A rounded see-through card with a title and "label  value" rows. Returns {right edge, bottom edge}. */
	private int[] panel(DrawContext context, int x, int y, String title, String subtitle, List<String[]> rows, int width, int screenW) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		int labelW = 0;
		for (String[] row : rows) {
			labelW = Math.max(labelW, renderer.getWidth(row[0]));
		}
		int w = width > 0 ? width : panelWidth(title, subtitle, rows);
		int h = 18 + rows.size() * 10 + 4;
		int bg = ((int) (opacity * 255) << 24) | 0x0C0C10;
		VeloDraw.fillRounded(context, x, y, w, h, 6, bg);
		int accent = ThemeManager.active().accentStart() | 0xFF000000;
		VeloDraw.fillRounded(context, (float) x + 6, y + 5, 2f, 8f, 1f, accent);
		context.drawTextWithShadow(renderer, title, x + 12, y + 5, 0xFFFFFFFF);
		if (!subtitle.isEmpty()) {
			context.drawTextWithShadow(renderer, subtitle, x + 18 + renderer.getWidth(title), y + 5, 0xFF9A9AA6);
		}
		int rowY = y + 18;
		for (String[] row : rows) {
			context.drawTextWithShadow(renderer, row[0], x + 8, rowY, 0xFF9A9AA6);
			context.drawTextWithShadow(renderer, row[1], x + 14 + labelW, rowY, 0xFFF2F2F5);
			rowY += 10;
		}
		return new int[] {x + w, y + h};
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		for (String[] row : ROWS) {
			String id = row[0];
			fields.add(new ConfigField.ToggleField("Show " + row[1], () -> on(id), v -> shown.put(id, v)));
		}
		fields.add(new ConfigField.ToggleField("Colored X / Y / Z", () -> coloredAxes, v -> coloredAxes = v));
		fields.add(new ConfigField.SliderField("Background Opacity", 0, 1, () -> opacity, v -> opacity = v,
				v -> Math.round(v * 100) + "%"));
		return fields;
	}
}
