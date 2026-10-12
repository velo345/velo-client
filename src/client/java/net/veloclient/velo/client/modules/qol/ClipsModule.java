package net.veloclient.velo.client.modules.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.clips.ClipRecorder;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Instant replay: keeps the last 30 seconds (configurable) of gameplay in memory and saves them as
 * a clip with one key - after the moment happened, ShadowPlay-style. Clips open in R-Shift >
 * Menu > Clips & Screenshots and in the launcher's gallery. Costs a little GPU readback and one
 * background thread while on, so it's off until you turn it on.
 */
public final class ClipsModule extends AbstractModule implements Configurable {

	public static final KeyBinding SAVE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.save_clip", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.F8, VeloKeybinds.CATEGORY));

	private static final List<String> FPS = List.of("15", "20", "24", "30", "60");
	private static final List<String> RESOLUTION = List.of("360p", "480p", "720p", "1080p");

	private double seconds = 30;
	private String fps = "24";
	private String resolution = "720p";
	private double quality = 75;

	public ClipsModule() {
		super("clips", "Clips", "Save the last 30 seconds of gameplay with one key (F8) - watch them in-game or in the launcher.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (SAVE_KEY.wasPressed()) {
				if (!isEnabled()) {
					NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Clips are off",
							"Turn on the Clips module to keep a replay buffer.", 3500, null, null, null));
					continue;
				}
				saveNow();
			}
		});
	}

	private static void saveNow() {
		double buffered = ClipRecorder.bufferedSeconds();
		ClipRecorder.save("key", null, 0, file -> NotificationOverlay.show(new NotificationOverlay.Toast(null, null,
				file != null ? "Clip saved" : "Nothing to save yet",
				file != null ? String.format(java.util.Locale.ROOT, "%.0f seconds - R-Shift > Menu > Clips", buffered) : "The replay buffer is still empty.",
				4000, file == null ? null : () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(
						new net.veloclient.velo.client.gui.media.MediaScreen(null, file)), null, null)));
	}

	private void apply() {
		int height = Integer.parseInt(resolution.replace("p", ""));
		ClipRecorder.configure(isEnabled(), Integer.parseInt(fps), height, (int) Math.round(seconds), (float) (quality / 100.0));
	}

	@Override
	public void onEnable() {
		ClipRecorder.configure(true, Integer.parseInt(fps), Integer.parseInt(resolution.replace("p", "")), (int) Math.round(seconds),
				(float) (quality / 100.0));
	}

	@Override
	public void onDisable() {
		ClipRecorder.configure(false, 24, 720, 30, 0.75f);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Save Clip Key", SAVE_KEY),
				new ConfigField.SliderField("Clip Length (s)", 10, 120, () -> seconds, v -> {
					seconds = Math.round(v / 5) * 5;
					apply();
				}, v -> (int) Math.round(v) + "s"),
				new ConfigField.ChoiceField("Frame Rate", FPS, () -> fps, v -> {
					fps = v;
					apply();
				}),
				new ConfigField.ChoiceField("Resolution", RESOLUTION, () -> resolution, v -> {
					resolution = v;
					apply();
				}),
				new ConfigField.SliderField("Quality", 40, 95, () -> quality, v -> {
					quality = Math.round(v);
					apply();
				}, v -> (int) Math.round(v) + "%"));
	}
}
