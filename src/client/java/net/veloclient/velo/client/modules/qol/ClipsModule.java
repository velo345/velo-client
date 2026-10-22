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
	// Clips Pro (Velo+): a real video buffer through FFmpeg.
	private static final List<String> PRO_QUALITY = List.of("Balanced", "High", "Max");
	private boolean pro = true;
	private double proMinutes = 3;
	private String proFps = "60";
	private String proResolution = "1080p";
	private String proQuality = "High";
	private boolean compressSaved = true;
	// Clips Pro sound: what you hear (game, Discord, music) and optionally your microphone.
	private boolean desktopSound = true;
	private double desktopVolume = 100;
	private boolean micSound;
	private double micVolume = 100;
	private int plusCheck;

	public ClipsModule() {
		super("clips", "Clips", "Save the last 30 seconds of gameplay with one key (F8). Velo+ Clips Pro: 2-5 minutes at 1080p60, auto clips and share links.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Velo+ is only known once you're signed in: switch to Clips Pro (or back) when that changes.
			if (isEnabled() && pro && ++plusCheck % 100 == 0) {
				boolean plus = net.veloclient.velo.client.namestyle.NameStyles.plus();
				if (plus != net.veloclient.velo.client.clips.ProClipRecorder.active()) {
					apply();
				}
			}
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

	/** Clips Pro when it's on and you're Velo+, else the normal in-memory buffer. */
	private void apply() {
		boolean usePro = isEnabled() && pro && net.veloclient.velo.client.namestyle.NameStyles.plus();
		int kbps = switch (proQuality) {
			case "Balanced" -> 8000;
			case "Max" -> 20000;
			default -> 12000;
		};
		net.veloclient.velo.client.clips.ProClipRecorder.configureAudio(desktopSound, (float) (desktopVolume / 100), micSound, (float) (micVolume / 100));
		net.veloclient.velo.client.clips.ProClipRecorder.configure(usePro, Integer.parseInt(proFps), Integer.parseInt(proResolution.replace("p", "")),
				(int) Math.round(proMinutes * 60), kbps, compressSaved);
		int height = Integer.parseInt(resolution.replace("p", ""));
		ClipRecorder.configure(isEnabled() && !usePro, Integer.parseInt(fps), height, (int) Math.round(seconds), (float) (quality / 100.0));
	}

	@Override
	public void onEnable() {
		apply();
	}

	@Override
	public void onDisable() {
		net.veloclient.velo.client.clips.ProClipRecorder.configure(false, 60, 1080, 180, 12000, true);
		ClipRecorder.configure(false, 24, 720, 30, 0.75f);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Save Clip Key", SAVE_KEY),
				new ConfigField.ToggleField("Clips Pro (Velo+)", () -> pro, v -> {
					pro = v;
					if (v && !net.veloclient.velo.client.namestyle.NameStyles.plus()) {
						NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Clips Pro is part of Velo+",
								"A 2-5 minute 1080p60 buffer, auto clips and share links. Get it in the Store.", 5000, null, null, null));
					}
					apply();
				}),
				new ConfigField.SliderField("Pro Buffer (min)", 2, 5, () -> proMinutes, v -> {
					proMinutes = Math.round(v);
					apply();
				}, v -> (int) Math.round(v) + " min"),
				new ConfigField.ChoiceField("Pro Frame Rate", List.of("30", "60"), () -> proFps, v -> {
					proFps = v;
					apply();
				}),
				new ConfigField.ChoiceField("Pro Resolution", List.of("720p", "1080p"), () -> proResolution, v -> {
					proResolution = v;
					apply();
				}),
				new ConfigField.ChoiceField("Pro Quality", PRO_QUALITY, () -> proQuality, v -> {
					proQuality = v;
					apply();
				}),
				new ConfigField.ToggleField("Record Game & Discord Sound", () -> desktopSound, v -> {
					desktopSound = v;
					apply();
				}),
				new ConfigField.SliderField("Game & Discord Volume", 0, 200, () -> desktopVolume, v -> {
					desktopVolume = Math.round(v / 5) * 5;
					apply();
				}, v -> (int) Math.round(v) + "%"),
				new ConfigField.ToggleField("Record My Microphone", () -> micSound, v -> {
					micSound = v;
					apply();
				}),
				new ConfigField.SliderField("Microphone Volume", 0, 200, () -> micVolume, v -> {
					micVolume = Math.round(v / 5) * 5;
					apply();
				}, v -> (int) Math.round(v) + "%"),
				new ConfigField.ToggleField("Shrink Saved Clips", () -> compressSaved, v -> {
					compressSaved = v;
					apply();
				}),
				new ConfigField.ToggleField("Auto-Clip Kills", () -> net.veloclient.velo.client.clips.AutoClips.kills,
						v -> net.veloclient.velo.client.clips.AutoClips.kills = v),
				new ConfigField.ToggleField("Auto-Clip Wins", () -> net.veloclient.velo.client.clips.AutoClips.wins,
						v -> net.veloclient.velo.client.clips.AutoClips.wins = v),
				new ConfigField.SliderField("Auto-Clip Length (s)", 10, 120, () -> net.veloclient.velo.client.clips.AutoClips.length,
						v -> net.veloclient.velo.client.clips.AutoClips.length = (int) (Math.round(v / 5) * 5), v -> (int) Math.round(v) + "s"),
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
