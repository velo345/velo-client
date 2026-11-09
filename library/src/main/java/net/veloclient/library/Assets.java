package net.veloclient.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minecraft's own textures for item icons, the default skins and the bitmap font - taken from Mojang's client jar of
 * the newest release (downloaded once from Mojang like every launcher does, extracted into {@code <data>/assets/}).
 * Item icons are the item's model layers, or an isometric cube for block items.
 */
final class Assets {

	private final Path root;
	private volatile Path dir;
	private volatile String version;
	private volatile Map<String, String> names = Map.of();
	private volatile Map<Integer, String> legacyIds = Map.of();
	private final Cache.Memo<BufferedImage> images = new Cache.Memo<>(4000);

	Assets(Path dataDir) throws IOException {
		root = Files.createDirectories(dataDir.resolve("assets"));
	}

	boolean ready() {
		return dir != null;
	}

	String version() {
		return version;
	}

	/** Finds or downloads the newest release's assets; safe to call again (does nothing when current). */
	void update() {
		try {
			JsonObject manifest = JsonParser.parseString(Net.get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", 4 << 20).text()).getAsJsonObject();
			String latest = manifest.getAsJsonObject("latest").get("release").getAsString();
			Path target = root.resolve(latest);
			if (!Files.exists(target.resolve(".done3"))) {
				String url = null;
				for (JsonElement v : manifest.getAsJsonArray("versions")) {
					if (v.getAsJsonObject().get("id").getAsString().equals(latest)) {
						url = v.getAsJsonObject().get("url").getAsString();
					}
				}
				JsonObject meta = JsonParser.parseString(Net.get(url, 4 << 20).text()).getAsJsonObject();
				String jar = meta.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
				extract(jar, target);
				Files.writeString(target.resolve(".done3"), latest);
			}
			use(target, latest);
		} catch (Exception e) {
			System.out.println("[library] assets update failed: " + e);
			if (dir == null) {
				try (var list = Files.list(root)) {
					list.filter(p -> Files.exists(p.resolve(".done3"))).sorted().reduce((a, b) -> b).ifPresent(p -> use(p, p.getFileName().toString()));
				} catch (IOException ignored) {
					// stays unavailable
				}
			}
		}
		loadLegacyIds();
	}

	private void use(Path target, String v) {
		dir = target;
		version = v;
		try {
			JsonObject lang = JsonParser.parseString(Files.readString(target.resolve("lang/en_us.json"))).getAsJsonObject();
			Map<String, String> n = new TreeMap<>();
			for (var e : lang.entrySet()) {
				String k = e.getKey();
				if ((k.startsWith("item.minecraft.") || k.startsWith("block.minecraft.")) && k.indexOf('.', k.indexOf("minecraft.") + 10) < 0) {
					String id = k.substring(k.indexOf("minecraft.") + 10);
					if (Files.exists(target.resolve("items/" + id + ".json")) || Files.exists(target.resolve("models/item/" + id + ".json"))) {
						n.putIfAbsent(id, e.getValue().getAsString());
					}
				}
			}
			names = n;
		} catch (IOException e) {
			System.out.println("[library] no item names: " + e.getMessage());
		}
	}

	/** Only what the Library needs: item/block textures and models, font, default skins, the language file. */
	private static void extract(String jarUrl, Path target) throws IOException, InterruptedException {
		Files.createDirectories(target);
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
		HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(jarUrl)).header("User-Agent", Net.UA).timeout(Duration.ofMinutes(5)).build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (r.statusCode() != 200) {
			throw new IOException("client jar: HTTP " + r.statusCode());
		}
		long total = 0;
		try (ZipInputStream zip = new ZipInputStream(r.body())) {
			ZipEntry e;
			while ((e = zip.getNextEntry()) != null) {
				String n = e.getName();
				if (!n.startsWith("assets/minecraft/") || e.isDirectory()) {
					continue;
				}
				String rel = n.substring("assets/minecraft/".length());
				boolean want = rel.startsWith("items/") || rel.startsWith("textures/item/") || rel.startsWith("textures/block/") || rel.startsWith("models/item/")
						|| rel.startsWith("models/block/") || rel.equals("textures/font/ascii.png") || rel.equals("lang/en_us.json")
						|| rel.startsWith("textures/entity/player/") || rel.startsWith("textures/entity/banner/") || rel.startsWith("textures/gui/sprites/hud/");
				if (!want || rel.contains("..")) {
					continue;
				}
				Path out = target.resolve(rel).normalize();
				if (!out.startsWith(target)) {
					continue;
				}
				Files.createDirectories(out.getParent());
				byte[] b = zip.readNBytes(4 << 20);
				total += b.length;
				if (total > 400L << 20) {
					throw new IOException("client jar assets unexpectedly large");
				}
				Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
				Files.write(tmp, b);
				Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/** 1.8-era numeric ids (276 = diamond_sword) from PrismarineJS' minecraft-data. */
	private void loadLegacyIds() {
		Path cache = root.resolve("legacy-ids.json");
		try {
			if (!Files.exists(cache)) {
				Map<Integer, String> ids = new HashMap<>();
				for (String kind : new String[] {"blocks", "items"}) {
					Net.Response r = Net.get("https://raw.githubusercontent.com/PrismarineJS/minecraft-data/master/data/pc/1.8/" + kind + ".json", 2 << 20);
					if (r.status() == 200) {
						for (JsonElement e : JsonParser.parseString(r.text()).getAsJsonArray()) {
							JsonObject o = e.getAsJsonObject();
							ids.putIfAbsent(o.get("id").getAsInt(), o.get("name").getAsString());
						}
					}
				}
				if (!ids.isEmpty()) {
					Files.writeString(cache, Http.GSON.toJson(ids));
				}
			}
			Map<Integer, String> loaded = new HashMap<>();
			JsonParser.parseString(Files.readString(cache)).getAsJsonObject().entrySet()
					.forEach(e -> loaded.put(Integer.parseInt(e.getKey()), e.getValue().getAsString()));
			legacyIds = loaded;
		} catch (Exception e) {
			System.out.println("[library] legacy ids unavailable: " + e.getMessage());
		}
	}

	private static final Map<String, String> LEGACY_RENAMES = Map.ofEntries(
			Map.entry("grass", "grass_block"), Map.entry("wooden_sword", "wooden_sword"), Map.entry("gold_ingot", "gold_ingot"),
			Map.entry("golden_apple", "golden_apple"), Map.entry("log", "oak_log"), Map.entry("planks", "oak_planks"), Map.entry("wool", "white_wool"),
			Map.entry("stonebrick", "stone_bricks"), Map.entry("web", "cobweb"), Map.entry("reeds", "sugar_cane"), Map.entry("fireworks", "firework_rocket"),
			Map.entry("speckled_melon", "glistering_melon_slice"), Map.entry("melon", "melon_slice"), Map.entry("boat", "oak_boat"),
			Map.entry("wooden_door", "oak_door"), Map.entry("sign", "oak_sign"), Map.entry("bed", "red_bed"), Map.entry("skull", "skeleton_skull"),
			Map.entry("netherbrick", "nether_brick"), Map.entry("brick_block", "bricks"), Map.entry("snow_layer", "snow"), Map.entry("lit_pumpkin", "jack_o_lantern"),
			Map.entry("noteblock", "note_block"), Map.entry("golden_rail", "powered_rail"), Map.entry("hardened_clay", "terracotta"),
			Map.entry("stained_hardened_clay", "white_terracotta"), Map.entry("stained_glass", "white_stained_glass"), Map.entry("carpet", "white_carpet"),
			Map.entry("dye", "ink_sac"), Map.entry("fish", "cod"), Map.entry("cooked_fish", "cooked_cod"), Map.entry("record_13", "music_disc_13"),
			Map.entry("chest_minecart", "chest_minecart"), Map.entry("map", "map"), Map.entry("filled_map", "filled_map"), Map.entry("leaves", "oak_leaves"),
			Map.entry("sapling", "oak_sapling"), Map.entry("tallgrass", "short_grass"), Map.entry("yellow_flower", "dandelion"), Map.entry("red_flower", "poppy"),
			Map.entry("stone_slab", "smooth_stone_slab"), Map.entry("wooden_slab", "oak_slab"), Map.entry("fence", "oak_fence"), Map.entry("fence_gate", "oak_fence_gate"),
			Map.entry("trapdoor", "oak_trapdoor"), Map.entry("wooden_pressure_plate", "oak_pressure_plate"), Map.entry("wooden_button", "oak_button"),
			Map.entry("monster_egg", "infested_stone"), Map.entry("mob_spawner", "spawner"), Map.entry("quartz_ore", "nether_quartz_ore"),
			Map.entry("enchanting_table", "enchanting_table"), Map.entry("end_portal_frame", "end_portal_frame"), Map.entry("spawn_egg", "pig_spawn_egg"));

	/** "diamond_sword", "minecraft:diamond_sword", "276" or "276:0" -> modern id, or null. */
	String resolve(String query) {
		if (query == null) {
			return null;
		}
		String q = query.trim().toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "").replace(".png", "");
		if (q.matches("\\d{1,4}(:\\d{1,3})?")) {
			String legacy = legacyIds.get(Integer.parseInt(q.split(":")[0]));
			if (legacy == null) {
				return null;
			}
			q = LEGACY_RENAMES.getOrDefault(legacy, legacy);
		}
		if (!q.matches("[a-z0-9_]{1,64}")) {
			return null;
		}
		return Files.exists(dir.resolve("items/" + q + ".json")) || Files.exists(dir.resolve("models/item/" + q + ".json")) ? q : null;
	}

	List<Map<String, Object>> search(String query, int max) {
		String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
		List<Map<String, Object>> out = new ArrayList<>();
		for (var e : names.entrySet()) {
			if (q.isEmpty() || e.getKey().contains(q) || e.getValue().toLowerCase(java.util.Locale.ROOT).contains(q)) {
				out.add(Http.map("id", e.getKey(), "name", e.getValue()));
				if (out.size() >= max) {
					break;
				}
			}
		}
		return out;
	}

	String displayName(String id) {
		return names.get(id);
	}

	int count() {
		return names.size();
	}

	BufferedImage texture(String rel) {
		BufferedImage cached = images.get(rel);
		if (cached != null) {
			return cached;
		}
		try {
			Path p = dir.resolve("textures/" + rel + ".png").normalize();
			if (!p.startsWith(dir) || !Files.exists(p)) {
				return null;
			}
			BufferedImage img = ImageIO.read(p.toFile());
			if (img != null && img.getHeight() > img.getWidth()) {
				img = img.getSubimage(0, 0, img.getWidth(), img.getWidth()); // animated strip: first frame
			}
			images.put(rel, img, 3_600_000);
			return img;
		} catch (IOException e) {
			return null;
		}
	}

	private JsonObject model(String ref) {
		try {
			String r = ref.replace("minecraft:", "");
			Path p = dir.resolve("models/" + r + ".json").normalize();
			if (!p.startsWith(dir) || !Files.exists(p)) {
				return null;
			}
			return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			return null;
		}
	}

	/** The item's icon at {@code size} px, or null. */
	/** 1.21.4+: items/<id>.json names the model (possibly nested in conditions); older jars: models/item/<id>.json. */
	private String modelRef(String id) {
		try {
			Path def = dir.resolve("items/" + id + ".json");
			if (Files.exists(def)) {
				String found = firstModel(JsonParser.parseString(Files.readString(def)));
				if (found != null) {
					return found.replace("minecraft:", "");
				}
			}
		} catch (Exception ignored) {
			// fall back to the old location
		}
		return "item/" + id;
	}

	private static String firstModel(JsonElement e) {
		if (e.isJsonObject()) {
			JsonObject o = e.getAsJsonObject();
			if (o.has("model") && o.get("model").isJsonPrimitive() && "minecraft:model".equals(o.has("type") ? o.get("type").getAsString() : "minecraft:model")) {
				return o.get("model").getAsString();
			}
			for (String k : new String[] {"model", "fallback", "on_false", "cases", "entries", "models"}) {
				if (o.has(k)) {
					String r = firstModel(o.get(k));
					if (r != null) {
						return r;
					}
				}
			}
			for (var en : o.entrySet()) {
				if (!en.getValue().isJsonPrimitive()) {
					String r = firstModel(en.getValue());
					if (r != null) {
						return r;
					}
				}
			}
		} else if (e.isJsonArray()) {
			for (JsonElement x : e.getAsJsonArray()) {
				String r = firstModel(x);
				if (r != null) {
					return r;
				}
			}
		}
		return null;
	}

	BufferedImage icon(String id, int size) {
		String ref = modelRef(id);
		JsonObject m = model(ref);
		if (ref.startsWith("block/") && m != null && !m.has("textures") && !m.has("parent")) {
			m = null;
		}
		boolean startsBlock = ref.startsWith("block/");
		if (m == null) {
			return null;
		}
		Map<String, String> textures = new HashMap<>();
		String parent = null;
		boolean block = startsBlock;
		for (int depth = 0; m != null && depth < 8; depth++) {
			if (m.has("textures")) {
				for (var e : m.getAsJsonObject("textures").entrySet()) {
					textures.putIfAbsent(e.getKey(), texName(e.getValue()));
				}
			}
			if (!m.has("parent")) {
				break;
			}
			parent = m.get("parent").getAsString().replace("minecraft:", "");
			if (parent.startsWith("block/") && !parent.equals("block/block")) {
				block = true;
			}
			if (parent.startsWith("builtin/")) {
				break;
			}
			m = model(parent);
		}
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		if (textures.containsKey("layer0")) {
			for (int i = 0; i < 5; i++) {
				String t = textures.get("layer" + i);
				if (t == null) {
					break;
				}
				BufferedImage img = texture(ref(t, textures));
				if (img != null) {
					g.drawImage(img, 0, 0, size, size, null);
				}
			}
			g.dispose();
			return out;
		}
		if (block) {
			String top = first(textures, "top", "end", "all", "up", "texture", "particle", "side");
			String side = first(textures, "side", "all", "north", "front", "texture", "particle", "end");
			String front = first(textures, "front", "north", "side", "all", "texture", "particle");
			BufferedImage t = top == null ? null : texture(ref(top, textures));
			BufferedImage l = front == null ? null : texture(ref(front, textures));
			BufferedImage r = side == null ? null : texture(ref(side, textures));
			if (t != null || l != null || r != null) {
				isoCube(g, size, t, l != null ? l : r, r != null ? r : l, id);
				g.dispose();
				return out;
			}
		}
		g.dispose();
		BufferedImage flat = texture("block/" + id);
		if (flat != null) {
			BufferedImage o = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g2 = o.createGraphics();
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g2.drawImage(flat, 0, 0, size, size, null);
			g2.dispose();
			return o;
		}
		return null;
	}

	/** A model texture entry: "block/stone", or since 1.21.x {"sprite": "block/glass", "force_translucent": true}. */
	private static String texName(JsonElement v) {
		if (v.isJsonObject() && v.getAsJsonObject().has("sprite")) {
			return v.getAsJsonObject().get("sprite").getAsString();
		}
		return v.isJsonPrimitive() ? v.getAsString() : "";
	}

	private static String first(Map<String, String> t, String... keys) {
		for (String k : keys) {
			if (t.containsKey(k)) {
				return t.get(k);
			}
		}
		return null;
	}

	private static String ref(String t, Map<String, String> all) {
		for (int i = 0; i < 6 && t.startsWith("#"); i++) {
			t = all.getOrDefault(t.substring(1), t);
		}
		return t.replace("minecraft:", "");
	}

	/** Grass and leaves carry no colour in their textures; tint like the plains biome. */
	private static int tint(String id, String face) {
		if (id.contains("leaves") && !id.contains("azalea") && !id.contains("cherry")) {
			return 0x48b518;
		}
		if (id.equals("grass_block") && face.equals("top")) {
			return 0x91bd59;
		}
		return -1;
	}

	/** The classic inventory cube: top, left (front) and right faces with Minecraft's shading. */
	private static void isoCube(Graphics2D g, int size, BufferedImage top, BufferedImage left, BufferedImage right, String id) {
		double s = size / 2.0 * 0.98;
		double cx = size / 2.0, cy = size / 2.0;
		double hx = s * Math.cos(Math.toRadians(30)), hy = s * 0.5;
		// top face: diamond
		drawFace(g, top, new double[][] {{cx, cy - s}, {cx + hx, cy - s + hy}, {cx, cy - s + 2 * hy}, {cx - hx, cy - s + hy}}, 1.0, tint(id, "top"));
		drawFace(g, left, new double[][] {{cx - hx, cy - s + hy}, {cx, cy - s + 2 * hy}, {cx, cy + s}, {cx - hx, cy + s - hy}}, 0.8, tint(id, "side"));
		drawFace(g, right, new double[][] {{cx, cy - s + 2 * hy}, {cx + hx, cy - s + hy}, {cx + hx, cy + s - hy}, {cx, cy + s}}, 0.62, tint(id, "side"));
	}

	/** Maps the texture onto the parallelogram p0 (tex 0,0), p1 (tex w,0), p3 (tex 0,h) - p2 follows. */
	private static void drawFace(Graphics2D g, BufferedImage tex, double[][] p, double shade, int tint) {
		if (tex == null) {
			return;
		}
		int w = tex.getWidth(), h = tex.getHeight();
		BufferedImage shaded = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int c = tex.getRGB(x, y);
				int r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
				if (tint >= 0) {
					r = r * ((tint >> 16) & 255) / 255;
					gg = gg * ((tint >> 8) & 255) / 255;
					b = b * (tint & 255) / 255;
				}
				shaded.setRGB(x, y, (c & 0xff000000) | (int) (r * shade) << 16 | (int) (gg * shade) << 8 | (int) (b * shade));
			}
		}
		java.awt.geom.AffineTransform t = new java.awt.geom.AffineTransform((p[1][0] - p[0][0]) / w, (p[1][1] - p[0][1]) / w,
				(p[3][0] - p[0][0]) / h, (p[3][1] - p[0][1]) / h, p[0][0], p[0][1]);
		g.drawImage(shaded, t, null);
	}

	/**
	 * A block's face textures for the 3D schematic viewer: {top, side, bottom, kind, tint}. kind: cube, cross (plants,
	 * torches - drawn as two crossed planes) or flat; null when the block has no model we know.
	 */
	Map<String, Object> faces(String id) {
		if (dir == null || !id.matches("[a-z0-9_]{1,64}")) {
			return null;
		}
		if (id.equals("redstone_wire")) {
			Map<String, Object> wire = new java.util.LinkedHashMap<>();
			wire.put("top", "redstone_dust_dot");
			wire.put("side", "redstone_dust_dot");
			wire.put("bottom", "redstone_dust_dot");
			wire.put("kind", "flat");
			wire.put("tint", "#d01010");
			wire.put("transparent", true);
			return wire;
		}
		JsonObject m = model("block/" + id);
		if (m == null) {
			return null;
		}
		Map<String, String> textures = new HashMap<>();
		String chain = "";
		for (int depth = 0; m != null && depth < 8; depth++) {
			if (m.has("textures")) {
				for (var e : m.getAsJsonObject("textures").entrySet()) {
					textures.putIfAbsent(e.getKey(), texName(e.getValue()));
				}
			}
			if (!m.has("parent")) {
				break;
			}
			String parent = m.get("parent").getAsString().replace("minecraft:", "");
			chain += parent + " ";
			m = model(parent);
		}
		String kind = chain.contains("block/cross") || chain.contains("tinted_cross") || chain.contains("torch") || chain.contains("flower_pot_cross") ? "cross"
				: chain.contains("block/cube") || chain.contains("block/leaves") || textures.containsKey("all") || textures.containsKey("side") || textures.containsKey("end") ? "cube" : "flat";
		String top = first(textures, "top", "end", "all", "up", "texture", "cross", "torch", "particle", "side");
		String side = first(textures, "side", "all", "north", "front", "texture", "cross", "torch", "particle", "end");
		String bottom = first(textures, "bottom", "end", "all", "down", "top", "texture", "particle", "side");
		if (top == null && side == null) {
			return null;
		}
		String t = top == null ? null : ref(top, textures).replaceFirst("^block/", "");
		String sd = side == null ? t : ref(side, textures).replaceFirst("^block/", "");
		String b = bottom == null ? t : ref(bottom, textures).replaceFirst("^block/", "");
		int tint = tint(id, "top");
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		out.put("top", t);
		out.put("side", sd);
		out.put("bottom", b);
		out.put("kind", kind);
		out.put("tint", id.contains("leaves") || id.equals("grass_block") || id.contains("grass") || id.contains("fern") || id.equals("vine") || id.equals("lily_pad")
				? String.format("#%06x", tint >= 0 ? tint : 0x48b518) : null);
		out.put("transparent", id.contains("glass") || id.contains("leaves") || id.contains("ice") && !id.contains("packed") || kind.equals("cross"));
		return out;
	}

	BufferedImage defaultSkin(boolean slim) {
		BufferedImage img = texture("entity/player/" + (slim ? "slim/alex" : "wide/steve"));
		return img != null ? img : new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
	}
}
