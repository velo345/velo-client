package net.veloclient.velo.client.gui.glass;

import net.minecraft.client.gui.DrawContext;

/**
 * A menu's frame cache (2026-11-26, owner: "mit R-Shift droppen FPS um 1000"): a menu rebuilt its whole draw list every
 * frame - hundreds of shapes, texts and icons at 1000+ FPS for a picture the monitor shows 144-240 times a second. Now
 * the menu draws at most twice the monitor's refresh rate (120-360 Hz) and the recorded elements are handed to the
 * frame's render state again in between, like Velo Turbo's HUD cache. Input invalidates it, so nothing ever lags.
 * 26.2+ (the GuiRenderState era) with the Turbo state accessor; elsewhere it never caches.
 */
public final class GuiFrameCache {

	private long recordedAt;
	private int recordedWidth;
	private int recordedHeight;
	private int recordedScale;
	private boolean valid;
	private boolean recording;
	private static long intervalNs = 1_000_000_000L / 240;
	private static long refreshCheckedAt;
	private static boolean broken;
	private static final boolean BENCH = Boolean.getBoolean("velo.drawerBench");
	private long loggedAt;

	//? if >=26.2 {
	/*private final Recorder recorder = new Recorder();
	private final java.util.ArrayList<Object> args = new java.util.ArrayList<>();
	private byte[] ops = new byte[512];
	private int opCount;
	private net.minecraft.client.renderer.state.gui.GuiRenderState swappedOut;
	*///?}

	public void invalidate() {
		valid = false;
	}

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
			int rate = hz <= 0 ? 240 : Math.max(120, Math.min(360, hz * 2));
			intervalNs = 1_000_000_000L / rate;
		}
		return intervalNs;
	}

	private static boolean allowed() {
		return !broken && net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo
				&& !net.veloclient.velo.client.util.ModuleProfiler.enabled;
	}

	/** True when the last recording was replayed (skip drawing this frame). */
	public boolean replay(DrawContext context, int width, int height) {
		//? if >=26.2 {
		/*if (!allowed()) {
			valid = false;
			return false;
		}
		int scale = net.veloclient.velo.client.util.ClientCompat.guiScale();
		if (!valid || width != recordedWidth || height != recordedHeight || scale != recordedScale
				|| System.nanoTime() - recordedAt >= interval(System.nanoTime())) {
			return false;
		}
		net.minecraft.client.renderer.state.gui.GuiRenderState state;
		try {
			state = ((net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context).velo$state();
		} catch (ClassCastException e) {
			broken = true;   // the accessor isn't there (no Turbo on this setup): draw live
			return false;
		}
		for (int i = 0; i < opCount; i++) {
			Object arg = args.get(i);
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
		return true;
		*///?} else {
		return false;
		//?}
	}

	public void begin(DrawContext context, int width, int height) {
		//? if >=26.2 {
		/*if (!allowed() || recording) {
			return;
		}
		net.veloclient.velo.client.mixin.TurboHudStateAccessor accessor;
		try {
			accessor = (net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context;
			swappedOut = accessor.velo$state();
		} catch (ClassCastException e) {
			broken = true;
			return;
		}
		recorder.real = swappedOut;
		recorder.owner = this;
		opCount = 0;
		args.clear();
		accessor.velo$setState(recorder);
		recording = true;
		recordedWidth = width;
		recordedHeight = height;
		recordedScale = net.veloclient.velo.client.util.ClientCompat.guiScale();
		*///?}
	}

	public void end(DrawContext context, boolean completed) {
		//? if >=26.2 {
		/*if (!recording) {
			return;
		}
		((net.veloclient.velo.client.mixin.TurboHudStateAccessor) (Object) context).velo$setState(swappedOut);
		recorder.real = null;
		swappedOut = null;
		recording = false;
		valid = completed;
		recordedAt = System.nanoTime();
		if (BENCH && recordedAt - loggedAt > 2_000_000_000L) {
			loggedAt = recordedAt;
			int[] byOp = new int[9];
			java.util.Map<String, Integer> byClass = new java.util.TreeMap<>();
			for (int i = 0; i < opCount; i++) {
				byOp[ops[i]]++;
				if (args.get(i) != null) {
					byClass.merge(args.get(i).getClass().getSimpleName(), 1, Integer::sum);
				}
			}
			net.veloclient.velo.VeloClient.LOGGER.info("[drawerBench] recorded {} ops {} {}", opCount, java.util.Arrays.toString(byOp), byClass);
		}
		*///?}
	}

	//? if >=26.2 {
	/*void record(int op, Object arg) {
		if (opCount == ops.length) {
			ops = java.util.Arrays.copyOf(ops, ops.length * 2);
		}
		ops[opCount++] = (byte) op;
		args.add(arg);
	}

	// Forwards everything to the frame's real state and remembers the additions.
	final class Recorder extends net.minecraft.client.renderer.state.gui.GuiRenderState {
		net.minecraft.client.renderer.state.gui.GuiRenderState real;
		GuiFrameCache owner;

		@Override
		public void nextStratum() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.nextStratum();
				return;
			}
			real.nextStratum();
			owner.record(0, null);
		}

		@Override
		public void blurBeforeThisStratum() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.blurBeforeThisStratum();
				return;
			}
			real.blurBeforeThisStratum();
			owner.record(1, null);
		}

		@Override
		public void up() {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.up();
				return;
			}
			real.up();
			owner.record(2, null);
		}

		@Override
		public void addItem(net.minecraft.client.renderer.state.gui.GuiItemRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addItem(state);
				return;
			}
			real.addItem(state);
			owner.record(3, state);
		}

		@Override
		public void addText(net.minecraft.client.renderer.state.gui.GuiTextRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addText(state);
				return;
			}
			real.addText(state);
			owner.record(4, state);
		}

		@Override
		public void addPicturesInPictureState(net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addPicturesInPictureState(state);
				return;
			}
			real.addPicturesInPictureState(state);
			owner.record(5, state);
		}

		@Override
		public void addGuiElement(net.minecraft.client.renderer.state.gui.GuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addGuiElement(state);
				return;
			}
			real.addGuiElement(state);
			owner.record(6, state);
		}

		@Override
		public void addBlitToCurrentLayer(net.minecraft.client.renderer.state.gui.BlitRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addBlitToCurrentLayer(state);
				return;
			}
			real.addBlitToCurrentLayer(state);
			owner.record(7, state);
		}

		@Override
		public void addGlyphToCurrentLayer(net.minecraft.client.renderer.state.gui.GuiElementRenderState state) {
			if (real == null) { // GuiRenderState's own constructor calls in before real is set
				super.addGlyphToCurrentLayer(state);
				return;
			}
			real.addGlyphToCurrentLayer(state);
			owner.record(8, state);
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
