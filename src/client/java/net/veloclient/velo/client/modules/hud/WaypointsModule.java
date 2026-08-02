package net.veloclient.velo.client.modules.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.debug.gizmo.GizmoDrawing;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Manual, user-placed waypoints only - never auto-populated from world
 * scanning, so this is a personal note system rather than X-ray-adjacent
 * (design spec section 6.2). Renders a 3D marker + label at each waypoint in
 * the current dimension (via the same {@code GizmoDrawing} API vanilla's own
 * debug renderers use) and a distance-sorted list in the HUD.
 */
public final class WaypointsModule extends AbstractModule implements HudModule, Configurable {

	public static final KeyBinding ADD_WAYPOINT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.add_waypoint", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	private static final int MARKER_COLOR = 0xFFFFAA00;
	private static final int MAX_LISTED = 5;

	private final HudPosition position = new HudPosition(0.02f, 0.36f);
	private List<Waypoint> waypoints = new ArrayList<>();

	public WaypointsModule() {
		super("waypoints", "Waypoints", "Manually place and track named waypoints; never auto-populated.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(this::onWorldRender);
	}

	@Override
	public void onEnable() {
		waypoints = WaypointStore.load();
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled() || client.player == null || client.world == null) {
			return;
		}
		while (ADD_WAYPOINT.wasPressed()) {
			String dimension = client.world.getRegistryKey().getValue().toString();
			String name = "WP-" + (waypoints.size() + 1);
			waypoints.add(new Waypoint(name, dimension, client.player.getX(), client.player.getY(), client.player.getZ()));
			WaypointStore.save(waypoints);
		}
	}

	private void onWorldRender(WorldRenderContext context) {
		if (!isEnabled()) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null) {
			return;
		}
		String dimension = client.world.getRegistryKey().getValue().toString();
		for (Waypoint waypoint : waypoints) {
			if (!waypoint.dimension().equals(dimension)) {
				continue;
			}
			BlockPos pos = BlockPos.ofFloored(waypoint.x(), waypoint.y(), waypoint.z());
			GizmoDrawing.blockLabel(waypoint.name(), pos, 0, MARKER_COLOR, 1.2f);
			GizmoDrawing.point(new Vec3d(waypoint.x(), waypoint.y(), waypoint.z()), MARKER_COLOR, 6f).ignoreOcclusion();
		}
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			return;
		}
		String dimension = client.world.getRegistryKey().getValue().toString();
		List<Waypoint> nearby = waypoints.stream()
				.filter(w -> w.dimension().equals(dimension))
				.sorted(Comparator.comparingDouble(w -> distanceSq(client, w)))
				.limit(MAX_LISTED)
				.toList();

		int lineHeight = client.textRenderer.fontHeight + 1;
		int rowY = y;
		for (Waypoint waypoint : nearby) {
			double distance = Math.sqrt(distanceSq(client, waypoint));
			String line = String.format("%s: %.0fm", waypoint.name(), distance);
			context.drawTextWithShadow(client.textRenderer, line, x, rowY, 0xFFFFAA00);
			rowY += lineHeight;
		}
	}

	private static double distanceSq(MinecraftClient client, Waypoint waypoint) {
		return client.player.squaredDistanceTo(waypoint.x(), waypoint.y(), waypoint.z());
	}

	@Override
	public int width() {
		return 160;
	}

	@Override
	public int height() {
		return MAX_LISTED * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(KeybindConfig.field("Add Waypoint Key", ADD_WAYPOINT));
	}
}
