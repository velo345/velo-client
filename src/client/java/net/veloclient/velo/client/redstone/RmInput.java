package net.veloclient.velo.client.redstone;

import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.util.Keys;

/**
 * Redstone Mode's controls. Mouse: with the Select tool left / right click set the two corners;
 * with a preview open (paste, move, stack, wiring) left click or Enter applies and right click or
 * Backspace cancels; the scroll wheel rotates a paste/move preview or changes the stack count, and
 * Alt+scroll switches tools. Keys (rebindable under Controls &gt; Velo Redstone Mode): Z next tool,
 * G move, U stack, H flip, J replace, K timeline, O settings, Delete erase, arrows tick control;
 * plus Ctrl+C copy, Ctrl+X cut, Ctrl+V paste, Ctrl+Z undo, Ctrl+Y redo, Ctrl+D deselect,
 * Ctrl+S save schematic, Ctrl+L load schematic.
 */
public final class RmInput {

	private static final char[] CTRL_KEYS = {'C', 'X', 'V', 'Y', 'D', 'S', 'L'};
	private static final boolean[] WAS_DOWN = new boolean[CTRL_KEYS.length];
	private static boolean scrollRegistered;

	private RmInput() {
	}

	/** Every client tick. */
	public static void tick() {
		if (!scrollRegistered) {
			scrollRegistered = true;
			net.veloclient.velo.client.addon.ScrollHandlers.register(RmInput::wantsScroll, RmInput::onScroll);
		}
		DustAnalyzer.tick();
		ClockTracker.tick();
		Timeline.tick();
		if (!RedstoneModeModule.active()) {
			drainKeys();
			return;
		}
		if (ClientCompat.currentScreen() != null) {
			return;
		}
		if (!Timeline.playbackActive()) {
			TickControls.tick(RedstoneModeModule.sprintTicks());
		}
		boolean ctrl = RmKeys.ctrl();
		while (RmKeys.NEXT_TOOL.wasPressed()) {
			if (ctrl) {
				RmOps.undo();
			} else {
				nextTool(1);
			}
		}
		for (int i = 0; i < CTRL_KEYS.length; i++) {
			boolean down = Keys.isKeyDown(Keys.letter(CTRL_KEYS[i]));
			if (down && !WAS_DOWN[i] && ctrl) {
				onCtrl(CTRL_KEYS[i]);
			}
			WAS_DOWN[i] = down;
		}
		while (RmKeys.MOVE.wasPressed()) {
			startPreview(RmState.Pending.MOVE);
		}
		while (RmKeys.STACK.wasPressed()) {
			startPreview(RmState.Pending.STACK);
		}
		while (RmKeys.FLIP.wasPressed()) {
			if (RmState.pending == RmState.Pending.PASTE || RmState.pending == RmState.Pending.MOVE) {
				RmState.mirrored = !RmState.mirrored;
			}
		}
		while (RmKeys.REPLACE.wasPressed()) {
			if (RmState.hasSelection()) {
				net.minecraft.client.MinecraftClient.getInstance().setScreen(new ReplaceScreen());
			} else {
				RmState.error("Select an area first");
			}
		}
		while (RmKeys.TIMELINE.wasPressed()) {
			Timeline.toggle();
		}
		while (RmKeys.SETTINGS.wasPressed()) {
			RedstoneModeModule.openSettings();
		}
		while (RmKeys.ERASE.wasPressed()) {
			RmOps.erase();
		}
		while (RmKeys.CONFIRM.wasPressed()) {
			confirm();
		}
		while (RmKeys.CANCEL.wasPressed()) {
			cancel();
		}
		if (RmState.pending == RmState.Pending.STACK) {
			RmState.stackFace = lookFace();
		}
	}

	private static void drainKeys() {
		for (var key : new net.minecraft.client.option.KeyBinding[] {RmKeys.NEXT_TOOL, RmKeys.MOVE, RmKeys.STACK, RmKeys.FLIP, RmKeys.REPLACE,
				RmKeys.TIMELINE, RmKeys.SETTINGS, RmKeys.ERASE, RmKeys.CONFIRM, RmKeys.CANCEL, RmKeys.STEP, RmKeys.BACK, RmKeys.PLAY,
				RmKeys.REWIND}) {
			while (key.wasPressed()) {
				// Not in Redstone Mode: these keys do nothing.
			}
		}
	}

	private static void onCtrl(char key) {
		switch (key) {
			case 'C' -> RmOps.copy(false);
			case 'X' -> RmOps.copy(true);
			case 'V' -> startPreview(RmState.Pending.PASTE);
			case 'Y' -> RmOps.redo();
			case 'D' -> {
				RmState.clearSelection();
				RmState.cancelPending();
				RmState.info("Selection cleared");
			}
			case 'S' -> {
				if (RmState.hasSelection()) {
					net.minecraft.client.MinecraftClient.getInstance().setScreen(new SchematicSaveScreen());
				} else {
					RmState.error("Select an area to save first");
				}
			}
			case 'L' -> net.minecraft.client.MinecraftClient.getInstance().setScreen(new SchematicPickScreen(null));
			default -> {
			}
		}
	}

	public static void nextTool(int direction) {
		RmState.Tool[] tools = RmState.Tool.values();
		RmState.tool = tools[Math.floorMod(RmState.tool.ordinal() + direction, tools.length)];
		RmState.cancelPending();
		RmState.wireStart = null;
		RmState.wireEnd = null;
		Wiring.clearPreview();
	}

	private static void startPreview(RmState.Pending kind) {
		switch (kind) {
			case PASTE -> {
				if (RmOps.clipboard() == null) {
					RmState.error("Clipboard is empty - Ctrl+C a selection or load a schematic (Ctrl+L)");
					return;
				}
			}
			case MOVE, STACK -> {
				if (!RmState.hasSelection()) {
					RmState.error("Select an area first");
					return;
				}
			}
			default -> {
			}
		}
		RmState.cancelPending();
		RmState.pending = kind;
		RmState.stackFace = lookFace();
		RmState.info(switch (kind) {
			case PASTE -> "Paste: scroll to rotate, H to flip, left click / Enter to place";
			case MOVE -> "Move: scroll to rotate, H to flip, left click / Enter to drop";
			case STACK -> "Stack: look in a direction, scroll for count, left click / Enter";
			default -> "";
		});
	}

	/** The face the player looks along (up/down when looking steeply). */
	static int lookFace() {
		var player = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		if (player == null) {
			return 2;
		}
		//? if <26.1 {
		float pitch = player.getPitch();
		//?} else {
		/*float pitch = player.getXRot();
		*///?}
		if (pitch < -50) {
			return 1;
		}
		if (pitch > 50) {
			return 0;
		}
		return RmWorld.playerFacing();
	}

	/** Size of what a paste/move preview places, after rotation: {sx, sy, sz}. */
	static int[] previewSize() {
		int sx;
		int sy;
		int sz;
		if (RmState.pending == RmState.Pending.PASTE) {
			Clip clip = RmOps.clipboard();
			if (clip == null) {
				return null;
			}
			sx = clip.sx;
			sy = clip.sy;
			sz = clip.sz;
		} else {
			int[] b = RmState.box();
			if (b == null) {
				return null;
			}
			sx = b[3] - b[0] + 1;
			sy = b[4] - b[1] + 1;
			sz = b[5] - b[2] + 1;
		}
		return RmState.rotation % 2 != 0 ? new int[] {sz, sy, sx} : new int[] {sx, sy, sz};
	}

	/** Minimum corner where a paste/move preview would land right now (on the face you look at), or null. */
	static int[] previewOrigin() {
		int[] size = previewSize();
		int[] look = RmWorld.lookedBlock(96);
		if (size == null || look == null) {
			return null;
		}
		int[] d = RmWorld.faceOffset(look[3]);
		int tx = look[0] + d[0];
		int ty = look[1] + d[1];
		int tz = look[2] + d[2];
		int y = look[3] == 0 ? ty - size[1] + 1 : ty;
		return new int[] {tx - size[0] / 2, y, tz - size[2] / 2};
	}

	private static void confirm() {
		switch (RmState.pending) {
			case PASTE -> {
				int[] origin = previewOrigin();
				Clip clip = RmOps.clipboard();
				if (origin != null && clip != null) {
					RmOps.paste(clip.transformed(RmState.rotation, RmState.mirrored), origin[0], origin[1], origin[2]);
				}
				RmState.cancelPending();
			}
			case MOVE -> {
				int[] origin = previewOrigin();
				if (origin != null) {
					RmOps.move(origin[0], origin[1], origin[2], RmState.rotation, RmState.mirrored);
				}
				RmState.cancelPending();
			}
			case STACK -> {
				RmOps.stack(RmState.stackCount, RmState.stackFace);
				RmState.cancelPending();
			}
			case WIRE, WATER -> Wiring.build();
			default -> {
			}
		}
	}

	private static void cancel() {
		if (RmState.pending != RmState.Pending.NONE) {
			RmState.cancelPending();
			Wiring.clearPreview();
			RmState.info("Cancelled");
		}
	}

	// ---- mouse (called from ClickInterceptMixin) ----

	/** Left click. True when Redstone Mode used it (vanilla must not attack / break). */
	public static boolean onAttack() {
		if (!RedstoneModeModule.active() || ClientCompat.currentScreen() != null) {
			return false;
		}
		if (RmState.pending != RmState.Pending.NONE && RmState.pending != RmState.Pending.WIRE && RmState.pending != RmState.Pending.WATER) {
			confirm();
			return true;
		}
		switch (RmState.tool) {
			case SELECT -> {
				int[] look = RmWorld.lookedBlock(128);
				if (look != null) {
					RmState.cornerA = new int[] {look[0], look[1], look[2]};
					RmState.info("Corner 1: " + look[0] + " " + look[1] + " " + look[2]);
				}
				return true;
			}
			case WIRE, WATER -> {
				if (RmState.pending == RmState.Pending.WIRE || RmState.pending == RmState.Pending.WATER) {
					confirm();
					return true;
				}
				int[] look = RmWorld.lookedBlock(128);
				if (look != null) {
					RmState.wireStart = look;
					RmState.wireEnd = null;
					Wiring.clearPreview();
					RmState.info("Start set - right click the end point");
				}
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	/** Holding left click (block breaking): swallowed whenever a Redstone tool owns the mouse. */
	public static boolean blocksBreaking() {
		return RedstoneModeModule.active() && RmState.tool != RmState.Tool.BUILD && ClientCompat.currentScreen() == null;
	}

	/** Right click. */
	public static boolean onUse() {
		if (!RedstoneModeModule.active() || ClientCompat.currentScreen() != null) {
			return false;
		}
		if (RmState.pending != RmState.Pending.NONE) {
			cancel();
			return true;
		}
		switch (RmState.tool) {
			case SELECT -> {
				int[] look = RmWorld.lookedBlock(128);
				if (look != null) {
					RmState.cornerB = new int[] {look[0], look[1], look[2]};
					if (RmState.cornerA == null) {
						RmState.cornerA = RmState.cornerB;
					}
					int[] b = RmState.box();
					RmState.info("Selected " + (b[3] - b[0] + 1) + "x" + (b[4] - b[1] + 1) + "x" + (b[5] - b[2] + 1)
							+ " - Ctrl+C copy, G move, U stack, Delete erase, K timeline");
				}
				return true;
			}
			case WIRE, WATER -> {
				int[] look = RmWorld.lookedBlock(128);
				if (look != null && RmState.wireStart != null) {
					RmState.wireEnd = look;
					Wiring.plan(RmState.tool == RmState.Tool.WATER);
				} else if (RmState.wireStart == null) {
					RmState.error("Left click the start point first");
				}
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private static boolean wantsScroll() {
		return RedstoneModeModule.active() && ClientCompat.currentScreen() == null
				&& (RmKeys.alt() || RmState.pending == RmState.Pending.PASTE || RmState.pending == RmState.Pending.MOVE
						|| RmState.pending == RmState.Pending.STACK || Timeline.playbackActive());
	}

	private static void onScroll(double amount) {
		int step = amount > 0 ? 1 : -1;
		if (RmKeys.alt()) {
			nextTool(-step);
			return;
		}
		if (Timeline.playbackActive()) {
			Timeline.scrub(-step * (RmKeys.shift() ? 10 : 1));
			return;
		}
		switch (RmState.pending) {
			case PASTE, MOVE -> RmState.rotation = Math.floorMod(RmState.rotation + step, 4);
			case STACK -> RmState.stackCount = Math.max(1, Math.min(64, RmState.stackCount + step));
			default -> {
			}
		}
	}
}
