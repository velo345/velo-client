package net.veloclient.velo.client.gui.glass;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The look of Velo's glass menus (Settings > Theme, 2026-11-25): the accent colour (white by default), how dark the
 * glass is, how strong the blur behind it is and how round the corners are. Every menu reads these live.
 * Saved in {@code glass.json}.
 */
public final class GlassPrefs {

	public static final int DEFAULT_ACCENT = 0xFFFFFFFF;
	public static final int DEFAULT_GLASS = 55;
	public static final int DEFAULT_BLUR = 70;
	public static final int DEFAULT_CORNERS = 60;

	/** The accent presets offered as swatches. */
	public static final int[] SWATCHES = {0xFFFFFFFF, 0xFFE5373E, 0xFFFF8A3D, 0xFFFFC93D, 0xFF4CD07D, 0xFF3DD6D0, 0xFF4C9AFF, 0xFF7C6BFF,
			0xFFC266FF, 0xFFFF5FA8};
	public static final String[] SWATCH_NAMES = {"White", "Red", "Orange", "Yellow", "Green", "Teal", "Blue", "Indigo", "Purple", "Pink"};

	private static boolean loaded;
	private static int accent = DEFAULT_ACCENT;
	private static int glass = DEFAULT_GLASS;
	private static int blur = DEFAULT_BLUR;
	private static int corners = DEFAULT_CORNERS;

	private GlassPrefs() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("glass.json");
	}

	private static long checkedAt;
	private static long fileTime = -1;

	private static void load() {
		if (loaded) {
			// the launcher's Customize > In-game look writes the same file: follow it while the game runs
			long now = System.currentTimeMillis();
			if (now - checkedAt < 500) {
				return;
			}
			checkedAt = now;
			long time;
			try {
				time = Files.exists(file()) ? Files.getLastModifiedTime(file()).toMillis() : -1;
			} catch (Exception e) {
				return;
			}
			if (time == fileTime) {
				return;
			}
		}
		loaded = true;
		checkedAt = System.currentTimeMillis();
		try {
			fileTime = Files.exists(file()) ? Files.getLastModifiedTime(file()).toMillis() : -1;
		} catch (Exception e) {
			fileTime = -1;
		}
		try {
			if (Files.exists(file())) {
				JsonObject o = new Gson().fromJson(Files.readString(file(), StandardCharsets.UTF_8), JsonObject.class);
				if (o != null) {
					accent = o.has("accent") ? o.get("accent").getAsInt() | 0xFF000000 : DEFAULT_ACCENT;
					glass = clamp(o.has("glass") ? o.get("glass").getAsInt() : DEFAULT_GLASS);
					blur = clamp(o.has("blur") ? o.get("blur").getAsInt() : DEFAULT_BLUR);
					corners = clamp(o.has("corners") ? o.get("corners").getAsInt() : DEFAULT_CORNERS);
				}
			}
		} catch (Exception ignored) {
			// unreadable: defaults
		}
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(100, v));
	}

	private static void save() {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("accent", accent);
			o.addProperty("glass", glass);
			o.addProperty("blur", blur);
			o.addProperty("corners", corners);
			Files.createDirectories(file().getParent());
			Files.writeString(file(), o.toString(), StandardCharsets.UTF_8);
			fileTime = Files.getLastModifiedTime(file()).toMillis();
		} catch (Exception ignored) {
			// not saved this time
		}
	}

	public static int accent() {
		load();
		return accent;
	}

	/** Text/icons on an accent fill: dark on light accents (white, yellow...), white on dark ones. */
	public static int onAccent() {
		int c = accent();
		double lum = (0.2126 * ((c >> 16) & 0xFF) + 0.7152 * ((c >> 8) & 0xFF) + 0.0722 * (c & 0xFF)) / 255.0;
		return lum > 0.6 ? 0xFF141416 : 0xFFFFFFFF;
	}

	public static int glass() {
		load();
		return glass;
	}

	public static int blur() {
		load();
		return blur;
	}

	public static int corners() {
		load();
		return corners;
	}

	public static void setAccent(int v) {
		load();
		accent = v | 0xFF000000;
		save();
	}

	public static void setGlass(int v) {
		load();
		glass = clamp(v);
		save();
	}

	public static void setBlur(int v) {
		load();
		blur = clamp(v);
		save();
	}

	public static void setCorners(int v) {
		load();
		corners = clamp(v);
		save();
	}

	public static void reset() {
		load();
		accent = DEFAULT_ACCENT;
		glass = DEFAULT_GLASS;
		blur = DEFAULT_BLUR;
		corners = DEFAULT_CORNERS;
		save();
	}

	/** A glass tint's alpha for the chosen darkness ({@code base} is the alpha at the default). */
	public static int tint(int argb) {
		int base = (argb >>> 24) & 0xFF;
		float f = glass() / (float) DEFAULT_GLASS;   // 0 = clear ... 100 = nearly solid
		int a = Math.round(Math.min(250f, base * (0.35f + 0.65f * f)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** Blur radius for menus ({@code max} at 100 %); 0 turns it off. */
	public static int blurRadius(int max) {
		return Math.round(max * blur() / 100f * 1.25f);
	}

	/** Corner radius scaled by the corners setting ({@code base} at the default). */
	public static float radius(float base) {
		return base * (0.25f + 1.25f * corners() / 100f);
	}

	public static String accentName() {
		int a = accent();
		for (int i = 0; i < SWATCHES.length; i++) {
			if (SWATCHES[i] == a) {
				return SWATCH_NAMES[i];
			}
		}
		return String.format("#%06X", a & 0xFFFFFF);
	}
}
