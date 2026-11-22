package net.veloclient.velo.client.browser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.media.MediaTextures;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.util.ClientCompat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The Velo Browser window: a page over the game with a slim bar (back, forward, reload, home, the address field, hide,
 * fullscreen, close). Drag it by the bar, resize it from any edge or corner (it never gets smaller than 300 x 190), it
 * fades and scales in and out. Closed with Esc it keeps running - as a small picture-in-picture or hidden (audio
 * only), as the player chose - until the X closes the page for good.
 */
public final class BrowserWindow {

	public enum Away { PIP, HIDDEN, CLOSE }

	static final int BAR = 22;
	static final int MIN_W = 300;
	static final int MIN_H = 190;
	private static final int GRIP = 6;

	static BrowserView view;
	/** Window position and size as fractions of the screen (so it fits any window size / GUI scale). */
	static float fx = 0.14f;
	static float fy = 0.10f;
	static float fw = 0.72f;
	static float fh = 0.76f;
	static boolean fullscreen;
	/** What Esc / the hide button leaves running. */
	static Away away = Away.PIP;
	/** Hidden by the eye button: only the sound goes on. */
	static boolean hidden;
	static float pipOpacity = 0.95f;
	static String background = "Velo";
	static Path picture;
	static final List<StartPage.Shortcut> SHORTCUTS = new ArrayList<>(StartPage.DEFAULTS);

	private static long openedAt;
	private static long closingAt;
	private static Runnable afterClose;
	/** Drag state: what's being dragged (0 none, 1 move, 2+ resize edges as bits: 2 left, 4 right, 8 top, 16 bottom). */
	private static int drag;
	private static double dragX;
	private static double dragY;
	private static float[] dragStart;
	private static boolean contentPressed;
	private static long lastClickAt;
	private static int clicks;
	static boolean addressFocused;
	static int[] addressRect;
	/** The address field's "go" button (only while typing), null when not shown. */
	static int[] goRect;

	private BrowserWindow() {
	}

	public static boolean active() {
		return view != null && !view.closed();
	}

	static void opened() {
		openedAt = System.nanoTime();
		closingAt = 0;
		hidden = false;
	}

	/** Plays the closing animation, then runs {@code then}. */
	static void animateClose(Runnable then) {
		if (closingAt == 0) {
			closingAt = System.nanoTime();
			afterClose = then;
		}
	}

	/** 0..1, eased: how "there" the window is right now. */
	private static float presence() {
		long now = System.nanoTime();
		if (closingAt != 0) {
			float t = Math.min(1f, (now - closingAt) / 160_000_000f);
			if (t >= 1f && afterClose != null) {
				Runnable r = afterClose;
				afterClose = null;
				MinecraftClient.getInstance().execute(r);
			}
			return 1f - t * t;
		}
		float t = Math.min(1f, (now - openedAt) / 260_000_000f);
		return 1f - (float) Math.pow(1f - t, 3);
	}

	static String home() {
		return StartPage.url(SHORTCUTS, background, picture);
	}

	// ------------------------------------------------------------------ layout

	/** {x, y, w, h} of the window in GUI pixels. */
	static int[] rect(int sw, int sh) {
		if (fullscreen) {
			return new int[] {0, 0, sw, sh};
		}
		int w = Math.max(Math.min(MIN_W, sw), Math.min(sw, Math.round(fw * sw)));
		int h = Math.max(Math.min(MIN_H, sh), Math.min(sh, Math.round(fh * sh)));
		int x = Math.max(0, Math.min(sw - w, Math.round(fx * sw)));
		int y = Math.max(0, Math.min(sh - h, Math.round(fy * sh)));
		return new int[] {x, y, w, h};
	}

	/** {x, y, w, h} of the page inside the window. */
	static int[] content(int[] r) {
		return new int[] {r[0], r[1] + BAR, r[2], r[3] - BAR};
	}

	/** The small window while playing in the corner. */
	static int[] pipRect(int sw, int sh, int texW, int texH) {
		// the page's own shape (it was squeezed into 16:9 before), no taller than half the screen
		int w = Math.max(160, Math.round(sw * 0.26f));
		int h = Math.round(w * (texH / (float) Math.max(1, texW)));
		int maxH = sh / 2;
		if (h > maxH) {
			w = Math.round(w * maxH / (float) h);
			h = maxH;
		}
		return new int[] {sw - w - 8, 8, w, h};
	}

	// ------------------------------------------------------------------ drawing

	/** The full window (the browser screen is open). */
	static void render(DrawContext context, int sw, int sh, int mouseX, int mouseY) {
		CefRuntime.pump();
		TextRenderer tr = MinecraftClient.getInstance().textRenderer;
		float k = presence();
		int[] r = rect(sw, sh);
		context.getMatrices().pushMatrix();
		float cx = r[0] + r[2] / 2f;
		float cy = r[1] + r[3] / 2f;
		float s = 0.94f + 0.06f * k;
		context.getMatrices().translate(cx, cy + (1f - k) * 8f);
		context.getMatrices().scale(s, s);
		context.getMatrices().translate(-cx, -cy);
		int alpha = Math.round(255 * k);
		int radius = fullscreen ? 0 : 8;
		if (!fullscreen) {
			VeloDraw.shadow(context, r[0], r[1], r[2], r[3], radius, 18, 6, (Math.round(0x8C * k) << 24));
		}
		VeloDraw.fillRounded(context, r[0], r[1], r[2], r[3], radius, (Math.round(0xF7 * k) << 24) | 0x0C0A0A);
		int[] c = content(r);
		if (!CefRuntime.ensure()) {
			drawSetup(context, tr, c, mouseX, mouseY, alpha);
		} else {
			if (view == null || view.closed()) {
				int scale = ClientCompat.guiScale();
				view = BrowserView.open(home(), c[2] * scale, c[3] * scale);
			}
			if (view != null) {
				int scale = ClientCompat.guiScale();
				view.resize(c[2] * scale, c[3] * scale);
				MediaTextures.Tex tex = view.texture();
				if (tex != null) {
					context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, tex.id(), c[0], c[1], 0f, 0f, c[2], c[3],
							tex.width(), tex.height(), tex.width(), tex.height(), (alpha << 24) | 0xFFFFFF);
				}
				if (view.loadError() != null) {
					drawError(context, tr, c, view.loadError(), alpha);
				}
			}
		}
		drawBar(context, tr, r, mouseX, mouseY, alpha);
		if (!fullscreen) {
			VeloDraw.strokeRounded(context, r[0], r[1], r[2], r[3], radius, (Math.round(0x18 * k) << 24) | 0xFFFFFF);
			// resize grip
			int gx = r[0] + r[2] - 9;
			int gy = r[1] + r[3] - 9;
			for (int i = 0; i < 3; i++) {
				context.fill(gx + 6 - i * 3, gy + 6, gx + 7 - i * 3, gy + 7, (Math.round(0x50 * k) << 24) | 0xFFFFFF);
				context.fill(gx + 6, gy + 6 - i * 3, gx + 7, gy + 7 - i * 3, (Math.round(0x50 * k) << 24) | 0xFFFFFF);
			}
		}
		context.getMatrices().popMatrix();
	}

	private static final String[] BUTTONS = {"arrow_left", "arrow_right", "reload", "home"};
	private static final String[] RIGHT = {"eye_off", "expand", "close"};

	private static void drawBar(DrawContext context, TextRenderer tr, int[] r, int mouseX, int mouseY, int alpha) {
		int y = r[1];
		int accent = MenuKit.accent();
		context.fill(r[0] + 1, y + BAR - 1, r[0] + r[2] - 1, y + BAR, (alpha * 0x16 / 255 << 24) | 0xFFFFFF);
		int x = r[0] + 6;
		for (int i = 0; i < BUTTONS.length; i++) {
			boolean enabled = view != null && switch (i) {
				case 0 -> view.canBack();
				case 1 -> view.canForward();
				default -> true;
			};
			drawButton(context, x, y + 3, BUTTONS[i], enabled, false, mouseX, mouseY, alpha);
			x += 18;
		}
		int right = r[0] + r[2] - 6;
		int rx = right - RIGHT.length * 18 + 2;
		for (int i = 0; i < RIGHT.length; i++) {
			drawButton(context, rx + i * 18, y + 3, RIGHT[i], true, i == RIGHT.length - 1, mouseX, mouseY, alpha);
		}
		// the address field
		int ax = x + 4;
		int aw = Math.max(40, rx - 6 - ax);
		addressRect = new int[] {ax, y + 3, aw, 16};
		boolean hover = MenuKit.inside(mouseX, mouseY, ax, y + 3, aw, 16);
		VeloDraw.fillRounded(context, ax, y + 3, aw, 16, 8, (alpha * (addressFocused ? 0x26 : hover ? 0x1C : 0x12) / 255 << 24) | 0xFFFFFF);
		if (addressFocused) {
			VeloDraw.strokeRounded(context, ax, y + 3, aw, 16, 8, (alpha << 24) | (accent & 0xFFFFFF));
		}
		String shown;
		int color;
		if (addressFocused && !BrowserScreen.fieldText().isEmpty()) {
			shown = BrowserScreen.fieldText();
			color = 0xFFFFFF;
		} else if (addressFocused) {
			shown = view == null || StartPage.isStartPage(view.url()) ? "Search Google or type an address" : pretty(view.url());
			color = 0x6E6E78;
		} else if (view == null || StartPage.isStartPage(view.url())) {
			shown = "Search Google or type an address";
			color = 0x8E8384;
		} else {
			shown = pretty(view.url());
			color = 0xC9C2C3;
		}
		boolean go = addressFocused && !BrowserScreen.fieldText().isBlank();
		goRect = go ? new int[] {ax + aw - 15, y + 4, 14, 14} : null;
		if (go) {
			boolean goHover = MenuKit.inside(mouseX, mouseY, goRect[0], goRect[1], goRect[2], goRect[3]);
			int accentRgb = accent & 0xFFFFFF;
			VeloDraw.fillRounded(context, goRect[0], goRect[1], 14, 14, 7,
					(alpha * (goHover ? 0xFF : 0xD8) / 255 << 24) | accentRgb);
			MenuKit.icon(context, "arrow_right", goRect[0] + 3, goRect[1] + 3, 8, (alpha << 24) | 0xFFFFFF);
		}
		Text label = MenuKit.fit(tr, MenuKit.ui(shown), aw - (go ? 34 : 18));
		MenuKit.text(context, tr, label, ax + 9, y + 7, (alpha << 24) | color);
		if (addressFocused && (System.currentTimeMillis() / 500) % 2 == 0) {
			int caret = ax + 9 + (BrowserScreen.fieldText().isEmpty() ? 0 : Math.min(aw - 18, MenuKit.width(tr, label)));
			context.fill(caret, y + 6, caret + 1, y + 16, (alpha << 24) | 0xFFFFFF);
		}
		// loading line
		if (view != null && view.loading()) {
			float t = (System.currentTimeMillis() % 1200) / 1200f;
			int lw = r[2] / 4;
			int lx = r[0] + Math.round((r[2] + lw) * t) - lw;
			context.enableScissor(r[0], y + BAR - 2, r[0] + r[2], y + BAR);
			context.fill(lx, y + BAR - 2, lx + lw, y + BAR, (alpha << 24) | (accent & 0xFFFFFF));
			context.disableScissor();
		}
	}

	/** "https://www.youtube.com/watch?v=x" -> "youtube.com/watch?v=x" */
	private static String pretty(String url) {
		return url.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
	}

	/** {@code danger}: the window's close button (red on hover). */
	private static void drawButton(DrawContext context, int x, int y, String icon, boolean enabled, boolean danger, int mouseX, int mouseY, int alpha) {
		boolean hover = enabled && MenuKit.inside(mouseX, mouseY, x, y, 16, 16);
		float h = MenuKit.hover("browser:" + icon + x, hover);
		if (h > 0.01f) {
			int fill = danger ? VeloAnim.lerpArgb(0x00E5484D, 0xC0E5484D, h) : VeloAnim.lerpArgb(0x00FFFFFF, 0x22FFFFFF, h);
			VeloDraw.fillRounded(context, x, y, 16, 16, 5, fill & ((alpha << 24) | 0xFFFFFF));
		}
		int color = enabled ? 0xD8D2D3 : 0x55555C;
		MenuKit.icon(context, icon, x + 3, y + 3, 10, (alpha << 24) | color);
	}

	private static void drawSetup(DrawContext context, TextRenderer tr, int[] c, int mouseX, int mouseY, int alpha) {
		int cx = c[0] + c[2] / 2;
		int cy = c[1] + c[3] / 2;
		CefRuntime.State s = CefRuntime.state();
		String head;
		String sub;
		if (s == CefRuntime.State.FAILED) {
			head = "The browser couldn't start";
			sub = CefRuntime.error() == null ? "" : CefRuntime.error();
		} else if (s == CefRuntime.State.DOWNLOADING) {
			head = "Getting the browser ready";
			sub = "One-time download (~120 MB) - " + CefRuntime.progressText();
		} else {
			head = "Starting the browser...";
			sub = "";
		}
		Text h = MenuKit.uiBold(head);
		MenuKit.text(context, tr, h, cx - MenuKit.width(tr, h) / 2, cy - 18, (alpha << 24) | 0xEDE6E6);
		Text t = MenuKit.fit(tr, MenuKit.ui(sub), c[2] - 30);
		MenuKit.text(context, tr, t, cx - MenuKit.width(tr, t) / 2, cy - 4, (alpha << 24) | 0x8E8384);
		if (s == CefRuntime.State.DOWNLOADING) {
			int bw = Math.min(220, c[2] - 60);
			VeloDraw.fillRounded(context, cx - bw / 2, cy + 12, bw, 4, 2, (alpha * 0x22 / 255 << 24) | 0xFFFFFF);
			VeloDraw.fillRounded(context, cx - bw / 2, cy + 12, Math.max(4, (int) (bw * Math.min(1, Math.max(0, CefRuntime.progress())))), 4, 2,
					(alpha << 24) | (MenuKit.accent() & 0xFFFFFF));
		} else if (s == CefRuntime.State.FAILED) {
			retryRect = new int[] {cx - 34, cy + 10, 68, 16};
			boolean hover = MenuKit.inside(mouseX, mouseY, cx - 34, cy + 10, 68, 16);
			VeloDraw.fillRounded(context, cx - 34, cy + 10, 68, 16, 8, hover ? MenuKit.accent() : (alpha << 24) | (MenuKit.accent() & 0xFFFFFF));
			Text retry = MenuKit.uiBold("Try again");
			MenuKit.text(context, tr, retry, cx - MenuKit.width(tr, retry) / 2, cy + 14, 0xFFFFFFFF);
			// the technical reason (step + exception), small and dim - what to send us when it keeps failing
			String detail = CefRuntime.errorDetail();
			if (detail != null && !detail.isEmpty()) {
				Text d = MenuKit.fit(tr, MenuKit.ui(detail), c[2] - 30);
				MenuKit.text(context, tr, d, cx - MenuKit.width(tr, d) / 2, cy + 34, (alpha << 24) | 0x5E5658);
			}
		}
	}

	private static int[] retryRect;

	private static void drawError(DrawContext context, TextRenderer tr, int[] c, String error, int alpha) {
		int cx = c[0] + c[2] / 2;
		int cy = c[1] + c[3] / 2;
		context.fill(c[0], c[1], c[0] + c[2], c[1] + c[3], (alpha * 0xE0 / 255 << 24) | 0x0C0A0A);
		Text h = MenuKit.uiBold("This page couldn't load");
		MenuKit.text(context, tr, h, cx - MenuKit.width(tr, h) / 2, cy - 10, (alpha << 24) | 0xEDE6E6);
		Text t = MenuKit.fit(tr, MenuKit.ui(error), c[2] - 30);
		MenuKit.text(context, tr, t, cx - MenuKit.width(tr, t) / 2, cy + 4, (alpha << 24) | 0x8E8384);
	}

	/** The corner window (screen closed, picture-in-picture). */
	public static void renderPip(DrawContext context, int sw, int sh) {
		CefRuntime.pump();
		if (view == null || view.closed() || hidden || away != Away.PIP) {
			return;
		}
		MediaTextures.Tex tex = view.texture();
		if (tex == null) {
			return;
		}
		int[] p = pipRect(sw, sh, tex.width(), tex.height());
		int a = Math.round(255 * pipOpacity);
		VeloDraw.shadow(context, p[0], p[1], p[2], p[3], 6, 12, 4, (a * 0x70 / 255) << 24);
		context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, tex.id(), p[0], p[1], 0f, 0f, p[2], p[3],
				tex.width(), tex.height(), tex.width(), tex.height(), (a << 24) | 0xFFFFFF);
		VeloDraw.strokeRounded(context, p[0], p[1], p[2], p[3], 6, (a * 0x30 / 255 << 24) | 0xFFFFFF);
	}

	// ------------------------------------------------------------------ input (GUI coordinates)

	/** @return true if the click was the window's */
	static boolean mouseClicked(double mx, double my, int button, int sw, int sh) {
		int[] r = rect(sw, sh);
		if (!MenuKit.inside((int) mx, (int) my, r[0], r[1], r[2], r[3])) {
			addressFocused = false;
			return false;
		}
		if (retryRect != null && CefRuntime.state() == CefRuntime.State.FAILED
				&& MenuKit.inside((int) mx, (int) my, retryRect[0], retryRect[1], retryRect[2], retryRect[3])) {
			CefRuntime.retry();
			return true;
		}
		// edges: resize
		int edge = edges(mx, my, r);
		if (edge != 0 && button == 0) {
			drag = edge;
			startDrag(mx, my);
			return true;
		}
		if (my < r[1] + BAR) {
			addressFocused = false;
			int x = r[0] + 6;
			for (int i = 0; i < BUTTONS.length; i++) {
				if (MenuKit.inside((int) mx, (int) my, x, r[1] + 3, 16, 16)) {
					barButton(i);
					return true;
				}
				x += 18;
			}
			int rx = r[0] + r[2] - 6 - RIGHT.length * 18 + 2;
			for (int i = 0; i < RIGHT.length; i++) {
				if (MenuKit.inside((int) mx, (int) my, rx + i * 18, r[1] + 3, 16, 16)) {
					rightButton(i);
					return true;
				}
			}
			if (goRect != null && MenuKit.inside((int) mx, (int) my, goRect[0], goRect[1], goRect[2], goRect[3])) {
				BrowserScreen.submitTyped();
				return true;
			}
			if (addressRect != null && MenuKit.inside((int) mx, (int) my, addressRect[0], addressRect[1], addressRect[2], addressRect[3])) {
				addressFocused = true;
				BrowserScreen.focusAddress(view == null || StartPage.isStartPage(view.url()) ? "" : view.url());
				return true;
			}
			if (button == 0 && !fullscreen) {
				drag = 1;
				startDrag(mx, my);
			}
			return true;
		}
		addressFocused = false;
		BrowserScreen.unfocusAddress();
		if (view != null) {
			long now = System.currentTimeMillis();
			clicks = now - lastClickAt < 350 ? clicks + 1 : 1;
			lastClickAt = now;
			int[] p = toPage(mx, my, sw, sh);
			view.mouseButton(p[0], p[1], button, true, clicks);
			contentPressed = true;
		}
		return true;
	}

	static boolean mouseReleased(double mx, double my, int button, int sw, int sh) {
		if (drag != 0) {
			drag = 0;
			BrowserModule.saveSoon();
			return true;
		}
		if (contentPressed && view != null) {
			contentPressed = false;
			int[] p = toPage(mx, my, sw, sh);
			view.mouseButton(p[0], p[1], button, false, clicks);
			return true;
		}
		return false;
	}

	static boolean mouseDragged(double mx, double my, int sw, int sh) {
		if (drag == 1) {
			fx = clamp(dragStart[0] + (float) ((mx - dragX) / sw), 0f, 1f - fw);
			fy = clamp(dragStart[1] + (float) ((my - dragY) / sh), 0f, 1f - fh);
			return true;
		}
		if (drag > 1) {
			float dx = (float) ((mx - dragX) / sw);
			float dy = (float) ((my - dragY) / sh);
			float minW = Math.min(1f, MIN_W / (float) sw);
			float minH = Math.min(1f, MIN_H / (float) sh);
			float x = dragStart[0];
			float y = dragStart[1];
			float w = dragStart[2];
			float h = dragStart[3];
			if ((drag & 2) != 0) {
				float nx = clamp(x + dx, 0f, x + w - minW);
				w += x - nx;
				x = nx;
			}
			if ((drag & 4) != 0) {
				w = clamp(w + dx, minW, 1f - x);
			}
			if ((drag & 8) != 0) {
				float ny = clamp(y + dy, 0f, y + h - minH);
				h += y - ny;
				y = ny;
			}
			if ((drag & 16) != 0) {
				h = clamp(h + dy, minH, 1f - y);
			}
			fx = x;
			fy = y;
			fw = w;
			fh = h;
			return true;
		}
		if (contentPressed && view != null) {
			int[] p = toPage(mx, my, sw, sh);
			view.mouseMove(p[0], p[1]);
			return true;
		}
		return false;
	}

	static void mouseMoved(double mx, double my, int sw, int sh) {
		if (view == null || drag != 0) {
			return;
		}
		int[] c = content(rect(sw, sh));
		if (MenuKit.inside((int) mx, (int) my, c[0], c[1], c[2], c[3])) {
			int[] p = toPage(mx, my, sw, sh);
			view.mouseMove(p[0], p[1]);
		}
	}

	static boolean mouseScrolled(double mx, double my, double amount, int sw, int sh) {
		int[] c = content(rect(sw, sh));
		if (view == null || !MenuKit.inside((int) mx, (int) my, c[0], c[1], c[2], c[3])) {
			return false;
		}
		int[] p = toPage(mx, my, sw, sh);
		view.wheel(p[0], p[1], amount * 3);
		return true;
	}

	private static int[] toPage(double mx, double my, int sw, int sh) {
		int[] c = content(rect(sw, sh));
		double kx = view.width() / (double) Math.max(1, c[2]);
		double ky = view.height() / (double) Math.max(1, c[3]);
		return new int[] {(int) Math.round((mx - c[0]) * kx), (int) Math.round((my - c[1]) * ky)};
	}

	private static int edges(double mx, double my, int[] r) {
		if (fullscreen) {
			return 0;
		}
		int e = 0;
		if (mx < r[0] + GRIP) {
			e |= 2;
		}
		if (mx >= r[0] + r[2] - GRIP) {
			e |= 4;
		}
		if (my < r[1] + 3) {
			e |= 8;
		}
		if (my >= r[1] + r[3] - GRIP) {
			e |= 16;
		}
		// the bottom-right grip is bigger
		if (mx >= r[0] + r[2] - 10 && my >= r[1] + r[3] - 10) {
			e = 4 | 16;
		}
		return e;
	}

	private static void startDrag(double mx, double my) {
		dragX = mx;
		dragY = my;
		dragStart = new float[] {fx, fy, fw, fh};
	}

	private static void barButton(int i) {
		if (view == null) {
			return;
		}
		switch (i) {
			case 0 -> view.back();
			case 1 -> view.forward();
			case 2 -> view.reload();
			default -> view.load(home());
		}
	}

	private static void rightButton(int i) {
		switch (i) {
			case 0 -> {
				// hide: the page keeps playing, only the sound remains
				hidden = true;
				animateClose(() -> MinecraftClient.getInstance().setScreen(null));
				BrowserModule.toast("Browser hidden - it keeps playing. " + BrowserModule.keyName() + " shows it again.");
			}
			case 1 -> {
				fullscreen = !fullscreen;
				BrowserModule.saveSoon();
			}
			default -> animateClose(() -> {
				closePage();
				MinecraftClient.getInstance().setScreen(null);
			});
		}
	}

	/** Navigates to what was typed into the address field. */
	static void submitAddress(String typed) {
		String target = StartPage.resolve(typed);
		addressFocused = false;
		if (target != null && view != null) {
			view.load(target);
		}
	}

	/** Closes the page and forgets it (and with it every cookie and login of this session's tabs). */
	public static void closePage() {
		if (view != null) {
			view.dispose();
			view = null;
		}
		hidden = false;
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(Math.max(min, max), v));
	}

	// ------------------------------------------------------------------ settings

	static String save() {
		JsonObject o = new JsonObject();
		o.addProperty("x", fx);
		o.addProperty("y", fy);
		o.addProperty("w", fw);
		o.addProperty("h", fh);
		o.addProperty("fullscreen", fullscreen);
		o.addProperty("away", away.name());
		o.addProperty("pipOpacity", pipOpacity);
		o.addProperty("background", background);
		if (picture != null) {
			o.addProperty("picture", picture.toString());
		}
		JsonArray list = new JsonArray();
		for (StartPage.Shortcut s : SHORTCUTS) {
			JsonObject e = new JsonObject();
			e.addProperty("name", s.name());
			e.addProperty("url", s.url());
			e.addProperty("color", s.color());
			list.add(e);
		}
		o.add("shortcuts", list);
		return o.toString();
	}

	static void load(String json) {
		try {
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			fx = o.has("x") ? o.get("x").getAsFloat() : fx;
			fy = o.has("y") ? o.get("y").getAsFloat() : fy;
			fw = o.has("w") ? o.get("w").getAsFloat() : fw;
			fh = o.has("h") ? o.get("h").getAsFloat() : fh;
			fullscreen = o.has("fullscreen") && o.get("fullscreen").getAsBoolean();
			away = o.has("away") ? Away.valueOf(o.get("away").getAsString()) : away;
			pipOpacity = o.has("pipOpacity") ? o.get("pipOpacity").getAsFloat() : pipOpacity;
			background = o.has("background") ? o.get("background").getAsString() : background;
			picture = o.has("picture") ? Path.of(o.get("picture").getAsString()) : null;
			if (o.has("shortcuts")) {
				SHORTCUTS.clear();
				for (var e : o.getAsJsonArray("shortcuts")) {
					JsonObject s = e.getAsJsonObject();
					SHORTCUTS.add(new StartPage.Shortcut(s.get("name").getAsString(), s.get("url").getAsString(), s.get("color").getAsString()));
				}
			}
		} catch (Exception ignored) {
			// defaults
		}
	}
}
