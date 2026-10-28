package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.nowplaying.CoverArt;
import net.veloclient.velo.client.nowplaying.MediaGlyphs;
import net.veloclient.velo.client.nowplaying.MediaState;
import net.veloclient.velo.client.nowplaying.NowPlaying;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Media Player: whatever plays on this computer (Spotify, a browser tab, VLC...) through the OS media controls - a card
 * that slides in on every song change (or stays as a widget), and play/pause/skip/volume in the Velo menu. Never talks
 * to Spotify or any other service itself; covers come from the player, the same image the OS media flyout shows.
 */
public final class MediaPlayerModule extends AbstractModule implements HudModule, Configurable {

	public static final String ID = "media-player";
	private static final List<String> STYLES = List.of("Popup", "Widget", "Pill");
	private static final int CARD_W = 176;
	private static final int CARD_H = 48;
	private static final int PILL_W = 136;
	private static final int PILL_H = 18;
	private static final int PANEL = 0xE80D0D11;

	private final HudPosition position = new HudPosition(0.99f, 0.30f);
	private String style = "Popup";
	private double popupSeconds = 5;
	private boolean onPause = true;
	private boolean coverColor = true;
	private boolean inMenu = true;

	// Animation state (render thread).
	private int seenTrack = -1;
	private int seenPlayState = -1;
	private long shownAt = Long.MIN_VALUE;
	private float visible;
	private float energy;
	private long lastFrame;
	private String shownTitle = "";
	private String shownArtist = "";
	private String oldTitle = "";
	private String oldArtist = "";
	private long textSwapAt;

	public MediaPlayerModule() {
		super(ID, "Media Player", "Shows what's playing (Spotify, YouTube, any player) with its cover when the song changes, "
				+ "and puts play, skip and volume controls in the Velo menu. Uses your system's media controls, so nothing to log into.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, true);
	}

	public static MediaPlayerModule get() {
		return net.veloclient.velo.module.ModuleRegistry.get(ID).filter(m -> m instanceof MediaPlayerModule).map(m -> (MediaPlayerModule) m)
				.orElse(null);
	}

	/** Whether the Velo menu should show the playback controls. */
	public static boolean controlsInMenu() {
		MediaPlayerModule m = get();
		return m != null && m.isEnabled() && m.inMenu;
	}

	/** The tint the cover gives the player UI (or the Velo accent). */
	public static int tint() {
		MediaPlayerModule m = get();
		int accent = MenuKit.accent();
		return m == null || m.coverColor ? CoverArt.color(accent) : accent;
	}

	@Override
	public void onEnable() {
		NowPlaying.start();
	}

	@Override
	public void onDisable() {
		NowPlaying.stop();
		MinecraftClient.getInstance().execute(NowPlaying::releaseCover);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public int width() {
		return style.equals("Pill") ? PILL_W : CARD_W;
	}

	@Override
	public int height() {
		return style.equals("Pill") ? PILL_H : CARD_H;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = STYLES.contains(v) ? v : "Popup"),
				new ConfigField.SliderField("Popup time", 2, 12, () -> popupSeconds, v -> popupSeconds = v, v -> Math.round(v) + " s"),
				new ConfigField.ToggleField("Pop up on pause / resume", () -> onPause, v -> onPause = v),
				new ConfigField.ToggleField("Colors from the cover", () -> coverColor, v -> coverColor = v),
				new ConfigField.ToggleField("Controls in the Velo menu", () -> inMenu, v -> inMenu = v));
	}

	// ---- Drawing ----

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		NowPlaying.syncCover();
		MediaState state = NowPlaying.state();
		boolean editing = net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;

		if (NowPlaying.trackSerial() != seenTrack) {
			seenTrack = NowPlaying.trackSerial();
			seenPlayState = NowPlaying.playStateSerial();
			if (state != null) {
				shownAt = now;
			}
		} else if (NowPlaying.playStateSerial() != seenPlayState) {
			seenPlayState = NowPlaying.playStateSerial();
			if (onPause && state != null) {
				shownAt = now;
			}
		}
		String title = state != null ? state.title() : editing ? "Nothing playing" : shownTitle;
		String artist = state != null ? nz(state.artist()) : editing ? "Play something in Spotify or a browser" : shownArtist;
		if (!title.equals(shownTitle)) {
			oldTitle = shownTitle;
			oldArtist = shownArtist;
			shownTitle = title;
			shownArtist = artist;
			textSwapAt = now;
		} else {
			shownArtist = artist;
		}

		boolean want;
		if (editing) {
			want = true;
		} else if (state == null) {
			want = false;
		} else if (style.equals("Popup")) {
			want = now - shownAt < (long) (popupSeconds * 1_000_000_000L);
		} else {
			want = true;
		}
		// Linear time (0.45 s in, 0.35 s out) shaped by the easing below - exponential steps can't overshoot nicely.
		visible = want ? Math.min(1f, visible + dt / 0.45f) : Math.max(0f, visible - dt / 0.35f);
		energy = VeloAnim.step(energy, state != null && state.playing() ? 1f : 0f, dt * 0.35f);
		if (visible < 0.01f) {
			return;
		}
		// In: a slide with a little overshoot. Out: slide back and fade.
		float ease = want ? backOut(visible) : visible * visible;
		boolean rightSide = x + width() / 2 > context.getScaledWindowWidth() / 2;
		float slide = (1f - ease) * (width() * 0.6f) * (rightSide ? 1 : -1);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(slide, 0f);
		try {
			if (style.equals("Pill")) {
				drawPill(context, x, y, state, Math.min(1f, visible * 1.4f), now);
			} else {
				drawCard(context, x, y, state, Math.min(1f, visible * 1.4f), now);
			}
		} finally {
			context.getMatrices().popMatrix();
		}
	}

	private void drawCard(DrawContext context, int x, int y, MediaState state, float alpha, long now) {
		int tint = tint();
		int w = CARD_W;
		int h = CARD_H;
		VeloDraw.shadow(context, x, y, w, h, 11, 12, 3, VeloUi.withAlpha(0xFF000000, Math.round(0x90 * alpha)));
		if (CoverArt.has()) {
			VeloDraw.shadow(context, x, y, w, h, 11, 9, 0, VeloUi.withAlpha(tint, Math.round(0x38 * alpha * (0.6f + 0.4f * energy))));
		}
		VeloDraw.fillRounded(context, x, y, w, h, 11, VeloUi.withAlpha(PANEL, Math.round(0xE8 * alpha)));
		VeloDraw.fillRoundedGradient(context, x, y, w, h, 11, VeloUi.withAlpha(tint, Math.round(0x34 * alpha)), 0x00000000);
		VeloDraw.strokeRounded(context, x, y, w, h, 11, VeloUi.withAlpha(VeloAnim.lerpArgb(0xFFFFFFFF, tint, 0.5f), Math.round(0x30 * alpha)));

		// Cover, with a soft pop on every song change.
		int size = 36;
		int cx = x + 6;
		int cy = y + 6;
		float pop = pop(now - textSwapAt);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(cx + size / 2f, cy + size / 2f);
		context.getMatrices().scale(pop, pop);
		context.getMatrices().translate(-(cx + size / 2f), -(cy + size / 2f));
		VeloDraw.fillRounded(context, cx, cy, size, size, 7, VeloUi.withAlpha(0xFF1E1E26, Math.round(0xFF * alpha)));
		if (CoverArt.has()) {
			CoverArt.draw(context, cx, cy, size, alpha);
		} else {
			MediaGlyphs.note(context, cx + size / 2f, cy + size / 2f, 16, VeloUi.withAlpha(tint, Math.round(0xD0 * alpha)));
		}
		VeloDraw.strokeRounded(context, cx, cy, size, size, 3, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x22 * alpha)));
		context.getMatrices().popMatrix();

		int tx = x + 50;
		int tw = w - 50 - 8;
		// Header: bars + where it plays.
		int headColor = VeloUi.withAlpha(VeloAnim.lerpArgb(0xFF8E8E9A, tint, 0.55f), Math.round(0xFF * alpha));
		float eqW = MediaGlyphs.equalizer(context, tx, y + 13, 6, energy, headColor);
		String head = state == null ? "MEDIA" : (state.playing() ? "NOW PLAYING" : "PAUSED") + "  ·  " + state.playerLabel().toUpperCase();
		HudLook.text(context, MenuKit.fit(MinecraftClient.getInstance().textRenderer, MenuKit.small(head), tw - (int) eqW - 4), tx + (int) eqW + 4,
				y + 7, headColor);

		// Title and artist: the old pair slides up and out while the new one comes in.
		float swap = Math.min(1f, (now - textSwapAt) / 380_000_000f);
		float e = 1f - (1f - swap) * (1f - swap) * (1f - swap);
		context.enableScissor(tx, y + 15, tx + tw, y + 39);
		if (swap < 1f && !oldTitle.isEmpty()) {
			int oy = Math.round(-e * 10);
			textPair(context, tx, y + 17 + oy, tw, oldTitle, oldArtist, alpha * (1f - e), now);
		}
		textPair(context, tx, y + 17 + Math.round((1f - e) * 10), tw, shownTitle, shownArtist, alpha * (oldTitle.isEmpty() ? 1f : e), now);
		context.disableScissor();

		// Progress.
		int by = y + h - 7;
		VeloDraw.fillRounded(context, tx, by, tw, 2, 1, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x1C * alpha)));
		if (state != null && state.lengthMs() > 0) {
			float p = state.positionNow() / (float) state.lengthMs();
			int pw = Math.max(2, Math.round(tw * p));
			VeloDraw.fillRounded(context, tx, by, pw, 2, 1, VeloUi.withAlpha(tint, Math.round(0xFF * alpha)));
			VeloDraw.fillCircle(context, tx + pw, by + 1f, 1.8f + energy * 0.4f, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0xF0 * alpha)));
		}
	}

	private void textPair(DrawContext context, int x, int y, int w, String title, String artist, float alpha, long now) {
		if (alpha < 0.03f) {
			return;
		}
		marquee(context, HudLook.value(title), x, y, w, VeloUi.withAlpha(MenuKit.TEXT, Math.round(0xFF * alpha)), now);
		if (!artist.isEmpty()) {
			marquee(context, HudLook.label(artist), x, y + 11, w, VeloUi.withAlpha(0xFFA9A9B3, Math.round(0xFF * alpha)), now);
		}
	}

	/** Text that doesn't fit drifts left and back, pausing at each end. */
	private static void marquee(DrawContext context, Text text, int x, int y, int w, int color, long now) {
		int tw = HudLook.width(text);
		int over = tw - w;
		if (over <= 0) {
			HudLook.text(context, text, x, y, color);
			return;
		}
		double period = 2.4 + over / 22.0;
		double t = (now / 1_000_000_000.0) % (period * 2 + 3.0);
		double shift;
		if (t < 1.5) {
			shift = 0;
		} else if (t < 1.5 + period) {
			shift = smooth((t - 1.5) / period) * over;
		} else if (t < 3.0 + period) {
			shift = over;
		} else {
			shift = (1 - smooth((t - 3.0 - period) / period)) * over;
		}
		context.enableScissor(x, y - 2, x + w, y + 10);
		HudLook.text(context, text, x - (int) Math.round(shift), y, color);
		context.disableScissor();
	}

	private void drawPill(DrawContext context, int x, int y, MediaState state, float alpha, long now) {
		int tint = tint();
		int w = PILL_W;
		int h = PILL_H;
		VeloDraw.shadow(context, x, y, w, h, h / 2, 8, 2, VeloUi.withAlpha(0xFF000000, Math.round(0x70 * alpha)));
		VeloDraw.fillRounded(context, x, y, w, h, h / 2, VeloUi.withAlpha(PANEL, Math.round(0xE0 * alpha)));
		VeloDraw.strokeRounded(context, x, y, w, h, h / 2, VeloUi.withAlpha(tint, Math.round(0x50 * alpha)));
		int size = 12;
		if (CoverArt.has()) {
			CoverArt.draw(context, x + 3, y + 3, size, alpha);
		} else {
			MediaGlyphs.note(context, x + 3 + size / 2f, y + h / 2f, 9, VeloUi.withAlpha(tint, Math.round(0xE0 * alpha)));
		}
		float eqW = MediaGlyphs.equalizer(context, x + w - 13, y + 12, 6, energy, VeloUi.withAlpha(tint, Math.round(0xFF * alpha)));
		int tx = x + 19;
		int tw = w - 19 - (int) eqW - 10;
		String line = shownArtist.isEmpty() ? shownTitle : shownTitle + "  ·  " + shownArtist;
		marquee(context, HudLook.value(line), tx, y + 5, tw, VeloUi.withAlpha(MenuKit.TEXT, Math.round(0xFF * alpha)), now);
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static double smooth(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	/** 0..1 with a small overshoot near the end. */
	private static float backOut(float t) {
		float c = 1.4f;
		float u = t - 1f;
		return 1f + (c + 1f) * u * u * u + c * u * u;
	}

	/** A quick 0.92 -> 1.04 -> 1 bump over 0.45 s after a song change. */
	private static float pop(long sinceNanos) {
		float t = sinceNanos / 450_000_000f;
		if (t >= 1f) {
			return 1f;
		}
		return 0.92f + 0.08f * backOut(t);
	}
}
