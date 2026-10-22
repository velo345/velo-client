package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
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
		windowWidth = Math.min(requestedWidth, Math.max(160, this.width - margin * 2));
		windowHeight = Math.min(requestedHeight, Math.max(120, this.height - margin * 2));
		windowX = Math.max(0, (this.width - windowWidth) / 2);
		windowY = Math.max(0, (this.height - windowHeight) / 2);
		layoutContent();
	}

	/** Called on init and after every drag movement; (re)position all child widgets from {@link #contentX()}/{@link #contentY()}. */
	protected abstract void layoutContent();

	protected int contentX() {
		return windowX + PADDING;
	}

	protected int contentY() {
		return windowY + HEADER_HEIGHT + PADDING;
	}

	protected int contentWidth() {
		return windowWidth - PADDING * 2;
	}

	protected int contentBottom() {
		return windowY + windowHeight - PADDING;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		float deltaSeconds = MinecraftClient.getInstance().getRenderTickCounter().getDynamicDeltaTicks() / 20f;
		long frameNanos = System.nanoTime();
		if (!openDecided) {
			// Opened straight from another Velo window (a rail tab, a sub-page): no second open animation - the
			// window just stays put and the new page fades in, instead of the whole menu bouncing on every switch.
			openDecided = true;
			if (frameNanos - lastWindowFrameNanos < 250_000_000L) {
				handedOff = true;
				openProgress = 1f;
				restartPageIn();
			}
		}
		lastWindowFrameNanos = frameNanos;
		if (closing && returnScreen instanceof VeloWindow) {
			// Back to the window underneath: switch right away too.
			openProgress = 0f;
		} else if (handedOff && !closing) {
			openProgress = 1f;
			springScale = 1f;
			springRise = 0f;
		} else if (springAnimation()) {
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

		context.fill(0, 0, this.width, this.height, (int) (0x70 * openProgress) << 24);

		// Opens with a gentle scale-up + rise, closes with the reverse.
		float scale = springAnimation() ? springScale : 0.955f + 0.045f * openProgress;
		float rise = springAnimation() ? springRise : (1f - openProgress) * 10f;
		context.getMatrices().pushMatrix();
		float centerX = windowX + windowWidth / 2f;
		float centerY = windowY + windowHeight / 2f;
		context.getMatrices().translate(centerX, centerY + rise);
		context.getMatrices().scale(scale, scale);
		context.getMatrices().translate(-centerX, -centerY);

		Theme theme = ThemeManager.active();
		int alpha = (int) (255 * openProgress);
		int radius = VeloStyle.RADIUS_WINDOW;
		VeloDraw.shadow(context, windowX, windowY, windowWidth, windowHeight, radius, 18, 6,
				VeloUi.withAlpha(0xFF000000, Math.round(0x8C * openProgress)));

		// 0.9.7: graphite glass (a touch of the theme's own surface), hairline edge, Sora title - no coloured header band.
		int glass = VeloAnim.lerpArgb(0xFF0E0E12, theme.surface() | 0xFF000000, 0.14f);
		int surface = (glass & 0x00FFFFFF) | (Math.round(0xEB * openProgress) << 24);
		VeloDraw.fillRounded(context, windowX, windowY, windowWidth, windowHeight, radius, surface);
		context.fill(windowX + 1, windowY + HEADER_HEIGHT, windowX + windowWidth - 1, windowY + HEADER_HEIGHT + 1,
				VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x10 * openProgress)));
		VeloDraw.strokeRounded(context, windowX, windowY, windowWidth, windowHeight, radius,
				VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x18 * openProgress)));

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
		VeloDraw.fillRounded(context, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE, 5,
				VeloAnim.lerpArgb(VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x0D * openProgress)),
						VeloUi.withAlpha(0xFFE5484D, Math.round(0xD0 * openProgress)), closeHover));
		int glyph = VeloAnim.lerpArgb(VeloUi.withAlpha(theme.text(), Math.round(0xB0 * openProgress)),
				VeloUi.withAlpha(0xFFFFFFFF, alpha), closeHover);
		VeloDraw.cross(context, closeX + CLOSE_SIZE / 2f, closeY + CLOSE_SIZE / 2f, 6.5f, 1.3f, glyph);

		if (openProgress > 0.4f) {
			if (showingSkeleton()) {
				drawSkeleton(context);
			} else {
				renderContentLayer(context, mouseX, mouseY, delta);
				super.render(context, mouseX, mouseY, delta);
				renderOverlayLayer(context, mouseX, mouseY, delta);
			}
			// A new page fades in after a tab switch - in place: moving the content made the whole window jump, and
			// hovering missed while it was still sliding.
			float page = pageIn();
			if (page < 1f) {
				int[] area = pageArea();
				VeloDraw.fillRounded(context, area[0], area[1], area[2], area[3], 6,
						(glass & 0x00FFFFFF) | (Math.round(0xD8 * (1f - page) * openProgress) << 24));
			}
		}

		context.getMatrices().popMatrix();
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
	private int[] tabW = new int[0];
	private float underlineX = -1;
	private float underlineW;
	private final java.util.Map<Integer, Float> tabHover = new java.util.HashMap<>();

	private void renderHeader(DrawContext context, int mouseX, int mouseY, int alpha) {
		String[] tabs = headerTabs();
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

	private boolean clickHeaderTab(double mouseX, double mouseY) {
		for (int i = 0; i < tabX.length; i++) {
			if (mouseX >= tabX[i] - 6 && mouseX < tabX[i] + tabW[i] + 6 && mouseY >= windowY && mouseY < windowY + HEADER_HEIGHT) {
				if (i != headerTab()) {
					selectHeaderTab(i);
					contentShownAt = System.nanoTime();
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
		float t = Math.min(1f, (System.nanoTime() - contentShownAt) / 200_000_000f);
		return 1f - (float) Math.pow(1f - t, 3);
	}

	/** The part of the window that fades in on a page switch: {x, y, w, h} (default: all of the content). */
	protected int[] pageArea() {
		return new int[] {contentX(), contentY(), contentWidth(), contentBottom() - contentY()};
	}

	/** Restarts the page entrance (call when a subclass switches pages its own way). */
	protected void restartPageIn() {
		contentShownAt = System.nanoTime();
	}

	private static final int CLOSE_SIZE = 18;
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
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (draggingHeader) {
			windowX = (int) (click.x() - dragOffsetX);
			windowY = (int) (click.y() - dragOffsetY);
			// Keep the whole window (not just the header) reachable - never
			// let it drag fully or partially off-screen.
			windowX = Math.max(0, Math.min(windowX, Math.max(0, this.width - windowWidth)));
			windowY = Math.max(0, Math.min(windowY, Math.max(0, this.height - windowHeight)));
			layoutContent();
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (draggingHeader) {
			draggingHeader = false;
			return true;
		}
		return super.mouseReleased(click);
	}

	protected void requestClose() {
		if (!closing) {
			closingAtNanos = System.nanoTime();
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
}
