package net.veloclient.velo.client.modules.servertools;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.DrawStyle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * Renders chunk section borders for build/world-gen testing (design spec section 6.3) - the
 * current chunk column's four corner edges plus its current 16x16x16 section box in one
 * customizable color, and a finer 2-block subdivision grid in a second one.
 *
 * <p>Its own renderer (loosely modeled on vanilla's own {@code ChunkBorderDebugRenderer}, but
 * with two customizable colors instead of vanilla's fixed red/cyan/yellow) rather than a toggle
 * over vanilla's F3 debug entry, which this module used to just delegate to - same reasoning as
 * {@link HitboxVisualizerModule}'s own switch away from that base class.
 *
 * <p>Every line segment is a thin filled {@link Box}, not a {@code GizmoDrawing.line(...)} call -
 * that API turned out to have a real, confirmed rendering bug on 1.21.11 specifically (segments
 * visibly drifted along with the player instead of staying fixed to the chunk grid, not present
 * on 26.1/26.2's own newer Gizmos facade). {@code box()} was already proven correct and stable on
 * all three versions by {@link WorldBorderVisualizerModule}, so every segment here uses that same
 * call instead, just expanded to a thin box along its two non-length axes.
 */
public final class ChunkBorderOverlayModule extends AbstractModule implements Configurable {

	private static final int GRID_STEP = 2;

	private int chunkColor = 0xFFFF4040;
	private int gridColor = 0xFF40C0FF;
	private double lineWidth = 2.0;

	public ChunkBorderOverlayModule() {
		super("chunk-border-overlay", "Chunk Boundary Overlay",
				"Renders chunk and chunk-section borders around you, for build/world-gen testing, with "
						+ "customizable line colors.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, false);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::onRender);
	}

	private void onRender(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		ClientWorld world = client.world;
		PlayerEntity player = client.player;
		if (world == null || player == null) {
			return;
		}

		double originX = Math.floor(player.getX() / 16.0) * 16.0;
		double originZ = Math.floor(player.getZ() / 16.0) * 16.0;
		double sectionOriginY = Math.floor(player.getY() / 16.0) * 16.0;
		double worldMinY = world.getBottomY();
		double worldMaxY = world.getTopYInclusive() + 1;
		// Thickness in world units (not screen pixels, unlike the old line-width
		// parameter) - scaled off the same slider so it's still adjustable, just
		// through a different unit now that these are thin solids, not strokes.
		double chunkHalfThickness = Math.max(0.02, lineWidth * 0.03);
		double gridHalfThickness = Math.max(0.01, lineWidth * 0.015);

		// Full-height edges at the four corners of the current chunk column.
		for (double dx = 0; dx <= 16; dx += 16) {
			for (double dz = 0; dz <= 16; dz += 16) {
				verticalSegment(originX + dx, worldMinY, originZ + dz, worldMaxY, chunkColor, chunkHalfThickness);
			}
		}

		// The current 16x16x16 chunk section, outlined as one box.
		Box section = new Box(originX, sectionOriginY, originZ, originX + 16, sectionOriginY + 16, originZ + 16);
		GizmoDrawing.box(section, DrawStyle.stroked(chunkColor, (float) lineWidth)).ignoreOcclusion();

		// A finer subdivision grid on the two near faces, every GRID_STEP blocks.
		for (int i = GRID_STEP; i < 16; i += GRID_STEP) {
			verticalSegment(originX + i, worldMinY, originZ, worldMaxY, gridColor, gridHalfThickness);
			verticalSegment(originX, worldMinY, originZ + i, worldMaxY, gridColor, gridHalfThickness);
		}
		for (double y = worldMinY; y <= worldMaxY; y += GRID_STEP) {
			horizontalRing(originX, y, originZ, gridColor, gridHalfThickness);
		}
	}

	private static void verticalSegment(double x, double yMin, double z, double yMax, int color, double halfThickness) {
		Box box = new Box(x - halfThickness, yMin, z - halfThickness, x + halfThickness, yMax, z + halfThickness);
		GizmoDrawing.box(box, DrawStyle.filled(color)).ignoreOcclusion();
	}

	/** The near two edges of a 16x16 chunk column ring at height y - the same pair {@code onRender} drew as two separate lines before. */
	private static void horizontalRing(double originX, double y, double originZ, int color, double halfThickness) {
		Box alongX = new Box(originX, y - halfThickness, originZ - halfThickness, originX + 16, y + halfThickness, originZ + halfThickness);
		Box alongZ = new Box(originX - halfThickness, y - halfThickness, originZ, originX + halfThickness, y + halfThickness, originZ + 16);
		GizmoDrawing.box(alongX, DrawStyle.filled(color)).ignoreOcclusion();
		GizmoDrawing.box(alongZ, DrawStyle.filled(color)).ignoreOcclusion();
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ColorField("Chunk Line Color", () -> chunkColor, v -> chunkColor = v, true),
				new ConfigField.ColorField("Grid Line Color", () -> gridColor, v -> gridColor = v, true),
				new ConfigField.SliderField("Line Width", 0.5, 4.0, () -> lineWidth,
						v -> lineWidth = v, v -> String.format(Locale.ROOT, "%.1f", v)));
	}
}
