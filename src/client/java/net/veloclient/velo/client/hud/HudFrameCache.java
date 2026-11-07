package net.veloclient.velo.client.hud;

import net.minecraft.client.gui.DrawContext;

/**
 * Velo Turbo HUD frame cache. Velo's HUD modules rebuilt their whole draw list every frame - at 2500 FPS that is
 * 2500 times a second for a picture the monitor shows at 144-240 Hz (~4.6% of the render thread on our bench).
 * Now the modules draw at most 360 times a second: while they draw, the extractor's render state is swapped for a
 * {@link Recorder} that forwards every element to the real state and remembers it; in the frames between, the
 * remembered elements are handed to the real state again, unchanged. 360 Hz is above every common monitor, so the
 * HUD never looks a frame behind. Live (no cache) while any screen is open, while the Module Profiler measures,
 * and after a resize or GUI scale change. 26.2+ (the GuiRenderState era); elsewhere a no-op.
 */
public final class HudFrameCache {

	private static final long INTERVAL_NS = 1_000_000_000L / 360;

	private static long recordedAt;
	private static int recordedWidth;
	private static int recordedHeight;
	private static int recordedScale;
	private static boolean valid;
	private static boolean recording;
	/** Profiling counters (RenderProfiler logs and resets them). */
	public static int replays;
	public static int records;

	//? if >=26.2 {
	/*private static final Recorder RECORDER = new Recorder();
	private static final java.util.ArrayList<Object> ARGS = new java.util.ArrayList<>();
	private static byte[] ops = new byte[256];
	private static int opCount;
	private static net.minecraft.client.renderer.state.gui.GuiRenderState swappedOut;
	*///?}

	private HudFrameCache() {
	}

	/**
	 * Whole-HUD mode (TurboHudWholeMixin): when Gnetum isn't caching (not installed, or the tuner found a light HUD), the
	 * entire vanilla + Velo HUD - hotbar, chat, scoreboard, every module - is recorded and replayed like the modules are:
	 * Gnetum's saving on the CPU without its full-screen blend on the GPU (the hotbar alone was 9% of a frame).
	 */
	public static boolean wholeHud() {
		return allowed() && !net.veloclient.velo.client.modules.performance.HudCacheTuner.gnetumCaching();
	}

	public static void invalidate() {
		valid = false;
	}

	private static boolean allowed() {
		return net.veloclient.velo.client.modules.performance.PerformanceBoostModule.hudFrameCache
				&& net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo
				&& !net.veloclient.velo.client.util.ModuleProfiler.enabled
				&& net.veloclient.velo.client.util.ClientCompat.currentScreen() == null;
	}

	/** Replays the last recorded HUD if it is still fresh; true = done, skip drawing the modules. */
	public static boolean replay(DrawContext context, int width, int height) {
		//? if >=26.2 {
		/*if (!allowed()) {
			valid = false;
			return false;
		}
		int scale = net.veloclient.velo.client.util.ClientCompat.guiScale();
		if (!valid || width != recordedWidth || height != recordedHeight || scale != recordedScale
				|| System.nanoTime() - recordedAt >= INTERVAL_NS) {
			return false;
		}
		var state = ((net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context).velo$state();
		for (int i = 0; i < opCount; i++) {
			Object arg = ARGS.get(i);
			switch (ops[i]) {
				case 0 -> state.nextStratum();
				case 1 -> state.blurBeforeThisStratum();
				case 2 -> state.up();
				case 3 -> state.addItem((net.minecraft.client.renderer.state.gui.GuiItemRenderState) arg);
				case 4 -> state.addText((net.minecraft.client.renderer.state.gui.GuiTextRenderState) arg);
				case 5 -> state.addPicturesInPictureState((net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState) arg);
				case 6 -> state.addGuiElement((net.minecraft.client.renderer.state.gui.GuiElementRenderState) arg);
				case 7 -> state.addBlitToCurrentLayer((net.minecraft.client.renderer.state.gui.BlitRenderState) arg);
				case 8 -> state.addGlyphToCurrentLayer((net.minecraft.client.renderer.state.gui.GuiElementRenderState) arg);
				default -> {
				}
			}
		}
		replays++;
		return true;
		*///?} else {
		return false;
		//?}
	}

	/** Starts recording what the HUD modules draw into {@code context} (when caching is allowed). */
	public static void beginRecord(DrawContext context, int width, int height) {
		//? if >=26.2 {
		/*if (!allowed() || recording) {
			return;
		}
		var accessor = (net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context;
		swappedOut = accessor.velo$state();
		RECORDER.real = swappedOut;
		opCount = 0;
		ARGS.clear();
		accessor.velo$setState(RECORDER);
		recording = true;
		records++;
		recordedWidth = width;
		recordedHeight = height;
		recordedScale = net.veloclient.velo.client.util.ClientCompat.guiScale();
		*///?}
	}

	/** Ends a recording (always restores the real render state, also after an exception). */
	public static void endRecord(DrawContext context, boolean completed) {
		//? if >=26.2 {
		/*if (!recording) {
			return;
		}
		((net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context).velo$setState(swappedOut);
		RECORDER.real = null;
		swappedOut = null;
		recording = false;
		valid = completed;
		recordedAt = System.nanoTime();
		*///?}
	}

	//? if >=26.2 {
	/*private static void record(int op, Object arg) {
		if (opCount == ops.length) {
			ops = java.util.Arrays.copyOf(ops, ops.length * 2);
		}
		ops[opCount++] = (byte) op;
		ARGS.add(arg);
	}

	// Forwards everything to the frame's real state and remembers the additions.
	static final class Recorder extends net.minecraft.client.renderer.state.gui.GuiRenderState {
		net.minecraft.client.renderer.state.gui.GuiRenderState real;

		@Override
		public void nextStratum() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.nextStratum();
				return;
			}
			real.nextStratum();
			record(0, null);
		}

		@Override
		public void blurBeforeThisStratum() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.blurBeforeThisStratum();
				return;
			}
			real.blurBeforeThisStratum();
			record(1, null);
		}

		@Override
		public void up() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.up();
				return;
			}
			real.up();
			record(2, null);
		}

		@Override
		public void addItem(net.minecraft.client.renderer.state.gui.GuiItemRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addItem(state);
				return;
			}
			real.addItem(state);
			record(3, state);
		}

		@Override
		public void addText(net.minecraft.client.renderer.state.gui.GuiTextRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addText(state);
				return;
			}
			real.addText(state);
			record(4, state);
		}

		@Override
		public void addPicturesInPictureState(net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addPicturesInPictureState(state);
				return;
			}
			real.addPicturesInPictureState(state);
			record(5, state);
		}

		@Override
		public void addGuiElement(net.minecraft.client.renderer.state.gui.GuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addGuiElement(state);
				return;
			}
			real.addGuiElement(state);
			record(6, state);
		}

		@Override
		public void addBlitToCurrentLayer(net.minecraft.client.renderer.state.gui.BlitRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addBlitToCurrentLayer(state);
				return;
			}
			real.addBlitToCurrentLayer(state);
			record(7, state);
		}

		@Override
		public void addGlyphToCurrentLayer(net.minecraft.client.renderer.state.gui.GuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addGlyphToCurrentLayer(state);
				return;
			}
			real.addGlyphToCurrentLayer(state);
			record(8, state);
		}

		@Override
		public java.util.Set<Object> getItemModelIdentities() {
			if (real == null) {
				return super.getItemModelIdentities();
			}
			return real.getItemModelIdentities();
		}

		@Override
		public void forEachElement(java.util.function.Consumer<net.minecraft.client.renderer.state.gui.GuiElementRenderState> consumer,
				net.minecraft.client.renderer.state.gui.GuiRenderState.TraverseRange range) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.forEachElement(consumer, range);
				return;
			}
			real.forEachElement(consumer, range);
		}

		@Override
		public void forEachItem(java.util.function.Consumer<net.minecraft.client.renderer.state.gui.GuiItemRenderState> consumer) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.forEachItem(consumer);
				return;
			}
			real.forEachItem(consumer);
		}

		@Override
		public void forEachText(java.util.function.Consumer<net.minecraft.client.renderer.state.gui.GuiTextRenderState> consumer) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.forEachText(consumer);
				return;
			}
			real.forEachText(consumer);
		}

		@Override
		public void forEachPictureInPicture(java.util.function.Consumer<net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState> consumer) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.forEachPictureInPicture(consumer);
				return;
			}
			real.forEachPictureInPicture(consumer);
		}

		@Override
		public void sortElements(java.util.Comparator<net.minecraft.client.renderer.state.gui.GuiElementRenderState> comparator) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.sortElements(comparator);
				return;
			}
			real.sortElements(comparator);
		}

		@Override
		public void reset() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.reset();
				return;
			}
			real.reset();
		}
	}
	*///?}
}
