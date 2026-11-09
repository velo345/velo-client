package net.veloclient.library;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minecraft banners (the block/item, not server banners): base colour + up to 16 pattern layers, drawn from the game's
 * own pattern textures, and the commands to get one in game (1.20.5+ components and 1.13-1.20.4 NBT).
 */
final class McBanner {

	/** Dye name, banner tint (DyeColor's texture colour), NBT colour id. */
	static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple",
			"blue", "brown", "green", "red", "black"};
	static final int[] TINT = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52, 0x9D9D97, 0x169C9C, 0x8932B8,
			0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};

	/** Pattern id -> legacy (pre-1.20.5) code; null = newer than the legacy format. */
	static final Map<String, String> PATTERNS = new LinkedHashMap<>();
	/** Patterns that need a banner pattern item in the loom. */
	static final Map<String, String> NEEDS_ITEM = Map.of("creeper", "creeper_banner_pattern", "skull", "skull_banner_pattern",
			"flower", "flower_banner_pattern", "mojang", "mojang_banner_pattern", "globe", "globe_banner_pattern", "piglin", "piglin_banner_pattern",
			"flow", "flow_banner_pattern", "guster", "guster_banner_pattern", "bricks", "field_masoned_banner_pattern", "curly_border", "bordure_indented_banner_pattern");

	static {
		String[][] p = {{"stripe_bottom", "bs"}, {"stripe_top", "ts"}, {"stripe_left", "ls"}, {"stripe_right", "rs"}, {"stripe_center", "cs"},
				{"stripe_middle", "ms"}, {"stripe_downright", "drs"}, {"stripe_downleft", "dls"}, {"small_stripes", "ss"}, {"cross", "cr"},
				{"straight_cross", "sc"}, {"triangle_bottom", "bt"}, {"triangle_top", "tt"}, {"triangles_bottom", "bts"}, {"triangles_top", "tts"},
				{"diagonal_left", "ld"}, {"diagonal_up_right", "rd"}, {"diagonal_up_left", "lud"}, {"diagonal_right", "rud"}, {"circle", "mc"},
				{"rhombus", "mr"}, {"half_vertical", "vh"}, {"half_horizontal", "hh"}, {"half_vertical_right", "vhr"}, {"half_horizontal_bottom", "hhb"},
				{"square_bottom_left", "bl"}, {"square_bottom_right", "br"}, {"square_top_left", "tl"}, {"square_top_right", "tr"}, {"border", "bo"},
				{"curly_border", "cbo"}, {"gradient", "gra"}, {"gradient_up", "gru"}, {"bricks", "bri"}, {"creeper", "cre"}, {"skull", "sku"},
				{"flower", "flo"}, {"mojang", "moj"}, {"globe", "glb"}, {"piglin", "pig"}, {"flow", null}, {"guster", null}};
		for (String[] x : p) {
			PATTERNS.put(x[0], x[1]);
		}
	}

	record Layer(String pattern, int dye) {
	}

	static int dye(String name) {
		for (int i = 0; i < DYES.length; i++) {
			if (DYES[i].equals(name)) {
				return i;
			}
		}
		throw new Http.Fail(400, "Unknown colour " + name + " - use the 16 dye names");
	}

	/** "stripe_top.red,cross.black" -> layers (max 16). */
	static List<Layer> parse(String spec) {
		List<Layer> out = new ArrayList<>();
		if (spec == null || spec.isBlank()) {
			return out;
		}
		for (String part : spec.split(",")) {
			String[] pc = part.trim().split("[.:]");
			if (pc.length != 2 || !PATTERNS.containsKey(pc[0])) {
				throw new Http.Fail(400, "Layers look like pattern.colour, e.g. stripe_top.red (" + part + ")");
			}
			out.add(new Layer(pc[0], dye(pc[1])));
			if (out.size() > 16) {
				throw new Http.Fail(400, "At most 16 layers");
			}
		}
		return out;
	}

	/** The banner's front (20x40 texels) scaled to {@code scale} pixels per texel. */
	static BufferedImage render(Assets assets, int base, List<Layer> layers, int scale) {
		int w = 20, h = 40;
		int[] px = new int[w * h];
		BufferedImage cloth = assets.texture("entity/banner/banner_base");
		paint(px, assets.texture("entity/banner/base"), TINT[base], true);
		for (Layer l : layers) {
			paint(px, assets.texture("entity/banner/" + l.pattern()), TINT[l.dye()], false);
		}
		// the cloth's shading (banner_base) over everything, like the game's multiply
		if (cloth != null) {
			int s = cloth.getWidth() / 64;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int c = cloth.getRGB((1 + x) * s, (1 + y) * s);
					int shade = (c >> 16) & 255;
					int p = px[y * w + x];
					px[y * w + x] = 0xff000000 | mul((p >> 16) & 255, shade) << 16 | mul((p >> 8) & 255, shade) << 8 | mul(p & 255, shade);
				}
			}
		}
		BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		small.setRGB(0, 0, w, h, px, 0, w);
		BufferedImage out = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(small, 0, 0, w * scale, h * scale, null);
		g.dispose();
		return out;
	}

	private static int mul(int a, int b) {
		return a * b / 255;
	}

	/** Pattern textures are white masks with alpha: tint and blend over what's there. */
	private static void paint(int[] px, BufferedImage mask, int tint, boolean opaque) {
		if (mask == null) {
			return;
		}
		int s = mask.getWidth() / 64;
		int tr = (tint >> 16) & 255, tg = (tint >> 8) & 255, tb = tint & 255;
		for (int y = 0; y < 40; y++) {
			for (int x = 0; x < 20; x++) {
				int c = mask.getRGB((1 + x) * s, (1 + y) * s);
				double a = opaque ? 1 : ((c >>> 24) & 255) / 255.0;
				if (a <= 0) {
					continue;
				}
				int lum = (c >> 16) & 255;
				int r = tr * lum / 255, g = tg * lum / 255, b = tb * lum / 255;
				int p = px[y * 20 + x];
				int pr = (p >> 16) & 255, pg = (p >> 8) & 255, pb = p & 255;
				px[y * 20 + x] = 0xff000000 | (int) (pr + (r - pr) * a) << 16 | (int) (pg + (g - pg) * a) << 8 | (int) (pb + (b - pb) * a);
			}
		}
	}

	/** The pattern's mask cropped to the banner front (white with alpha), for the website's live editor. */
	static BufferedImage mask(Assets assets, String pattern) {
		BufferedImage m = assets.texture("entity/banner/" + pattern);
		if (m == null) {
			return null;
		}
		int s = m.getWidth() / 64;
		BufferedImage out = new BufferedImage(20 * s, 40 * s, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(m, 0, 0, 20 * s, 40 * s, s, s, 21 * s, 41 * s, null);
		g.dispose();
		return out;
	}

	static Map<String, Object> commands(int base, List<Layer> layers) {
		StringBuilder modern = new StringBuilder("/give @p minecraft:" + DYES[base] + "_banner[banner_patterns=[");
		for (int i = 0; i < layers.size(); i++) {
			Layer l = layers.get(i);
			modern.append(i > 0 ? "," : "").append("{pattern:\"").append(l.pattern()).append("\",color:\"").append(DYES[l.dye()]).append("\"}");
		}
		modern.append("]] 1");
		boolean legacyOk = layers.stream().allMatch(l -> PATTERNS.get(l.pattern()) != null);
		String legacy = null;
		if (legacyOk) {
			StringBuilder b = new StringBuilder("/give @p minecraft:" + DYES[base] + "_banner{BlockEntityTag:{Patterns:[");
			for (int i = 0; i < layers.size(); i++) {
				Layer l = layers.get(i);
				b.append(i > 0 ? "," : "").append("{Pattern:\"").append(PATTERNS.get(l.pattern())).append("\",Color:").append(l.dye()).append("}");
			}
			legacy = b.append("]}} 1").toString();
		}
		List<String> items = new ArrayList<>();
		for (Layer l : layers) {
			if (NEEDS_ITEM.containsKey(l.pattern()) && !items.contains(NEEDS_ITEM.get(l.pattern()))) {
				items.add(NEEDS_ITEM.get(l.pattern()));
			}
		}
		return Http.map("give", modern.toString(), "giveLegacy", legacy, "survivalLayers", layers.size() <= 6, "patternItems", items);
	}

	static List<Map<String, Object>> patternList() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (var e : PATTERNS.entrySet()) {
			out.add(Http.map("id", e.getKey(), "legacy", e.getValue(), "item", NEEDS_ITEM.get(e.getKey()), "mask", "/v2/mcbanner/pattern/" + e.getKey() + ".png"));
		}
		return out;
	}
}
