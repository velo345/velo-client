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

	/**
	 * Recording rate: twice the monitor's refresh rate (so HUD animations stay smooth however the frames line up with the
	 * screen), at most 360 Hz, at least 120 Hz. A fixed 360 Hz re-recorded the whole HUD 6x per shown frame on a 60 Hz
	 * screen - the recording frames were ~7% of all frames and carried most of the HUD's cost (2026-11-21 profile).
	 */
	private static long intervalNs = 1_000_000_000L / 360;
	private static long refreshCheckedAt;

	private static long interval(long now) {
		if (now - refreshCheckedAt > 2_000_000_000L) {
			refreshCheckedAt = now;
			int hz = 0;
			try {
				//? if >=26.3 {
				/*var mode = net.minecraft.client.Minecraft.getInstance().getWindow().getActiveVideoMode();
				hz = mode == null ? 0 : Math.round(mode.getRefreshRate());
				*///?} else if >=26.2 {
				/*hz = net.minecraft.client.Minecraft.getInstance().getWindow().getRefreshRate();
				*///?}
			} catch (RuntimeException | LinkageError e) {
				hz = 0;
			}
			int rate = hz <= 0 ? 360 : Math.max(120, Math.min(360, hz * 2));
			intervalNs = 1_000_000_000L / rate;
		}
		return intervalNs;
	}

	/**
	 * With the canvas (HudCanvas) frames in between cost almost nothing, so the HUD is recorded at the monitor's refresh
	 * rate (60..240 Hz) instead of twice that - and at once when an input the HUD shows changed (movement keys, mouse
	 * buttons, hotbar slot): Keystrokes / CPS never lag behind.
	 */
	private static long canvasIntervalNs = 1_000_000_000L / 144;
	private static long canvasCheckedAt;
	private static long recordedInput;

	private static long canvasInterval(long now) {
		if (now - canvasCheckedAt > 2_000_000_000L) {
			canvasCheckedAt = now;
			int hz = 0;
			try {
				//? if >=26.3 {
				/*var mode = net.minecraft.client.Minecraft.getInstance().getWindow().getActiveVideoMode();
				hz = mode == null ? 0 : Math.round(mode.getRefreshRate());
				*///?} else if >=26.2 {
				/*hz = net.minecraft.client.Minecraft.getInstance().getWindow().getRefreshRate();
				*///?}
			} catch (RuntimeException | LinkageError e) {
				hz = 0;
			}
			canvasIntervalNs = 1_000_000_000L / (hz <= 0 ? 144 : Math.max(60, Math.min(240, hz)));
		}
		return canvasIntervalNs;
	}

	/** The inputs the HUD shows, as bits: movement, jump, sneak, sprint, attack, use, hotbar slot. */
	private static long inputMask() {
		//? if >=26.2 {
		/*var client = net.minecraft.client.Minecraft.getInstance();
		var o = client.options;
		long m = (o.keyUp.isDown() ? 1L : 0) | (o.keyDown.isDown() ? 2L : 0) | (o.keyLeft.isDown() ? 4L : 0) | (o.keyRight.isDown() ? 8L : 0)
				| (o.keyJump.isDown() ? 16L : 0) | (o.keyShift.isDown() ? 32L : 0) | (o.keySprint.isDown() ? 64L : 0)
				| (o.keyAttack.isDown() ? 128L : 0) | (o.keyUse.isDown() ? 256L : 0);
		if (client.player != null) {
			m |= (long) client.player.getInventory().getSelectedSlot() << 16;
		}
		return m;
		*///?} else {
		return 0L;
		//?}
	}

	private static long recordedAt;
	private static int recordedWidth;
	private static int recordedHeight;
	private static int recordedScale;
	private static boolean valid;
	private static boolean recording;
	/** The recording in progress goes through HudCanvas (26.2+). */
	private static boolean canvasMode;
	/** Profiling counters (RenderProfiler logs and resets them). */
	public static int replays;
	public static int records;

	//? if >=1.21.11 && <26.1 {
	private static final Recorder RECORDER = new Recorder();
	private static final java.util.ArrayList<Object> ARGS = new java.util.ArrayList<>();
	private static byte[] ops = new byte[256];
	private static int opCount;
	private static net.minecraft.client.gui.render.state.GuiRenderState swappedOut;
	//?} else if >=26.2 {
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
		HudCanvas.invalidate();
	}

	private static boolean allowed() {
		return net.veloclient.velo.client.modules.performance.PerformanceBoostModule.hudFrameCache
				&& net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo
				&& !net.veloclient.velo.client.util.ModuleProfiler.enabled
				&& net.veloclient.velo.client.util.ClientCompat.currentScreen() == null;
	}

	/** Replays the last recorded HUD if it is still fresh; true = done, skip drawing the modules. */
	public static boolean replay(DrawContext context, int width, int height) {
		//? if >=1.21.11 && <26.1 {
		if (!allowed()) {
			valid = false;
			return false;
		}
		int scale = net.veloclient.velo.client.util.ClientCompat.guiScale();
		if (wholeHud() && HudCanvas.ready(width, height, scale) && System.nanoTime() - recordedAt < interval(System.nanoTime())) {
			HudCanvas.addBlit(((net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context).velo$state());
			replays++;
			return true;
		}
		if (!valid || width != recordedWidth || height != recordedHeight || scale != recordedScale
				|| System.nanoTime() - recordedAt >= interval(System.nanoTime())) {
			return false;
		}
		var state = ((net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context).velo$state();
		for (int i = 0; i < opCount; i++) {
			Object arg = ARGS.get(i);
			switch (ops[i]) {
				case 0 -> state.createNewRootLayer();
				case 1 -> state.applyBlur();
				case 2 -> state.goUpLayer();
				case 3 -> state.addItem((net.minecraft.client.gui.render.state.ItemGuiElementRenderState) arg);
				case 4 -> state.addText((net.minecraft.client.gui.render.state.TextGuiElementRenderState) arg);
				case 5 -> state.addSpecialElement((net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState) arg);
				case 6 -> state.addSimpleElement((net.minecraft.client.gui.render.state.SimpleGuiElementRenderState) arg);
				case 7 -> state.addSimpleElementToCurrentLayer((net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState) arg);
				case 8 -> state.addPreparedTextElement((net.minecraft.client.gui.render.state.SimpleGuiElementRenderState) arg);
				default -> {
				}
			}
		}
		replays++;
		return true;
		//?} else if >=26.2 {
		/*if (!allowed()) {
			valid = false;
			return false;
		}
		int scale = net.veloclient.velo.client.util.ClientCompat.guiScale();
		if (wholeHud() && HudCanvas.ready(width, height, scale) && System.nanoTime() - recordedAt < canvasInterval(System.nanoTime())
				&& inputMask() == recordedInput) {
			HudCanvas.addBlit(((net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context).velo$state());
			replays++;
			return true;
		}
		if (!valid || width != recordedWidth || height != recordedHeight || scale != recordedScale
				|| System.nanoTime() - recordedAt >= interval(System.nanoTime())) {
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
		/*return false;
		*///?}
	}

	/** Starts recording what the HUD modules draw into {@code context} (when caching is allowed). */
	public static void beginRecord(DrawContext context, int width, int height) {
		//? if >=1.21.11 && <26.1 {
		if (!allowed() || recording) {
			return;
		}
		var accessor = (net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context;
		swappedOut = accessor.velo$state();
		RECORDER.real = swappedOut;
		opCount = 0;
		ARGS.clear();
		recordedScale = net.veloclient.velo.client.util.ClientCompat.guiScale();
		canvasMode = wholeHud() && HudCanvas.begin(width, height, recordedScale);   // never beside Gnetum / in the modules-only recording
		RECORDER.canvas = canvasMode ? HudCanvas.state : null;
		if (canvasMode) {
			HudCanvas.addBlit(swappedOut);
		}
		accessor.velo$setState(RECORDER);
		recording = true;
		records++;
		recordedWidth = width;
		recordedHeight = height;
		//?} else if >=26.2 {
		/*if (!allowed() || recording) {
			return;
		}
		var accessor = (net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context;
		swappedOut = accessor.velo$state();
		RECORDER.real = swappedOut;
		opCount = 0;
		ARGS.clear();
		recordedScale = net.veloclient.velo.client.util.ClientCompat.guiScale();
		canvasMode = wholeHud() && HudCanvas.begin(width, height, recordedScale);   // never beside Gnetum / in the modules-only recording
		RECORDER.canvas = canvasMode ? HudCanvas.state : null;
		if (canvasMode) {
			HudCanvas.addBlit(swappedOut);
		}
		accessor.velo$setState(RECORDER);
		recording = true;
		records++;
		recordedWidth = width;
		recordedHeight = height;
		*///?}
	}

	/** Ends a recording (always restores the real render state, also after an exception). */
	public static void endRecord(DrawContext context, boolean completed) {
		//? if >=1.21.11 && <26.1 {
		if (!recording) {
			return;
		}
		((net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context).velo$setState(swappedOut);
		RECORDER.real = null;
		RECORDER.canvas = null;
		swappedOut = null;
		recording = false;
		if (canvasMode) {
			HudCanvas.end(completed);
			valid = false;   // the draw list wasn't kept: the canvas shows it
			canvasMode = false;
		} else {
			valid = completed;
		}
		recordedAt = System.nanoTime();
		//?} else if >=26.2 {
		/*if (!recording) {
			return;
		}
		((net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context).velo$setState(swappedOut);
		RECORDER.real = null;
		RECORDER.canvas = null;
		swappedOut = null;
		recording = false;
		if (canvasMode) {
			HudCanvas.end(completed);
			valid = false;   // the draw list wasn't kept: the canvas shows it
			canvasMode = false;
		} else {
			valid = completed;
		}
		recordedAt = System.nanoTime();
		recordedInput = inputMask();
		*///?}
	}

	//? if >=1.21.11 && <26.1 {
	private static void record(int op, Object arg) {
		if (opCount == ops.length) {
			ops = java.util.Arrays.copyOf(ops, ops.length * 2);
		}
		ops[opCount++] = (byte) op;
		ARGS.add(arg);
	}

	// Forwards everything to the frame's real state and remembers the additions.
	static final class Recorder extends net.minecraft.client.gui.render.state.GuiRenderState {
		net.minecraft.client.gui.render.state.GuiRenderState real;
		// Canvas recording (HudCanvas): the HUD goes here instead; only what can't live in a transparent texture stays in real.
		net.minecraft.client.gui.render.state.GuiRenderState canvas;

		@Override
		public void createNewRootLayer() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.createNewRootLayer();
				return;
			}
			if (canvas != null) {
				canvas.createNewRootLayer();
				return;
			}
			real.createNewRootLayer();
			record(0, null);
		}

		@Override
		public void applyBlur() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.applyBlur();
				return;
			}
			real.applyBlur();   // (the HUD never blurs; if one does, it blurs the frame - not the canvas)
			if (canvas == null) {
				record(1, null);
			}
		}

		@Override
		public void goUpLayer() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.goUpLayer();
				return;
			}
			if (canvas != null) {
				canvas.goUpLayer();
				return;
			}
			real.goUpLayer();
			record(2, null);
		}

		@Override
		public void addItem(net.minecraft.client.gui.render.state.ItemGuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addItem(state);
				return;
			}
			if (canvas != null) {
				if (state.oversizedBounds() == null) {
					HudCanvas.cover(state.bounds());
					canvas.addItem(state);
				} else {
					real.addItem(state);
				}
				return;
			}
			real.addItem(state);
			record(3, state);
		}

		@Override
		public void addText(net.minecraft.client.gui.render.state.TextGuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addText(state);
				return;
			}
			if (canvas != null) {
				HudCanvas.cover(state.bounds());
				canvas.addText(state);
				return;
			}
			real.addText(state);
			record(4, state);
		}

		@Override
		public void addSpecialElement(net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addSpecialElement(state);
				return;
			}
			real.addSpecialElement(state);   // live: renders its own picture, no transparent-texture version
			if (canvas == null) {
				record(5, state);
			}
		}

		@Override
		public void addSimpleElement(net.minecraft.client.gui.render.state.SimpleGuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addSimpleElement(state);
				return;
			}
			if (canvas != null) {
				HudCanvas.target(state.pipeline(), state.bounds(), canvas, real).addSimpleElement(state);
				return;
			}
			real.addSimpleElement(state);
			record(6, state);
		}

		@Override
		public void addSimpleElementToCurrentLayer(net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addSimpleElementToCurrentLayer(state);
				return;
			}
			if (canvas != null) {
				HudCanvas.target(state.pipeline(), state.bounds(), canvas, real).addSimpleElementToCurrentLayer(state);
				return;
			}
			real.addSimpleElementToCurrentLayer(state);
			record(7, state);
		}

		@Override
		public void addPreparedTextElement(net.minecraft.client.gui.render.state.SimpleGuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addPreparedTextElement(state);
				return;
			}
			if (canvas != null) {
				HudCanvas.target(state.pipeline(), state.bounds(), canvas, real).addPreparedTextElement(state);
				return;
			}
			real.addPreparedTextElement(state);
			record(8, state);
		}

		@Override
		public java.util.Set<Object> getItemModelKeys() {
			if (real == null) {
				return super.getItemModelKeys();
			}
			return real.getItemModelKeys();
		}

		@Override
		public void forEachSimpleElement(java.util.function.Consumer<net.minecraft.client.gui.render.state.SimpleGuiElementRenderState> consumer,
				net.minecraft.client.gui.render.state.GuiRenderState.LayerFilter range) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.forEachSimpleElement(consumer, range);
				return;
			}
			real.forEachSimpleElement(consumer, range);
		}

		@Override
		public void forEachItemElement(java.util.function.Consumer<net.minecraft.client.gui.render.state.ItemGuiElementRenderState> consumer) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.forEachItemElement(consumer);
				return;
			}
			real.forEachItemElement(consumer);
		}

		@Override
		public void forEachTextElement(java.util.function.Consumer<net.minecraft.client.gui.render.state.TextGuiElementRenderState> consumer) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.forEachTextElement(consumer);
				return;
			}
			real.forEachTextElement(consumer);
		}

		@Override
		public void forEachSpecialElement(java.util.function.Consumer<net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState> consumer) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.forEachSpecialElement(consumer);
				return;
			}
			real.forEachSpecialElement(consumer);
		}

		@Override
		public void sortSimpleElements(java.util.Comparator<net.minecraft.client.gui.render.state.SimpleGuiElementRenderState> comparator) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.sortSimpleElements(comparator);
				return;
			}
			real.sortSimpleElements(comparator);
		}

		@Override
		public void clear() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.clear();
				return;
			}
			real.clear();
		}
	}
	//?} else if >=26.2 {
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
		// Canvas recording (HudCanvas): the HUD goes here instead; only what can't live in a transparent texture stays in real.
		net.minecraft.client.renderer.state.gui.GuiRenderState canvas;

		@Override
		public void nextStratum() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.nextStratum();
				return;
			}
			if (canvas != null) {
				canvas.nextStratum();
				capture(0, null);
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
			real.blurBeforeThisStratum();   // (the HUD never blurs; if one does, it blurs the frame - not the canvas)
			if (canvas == null) {
				record(1, null);
			}
		}

		@Override
		public void up() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.up();
				return;
			}
			if (canvas != null) {
				canvas.up();
				capture(2, null);
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
			if (canvas != null) {
				if (state.oversizedItemBounds() == null) {
					HudCanvas.cover(state.bounds());
					canvas.addItem(state);
				} else {
					real.addItem(state);
				}
				capture(3, state);
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
			if (canvas != null) {
				HudCanvas.cover(state.bounds());
				canvas.addText(state);
				capture(4, state);
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
			real.addPicturesInPictureState(state);   // live: renders its own picture, no transparent-texture version
			if (canvas == null) {
				record(5, state);
			} else {
				capture(5, state);
			}
		}

		@Override
		public void addGuiElement(net.minecraft.client.renderer.state.gui.GuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addGuiElement(state);
				return;
			}
			if (canvas != null) {
				HudCanvas.target(state.pipeline(), state.bounds(), canvas, real).addGuiElement(state);
				capture(6, state);
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
			if (canvas != null) {
				HudCanvas.target(state.pipeline(), state.bounds(), canvas, real).addBlitToCurrentLayer(state);
				capture(7, state);
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
			if (canvas != null) {
				HudCanvas.target(state.pipeline(), state.bounds(), canvas, real).addGlyphToCurrentLayer(state);
				capture(8, state);
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

	//? if >=26.2 {
	/*// ---- segments: a part of the HUD (the vanilla hotbar + bars) that is only re-extracted when its inputs changed.
	// Only while a canvas recording runs; the segment's elements are handed to the recorder again (so live elements
	// like the crosshair keep routing to the frame). Vanilla's HUD state only changes on client ticks, so the key is
	// (tick, selected slot, GUI size, no item pop animation).
	public static final class Segment {
		byte[] ops = new byte[64];
		Object[] args = new Object[64];
		int n;
		long key = Long.MIN_VALUE;
	}

	private static Segment capturing;

	private static void capture(int op, Object arg) {
		Segment seg = capturing;
		if (seg == null) {
			return;
		}
		if (seg.n == seg.ops.length) {
			seg.ops = java.util.Arrays.copyOf(seg.ops, seg.n * 2);
			seg.args = java.util.Arrays.copyOf(seg.args, seg.n * 2);
		}
		seg.ops[seg.n] = (byte) op;
		seg.args[seg.n++] = arg;
	}

	public static boolean canvasRecording() {
		return recording && canvasMode;
	}

	// True = the segment was replayed (skip the real extraction).
	public static boolean segmentReplay(Segment seg, long key) {
		if (!canvasRecording() || seg.key != key || seg.n == 0) {
			return false;
		}
		Segment saved = capturing;
		capturing = null;
		try {
			for (int i = 0; i < seg.n; i++) {
				Object arg = seg.args[i];
				switch (seg.ops[i]) {
					case 0 -> RECORDER.nextStratum();
					case 2 -> RECORDER.up();
					case 3 -> RECORDER.addItem((net.minecraft.client.renderer.state.gui.GuiItemRenderState) arg);
					case 4 -> RECORDER.addText((net.minecraft.client.renderer.state.gui.GuiTextRenderState) arg);
					case 5 -> RECORDER.addPicturesInPictureState((net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState) arg);
					case 6 -> RECORDER.addGuiElement((net.minecraft.client.renderer.state.gui.GuiElementRenderState) arg);
					case 7 -> RECORDER.addBlitToCurrentLayer((net.minecraft.client.renderer.state.gui.BlitRenderState) arg);
					case 8 -> RECORDER.addGlyphToCurrentLayer((net.minecraft.client.renderer.state.gui.GuiElementRenderState) arg);
					default -> {
					}
				}
			}
		} finally {
			capturing = saved;
		}
		segmentHits++;
		return true;
	}

	public static void segmentBegin(Segment seg) {
		seg.n = 0;
		seg.key = Long.MIN_VALUE;
		java.util.Arrays.fill(seg.args, null);
		capturing = canvasRecording() ? seg : null;
	}

	public static void segmentEnd(Segment seg, long key, boolean completed) {
		capturing = null;
		seg.key = completed ? key : Long.MIN_VALUE;
	}

	public static int segmentHits;
	*///?}
}
