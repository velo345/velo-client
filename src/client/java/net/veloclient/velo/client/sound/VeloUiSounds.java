package net.veloclient.velo.client.sound;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.veloclient.velo.client.Preview;
import net.veloclient.velo.client.emotes.EmoteSounds;

/**
 * Velo's own interface sounds (assets/velo-client/sounds/ui, made by tools/sounds/make_ui_sounds.py) and the calm title
 * track. Every Velo widget plays its click through here: in a dev build ({@link Preview#ON}) with the interface sounds
 * switched on that's Velo's soft click / toggle / swish, otherwise vanilla's click exactly as before.
 *
 * <p>The files carry their own relative loudness (levelled against vanilla's click), so they all play at {@link #VOLUME}.
 */
public final class VeloUiSounds {

	/** Playback volume of every UI sound - the files are levelled for it. */
	private static final float VOLUME = 0.3f;

	private static boolean loadedPlayed;
	private static EmoteSounds.Handle music;
	/** Screens (the guide) can ask for the title track for a moment even where it wouldn't play on its own. */
	private static long musicWantedUntil;
	private static boolean registered;
	private static int silentTicks;

	private VeloUiSounds() {
	}

	private static boolean velo() {
		return Preview.ON && UiPrefs.sounds();
	}

	/** A plain click (buttons, links, rows); {@code pitch} as the old vanilla call had it. */
	public static void click(float pitch) {
		if (velo()) {
			play("click", pitch);
		} else {
			vanillaClick(pitch);
		}
	}

	/** A switch flipped on or off. */
	public static void toggle(boolean on) {
		if (velo()) {
			play(on ? "toggle_on" : "toggle_off", 1f);
		} else {
			vanillaClick(on ? 1.1f : 0.9f);
		}
	}

	/** A slider moved one step (throttled, so dragging doesn't machine-gun). */
	public static void slider() {
		long now = System.nanoTime();
		if (velo() && now - lastSlider > 45_000_000L) {
			lastSlider = now;
			play("slider", 1f);
		}
	}

	private static long lastSlider;

	/** Switching tabs / pages inside a window. */
	public static void tab() {
		if (velo()) {
			play("tab", 1f);
		}
	}

	/** Hovering one of the guide's big choice cards - very quiet. */
	public static void hover() {
		if (velo()) {
			play("hover", 1f);
		}
	}

	/** The Velo menu opening from gameplay / closing back to it. */
	public static void menuOpen() {
		if (velo()) {
			play("menu_open", 1f);
		}
	}

	public static void menuClose() {
		if (velo()) {
			play("menu_close", 1f);
		}
	}

	public static void guideNext() {
		if (velo()) {
			play("guide_next", 1f);
		}
	}

	public static void guideBack() {
		if (velo()) {
			play("guide_back", 1f);
		}
	}

	public static void guideDone() {
		if (velo()) {
			play("guide_done", 1f);
		}
	}

	public static void welcome() {
		if (velo()) {
			play("welcome", 1f);
		}
	}

	/** Once per game start, when the title screen first shows. */
	public static void gameLoaded() {
		if (!loadedPlayed) {
			loadedPlayed = true;
			if (velo()) {
				play("game_loaded", 1f);
			}
		}
	}

	/** Keeps the title track playing for the next {@code millis} even outside the title screen (the guide does this). */
	public static void wantMusic(long millis) {
		musicWantedUntil = Math.max(musicWantedUntil, System.currentTimeMillis() + millis);
	}

	/** Hooks the title track into the client tick (once, from client init). */
	public static void register() {
		if (registered || !Preview.ON) {
			return;
		}
		registered = true;
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> tickMusic());
		hookPauseMenu();
	}

	private static void tickMusic() {
		MinecraftClient client = MinecraftClient.getInstance();
		boolean wanted = UiPrefs.titleMusic() && (System.currentTimeMillis() < musicWantedUntil || inMenus(client));
		if (wanted) {
			// Toggling options like Directional Audio restarts the sound engine: our track stops without being marked
			// done, so ask the engine itself (after a short grace - a freshly started sound reports late).
			if (music != null && !music.done() && !engineHas(client, music)) {
				silentTicks++;
			} else {
				silentTicks = 0;
			}
			if (music == null || music.done() || silentTicks > 30) {
				silentTicks = 0;
				music = EmoteSounds.menuMusic("velo-client:music.title_ambient", 1f);
			}
			// vanilla's menu music would start on top of it
			//? if <26.1 {
			client.getMusicTracker().stop();
			//?} else {
			/*client.getMusicManager().stopPlaying();
			*///?}
		} else if (music != null) {
			music.fadeOut();
			music = null;
		}
	}

	/** True from opening the Escape menu over the game until you're back in the game (Options & co. in between count). */
	private static boolean inPauseMenus;

	/**
	 * The Escape menu gets its own small sounds: one when it opens over the game, one when you go back to the game.
	 * Coming back to it from Options, Advancements, the Velo menu... plays nothing - you never left the menus.
	 */
	private static void hookPauseMenu() {
		net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!inWorld()) {
				inPauseMenus = false; // left the world from the menu (Disconnect / Save and Quit)
			}
			if (isPauseScreen(screen) && !inPauseMenus && inWorld()) {
				inPauseMenus = true;
				if (velo()) {
					play("pause_open", 1f);
				}
			}
			if (inPauseMenus) {
				// The next screen change: back in the game (no screen) -> the closing sound.
				net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.remove(screen).register(removed ->
						MinecraftClient.getInstance().execute(() -> {
							if (inPauseMenus && currentScreen() == null) {
								inPauseMenus = false;
								if (velo() && inWorld()) {
									play("pause_close", 1f);
								}
							}
						}));
			}
		});
	}

	private static boolean isPauseScreen(Object screen) {
		String name = screen == null ? "" : screen.getClass().getSimpleName();
		return name.equals("GameMenuScreen") || name.equals("PauseScreen") || name.equals("class_433");
	}

	private static Object currentScreen() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		return client.currentScreen;
		//?} else if <26.2 {
		/*return client.screen;
		*///?} else {
		/*return client.gui.screen();
		*///?}
	}

	private static boolean inWorld() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		return client.world != null;
		//?} else {
		/*return client.level != null;
		*///?}
	}

	private static boolean engineHas(MinecraftClient client, EmoteSounds.Handle handle) {
		if (!(handle instanceof net.minecraft.client.sound.SoundInstance instance)) {
			return true;
		}
		//? if <26.1 {
		return client.getSoundManager().isPlaying(instance);
		//?} else {
		/*return client.getSoundManager().isActive(instance);
		*///?}
	}

	/** Title screen and the menus behind it (worlds, servers, options) - not loading or joining screens, not in a world. */
	private static boolean inMenus(MinecraftClient client) {
		//? if <26.1 {
		var screen = client.currentScreen;
		boolean inWorld = client.world != null;
		//?} else if <26.2 {
		/*var screen = client.screen;
		boolean inWorld = client.level != null;
		*///?} else {
		/*var screen = client.gui.screen();
		boolean inWorld = client.level != null;
		*///?}
		if (inWorld || screen == null) {
			return false;
		}
		String name = screen.getClass().getSimpleName();
		return !(name.contains("Loading") || name.contains("Progress") || name.contains("Connect") || name.contains("Generating")
				|| name.contains("Download") || name.contains("Message") || name.contains("Reconfig"));
	}

	private static void play(String name, float pitch) {
		try {
			//? if <26.1 {
			MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(
					net.minecraft.sound.SoundEvent.of(net.minecraft.util.Identifier.of("velo-client", "ui." + name)), pitch, VOLUME));
			//?} else {
			/*net.minecraft.client.Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
					net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "ui." + name)),
					pitch, VOLUME));
			*///?}
		} catch (RuntimeException ignored) {
			// no sound engine
		}
	}

	private static void vanillaClick(float pitch) {
		MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, pitch));
	}
}
