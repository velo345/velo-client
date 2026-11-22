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
	public static final List<String> FONTS = List.of("Archivo", "Inter", "Sora", "Space Grotesk", "Poppins", "Audiowide", "JetBrains Mono", "Minecraft");
	public static final List<String> COLOR_MODES = List.of("Solid", "Gradient", "Flowing Gradient", "Rainbow", "Per Word");
	public static final List<String> CASES = List.of("As Written", "UPPERCASE", "lowercase");
	public static final List<String> ENTERS = List.of("Fade", "Rise", "Drop", "Slide", "Zoom", "Pop", "Bounce", "Wave", "Spin", "Flip", "Fly In",
			"Typewriter", "Word by Word", "Glitch", "Blur In");
	public static final List<String> TRIGGERS = List.of("Big moments", "Every beat");
	public static final List<String> DEPTHS_3D = List.of("Off", "Light", "Deep");
	/** What the Studio offers (older saves may still say "Per Word" - shown as a gradient). */
	public static final List<String> SIMPLE_COLOR_MODES = List.of("Solid", "Gradient", "Flowing Gradient", "Rainbow");
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
	/** Beat effects on the music's beat (BeatTracker listens to what the PC plays): see LyricsPulse. */
	public boolean beatSync = false;
	public String beatTrigger = "Big moments";
	public double beatStrength = 1.0;
	public boolean fxSplit = true;
	public boolean fxFlash = true;
	public boolean fxShake = false;
	public boolean fxGlitch = false;
	public boolean fxZoom = false;
	/** The letters bounce a little on every beat. */
	public boolean beatBounce = true;
	/** Extruded 3D letters: Off / Light / Deep, in a darker shade of the colour (or depthColor when set). */
	public String depth3d = "Off";
	public int depthColor = 0;
	/** Older saves (Subtle / Strong / Insane) - read once into the fields above. */
	public String beatPulse;

	public boolean world3d() {
		return !"2D Screen".equals(display);
	}

	public boolean sky() {
		return "Sky".equals(placement);
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
		if (!TRIGGERS.contains(beatTrigger)) beatTrigger = d.beatTrigger;
		if (!DEPTHS_3D.contains(depth3d)) depth3d = d.depth3d;
		if (beatStrength < 0.2 || beatStrength > 2.5) beatStrength = d.beatStrength;
		if ("Per Word".equals(colorMode)) colorMode = "Gradient";
		if (beatPulse != null) {
			beatSync = !"Off".equals(beatPulse);
			beatStrength = "Subtle".equals(beatPulse) ? 0.5 : "Insane".equals(beatPulse) ? 1.8 : 1.0;
			beatPulse = null;
		}
		if (lineGap < 1.2 || lineGap > 5) lineGap = d.lineGap;
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

	// Presets: one colour family each (a gradient between neighbouring tones, the glow in the same family and kept soft),
	// one shading idea each (glow OR outline OR 3D depth - never all stacked), calm motion unless the preset is about it.
	static {
		preset("Concert Sky", s -> {
			sky(s, 110, 22, 1.0);
			s.font = "Archivo"; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFFCFE0FF;
			s.glow = true; s.glowColor = 0xFF6E9BFF; s.glowSize = 1.0; s.glowStrength = 0.7;
			s.depth3d = "Light"; s.depthColor = 0xFF2B3E73;
			s.enter = "Rise"; s.exit = "Fade"; s.perLetter = true; s.idle = "Float"; s.showNext = true;
			s.beatSync = true;
		});
		preset("Velo Red", s -> {
			sky(s, 105, 20, 1.05);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 0.8; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFFFFDDE0;
			s.glow = true; s.glowColor = 0xFFE0262E; s.glowSize = 1.0; s.glowStrength = 0.6;
			s.depth3d = "Light"; s.depthColor = 0xFF7A0E16;
			s.enter = "Rise"; s.exit = "Fade"; s.perLetter = true; s.idle = "None"; s.showNext = true;
			s.beatSync = true;
		});
		preset("Midnight", s -> {
			sky(s, 140, 34, 0.8); s.spread = "Everywhere"; s.followView = false;
			s.font = "Sora"; s.textCase = "lowercase"; s.spacing = 1.2; s.colorMode = "Solid"; s.color = 0xFFF3F5FF;
			s.glow = true; s.glowColor = 0xFFC9D4FF; s.glowSize = 1.3; s.glowStrength = 0.45;
			s.enter = "Fly In"; s.exit = "Scatter"; s.perLetter = true; s.idle = "Breathe"; s.showNext = false; s.speed = 0.8;
			s.depth3d = "Light"; s.depthColor = 0xFF262C45;
		});
		preset("Aurora", s -> {
			sky(s, 160, 30, 1.2);
			s.font = "Sora"; s.colorMode = "Flowing Gradient"; s.color = 0xFF8DF5D2; s.color2 = 0xFF8FA2FF;
			s.glow = true; s.glowColor = 0xFF4FD1B5; s.glowSize = 1.2; s.glowStrength = 0.6;
			s.enter = "Wave"; s.exit = "Fly Away"; s.perLetter = true; s.idle = "Wave"; s.showNext = true;
			s.depth3d = "Light"; s.depthColor = 0xFF0F4A45;
		});
		preset("Golden Hour", s -> {
			sky(s, 100, 14, 1.1);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 1.0; s.colorMode = "Gradient"; s.color = 0xFFFFF1C9; s.color2 = 0xFFFFB36B;
			s.glow = true; s.glowColor = 0xFFFF9B4A; s.glowSize = 1.1; s.glowStrength = 0.55;
			s.enter = "Blur In"; s.exit = "Fade"; s.perLetter = false; s.idle = "None"; s.speed = 0.7; s.showNext = false;
			s.depth3d = "Light"; s.depthColor = 0xFF6B3410;
		});
		preset("Pastel Dream", s -> {
			sky(s, 95, 18, 1.0);
			s.font = "Poppins"; s.textCase = "lowercase"; s.spacing = 0.6; s.colorMode = "Flowing Gradient"; s.color = 0xFFFFD1E8; s.color2 = 0xFFC9D8FF;
			s.glow = true; s.glowColor = 0xFFFFB8DA; s.glowSize = 1.2; s.glowStrength = 0.5;
			s.enter = "Bounce"; s.exit = "Fade"; s.perLetter = true; s.idle = "Float"; s.speed = 0.9; s.showNext = true;
			s.depth3d = "Light"; s.depthColor = 0xFF6B4A7A;
		});
		preset("Frost", s -> {
			sky(s, 90, 18, 1.0); s.spread = "Around You"; s.followView = false;
			s.font = "Sora"; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFFBDE9FF;
			s.glow = true; s.glowColor = 0xFF9AD9FF; s.glowSize = 1.0; s.glowStrength = 0.55;
			s.enter = "Drop"; s.exit = "Shrink"; s.perLetter = true; s.idle = "Sway"; s.speed = 0.8; s.showNext = true;
			s.depth3d = "Light"; s.depthColor = 0xFF2C4F6E;
		});
		preset("Synthwave", s -> {
			sky(s, 120, 20, 1.2); s.spread = "Around You";
			s.font = "Audiowide"; s.spacing = 1.4; s.colorMode = "Gradient"; s.color = 0xFFFF8AD8; s.color2 = 0xFF7DE2FF;
			s.glow = true; s.glowColor = 0xFFC77DFF; s.glowSize = 1.1; s.glowStrength = 0.6;
			s.depth3d = "Deep"; s.depthColor = 0xFF3B1F6E;
			s.enter = "Slide"; s.exit = "Slide"; s.perLetter = true; s.idle = "Sway"; s.tilt = -6; s.showNext = false;
			s.beatSync = true;
		});
		preset("Neon Night", s -> {
			front(s, 6, 0.6, 1.0, true);
			s.font = "Audiowide"; s.colorMode = "Gradient"; s.color = 0xFFFF5FD2; s.color2 = 0xFF5EE7FF;
			s.glow = true; s.glowColor = 0xFF9B5CFF; s.glowSize = 1.1; s.glowStrength = 0.8;
			s.enter = "Glitch"; s.exit = "Glitch"; s.perLetter = true; s.idle = "Pulse"; s.karaoke = true; s.highlight = 0xFFFFFFFF; s.showNext = false;
			s.beatSync = true; s.fxGlitch = true;
			s.depth3d = "Light"; s.depthColor = 0xFF2A1050;
		});
		preset("Chrome", s -> {
			front(s, 6, 0.5, 1.15, false);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 0.6; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFFA7B0C0;
			s.glow = false; s.outline = true; s.outlineColor = 0xFF1C2028; s.outlineSize = 0.8;
			s.depth3d = "Deep"; s.depthColor = 0xFF3A3F4A;
			s.enter = "Zoom"; s.exit = "Fade"; s.perLetter = false; s.idle = "None"; s.showNext = false;
		});
		preset("Ember", s -> {
			front(s, 7, 1.0, 1.1, false);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.colorMode = "Flowing Gradient"; s.color = 0xFFFFE7A0; s.color2 = 0xFFFF6A2E;
			s.glow = true; s.glowColor = 0xFFFF5A1F; s.glowSize = 1.1; s.glowStrength = 0.75;
			s.depth3d = "Light"; s.depthColor = 0xFF5A1608;
			s.enter = "Rise"; s.exit = "Fly Away"; s.perLetter = true; s.idle = "Breathe"; s.showNext = false;
			s.beatSync = true; s.beatStrength = 0.6;
		});
		preset("Hologram", s -> {
			front(s, 5, 0.9, 0.9, true); s.spread = "Around You"; s.followView = false;
			s.font = "JetBrains Mono"; s.textCase = "UPPERCASE"; s.spacing = 0.8; s.colorMode = "Solid"; s.color = 0xFF9FF5FF;
			s.glow = true; s.glowColor = 0xFF22C7FF; s.glowSize = 0.9; s.glowStrength = 0.7; s.opacity = 0.85;
			s.enter = "Flip"; s.exit = "Dissolve"; s.perLetter = true; s.idle = "None"; s.showNext = true;
			s.depth3d = "Light"; s.depthColor = 0xFF0A3440;
		});
		preset("Karaoke", s -> {
			front(s, 5, -0.4, 0.9, true);
			s.font = "Poppins"; s.colorMode = "Solid"; s.color = 0xFFB8B8C8; s.karaoke = true; s.highlight = 0xFFFFD84A;
			s.outline = true; s.outlineColor = 0xFF101014; s.glow = false;
			s.enter = "Pop"; s.exit = "Rise"; s.perLetter = false; s.idle = "None"; s.showNext = true;
		});
		preset("Comic", s -> {
			front(s, 6, 0.8, 1.2, false); s.spread = "Around You"; s.followView = false;
			s.font = "Poppins"; s.textCase = "UPPERCASE"; s.colorMode = "Gradient"; s.color = 0xFFFFE45C; s.color2 = 0xFFFF8A3D;
			s.glow = false; s.outline = true; s.outlineColor = 0xFF1A0E05; s.outlineSize = 1.6;
			s.depth3d = "Deep"; s.depthColor = 0xFF6B2A0C;
			s.enter = "Bounce"; s.exit = "Explode"; s.perLetter = true; s.idle = "Jelly"; s.chaos = 0.15; s.showNext = false;
		});
		preset("Rainbow Party", s -> {
			front(s, 7, 1.2, 1.3, true); s.spread = "Everywhere"; s.followView = false;
			s.font = "Poppins"; s.textCase = "UPPERCASE"; s.colorMode = "Rainbow";
			s.glow = false; s.outline = true; s.outlineColor = 0xFF101014; s.outlineSize = 1.2;
			s.depth3d = "Light"; s.depthColor = 0xFF1A1A22;
			s.enter = "Spin"; s.exit = "Explode"; s.perLetter = true; s.idle = "Jelly"; s.chaos = 0.15; s.showNext = false;
			s.beatSync = true; s.beatStrength = 1.4; s.fxShake = true; s.fxGlitch = true;
		});
		preset("Terminal", s -> {
			front(s, 5, 0.2, 0.8, true);
			s.font = "JetBrains Mono"; s.colorMode = "Solid"; s.color = 0xFF6CFF8E; s.textCase = "lowercase";
			s.glow = true; s.glowColor = 0xFF2BFF6A; s.glowSize = 0.7; s.glowStrength = 0.6;
			s.enter = "Typewriter"; s.exit = "Glitch"; s.perLetter = true; s.idle = "None"; s.showNext = false; s.showPrevious = true;
			s.depth3d = "Light"; s.depthColor = 0xFF06301A;
		});
		preset("Ghost", s -> {
			front(s, 8, 0.4, 1.1, false); s.spread = "Around You"; s.followView = false;
			s.font = "Inter"; s.textCase = "lowercase"; s.colorMode = "Solid"; s.color = 0xFFEEF1FF; s.opacity = 0.6;
			s.glow = true; s.glowColor = 0xFFFFFFFF; s.glowSize = 1.6; s.glowStrength = 0.45;
			s.enter = "Blur In"; s.exit = "Dissolve"; s.perLetter = true; s.idle = "Breathe"; s.speed = 0.6; s.showNext = false;
		});
		preset("Retro", s -> {
			s.depth3d = "Deep";
			front(s, 7, 1.2, 1.2, false);
			s.font = "Minecraft"; s.colorMode = "Gradient"; s.color = 0xFFFFE066; s.color2 = 0xFFFF6B6B;
			s.outline = true; s.outlineColor = 0xFF2A1430; s.glow = false; s.shadow = true;
			s.enter = "Drop"; s.exit = "Fall"; s.perLetter = true; s.idle = "Float"; s.showNext = false;
		});
		preset("Word Pop", s -> {
			s.beatSync = true;
			front(s, 6, 0.3, 1.1, true);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFF6EE7FF;
			s.glow = true; s.glowColor = 0xFF2B6BFF; s.glowSize = 0.9; s.glowStrength = 0.8; s.karaoke = true; s.highlight = 0xFFFFD84A;
			s.enter = "Word by Word"; s.exit = "Zoom"; s.idle = "Pulse"; s.showNext = false;
		});
		preset("Ocean", s -> {
			sky(s, 115, 24, 1.05);
			s.font = "Sora"; s.colorMode = "Flowing Gradient"; s.color = 0xFFE6FBFF; s.color2 = 0xFF7FD8FF;
			s.glow = true; s.glowColor = 0xFF3BB8FF; s.glowSize = 1.0; s.glowStrength = 0.5;
			s.depth3d = "Light"; s.depthColor = 0xFF0B3B5C;
			s.enter = "Wave"; s.exit = "Fade"; s.perLetter = true; s.idle = "Wave"; s.speed = 0.85; s.showNext = true;
		});
		preset("Sakura", s -> {
			sky(s, 100, 20, 1.0); s.spread = "Around You";
			s.font = "Poppins"; s.textCase = "lowercase"; s.spacing = 0.6; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFFFFC1D9;
			s.glow = true; s.glowColor = 0xFFFF8FB8; s.glowSize = 1.0; s.glowStrength = 0.5;
			s.depth3d = "Light"; s.depthColor = 0xFF7A3550;
			s.enter = "Drop"; s.exit = "Fly Away"; s.perLetter = true; s.idle = "Float"; s.speed = 0.8; s.showNext = true;
		});
		preset("Gold", s -> {
			front(s, 6, 0.6, 1.15, false);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 0.9; s.colorMode = "Gradient"; s.color = 0xFFFFF6D0; s.color2 = 0xFFE8B547;
			s.glow = false; s.outline = true; s.outlineColor = 0xFF2A1C04; s.outlineSize = 0.6;
			s.depth3d = "Deep"; s.depthColor = 0xFF5A3D0A;
			s.enter = "Zoom"; s.exit = "Fade"; s.perLetter = false; s.idle = "None"; s.showNext = false;
			s.beatSync = true; s.fxSplit = false;
		});
		preset("Bubblegum", s -> {
			front(s, 6, 0.7, 1.15, false); s.spread = "Around You"; s.followView = false;
			s.font = "Poppins"; s.colorMode = "Gradient"; s.color = 0xFFFF9AD5; s.color2 = 0xFFFFD36E;
			s.glow = false; s.outline = true; s.outlineColor = 0xFF4A1838; s.outlineSize = 1.0;
			s.depth3d = "Light"; s.depthColor = 0xFF7A2A5A;
			s.enter = "Bounce"; s.exit = "Shrink"; s.perLetter = true; s.idle = "Jelly"; s.chaos = 0.1; s.showNext = false;
			s.beatSync = true; s.beatTrigger = "Every beat"; s.beatStrength = 0.5; s.fxSplit = false; s.fxFlash = true;
		});
		preset("Cinematic", s -> {
			screen(s, "Bottom", 1.2);
			s.font = "Archivo"; s.spacing = 0.6; s.colorMode = "Solid"; s.color = 0xFFF5F5F7; s.shadow = true; s.shadowColor = 0xC0000000; s.glow = false;
			s.enter = "Fade"; s.exit = "Fade"; s.perLetter = false; s.idle = "None"; s.showNext = false;
		});
		preset("Minimal", s -> {
			screen(s, "Bottom", 0.9);
			s.font = "Inter"; s.colorMode = "Solid"; s.color = 0xFFFFFFFF; s.shadow = true; s.glow = false;
			s.enter = "Fade"; s.exit = "Fade"; s.perLetter = false; s.idle = "None"; s.karaoke = true; s.highlight = 0xFF9DB8FF; s.showNext = true;
		});
		preset("Mono", s -> {
			screen(s, "Center", 1.3);
			s.font = "Space Grotesk"; s.textCase = "lowercase"; s.spacing = 0.5; s.colorMode = "Solid"; s.color = 0xFFF2F2F5;
			s.glow = false; s.shadow = true; s.shadowColor = 0xA0000000;
			s.enter = "Typewriter"; s.exit = "Fade"; s.perLetter = true; s.idle = "None"; s.showNext = true; s.lineGap = 2.0;
		});
		preset("Spotlight", s -> {
			screen(s, "Center", 1.6);
			s.font = "Archivo"; s.textCase = "UPPERCASE"; s.spacing = 0.8; s.colorMode = "Gradient"; s.color = 0xFFFFFFFF; s.color2 = 0xFFFF8A94;
			s.glow = true; s.glowColor = 0xFFFF4D5E; s.glowSize = 1.0; s.glowStrength = 0.6;
			s.enter = "Word by Word"; s.exit = "Fade"; s.idle = "Pulse"; s.showNext = false;
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
