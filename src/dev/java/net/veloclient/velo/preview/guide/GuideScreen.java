package net.veloclient.velo.preview.guide;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.sound.VeloUiSounds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * The first-start guide (dev build): a short, skippable walk through the settings people change first - each one with a
 * live preview, so you see what a slider does before you ever load a world. Welcome -> look (Low Fire, shield, mount) ->
 * light & cloak -> HUD -> clips -> PvP -> done. Pages slide in and out, every control has its sound, the title track plays
 * underneath, and "Skip setup" is always one click away (Esc too).
 */
public final class GuideScreen extends Screen {

	private static final Identifier FIRE = Identifier.of("minecraft", "textures/block/fire_0.png");
	//? if <26.1 {
	private static final Identifier SHIELD = Identifier.of("minecraft", "textures/entity/shield_base_nopattern.png");
	//?} else {
	/*private static final Identifier SHIELD = Identifier.of("minecraft", "textures/entity/shield/shield_base_nopattern.png");
	*///?}
	private static final Identifier NEON = Identifier.of("velo-client", "textures/gui/guide/neon_cape.png");
	private static final Identifier HEART_FILL = Identifier.of("velo-client", "textures/gui/hud/heart_fill.png");
	private static final Identifier HEART_OUTLINE = Identifier.of("velo-client", "textures/gui/hud/heart_outline.png");
	private static final Identifier HEART_DETAIL = Identifier.of("velo-client", "textures/gui/hud/heart_detail.png");
	private static final Identifier FOOD_FILL = Identifier.of("velo-client", "textures/gui/hud/food_fill.png");
	private static final Identifier FOOD_OUTLINE = Identifier.of("velo-client", "textures/gui/hud/food_outline.png");
	private static final Identifier FOOD_DETAIL = Identifier.of("velo-client", "textures/gui/hud/food_detail.png");
	private static final Identifier ARMOR_FILL = Identifier.of("velo-client", "textures/gui/hud/armor_fill.png");
	private static final Identifier ARMOR_OUTLINE = Identifier.of("velo-client", "textures/gui/hud/armor_outline.png");
	private static final Identifier ARMOR_DETAIL = Identifier.of("velo-client", "textures/gui/hud/armor_detail.png");

	private static final String[] STEP_NAMES = {"Welcome", "Look", "View", "Style", "HUD", "Overlays", "Combat HUD", "Clips", "PvP", "Done"};
	/** The guide is laid out on this fixed canvas and scaled to fit the window, so it never clips at any GUI scale. */
	private static final int DW = 640;
	private static final int DH = 400;
	private static final int[] SWATCHES = {0xFFE3262B, 0xFFFF7A1A, 0xFFF5C33B, 0xFF39D98A, 0xFF3BA7FF, 0xFF9B6BFF, 0xFFFF5FD2, 0xFFF4F4F6};

	private final Screen parent;
	private int step;
	private int prevStep = -1;
	private long stepChangedAt;
	private int direction = 1;
	private long openedAt;
	private long lastNanos;
	private boolean closing;
	private long closingAt;
	private String hudPick = "hearts";

	/** Clickable areas of the current page (rebuilt each frame). */
	private final List<Hit> hits = new ArrayList<>();
	/** Sliders of the current page (rebuilt each frame), the one being dragged. */
	private final List<Slider> sliders = new ArrayList<>();
	private Slider dragging;
	private final Map<String, Float> anim = new HashMap<>();
	private String hovered;
	/** Where the canvas sits on screen this frame (mouse input is mapped back through it). */
	private float canvasX;
	private float canvasY;
	private float canvasScale = 1f;

	private record Hit(String id, int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private record Slider(String id, int x, int y, int w, double min, double max, DoubleConsumer set) {
	}

	public GuideScreen(Screen parent) {
		super(Text.literal("Welcome to Velo Client"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (openedAt == 0) {
			openedAt = System.nanoTime();
			stepChangedAt = openedAt;
			VeloUiSounds.welcome();
		}
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	// ---------------------------------------------------------------- navigation

	private void go(int next) {
		if (next < 0 || next >= STEP_NAMES.length || next == step) {
			return;
		}
		direction = next > step ? 1 : -1;
		prevStep = step;
		step = next;
		stepChangedAt = System.nanoTime();
		dragging = null;
		if (direction > 0) {
			VeloUiSounds.guideNext();
		} else {
			VeloUiSounds.guideBack();
		}
		if (step == STEP_NAMES.length - 1) {
			VeloUiSounds.guideDone();
		}
	}

	/** Dev-only (screenshot tour): jump to a page / pick a HUD part. */
	public void goForTour(int page, String hud) {
		if (hud != null) {
			hudPick = hud;
		}
		go(page);
	}

	private void finish() {
		if (closing) {
			return;
		}
		closing = true;
		closingAt = System.nanoTime();
		GuideSettings.saveIfChanged();
		if (fovChanged) {
			//? if <26.1 {
			MinecraftClient.getInstance().options.write();
			//?} else {
			/*MinecraftClient.getInstance().options.save();
			*///?}
		}
		GuideTrigger.finished();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		VeloUiSounds.wantMusic(1500);
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		lastNanos = now;
		float open = ease(Math.min(1f, (now - openedAt) / 450_000_000f));
		if (closing) {
			float t = Math.min(1f, (now - closingAt) / 320_000_000f);
			open = 1f - ease(t);
			if (t >= 1f) {
				MinecraftClient.getInstance().setScreen(parent);
				return;
			}
		}
		hits.clear();
		sliders.clear();

		// dim the title screen, a warm accent glow behind the card
		context.fill(0, 0, this.width, this.height, VeloUi.withAlpha(0xFF07070A, Math.round(0xC8 * open)));
		int glowR = Math.max(this.width, this.height);
		for (int i = 0; i < 6; i++) {
			float f = i / 6f;
			VeloDraw.fillCircle(context, this.width / 2f, this.height * 0.42f, glowR * (0.55f - f * 0.08f),
					VeloUi.withAlpha(MenuKit.accent(), Math.round(0x05 * open)));
		}

		// everything below is drawn on the DW x DH canvas, scaled to fit
		canvasScale = Math.min(1.2f, Math.min((this.width - 16f) / DW, (this.height - 16f) / DH));
		// snap to whole screen pixels per canvas unit, so pixel art (crosshair, items, sprites) stays sharp and centred
		int gui = net.veloclient.velo.client.util.ClientCompat.guiScale();
		float physical = canvasScale * gui;
		if (physical >= 1f) {
			canvasScale = (float) Math.floor(physical) / gui;
		}
		canvasX = (this.width - DW * canvasScale) / 2f;
		canvasY = (this.height - DH * canvasScale) / 2f;
		mouseX = Math.round((mouseX - canvasX) / canvasScale);
		mouseY = Math.round((mouseY - canvasY) / canvasScale);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(canvasX, canvasY);
		context.getMatrices().scale(canvasScale, canvasScale);

		int cw = DW;
		int ch = DH;
		int cx = 0;
		int cy = Math.round((1f - open) * 18f);
		VeloDraw.shadow(context, cx, cy, cw, ch, 14, 24, 8, VeloUi.withAlpha(0xFF000000, Math.round(0xA0 * open)));
		VeloDraw.fillRounded(context, cx, cy, cw, ch, 14, VeloUi.withAlpha(0xFF101015, Math.round(0xF4 * open)));
		VeloDraw.strokeRounded(context, cx, cy, cw, ch, 14, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x1C * open)));

		// header: progress + Skip
		int a = Math.round(255 * open);
		if (step > 0 && step < STEP_NAMES.length - 1) {
			Text kicker = MenuKit.small(("Step " + step + " of " + (STEP_NAMES.length - 2) + " · " + STEP_NAMES[step]).toUpperCase());
			MenuKit.text(context, this.textRenderer, kicker, cx + 22, cy + 18, VeloUi.withAlpha(MenuKit.accentText(), a));
		}
		if (step < STEP_NAMES.length - 1) {
			Text skip = MenuKit.uiBold("Skip setup");
			int sw = this.textRenderer.getWidth(skip) + 22;
			int sx = cx + cw - sw - 14;
			int sy = cy + 12;
			float h = hover("skip", mouseX, mouseY, sx, sy, sw, 20, dt);
			VeloDraw.fillRounded(context, sx, sy, sw, 20, 7, VeloUi.withAlpha(0xFFFFFFFF, Math.round((0x0C + 0x14 * h) * open)));
			VeloDraw.strokeRounded(context, sx, sy, sw, 20, 7, VeloUi.withAlpha(0xFFFFFFFF, Math.round((0x22 + 0x30 * h) * open)));
			MenuKit.text(context, this.textRenderer, skip, sx + 11, sy + 6, VeloUi.withAlpha(VeloAnim.lerpArgb(MenuKit.TEXT_2, 0xFFFFFFFF, h), a));
			hits.add(new Hit("skip", sx, sy, sw, 20, this::finish));
		}

		// the page, sliding in from the side you moved to (the old one slides out)
		float t = Math.min(1f, (now - stepChangedAt) / 380_000_000f);
		float e = ease(t);
		int px = cx + 22;
		int py = cy + 40;
		int pw = cw - 44;
		int ph = ch - 40 - 54;
		context.enableScissor(cx + 1, cy + 34, cx + cw - 1, cy + ch - 50);
		if (prevStep >= 0 && t < 1f) {
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(-direction * 60f * e, 0f);
			drawPage(context, prevStep, px, py, pw, ph, -1, -1, (1f - e) * (1f - e) * open, dt, false);
			context.getMatrices().popMatrix();
		}
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(direction * 60f * (1f - e), 0f);
		drawPage(context, step, px, py, pw, ph, mouseX, mouseY, (prevStep < 0 ? 1f : e) * open, dt, true);
		context.getMatrices().popMatrix();
		context.disableScissor();

		// footer: dots, Back / Next
		int fy = cy + ch - 40;
		int dots = STEP_NAMES.length;
		int dx = cx + 22;
		for (int i = 0; i < dots; i++) {
			boolean on = i == step;
			float w = anim.merge("dot" + i, 0f, (o, n) -> VeloAnim.step(o, on ? 1f : 0f, dt * 6f));
			int dw = 6 + Math.round(14 * w);
			int col = i <= step ? MenuKit.accent() : 0xFF3A3A44;
			VeloDraw.fillRounded(context, dx, fy + 10, dw, 6, 3, VeloUi.withAlpha(col, a));
			dx += dw + 5;
		}
		if (step > 0 && step < STEP_NAMES.length - 1) {
			button(context, "back", "Back", cx + cw - 22 - 104 - 8 - 80, fy, 80, 26, false, mouseX, mouseY, dt, open, () -> go(step - 1));
		}
		String nextLabel = step == 0 ? "Set me up" : step == STEP_NAMES.length - 1 ? "Let's play" : step == STEP_NAMES.length - 2 ? "Finish" : "Next";
		button(context, "next", nextLabel, cx + cw - 22 - 104, fy, 104, 26, true, mouseX, mouseY, dt, open,
				() -> {
					if (step == STEP_NAMES.length - 1) {
						finish();
					} else {
						go(step + 1);
					}
				});
		context.getMatrices().popMatrix();
	}

	private void drawPage(DrawContext context, int page, int x, int y, int w, int h, int mouseX, int mouseY, float alpha, float dt, boolean live) {
		if (alpha <= 0.01f) {
			return;
		}
		Page p = new Page(context, x, y, w, h, live ? mouseX : -9999, live ? mouseY : -9999, alpha, dt, live);
		switch (page) {
			case 0 -> welcome(p);
			case 1 -> look(p);
			case 2 -> view(p);
			case 3 -> style(p);
			case 4 -> hud(p);
			case 5 -> overlays(p);
			case 6 -> combatHud(p);
			case 7 -> clips(p);
			case 8 -> pvp(p);
			default -> done(p);
		}
	}

	/** Everything a page draws with. */
	private final class Page {
		final DrawContext c;
		final int x;
		final int y;
		final int w;
		final int h;
		final int mx;
		final int my;
		final float alpha;
		final float dt;
		final boolean live;

		Page(DrawContext c, int x, int y, int w, int h, int mx, int my, float alpha, float dt, boolean live) {
			this.c = c;
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.mx = mx;
			this.my = my;
			this.alpha = alpha;
			this.dt = dt;
			this.live = live;
		}

		int a(int argb) {
			return VeloUi.withAlpha(argb, Math.round(((argb >>> 24) & 0xFF) * alpha));
		}

		void title(String title, String sub) {
			MenuKit.text(c, GuideScreen.this.textRenderer, MenuKit.displayLg(title), x, y, a(MenuKit.TEXT));
			MenuKit.text(c, GuideScreen.this.textRenderer, MenuKit.fit(GuideScreen.this.textRenderer, MenuKit.ui(sub), w), x, y + 20, a(MenuKit.TEXT_2));
		}
	}

	// ---------------------------------------------------------------- pages

	private void welcome(Page p) {
		long now = System.nanoTime();
		float t = (now - openedAt) / 1_000_000_000f;
		int logo = 46;
		float pop = ease(Math.min(1f, t / 0.8f));
		int lx = p.x + p.w / 2 - logo / 2;
		int ly = p.y + 30 + Math.round((1f - pop) * 14f);
		for (int i = 0; i < 4; i++) {
			VeloDraw.fillCircle(p.c, lx + logo / 2f, ly + logo / 2f, logo * (0.9f + i * 0.35f) + (float) Math.sin(t * 1.6f) * 2f,
					VeloUi.withAlpha(MenuKit.accent(), Math.round(0x0E * p.alpha * pop)));
		}
		MenuKit.logo(p.c, lx, ly, logo);
		Text title = MenuKit.hero("Welcome to Velo");
		int tw = GuideScreen.this.textRenderer.getWidth(title);
		float tp = ease(Math.min(1f, Math.max(0f, (t - 0.25f) / 0.6f)));
		MenuKit.text(p.c, GuideScreen.this.textRenderer, title, p.x + (p.w - tw) / 2, ly + logo + 22 + Math.round((1f - tp) * 10f),
				VeloUi.withAlpha(MenuKit.TEXT, Math.round(255 * p.alpha * tp)));
		String[] lines = {"Let's set up the few things people change first -", "every setting shows you a live preview. Takes about a minute."};
		for (int i = 0; i < lines.length; i++) {
			Text line = MenuKit.ui(lines[i]);
			float lp = ease(Math.min(1f, Math.max(0f, (t - 0.45f - i * 0.12f) / 0.6f)));
			MenuKit.text(p.c, GuideScreen.this.textRenderer, line, p.x + (p.w - GuideScreen.this.textRenderer.getWidth(line)) / 2, ly + logo + 56 + i * 13,
					VeloUi.withAlpha(MenuKit.TEXT_2, Math.round(255 * p.alpha * lp)));
		}
		Text hint = MenuKit.small("CHANGE ANY OF IT LATER WITH RIGHT SHIFT");
		MenuKit.text(p.c, GuideScreen.this.textRenderer, hint, p.x + (p.w - GuideScreen.this.textRenderer.getWidth(hint)) / 2, p.y + p.h - 14, p.a(MenuKit.TEXT_3));
	}

	private void look(Page p) {
		p.title("How things look", "Less in your way while you fight, build or ride.");
		int gap = 10;
		int cw = (p.w - gap * 2) / 3;
		int top = p.y + 44;
		int chh = p.h - 44;

		// Low Fire
		int x0 = p.x;
		card(p, x0, top, cw, chh, "Low Fire", null);
		boolean fire = GuideSettings.enabled("low-fire");
		int pv = previewBox(p, x0 + 8, top + 26, cw - 16, 84);
		double lower = net.veloclient.velo.client.modules.pvp.LowFireModule.lowerSetting();
		double opacity = net.veloclient.velo.client.modules.pvp.LowFireModule.opacitySetting();
		drawFirePreview(p, x0 + 8, top + 26, cw - 16, 84, fire ? lower : 0, fire ? opacity : 1);
		int ry = top + 26 + 84 + 10;
		toggleRow(p, "fire_on", x0 + 10, ry, cw - 20, "Lower the fire", fire, v -> GuideSettings.setEnabled("low-fire", v));
		slider(p, "fire_lower", x0 + 10, ry + 26, cw - 20, "Height", lower, 0, 0.6, v -> Math.round(v / 0.6 * 100) + "%", fire,
				v -> {
					net.veloclient.velo.client.modules.pvp.LowFireModule.setLowerSetting(v);
					GuideSettings.markDirty();
				});
		slider(p, "fire_alpha", x0 + 10, ry + 58, cw - 20, "Opacity", opacity, 0.1, 1.0, v -> Math.round(v * 100) + "%", fire,
				v -> {
					net.veloclient.velo.client.modules.pvp.LowFireModule.setOpacitySetting(v);
					GuideSettings.markDirty();
				});

		// Shield (Item Position's "Lower Shield")
		int x1 = p.x + cw + gap;
		card(p, x1, top, cw, chh, "Shield", null);
		boolean items = GuideSettings.enabled("item-position");
		double shield = GuideSettings.slider("item-position", "Lower Shield", 0);
		previewBox(p, x1 + 8, top + 26, cw - 16, 84);
		drawShieldPreview(p, x1 + 8, top + 26, cw - 16, 84, items ? shield : 0);
		toggleRow(p, "shield_on", x1 + 10, ry, cw - 20, "Lower the shield", items, v -> GuideSettings.setEnabled("item-position", v));
		slider(p, "shield_lower", x1 + 10, ry + 26, cw - 20, "Lower by", shield, 0, 0.6, v -> Math.round(v / 0.6 * 100) + "%", items,
				v -> GuideSettings.setSlider("item-position", "Lower Shield", Math.round(v * 100) / 100.0));

		// Rideable Opacity
		int x2 = p.x + (cw + gap) * 2;
		card(p, x2, top, cw, chh, "Mounts", null);
		boolean ride = GuideSettings.enabled("rideable-opacity");
		double rideAlpha = net.veloclient.velo.preview.tweaks.RideableOpacityModule.previewOpacity();
		previewBox(p, x2 + 8, top + 26, cw - 16, 84);
		drawMountPreview(p, x2 + 8, top + 26, cw - 16, 84, ride ? rideAlpha : 1);
		toggleRow(p, "ride_on", x2 + 10, ry, cw - 20, "See-through mount", ride, v -> GuideSettings.setEnabled("rideable-opacity", v));
		slider(p, "ride_alpha", x2 + 10, ry + 26, cw - 20, "Opacity", rideAlpha, 0.05, 1.0, v -> Math.round(v * 100) + "%", ride,
				v -> {
					net.veloclient.velo.preview.tweaks.RideableOpacityModule.setPreviewOpacity(v);
					GuideSettings.markDirty();
				});
	}

	private void view(Page p) {
		p.title("What you see", "Field of view, light and a map in the corner.");
		int gap = 10;
		int cw = (p.w - gap * 2) / 3;
		int top = p.y + 44;
		int chh = p.h - 44;
		int pvh = 92;
		int ry = top + 26 + pvh + 10;

		// FOV (Minecraft's own option)
		int x0 = p.x;
		card(p, x0, top, cw, chh, "Field of view", null);
		previewBox(p, x0 + 8, top + 26, cw - 16, pvh);
		int fov = fov();
		drawFovPreview(p, x0 + 8, top + 26, cw - 16, pvh, fov);
		slider(p, "fov", x0 + 10, ry, cw - 20, "FOV", fov, 30, 110, v -> v >= 109.5 ? "Quake Pro" : Math.round(v) + (Math.round(v) == 70 ? " (normal)" : ""), true,
				v -> setFov((int) Math.round(v)));
		List<String> presets = List.of("70", "90", "100", "110");
		chips(p, "fovp", x0 + 10, ry + 30, cw - 20, presets, String.valueOf(fov), v -> setFov(Integer.parseInt(v)));
		note(p, x0 + 10, ry + 56, cw - 20, "Wider shows more around you. Most PvP players use 90-100.");

		// Fullbright
		int x1 = p.x + cw + gap;
		card(p, x1, top, cw, chh, "Fullbright", null);
		boolean bright = GuideSettings.enabled("full-bright");
		previewBox(p, x1 + 8, top + 26, cw - 16, pvh);
		drawCavePreview(p, x1 + 8, top + 26, cw - 16, pvh, bright);
		toggleRow(p, "bright_on", x1 + 10, ry, cw - 20, "Always see clearly", bright, v -> GuideSettings.setEnabled("full-bright", v));
		note(p, x1 + 10, ry + 28, cw - 20, "Caves and nights look like daylight. No FPS cost.");

		// Minimap
		int x2 = p.x + (cw + gap) * 2;
		card(p, x2, top, cw, chh, "Minimap", null);
		boolean map = GuideSettings.enabled("minimap");
		String shape = GuideSettings.choice("minimap", "Shape", "Square");
		previewBox(p, x2 + 8, top + 26, cw - 16, pvh);
		drawMinimapPreview(p, x2 + 8, top + 26, cw - 16, pvh, map, shape);
		toggleRow(p, "map_on", x2 + 10, ry, cw - 20, "Show a minimap", map, v -> GuideSettings.setEnabled("minimap", v));
		chips(p, "mapshape", x2 + 10, ry + 28, cw - 20, List.of("Square", "Round"), shape, v -> {
			GuideSettings.setEnabled("minimap", true);
			GuideSettings.setChoice("minimap", "Shape", v);
		});
		note(p, x2 + 10, ry + 54, cw - 20, "The big world map is on M.");
	}

	private void style(Page p) {
		p.title("Your style", "One font for your whole HUD - and how cloaks move.");
		int gap = 12;
		int cw = (p.w - gap) / 2;
		int top = p.y + 44;
		int chh = p.h - 44;

		int x0 = p.x;
		card(p, x0, top, cw, chh, "HUD font", null);
		boolean on = GuideSettings.enabled("hud-style");
		String font = on ? GuideSettings.choice("hud-style", "Font", "Inter") : "Minecraft";
		String bg = on ? GuideSettings.choice("hud-style", "Background", "Glass") : "None";
		previewBox(p, x0 + 8, top + 26, cw - 16, 74);
		drawFontPreview(p, x0 + 8, top + 26, cw - 16, 74, font, bg);
		int cy = top + 26 + 74 + 8;
		label(p, x0 + 10, cy, "Font");
		chips(p, "font", x0 + 10, cy + 11, cw - 20, net.veloclient.velo.client.modules.hud.HudStyleModule.FONTS, font, v -> {
			GuideSettings.setEnabled("hud-style", true);
			GuideSettings.setChoice("hud-style", "Font", v);
		});
		label(p, x0 + 10, cy + 81, "Background");
		chips(p, "bg", x0 + 10, cy + 92, cw - 20, net.veloclient.velo.client.modules.hud.HudStyleModule.BACKGROUNDS, bg, v -> {
			GuideSettings.setEnabled("hud-style", true);
			GuideSettings.setChoice("hud-style", "Background", v);
		});

		int x1 = p.x + cw + gap;
		card(p, x1, top, cw, chh, "Cloak animation", null);
		String cape = net.veloclient.velo.client.modules.cosmetics.CapeCosmeticsModule.styleName();
		previewBox(p, x1 + 8, top + 26, cw - 16, 116);
		drawCapePreview(p, x1 + 8, top + 26, cw - 16, 116, cape);
		List<String> options = new ArrayList<>(List.of("Cloth physics", "Classic"));
		if (net.veloclient.velo.client.cosmetics.WaveyCapesCompat.isLoaded()) {
			options.add("Wavey Capes");
		}
		chips(p, "cape", x1 + 10, top + 26 + 116 + 12, cw - 20, options, cape, v -> {
			net.veloclient.velo.client.modules.cosmetics.CapeCosmeticsModule.setStyleName(v);
			GuideSettings.markDirty();
		});
		note(p, x1 + 10, top + 26 + 116 + 40, cw - 20, switch (cape) {
			case "Classic" -> "Minecraft's own stiff cape and swing.";
			case "Wavey Capes" -> "Your Wavey Capes mod animates every cloak.";
			default -> "Real cloth that flows as you move. Tiny FPS cost.";
		});
		Text ex = MenuKit.small("EXAMPLE: VELO NEON - FREE WHEN YOU LINK DISCORD");
		MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.fit(GuideScreen.this.textRenderer, ex, cw - 20), x1 + 10, top + chh - 14, p.a(MenuKit.TEXT_3));
	}

	private void overlays(Page p) {
		p.title("On-screen info", "Turn on what you want to see while you play - in your HUD font.");
		String[][] tiles = {{"keystrokes", "Keystrokes"}, {"mouse-buttons", "Mouse buttons & CPS"}, {"fps-counter", "FPS counter"},
				{"coordinates", "Coordinates"}, {"clock", "Clock"}, {"potion-timers", "Potion timers"}};
		int gap = 8;
		int cols = 3;
		int tw = (p.w - gap * (cols - 1)) / cols;
		int top = p.y + 42;
		int th = (p.h - 42 - gap) / 2;
		for (int i = 0; i < tiles.length; i++) {
			String id = tiles[i][0];
			int tx = p.x + (i % cols) * (tw + gap);
			int ty = top + (i / cols) * (th + gap);
			boolean on = GuideSettings.enabled(id);
			card(p, tx, ty, tw, th, null, null);
			int pvh = th - 34;
			previewBox(p, tx + 6, ty + 6, tw - 12, pvh);
			drawOverlayPreview(p, id, tx + 6, ty + 6, tw - 12, pvh, on);
			toggleRow(p, "ov_" + id, tx + 9, ty + th - 25, tw - 18, tiles[i][1], on, v -> GuideSettings.setEnabled(id, v));
		}
	}

	private void hud(Page p) {
		p.title("Your HUD", "Pick a part, then a style - the preview is the real look.");
		String[][] parts = {{"hearts", "Hearts"}, {"hunger", "Hunger"}, {"armor-bar", "Armor"}, {"hotbar", "Hotbar"}, {"glint-colorizer", "Glint"},
				{"xp-bar", "XP bar"}};
		int top = p.y + 44;
		int bw = (p.w - 5 * 6) / 6;
		for (int i = 0; i < parts.length; i++) {
			String id = parts[i][0];
			int bx = p.x + i * (bw + 6);
			boolean on = id.equals(hudPick);
			float h = p.live ? hover("hud_" + id, p.mx, p.my, bx, top, bw, 46, p.dt) : 0f;
			float sel = anim.merge("hudsel_" + id, 0f, (o, n) -> VeloAnim.step(o, on ? 1f : 0f, p.dt * 7f));
			int bg = VeloAnim.lerpArgb(0x0CFFFFFF, VeloUi.withAlpha(MenuKit.accent(), 0x2A), sel);
			VeloDraw.fillRounded(p.c, bx, top, bw, 46, 9, p.a(VeloAnim.lerpArgb(bg, 0x1AFFFFFF, h * (1f - sel))));
			VeloDraw.strokeRounded(p.c, bx, top, bw, 46, 9, p.a(VeloAnim.lerpArgb(0x18FFFFFF, VeloUi.withAlpha(MenuKit.accent(), 0xA0), sel)));
			drawHudIcon(p, id, bx + bw / 2 - 9, top + 7);
			Text label = MenuKit.uiBold(parts[i][1]);
			MenuKit.text(p.c, GuideScreen.this.textRenderer, label, bx + (bw - GuideScreen.this.textRenderer.getWidth(label)) / 2, top + 31, p.a(on ? 0xFFFFFFFF : MenuKit.TEXT_2));
			boolean active = GuideSettings.enabled(id);
			if (active) {
				VeloDraw.fillCircle(p.c, bx + bw - 7f, top + 7f, 2.5f, p.a(0xFF39D98A));
			}
			if (p.live) {
				hits.add(new Hit("hud_" + id, bx, top, bw, 46, () -> {
					if (!id.equals(hudPick)) {
						VeloUiSounds.tab();
					}
					hudPick = id;
				}));
			}
		}
		int dy = top + 56;
		int dh = p.h - 56 - 44;
		card(p, p.x, dy, p.w, dh, null, null);
		int pvw = Math.min(300, p.w / 2);
		previewBox(p, p.x + 8, dy + 8, pvw, dh - 16);
		drawHudPreview(p, hudPick, p.x + 8, dy + 8, pvw, dh - 16);
		int cx = p.x + pvw + 22;
		int cw = p.w - pvw - 32;
		hudControls(p, hudPick, cx, dy + 12, cw);
	}

	private void hudControls(Page p, String id, int x, int y, int w) {
		boolean on = GuideSettings.enabled(id);
		String name = switch (id) {
			case "hearts" -> "Restyle my hearts";
			case "hunger" -> "Restyle my hunger bar";
			case "armor-bar" -> "Restyle my armor bar";
			case "hotbar" -> "Color my hotbar";
			case "glint-colorizer" -> "Color the enchant glint";
			default -> "Color my XP bar";
		};
		toggleRow(p, "hudon_" + id, x, y, w, name, on, v -> GuideSettings.setEnabled(id, v));
		String styleLabel = switch (id) {
			case "hearts", "armor-bar" -> "Style";
			case "hunger" -> "Food Icon Style";
			case "glint-colorizer" -> "Mode";
			default -> null;
		};
		String colorLabel = switch (id) {
			case "hearts" -> "Heart Color";
			case "hunger" -> "Food Color (Recolored)";
			case "armor-bar" -> "Armor Color";
			case "hotbar" -> "Slot Color";
			case "glint-colorizer" -> "Glint Color";
			default -> "XP Bar Fill";
		};
		int cy = y + 30;
		if (styleLabel != null) {
			List<String> styles = GuideSettings.choices(id, styleLabel).stream().filter(s -> !s.startsWith("Custom")).toList();
			String cur = GuideSettings.choice(id, styleLabel, styles.isEmpty() ? "" : styles.get(0));
			label(p, x, cy, "Style");
			chips(p, "style_" + id, x, cy + 12, w, styles, cur, v -> {
				GuideSettings.setEnabled(id, true);
				GuideSettings.setChoice(id, styleLabel, v);
			});
			cy += 42;
		}
		if (id.equals("hotbar") && !GuideSettings.toggle("hotbar", "Color The Slots") && on) {
			GuideSettings.setToggle("hotbar", "Color The Slots", true);
		}
		label(p, x, cy, "Color");
		int cur = GuideSettings.color(id, colorLabel, 0xFFE3262B);
		int sw = 18;
		for (int i = 0; i < SWATCHES.length; i++) {
			int sx = x + i * (sw + 6);
			int col = SWATCHES[i];
			boolean sel = (cur & 0xFFFFFF) == (col & 0xFFFFFF);
			float h = p.live ? hover("sw_" + id + i, p.mx, p.my, sx, cy + 12, sw, sw, p.dt) : 0f;
			if (sel) {
				VeloDraw.strokeRounded(p.c, sx - 2, cy + 10, sw + 4, sw + 4, 7, p.a(0xFFFFFFFF));
			}
			VeloDraw.fillRounded(p.c, sx, cy + 12 - Math.round(h * 2), sw, sw, 6, p.a(col));
			if (p.live) {
				int keep = cur;
				hits.add(new Hit("sw_" + id + i, sx, cy + 12, sw, sw, () -> {
					GuideSettings.setEnabled(id, true);
					int alpha = id.equals("hotbar") ? (keep & 0xFF000000) == 0 ? 0x90000000 : keep & 0xFF000000 : 0xFF000000;
					GuideSettings.setColor(id, colorLabel, (col & 0x00FFFFFF) | alpha);
					if (id.equals("hotbar")) {
						GuideSettings.setToggle("hotbar", "Color The Slots", true);
					}
					VeloUiSounds.click(1.15f);
				}));
			}
		}
		note(p, x, cy + 40, w, "More colors and your own drawings: Right Shift > " + switch (id) {
			case "hearts" -> "Hearts";
			case "hunger" -> "Hunger";
			case "armor-bar" -> "Armor & Durability";
			case "hotbar" -> "Hotbar Colors";
			case "glint-colorizer" -> "Glint Colorizer";
			default -> "XP & Locator Bar";
		} + ".");
	}

	private void combatHud(Page p) {
		p.title("Combat HUD", "The counters PvP players check all the time - exactly as they show in game.");
		String[][] tiles = {{"totem-counter", "Totem counter"}, {"totem-pops", "Totem pops"}, {"arrow-counter", "Arrow counter"},
				{"pot-counter", "Potion counter"}, {"cooldowns", "Cooldowns"}, {"armor-durability", "Armor durability"}};
		int gap = 8;
		int cols = 3;
		int tw = (p.w - gap * (cols - 1)) / cols;
		int top = p.y + 42;
		int th = (p.h - 42 - gap) / 2;
		for (int i = 0; i < tiles.length; i++) {
			String id = tiles[i][0];
			int tx = p.x + (i % cols) * (tw + gap);
			int ty = top + (i / cols) * (th + gap);
			boolean on = GuideSettings.enabled(id);
			card(p, tx, ty, tw, th, null, null);
			int pvh = th - 34;
			previewBox(p, tx + 6, ty + 6, tw - 12, pvh);
			p.c.enableScissor(tx + 7, ty + 7, tx + tw - 7, ty + 6 + pvh - 1);
			sky(p, tx + 6, ty + 6, tw - 12, pvh);
			// the module's own layout, drawn at 2x around the middle of the box
			p.c.getMatrices().pushMatrix();
			p.c.getMatrices().translate(tx + 6 + (tw - 12) / 2, ty + 6 + pvh / 2);
			p.c.getMatrices().scale(2f, 2f);
			drawCombatWidget(p, id, on);
			p.c.getMatrices().popMatrix();
			if (!on) {
				MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.small("OFF"), tx + 12, ty + 11, p.a(MenuKit.TEXT_3));
			}
			p.c.disableScissor();
			toggleRow(p, "cb_" + id, tx + 9, ty + th - 25, tw - 18, tiles[i][1], on, v -> GuideSettings.setEnabled(id, v));
		}
	}

	/** One combat counter at the origin, laid out like its module draws it (22 px tall pill, icon at +4/+3, count centred). */
	private void drawCombatWidget(Page p, String id, boolean on) {
		float t = time();
		int a = on ? 255 : 120;
		float am = a / 255f * p.alpha;
		switch (id) {
			case "totem-counter", "arrow-counter", "pot-counter" -> {
				int count = switch (id) {
					case "totem-counter" -> 3 - (int) (t / 1.6f) % 3;
					case "arrow-counter" -> 64 - (int) (t * 3) % 40;
					default -> 12 - (int) (t / 1.2f) % 12;
				};
				String label = String.valueOf(count);
				int w = 44;
				VeloDraw.fillRounded(p.c, -w / 2, -11, w, 22, 6, VeloUi.withAlpha(0xFF101014, Math.round(0x90 * am)));
				int col = VeloUi.withAlpha(0xFFFFFFFF, Math.round(255 * am));
				if (id.equals("pot-counter")) {
					tex(p.c, "item/splash_potion", -w / 2 + 4, -8, 16, col);
					tex(p.c, "item/potion_overlay", -w / 2 + 4, -8, 16, VeloUi.withAlpha(0xFFF82423, Math.round(255 * am)));
				} else {
					tex(p.c, id.equals("totem-counter") ? "item/totem_of_undying" : "item/arrow", -w / 2 + 4, -8, 16, col);
				}
				int color = id.equals("totem-counter") ? (count == 0 ? 0xFFFF5555 : count == 1 ? 0xFFFFC53D : 0xFF6FE39A) : 0xFFFFFFFF;
				int tw = net.veloclient.velo.client.hud.HudLook.measure(label);
				net.veloclient.velo.client.hud.HudLook.draw(p.c, label, -w / 2 + 24 + (w - 28 - tw) / 2, -4, VeloUi.withAlpha(color, Math.round(255 * am)));
			}
			case "totem-pops" -> {
				int pops = 1 + (int) (t / 1.3f) % 4;
				String name = "Steve";
				String n = "\u00d7" + pops;
				int w = 24 + net.veloclient.velo.client.hud.HudLook.measure(name) + 10 + net.veloclient.velo.client.hud.HudLook.measure(n) + 8;
				VeloDraw.fillRounded(p.c, -w / 2, -11, w, 22, 6, VeloUi.withAlpha(0xFF101014, Math.round(0x90 * am)));
				tex(p.c, "item/totem_of_undying", -w / 2 + 4, -8, 16, VeloUi.withAlpha(0xFFFFFFFF, Math.round(255 * am)));
				net.veloclient.velo.client.hud.HudLook.draw(p.c, name, -w / 2 + 24, -4, VeloUi.withAlpha(0xFFFFFFFF, Math.round(255 * am)));
				net.veloclient.velo.client.hud.HudLook.draw(p.c, n, w / 2 - 8 - net.veloclient.velo.client.hud.HudLook.measure(n), -4,
						VeloUi.withAlpha(0xFFFFC53D, Math.round(255 * am)));
			}
			case "cooldowns" -> {
				int cell = 22;
				float progress = 1f - (t % 3f) / 3f;
				float ccx = 0f;
				float ccy = -4f;
				VeloDraw.fillCircle(p.c, ccx, ccy, cell / 2f, VeloUi.withAlpha(0xFF101014, Math.round(0x99 * am)));
				tex(p.c, "item/ender_pearl", -8, Math.round(ccy) - 8, 16, VeloUi.withAlpha(0xFFFFFFFF, Math.round(255 * am)));
				int r = (int) (0x6F + (0xFF - 0x6F) * progress);
				int g = (int) (0xE3 + (0x55 - 0xE3) * progress);
				int accent = 0xFF000000 | r << 16 | g << 8 | 0x55;
				VeloDraw.strokeArc(p.c, ccx, ccy, cell / 2f - 1.5f, 2f, 0, 360, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x40 * am)));
				VeloDraw.strokeArc(p.c, ccx, ccy, cell / 2f - 1.5f, 2f, 360f * (1f - progress), 360f * progress, VeloUi.withAlpha(accent, Math.round(255 * am)));
				String label = String.format(java.util.Locale.ROOT, "%.1fs", progress * 3f);
				net.veloclient.velo.client.hud.HudLook.draw(p.c, label, -net.veloclient.velo.client.hud.HudLook.measure(label) / 2, Math.round(ccy) + cell / 2 + 1,
						VeloUi.withAlpha(0xFFFFFFFF, Math.round(255 * am)));
			}
			default -> {
				String[] armor = {"item/diamond_helmet", "item/diamond_chestplate", "item/diamond_leggings", "item/diamond_boots"};
				int[] dur = {363, 528 - (int) (t * 9) % 120, 495, 429};
				for (int i = 0; i < 4; i++) {
					int ry = -17 + i * 9;
					p.c.getMatrices().pushMatrix();
					p.c.getMatrices().translate(-16f, ry);
					p.c.getMatrices().scale(0.5f, 0.5f);
					tex(p.c, armor[i], 0, 0, 16, VeloUi.withAlpha(0xFFFFFFFF, Math.round(255 * am)));
					p.c.getMatrices().popMatrix();
					float frac = dur[i] / 528f;
					int col = frac > 0.6f ? 0xFF6FE39A : frac > 0.3f ? 0xFFFFC53D : 0xFFFF5555;
					p.c.getMatrices().pushMatrix();
					p.c.getMatrices().translate(-6f, ry + 1);
					p.c.getMatrices().scale(0.6f, 0.6f);
					net.veloclient.velo.client.hud.HudLook.draw(p.c, String.valueOf(dur[i]), 0, 0, VeloUi.withAlpha(col, Math.round(255 * am)));
					p.c.getMatrices().popMatrix();
				}
			}
		}
	}

	private void clips(Page p) {
		p.title("Clip your best moments", "Like Medal, built in and free: press a key and the last seconds are saved.");
		int top = p.y + 44;
		int chh = p.h - 44;
		int pvw = Math.min(330, p.w * 3 / 5);
		card(p, p.x, top, p.w, chh, null, null);
		previewBox(p, p.x + 8, top + 8, pvw, chh - 16);
		drawClipPreview(p, p.x + 8, top + 8, pvw, chh - 16);
		int x = p.x + pvw + 22;
		int w = p.w - pvw - 32;
		boolean on = GuideSettings.enabled("clips");
		toggleRow(p, "clips_on", x, top + 14, w, "Record clips", on, v -> GuideSettings.setEnabled("clips", v));
		perfBadge(p, x, top + 44, "Small FPS cost while it records");
		note(p, x, top + 66, w, "Velo keeps the last moments in memory and saves them when you press F8 - no recording running all the time on disk.");
		note(p, x, top + 104, w, "Watch, trim and share them in Right Shift > Clips & Screenshots.");
		keycap(p, x, top + chh - 40, "F8", on);
		MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.ui("Save a clip"), x + 34, top + chh - 32, p.a(MenuKit.TEXT_2));
	}

	private void pvp(Page p) {
		p.title("PvP", "The basics most players set up first.");
		int gap = 10;
		int cw = (p.w - gap * 2) / 3;
		int top = p.y + 44;
		int chh = p.h - 44;

		// Crosshair
		int x0 = p.x;
		card(p, x0, top, cw, chh, "Crosshair", null);
		previewBox(p, x0 + 8, top + 26, cw - 16, 84);
		drawCrosshairPreview(p, x0 + 8, top + 26, cw - 16, 84);
		var library = new ArrayList<>(net.veloclient.velo.client.crosshair.CrosshairManager.library().keySet());
		String equipped = net.veloclient.velo.client.crosshair.CrosshairManager.equipped().map(d -> d.id()).orElse("");
		boolean custom = GuideSettings.enabled("custom-crosshair");
		int ry = top + 26 + 84 + 10;
		if (library.isEmpty()) {
			toggleRow(p, "xh_on", x0 + 10, ry, cw - 20, "Custom crosshair", custom, v -> GuideSettings.setEnabled("custom-crosshair", v));
			button(p.c, "xh_edit", "Draw one", x0 + 10, ry + 28, cw - 20, 22, false, p.mx, p.my, p.dt, p.alpha,
					() -> MinecraftClient.getInstance().setScreen(new net.veloclient.velo.client.gui.CrosshairSelectScreen(this)));
		} else {
			List<String> names = new ArrayList<>(List.of("Vanilla"));
			for (String id : library.subList(0, Math.min(4, library.size()))) {
				names.add(net.veloclient.velo.client.crosshair.CrosshairManager.library().get(id).name());
			}
			String cur = custom && !equipped.isEmpty() ? net.veloclient.velo.client.crosshair.CrosshairManager.library().getOrDefault(equipped,
					null) == null ? "Vanilla" : net.veloclient.velo.client.crosshair.CrosshairManager.library().get(equipped).name() : "Vanilla";
			chips(p, "xh", x0 + 10, ry, cw - 20, names, cur, v -> {
				if (v.equals("Vanilla")) {
					GuideSettings.setEnabled("custom-crosshair", false);
					return;
				}
				for (var e : net.veloclient.velo.client.crosshair.CrosshairManager.library().entrySet()) {
					if (e.getValue().name().equals(v)) {
						net.veloclient.velo.client.crosshair.CrosshairManager.equip(e.getKey());
						GuideSettings.setEnabled("custom-crosshair", true);
						return;
					}
				}
			});
		}

		// Attack indicator
		int x1 = p.x + cw + gap;
		card(p, x1, top, cw, chh, "Attack indicator", null);
		previewBox(p, x1 + 8, top + 26, cw - 16, 84);
		boolean ind = GuideSettings.enabled("attack-indicator");
		String style = GuideSettings.choice("attack-indicator", "Style", "Bar");
		drawAttackPreview(p, x1 + 8, top + 26, cw - 16, 84, ind ? style : "Vanilla");
		toggleRow(p, "ai_on", x1 + 10, ry, cw - 20, "Velo indicator", ind, v -> GuideSettings.setEnabled("attack-indicator", v));
		chips(p, "ai", x1 + 10, ry + 28, cw - 20, GuideSettings.choices("attack-indicator", "Style"), style, v -> {
			GuideSettings.setEnabled("attack-indicator", true);
			GuideSettings.setChoice("attack-indicator", "Style", v);
		});

		// 1.8 animations + toggle sprint
		int x2 = p.x + (cw + gap) * 2;
		card(p, x2, top, cw, chh, "1.8 feel", null);
		previewBox(p, x2 + 8, top + 26, cw - 16, 84);
		boolean old = GuideSettings.enabled("old-animations");
		drawSwingPreview(p, x2 + 8, top + 26, cw - 16, 84, old);
		toggleRow(p, "old_on", x2 + 10, ry, cw - 20, "1.8 animations", old, v -> GuideSettings.setEnabled("old-animations", v));
		toggleRow(p, "sprint_on", x2 + 10, ry + 26, cw - 20, "Toggle sprint", GuideSettings.enabled("toggle-sprint"),
				v -> GuideSettings.setEnabled("toggle-sprint", v));
	}

	private void done(Page p) {
		long now = System.nanoTime();
		float t = (now - stepChangedAt) / 1_000_000_000f;
		float pop = ease(Math.min(1f, t / 0.7f));
		int r = 26;
		int cx = p.x + p.w / 2;
		int cy = p.y + 64;
		for (int i = 0; i < 5; i++) {
			float ring = (t * 0.6f + i * 0.2f) % 1f;
			VeloDraw.strokeRounded(p.c, cx - r - ring * 40, cy - r - ring * 40, (r + ring * 40) * 2, (r + ring * 40) * 2, r + ring * 40, 1.5f,
					VeloUi.withAlpha(MenuKit.accent(), Math.round(0x50 * (1f - ring) * p.alpha)));
		}
		VeloDraw.fillCircle(p.c, (float) cx, (float) cy, r * pop, p.a(MenuKit.accent()));
		p.c.getMatrices().pushMatrix();
		p.c.getMatrices().translate(cx, cy);
		p.c.getMatrices().scale(pop, pop);
		drawCheck(p.c, 0, 0, 14, p.a(0xFFFFFFFF));
		p.c.getMatrices().popMatrix();
		Text title = MenuKit.hero("You're all set");
		MenuKit.text(p.c, GuideScreen.this.textRenderer, title, p.x + (p.w - GuideScreen.this.textRenderer.getWidth(title)) / 2, cy + r + 20, p.a(MenuKit.TEXT));
		String[] lines = {"Right Shift opens everything you just set up - and a lot more.", "Your Locker has cloaks, wings, pets and emotes to try on."};
		for (int i = 0; i < lines.length; i++) {
			Text line = MenuKit.ui(lines[i]);
			MenuKit.text(p.c, GuideScreen.this.textRenderer, line, p.x + (p.w - GuideScreen.this.textRenderer.getWidth(line)) / 2, cy + r + 50 + i * 13, p.a(MenuKit.TEXT_2));
		}
		keycap(p, p.x + p.w / 2 - 44, cy + r + 84, "R-Shift", true);
	}

	// ---------------------------------------------------------------- previews

	private int previewBox(Page p, int x, int y, int w, int h) {
		// square-ish corners: the previews are scissored to the box, so big round corners would show their edges
		VeloDraw.fillRounded(p.c, x, y, w, h, 2, p.a(0xFF0A0A0E));
		VeloDraw.strokeRounded(p.c, x, y, w, h, 2, p.a(0x14FFFFFF));
		return h;
	}

	private float time() {
		return (System.nanoTime() - openedAt) / 1_000_000_000f;
	}

	/** A first-person view while burning: the two fire sheets come up from the bottom, lowered and faded as set. */
	private void drawFirePreview(Page p, int x, int y, int w, int h, double lower, double opacity) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		int frame = (int) (time() * 16) % 32;
		int fw = w * 6 / 10;
		int fh = h + 6;
		int drop = (int) Math.round(lower * h * 1.1);
		int col = VeloUi.withAlpha(0xFFFFFFFF, (int) Math.round(0xE5 * opacity * p.alpha));
		for (int side = 0; side < 2; side++) {
			int fx = side == 0 ? x - fw / 6 : x + w - fw + fw / 6;
			p.c.drawTexture(RenderPipelines.GUI_TEXTURED, FIRE, fx, y + h - fh + drop + 12, 0f, frame * 16f, fw, fh, 16, 16, 16, 512, col);
		}
		crosshair(p, x + w / 2, y + h / 2);
		p.c.disableScissor();
	}

	/** Your shield in the off hand from first person, lowered by the set amount. */
	private void drawShieldPreview(Page p, int x, int y, int w, int h, double lower) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		crosshair(p, x + w / 2, y + h / 2);
		float scale = 3.4f;
		float sx = x + w * 0.18f;
		float sy = y + h * 0.42f + (float) (lower * h * 1.2);
		p.c.getMatrices().pushMatrix();
		p.c.getMatrices().translate(sx, sy);
		p.c.getMatrices().scale(scale, scale);
		// the shield's front face from its entity texture (no item stacks on the title screen: their data isn't bound yet)
		p.c.drawTexture(RenderPipelines.GUI_TEXTURED, SHIELD, 0, 0, 1f, 1f, 12, 22, 12, 22, 64, 64, p.a(0xFFFFFFFF));
		p.c.getMatrices().popMatrix();
		p.c.disableScissor();
	}

	/** Riding: the mount's neck and ears in front of you, faded to the set opacity - so the path below shows through. */
	private void drawMountPreview(Page p, int x, int y, int w, int h, double opacity) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		// a path going away from you
		for (int i = 0; i < 6; i++) {
			int pw = 8 + i * 10;
			int py = y + h / 2 + 4 + i * 7;
			VeloDraw.fillRounded(p.c, x + w / 2 - pw / 2, py, pw, 6, 1, p.a(0xFF8B6B3E));
		}
		int a = (int) Math.round(255 * opacity * p.alpha);
		int coat = VeloUi.withAlpha(0xFF6B4423, a);
		int dark = VeloUi.withAlpha(0xFF4A2E16, a);
		int mane = VeloUi.withAlpha(0xFF2A1A0E, a);
		float bob = (float) Math.sin(time() * 5.0) * 1.5f;
		int nx = x + w / 2 - 22;
		int ny = (int) (y + h * 0.38f + bob);
		VeloDraw.fillRounded(p.c, nx, ny + 10, 44, h, 6, coat);
		VeloDraw.fillRounded(p.c, nx + 16, ny + 6, 12, h, 3, mane);
		VeloDraw.fillRounded(p.c, nx + 2, ny - 8, 9, 22, 3, dark);
		VeloDraw.fillRounded(p.c, nx + 33, ny - 8, 9, 22, 3, dark);
		crosshair(p, x + w / 2, y + h / 2 - 6);
		p.c.disableScissor();
	}

	/** A dark cave wall with ore in it: Fullbright off it's nearly black, on it's lit like daytime. */
	private void drawCavePreview(Page p, int x, int y, int w, int h, boolean bright) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		String[] blocks = {"block/stone", "block/coal_ore", "block/stone", "block/iron_ore", "block/deepslate", "block/diamond_ore", "block/stone",
				"block/gold_ore"};
		int size = 26;
		int cols = w / size + 2;
		int rows = h / size + 2;
		float s = size / 16f;
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				tex(p.c, blocks[(r * 3 + c * 5) % blocks.length], x + c * size, y + r * size, size, p.a(0xFFFFFFFF));
			}
		}
		float lit = anim.merge("cave", bright ? 1f : 0f, (o, n) -> VeloAnim.step(o, bright ? 1f : 0f, p.dt * 3f));
		// night/cave darkness the way the game shows it: everything evenly dim, a hint of the blocks left
		int dark = Math.round(0xDA * (1f - lit) * p.alpha);
		p.c.fill(x + 1, y + 1, x + w - 1, y + h - 1, VeloUi.withAlpha(0xFF02030A, dark));
		p.c.disableScissor();
	}

	/** The same scene at the picked field of view: wider FOV, things shrink towards the middle and more fits in. */
	private void drawFovPreview(Page p, int x, int y, int w, int h, int fov) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		double k = Math.tan(Math.toRadians(70 / 2.0)) / Math.tan(Math.toRadians(Math.max(30, fov) / 2.0));
		int cx = x + w / 2;
		int horizon = y + h / 2;
		// pillars standing at fixed spots in the world, projected for this FOV
		double[] spots = {-1.6, -1.05, -0.55, 0.0, 0.6, 1.1, 1.7};
		for (int i = 0; i < spots.length; i++) {
			double depth = 1.0 + (i % 3) * 0.5;
			int px = cx + (int) Math.round(spots[i] / depth * w * 0.45 * k);
			int ph = (int) Math.round(h * 0.42 * k / depth);
			int pw = Math.max(3, (int) Math.round(w * 0.06 * k / depth));
			int col = i % 2 == 0 ? 0xFF6B4A2B : 0xFF4E7A35;
			VeloDraw.fillRounded(p.c, px - pw / 2, horizon - ph, pw, ph + 2, 1, p.a(col));
			VeloDraw.fillRounded(p.c, px - pw, horizon - ph - pw, pw * 2, pw * 2, 2, p.a(0xFF3C6B2A));
		}
		crosshair(p, cx, horizon);
		Text t = MenuKit.small(fov >= 110 ? "QUAKE PRO" : "FOV " + fov);
		MenuKit.text(p.c, GuideScreen.this.textRenderer, t, x + 7, y + 7, p.a(fov == 70 ? MenuKit.TEXT_3 : MenuKit.accentText()));
		p.c.disableScissor();
	}

	/** A little map scrolling under the player arrow, square or round. */
	private void drawMinimapPreview(Page p, int x, int y, int w, int h, boolean on, String shape) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		if (!on) {
			MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.small("MINIMAP OFF"), x + 7, y + 7, p.a(MenuKit.TEXT_3));
			p.c.disableScissor();
			return;
		}
		int size = Math.min(h - 12, 70);
		int mx = x + w - size - 8;
		int my = y + 6;
		boolean round = shape.equals("Round");
		int cell = 5;
		float t = time();
		int offX = (int) (t * 6) ;
		int offY = (int) (t * 3);
		int r = size / 2;
		for (int gy = 0; gy < size; gy += cell) {
			for (int gx = 0; gx < size; gx += cell) {
				if (round) {
					int dx = gx + cell / 2 - r;
					int dy = gy + cell / 2 - r;
					if (dx * dx + dy * dy > r * r) {
						continue;
					}
				}
				int wx = (gx + offX) / cell;
				int wy = (gy + offY) / cell;
				int hsh = (wx * 73856093) ^ (wy * 19349663);
				double n = Math.sin(wx * 0.35) + Math.cos(wy * 0.3) + ((hsh >>> 8) & 7) * 0.08;
				int col = n < -0.9 ? 0xFF2F6FB8 : n < -0.6 ? 0xFFD8C78A : n > 1.2 ? 0xFF2E5E22 : 0xFF4F8F3A;
				p.c.fill(mx + gx, my + gy, mx + gx + cell, my + gy + cell, p.a(col));
			}
		}
		if (round) {
			VeloDraw.strokeRounded(p.c, (float) mx, (float) my, (float) size, (float) size, r, 1.5f, p.a(0xFFFFFFFF));
		} else {
			VeloDraw.strokeRounded(p.c, mx, my, size, size, 3, p.a(0xFFFFFFFF));
		}
		// the player arrow
		float ax = mx + r;
		float ay = my + r;
		VeloDraw.line(p.c, ax, ay - 4, ax - 3, ay + 3, 1.6f, p.a(0xFFFF3B44));
		VeloDraw.line(p.c, ax, ay - 4, ax + 3, ay + 3, 1.6f, p.a(0xFFFF3B44));
		MenuKit.text(p.c, GuideScreen.this.textRenderer, net.veloclient.velo.client.hud.HudLook.preview(hudFont(), "128, 64, -42"), mx, my + size + 3,
				p.a(0xFFFFFFFF));
		p.c.disableScissor();
	}

	/** Three HUD readouts in the picked font and background. */
	private void drawFontPreview(Page p, int x, int y, int w, int h, String font, String bg) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		int fps = 1180 + (int) (Math.sin(time() * 3) * 6);
		hudPill(p, x + 10, y + 10, String.valueOf(fps), "FPS", font, bg);
		hudPill(p, x + 10, y + 32, "18", "ms", font, bg);
		java.time.LocalTime now = java.time.LocalTime.now();
		hudPill(p, x + w - 66, y + 10, String.format("%02d:%02d", now.getHour(), now.getMinute()), "", font, bg);
		Text name = MenuKit.small(font.toUpperCase());
		MenuKit.text(p.c, GuideScreen.this.textRenderer, name, x + w - GuideScreen.this.textRenderer.getWidth(name) - 8, y + h - 12, p.a(MenuKit.TEXT_3));
		p.c.disableScissor();
	}

	private String hudFont() {
		return GuideSettings.enabled("hud-style") ? GuideSettings.choice("hud-style", "Font", "Inter") : "Minecraft";
	}

	private String hudBackground() {
		return GuideSettings.enabled("hud-style") ? GuideSettings.choice("hud-style", "Background", "Glass") : "None";
	}

	/** A HUD readout ("1182 FPS"): bold value, muted unit, on the picked background. Returns its width. */
	private int hudPill(Page p, int x, int y, String value, String unit, String font, String bg) {
		Text v = net.veloclient.velo.client.hud.HudLook.preview(font, value);
		Text u = unit.isEmpty() ? null : net.veloclient.velo.client.hud.HudLook.preview(font, unit);
		int vw = GuideScreen.this.textRenderer.getWidth(v);
		int uw = u == null ? 0 : GuideScreen.this.textRenderer.getWidth(u) + 3;
		int w = vw + uw + 12;
		if (!bg.equals("None")) {
			VeloDraw.fillRounded(p.c, x, y, w, 16, 5, p.a(bg.equals("Solid") ? 0xF0141418 : 0xA00C0C10));
		}
		MenuKit.text(p.c, GuideScreen.this.textRenderer, v, x + 6, y + 4, p.a(0xFFFFFFFF));
		if (u != null) {
			MenuKit.text(p.c, GuideScreen.this.textRenderer, u, x + 6 + vw + 3, y + 4, p.a(0xFFB4B4BE));
		}
		return w;
	}

	/** The little animated look of each on-screen overlay, in the HUD font and background. */
	private void drawOverlayPreview(Page p, String id, int x, int y, int w, int h, boolean on) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		String font = hudFont();
		String bg = hudBackground();
		int alphaMul = on ? 255 : 110;
		float t = time();
		int cx = x + w / 2;
		int cy = y + h / 2;
		switch (id) {
			case "keystrokes" -> {
				int k = 14;
				int g = 2;
				String[] keys = {"W", "A", "S", "D"};
				int[][] pos = {{0, -1}, {-1, 0}, {0, 0}, {1, 0}};
				int phase = (int) (t * 4) % 8;
				boolean[] down = {phase < 5, phase == 2 || phase == 3, phase == 6, phase == 5 || phase == 6};
				for (int i = 0; i < 4; i++) {
					int kx = cx - k / 2 + pos[i][0] * (k + g);
					int ky = cy - k + pos[i][1] * (k + g) + 4;
					key(p, kx, ky, k, k, keys[i], down[i] && on, font, alphaMul);
				}
				key(p, cx - k / 2 - (k + g), cy + 7, k * 3 + g * 2, 7, "", ((int) (t * 2) % 3 == 0) && on, font, alphaMul);
			}
			case "mouse-buttons" -> {
				boolean l = on && (t * 7) % 1 < 0.35;
				boolean r = on && (t * 3.3) % 1 < 0.25;
				key(p, cx - 26, cy - 10, 25, 16, "LMB", l, font, alphaMul);
				key(p, cx + 1, cy - 10, 25, 16, "RMB", r, font, alphaMul);
				Text cps = net.veloclient.velo.client.hud.HudLook.preview(font, (on ? 9 + (int) (Math.sin(t) * 2) : 0) + " | " + (on ? 3 : 0) + " CPS");
				MenuKit.text(p.c, GuideScreen.this.textRenderer, cps, cx - GuideScreen.this.textRenderer.getWidth(cps) / 2, cy + 10,
						VeloUi.withAlpha(0xFFFFFFFF, Math.round(alphaMul * p.alpha)));
			}
			case "fps-counter" -> pillCentered(p, cx, cy, String.valueOf(1180 + (int) (Math.sin(t * 3) * 6)), "FPS", font, bg, on);
			case "coordinates" -> {
				int z = -42 - (int) (t * 4) % 60;
				pillCentered(p, cx, cy - 9, "XYZ", "128 64 " + z, font, bg, on);
				pillCentered(p, cx, cy + 9, "Facing", "north", font, bg, on);
			}
			case "clock" -> {
				java.time.LocalTime now = java.time.LocalTime.now();
				pillCentered(p, cx, cy, String.format("%02d:%02d", now.getHour(), now.getMinute()), now.getSecond() % 2 == 0 ? "" : "", font, bg, on);
			}
			default -> {
				int left = 84 - (int) (t % 84);
				VeloDraw.fillCircle(p.c, (float) (cx - 34), (float) cy, 5f, VeloUi.withAlpha(0xFF7CAFC6, Math.round(alphaMul * p.alpha)));
				pillCentered(p, cx + 6, cy, "Speed II", String.format("%d:%02d", left / 60, left % 60), font, bg, on);
			}
		}
		if (!on) {
			MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.small("OFF"), x + 6, y + 5, p.a(MenuKit.TEXT_3));
		}
		p.c.disableScissor();
	}

	private void pillCentered(Page p, int cx, int cy, String value, String unit, String font, String bg, boolean on) {
		Text v = net.veloclient.velo.client.hud.HudLook.preview(font, value);
		Text u = unit.isEmpty() ? null : net.veloclient.velo.client.hud.HudLook.preview(font, unit);
		int w = GuideScreen.this.textRenderer.getWidth(v) + (u == null ? 0 : GuideScreen.this.textRenderer.getWidth(u) + 3) + 12;
		int a = on ? 255 : 110;
		int x = cx - w / 2;
		int y = cy - 8;
		if (!bg.equals("None")) {
			VeloDraw.fillRounded(p.c, x, y, w, 16, 5, VeloUi.withAlpha(bg.equals("Solid") ? 0xFF141418 : 0xFF0C0C10, Math.round((bg.equals("Solid") ? 0xF0 : 0xA0) * a / 255f * p.alpha)));
		}
		MenuKit.text(p.c, GuideScreen.this.textRenderer, v, x + 6, y + 4, VeloUi.withAlpha(0xFFFFFFFF, Math.round(a * p.alpha)));
		if (u != null) {
			MenuKit.text(p.c, GuideScreen.this.textRenderer, u, x + 6 + GuideScreen.this.textRenderer.getWidth(v) + 3, y + 4,
					VeloUi.withAlpha(0xFFB4B4BE, Math.round(a * p.alpha)));
		}
	}

	/** A keystroke key: lights up in the accent colour while held. */
	private void key(Page p, int x, int y, int w, int h, String label, boolean down, String font, int alphaMul) {
		float am = alphaMul / 255f * p.alpha;
		int bg = down ? MenuKit.accent() : 0xFF0C0C10;
		VeloDraw.fillRounded(p.c, x, y + (down ? 1 : 0), w, h, 3, VeloUi.withAlpha(bg, Math.round((down ? 0xE0 : 0xA0) * am)));
		if (!label.isEmpty()) {
			Text t = net.veloclient.velo.client.hud.HudLook.preview(font, label);
			MenuKit.text(p.c, GuideScreen.this.textRenderer, t, x + (w - GuideScreen.this.textRenderer.getWidth(t)) / 2, y + (h - 8) / 2 + 1 + (down ? 1 : 0),
					VeloUi.withAlpha(0xFFFFFFFF, Math.round(255 * am)));
		}
	}

	private static int fov() {
		return MinecraftClient.getInstance().options.getFov().getValue();
	}

	private boolean fovChanged;

	private void setFov(int value) {
		MinecraftClient.getInstance().options.getFov().setValue(Math.max(30, Math.min(110, value)));
		fovChanged = true;
	}

	/**
	 * A player seen from behind, walking, with the Velo Neon cloak on - moving like the picked animation really does in game:
	 * Classic is vanilla's stiff plate swinging back with each step, Cloth bends (the bottom lifts most, a wave runs down it,
	 * the hem flutters), Wavey Capes ripples in even waves. Each strip of the cloak is projected by its own angle and shaded
	 * by how far it tilts, so it reads as a real surface.
	 */
	private void drawCapePreview(Page p, int x, int y, int w, int h, String style) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		float t = time();
		int unit = Math.max(2, (h - 14) / 34);
		int bodyW = unit * 8;
		int bodyH = unit * 12;
		int headS = unit * 8;
		int cx = x + w / 2;
		float bob = (float) Math.abs(Math.sin(t * 4.4)) * unit * 0.6f;
		int top = y + 6 + headS + Math.round(bob);
		// legs, arms, torso, head (dark silhouette with a rim light)
		int legH = unit * 12;
		int step = Math.round((float) Math.sin(t * 4.4) * unit);
		p.c.fill(cx - bodyW / 2, top + bodyH, cx, top + bodyH + legH + step, p.a(0xFF15151C));
		p.c.fill(cx, top + bodyH, cx + bodyW / 2, top + bodyH + legH - step, p.a(0xFF191922));
		p.c.fill(cx - bodyW / 2 - unit * 4, top, cx - bodyW / 2, top + bodyH, p.a(0xFF1B1B24));
		p.c.fill(cx + bodyW / 2, top, cx + bodyW / 2 + unit * 4, top + bodyH, p.a(0xFF1B1B24));
		p.c.fill(cx - bodyW / 2, top, cx + bodyW / 2, top + bodyH, p.a(0xFF20202A));
		p.c.fill(cx - headS / 2, top - headS, cx + headS / 2, top, p.a(0xFF262630));
		p.c.fill(cx - headS / 2, top - headS, cx + headS / 2, top - headS + 1, p.a(0x50FFFFFF));

		// the cloak: 10 x 16 cape pixels, hung from the shoulders
		int capeW = unit * 10;
		float capeLen = unit * 16;
		int frame = (int) (t * 10) % 10;
		int slices = 32;
		float walk = (float) (0.5 + 0.5 * Math.sin(t * 2.2));
		float stepSwing = (float) Math.abs(Math.sin(t * 4.4));
		float py = top + unit * 0.5f;
		for (int i = 0; i < slices; i++) {
			float f = (i + 0.5f) / slices;
			double angle;
			float side;
			switch (style) {
				case "Classic" -> {
					// one rigid plate: the same angle all the way down, a little kick with each step
					angle = Math.toRadians(8 + 26 * walk + 7 * stepSwing);
					side = (float) Math.sin(t * 1.1) * unit * 0.8f * f;
				}
				case "Wavey Capes" -> {
					angle = Math.toRadians((10 + 24 * walk) * f) + 0.32 * Math.sin(t * 5.0 - f * 9.0) * f;
					side = (float) Math.sin(t * 3.0 - f * 5.0) * unit * 0.6f * f;
				}
				default -> {
					// cloth: the top stays near the back, the lower part lifts, a wave travels down and the hem flutters
					angle = Math.toRadians((6 + 34 * walk + 6 * stepSwing) * (0.3 + 0.7 * Math.pow(f, 1.4)))
							+ 0.2 * Math.sin(t * 3.4 - f * 5.5) * f;
					side = (float) (Math.sin(t * 4.2 - f * 4.0) * unit * 1.3 * f * f);
				}
			}
			float len = capeLen / slices * (float) Math.cos(angle);
			float grow = 1f + 0.14f * (float) Math.sin(angle) * f; // closer to the camera when it swings back
			int sw = Math.round(capeW * grow);
			int sx = Math.round(cx - sw / 2f + side);
			int syTop = Math.round(py);
			py += Math.max(0.6f, len);
			int sh = Math.max(1, Math.round(py) - syTop);
			// tilted strips catch more light
			float light = 0.78f + 0.3f * (float) Math.sin(angle);
			int c = Math.min(255, Math.round(255 * light));
			int col = p.a(0xFF000000 | c << 16 | c << 8 | c);
			p.c.drawTexture(RenderPipelines.GUI_TEXTURED, NEON, sx, syTop, 0f, frame * 256f + f * 256f - 128f / slices, sw, sh, 160,
					Math.max(1, Math.round(256f / slices)), 160, 2560, col);
		}
		p.c.disableScissor();
	}

	private void drawHudPreview(Page p, String id, int x, int y, int w, int h) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		boolean on = GuideSettings.enabled(id);
		float scale = 2f;
		int rowW = Math.round(81 * scale);
		int ox = x + (w - rowW) / 2;
		int oy = y + h / 2 - 9;
		p.c.getMatrices().pushMatrix();
		p.c.getMatrices().translate(ox, oy);
		p.c.getMatrices().scale(scale, scale);
		switch (id) {
			case "hearts" -> statusRow(p, "hearts", on, "Style", "Heart Color", HEART_FILL, HEART_OUTLINE, HEART_DETAIL, "hud/heart/full", "hud/heart/container", 8);
			case "hunger" -> statusRow(p, "hunger", on, "Food Icon Style", "Food Color (Recolored)", FOOD_FILL, FOOD_OUTLINE, FOOD_DETAIL, "hud/food_full",
					"hud/food_empty", 7);
			case "armor-bar" -> statusRow(p, "armor-bar", on, "Style", "Armor Color", ARMOR_FILL, ARMOR_OUTLINE, ARMOR_DETAIL, "hud/armor_full",
					"hud/armor_empty", 6);
			case "hotbar" -> hotbar(p, on);
			case "glint-colorizer" -> {
				String[] glinty = {"item/enchanted_book", "item/diamond_sword", "item/diamond_chestplate", "item/bow"};
				int glint = on ? GuideSettings.color("glint-colorizer", "Glint Color", 0xFF49C6FF) : 0xFF8A4FFF;
				for (int i = 0; i < 4; i++) {
					Identifier t = GuideGlint.texture(glinty[i], glint, time());
					if (t != null) {
						p.c.drawTexture(RenderPipelines.GUI_TEXTURED, t, i * 21, -4, 0f, 0f, 16, 16, 16, 16, 16, 16, p.a(0xFFFFFFFF));
					} else {
						tex(p.c, glinty[i], i * 21, -4, 16, p.a(0xFFFFFFFF));
					}
				}
			}
			default -> xpBar(p, on);
		}
		p.c.getMatrices().popMatrix();
		Text tag = MenuKit.small(on ? "VELO" : "VANILLA");
		MenuKit.text(p.c, GuideScreen.this.textRenderer, tag, x + 8, y + 8, p.a(on ? MenuKit.accentText() : MenuKit.TEXT_3));
		p.c.disableScissor();
	}

	/** Ten icons the way the HUD module draws them: vanilla, vanilla recoloured, or Velo's own shapes. */
	private void statusRow(Page p, String id, boolean on, String styleLabel, String colorLabel, Identifier fill, Identifier outline, Identifier detail,
			String vanillaFull, String vanillaEmpty, int filled) {
		String style = on ? GuideSettings.choice(id, styleLabel, "Vanilla") : "Vanilla";
		int color = GuideSettings.color(id, colorLabel, 0xFFE3262B);
		for (int i = 0; i < 10; i++) {
			int ix = i * 8;
			boolean full = i < filled;
			if (style.startsWith("Velo")) {
				p.c.drawTexture(RenderPipelines.GUI_TEXTURED, fill, ix, 0, 0f, 0f, 9, 9, 9, 9, 9, 9, p.a(full ? color : 0xFF2B2323));
				p.c.drawTexture(RenderPipelines.GUI_TEXTURED, full ? detail : outline, ix, 0, 0f, 0f, 9, 9, 9, 9, 9, 9, p.a(full ? 0xFFFFFFFF : 0xFF000000));
			} else if (style.startsWith("Vanilla") && !style.equals("Vanilla")) {
				p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", vanillaEmpty), ix, 0, 9, 9);
				if (full) {
					Identifier tex = net.veloclient.velo.client.hud.StatusBarIcons.recoloredSprite(vanillaFull, color);
					if (tex != null) {
						net.veloclient.velo.client.hud.StatusBarIcons.drawSquare(p.c, tex, net.veloclient.velo.client.hud.StatusBarIcons.recoloredSize(vanillaFull, color),
								ix, 0, false, 0xFFFFFFFF);
					} else {
						p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", vanillaFull), ix, 0, 9, 9);
					}
				}
			} else {
				p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", vanillaEmpty), ix, 0, 9, 9);
				if (full) {
					p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", vanillaFull), ix, 0, 9, 9);
				}
			}
		}
	}

	private void hotbar(Page p, boolean on) {
		p.c.getMatrices().pushMatrix();
		p.c.getMatrices().scale(0.45f, 0.45f);
		p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/hotbar"), 0, 0, 182, 22);
		int color = GuideSettings.color("hotbar", "Slot Color", 0x903BA7FF);
		if (on && GuideSettings.toggle("hotbar", "Color The Slots")) {
			for (int i = 0; i < 9; i++) {
				p.c.fill(3 + i * 20, 3, 3 + i * 20 + 16, 19, VeloUi.withAlpha(color, Math.round(((color >>> 24) & 0xFF) * 0.55f * p.alpha)));
			}
		}
		String[] items = {"item/diamond_sword", "item/bow", "item/golden_apple", "block/cobblestone", "item/ender_pearl"};
		for (int i = 0; i < items.length; i++) {
			tex(p.c, items[i], 3 + i * 20, 3, 16, p.a(0xFFFFFFFF));
		}
		int sel = (int) (time() * 1.2f) % 5;
		p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/hotbar_selection"), -1 + sel * 20, -1, 24, 23);
		p.c.getMatrices().popMatrix();
	}

	private void xpBar(Page p, boolean on) {
		int fill = on ? GuideSettings.color("xp-bar", "XP Bar Fill", 0xFF80FF20) : 0xFF80FF20;
		int bg = on ? GuideSettings.color("xp-bar", "XP Bar Background", 0xFF1A1A1A) : 0xFF1A1A1A;
		float prog = 0.35f + 0.5f * (float) ((Math.sin(time() * 0.8) + 1) / 2);
		VeloDraw.fillRounded(p.c, 0, 4, 81, 3, 1, p.a(bg | 0xFF000000));
		VeloDraw.fillRounded(p.c, 0, 4, Math.round(81 * prog), 3, 1, p.a(fill | 0xFF000000));
		Text lvl = Text.literal("30");
		p.c.drawTextWithShadow(GuideScreen.this.textRenderer, lvl, 40 - GuideScreen.this.textRenderer.getWidth(lvl) / 2, -6, p.a(fill | 0xFF000000));
	}

	private void drawHudIcon(Page p, String id, int x, int y) {
		switch (id) {
			case "hearts" -> p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/heart/full"), x + 4, y + 4, 9, 9);
			case "hunger" -> p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/food_full"), x + 4, y + 4, 9, 9);
			case "armor-bar" -> p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/armor_full"), x + 4, y + 4, 9, 9);
			case "hotbar" -> tex(p.c, "item/diamond_sword", x + 1, y, 16, p.a(0xFFFFFFFF));
			case "glint-colorizer" -> tex(p.c, "item/enchanted_book", x + 1, y, 16, p.a(0xFFFFFFFF));
			default -> tex(p.c, "item/experience_bottle", x + 1, y, 16, p.a(0xFFFFFFFF));
		}
	}

	/** A fake moment of gameplay with the "Clip saved" toast sliding in every few seconds. */
	private void drawClipPreview(Page p, int x, int y, int w, int h) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		for (int i = 0; i < 5; i++) {
			int bx = x + 20 + i * 46;
			int bh = 30 + (i * 37) % 40;
			VeloDraw.fillRounded(p.c, bx, y + h - 26 - bh, 30, bh, 2, p.a(0xFF2E3B2A));
		}
		crosshair(p, x + w / 2, y + h / 2);
		boolean on = GuideSettings.enabled("clips");
		float cycle = time() % 4f;
		if (on) {
			// a red dot: recording (in memory)
			float pulse = (float) (0.6 + 0.4 * Math.sin(time() * 4));
			VeloDraw.fillCircle(p.c, x + 12f, y + 12f, 3.5f, VeloUi.withAlpha(0xFFE5484D, Math.round(255 * pulse * p.alpha)));
			MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.small("REC · LAST 30 S"), x + 20, y + 9, p.a(MenuKit.TEXT_2));
			float in = cycle < 1.2f ? ease(Math.min(1f, cycle / 0.35f)) * (cycle > 0.9f ? 1f - (cycle - 0.9f) / 0.3f : 1f) : 0f;
			if (in > 0f) {
				int tw = 150;
				int tx = x + w - Math.round((tw + 10) * ease(in));
				int ty = y + h - 40;
				VeloDraw.fillRounded(p.c, tx, ty, tw, 30, 7, p.a(0xF0151519));
				VeloDraw.fillRounded(p.c, tx, ty, 3, 30, 2, p.a(MenuKit.accent()));
				MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.uiBold("Clip saved"), tx + 10, ty + 6, p.a(MenuKit.accentText()));
				MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.ui("30 seconds · Right Shift > Clips"), tx + 10, ty + 17, p.a(MenuKit.TEXT_2));
			}
		} else {
			MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.small("CLIPS OFF"), x + 10, y + 9, p.a(MenuKit.TEXT_3));
		}
		p.c.disableScissor();
	}

	private void drawCrosshairPreview(Page p, int x, int y, int w, int h) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		var equipped = net.veloclient.velo.client.crosshair.CrosshairManager.equipped();
		boolean custom = GuideSettings.enabled("custom-crosshair") && equipped.isPresent();
		if (custom) {
			Identifier tex = net.veloclient.velo.client.crosshair.CrosshairManager.textureIdentifier(equipped.get().id(), false);
			int s = 32;
			if (tex != null) {
				p.c.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x + w / 2 - s / 2, y + h / 2 - s / 2, 0f, 0f, s, s, s, s, s, s, p.a(0xFFFFFFFF));
			}
		} else {
			p.c.getMatrices().pushMatrix();
			p.c.getMatrices().translate(x + w / 2, y + h / 2);
			p.c.getMatrices().scale(2f, 2f);
			vanillaCrosshair(p, 0, 0);
			p.c.getMatrices().popMatrix();
		}
		p.c.disableScissor();
	}

	/** The crosshair with the attack cooldown filling under it in the picked style. */
	private void drawAttackPreview(Page p, int x, int y, int w, int h, String style) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		float charge = Math.min(1f, (time() % 1.4f) / 1.1f);
		int fill = GuideSettings.color("attack-indicator", "Charge Color", MenuKit.accent());
		// everything at 2x around the crosshair, laid out exactly like the HUD
		p.c.getMatrices().pushMatrix();
		p.c.getMatrices().translate(x + w / 2, y + h / 2 - 8);
		p.c.getMatrices().scale(2f, 2f);
		vanillaCrosshair(p, 0, 0);
		switch (style) {
			case "Bar" -> {
				VeloDraw.fillRounded(p.c, -10, 9, 20, 3, 1, p.a(0x90000000));
				VeloDraw.fillRounded(p.c, -10, 9, Math.max(1, Math.round(20 * charge)), 3, 1, p.a(fill | 0xFF000000));
			}
			case "Thin Line" -> {
				p.c.fill(-11, 10, 11, 11, p.a(0x70000000));
				p.c.fill(-11, 10, -11 + Math.round(22 * charge), 11, p.a(fill | 0xFF000000));
			}
			default -> {
				// vanilla: the sword-shaped background, the progress sprite revealed left to right (17 steps), 16 px wide
				p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/crosshair_attack_indicator_background"), -8, 9, 16, 4);
				int l = Math.round(charge * 17f);
				if (l > 0) {
					p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/crosshair_attack_indicator_progress"), 16, 4, 0, 0, -8, 9,
							Math.min(16, l), 4);
				}
			}
		}
		p.c.getMatrices().popMatrix();
		p.c.disableScissor();
	}

	/** Minecraft's crosshair sprite centred on (cx, cy) the way the HUD places it ((size - 15) / 2). */
	private void vanillaCrosshair(Page p, int cx, int cy) {
		p.c.drawGuiTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "hud/crosshair"), cx - 7, cy - 7, 15, 15);
	}

	/** A sword swinging: modern swings drop the hand and wait, 1.8 swings snap and repeat. */
	private void drawSwingPreview(Page p, int x, int y, int w, int h, boolean old) {
		p.c.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		sky(p, x, y, w, h);
		crosshair(p, x + w / 2, y + h / 2);
		float period = old ? 0.35f : 0.9f;
		float ph = (time() % period) / period;
		float swing = (float) Math.sin(Math.min(1f, ph * (old ? 1f : 1.8f)) * Math.PI);
		float drop = old ? 0f : (ph > 0.55f ? 0f : (float) Math.sin(ph / 0.55f * Math.PI) * 10f);
		p.c.getMatrices().pushMatrix();
		p.c.getMatrices().translate(x + w * 0.66f, y + h * 0.62f + drop);
		p.c.getMatrices().rotate((float) Math.toRadians(-35 - swing * 55));
		p.c.getMatrices().scale(3f, 3f);
		tex(p.c, "item/diamond_sword", -8, -8, 16, p.a(0xFFFFFFFF));
		p.c.getMatrices().popMatrix();
		MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.small(old ? "1.8 SWING" : "MODERN SWING"), x + 8, y + 8, p.a(old ? MenuKit.accentText() : MenuKit.TEXT_3));
		p.c.disableScissor();
	}

	private void sky(Page p, int x, int y, int w, int h) {
		int bands = 8;
		for (int i = 0; i < bands; i++) {
			int c = VeloAnim.lerpArgb(0xFF2B4A72, 0xFF7FA9D8, i / (float) bands);
			p.c.fill(x, y + i * h / bands / 2, x + w, y + (i + 1) * h / bands / 2, p.a(c));
		}
		p.c.fill(x, y + h / 2, x + w, y + h, p.a(0xFF3F6B2E));
		p.c.fill(x, y + h / 2, x + w, y + h / 2 + 2, p.a(0xFF5C8F43));
	}

	private void crosshair(Page p, int cx, int cy) {
		p.c.fill(cx - 4, cy, cx + 5, cy + 1, p.a(0xC0FFFFFF));
		p.c.fill(cx, cy - 4, cx + 1, cy + 5, p.a(0xC0FFFFFF));
	}

	// ---------------------------------------------------------------- controls

	private void card(Page p, int x, int y, int w, int h, String title, String note) {
		VeloDraw.fillRounded(p.c, x, y, w, h, 10, p.a(0x0AFFFFFF));
		VeloDraw.strokeRounded(p.c, x, y, w, h, 10, p.a(0x14FFFFFF));
		if (title != null) {
			MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.uiBold(title), x + 10, y + 9, p.a(MenuKit.TEXT));
		}
	}

	private void label(Page p, int x, int y, String text) {
		MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.small(text.toUpperCase()), x, y, p.a(MenuKit.TEXT_3));
	}

	private void note(Page p, int x, int y, int w, String text) {
		int line = 0;
		StringBuilder cur = new StringBuilder();
		for (String word : text.split(" ")) {
			String next = cur.isEmpty() ? word : cur + " " + word;
			if (GuideScreen.this.textRenderer.getWidth(MenuKit.ui(next)) > w && !cur.isEmpty()) {
				MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.ui(cur.toString()), x, y + line * 11, p.a(MenuKit.TEXT_3));
				line++;
				cur = new StringBuilder(word);
			} else {
				cur = new StringBuilder(next);
			}
		}
		if (!cur.isEmpty()) {
			MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.ui(cur.toString()), x, y + line * 11, p.a(MenuKit.TEXT_3));
		}
	}

	private void perfBadge(Page p, int x, int y, String text) {
		Text t = MenuKit.small(text.toUpperCase());
		int w = GuideScreen.this.textRenderer.getWidth(t) + 20;
		VeloDraw.fillRounded(p.c, x, y, w, 14, 5, p.a(0x26F5C33B));
		VeloDraw.strokeRounded(p.c, x, y, w, 14, 5, p.a(0x66F5C33B));
		drawBolt(p.c, x + 7, y + 7, p.a(0xFFF5C33B));
		MenuKit.text(p.c, GuideScreen.this.textRenderer, t, x + 14, y + 4, p.a(0xFFF5D27A));
	}

	private void keycap(Page p, int x, int y, String key, boolean lit) {
		Text t = MenuKit.uiBold(key);
		int w = Math.max(26, GuideScreen.this.textRenderer.getWidth(t) + 14);
		VeloDraw.fillRounded(p.c, x, y + 3, w, 22, 6, p.a(0xFF0A0A0D));
		VeloDraw.fillRounded(p.c, x, y, w, 22, 6, p.a(lit ? VeloUi.withAlpha(MenuKit.accent(), 0xFF) : 0xFF26262E));
		VeloDraw.strokeRounded(p.c, x, y, w, 22, 6, p.a(0x30FFFFFF));
		MenuKit.text(p.c, GuideScreen.this.textRenderer, t, x + (w - GuideScreen.this.textRenderer.getWidth(t)) / 2, y + 7, p.a(0xFFFFFFFF));
	}

	private void toggleRow(Page p, String id, int x, int y, int w, String text, boolean on, Consumer<Boolean> set) {
		float v = anim.merge("tg_" + id, on ? 1f : 0f, (o, n) -> VeloAnim.step(o, on ? 1f : 0f, p.dt * 9f));
		float h = p.live ? hover("tg_" + id, p.mx, p.my, x, y, w, 20, p.dt) : 0f;
		MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.fit(GuideScreen.this.textRenderer, MenuKit.ui(text), w - 40), x, y + 6, p.a(VeloAnim.lerpArgb(MenuKit.TEXT_2, MenuKit.TEXT, Math.max(v, h))));
		int sx = x + w - 30;
		VeloDraw.fillRounded(p.c, sx, y + 3, 30, 14, 7, p.a(VeloAnim.lerpArgb(0xFF2A2A33, MenuKit.accent(), v)));
		VeloDraw.fillCircle(p.c, sx + 7f + 16f * v, y + 10f, 5f, p.a(0xFFFFFFFF));
		if (p.live) {
			hits.add(new Hit("tg_" + id, x, y, w, 20, () -> {
				set.accept(!on);
				VeloUiSounds.toggle(!on);
			}));
		}
	}

	private void chips(Page p, String id, int x, int y, int w, List<String> options, String current, Consumer<String> pick) {
		int cx = x;
		int cy = y;
		for (String option : options) {
			Text t = MenuKit.uiBold(option);
			int cw = GuideScreen.this.textRenderer.getWidth(t) + 16;
			if (cx + cw > x + w && cx > x) {
				cx = x;
				cy += 22;
			}
			boolean sel = option.equals(current);
			float s = anim.merge("ch_" + id + option, sel ? 1f : 0f, (o, n) -> VeloAnim.step(o, sel ? 1f : 0f, p.dt * 9f));
			float h = p.live ? hover("ch_" + id + option, p.mx, p.my, cx, cy, cw, 18, p.dt) : 0f;
			VeloDraw.fillRounded(p.c, cx, cy, cw, 18, 7, p.a(VeloAnim.lerpArgb(VeloAnim.lerpArgb(0x0EFFFFFF, 0x1CFFFFFF, h), VeloUi.withAlpha(MenuKit.accent(), 0xE0), s)));
			MenuKit.text(p.c, GuideScreen.this.textRenderer, t, cx + 8, cy + 5, p.a(VeloAnim.lerpArgb(MenuKit.TEXT_2, 0xFFFFFFFF, Math.max(s, h))));
			if (p.live) {
				hits.add(new Hit("ch_" + id + option, cx, cy, cw, 18, () -> {
					if (!option.equals(current)) {
						pick.accept(option);
						VeloUiSounds.click(1.1f);
					}
				}));
			}
			cx += cw + 5;
		}
	}

	private void slider(Page p, String id, int x, int y, int w, String text, double value, double min, double max,
			java.util.function.DoubleFunction<String> format, boolean enabled, DoubleConsumer set) {
		float en = anim.merge("sl_en_" + id, enabled ? 1f : 0f, (o, n) -> VeloAnim.step(o, enabled ? 1f : 0f, p.dt * 7f));
		int textCol = VeloAnim.lerpArgb(MenuKit.TEXT_3, MenuKit.TEXT_2, en);
		MenuKit.text(p.c, GuideScreen.this.textRenderer, MenuKit.ui(text), x, y, p.a(textCol));
		Text val = MenuKit.uiBold(format.apply(value));
		MenuKit.text(p.c, GuideScreen.this.textRenderer, val, x + w - GuideScreen.this.textRenderer.getWidth(val), y, p.a(VeloAnim.lerpArgb(MenuKit.TEXT_3, MenuKit.TEXT, en)));
		int ty = y + 14;
		float f = (float) ((value - min) / (max - min));
		VeloDraw.fillRounded(p.c, x, ty, w, 4, 2, p.a(0xFF26262E));
		VeloDraw.fillRounded(p.c, x, ty, Math.max(4, Math.round(w * f)), 4, 2, p.a(VeloAnim.lerpArgb(0xFF4A4A55, MenuKit.accent(), en)));
		boolean drag = dragging != null && dragging.id().equals(id);
		float h = p.live ? hover("sl_" + id, p.mx, p.my, x - 4, ty - 6, w + 8, 16, p.dt) : 0f;
		VeloDraw.fillCircle(p.c, x + w * f, ty + 2f, 5f + (drag ? 1.5f : h), p.a(0xFFFFFFFF));
		if (p.live && enabled) {
			sliders.add(new Slider(id, x, ty, w, min, max, v -> {
				set.accept(v);
				VeloUiSounds.slider();
			}));
		}
	}

	private void button(DrawContext context, String id, String text, int x, int y, int w, int h, boolean primary, int mouseX, int mouseY, float dt,
			float alpha, Runnable action) {
		float hv = hover("bt_" + id, mouseX, mouseY, x, y, w, h, dt);
		int a = Math.round(255 * alpha);
		if (primary) {
			VeloDraw.shadow(context, x, y, w, h, 8, 10, 3, VeloUi.withAlpha(MenuKit.accent(), Math.round((0x40 + 0x40 * hv) * alpha)));
			VeloDraw.fillRounded(context, x, y - Math.round(hv), w, h, 8, VeloUi.withAlpha(VeloAnim.lerpArgb(MenuKit.accent(), 0xFFFF5A63, hv), a));
		} else {
			VeloDraw.fillRounded(context, x, y, w, h, 8, VeloUi.withAlpha(0xFFFFFFFF, Math.round((0x0E + 0x12 * hv) * alpha)));
			VeloDraw.strokeRounded(context, x, y, w, h, 8, VeloUi.withAlpha(0xFFFFFFFF, Math.round((0x22 + 0x30 * hv) * alpha)));
		}
		Text t = MenuKit.uiBold(text);
		MenuKit.text(context, this.textRenderer, t, x + (w - this.textRenderer.getWidth(t)) / 2, y + (h - 8) / 2 + 1 - (primary ? Math.round(hv) : 0),
				VeloUi.withAlpha(0xFFFFFFFF, a));
		hits.add(new Hit("bt_" + id, x, y, w, h, action));
	}

	/** Eased hover for a control (0..1); plays the faint hover tick when it starts on a big card. */
	private float hover(String id, int mouseX, int mouseY, int x, int y, int w, int h, float dt) {
		boolean over = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		if (over && !id.equals(hovered) && (id.startsWith("hud_") || id.startsWith("bt_"))) {
			hovered = id;
			VeloUiSounds.hover();
		} else if (!over && id.equals(hovered)) {
			hovered = null;
		}
		return anim.merge("hv_" + id, over ? 1f : 0f, (o, n) -> VeloAnim.step(o, over ? 1f : 0f, Math.max(dt, 1 / 120f) * 10f));
	}

	private static void drawCheck(DrawContext c, float cx, float cy, float size, int color) {
		float h = size / 2f;
		VeloDraw.line(c, cx - h, cy, cx - h * 0.25f, cy + h * 0.7f, 2.4f, color);
		VeloDraw.line(c, cx - h * 0.25f, cy + h * 0.7f, cx + h, cy - h * 0.6f, 2.4f, color);
	}

	private static void drawBolt(DrawContext c, int cx, int cy, int color) {
		VeloDraw.line(c, cx + 1, cy - 4, cx - 2, cy + 1, 1.4f, color);
		VeloDraw.line(c, cx - 2, cy + 1, cx + 2, cy - 1, 1.4f, color);
		VeloDraw.line(c, cx + 2, cy - 1, cx - 1, cy + 4, 1.4f, color);
	}

	/**
	 * Draws a block/item texture straight from the resources ("item/diamond_sword"). The guide never makes item stacks:
	 * on the title screen their data components aren't bound yet and creating one crashes the game (26.x).
	 */
	private static void tex(DrawContext context, String path, int x, int y, int size, int color) {
		context.drawTexture(RenderPipelines.GUI_TEXTURED, Identifier.of("minecraft", "textures/" + path + ".png"), x, y, 0f, 0f, size, size, 16, 16, 16, 16,
				color);
	}

	private static float ease(float t) {
		t = Math.max(0f, Math.min(1f, t));
		return 1f - (float) Math.pow(1f - t, 3);
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (closing) {
			return true;
		}
		double mx = (click.x() - canvasX) / canvasScale;
		double my = (click.y() - canvasY) / canvasScale;
		for (Slider s : List.copyOf(sliders)) {
			if (mx >= s.x() - 4 && mx <= s.x() + s.w() + 4 && my >= s.y() - 7 && my <= s.y() + 10) {
				dragging = s;
				dragTo(click.x());
				return true;
			}
		}
		for (Hit hit : List.copyOf(hits)) {
			if (hit.contains(mx, my)) {
				if (hit.id().startsWith("bt_") || hit.id().equals("skip")) {
					VeloUiSounds.click(1.0f);
				}
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging != null) {
			dragTo(click.x());
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (dragging != null) {
			dragging = null;
			return true;
		}
		return super.mouseReleased(click);
	}

	private void dragTo(double screenX) {
		Slider s = dragging;
		double mouseX = (screenX - canvasX) / canvasScale;
		double f = Math.max(0, Math.min(1, (mouseX - s.x()) / s.w()));
		double v = s.min() + (s.max() - s.min()) * f;
		s.set().accept(Math.round(v * 100) / 100.0);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		int key = input.key();
		if (key == net.veloclient.velo.client.util.Keys.ESCAPE) {
			finish();
			return true;
		}
		if (key == net.veloclient.velo.client.util.Keys.ENTER || key == net.veloclient.velo.client.util.Keys.RIGHT) {
			if (step == STEP_NAMES.length - 1) {
				finish();
			} else {
				go(step + 1);
			}
			return true;
		}
		if (key == net.veloclient.velo.client.util.Keys.LEFT && step > 0) {
			go(step - 1);
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void close() {
		finish();
	}
}
