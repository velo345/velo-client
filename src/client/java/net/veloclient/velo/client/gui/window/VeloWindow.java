package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * Base for every Velo panel: a floating, draggable, glass-styled window
 * instead of a full-screen vanilla menu. Subclasses lay their widgets out
 * relative to {@link #contentX()}/{@link #contentY()} inside
 * {@link #layoutContent()}, which is re-invoked whenever the window moves so
 * dragging repositions every child widget consistently.
 */
public abstract class VeloWindow extends Screen {

	protected static final int HEADER_HEIGHT = 30;
	protected static final int PADDING = 16;

	protected int windowX;
	protected int windowY;
	protected int windowWidth;
	protected int windowHeight;

	private final int requestedWidth;
	private final int requestedHeight;

	private boolean draggingHeader;
	private double dragOffsetX;
	private double dragOffsetY;
	/** Set while {@link net.veloclient.velo.client.util.MenuWarmup} draws a window invisibly once (no sound, instantly open). */
	public static boolean warming;

	private float openProgress;
	private boolean closing;
	/** Spring animation ({@link #springAnimation()}): when the window started opening / closing. */
	private long openedAtNanos = -1;
	private long closingAtNanos;
	private float springScale = 1f;
	private float springRise;
	private Runnable onClosed;
	private Screen returnScreen;
	/** The last frame any Velo window drew - a window opening right after one is a hand-off, not a fresh open. */
	private static long lastWindowFrameNanos;
	private boolean openDecided;
	private boolean handedOff;
	/** The shared rail's drawing state while this window is a page of the one-window menu. */
	private final MenuShell.State shellState = new MenuShell.State();

	protected VeloWindow(Text title, int windowWidth, int windowHeight) {
		super(title);
		this.requestedWidth = windowWidth;
		this.requestedHeight = windowHeight;
		this.windowWidth = windowWidth;
		this.windowHeight = windowHeight;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// 2026-11-24 glass: in a world, the game's own blur behind the window - not vanilla's dark gradient on top of it
	//? if >=1.21.2 && <26.1 {
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		if (MinecraftClient.getInstance().world != null) {
			this.applyBlur(context);
		} else {
			super.renderBackground(context, mouseX, mouseY, delta);
		}
	}
	//?} else if >=26.1 {
	/*@Override
	public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		if (net.minecraft.client.Minecraft.getInstance().level != null) {
			this.extractBlurredBackground(context);
		} else {
			super.extractBackground(context, mouseX, mouseY, delta);
		}
	}
	*///?}

	/** Glass is the base of every look (blurred game, translucent body, white pills); glow/neon only add their edge. Solid stays opaque. */
	protected static boolean glassLook() {
		return VeloStyle.glass();
	}

	public void onClosed(Runnable callback) {
		this.onClosed = callback;
	}

	/** Screen to return to once the close animation finishes (default: none, returns to gameplay). */
	public void returnTo(Screen screen) {
		this.returnScreen = screen;
	}

	/** Dev-only (screenshot tour): close like pressing Esc. */
	public void closeForTour() {
		requestClose();
	}

	public Screen returnScreen() {
		return returnScreen;
	}

	@Override
	protected void init() {
		// Requested sizes assume plenty of scaled screen space; at a high GUI
		// Scale (common on large/high-DPI displays with "Auto") the scaled
		// screen can be much smaller than that, so clamp to what's actually
		// available or the window (and its header/drag area) ends up
		// partially or fully off-screen.
		if (DenseGui.appliesTo(this)) {
			float factor = DenseGui.factor();
			this.width = (int) Math.floor(MinecraftClient.getInstance().getWindow().getScaledWidth() * factor);
			this.height = (int) Math.floor(MinecraftClient.getInstance().getWindow().getScaledHeight() * factor);
		}
		int margin = 12;
		boolean shell = shell();
		windowWidth = Math.min(shell ? MenuShell.WIDTH : requestedWidth, Math.max(160, this.width - margin * 2));
		windowHeight = Math.min(shell ? MenuShell.HEIGHT : requestedHeight, Math.max(120, this.height - margin * 2));
		windowX = Math.max(0, (this.width - windowWidth) / 2);
		windowY = Math.max(0, (this.height - windowHeight) / 2);
		if (shell && MenuShell.savedX != Integer.MIN_VALUE) {
			// every page of the menu sits where the frame was dragged to
			windowX = Math.max(0, Math.min(MenuShell.savedX, this.width - windowWidth));
			windowY = Math.max(0, Math.min(MenuShell.savedY, this.height - windowHeight));
		}
		layoutContent();
	}

	/** Called on init and after every drag movement; (re)position all child widgets from {@link #contentX()}/{@link #contentY()}. */
	protected abstract void layoutContent();

	protected int contentX() {
		return windowX + PADDING + shellInset();
	}

	/** True for the pages of the one-window Velo menu (dev build): shared frame and rail, see {@link MenuShell}. */
	protected final boolean shell() {
		// 2026-11-24: no rail any more - the R-Shift menu is the navigation; every page is its own glass window
		return false;
	}

	/** Room the rail takes on the left of a menu page, plus centring for pages with a max content width. */
	private int shellInset() {
		if (!shell()) {
			return 0;
		}
		int avail = windowWidth - PADDING * 2 - MenuShell.RAIL - MenuShell.GAP;
		int max = ((MenuPage) this).shellMaxContentWidth();
		return MenuShell.RAIL + MenuShell.GAP + Math.max(0, (avail - max) / 2);
	}

	protected int contentY() {
		return windowY + HEADER_HEIGHT + PADDING;
	}

	protected int contentWidth() {
		if (shell()) {
			int avail = windowWidth - PADDING * 2 - MenuShell.RAIL - MenuShell.GAP;
			return Math.min(avail, ((MenuPage) this).shellMaxContentWidth());
		}
		return windowWidth - PADDING * 2;
	}

	protected int contentBottom() {
		return windowY + windowHeight - PADDING;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		float deltaSeconds = MinecraftClient.getInstance().getRenderTickCounter().getDynamicDeltaTicks() / 20f;
		long frameNanos = System.nanoTime();
		if (warming) {
			// MenuWarmup's invisible first draw: fully open at once, no sound, no hand-off trace for the real open later
			openDecided = true;
			handedOff = true;
			openProgress = 1f;
		}
		if (!openDecided) {
			// Opened straight from another Velo window (a rail tab, a sub-page): no second open animation - the
			// window just stays put and the new page fades in, instead of the whole menu bouncing on every switch.
			openDecided = true;
			if (frameNanos - lastWindowFrameNanos < 250_000_000L) {
				handedOff = true;
				openProgress = 1f;
				restartPageIn();
				net.veloclient.velo.client.sound.VeloUiSounds.tab();
			} else if (springAnimation() || shell()) {
				net.veloclient.velo.client.sound.VeloUiSounds.menuOpen();
			}
		}
		if (!warming) {
			lastWindowFrameNanos = frameNanos;
		}
		// Velo+ Customize: the menu's look and motion (ScreenLook.Menu); the defaults are Velo's own
		net.veloclient.velo.client.gui.title.ScreenLook.Menu look = net.veloclient.velo.client.gui.title.ScreenLook.menu();
		if (closing && returnScreen instanceof VeloWindow) {
			// Back to the window underneath: switch right away too.
			openProgress = 0f;
		} else if (handedOff && !closing) {
			openProgress = 1f;
			springScale = 1f;
			springRise = 0f;
		} else if (customMotion(look)) {
			customStep(look);
		} else if (springAnimation() || shell()) {
			springStep();
		} else {
			openProgress = VeloAnim.step(openProgress, closing ? 0f : 1f, Math.max(deltaSeconds, 1 / 60f) * 4.5f);
		}
		if (closing && openProgress < 0.02f) {
			if (onClosed != null) {
				onClosed.run();
			}
			//? if <26.1 {
			if (this.client.currentScreen == this) {
			//?} else if <26.2 {
			/*if (this.minecraft.screen == this) {
			*///?} else {
			/*if (this.minecraft.gui.screen() == this) {
			*///?}
				this.client.setScreen(returnScreen);
			}
			return;
		}

		int[] hole = openProgress > 0.97f && !closing ? windowHole() : null;
		int dimMax = Math.round(look.dim * 2.55f * (glassLook() ? 0.35f : 1f));   // glass: a light veil, the blur does the rest
		if (hole == null) {
			context.fill(0, 0, this.width, this.height, (int) (dimMax * openProgress) << 24);
		} else {
			// dim everything but the hole (the world shows through it untouched)
			int dim = (int) (dimMax * openProgress) << 24;
			context.fill(0, 0, this.width, hole[1], dim);
			context.fill(0, hole[1] + hole[3], this.width, this.height, dim);
			context.fill(0, hole[1], hole[0], hole[1] + hole[3], dim);
			context.fill(hole[0] + hole[2], hole[1], this.width, hole[1] + hole[3], dim);
			holeCorners(context, hole, dim);
		}

		// Opens with a gentle scale-up + rise, closes with the reverse.
		boolean spring = springAnimation() || shell() || customMotion(look);
		float scale = spring ? springScale : 0.955f + 0.045f * openProgress;
		float rise = spring ? springRise : (1f - openProgress) * 10f;
		float shiftX = customMotion(look) ? motionX : 0f;
		float scaleX = customMotion(look) ? scale * motionSX : scale;
		float scaleY = customMotion(look) ? scale * motionSY : scale;
		context.getMatrices().pushMatrix();
		float centerX = windowX + windowWidth / 2f;
		float centerY = windowY + windowHeight / 2f;
		context.getMatrices().translate(centerX + shiftX, centerY + rise);
		context.getMatrices().scale(scaleX, scaleY);
		context.getMatrices().translate(-centerX, -centerY);

		Theme theme = ThemeManager.active();
		int alpha = (int) (255 * openProgress);
		int radius = look.radius >= 0 ? look.radius : look.style.equals("minimal") ? 6 : glassLook() ? 14 : VeloStyle.RADIUS_WINDOW;
		int accentRgb = net.veloclient.velo.client.gui.title.MenuKit.accent() & 0xFFFFFF;
		String style = glassLook() ? "velo" : look.style;   // glass: one clean edge, no coloured glow
		switch (style) {
			case "minimal" -> {
				// flat: no shadow at all
			}
			case "glow" -> {
				VeloDraw.shadow(context, windowX, windowY, windowWidth, windowHeight, radius, 26, 0,
						(Math.round(0x70 * openProgress) << 24) | accentRgb);
				VeloDraw.shadow(context, windowX, windowY, windowWidth, windowHeight, radius, 12, 4,
						VeloUi.withAlpha(0xFF000000, Math.round(0x80 * openProgress)));
			}
			case "neon" -> {
				VeloDraw.shadow(context, windowX, windowY, windowWidth, windowHeight, radius, 34, 0,
						(Math.round(0x90 * openProgress) << 24) | accentRgb);
				VeloDraw.shadow(context, windowX, windowY, windowWidth, windowHeight, radius, 10, 0,
						(Math.round(0xA0 * openProgress) << 24) | accentRgb);
			}
			case "solid" -> VeloDraw.shadow(context, windowX, windowY, windowWidth, windowHeight, radius, 24, 10,
					VeloUi.withAlpha(0xFF000000, Math.round(0xB0 * openProgress)));
			default -> {
				// glass: no drop shadow (it shows through the translucent body as a dark band)
				if (!glassLook()) {
					VeloDraw.shadow(context, windowX, windowY, windowWidth, windowHeight, radius, 18, 6,
							VeloUi.withAlpha(0xFF000000, Math.round(0x8C * openProgress)));
				}
			}
		}

		// 2026-11: the launcher's ground (#0C0A0A, warm black, a touch of the theme), almost opaque - same look as the launcher.
		int glass = switch (style) {
			case "glass" -> VeloAnim.lerpArgb(0xFF181620, theme.surface() | 0xFF000000, 0.25f);
			case "solid" -> theme.surface() | 0xFF000000;
			case "neon" -> 0xFF07060A;
			case "glow" -> VeloAnim.lerpArgb(0xFF0C0A0A, 0xFF000000 | accentRgb, 0.05f);
			default -> 0xFF141418;
		};
		int baseAlpha = switch (style) {
			case "glass" -> 0xA8;
			case "solid" -> 0xFF;
			default -> 0xA4;   // frosted glass over the softly blurred game
		};
		if (glassLook()) {
			glass = 0xFF141418;
			baseAlpha = 0xA4;
		}
		baseAlpha = Math.round(baseAlpha * (1f - look.clear / 100f * 0.75f));
		int surface = (glass & 0x00FFFFFF) | (Math.round(baseAlpha * openProgress) << 24);
		if (hole == null) {
			VeloDraw.fillRounded(context, windowX, windowY, windowWidth, windowHeight, radius, surface);
		} else {
			// The window around a see-through preview: the same rounded body drawn in four clipped bands.
			int[][] bands = {
					{windowX, windowY, windowX + windowWidth, hole[1]},
					{windowX, hole[1] + hole[3], windowX + windowWidth, windowY + windowHeight},
					{windowX, hole[1], hole[0], hole[1] + hole[3]},
					{hole[0] + hole[2], hole[1], windowX + windowWidth, hole[1] + hole[3]}};
			for (int[] band : bands) {
				if (band[2] > band[0] && band[3] > band[1]) {
					context.enableScissor(band[0], band[1], band[2], band[3]);
					VeloDraw.fillRounded(context, windowX, windowY, windowWidth, windowHeight, radius, surface);
					context.disableScissor();
				}
			}
			holeCorners(context, hole, surface);
		}
		if (style.equals("glass")) {
			// a soft sheen across the top, like frosted glass catching light
			context.fillGradient(windowX + 1, windowY + 1, windowX + windowWidth - 1, windowY + Math.min(windowHeight, 90),
					VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x14 * openProgress)), 0x00FFFFFF);
		} else if (style.equals("glow") || style.equals("neon")) {
			// the accent rises from the header line
			context.fillGradient(windowX + 1, windowY + 1, windowX + windowWidth - 1, windowY + HEADER_HEIGHT,
					(Math.round((style.equals("neon") ? 0x30 : 0x1C) * openProgress) << 24) | accentRgb, accentRgb);
		}
		if (!style.equals("minimal") && !glassLook()) {
			context.fill(windowX + 1, windowY + HEADER_HEIGHT, windowX + windowWidth - 1, windowY + HEADER_HEIGHT + 1,
					style.equals("neon") ? (Math.round(0x80 * openProgress) << 24) | accentRgb
							: VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x10 * openProgress)));
		}
		int edge = switch (style) {
			case "glass" -> VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x38 * openProgress));
			case "minimal" -> VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x0C * openProgress));
			case "glow" -> (Math.round(0x90 * openProgress) << 24) | accentRgb;
			case "neon" -> (Math.round(0xFF * openProgress) << 24) | VeloAnim.lerpArgb(accentRgb | 0xFF000000, 0xFFFFFFFF, 0.25f) & 0xFFFFFF;
			default -> VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x26 * openProgress));
		};
		VeloDraw.strokeRounded(context, windowX, windowY, windowWidth, windowHeight, radius, edge);
		if (glassLook()) {
			// light catching the top edge
			context.fill(windowX + radius, windowY, windowX + windowWidth - radius, windowY + 1, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x34 * openProgress)));
		}
		if (style.equals("neon")) {
			VeloDraw.strokeRounded(context, windowX + 1, windowY + 1, windowWidth - 2, windowHeight - 2, Math.max(0, radius - 1),
					(Math.round(0x50 * openProgress) << 24) | accentRgb);
		}

		renderHeader(context, mouseX, mouseY, alpha);
		if (openProgress > 0.4f) {
			renderHeaderExtras(context, mouseX, mouseY);
		}

		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastFrameNanos, now);
		lastFrameNanos = now;
		boolean closeHovered = isOverClose(mouseX, mouseY);
		closeHover = VeloAnim.step(closeHover, closeHovered ? 1f : 0f, dt);
		int closeX = closeButtonX();
		int closeY = closeButtonY();
		VeloDraw.fillRounded(context, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE, glassLook() ? CLOSE_SIZE / 2f : 5,
				VeloAnim.lerpArgb(VeloUi.withAlpha(0xFFFFFFFF, Math.round((glassLook() ? 0x1A : 0x0D) * openProgress)),
						VeloUi.withAlpha(0xFFE5484D, Math.round(0xD0 * openProgress)), closeHover));
		int glyph = VeloAnim.lerpArgb(VeloUi.withAlpha(theme.text(), Math.round(0xB0 * openProgress)),
				VeloUi.withAlpha(0xFFFFFFFF, alpha), closeHover);
		VeloDraw.cross(context, closeX + CLOSE_SIZE / 2f, closeY + CLOSE_SIZE / 2f, 7.5f, 1.6f, glyph);

		String railId = shell() ? ((MenuPage) this).railId() : null;
		if (railId != null) {
			MenuShell.draw(context, shellState, windowX + PADDING, windowY + HEADER_HEIGHT + PADDING, contentBottom(), railId, mouseX, mouseY,
					openProgress);
		}
		if (openProgress > 0.4f) {
			// a page switched to from the rail slides in from the direction you moved; the frame and rail stay put
			float slide = railId != null && look.pages.equals("slide") ? MenuShell.slideIn(railId) : 0f;
			if (slide != 0f) {
				context.getMatrices().pushMatrix();
				context.getMatrices().translate(0f, slide);
			}
			// After a tab switch the new page glides in from the side it came from (down/up for the Locker's slot
			// column, right/left for header tabs), clipped to the page so nothing spills over the frame.
			float glide = look.pages.equals("slide") ? 1f - pageIn() : 0f;
			int[] glideArea = glide > 0f && (slideX != 0 || slideY != 0) ? pageArea() : null;
			fixedStripRight = Integer.MIN_VALUE;
			pageOffsetX = glideArea == null ? 0f : glide * SLIDE_PX * slideX;
			pageOffsetY = glideArea == null ? 0f : glide * SLIDE_PX * slideY;
			if (glideArea != null) {
				context.enableScissor(glideArea[0], glideArea[1], glideArea[0] + glideArea[2], glideArea[1] + glideArea[3]);
				context.getMatrices().pushMatrix();
				context.getMatrices().translate(pageOffsetX, pageOffsetY);
			}
			try {
				if (showingSkeleton()) {
					drawSkeleton(context);
				} else {
					renderContentLayer(context, mouseX, mouseY, delta);
					super.render(context, mouseX, mouseY, delta);
					renderOverlayLayer(context, mouseX, mouseY, delta);
				}
			} finally {
				if (glideArea != null) {
					context.getMatrices().popMatrix();
					context.disableScissor();
				}
				pageOffsetX = 0f;
				pageOffsetY = 0f;
			}
			if (slide != 0f) {
				context.getMatrices().popMatrix();
			}
			// A new page fades in after a tab switch - in place: moving the content made the whole window jump, and
			// hovering missed while it was still sliding.
			float page = pageIn();
			if (page < 1f) {
				int[] area = pageArea().clone();
				if (fixedStripRight > area[0] && fixedStripRight < area[0] + area[2]) {
					// the Locker's slot column didn't change - only the page beside it fades in
					area[2] -= fixedStripRight - area[0];
					area[0] = fixedStripRight;
				}
				VeloDraw.fillRounded(context, area[0], area[1], area[2], area[3], 6,
						(glass & 0x00FFFFFF) | (Math.round(0xD8 * (1f - page) * openProgress) << 24));
			}
			if (railId != null) {
				MenuShell.drawLabels(context, this.textRenderer, shellState, mouseX, mouseY);
			}
		}

		context.getMatrices().popMatrix();
		net.veloclient.velo.client.gui.widget.VeloContextMenu.render(this, context, mouseX, mouseY);
		lastTabs = headerTabs();
		lastTab = headerTab();
	}

	private static final float SLIDE_PX = 16f;
	/** The tab set and tab the last drawn window showed - a new page compares itself with it to know its direction. */
	private static String[] lastTabs;
	private static int lastTab = -1;
	/** When a Locker slot row was last clicked: a switch right after it slides vertically (the slots are a column). */
	private static long slotClickNanos;
	private int slideX;
	private int slideY;
	/** The current page offset while it glides in - parts that must stay put (the Locker's slot column) undo it. */
	private static float pageOffsetX;
	private static float pageOffsetY;

	/** Right edge of a fixed column drawn this frame (the Locker's slots), or MIN_VALUE. */
	private static int fixedStripRight = Integer.MIN_VALUE;

	/** Called while drawing a column that stays put on page switches (excluded from the fade-in). */
	public static void fixedStrip(int right) {
		fixedStripRight = right;
	}

	/** A Locker slot row was clicked (see {@link #slotClickNanos}). */
	public static void noteSlotClick() {
		slotClickNanos = System.nanoTime();
	}

	/** Draws {@code body} where it would be without the page glide (for fixed parts inside the content). */
	public static void unshifted(DrawContext context, Runnable body) {
		if (pageOffsetX == 0f && pageOffsetY == 0f) {
			body.run();
			return;
		}
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(-pageOffsetX, -pageOffsetY);
		try {
			body.run();
		} finally {
			context.getMatrices().popMatrix();
		}
	}

	/**
	 * False while the window still waits for its data (the Store's catalog, the friends list...): it then shows a
	 * loading skeleton instead of half-empty panels - for at most {@link #SKELETON_MAX_NANOS}, so it never hangs.
	 */
	protected boolean contentReady() {
		return true;
	}

	private static final long SKELETON_MAX_NANOS = 1_500_000_000L;
	private final long createdNanos = System.nanoTime();

	private boolean showingSkeleton() {
		return !contentReady() && System.nanoTime() - createdNanos < SKELETON_MAX_NANOS;
	}

	/** The loading skeleton: a title bar and a grid of cards in faint grey with a light sweeping across. */
	private void drawSkeleton(DrawContext context) {
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		int h = contentBottom() - y;
		int shape = 0x10FFFFFF;
		VeloDraw.fillRounded(context, x, y, Math.min(220, w / 3), 14, 4, shape);
		VeloDraw.fillRounded(context, x, y + 26, w, Math.min(120, h / 3), 10, 0x0AFFFFFF);
		int top = y + 26 + Math.min(120, h / 3) + 16;
		int columns = Math.max(2, w / 150);
		int gap = 10;
		int cw = (w - (columns - 1) * gap) / columns;
		int ch = Math.max(40, Math.min(110, (contentBottom() - top - gap) / 2));
		for (int row = 0; row < 2; row++) {
			for (int c = 0; c < columns; c++) {
				int cy = top + row * (ch + gap);
				if (cy + ch > contentBottom()) {
					continue;
				}
				VeloDraw.fillRounded(context, x + c * (cw + gap), cy, cw, ch, 9, 0x0AFFFFFF);
				VeloDraw.fillRounded(context, x + c * (cw + gap) + 10, cy + ch - 24, Math.min(cw - 20, 80), 8, 3, shape);
			}
		}
		float sweep = (System.nanoTime() / 1_000_000L % 1200) / 1200f;
		int band = Math.max(60, w / 4);
		int bx = x - band + Math.round((w + band) * sweep);
		context.enableScissor(x, y, x + w, contentBottom());
		VeloDraw.fillRounded(context, bx, y, band, h, 0, 0x08FFFFFF);
		context.disableScissor();
	}

	/** Draws a subclass's header extras (coins, actions) right-aligned before {@link #headerRight()}. */
	/** A rect {x, y, w, h} left out of the window body (and the dim), so the game shows through it - or null. */
	protected int[] windowHole() {
		return null;
	}

	/** The hole's frame is rounded (radius {@link #HOLE_RADIUS}): fill its four corners outside that curve with {@code color}. */
	private static void holeCorners(DrawContext context, int[] hole, int color) {
		int r = HOLE_RADIUS;
		int x0 = hole[0];
		int y0 = hole[1];
		int x1 = hole[0] + hole[2];
		int y1 = hole[1] + hole[3];
		for (int row = 0; row < r; row++) {
			double dy = r - row - 0.5;
			int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
			if (inset <= 0) {
				continue;
			}
			context.fill(x0, y0 + row, x0 + inset, y0 + row + 1, color);
			context.fill(x1 - inset, y0 + row, x1, y0 + row + 1, color);
			context.fill(x0, y1 - row - 1, x0 + inset, y1 - row, color);
			context.fill(x1 - inset, y1 - row - 1, x1, y1 - row, color);
		}
	}

	/** Corner radius of a see-through preview's frame (ModuleConfigScreen strokes it with the same radius). */
	protected static final int HOLE_RADIUS = 8;

		protected void renderHeaderExtras(DrawContext context, int mouseX, int mouseY) {
	}

	/** Clicks on header extras a subclass draws (coins, actions); true when handled. */
	protected boolean headerClick(int mouseX, int mouseY) {
		return false;
	}

	// ---- Header: big title + launcher-style underline tabs ----

	/** Tabs shown next to the title in the header (launcher look), or null for none. */
	protected String[] headerTabs() {
		return null;
	}

	/** The selected header tab. */
	protected int headerTab() {
		return -1;
	}

	protected void selectHeaderTab(int index) {
	}

	/** A small accent badge after a tab's label ("NEW"), or null. */
	protected String headerTabBadge(int index) {
		return null;
	}

	/** Where header extras (coins, actions) may start from the right: subclasses draw them left of this x. */
	protected int headerRight() {
		return closeButtonX() - 8;
	}

	private int[] tabX = new int[0];

	/** Where the header tabs end (x), so header extras can stay clear of them; 0 without tabs. */
	protected int headerTabsEnd() {
		return tabX.length == 0 || tabW == null || tabW.length != tabX.length ? 0 : tabX[tabX.length - 1] + tabW[tabX.length - 1];
	}
	private int[] tabW = new int[0];
	private float underlineX = -1;
	private float underlineW;
	private final java.util.Map<Integer, Float> tabHover = new java.util.HashMap<>();

	private void renderHeader(DrawContext context, int mouseX, int mouseY, int alpha) {
		String[] tabs = headerTabs();
		if (glassLook()) {
			renderGlassHeader(context, mouseX, mouseY, alpha, tabs);
			return;
		}
		Text title = net.veloclient.velo.client.gui.title.MenuKit.display(this.title.getString());
		// Sora's capitals sit low in its line box: this centres them on the header (measured on screen).
		int titleY = windowY + (HEADER_HEIGHT - net.veloclient.velo.client.gui.title.MenuKit.H_DISPLAY) / 2 + 5;
		net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer,
				net.veloclient.velo.client.gui.title.MenuKit.fit(this.textRenderer, title, windowWidth - PADDING * 2 - 40),
				windowX + PADDING, titleY, VeloUi.withAlpha(0xFFF4F4F6, alpha));
		if (tabs == null || tabs.length == 0) {
			tabX = new int[0];
			return;
		}
		int x = windowX + PADDING + this.textRenderer.getWidth(title) + 18;
		int textY = windowY + (HEADER_HEIGHT - 8) / 2 + 1;
		tabX = new int[tabs.length];
		tabW = new int[tabs.length];
		int active = headerTab();
		for (int i = 0; i < tabs.length; i++) {
			Text label = net.veloclient.velo.client.gui.title.MenuKit.ui(tabs[i]);
			int w = this.textRenderer.getWidth(label);
			String badge = headerTabBadge(i);
			int badgeW = badge == null ? 0 : this.textRenderer.getWidth(net.veloclient.velo.client.gui.title.MenuKit.small(badge)) + 8;
			tabX[i] = x;
			tabW[i] = w + (badge == null ? 0 : badgeW + 4);
			boolean hovered = mouseX >= x - 4 && mouseX < x + tabW[i] + 4 && mouseY >= windowY && mouseY < windowY + HEADER_HEIGHT;
			float h = tabHover.getOrDefault(i, 0f);
			h = VeloAnim.step(h, hovered ? 1f : 0f, 0.016f * 6);
			tabHover.put(i, h);
			int color = i == active ? 0xFFF4F4F6 : VeloAnim.lerpArgb(0xFF9A9AA6, 0xFFF4F4F6, h);
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, label, x, textY, VeloUi.withAlpha(color, alpha));
			if (badge != null) {
				int bx = x + w + 4;
				VeloDraw.fillRounded(context, bx, textY - 1, badgeW, 10, 3, VeloUi.withAlpha(net.veloclient.velo.client.gui.title.MenuKit.accent(), alpha));
				net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.small(badge),
						bx + 4, textY + 1, VeloUi.withAlpha(0xFFFFFFFF, alpha));
			}
			x += tabW[i] + 16;
		}
		if (active >= 0 && active < tabs.length) {
			// The underline slides to the selected tab.
			if (underlineX < 0) {
				underlineX = tabX[active];
				underlineW = tabW[active];
			}
			underlineX += (tabX[active] - underlineX) * 0.25f;
			underlineW += (tabW[active] - underlineW) * 0.25f;
			VeloDraw.fillRounded(context, Math.round(underlineX), windowY + HEADER_HEIGHT - 2, Math.round(underlineW), 2, 1,
					VeloUi.withAlpha(net.veloclient.velo.client.gui.title.MenuKit.accent(), alpha));
		}
	}

	private static final Identifier LOGO = Identifier.of("velo-client", "textures/icon/logo.png");
	private float pillX = -1;
	private float pillW;

	/** Glass header: the Velo icon, the title in Geist, and the tabs as pills (the active one white, gliding over). */
	private void renderGlassHeader(DrawContext context, int mouseX, int mouseY, int alpha, String[] tabs) {
		int logo = 14;
		int ly = windowY + (HEADER_HEIGHT - logo) / 2 + 1;
		int gs = net.veloclient.velo.client.util.ClientCompat.guiScale();
		Identifier tex = net.veloclient.velo.client.gui.drawer.CrispIcons.get(LOGO, logo * gs);
		if (tex != null) {
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(windowX + PADDING, ly);
			context.getMatrices().scale(1f / gs, 1f / gs);
			context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, logo * gs, logo * gs, logo * gs, logo * gs,
					logo * gs, logo * gs, VeloUi.withAlpha(0xFFFFFFFF, alpha));
			context.getMatrices().popMatrix();
		}
		Text title = net.veloclient.velo.client.gui.title.MenuKit.title13(this.title.getString());
		int tx = windowX + PADDING + logo + 7;
		int titleY = windowY + (HEADER_HEIGHT - 10) / 2 + 1;
		net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer,
				net.veloclient.velo.client.gui.title.MenuKit.fit(this.textRenderer, title, windowWidth - PADDING * 2 - 60), tx, titleY,
				VeloUi.withAlpha(0xFFFFFFFF, alpha));
		if (tabs == null || tabs.length == 0) {
			tabX = new int[0];
			return;
		}
		int pad = 9;
		int h = 18;
		int y = windowY + (HEADER_HEIGHT - h) / 2;
		int x = tx + this.textRenderer.getWidth(title) + 14;
		tabX = new int[tabs.length];
		tabW = new int[tabs.length];
		int total = 4;
		Text[] labels = new Text[tabs.length];
		for (int i = 0; i < tabs.length; i++) {
			labels[i] = net.veloclient.velo.client.gui.title.MenuKit.uiBold(tabs[i]);
			String badge = headerTabBadge(i);
			int badgeW = badge == null ? 0 : this.textRenderer.getWidth(net.veloclient.velo.client.gui.title.MenuKit.small(badge)) + 10;
			tabW[i] = this.textRenderer.getWidth(labels[i]) + pad * 2 + badgeW;
			tabX[i] = x + total - 2;
			total += tabW[i];
		}
		VeloDraw.fillRounded(context, x, y, total, h, h / 2f, VeloUi.withAlpha(0xFF000000, Math.round(0x48 * alpha / 255f)));
		int active = headerTab();
		if (active >= 0 && active < tabs.length) {
			if (pillX < 0) {
				pillX = tabX[active];
				pillW = tabW[active];
			}
			pillX += (tabX[active] - pillX) * 0.25f;
			pillW += (tabW[active] - pillW) * 0.25f;
			VeloDraw.fillRounded(context, pillX, y + 2, pillW, h - 4, (h - 4) / 2f, VeloUi.withAlpha(0xFFFFFFFF, alpha));
		}
		int textY = y + (h - 8) / 2 + 1;
		for (int i = 0; i < tabs.length; i++) {
			boolean on = i == active;
			boolean hovered = !on && mouseX >= tabX[i] && mouseX < tabX[i] + tabW[i] && mouseY >= y && mouseY < y + h;
			int color = on ? 0xFF141416 : hovered ? 0xFFFFFFFF : 0xB8FFFFFF;
			net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, labels[i], tabX[i] + pad, textY,
					VeloUi.withAlpha(color, Math.round(((color >>> 24) & 0xFF) * alpha / 255f)));
			String badge = headerTabBadge(i);
			if (badge != null) {
				int bx = tabX[i] + pad + this.textRenderer.getWidth(labels[i]) + 4;
				int bw = this.textRenderer.getWidth(net.veloclient.velo.client.gui.title.MenuKit.small(badge)) + 6;
				VeloDraw.fillRounded(context, bx, textY - 1, bw, 10, 5, VeloUi.withAlpha(net.veloclient.velo.client.gui.title.MenuKit.accent(), alpha));
				net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.small(badge),
						bx + 3, textY + 1, VeloUi.withAlpha(0xFFFFFFFF, alpha));
			}
		}
	}

	private boolean clickHeaderTab(double mouseX, double mouseY) {
		for (int i = 0; i < tabX.length; i++) {
			if (mouseX >= tabX[i] - 6 && mouseX < tabX[i] + tabW[i] + 6 && mouseY >= windowY && mouseY < windowY + HEADER_HEIGHT) {
				if (i != headerTab()) {
					selectHeaderTab(i);
					restartPageIn();
					net.veloclient.velo.client.sound.VeloUiSounds.tab();
				}
				return true;
			}
		}
		return false;
	}

	/** When the current page appeared - pages fade/slide in from this (see {@link #pageIn()}). */
	private long contentShownAt = System.nanoTime();

	/** 0..1 eased progress of the current page's entrance (after a tab switch). */
	protected float pageIn() {
		var look = net.veloclient.velo.client.gui.title.ScreenLook.menu();
		if (look.pages.equals("none")) {
			return 1f;
		}
		float t = Math.min(1f, (System.nanoTime() - contentShownAt) / (260_000_000f / look.speed));
		return 1f - (float) Math.pow(1f - t, 3);
	}

	/** The part of the window that fades in on a page switch: {x, y, w, h} (default: all of the content). */
	protected int[] pageArea() {
		return new int[] {contentX(), contentY(), contentWidth(), contentBottom() - contentY()};
	}

	/** Restarts the page entrance (call when a subclass switches pages its own way). */
	protected void restartPageIn() {
		contentShownAt = System.nanoTime();
		// Which way to glide: compared with the tab the last window showed, if it was the same set of tabs.
		slideX = 0;
		slideY = 0;
		int now = headerTab();
		String[] tabs = headerTabs();
		if (now >= 0 && lastTab >= 0 && now != lastTab && tabs != null && java.util.Arrays.equals(tabs, lastTabs)) {
			int direction = Integer.signum(now - lastTab);
			if (System.nanoTime() - slotClickNanos < 400_000_000L) {
				slideY = direction;
			} else {
				slideX = direction;
			}
		}
	}

	private static final int CLOSE_SIZE = 22;   // big enough to hit, and clear of header buttons
	private float closeHover;
	private long lastFrameNanos;

	private int closeButtonX() {
		return windowX + windowWidth - 7 - CLOSE_SIZE;
	}

	private int closeButtonY() {
		return windowY + (HEADER_HEIGHT - CLOSE_SIZE) / 2;
	}

	private boolean isOverClose(double mouseX, double mouseY) {
		return mouseX >= closeButtonX() - 2 && mouseX <= closeButtonX() + CLOSE_SIZE + 2
				&& mouseY >= closeButtonY() - 2 && mouseY <= closeButtonY() + CLOSE_SIZE + 2;
	}

	/**
	 * Hook for windows that draw their own panels: runs inside the window's open/scale transform,
	 * after the frame and before child widgets, so widgets (text fields...) end up on top of it.
	 */
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
	}

	/** Hook drawn after the child widgets (inside the window transform): text or badges over previews. */
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		int mouseX = (int) click.x();
		int mouseY = (int) click.y();
		if (net.veloclient.velo.client.gui.widget.VeloContextMenu.click(this, click.x(), click.y())) {
			return true;
		}
		if (click.button() == 0 && net.veloclient.velo.client.gui.widget.VeloScrollRegion.pressScrollbar(this, click.x(), click.y())) {
			return true;
		}
		if (shell() && MenuShell.click(this, shellState, ((MenuPage) this).railId(), click.x(), click.y())) {
			return true;
		}
		if (mouseY >= windowY && mouseY <= windowY + HEADER_HEIGHT) {
			if (isOverClose(mouseX, mouseY)) {
				requestClose();
				return true;
			}
			if (clickHeaderTab(mouseX, mouseY)) {
				return true;
			}
			if (headerClick(mouseX, mouseY)) {
				return true;
			}
			if (mouseX >= windowX && mouseX <= windowX + windowWidth) {
				draggingHeader = true;
				dragOffsetX = mouseX - windowX;
				dragOffsetY = mouseY - windowY;
				return true;
			}
		}
		// Rows of a scroll list may stick out past its visible area (partly scrolled-in tiles);
		// those hidden parts must not swallow clicks meant for buttons drawn over them.
		boolean anyClipped = false;
		for (var child : this.children()) {
			if (net.veloclient.velo.client.gui.widget.VeloScrollRegion.clipsClick(child, click.x(), click.y())) {
				anyClipped = true;
				break;
			}
		}
		if (anyClipped) {
			for (var child : this.children()) {
				if (net.veloclient.velo.client.gui.widget.VeloScrollRegion.clipsClick(child, click.x(), click.y())) {
					continue;
				}
				if (child.mouseClicked(click, doubled)) {
					this.setFocused(child);
					this.setDragging(true);
					return true;
				}
			}
			return false;
		}
		return velo$superMouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (net.veloclient.velo.client.gui.widget.VeloScrollRegion.dragScrollbar(click.y())) {
			return true;
		}
		if (draggingHeader) {
			windowX = (int) (click.x() - dragOffsetX);
			windowY = (int) (click.y() - dragOffsetY);
			// Keep the whole window (not just the header) reachable - never
			// let it drag fully or partially off-screen.
			windowX = Math.max(0, Math.min(windowX, Math.max(0, this.width - windowWidth)));
			windowY = Math.max(0, Math.min(windowY, Math.max(0, this.height - windowHeight)));
			if (shell()) {
				MenuShell.savedX = windowX;
				MenuShell.savedY = windowY;
			}
			layoutContent();
			return true;
		}
		return velo$superMouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (net.veloclient.velo.client.gui.widget.VeloScrollRegion.releaseScrollbar()) {
			return true;
		}
		if (draggingHeader) {
			draggingHeader = false;
			return true;
		}
		return velo$superMouseReleased(click);
	}

	protected void requestClose() {
		if (!closing && shell() && returnScreen instanceof MenuPage) {
			// one window: Esc on any page closes the menu, it doesn't walk back through the pages you visited
			returnScreen = null;
		}
		if (!closing) {
			closingAtNanos = System.nanoTime();
			if (returnScreen == null && (springAnimation() || handedOff || shell())) {
				net.veloclient.velo.client.sound.VeloUiSounds.menuClose();
			}
		}
		closing = true;
	}

	/**
	 * True for windows that open with a spring (the Velo menu): a quick fade with a slight overshoot in scale on the
	 * way in, a short ease-in shrink on the way out. Time-based, so it looks the same at any frame rate.
	 */
	protected boolean springAnimation() {
		return false;
	}

	/** Customize motion: offsets and per-axis scale of the open/close animation (beside springScale / springRise). */
	private float motionX;
	private float motionSX = 1f;
	private float motionSY = 1f;

	private boolean customMotion(net.veloclient.velo.client.gui.title.ScreenLook.Menu look) {
		return !(look.open.equals("spring") && look.close.equals("spring") && look.speed == 1f);
	}

	/** The chosen open / close animation, time-based (same at any frame rate), at the chosen speed. */
	private void customStep(net.veloclient.velo.client.gui.title.ScreenLook.Menu look) {
		long now = System.nanoTime();
		if (openedAtNanos < 0) {
			openedAtNanos = now;
		}
		String kind = closing ? look.close : look.open;
		if (kind.equals("spring")) {
			// Velo's own, just at another speed
			long shift = (long) ((now - (closing ? closingAtNanos : openedAtNanos)) * (look.speed - 1f));
			long realOpen = openedAtNanos;
			long realClose = closingAtNanos;
			if (closing) {
				closingAtNanos -= shift;
			} else {
				openedAtNanos -= shift;
			}
			springStep();
			openedAtNanos = realOpen;
			closingAtNanos = realClose;
			motionX = 0f;
			motionSX = 1f;
			motionSY = 1f;
			return;
		}
		float ms = closing ? (kind.equals("fold") ? 260f : 200f) : kind.equals("drop") ? 620f : kind.equals("pop") ? 460f : 360f;
		float t = kind.equals("none") ? 1f : Math.min(1f, (now - (closing ? closingAtNanos : openedAtNanos)) / (ms * 1_000_000f / look.speed));
		// k: how "there" the window is (1 = fully open)
		float k = closing ? 1f - t * t * t : 1f - (float) Math.pow(1f - t, 3);
		openProgress = Math.min(1f, kind.equals("drop") || kind.startsWith("slide") ? Math.min(1f, k * 1.6f) : k);
		springScale = 1f;
		springRise = 0f;
		motionX = 0f;
		motionSX = 1f;
		motionSY = 1f;
		float away = 1f - k;
		switch (kind) {
			case "zoom" -> springScale = 0.82f + 0.18f * k;
			case "pop" -> {
				float c1 = 1.70158f * 1.4f;
				float c3 = c1 + 1f;
				float back = 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
				springScale = 0.6f + 0.4f * back;
			}
			case "slide-up" -> springRise = away * 60f * (closing ? -1f : 1f);
			case "slide-down" -> springRise = away * -60f * (closing ? -1f : 1f);
			case "slide-left" -> motionX = away * 90f * (closing ? -1f : 1f);
			case "slide-right" -> motionX = away * -90f * (closing ? -1f : 1f);
			case "drop" -> {
				// falls in from above and settles with a small bounce
				springRise = -(1f - bounce(t)) * (this.height * 0.7f);
			}
			case "unfold" -> {
				motionSY = Math.max(0.02f, k);
				springScale = 1f;
			}
			case "fold" -> motionSY = Math.max(0.02f, k);
			default -> {
				// fade: just the opacity
			}
		}
		if (closing && t >= 1f) {
			openProgress = 0f;
		}
	}

	private static float bounce(float t) {
		float n1 = 7.5625f;
		float d1 = 2.75f;
		if (t < 1 / d1) {
			return n1 * t * t;
		} else if (t < 2 / d1) {
			t -= 1.5f / d1;
			return n1 * t * t + 0.75f;
		} else if (t < 2.5 / d1) {
			t -= 2.25f / d1;
			return n1 * t * t + 0.9375f;
		}
		t -= 2.625f / d1;
		return n1 * t * t + 0.984375f;
	}

	private void springStep() {
		long now = System.nanoTime();
		if (openedAtNanos < 0) {
			openedAtNanos = now;
		}
		if (!closing) {
			float t = Math.min(1f, (now - openedAtNanos) / 420_000_000f);
			openProgress = 1f - (float) Math.pow(1f - Math.min(1f, t * 1.6f), 3);
			// Back-out: 0.9 -> a hair over 1 -> 1.
			float c1 = 1.4f;
			float c3 = c1 + 1f;
			float back = 1f + c3 * (float) Math.pow(t - 1f, 3) + c1 * (float) Math.pow(t - 1f, 2);
			springScale = 0.9f + 0.1f * back;
			springRise = (1f - openProgress) * 16f;
		} else {
			float t = Math.min(1f, (now - closingAtNanos) / 170_000_000f);
			float in = t * t * t;
			openProgress = Math.min(openProgress, 1f - in);
			springScale = 1f - 0.05f * in;
			springRise = -4f * in;
			if (t >= 1f) {
				openProgress = 0f;
			}
		}
	}

	/**
	 * Closes with the normal scale-down animation but always lands on
	 * gameplay, no matter how many Velo screens deep this one is nested
	 * (e.g. a module's settings screen returns to the main panel by
	 * default) - used by the panel-toggle keybind, which should back all
	 * the way out in one press rather than pop just one level.
	 */
	public void closeToGame() {
		this.returnScreen = null;
		requestClose();
	}

	@Override
	public void close() {
		requestClose();
	}

	// ---- 1.21.1: vanilla input still comes as numbers; the screens use 1.21.9's event objects ----

	//? if <1.21.2 {
	/*public boolean keyPressed(net.veloclient.velo.client.compat.KeyInput input) {
		return super.keyPressed(input.key(), input.scancode(), input.modifiers());
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return keyPressed(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}

	public boolean keyReleased(net.veloclient.velo.client.compat.KeyInput input) {
		return super.keyReleased(input.key(), input.scancode(), input.modifiers());
	}

	@Override
	public boolean keyReleased(int key, int scancode, int modifiers) {
		return keyReleased(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}

	public boolean charTyped(net.veloclient.velo.client.compat.CharInput input) {
		return super.charTyped((char) input.codepoint(), input.modifiers());
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		return charTyped(new net.veloclient.velo.client.compat.CharInput(chr, modifiers));
	}
	*///?}
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
	*///?}
}
