package net.veloclient.launcher.doctor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads what every jar in a mods folder is: its fabric.mod.json (id, version, what it needs, breaks, provides, its
 * mixin configs), the mods nested inside it (Fabric API carries ~100), the Java packages it owns (to blame stack
 * frames on a mod) - or that it's a Forge / NeoForge / Quilt-only mod, or not a readable jar at all. Results are
 * cached per file (path + size + modified time), so re-checking after every change costs almost nothing.
 */
final class ModScanner {

	/** One jar (or a mod nested in one). */
	record ModInfo(Path file, boolean enabled, String id, String name, String version, String environment,
			Map<String, JsonElement> depends, Map<String, JsonElement> breaks, Map<String, JsonElement> conflicts,
			Set<String> provides, Set<String> mixinConfigs, Set<String> packages, List<ModInfo> nested, Problem problem) {

		String displayName() {
			return name != null && !name.isBlank() ? name : id != null ? id : file.getFileName().toString();
		}

		/** Every id this jar makes available: its own, its "provides", and everything nested. */
		Set<String> allIds() {
			Set<String> out = new LinkedHashSet<>();
			if (id != null) {
				out.add(id);
			}
			out.addAll(provides);
			for (ModInfo n : nested) {
				out.addAll(n.allIds());
			}
			return out;
		}
	}

	enum Problem { NONE, NOT_A_JAR, FORGE, NEOFORGE, QUILT_ONLY, NO_METADATA }

	private record Cached(long size, long modified, ModInfo info) {
	}

	private static final Map<Path, Cached> CACHE = new ConcurrentHashMap<>();
	private static final String DISABLED = ".disabled";

	private ModScanner() {
	}

	static List<ModInfo> scan(Path modsDir) {
		List<ModInfo> out = new ArrayList<>();
		try (Stream<Path> files = Files.list(modsDir)) {
			for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
				String name = file.getFileName().toString();
				boolean enabled = name.endsWith(".jar");
				if (!enabled && !name.endsWith(".jar" + DISABLED)) {
					continue;
				}
				out.add(read(file, enabled));
			}
		} catch (IOException ignored) {
			// No mods folder yet.
		}
		return out;
	}

	/** One jar outside the mods folder (Fabric Loader itself: it carries MixinExtras). */
	static ModInfo readJar(Path file) {
		return read(file, true);
	}

	private static ModInfo read(Path file, boolean enabled) {
		try {
			long size = Files.size(file);
			long modified = Files.getLastModifiedTime(file).toMillis();
			Cached cached = CACHE.get(file);
			if (cached != null && cached.size() == size && cached.modified() == modified) {
				return cached.info();
			}
			ModInfo info;
			try (InputStream in = Files.newInputStream(file)) {
				info = parse(file, enabled, in, 0);
			}
			CACHE.put(file, new Cached(size, modified, info));
			return info;
		} catch (IOException e) {
			return broken(file, enabled, Problem.NOT_A_JAR);
		}
	}

	private static ModInfo broken(Path file, boolean enabled, Problem problem) {
		return new ModInfo(file, enabled, null, null, null, null, Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), List.of(), problem);
	}

	private static ModInfo parse(Path file, boolean enabled, InputStream raw, int depth) throws IOException {
		Map<String, byte[]> wanted = new HashMap<>();
		Set<String> packages = new LinkedHashSet<>();
		boolean anyEntry = false;
		boolean forge = false;
		boolean neoforge = false;
		boolean quilt = false;
		try (ZipInputStream zip = new ZipInputStream(raw)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				anyEntry = true;
				String name = entry.getName();
				if (name.equals("fabric.mod.json")) {
					wanted.put(name, zip.readAllBytes());
				} else if (name.equals("META-INF/mods.toml")) {
					forge = true;
				} else if (name.equals("META-INF/neoforge.mods.toml")) {
					neoforge = true;
				} else if (name.equals("quilt.mod.json")) {
					quilt = true;
				} else if (name.startsWith("META-INF/jars/") && name.endsWith(".jar") && depth < 3) {
					wanted.put(name, zip.readAllBytes());
				} else if (name.endsWith(".class") && depth == 0) {
					int last = name.lastIndexOf('/');
					if (last > 0) {
						// Three levels ("net/caffeinemc/mods") are enough to tell mods apart without listing every package.
						String[] parts = name.substring(0, last).split("/");
						packages.add(String.join(".", java.util.Arrays.copyOf(parts, Math.min(parts.length, 3))));
					}
				}
			}
		} catch (java.util.zip.ZipException e) {
			return broken(file, enabled, Problem.NOT_A_JAR);
		}
		if (!anyEntry) {
			return broken(file, enabled, Problem.NOT_A_JAR);
		}
		byte[] fmj = wanted.get("fabric.mod.json");
		if (fmj == null) {
			return broken(file, enabled, neoforge ? Problem.NEOFORGE : forge ? Problem.FORGE : quilt ? Problem.QUILT_ONLY : Problem.NO_METADATA);
		}
		JsonObject json;
		try {
			// Some mods ship comments or trailing commas - Gson's lenient reader copes.
			json = JsonParser.parseString(new String(fmj, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (RuntimeException e) {
			return broken(file, enabled, Problem.NO_METADATA);
		}
		List<ModInfo> nested = new ArrayList<>();
		if (json.has("jars") && json.get("jars").isJsonArray()) {
			for (JsonElement jar : json.getAsJsonArray("jars")) {
				if (jar.isJsonObject() && jar.getAsJsonObject().has("file")) {
					byte[] bytes = wanted.get(jar.getAsJsonObject().get("file").getAsString());
					if (bytes != null) {
						ModInfo child = parse(file, enabled, new ByteArrayInputStream(bytes), depth + 1);
						if (child.id() != null) {
							nested.add(child);
						}
					}
				}
			}
		}
		Set<String> mixins = new LinkedHashSet<>();
		if (json.has("mixins") && json.get("mixins").isJsonArray()) {
			for (JsonElement m : json.getAsJsonArray("mixins")) {
				if (m.isJsonPrimitive()) {
					mixins.add(m.getAsString());
				} else if (m.isJsonObject() && m.getAsJsonObject().has("config")) {
					mixins.add(m.getAsJsonObject().get("config").getAsString());
				}
			}
		}
		Set<String> provides = new LinkedHashSet<>();
		if (json.has("provides") && json.get("provides").isJsonArray()) {
			json.getAsJsonArray("provides").forEach(p -> provides.add(p.getAsString()));
		}
		return new ModInfo(file, enabled, str(json, "id"), str(json, "name"), str(json, "version"), str(json, "environment"),
				map(json, "depends"), map(json, "breaks"), map(json, "conflicts"), provides, mixins, packages, nested, Problem.NONE);
	}

	private static String str(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}

	private static Map<String, JsonElement> map(JsonObject json, String key) {
		if (!json.has(key) || !json.get(key).isJsonObject()) {
			return Map.of();
		}
		Map<String, JsonElement> out = new HashMap<>();
		json.getAsJsonObject(key).entrySet().forEach(e -> out.put(e.getKey(), e.getValue()));
		return out;
	}
}
