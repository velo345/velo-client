package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * A Xaero-style top-down minimap: nearby terrain sampled through vanilla's
 * own block-to-map-color system ({@link MinimapManager}), drawn rotating
 * underneath a fixed player arrow (or north-locked, with a rotating arrow
 * instead) inside a framed square box, with optional coordinates/facing
 * readout below it. Sized/positioned/scaled through the same drag-and-scale
 * system every other HUD module uses (design spec section 5/7) - nothing
 * minimap-specific there.
 */
public final class MinimapModule extends AbstractModule implements HudModule, Configurable {

	private static final int BORDER_THICKNESS = 2;
	private static final int ARROW_SIZE = 6;

	private final HudPosition position = new HudPosition(0.85f, 0.02f);

	private int mapSize = 128;
	private int viewRadius = 96;
	private int refreshMillis = 750;
	private boolean rotateWithPlayer = true;
	private boolean showArrow = true;
	private boolean showCoordinates = true;
	private boolean showFacing = true;
	private int borderColor = 0xFF2A2A2A;
	private int arrowColor = 0xFFFF4747;

	public MinimapModule() {
		super("minimap", "Minimap", "A rotating top-down minimap of nearby terrain, sampled from vanilla's own map colors.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		PlayerEntity player = client.player;
		if (player == null || client.world == null) {
			return;
		}

		Theme theme = ThemeManager.active();
		// Plain rectangular fill, not a rounded one - a solid square behind
		// an inset viewport is both a sharp-cornered border and (mostly)
		// centered. The right/bottom edges get one extra pixel of inset
		// pulled back in - fill()/enableScissor() were visibly leaving a
		// thicker strip there than on the left/top at non-1.0 HUD scale,
		// most likely from the outer HudManager scale transform rounding
		// the two calls' corners slightly differently - so this corrects
		// for it directly rather than chasing the rounding itself.
		context.fill(x, y, x + mapSize, y + mapSize, borderColor);

		int viewportX = x + BORDER_THICKNESS;
		int viewportY = y + BORDER_THICKNESS;
		int viewportSize = mapSize - BORDER_THICKNESS * 2 + 1;

		// Sampled a bit wider than the visible view radius so a rotated map
		// still has real terrain data at the box's corners instead of
		// running out of texture there - a square raster's own corners
		// already reach sqrt(2)*radius, but only along its own fixed
		// diagonals, not whichever screen-space diagonal the box's corners
		// land on after rotation, so this needs real margin, not just luck.
		int sampleRadius = Math.max(viewRadius, Math.round(viewRadius * 1.5f));
		Identifier texture = MinimapManager.textureFor(sampleRadius, refreshMillis);

		context.enableScissor(viewportX, viewportY, viewportX + viewportSize, viewportY + viewportSize);
		context.fill(viewportX, viewportY, viewportX + viewportSize, viewportY + viewportSize, 0xFF1A1A1A);
		if (texture != null) {
			drawRotatedMap(context, player, texture, sampleRadius, viewportX, viewportY, viewportSize);
		}
		context.disableScissor();

		if (showArrow) {
			drawPlayerArrow(context, player, viewportX + viewportSize / 2, viewportY + viewportSize / 2);
		}

		int infoY = y + mapSize + 2;
		if (showCoordinates) {
			String coords = String.format("%d, %d, %d", player.getBlockX(), player.getBlockY(), player.getBlockZ());
			context.drawTextWithShadow(client.textRenderer, coords, x, infoY, theme.text());
			infoY += client.textRenderer.fontHeight + 1;
		}
		if (showFacing) {
			context.drawTextWithShadow(client.textRenderer, facingLabel(player.getYaw()), x, infoY, theme.text());
		}
	}

	/** Screen angle (radians) that rotates the map so the player's facing direction points up - 0 rotation leaves the map north-up, since row 0 of the sampled texture is the northernmost column. Facing yaw 180 is due north, so that's the zero point. Flip the sign here if a live test shows it turning the wrong way. */
	private static float mapRotationRadians(float yaw) {
		return (float) Math.toRadians(180 - yaw);
	}

	private void drawRotatedMap(DrawContext context, PlayerEntity player, Identifier texture, int sampleRadius,
			int viewportX, int viewportY, int viewportSize) {
		int textureSize = sampleRadius * 2 + 1;
		float scale = viewportSize / (2f * viewRadius);
		float offsetX = (float) (player.getX() - MinimapManager.centerX());
		float offsetZ = (float) (player.getZ() - MinimapManager.centerZ());

		context.getMatrices().pushMatrix();
		context.getMatrices().translate(viewportX + viewportSize / 2f, viewportY + viewportSize / 2f);
		if (rotateWithPlayer) {
			context.getMatrices().rotate(mapRotationRadians(player.getYaw()));
		}
		context.getMatrices().scale(scale, scale);
		context.getMatrices().translate(-(sampleRadius + offsetX), -(sampleRadius + offsetZ));
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0f, 0f,
				textureSize, textureSize, textureSize, textureSize, textureSize, textureSize);
		context.getMatrices().popMatrix();
	}

	private void drawPlayerArrow(DrawContext context, PlayerEntity player, int centerX, int centerY) {
		float angle = rotateWithPlayer ? 0f : (float) Math.toRadians(player.getYaw() + 180);
		float sin = (float) Math.sin(angle);
		float cos = (float) Math.cos(angle);
		// A small filled triangle, rotated by hand (three points around the
		// origin) rather than through the matrix stack, so its thickness
		// doesn't get stretched by the map's own zoom scale.
		int[][] localPoints = {{0, -ARROW_SIZE}, {-ARROW_SIZE / 2, ARROW_SIZE / 2}, {ARROW_SIZE / 2, ARROW_SIZE / 2}};
		int[] xs = new int[3];
		int[] ys = new int[3];
		for (int i = 0; i < 3; i++) {
			int lx = localPoints[i][0];
			int ly = localPoints[i][1];
			xs[i] = centerX + Math.round(lx * cos - ly * sin);
			ys[i] = centerY + Math.round(lx * sin + ly * cos);
		}
		fillTriangle(context, xs, ys, arrowColor);
	}

	/** Cheap scanline triangle fill - the arrow is only ever 3 points, not worth pulling in a general polygon renderer for. */
	private static void fillTriangle(DrawContext context, int[] xs, int[] ys, int color) {
		int minY = Math.min(ys[0], Math.min(ys[1], ys[2]));
		int maxY = Math.max(ys[0], Math.max(ys[1], ys[2]));
		for (int py = minY; py <= maxY; py++) {
			double minX = Double.MAX_VALUE;
			double maxX = -Double.MAX_VALUE;
			for (int i = 0; i < 3; i++) {
				int j = (i + 1) % 3;
				int y1 = ys[i];
				int y2 = ys[j];
				if (y1 == y2) {
					continue;
				}
				if ((py >= y1 && py < y2) || (py >= y2 && py < y1)) {
					double t = (py - y1) / (double) (y2 - y1);
					double x = xs[i] + t * (xs[j] - xs[i]);
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
				}
			}
			if (minX <= maxX) {
				context.fill((int) Math.round(minX), py, (int) Math.round(maxX) + 1, py + 1, color);
			}
		}
	}

	private static String facingLabel(float yaw) {
		float normalized = yaw % 360f;
		if (normalized < 0) {
			normalized += 360f;
		}
		String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		int index = Math.round(normalized / 45f) % 8;
		return names[index] + " " + Math.round(normalized) + "°";
	}

	@Override
	public int width() {
		return mapSize;
	}

	@Override
	public int height() {
		int extra = 0;
		if (showCoordinates) {
			extra += MinecraftClient.getInstance().textRenderer.fontHeight + 1;
		}
		if (showFacing) {
			extra += MinecraftClient.getInstance().textRenderer.fontHeight + 1;
		}
		return mapSize + (extra > 0 ? extra + 2 : 0);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Map Size", 64, 220, () -> mapSize, v -> mapSize = (int) v, v -> Math.round(v) + "px"),
				new ConfigField.SliderField("View Radius", 24, 200, () -> viewRadius, v -> viewRadius = (int) v, v -> Math.round(v) + " blocks"),
				new ConfigField.SliderField("Refresh Rate", 200, 3000, () -> refreshMillis, v -> refreshMillis = (int) v, v -> Math.round(v) + "ms"),
				new ConfigField.ToggleField("Rotate With Player", () -> rotateWithPlayer, v -> rotateWithPlayer = v),
				new ConfigField.ToggleField("Show Player Arrow", () -> showArrow, v -> showArrow = v),
				new ConfigField.ToggleField("Show Coordinates", () -> showCoordinates, v -> showCoordinates = v),
				new ConfigField.ToggleField("Show Facing", () -> showFacing, v -> showFacing = v),
				new ConfigField.ColorField("Border Color", () -> borderColor, v -> borderColor = v, false),
				new ConfigField.ColorField("Arrow Color", () -> arrowColor, v -> arrowColor = v, false));
	}
}
