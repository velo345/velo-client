package net.veloclient.velo.client.browser;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.media.MediaTextures;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.browser.CefFrame;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * One Velo Browser page, drawn off-screen by Chromium into a texture the game shows anywhere (the browser window, the
 * hidden audio mode, a World Note canvas). Chromium paints on the render thread while {@link CefRuntime#pump} runs;
 * the pixels are converted (BGRA to RGBA) right there and uploaded once per frame in {@link #texture}.
 */
public final class BrowserView {

	private static final List<BrowserView> ALL = new ArrayList<>();

	private final Page page;
	private final org.cef.CefClient client;
	private int width;
	private int height;
	/** RGBA, width x height - written by onPaint, read by texture(). */
	private ByteBuffer pixels;
	private int pixelsW;
	private int pixelsH;
	private boolean dirty;
	/** When the page was last drawn on screen; frames nobody sees (hidden, audio only) aren't converted. */
	private volatile long lastSeen = System.nanoTime();
	private volatile boolean stale;
	private Rectangle popup;
	private boolean popupShown;

	private NativeImage image;
	private Identifier texId;
	private NativeImageBackedTexture texture;

	private volatile String url = "";
	private volatile String title = "";
	private volatile boolean loading;
	private volatile boolean canBack;
	private volatile boolean canForward;
	private volatile String loadError;
	private boolean closed;
	private int buttons;

	/** Chromium's off-screen browser, drawing into this view. */
	private final class Page extends CefBrowserOsr {
		Page(org.cef.CefClient client, String url) {
			super(client, url, false, null);
		}

		void resize(int w, int h) {
			browser_rect_.setBounds(0, 0, w, h);
			wasResized(w, h);
		}

		@Override
		public void onPopupShow(CefBrowser browser, boolean show) {
			popupShown = show;
			if (!show) {
				popup = null;
				invalidate();
			}
		}

		@Override
		public void onPopupSize(CefBrowser browser, Rectangle size) {
			popup = new Rectangle(size);
		}

		@Override
		public void onPaint(CefBrowser browser, boolean isPopup, Rectangle[] dirtyRects, ByteBuffer buffer, int w, int h) {
			paint(isPopup, buffer, w, h);
		}

		void mouse(CefMouseEvent e) {
			sendMouseEvent(e);
		}

		void wheel(CefMouseWheelEvent e) {
			sendMouseWheelEvent(e);
		}

		void key(CefKeyEvent e) {
			sendKeyEvent(e);
		}

		void repaint() {
			invalidate();
		}
	}

	private BrowserView(String startUrl, int w, int h) {
		this.width = Math.max(64, w);
		this.height = Math.max(64, h);
		this.url = startUrl;
		client = CefRuntime.app().createClient();
		client.addDisplayHandler(new CefDisplayHandlerAdapter() {
			@Override
			public void onAddressChange(CefBrowser browser, CefFrame frame, String address) {
				if (frame == null || frame.isMain()) {
					url = address;
				}
			}

			@Override
			public void onTitleChange(CefBrowser browser, String t) {
				title = t == null ? "" : t;
			}
		});
		client.addLoadHandler(new CefLoadHandlerAdapter() {
			@Override
			public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean back, boolean forward) {
				loading = isLoading;
				canBack = back;
				canForward = forward;
				if (isLoading) {
					loadError = null;
				}
			}

			@Override
			public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
				if (frame != null && frame.isMain() && errorCode != ErrorCode.ERR_ABORTED) {
					loadError = errorText == null || errorText.isBlank() ? String.valueOf(errorCode) : errorText;
				}
			}
		});
		client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
			@Override
			public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String targetUrl, String targetFrameName) {
				// links that open a new window open here (one window, like a phone browser)
				if (targetUrl != null && !targetUrl.isBlank()) {
					browser.loadURL(targetUrl);
				}
				return true;
			}
		});
		page = new Page(client, startUrl);
		page.resize(width, height);
		page.createImmediately();
		page.setFocus(true);
	}

	/** A new page at {@code url}, {@code w x h} real pixels; null while Chromium isn't ready yet. */
	public static BrowserView open(String url, int w, int h) {
		if (!CefRuntime.ensure()) {
			return null;
		}
		BrowserView view = new BrowserView(url, w, h);
		ALL.add(view);
		return view;
	}

	// ------------------------------------------------------------------ pixels

	private void paint(boolean isPopup, ByteBuffer bgra, int w, int h) {
		if (closed) {
			return;
		}
		if (!seenRecently()) {
			stale = true;
			return;
		}
		if (!isPopup) {
			if (pixels == null || pixelsW != w || pixelsH != h) {
				pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
				pixelsW = w;
				pixelsH = h;
			}
			convert(bgra, w, h, pixels, 0, 0, w);
		} else if (popup != null && pixels != null && popupShown) {
			// a dropdown (<select>) is its own little buffer: drawn over the page at its place
			int px = Math.max(0, popup.x);
			int py = Math.max(0, popup.y);
			int cw = Math.min(w, pixelsW - px);
			int ch = Math.min(h, pixelsH - py);
			if (cw > 0 && ch > 0) {
				IntBuffer src = bgra.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
				IntBuffer dst = pixels.asIntBuffer();
				int[] row = new int[cw];
				for (int y = 0; y < ch; y++) {
					src.get(y * w, row, 0, cw);
					swap(row, cw);
					dst.put((py + y) * pixelsW + px, row, 0, cw);
				}
			}
		}
		dirty = true;
	}

	private static void convert(ByteBuffer bgra, int w, int h, ByteBuffer out, int ox, int oy, int outW) {
		IntBuffer src = bgra.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		IntBuffer dst = out.asIntBuffer();
		int[] row = new int[w];
		for (int y = 0; y < h; y++) {
			src.get(y * w, row, 0, w);
			swap(row, w);
			dst.put((oy + y) * outW + ox, row, 0, w);
		}
	}

	/** BGRA (as little-endian ints) to RGBA, fully opaque. */
	private static void swap(int[] row, int n) {
		for (int x = 0; x < n; x++) {
			int v = row[x];
			row[x] = 0xFF000000 | v & 0x0000FF00 | (v >>> 16) & 0xFF | (v & 0xFF) << 16;
		}
	}

	/** This frame's picture (render thread, once per frame), or null before the first one. */
	/** Visible within the last half second. */
	boolean seenRecently() {
		return System.nanoTime() - lastSeen < 500_000_000L;
	}

	public MediaTextures.Tex texture() {
		if (closed) {
			return null;
		}
		lastSeen = System.nanoTime();
		if (stale) {
			// frames were skipped while hidden: ask for a fresh one
			stale = false;
			page.repaint();
		}
		if (dirty && pixels != null) {
			dirty = false;
			if (image == null || image.getWidth() != pixelsW || image.getHeight() != pixelsH) {
				releaseTexture();
				image = new NativeImage(pixelsW, pixelsH, false);
				MediaTextures.Tex tex = MediaTextures.register(image, "velo browser");
				texId = tex.id();
				texture = (NativeImageBackedTexture) MinecraftClient.getInstance().getTextureManager().getTexture(texId);
			}
			//? if <26.1 {
			//? if <1.21.2 {
			/*long pointer = net.veloclient.velo.client.compat.ImageCompat.pointer(image);
			*///?} else {
			long pointer = image.imageId();
			//?}
			//?} else {
			/*long pointer = image.getPointer();
			*///?}
			int bytes = pixelsW * pixelsH * 4;
			org.lwjgl.system.MemoryUtil.memCopy(org.lwjgl.system.MemoryUtil.memAddress(pixels), pointer, bytes);
			texture.upload();
		}
		return texId == null ? null : new MediaTextures.Tex(texId, pixelsW, pixelsH);
	}

	private void releaseTexture() {
		if (texId != null) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(texId);
			texId = null;
			texture = null;
			image = null;
		}
	}

	// ------------------------------------------------------------------ size and input (real pixels inside the page)

	public void resize(int w, int h) {
		w = Math.max(64, Math.min(3840, w));
		h = Math.max(64, Math.min(2160, h));
		if (w != width || h != height) {
			width = w;
			height = h;
			page.resize(w, h);
		}
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public void mouseMove(int x, int y) {
		page.mouse(new CefMouseEvent(CefMouseEvent.MOUSE_MOVED, x, y, 0, 0, buttons | mods()));
	}

	public void mouseExit() {
		page.mouse(new CefMouseEvent(CefMouseEvent.MOUSE_EXIT, 0, 0, 0, 0, mods()));
	}

	/** {@code button}: 0 left, 1 right, 2 middle (vanilla's numbering before 26.3). */
	public void mouseButton(int x, int y, int button, boolean pressed, int clicks) {
		// Chromium's order: 0 left, 1 middle, 2 right
		int cef = button == 1 ? 2 : button == 2 ? 1 : button;
		int mask = cef == 0 ? CefMouseEvent.BUTTON1_MASK : cef == 1 ? CefMouseEvent.BUTTON2_MASK : CefMouseEvent.BUTTON3_MASK;
		buttons = pressed ? buttons | mask : buttons & ~mask;
		page.setFocus(true);
		page.mouse(new CefMouseEvent(pressed ? 1 : 0, x, y, Math.max(1, clicks), cef, buttons | mods()));
	}

	public void wheel(int x, int y, double amount) {
		page.wheel(new CefMouseWheelEvent(CefMouseWheelEvent.WHEEL_UNIT_SCROLL, x, y, amount, mods()));
	}

	/** A key went down / up - {@code glfwKey} in GLFW codes (see {@link #glfw}). */
	public void key(int glfwKey, boolean pressed) {
		CefKeyEvent e = new CefKeyEvent(pressed ? CefKeyEvent.KEY_PRESS : CefKeyEvent.KEY_RELEASE, glfwKey, (char) glfwKey, mods());
		e.scancode = net.veloclient.velo.client.util.WindowFront.scanCodeForGlfw(glfwKey);
		page.key(e);
		// Enter makes no typed character in the game, but pages submit forms (searches!) on the '\r' character
		if (pressed && (glfwKey == 257 || glfwKey == 335)) {
			CefKeyEvent ch = new CefKeyEvent(CefKeyEvent.KEY_TYPE, glfwKey, '\r', mods());
			ch.scancode = e.scancode;
			page.key(ch);
		}
	}

	public void typed(char c) {
		page.key(new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, mods()));
	}

	public void focus(boolean focused) {
		page.setFocus(focused);
	}

	/** The pressed modifier keys in GLFW's bits (shift 1, ctrl 2, alt 4, super 8) - read live, same on every version. */
	private static int mods() {
		int m = 0;
		if (net.veloclient.velo.client.util.Keys.isKeyDown(net.veloclient.velo.client.util.Keys.LEFT_SHIFT)
				|| net.veloclient.velo.client.util.Keys.isKeyDown(net.veloclient.velo.client.util.Keys.RIGHT_SHIFT)) {
			m |= 1;
		}
		if (net.veloclient.velo.client.util.Keys.isKeyDown(net.veloclient.velo.client.util.Keys.LEFT_CONTROL)
				|| net.veloclient.velo.client.util.Keys.isKeyDown(net.veloclient.velo.client.util.Keys.RIGHT_CONTROL)) {
			m |= 2;
		}
		// left Alt only: AltGr (right Alt) types @, { and } on many keyboards
		if (net.veloclient.velo.client.util.Keys.isKeyDown(net.veloclient.velo.client.util.Keys.LEFT_ALT)) {
			m |= 4;
		}
		return m;
	}

	/** This version's key code as a GLFW code (26.3 uses SDL scancodes; Chromium's side expects GLFW's). */
	public static int glfw(int key) {
		return net.veloclient.velo.client.util.Keys.toGlfw(key);
	}

	// ------------------------------------------------------------------ navigation

	public void load(String target) {
		loadError = null;
		page.loadURL(target);
	}

	public void back() {
		if (page.canGoBack()) {
			page.goBack();
		}
	}

	public void forward() {
		if (page.canGoForward()) {
			page.goForward();
		}
	}

	public void reload() {
		page.reload();
	}

	public void stop() {
		page.stopLoad();
	}

	public void zoom(double level) {
		page.setZoomLevel(level);
	}

	/** Runs JavaScript in the page (the start page's search box, pausing media). */
	public void js(String code) {
		page.executeJavaScript(code, page.getURL(), 0);
	}

	public String url() {
		return url;
	}

	public String title() {
		return title;
	}

	public boolean loading() {
		return loading;
	}

	public boolean canBack() {
		return canBack;
	}

	public boolean canForward() {
		return canForward;
	}

	public String loadError() {
		return loadError;
	}

	public boolean closed() {
		return closed;
	}

	public void dispose() {
		if (closed) {
			return;
		}
		closed = true;
		ALL.remove(this);
		try {
			page.close(true);
			client.dispose();
		} catch (Throwable ignored) {
			// closing anyway
		}
		releaseTexture();
	}

	/** Whether any page is open (the runtime pumps much less often without one). */
	static boolean anyOpen() {
		return !ALL.isEmpty();
	}

	/** Whether an open page is on screen right now (else it only plays sound). */
	static boolean anySeen() {
		for (BrowserView v : ALL) {
			if (v.seenRecently()) {
				return true;
			}
		}
		return false;
	}

	static void closeAll() {
		for (BrowserView view : new ArrayList<>(ALL)) {
			view.dispose();
		}
	}
}
