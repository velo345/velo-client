package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.NativeFileDialog;
import net.veloclient.velo.client.gui.VeloConfirmScreen;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.util.Keys;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The in-game texture editor: pencil, brush, eraser, bucket fill, gradient, line / rectangle / ellipse, colour
 * picker, shading and dither brushes; rectangle, ellipse, lasso and magic wand selections with add / subtract /
 * intersect, moving, copy & paste; filters (hue / saturation / brightness, colorize, replace colour, contrast,
 * posterize, noise, invert, grayscale, sepia, blur, outline, flip, rotate, offset) on the selection; animation
 * frames with timing, interpolation, onion skin and playback; a live 3D preview of the block / item / entity the
 * texture belongs to; undo / redo. Saves the PNG (and .mcmeta) straight into the pack.
 */
public final class TextureEditorScreen extends VeloWindow {

	private enum Tool {
		PENCIL("pencil", "Pencil", 'B', "Hard pixels. Shift+click draws a line from the last point."),
		BRUSH("brush", "Brush", 'N', "A round, semi-transparent brush for soft shading."),
		ERASER("eraser", "Eraser", 'E', "Erases to transparent (lower opacity erases partly)."),
		FILL("fill", "Fill", 'G', "Fills similar connected pixels (or every match with Contiguous off)."),
		GRADIENT("gradient", "Gradient", 'D', "Drag: primary to secondary colour over the selection."),
		LINE("line", "Line", 'L', "Drag a straight line."),
		RECT("rect", "Rectangle", 'U', "Drag a rectangle."),
		ELLIPSE("ellipse", "Ellipse", 'O', "Drag an ellipse."),
		PICKER("picker", "Color picker", 'I', "Left: primary colour, right: secondary. Alt+click works with any tool."),
		SHADE("shade", "Shade", 'S', "Left lightens, right darkens the pixels you paint over."),
		DITHER("dither", "Dither", 'K', "Paints a checker pattern - classic pixel-art blending."),
		SELECT_RECT("select", "Rectangle select", 'M', "Drag to select. Shift adds, Alt subtracts."),
		SELECT_ELLIPSE("select_ellipse", "Ellipse select", 'J', "Drag to select an ellipse. Shift adds, Alt subtracts."),
		LASSO("lasso", "Lasso", 'Q', "Draw around an area to select it."),
		WAND("wand", "Magic wand", 'W', "Selects similar colours. Shift adds, Alt subtracts."),
		MOVE("move", "Move", 'V', "Drag the selection (or everything). Arrow keys nudge, Enter drops it."),
		HAND("hand", "Hand", 'H', "Drag to pan. Middle mouse or Space+drag pan with any tool too.");

		final String glyph;
		final String label;
		final char key;
		final String hint;

		Tool(String glyph, String label, char key, String hint) {
			this.glyph = glyph;
			this.label = label;
			this.key = key;
			this.hint = hint;
		}

		boolean paints() {
			return this == PENCIL || this == BRUSH || this == ERASER || this == SHADE || this == DITHER;
		}

		boolean selects() {
			return this == SELECT_RECT || this == SELECT_ELLIPSE || this == LASSO || this == WAND;
		}

		boolean shapes() {
			return this == LINE || this == RECT || this == ELLIPSE || this == GRADIENT;
		}
	}

	/** Per-tool settings. */
	private static final class ToolSettings {
		int size = 1;
		float opacity = 1f;
		boolean round = true;
		float tolerance = 0.12f;
		boolean contiguous = true;
		boolean filled;
		int steps;
		int mode;
	}

	private enum Filter {
		HSB("Hue / Saturation"), COLORIZE("Colorize (GIMP)"), REPLACE("Replace color"), CONTRAST("Brightness / Contrast"), POSTERIZE("Posterize"),
		NOISE("Noise");

		final String label;

		Filter(String label) {
			this.label = label;
		}
	}

	private enum Drag { NONE, PAINT, SHAPE, SELECT, LASSO, MOVE, PAN, SV, HUE, ALPHA, SLIDER, PREVIEW, FRAME_SCROLL }

	private static final float[] ZOOMS = {0.25f, 0.5f, 0.75f, 1, 1.5f, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64};
	private static final int[] RECENT_DEFAULT = {0xFFFFFFFF, 0xFF000000, 0xFF7F7F7F, 0xFFE3262B, 0xFFFFD43B, 0xFF7ED957, 0xFF3D8BFF, 0xFF8B5A2B};
	/** Copy / paste between textures (and editor sessions). */
	private static int[] clipPx;
	private static boolean[] clipMask;
	private static int clipW;
	private static int clipH;
	private static int clipX;
	private static int clipY;
	private static final Deque<Integer> RECENTS = new ArrayDeque<>();

	private final PackBrowserScreen browser;
	private final Path packRoot;
	/** "ns:textures/..png" or null for the pack icon. */
	private final String key;
	private final Path file;
	private final String ref;
	private TexDoc doc;
	private String loadError;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private final Map<Tool, ToolSettings> settings = new EnumMap<>(Tool.class);
	private Tool tool = Tool.PENCIL;
	private int primary = 0xFFFFFFFF;
	private int secondary = 0xFF000000;
	private float[] hsv = {0, 0, 1};
	private boolean mirrorX;
	private boolean mirrorY;
	private boolean grid = true;
	private boolean tile;
	private boolean onion;
	private boolean allFrames;
	private String status = "";
	private long statusAt;
	private List<String> tooltip = List.of();

	// Layout (computed every frame).
	private int tbX;
	private int tbY;
	private int optX;
	private int optY;
	private int optW;
	private int vpX;
	private int vpY;
	private int vpW;
	private int vpH;
	private int tlX;
	private int tlY;
	private int tlW;
	private int tlH;
	private int rpX;
	private int rpY;
	private int rpW;
	private int rpH;

	// View.
	private float zoom = -1;
	private float viewX;
	private float viewY;
	private int hoverX = -1;
	private int hoverY = -1;

	// GPU images.
	private GpuImage canvasTex;
	private GpuImage checkerTex;
	private GpuImage svTex;
	private GpuImage hueTex;
	private GpuImage previewTex;
	private GpuImage stripTex;
	private int uploadedRevision = -1;
	private int uploadedFrame = -1;
	private boolean forceUpload = true;
	private float svHue = -1;
	private int stripRevision = -1;

	// Interaction.
	private Drag drag = Drag.NONE;
	private int dragButton;
	private int startX;
	private int startY;
	private int lastX;
	private int lastY;
	private int strokeEndX = -1;
	private int strokeEndY = -1;
	private boolean[] touched;
	private int curX;
	private int curY;
	private final List<Float> lassoPoints = new ArrayList<>();
	private boolean[] pendingMask;
	private int selectOp;
	private double panStartX;
	private double panStartY;
	private float panViewX;
	private float panViewY;
	private PackUi.SliderDrag slider;
	private float frameScroll;

	// Floating pixels (move / paste).
	private int[] floatPx;
	private boolean[] floatMask;
	private int floatDx;
	private int floatDy;

	// Selection outline cache.
	private int[] edges = new int[0];
	private int edgeCount;
	private boolean[] edgesFor;
	private int edgesRevision = -1;

	// Filters.
	private Filter filter;
	private float fa;
	private float fb;
	private float fc;
	private long noiseSeed = 1;
	private int[] filterPreview;
	private boolean filterDirty;

	// Right panel.
	private int rightTab;
	private TextFieldWidget hexField;
	private boolean settingHex;
	private int[] texturePalette = new int[0];
	private int paletteRevision = -1;

	// Preview.
	private PreviewModels.Preview preview;
	private int previewIndex;
	private boolean previewBuilt;
	private int previewRevision = -1;
	private float yaw = 35;
	private float pitch = 28;
	private float previewZoom = 1f;
	private boolean autoRotate = true;
	private boolean playing;
	private double ticks;
	private long lastFrameNanos;
	private int[] previewPx;
	private float lastRenderYaw = Float.NaN;
	private int previewSize;
	private long lastPreviewBuild;

	private boolean finished;
	private boolean discardConfirmed;

	public TextureEditorScreen(PackBrowserScreen browser, Path packRoot, String key, Path file) {
		super(Text.literal(key == null ? "Pack icon" : PackContents.displayName(key.substring(key.indexOf(':') + 1))), 1000, 600);
		this.browser = browser;
		this.packRoot = packRoot;
		this.key = key;
		this.file = file;
		this.ref = key == null ? null : PreviewModels.textureRef(key);
		returnTo(browser);
		for (Tool t : Tool.values()) {
			settings.put(t, new ToolSettings());
		}
		settings.get(Tool.BRUSH).size = 3;
		settings.get(Tool.BRUSH).opacity = 0.55f;
		settings.get(Tool.SHADE).size = 2;
		settings.get(Tool.SHADE).opacity = 0.5f;
		settings.get(Tool.DITHER).size = 3;
		settings.get(Tool.FILL).tolerance = 0f;
		settings.get(Tool.WAND).tolerance = 0.08f;
		settings.get(Tool.ERASER).round = false;
		settings.get(Tool.PENCIL).round = false;
		if (RECENTS.isEmpty()) {
			for (int c : RECENT_DEFAULT) {
				RECENTS.add(c);
			}
		}
		try {
			doc = TexDoc.read(file);
		} catch (Exception e) {
			loadError = "Couldn't open this texture: " + e.getMessage();
			doc = TexDoc.blank(16, 16);
		}
		hsv = PixelOps.hsv(primary);
	}

	// ---- Layout ----

	private void computeLayout() {
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		int bottom = contentBottom();
		int tbW = 50;
		rpW = w >= 720 ? 188 : 160;
		tbX = x;
		tbY = y + 28;
		optX = x + tbW + 8;
		optY = y;
		optW = w - tbW - 8 - rpW - 12;
		vpX = optX;
		vpY = y + 28;
		vpW = optW;
		tlH = 52;
		tlX = vpX;
		tlW = vpW;
		tlY = bottom - tlH;
		vpH = tlY - 6 - vpY;
		rpX = x + w - rpW;
		rpY = y;
		rpH = bottom - y;
	}

	@Override
	protected void layoutContent() {
		computeLayout();
		this.clearChildren();
		hexField = new TextFieldWidget(this.textRenderer, rpX + 44, rpY + 7, rpW - 50, 12, Text.literal("Hex"));
		hexField.setDrawsBackground(false);
		hexField.setMaxLength(9);
		hexField.setChangedListener(v -> {
			if (settingHex) {
				return;
			}
			Integer parsed = parseHex(v);
			if (parsed != null) {
				primary = parsed;
				hsv = PixelOps.hsv(primary);
				filterDirty = true;
			}
		});
		addDrawableChild(hexField);
		syncHex();
		if (zoom < 0) {
			fitView();
		}
	}

	private void syncHex() {
		if (hexField != null && !hexField.isFocused()) {
			settingHex = true;
			hexField.setText(String.format(Locale.ROOT, "#%06X%s", primary & 0xFFFFFF, (primary >>> 24) == 255 ? "" : String.format(Locale.ROOT, "%02X", primary >>> 24)));
			settingHex = false;
		}
	}

	private static Integer parseHex(String v) {
		String s = v.trim().replace("#", "");
		try {
			if (s.length() == 6) {
				return 0xFF000000 | Integer.parseUnsignedInt(s, 16);
			}
			if (s.length() == 8) {
				int rgb = Integer.parseUnsignedInt(s.substring(0, 6), 16);
				int a = Integer.parseUnsignedInt(s.substring(6), 16);
				return a << 24 | rgb;
			}
		} catch (NumberFormatException ignored) {
			// Not a colour yet.
		}
		return null;
	}

	private void fitView() {
		if (vpW <= 0) {
			return;
		}
		float best = ZOOMS[0];
		for (float z : ZOOMS) {
			if (doc.width * z <= vpW * 0.86f && doc.height * z <= vpH * 0.86f) {
				best = z;
			}
		}
		zoom = best;
		viewX = vpX + (vpW - doc.width * zoom) / 2f;
		viewY = vpY + (vpH - doc.height * zoom) / 2f;
	}

	private void zoomAt(double mouseX, double mouseY, int steps) {
		int i = 0;
		for (int k = 0; k < ZOOMS.length; k++) {
			if (Math.abs(ZOOMS[k] - zoom) < 1e-3f) {
				i = k;
			}
		}
		int ni = Math.max(0, Math.min(ZOOMS.length - 1, i + steps));
		float nz = ZOOMS[ni];
		float px = (float) (mouseX - viewX) / zoom;
		float py = (float) (mouseY - viewY) / zoom;
		zoom = nz;
		viewX = (float) mouseX - px * zoom;
		viewY = (float) mouseY - py * zoom;
		viewX = Math.round(viewX);
		viewY = Math.round(viewY);
	}

	private void setStatus(String s) {
		status = s;
		statusAt = System.currentTimeMillis();
	}

	private ToolSettings ts() {
		return settings.get(tool);
	}

	// ---- Rendering ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		PackUi.frame();
		computeLayout();
		hits.clear();
		tooltip = List.of();
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0 : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
		lastFrameNanos = now;
		if (playing) {
			ticks += dt * 20;
		}
		if (autoRotate && drag != Drag.PREVIEW) {
			yaw += dt * 22;
		}
		ensureTextures();
		drawToolbar(context, mouseX, mouseY);
		drawOptions(context, mouseX, mouseY);
		drawViewport(context, mouseX, mouseY);
		drawTimeline(context, mouseX, mouseY);
		drawRightPanel(context, mouseX, mouseY);
		if (loadError != null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(loadError), vpX + 8, vpY + 8, MenuKit.RED_TEXT);
		}
	}

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		if (!tooltip.isEmpty() && drag == Drag.NONE) {
			PackUi.tooltip(context, tooltip, mouseX, mouseY, this.width, this.height);
		}
	}

	private void ensureTextures() {
		if (canvasTex == null || canvasTex.width != doc.width || canvasTex.height != doc.height) {
			if (canvasTex != null) {
				canvasTex.release();
				checkerTex.release();
			}
			canvasTex = new GpuImage("texture editor canvas", doc.width, doc.height);
			checkerTex = new GpuImage("texture editor checker", doc.width, doc.height);
			int[] checker = new int[doc.width * doc.height];
			int cell = Math.max(1, Math.max(doc.width, doc.height) / 16);
			for (int y = 0; y < doc.height; y++) {
				for (int x = 0; x < doc.width; x++) {
					checker[y * doc.width + x] = ((x / cell + y / cell) % 2 == 0) ? 0xFF3A3A42 : 0xFF2C2C33;
				}
			}
			checkerTex.upload(checker);
			forceUpload = true;
		}
		if (hueTex == null) {
			hueTex = new GpuImage("hue bar", 1, 96);
			int[] hue = new int[96];
			for (int i = 0; i < 96; i++) {
				hue[i] = PixelOps.fromHsv(i * 360f / 95f, 1, 1, 255);
			}
			hueTex.upload(hue);
		}
		if (svTex == null) {
			svTex = new GpuImage("saturation value", 48, 48);
		}
		if (svHue != hsv[0]) {
			svHue = hsv[0];
			int[] sv = new int[48 * 48];
			for (int y = 0; y < 48; y++) {
				for (int x = 0; x < 48; x++) {
					sv[y * 48 + x] = PixelOps.fromHsv(svHue, x / 47f, 1 - y / 47f, 255);
				}
			}
			svTex.upload(sv);
		}
		boolean dynamic = drag == Drag.SHAPE || drag == Drag.MOVE || floatPx != null || filterPreview != null || filterDirty || onion;
		if (forceUpload || dynamic || uploadedRevision != doc.revision || uploadedFrame != doc.current) {
			if (filterDirty) {
				filterDirty = false;
				computeFilterPreview();
			}
			canvasTex.upload(displayPixels());
			uploadedRevision = doc.revision;
			uploadedFrame = doc.current;
			forceUpload = false;
		}
	}

	/** What the canvas shows: the frame plus live previews (filter, shape being dragged, floating pixels, onion skin). */
	private int[] displayPixels() {
		int[] base = filterPreview != null ? filterPreview : doc.frame();
		boolean copy = drag == Drag.SHAPE || floatPx != null || (onion && doc.frames.size() > 1);
		if (!copy) {
			return base;
		}
		int w = doc.width;
		int h = doc.height;
		int[] out = base.clone();
		if (onion && doc.frames.size() > 1) {
			int[] prev = doc.frames.get(Math.floorMod(doc.current - 1, doc.frames.size()));
			for (int i = 0; i < out.length; i++) {
				int ghost = prev[i];
				if ((ghost >>> 24) > 0) {
					int tinted = PixelOps.lerp(ghost, 0xFFFF5A5A, 0.35f);
					int faded = (tinted & 0xFFFFFF) | (Math.round((ghost >>> 24) * 0.32f) << 24);
					out[i] = PixelOps.over(faded, out[i], 1f);
				}
			}
		}
		if (drag == Drag.SHAPE) {
			applyShape(out, startX, startY, curX, curY);
		}
		if (floatPx != null) {
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = y * w + x;
					if (!floatMask[i]) {
						continue;
					}
					int tx = x + floatDx;
					int ty = y + floatDy;
					if (tx >= 0 && ty >= 0 && tx < w && ty < h) {
						out[ty * w + tx] = PixelOps.over(out[ty * w + tx], floatPx[i], 1f);
					}
				}
			}
		}
		return out;
	}

	private void drawToolbar(DrawContext context, int mouseX, int mouseY) {
		int size = 22;
		Tool[] tools = Tool.values();
		VeloDraw.fillRounded(context, tbX - 2, tbY - 2, 54, ((tools.length + 1) / 2) * 24 + 4, 8, 0x0CFFFFFF);
		for (int i = 0; i < tools.length; i++) {
			Tool t = tools[i];
			int x = tbX + (i % 2) * 24;
			int y = tbY + (i / 2) * 24;
			hits.add(PackUi.iconButton(context, x, y, size, t.glyph, tool == t, mouseX, mouseY, () -> selectTool(t)));
			if (VeloUi.inside(mouseX, mouseY, x, y, size, size)) {
				tooltip = List.of(t.label + "  (" + t.key + ")", t.hint);
			}
		}
		int y = tbY + ((tools.length + 1) / 2) * 24 + 10;
		// Colour swatches under the tools: primary over secondary, click to swap.
		VeloDraw.fillRounded(context, tbX + 16, y + 14, 26, 26, 5, 0xFF2C2C33);
		swatch(context, tbX + 16, y + 14, 26, secondary);
		VeloDraw.fillRounded(context, tbX + 2, y, 28, 28, 5, 0xFF2C2C33);
		swatch(context, tbX + 2, y, 28, primary);
		VeloDraw.strokeRounded(context, tbX + 2, y, 28, 28, 5, 0xC0FFFFFF);
		hits.add(new VeloUi.Hit(tbX, y, 50, 42, this::swapColors));
		if (VeloUi.inside(mouseX, mouseY, tbX, y, 50, 42)) {
			tooltip = List.of("Primary / secondary colour", "Click or press X to swap");
		}
	}

	private void swatch(DrawContext context, int x, int y, int s, int c) {
		int half = s / 2;
		context.fill(x + 2, y + 2, x + half, y + half, 0xFF55555E);
		context.fill(x + half, y + half, x + s - 2, y + s - 2, 0xFF55555E);
		context.fill(x + 2, y + half, x + half, y + s - 2, 0xFF3A3A42);
		context.fill(x + half, y + 2, x + s - 2, y + half, 0xFF3A3A42);
		VeloDraw.fillRounded(context, x + 1, y + 1, s - 2, s - 2, 4, c);
	}

	private void selectTool(Tool t) {
		if (tool == Tool.MOVE && t != Tool.MOVE) {
			dropFloating();
		}
		tool = t;
	}

	private void swapColors() {
		int t = primary;
		primary = secondary;
		secondary = t;
		hsv = PixelOps.hsv(primary);
		syncHex();
		filterDirty = true;
	}

	private void drawOptions(DrawContext context, int mouseX, int mouseY) {
		int x = optX;
		int y = optY;
		VeloDraw.fillRounded(context, optX - 2, optY - 1, optW + 4, 24, 7, 0x0CFFFFFF);
		x += 6;
		ToolSettings s = ts();
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold(tool.label), x, y + 7, MenuKit.TEXT);
		x += this.textRenderer.getWidth(MenuKit.uiBold(tool.label)) + 12;
		int right = optX + optW - 4;
		// Right side: view toggles + undo / redo.
		int rx = right - 18;
		hits.add(PackUi.iconButton(context, rx, y + 2, 18, "redo", false, mouseX, mouseY, this::redo));
		rx -= 20;
		hits.add(PackUi.iconButton(context, rx, y + 2, 18, "undo", false, mouseX, mouseY, this::undo));
		if (VeloUi.inside(mouseX, mouseY, rx, y + 2, 38, 18)) {
			tooltip = mouseX < rx + 19 ? List.of("Undo (Ctrl+Z)", doc.canUndo() ? doc.undoCount() + " steps back" : "Nothing to undo")
					: List.of("Redo (Ctrl+Y / Ctrl+Shift+Z)", doc.canRedo() ? doc.redoCount() + " steps forward" : "Nothing to redo");
		}
		rx -= 26;
		hits.add(PackUi.iconButton(context, rx, y + 2, 18, "tile", tile, mouseX, mouseY, () -> tile = !tile));
		if (VeloUi.inside(mouseX, mouseY, rx, y + 2, 18, 18)) {
			tooltip = List.of("Tile preview", "Shows the texture repeated to spot seams");
		}
		rx -= 20;
		hits.add(PackUi.iconButton(context, rx, y + 2, 18, "grid", grid, mouseX, mouseY, () -> grid = !grid));
		rx -= 52;
		String zoomLabel = Math.round(zoom * 100) + "%";
		hits.add(PackUi.button(context, rx, y + 2, 48, 18, zoomLabel, 0, true, mouseX, mouseY, this::fitView));
		if (VeloUi.inside(mouseX, mouseY, rx, y + 2, 48, 18)) {
			tooltip = List.of("Zoom", "Mouse wheel zooms, click fits the canvas");
		}
		int limit = rx - 8;
		if (tool.paints() || tool == Tool.LINE || tool == Tool.RECT || tool == Tool.ELLIPSE) {
			x = stepper(context, x, y, "Size", s.size + " px", () -> s.size = Math.max(1, s.size - 1), () -> s.size = Math.min(64, s.size + 1), mouseX, mouseY);
			if (tool.paints() && s.size > 1) {
				x = toggle(context, x, y, s.round ? "Round" : "Square", s.round, () -> s.round = !s.round, mouseX, mouseY);
			}
		}
		if (tool == Tool.RECT || tool == Tool.ELLIPSE) {
			x = toggle(context, x, y, "Filled", s.filled, () -> s.filled = !s.filled, mouseX, mouseY);
		}
		if (tool.paints() || tool == Tool.FILL) {
			x = inlineSlider(context, x, y, limit, "opacity", "Opacity", Math.round(s.opacity * 100) + "%", s.opacity, 0.05, 1, v -> s.opacity = (float) v,
					mouseX, mouseY);
		}
		if (tool == Tool.FILL || tool == Tool.WAND) {
			x = inlineSlider(context, x, y, limit, "tolerance", "Tolerance", Math.round(s.tolerance * 100) + "%", s.tolerance, 0, 1,
					v -> s.tolerance = (float) v, mouseX, mouseY);
			x = toggle(context, x, y, "Contiguous", s.contiguous, () -> s.contiguous = !s.contiguous, mouseX, mouseY);
		}
		if (tool == Tool.GRADIENT) {
			x = inlineSlider(context, x, y, limit, "steps", "Steps", s.steps <= 1 ? "Smooth" : String.valueOf(s.steps), s.steps, 0, 16,
					v -> s.steps = (int) Math.round(v), mouseX, mouseY);
		}
		if (tool.paints() || tool == Tool.LINE || tool == Tool.RECT || tool == Tool.ELLIPSE || tool == Tool.FILL) {
			x = toggle(context, x, y, "Mirror X", mirrorX, () -> mirrorX = !mirrorX, mouseX, mouseY);
			x = toggle(context, x, y, "Mirror Y", mirrorY, () -> mirrorY = !mirrorY, mouseX, mouseY);
		}
		if (tool.selects()) {
			String[] modes = {"New", "Add", "Subtract", "Intersect"};
			for (int i = 0; i < modes.length; i++) {
				int mode = i;
				int w = this.textRenderer.getWidth(MenuKit.ui(modes[i])) + 14;
				if (x + w > limit) {
					break;
				}
				hits.add(PackUi.button(context, x, y + 2, w, 18, modes[i], s.mode == i ? 1 : 0, true, mouseX, mouseY, () -> s.mode = mode));
				x += w + 3;
			}
			x += 6;
			x = smallButton(context, x, y, limit, "All", this::selectAll, mouseX, mouseY);
			x = smallButton(context, x, y, limit, "None", this::deselect, mouseX, mouseY);
			x = smallButton(context, x, y, limit, "Invert", this::invertSelection, mouseX, mouseY);
		}
		if (tool == Tool.MOVE && floatPx != null) {
			x = smallButton(context, x, y, limit, "Drop", this::dropFloating, mouseX, mouseY);
		}
		if (x < limit - 40 && (tool == Tool.PICKER || tool == Tool.MOVE || tool == Tool.HAND || tool == Tool.SHADE)) {
			MenuKit.text(context, this.textRenderer, MenuKit.small(VeloUi.trim(tool.hint, limit - x)), x, y + 8, MenuKit.TEXT_3);
		}
	}

	private int stepper(DrawContext context, int x, int y, String label, String value, Runnable minus, Runnable plus, int mouseX, int mouseY) {
		MenuKit.text(context, this.textRenderer, MenuKit.small(label), x, y + 8, MenuKit.TEXT_3);
		x += this.textRenderer.getWidth(MenuKit.small(label)) + 4;
		hits.add(PackUi.iconButton(context, x, y + 3, 16, "left", false, mouseX, mouseY, minus));
		Text v = MenuKit.ui(value);
		int vw = Math.max(30, this.textRenderer.getWidth(v) + 6);
		MenuKit.text(context, this.textRenderer, v, x + 18 + (vw - this.textRenderer.getWidth(v)) / 2, y + 7, MenuKit.TEXT);
		hits.add(PackUi.iconButton(context, x + 18 + vw, y + 3, 16, "right", false, mouseX, mouseY, plus));
		return x + 18 + vw + 16 + 10;
	}

	private int toggle(DrawContext context, int x, int y, String label, boolean on, Runnable action, int mouseX, int mouseY) {
		int w = this.textRenderer.getWidth(MenuKit.ui(label)) + 14;
		hits.add(PackUi.button(context, x, y + 2, w, 18, label, on ? 1 : 0, true, mouseX, mouseY, action));
		return x + w + 4;
	}

	private int smallButton(DrawContext context, int x, int y, int limit, String label, Runnable action, int mouseX, int mouseY) {
		int w = this.textRenderer.getWidth(MenuKit.ui(label)) + 14;
		if (x + w > limit) {
			return x;
		}
		hits.add(PackUi.button(context, x, y + 2, w, 18, label, 0, true, mouseX, mouseY, action));
		return x + w + 3;
	}

	private int inlineSlider(DrawContext context, int x, int y, int limit, String id, String label, String value, double v, double min, double max,
			java.util.function.DoubleConsumer set, int mouseX, int mouseY) {
		int w = 92;
		if (x + w > limit) {
			return x;
		}
		hits.add(PackUi.slider(context, "opt:" + tool + id, x, y - 1, w, label, value, v, min, max, mouseX, mouseY, slider, set, this::startSlider));
		return x + w + 12;
	}

	private void startSlider(PackUi.SliderDrag s) {
		slider = s;
		drag = Drag.SLIDER;
	}

	// ---- Canvas ----

	private void drawViewport(DrawContext context, int mouseX, int mouseY) {
		VeloDraw.fillRounded(context, vpX, vpY, vpW, vpH, 8, 0xFF16161B);
		context.enableScissor(vpX + 1, vpY + 1, vpX + vpW - 1, vpY + vpH - 1);
		int w = Math.max(1, Math.round(doc.width * zoom));
		int h = Math.max(1, Math.round(doc.height * zoom));
		int ix = Math.round(viewX);
		int iy = Math.round(viewY);
		if (tile) {
			for (int ty = -1; ty <= 1; ty++) {
				for (int tx = -1; tx <= 1; tx++) {
					if (tx == 0 && ty == 0) {
						continue;
					}
					canvasTex.drawTinted(context, ix + tx * w, iy + ty * h, w, h, 0xA0FFFFFF);
				}
			}
		}
		VeloDraw.shadow(context, ix, iy, w, h, 2, 10, 3, 0x70000000);
		checkerTex.draw(context, ix, iy, w, h);
		canvasTex.draw(context, ix, iy, w, h);
		if (grid && zoom >= 6) {
			int line = zoom >= 16 ? 0x26FFFFFF : 0x16FFFFFF;
			int x0 = Math.max(vpX, ix);
			int x1 = Math.min(vpX + vpW, ix + w);
			int y0 = Math.max(vpY, iy);
			int y1 = Math.min(vpY + vpH, iy + h);
			for (int i = 1; i < doc.width; i++) {
				int gx = ix + Math.round(i * zoom);
				if (gx > vpX && gx < vpX + vpW) {
					context.fill(gx, y0, gx + 1, y1, line);
				}
			}
			for (int i = 1; i < doc.height; i++) {
				int gy = iy + Math.round(i * zoom);
				if (gy > vpY && gy < vpY + vpH) {
					context.fill(x0, gy, x1, gy + 1, line);
				}
			}
		}
		if (mirrorX && (tool.paints() || tool.shapes() || tool == Tool.FILL)) {
			int mx = ix + w / 2;
			context.fill(mx, iy, mx + 1, iy + h, VeloUi.withAlpha(VeloStyle.accent(), 0xA0));
		}
		if (mirrorY && (tool.paints() || tool.shapes() || tool == Tool.FILL)) {
			int my = iy + h / 2;
			context.fill(ix, my, ix + w, my + 1, VeloUi.withAlpha(VeloStyle.accent(), 0xA0));
		}
		drawSelection(context, ix, iy);
		if (drag == Drag.LASSO && lassoPoints.size() >= 4) {
			for (int i = 2; i < lassoPoints.size(); i += 2) {
				VeloDraw.line(context, ix + lassoPoints.get(i - 2) * zoom, iy + lassoPoints.get(i - 1) * zoom, ix + lassoPoints.get(i) * zoom,
						iy + lassoPoints.get(i + 1) * zoom, 1f, 0xFFFFFFFF);
			}
		}
		// Hover: the brush footprint.
		boolean inViewport = VeloUi.inside(mouseX, mouseY, vpX, vpY, vpW, vpH);
		hoverX = (int) Math.floor((mouseX - viewX) / zoom);
		hoverY = (int) Math.floor((mouseY - viewY) / zoom);
		boolean onImage = inViewport && hoverX >= 0 && hoverY >= 0 && hoverX < doc.width && hoverY < doc.height;
		if (inViewport && drag == Drag.NONE || drag == Drag.PAINT) {
			if (tool.paints() || tool == Tool.LINE || tool == Tool.RECT || tool == Tool.ELLIPSE) {
				drawFootprint(context, ix, iy, hoverX, hoverY);
			} else if (onImage) {
				VeloDraw.strokeRect(context, ix + Math.round(hoverX * zoom), iy + Math.round(hoverY * zoom), Math.max(1, Math.round(zoom)),
						Math.max(1, Math.round(zoom)), 0xC0FFFFFF);
			}
		}
		context.disableScissor();
		// Status chip.
		String info;
		if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 4500) {
			info = status;
		} else if (onImage) {
			int c = doc.frame()[hoverY * doc.width + hoverX];
			info = hoverX + ", " + hoverY + "   " + ((c >>> 24) == 0 ? "transparent" : String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF)
					+ ((c >>> 24) == 255 ? "" : "  " + Math.round((c >>> 24) / 2.55f) + "%"));
		} else {
			info = doc.width + " x " + doc.height + (doc.animated() ? "  -  " + doc.frames.size() + " frames" : "") + (doc.dirty ? "  -  unsaved" : "");
		}
		Text infoText = MenuKit.small(info);
		int iw = this.textRenderer.getWidth(infoText) + 12;
		VeloDraw.fillRounded(context, vpX + 6, vpY + vpH - 18, iw, 13, 4, 0xC0101014);
		MenuKit.text(context, this.textRenderer, infoText, vpX + 12, vpY + vpH - 15, MenuKit.TEXT_2);
		if (onImage && (tool.paints() || tool == Tool.FILL || tool == Tool.PICKER)) {
			int c = doc.frame()[hoverY * doc.width + hoverX];
			if ((c >>> 24) != 0) {
				VeloDraw.fillRounded(context, vpX + 10 + iw, vpY + vpH - 18, 13, 13, 4, c | 0xFF000000);
			}
		}
	}

	private void drawFootprint(DrawContext context, int ix, int iy, int px, int py) {
		int size = tool == Tool.LINE || tool == Tool.RECT || tool == Tool.ELLIPSE || tool.paints() ? ts().size : 1;
		boolean round = tool.paints() && ts().round;
		int r0 = (size - 1) / 2;
		if (size * zoom > 120 || size > 24) {
			VeloDraw.strokeRect(context, ix + Math.round((px - r0) * zoom), iy + Math.round((py - r0) * zoom), Math.round(size * zoom), Math.round(size * zoom),
					0xC0FFFFFF);
			return;
		}
		boolean[] mask = new boolean[size * size];
		int[] tmp = new int[size * size];
		PixelOps.stamp(tmp, size, size, null, r0, r0, size, round, 0xFFFFFFFF, 1f, PixelOps.Mode.PAINT, null);
		for (int i = 0; i < tmp.length; i++) {
			mask[i] = tmp[i] != 0;
		}
		int fill = tool == Tool.ERASER ? 0x50FF6060 : VeloUi.withAlpha(primary, 0x90);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				if (!mask[y * size + x]) {
					continue;
				}
				int sx = ix + Math.round((px - r0 + x) * zoom);
				int sy = iy + Math.round((py - r0 + y) * zoom);
				int ex = ix + Math.round((px - r0 + x + 1) * zoom);
				int ey = iy + Math.round((py - r0 + y + 1) * zoom);
				context.fill(sx, sy, Math.max(sx + 1, ex), Math.max(sy + 1, ey), fill);
			}
		}
		VeloDraw.strokeRect(context, ix + Math.round((px - r0) * zoom), iy + Math.round((py - r0) * zoom), Math.max(1, Math.round(size * zoom)),
				Math.max(1, Math.round(size * zoom)), 0x90FFFFFF);
	}

	/** Marching ants along the selection's edge (or the one being dragged). */
	private void drawSelection(DrawContext context, int ix, int iy) {
		boolean[] mask = pendingMask != null ? pendingMask : floatMask != null ? shiftedFloatMask() : doc.selection;
		if (mask == null) {
			return;
		}
		if (mask != edgesFor || edgesRevision != doc.revision) {
			computeEdges(mask);
			edgesFor = mask;
			edgesRevision = doc.revision;
		}
		int phase = (int) (System.currentTimeMillis() / 140 % 4);
		int z = Math.max(1, Math.round(zoom));
		for (int e = 0; e < edgeCount; e++) {
			int x = edges[e * 3];
			int y = edges[e * 3 + 1];
			int dir = edges[e * 3 + 2];
			int sx = ix + Math.round(x * zoom);
			int sy = iy + Math.round(y * zoom);
			int color = ((x + y + phase / 2) & 1) == 0 ? 0xFFFFFFFF : 0xFF101014;
			if (dir == 0) {
				context.fill(sx, sy, sx + z, sy + 1, color);
			} else {
				context.fill(sx, sy, sx + 1, sy + z, color);
			}
		}
	}

	private boolean[] shiftedFloatMask() {
		boolean[] out = new boolean[floatMask.length];
		int w = doc.width;
		int h = doc.height;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (floatMask[y * w + x]) {
					int tx = x + floatDx;
					int ty = y + floatDy;
					if (tx >= 0 && ty >= 0 && tx < w && ty < h) {
						out[ty * w + tx] = true;
					}
				}
			}
		}
		return out;
	}

	private void computeEdges(boolean[] mask) {
		int w = doc.width;
		int h = doc.height;
		int n = 0;
		int[] out = new int[Math.min(24000, mask.length * 4) * 3];
		for (int y = 0; y < h && n < out.length / 3 - 4; y++) {
			for (int x = 0; x < w && n < out.length / 3 - 4; x++) {
				if (!mask[y * w + x]) {
					continue;
				}
				if (y == 0 || !mask[(y - 1) * w + x]) {
					out[n * 3] = x;
					out[n * 3 + 1] = y;
					out[n * 3 + 2] = 0;
					n++;
				}
				if (y == h - 1 || !mask[(y + 1) * w + x]) {
					out[n * 3] = x;
					out[n * 3 + 1] = y + 1;
					out[n * 3 + 2] = 0;
					n++;
				}
				if (x == 0 || !mask[y * w + x - 1]) {
					out[n * 3] = x;
					out[n * 3 + 1] = y;
					out[n * 3 + 2] = 1;
					n++;
				}
				if (x == w - 1 || !mask[y * w + x + 1]) {
					out[n * 3] = x + 1;
					out[n * 3 + 1] = y;
					out[n * 3 + 2] = 1;
					n++;
				}
			}
		}
		edges = out;
		edgeCount = n;
	}

	// ---- Timeline ----

	private void drawTimeline(DrawContext context, int mouseX, int mouseY) {
		VeloDraw.fillRounded(context, tlX, tlY, tlW, tlH, 8, 0x0CFFFFFF);
		int x = tlX + 6;
		int y = tlY + 6;
		hits.add(PackUi.iconButton(context, x, y, 20, playing ? "pause" : "play", playing, mouseX, mouseY, () -> playing = !playing));
		if (VeloUi.inside(mouseX, mouseY, x, y, 20, 20)) {
			tooltip = List.of(playing ? "Pause (P)" : "Play (P)", "Plays the animation in the preview");
		}
		hits.add(PackUi.iconButton(context, x, y + 22, 20, "onion", onion, mouseX, mouseY, () -> {
			onion = !onion;
			forceUpload = true;
		}));
		if (VeloUi.inside(mouseX, mouseY, x, y + 22, 20, 20)) {
			tooltip = List.of("Onion skin", "Shows the previous frame faintly under this one");
		}
		// Right controls.
		int controlsW = 196;
		int cx = tlX + tlW - controlsW - 6;
		hits.add(PackUi.iconButton(context, cx, y, 20, "plus", false, mouseX, mouseY, () -> {
			doc.addFrame(false);
			setStatus(doc.frames.size() == 2 ? "Now animated - saved with a .mcmeta file" : "Frame added");
		}));
		hits.add(PackUi.iconButton(context, cx + 22, y, 20, "copy", false, mouseX, mouseY, () -> doc.addFrame(true)));
		hits.add(PackUi.iconButton(context, cx + 44, y, 20, "trash", false, mouseX, mouseY, doc::deleteFrame));
		hits.add(PackUi.iconButton(context, cx + 66, y, 20, "left", false, mouseX, mouseY, () -> doc.moveFrame(-1)));
		hits.add(PackUi.iconButton(context, cx + 88, y, 20, "right", false, mouseX, mouseY, () -> doc.moveFrame(1)));
		if (VeloUi.inside(mouseX, mouseY, cx, y, 108, 20)) {
			int which = (mouseX - cx) / 22;
			String[] tips = {"New empty frame", "Duplicate frame", "Delete frame", "Move frame left", "Move frame right"};
			tooltip = List.of(tips[Math.max(0, Math.min(4, which))]);
		}
		hits.add(PackUi.button(context, cx + 112, y + 1, 84, 18, "Interpolate", doc.interpolate ? 1 : 0, doc.animated(), mouseX, mouseY, () -> {
			doc.interpolate = !doc.interpolate;
			doc.changed();
		}));
		// Frame time.
		int ft = doc.frametime;
		hits.add(PackUi.iconButton(context, cx, y + 24, 16, "left", false, mouseX, mouseY, () -> setFrameTime(-1)));
		String time = currentFrameTimeLabel();
		Text tt = MenuKit.small(time);
		MenuKit.text(context, this.textRenderer, tt, cx + 18 + (160 - this.textRenderer.getWidth(tt)) / 2, y + 29, MenuKit.TEXT_2);
		hits.add(PackUi.iconButton(context, cx + 180, y + 24, 16, "right", false, mouseX, mouseY, () -> setFrameTime(1)));
		if (VeloUi.inside(mouseX, mouseY, cx, y + 24, 196, 16)) {
			tooltip = List.of("Frame time (" + ft + " ticks = " + String.format(Locale.ROOT, "%.2f", ft / 20f) + " s)",
					"Shift: only this frame's time");
		}

		// Frames.
		int stripX = x + 28;
		int stripW = cx - 10 - stripX;
		int thumb = 38;
		int count = doc.frames.size();
		boolean thumbs = (long) doc.width * doc.height * count <= 2_000_000L;
		if (thumbs && (stripTex == null || stripRevision != doc.revision || stripTex.width != doc.width || stripTex.height != doc.height * count)) {
			if (stripTex != null) {
				stripTex.release();
			}
			stripTex = new GpuImage("frame strip", doc.width, doc.height * count);
			int[] all = new int[doc.width * doc.height * count];
			for (int i = 0; i < count; i++) {
				System.arraycopy(doc.frames.get(i), 0, all, i * doc.width * doc.height, doc.width * doc.height);
			}
			stripTex.upload(all);
			stripRevision = doc.revision;
		}
		float maxScroll = Math.max(0, count * (thumb + 4) - stripW);
		frameScroll = Math.max(0, Math.min(maxScroll, frameScroll));
		context.enableScissor(stripX, tlY + 2, stripX + stripW, tlY + tlH - 2);
		int playing0 = playing ? (int) doc.frameAt(ticks)[0] : -1;
		for (int i = 0; i < count; i++) {
			int fx = stripX + i * (thumb + 4) - Math.round(frameScroll);
			if (fx + thumb < stripX || fx > stripX + stripW) {
				continue;
			}
			boolean active = i == doc.current;
			boolean hovered = VeloUi.inside(mouseX, mouseY, fx, y, thumb, thumb) && VeloUi.inside(mouseX, mouseY, stripX, tlY, stripW, tlH);
			float h = PackUi.hover("frame:" + i, hovered || active);
			VeloDraw.fillRounded(context, fx, y, thumb, thumb, 5, VeloAnim.lerpArgb(0x14FFFFFF, 0x26FFFFFF, h));
			if (thumbs && stripTex != null) {
				float sc = Math.min((thumb - 6) / (float) doc.width, (thumb - 6) / (float) doc.height);
				int dw = Math.max(1, Math.round(doc.width * sc));
				int dh = Math.max(1, Math.round(doc.height * sc));
				stripTex.draw(context, fx + (thumb - dw) / 2, y + (thumb - dh) / 2, dw, dh, 0, i * doc.height, doc.width, doc.height);
			}
			if (active) {
				VeloDraw.strokeRounded(context, fx, y, thumb, thumb, 5, VeloStyle.accent());
			}
			if (i == playing0) {
				VeloDraw.fillRounded(context, fx + 2, y + thumb - 4, thumb - 4, 2, 1, MenuKit.GOLD);
			}
			Text n = MenuKit.small(String.valueOf(i + 1));
			MenuKit.text(context, this.textRenderer, n, fx + 3, y + 2, MenuKit.TEXT_2);
			int own = doc.frameTimes.get(i);
			if (own > 0) {
				Text t = MenuKit.small(own + "t");
				MenuKit.text(context, this.textRenderer, t, fx + thumb - 3 - this.textRenderer.getWidth(t), y + 2, MenuKit.GOLD);
			}
			int index = i;
			if (hovered) {
				hits.add(new VeloUi.Hit(fx, y, thumb, thumb, () -> {
					dropFloating();
					doc.current = index;
					filterDirty = filter != null;
				}));
			}
		}
		context.disableScissor();
		if (count == 1) {
			MenuKit.text(context, this.textRenderer, MenuKit.small("Still texture - add a frame to animate it"), stripX + thumb + 10, y + 16, MenuKit.TEXT_3);
		}
		if (doc.order != null) {
			MenuKit.text(context, this.textRenderer, MenuKit.small("Custom play order kept"), stripX + 4, tlY + tlH - 10, MenuKit.GOLD);
		}
	}

	private String currentFrameTimeLabel() {
		int own = doc.frameTimes.isEmpty() ? 0 : doc.frameTimes.get(doc.current);
		String base = doc.frametime + (doc.frametime == 1 ? " tick" : " ticks") + " / frame";
		return own > 0 ? "Frame " + (doc.current + 1) + ": " + own + "t  (default " + doc.frametime + ")" : base;
	}

	private void setFrameTime(int by) {
		boolean shift = Keys.isKeyDown(Keys.LEFT_SHIFT) || Keys.isKeyDown(Keys.RIGHT_SHIFT);
		if (shift) {
			int own = doc.frameTimes.get(doc.current);
			int base = own > 0 ? own : doc.frametime;
			int next = Math.max(1, base + by);
			doc.frameTimes.set(doc.current, next == doc.frametime ? 0 : next);
		} else {
			doc.frametime = Math.max(1, Math.min(200, doc.frametime + by));
		}
		doc.changed();
	}

	// ---- Right panel: colour, preview, adjustments ----

	private void drawRightPanel(DrawContext context, int mouseX, int mouseY) {
		int x = rpX;
		int y = rpY;
		int w = rpW;
		// Swatch + hex.
		VeloDraw.fillRounded(context, x, y, 36, 26, 6, 0xFF2C2C33);
		swatch(context, x, y, 26, primary);
		VeloStyle.drawInputField(context, x + 38, y + 2, w - 38, 22, hexField != null && hexField.isFocused(), false);
		// SV square + hue bar.
		int sy = y + 32;
		int svW = w - 18;
		int svH = Math.max(40, Math.min(90, rpH / 6));
		svTex.draw(context, x, sy, svW, svH);
		VeloDraw.strokeRect(context, x, sy, svW, svH, 0x30FFFFFF);
		float kx = x + hsv[1] * svW;
		float ky = sy + (1 - hsv[2]) * svH;
		VeloDraw.strokeArc(context, kx, ky, 4f, 1.5f, 0, 360, 0xFFFFFFFF);
		VeloDraw.strokeArc(context, kx, ky, 5.5f, 1f, 0, 360, 0x80000000);
		hits.add(new VeloUi.Hit(x, sy, svW, svH, () -> {
			drag = Drag.SV;
			updateColorDrag(mouseX, mouseY);
		}));
		hueTex.draw(context, x + w - 12, sy, 12, svH);
		int hy = sy + Math.round(hsv[0] / 360f * svH);
		VeloDraw.fillRounded(context, x + w - 14, hy - 2, 16, 4, 2, 0xFFFFFFFF);
		hits.add(new VeloUi.Hit(x + w - 14, sy, 16, svH, () -> {
			drag = Drag.HUE;
			updateColorDrag(mouseX, mouseY);
		}));
		// Alpha bar.
		int ay = sy + svH + 6;
		for (int i = 0; i < w; i += 4) {
			context.fill(x + i, ay, Math.min(x + w, x + i + 4), ay + 4, (i / 4) % 2 == 0 ? 0xFF55555E : 0xFF3A3A42);
			context.fill(x + i, ay + 4, Math.min(x + w, x + i + 4), ay + 8, (i / 4) % 2 == 0 ? 0xFF3A3A42 : 0xFF55555E);
		}
		MenuKit.hGradient(context, x, ay, w, 8, primary & 0xFFFFFF, primary | 0xFF000000);
		int axPos = x + Math.round((primary >>> 24) / 255f * w);
		VeloDraw.fillRounded(context, axPos - 2, ay - 2, 4, 12, 2, 0xFFFFFFFF);
		hits.add(new VeloUi.Hit(x, ay - 2, w, 12, () -> {
			drag = Drag.ALPHA;
			updateColorDrag(mouseX, mouseY);
		}));
		// Palettes.
		int py = ay + 14;
		if (paletteRevision != doc.revision && drag != Drag.PAINT) {
			texturePalette = PixelOps.palette(doc.frame(), 24);
			paletteRevision = doc.revision;
		}
		int sw = 12;
		int perRow = Math.max(1, (w + 2) / (sw + 2));
		py = paletteRow(context, x, py, perRow, sw, texturePalette, 2, mouseX, mouseY);
		int[] recents = RECENTS.stream().mapToInt(Integer::intValue).toArray();
		py = paletteRow(context, x, py + 2, perRow, sw, recents, 1, mouseX, mouseY);

		// Tabs.
		int tabY = py + 6;
		int tabW = (w - 4) / 2;
		// Segmented switch: the accent pill slides to the chosen tab, the page slides in and fades up.
		tabSlide += (rightTab - tabSlide) * 0.28f;
		if (Math.abs(rightTab - tabSlide) < 0.005f) {
			tabSlide = rightTab;
		}
		VeloDraw.fillRounded(context, x, tabY, w, 16, 6, 0x0DFFFFFF);
		VeloDraw.fillRounded(context, Math.round(x + 2 + tabSlide * (tabW + 4 - 2)), tabY + 1, tabW - 2, 14, 5, VeloStyle.accent());
		String[] tabNames = {"Preview", "Adjust"};
		for (int i = 0; i < 2; i++) {
			int tx = x + i * (tabW + 4);
			float on = 1 - Math.min(1, Math.abs(tabSlide - i));
			Text label = on > 0.5f ? MenuKit.uiBold(tabNames[i]) : MenuKit.ui(tabNames[i]);
			int color = VeloAnim.lerpArgb(MenuKit.TEXT_2, VeloStyle.onAccent(), on);
			MenuKit.text(context, this.textRenderer, label, tx + (tabW - this.textRenderer.getWidth(label)) / 2, tabY + 4, color);
			int index = i;
			hits.add(new VeloUi.Hit(tx, tabY, tabW, 16, () -> switchTab(index)));
		}
		int contentTop = tabY + 22;
		int saveY = rpY + rpH - 18;
		int contentBottom = saveY - 6;
		float t = Math.min(1f, (System.nanoTime() - tabSwitchedAt) / 220_000_000f);
		float eased = 1 - (float) Math.pow(1 - t, 3);
		float slide = (1 - eased) * 14 * tabDirection;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(slide, 0);
		context.enableScissor(x - 2, contentTop - 2, x + w + 2, contentBottom + 2);
		int shiftedMouseX = mouseX - Math.round(slide);
		if (rightTab == 0) {
			drawPreview(context, x, contentTop, w, contentBottom - contentTop, shiftedMouseX, mouseY);
		} else {
			drawAdjust(context, x, contentTop, w, contentBottom - contentTop, shiftedMouseX, mouseY);
		}
		context.disableScissor();
		context.getMatrices().popMatrix();
		if (eased < 1) {
			int glass = VeloAnim.lerpArgb(0xFF0E0E12, net.veloclient.velo.client.theme.ThemeManager.active().surface() | 0xFF000000, 0.14f);
			context.fill(x - 2, contentTop - 2, x + w + 2, contentBottom + 2, VeloUi.withAlpha(glass, Math.round(0xE0 * (1 - eased))));
		}
		int half = (w - 4) / 2;
		hits.add(PackUi.button(context, x, saveY, half, 18, doc.dirty ? "Save" : "Saved", doc.dirty ? 0 : 0, doc.dirty, mouseX, mouseY, this::save));
		hits.add(PackUi.button(context, x + half + 4, saveY, w - half - 4, 18, "Save & reload", 1, true, mouseX, mouseY, this::saveAndReload));
		if (VeloUi.inside(mouseX, mouseY, x + half + 4, saveY, w - half - 4, 18)) {
			tooltip = List.of("Save & reload (Ctrl+Shift+S)", "Saves, selects the pack and reloads the game's", "resources so you see it in the world");
		}
	}

	private int paletteRow(DrawContext context, int x, int y, int perRow, int sw, int[] colors, int maxRows, int mouseX, int mouseY) {
		int shown = Math.min(colors.length, perRow * maxRows);
		for (int i = 0; i < shown; i++) {
			int c = colors[i];
			int sx = x + (i % perRow) * (sw + 2);
			int sy = y + (i / perRow) * (sw + 2);
			context.fill(sx, sy, sx + sw, sy + sw, 0xFF3A3A42);
			context.fill(sx, sy, sx + sw / 2, sy + sw / 2, 0xFF55555E);
			context.fill(sx + sw / 2, sy + sw / 2, sx + sw, sy + sw, 0xFF55555E);
			context.fill(sx, sy, sx + sw, sy + sw, c);
			boolean hovered = VeloUi.inside(mouseX, mouseY, sx, sy, sw, sw);
			if (c == primary || hovered) {
				VeloDraw.strokeRect(context, sx - 1, sy - 1, sw + 2, sw + 2, c == primary ? 0xFFFFFFFF : 0x90FFFFFF);
			}
			if (hovered) {
				tooltip = List.of(String.format(Locale.ROOT, "#%06X", c & 0xFFFFFF), "Left: primary, right: secondary");
				hits.add(new VeloUi.Hit(sx, sy, sw, sw, () -> {
					if (dragButton == Keys.MOUSE_RIGHT) {
						secondary = c;
					} else {
						setPrimary(c);
					}
				}));
			}
		}
		int rows = shown == 0 ? 0 : (shown + perRow - 1) / perRow;
		return y + rows * (sw + 2);
	}

	private void setPrimary(int c) {
		primary = c;
		hsv = PixelOps.hsv(c);
		syncHex();
		filterDirty = filter != null;
	}

	private void rememberColor(int c) {
		RECENTS.remove(c);
		RECENTS.addFirst(c);
		while (RECENTS.size() > 24) {
			RECENTS.removeLast();
		}
	}

	private void updateColorDrag(double mouseX, double mouseY) {
		int x = rpX;
		int sy = rpY + 32;
		int sw = rpW - 18;
		int sh = Math.max(40, Math.min(90, rpH / 6));
		int alpha = primary >>> 24;
		if (drag == Drag.SV) {
			hsv[1] = (float) Math.max(0, Math.min(1, (mouseX - x) / sw));
			hsv[2] = (float) Math.max(0, Math.min(1, 1 - (mouseY - sy) / sh));
			primary = PixelOps.fromHsv(hsv[0], hsv[1], hsv[2], alpha);
		} else if (drag == Drag.HUE) {
			hsv[0] = (float) Math.max(0, Math.min(359.9, (mouseY - sy) / sh * 360));
			primary = PixelOps.fromHsv(hsv[0], hsv[1], hsv[2], alpha);
		} else if (drag == Drag.ALPHA) {
			int a = (int) Math.round(Math.max(0, Math.min(1, (mouseX - x) / rpW)) * 255);
			primary = (primary & 0xFFFFFF) | a << 24;
		}
		syncHex();
		filterDirty = filter != null;
	}

	private float tabSlide;
	private long tabSwitchedAt;
	private int tabDirection = 1;

	private void switchTab(int tab) {
		if (tab == rightTab) {
			return;
		}
		tabDirection = tab > rightTab ? 1 : -1;
		rightTab = tab;
		tabSwitchedAt = System.nanoTime();
	}

	private void drawPreview(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		int size = Math.max(40, Math.min(w, h - 26));
		int px = x + (w - size) / 2;
		VeloDraw.fillRounded(context, x, y, w, size, 8, 0xFF16161B);
		VeloDraw.fillGradient(context, x + 1, y + size / 2, w - 2, size / 2 - 1, 0x00000000, VeloUi.withAlpha(VeloStyle.accent(), 0x18));
		if (ref == null || PreviewModels.kindOf(ref) == PreviewModels.Kind.FLAT) {
			drawFlatPreview(context, x, y, w, size);
			return;
		}
		if (!previewBuilt || (preview != null && preview.kind() == PreviewModels.Kind.ITEM && previewRevision != doc.revision
				&& System.currentTimeMillis() - lastPreviewBuild > 150 && drag != Drag.PAINT)) {
			buildPreview();
		}
		if (preview == null || preview.kind() == PreviewModels.Kind.FLAT) {
			drawFlatPreview(context, x, y, w, size);
			if (!PreviewModels.indexReady()) {
				MenuKit.text(context, this.textRenderer, MenuKit.small("Finding models..."), x + 6, y + 6, MenuKit.TEXT_3);
			}
			return;
		}
		int res = Math.min(192, Math.max(64, size));
		if (previewTex == null || previewSize != res) {
			if (previewTex != null) {
				previewTex.release();
			}
			previewTex = new GpuImage("texture editor preview", res, res);
			previewSize = res;
			previewPx = new int[res * res];
			lastRenderYaw = Float.NaN;
		}
		Map<String, Soft3d.Tex> textures = new HashMap<>(preview.textures());
		int[] pixels = playing ? doc.pixelsAt(ticks) : displayPixels();
		textures.put(ref, new Soft3d.Tex(pixels, doc.width, doc.height));
		preview.model().render(previewPx, res, yaw, pitch, previewZoom, textures);
		Soft3d.outline(previewPx, res, 0x60000000);
		previewTex.upload(previewPx);
		previewTex.draw(context, px, y, size, size);
		hits.add(new VeloUi.Hit(x, y, w, size, () -> drag = Drag.PREVIEW));
		if (VeloUi.inside(mouseX, mouseY, x, y, w, size)) {
			tooltip = List.of("3D preview", "Drag to turn, scroll to zoom");
		}
		int by = y + size + 4;
		String label = preview.label();
		if (preview.candidates().size() > 1) {
			hits.add(PackUi.iconButton(context, x, by, 16, "left", false, mouseX, mouseY, () -> cyclePreview(-1)));
			hits.add(PackUi.iconButton(context, x + w - 16, by, 16, "right", false, mouseX, mouseY, () -> cyclePreview(1)));
			Text t = MenuKit.fit(this.textRenderer, MenuKit.small(label), w - 40);
			MenuKit.text(context, this.textRenderer, t, x + (w - this.textRenderer.getWidth(t)) / 2, by + 5, MenuKit.TEXT_2);
		} else {
			Text t = MenuKit.fit(this.textRenderer, MenuKit.small(label), w);
			MenuKit.text(context, this.textRenderer, t, x + (w - this.textRenderer.getWidth(t)) / 2, by + 5, MenuKit.TEXT_2);
		}
		hits.add(PackUi.iconButton(context, x + w - 16, y + 4, 14, "rotate", autoRotate, mouseX, mouseY, () -> autoRotate = !autoRotate));
	}

	private void drawFlatPreview(DrawContext context, int x, int y, int w, int size) {
		// The texture at its real in-game size (1x, 2x) and tiled.
		int[] pixels = playing ? doc.pixelsAt(ticks) : null;
		if (pixels != null) {
			if (previewTex == null || previewTex.width != doc.width || previewTex.height != doc.height) {
				if (previewTex != null) {
					previewTex.release();
				}
				previewTex = new GpuImage("texture editor flat preview", doc.width, doc.height);
				previewSize = -1;
			}
			previewTex.upload(pixels);
		}
		GpuImage image = pixels != null ? previewTex : canvasTex;
		float fit = Math.min((w - 16) / (float) doc.width, (size - 30) / (float) doc.height);
		float scale = fit >= 1 ? (float) Math.floor(Math.min(fit, 4)) : fit;
		int dw = Math.max(1, Math.round(doc.width * scale));
		int dh = Math.max(1, Math.round(doc.height * scale));
		int cx = x + (w - dw) / 2;
		int cy = y + 8 + (size - 30 - dh) / 2;
		checkerTex.draw(context, cx, cy, dw, dh);
		image.draw(context, cx, cy, dw, dh);
		String label = scale >= 1 ? Math.round(scale) + "x" : Math.round(scale * 100) + "%";
		Text t = MenuKit.small(label + "  -  " + doc.width + " x " + doc.height);
		MenuKit.text(context, this.textRenderer, t, x + (w - this.textRenderer.getWidth(t)) / 2, y + size - 16, MenuKit.TEXT_3);
	}

	private void buildPreview() {
		previewBuilt = true;
		lastPreviewBuild = System.currentTimeMillis();
		previewRevision = doc.revision;
		if (ref == null) {
			preview = null;
			return;
		}
		if (!PreviewModels.indexReady() && PreviewModels.kindOf(ref) != PreviewModels.Kind.ENTITY) {
			previewBuilt = false;
			return;
		}
		try {
			preview = PreviewModels.build(ref, previewIndex, doc.width, doc.height, k -> PackContents.read(packRoot, k), doc.frame());
		} catch (RuntimeException e) {
			preview = null;
		}
	}

	private void cyclePreview(int by) {
		previewIndex += by;
		previewBuilt = false;
		previewZoom = 1f;
	}

	private void drawAdjust(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		int cy = y;
		if (filter != null) {
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(filter.label), x, cy, MenuKit.TEXT);
			cy += 14;
			switch (filter) {
				case HSB -> {
					cy = filterSlider(context, x, cy, w, "hue", "Hue", Math.round(fa) + " deg", fa, -180, 180, v -> fa = (float) v, mouseX, mouseY);
					cy = filterSlider(context, x, cy, w, "sat", "Saturation", Math.round(fb * 100) + "%", fb, -1, 1, v -> fb = (float) v, mouseX, mouseY);
					cy = filterSlider(context, x, cy, w, "bri", "Brightness", Math.round(fc * 100) + "%", fc, -1, 1, v -> fc = (float) v, mouseX, mouseY);
				}
				case COLORIZE -> {
					MenuKit.text(context, this.textRenderer, MenuKit.small("Recolours by brightness - shading stays"), x, cy, MenuKit.TEXT_3);
					cy += 12;
					// The hue slider's own track shows the colour it picks.
					int hueColor = PixelOps.fromHsl(fa, Math.max(0.35f, fb), 0.5f, 255);
					VeloDraw.fillRounded(context, x + w - 10, cy + 1, 8, 8, 2, hueColor);
					cy = filterSlider(context, x, cy, w - 14, "hue", "Hue", Math.round(fa) + " deg", fa, 0, 360, v -> fa = (float) v, mouseX, mouseY);
					cy = filterSlider(context, x, cy, w, "sat", "Saturation", Math.round(fb * 100) + "%", fb, 0, 1, v -> fb = (float) v, mouseX, mouseY);
					cy = filterSlider(context, x, cy, w, "light", "Lightness", Math.round(fc * 100) + "%", fc, -1, 1, v -> fc = (float) v, mouseX, mouseY);
					hits.add(PackUi.button(context, x, cy, w, 16, "Take primary colour", 0, true, mouseX, mouseY, this::colorizeFromPrimary));
					cy += 18;
				}
				case REPLACE -> {
					MenuKit.text(context, this.textRenderer, MenuKit.small("Secondary colour becomes the primary"), x, cy, MenuKit.TEXT_3);
					cy += 12;
					swatch(context, x, cy, 16, secondary);
					PackUi.glyph(context, "right", x + 26, cy + 8, 10, MenuKit.TEXT_2);
					swatch(context, x + 36, cy, 16, primary);
					cy += 22;
					cy = filterSlider(context, x, cy, w, "tol", "Tolerance", Math.round(fa * 100) + "%", fa, 0, 1, v -> fa = (float) v, mouseX, mouseY);
				}
				case CONTRAST -> {
					cy = filterSlider(context, x, cy, w, "bri", "Brightness", Math.round(fb * 100) + "%", fb, -1, 1, v -> fb = (float) v, mouseX, mouseY);
					cy = filterSlider(context, x, cy, w, "con", "Contrast", Math.round(fa * 100) + "%", fa, -1, 1, v -> fa = (float) v, mouseX, mouseY);
				}
				case POSTERIZE -> cy = filterSlider(context, x, cy, w, "levels", "Levels", String.valueOf(Math.round(fa)), fa, 2, 16, v -> fa = (float) v,
						mouseX, mouseY);
				case NOISE -> {
					cy = filterSlider(context, x, cy, w, "amount", "Amount", Math.round(fa * 100) + "%", fa, 0, 1, v -> fa = (float) v, mouseX, mouseY);
					hits.add(PackUi.button(context, x, cy, w, 16, "New pattern", 0, true, mouseX, mouseY, () -> {
						noiseSeed++;
						filterDirty = true;
					}));
					cy += 20;
				}
				default -> {
				}
			}
			// Apply / Cancel sit at the bottom of the panel (with the frames switch above them for animations).
			int half = (w - 4) / 2;
			int by = Math.max(cy + 2, y + h - 18);
			if (doc.animated()) {
				int fy = Math.max(cy + 2, y + h - 38);
				by = fy + 20;
				hits.add(PackUi.button(context, x, fy, w, 16, allFrames ? "All frames" : "Only this frame", allFrames ? 1 : 0, true, mouseX, mouseY,
						() -> allFrames = !allFrames));
			}
			hits.add(PackUi.button(context, x, by, half, 18, "Cancel", 0, true, mouseX, mouseY, this::cancelFilter));
			hits.add(PackUi.button(context, x + half + 4, by, w - half - 4, 18, "Apply", 1, true, mouseX, mouseY, this::applyFilter));
			return;
		}
		PackUi.heading(context, "Adjust" + (PixelOps.any(doc.selection) ? " selection" : ""), x, cy);
		cy += 12;
		int half = (w - 4) / 2;
		Filter[] filters = Filter.values();
		for (int i = 0; i < filters.length; i++) {
			Filter f = filters[i];
			int bx = x + (i % 2) * (half + 4);
			int by = cy + (i / 2) * 20;
			hits.add(PackUi.button(context, bx, by, i % 2 == 0 ? half : w - half - 4, 17, f.label, 0, true, mouseX, mouseY, () -> startFilter(f)));
		}
		cy += ((filters.length + 1) / 2) * 20 + 6;
		PackUi.heading(context, "Quick", x, cy);
		cy += 12;
		String[][] quick = {{"Invert", "invert"}, {"Grayscale", "gray"}, {"Sepia", "sepia"}, {"Blur", "blur"}, {"Outline", "outline"}, {"Flip H", "fliph"},
				{"Flip V", "flipv"}, {"Rotate", "rotate"}, {"Offset", "offset"}, {"Clear", "clear"}};
		for (int i = 0; i < quick.length; i++) {
			String id = quick[i][1];
			int bx = x + (i % 2) * (half + 4);
			int by = cy + (i / 2) * 20;
			if (by + 17 > y + h - 44) {
				break;
			}
			hits.add(PackUi.button(context, bx, by, i % 2 == 0 ? half : w - half - 4, 17, quick[i][0], 0, true, mouseX, mouseY, () -> quick(id)));
		}
		cy += ((quick.length + 1) / 2) * 20 + 6;
		if (cy + 30 <= y + h) {
			PackUi.heading(context, "Canvas  " + doc.width + " x " + doc.height, x, cy);
			cy += 12;
			hits.add(PackUi.button(context, x, cy, half, 17, "Half size", 0, doc.width > 1 && doc.height > 1, mouseX, mouseY,
					() -> resizeCanvas(Math.max(1, doc.width / 2), Math.max(1, doc.height / 2))));
			hits.add(PackUi.button(context, x + half + 4, cy, w - half - 4, 17, "Double size", 0, doc.width <= 1024, mouseX, mouseY,
					() -> resizeCanvas(doc.width * 2, doc.height * 2)));
			cy += 26;
		}
		if (cy + 30 <= y + h) {
			PackUi.heading(context, "PNG files", x, cy);
			cy += 12;
			hits.add(PackUi.button(context, x, cy, half, 17, "Import...", 0, true, mouseX, mouseY, this::importImage));
			hits.add(PackUi.button(context, x + half + 4, cy, w - half - 4, 17, "Export...", 0, true, mouseX, mouseY, this::exportImage));
			if (VeloUi.inside(mouseX, mouseY, x, cy, w, 17)) {
				tooltip = VeloUi.inside(mouseX, mouseY, x, cy, half, 17)
						? List.of("Import a PNG", "Pasted as a movable layer - drag it, Enter drops it", "Shift: import as a new frame")
						: List.of("Export as PNG", doc.animated() ? "All frames as one strip + .mcmeta" : "The texture as it is now",
								"Shift: only the current frame");
			}
		}
	}

	private int filterSlider(DrawContext context, int x, int y, int w, String id, String label, String value, double v, double min, double max,
			java.util.function.DoubleConsumer set, int mouseX, int mouseY) {
		hits.add(PackUi.slider(context, "filter:" + id, x, y, w, label, value, v, min, max, mouseX, mouseY, slider, val -> {
			set.accept(val);
			filterDirty = true;
		}, this::startSlider));
		return y + 21;
	}

	// ---- Filters ----

	private void startFilter(Filter f) {
		filter = f;
		fa = 0;
		fb = 0;
		fc = 0;
		if (f == Filter.COLORIZE) {
			colorizeFromPrimary();
		} else if (f == Filter.REPLACE) {
			fa = 0.1f;
		} else if (f == Filter.POSTERIZE) {
			fa = 4;
		} else if (f == Filter.NOISE) {
			fa = 0.2f;
		}
		filterDirty = true;
	}

	private PixelOps.PixelFilter currentFilter() {
		return switch (filter) {
			case HSB -> PixelOps.hsb(fa, fb, fc);
			case COLORIZE -> PixelOps.colorize(fa, fb, fc);
			case REPLACE -> PixelOps.replace(secondary, primary, fa);
			case CONTRAST -> {
				PixelOps.PixelFilter contrast = PixelOps.contrast(fa);
				PixelOps.PixelFilter brightness = PixelOps.hsb(0, 0, fb);
				yield (c, x, y) -> contrast.apply(brightness.apply(c, x, y), x, y);
			}
			case POSTERIZE -> PixelOps.posterize(Math.round(fa));
			case NOISE -> PixelOps.noise(fa, noiseSeed);
		};
	}

	/** Colorize settings from the primary colour: its hue and saturation, lightness offset from its brightness. */
	private void colorizeFromPrimary() {
		float[] hsl = PixelOps.hsl(primary);
		fa = hsl[0];
		fb = Math.max(0.25f, hsl[1]);
		fc = Math.max(-0.6f, Math.min(0.6f, (hsl[2] - 0.5f) * 0.8f));
		filterDirty = true;
	}

	private void computeFilterPreview() {
		if (filter == null) {
			filterPreview = null;
			return;
		}
		int[] copy = doc.frame().clone();
		PixelOps.filter(copy, doc.width, doc.height, doc.selection, currentFilter());
		filterPreview = copy;
	}

	private void applyFilter() {
		if (filter == null) {
			return;
		}
		doc.checkpoint(filter.label);
		PixelOps.PixelFilter f = currentFilter();
		if (allFrames) {
			for (int[] frame : doc.frames) {
				PixelOps.filter(frame, doc.width, doc.height, doc.selection, f);
			}
		} else {
			PixelOps.filter(doc.frame(), doc.width, doc.height, doc.selection, f);
		}
		doc.changed();
		setStatus(filter.label + " applied");
		cancelFilter();
	}

	private void cancelFilter() {
		filter = null;
		filterPreview = null;
		forceUpload = true;
	}

	private void quick(String id) {
		dropFloating();
		doc.checkpoint(id);
		List<int[]> targets = allFrames && doc.animated() ? new ArrayList<>(doc.frames) : List.of(doc.frame());
		int w = doc.width;
		int h = doc.height;
		boolean[] mask = doc.selection;
		for (int[] frame : targets) {
			switch (id) {
				case "invert" -> PixelOps.filter(frame, w, h, mask, PixelOps.invertColors());
				case "gray" -> PixelOps.filter(frame, w, h, mask, PixelOps.grayscale());
				case "sepia" -> PixelOps.filter(frame, w, h, mask, PixelOps.sepia());
				case "blur" -> PixelOps.blur(frame, w, h, mask);
				case "outline" -> PixelOps.outline(frame, w, h, mask, primary, false);
				case "fliph" -> PixelOps.flip(frame, w, h, mask, true);
				case "flipv" -> PixelOps.flip(frame, w, h, mask, false);
				case "rotate" -> {
					if (w == h) {
						System.arraycopy(PixelOps.rotate(frame, w), 0, frame, 0, frame.length);
					} else {
						setStatus("Rotate needs a square texture");
					}
				}
				case "offset" -> System.arraycopy(PixelOps.offset(frame, w, h, w / 2, h / 2), 0, frame, 0, frame.length);
				case "clear" -> {
					for (int i = 0; i < frame.length; i++) {
						if (mask == null || mask[i]) {
							frame[i] = 0;
						}
					}
				}
				default -> {
				}
			}
		}
		doc.changed();
	}

	private void exportImage() {
		dropFloating();
		boolean frameOnly = Keys.isKeyDown(Keys.LEFT_SHIFT) || Keys.isKeyDown(Keys.RIGHT_SHIFT);
		String base = file.getFileName().toString().replaceFirst("(?i)\\.png$", "");
		String suggested = base + (frameOnly && doc.animated() ? "_frame" + (doc.current + 1) : "") + ".png";
		int frame = doc.current;
		setStatus("Opening file picker...");
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				Path target = NativeFileDialog.savePngFile("Export " + this.title.getString(), suggested);
				MinecraftClient.getInstance().execute(() -> {
					if (target == null) {
						setStatus("");
						return;
					}
					try {
						if (frameOnly || !doc.animated()) {
							doc.exportFrame(frameOnly ? frame : 0, target);
						} else {
							doc.exportAll(target);
						}
						setStatus("Exported to " + target.getFileName());
					} catch (IOException e) {
						setStatus("Export failed: " + e.getMessage());
					}
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> setStatus("Export failed: " + e.getMessage()));
			}
		});
	}

	private void resizeCanvas(int nw, int nh) {
		dropFloating();
		doc.resize(nw, nh, true);
		fitView();
		setStatus("Canvas is now " + nw + " x " + nh);
	}

	private void importImage() {
		boolean asFrame = Keys.isKeyDown(Keys.LEFT_SHIFT) || Keys.isKeyDown(Keys.RIGHT_SHIFT);
		setStatus("Opening file picker...");
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				Path picked = NativeFileDialog.pickPngFile("Choose a PNG to paste into " + this.title.getString());
				if (picked == null) {
					MinecraftClient.getInstance().execute(() -> setStatus(""));
					return;
				}
				TexDoc imported = TexDoc.read(Files.readAllBytes(picked), null);
				MinecraftClient.getInstance().execute(() -> {
					int[] src = imported.frames.get(0);
					int[] fitted = src;
					int iw = imported.width;
					int ih = imported.height;
					if (iw > doc.width || ih > doc.height) {
						float s = Math.min(doc.width / (float) iw, doc.height / (float) ih);
						int nw = Math.max(1, Math.round(iw * s));
						int nh = Math.max(1, Math.round(ih * s));
						fitted = PixelOps.resample(src, iw, ih, nw, nh);
						iw = nw;
						ih = nh;
					}
					if (asFrame) {
						dropFloating();
						doc.addFrame(false);
					}
					pasteRegion(fitted, null, iw, ih, 0, 0);
					setStatus("Imported " + picked.getFileName() + (asFrame ? " as a new frame" : "") + " - drag it into place, Enter drops it");
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> setStatus("Import failed: " + e.getMessage()));
			}
		});
	}

	// ---- Painting ----

	private int colorFor(int button) {
		return button == Keys.MOUSE_RIGHT ? secondary : primary;
	}

	private void dab(int x, int y, int button) {
		ToolSettings s = ts();
		int[] px = doc.frame();
		int w = doc.width;
		int h = doc.height;
		PixelOps.Mode mode = switch (tool) {
			case ERASER -> PixelOps.Mode.ERASE;
			case SHADE -> button == Keys.MOUSE_RIGHT ? PixelOps.Mode.DARKEN : PixelOps.Mode.LIGHTEN;
			case DITHER -> PixelOps.Mode.DITHER;
			default -> PixelOps.Mode.PAINT;
		};
		int color = colorFor(button);
		float opacity = tool == Tool.PENCIL && s.opacity >= 0.999f ? 1f : s.opacity;
		boolean[] guard = s.opacity < 0.999f || tool == Tool.SHADE ? touched : null;
		for (int[] p : mirrored(x, y)) {
			PixelOps.stamp(px, w, h, doc.selection, p[0], p[1], s.size, s.round, color, opacity, mode, guard);
		}
	}

	private List<int[]> mirrored(int x, int y) {
		List<int[]> out = new ArrayList<>(4);
		out.add(new int[] {x, y});
		if (mirrorX) {
			out.add(new int[] {doc.width - 1 - x, y});
		}
		if (mirrorY) {
			out.add(new int[] {x, doc.height - 1 - y});
		}
		if (mirrorX && mirrorY) {
			out.add(new int[] {doc.width - 1 - x, doc.height - 1 - y});
		}
		return out;
	}

	/** Draws the shape being dragged (line / rectangle / ellipse / gradient) into {@code px}. */
	private void applyShape(int[] px, int x0, int y0, int x1, int y1) {
		ToolSettings s = ts();
		int w = doc.width;
		int h = doc.height;
		int color = colorFor(dragButton);
		if (tool == Tool.GRADIENT) {
			int c0 = dragButton == Keys.MOUSE_RIGHT ? secondary : primary;
			int c1 = dragButton == Keys.MOUSE_RIGHT ? primary : secondary;
			PixelOps.gradient(px, w, h, doc.selection, x0, y0, x1, y1, c0, c1, s.steps);
			return;
		}
		if (Keys.isKeyDown(Keys.LEFT_SHIFT) && (tool == Tool.RECT || tool == Tool.ELLIPSE)) {
			int d = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
			x1 = x0 + (x1 >= x0 ? d : -d);
			y1 = y0 + (y1 >= y0 ? d : -d);
		}
		List<int[][]> copies = new ArrayList<>();
		copies.add(new int[][] {{x0, y0}, {x1, y1}});
		if (mirrorX) {
			copies.add(new int[][] {{w - 1 - x0, y0}, {w - 1 - x1, y1}});
		}
		if (mirrorY) {
			copies.add(new int[][] {{x0, h - 1 - y0}, {x1, h - 1 - y1}});
		}
		if (mirrorX && mirrorY) {
			copies.add(new int[][] {{w - 1 - x0, h - 1 - y0}, {w - 1 - x1, h - 1 - y1}});
		}
		for (int[][] c : copies) {
			java.util.function.BiConsumer<Integer, Integer> plot = (x, y) -> PixelOps.stamp(px, w, h, doc.selection, x, y, s.size, true, color, 1f,
					PixelOps.Mode.PAINT, null);
			switch (tool) {
				case LINE -> PixelOps.line(c[0][0], c[0][1], c[1][0], c[1][1], plot);
				case RECT -> {
					if (s.filled) {
						PixelOps.rect(c[0][0], c[0][1], c[1][0], c[1][1], true, (x, y) -> PixelOps.stamp(px, w, h, doc.selection, x, y, 1, false, color, 1f,
								PixelOps.Mode.PAINT, null));
					} else {
						PixelOps.rect(c[0][0], c[0][1], c[1][0], c[1][1], false, plot);
					}
				}
				case ELLIPSE -> {
					if (s.filled) {
						PixelOps.ellipse(c[0][0], c[0][1], c[1][0], c[1][1], true, (x, y) -> PixelOps.stamp(px, w, h, doc.selection, x, y, 1, false, color, 1f,
								PixelOps.Mode.PAINT, null));
					} else {
						PixelOps.ellipse(c[0][0], c[0][1], c[1][0], c[1][1], false, plot);
					}
				}
				default -> {
				}
			}
		}
	}

	// ---- Selection ----

	private void selectAll() {
		doc.checkpoint("Select all");
		doc.selection = new boolean[doc.width * doc.height];
		java.util.Arrays.fill(doc.selection, true);
		doc.changed();
	}

	private void deselect() {
		dropFloating();
		if (doc.selection != null) {
			doc.checkpoint("Deselect");
			doc.selection = null;
			doc.changed();
		}
	}

	private void invertSelection() {
		doc.checkpoint("Invert selection");
		boolean[] inverted = PixelOps.invert(doc.selection, doc.width * doc.height);
		doc.selection = PixelOps.any(inverted) ? inverted : null;
		doc.changed();
	}

	private int selectionOp() {
		if (Keys.isKeyDown(Keys.LEFT_SHIFT) || Keys.isKeyDown(Keys.RIGHT_SHIFT)) {
			return 1;
		}
		if (Keys.isKeyDown(Keys.LEFT_ALT)) {
			return 2;
		}
		return ts().mode;
	}

	private void commitSelection(boolean[] added) {
		doc.checkpoint("Select");
		boolean[] combined = PixelOps.combine(doc.selection, added, selectOp);
		doc.selection = PixelOps.any(combined) ? combined : null;
		doc.changed();
	}

	/** Lifts the selected pixels (or everything) so they can be dragged. */
	private void liftFloating() {
		if (floatPx != null) {
			return;
		}
		int[] frame = doc.frame();
		boolean[] mask = doc.selection;
		if (mask == null) {
			mask = new boolean[frame.length];
			java.util.Arrays.fill(mask, true);
		}
		doc.checkpoint("Move");
		floatPx = new int[frame.length];
		floatMask = mask.clone();
		for (int i = 0; i < frame.length; i++) {
			if (mask[i]) {
				floatPx[i] = frame[i];
				frame[i] = 0;
			}
		}
		floatDx = 0;
		floatDy = 0;
		doc.changed();
	}

	/** Puts floating pixels down where they are now. */
	private void dropFloating() {
		if (floatPx == null) {
			return;
		}
		int[] frame = doc.frame();
		int w = doc.width;
		int h = doc.height;
		boolean[] moved = new boolean[frame.length];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int i = y * w + x;
				if (!floatMask[i]) {
					continue;
				}
				int tx = x + floatDx;
				int ty = y + floatDy;
				if (tx >= 0 && ty >= 0 && tx < w && ty < h) {
					frame[ty * w + tx] = PixelOps.over(frame[ty * w + tx], floatPx[i], 1f);
					moved[ty * w + tx] = true;
				}
			}
		}
		boolean wasEverything = doc.selection == null;
		doc.selection = wasEverything || !PixelOps.any(moved) ? null : moved;
		floatPx = null;
		floatMask = null;
		doc.changed();
	}

	private void copySelection(boolean cut) {
		int[] frame = doc.frame();
		boolean[] mask = doc.selection;
		int[] b = PixelOps.bounds(mask, doc.width, doc.height);
		clipW = b[2] - b[0] + 1;
		clipH = b[3] - b[1] + 1;
		clipX = b[0];
		clipY = b[1];
		clipPx = new int[clipW * clipH];
		clipMask = new boolean[clipW * clipH];
		for (int y = 0; y < clipH; y++) {
			for (int x = 0; x < clipW; x++) {
				int i = (b[1] + y) * doc.width + b[0] + x;
				boolean in = mask == null || mask[i];
				clipMask[y * clipW + x] = in;
				clipPx[y * clipW + x] = in ? frame[i] : 0;
			}
		}
		if (cut) {
			doc.checkpoint("Cut");
			for (int i = 0; i < frame.length; i++) {
				if (mask == null || mask[i]) {
					frame[i] = 0;
				}
			}
			doc.changed();
		}
		setStatus((cut ? "Cut " : "Copied ") + clipW + " x " + clipH);
	}

	private void paste() {
		if (clipPx == null) {
			setStatus("Nothing copied yet");
			return;
		}
		int x = clipX + clipW <= doc.width ? clipX : 0;
		int y = clipY + clipH <= doc.height ? clipY : 0;
		pasteRegion(clipPx, clipMask, clipW, clipH, x, y);
	}

	/** Puts a region in as floating pixels (move tool active) at (x, y). */
	private void pasteRegion(int[] px, boolean[] mask, int pw, int ph, int x, int y) {
		dropFloating();
		doc.checkpoint("Paste");
		int w = doc.width;
		int h = doc.height;
		floatPx = new int[w * h];
		floatMask = new boolean[w * h];
		for (int yy = 0; yy < ph; yy++) {
			for (int xx = 0; xx < pw; xx++) {
				int tx = x + xx;
				int ty = y + yy;
				if (tx < 0 || ty < 0 || tx >= w || ty >= h) {
					continue;
				}
				int si = yy * pw + xx;
				if (mask != null && !mask[si]) {
					continue;
				}
				floatPx[ty * w + tx] = px[si];
				floatMask[ty * w + tx] = true;
			}
		}
		floatDx = 0;
		floatDy = 0;
		tool = Tool.MOVE;
		doc.changed();
	}

	// ---- History & saving ----

	private void undo() {
		dropFloating();
		String label = doc.undo();
		setStatus(label == null ? "Nothing to undo" : "Undid " + label.toLowerCase(Locale.ROOT));
		filterDirty = filter != null;
		if (canvasTex != null && (canvasTex.width != doc.width || canvasTex.height != doc.height)) {
			fitView();
		}
	}

	private void redo() {
		dropFloating();
		String label = doc.redo();
		setStatus(label == null ? "Nothing to redo" : "Redid " + label.toLowerCase(Locale.ROOT));
		filterDirty = filter != null;
	}

	private boolean save() {
		dropFloating();
		try {
			doc.save(file);
			browser.onSaved(key);
			setStatus("Saved " + file.getFileName());
			return true;
		} catch (IOException e) {
			setStatus("Couldn't save: " + e.getMessage());
			return false;
		}
	}

	private void saveAndReload() {
		if (save()) {
			browser.applyAndReload();
			setStatus("Saved - reloading resources...");
		}
	}

	// ---- Input ----

	private int[] pixelAt(double mouseX, double mouseY) {
		return new int[] {(int) Math.floor((mouseX - viewX) / zoom), (int) Math.floor((mouseY - viewY) / zoom)};
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		double mx = click.x();
		double my = click.y();
		dragButton = click.button();
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(mx, my)) {
				hit.action().run();
				if (drag == Drag.PREVIEW) {
					panStartX = mx;
					panStartY = my;
				}
				return true;
			}
		}
		if (super.mouseClicked(click, doubled)) {
			return true;
		}
		if (hexField != null && hexField.isFocused()) {
			hexField.setFocused(false);
			setFocused(null);
		}
		if (!VeloUi.inside(mx, my, vpX, vpY, vpW, vpH) || loadError != null) {
			return false;
		}
		boolean pan = click.button() == Keys.MOUSE_MIDDLE || tool == Tool.HAND || Keys.isKeyDown(Keys.SPACE);
		if (pan) {
			drag = Drag.PAN;
			panStartX = mx;
			panStartY = my;
			panViewX = viewX;
			panViewY = viewY;
			return true;
		}
		int[] p = pixelAt(mx, my);
		int x = p[0];
		int y = p[1];
		boolean alt = Keys.isKeyDown(Keys.LEFT_ALT);
		if (tool == Tool.PICKER || (alt && (tool.paints() || tool.shapes() || tool == Tool.FILL))) {
			pick(x, y, click.button());
			return true;
		}
		if (filter != null) {
			setStatus("Apply or cancel the filter first");
			return true;
		}
		startX = x;
		startY = y;
		curX = x;
		curY = y;
		if (tool != Tool.MOVE) {
			dropFloating();
		}
		switch (tool) {
			case PENCIL, BRUSH, ERASER, SHADE, DITHER -> {
				doc.checkpoint(tool.label);
				touched = new boolean[doc.width * doc.height];
				boolean shift = Keys.isKeyDown(Keys.LEFT_SHIFT) || Keys.isKeyDown(Keys.RIGHT_SHIFT);
				if (shift && strokeEndX >= 0) {
					int b = click.button();
					PixelOps.line(strokeEndX, strokeEndY, x, y, (lx, ly) -> dab(lx, ly, b));
				} else {
					dab(x, y, click.button());
				}
				lastX = x;
				lastY = y;
				drag = Drag.PAINT;
				doc.changed();
				if (tool != Tool.ERASER) {
					rememberColor(colorFor(click.button()));
				}
			}
			case FILL -> {
				if (x >= 0 && y >= 0 && x < doc.width && y < doc.height) {
					doc.checkpoint("Fill");
					ToolSettings s = ts();
					for (int[] m : mirrored(x, y)) {
						PixelOps.fill(doc.frame(), doc.width, doc.height, doc.selection, m[0], m[1], colorFor(click.button()), s.tolerance, s.contiguous, s.opacity);
					}
					rememberColor(colorFor(click.button()));
					doc.changed();
				}
			}
			case LINE, RECT, ELLIPSE, GRADIENT -> drag = Drag.SHAPE;
			case SELECT_RECT, SELECT_ELLIPSE -> {
				selectOp = selectionOp();
				drag = Drag.SELECT;
				pendingMask = null;
			}
			case LASSO -> {
				selectOp = selectionOp();
				drag = Drag.LASSO;
				lassoPoints.clear();
				float[] f = pixelAtFloat(mx, my);
				lassoPoints.add(f[0]);
				lassoPoints.add(f[1]);
			}
			case WAND -> {
				if (x >= 0 && y >= 0 && x < doc.width && y < doc.height) {
					selectOp = selectionOp();
					ToolSettings s = ts();
					commitSelection(PixelOps.similar(doc.frame(), doc.width, doc.height, x, y, s.tolerance, s.contiguous));
				}
			}
			case MOVE -> {
				liftFloating();
				drag = Drag.MOVE;
				lastX = x;
				lastY = y;
			}
			default -> {
			}
		}
		return true;
	}

	private float[] pixelAtFloat(double mouseX, double mouseY) {
		return new float[] {(float) ((mouseX - viewX) / zoom), (float) ((mouseY - viewY) / zoom)};
	}

	private void pick(int x, int y, int button) {
		if (x < 0 || y < 0 || x >= doc.width || y >= doc.height) {
			return;
		}
		int c = doc.frame()[y * doc.width + x];
		if ((c >>> 24) == 0) {
			setStatus("Transparent pixel");
			return;
		}
		if (button == Keys.MOUSE_RIGHT) {
			secondary = c;
		} else {
			setPrimary(c);
		}
		rememberColor(c);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		double mx = click.x();
		double my = click.y();
		switch (drag) {
			case SLIDER -> {
				slider.update(mx);
				return true;
			}
			case SV, HUE, ALPHA -> {
				updateColorDrag(mx, my);
				return true;
			}
			case PREVIEW -> {
				yaw += (float) (mx - panStartX) * 1.2f;
				pitch = Math.max(-89, Math.min(89, pitch + (float) (my - panStartY) * 1.2f));
				panStartX = mx;
				panStartY = my;
				return true;
			}
			case PAN -> {
				viewX = Math.round(panViewX + (float) (mx - panStartX));
				viewY = Math.round(panViewY + (float) (my - panStartY));
				return true;
			}
			case PAINT -> {
				int[] p = pixelAt(mx, my);
				if (p[0] != lastX || p[1] != lastY) {
					int b = dragButton;
					PixelOps.line(lastX, lastY, p[0], p[1], (lx, ly) -> dab(lx, ly, b));
					lastX = p[0];
					lastY = p[1];
					doc.changed();
				}
				return true;
			}
			case SHAPE -> {
				int[] p = pixelAt(mx, my);
				curX = p[0];
				curY = p[1];
				return true;
			}
			case SELECT -> {
				int[] p = pixelAt(mx, my);
				curX = Math.max(0, Math.min(doc.width - 1, p[0]));
				curY = Math.max(0, Math.min(doc.height - 1, p[1]));
				int sx = Math.max(0, Math.min(doc.width - 1, startX));
				int sy = Math.max(0, Math.min(doc.height - 1, startY));
				pendingMask = tool == Tool.SELECT_ELLIPSE ? PixelOps.ellipseMask(doc.width, doc.height, sx, sy, curX, curY)
						: PixelOps.rectMask(doc.width, doc.height, sx, sy, curX, curY);
				return true;
			}
			case LASSO -> {
				float[] f = pixelAtFloat(mx, my);
				int n = lassoPoints.size();
				if (n < 2 || Math.hypot(f[0] - lassoPoints.get(n - 2), f[1] - lassoPoints.get(n - 1)) > 0.4) {
					lassoPoints.add(f[0]);
					lassoPoints.add(f[1]);
				}
				return true;
			}
			case MOVE -> {
				int[] p = pixelAt(mx, my);
				floatDx += p[0] - lastX;
				floatDy += p[1] - lastY;
				lastX = p[0];
				lastY = p[1];
				return true;
			}
			default -> {
				return super.mouseDragged(click, offsetX, offsetY);
			}
		}
	}

	@Override
	public boolean mouseReleased(Click click) {
		Drag was = drag;
		drag = Drag.NONE;
		slider = null;
		switch (was) {
			case PAINT -> {
				strokeEndX = lastX;
				strokeEndY = lastY;
				touched = null;
				return true;
			}
			case SHAPE -> {
				doc.checkpoint(tool.label);
				applyShape(doc.frame(), startX, startY, curX, curY);
				if (tool != Tool.GRADIENT) {
					rememberColor(colorFor(dragButton));
				}
				doc.changed();
				return true;
			}
			case SELECT -> {
				if (pendingMask == null || (curX == startX && curY == startY && selectOp == 0)) {
					pendingMask = null;
					deselect();
				} else {
					boolean[] mask = pendingMask;
					pendingMask = null;
					commitSelection(mask);
				}
				return true;
			}
			case LASSO -> {
				float[] pts = new float[lassoPoints.size()];
				for (int i = 0; i < pts.length; i++) {
					pts[i] = lassoPoints.get(i);
				}
				lassoPoints.clear();
				if (pts.length >= 6) {
					commitSelection(PixelOps.polygonMask(doc.width, doc.height, pts));
				} else if (selectOp == 0) {
					deselect();
				}
				return true;
			}
			case MOVE -> {
				doc.changed();
				return true;
			}
			case NONE -> {
				return super.mouseReleased(click);
			}
			default -> {
				return true;
			}
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (VeloUi.inside(mouseX, mouseY, vpX, vpY, vpW, vpH)) {
			if (Keys.isKeyDown(Keys.LEFT_SHIFT) && (tool.paints() || tool == Tool.LINE || tool == Tool.RECT || tool == Tool.ELLIPSE)) {
				ts().size = Math.max(1, Math.min(64, ts().size + (verticalAmount > 0 ? 1 : -1)));
				return true;
			}
			zoomAt(mouseX, mouseY, verticalAmount > 0 ? 1 : -1);
			return true;
		}
		if (rightTab == 0 && VeloUi.inside(mouseX, mouseY, rpX, rpY + 150, rpW, rpH - 150)) {
			previewZoom = Math.max(0.4f, Math.min(4f, previewZoom * (verticalAmount > 0 ? 1.12f : 1 / 1.12f)));
			return true;
		}
		if (VeloUi.inside(mouseX, mouseY, tlX, tlY, tlW, tlH)) {
			frameScroll -= (float) verticalAmount * 30;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (hexField != null && hexField.isFocused()) {
			if (input.key() == Keys.ENTER || input.key() == Keys.ESCAPE) {
				hexField.setFocused(false);
				setFocused(null);
				syncHex();
				return true;
			}
			return super.keyPressed(input);
		}
		int key = input.key();
		boolean ctrl = (input.modifiers() & Keys.MOD_CONTROL) != 0;
		boolean shift = (input.modifiers() & Keys.MOD_SHIFT) != 0;
		if (key == Keys.ESCAPE) {
			if (filter != null) {
				cancelFilter();
				return true;
			}
			if (floatPx != null) {
				dropFloating();
				return true;
			}
			if (doc.selection != null) {
				deselect();
				return true;
			}
			return super.keyPressed(input);
		}
		if (ctrl) {
			if (key == Keys.Z) {
				if (shift) {
					redo();
				} else {
					undo();
				}
				return true;
			}
			if (key == Keys.Y) {
				redo();
				return true;
			}
			if (key == Keys.letter('S')) {
				if (shift) {
					saveAndReload();
				} else {
					save();
				}
				return true;
			}
			if (key == Keys.letter('A')) {
				selectAll();
				return true;
			}
			if (key == Keys.letter('D')) {
				deselect();
				return true;
			}
			if (key == Keys.I && shift) {
				invertSelection();
				return true;
			}
			if (key == Keys.C) {
				copySelection(false);
				return true;
			}
			if (key == Keys.letter('X')) {
				copySelection(true);
				return true;
			}
			if (key == Keys.V) {
				paste();
				return true;
			}
			return super.keyPressed(input);
		}
		if (key == Keys.ENTER || key == Keys.KP_ENTER) {
			if (filter != null) {
				applyFilter();
			} else {
				dropFloating();
			}
			return true;
		}
		if (key == Keys.DELETE || key == Keys.BACKSPACE) {
			dropFloating();
			quick("clear");
			return true;
		}
		if (key == Keys.LEFT || key == Keys.RIGHT || key == Keys.UP || key == Keys.DOWN) {
			int dx = key == Keys.LEFT ? -1 : key == Keys.RIGHT ? 1 : 0;
			int dy = key == Keys.UP ? -1 : key == Keys.DOWN ? 1 : 0;
			if (tool == Tool.MOVE || floatPx != null) {
				liftFloating();
				floatDx += dx;
				floatDy += dy;
			} else if (dx != 0 && doc.frames.size() > 1) {
				doc.current = Math.floorMod(doc.current + dx, doc.frames.size());
				filterDirty = filter != null;
			}
			return true;
		}
		if (key == Keys.P) {
			playing = !playing;
			return true;
		}
		if (key == Keys.letter('X')) {
			swapColors();
			return true;
		}
		if (key == Keys.digit(0)) {
			fitView();
			return true;
		}
		for (Tool t : Tool.values()) {
			if (key == Keys.letter(t.key)) {
				selectTool(t);
				return true;
			}
		}
		return super.keyPressed(input);
	}

	/** Dev-only (screenshot tour): a few strokes, a selection and the Colorize panel. */
	public void demoForTour(boolean colorize) {
		primary = 0xFFE3262B;
		hsv = PixelOps.hsv(primary);
		syncHex();
		doc.checkpoint("Tour");
		PixelOps.line(1, 1, doc.width - 2, doc.height - 2, (x, y) -> PixelOps.stamp(doc.frame(), doc.width, doc.height, null, x, y, 2, true, primary,
				1f, PixelOps.Mode.PAINT, null));
		doc.selection = PixelOps.rectMask(doc.width, doc.height, 2, 2, doc.width / 2, doc.height / 2);
		doc.changed();
		if (colorize) {
			switchTab(1);
			startFilter(Filter.COLORIZE);
		}
	}

	// ---- Dev-only (screenshot tour): drives the editor frame by frame for promo captures ----

	/** Screen position of texture pixel coordinates (pixel centres at +0.5). */
	public float[] screenPointForTour(float px, float py) {
		computeLayout();
		if (zoom < 0) {
			fitView();
		}
		return new float[] {viewX + px * zoom, viewY + py * zoom};
	}

	/** Zoom so {@code cx, cy} (texture pixels) sits in the middle of the canvas; zoom <= 0 fits the texture. */
	public void viewForTour(float z, float cx, float cy) {
		computeLayout();
		if (z <= 0) {
			fitView();
			return;
		}
		zoom = z;
		viewX = vpX + vpW / 2f - cx * z;
		viewY = vpY + vpH / 2f - cy * z;
	}

	public void toolForTour(String name, int size) {
		selectTool(Tool.valueOf(name));
		ts().size = size;
	}

	public void colorForTour(int argb) {
		setPrimary(argb);
	}

	/** Paints a line with the current tool; {@code start} begins a new undo step. */
	public void strokeForTour(int x0, int y0, int x1, int y1, boolean start) {
		if (start) {
			doc.checkpoint(tool.label);
			touched = new boolean[doc.width * doc.height];
		}
		PixelOps.line(x0, y0, x1, y1, (x, y) -> dab(x, y, Keys.MOUSE_LEFT));
		doc.changed();
	}

	public void wandForTour(int x, int y, float tolerance) {
		selectOp = 0;
		commitSelection(PixelOps.similar(doc.frame(), doc.width, doc.height, x, y, tolerance, true));
	}

	public void deselectForTour() {
		deselect();
	}

	/** Opens (or updates) a filter on the Adjust tab: COLORIZE takes hue, saturation, lightness. */
	public void filterForTour(String name, float a, float b, float c) {
		switchTab(1);
		Filter f = Filter.valueOf(name);
		if (filter != f) {
			startFilter(f);
		}
		allFrames = true;
		fa = a;
		fb = b;
		fc = c;
		filterDirty = true;
	}

	public void applyFilterForTour() {
		applyFilter();
	}

	public void tabForTour(int tab) {
		switchTab(tab);
	}

	public void previewForTour(float yaw, float pitch, float zoom) {
		autoRotate = false;
		this.yaw = yaw;
		this.pitch = pitch;
		previewZoom = zoom;
	}

	public void playForTour(boolean play) {
		playing = play;
	}

	public void saveForTour() {
		save();
	}

	// ---- Lifecycle ----

	@Override
	protected void requestClose() {
		dropFloating();
		if (doc.dirty && !discardConfirmed && loadError == null) {
			this.client.setScreen(new VeloConfirmScreen(this, "Unsaved changes", "Close without saving your changes to " + this.title.getString() + "?",
					"Discard", () -> {
						discardConfirmed = true;
						requestClose();
					}));
			return;
		}
		finished = true;
		super.requestClose();
	}

	@Override
	public void removed() {
		if (finished) {
			for (GpuImage image : new GpuImage[] {canvasTex, checkerTex, svTex, hueTex, previewTex, stripTex}) {
				if (image != null) {
					image.release();
				}
			}
		}
		super.removed();
	}
}
