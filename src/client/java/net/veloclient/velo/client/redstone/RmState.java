package net.veloclient.velo.client.redstone;

/**
 * Redstone Mode's live client state: the chosen tool (used while the Redstone slot is selected in
 * the hotbar), the box selection, the move / stack offset built up with the scroll wheel, a
 * clipboard paste following the crosshair, wiring endpoints and short status messages.
 */
public final class RmState {

	public enum Tool {
		SELECT("select", "Select", "Left click corner 1  ·  right click corner 2  ·  Shift+scroll grows the side you look at"),
		MOVE("move", "Move", "Look along an axis and scroll to move  ·  Shift+scroll rotates  ·  left click applies"),
		STACK("stack", "Stack", "Look along an axis and scroll to add copies  ·  left click applies"),
		WIRE("wire", "Redstone Wire", "Left click start  ·  right click end  ·  left click again to build"),
		WATER("water", "Water Wire", "Left click start  ·  right click end  ·  left click again to build");

		public final String icon;
		public final String label;
		public final String hint;

		Tool(String icon, String label, String hint) {
			this.icon = icon;
			this.label = label;
			this.hint = hint;
		}
	}

	public static Tool tool = Tool.SELECT;
	public static int[] cornerA;
	public static int[] cornerB;

	/** Move tool: offset built with the scroll wheel, plus rotation / mirror. */
	public static int[] moveOffset = {0, 0, 0};
	public static int moveRotation;
	public static boolean moveMirror;

	/** Stack tool: extra copies along one axis (negative = the other way). */
	public static int stackCount;
	public static int stackFace = 2;

	/** Clipboard paste following the crosshair (Ctrl+V), with rotation / mirror. */
	public static boolean pasting;
	public static int pasteRotation;
	public static boolean pasteMirror;

	public static int[] wireStart;
	public static int[] wireEnd;

	private static String message;
	private static long messageUntil;
	private static boolean messageError;

	private RmState() {
	}

	public static boolean hasSelection() {
		return cornerA != null && cornerB != null;
	}

	/** {minX, minY, minZ, maxX, maxY, maxZ} or null. */
	public static int[] box() {
		if (!hasSelection()) {
			return null;
		}
		return new int[] {Math.min(cornerA[0], cornerB[0]), Math.min(cornerA[1], cornerB[1]), Math.min(cornerA[2], cornerB[2]),
				Math.max(cornerA[0], cornerB[0]), Math.max(cornerA[1], cornerB[1]), Math.max(cornerA[2], cornerB[2])};
	}

	public static long volume() {
		int[] b = box();
		return b == null ? 0 : (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
	}

	public static int[] size() {
		int[] b = box();
		return b == null ? null : new int[] {b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1};
	}

	public static void clearSelection() {
		cornerA = null;
		cornerB = null;
		resetTransforms();
	}

	/** Forget any move / stack offsets and an open paste. */
	public static void resetTransforms() {
		moveOffset = new int[] {0, 0, 0};
		moveRotation = 0;
		moveMirror = false;
		stackCount = 0;
		pasting = false;
		pasteRotation = 0;
		pasteMirror = false;
	}

	public static boolean moveActive() {
		return moveOffset[0] != 0 || moveOffset[1] != 0 || moveOffset[2] != 0 || moveRotation != 0 || moveMirror;
	}

	public static void info(String text) {
		message = text;
		messageError = false;
		messageUntil = System.currentTimeMillis() + 4000;
	}

	public static void error(String text) {
		message = text;
		messageError = true;
		messageUntil = System.currentTimeMillis() + 5000;
	}

	public static String message() {
		return System.currentTimeMillis() < messageUntil ? message : null;
	}

	public static boolean messageIsError() {
		return messageError;
	}
}
