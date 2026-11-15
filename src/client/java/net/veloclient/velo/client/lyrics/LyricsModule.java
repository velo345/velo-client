package net.veloclient.velo.client.lyrics;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.nowplaying.NowPlaying;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Lyrics: the words of the song playing on Spotify (or any player the system's media controls know), synced line by
 * line - big in the sky or floating in front of you, or flat on the screen. The Lyrics Studio has every setting with a
 * live preview; presets give a finished look in one click.
 */
public final class LyricsModule extends AbstractModule implements Configurable {

	private static LyricsModule instance;
	static LyricsStyle style = LyricsStyle.PRESETS.get("Concert Sky").copy();

	public LyricsModule() {
		super("lyrics", "Lyrics", "The lyrics of the song playing on Spotify, synced to the music - huge in the sky, floating in front of you "
				+ "or on screen, with animations, fonts, colours and presets. The Lyrics Studio has a live preview.",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
		HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, Identifier.of("velo-client", "lyrics"),
				(context, tickCounter) -> draw(context));
		LyricsWorld.register();
	}

	static LyricsModule get() {
		return instance;
	}

	/** True while the Lyrics Studio is open: demo lines show when nothing plays. */
	static boolean studioOpen;

	/** Whether lyrics should show at all right now (on, or the Studio's preview; and something is playing). */
	private static boolean active() {
		LyricsModule m = instance;
		if (m == null || !m.isEnabled() && !studioOpen) {
			return false;
		}
		LyricsSync.update();
		return studioOpen || LyricsSync.status() != LyricsSync.Status.NOTHING_PLAYING
				&& (LyricsSync.playing() || LyricsSync.status() == LyricsSync.Status.FOUND);
	}

	/** The world pass asks: 3D lyrics this frame? */
	static boolean showing3d() {
		return style.world3d() && active();
	}

	private static long lastHudNanos;

	/** The 2D style draws on the HUD; 3D draws in the world pass ({@link LyricsWorld}). */
	private void draw(DrawContext context) {
		if (style.world3d() || !active()) {
			return;
		}
		try {
			long nanos = System.nanoTime();
			float dt = lastHudNanos == 0 ? 0.016f : Math.min(0.1f, (nanos - lastHudNanos) / 1e9f);
			lastHudNanos = nanos;
			LyricsRenderer.frame(new LyricsRenderer.HudSink(context), style, studioOpen, null, context.getScaledWindowWidth(),
					context.getScaledWindowHeight(), dt, true);
		} catch (RuntimeException e) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Lyrics failed to draw", e);
		}
	}

	/** The R-Shift card preview. */
	public static void drawCard(DrawContext context, int x, int y, int w, int h) {
		LyricsRenderer.renderCard(context, x, y, w, h, style);
	}

	@Override
	public void onEnable() {
		NowPlaying.acquire("lyrics");
	}

	@Override
	public void onDisable() {
		if (!studioOpen) {
			NowPlaying.release("lyrics");
		}
	}

	static void setStyle(LyricsStyle s) {
		style = s;
		LyricsRenderer.reset();
	}

	/** Dev tour: switch to a preset by name. */
	public static void applyPreset(String name) {
		setStyle(style.withPreset(name));
	}

	/** Dev tour: change the style directly. */
	public static void editForTour(java.util.function.Consumer<LyricsStyle> change) {
		edit(change);
	}

	public static void openStudio() {
		MinecraftClient.getInstance().setScreen(new LyricsStudioScreen(net.veloclient.velo.client.util.ClientCompat.currentScreen()));
	}

	// ---------------------------------------------------------------- settings

	@Override
	public String extraState() {
		return style.save();
	}

	@Override
	public void restoreExtraState(String state) {
		if (state != null && !state.isBlank()) {
			setStyle(LyricsStyle.load(state));
		}
	}

	/** Everything lives in the one JSON string (extraState) - the fields below just edit it. */
	@Override
	public boolean persistField(String label) {
		return false;
	}

	/** The module settings are the Lyrics Studio itself (R-Shift opens it directly); this button is for anywhere else. */
	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.ActionButtonField("Open Lyrics Studio", LyricsModule::openStudio));
	}

	static void placement(LyricsStyle s, String v) {
		if (!v.equals(s.placement)) {
			s.placement = v;
			// each placement has its own sensible distance/height
			s.distance = "Sky".equals(v) ? 110 : 6;
			s.height = "Sky".equals(v) ? 22 : 0.6;
		}
	}

	static void edit(java.util.function.Consumer<LyricsStyle> change) {
		change.accept(style);
		style.preset = "Custom";
		LyricsRenderer.reset();
	}
}
