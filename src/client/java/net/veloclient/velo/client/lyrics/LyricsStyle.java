package net.veloclient.velo.client.lyrics;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every Lyrics setting in one place (saved as one JSON string with the profile), plus the presets. */
public final class LyricsStyle {

	public static final List<String> DISPLAYS = List.of("3D World", "2D Screen");
	public static final List<String> PLACEMENTS = List.of("Sky", "In Front");
	public static final List<String> DEPTHS = List.of("Behind Blocks", "Always In Front");
	public static final List<String> SPREADS = List.of("One Spot", "Around You", "Everywhere");
	public static final List<String> FONTS = List.of("Archivo", "Inter", "Sora", "Space Grotesk", "Poppins", "Audiowide", "JetBrains Mono", "MinecraftClient");
	public static final List<String> COLOR_MODES = List.of("Solid", "Gradient", "Flowing Gradient", "Rainbow", "Per Word");
	public static final List<String> CASES = List.of("As Written", "UPPERCASE", "lowercase");
	public static final List<String> ENTERS = List.of("Fade", "Rise", "Drop", "Slide", "Zoom", "Pop", "Bounce", "Wave", "Spin", "Flip", "Fly In",
			"Typewriter", "Word by Word", "Glitch", "Blur In");
	public static final List<String> EXITS = List.of("Fade", "Rise", "Fall", "Slide", "Zoom", "Shrink", "Scatter", "Explode", "Fly Away", "Dissolve",
			"Glitch");
	public static final List<String> IDLES = List.of("None", "Float", "Wave", "Pulse", "Sway", "Breathe", "Shake", "Jelly");
	public static final List<String> SCREEN_SPOTS = List.of("Bottom", "Center", "Top");

	public String preset = "Concert Sky";
	// where
	public String display = "3D World";
	public String placement = "Sky";
	public String depth = "Behind Blocks";
	public String spread = "One Spot";
	public boolean followView = true;
	public double distance = 110;
	public double height = 22;
	public double size = 1.0;
	public double tilt = 0;
	public String screenSpot = "Bottom";
	public double screenSize = 1.0;
	// look
	public String font = "Archivo";
	public String textCase = "As Written";
	public double spacing = 0.4;
	public String colorMode = "Gradient";
	public int color = 0xFFFFFFFF;
	public int color2 = 0xFF9EC5FF;
	public boolean karaoke = false;
	public int highlight = 0xFFFFD84A;
	// solid letters: real depth (3D text, not a flat picture hanging in the world); only in the 3D World display
	public boolean text3d = false;
	/** How thick the letters are, in font units (a line is 9). */
	public double thickness = 2.4;
	/** Rounded edge, in font units. */
	public double bevel = 0.22;
	/** Colour of the sides; 0 = a darker shade of the letter's colour. */
	public int sideColor = 0;
	public boolean shadow = false;
	public int shadowColor = 0xFF000000;
	public boolean outline = false;
	public int outlineColor = 0xFF101014;
	public double outlineSize = 1.0;
	public boolean glow = true;
	public int glowColor = 0xFF5B8CFF;
	public double glowSize = 1.0;
	public double glowStrength = 1.0;
	public double opacity = 1.0;
	// motion
	public String enter = "Rise";
	public String exit = "Fade";
	public boolean perLetter = true;
	public double speed = 1.0;
	public String idle = "Float";
	public double chaos = 0;
	// lines
	public boolean showNext = true;
	public boolean showPrevious = false;
	/** Space between the current line and the previous / next line, in line heights. */
	public double lineGap = 2.4;
	public boolean songCard = true;
	public int offsetMs = 0;

	public boolean world3d() {
		return !"2D Screen".equals(display);
	}

	public boolean sky() {
		return "Sky".equals(placement);
	}

	/** Solid letters are drawn: only in the world, never in the 2D HUD. */
	public boolean solid() {
		return text3d && world3d();
	}

	public boolean seeThrough() {
		return "Always In Front".equals(depth);
	}

	private static final Gson GSON = new Gson();

	public String save() {
		return GSON.toJson(this);
	}

	public static LyricsStyle load(String json) {
		try {
			LyricsStyle s = GSON.fromJson(JsonParser.parseString(json).getAsJsonObject(), LyricsStyle.class);
			return s == null ? new LyricsStyle() : s.fixed();
		} catch (RuntimeException e) {
			return new LyricsStyle();
		}
	}

	/** Values from older saves or a typo'd file put back into range. */
	private LyricsStyle fixed() {
		LyricsStyle d = new LyricsStyle();
		if (!DISPLAYS.contains(display)) display = d.display;
		if (!PLACEMENTS.contains(placement)) placement = d.placement;
		if (!DEPTHS.contains(depth)) depth = d.depth;
		if (!SPREADS.contains(spread)) spread = d.spread;
		if (!FONTS.contains(font)) font = d.font;
		if (!COLOR_MODES.contains(colorMode)) colorMode = d.colorMode;
		if (!CASES.contains(textCase)) textCase = d.textCase;
		if (!ENTERS.contains(enter)) enter = d.enter;
		if (!EXITS.contains(exit)) exit = d.exit;
		if (!IDLES.contains(idle)) idle = d.idle;
		if (!SCREEN_SPOTS.contains(screenSpot)) screenSpot = d.screenSpot;
		if (lineGap < 1.2 || lineGap > 5) lineGap = d.lineGap;
		thickness = Math.max(0.4, Math.min(8, thickness));
		bevel = Math.max(0, Math.min(1.2, bevel));
		return this;
	}

	public LyricsStyle copy() {
		return load(save());
	}

	// ---------------------------------------------------------------- presets

	public static final Map<String, LyricsStyle> PRESETS = new LinkedHashMap<>();

	private static void preset(String name, java.util.function.Consumer<LyricsStyle> setup) {
		LyricsStyle s = new LyricsStyle();
		s.preset = name;
		setup.accept(s);
		PRESETS.put(name, s);
	}

	private static void sky(LyricsStyle s, double distance, double height, double size) {
		s.display = "3D World";
		s.placement = "Sky";
		s.depth = "Behind Blocks";
		s.distance = distance;
		s.height = height;
		s.size = size;
	}

	private static void front(LyricsStyle s, double distance, double height, double size, boolean seeThrough) {
		s.display = "3D World";
		s.placement = "In Front";
		s.depth = seeThrough ? "Always In Front" : "Behind Blocks";
		s.distance = distance;
		s.height = height;
		s.size = size;
	}

	private static void screen(LyricsStyle s, String spot, double size) {
		s.display = "2D Screen";
		s.screenSpot = spot;
		s.screenSize = size;
	}

	/** Solid letters with a given depth and rounded edge (the 3D look: real thickness, not a flat picture). */
	private static void solid(LyricsStyle s, double thickness, double bevel) {
		s.text3d = true;
		s.thickness = thickness;
		s.bevel = bevel;
	}

	// The presets are deliberately calm: white and one soft accent, no outlines or cartoon colours, gentle motion.
	static {
		preset("Concert Sky", s -> {
			sky(s, 110, 22, 1.0); solid(s, 2.6, 0.28);
			s.font = "Archivo"; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFFCFE0FF;
			s.glow = true; s.glowColor = 0xFF7FA8FF; s.glowSize = 1.1; s.glowStrength = 0.7;
			s.enter = "Rise"; s.exit = "Fade"; s.perLetter = true; s.idle = "Float"; s.showNext = true;
		});
		preset("Block Letters", s -> {
			front(s, 7, 0.6, 1.2, false); solid(s, 4.5, 0.18);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 0.6; s.colorMode = "Solid"; s.color = 0xFFF4F4F6; s.sideColor = 0xFF8E94A3;
			s.glow = false;
			s.enter = "Zoom"; s.exit = "Zoom"; s.perLetter = false; s.idle = "Breathe"; s.showNext = false;
		});
		preset("Chrome", s -> {
			front(s, 7, 0.8, 1.1, false); solid(s, 3.2, 0.5);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 0.9; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFFB4BCCB; s.sideColor = 0xFF5D6575;
			s.glow = false;
			s.enter = "Slide"; s.exit = "Fade"; s.perLetter = true; s.idle = "Sway"; s.speed = 0.9; s.showNext = false;
		});
		preset("Obsidian", s -> {
			front(s, 7, 0.6, 1.1, false); solid(s, 3.0, 0.3);
			s.font = "Sora"; s.spacing = 0.7; s.colorMode = "Solid"; s.color = 0xFF16181F; s.sideColor = 0xFF2B2F3B;
			s.glow = true; s.glowColor = 0xFFFFFFFF; s.glowSize = 0.9; s.glowStrength = 0.55;
			s.enter = "Fade"; s.exit = "Fade"; s.perLetter = false; s.idle = "Float"; s.showNext = false; s.speed = 0.8;
		});
		preset("Crimson", s -> {
			front(s, 7, 0.7, 1.2, false); solid(s, 3.4, 0.25);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 0.8; s.colorMode = "Solid"; s.color = 0xFFF0F0F2; s.sideColor = 0xFFD3243A;
			s.glow = true; s.glowColor = 0xFFD3243A; s.glowSize = 1.2; s.glowStrength = 0.6;
			s.enter = "Zoom"; s.exit = "Fade"; s.perLetter = false; s.idle = "None"; s.showNext = false;
		});
		preset("Midnight", s -> {
			sky(s, 120, 24, 1.1); solid(s, 2.4, 0.3);
			s.font = "Sora"; s.spacing = 0.6; s.colorMode = "Gradient"; s.color = 0xFFEAF0FF; s.color2 = 0xFF8FA6E8;
			s.glow = true; s.glowColor = 0xFF3F5FD0; s.glowSize = 1.4; s.glowStrength = 0.7;
			s.enter = "Blur In"; s.exit = "Fade"; s.perLetter = true; s.idle = "Float"; s.speed = 0.8; s.showNext = true;
		});
		preset("Starfield", s -> {
			sky(s, 140, 34, 0.8); s.spread = "Everywhere"; s.followView = false;
			s.font = "Sora"; s.textCase = "lowercase"; s.spacing = 1.4; s.colorMode = "Solid"; s.color = 0xFFF4F7FF;
			s.glow = true; s.glowColor = 0xFFFFFFFF; s.glowSize = 1.6; s.glowStrength = 0.6;
			s.enter = "Fly In"; s.exit = "Scatter"; s.perLetter = true; s.idle = "Breathe"; s.showNext = false; s.speed = 0.8;
		});
		preset("Aurora", s -> {
			sky(s, 160, 30, 1.2); solid(s, 2.2, 0.3);
			s.font = "Sora"; s.colorMode = "Flowing Gradient"; s.color = 0xFFD8FFF0; s.color2 = 0xFF9FB4FF;
			s.glow = true; s.glowColor = 0xFF5CCFB0; s.glowSize = 1.6; s.glowStrength = 0.6;
			s.enter = "Wave"; s.exit = "Fade"; s.perLetter = true; s.idle = "Wave"; s.speed = 0.8; s.showNext = true;
		});
		preset("Golden Hour", s -> {
			sky(s, 100, 14, 1.1); solid(s, 2.4, 0.3);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 1.0; s.colorMode = "Gradient"; s.color = 0xFFFFF0CF; s.color2 = 0xFFFFC27A;
			s.glow = true; s.glowColor = 0xFFFF9A3D; s.glowSize = 1.3; s.glowStrength = 0.6;
			s.enter = "Blur In"; s.exit = "Fade"; s.perLetter = false; s.idle = "None"; s.speed = 0.7; s.showNext = false;
		});
		preset("Dusk", s -> {
			sky(s, 120, 20, 1.2); solid(s, 2.4, 0.3);
			s.font = "Inter"; s.spacing = 1.2; s.colorMode = "Gradient"; s.color = 0xFFF6E9FF; s.color2 = 0xFFFFC9B8;
			s.glow = true; s.glowColor = 0xFFB58AF0; s.glowSize = 1.4; s.glowStrength = 0.55;
			s.enter = "Slide"; s.exit = "Fade"; s.perLetter = true; s.idle = "Sway"; s.tilt = -4; s.showNext = false; s.speed = 0.8;
		});
		preset("Ember", s -> {
			front(s, 7, 0.9, 1.1, false); solid(s, 3.0, 0.25);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 0.7; s.colorMode = "Gradient"; s.color = 0xFFFFF1D6; s.color2 = 0xFFFF9A52; s.sideColor = 0xFF9E3B14;
			s.glow = true; s.glowColor = 0xFFFF6A1F; s.glowSize = 1.3; s.glowStrength = 0.7;
			s.enter = "Rise"; s.exit = "Fade"; s.perLetter = true; s.idle = "Breathe"; s.showNext = false;
		});
		preset("Frost", s -> {
			sky(s, 90, 18, 1.0); s.spread = "Around You"; s.followView = false; solid(s, 2.2, 0.35);
			s.font = "Sora"; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFFBFE8FF;
			s.glow = true; s.glowColor = 0xFF8FD8FF; s.glowSize = 1.2; s.glowStrength = 0.6;
			s.enter = "Drop"; s.exit = "Fade"; s.perLetter = true; s.idle = "Sway"; s.speed = 0.8; s.showNext = true;
		});
		preset("Night Drive", s -> {
			front(s, 6, 0.6, 1.0, true);
			s.font = "Audiowide"; s.colorMode = "Solid"; s.color = 0xFFFFFFFF;
			s.glow = true; s.glowColor = 0xFF4F8CFF; s.glowSize = 1.3; s.glowStrength = 1.0;
			s.enter = "Fade"; s.exit = "Fade"; s.perLetter = true; s.idle = "Pulse"; s.karaoke = true; s.highlight = 0xFF8FB8FF; s.showNext = false;
		});
		preset("Hologram", s -> {
			front(s, 5, 0.9, 0.9, true); s.spread = "Around You"; s.followView = false;
			s.font = "JetBrains Mono"; s.textCase = "UPPERCASE"; s.spacing = 0.8; s.colorMode = "Solid"; s.color = 0xFFC9F4FF;
			s.glow = true; s.glowColor = 0xFF4FC8FF; s.glowSize = 1.0; s.glowStrength = 0.9; s.opacity = 0.85;
			s.enter = "Flip"; s.exit = "Dissolve"; s.perLetter = true; s.idle = "None"; s.showNext = true;
		});
		preset("Terminal", s -> {
			front(s, 5, 0.2, 0.8, true);
			s.font = "JetBrains Mono"; s.colorMode = "Solid"; s.color = 0xFFA6F0B8; s.textCase = "lowercase";
			s.glow = true; s.glowColor = 0xFF5CD27E; s.glowSize = 0.8; s.glowStrength = 0.6;
			s.enter = "Typewriter"; s.exit = "Fade"; s.perLetter = true; s.idle = "None"; s.showNext = false; s.showPrevious = true;
		});
		preset("Ghost", s -> {
			front(s, 8, 0.4, 1.1, false); s.spread = "Around You"; s.followView = false;
			s.font = "Inter"; s.textCase = "lowercase"; s.colorMode = "Solid"; s.color = 0xFFE8ECFF; s.opacity = 0.6;
			s.glow = true; s.glowColor = 0xFFFFFFFF; s.glowSize = 2.0; s.glowStrength = 0.5;
			s.enter = "Blur In"; s.exit = "Dissolve"; s.perLetter = true; s.idle = "Breathe"; s.speed = 0.6; s.showNext = false;
		});
		preset("Karaoke", s -> {
			front(s, 5, -0.4, 0.9, true);
			s.font = "Poppins"; s.colorMode = "Solid"; s.color = 0xFFB9BDC9; s.karaoke = true; s.highlight = 0xFFFFFFFF;
			s.outline = true; s.outlineColor = 0xFF101014; s.glow = false;
			s.enter = "Fade"; s.exit = "Rise"; s.perLetter = false; s.idle = "None"; s.showNext = true;
		});
		preset("Word Pop", s -> {
			front(s, 6, 0.3, 1.1, true);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.colorMode = "Solid"; s.color = 0xFFFFFFFF;
			s.glow = true; s.glowColor = 0xFF4F7BFF; s.glowSize = 0.9; s.glowStrength = 0.7; s.karaoke = true; s.highlight = 0xFFBFD3FF;
			s.enter = "Word by Word"; s.exit = "Zoom"; s.idle = "None"; s.showNext = false;
		});
		preset("Cinematic", s -> {
			screen(s, "Bottom", 1.2);
			s.font = "Archivo"; s.colorMode = "Solid"; s.color = 0xFFFFFFFF; s.shadow = true; s.glow = false;
			s.enter = "Zoom"; s.exit = "Fade"; s.perLetter = false; s.idle = "None"; s.showNext = false;
		});
		preset("Minimal", s -> {
			screen(s, "Bottom", 0.9);
			s.font = "Inter"; s.colorMode = "Solid"; s.color = 0xFFFFFFFF; s.shadow = true; s.glow = false;
			s.enter = "Fade"; s.exit = "Fade"; s.perLetter = false; s.idle = "None"; s.karaoke = true; s.highlight = 0xFF9DB8FF; s.showNext = true;
		});
		preset("Spotlight", s -> {
			screen(s, "Center", 1.6);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 0.8; s.colorMode = "Solid"; s.color = 0xFFFFFFFF;
			s.glow = true; s.glowColor = 0xFFFFFFFF; s.glowSize = 1.1; s.glowStrength = 0.5;
			s.enter = "Word by Word"; s.exit = "Fade"; s.idle = "None"; s.showNext = false;
		});
	}

	/** This style with a preset's look applied (keeps the sync offset). */
	public LyricsStyle withPreset(String name) {
		LyricsStyle p = PRESETS.get(name);
		if (p == null) {
			return this;
		}
		LyricsStyle s = p.copy();
		s.offsetMs = offsetMs;
		return s;
	}
}
