package net.veloclient.velo.client.gui.packs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What's inside a folder resource pack, sorted into categories (blocks, items, entities, interface...), and the
 * game's own assets in the same shape for "override from the game". Plain Java over the pack folder.
 */
public final class PackContents {

	/** Asset categories in the order the editor lists them. */
	public enum Category {
		BLOCKS("Blocks", true),
		ITEMS("Items", true),
		ENTITIES("Entities", true),
		INTERFACE("Interface", true),
		PARTICLES("Particles", true),
		ENVIRONMENT("Environment", true),
		PAINTINGS("Paintings", true),
		EFFECTS("Effects", true),
		ARMOR("Armor & trims", true),
		FONTS("Fonts", false),
		OTHER_TEXTURES("More textures", true),
		MODELS("Models", false),
		BLOCKSTATES("Block states", false),
		ITEM_DEFINITIONS("Item definitions", false),
		SOUNDS("Sounds & music", false),
		LANGUAGES("Languages", false),
		SHADERS("Shaders", false),
		SPLASHES("Splashes & credits", false),
		OTHER("Other files", false);

		public final String label;
		/** Shown as a grid of texture tiles (otherwise a file list). */
		public final boolean textures;

		Category(String label, boolean textures) {
			this.label = label;
			this.textures = textures;
		}
	}

	/** One file: {@code key} is "namespace:path" inside assets (e.g. "minecraft:textures/block/stone.png"). */
	public record Item(String key, Category category, String name, boolean texture, boolean animated, long bytes) {
		public String namespace() {
			return key.substring(0, key.indexOf(':'));
		}

		public String path() {
			return key.substring(key.indexOf(':') + 1);
		}
	}

	public final Path root;
	public final Map<Category, List<Item>> items = new EnumMap<>(Category.class);

	private PackContents(Path root) {
		this.root = root;
		for (Category c : Category.values()) {
			items.put(c, new ArrayList<>());
		}
	}

	public static Category categorize(String path) {
		String p = path.toLowerCase(Locale.ROOT);
		if (p.startsWith("textures/")) {
			String sub = p.substring("textures/".length());
			if (sub.startsWith("block/") || sub.startsWith("blocks/")) {
				return Category.BLOCKS;
			}
			if (sub.startsWith("item/") || sub.startsWith("items/")) {
				return Category.ITEMS;
			}
			if (sub.startsWith("entity/")) {
				return Category.ENTITIES;
			}
			if (sub.startsWith("gui/") || sub.startsWith("map/")) {
				return Category.INTERFACE;
			}
			if (sub.startsWith("particle/")) {
				return Category.PARTICLES;
			}
			if (sub.startsWith("environment/") || sub.startsWith("colormap/") || sub.startsWith("misc/") || sub.startsWith("effect/")) {
				return Category.ENVIRONMENT;
			}
			if (sub.startsWith("painting/")) {
				return Category.PAINTINGS;
			}
			if (sub.startsWith("mob_effect/")) {
				return Category.EFFECTS;
			}
			if (sub.startsWith("trims/") || sub.startsWith("models/")) {
				return Category.ARMOR;
			}
			if (sub.startsWith("font/")) {
				return Category.FONTS;
			}
			return Category.OTHER_TEXTURES;
		}
		if (p.startsWith("models/")) {
			return Category.MODELS;
		}
		if (p.startsWith("blockstates/")) {
			return Category.BLOCKSTATES;
		}
		if (p.startsWith("items/")) {
			return Category.ITEM_DEFINITIONS;
		}
		if (p.startsWith("sounds/") || p.equals("sounds.json")) {
			return Category.SOUNDS;
		}
		if (p.startsWith("lang/")) {
			return Category.LANGUAGES;
		}
		if (p.startsWith("shaders/") || p.startsWith("post_effect/")) {
			return Category.SHADERS;
		}
		if (p.startsWith("texts/")) {
			return Category.SPLASHES;
		}
		if (p.startsWith("font/")) {
			return Category.FONTS;
		}
		return Category.OTHER;
	}

	/** Scans a folder pack (everything under assets/, .mcmeta files folded into their PNG). */
	public static PackContents scan(Path root) {
		PackContents out = new PackContents(root);
		Path assets = root.resolve("assets");
		if (!Files.isDirectory(assets)) {
			return out;
		}
		try (Stream<Path> walk = Files.walk(assets)) {
			walk.filter(Files::isRegularFile).forEach(file -> {
				Path rel = assets.relativize(file);
				if (rel.getNameCount() < 2) {
					return;
				}
				String ns = rel.getName(0).toString();
				String path = rel.subpath(1, rel.getNameCount()).toString().replace('\\', '/');
				if (path.endsWith(".png.mcmeta")) {
					return;
				}
				boolean texture = path.endsWith(".png");
				Category category = categorize(path);
				if (texture && !category.textures && category != Category.FONTS) {
					category = Category.OTHER_TEXTURES;
				}
				boolean animated = texture && isAnimated(file.resolveSibling(file.getFileName() + ".mcmeta"));
				long bytes;
				try {
					bytes = Files.size(file);
				} catch (IOException e) {
					bytes = 0;
				}
				out.items.get(category).add(new Item(ns + ":" + path, category, displayName(path), texture, animated, bytes));
			});
		} catch (IOException ignored) {
			// Partly listed.
		}
		for (List<Item> list : out.items.values()) {
			list.sort(Comparator.comparing(Item::path));
		}
		return out;
	}

	private static boolean isAnimated(Path meta) {
		if (!Files.exists(meta)) {
			return false;
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
			return json.has("animation");
		} catch (Exception e) {
			return false;
		}
	}

	/** The game's loaded textures, sorted into the same categories (for "override from the game"). */
	public static Map<Category, List<Item>> gameTextures() {
		Map<Category, List<Item>> out = new EnumMap<>(Category.class);
		for (Category c : Category.values()) {
			out.put(c, new ArrayList<>());
		}
		List<String> metas = PackBridge.listGame("textures", ".png.mcmeta");
		java.util.Set<String> animated = new java.util.HashSet<>();
		for (String m : metas) {
			animated.add(m.substring(0, m.length() - ".mcmeta".length()));
		}
		for (String key : PackBridge.listGame("textures", ".png")) {
			String path = key.substring(key.indexOf(':') + 1);
			Category category = categorize(path);
			out.get(category).add(new Item(key, category, displayName(path), true, animated.contains(key), 0));
		}
		for (List<Item> list : out.values()) {
			list.sort(Comparator.comparing(Item::key));
		}
		return out;
	}

	public static String displayName(String path) {
		String file = path.substring(path.lastIndexOf('/') + 1);
		int dot = file.indexOf('.');
		if (dot > 0) {
			file = file.substring(0, dot);
		}
		String[] words = file.replace('-', '_').split("_");
		StringBuilder sb = new StringBuilder();
		for (String w : words) {
			if (w.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return sb.length() == 0 ? file : sb.toString();
	}

	/** The file of an asset inside a pack folder. */
	public static Path file(Path packRoot, String key) {
		int colon = key.indexOf(':');
		return packRoot.resolve("assets").resolve(key.substring(0, colon)).resolve(key.substring(colon + 1));
	}

	/** Reads an asset: from the pack folder first, else whatever the game has loaded. */
	public static byte[] read(Path packRoot, String key) {
		if (packRoot != null) {
			Path file = file(packRoot, key);
			if (Files.isRegularFile(file)) {
				try {
					return Files.readAllBytes(file);
				} catch (IOException ignored) {
					// Fall back to the game's copy.
				}
			}
		}
		return PackBridge.readGame(key);
	}

	public int count() {
		return items.values().stream().mapToInt(List::size).sum();
	}
}
