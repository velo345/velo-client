package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
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

		context.fill(0, 0, this.width, this.height, (int) (0x55 * openProgress) << 24);

		float scale = 0.94f + 0.06f * openProgress;
		context.getMatrices().pushMatrix();
		float centerX = windowX + windowWidth / 2f;
		float centerY = windowY + windowHeight / 2f;
		context.getMatrices().translate(centerX, centerY);
		context.getMatrices().scale(scale, scale);
		context.getMatrices().translate(-centerX, -centerY);

		Theme theme = ThemeManager.active();
		int alpha = (int) (255 * openProgress);
		int shadowAlpha = Math.min(90, alpha);
		context.fill(windowX + 4, windowY + 6, windowX + windowWidth + 4, windowY + windowHeight + 6, shadowAlpha << 24);

		int surface = (theme.surfaceWithOpacity() & 0x00FFFFFF) | (Math.min(alpha, (theme.surfaceWithOpacity() >>> 24)) << 24);
		VeloDraw.fillRounded(context, windowX, windowY, windowWidth, windowHeight, 6, surface);

		int headerColor = (theme.accentStart() & 0x00FFFFFF) | (Math.min(alpha, 40) << 24);
		context.fill(windowX, windowY, windowX + windowWidth, windowY + HEADER_HEIGHT, headerColor);
		context.fill(windowX, windowY + HEADER_HEIGHT, windowX + windowWidth, windowY + HEADER_HEIGHT + 1,
				(theme.accentStart() & 0x00FFFFFF) | (Math.min(alpha, 180) << 24));

		context.drawTextWithShadow(this.textRenderer, this.title, windowX + PADDING, windowY + (HEADER_HEIGHT - 8) / 2,
				(theme.text() & 0x00FFFFFF) | (alpha << 24));

		String closeLabel = "✕";
		int closeX = windowX + windowWidth - 22;
		int closeY = windowY + (HEADER_HEIGHT - 8) / 2;
		boolean closeHovered = mouseX >= closeX - 4 && mouseX <= closeX + 12 && mouseY >= windowY && mouseY <= windowY + HEADER_HEIGHT;
		context.drawTextWithShadow(this.textRenderer, closeLabel, closeX, closeY,
				closeHovered ? theme.accentStart() : ((theme.text() & 0x00FFFFFF) | (alpha << 24)));

		if (openProgress > 0.4f) {
			super.render(context, mouseX, mouseY, delta);
		}

		context.getMatrices().popMatrix();
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		int mouseX = (int) click.x();
		int mouseY = (int) click.y();
		int closeX = windowX + windowWidth - 22;
		if (mouseY >= windowY && mouseY <= windowY + HEADER_HEIGHT) {
			if (mouseX >= closeX - 4 && mouseX <= closeX + 12) {
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
