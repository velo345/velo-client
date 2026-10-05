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
	private Runnable onClosed;
	private Screen returnScreen;

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
		openProgress = VeloAnim.step(openProgress, closing ? 0f : 1f, Math.max(deltaSeconds, 1 / 60f) * 3f);
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
		float scale = 0.955f + 0.045f * openProgress;
		float rise = (1f - openProgress) * 10f;
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

		int surface = (theme.surfaceWithOpacity() & 0x00FFFFFF) | (Math.min(alpha, (theme.surfaceWithOpacity() >>> 24)) << 24);
		VeloDraw.fillRounded(context, windowX, windowY, windowWidth, windowHeight, radius, surface);
		// A faint accent wash at the very top gives the header depth without a hard colored band.
		VeloDraw.fillRoundedTop(context, windowX, windowY, windowWidth, HEADER_HEIGHT, radius,
				VeloUi.withAlpha(theme.accentStart(), Math.round(0x16 * openProgress)));
		context.fill(windowX + 1, windowY + HEADER_HEIGHT, windowX + windowWidth - 1, windowY + HEADER_HEIGHT + 1,
				VeloUi.withAlpha(theme.text(), Math.round(0x1A * openProgress)));
		VeloDraw.strokeRounded(context, windowX, windowY, windowWidth, windowHeight, radius,
				VeloUi.withAlpha(theme.text(), Math.round(0x26 * openProgress)));

		// Title: a small accent pill + the window name in the bold UI font.
		int titleY = windowY + (HEADER_HEIGHT - 8) / 2;
		VeloDraw.fillRoundedGradient(context, windowX + PADDING, titleY - 1, 3, 10, 1,
				VeloUi.withAlpha(theme.accentStart(), alpha), VeloUi.withAlpha(theme.accentEnd(), alpha));
		context.drawTextWithShadow(this.textRenderer,
				VeloUi.trimStyled(this.title, windowWidth - PADDING * 2 - 40, t -> net.veloclient.velo.client.gui.title.TitleScreenTheme.tileFont(t.getString())),
				windowX + PADDING + 9, titleY, VeloUi.withAlpha(theme.text(), alpha));

		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastFrameNanos, now);
		lastFrameNanos = now;
		boolean closeHovered = isOverClose(mouseX, mouseY);
		closeHover = VeloAnim.step(closeHover, closeHovered ? 1f : 0f, dt);
		int closeX = closeButtonX();
		int closeY = closeButtonY();
		if (closeHover > 0.01f) {
			VeloDraw.fillRounded(context, closeX, closeY, CLOSE_SIZE, CLOSE_SIZE, 6,
					VeloUi.withAlpha(0xFFE5484D, Math.round(0xD0 * closeHover * openProgress)));
		}
		int glyph = VeloAnim.lerpArgb(VeloUi.withAlpha(theme.text(), Math.round(0xB0 * openProgress)),
				VeloUi.withAlpha(0xFFFFFFFF, alpha), closeHover);
		VeloDraw.cross(context, closeX + CLOSE_SIZE / 2f, closeY + CLOSE_SIZE / 2f, 6.5f, 1.3f, glyph);

		if (openProgress > 0.4f) {
			renderContentLayer(context, mouseX, mouseY, delta);
			super.render(context, mouseX, mouseY, delta);
		}

		context.getMatrices().popMatrix();
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

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		int mouseX = (int) click.x();
		int mouseY = (int) click.y();
		if (mouseY >= windowY && mouseY <= windowY + HEADER_HEIGHT) {
			if (isOverClose(mouseX, mouseY)) {
				requestClose();
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
		closing = true;
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
