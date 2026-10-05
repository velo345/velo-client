package net.veloclient.velo.client.worldmap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.WaypointEditScreen;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.waypoints.Waypoint;
import net.veloclient.velo.client.waypoints.WaypointManager;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The world map: everywhere you've explored in this world, full screen. Drag to move, scroll to zoom
 * (smoothly, toward the cursor), right-click for "add waypoint here" / "copy coordinates". Layers:
 * Surface (map colors with relief shading), Topography (height colors + contour lines), Biomes and
 * Caves (recorded at your height while underground - pick the level). Shows you, your waypoints and
 * death points; switch dimensions to look at the Nether/End maps you've explored.
 */
public final class WorldMapScreen extends VeloWindow {

	private enum Layer { SURFACE("Surface"), TOPOGRAPHY("Topography"), BIOMES("Biomes"), CAVES("Caves");
		final String label;
		Layer(String label) {
			this.label = label;
		}
	}

	// View state survives closing and reopening the map.
	private static Layer layer = Layer.SURFACE;
	private static double centerX = Double.NaN;
	private static double centerZ;
	private static float zoom = 1f;
	private static boolean follow = true;
	private static boolean grid;
	private static boolean showWaypoints = true;
	private static String viewedDimension;
	private static Integer caveBand;

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private float targetZoom = zoom;
	private boolean dragging;
	private double dragged;
	private double[] menuAt;
	private long lastNanos;

	public WorldMapScreen(Screen parent) {
		super(Text.literal("World Map"), 10_000, 10_000);
		returnTo(parent);
		String current = WorldMap.currentDimension();
		if (viewedDimension == null || current != null && !current.equals(viewedDimension) && follow) {
			viewedDimension = current;
		}
		double[] player = MapCompat.player();
		if (player != null && (follow || Double.isNaN(centerX))) {
			centerX = player[0];
			centerZ = player[2];
		}
	}

	/** Dev-only (screenshot tour). */
	public static void tourLayer(int index) {
		layer = Layer.values()[index];
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
	}

	@Override
	public void removed() {
		WorldMap.flush();
		super.removed();
	}

	private int mapTop() {
		return contentY() + 24;
	}

	private int mapBottom() {
		return contentBottom() - 16;
	}

	private int mapCenterX() {
		return contentX() + contentWidth() / 2;
	}

	private int mapCenterY() {
		return (mapTop() + mapBottom()) / 2;
	}

	private double blockX(double screenX) {
		return centerX + (screenX - mapCenterX()) / zoom;
	}

	private double blockZ(double screenY) {
		return centerZ + (screenY - mapCenterY()) / zoom;
	}

	private float screenX(double blockX) {
		return (float) (mapCenterX() + (blockX - centerX) * zoom);
	}

	private float screenY(double blockZ) {
		return (float) (mapCenterY() + (blockZ - centerZ) * zoom);
	}

	private MapStore store() {
		String world = WorldMap.currentWorld();
		if (world == null || viewedDimension == null) {
			return null;
		}
		if (layer == Layer.CAVES) {
			Integer band = caveBand != null ? caveBand : playerBand();
			return band == null ? null : WorldMap.store(world, viewedDimension, "cave" + band);
		}
		return WorldMap.store(world, viewedDimension, "surface");
	}

	private Integer playerBand() {
		double[] player = MapCompat.player();
		return player == null ? null : Math.floorDiv((int) Math.floor(player[1]), 16);
	}

	/** Cave levels recorded so far in the viewed dimension. */
	private List<Integer> caveBands() {
		List<Integer> bands = new ArrayList<>();
		String world = WorldMap.currentWorld();
		if (world == null || viewedDimension == null) {
			return bands;
		}
		var dir = WorldMap.root().resolve(WorldMap.safe(world)).resolve(WorldMap.safe(viewedDimension));
		try (var files = Files.list(dir)) {
			files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("cave")).forEach(n -> {
				try {
					bands.add(Integer.parseInt(n.substring(4)));
				} catch (NumberFormatException ignored) {
					// not a band folder
				}
			});
		} catch (Exception ignored) {
			// nothing recorded yet
		}
		Integer current = playerBand();
		if (current != null && !bands.contains(current)) {
			bands.add(current);
		}
		bands.sort(null);
		return bands;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		// Smooth zoom toward the target, keeping the point under the cursor in place.
		if (Math.abs(targetZoom - zoom) > 0.0005f) {
			double anchorX = blockX(mouseX);
			double anchorZ = blockZ(mouseY);
			boolean insideMap = mouseY >= mapTop() && mouseY < mapBottom();
			zoom += (targetZoom - zoom) * Math.min(1f, dt * 14f);
			if (insideMap && !follow) {
				centerX += anchorX - blockX(mouseX);
				centerZ += anchorZ - blockZ(mouseY);
			}
		}
		double[] player = MapCompat.player();
		if (follow && player != null) {
			centerX = player[0];
			centerZ = player[2];
		}
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		MapTextures.beginFrame();
		int left = contentX();
		int right = contentX() + contentWidth();
		int top = mapTop();
		int bottom = mapBottom();

		VeloDraw.fillRounded(context, left, top, right - left, bottom - top, 8, 0xFF0D0D10);
		context.enableScissor(left, top, right, bottom);
		MapStore store = store();
		int regionsDrawn = 0;
		if (store != null) {
			MapTextures.Style style = switch (layer) {
				case TOPOGRAPHY -> MapTextures.Style.TOPOGRAPHY;
				case BIOMES -> MapTextures.Style.BIOMES;
				default -> MapTextures.Style.SURFACE;
			};
			int minRx = (int) Math.floor(blockX(left) / MapRegion.SIZE);
			int maxRx = (int) Math.floor(blockX(right) / MapRegion.SIZE);
			int minRz = (int) Math.floor(blockZ(top) / MapRegion.SIZE);
			int maxRz = (int) Math.floor(blockZ(bottom) / MapRegion.SIZE);
			if ((long) (maxRx - minRx + 1) * (maxRz - minRz + 1) <= 400) {
				for (int rx = minRx; rx <= maxRx; rx++) {
					for (int rz = minRz; rz <= maxRz; rz++) {
						MapRegion region = store.region(rx, rz, false);
						if (region == null) {
							continue;
						}
						Identifier tex = MapTextures.texture(store, region, style);
						if (tex == null) {
							continue;
						}
						context.getMatrices().pushMatrix();
						context.getMatrices().translate(screenX((double) rx * MapRegion.SIZE), screenY((double) rz * MapRegion.SIZE));
						context.getMatrices().scale(zoom, zoom);
						context.drawTexture(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, MapRegion.SIZE, MapRegion.SIZE,
								MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE, MapRegion.SIZE);
						context.getMatrices().popMatrix();
						regionsDrawn++;
					}
				}
			}
		}
		if (grid) {
			drawGrid(context, left, top, right, bottom);
		}
		if (showWaypoints) {
			drawWaypoints(context, mouseX, mouseY);
		}
		if (player != null && WorldMap.currentDimension() != null && WorldMap.currentDimension().equals(viewedDimension)) {
			drawPlayer(context, screenX(player[0]), screenY(player[2]), (float) player[3]);
		}
		if (regionsDrawn == 0) {
			String message = store == null ? "Join a world to start mapping." : layer == Layer.CAVES
					? "No caves recorded at this level yet - go underground to map them."
					: "Nothing explored here yet - walk around and the map fills in.";
			context.drawTextWithShadow(this.textRenderer, message, mapCenterX() - this.textRenderer.getWidth(message) / 2, mapCenterY() + 18,
					VeloStyle.textMuted());
		}
		context.disableScissor();

		drawToolbar(context, mouseX, mouseY);
		drawStatus(context, mouseX, mouseY, store);
		if (menuAt != null) {
			drawMenu(context, mouseX, mouseY);
		}
	}

	private void drawGrid(DrawContext context, int left, int top, int right, int bottom) {
		int step = zoom >= 1.5f ? 16 : zoom >= 0.15f ? MapRegion.SIZE : 0;
		if (step == 0) {
			return;
		}
		double startX = Math.floor(blockX(left) / step) * step;
		for (double bx = startX; screenX(bx) < right; bx += step) {
			int sx = Math.round(screenX(bx));
			boolean region = Math.floorMod((long) bx, MapRegion.SIZE) == 0;
			context.fill(sx, top, sx + 1, bottom, region ? 0x50FFFFFF : 0x22FFFFFF);
		}
		double startZ = Math.floor(blockZ(top) / step) * step;
		for (double bz = startZ; screenY(bz) < bottom; bz += step) {
			int sy = Math.round(screenY(bz));
			boolean region = Math.floorMod((long) bz, MapRegion.SIZE) == 0;
			context.fill(left, sy, right, sy + 1, region ? 0x50FFFFFF : 0x22FFFFFF);
		}
	}

	private void drawWaypoints(DrawContext context, int mouseX, int mouseY) {
		String world = WorldMap.currentWorld();
		if (world == null) {
			return;
		}
		for (Waypoint waypoint : WaypointManager.inWorldAndDimension(world, viewedDimension)) {
			if (!waypoint.enabled) {
				continue;
			}
			float sx = screenX(waypoint.x);
			float sy = screenY(waypoint.z);
			int r = waypoint.death ? 4 : 5;
			VeloDraw.fillCircle(context, sx, sy, r + 1.5f, 0xFF000000);
			VeloDraw.fillCircle(context, sx, sy, r, waypoint.color | 0xFF000000);
			if (waypoint.death) {
				VeloDraw.cross(context, sx, sy, 3.5f, 1.2f, 0xFFFFFFFF);
			}
			boolean hovered = Math.hypot(mouseX - sx, mouseY - sy) < r + 3;
			if (hovered || zoom >= 0.6f) {
				String label = waypoint.name + (hovered && WorldMapModule.showCoordinates()
						? "  " + (int) Math.floor(waypoint.x) + ", " + (int) Math.floor(waypoint.z) : "");
				int tw = this.textRenderer.getWidth(label) + 8;
				VeloDraw.fillRounded(context, Math.round(sx - tw / 2f), Math.round(sy + r + 3), tw, 12, 4, 0xC0101014);
				context.drawTextWithShadow(this.textRenderer, label, Math.round(sx - tw / 2f) + 4, Math.round(sy + r + 5), 0xFFFFFFFF);
			}
		}
	}

	/** The player as an arrow pointing where they look (north = up). */
	private void drawPlayer(DrawContext context, float x, float y, float yaw) {
		double angle = Math.toRadians(yaw + 180);
		float size = 7;
		float tipX = (float) (x - Math.sin(angle) * size);
		float tipY = (float) (y + Math.cos(angle) * size);
		float leftX = (float) (x - Math.sin(angle + 2.5) * size * 0.8);
		float leftY = (float) (y + Math.cos(angle + 2.5) * size * 0.8);
		float rightX = (float) (x - Math.sin(angle - 2.5) * size * 0.8);
		float rightY = (float) (y + Math.cos(angle - 2.5) * size * 0.8);
		VeloDraw.fillCircle(context, x, y, 9, 0x50000000);
		for (float t = 0; t <= 1; t += 0.1f) {
			// Filled by sweeping lines from the tip to the back edge.
			float bx = leftX + (rightX - leftX) * t;
			float by = leftY + (rightY - leftY) * t;
			VeloDraw.line(context, tipX, tipY, bx, by, 1.6f, 0xFFFFFFFF);
		}
		VeloDraw.line(context, tipX, tipY, leftX, leftY, 1.4f, VeloStyle.accent());
		VeloDraw.line(context, tipX, tipY, rightX, rightY, 1.4f, VeloStyle.accent());
		VeloDraw.line(context, leftX, leftY, x, y, 1.4f, VeloStyle.accent());
		VeloDraw.line(context, rightX, rightY, x, y, 1.4f, VeloStyle.accent());
	}

	private void drawToolbar(DrawContext context, int mouseX, int mouseY) {
		int x = contentX();
		int y = contentY();
		for (Layer l : Layer.values()) {
			int w = this.textRenderer.getWidth(l.label) + 16;
			hits.add(VeloUi.pill(context, x, y, w, 16, l.label, layer == l ? 1 : 0, mouseX, mouseY, () -> {
				layer = l;
				caveBand = null;
			}));
			x += w + 4;
		}
		if (layer == Layer.CAVES) {
			List<Integer> bands = caveBands();
			Integer band = caveBand != null ? caveBand : playerBand();
			if (band != null) {
				x += 6;
				hits.add(VeloUi.pill(context, x, y, 16, 16, "-", 0, mouseX, mouseY, () -> caveBand = step(bands, band, -1)));
				String level = "Y " + band * 16 + " to " + (band * 16 + 15);
				int lw = this.textRenderer.getWidth(level) + 12;
				context.drawTextWithShadow(this.textRenderer, level, x + 22, y + 4, VeloStyle.text());
				hits.add(VeloUi.pill(context, x + 22 + lw, y, 16, 16, "+", 0, mouseX, mouseY, () -> caveBand = step(bands, band, 1)));
				x += 22 + lw + 22;
			}
		}
		// Dimension: one button that cycles through them.
		String[][] dims = {{"minecraft:overworld", "Overworld"}, {"minecraft:the_nether", "Nether"}, {"minecraft:the_end", "End"}};
		int current = 0;
		for (int i = 0; i < dims.length; i++) {
			if (dims[i][0].equals(viewedDimension)) {
				current = i;
			}
		}
		String dimLabel = dims[current][1] + "  >";
		int dw = this.textRenderer.getWidth(dimLabel) + 16;
		int next = (current + 1) % dims.length;
		hits.add(VeloUi.pill(context, contentX() + contentWidth() - dw, y, dw, 16, dimLabel, 0, mouseX, mouseY, () -> {
			viewedDimension = dims[next][0];
			follow = false;
		}));

		// View controls float in the map's top-right corner.
		int rx = contentX() + contentWidth() - 6;
		int cy = mapTop() + 6;
		rx -= 20;
		hits.add(VeloUi.pill(context, rx, cy, 20, 16, "+", 0, mouseX, mouseY, () -> targetZoom = Math.min(16f, targetZoom * 1.5f)));
		rx -= 24;
		hits.add(VeloUi.pill(context, rx, cy, 20, 16, "-", 0, mouseX, mouseY, () -> targetZoom = Math.max(0.05f, targetZoom / 1.5f)));
		for (Object[] toggle : new Object[][] {
				{"Follow me", follow, (Runnable) () -> follow = !follow},
				{"Waypoints", showWaypoints, (Runnable) () -> showWaypoints = !showWaypoints},
				{"Grid", grid, (Runnable) () -> grid = !grid}}) {
			String label = (String) toggle[0];
			int w = this.textRenderer.getWidth(label) + 14;
			rx -= w + 4;
			hits.add(VeloUi.pill(context, rx, cy, w, 16, label, (Boolean) toggle[1] ? 1 : 0, mouseX, mouseY, (Runnable) toggle[2]));
		}
	}

	private static Integer step(List<Integer> bands, int current, int direction) {
		if (bands.isEmpty()) {
			return current + direction;
		}
		int index = bands.indexOf(current);
		if (index < 0) {
			return bands.get(0);
		}
		return bands.get(Math.max(0, Math.min(bands.size() - 1, index + direction)));
	}

	private void drawStatus(DrawContext context, int mouseX, int mouseY, MapStore store) {
		int y = contentBottom() - 11;
		String text;
		if (mouseY >= mapTop() && mouseY < mapBottom()) {
			int bx = (int) Math.floor(blockX(mouseX));
			int bz = (int) Math.floor(blockZ(mouseY));
			text = WorldMapModule.showCoordinates() ? "X " + bx + "   Z " + bz : "";
			if (store != null) {
				MapRegion region = store.region(Math.floorDiv(bx, MapRegion.SIZE), Math.floorDiv(bz, MapRegion.SIZE), false);
				if (region != null) {
					int i = Math.floorMod(bz, MapRegion.SIZE) * MapRegion.SIZE + Math.floorMod(bx, MapRegion.SIZE);
					if (region.rgb[i] != 0) {
						String biome = store.biomeName(region.biome[i]);
						biome = biome.contains(":") ? biome.substring(biome.indexOf(':') + 1) : biome;
						if (WorldMapModule.showCoordinates()) {
							text += "   Y " + region.height[i];
						}
						if (WorldMapModule.showBiomes()) {
							text += "   " + biome.replace('_', ' ');
						}
					}
				}
			}
		} else {
			text = "";
		}
		context.drawTextWithShadow(this.textRenderer, text, contentX(), y, VeloStyle.text());
		String help = "Drag to move  ·  scroll to zoom  ·  right-click for waypoints  ·  " + Math.round(zoom * 100) + "%";
		context.drawTextWithShadow(this.textRenderer, help, contentX() + contentWidth() - this.textRenderer.getWidth(help), y, VeloStyle.textFaint());
	}

	private void drawMenu(DrawContext context, int mouseX, int mouseY) {
		int mx = (int) menuAt[0];
		int my = (int) menuAt[1];
		int bx = (int) Math.floor(menuAt[2]);
		int bz = (int) Math.floor(menuAt[3]);
		int w = 150;
		boolean coords = WorldMapModule.showCoordinates();
		VeloDraw.fillRounded(context, mx, my, w, coords ? 58 : 40, 7, 0xF0141418);
		VeloDraw.strokeRounded(context, mx, my, w, coords ? 58 : 40, 7, VeloStyle.border());
		context.drawTextWithShadow(this.textRenderer, coords ? "X " + bx + ", Z " + bz : "Here", mx + 8, my + 6, VeloStyle.textMuted());
		hits.add(0, VeloUi.pill(context, mx + 4, my + 18, w - 8, 16, "Add waypoint here", 1, mouseX, mouseY, () -> {
			menuAt = null;
			int y = surfaceY(bx, bz);
			this.client.setScreen(WaypointEditScreen.createAt(this, WorldMap.currentWorld(), viewedDimension, bx, y, bz));
		}));
		if (coords) {
			hits.add(0, VeloUi.pill(context, mx + 4, my + 38, w - 8, 16, "Copy coordinates", 0, mouseX, mouseY, () -> {
				menuAt = null;
				MinecraftClient.getInstance().keyboard.setClipboard(bx + " " + surfaceY(bx, bz) + " " + bz);
			}));
		}
	}

	private int surfaceY(int bx, int bz) {
		MapStore store = store();
		if (store != null) {
			MapRegion region = store.region(Math.floorDiv(bx, MapRegion.SIZE), Math.floorDiv(bz, MapRegion.SIZE), false);
			if (region != null) {
				int i = Math.floorMod(bz, MapRegion.SIZE) * MapRegion.SIZE + Math.floorMod(bx, MapRegion.SIZE);
				if (region.rgb[i] != 0) {
					return region.height[i] + 1;
				}
			}
		}
		double[] player = MapCompat.player();
		return player == null ? 64 : (int) Math.floor(player[1]);
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		if (menuAt != null) {
			menuAt = null;
			return true;
		}
		if (click.y() >= mapTop() && click.y() < mapBottom() && click.x() >= contentX() && click.x() < contentX() + contentWidth()) {
			if (click.button() == 1) {
				menuAt = new double[] {Math.min(click.x(), contentX() + contentWidth() - 152), Math.min(click.y(), mapBottom() - 60),
						blockX(click.x()), blockZ(click.y())};
				return true;
			}
			dragging = true;
			dragged = 0;
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging) {
			dragged += Math.abs(offsetX) + Math.abs(offsetY);
			if (dragged > 2) {
				follow = false;
			}
			centerX -= offsetX / zoom;
			centerZ -= offsetY / zoom;
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		dragging = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		targetZoom = Math.max(0.05f, Math.min(16f, targetZoom * (verticalAmount > 0 ? 1.25f : 0.8f)));
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == WorldMapKeys.keyCode() && menuAt == null) {
			requestClose();
			return true;
		}
		if (input.key() == GLFW.GLFW_KEY_SPACE) {
			follow = true;
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}
}
