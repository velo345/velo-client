package net.veloclient.velo.client.nowplaying;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.modules.hud.MediaPlayerModule;

import java.util.ArrayList;
import java.util.List;

/**
 * The playback controls in the Velo menu: a pill (cover, song, play/pause, next) that opens a panel with the big cover,
 * a seek bar, previous/play/next and a volume slider. Everything reads {@link NowPlaying#state()} every frame, so a
 * pause in Spotify, a media key or another device shows up here within a poll. One instance per screen.
 */
public final class MediaControls {

	private static final int PILL_H = 22;
	private static final int PANEL_W = 236;
	private static final int PANEL_H = 128;

	private record Hit(float x, float y, float w, float h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && my >= y && mx < x + w && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private final java.util.Map<String, Float> hover = new java.util.HashMap<>();
	private final java.util.Map<String, Long> pressed = new java.util.HashMap<>();
	private boolean open;
	private float openAnim;
	private float present;
	private float energy;
	private long lastFrame;
	private float frameDt;
	private int pillX;
	private int pillY;
	private int pillW;
	private int panelX;
	private int panelY;
	private int seekX;
	private int seekY;
	private int seekW;
	private int volX;
	private int volY;
	private int volW;
	private float shownVolume = -1;
	private enum Drag { NONE, SEEK, VOLUME }
	private Drag drag = Drag.NONE;
	private double dragValue;

	public static boolean available() {
		return MediaPlayerModule.controlsInMenu();
	}

	/** True when something plays (or just stopped and the pill is still fading out). */
	public boolean visible() {
		return available() && (NowPlaying.state() != null || present > 0.02f);
	}

	private float dt() {
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		return dt;
	}

	/**
	 * Draws the pill with its right edge at {@code right}, at most {@code maxW} wide; returns the width it took (0 when
	 * nothing plays). Call once per frame; then {@link #renderPanel} on top of everything.
	 */
	public int renderPill(DrawContext context, TextRenderer textRenderer, int right, int y, int maxW, int mouseX, int mouseY) {
		hits.clear();
		float dt = dt();
		frameDt = dt;
		if (!available()) {
			present = 0;
			return 0;
		}
		NowPlaying.syncCover();
		MediaState s = NowPlaying.state();
		present = VeloAnim.step(present, s != null ? 1f : 0f, dt * 0.8f);
		energy = VeloAnim.step(energy, s != null && s.playing() ? 1f : 0f, dt * 0.35f);
		openAnim = VeloAnim.step(openAnim, open && s != null ? 1f : 0f, dt * 1.3f);
		if (s == null) {
			open = false;
		}
		if (present < 0.02f || maxW < 90) {
			pillW = 0;
			return 0;
		}
		int tint = MediaPlayerModule.tint();
		int full = Math.min(maxW, 210);
		int w = Math.round(full * (0.6f + 0.4f * present));
		int x = right - w;
		pillX = x;
		pillY = y;
		pillW = w;
		int a = Math.round(0xFF * present);
		boolean overPill = MenuKit.inside(mouseX, mouseY, x, y, w, PILL_H);
		float lit = anim("pill", overPill || open, dt);
		VeloDraw.fillRounded(context, x, y, w, PILL_H, 7, VeloUi.withAlpha(VeloAnim.lerpArgb(0xFF16161C, tint, 0.10f + 0.08f * lit), Math.round(0xE0 * present)));
		VeloDraw.strokeRounded(context, x, y, w, PILL_H, 7, VeloUi.withAlpha(tint, Math.round((0x30 + 0x50 * lit) * present)));
		// Cover.
		int cs = 16;
		if (CoverArt.has()) {
			CoverArt.draw(context, x + 3, y + 3, cs, present);
		} else {
			VeloDraw.fillRounded(context, x + 3, y + 3, cs, cs, 4, VeloUi.withAlpha(tint, Math.round(0x30 * present)));
			MediaGlyphs.note(context, x + 3 + cs / 2f, y + 3 + cs / 2f, 10, VeloUi.withAlpha(tint, a));
		}
		// Buttons on the right: play/pause and next.
		int bs = 16;
		int nx = x + w - 3 - bs;
		int px = nx - 2 - bs;
		button(context, "pill-play", px, y + 3, bs, mouseX, mouseY, dt, tint, true, NowPlaying::playPause,
				(cx, cy, c) -> {
					if (s != null && s.playing()) {
						MediaGlyphs.pause(context, cx, cy, 7, c);
					} else {
						MediaGlyphs.play(context, cx + 0.5f, cy, 7, c);
					}
				});
		button(context, "pill-next", nx, y + 3, bs, mouseX, mouseY, dt, tint, false, NowPlaying::next,
				(cx, cy, c) -> MediaGlyphs.skip(context, cx, cy, 6, 1, c));
		// Song, scrolling when it doesn't fit.
		int tx = x + 3 + cs + 6;
		int tw = px - 4 - tx;
		if (s != null && tw > 16) {
			Text line = MenuKit.uiBold(s.title());
			if (s.artist() != null) {
				line = Text.empty().append(line).append(MenuKit.ui("  " + s.artist()).copy().withColor(0xA9A9B3));
			}
			marquee(context, textRenderer, line, tx, y + 7, tw, VeloUi.withAlpha(MenuKit.TEXT, a));
		}
		// The pill's own progress, hairline along the bottom.
		if (s != null && s.lengthMs() > 0) {
			float p = s.positionNow() / (float) s.lengthMs();
			int bw = w - 14;
			context.fill(x + 7, y + PILL_H - 2, x + 7 + Math.round(bw * p), y + PILL_H - 1, VeloUi.withAlpha(tint, Math.round(0xC0 * present)));
		}
		hits.add(new Hit(x, y, px - x - 2, PILL_H, () -> open = !open));
		return w;
	}

	@FunctionalInterface
	private interface Glyph {
		void draw(float cx, float cy, int color);
	}

	private float anim(String key, boolean on, float dt) {
		float t = VeloAnim.step(hover.getOrDefault(key, 0f), on ? 1f : 0f, dt * 1.6f);
		hover.put(key, t);
		return t;
	}

	private void button(DrawContext context, String key, float x, float y, float size, int mouseX, int mouseY, float dt, int tint, boolean primary,
			Runnable action, Glyph glyph) {
		boolean over = mouseX >= x && mouseY >= y && mouseX < x + size && mouseY < y + size;
		float t = anim(key, over, dt);
		float scale = VeloStyle.pressScale(pressed.getOrDefault(key, 0L));
		float cx = x + size / 2f;
		float cy = y + size / 2f;
		VeloStyle.pushScale(context, cx, cy, scale);
		if (primary) {
			VeloDraw.shadow(context, Math.round(x), Math.round(y), Math.round(size), Math.round(size), Math.round(size / 2), Math.round(4 + 4 * t), 1,
					VeloUi.withAlpha(tint, Math.round((0x40 + 0x40 * t) * present)));
			VeloDraw.fillCircle(context, cx, cy, size / 2f, VeloUi.withAlpha(VeloAnim.lerpArgb(tint, 0xFFFFFFFF, 0.12f * t), Math.round(0xFF * present)));
			glyph.draw(cx, cy, VeloUi.withAlpha(0xFF101014, Math.round(0xFF * present)));
		} else {
			if (t > 0.02f) {
				VeloDraw.fillCircle(context, cx, cy, size / 2f, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x1A * t * present)));
			}
			glyph.draw(cx, cy, VeloUi.withAlpha(VeloAnim.lerpArgb(0xFFC9C9D1, 0xFFFFFFFF, t), Math.round(0xFF * present)));
		}
		VeloStyle.popScale(context);
		hits.add(new Hit(x, y, size, size, () -> {
			pressed.put(key, System.nanoTime());
			action.run();
		}));
	}

	/** The panel under the pill: drawn last, over the rest of the screen. */
	public void renderPanel(DrawContext context, TextRenderer textRenderer, int mouseX, int mouseY, int screenW, int screenH) {
		MediaState s = NowPlaying.state();
		if (openAnim < 0.02f || s == null || pillW == 0) {
			return;
		}
		float dt = frameDt;
		int tint = MediaPlayerModule.tint();
		float e = 1f - (1f - openAnim) * (1f - openAnim) * (1f - openAnim);
		int a = Math.round(0xFF * Math.min(1f, openAnim * 1.3f));
		int w = PANEL_W;
		int h = PANEL_H;
		int x = Math.max(4, Math.min(screenW - w - 4, pillX + pillW - w));
		int y = pillY + PILL_H + 6;
		panelX = x;
		panelY = y;
		// Grows out of the pill: scale from its top-right corner, sliding down a touch.
		context.getMatrices().pushMatrix();
		float ox = pillX + pillW;
		float oy = y;
		context.getMatrices().translate(ox, oy + (1f - e) * -6f);
		float sc = 0.86f + 0.14f * e;
		context.getMatrices().scale(sc, sc);
		context.getMatrices().translate(-ox, -oy);
		try {
			VeloDraw.shadow(context, x, y, w, h, 12, 18, 5, VeloUi.withAlpha(0xFF000000, Math.round(0xA0 * openAnim)));
			VeloDraw.shadow(context, x, y, w, h, 12, 12, 0, VeloUi.withAlpha(tint, Math.round(0x40 * openAnim * (0.6f + 0.4f * energy))));
			VeloDraw.fillRounded(context, x, y, w, h, 12, VeloUi.withAlpha(0xFF111116, a));
			VeloDraw.fillRoundedGradient(context, x, y, w, h, 12, VeloUi.withAlpha(tint, Math.round(0x40 * openAnim)), 0x00000000);
			VeloDraw.strokeRounded(context, x, y, w, h, 12, VeloUi.withAlpha(VeloAnim.lerpArgb(0xFFFFFFFF, tint, 0.5f), Math.round(0x38 * openAnim)));

			// Cover.
			int cs = 64;
			int cx = x + 10;
			int cy = y + 10;
			VeloDraw.fillRounded(context, cx, cy, cs, cs, 8, VeloUi.withAlpha(0xFF1E1E26, a));
			if (CoverArt.has()) {
				CoverArt.draw(context, cx, cy, cs, openAnim);
			} else {
				MediaGlyphs.note(context, cx + cs / 2f, cy + cs / 2f, 26, VeloUi.withAlpha(tint, a));
			}
			VeloDraw.strokeRounded(context, cx, cy, cs, cs, 4, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x24 * openAnim)));

			// Text block.
			int tx = cx + cs + 10;
			int tw = x + w - 10 - tx;
			int head = VeloUi.withAlpha(VeloAnim.lerpArgb(0xFF8E8E9A, tint, 0.6f), a);
			float eq = MediaGlyphs.equalizer(context, tx, cy + 7, 6, energy, head);
			MenuKit.text(context, textRenderer, MenuKit.fit(textRenderer, MenuKit.small((s.playing() ? "PLAYING ON " : "PAUSED ON ") + s.playerLabel().toUpperCase()),
					tw - (int) eq - 4), tx + (int) eq + 4, cy + 1, head);
			marquee(context, textRenderer, MenuKit.display(s.title()), tx, cy + 14, tw, VeloUi.withAlpha(MenuKit.TEXT, a));
			if (s.artist() != null) {
				marquee(context, textRenderer, MenuKit.ui(s.artist()), tx, cy + 28, tw, VeloUi.withAlpha(MenuKit.TEXT_2, a));
			}
			if (s.album() != null && !s.album().equals(s.title())) {
				marquee(context, textRenderer, MenuKit.ui(s.album()), tx, cy + 40, tw, VeloUi.withAlpha(MenuKit.TEXT_3, a));
			}

			// Transport, centred under the text.
			float by = cy + 58;
			float mid = tx + tw / 2f;
			button(context, "prev", mid - 34, by - 9, 18, mouseX, mouseY, dt, tint, false, NowPlaying::previous,
					(gx, gy, c) -> MediaGlyphs.skip(context, gx, gy, 7, -1, s.canPrevious() ? c : VeloUi.withAlpha(c, 0x60)));
			button(context, "play", mid - 11, by - 11, 22, mouseX, mouseY, dt, tint, true, NowPlaying::playPause, (gx, gy, c) -> {
				if (s.playing()) {
					MediaGlyphs.pause(context, gx, gy, 9, c);
				} else {
					MediaGlyphs.play(context, gx + 0.8f, gy, 9, c);
				}
			});
			button(context, "next", mid + 16, by - 9, 18, mouseX, mouseY, dt, tint, false, NowPlaying::next,
					(gx, gy, c) -> MediaGlyphs.skip(context, gx, gy, 7, 1, s.canNext() ? c : VeloUi.withAlpha(c, 0x60)));

			// Seek bar with times.
			seekX = x + 10;
			seekY = y + 90;
			seekW = w - 20;
			long length = s.lengthMs();
			long pos = drag == Drag.SEEK ? (long) (dragValue * length) : s.positionNow();
			float p = length > 0 ? Math.min(1f, pos / (float) length) : 0f;
			boolean overSeek = s.canSeek() && length > 0 && MenuKit.inside(mouseX, mouseY, seekX - 2, seekY - 5, seekW + 4, 11);
			float st = anim("seek", overSeek || drag == Drag.SEEK, dt);
			float th = 2f + 1.5f * st;
			VeloDraw.fillRounded(context, seekX, seekY - th / 2, seekW, th, th / 2, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x22 * openAnim)));
			if (length > 0) {
				VeloDraw.fillRounded(context, seekX, seekY - th / 2, Math.max(th, seekW * p), th, th / 2, VeloUi.withAlpha(tint, a));
				if (st > 0.02f) {
					VeloDraw.fillCircle(context, seekX + seekW * p, seekY, 2.5f + 1.5f * st, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0xFF * st * openAnim)));
				}
			}
			Text left = MenuKit.mono(NowPlaying.time(pos));
			Text right = MenuKit.mono(length > 0 ? NowPlaying.time(length) : "--:--");
			MenuKit.text(context, textRenderer, left, seekX, seekY + 5, VeloUi.withAlpha(MenuKit.TEXT_3, a));
			MenuKit.text(context, textRenderer, right, seekX + seekW - textRenderer.getWidth(right), seekY + 5, VeloUi.withAlpha(MenuKit.TEXT_3, a));

			// Volume.
			int vy = y + h - 14;
			double vol = s.volume();
			if (vol >= 0) {
				if (shownVolume < 0) {
					shownVolume = (float) vol;
				}
				shownVolume = VeloAnim.step(shownVolume, (float) (drag == Drag.VOLUME ? dragValue : vol), drag == Drag.VOLUME ? 1f : dt * 0.6f);
				MediaGlyphs.volume(context, x + 16, vy, 9, shownVolume, VeloUi.withAlpha(0xFFC9C9D1, a));
				hits.add(new Hit(x + 9, vy - 7, 14, 14, () -> NowPlaying.setVolume(s.volume() > 0.001 ? 0 : 0.5)));
				volX = x + 28;
				volY = vy;
				volW = w - 28 - 40;
				boolean overVol = MenuKit.inside(mouseX, mouseY, volX - 3, vy - 6, volW + 6, 12);
				float vt = anim("vol", overVol || drag == Drag.VOLUME, dt);
				float vh = 3f + 1f * vt;
				VeloDraw.fillRounded(context, volX, vy - vh / 2, volW, vh, vh / 2, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x22 * openAnim)));
				VeloDraw.fillRounded(context, volX, vy - vh / 2, Math.max(vh, volW * shownVolume), vh, vh / 2,
						VeloUi.withAlpha(VeloAnim.lerpArgb(0xFFE4E4E8, tint, 0.55f), a));
				VeloDraw.fillCircle(context, volX + volW * shownVolume, vy, 3.2f + 1f * vt, VeloUi.withAlpha(0xFFFFFFFF, a));
				Text pct = MenuKit.mono(Math.round(shownVolume * 100) + "%");
				MenuKit.text(context, textRenderer, pct, x + w - 10 - textRenderer.getWidth(pct), vy - 3, VeloUi.withAlpha(MenuKit.TEXT_2, a));
			} else {
				volW = 0;
				MenuKit.text(context, textRenderer, MenuKit.ui(s.playerLabel() + " doesn't share its volume - use its own slider."), x + 10, vy - 3,
						VeloUi.withAlpha(MenuKit.TEXT_3, a));
			}
		} finally {
			context.getMatrices().popMatrix();
		}
	}

	private static void marquee(DrawContext context, TextRenderer textRenderer, Text text, int x, int y, int w, int color) {
		int tw = textRenderer.getWidth(text);
		int over = tw - w;
		if (over <= 0) {
			MenuKit.text(context, textRenderer, text, x, y, color);
			return;
		}
		double period = 2.4 + over / 22.0;
		double t = (System.nanoTime() / 1_000_000_000.0) % (period * 2 + 3.0);
		double shift = t < 1.5 ? 0 : t < 1.5 + period ? smooth((t - 1.5) / period) * over : t < 3.0 + period ? over
				: (1 - smooth((t - 3.0 - period) / period)) * over;
		context.enableScissor(x, y - 3, x + w, y + 12);
		MenuKit.text(context, textRenderer, text, x - (int) Math.round(shift), y, color);
		context.disableScissor();
	}

	private static double smooth(double t) {
		t = Math.max(0, Math.min(1, t));
		return t * t * (3 - 2 * t);
	}

	private boolean insidePanel(double mx, double my) {
		return openAnim > 0.5f && mx >= panelX && my >= panelY && mx < panelX + PANEL_W && my < panelY + PANEL_H;
	}

	// ---- Input: the screen forwards these first; true = handled ----

	public boolean mouseClicked(double mx, double my) {
		MediaState s = NowPlaying.state();
		if (s != null && openAnim > 0.5f) {
			if (s.canSeek() && s.lengthMs() > 0 && mx >= seekX - 2 && mx <= seekX + seekW + 2 && my >= seekY - 5 && my <= seekY + 6) {
				drag = Drag.SEEK;
				dragValue = clamp((mx - seekX) / seekW);
				return true;
			}
			if (volW > 0 && s.volume() >= 0 && mx >= volX - 3 && mx <= volX + volW + 3 && my >= volY - 6 && my <= volY + 6) {
				drag = Drag.VOLUME;
				dragValue = clamp((mx - volX) / volW);
				NowPlaying.setVolume(dragValue);
				return true;
			}
		}
		for (Hit hit : List.copyOf(hits)) {
			if (hit.contains(mx, my)) {
				MinecraftClient.getInstance().getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.0f));
				hit.action().run();
				return true;
			}
		}
		if (insidePanel(mx, my)) {
			return true;
		}
		if (open) {
			// A click anywhere else closes the panel (and does nothing else).
			open = false;
			return true;
		}
		return false;
	}

	public boolean mouseDragged(double mx, double my) {
		if (drag == Drag.SEEK) {
			dragValue = clamp((mx - seekX) / seekW);
			return true;
		}
		if (drag == Drag.VOLUME) {
			dragValue = clamp((mx - volX) / volW);
			NowPlaying.setVolume(dragValue);
			return true;
		}
		return false;
	}

	public boolean mouseReleased() {
		MediaState s = NowPlaying.state();
		if (drag == Drag.SEEK && s != null) {
			NowPlaying.seek((long) (dragValue * s.lengthMs()));
		}
		boolean was = drag != Drag.NONE;
		drag = Drag.NONE;
		return was;
	}

	/** Scrolling over the pill or the panel changes the volume. */
	public boolean mouseScrolled(double mx, double my, double amount) {
		MediaState s = NowPlaying.state();
		boolean over = pillW > 0 && mx >= pillX && my >= pillY && mx < pillX + pillW && my < pillY + PILL_H || insidePanel(mx, my);
		if (!over || s == null || s.volume() < 0) {
			return false;
		}
		NowPlaying.setVolume(s.volume() + Math.signum(amount) * 0.05);
		return true;
	}

	/** Escape closes the panel first. */
	public boolean closePanel() {
		if (open) {
			open = false;
			return true;
		}
		return false;
	}

	private static double clamp(double v) {
		return Math.max(0, Math.min(1, v));
	}
}
