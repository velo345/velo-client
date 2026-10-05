package net.veloclient.velo.client.worldmap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GPU textures for visible map regions, one per region + layer style, rebuilt only when the region
 * changed (and at most a few per frame, so opening the map never stutters). Least recently drawn
 * textures are released.
 */
final class MapTextures {

	enum Style { SURFACE, TOPOGRAPHY, BIOMES }

	private static final int MAX_TEXTURES = 72;

	private record Entry(Identifier id, NativeImageBackedTexture texture, int version) {
	}

	private static final Map<String, Entry> CACHE = new LinkedHashMap<>(96, 0.75f, true);
	private static int counter;
	private static int builtThisFrame;

	private MapTextures() {
	}

	static void beginFrame() {
		builtThisFrame = 0;
	}

	/** Texture for a region in a style, or null if it isn't built yet (try again next frame). */
	static Identifier texture(MapStore store, MapRegion region, Style style) {
		String key = store.dir + "|" + region.rx + "|" + region.rz + "|" + style;
		Entry entry = CACHE.get(key);
		if (entry != null && entry.version == region.version) {
			return entry.id;
		}
		if (builtThisFrame >= 4) {
			return entry != null ? entry.id : null;
		}
		builtThisFrame++;
		int version = region.version;
		if (entry != null) {
			fill(entry.texture.getImage(), store, region, style);
			entry.texture.upload();
			CACHE.put(key, new Entry(entry.id, entry.texture, version));
			return entry.id;
		}
		NativeImage image = new NativeImage(MapRegion.SIZE, MapRegion.SIZE, true);
		fill(image, store, region, style);
		Identifier id = Identifier.of("velo-client", "worldmap_" + (counter++));
		NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "velo world map", image);
		MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
		CACHE.put(key, new Entry(id, texture, version));
		while (CACHE.size() > MAX_TEXTURES) {
			String eldest = CACHE.keySet().iterator().next();
			Entry out = CACHE.remove(eldest);
			MinecraftClient.getInstance().getTextureManager().destroyTexture(out.id);
		}
		return id;
	}

	static void clear() {
		for (Entry entry : CACHE.values()) {
			MinecraftClient.getInstance().getTextureManager().destroyTexture(entry.id);
		}
		CACHE.clear();
	}

	private static void fill(NativeImage image, MapStore store, MapRegion region, Style style) {
		int n = MapRegion.SIZE;
		for (int z = 0; z < n; z++) {
			for (int x = 0; x < n; x++) {
				int i = z * n + x;
				int rgb = region.rgb[i];
				if (rgb == 0) {
					image.setColorArgb(x, z, 0);
					continue;
				}
				int h = region.height[i];
				int north = z > 0 && region.rgb[i - n] != 0 ? region.height[i - n] : h;
				int west = x > 0 && region.rgb[i - 1] != 0 ? region.height[i - 1] : h;
				int color = switch (style) {
					case SURFACE -> {
						// Vanilla map shading: brighter facing a rise to the north, darker below it.
						float factor = h > north ? 1.0f : h < north ? 0.71f : 0.86f;
						yield WorldMap.shade(rgb & 0xFFFFFF, factor);
					}
					case TOPOGRAPHY -> {
						int c = heightColor(h);
						boolean contour = Math.floorDiv(h, 8) != Math.floorDiv(north, 8) || Math.floorDiv(h, 8) != Math.floorDiv(west, 8);
						yield contour ? WorldMap.shade(c, 0.62f) : c;
					}
					case BIOMES -> {
						float factor = h > north ? 1.0f : h < north ? 0.8f : 0.9f;
						yield WorldMap.shade(biomeColor(store.biomeName(region.biome[i])), factor);
					}
				};
				image.setColorArgb(x, z, 0xFF000000 | color);
			}
		}
	}

	/** Height ramp: deep blue sea, green lowlands, yellow hills, brown mountains, white peaks. */
	static int heightColor(int y) {
		if (y < 40) {
			return mix(0x0B2A5C, 0x1E5AA8, (y + 64) / 104f);
		}
		if (y < 63) {
			return mix(0x1E5AA8, 0x4A90D9, (y - 40) / 23f);
		}
		if (y < 90) {
			return mix(0x5DA34A, 0xA9C25A, (y - 63) / 27f);
		}
		if (y < 130) {
			return mix(0xA9C25A, 0xC9A35A, (y - 90) / 40f);
		}
		if (y < 190) {
			return mix(0xC9A35A, 0x8A6A4A, (y - 130) / 60f);
		}
		return mix(0x8A6A4A, 0xF2F2F2, Math.min(1f, (y - 190) / 60f));
	}

	private static int mix(int a, int b, float t) {
		t = Math.max(0, Math.min(1, t));
		int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return r << 16 | g << 8 | bl;
	}

	/** A recognisable color per biome: by family where it's obvious, otherwise a stable hash. */
	static int biomeColor(String id) {
		String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
		if (name.contains("deep") && name.contains("ocean")) {
			return 0x15306E;
		}
		if (name.contains("ocean")) {
			return 0x2D5FB5;
		}
		if (name.contains("river")) {
			return 0x3F7FD8;
		}
		if (name.contains("beach") || name.contains("desert")) {
			return 0xE3D28F;
		}
		if (name.contains("badlands")) {
			return 0xC56B35;
		}
		if (name.contains("snow") || name.contains("frozen") || name.contains("ice") || name.contains("peaks")) {
			return 0xE6F2F8;
		}
		if (name.contains("jungle")) {
			return 0x2E8B1E;
		}
		if (name.contains("swamp") || name.contains("mangrove")) {
			return 0x4C6B3A;
		}
		if (name.contains("taiga") || name.contains("grove")) {
			return 0x3E6E58;
		}
		if (name.contains("dark_forest")) {
			return 0x2F4A1E;
		}
		if (name.contains("cherry")) {
			return 0xF2A7C6;
		}
		if (name.contains("birch")) {
			return 0x7FB86A;
		}
		if (name.contains("forest")) {
			return 0x3F8A35;
		}
		if (name.contains("savanna")) {
			return 0xB7A94E;
		}
		if (name.contains("plains") || name.contains("meadow")) {
			return 0x86C25C;
		}
		if (name.contains("mushroom")) {
			return 0x9A6FA8;
		}
		if (name.contains("nether") || name.contains("crimson") || name.contains("basalt") || name.contains("soul")) {
			return name.contains("warped") ? 0x2A8C82 : name.contains("soul") ? 0x6A5A4A : 0x9C2B2B;
		}
		if (name.contains("warped")) {
			return 0x2A8C82;
		}
		if (name.contains("end")) {
			return 0xD8D59A;
		}
		if (name.contains("cave") || name.contains("dripstone") || name.contains("deep_dark")) {
			return 0x5A5A66;
		}
		int hash = id.hashCode();
		return 0x404040 | (hash & 0x7F7F7F);
	}
}
