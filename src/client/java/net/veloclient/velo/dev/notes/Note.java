package net.veloclient.velo.dev.notes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One World Note, as the client keeps it - the same fields the Velo server stores for shared notes. Private notes
 * ({@link #local}) never leave this PC; they carry their picture inline ({@link #png}).
 */
public final class Note {

	public static final class Check {
		public String id;
		public String text;
		public boolean done;
		public String doneBy;
		public long doneAt;
	}

	/** A whiteboard stroke on the 256x160 board: colour (alpha 0 = eraser), width, points x,y,x,y... */
	public static final class Stroke {
		public int c;
		public int w;
		public int[] p;
	}

	public static final int BOARD_W = 256;
	public static final int BOARD_H = 160;
	public static final List<String> STYLES = List.of("paper", "sticky", "glass", "sign", "neon");
	public static final int[] COLORS = {0xFFF5D76E, 0xFFFF8A65, 0xFFF06292, 0xFFBA68C8, 0xFF64B5F6, 0xFF4DD0E1, 0xFF81C784, 0xFFE0E0E0};

	public String id;
	public boolean local;
	public String owner;
	public String ownerName;
	public boolean mine = true;
	public boolean canEdit = true;
	public boolean unread;
	public long created;
	public long updated;
	public String editorName;
	public int version;
	public String server;
	public String world;
	public String dim;
	public double x, y, z;
	public String title;
	public String text;
	public int color = COLORS[0];
	public String icon;
	public String style = "paper";
	public double size = 1.0;
	public boolean face = true;
	public double yaw;
	public List<Check> checks = new ArrayList<>();
	public List<Stroke> board = new ArrayList<>();
	public int image;
	/** Private notes only: the picture as base64 PNG. */
	public String png;
	public String share = "people";
	public List<String> people = new ArrayList<>();
	public Map<String, String> names = new LinkedHashMap<>();
	/** "owner", "everyone" or "picked" ({@link #editors} may edit and draw too). */
	public String edit = "owner";
	public List<String> editors = new ArrayList<>();
	/** "note" (text, checklist, extras), "image" (only a picture) or "board" (only a whiteboard). Picture and board are Velo+. */
	public String kind = "note";
	public List<String> mentions = new ArrayList<>();

	public boolean isImage() {
		return "image".equals(kind);
	}

	public boolean isBoard() {
		return "board".equals(kind);
	}

	/** Pictures, picture notes and whiteboards are Velo+ (people a note is shared with can still draw on it). */
	public static boolean plus() {
		return net.veloclient.velo.client.network.StoreClient.plus();
	}

	/** Whether the server you're on lets pictures / whiteboards show (Velo Bridge plugin settings). */
	public static boolean imagesHere() {
		return net.veloclient.velo.dev.bridge.VeloBridgeClient.notesImages();
	}

	public static boolean whiteboardsHere() {
		return net.veloclient.velo.dev.bridge.VeloBridgeClient.notesWhiteboards();
	}

	/** False when this server turned off what the note is made of (a picture note where pictures are off...). */
	public boolean showsHere() {
		return !(isImage() && !imagesHere() || isBoard() && !whiteboardsHere());
	}

	public String displayTitle() {
		if (title != null && !title.isBlank()) {
			return title;
		}
		if (text != null && !text.isBlank()) {
			String first = text.strip().lines().findFirst().orElse("Note");
			return first.length() > 40 ? first.substring(0, 40) + "..." : first;
		}
		return isImage() ? "Picture" : isBoard() ? "Whiteboard" : "Note";
	}

	public int done() {
		return (int) checks.stream().filter(c -> c.done).count();
	}

	/** Shared with anyone (lives on the Velo server). */
	public boolean shared() {
		return !local;
	}

	public Note copy() {
		return NoteStore.GSON.fromJson(NoteStore.GSON.toJson(this), Note.class);
	}
}
