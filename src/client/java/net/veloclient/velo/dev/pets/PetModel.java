package net.veloclient.velo.dev.pets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A pet as exported by the prototype stage ({@code export.js}): a node tree (bones, cuboids with their UVs and skinned
 * membranes) in model pixels, its materials and textures, and baked clips sampled at {@link #fps}. Loops ({@code idle},
 * {@code move}) put the root in the pet's own frame; scenes ({@code special, kill, hit, perch, sleep, wake}) in the
 * player's frame. A clip channel holds one value when it never changes, otherwise one per frame.
 */
public final class PetModel {

	public static final int BONE = 0;
	public static final int BOX = 1;
	public static final int SKIN = 2;

	public static final class Node {
		int parent;
		int type;
		int material = -1;
		float[] pos;
		float[] uv;
		int[] index;
		int[] skinIndex;
		float[] skinWeight;
		int[] bones;
		float[][] boneInverses;
	}

	public static final class Material {
		int color;
		int emissive;
		float emissiveIntensity;
		float opacity;
		boolean transparent;
		/** Drawn with the wearer's own skin (Mini-Me) instead of the exported texture. */
		boolean skin;
	}

	public static final class Channels {
		float[] p;
		float[] q;
		float[] s;
		float[] v;
	}

	public static final class Clip {
		int frames;
		Channels root;
		Channels[] nodes;
		float[][] emissive;
		float[][] opacity;
		float[][] emissiveColor;
		/** Glow dots per frame: x, y, z (px x 8), r, g, b (x 100), size (x 100); {@link #dotStart} indexes them by frame. */
		short[] dots;
		int[] dotStart;
		/** Sprites per frame: kind, x, y, z (px x 8), size (x 100), opacity (x 1000), r, g, b (0..255). */
		short[] sprites;
		int[] spriteStart;

		boolean hasFx() {
			return dots.length > 0 || sprites.length > 0;
		}

		public float seconds(int fps) {
			return frames / (float) fps;
		}

		private float[] calm;

		/**
		 * The longest calm stretch of the clip after its first 30% (no bone turning more than ~2.5 degrees a frame), as
		 * {start, end} in frames - what a held pose (perch, sleep) loops, so a wave or a stretch in the clip plays at most
		 * once instead of over and over. Null if there's no calm stretch of at least 24 frames.
		 */
		float[] calm() {
			if (calm != null) {
				return calm.length == 0 ? null : calm;
			}
			int from = Math.max(1, frames * 3 / 10);
			int bestStart = -1;
			int bestLength = 0;
			int runStart = -1;
			for (int f = from; f < frames; f++) {
				boolean quiet = motion(f - 1, f) < 2.5;
				if (quiet && runStart < 0) {
					runStart = f - 1;
				}
				if ((!quiet || f == frames - 1) && runStart >= 0) {
					int end = quiet ? f : f - 1;
					if (end - runStart > bestLength) {
						bestLength = end - runStart;
						bestStart = runStart;
					}
					runStart = -1;
				}
			}
			calm = bestLength >= 24 ? new float[] {bestStart, bestStart + bestLength} : new float[0];
			return calm.length == 0 ? null : calm;
		}

		/** Largest bone turn (degrees) between two frames. */
		private double motion(int a, int b) {
			double most = 0;
			for (Channels c : nodes) {
				if (c == null || c.q == null || c.q.length < (b + 1) * 4) {
					continue;
				}
				double dot = Math.abs(c.q[a * 4] * c.q[b * 4] + c.q[a * 4 + 1] * c.q[b * 4 + 1] + c.q[a * 4 + 2] * c.q[b * 4 + 2]
						+ c.q[a * 4 + 3] * c.q[b * 4 + 3]);
				most = Math.max(most, Math.toDegrees(2 * Math.acos(Math.min(1, dot))));
			}
			return most;
		}
	}

	final String id;
	final String name;
	final String kind;
	final float[] follow;
	final float[] perch;
	final int fps;
	final Node[] nodes;
	final Material[] materials;
	final String[] textures;
	final Map<String, Clip> clips = new HashMap<>();

	private PetModel(JsonObject json) {
		id = json.get("id").getAsString();
		name = json.get("name").getAsString();
		kind = json.get("kind").getAsString();
		follow = floats(json.getAsJsonArray("follow"));
		perch = floats(json.getAsJsonArray("perch"));
		fps = json.get("fps").getAsInt();
		JsonArray ns = json.getAsJsonArray("nodes");
		nodes = new Node[ns.size()];
		for (int i = 0; i < nodes.length; i++) {
			JsonObject o = ns.get(i).getAsJsonObject();
			Node n = new Node();
			n.parent = o.get("parent").getAsInt();
			String type = o.get("type").getAsString();
			n.type = "skin".equals(type) ? SKIN : "box".equals(type) ? BOX : BONE;
			if (n.type != BONE) {
				n.material = o.get("material").getAsInt();
				n.pos = floats(o.getAsJsonArray("pos"));
				n.uv = floats(o.getAsJsonArray("uv"));
			}
			if (n.type == SKIN) {
				n.index = ints(o.getAsJsonArray("index"));
				n.skinIndex = ints(o.getAsJsonArray("skinIndex"));
				n.skinWeight = floats(o.getAsJsonArray("skinWeight"));
				n.bones = ints(o.getAsJsonArray("bones"));
				JsonArray inv = o.getAsJsonArray("boneInverses");
				n.boneInverses = new float[inv.size()][];
				for (int b = 0; b < inv.size(); b++) {
					n.boneInverses[b] = floats(inv.get(b).getAsJsonArray());
				}
			}
			nodes[i] = n;
		}
		JsonArray ms = json.getAsJsonArray("materials");
		materials = new Material[ms.size()];
		for (int i = 0; i < materials.length; i++) {
			JsonObject o = ms.get(i).getAsJsonObject();
			Material m = new Material();
			m.color = o.get("color").getAsInt();
			m.emissive = o.get("emissive").getAsInt();
			m.emissiveIntensity = o.get("emissiveIntensity").getAsFloat();
			m.opacity = o.get("opacity").getAsFloat();
			m.transparent = o.get("transparent").getAsBoolean();
			m.skin = o.has("skin") && o.get("skin").getAsBoolean();
			materials[i] = m;
		}
		JsonArray ts = json.getAsJsonArray("textures");
		textures = new String[ts.size()];
		for (int i = 0; i < textures.length; i++) {
			textures[i] = ts.get(i).getAsString();
		}
		for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("clips").entrySet()) {
			JsonObject o = e.getValue().getAsJsonObject();
			Clip c = new Clip();
			c.frames = o.get("frames").getAsInt();
			c.root = channels(o.getAsJsonObject("root"));
			JsonArray cn = o.getAsJsonArray("nodes");
			c.nodes = new Channels[cn.size()];
			for (int i = 0; i < c.nodes.length; i++) {
				c.nodes[i] = channels(cn.get(i).getAsJsonObject());
			}
			JsonArray cm = o.getAsJsonArray("materials");
			c.emissive = new float[cm.size()][];
			c.opacity = new float[cm.size()][];
			c.emissiveColor = new float[cm.size()][];
			for (int i = 0; i < cm.size(); i++) {
				JsonObject mo = cm.get(i).getAsJsonObject();
				c.emissive[i] = floats(mo.getAsJsonArray("e"));
				c.opacity[i] = floats(mo.getAsJsonArray("o"));
				c.emissiveColor[i] = mo.has("c") ? floats(mo.getAsJsonArray("c")) : new float[] {1, 1, 1};
			}
			c.dots = new short[0];
			c.dotStart = new int[c.frames + 1];
			c.sprites = new short[0];
			c.spriteStart = new int[c.frames + 1];
			if (o.has("dots")) {
				c.dots = shorts(o.getAsJsonObject("dots").get("data").getAsString());
				c.dotStart = starts(ints(o.getAsJsonObject("dots").getAsJsonArray("counts")), 7);
			}
			if (o.has("sprites")) {
				c.sprites = shorts(o.getAsJsonObject("sprites").get("data").getAsString());
				c.spriteStart = starts(ints(o.getAsJsonObject("sprites").getAsJsonArray("counts")), 9);
			}
			clips.put(e.getKey(), c);
		}
	}

	public boolean flies() {
		return !"walk".equals(kind);
	}

	private static final Map<String, PetModel> CACHE = new HashMap<>();

	/** The bundled pet {@code id}, loaded once; null if it isn't there or won't parse. */
	public static synchronized PetModel load(String id) {
		if (CACHE.containsKey(id)) {
			return CACHE.get(id);
		}
		PetModel model = null;
		try (InputStream raw = PetModel.class.getResourceAsStream("/assets/velo-client/pets/" + id + ".json.gz")) {
			if (raw != null) {
				try (var reader = new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8)) {
					model = new PetModel(JsonParser.parseReader(reader).getAsJsonObject());
				}
			}
		} catch (Exception e) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Couldn't load pet {}", id, e);
		}
		CACHE.put(id, model);
		return model;
	}

	private static Channels channels(JsonObject o) {
		Channels c = new Channels();
		c.p = floats(o.getAsJsonArray("p"));
		c.q = floats(o.getAsJsonArray("q"));
		c.s = floats(o.getAsJsonArray("s"));
		c.v = o.has("v") ? floats(o.getAsJsonArray("v")) : new float[] {1};
		return c;
	}

	/** Per-frame counts to start offsets (in values, {@code width} per item); one extra entry ends the last frame. */
	private static int[] starts(int[] counts, int width) {
		int[] out = new int[counts.length + 1];
		for (int i = 0; i < counts.length; i++) {
			out[i + 1] = out[i] + counts[i] * width;
		}
		return out;
	}

	private static short[] shorts(String base64) {
		byte[] bytes = java.util.Base64.getDecoder().decode(base64);
		short[] out = new short[bytes.length / 2];
		java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
		return out;
	}

	private static float[] floats(JsonArray a) {
		float[] out = new float[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsFloat();
		}
		return out;
	}

	private static int[] ints(JsonArray a) {
		int[] out = new int[a.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = a.get(i).getAsInt();
		}
		return out;
	}
}
