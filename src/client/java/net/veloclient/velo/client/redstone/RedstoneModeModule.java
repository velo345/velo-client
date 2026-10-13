package net.veloclient.velo.client.redstone;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.gui.window.ModuleConfigScreen;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.ModuleRegistry;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Redstone Mode: a focused building mode for redstone (toggle with R). While it's on, the overlay
 * shows the tools and keys, Tick Mode freezes the world (arrow keys step / rewind), every other
 * Velo module keybind is paused, and the redstone tools take over the mouse - box selection,
 * copy / cut / paste / move / stack / erase / replace with rotate and flip, undo, schematics,
 * timeline recording, redstone and water wiring. World editing needs singleplayer; the redstone
 * analysis (dust length, clocks) works anywhere.
 */
public final class RedstoneModeModule extends AbstractModule implements Configurable {

	private static RedstoneModeModule instance;

	// ---- settings ----
	static String dustWarnings = "Always";
	static boolean clockTracking = true;
	static boolean preserveStates = true;
	static String wireStyle = "Dust";
	static String wireBlock = "Auto";
	static String pathMode = "Go Around";
	static boolean delayMode = false;
	static double delayTicks = 1;
	static String waterBase = "Auto";
	static String waterSeparator = "Sign";
	static String waterWalls = "Glass";
	static double timelineFrames = 600;
	static double playbackSpeed = 1;
	static double tickHistory = 400;
	static double sprintTicks = 200;

	public RedstoneModeModule() {
		super("redstone-mode", "Redstone Mode", "Axiom-style redstone building: selection, copy/move/stack, wiring, tick stepping, timeline replay.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		RmKeys.init();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (RmKeys.TOGGLE.wasPressed()) {
				setEnabled(!isEnabled());
			}
			RmInput.tick();
		});
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> RmRender.world());
	}

	/** True while Redstone Mode is on. */
	public static boolean active() {
		RedstoneModeModule m = instance;
		return m != null && m.isEnabled();
	}

	public static String dustWarnings() {
		return dustWarnings;
	}

	public static boolean clockTracking() {
		return clockTracking;
	}

	public static boolean preserveStates() {
		return preserveStates;
	}

	@Override
	public void onEnable() {
		RmState.cancelPending();
		if (RmWorld.singleplayer()) {
			TickControls.enter((int) Math.round(tickHistory), 32);
			NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Redstone Mode on",
					"World frozen - → step, ← back, ↑ play. Z: next tool, O: settings, R: leave.", 4500, null, null, null));
		} else {
			NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Redstone Mode on",
					"On servers only the redstone analysis works - editing tools need singleplayer.", 4500, null, null, null));
		}
	}

	@Override
	public void onDisable() {
		RmState.cancelPending();
		Timeline.stopAll();
		TickControls.exit();
	}

	static int sprintTicks() {
		return (int) Math.round(sprintTicks);
	}

	static void openSettings() {
		RedstoneModeModule m = instance;
		if (m != null) {
			MinecraftClient.getInstance().setScreen(new ModuleConfigScreen(m, () -> MinecraftClient.getInstance().setScreen(null)));
		}
	}

	private static ConfigField moduleShortcut(String label, String id) {
		return new ConfigField.ActionButtonField(label, () -> ModuleRegistry.get(id).ifPresent(module ->
				MinecraftClient.getInstance().setScreen(new ModuleConfigScreen(module, RedstoneModeModule::openSettings))));
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		fields.add(new ConfigField.HeaderField("Analysis"));
		fields.add(new ConfigField.ChoiceField("Dust Length Warnings", List.of("Always", "Redstone Mode", "Off"), () -> dustWarnings,
				v -> dustWarnings = v));
		fields.add(new ConfigField.ToggleField("Show Clock Speeds", () -> clockTracking, v -> clockTracking = v));
		fields.add(new ConfigField.HeaderField("Copy & Paste"));
		fields.add(new ConfigField.ToggleField("Keep Redstone Running When Pasting", () -> preserveStates, v -> preserveStates = v));
		fields.add(new ConfigField.HeaderField("Redstone Wiring"));
		fields.add(new ConfigField.ChoiceField("Wire Type", List.of("Dust", "Observer"), () -> wireStyle, v -> wireStyle = v));
		fields.add(new ConfigField.ChoiceField("Wire Block", WireBlocks.CHOICES, () -> wireBlock, v -> wireBlock = v));
		fields.add(new ConfigField.ChoiceField("Path", List.of("Go Around", "Go Through"), () -> pathMode, v -> pathMode = v));
		fields.add(new ConfigField.ToggleField("Delay Mode (repeaters)", () -> delayMode, v -> delayMode = v));
		fields.add(new ConfigField.SliderField("Repeater Delay (ticks)", 1, 4, () -> delayTicks, v -> delayTicks = Math.round(v),
				v -> String.valueOf(Math.round(v))));
		fields.add(new ConfigField.HeaderField("Water Wiring"));
		fields.add(new ConfigField.ChoiceField("Floor", List.of("Auto", "Blue Ice", "Packed Ice"), () -> waterBase, v -> waterBase = v));
		fields.add(new ConfigField.ChoiceField("Separator", List.of("Sign", "Auto"), () -> waterSeparator, v -> waterSeparator = v));
		fields.add(new ConfigField.ChoiceField("Walls", List.of("Glass", "Stone Bricks", "Wire Block"), () -> waterWalls, v -> waterWalls = v));
		fields.add(new ConfigField.HeaderField("Ticks & Timeline"));
		fields.add(new ConfigField.SliderField("Rewind History (ticks)", 40, 2400, () -> tickHistory, v -> tickHistory = Math.round(v / 20) * 20,
				v -> Math.round(v) + " (" + Math.round(v / 20) + "s)"));
		fields.add(new ConfigField.SliderField("Sprint Length (ticks)", 20, 6000, () -> sprintTicks, v -> sprintTicks = Math.round(v / 20) * 20,
				v -> String.valueOf(Math.round(v))));
		fields.add(new ConfigField.SliderField("Timeline Length (ticks)", 40, 2400, () -> timelineFrames,
				v -> timelineFrames = Math.round(v / 20) * 20, v -> Math.round(v) + " (" + Math.round(v / 20) + "s)"));
		fields.add(new ConfigField.SliderField("Playback Speed", 0.05, 4, () -> playbackSpeed, v -> playbackSpeed = Math.round(v * 20) / 20.0,
				v -> String.format(java.util.Locale.ROOT, "%.2fx", v)));
		fields.add(new ConfigField.HeaderField("World"));
		fields.add(moduleShortcut("Hopper Speed Settings", "hopper-speed"));
		fields.add(moduleShortcut("Fast Grow Settings", "fast-grow"));
		return fields;
	}
}
