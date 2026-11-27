package net.veloclient.velo.client.hud;

/**
 * Velo Turbo HUD canvas (26.2+): the whole HUD is drawn into its own transparent texture only when it was rebuilt
 * (HudFrameCache records it at 120-360 Hz), and every other frame the world just gets ONE textured quad of that
 * texture. Before, every frame between recordings still handed ~hundreds of remembered elements back to the GUI
 * extractor, sorted them, built their vertices and uploaded/drew them again - ~20% of the render thread with a busy HUD
 * (2026-11-26 profile: Hud.extractRenderState 12% + GuiRenderer.render 13%).
 *
 * <p>How: while the HUD is recorded, its elements go into a private GuiRenderState instead of the frame's. Elements that
 * can't live in a transparent texture - the crosshair inverts the pixels behind it, additive/invert pipelines, picture-in-
 * picture, oversized items - stay live in the frame's real state (above the canvas, the GUI layering does that by itself).
 * A second GuiRenderer (no changes to vanilla's) renders the private state into the texture right before the real GUI
 * pass; one mixin hook redirects its "main render target". Any failure turns the canvas off for the session and the old
 * replay cache takes over. {@code -Dvelo.noHudCanvas=true} turns it off for A/B runs.
 */
public final class HudCanvas {

	private HudCanvas() {
	}

	//? if >=26.2 {
	/*private static final boolean DISABLED = Boolean.getBoolean("velo.noHudCanvas");
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("velo-hud-canvas");

	// The private state the HUD is recorded into while a canvas recording runs.
	static net.minecraft.client.renderer.state.gui.GuiRenderState state;
	private static net.minecraft.client.gui.render.GuiRenderer renderer;
	private static com.mojang.blaze3d.pipeline.TextureTarget target;
	private static boolean broken;
	private static boolean rendering;
	// profiling (RenderProfiler): canvas textures drawn so far
	public static int renders;
	// A canvas recording that has not been drawn into the texture yet.
	private static boolean dirty;
	// The texture holds a complete HUD for {@link #width} x {@link #height} x {@link #scale}.
	private static boolean valid;
	private static int width;
	private static int height;
	private static int scale;
	private static final org.joml.Matrix3x2f IDENTITY = new org.joml.Matrix3x2f();

	public static boolean enabled() {
		return !DISABLED && !broken && net.veloclient.velo.client.modules.performance.PerformanceBoostModule.hudCanvas;
	}

	// ---- tight blits: the canvas is shown through a few rectangles around what was really drawn, not one full-screen quad
	// (4K at 1000 FPS made the full-screen premultiplied blend measurable). 8-pixel cells, 3 cells of padding for shadows.
	private static final int CELL = 8;
	private static boolean[] cells = new boolean[0];
	private static int cellsW;
	private static int cellsH;
	// rectangles of the PREVIOUS recording (cell units x0,y0,x1,y1); record frames use them, so a brand-new element shows
	// one frame late at worst; null = unknown -> full screen
	private static int[] rects;

	private static void startCover(int guiWidth, int guiHeight) {
		cellsW = guiWidth / CELL + 1;
		cellsH = guiHeight / CELL + 1;
		if (cells.length != cellsW * cellsH) {
			cells = new boolean[cellsW * cellsH];
		} else {
			java.util.Arrays.fill(cells, false);
		}
	}

	static void cover(net.minecraft.client.gui.navigation.ScreenRectangle b) {
		if (b == null || cells.length == 0) {
			return;
		}
		int x0 = Math.max(0, Math.floorDiv(b.left() - 3, CELL));
		int y0 = Math.max(0, Math.floorDiv(b.top() - 3, CELL));
		int x1 = Math.min(cellsW - 1, Math.floorDiv(b.right() + 3, CELL));
		int y1 = Math.min(cellsH - 1, Math.floorDiv(b.bottom() + 3, CELL));
		for (int y = y0; y <= y1; y++) {
			int row = y * cellsW;
			for (int x = x0; x <= x1; x++) {
				cells[row + x] = true;
			}
		}
	}

	// Where an element goes: into the canvas (and its area counted) when its pipeline works on a transparent texture.
	static net.minecraft.client.renderer.state.gui.GuiRenderState target(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.client.gui.navigation.ScreenRectangle bounds, net.minecraft.client.renderer.state.gui.GuiRenderState canvas, net.minecraft.client.renderer.state.gui.GuiRenderState real) {
		if (safe(pipeline)) {
			cover(bounds);
			return canvas;
		}
		return real;
	}

	// Cells -> a few rectangles: horizontal runs, equal runs of neighbouring rows stacked, then merged down to 12.
	private static void buildRects() {
		java.util.ArrayList<int[]> open = new java.util.ArrayList<>();
		java.util.ArrayList<int[]> done = new java.util.ArrayList<>();
		for (int y = 0; y < cellsH; y++) {
			java.util.ArrayList<int[]> next = new java.util.ArrayList<>();
			int x = 0;
			while (x < cellsW) {
				if (!cells[y * cellsW + x]) {
					x++;
					continue;
				}
				int start = x;
				while (x < cellsW && cells[y * cellsW + x]) {
					x++;
				}
				int[] stacked = null;
				for (int[] r : open) {
					if (r[0] == start && r[2] == x && r[3] == y) {
						stacked = r;
						break;
					}
				}
				if (stacked != null) {
					stacked[3] = y + 1;
					open.remove(stacked);
					next.add(stacked);
				} else {
					next.add(new int[] {start, y, x, y + 1});
				}
			}
			done.addAll(open);   // rows that did not continue
			open = next;
		}
		done.addAll(open);
		while (done.size() > 12) {
			int bi = 0;
			int bj = 1;
			long best = Long.MAX_VALUE;
			for (int i = 0; i < done.size(); i++) {
				for (int j = i + 1; j < done.size(); j++) {
					int[] a = done.get(i);
					int[] b = done.get(j);
					long ua = (long) (Math.max(a[2], b[2]) - Math.min(a[0], b[0])) * (Math.max(a[3], b[3]) - Math.min(a[1], b[1]));
					long grow = ua - (long) (a[2] - a[0]) * (a[3] - a[1]) - (long) (b[2] - b[0]) * (b[3] - b[1]);
					if (grow < best) {
						best = grow;
						bi = i;
						bj = j;
					}
				}
			}
			int[] a = done.get(bi);
			int[] b = done.remove(bj);
			a[0] = Math.min(a[0], b[0]);
			a[1] = Math.min(a[1], b[1]);
			a[2] = Math.max(a[2], b[2]);
			a[3] = Math.max(a[3], b[3]);
		}
		int[] flat = new int[done.size() * 4];
		for (int i = 0; i < done.size(); i++) {
			System.arraycopy(done.get(i), 0, flat, i * 4, 4);
		}
		rects = flat;
	}

	// True while the canvas GuiRenderer runs (the mixin hands it the canvas texture as its target).
	public static boolean isCanvasRenderer(Object guiRenderer) {
		return rendering && guiRenderer == renderer;
	}

	public static com.mojang.blaze3d.pipeline.RenderTarget target() {
		return target;
	}

	// The canvas can be shown (a complete HUD of this size is in the texture).
	static boolean ready(int guiWidth, int guiHeight, int guiScale) {
		return enabled() && valid && target != null && guiWidth == width && guiHeight == height && guiScale == scale;
	}

	// Called when a recording starts: prepares texture and renderer; false = use the old replay cache.
	static boolean begin(int guiWidth, int guiHeight, int guiScale) {
		if (!enabled()) {
			return false;
		}
		try {
			var minecraft = net.minecraft.client.Minecraft.getInstance();
			var main = minecraft.gameRenderer.mainRenderTarget();
			if (renderer == null) {
				state = new net.minecraft.client.renderer.state.gui.GuiRenderState();
				// created by the mixin hook (it owns the FeatureRenderDispatcher); nothing to do before the first GUI pass
				return false;
			}
			if (target == null || target.width != main.width || target.height != main.height) {
				if (target == null) {
					target = HudCanvasCompat.newTarget(main.width, main.height);
				} else {
					target.resize(main.width, main.height);
				}
				valid = false;
			}
			if (dirty) {
				state.reset();   // a recording nobody drew (e.g. no GUI pass in between)
			}
			if (guiWidth != width || guiHeight != height || guiScale != scale) {
				rects = null;
			}
			width = guiWidth;
			height = guiHeight;
			scale = guiScale;
			valid = false;   // complete again when this recording ends
			startCover(guiWidth, guiHeight);
			return true;
		} catch (Throwable t) {
			fail("begin", t);
			return false;
		}
	}

	static void end(boolean completed) {
		if (completed) {
			dirty = true;
			valid = true;
			try {
				buildRects();
			} catch (RuntimeException e) {
				rects = null;
			}
		} else {
			dirty = false;
			valid = false;
			rects = null;
			if (state != null) {
				state.reset();
			}
		}
	}

	// The one quad that shows the canvas, added to the frame's real state.
	static void addBlit(net.minecraft.client.renderer.state.gui.GuiRenderState frame) {
		frame.nextStratum();
		var setup = net.minecraft.client.gui.render.TextureSetup.singleTexture(target.getColorTextureView(),
				com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getRepeat(com.mojang.blaze3d.textures.FilterMode.NEAREST));
		int[] r = rects;
		if (r == null) {
			frame.addBlitToCurrentLayer(new net.minecraft.client.renderer.state.gui.BlitRenderState(
					net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, setup,
					IDENTITY, 0, 0, width, height, 0.0F, 1.0F, 1.0F, 0.0F, -1, null));
			return;
		}
		for (int i = 0; i < r.length; i += 4) {
			int x0 = Math.min(width, r[i] * CELL);
			int y0 = Math.min(height, r[i + 1] * CELL);
			int x1 = Math.min(width, r[i + 2] * CELL);
			int y1 = Math.min(height, r[i + 3] * CELL);
			if (x1 > x0 && y1 > y0) {
				frame.addBlitToCurrentLayer(new net.minecraft.client.renderer.state.gui.BlitRenderState(
						net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, setup, IDENTITY, x0, y0, x1, y1,
						x0 / (float) width, x1 / (float) width, 1.0F - y0 / (float) height, 1.0F - y1 / (float) height, -1, null));
			}
		}
	}

	// Pipelines whose result is the same on a transparent texture as on the finished world picture.
	static boolean safe(com.mojang.blaze3d.pipeline.RenderPipeline pipeline) {
		return pipeline == net.minecraft.client.renderer.RenderPipelines.GUI
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.GUI_OPAQUE_TEXTURED_BACKGROUND
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.GUI_TEXT
				|| pipeline == net.minecraft.client.renderer.RenderPipelines.GUI_TEXT_GRAYSCALE;
	}

	// Runs from the mixin at the head of every GuiRenderer.render(): draws a pending canvas recording into its texture.
	public static void renderBefore(net.minecraft.client.gui.render.GuiRenderer real,
			net.minecraft.client.renderer.feature.FeatureRenderDispatcher dispatcher) {
		if (rendering || broken || DISABLED) {
			return;
		}
		try {
			if (renderer == null) {
				if (state == null) {
					return;   // no HUD recorded yet
				}
				renderer = new net.minecraft.client.gui.render.GuiRenderer(state, dispatcher, java.util.List.of());
				return;
			}
			if (!dirty || target == null) {
				return;
			}
			dirty = false;
			rendering = true;
			// clear to transparent, then the normal GUI pass draws into the texture (the mixin swaps its target)
			com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Velo HUD canvas clear",
					target.getColorTextureView(), java.util.Optional.of(new org.joml.Vector4f(0.0F)), null, java.util.OptionalDouble.empty()).close();
			renderer.render();
			renderer.endFrame();   // animated items (glint...) are redrawn on the next canvas pass
			renders++;
			if (renders == 1) {
				LOG.info("HUD canvas active ({}x{})", target.width, target.height);
			}
		} catch (Throwable t) {
			fail("render", t);
		} finally {
			rendering = false;
		}
	}

	private static void fail(String where, Throwable t) {
		broken = true;
		valid = false;
		dirty = false;
		LOG.warn("HUD canvas failed in {} - the HUD uses the plain replay cache for the rest of this session", where, t);
	}

	// A resource reload or a world change: what was drawn may point at stale sprites.
	public static void invalidate() {
		valid = false;
	}
	*///?} else if >=1.21.11 && <26.1 {
	private static final boolean DISABLED = Boolean.getBoolean("velo.noHudCanvas");
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("velo-hud-canvas");

	// The private state the HUD is recorded into while a canvas recording runs.
	static net.minecraft.client.gui.render.state.GuiRenderState state;
	private static net.minecraft.client.gui.render.GuiRenderer renderer;
	private static net.minecraft.client.gl.SimpleFramebuffer target;
	private static boolean broken;
	private static boolean rendering;
	// profiling: canvas textures drawn so far
	public static int renders;
	// A canvas recording that has not been drawn into the texture yet.
	private static boolean dirty;
	// The texture holds a complete HUD for width x height x scale.
	private static boolean valid;
	private static int width;
	private static int height;
	private static int scale;
	private static final org.joml.Matrix3x2f IDENTITY = new org.joml.Matrix3x2f();

	public static boolean enabled() {
		return !DISABLED && !broken && net.veloclient.velo.client.modules.performance.PerformanceBoostModule.hudCanvas;
	}

	// ---- tight blits: the canvas is shown through a few rectangles around what was really drawn, not one full-screen quad
	// (4K at 1000 FPS made the full-screen premultiplied blend measurable). 8-pixel cells, 3 cells of padding for shadows.
	private static final int CELL = 8;
	private static boolean[] cells = new boolean[0];
	private static int cellsW;
	private static int cellsH;
	// rectangles of the PREVIOUS recording (cell units x0,y0,x1,y1); record frames use them, so a brand-new element shows
	// one frame late at worst; null = unknown -> full screen
	private static int[] rects;

	private static void startCover(int guiWidth, int guiHeight) {
		cellsW = guiWidth / CELL + 1;
		cellsH = guiHeight / CELL + 1;
		if (cells.length != cellsW * cellsH) {
			cells = new boolean[cellsW * cellsH];
		} else {
			java.util.Arrays.fill(cells, false);
		}
	}

	static void cover(net.minecraft.client.gui.ScreenRect b) {
		if (b == null || cells.length == 0) {
			return;
		}
		int x0 = Math.max(0, Math.floorDiv(b.getLeft() - 3, CELL));
		int y0 = Math.max(0, Math.floorDiv(b.getTop() - 3, CELL));
		int x1 = Math.min(cellsW - 1, Math.floorDiv(b.getRight() + 3, CELL));
		int y1 = Math.min(cellsH - 1, Math.floorDiv(b.getBottom() + 3, CELL));
		for (int y = y0; y <= y1; y++) {
			int row = y * cellsW;
			for (int x = x0; x <= x1; x++) {
				cells[row + x] = true;
			}
		}
	}

	// Where an element goes: into the canvas (and its area counted) when its pipeline works on a transparent texture.
	static net.minecraft.client.gui.render.state.GuiRenderState target(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, net.minecraft.client.gui.ScreenRect bounds, net.minecraft.client.gui.render.state.GuiRenderState canvas, net.minecraft.client.gui.render.state.GuiRenderState real) {
		if (safe(pipeline)) {
			cover(bounds);
			return canvas;
		}
		return real;
	}

	// Cells -> a few rectangles: horizontal runs, equal runs of neighbouring rows stacked, then merged down to 12.
	private static void buildRects() {
		java.util.ArrayList<int[]> open = new java.util.ArrayList<>();
		java.util.ArrayList<int[]> done = new java.util.ArrayList<>();
		for (int y = 0; y < cellsH; y++) {
			java.util.ArrayList<int[]> next = new java.util.ArrayList<>();
			int x = 0;
			while (x < cellsW) {
				if (!cells[y * cellsW + x]) {
					x++;
					continue;
				}
				int start = x;
				while (x < cellsW && cells[y * cellsW + x]) {
					x++;
				}
				int[] stacked = null;
				for (int[] r : open) {
					if (r[0] == start && r[2] == x && r[3] == y) {
						stacked = r;
						break;
					}
				}
				if (stacked != null) {
					stacked[3] = y + 1;
					open.remove(stacked);
					next.add(stacked);
				} else {
					next.add(new int[] {start, y, x, y + 1});
				}
			}
			done.addAll(open);   // rows that did not continue
			open = next;
		}
		done.addAll(open);
		while (done.size() > 12) {
			int bi = 0;
			int bj = 1;
			long best = Long.MAX_VALUE;
			for (int i = 0; i < done.size(); i++) {
				for (int j = i + 1; j < done.size(); j++) {
					int[] a = done.get(i);
					int[] b = done.get(j);
					long ua = (long) (Math.max(a[2], b[2]) - Math.min(a[0], b[0])) * (Math.max(a[3], b[3]) - Math.min(a[1], b[1]));
					long grow = ua - (long) (a[2] - a[0]) * (a[3] - a[1]) - (long) (b[2] - b[0]) * (b[3] - b[1]);
					if (grow < best) {
						best = grow;
						bi = i;
						bj = j;
					}
				}
			}
			int[] a = done.get(bi);
			int[] b = done.remove(bj);
			a[0] = Math.min(a[0], b[0]);
			a[1] = Math.min(a[1], b[1]);
			a[2] = Math.max(a[2], b[2]);
			a[3] = Math.max(a[3], b[3]);
		}
		int[] flat = new int[done.size() * 4];
		for (int i = 0; i < done.size(); i++) {
			System.arraycopy(done.get(i), 0, flat, i * 4, 4);
		}
		rects = flat;
	}

	// True while the canvas GuiRenderer runs (the mixin hands it the canvas texture as its framebuffer).
	public static boolean isCanvasRenderer(Object guiRenderer) {
		return rendering && guiRenderer == renderer;
	}

	public static net.minecraft.client.gl.Framebuffer target() {
		return target;
	}

	// The canvas can be shown (a complete HUD of this size is in the texture).
	static boolean ready(int guiWidth, int guiHeight, int guiScale) {
		return enabled() && valid && target != null && guiWidth == width && guiHeight == height && guiScale == scale;
	}

	// Called when a recording starts: prepares texture and renderer; false = use the old replay cache.
	static boolean begin(int guiWidth, int guiHeight, int guiScale) {
		if (!enabled()) {
			return false;
		}
		try {
			var main = net.minecraft.client.MinecraftClient.getInstance().getFramebuffer();
			if (renderer == null) {
				state = new net.minecraft.client.gui.render.state.GuiRenderState();
				// created by the mixin hook (it owns the vertex consumers and queues); nothing to do before the first GUI pass
				return false;
			}
			if (target == null || target.textureWidth != main.textureWidth || target.textureHeight != main.textureHeight) {
				if (target == null) {
					target = new net.minecraft.client.gl.SimpleFramebuffer("Velo HUD canvas", main.textureWidth, main.textureHeight, true);
				} else {
					target.resize(main.textureWidth, main.textureHeight);
				}
				valid = false;
			}
			if (dirty) {
				state.clear();   // a recording nobody drew (e.g. no GUI pass in between)
			}
			if (guiWidth != width || guiHeight != height || guiScale != scale) {
				rects = null;
			}
			width = guiWidth;
			height = guiHeight;
			scale = guiScale;
			valid = false;   // complete again when this recording ends
			startCover(guiWidth, guiHeight);
			return true;
		} catch (Throwable t) {
			fail("begin", t);
			return false;
		}
	}

	static void end(boolean completed) {
		if (completed) {
			dirty = true;
			valid = true;
			try {
				buildRects();
			} catch (RuntimeException e) {
				rects = null;
			}
		} else {
			dirty = false;
			valid = false;
			rects = null;
			if (state != null) {
				state.clear();
			}
		}
	}

	// The one quad that shows the canvas, added to the frame's real state.
	static void addBlit(net.minecraft.client.gui.render.state.GuiRenderState frame) {
		frame.createNewRootLayer();
		var setup = net.minecraft.client.texture.TextureSetup.of(target.getColorAttachmentView(),
				com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().get(com.mojang.blaze3d.textures.FilterMode.NEAREST));
		int[] r = rects;
		if (r == null) {
			frame.addSimpleElementToCurrentLayer(new net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState(
					net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, setup,
					IDENTITY, 0, 0, width, height, 0.0F, 1.0F, 1.0F, 0.0F, -1, null));
			return;
		}
		for (int i = 0; i < r.length; i += 4) {
			int x0 = Math.min(width, r[i] * CELL);
			int y0 = Math.min(height, r[i + 1] * CELL);
			int x1 = Math.min(width, r[i + 2] * CELL);
			int y1 = Math.min(height, r[i + 3] * CELL);
			if (x1 > x0 && y1 > y0) {
				frame.addSimpleElementToCurrentLayer(new net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState(
						net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, setup, IDENTITY, x0, y0, x1, y1,
						x0 / (float) width, x1 / (float) width, 1.0F - y0 / (float) height, 1.0F - y1 / (float) height, -1, null));
			}
		}
	}

	// Pipelines whose result is the same on a transparent texture as on the finished world picture.
	static boolean safe(com.mojang.blaze3d.pipeline.RenderPipeline pipeline) {
		return pipeline == net.minecraft.client.gl.RenderPipelines.GUI
				|| pipeline == net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED
				|| pipeline == net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA
				|| pipeline == net.minecraft.client.gl.RenderPipelines.GUI_OPAQUE_TEX_BG
				|| pipeline == net.minecraft.client.gl.RenderPipelines.GUI_TEXT
				|| pipeline == net.minecraft.client.gl.RenderPipelines.GUI_TEXT_INTENSITY;
	}

	// Runs from the mixin at the head of every GuiRenderer.render(): draws a pending canvas recording into its texture.
	public static void renderBefore(net.minecraft.client.gui.render.GuiRenderer real, net.minecraft.client.render.VertexConsumerProvider.Immediate consumers,
			net.minecraft.client.render.command.OrderedRenderCommandQueue queue, net.minecraft.client.render.command.RenderDispatcher dispatcher,
			com.mojang.blaze3d.buffers.GpuBufferSlice fogBuffer) {
		if (rendering || broken || DISABLED) {
			return;
		}
		try {
			if (renderer == null) {
				if (state == null) {
					return;   // no HUD recorded yet
				}
				renderer = new net.minecraft.client.gui.render.GuiRenderer(state, consumers, queue, dispatcher, java.util.List.of());
				return;
			}
			if (!dirty || target == null) {
				return;
			}
			dirty = false;
			rendering = true;
			// clear to transparent, then the normal GUI pass draws into the texture (the mixin swaps its framebuffer)
			com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorAttachment(), 0,
					target.getDepthAttachment(), 1.0);
			renderer.incrementFrame();
			renderer.render(fogBuffer);
			renders++;
			if (renders == 1) {
				LOG.info("HUD canvas active ({}x{})", target.textureWidth, target.textureHeight);
			}
		} catch (Throwable t) {
			fail("render", t);
		} finally {
			rendering = false;
		}
	}

	private static void fail(String where, Throwable t) {
		broken = true;
		valid = false;
		dirty = false;
		LOG.warn("HUD canvas failed in {} - the HUD uses the plain replay cache for the rest of this session", where, t);
	}

	// A resource reload or a world change: what was drawn may point at stale sprites.
	public static void invalidate() {
		valid = false;
	}
	//?} else {
	/*public static void invalidate() {
	}
	*///?}
}
