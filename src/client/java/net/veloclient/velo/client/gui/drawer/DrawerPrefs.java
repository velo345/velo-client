package net.veloclient.velo.client.gui.drawer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What R-Shift opens (Settings > Controls): the last size you used (default), always the panel, or always the full menu.
 * Saved in {@code drawer.json}.
 */
public final class DrawerPrefs {

	public static final String LAST = "Last used";
	public static final String PANEL = "Panel";
	public static final String FULL = "Full menu";

	private static String mode;
	private static boolean lastFull;
	private static boolean toured;

	private DrawerPrefs() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("drawer.json");
	}

	private static void load() {
		if (mode != null) {
			return;
		}
		mode = LAST;
		try {
			if (Files.exists(file())) {
				JsonObject o = new Gson().fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
				if (o != null && o.has("mode")) {
					String m = o.get("mode").getAsString();
					mode = m.equals(PANEL) || m.equals(FULL) ? m : LAST;
				}
				lastFull = o != null && o.has("lastFull") && o.get("lastFull").getAsBoolean();
				toured = o != null && o.has("toured") && o.get("toured").getAsBoolean();
			}
		} catch (Exception ignored) {
			// unreadable: defaults
		}
	}

	private static void save() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("mode", mode);
			o.addProperty("lastFull", lastFull);
			o.addProperty("toured", toured);
			Files.createDirectories(file().getParent());
			Files.writeString(file(), o.toString(), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// not saved this time
		}
	}

	public static String mode() {
		load();
		return mode;
	}

	/** Settings row: Last used > Panel > Full menu > ... */
	public static void cycleMode() {
		load();
		mode = mode.equals(LAST) ? PANEL : mode.equals(PANEL) ? FULL : LAST;
		save();
	}

	/** Whether R-Shift opens the full menu. */
	public static boolean startFull() {
		load();
		return mode.equals(FULL) || (mode.equals(LAST) && lastFull);
	}

	/** Whether the short R-Shift tour has been shown (or skipped). */
	public static boolean toured() {
		load();
		return toured;
	}

	/** Settings > Controls "Menu tour": false shows it again the next time the menu opens. */
	public static void setToured(boolean value) {
		load();
		if (toured != value) {
			toured = value;
			save();
		}
	}

	/** The size the menu was left in (for "Last used"). */
	public static void rememberFull(boolean full) {
		load();
		if (lastFull != full) {
			lastFull = full;
			save();
		}
	}
}
