package net.veloclient.velo.client.redstone;

/**
 * Redstone Mode's live state on the client: the active tool, the box selection, what's being
 * previewed (paste / move / stack / a wiring route), and short status messages for the overlay.
 */
public final class RmState {

	public enum Tool {
		SELECT("Select", "Left click: corner 1  ·  Right click: corner 2"),
		WIRE("Redstone Wire", "Left click: start  ·  Right click: end  ·  Enter: build"),
		WATER("Water Wire", "Left click: start  ·  Right click: end  ·  Enter: build"),
		BUILD("Build", "Normal building - Redstone keys still work");

		public final String label;
		public final String hint;

		Tool(String label, String hint) {
			this.label = label;
			this.hint = hint;
		}
	}

	/** What a confirm (Enter / left click) would apply right now. */
	public enum Pending { NONE, PASTE, MOVE, STACK, WIRE, WATER }

	public static Tool tool = Tool.SELECT;
	/** Selection corners {x,y,z} or null. */
	public static int[] cornerA;
	public static int[] cornerB;

	public static Pending pending = Pending.NONE;
	/** Preview transform for paste/move: quarter turns clockwise and east-west mirror. */
	public static int rotation;
	public static boolean mirrored;
	/** Stack: how many extra copies and in which direction (face index). */
	public static int stackCount = 1;
	public static int stackFace = 2;

	/** Wiring endpoints {x,y,z,face}. */
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

	/** {minX, minY, minZ, maxX, maxY, maxZ} of the selection, or null. */
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

	public static void clearSelection() {
		cornerA = null;
		cornerB = null;
	}

	public static void cancelPending() {
		pending = Pending.NONE;
		rotation = 0;
		mirrored = false;
		stackCount = 1;
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

	/** The current status line, or null when it has expired. */
	public static String message() {
		return System.currentTimeMillis() < messageUntil ? message : null;
	}

	public static boolean messageIsError() {
		return messageError;
	}
}
