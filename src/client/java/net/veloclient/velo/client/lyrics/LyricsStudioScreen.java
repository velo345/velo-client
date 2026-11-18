package net.veloclient.velo.client.lyrics;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.util.Keys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The Lyrics Studio: every Lyrics setting on a panel at the left, the game stays visible on the right with the lyrics
 * drawn live (demo lines when nothing plays), so each change shows the moment it's made. Presets on top.
 */
public final class LyricsStudioScreen extends Screen {

	private static final int PANEL_W = 236;
	private static final int ROW = 20;
	private static final int[] PALETTE = {0xFFFFFFFF, 0xFFB8B8C8, 0xFF101014, 0xFFFF3B47, 0xFFFF8A3D, 0xFFFFD84A, 0xFF3DDC97,
			0xFF7CFFCB, 0xFF39E7FF, 0xFF9EC5FF, 0xFF5B8CFF, 0xFF7A8CFF, 0xFFB040FF, 0xFFFF3DDB,
			0xFFFFE7A3, 0xFFFF9A3D, 0xFFFF3D1F, 0xFF05FFA1, 0xFF01CDFE, 0xFFFF71CE, 0xFF7FF6FF};

	private final Screen parent;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private int scroll;
	private int contentH;
	private Slider dragging;
	private final List<Slider> sliders = new ArrayList<>();
	private static final List<String> SCREEN_SPOTS = LyricsStyle.SCREEN_SPOTS;

	private record Slider(String id, int x, int w, double min, double max, DoubleConsumer set) {
	}

	public LyricsStudioScreen(Screen parent) {
		super(Text.literal("Lyrics Studio"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		LyricsModule.studioOpen = true;
		LyricsRenderer.previewShift = (PANEL_W + 16) / (float) Math.max(1, this.width);
		net.veloclient.velo.client.nowplaying.NowPlaying.acquire("lyrics");
	}

	@Override
	public void removed() {
		LyricsModule.studioOpen = false;
		LyricsRenderer.previewShift = 0f;
		LyricsModule m = LyricsModule.get();
		if (m == null || !m.isEnabled()) {
			net.veloclient.velo.client.nowplaying.NowPlaying.release("lyrics");
		}
		net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		super.removed();
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public void close() {
		leave();
	}

	private void leave() {
		MinecraftClient.getInstance().setScreen(parent);
	}

	// the world stays visible behind: no blur, no dimming
	//? if <26.1 {
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}
	//?} else {
	/*@Override
	public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
	}
	*///?}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		sliders.clear();
		TextRenderer textRenderer = this.textRenderer;
		LyricsStyle st = LyricsModule.style;
		int px = 8;
		int py = 8;
		int ph = this.height - 16;
		VeloDraw.fillRounded(context, px, py, PANEL_W, ph, 10, 0xE40C0C10);
		VeloDraw.strokeRounded(context, px, py, PANEL_W, ph, 10, 0x22FFFFFF);
		// header
		MenuKit.text(context, textRenderer, MenuKit.uiBold("Lyrics Studio"), px + 12, py + 11, 0xFFFFFFFF);
		MenuKit.text(context, textRenderer, MenuKit.ui(statusLine()), px + 12, py + 24, statusColor());
		hits.add(button(context, textRenderer, px + PANEL_W - 52, py + 9, 42, "Done", mouseX, mouseY, this::leave));
		int top = py + 40;
		int bottom = py + ph - 8;
		int maxScroll = Math.max(0, contentH - (bottom - top));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		context.enableScissor(px, top, px + PANEL_W, bottom);
		int x = px + 12;
		int w = PANEL_W - 24;
		int y = top - scroll;
		int startY = y;

		// presets
		y = header(context, textRenderer, "Presets", x, y);
		int i = 0;
		int cardW = (w - 6) / 2;
		for (String name : LyricsStyle.PRESETS.keySet()) {
			LyricsStyle p = LyricsStyle.PRESETS.get(name);
			int cx = x + (i % 2) * (cardW + 6);
			int cy = y + (i / 2) * 36;
			boolean on = name.equals(st.preset);
			boolean hov = VeloUi.inside(mouseX, mouseY, cx, cy, cardW, 32) && mouseY >= top && mouseY < bottom;
			VeloDraw.fillRounded(context, cx, cy, cardW, 32, 7, hov ? 0xFF22222A : 0xFF18181E);
			if (on) {
				VeloDraw.strokeRounded(context, cx, cy, cardW, 32, 7, MenuKit.accent());
			}
			// a little gradient bar in the preset's colours
			for (int b = 0; b < cardW - 16; b++) {
				float f = b / (float) (cardW - 16);
				int c = switch (p.colorMode) {
					case "Rainbow" -> 0xFF000000 | java.awt.Color.HSBtoRGB(f * 0.8f, 0.65f, 1f);
					case "Solid" -> p.color;
					case "Per Word" -> f < 0.5f ? p.color : p.color2;
					default -> LyricsRenderer.lerpColor(p.color, p.color2, f);
				};
				context.fill(cx + 8 + b, cy + 24, cx + 9 + b, cy + 26, c);
			}
			MenuKit.text(context, textRenderer, LyricsRenderer.fontText(p.font, name), cx + 8, cy + 7, 0xFFFFFFFF);
			MenuKit.text(context, textRenderer, MenuKit.ui(p.world3d() ? (p.sky() ? "3D sky" : "3D in front") : "2D screen"), cx + 8, cy + 15, 0xFF8A8A95);
			if (cy >= top && cy + 32 <= bottom) {
				hits.add(new VeloUi.Hit(cx, cy, cardW, 32, () -> LyricsModule.setStyle(st.withPreset(name))));
			}
			i++;
		}
		y += ((i + 1) / 2) * 36 + 6;

		// where
		y = header(context, textRenderer, "Where", x, y);
		y = choice(context, textRenderer, "Style", LyricsStyle.DISPLAYS, () -> st.display, v -> st.display = v, x, y, w, mouseX, mouseY, top, bottom);
		if (st.world3d()) {
			y = choice(context, textRenderer, "Place", LyricsStyle.PLACEMENTS, () -> st.placement, v -> LyricsModule.placement(st, v), x, y, w, mouseX, mouseY, top, bottom);
			y = choice(context, textRenderer, "Depth", LyricsStyle.DEPTHS, () -> st.depth, v -> st.depth = v, x, y, w, mouseX, mouseY, top, bottom);
			y = choice(context, textRenderer, "Lines appear", LyricsStyle.SPREADS, () -> st.spread, v -> st.spread = v, x, y, w, mouseX, mouseY, top, bottom);
			y = toggle(context, textRenderer, "Follow my view", () -> st.followView, v -> st.followView = v, x, y, w, mouseX, mouseY, top, bottom);
			if (st.sky()) {
				y = slider(context, textRenderer, "Distance", 30, 240, () -> st.distance, v -> st.distance = Math.round(v), v -> Math.round(v) + " blocks", x, y, w, mouseX, mouseY, top, bottom);
				y = slider(context, textRenderer, "Height in the sky", 0, 75, () -> st.height, v -> st.height = Math.round(v), v -> Math.round(v) + "\u00B0", x, y, w, mouseX, mouseY, top, bottom);
			} else {
				y = slider(context, textRenderer, "Distance", 2, 14, () -> st.distance, v -> st.distance = Math.round(v * 2) / 2.0, v -> String.format(Locale.ROOT, "%.1f blocks", v), x, y, w, mouseX, mouseY, top, bottom);
				y = slider(context, textRenderer, "Height", -3, 5, () -> st.height, v -> st.height = Math.round(v * 10) / 10.0, v -> String.format(Locale.ROOT, "%+.1f", v), x, y, w, mouseX, mouseY, top, bottom);
			}
			y = slider(context, textRenderer, "Size", 0.2, 4, () -> st.size, v -> st.size = Math.round(v * 10) / 10.0, v -> String.format(Locale.ROOT, "%.1fx", v), x, y, w, mouseX, mouseY, top, bottom);
			y = slider(context, textRenderer, "Tilt", -30, 30, () -> st.tilt, v -> st.tilt = Math.round(v), v -> Math.round(v) + "\u00B0", x, y, w, mouseX, mouseY, top, bottom);
		} else {
			y = choice(context, textRenderer, "Position", SCREEN_SPOTS, () -> st.screenSpot, v -> st.screenSpot = v, x, y, w, mouseX, mouseY, top, bottom);
			y = choice(context, textRenderer, "Lines appear", LyricsStyle.SPREADS, () -> st.spread, v -> st.spread = v, x, y, w, mouseX, mouseY, top, bottom);
			y = slider(context, textRenderer, "Size", 0.4, 3, () -> st.screenSize, v -> st.screenSize = Math.round(v * 10) / 10.0, v -> String.format(Locale.ROOT, "%.1fx", v), x, y, w, mouseX, mouseY, top, bottom);
		}

		// look
		y = header(context, textRenderer, "Look", x, y + 4);
		y = choice(context, textRenderer, "Font", LyricsStyle.FONTS, () -> st.font, v -> st.font = v, x, y, w, mouseX, mouseY, top, bottom);
		if (st.world3d()) {
			y = toggle(context, textRenderer, "Solid 3D letters", () -> st.text3d, v -> st.text3d = v, x, y, w, mouseX, mouseY, top, bottom);
			if (st.text3d) {
				y = slider(context, textRenderer, "Letter depth", 0.5, 8, () -> st.thickness, v -> st.thickness = Math.round(v * 10) / 10.0, v -> String.format(Locale.ROOT, "%.1f", v), x, y, w, mouseX, mouseY, top, bottom);
				y = slider(context, textRenderer, "Rounded edge", 0, 1, () -> st.bevel, v -> st.bevel = Math.round(v * 20) / 20.0, v -> Math.round(v * 100) + "%", x, y, w, mouseX, mouseY, top, bottom);
			}
		}
		y = choice(context, textRenderer, "Letters", LyricsStyle.CASES, () -> st.textCase, v -> st.textCase = v, x, y, w, mouseX, mouseY, top, bottom);
		y = slider(context, textRenderer, "Letter spacing", 0, 4, () -> st.spacing, v -> st.spacing = Math.round(v * 10) / 10.0, v -> String.format(Locale.ROOT, "%.1f", v), x, y, w, mouseX, mouseY, top, bottom);
		y = choice(context, textRenderer, "Colours", LyricsStyle.COLOR_MODES, () -> st.colorMode, v -> st.colorMode = v, x, y, w, mouseX, mouseY, top, bottom);
		if (!"Rainbow".equals(st.colorMode)) {
			y = colors(context, textRenderer, "Colour", () -> st.color, v -> st.color = v, x, y, w, mouseX, mouseY, top, bottom);
		}
		if (!"Solid".equals(st.colorMode) && !"Rainbow".equals(st.colorMode)) {
			y = colors(context, textRenderer, "Second colour", () -> st.color2, v -> st.color2 = v, x, y, w, mouseX, mouseY, top, bottom);
		}
		y = toggle(context, textRenderer, "Karaoke fill (in time)", () -> st.karaoke, v -> st.karaoke = v, x, y, w, mouseX, mouseY, top, bottom);
		if (st.karaoke) {
			y = colors(context, textRenderer, "Fill colour", () -> st.highlight, v -> st.highlight = v, x, y, w, mouseX, mouseY, top, bottom);
		}
		y = slider(context, textRenderer, "Opacity", 0.1, 1, () -> st.opacity, v -> st.opacity = Math.round(v * 20) / 20.0, v -> Math.round(v * 100) + "%", x, y, w, mouseX, mouseY, top, bottom);

		// effects
		y = header(context, textRenderer, "Effects", x, y + 4);
		y = toggle(context, textRenderer, "Glow", () -> st.glow, v -> st.glow = v, x, y, w, mouseX, mouseY, top, bottom);
		if (st.glow) {
			y = colors(context, textRenderer, "Glow colour", () -> st.glowColor, v -> st.glowColor = v, x, y, w, mouseX, mouseY, top, bottom);
			y = slider(context, textRenderer, "Glow size", 0.3, 3, () -> st.glowSize, v -> st.glowSize = Math.round(v * 10) / 10.0, v -> String.format(Locale.ROOT, "%.1f", v), x, y, w, mouseX, mouseY, top, bottom);
			y = slider(context, textRenderer, "Glow strength", 0.1, 2.5, () -> st.glowStrength, v -> st.glowStrength = Math.round(v * 10) / 10.0, v -> Math.round(v * 100) + "%", x, y, w, mouseX, mouseY, top, bottom);
		}
		if (!st.solid()) {
		y = toggle(context, textRenderer, "Outline", () -> st.outline, v -> st.outline = v, x, y, w, mouseX, mouseY, top, bottom);
		if (st.outline) {
			y = colors(context, textRenderer, "Outline colour", () -> st.outlineColor, v -> st.outlineColor = v, x, y, w, mouseX, mouseY, top, bottom);
			y = slider(context, textRenderer, "Outline thickness", 0.4, 3, () -> st.outlineSize, v -> st.outlineSize = Math.round(v * 10) / 10.0, v -> String.format(Locale.ROOT, "%.1f", v), x, y, w, mouseX, mouseY, top, bottom);
		}
		y = toggle(context, textRenderer, "Drop shadow", () -> st.shadow, v -> st.shadow = v, x, y, w, mouseX, mouseY, top, bottom);
		if (st.shadow) {
			y = colors(context, textRenderer, "Shadow colour", () -> st.shadowColor, v -> st.shadowColor = v, x, y, w, mouseX, mouseY, top, bottom);
		}
		}

		// motion
		y = header(context, textRenderer, "Animation", x, y + 4);
		y = choice(context, textRenderer, "Coming in", LyricsStyle.ENTERS, () -> st.enter, v -> st.enter = v, x, y, w, mouseX, mouseY, top, bottom);
		y = choice(context, textRenderer, "Going out", LyricsStyle.EXITS, () -> st.exit, v -> st.exit = v, x, y, w, mouseX, mouseY, top, bottom);
		y = toggle(context, textRenderer, "Letter by letter", () -> st.perLetter, v -> st.perLetter = v, x, y, w, mouseX, mouseY, top, bottom);
		y = slider(context, textRenderer, "Speed", 0.3, 3, () -> st.speed, v -> st.speed = Math.round(v * 10) / 10.0, v -> String.format(Locale.ROOT, "%.1fx", v), x, y, w, mouseX, mouseY, top, bottom);
		y = choice(context, textRenderer, "While showing", LyricsStyle.IDLES, () -> st.idle, v -> st.idle = v, x, y, w, mouseX, mouseY, top, bottom);
		y = slider(context, textRenderer, "Chaos", 0, 1, () -> st.chaos, v -> st.chaos = Math.round(v * 20) / 20.0, v -> Math.round(v * 100) + "%", x, y, w, mouseX, mouseY, top, bottom);

		// lines
		y = header(context, textRenderer, "Lines", x, y + 4);
		y = toggle(context, textRenderer, "Show the next line", () -> st.showNext, v -> st.showNext = v, x, y, w, mouseX, mouseY, top, bottom);
		y = toggle(context, textRenderer, "Show the previous line", () -> st.showPrevious, v -> st.showPrevious = v, x, y, w, mouseX, mouseY, top, bottom);
		if (st.showNext || st.showPrevious) {
			y = slider(context, textRenderer, "Space to other lines", 1.2, 5, () -> st.lineGap, v -> st.lineGap = Math.round(v * 10) / 10.0, v -> String.format(Locale.ROOT, "%.1f", v), x, y, w, mouseX, mouseY, top, bottom);
		}
		y = toggle(context, textRenderer, "Song title when a song starts", () -> st.songCard, v -> st.songCard = v, x, y, w, mouseX, mouseY, top, bottom);

		// sync
		y = header(context, textRenderer, "Sync", x, y + 4);
		y = slider(context, textRenderer, "Offset", -2000, 2000, () -> st.offsetMs, v -> st.offsetMs = (int) Math.round(v / 50.0) * 50,
				v -> (Math.round(v / 50.0) * 50 > 0 ? "+" : "") + Math.round(v / 50.0) * 50 + " ms", x, y, w, mouseX, mouseY, top, bottom);
		MenuKit.text(context, textRenderer, MenuKit.ui("Lyrics early? Move it right. Late? Left."), x, y + 2, 0xFF6E6E78);
		y += 16;
		MenuKit.text(context, textRenderer, MenuKit.ui("Synced lyrics from LRCLIB (lrclib.net)."), x, y + 2, 0xFF6E6E78);
		y += 16;
		contentH = y - startY + 8;
		context.disableScissor();
		if (maxScroll > 0) {
			int barH = Math.max(24, (bottom - top) * (bottom - top) / Math.max(1, contentH));
			int barY = top + (int) ((bottom - top - barH) * (scroll / (double) maxScroll));
			VeloDraw.fillRounded(context, px + PANEL_W - 5, barY, 3, barH, 1, 0x50FFFFFF);
		}
		// what the preview shows, at the right
		int hx = PANEL_W + 24;
		if (this.width - hx > 160) {
			String hint = LyricsSync.showingDemo(true) ? "Live preview - play a song on Spotify to see its real lyrics" : "Live preview - " + LyricsSync.title();
			MenuKit.text(context, textRenderer, MenuKit.ui(VeloUi.trim(hint, this.width - hx - 12)), hx, this.height - 18, 0xA0FFFFFF);
		}
	}

	private String statusLine() {
		return switch (LyricsSync.status()) {
			case NOTHING_PLAYING -> "Nothing playing - showing demo lines";
			case LOADING -> "Finding lyrics for " + VeloUi.trim(LyricsSync.title(), 130);
			case FOUND -> "♪ " + VeloUi.trim(LyricsSync.title(), 150);
			case NOT_FOUND -> "No synced lyrics for this song";
			case OFFLINE -> "Couldn't reach the lyrics service";
		};
	}

	private int statusColor() {
		return switch (LyricsSync.status()) {
			case FOUND -> 0xFF3DDC97;
			case NOT_FOUND, OFFLINE -> 0xFFFF8A8D;
			default -> 0xFF8A8A95;
		};
	}

	// ---------------------------------------------------------------- controls

	private int header(DrawContext context, TextRenderer textRenderer, String label, int x, int y) {
		MenuKit.text(context, textRenderer, MenuKit.uiBold(label.toUpperCase(Locale.ROOT)), x, y + 4, 0xFF8A8A95);
		return y + 18;
	}

	private VeloUi.Hit button(DrawContext context, TextRenderer textRenderer, int x, int y, int w, String label, int mouseX, int mouseY, Runnable action) {
		boolean hov = VeloUi.inside(mouseX, mouseY, x, y, w, 16);
		VeloDraw.fillRounded(context, x, y, w, 16, 8, hov ? MenuKit.accent() : 0xFF26262E);
		Text t = MenuKit.uiBold(label);
		MenuKit.text(context, textRenderer, t, x + (w - MenuKit.width(textRenderer, t)) / 2, y + 4, 0xFFFFFFFF);
		return new VeloUi.Hit(x, y, w, 16, action);
	}

	private boolean visible(int y, int h, int top, int bottom) {
		return y >= top && y + h <= bottom;
	}

	private int choice(DrawContext context, TextRenderer textRenderer, String label, List<String> options, Supplier<String> get, Consumer<String> set,
			int x, int y, int w, int mouseX, int mouseY, int top, int bottom) {
		MenuKit.text(context, textRenderer, MenuKit.ui(label), x, y + 6, 0xFFE6E6EC);
		int bw = 112;
		int bx = x + w - bw;
		boolean hov = VeloUi.inside(mouseX, mouseY, bx, y + 1, bw, 16) && mouseY >= top && mouseY < bottom;
		VeloDraw.fillRounded(context, bx, y + 1, bw, 16, 8, hov ? 0xFF2A2A33 : 0xFF1C1C23);
		Text value = MenuKit.ui(VeloUi.trim(get.get(), bw - 28));
		MenuKit.text(context, textRenderer, value, bx + (bw - MenuKit.width(textRenderer, value)) / 2, y + 5, 0xFFFFFFFF);
		MenuKit.text(context, textRenderer, MenuKit.ui("‹"), bx + 6, y + 5, 0xFF8A8A95);
		MenuKit.text(context, textRenderer, MenuKit.ui("›"), bx + bw - 10, y + 5, 0xFF8A8A95);
		if (visible(y, 18, top, bottom)) {
			int idx = Math.max(0, options.indexOf(get.get()));
			hits.add(new VeloUi.Hit(bx, y + 1, bw / 2, 16, () -> apply(() -> set.accept(options.get((idx - 1 + options.size()) % options.size())))));
			hits.add(new VeloUi.Hit(bx + bw / 2, y + 1, bw - bw / 2, 16, () -> apply(() -> set.accept(options.get((idx + 1) % options.size())))));
		}
		return y + ROW;
	}

	private int toggle(DrawContext context, TextRenderer textRenderer, String label, java.util.function.BooleanSupplier get, Consumer<Boolean> set,
			int x, int y, int w, int mouseX, int mouseY, int top, int bottom) {
		MenuKit.text(context, textRenderer, MenuKit.ui(label), x, y + 6, 0xFFE6E6EC);
		boolean on = get.getAsBoolean();
		int sx = x + w - 24;
		VeloDraw.fillRounded(context, sx, y + 4, 24, 12, 6, on ? MenuKit.accent() : 0xFF33333C);
		VeloDraw.fillRounded(context, on ? sx + 13 : sx + 1, y + 5, 10, 10, 5, 0xFFFFFFFF);
		if (visible(y, 18, top, bottom)) {
			hits.add(new VeloUi.Hit(x, y, w, 18, () -> apply(() -> set.accept(!on))));
		}
		return y + ROW;
	}

	private int slider(DrawContext context, TextRenderer textRenderer, String label, double min, double max, DoubleSupplier get, DoubleConsumer set,
			java.util.function.DoubleFunction<String> format, int x, int y, int w, int mouseX, int mouseY, int top, int bottom) {
		double v = Math.max(min, Math.min(max, get.getAsDouble()));
		MenuKit.text(context, textRenderer, MenuKit.ui(label), x, y + 2, 0xFFE6E6EC);
		Text val = MenuKit.ui(format.apply(v));
		MenuKit.text(context, textRenderer, val, x + w - MenuKit.width(textRenderer, val), y + 2, 0xFF8A8A95);
		int ty = y + 14;
		VeloDraw.fillRounded(context, x, ty, w, 3, 1, 0xFF2A2A33);
		int fill = (int) Math.round((v - min) / (max - min) * w);
		VeloDraw.fillRounded(context, x, ty, Math.max(2, fill), 3, 1, MenuKit.accent());
		VeloDraw.fillRounded(context, x + fill - 4, ty - 3, 9, 9, 4, 0xFFFFFFFF);
		if (visible(y, 22, top, bottom)) {
			Slider s = new Slider(label, x, w, min, max, set);
			sliders.add(s);
			hits.add(new VeloUi.Hit(x - 4, ty - 6, w + 8, 14, () -> {
				dragging = s;
			}));
		}
		return y + 24;
	}

	private int colors(DrawContext context, TextRenderer textRenderer, String label, IntSupplier get, IntConsumer set,
			int x, int y, int w, int mouseX, int mouseY, int top, int bottom) {
		MenuKit.text(context, textRenderer, MenuKit.ui(label), x, y + 2, 0xFFE6E6EC);
		int current = get.getAsInt() | 0xFF000000;
		int sw = 13;
		int gap = (w - 7 * sw) / 6;
		for (int i = 0; i < PALETTE.length; i++) {
			int cx = x + (i % 7) * (sw + gap);
			int cy = y + 14 + (i / 7) * (sw + 4);
			boolean on = (PALETTE[i] | 0xFF000000) == current;
			if (on) {
				VeloDraw.fillRounded(context, cx - 2, cy - 2, sw + 4, sw + 4, 4, 0xFFFFFFFF);
			}
			VeloDraw.fillRounded(context, cx, cy, sw, sw, 3, PALETTE[i]);
			if (visible(cy, sw, top, bottom)) {
				int color = PALETTE[i];
				hits.add(new VeloUi.Hit(cx, cy, sw, sw, () -> apply(() -> set.accept(color))));
			}
		}
		return y + 14 + ((PALETTE.length + 6) / 7) * (sw + 4) + 4;
	}

	private void apply(Runnable change) {
		LyricsModule.edit(s -> change.run());
	}

	// ---------------------------------------------------------------- input

	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				if (dragging != null) {
					dragTo(click.x());
				}
				return true;
			}
		}
		return velo$superMouseClicked(click, doubled);
	}

	private void dragTo(double mx) {
		Slider s = dragging;
		double p = Math.max(0, Math.min(1, (mx - s.x()) / (double) s.w()));
		double value = s.min() + (s.max() - s.min()) * p;
		LyricsModule.edit(st -> s.set().accept(value));
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging != null) {
			dragTo(click.x());
			return true;
		}
		return velo$superMouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging != null) {
			dragging = null;
			return true;
		}
		return velo$superMouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (mouseX < PANEL_W + 8) {
			scroll -= (int) (vertical * 24);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (input.key() == Keys.ESCAPE) {
			leave();
			return true;
		}
		return velo$superKeyPressed(input);
	}

	// ---- 1.21.1: vanilla input still comes as numbers; the screens use 1.21.9's event objects ----
	private boolean velo$superKeyPressed(net.minecraft.client.input.KeyInput c) {
		//? if <1.21.2 {
		/*return super.keyPressed(c.key(), c.scancode(), c.modifiers());
		*///?} else {
		return super.keyPressed(c);
		//?}
	}

	private boolean velo$superMouseClicked(net.minecraft.client.gui.Click c, boolean d) {
		//? if <1.21.2 {
		/*return super.mouseClicked(c.x(), c.y(), c.button());
		*///?} else {
		return super.mouseClicked(c, d);
		//?}
	}

	private boolean velo$superMouseDragged(net.minecraft.client.gui.Click c, double dx, double dy) {
		//? if <1.21.2 {
		/*return super.mouseDragged(c.x(), c.y(), c.button(), dx, dy);
		*///?} else {
		return super.mouseDragged(c, dx, dy);
		//?}
	}

	private boolean velo$superMouseReleased(net.minecraft.client.gui.Click c) {
		//? if <1.21.2 {
		/*return super.mouseReleased(c.x(), c.y(), c.button());
		*///?} else {
		return super.mouseReleased(c);
		//?}
	}

	//? if <1.21.2 {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return mouseClicked(new net.veloclient.velo.client.compat.Click(x, y, button), net.veloclient.velo.client.compat.Click.doubled(x, y, button));
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return mouseReleased(new net.veloclient.velo.client.compat.Click(x, y, button));
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return mouseDragged(new net.veloclient.velo.client.compat.Click(x, y, button), dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return keyPressed(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}
	*///?}
}
