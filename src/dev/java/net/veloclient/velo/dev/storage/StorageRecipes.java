package net.veloclient.velo.dev.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every vanilla recipe, read straight from the game jar's built-in data pack ({@code data/minecraft/recipe}) - since
 * 1.21.2 the client only gets the recipes you've unlocked, this way locked ones show too. Tags in ingredients
 * ({@code #minecraft:planks}) are resolved from the jar's item tags. Loaded once, in the background.
 */
public final class StorageRecipes {

	/** One recipe slot: any of {@code options}; {@code label} names a tag ("Any Planks"). */
	public record Ingredient(List<String> options, String label) {
	}

	/**
	 * {@code kind}: crafting, smelting, blasting, smoking, campfire, stonecutting, smithing. Shaped crafting lists its
	 * grid row by row ({@code width} cells per row, null = empty); everything else lists its inputs in order.
	 */
	public record Recipe(String id, String kind, String result, int count, List<Ingredient> inputs, int width, boolean shaped) {

		/** The block you make it at. */
		public String station() {
			return switch (kind) {
				case "smelting" -> "minecraft:furnace";
				case "blasting" -> "minecraft:blast_furnace";
				case "smoking" -> "minecraft:smoker";
				case "campfire" -> "minecraft:campfire";
				case "stonecutting" -> "minecraft:stonecutter";
				case "smithing" -> "minecraft:smithing_table";
				default -> (shaped ? width > 2 || inputs.size() / Math.max(1, width) > 2 : inputs.size() > 4) ? "minecraft:crafting_table" : null;
			};
		}

		/** How many of each distinct ingredient one craft takes. */
		public Map<Ingredient, Integer> needs() {
			Map<Ingredient, Integer> needs = new LinkedHashMap<>();
			for (Ingredient ingredient : inputs) {
				if (ingredient != null) {
					needs.merge(ingredient, 1, Integer::sum);
				}
			}
			return needs;
		}
	}

	private static volatile Map<String, List<Recipe>> byResult;
	private static volatile boolean loading;
	private static final Map<String, List<String>> TAGS = new HashMap<>();

	private StorageRecipes() {
	}

	/** Recipes that make {@code itemId} (empty while still loading). */
	public static List<Recipe> forItem(String itemId) {
		Map<String, List<Recipe>> map = byResult;
		if (map == null) {
			loadAsync();
			return List.of();
		}
		return map.getOrDefault(itemId, List.of());
	}

	public static boolean ready() {
		return byResult != null;
	}

	public static void loadAsync() {
		if (byResult != null || loading) {
			return;
		}
		loading = true;
		Thread thread = new Thread(() -> {
			try {
				byResult = load();
			} catch (Throwable t) {
				byResult = Map.of();
			} finally {
				loading = false;
			}
		}, "Velo storage recipes");
		thread.setDaemon(true);
		thread.start();
	}

	private static Path dataRoot() {
		Path root = FabricLoader.getInstance().getModContainer("minecraft").flatMap(c -> c.findPath("data/minecraft")).orElse(null);
		if (root != null) {
			return root;
		}
		// Fallback: find the jar through a recipe file on the classpath.
		try {
			var url = StorageRecipes.class.getClassLoader().getResource("data/minecraft/recipe/stick.json");
			if (url == null) {
				return null;
			}
			return Path.of(url.toURI()).getParent().getParent();
		} catch (java.nio.file.FileSystemNotFoundException e) {
			try {
				var url = StorageRecipes.class.getClassLoader().getResource("data/minecraft/recipe/stick.json");
				var fs = java.nio.file.FileSystems.newFileSystem(url.toURI(), Map.of());
				return fs.provider().getPath(url.toURI()).getParent().getParent();
			} catch (Exception ignored) {
				return null;
			}
		} catch (Exception e) {
			return null;
		}
	}

	private static Map<String, List<Recipe>> load() throws Exception {
		Path root = dataRoot();
		Map<String, List<Recipe>> map = new HashMap<>();
		if (root == null) {
			return map;
		}
		Path recipes = root.resolve("recipe");
		List<Path> files;
		try (Stream<Path> walk = Files.walk(recipes)) {
			files = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
		}
		for (Path file : files) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				String name = file.getFileName().toString().replace(".json", "");
				Recipe recipe = parse(root, "minecraft:" + name, json);
				if (recipe != null) {
					map.computeIfAbsent(recipe.result(), k -> new ArrayList<>()).add(recipe);
				}
			} catch (Exception ignored) {
				// a recipe format we don't know: skip it
			}
		}
		// Crafting first, then the cooking / cutting ways.
		for (List<Recipe> list : map.values()) {
			list.sort((a, b) -> Integer.compare(order(a.kind()), order(b.kind())));
		}
		return map;
	}

	private static int order(String kind) {
		return switch (kind) {
			case "crafting" -> 0;
			case "smithing" -> 1;
			case "stonecutting" -> 2;
			case "smelting" -> 3;
			case "blasting" -> 4;
			case "smoking" -> 5;
			default -> 6;
		};
	}

	private static Recipe parse(Path root, String id, JsonObject json) {
		String type = json.has("type") ? json.get("type").getAsString().replace("minecraft:", "") : "";
		String[] result = result(json.get("result"));
		if (result == null) {
			return null;
		}
		String out = result[0];
		int count = Integer.parseInt(result[1]);
		switch (type) {
			case "crafting_shaped" -> {
				JsonArray pattern = json.getAsJsonArray("pattern");
				JsonObject key = json.getAsJsonObject("key");
				int width = 0;
				for (JsonElement row : pattern) {
					width = Math.max(width, row.getAsString().length());
				}
				List<Ingredient> grid = new ArrayList<>();
				Map<Character, Ingredient> keys = new HashMap<>();
				for (JsonElement rowElement : pattern) {
					String row = rowElement.getAsString();
					for (int i = 0; i < width; i++) {
						char c = i < row.length() ? row.charAt(i) : ' ';
						if (c == ' ' || !key.has(String.valueOf(c))) {
							grid.add(null);
						} else {
							grid.add(keys.computeIfAbsent(c, k -> ingredient(root, key.get(String.valueOf(k)))));
						}
					}
				}
				return new Recipe(id, "crafting", out, count, grid, width, true);
			}
			case "crafting_shapeless" -> {
				List<Ingredient> inputs = new ArrayList<>();
				for (JsonElement element : json.getAsJsonArray("ingredients")) {
					inputs.add(ingredient(root, element));
				}
				return new Recipe(id, "crafting", out, count, inputs, 3, false);
			}
			case "crafting_transmute" -> {
				return new Recipe(id, "crafting", out, count, List.of(ingredient(root, json.get("input")), ingredient(root, json.get("material"))), 3, false);
			}
			case "crafting_imbue" -> {
				Ingredient material = ingredient(root, json.get("material"));
				Ingredient source = ingredient(root, json.get("source"));
				List<Ingredient> inputs = new ArrayList<>();
				for (int i = 0; i < 9; i++) {
					inputs.add(i == 4 ? source : material);
				}
				return new Recipe(id, "crafting", out, count, inputs, 3, true);
			}
			case "smelting", "blasting", "smoking", "stonecutting" -> {
				return new Recipe(id, type, out, count, List.of(ingredient(root, json.get("ingredient"))), 1, false);
			}
			case "campfire_cooking" -> {
				return new Recipe(id, "campfire", out, count, List.of(ingredient(root, json.get("ingredient"))), 1, false);
			}
			case "smithing_transform" -> {
				return new Recipe(id, "smithing", out, count, List.of(ingredient(root, json.get("template")), ingredient(root, json.get("base")),
						ingredient(root, json.get("addition"))), 3, false);
			}
			default -> {
				return null;
			}
		}
	}

	/** {id, count} from {"id": .., "count": ..}, {"item": ..} or a plain id. */
	private static String[] result(JsonElement element) {
		if (element == null) {
			return null;
		}
		if (element.isJsonPrimitive()) {
			return new String[] {element.getAsString(), "1"};
		}
		JsonObject object = element.getAsJsonObject();
		String id = object.has("id") ? object.get("id").getAsString() : object.has("item") ? object.get("item").getAsString() : null;
		if (id == null) {
			return null;
		}
		int count = object.has("count") ? object.get("count").getAsInt() : 1;
		return new String[] {id, String.valueOf(count)};
	}

	private static Ingredient ingredient(Path root, JsonElement element) {
		if (element == null) {
			return new Ingredient(List.of(), "?");
		}
		Set<String> options = new LinkedHashSet<>();
		String label = null;
		List<JsonElement> parts = new ArrayList<>();
		if (element.isJsonArray()) {
			element.getAsJsonArray().forEach(parts::add);
		} else {
			parts.add(element);
		}
		for (JsonElement part : parts) {
			String value;
			if (part.isJsonObject()) {
				JsonObject object = part.getAsJsonObject();
				value = object.has("item") ? object.get("item").getAsString() : object.has("tag") ? "#" + object.get("tag").getAsString() : null;
			} else {
				value = part.getAsString();
			}
			if (value == null) {
				continue;
			}
			if (value.startsWith("#")) {
				String tag = value.substring(1);
				options.addAll(tag(root, tag, 0));
				label = "Any " + StorageStore.pretty(tag);
			} else {
				options.add(value.contains(":") ? value : "minecraft:" + value);
			}
		}
		return new Ingredient(List.copyOf(options), label);
	}

	private static List<String> tag(Path root, String tag, int depth) {
		synchronized (TAGS) {
			List<String> cached = TAGS.get(tag);
			if (cached != null) {
				return cached;
			}
		}
		List<String> items = new ArrayList<>();
		String path = tag.contains(":") ? tag.substring(tag.indexOf(':') + 1) : tag;
		Path file = root.resolve("tags").resolve("item").resolve(path + ".json");
		if (depth < 8 && Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				for (JsonElement element : json.getAsJsonArray("values")) {
					String value = element.isJsonObject() ? element.getAsJsonObject().get("id").getAsString() : element.getAsString();
					if (value.startsWith("#")) {
						for (String nested : tag(root, value.substring(1), depth + 1)) {
							if (!items.contains(nested)) {
								items.add(nested);
							}
						}
					} else if (!items.contains(value)) {
						items.add(value);
					}
				}
			} catch (Exception ignored) {
				// leave it empty
			}
		}
		synchronized (TAGS) {
			TAGS.put(tag, items);
		}
		return items;
	}
}
