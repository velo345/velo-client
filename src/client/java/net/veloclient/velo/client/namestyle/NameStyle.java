package net.veloclient.velo.client.namestyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Velo+ member's name style: an animated effect over 1-6 colours, how fast it runs, a few flags, an
 * optional icon in front and where it shows. Travels to other Velo players in the heartbeat as
 * {@code 1|effect|speed|flags|colors|icon|where} (see the server's {@code SessionRegistry#normalizeNameStyle}); the spread
 * rides along as a digit flag ({@code 1}-{@code 9}, 5 = normal), the font as a capital letter ({@code B} = {@link #FONTS}[1]...).
 *
 * <p>Plain Java (no Minecraft classes) so the launcher can show the same thing.
 */
public record NameStyle(Effect effect, int speed, int spread, int font, boolean bold, boolean italic, boolean underline, boolean glow,
		boolean reverse, int[] colors, int icon, boolean chat, boolean tab, boolean nameTag) {

	/** Every effect, with the name the menu shows and a one-line description. */
	public enum Effect {
		SOLID("solid", "Solid", "One colour, no movement"),
		GRADIENT("gradient", "Gradient", "Your colours across the name"),
		WAVE("wave", "Wave", "A gradient that flows along"),
		RAINBOW("rainbow", "Rainbow", "Every colour, always moving"),
		PULSE("pulse", "Pulse", "Breathes between your colours"),
		SHIMMER("shimmer", "Shimmer", "A bright shine sweeps across"),
		NEON("neon", "Neon", "Glows and flickers like a sign"),
		SPARKLE("sparkle", "Sparkle", "Letters twinkle at random"),
		FIRE("fire", "Fire", "Flickers like flames"),
		AURORA("aurora", "Aurora", "Slow northern lights"),
		GLITCH("glitch", "Glitch", "Breaks up now and then"),
		CHROMA("chroma", "Chroma", "Fast colour cycling per letter"),
		STRIPES("stripes", "Stripes", "Candy stripes sliding by"),
		SCANNER("scanner", "Scanner", "A light runs back and forth"),
		HEARTBEAT("heartbeat", "Heartbeat", "Double pulse, like a heart"),
		STROBE("strobe", "Strobe", "Hard flashes between colours"),
		LAVA("lava", "Lava", "Slow, thick molten glow"),
		ELECTRIC("electric", "Electric", "Lightning cracks through"),
		REVEAL("reveal", "Reveal", "Letters light up one by one"),
		MATRIX("matrix", "Matrix", "Falling code over your name"),
		DUO("duo", "Duo", "Letters swap between colours");

		public final String id;
		public final String label;
		public final String blurb;

		Effect(String id, String label, String blurb) {
			this.id = id;
			this.label = label;
			this.blurb = blurb;
		}

		public boolean animated() {
			return this != SOLID && this != GRADIENT;
		}

		/** Whether the colours matter (rainbow and chroma make their own). */
		public boolean usesColors() {
			return this != RAINBOW && this != CHROMA;
		}

		/** Whether the Spread setting (how wide the pattern is) changes anything. */
		public boolean usesSpread() {
			return this == GRADIENT || this == WAVE || this == RAINBOW || this == CHROMA || this == AURORA || this == STRIPES || this == SHIMMER
					|| this == SCANNER || this == PULSE;
		}

		public static Effect byId(String id) {
			for (Effect effect : values()) {
				if (effect.id.equals(id)) {
					return effect;
				}
			}
			return null;
		}
	}

	/** Icons that can go in front of the name (0 = none). Plain Unicode, so every viewer's font has them. */
	public static final String[] ICONS = {"", "✦", "★", "♛", "⚡", "❤", "☠", "⚔", "✿", "♦",
			"☾", "❄", "♪", "☀", "✪", "❁", "♨", "☘", "⚜", "☢", "☯"};

	/** Fonts a name can be drawn in: {label, font id} - all bundled with every Velo Client, so every viewer has them. */
	public static final String[][] FONTS = {{"Minecraft", "velo-client:mc"}, {"Sora", "velo-client:hud_sora"},
			{"Space Grotesk", "velo-client:hud_space_grotesk"}, {"Poppins", "velo-client:hud_poppins"}, {"IBM Plex", "velo-client:hud_plex"},
			{"JetBrains Mono", "velo-client:hud_mono"}, {"Audiowide", "velo-client:hud_audiowide"}, {"Inter", "velo-client:ui"},
			{"Smooth", "minecraft:uniform"}};

	public static final int MAX_COLORS = 6;
	public static final int MIN_SPEED = 1;
	public static final int MAX_SPEED = 40;

	public NameStyle {
		speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
		spread = Math.max(1, Math.min(9, spread));
		font = font < 0 || font >= FONTS.length ? 0 : font;
		if (colors == null || colors.length == 0) {
			colors = new int[] {0xFFFFFF};
		}
		if (colors.length > MAX_COLORS) {
			colors = java.util.Arrays.copyOf(colors, MAX_COLORS);
		}
		icon = icon < 0 || icon >= ICONS.length ? 0 : icon;
	}

	public static NameStyle defaults() {
		return PRESETS.get(0).style();
	}

	public String iconText() {
		return ICONS[icon];
	}

	public NameStyle withEffect(Effect value) {
		return new NameStyle(value, speed, spread, font, bold, italic, underline, glow, reverse, colors, icon, chat, tab, nameTag);
	}

	public NameStyle withSpeed(int value) {
		return new NameStyle(effect, value, spread, font, bold, italic, underline, glow, reverse, colors, icon, chat, tab, nameTag);
	}

	public NameStyle withSpread(int value) {
		return new NameStyle(effect, speed, value, font, bold, italic, underline, glow, reverse, colors, icon, chat, tab, nameTag);
	}

	public NameStyle withFont(int value) {
		return new NameStyle(effect, speed, spread, value, bold, italic, underline, glow, reverse, colors, icon, chat, tab, nameTag);
	}

	public NameStyle withFlags(boolean b, boolean i, boolean u, boolean g, boolean r) {
		return new NameStyle(effect, speed, spread, font, b, i, u, g, r, colors, icon, chat, tab, nameTag);
	}

	public NameStyle withColors(int[] value) {
		return new NameStyle(effect, speed, spread, font, bold, italic, underline, glow, reverse, value, icon, chat, tab, nameTag);
	}

	public NameStyle withIcon(int value) {
		return new NameStyle(effect, speed, spread, font, bold, italic, underline, glow, reverse, colors, value, chat, tab, nameTag);
	}

	public NameStyle withPlaces(boolean inChat, boolean inTab, boolean inNameTag) {
		return new NameStyle(effect, speed, spread, font, bold, italic, underline, glow, reverse, colors, icon, inChat, inTab, inNameTag);
	}

	/** The wire form ({@code 1|wave|12|bg|ff4d4d,ffb84d|3|ctn}). */
	public String encode() {
		StringBuilder flags = new StringBuilder();
		if (bold) {
			flags.append('b');
		}
		if (italic) {
			flags.append('i');
		}
		if (underline) {
			flags.append('u');
		}
		if (glow) {
			flags.append('g');
		}
		if (reverse) {
			flags.append('r');
		}
		if (spread != 5) {
			flags.append(spread);
		}
		if (font > 0) {
			flags.append((char) ('A' + font));
		}
		List<String> hex = new ArrayList<>();
		for (int color : colors) {
			hex.add(String.format(Locale.ROOT, "%06x", color & 0xFFFFFF));
		}
		String where = (chat ? "c" : "") + (tab ? "t" : "") + (nameTag ? "n" : "");
		return "1|" + effect.id + "|" + speed + "|" + flags + "|" + String.join(",", hex) + "|" + icon + "|" + where;
	}

	/** Parses the wire form; null for anything malformed (a viewer never trusts what the network sends). */
	public static NameStyle decode(String text) {
		if (text == null || text.length() > 96) {
			return null;
		}
		String[] parts = text.split("\\|", -1);
		if (parts.length != 7 || !parts[0].equals("1")) {
			return null;
		}
		try {
			Effect effect = Effect.byId(parts[1]);
			if (effect == null) {
				return null;
			}
			int speed = Integer.parseInt(parts[2]);
			String flags = parts[3];
			String[] hex = parts[4].split(",");
			if (hex.length < 1 || hex.length > MAX_COLORS) {
				return null;
			}
			int[] colors = new int[hex.length];
			for (int i = 0; i < hex.length; i++) {
				if (!hex[i].matches("[0-9a-fA-F]{6}")) {
					return null;
				}
				colors[i] = Integer.parseInt(hex[i], 16);
			}
			int icon = Integer.parseInt(parts[5]);
			String where = parts[6];
			int spread = 5;
			int font = 0;
			for (char ch : flags.toCharArray()) {
				if (ch >= '1' && ch <= '9') {
					spread = ch - '0';
				} else if (ch >= 'A' && ch <= 'Z') {
					font = ch - 'A';
				}
			}
			return new NameStyle(effect, speed, spread, font, flags.indexOf('b') >= 0, flags.indexOf('i') >= 0, flags.indexOf('u') >= 0,
					flags.indexOf('g') >= 0, flags.indexOf('r') >= 0, colors, icon, where.indexOf('c') >= 0, where.indexOf('t') >= 0, where.indexOf('n') >= 0);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof NameStyle style && style.encode().equals(encode());
	}

	@Override
	public int hashCode() {
		return encode().hashCode();
	}

	/** A ready-made look with a name, for the preset gallery. */
	public record Preset(String name, NameStyle style) {
	}

	private static NameStyle make(Effect effect, int speed, String flags, int icon, int... colors) {
		return new NameStyle(effect, speed, 5, 0, flags.contains("b"), flags.contains("i"), flags.contains("u"), flags.contains("g"), flags.contains("r"),
				colors, icon, true, true, true);
	}

	public static final List<Preset> PRESETS = List.of(
			new Preset("Velo", make(Effect.WAVE, 10, "bg", 1, 0xFF2D3C, 0xFF7A7E, 0xFFD1D3)),
			new Preset("Inferno", make(Effect.FIRE, 14, "bg", 15, 0xFF2A00, 0xFF8A00, 0xFFE14D)),
			new Preset("Aurora", make(Effect.AURORA, 6, "g", 10, 0x3DDC97, 0x2DD4BF, 0x5AA9FF, 0xB46CFF)),
			new Preset("Rainbow", make(Effect.RAINBOW, 10, "b", 0)),
			new Preset("Neon Pink", make(Effect.NEON, 10, "bg", 14, 0xFF4FD8, 0xFF9DEB)),
			new Preset("Gold Rush", make(Effect.SHIMMER, 12, "b", 3, 0xF7C04A, 0xFFE08A, 0xC88A14)),
			new Preset("Ocean", make(Effect.WAVE, 7, "", 0, 0x0B6CFF, 0x2DD4BF, 0x9BF6FF)),
			new Preset("Glitch", make(Effect.GLITCH, 16, "b", 19, 0x00FFD0, 0xFF2D7A, 0xFFFFFF)),
			new Preset("Sakura", make(Effect.SPARKLE, 8, "i", 8, 0xFFB7C5, 0xFF7AA2, 0xFFFFFF)),
			new Preset("Toxic", make(Effect.PULSE, 9, "bg", 19, 0x7CFF00, 0x00FF7F)),
			new Preset("Frost", make(Effect.SHIMMER, 8, "", 11, 0x9BE7FF, 0xE6FAFF, 0x5AA9FF)),
			new Preset("Blood Moon", make(Effect.PULSE, 6, "bg", 10, 0x8B0000, 0xFF2D3C)),
			new Preset("Vaporwave", make(Effect.WAVE, 9, "i", 12, 0xFF71CE, 0x01CDFE, 0xB967FF, 0xFFFB96)),
			new Preset("Galaxy", make(Effect.SPARKLE, 10, "g", 2, 0x6A00FF, 0xB46CFF, 0x00B4FF)),
			new Preset("Sunset", make(Effect.GRADIENT, 10, "b", 13, 0xFF5E62, 0xFF9966, 0xFFD86F)),
			new Preset("Chroma", make(Effect.CHROMA, 18, "b", 4)),
			new Preset("Mint", make(Effect.SOLID, 10, "", 18, 0x3DDC97)),
			new Preset("Royal", make(Effect.GRADIENT, 10, "bi", 3, 0x7B2FF7, 0xF107A3)),
			new Preset("Candy", make(Effect.STRIPES, 10, "b", 8, 0xFF4FA3, 0xFFFFFF)),
			new Preset("Cyber Scan", make(Effect.SCANNER, 12, "bg", 19, 0x0A3D62, 0x00E5FF)),
			new Preset("Lovesick", make(Effect.HEARTBEAT, 10, "bg", 5, 0xB0003A, 0xFF4D6D)),
			new Preset("Rave", make(Effect.STROBE, 12, "b", 4, 0xFF00E6, 0x00FFE1, 0xFFF200)),
			new Preset("Magma", make(Effect.LAVA, 6, "bg", 15, 0x5A0A00, 0xE23B00, 0xFFB100)),
			new Preset("Storm", make(Effect.ELECTRIC, 12, "bg", 4, 0x3B4CFF, 0x9FB4FF)),
			new Preset("Spotlight", make(Effect.REVEAL, 10, "b", 2, 0x55555F, 0xFFFFFF)),
			new Preset("Matrix", make(Effect.MATRIX, 12, "g", 0, 0x00FF41, 0x008F11)),
			new Preset("Ice & Fire", make(Effect.DUO, 8, "b", 0, 0x5AA9FF, 0xFF5A3C)));
}
