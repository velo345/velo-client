package net.veloclient.velo.dev.notes;

import java.util.HashMap;
import java.util.Map;

/** Width / height of note pictures (read from the PNG header when the bytes arrive), so cards don't stretch them. */
public final class NoteImages {

	private static final Map<String, Float> ASPECT = new HashMap<>();

	private NoteImages() {
	}

	static void remember(String key, byte[] png) {
		if (png == null || png.length < 24) {
			return;
		}
		int w = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
		int h = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
		if (w > 0 && h > 0) {
			ASPECT.put(key, w / (float) h);
		}
	}

	/** Width / height of the note's picture (16:10 until it's known). */
	public static float aspect(Note note) {
		return ASPECT.getOrDefault(note.id + ":" + note.image, 1.6f);
	}
}
