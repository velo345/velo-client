package net.veloclient.velo.client.gui.packs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Builds the 3D preview of a texture: the block models that use it (parsed from the model JSONs, parents,
 * elements, face UVs and rotations included), items as the game's extruded sprite, and entity textures on the
 * real baked entity model. Which models use which texture comes from an index over every loaded model, built
 * once in the background.
 */
public final class PreviewModels {

	/** What a texture can be shown on. */
	public enum Kind { BLOCK, ITEM, ENTITY, FLAT }

	/** A built preview: the model, the textures it needs (besides the edited one) and the alternatives to switch to. */
	public record Preview(Kind kind, Soft3d model, Map<String, Soft3d.Tex> textures, List<String> candidates, int index, String label) {
	}

	private static volatile Map<String, List<String>> textureToModels;
	private static CompletableFuture<Void> indexing;
	private static final int FOLIAGE = 0xFF6FAE4A;

	private PreviewModels() {
	}

	/** "ns:textures/block/stone.png" -> "ns:block/stone" (how models refer to it); null for non-textures. */
	public static String textureRef(String assetKey) {
		int colon = assetKey.indexOf(':');
		String ns = assetKey.substring(0, colon);
		String path = assetKey.substring(colon + 1);
		if (!path.startsWith("textures/") || !path.endsWith(".png")) {
			return null;
		}
		return ns + ":" + path.substring("textures/".length(), path.length() - 4);
	}

	public static Kind kindOf(String ref) {
		String path = ref.substring(ref.indexOf(':') + 1);
		if (path.startsWith("block/")) {
			return Kind.BLOCK;
		}
		if (path.startsWith("item/")) {
			return Kind.ITEM;
		}
		if (path.startsWith("entity/")) {
			return Kind.ENTITY;
		}
		return Kind.FLAT;
	}

	/** Starts (once) indexing which models use which texture. */
	public static void warmUp() {
		if (indexing == null) {
			indexing = CompletableFuture.runAsync(PreviewModels::buildIndex);
		}
	}

	public static boolean indexReady() {
		return textureToModels != null;
	}

	private static void buildIndex() {
		Map<String, List<String>> index = new HashMap<>();
		for (String key : PackBridge.listGame("models", ".json")) {
			String path = key.substring(key.indexOf(':') + 1);
			if (!path.startsWith("models/block/") && !path.startsWith("models/item/")) {
				continue;
			}
			byte[] data = PackBridge.readGame(key);
			if (data == null) {
				continue;
			}
			try {
				JsonObject json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
				if (!json.has("textures")) {
					continue;
				}
				String model = key.substring(0, key.indexOf(':')) + ":" + path.substring("models/".length(), path.length() - 5);
				for (Map.Entry<String, JsonElement> t : json.getAsJsonObject("textures").entrySet()) {
					if (!t.getValue().isJsonPrimitive()) {
						continue;
					}
					String value = t.getValue().getAsString();
					if (value.startsWith("#")) {
						continue;
					}
					index.computeIfAbsent(normalize(value), k -> new ArrayList<>()).add(model);
				}
			} catch (RuntimeException ignored) {
				// Broken model JSON: skipped.
			}
		}
		textureToModels = index;
	}

	private static String normalize(String ref) {
		return ref.contains(":") ? ref : "minecraft:" + ref;
	}

	/** The models that use texture {@code ref}, the one named like the texture first. */
	public static List<String> modelsUsing(String ref) {
		Map<String, List<String>> index = textureToModels;
		List<String> out = new ArrayList<>(index == null ? List.of() : index.getOrDefault(ref, List.of()));
		String name = ref.substring(ref.lastIndexOf('/') + 1);
		out.sort(Comparator.comparingInt((String m) -> m.endsWith("/" + name) ? 0 : m.contains(name) ? 1 : 2).thenComparingInt(String::length));
		return out;
	}

	/** Entity model layers that probably use an entity texture, best guess first. */
	public static List<String> layersFor(String ref) {
		String path = ref.substring(ref.indexOf(':') + 1).substring("entity/".length());
		String[] parts = path.split("/");
		String file = parts[parts.length - 1];
		String folder = parts.length > 1 ? parts[parts.length - 2] : "";
		String folderSingular = folder.endsWith("s") ? folder.substring(0, folder.length() - 1) : folder;
		Map<String, Integer> scores = new LinkedHashMap<>();
		for (String layer : PackBridge.entityLayers()) {
			int hash = layer.indexOf('#');
			String id = layer.substring(layer.indexOf(':') + 1, hash);
			String name = layer.substring(hash + 1);
			String plain = id.replace('/', '_');
			int score;
			if (id.equals(file) || plain.equals(file)) {
				score = 100;
			} else if (path.equals(id) || path.replace("s/", "/").equals(id)) {
				score = 98;
			} else if (id.equals(folder) || id.equals(folderSingular)) {
				score = 90;
			} else if (file.startsWith(plain + "_") || file.endsWith("_" + plain)) {
				score = 70 + plain.length();
			} else if (plain.length() > 2 && file.contains(plain)) {
				score = 50 + plain.length();
			} else if (plain.length() > 2 && folder.contains(plain)) {
				score = 40 + plain.length();
			} else {
				continue;
			}
			if (path.startsWith("player/slim") && id.equals("player_slim")) {
				score = 120;
			} else if (path.startsWith("player/wide") && id.equals("player")) {
				score = 120;
			}
			if (name.equals("main")) {
				score += 5;
			} else if (name.contains("armor") || name.contains("outer") || name.contains("inner")) {
				score -= 30;
			}
			scores.put(layer, score);
		}
		List<String> out = new ArrayList<>(scores.keySet());
		out.sort(Comparator.comparingInt((String l) -> -scores.get(l)).thenComparingInt(String::length));
		return out.size() > 16 ? new ArrayList<>(out.subList(0, 16)) : out;
	}

	/**
	 * Builds the preview of texture {@code ref} using candidate {@code index} (a model id or entity layer).
	 * {@code assets} reads "ns:path" files (the pack first, then the game); {@code size} is the edited texture's size.
	 */
	public static Preview build(String ref, int index, int width, int height, Function<String, byte[]> assets, int[] firstFrame) {
		Kind kind = kindOf(ref);
		Map<String, Soft3d.Tex> textures = new HashMap<>();
		Soft3d model = new Soft3d();
		if (kind == Kind.ENTITY) {
			List<String> layers = layersFor(ref);
			if (!layers.isEmpty()) {
				int i = Math.floorMod(index, layers.size());
				float[] quads = PackBridge.entityQuads(layers.get(i));
				if (quads != null && quads.length > 0) {
					for (int q = 0; q + 20 <= quads.length; q += 20) {
						float[] v = new float[20];
						for (int k = 0; k < 4; k++) {
							v[k * 5] = quads[q + k * 5];
							v[k * 5 + 1] = -quads[q + k * 5 + 1];
							v[k * 5 + 2] = -quads[q + k * 5 + 2];
							v[k * 5 + 3] = quads[q + k * 5 + 3];
							v[k * 5 + 4] = quads[q + k * 5 + 4];
						}
						model.add(new Soft3d.Quad(v, ref, 0xFFFFFFFF, true));
					}
					model.fit();
					String layer = layers.get(i);
					return new Preview(kind, model, textures, layers, i, "Model: " + layer.substring(layer.indexOf(':') + 1).replace("#main", ""));
				}
			}
			return new Preview(Kind.FLAT, model, textures, List.of(), 0, "No entity model found");
		}
		if (kind == Kind.BLOCK || kind == Kind.ITEM) {
			List<String> models = modelsUsing(ref);
			if (!models.isEmpty()) {
				int i = Math.floorMod(index, models.size());
				String modelId = models.get(i);
				ResolvedModel resolved = resolve(modelId, assets);
				if (resolved != null && resolved.generated) {
					extrude(model, resolved, ref, width, height, firstFrame, assets, textures);
					model.fit();
					return new Preview(Kind.ITEM, model, textures, models, i, "Model: " + shortName(modelId));
				}
				if (resolved != null && !resolved.elements.isEmpty()) {
					addElements(model, resolved, ref, kind == Kind.BLOCK, assets, textures);
					if (!model.quads.isEmpty()) {
						model.fit();
						return new Preview(kind, model, textures, models, i, "Model: " + shortName(modelId));
					}
				}
			}
			if (kind == Kind.ITEM) {
				extrudeLayer(model, ref, width, height, firstFrame, 0f);
				model.fit();
				return new Preview(Kind.ITEM, model, textures, models, 0, "Item");
			}
			cube(model, ref);
			model.fit();
			return new Preview(Kind.BLOCK, model, textures, models, 0, models.isEmpty() ? "Full block" : "Model: " + shortName(models.get(0)));
		}
		return new Preview(Kind.FLAT, model, textures, List.of(), 0, "");
	}

	private static String shortName(String modelId) {
		return modelId.substring(modelId.indexOf(':') + 1);
	}

	// ---- Model JSON ----

	private static final class ResolvedModel {
		final Map<String, String> textures = new HashMap<>();
		JsonArray elements = new JsonArray();
		boolean generated;
	}

	private static ResolvedModel resolve(String modelId, Function<String, byte[]> assets) {
		ResolvedModel out = new ResolvedModel();
		List<JsonObject> chain = new ArrayList<>();
		String id = modelId;
		for (int depth = 0; depth < 12 && id != null; depth++) {
			String full = normalize(id);
			if (full.endsWith(":builtin/generated") || full.equals("minecraft:item/generated")) {
				out.generated = true;
				break;
			}
			if (full.startsWith("minecraft:builtin/")) {
				break;
			}
			int colon = full.indexOf(':');
			byte[] data = assets.apply(full.substring(0, colon) + ":models/" + full.substring(colon + 1) + ".json");
			if (data == null) {
				break;
			}
			JsonObject json;
			try {
				json = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
			} catch (RuntimeException e) {
				break;
			}
			chain.add(json);
			id = json.has("parent") ? json.get("parent").getAsString() : null;
		}
		if (chain.isEmpty() && !out.generated) {
			return null;
		}
		// Parents first, children override.
		for (int i = chain.size() - 1; i >= 0; i--) {
			JsonObject json = chain.get(i);
			if (json.has("textures")) {
				for (Map.Entry<String, JsonElement> t : json.getAsJsonObject("textures").entrySet()) {
					if (t.getValue().isJsonPrimitive()) {
						out.textures.put(t.getKey(), t.getValue().getAsString());
					}
				}
			}
			if (json.has("elements")) {
				out.elements = json.getAsJsonArray("elements");
			}
		}
		if (out.generated) {
			out.elements = new JsonArray();
		}
		return out;
	}

	/** "#side" -> the actual texture id (following variables), or null. */
	private static String lookup(ResolvedModel model, String name) {
		String value = name;
		for (int i = 0; i < 8 && value != null && value.startsWith("#"); i++) {
			value = model.textures.get(value.substring(1));
		}
		return value == null || value.startsWith("#") ? null : normalize(value);
	}

	private static void addElements(Soft3d model, ResolvedModel resolved, String editedRef, boolean block, Function<String, byte[]> assets,
			Map<String, Soft3d.Tex> textures) {
		for (JsonElement element : resolved.elements) {
			JsonObject el = element.getAsJsonObject();
			float[] from = vec(el.getAsJsonArray("from"));
			float[] to = vec(el.getAsJsonArray("to"));
			if (!el.has("faces")) {
				continue;
			}
			boolean shade = !el.has("shade") || el.get("shade").getAsBoolean();
			JsonObject rotation = el.has("rotation") ? el.getAsJsonObject("rotation") : null;
			for (Map.Entry<String, JsonElement> faceEntry : el.getAsJsonObject("faces").entrySet()) {
				JsonObject face = faceEntry.getValue().getAsJsonObject();
				String tex = face.has("texture") ? lookup(resolved, face.get("texture").getAsString()) : null;
				if (tex == null) {
					continue;
				}
				if (!tex.equals(editedRef) && !textures.containsKey(tex)) {
					Soft3d.Tex loaded = loadTexture(tex, assets);
					if (loaded == null) {
						continue;
					}
					textures.put(tex, loaded);
				}
				float[] uv = face.has("uv") ? uvs(face.getAsJsonArray("uv")) : defaultUv(faceEntry.getKey(), from, to);
				int faceRotation = face.has("rotation") ? face.get("rotation").getAsInt() : 0;
				float[][] corners = corners(faceEntry.getKey(), from, to);
				if (corners == null) {
					continue;
				}
				float[][] uvCorners = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
				int shift = Math.floorMod(faceRotation / 90, 4);
				float[] v = new float[20];
				for (int k = 0; k < 4; k++) {
					float[] p = rotate(corners[k], rotation);
					float[] t = uvCorners[Math.floorMod(k - shift, 4)];
					v[k * 5] = p[0];
					v[k * 5 + 1] = p[1];
					v[k * 5 + 2] = p[2];
					v[k * 5 + 3] = t[0] / 16f;
					v[k * 5 + 4] = t[1] / 16f;
				}
				int tint = block && face.has("tintindex") ? FOLIAGE : 0xFFFFFFFF;
				model.add(new Soft3d.Quad(v, tex, tint, shade));
			}
		}
	}

	private static float[] vec(JsonArray a) {
		return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
	}

	private static float[] uvs(JsonArray a) {
		return new float[] {a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
	}

	/** The game's automatic face UVs (from the element's extent). */
	private static float[] defaultUv(String dir, float[] f, float[] t) {
		return switch (dir) {
			case "down" -> new float[] {f[0], 16 - t[2], t[0], 16 - f[2]};
			case "up" -> new float[] {f[0], f[2], t[0], t[2]};
			case "north" -> new float[] {16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]};
			case "south" -> new float[] {f[0], 16 - t[1], t[0], 16 - f[1]};
			case "west" -> new float[] {f[2], 16 - t[1], t[2], 16 - f[1]};
			default -> new float[] {16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]};
		};
	}

	/** Face corners in texture order: top-left, top-right, bottom-right, bottom-left (as seen from outside). */
	private static float[][] corners(String dir, float[] f, float[] t) {
		return switch (dir) {
			case "north" -> new float[][] {{t[0], t[1], f[2]}, {f[0], t[1], f[2]}, {f[0], f[1], f[2]}, {t[0], f[1], f[2]}};
			case "south" -> new float[][] {{f[0], t[1], t[2]}, {t[0], t[1], t[2]}, {t[0], f[1], t[2]}, {f[0], f[1], t[2]}};
			case "west" -> new float[][] {{f[0], t[1], f[2]}, {f[0], t[1], t[2]}, {f[0], f[1], t[2]}, {f[0], f[1], f[2]}};
			case "east" -> new float[][] {{t[0], t[1], t[2]}, {t[0], t[1], f[2]}, {t[0], f[1], f[2]}, {t[0], f[1], t[2]}};
			case "up" -> new float[][] {{f[0], t[1], f[2]}, {t[0], t[1], f[2]}, {t[0], t[1], t[2]}, {f[0], t[1], t[2]}};
			case "down" -> new float[][] {{f[0], f[1], t[2]}, {t[0], f[1], t[2]}, {t[0], f[1], f[2]}, {f[0], f[1], f[2]}};
			default -> null;
		};
	}

	private static float[] rotate(float[] p, JsonObject rotation) {
		if (rotation == null || !rotation.has("angle")) {
			return p;
		}
		float angle = (float) Math.toRadians(rotation.get("angle").getAsFloat());
		float[] origin = rotation.has("origin") ? vec(rotation.getAsJsonArray("origin")) : new float[] {8, 8, 8};
		String axis = rotation.has("axis") ? rotation.get("axis").getAsString() : "y";
		float x = p[0] - origin[0];
		float y = p[1] - origin[1];
		float z = p[2] - origin[2];
		float c = (float) Math.cos(angle);
		float s = (float) Math.sin(angle);
		float[] r = switch (axis) {
			case "x" -> new float[] {x, y * c - z * s, y * s + z * c};
			case "z" -> new float[] {x * c - y * s, x * s + y * c, z};
			default -> new float[] {x * c + z * s, y, -x * s + z * c};
		};
		return new float[] {r[0] + origin[0], r[1] + origin[1], r[2] + origin[2]};
	}

	private static void cube(Soft3d model, String tex) {
		ResolvedModel cube = new ResolvedModel();
		cube.textures.put("all", tex);
		JsonObject el = new JsonObject();
		JsonArray from = new JsonArray();
		JsonArray to = new JsonArray();
		for (int i = 0; i < 3; i++) {
			from.add(0);
			to.add(16);
		}
		el.add("from", from);
		el.add("to", to);
		JsonObject faces = new JsonObject();
		for (String dir : new String[] {"down", "up", "north", "south", "west", "east"}) {
			JsonObject face = new JsonObject();
			face.addProperty("texture", "#all");
			faces.add(dir, face);
		}
		el.add("faces", faces);
		cube.elements.add(el);
		addElements(model, cube, tex, true, k -> null, new HashMap<>());
	}

	// ---- Items: the game's extruded sprites ----

	private static void extrude(Soft3d model, ResolvedModel resolved, String editedRef, int width, int height, int[] firstFrame,
			Function<String, byte[]> assets, Map<String, Soft3d.Tex> textures) {
		float z = 0f;
		for (int layer = 0; layer < 5; layer++) {
			String tex = lookup(resolved, "#layer" + layer);
			if (tex == null) {
				continue;
			}
			if (tex.equals(editedRef)) {
				extrudeLayer(model, tex, width, height, firstFrame, z);
			} else {
				Soft3d.Tex loaded = textures.computeIfAbsent(tex, k -> loadTexture(k, assets));
				if (loaded != null) {
					extrudeLayer(model, tex, loaded.width(), loaded.height(), loaded.px(), z);
				}
			}
			z += 0.01f;
		}
		if (model.quads.isEmpty()) {
			extrudeLayer(model, editedRef, width, height, firstFrame, 0f);
		}
	}

	/** A sprite as a 1 px thick slab: front, back and the side walls along its opaque edges. */
	private static void extrudeLayer(Soft3d model, String tex, int width, int height, int[] px, float lift) {
		float sx = 16f / width;
		float sy = 16f / height;
		float front = 8.5f + lift;
		float back = 7.5f - lift;
		model.add(new Soft3d.Quad(new float[] {0, 16, front, 0, 0, 16, 16, front, 1, 0, 16, 0, front, 1, 1, 0, 0, front, 0, 1}, tex, 0xFFFFFFFF, true));
		model.add(new Soft3d.Quad(new float[] {16, 16, back, 1, 0, 0, 16, back, 0, 0, 0, 0, back, 0, 1, 16, 0, back, 1, 1}, tex, 0xFFFFFFFF, true));
		if (px == null || px.length < width * height) {
			return;
		}
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if ((px[y * width + x] >>> 24) == 0) {
					continue;
				}
				float u0 = (x + 0.25f) / width;
				float u1 = (x + 0.75f) / width;
				float v0 = (y + 0.25f) / height;
				float v1 = (y + 0.75f) / height;
				float x0 = x * sx;
				float x1 = (x + 1) * sx;
				float y0 = 16 - (y + 1) * sy;
				float y1 = 16 - y * sy;
				if (y == 0 || (px[(y - 1) * width + x] >>> 24) == 0) {
					model.add(new Soft3d.Quad(new float[] {x0, y1, back, u0, v0, x1, y1, back, u1, v0, x1, y1, front, u1, v1, x0, y1, front, u0, v1}, tex, 0xFFFFFFFF, true));
				}
				if (y == height - 1 || (px[(y + 1) * width + x] >>> 24) == 0) {
					model.add(new Soft3d.Quad(new float[] {x0, y0, front, u0, v0, x1, y0, front, u1, v0, x1, y0, back, u1, v1, x0, y0, back, u0, v1}, tex, 0xFFFFFFFF, true));
				}
				if (x == 0 || (px[y * width + x - 1] >>> 24) == 0) {
					model.add(new Soft3d.Quad(new float[] {x0, y1, back, u0, v0, x0, y1, front, u1, v0, x0, y0, front, u1, v1, x0, y0, back, u0, v1}, tex, 0xFFFFFFFF, true));
				}
				if (x == width - 1 || (px[y * width + x + 1] >>> 24) == 0) {
					model.add(new Soft3d.Quad(new float[] {x1, y1, front, u0, v0, x1, y1, back, u1, v0, x1, y0, back, u1, v1, x1, y0, front, u0, v1}, tex, 0xFFFFFFFF, true));
				}
			}
		}
	}

	/** Loads another texture a model needs (first frame of animated ones). */
	static Soft3d.Tex loadTexture(String ref, Function<String, byte[]> assets) {
		int colon = ref.indexOf(':');
		String key = ref.substring(0, colon) + ":textures/" + ref.substring(colon + 1) + ".png";
		byte[] png = assets.apply(key);
		if (png == null) {
			return null;
		}
		try {
			TexDoc doc = TexDoc.read(png, assets.apply(key + ".mcmeta"));
			return new Soft3d.Tex(doc.frames.get(0), doc.width, doc.height);
		} catch (Exception e) {
			return null;
		}
	}

	/** Lower-case words of a path for search ("block/oak_log" -> "block oak log"). */
	public static String words(String path) {
		return path.replace('/', ' ').replace('_', ' ').toLowerCase(Locale.ROOT);
	}
}
