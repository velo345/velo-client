package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.util.Keys;

/**
 * Redstone Mode controls.
 *
 * <p>Always (while the mode is on): R leaves, a Left Alt tap frees the mouse for the overlay
 * (Alt+scroll switches tools), F7 freezes, F9 hides the overlay, K records / replays, End stops a
 * running paste, and the arrow keys step ticks while frozen or scrub a replay. Middle click on a
 * schematic preview block picks it from your inventory.
 *
 * <p>Only while the Redstone slot is selected in the hotbar (every other slot plays normally):
 * <ul>
 *   <li>Select - left / right click set the corners; Shift+scroll grows or shrinks the side you
 *   look at.</li>
 *   <li>Move - look along an axis and scroll: forward moves the selection that way, backward
 *   towards you; Shift+scroll rotates; left click / Enter applies, right click resets.</li>
 *   <li>Stack - the same scrolling adds copies (backward = the other way); left click applies.</li>
 *   <li>Wire / Water - left click the start, right click the end, left click again builds.</li>
 *   <li>Ctrl+C / Ctrl+X / Ctrl+V / Ctrl+Z / Ctrl+Y, Delete erases, Backspace clears / cancels.</li>
 * </ul>
 * A paste or a freshly loaded schematic follows the crosshair (Shift+scroll rotates); left click
 * places / locks it, right click cancels.
 */
public final class RmInput {

	private static final char[] CTRL_KEYS = {'C', 'X', 'V', 'Y', 'Z'};
	private static final boolean[] WAS_DOWN = new boolean[CTRL_KEYS.length];
	private static boolean deleteDown;
	private static boolean enterDown;
	private static boolean backspaceDown;
	private static boolean scrollRegistered;
	private static boolean altDown;
	private static boolean altScrolled;

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
		if (!RedstoneMode.active()) {
			RmKeys.drain(RmKeys.CURSOR, RmKeys.FREEZE, RmKeys.HIDE, RmKeys.RECORD, RmKeys.CANCEL_JOB, RmKeys.TOOL_SELECT, RmKeys.TOOL_MOVE,
					RmKeys.TOOL_STACK, RmKeys.TOOL_WIRE, RmKeys.TOOL_WATER, RmKeys.STEP, RmKeys.BACK, RmKeys.PLAY, RmKeys.REWIND);
			return;
		}
		RmAnalysis.tick();
		Placements.tick();
		Timeline.tick();
		Wiring.tick();
		boolean inScreen = ClientCompat.currentScreen() != null;
		// Alt tap (press + release without scrolling) toggles the overlay cursor.
		boolean alt = RmKeys.CURSOR.isPressed();
		if (alt && !altDown) {
			altScrolled = false;
		}
		if (!alt && altDown && !altScrolled && !inScreen) {
			MinecraftClient.getInstance().setScreen(new RmCursorScreen());
		}
		altDown = alt;
		RmKeys.drain(RmKeys.CURSOR);
		if (inScreen) {
			return;
		}
		if (!Timeline.replayOpen()) {
			TickControls.tick(200);
		}
		while (RmKeys.FREEZE.wasPressed()) {
			TickControls.toggleFreeze();
		}
		while (RmKeys.HIDE.wasPressed()) {
			RedstoneMode.toggleOverlay();
		}
		while (RmKeys.RECORD.wasPressed()) {
			Timeline.toggle();
		}
		while (RmKeys.CANCEL_JOB.wasPressed()) {
			RmJobs.cancelCurrent();
		}
		toolKey(RmKeys.TOOL_SELECT, RmState.Tool.SELECT);
		toolKey(RmKeys.TOOL_MOVE, RmState.Tool.MOVE);
		toolKey(RmKeys.TOOL_STACK, RmState.Tool.STACK);
		toolKey(RmKeys.TOOL_WIRE, RmState.Tool.WIRE);
		toolKey(RmKeys.TOOL_WATER, RmState.Tool.WATER);
		boolean held = RedstoneMode.toolHeld();
		boolean ctrl = RmKeys.ctrl();
		for (int i = 0; i < CTRL_KEYS.length; i++) {
			boolean down = Keys.isKeyDown(Keys.letter(CTRL_KEYS[i]));
			if (down && !WAS_DOWN[i] && ctrl && held) {
				onCtrl(CTRL_KEYS[i]);
			}
			WAS_DOWN[i] = down;
		}
		boolean delete = Keys.isKeyDown(Keys.DELETE);
		if (delete && !deleteDown && held) {
			RmOps.erase();
		}
		deleteDown = delete;
		boolean enter = Keys.isKeyDown(Keys.ENTER);
		if (enter && !enterDown && held) {
			confirm();
		}
		enterDown = enter;
		boolean backspace = Keys.isKeyDown(Keys.BACKSPACE);
		if (backspace && !backspaceDown && held) {
			cancelOrClear();
		}
		backspaceDown = backspace;
	}

	private static void toolKey(net.minecraft.client.option.KeyBinding key, RmState.Tool tool) {
		while (key.wasPressed()) {
			RedstoneMode.selectTool(tool);
		}
	}

	private static void onCtrl(char key) {
		switch (key) {
			case 'C' -> RmOps.copy(false);
			case 'X' -> RmOps.copy(true);
			case 'V' -> startPaste();
			case 'Z' -> RmOps.undo();
			case 'Y' -> RmOps.redo();
			default -> {
			}
		}
	}

	public static void startPaste() {
		if (RmOps.clipboard() == null) {
			RmState.error("Clipboard is empty - copy a selection first (Ctrl+C)");
			return;
		}
		RmState.pasting = true;
		RmState.pasteRotation = 0;
		RmState.pasteMirror = false;
		RmState.info("Aim and left click to paste  ·  Shift+scroll rotates  ·  right click cancels");
	}

	/** The axis you look along, as a face (up/down when looking steeply). */
	static int lookFace() {
		double[] look = RmWorld.eyeAndLook();
		if (look == null) {
			return 2;
		}
		double ax = Math.abs(look[3]);
		double ay = Math.abs(look[4]);
		double az = Math.abs(look[5]);
		if (ay >= ax && ay >= az) {
			return look[4] > 0 ? 1 : 0;
		}
		if (ax >= az) {
			return look[3] > 0 ? 5 : 4;
		}
		return look[5] > 0 ? 3 : 2;
	}

	/** Minimum corner where the clipboard paste would land (on the face you look at), or null. */
	static int[] pasteOrigin(Clip clip) {
		int[] look = RmWorld.lookedBlock(128);
		if (clip == null || look == null) {
			return null;
		}
		int[] d = RmWorld.faceOffset(look[3]);
		int y = look[3] == 0 ? look[1] - clip.sy : look[1] + d[1];
		return new int[] {look[0] + d[0] - clip.sx / 2, y, look[2] + d[2] - clip.sz / 2};
	}

	private static void confirm() {
		if (RmState.pasting) {
			Clip clip = RmOps.clipboard();
			if (clip != null) {
				Clip turned = clip.transformed(RmState.pasteRotation, RmState.pasteMirror);
				int[] origin = pasteOrigin(turned);
				if (origin != null) {
					RmOps.paste(turned, origin[0], origin[1], origin[2]);
				}
			}
			RmState.pasting = false;
			return;
		}
		switch (RmState.tool) {
			case MOVE -> RmOps.applyMove();
			case STACK -> RmOps.applyStack();
			case WIRE, WATER -> Wiring.build();
			default -> {
			}
		}
	}

	private static void cancelOrClear() {
		if (RmState.pasting) {
			RmState.pasting = false;
		} else if (!Wiring.preview().isEmpty() || RmState.wireStart != null) {
			Wiring.reset();
		} else if (RmState.moveActive() || RmState.stackCount != 0) {
			RmState.resetTransforms();
		} else {
			RmState.clearSelection();
		}
	}

	// ---- mouse (from RedstoneClickMixin) ----

	/** Left click; true when Redstone Mode used it. */
	public static boolean onAttack() {
		if (!RedstoneMode.active() || ClientCompat.currentScreen() != null) {
			return false;
		}
		Placements.Placement following = Placements.following();
		if (following != null) {
			following.locked = true;
			RmState.info("Schematic locked in place - it stays until you remove it in the Schematics panel");
			return true;
		}
		if (!RedstoneMode.toolHeld()) {
			return false;
		}
		if (RmState.pasting) {
			confirm();
			return true;
		}
		switch (RmState.tool) {
			case SELECT -> {
				int[] look = RmWorld.lookedBlock(256);
				if (look != null) {
					RmState.cornerA = new int[] {look[0], look[1], look[2]};
					RmState.resetTransforms();
				}
			}
			case MOVE, STACK -> confirm();
			case WIRE, WATER -> {
				if (!Wiring.preview().isEmpty()) {
					Wiring.build();
				} else {
					int[] look = RmWorld.lookedBlock(256);
					if (look != null) {
						RmState.wireStart = look;
						RmState.wireEnd = null;
						Wiring.clearPreview();
					}
				}
			}
		}
		return true;
	}

	public static boolean blocksBreaking() {
		return RedstoneMode.active() && ClientCompat.currentScreen() == null && (RedstoneMode.toolHeld() || Placements.following() != null);
	}

	/** Right click. */
	public static boolean onUse() {
		if (!RedstoneMode.active() || ClientCompat.currentScreen() != null) {
			return false;
		}
		Placements.Placement following = Placements.following();
		if (following != null) {
			Placements.remove(following);
			return true;
		}
		if (!RedstoneMode.toolHeld()) {
			return false;
		}
		if (RmState.pasting) {
			RmState.pasting = false;
			return true;
		}
		switch (RmState.tool) {
			case SELECT -> {
				int[] look = RmWorld.lookedBlock(256);
				if (look != null) {
					RmState.cornerB = new int[] {look[0], look[1], look[2]};
					if (RmState.cornerA == null) {
						RmState.cornerA = RmState.cornerB;
					}
					RmState.resetTransforms();
				}
			}
			case MOVE, STACK -> RmState.resetTransforms();
			case WIRE, WATER -> {
				int[] look = RmWorld.lookedBlock(256);
				if (look == null) {
					return true;
				}
				if (RmState.wireStart == null) {
					RmState.error("Left click the start first");
				} else {
					RmState.wireEnd = look;
					Wiring.plan(RmState.tool == RmState.Tool.WATER);
				}
			}
		}
		return true;
	}

	/** Middle click; true when it picked a schematic block. */
	public static boolean onPick() {
		return RedstoneMode.active() && ClientCompat.currentScreen() == null && Placements.pickLooked();
	}

	// ---- scroll ----

	private static boolean wantsScroll() {
		if (!RedstoneMode.active() || ClientCompat.currentScreen() != null) {
			return false;
		}
		if (RmKeys.CURSOR.isPressed()) {
			return true;
		}
		if (Placements.following() != null && RmKeys.shift()) {
			return true;
		}
		if (!RedstoneMode.toolHeld()) {
			return false;
		}
		if (RmState.pasting) {
			return RmKeys.shift();
		}
		return switch (RmState.tool) {
			case SELECT -> RmKeys.shift() && RmState.hasSelection();
			case MOVE, STACK -> RmState.hasSelection();
			default -> false;
		};
	}

	private static void onScroll(double amount) {
		int step = amount > 0 ? 1 : -1;
		if (RmKeys.CURSOR.isPressed()) {
			altScrolled = true;
			RmState.Tool[] tools = RmState.Tool.values();
			RedstoneMode.selectTool(tools[Math.floorMod(RmState.tool.ordinal() - step, tools.length)]);
			return;
		}
		Placements.Placement following = Placements.following();
		if (following != null) {
			following.rotation = Math.floorMod(following.rotation + step, 4);
			return;
		}
		if (RmState.pasting) {
			RmState.pasteRotation = Math.floorMod(RmState.pasteRotation + step, 4);
			return;
		}
		int face = lookFace();
		int[] d = RmWorld.faceOffset(face);
		switch (RmState.tool) {
			case SELECT -> grow(face, step);
			case MOVE -> {
				if (RmKeys.shift()) {
					RmState.moveRotation = Math.floorMod(RmState.moveRotation + step, 4);
				} else {
					RmState.moveOffset = new int[] {RmState.moveOffset[0] + d[0] * step, RmState.moveOffset[1] + d[1] * step,
							RmState.moveOffset[2] + d[2] * step};
				}
			}
			case STACK -> {
				// Count along the axis you look at; switching axis starts over.
				int axisFace = face % 2 == 0 ? face : face - 1;
				int currentAxis = RmState.stackFace % 2 == 0 ? RmState.stackFace : RmState.stackFace - 1;
				int signed = RmState.stackFace == face ? RmState.stackCount : axisFace == currentAxis ? -RmState.stackCount : 0;
				signed += step;
				RmState.stackFace = signed >= 0 ? face : opposite(face);
				RmState.stackCount = Math.min(256, Math.abs(signed));
			}
			default -> {
			}
		}
	}

	private static int opposite(int face) {
		return face % 2 == 0 ? face + 1 : face - 1;
	}

	/** Shift+scroll with Select: moves the selection's side you look at outwards (scroll forward) or inwards. */
	private static void grow(int face, int step) {
		int[] b = RmState.box();
		if (b == null) {
			return;
		}
		int[] d = RmWorld.faceOffset(face);
		int[] min = {b[0], b[1], b[2]};
		int[] max = {b[3], b[4], b[5]};
		for (int a = 0; a < 3; a++) {
			if (d[a] > 0) {
				max[a] = Math.max(min[a], max[a] + step);
			} else if (d[a] < 0) {
				min[a] = Math.min(max[a], min[a] - step);
			}
		}
		RmState.cornerA = min;
		RmState.cornerB = max;
	}
}
