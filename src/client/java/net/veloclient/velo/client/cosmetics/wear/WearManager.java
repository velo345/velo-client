package net.veloclient.velo.client.cosmetics.wear;

import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;
import net.veloclient.velo.client.emotes.EmoteProps;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bodywear: hats, ears, glasses, masks, chains, watches, shoes and backpacks - one item per {@link #SLOTS slot}, every slot worn at
 * once, each optionally recoloured. Items are Velo Store items ({@code wear_<id>}) from the server catalog: a mesh in
 * the bone's own space (skin pixels from the bone's pivot, y down, the face towards -z; made by
 * tools/cosmetics/wear/blender_wear.py), the bone it rides on ({@code model.bone}, feet also {@code model.mirror} with a
 * mirrored {@code mesh_l}), its texture and, for recolourable items, a mask of the pixels a colour pick tints.
 *
 * <p>What you wear goes out with the heartbeat as "id[:colour[:height]],..." and comes back for everyone in {@code /v1/online}
 * (the server only passes on what each player owns). Colour codes and the recolouring are {@link WearTint}'s.
 */
public final class WearManager {

	public static final String ITEM_PREFIX = "wear_";
	/** The slots, in the order the Locker lists them. */
	public static final List<String> SLOTS = List.of("head", "ears", "face", "mask", "neck", "wrist", "feet", "back");

	/** A bodywear item from the catalog (its id without the prefix). */
	public record WearType(String id, String name, String description, String slot, String bone, String mirrorBone, int price, boolean inStore,
			Integer accent, String collection, Map<String, String> files) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}

		public boolean tintable() {
			return files.containsKey("mask");
		}
	}

	/**
	 * One worn item, its colour code, (glasses, masks, back items) how far it sits lower and (glasses, masks) how far
	 * out from the face, both in quarter pixels.
	 */
	public record Worn(String id, int color, int offset, int depth) {
	}

	/** The height setting of glasses and back items, quarter pixels (+ = lower). */
	public static final int MAX_OFFSET = 8;

	/** Slots whose items can be set nearer to or further from the face. */
	public static boolean depthAdjustable(String slot) {
		return "face".equals(slot) || "mask".equals(slot);
	}

	/** Slots whose items can be set to sit higher or lower. */
	public static boolean adjustable(String slot) {
		return "face".equals(slot) || "mask".equals(slot) || "back".equals(slot);
	}

	/**
	 * Loaded art: the mesh (and the left-foot one for mirrored items) as made, plus a simplified copy of each (at most
	 * ~2,000 triangles, see MeshLod) for where the full one would cost more than it shows: other players further away,
	 * and Lite on the CPU path. The simplified copy merges vertices, which bends thin parts and smears the texture - it
	 * must never be what you see up close (a chain looked broken with it, 2026-11-07).
	 */
	public record Model(EmoteProps.Mesh mesh, EmoteProps.Mesh mirrored, EmoteProps.Mesh lod, EmoteProps.Mesh lodMirrored,
			EmoteProps.Mesh low, EmoteProps.Mesh lowMirrored) {
		public EmoteProps.Mesh mesh(int side, boolean full) {
			return side == 0 ? (full ? mesh : lod) : (full ? mirrored : lodMirrored);
		}

		/** Cosmetics Quality Low: the ~500 triangle copy. */
		public EmoteProps.Mesh lowMesh(int side) {
			return side == 0 ? low : lowMirrored;
		}
	}

	/** Saved: what's in each slot and every item's colour. */
	private record State(Map<String, String> slots, Map<String, Integer> colors, Map<String, Integer> offsets, Map<String, Integer> depths) {
	}

	private static final Map<String, String> SLOTS_WORN = new LinkedHashMap<>();
	private static final Map<String, Integer> COLORS = new HashMap<>();
	private static final Map<String, Integer> OFFSETS = new HashMap<>();
	private static final Map<String, Integer> DEPTHS = new HashMap<>();
	/** The Locker's try-on: shown on you instead of what's in that slot (null value = the slot empty). */
	private static final Map<String, String> PREVIEW = new HashMap<>();
	private static final Map<String, Model> MODELS = new HashMap<>();
	private static final Map<String, Identifier> TEXTURES = new HashMap<>();
	/** Recoloured textures, oldest first: dragging through the colour picker makes many, the oldest are freed. */
	private static final java.util.ArrayDeque<String> TINTED = new java.util.ArrayDeque<>();
	private static final int MAX_TINTED = 40;
	private static final Map<String, Long> FAILED = new HashMap<>();
	private static boolean loaded;

	private WearManager() {
	}

	// ---- catalog ----

	/** Every bodywear item the catalog knows (in the store, owned, or looked up because someone wears it). */
	public static List<WearType> all() {
		List<WearType> out = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (List<CosmeticsCatalog.Item> list : List.of(CosmeticsCatalog.items(), CosmeticsCatalog.lookedUp())) {
			for (CosmeticsCatalog.Item item : list) {
				if ("wear".equals(item.type()) && item.id().startsWith(ITEM_PREFIX) && item.slot() != null && seen.add(item.id())) {
					out.add(of(item));
				}
			}
		}
		return out;
	}

	public static Optional<WearType> type(String id) {
		if (id == null) {
			return Optional.empty();
		}
		Optional<CosmeticsCatalog.Item> item = CosmeticsCatalog.item(ITEM_PREFIX + id);
		if (item.isEmpty()) {
			CosmeticsCatalog.requestItem(ITEM_PREFIX + id);
			return Optional.empty();
		}
		return item.map(WearManager::of);
	}

	private static WearType of(CosmeticsCatalog.Item item) {
		return new WearType(item.id().substring(ITEM_PREFIX.length()), item.name(), item.description(), item.slot(),
				item.bone() == null ? "head" : item.bone(), item.mirrorBone(), item.price(), item.inStore(), item.accent(), item.collection(), item.files());
	}

	public static boolean owns(String id) {
		return id != null && net.veloclient.velo.client.network.StoreClient.owns(ITEM_PREFIX + id);
	}

	// ---- what you wear ----

	/** Changed in the launcher's Locker: read it again (never saves - the launcher wrote it). */
	public static synchronized void reloadFromDisk() {
		loaded = false;
		SLOTS_WORN.clear();
		COLORS.clear();
		OFFSETS.clear();
		DEPTHS.clear();
		ensureLoaded();
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		State state = net.veloclient.velo.config.ConfigManager.load("cosmetics-wear", State.class, new State(null, null, null, null));
		if (state.slots() != null) {
			SLOTS_WORN.putAll(state.slots());
		}
		if (state.colors() != null) {
			COLORS.putAll(state.colors());
		}
		if (state.offsets() != null) {
			OFFSETS.putAll(state.offsets());
		}
		if (state.depths() != null) {
			DEPTHS.putAll(state.depths());
		}
	}

	private static void save() {
		net.veloclient.velo.config.ConfigManager.save("cosmetics-wear", new State(new LinkedHashMap<>(SLOTS_WORN), new HashMap<>(COLORS), new HashMap<>(OFFSETS), new HashMap<>(DEPTHS)));
		net.veloclient.velo.client.network.VeloServerClient.syncSoon();
		net.veloclient.velo.client.cosmetics.Loadouts.changed();
	}

	/** What's in every slot (equipped, not the try-on): slot -> item id. For loadouts. */
	public static synchronized Map<String, String> equippedAll() {
		ensureLoaded();
		return new LinkedHashMap<>(SLOTS_WORN);
	}

	/** What's in {@code slot} (equipped, not the preview), or null. */
	public static synchronized String equipped(String slot) {
		ensureLoaded();
		return SLOTS_WORN.get(slot);
	}

	/** Puts an owned item on in its slot (replacing what was there), or empties {@code slot} with null. */
	public static synchronized boolean equip(String slot, String id) {
		ensureLoaded();
		if (id == null) {
			SLOTS_WORN.remove(slot);
		} else {
			if (!owns(id)) {
				return false;
			}
			SLOTS_WORN.put(slot, id);
		}
		save();
		return true;
	}

	public static synchronized int color(String id) {
		ensureLoaded();
		return COLORS.getOrDefault(id, 0);
	}

	/** Sets an item's colour code ({@link WearTint}; 0 = its own colour). */
	public static synchronized void setColor(String id, int code) {
		ensureLoaded();
		if (code <= 0 || !WearTint.valid(code)) {
			COLORS.remove(id);
		} else {
			COLORS.put(id, code);
		}
		save();
	}

	/** How far an item sits lower than modelled, in quarter pixels (glasses and back items). */
	public static synchronized int offset(String id) {
		ensureLoaded();
		return OFFSETS.getOrDefault(id, 0);
	}

	public static synchronized void setOffset(String id, int offset) {
		ensureLoaded();
		int o = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, offset));
		if (o == 0) {
			OFFSETS.remove(id);
		} else {
			OFFSETS.put(id, o);
		}
		save();
	}

	/** How far out from the face an item sits beyond its modelled place, in quarter pixels (glasses and masks). */
	public static synchronized int depth(String id) {
		ensureLoaded();
		return DEPTHS.getOrDefault(id, 0);
	}

	public static synchronized void setDepth(String id, int depth) {
		ensureLoaded();
		int d = Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, depth));
		if (d == 0) {
			DEPTHS.remove(id);
		} else {
			DEPTHS.put(id, d);
		}
		save();
	}

	/** Locker try-on: shows {@code id} (null = nothing) in {@code slot} on you until {@link #clearPreview()}. */
	public static synchronized void setPreview(String slot, String id) {
		PREVIEW.put(slot, id);
	}

	public static synchronized void clearPreview() {
		PREVIEW.clear();
	}

	/** What the local player shows: equipped items, the Locker's try-on over them. */
	public static synchronized List<Worn> wornByLocalPlayer() {
		ensureLoaded();
		List<Worn> out = new ArrayList<>();
		for (String slot : SLOTS) {
			String id = PREVIEW.containsKey(slot) ? PREVIEW.get(slot) : SLOTS_WORN.get(slot);
			if (id != null && (PREVIEW.containsKey(slot) || owns(id) || !net.veloclient.velo.client.network.StoreClient.walletLoaded())) {
				out.add(new Worn(id, color(id), offset(id), depth(id)));
			}
		}
		return out;
	}

	/** The heartbeat value: "id[:colour[:height[:depth]]],..." of what you own and wear (colour empty when it's its own), or null. */
	public static synchronized String networkValue() {
		ensureLoaded();
		List<String> parts = new ArrayList<>();
		for (String slot : SLOTS) {
			String id = SLOTS_WORN.get(slot);
			if (id != null && owns(id)) {
				String c = WearTint.encode(color(id));
				int o = offset(id);
				int d = depth(id);
				String colour = c != null ? c : "";
				parts.add(d != 0 ? id + ":" + colour + ":" + o + ":" + d : o != 0 ? id + ":" + colour + ":" + o : c != null ? id + ":" + c : id);
			}
		}
		return parts.isEmpty() ? null : String.join(",", parts);
	}

	/** Reads another player's value. */
	public static List<Worn> parseNetworkValue(String value) {
		List<Worn> out = new ArrayList<>();
		if (value == null || value.isBlank()) {
			return out;
		}
		for (String part : value.split(",")) {
			String[] bits = part.split(":", 4);
			if (!bits[0].matches("[a-z0-9_-]{1,40}")) {
				continue;
			}
			out.add(new Worn(bits[0], bits.length > 1 ? WearTint.decode(bits[1]) : 0, bits.length > 2 ? quarter(bits[2]) : 0, bits.length > 3 ? quarter(bits[3]) : 0));
		}
		return out;
	}

	private static int quarter(String text) {
		try {
			return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, Integer.parseInt(text)));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	// ---- art ----

	private static boolean failedRecently(String key) {
		Long at = FAILED.get(key);
		return at != null && System.currentTimeMillis() - at < 30_000;
	}

	private static final java.util.Set<String> LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * The item's meshes, loaded on first use; null until its files have downloaded and the mesh is ready. Render thread.
	 *
	 * <p>Reading and simplifying a mesh (up to 21k triangles down to 2k) takes tens of milliseconds - done here it froze
	 * the frame that first showed the item (opening the Store or the Locker with bodywear in view, JFR 2026-11-06). It
	 * runs on a background thread now; the item simply appears a few frames later.
	 */
	public static synchronized Model model(String id) {
		Model model = MODELS.get(id);
		if (model != null || failedRecently(id)) {
			return model;
		}
		WearType type = type(id).orElse(null);
		if (type == null || !LOADING.add(id)) {
			return null;
		}
		Thread.ofVirtual().name("velo-wear-mesh").start(() -> {
			try {
				EmoteProps.Mesh mesh = readMesh(type.files().get("mesh"));
				EmoteProps.Mesh mirrored = type.mirrorBone() != null ? readMesh(type.files().get("mesh_l")) : null;
				if (mesh != null && (type.mirrorBone() == null || mirrored != null)) {
					synchronized (WearManager.class) {
						MODELS.put(id, new Model(mesh, mirrored, lod(mesh), lod(mirrored), lowLod(mesh), lowLod(mirrored)));
					}
				}
			} catch (Exception e) {
				synchronized (WearManager.class) {
					FAILED.put(id, System.currentTimeMillis());
				}
				VeloClient.LOGGER.warn("Couldn't load bodywear {}", id, e);
			} finally {
				LOADING.remove(id);
			}
		});
		return null;
	}

	private static EmoteProps.Mesh readMesh(String sha) throws java.io.IOException {
		java.nio.file.Path file = sha == null ? null : CosmeticsCatalog.file(sha);
		if (file == null) {
			return null;
		}
		try (InputStream in = java.nio.file.Files.newInputStream(file)) {
			return EmoteProps.read(in);
		}
	}

	/** Cosmetics Quality Low: a much coarser copy (~500 triangles). */
	private static EmoteProps.Mesh lowLod(EmoteProps.Mesh mesh) {
		return mesh == null ? null : net.veloclient.velo.client.cosmetics.render.MeshLod.simplify(mesh, 500);
	}

	/** The simplified copy (at most ~2,000 triangles; the Meshy originals have up to 21k). */
	private static EmoteProps.Mesh lod(EmoteProps.Mesh mesh) {
		return mesh == null ? null : net.veloclient.velo.client.cosmetics.render.MeshLod.simplify(mesh, 2000);
	}

	/**
	 * The item's texture in colour {@code code} (0 = its own): the mask's pixels painted by {@link WearTint#colorize}.
	 * Null until downloaded. Render thread.
	 */
	public static synchronized Identifier texture(String id, int code) {
		WearType type = type(id).orElse(null);
		if (type == null) {
			return null;
		}
		int c = type.tintable() && WearTint.valid(code) ? code : 0;
		String key = id + ":" + c;
		Identifier known = TEXTURES.get(key);
		if (known != null || failedRecently(key)) {
			return known;
		}
		java.nio.file.Path texFile = CosmeticsCatalog.file(type.files().get("texture"));
		java.nio.file.Path maskFile = c > 0 ? CosmeticsCatalog.file(type.files().get("mask")) : null;
		if (texFile == null || c > 0 && maskFile == null) {
			return null;
		}
		try (InputStream in = java.nio.file.Files.newInputStream(texFile)) {
			net.minecraft.client.texture.NativeImage image = net.minecraft.client.texture.NativeImage.read(in);
			if (c > 0) {
				try (InputStream maskIn = java.nio.file.Files.newInputStream(maskFile)) {
					net.minecraft.client.texture.NativeImage mask = net.minecraft.client.texture.NativeImage.read(maskIn);
					tint(image, mask, WearTint.rgb(c));
					mask.close();
				}
			}
			Identifier identifier = net.veloclient.velo.client.cosmetics.CapeManager.registerPreloadedTexture("wear_" + id + "_" + c, image, "bodywear " + id);
			TEXTURES.put(key, identifier);
			if (c > 0) {
				TINTED.addLast(key);
				while (TINTED.size() > MAX_TINTED) {
					Identifier old = TEXTURES.remove(TINTED.removeFirst());
					if (old != null) {
						net.minecraft.client.MinecraftClient.getInstance().getTextureManager().destroyTexture(old);
					}
				}
			}
			return identifier;
		} catch (Exception e) {
			FAILED.put(key, System.currentTimeMillis());
			VeloClient.LOGGER.warn("Couldn't load bodywear texture {}", id, e);
			return null;
		}
	}

	/** Paints the mask's pixels in {@code rgb} (the same {@link WearTint#colorize} as the launcher and the website). */
	private static void tint(net.minecraft.client.texture.NativeImage image, net.minecraft.client.texture.NativeImage mask, int rgb) {
		int w = image.getWidth();
		int h = image.getHeight();
		int[] pixels = new int[w * h];
		int[] amounts = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				pixels[y * w + x] = image.getColorArgb(x, y);
				amounts[y * w + x] = (mask.getColorArgb(x * mask.getWidth() / w, y * mask.getHeight() / h) >> 16) & 0xFF;
			}
		}
		WearTint.colorize(pixels, amounts, rgb);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setColorArgb(x, y, pixels[y * w + x]);
			}
		}
	}

	/** The item's Store picture, for cards (null until downloaded). Render thread. */
	public static synchronized Identifier previewTexture(String id) {
		WearType type = type(id).orElse(null);
		if (type == null || type.files().get("preview") == null) {
			return null;
		}
		String key = "preview:" + id;
		Identifier known = TEXTURES.get(key);
		if (known != null || failedRecently(key)) {
			return known;
		}
		java.nio.file.Path file = CosmeticsCatalog.file(type.files().get("preview"));
		if (file == null) {
			return null;
		}
		try (InputStream in = java.nio.file.Files.newInputStream(file)) {
			net.minecraft.client.texture.NativeImage image = net.minecraft.client.texture.NativeImage.read(in);
			Identifier identifier = net.veloclient.velo.client.cosmetics.CapeManager.registerPreloadedTexture("wearpreview_" + id, image, "bodywear preview " + id);
			TEXTURES.put(key, identifier);
			return identifier;
		} catch (Exception e) {
			FAILED.put(key, System.currentTimeMillis());
			return null;
		}
	}
}
