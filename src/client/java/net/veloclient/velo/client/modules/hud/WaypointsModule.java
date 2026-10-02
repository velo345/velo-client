package net.veloclient.velo.client.modules.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.gui.WaypointEditScreen;
import net.veloclient.velo.client.gui.WaypointsScreen;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.util.ModuleProfiler;
import net.veloclient.velo.client.waypoints.Waypoint;
import net.veloclient.velo.client.waypoints.WaypointManager;
import net.veloclient.velo.client.waypoints.WaypointRenderer;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Waypoints: per-world, per-dimension markers with a colored beam in the world and an
 * icon + distance marker on screen (and on the minimap). Manual only - never auto-populated from
 * world scanning (design spec section 6.2), apart from your own death position when "Death
 * Waypoints" is on. Everything is managed in the Waypoints menu (P); N drops a new one where you
 * stand. Turning this module off hides them but keeps them all.
 */
public final class WaypointsModule extends AbstractModule implements Configurable {

	public static final KeyBinding OPEN_MENU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.open_waypoints", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P, VeloKeybinds.CATEGORY));
	public static final KeyBinding NEW_WAYPOINT = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.new_waypoint", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, VeloKeybinds.CATEGORY));

	private static boolean deathWaypoints = true;
	private boolean wasDead;

	public WaypointsModule() {
		super("waypoints", "Waypoints",
				"Per-world waypoints with beams, on-screen distance markers and minimap dots. P opens the menu, N adds one where you stand.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, true);
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.WORLD_RENDER, WaypointRenderer::renderWorld));
	}

	@Override
	public void onEnable() {
		WaypointRenderer.Settings.enabled = true;
	}

	@Override
	public void onDisable() {
		WaypointRenderer.Settings.enabled = false;
	}

	public static boolean deathWaypointsEnabled() {
		return deathWaypoints;
	}

	public static void setDeathWaypoints(boolean value) {
		deathWaypoints = value;
	}

	private void onTick(MinecraftClient client) {
		// The menu and the "new here" key work even with rendering switched off.
		while (OPEN_MENU.wasPressed()) {
			if (ClientCompat.currentScreen() == null) {
				client.setScreen(new WaypointsScreen(null));
			}
		}
		while (NEW_WAYPOINT.wasPressed()) {
			if (ClientCompat.currentScreen() == null && client.player != null) {
				client.setScreen(WaypointEditScreen.createHere(null));
			}
		}
		trackDeath(client);
	}

	/** Death waypoint, recorded once per death at the moment health hits zero. */
	private void trackDeath(MinecraftClient client) {
		if (client.player == null || client.world == null) {
			wasDead = false;
			return;
		}
		boolean dead = client.player.getHealth() <= 0f;
		if (dead && !wasDead && deathWaypoints) {
			Waypoint death = WaypointManager.recordDeath(client.player.getX(), client.player.getY(), client.player.getZ());
			if (death != null) {
				NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Death waypoint saved",
						String.format(java.util.Locale.ROOT, "%d, %d, %d - %s", death.blockX(), death.blockY(), death.blockZ(),
								WaypointManager.dimensionLabel(death.dimension)), 5000,
						() -> MinecraftClient.getInstance().setScreen(new WaypointsScreen(null)), null, null));
			}
		}
		wasDead = dead;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ActionButtonField("Open Waypoints Menu...", () -> MinecraftClient.getInstance().setScreen(
						new WaypointsScreen(ClientCompat.currentScreen()))),
				KeybindConfig.field("Waypoints Menu Key", OPEN_MENU),
				KeybindConfig.field("New Waypoint Key", NEW_WAYPOINT),
				new ConfigField.ToggleField("Death Waypoints", () -> deathWaypoints, v -> deathWaypoints = v),
				new ConfigField.ToggleField("Show Beams", () -> WaypointRenderer.Settings.beams, v -> WaypointRenderer.Settings.beams = v),
				new ConfigField.SliderField("Beam Opacity", 0.1, 1.0, () -> WaypointRenderer.Settings.beamOpacity,
						v -> WaypointRenderer.Settings.beamOpacity = (float) v, v -> Math.round(v * 100) + "%"),
				new ConfigField.SliderField("Marker Size", 0.6, 2.0, () -> WaypointRenderer.Settings.markerScale,
						v -> WaypointRenderer.Settings.markerScale = (float) v, v -> Math.round(v * 100) + "%"),
				new ConfigField.ToggleField("Show Distance", () -> WaypointRenderer.Settings.showDistance,
						v -> WaypointRenderer.Settings.showDistance = v),
				new ConfigField.ToggleField("Arrows For Off-Screen Waypoints", () -> WaypointRenderer.Settings.edgeArrows,
						v -> WaypointRenderer.Settings.edgeArrows = v),
				new ConfigField.SliderField("Max Distance (0 = unlimited)", 0, 20000, () -> WaypointRenderer.Settings.maxDistance,
						v -> WaypointRenderer.Settings.maxDistance = (int) (Math.round(v / 100) * 100),
						v -> v < 50 ? "Unlimited" : Math.round(v / 100) * 100 + " blocks"),
				new ConfigField.ToggleField("Show On Minimap", () -> showOnMinimap, v -> showOnMinimap = v));
	}

	public static boolean showOnMinimap = true;
}
