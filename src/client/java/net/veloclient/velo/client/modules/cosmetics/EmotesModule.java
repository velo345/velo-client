package net.veloclient.velo.client.modules.cosmetics;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.emotes.EmoteCatalog;
import net.veloclient.velo.client.emotes.EmoteEffects;
import net.veloclient.velo.client.emotes.EmoteLibrary;
import net.veloclient.velo.client.emotes.Emotes;
import net.veloclient.velo.client.gui.EmoteWheelScreen;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Emotes: hold (or tap) the emote key for a wheel of eight emotes - point at one with the mouse and let go, click
 * it, or press 1-8. Your player plays it with its effects, props, sounds and music (the camera swings to third person meanwhile);
 * moving, jumping, sneaking, attacking or taking a hit ends it. Emotes are Velo Store items: the wheel holds the ones
 * you bought, arranged in the Locker's Emotes tab. Other Velo players see your emotes and you theirs (through the
 * Velo network, a few seconds late). Purely visual and client side - no packets to the Minecraft server.
 */
public final class EmotesModule extends AbstractModule implements Configurable {

	public static final KeyBinding WHEEL_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.emote_wheel", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.B, VeloKeybinds.CATEGORY));

	public static final int SLOTS = EmoteCatalog.SLOTS;

	private static EmotesModule instance;

	private boolean thirdPerson = true;
	private boolean showOthers = true;
	private boolean particles = true;
	private boolean sounds = true;
	private boolean music = true;
	private boolean othersSounds = true;
	private double volume = 1.0;
	private double musicVolume = 0.8;
	private double othersVolume = 0.8;
	private int catalogSeen = -1;

	// your emote while it plays: camera handling and the keep-alive for the network
	private boolean wasPlaying;
	private boolean cameraChanged;
	private int graceTicks;
	private int keepAliveTicks;

	//? if <26.1 {
	private net.minecraft.client.option.Perspective previousPerspective;
	//?} else {
	/*private net.minecraft.client.CameraType previousPerspective;
	*///?}

	public EmotesModule() {
		super("emotes", "Emotes",
				"Emote wheel: hold B, point at an emote and let go (or press 1-8) - with effects, props, sounds and music. Get emotes in the Store.",
				ModuleCategory.COSMETICS, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
		EmoteLibrary.warmUp();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	public static EmotesModule get() {
		return instance;
	}

	/** The emote in each wheel slot (null = empty or not owned). */
	public String slot(int index) {
		return EmoteCatalog.slot(index);
	}

	/** Plays one of your emotes as you (from the wheel or its Store page). */
	public void play(String id) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || !EmoteCatalog.owns(id)) {
			return;
		}
		Emotes.playLocal(PvpCompat.uuid(client.player), id);
		// keys still held from opening the wheel don't end it at once
		graceTicks = 4;
		keepAliveTicks = 0;
		if (thirdPerson) {
			forceThirdPerson(client);
		}
	}

	@Override
	public void onDisable() {
		Emotes.stopLocal();
		restoreCamera(MinecraftClient.getInstance());
	}

	private void tick(MinecraftClient client) {
		Emotes.setShowOthers(isEnabled() && showOthers);
		EmoteEffects.configure(particles, sounds, music, othersSounds, (float) volume, (float) musicVolume, (float) othersVolume);
		EmoteEffects.tick();
		// smart loading: whatever you own is downloaded and parsed in the background as soon as the catalog knows it
		int catalog = net.veloclient.velo.client.cosmetics.CosmeticsCatalog.version();
		if (catalog != catalogSeen && net.veloclient.velo.client.network.StoreClient.walletLoaded()) {
			catalogSeen = catalog;
			EmoteLibrary.prefetch(EmoteCatalog.owned());
		}
		if (!isEnabled()) {
			return;
		}
		if (client.player != null) {
			Emotes.setLocalUuid(PvpCompat.uuid(client.player));
		}
		if (client.player == null) {
			Emotes.stopLocal();
			wasPlaying = false;
			cameraChanged = false;
			previousPerspective = null;
			return;
		}
		while (WHEEL_KEY.wasPressed()) {
			if (net.veloclient.velo.client.util.ClientCompat.currentScreen() == null) {
				client.setScreen(new EmoteWheelScreen(this, KeybindConfig.currentKeyCode(WHEEL_KEY)));
			}
		}
		boolean playing = Emotes.localEmote() != null;
		if (playing) {
			if (graceTicks > 0) {
				graceTicks--;
			} else if (interrupted(client)) {
				Emotes.stopLocal();
				playing = false;
			}
			// a looping emote stays listed on the server only while the heartbeat repeats it
			if (++keepAliveTicks % 160 == 0) {
				net.veloclient.velo.client.network.VeloServerClient.syncSoon();
			}
		}
		if (wasPlaying && Emotes.localDone()) {
			restoreCamera(client);
			wasPlaying = false;
		}
		wasPlaying |= playing;
	}

	private static boolean interrupted(MinecraftClient client) {
		return client.options.forwardKey.isPressed() || client.options.backKey.isPressed() || client.options.leftKey.isPressed()
				|| client.options.rightKey.isPressed() || client.options.jumpKey.isPressed() || client.options.sneakKey.isPressed()
				|| client.options.attackKey.isPressed() || client.options.useKey.isPressed()
				|| client.player.getVehicle() != null || PvpCompat.hurtTime(client.player) > 0;
	}

	//? if <26.1 {
	private void forceThirdPerson(MinecraftClient client) {
		if (client.options.getPerspective() != net.minecraft.client.option.Perspective.FIRST_PERSON) {
			return;
		}
		previousPerspective = client.options.getPerspective();
		client.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
		cameraChanged = true;
	}

	private void restoreCamera(MinecraftClient client) {
		if (cameraChanged && previousPerspective != null) {
			client.options.setPerspective(previousPerspective);
		}
		cameraChanged = false;
		previousPerspective = null;
	}
	//?} else {
	/*private void forceThirdPerson(MinecraftClient client) {
		if (client.options.getCameraType() != net.minecraft.client.CameraType.FIRST_PERSON) {
			return;
		}
		previousPerspective = client.options.getCameraType();
		client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
		cameraChanged = true;
	}

	private void restoreCamera(MinecraftClient client) {
		if (cameraChanged && previousPerspective != null) {
			client.options.setCameraType(previousPerspective);
		}
		cameraChanged = false;
		previousPerspective = null;
	}
	*///?}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		fields.add(KeybindConfig.field("Emote Wheel Key", WHEEL_KEY));
		fields.add(new ConfigField.ToggleField("Third Person While Emoting", () -> thirdPerson, v -> thirdPerson = v));
		fields.add(new ConfigField.ToggleField("Show Other Players' Emotes", () -> showOthers, v -> showOthers = v));
		fields.add(new ConfigField.HeaderField("Effects"));
		fields.add(new ConfigField.ToggleField("Emote Effects & Props", () -> particles, v -> particles = v));
		fields.add(new ConfigField.HeaderField("Sound"));
		fields.add(new ConfigField.ToggleField("Emote Sounds", () -> sounds, v -> sounds = v));
		fields.add(new ConfigField.ToggleField("Dance Music", () -> music, v -> music = v));
		fields.add(new ConfigField.ToggleField("Hear Other Players' Emotes", () -> othersSounds, v -> othersSounds = v));
		fields.add(new ConfigField.SliderField("Emote Volume", 0, 1, () -> volume, v -> volume = v, v -> Math.round(v * 100) + "%"));
		fields.add(new ConfigField.SliderField("Music Volume", 0, 1, () -> musicVolume, v -> musicVolume = v, v -> Math.round(v * 100) + "%"));
		fields.add(new ConfigField.SliderField("Other Players' Volume", 0, 1, () -> othersVolume, v -> othersVolume = v,
				v -> Math.round(v * 100) + "%"));
		return fields;
	}
}
