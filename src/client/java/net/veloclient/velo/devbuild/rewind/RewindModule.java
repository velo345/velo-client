package net.veloclient.velo.devbuild.rewind;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Velo Rewind: records what happens around you as a 3D replay (not a video) - start / stop with F6, pause / resume with
 * its own key (unbound by default) or from the pause menu. Recordings land in {@code velo-rewind/} as {@code .vrewind}.
 * 26.2+ (see {@link RewindRecorder}).
 */
public final class RewindModule extends AbstractModule implements net.veloclient.velo.module.Configurable {

	public static final KeyBinding RECORD_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.rewind_record", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.F6, VeloKeybinds.CATEGORY));
	public static final KeyBinding PAUSE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.rewind_pause", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.UNKNOWN, VeloKeybinds.CATEGORY));

	// Play / pause while watching a replay: Enter (vanilla's P opens Social Interactions; nothing in vanilla or Essential
	// uses Enter while flying around). Rebindable in Controls.
	public static final KeyBinding PLAY_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.rewind_play", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.ENTER, VeloKeybinds.CATEGORY));

	private static volatile RewindModule instance;
	// recordings recovered after a crash at this start (the library mentions it)
	public static volatile int recovered;

	public RewindModule() {
		super("velo-rewind", "Velo Rewind", "Record 3D replays of what happens around you - F6 to start / stop, pause from the pause menu. AI Access lets AI agents edit and export replays through a local API.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
		RewindScreens.register();
		net.veloclient.velo.client.modules.queue.BackgroundQueueManager.suspended = RewindPlayer::active;
		// closing the game saves what's open: the recording (finished, not lost) and the replay's edits
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			RewindRecorder.shutdown();
			RewindPlayer.flushEdits();
		});
		// recordings a crash left unfinished come back as "<name> (recovered)"
		Thread.ofVirtual().name("velo-rewind-recover").start(() -> {
			var found = RewindWriter.recover(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("velo-rewind"));
			if (!found.isEmpty()) {
				recovered = found.size();
			}
		});
		RewindApi.init();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean on = isEnabled();
			while (RECORD_KEY.wasPressed()) {
				if (on) {
					RewindRecorder.toggle();
				}
			}
			while (PAUSE_KEY.wasPressed()) {
				if (on) {
					RewindRecorder.togglePause();
				}
			}
			RewindRecorder.tick();
			RewindSaveScreen.pump();
			RewindRecorder.testTick();
			RewindPlayer.tick();
			RewindEditorScreen.worldTick();
			RewindApply.tick();
			RewindPlayer.testTick();
		});
	}

	@Override
	public void onDisable() {
		if (System.getProperty("velo.aiApi", System.getProperty("velo.rewindApi")) == null) {
			RewindApi.setEnabled(false);
		}
		if (RewindRecorder.active()) {
			RewindRecorder.toggle(); // stop and save
		}
	}

	// AI Access: the local API agents use to edit replays and make videos (see RewindApi / velo-rewind/AI.md)
	private boolean aiAccess = false;
	// audio while recording: other players' voice chat (Simple Voice Chat), your microphone, what your speakers play
	private boolean recordVoice = true;
	private boolean recordMic = false;
	private boolean recordSystem = false;
	private String systemDevice = "Automatic";
	private String micDevice = "Default"; 

	@Override
	public java.util.List<net.veloclient.velo.module.ConfigField> configFields() {
		return java.util.List.of(
				new net.veloclient.velo.module.ConfigField.ToggleField("Record Voice Chat", () -> recordVoice, v -> {
					recordVoice = v;
					RewindAudio.recordVoice = v;
				}),
				new net.veloclient.velo.module.ConfigField.ToggleField("Record My Microphone", () -> recordMic, v -> {
					recordMic = v;
					RewindAudio.recordMic = v;
				}),
				new net.veloclient.velo.module.ConfigField.ToggleField("Record System Audio (Discord, ...)", () -> recordSystem, v -> {
					recordSystem = v;
					RewindAudio.recordSystem = v;
				}),
				new net.veloclient.velo.module.ConfigField.ChoiceField("System Audio Device", withFirst("Automatic", RewindAudio.systemDevices()),
						() -> systemDevice, v -> {
							systemDevice = v;
							RewindAudio.systemDevice = v.equals("Automatic") ? "" : v;
						}),
				new net.veloclient.velo.module.ConfigField.ChoiceField("Microphone", withFirst("Default", RewindAudio.micDevices()), () -> micDevice, v -> {
					micDevice = v;
					RewindAudio.micDevice = v.equals("Default") ? "" : v;
				}),
				new net.veloclient.velo.module.ConfigField.ToggleField("AI Access (local API)", () -> aiAccess, v -> {
					aiAccess = v;
					RewindApi.setEnabled(v && isEnabled());
				}));
	}

	private static java.util.List<String> withFirst(String first, java.util.List<String> rest) {
		java.util.List<String> out = new java.util.ArrayList<>();
		out.add(first);
		out.addAll(rest);
		return out;
	}

	// The AI API changed the audio settings: keep them (saved with the profile like the settings screen does).
	public static void audioChanged() {
		RewindModule m = instance;
		if (m == null) {
			return;
		}
		m.recordVoice = RewindAudio.recordVoice;
		m.recordMic = RewindAudio.recordMic;
		m.recordSystem = RewindAudio.recordSystem;
		m.systemDevice = RewindAudio.systemDevice.isBlank() ? "Automatic" : RewindAudio.systemDevice;
		m.micDevice = RewindAudio.micDevice.isBlank() ? "Default" : RewindAudio.micDevice;
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
	}

	@Override
	public void onEnable() {
		RewindAudio.systemDevice = "Automatic".equals(systemDevice) ? "" : systemDevice;
		RewindAudio.micDevice = "Default".equals(micDevice) ? "" : micDevice;
		RewindAudio.recordVoice = recordVoice;
		RewindAudio.recordMic = recordMic;
		RewindAudio.recordSystem = recordSystem;
		RewindApi.setEnabled(aiAccess || System.getProperty("velo.aiApi", System.getProperty("velo.rewindApi")) != null);
	}

	public static boolean aiAccess() {
		RewindModule m = instance;
		return m != null && m.aiAccess;
	}

	// From the replay editor's AI card: flips the setting and saves the profile.
	public static void setAiAccess(boolean on) {
		RewindModule m = instance;
		if (m == null) {
			return;
		}
		m.aiAccess = on;
		RewindApi.setEnabled(on && m.isEnabled() || System.getProperty("velo.aiApi", System.getProperty("velo.rewindApi")) != null);
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
	}

	public static boolean available() {
		RewindModule m = instance;
		return m != null && m.isEnabled();
	}
}
