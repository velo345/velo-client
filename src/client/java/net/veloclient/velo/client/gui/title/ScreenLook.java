package net.veloclient.velo.client.gui.title;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Velo+ Customize: how the title screen, the pause menu and the loading screen look - edited in the launcher's
 * Customize tab, saved in {@code config/screens.json} (on this PC only) and picked up live (the file is checked twice a
 * second). Without Velo+ every screen keeps Velo's own look; the file stays for when the membership comes back.
 *
 * <p>Positions are never pixels: an alignment (left / centre / right) plus offsets as a fraction of the screen, so a
 * layout made on one monitor fits every other window size and GUI scale. Key names avoid "Text"/"Box" (the build's
 * version rules rewrite those words, also inside strings).
 */
public final class ScreenLook {

	/** The title screen. */
	public static final class Title {
		/** "velo" (the animated panorama), "image" (your picture or GIF) or "vanilla" (Minecraft's own panorama). */
		public String background = "velo";
		/** Extra darkness over the background, 0..100. */
		public int dim = 0;
		/** The dark fade behind the menu, 0..100 (100 = Velo's default). */
		public int scrim = 100;
		/** Blur of your own picture, 0..100. */
		public int blur = 0;
		/** "left", "center" or "right". */
		public String align = "left";
		/** Size of the menu words, 0.6..1.6. */
		public float scale = 1f;
		/** "Archivo" (Velo's), "Sora", "Inter", "Poppins", "Space Grotesk", "Mono", "Audiowide" or "Minecraft". */
		public String font = "Archivo";
		/** Word colour / hover colour / accent (0xRRGGBB), or -1 for the theme's. */
		public int color = -1;
		public int hoverColor = -1;
		public int accent = -1;
		/** "words", "pills", "glass", "outline" or "underline". */
		public String buttons = "words";
		public boolean shadow = false;
		/** Glow around the words, 0..100. */
		public int glow = 0;
		public boolean brand = true;
		public boolean jump = true;
		public boolean footer = true;
		public boolean store = true;
		/** Free offsets per part ("brand", "card", "menu", "footer", "store"): {dx, dy} as a fraction of the screen. */
		public final Map<String, float[]> moved = new HashMap<>();

		public float dx(String part) {
			float[] v = moved.get(part);
			return v == null ? 0f : v[0];
		}

		public float dy(String part) {
			float[] v = moved.get(part);
			return v == null ? 0f : v[1];
		}

		boolean isDefault() {
			return "velo".equals(background) && dim == 0 && scrim == 100 && "left".equals(align) && scale == 1f && "Archivo".equals(font)
					&& color == -1 && hoverColor == -1 && accent == -1 && "words".equals(buttons) && !shadow && glow == 0 && brand && jump
					&& footer && store && moved.isEmpty();
		}
	}

	/** The pause menu. */
	public static final class Pause {
		/** Darkness over the game, 0..100 (100 = Velo's default). */
		public int dim = 100;
		/** Blur of the game behind, 0..10 like Minecraft's option, or -1 to keep the game's setting. */
		public int blur = -1;
		/** Tint of the overlay (0xRRGGBB). */
		public int tint = 0x08080B;
		/** "left" or "right" (with Essential always left: its character needs that side). */
		public String align = "left";
		/** "glass", "solid" or "clear". */
		public String panel = "glass";
		/** The server / toggles / party cards on the other side. */
		public boolean cards = true;
		public int accent = -1;
		public int color = -1;
	}

	/** The loading screen (startup and resource reloads). */
	public static final class Loading {
		/** "velo" (the blurred panorama), "image" (your picture) or "color". */
		public String background = "velo";
		public int color = 0x0B0B10;
		/** Blur of your picture, 0..100. */
		public int blur = 40;
		/** Extra darkness, 0..100. */
		public int dim = 25;
		public boolean logo = true;
		/** Logo size, 0.5..2. */
		public float logoSize = 1f;
		/** "line", "thick" or "hidden". */
		public String bar = "line";
		public int barColor = 0xFFFFFF;
		/** Your own line under the logo (drawn from Velo's font files - the game's fonts aren't loaded yet). */
		public String caption = "";
		public String captionFont = "Inter";
		/** 0.5..3 */
		public float captionSize = 1f;
		public int captionColor = 0xFFFFFF;
	}

	/** The Velo menu (R-Shift) and every Velo window: look and motion. */
	public static final class Menu {
		/** "velo", "glass", "minimal", "glow", "neon" or "solid". */
		public String style = "velo";
		/** How it opens: "spring", "fade", "zoom", "pop", "slide-up", "slide-down", "slide-left", "slide-right", "drop", "unfold", "none". */
		public String open = "spring";
		/** How it closes: "spring", "fade", "zoom", "slide-up", "slide-down", "slide-left", "slide-right", "fold", "none". */
		public String close = "spring";
		/** Page switches inside the menu: "slide", "fade" or "none". */
		public String pages = "slide";
		/** 0.5 (slow) .. 2 (fast). */
		public float speed = 1f;
		/** Darkness behind the menu, 0..100 (44 = Velo's). */
		public int dim = 44;
		/** How see-through the window is, 0..100 (0 = Velo's almost solid). */
		public int clear = 0;
		/** Corner radius, 0..20, or -1 for Velo's. */
		public int radius = -1;
		public boolean isDefault() {
			return "velo".equals(style) && "spring".equals(open) && "spring".equals(close) && "slide".equals(pages) && speed == 1f
					&& dim == 44 && clear == 0 && radius == -1;
		}
	}

	private static final Menu DEFAULT_MENU = new Menu();
	private static volatile Menu menu = DEFAULT_MENU;

	public static Menu menu() {
		refresh();
		return plus() ? menu : DEFAULT_MENU;
	}

	private static final Title DEFAULT_TITLE = new Title();
	private static final Pause DEFAULT_PAUSE = new Pause();
	private static final Loading DEFAULT_LOADING = new Loading();

	private static volatile Title title = DEFAULT_TITLE;
	private static volatile Pause pause = DEFAULT_PAUSE;
	private static volatile Loading loading = DEFAULT_LOADING;
	private static long stamp = Long.MIN_VALUE;
	private static long checkedAt;
	private static volatile int generation;

	/** Dev tours: -Dvelo.screenLook=<file> uses that file and counts as Velo+. */
	private static final String TOUR_FILE = System.getProperty("velo.screenLook");

	private ScreenLook() {
	}

	public static Path file() {
		return TOUR_FILE != null ? Path.of(TOUR_FILE) : VeloPaths.config().resolve("screens.json");
	}

	/** Where the pictures live: {@code <velo root>/backgrounds/<slot>.<png|jpg|gif|bmp>}. */
	public static Path picture(String slot) {
		Path dir = TOUR_FILE != null ? Path.of(TOUR_FILE).toAbsolutePath().getParent() : VeloPaths.root().resolve("backgrounds");
		for (String type : new String[] {"png", "jpg", "jpeg", "gif", "bmp"}) {
			Path p = dir.resolve(slot + "." + type);
			if (Files.isRegularFile(p)) {
				return p;
			}
		}
		return null;
	}

	public static Title title() {
		refresh();
		return plus() ? title : DEFAULT_TITLE;
	}

	public static Pause pause() {
		refresh();
		return plus() ? pause : DEFAULT_PAUSE;
	}

	public static Loading loading() {
		refresh();
		return plus() ? loading : DEFAULT_LOADING;
	}

	/** Bumps whenever the file changed (pictures reload on it). */
	public static int generation() {
		return generation;
	}

	// ---- Velo+ ----

	private static boolean proof;
	private static long proofAt;

	/**
	 * Velo+, also before the store answered (startup, offline): the signed proof on disk counts too - else the loading
	 * screen and the first title frames would show the default look and then jump.
	 */
	public static boolean plus() {
		if (TOUR_FILE != null) {
			return true;
		}
		try {
			if (net.veloclient.velo.client.network.StoreClient.plus()) {
				return true;
			}
		} catch (Throwable ignored) {
			// not loaded yet
		}
		long now = System.currentTimeMillis();
		// a "no" is checked again soon: at startup the session (whose account the proof must match) may not be known yet
		if (now - proofAt > (proof ? 30_000 : 2_000)) {
			proofAt = now;
			try {
				proof = net.veloclient.velo.client.plus.PlusAccess.allowed();
			} catch (Throwable t) {
				proof = false;
			}
		}
		return proof;
	}

	// ---- Reading ----

	private static synchronized void refresh() {
		long now = System.nanoTime();
		if (stamp != Long.MIN_VALUE && now - checkedAt < 500_000_000L) {
			return;
		}
		checkedAt = now;
		Path file = file();
		long current;
		try {
			current = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() ^ Files.size(file) : 0;
		} catch (Exception e) {
			current = 0;
		}
		if (current == stamp) {
			return;
		}
		stamp = current;
		generation++;
		if (current == 0) {
			title = DEFAULT_TITLE;
			pause = DEFAULT_PAUSE;
			loading = DEFAULT_LOADING;
			menu = DEFAULT_MENU;
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			title = readTitle(obj(root, "title"));
			pause = readPause(obj(root, "pause"));
			loading = readLoading(obj(root, "loading"));
			menu = readMenu(obj(root, "menu"));
		} catch (Exception e) {
			// half-written file (the launcher saves while you drag): keep the last good look, try again soon
			stamp = Long.MIN_VALUE + 1;
		}
	}

	private static Title readTitle(JsonObject o) {
		Title t = new Title();
		t.background = oneOf(str(o, "background", t.background), "velo", "image", "vanilla");
		t.dim = num(o, "dim", t.dim, 0, 100);
		t.scrim = num(o, "scrim", t.scrim, 0, 100);
		t.blur = num(o, "blur", t.blur, 0, 100);
		t.align = oneOf(str(o, "align", t.align), "left", "center", "right");
		t.scale = fnum(o, "scale", t.scale, 0.6f, 1.6f);
		t.font = str(o, "font", t.font);
		t.color = color(o, "color");
		t.hoverColor = color(o, "hoverColor");
		t.accent = color(o, "accent");
		t.buttons = oneOf(str(o, "buttons", t.buttons), "words", "pills", "glass", "outline", "underline");
		t.shadow = bool(o, "shadow", t.shadow);
		t.glow = num(o, "glow", t.glow, 0, 100);
		t.brand = bool(o, "brand", t.brand);
		t.jump = bool(o, "jump", t.jump);
		t.footer = bool(o, "footer", t.footer);
		t.store = bool(o, "store", t.store);
		JsonObject moved = obj(o, "moved");
		for (String part : moved.keySet()) {
			try {
				var arr = moved.getAsJsonArray(part);
				float dx = Math.max(-1f, Math.min(1f, arr.get(0).getAsFloat()));
				float dy = Math.max(-1f, Math.min(1f, arr.get(1).getAsFloat()));
				if (dx != 0 || dy != 0) {
					t.moved.put(part, new float[] {dx, dy});
				}
			} catch (Exception ignored) {
				// skip a broken entry
			}
		}
		return t.isDefault() ? DEFAULT_TITLE : t;
	}

	private static Pause readPause(JsonObject o) {
		Pause p = new Pause();
		p.dim = num(o, "dim", p.dim, 0, 100);
		p.blur = num(o, "blur", p.blur, -1, 10);
		int tint = color(o, "tint");
		p.tint = tint < 0 ? p.tint : tint;
		p.align = oneOf(str(o, "align", p.align), "left", "right");
		p.panel = oneOf(str(o, "panel", p.panel), "glass", "solid", "clear");
		p.cards = bool(o, "cards", p.cards);
		p.accent = color(o, "accent");
		p.color = color(o, "color");
		return p;
	}

	private static Menu readMenu(JsonObject o) {
		Menu m = new Menu();
		m.style = oneOf(str(o, "style", m.style), "velo", "glass", "minimal", "glow", "neon", "solid");
		m.open = oneOf(str(o, "open", m.open), "spring", "fade", "zoom", "pop", "slide-up", "slide-down", "slide-left", "slide-right",
				"drop", "unfold", "none");
		m.close = oneOf(str(o, "close", m.close), "spring", "fade", "zoom", "slide-up", "slide-down", "slide-left", "slide-right", "fold", "none");
		m.pages = oneOf(str(o, "pages", m.pages), "slide", "fade", "none");
		m.speed = fnum(o, "speed", m.speed, 0.5f, 2f);
		m.dim = num(o, "dim", m.dim, 0, 100);
		m.clear = num(o, "clear", m.clear, 0, 100);
		m.radius = num(o, "radius", m.radius, -1, 20);
		return m.isDefault() ? DEFAULT_MENU : m;
	}

	private static Loading readLoading(JsonObject o) {
		Loading l = new Loading();
		l.background = oneOf(str(o, "background", l.background), "velo", "image", "color");
		int c = color(o, "color");
		l.color = c < 0 ? l.color : c;
		l.blur = num(o, "blur", l.blur, 0, 100);
		l.dim = num(o, "dim", l.dim, 0, 100);
		l.logo = bool(o, "logo", l.logo);
		l.logoSize = fnum(o, "logoSize", l.logoSize, 0.5f, 2f);
		l.bar = oneOf(str(o, "bar", l.bar), "line", "thick", "hidden");
		int bc = color(o, "barColor");
		l.barColor = bc < 0 ? l.barColor : bc;
		l.caption = str(o, "caption", "");
		if (l.caption.length() > 80) {
			l.caption = l.caption.substring(0, 80);
		}
		l.captionFont = str(o, "captionFont", l.captionFont);
		l.captionSize = fnum(o, "captionSize", l.captionSize, 0.5f, 3f);
		int cc = color(o, "captionColor");
		l.captionColor = cc < 0 ? l.captionColor : cc;
		return l;
	}

	// ---- JSON helpers ----

	private static JsonObject obj(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
	}

	private static String str(JsonObject o, String key, String fallback) {
		try {
			return o.has(key) ? o.get(key).getAsString() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static String oneOf(String v, String... allowed) {
		for (String a : allowed) {
			if (a.equals(v)) {
				return v;
			}
		}
		return allowed[0];
	}

	private static int num(JsonObject o, String key, int fallback, int min, int max) {
		try {
			return o.has(key) ? Math.max(min, Math.min(max, o.get(key).getAsInt())) : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static float fnum(JsonObject o, String key, float fallback, float min, float max) {
		try {
			return o.has(key) ? Math.max(min, Math.min(max, o.get(key).getAsFloat())) : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	private static boolean bool(JsonObject o, String key, boolean fallback) {
		try {
			return o.has(key) ? o.get(key).getAsBoolean() : fallback;
		} catch (Exception e) {
			return fallback;
		}
	}

	/** "#RRGGBB" (or empty / missing for the theme's) as 0xRRGGBB, -1 for none. */
	private static int color(JsonObject o, String key) {
		try {
			if (!o.has(key)) {
				return -1;
			}
			String s = o.get(key).getAsString().trim();
			if (s.startsWith("#")) {
				s = s.substring(1);
			}
			return s.length() == 6 ? Integer.parseInt(s, 16) : -1;
		} catch (Exception e) {
			return -1;
		}
	}

	// ---- Fonts ----

	/** The high-resolution font id (size 9, 8x oversampled) for a font name, or null for Velo's own menu font. */
	public static String fontId(String name) {
		return switch (name == null ? "" : name) {
			case "Sora" -> "lyrics_sora";
			case "Inter" -> "lyrics_inter";
			case "Poppins" -> "lyrics_poppins";
			case "Space Grotesk" -> "lyrics_grotesk";
			case "Mono" -> "lyrics_mono";
			case "Audiowide" -> "lyrics_audiowide";
			case "Minecraft" -> "mc";
			default -> null;
		};
	}
}
