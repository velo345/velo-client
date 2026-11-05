package net.veloclient.launcher.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * NoRisk Client (launcher v3) doesn't keep a profile's mods in its {@code mods/} folder: they're downloaded once into
 * {@code noriskclientv3/meta/mod_cache} and handed to the game at launch. Two lists decide which:
 * <ul>
 * <li>the profile's NoRisk pack ({@code norisk_modpacks.json}, "norisk-prod" by default): Sodium, Simple Voice Chat, ...,
 * cached as {@code <mod id>-<version identifier>.jar} per Minecraft version;</li>
 * <li>mods the player added, rows of {@code profile_mods} in {@code meta/app.db} (SQLite) naming a cached file.</li>
 * </ul>
 * NoRisk's own client jars ({@code nrc-*}) never come along. Read-only, best effort: anything unreadable gives fewer jars.
 */
final class NoRiskMods {

	private static final String DEFAULT_PACK = "norisk-prod";
	private static final Pattern JAR_NAME = Pattern.compile("[A-Za-z0-9_.+\\-]{3,200}\\.jar");

	private NoRiskMods() {
	}

	/** A NoRisk profile as its app.db knows it. {@code dir}: its game folder (several profiles can share one). */
	record Profile(String id, String name, Path dir, String mcVersion, String loader, String pack) {
	}

	/** Marks a per-profile import source: {@code <game dir>/mods/.velo-norisk-<profile id>} (never a real folder). */
	static final String MARKER = ".velo-norisk-";

	static Path sourceModsDir(Profile profile) {
		return profile.dir().resolve("mods").resolve(MARKER + profile.id());
	}

	/** Every profile in {@code root}'s app.db whose folder exists, or empty when the database can't be read. */
	static List<Profile> profiles(Path root) {
		List<Profile> out = new ArrayList<>();
		try {
			SqliteReader db = new SqliteReader(root.resolve("meta").resolve("app.db"));
			for (Map<String, Object> row : db.table("profiles")) {
				String path = str(row.get("path"));
				if (path == null || str(row.get("id")) == null) {
					continue;
				}
				Path dir = root.resolve("profiles").resolve(path).normalize();
				if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
					continue;
				}
				out.add(new Profile(str(row.get("id")), str(row.get("name")), dir, str(row.get("game_version")),
						str(row.get("loader")), str(row.get("selected_norisk_pack_id"))));
			}
		} catch (IOException | RuntimeException e) {
			net.veloclient.launcher.LauncherLog.info("NoRisk app.db unreadable (" + e.getMessage() + ") - importing by folder");
		}
		return out;
	}

	private static String str(Object o) {
		return o instanceof String s && !s.isBlank() ? s : null;
	}

	/** The NoRisk v3 data folder above a profile's mods folder, or null when it isn't one. */
	static Path rootOf(Path modsDir) {
		Path p = modsDir == null ? null : modsDir.toAbsolutePath().normalize();
		for (int i = 0; p != null && i < 6; i++, p = p.getParent()) {
			if (Files.isDirectory(p.resolve("meta").resolve("mod_cache")) && Files.isRegularFile(p.resolve("norisk_modpacks.json"))) {
				return p;
			}
		}
		return null;
	}

	/** The jars a NoRisk profile really plays with, beyond what's in its mods folder. Empty when {@code modsDir} isn't NoRisk's. */
	static List<Path> jars(Path modsDir) {
		Path root = rootOf(modsDir);
		if (root == null) {
			return List.of();
		}
		String marker = modsDir.getFileName() == null ? "" : modsDir.getFileName().toString();
		if (marker.startsWith(MARKER)) {
			String id = marker.substring(MARKER.length());
			for (Profile profile : profiles(root)) {
				if (profile.id().equals(id)) {
					return profileJars(root, profile);
				}
			}
			return List.of();
		}
		Path cache = root.resolve("meta").resolve("mod_cache");
		Path profile = modsDir.toAbsolutePath().normalize().getParent();
		Set<Path> out = new LinkedHashSet<>();
		// The profile's own extra folders (subfolders like mods/nrc-26.2-fabric/, custom_mods/).
		for (Path dir : List.of(profile.resolve("custom_mods"))) {
			out.addAll(plainJars(dir));
		}
		try (Stream<Path> subs = Files.list(modsDir)) {
			subs.filter(Files::isDirectory).forEach(d -> out.addAll(plainJars(d)));
		} catch (IOException | RuntimeException ignored) {
			// no mods folder
		}
		String mc = lastPlayedVersion(profile);
		out.addAll(packJars(root, cache, mc));
		out.addAll(addedJars(root, cache));
		List<Path> list = new ArrayList<>();
		for (Path jar : out) {
			if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("nrc-")) {
				list.add(jar);
			}
		}
		return list;
	}

	/**
	 * What NoRisk itself starts this profile with: its pack's mods for its version and loader (minus the ones the player
	 * switched off), its own enabled mods (cached by file name), and its custom_mods folder. Never nrc-* (NoRisk's client).
	 */
	static List<Path> profileJars(Path root, Profile profile) {
		return profileJars(root, profile, null);
	}

	static List<Path> profileJars(Path root, Profile profile, List<String> missing) {
		Path cache = root.resolve("meta").resolve("mod_cache");
		Set<Path> out = new LinkedHashSet<>();
		java.util.Set<String> disabled = new HashSet<>();
		List<Map<String, Object>> own = List.of();
		try {
			SqliteReader db = new SqliteReader(root.resolve("meta").resolve("app.db"));
			for (Map<String, Object> row : db.table("profile_disabled_norisk_mods")) {
				if (profile.id().equals(row.get("profile_id")) && (profile.pack() == null || profile.pack().equals(row.get("pack_id")))
						&& (row.get("game_version") == null || row.get("game_version").equals(profile.mcVersion()))) {
					disabled.add(String.valueOf(row.get("mod_id")));
				}
			}
			own = db.table("profile_mods");
		} catch (IOException | RuntimeException ignored) {
			// pack only
		}
		if (profile.pack() != null) {
			out.addAll(packJars(root, cache, profile.pack(), profile.mcVersion(), profile.loader() == null ? "fabric" : profile.loader(), disabled,
					missing));
		}
		for (Map<String, Object> row : own) {
			if (!profile.id().equals(row.get("profile_id")) || Long.valueOf(0).equals(row.get("enabled"))) {
				continue;
			}
			String file = str(row.get("file_name_override")) != null ? str(row.get("file_name_override")) : str(row.get("file_name"));
			if (file == null || file.contains("/") || file.contains("\\")) {
				continue;
			}
			for (Path candidate : List.of(cache.resolve(file), profile.dir().resolve("custom_mods").resolve(file), profile.dir().resolve("mods").resolve(file))) {
				if (Files.isRegularFile(candidate)) {
					out.add(candidate);
					break;
				}
			}
		}
		out.addAll(plainJars(profile.dir().resolve("custom_mods")));
		out.addAll(plainJars(profile.dir().resolve("mods")));
		List<Path> list = new ArrayList<>();
		for (Path jar : out) {
			if (!jar.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("nrc-")) {
				list.add(jar);
			}
		}
		return list;
	}

	/** Velo's own cache for pack mods NoRisk never downloaded (by Modrinth version id). */
	private static Path fetchedDir() {
		return VeloPaths.root().resolve("cache").resolve("norisk-import");
	}

	/** How many mods the profile's NoRisk setup has, counting the ones only Modrinth has yet. */
	static int count(Path modsDir) {
		Profile profile = profileOf(modsDir);
		if (profile == null) {
			return jars(modsDir).size();
		}
		List<String> missing = new ArrayList<>();
		return profileJars(rootOf(modsDir), profile, missing).size() + missing.size();
	}

	/** Downloads the pack mods NoRisk never fetched for this profile (before an import). Best effort. */
	static void fetchMissing(Path modsDir, java.util.function.Consumer<String> progress) {
		Profile profile = profileOf(modsDir);
		if (profile == null) {
			return;
		}
		List<String> missing = new ArrayList<>();
		profileJars(rootOf(modsDir), profile, missing);
		int i = 0;
		for (String versionId : missing) {
			progress.accept("Fetching NoRisk's mods from Modrinth " + (++i) + " / " + missing.size());
			try {
				var version = net.veloclient.launcher.modrinth.ModrinthClient.getVersion(versionId).orElse(null);
				var file = version == null ? null : version.primaryFile().orElse(null);
				if (file != null) {
					Path dir = fetchedDir().resolve(versionId);
					Files.createDirectories(dir);
					String name = Path.of(file.filename() == null || file.filename().isBlank() ? versionId + ".jar" : file.filename()).getFileName().toString();
					net.veloclient.launcher.launch.Downloader.ensure(java.net.URI.create(file.url()), dir.resolve(name),
							file.sha1(), file.size(), b -> { });
				}
			} catch (IOException | RuntimeException e) {
				net.veloclient.launcher.LauncherLog.info("NoRisk import: couldn't fetch Modrinth version " + versionId + ": " + e.getMessage());
			}
		}
	}

	private static Profile profileOf(Path modsDir) {
		Path root = rootOf(modsDir);
		String marker = modsDir == null || modsDir.getFileName() == null ? "" : modsDir.getFileName().toString();
		if (root == null || !marker.startsWith(MARKER)) {
			return null;
		}
		String id = marker.substring(MARKER.length());
		return profiles(root).stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
	}

	private static List<Path> plainJars(Path dir) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar") && Files.isRegularFile(p)).sorted().toList();
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
	}

	/** {@code optionsNRC.json} remembers which Minecraft version the profile ran last ("26.2"). */
	static String lastPlayedVersion(Path profile) {
		try {
			Path file = profile.resolve("optionsNRC.json");
			if (Files.isRegularFile(file)) {
				JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				JsonObject meta = json.has("_meta") ? json.getAsJsonObject("_meta") : null;
				if (meta != null && meta.has("lastLaunchedVersion")) {
					return meta.get("lastLaunchedVersion").getAsString();
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// unreadable
		}
		return null;
	}

	/** The pack's Fabric mods that are in the cache - for {@code mc} when known, else any cached version of each. */
	private static List<Path> packJars(Path root, Path cache, String mc) {
		return packJars(root, cache, DEFAULT_PACK, mc, "fabric", java.util.Set.of());
	}

	private static List<Path> packJars(Path root, Path cache, String packId, String mc, String loader, java.util.Set<String> disabled) {
		return packJars(root, cache, packId, mc, loader, disabled, null);
	}

	/** Modrinth versions the profile's pack needs that no cache has yet ({@link #fetchMissing}). */
	private static List<Path> packJars(Path root, Path cache, String packId, String mc, String loader, java.util.Set<String> disabled,
			List<String> missing) {
		JsonObject packs;
		try {
			packs = JsonParser.parseString(Files.readString(root.resolve("norisk_modpacks.json"), StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonObject("packs");
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
		if (packs == null || !packs.has(packId)) {
			return List.of();
		}
		// Mods of the pack and the ones it inherits from, minus what it excludes.
		Map<String, JsonObject> mods = new LinkedHashMap<>();
		Set<String> excluded = new HashSet<>();
		Set<String> seen = new HashSet<>();
		String id = packId;
		while (id != null && seen.add(id) && packs.has(id)) {
			JsonObject pack = packs.getAsJsonObject(id);
			if (pack.has("excludeMods") && pack.get("excludeMods").isJsonArray()) {
				pack.getAsJsonArray("excludeMods").forEach(e -> excluded.add(e.getAsString()));
			}
			if (pack.has("mods") && pack.get("mods").isJsonArray()) {
				for (JsonElement e : pack.getAsJsonArray("mods")) {
					JsonObject mod = e.getAsJsonObject();
					mods.putIfAbsent(mod.get("id").getAsString(), mod);
				}
			}
			JsonElement parent = pack.get("inheritsFrom");
			id = parent != null && parent.isJsonPrimitive() ? parent.getAsString() : null;
		}
		List<Path> out = new ArrayList<>();
		for (Map.Entry<String, JsonObject> entry : mods.entrySet()) {
			if (excluded.contains(entry.getKey()) || disabled.contains(entry.getKey()) || entry.getKey().startsWith("nrc-")) {
				continue;
			}
			JsonObject compat = entry.getValue().has("compatibility") ? entry.getValue().getAsJsonObject("compatibility") : null;
			if (compat == null) {
				continue;
			}
			Path found = null;
			List<String> versions = new ArrayList<>();
			if (mc != null && compat.has(mc)) {
				versions.add(mc);
			}
			if (found == null && (mc == null || !compat.has(mc))) {
				versions.addAll(compat.keySet());
			}
			for (String v : versions) {
				JsonObject loaders = compat.getAsJsonObject(v);
				JsonObject fabric = loaders != null && loaders.has(loader) ? loaders.getAsJsonObject(loader) : null;
				if (fabric == null) {
					continue;
				}
				String file = fabric.has("filename") && fabric.get("filename").isJsonPrimitive() ? fabric.get("filename").getAsString()
						: fabric.has("identifier") && fabric.get("identifier").isJsonPrimitive()
								? entry.getKey() + "-" + fabric.get("identifier").getAsString() + ".jar" : null;
				if (file != null && Files.isRegularFile(cache.resolve(file))) {
					found = cache.resolve(file);
					break;
				}
				// Cached under the Modrinth slug instead of the pack's id ("wavey-capes-<id>.jar" for "waveycapes").
				String ident = fabric.has("identifier") && fabric.get("identifier").isJsonPrimitive() ? fabric.get("identifier").getAsString() : null;
				if (ident != null && ident.length() >= 6) {
					try (Stream<Path> s = Files.list(cache)) {
						found = s.filter(p -> p.getFileName().toString().endsWith("-" + ident + ".jar")).findFirst().orElse(null);
					} catch (IOException ignored) {
						// unreadable cache
					}
					if (found != null) {
						break;
					}
				}
			}
			if (found != null) {
				out.add(found);
			} else {
				// Never downloaded by NoRisk (a profile not played yet): Velo fetches it from Modrinth at import, cached here.
				JsonObject src = entry.getValue().getAsJsonObject("source");
				JsonObject loaders = mc != null && compat.has(mc) ? compat.getAsJsonObject(mc) : null;
				JsonObject target = loaders != null && loaders.has(loader) ? loaders.getAsJsonObject(loader) : null;
				if (src != null && "modrinth".equals(str(src.get("type") == null ? null : src.get("type").getAsString()))
						&& target != null && target.has("identifier") && target.get("identifier").isJsonPrimitive()) {
					String versionId = target.get("identifier").getAsString();
					Path fetched = plainJars(fetchedDir().resolve(versionId)).stream().findFirst().orElse(null);
					if (fetched != null) {
						out.add(fetched);
					} else if (missing != null) {
						missing.add(versionId);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Mods the player added: their file names sit in {@code profile_mods} of the SQLite app.db (and its write-ahead log).
	 * There's no SQLite reader here, so the database is read as text and every cached jar it names counts - pack jars
	 * aren't stored there, and names that only appear in Modrinth's cached metadata aren't files in mod_cache.
	 */
	private static List<Path> addedJars(Path root, Path cache) {
		Set<String> names = new LinkedHashSet<>();
		for (String file : List.of("app.db", "app.db-wal")) {
			Path db = root.resolve("meta").resolve(file);
			try {
				if (Files.isRegularFile(db) && Files.size(db) < 256L * 1024 * 1024) {
					Matcher m = JAR_NAME.matcher(new String(Files.readAllBytes(db), StandardCharsets.ISO_8859_1));
					while (m.find()) {
						names.add(m.group());
					}
				}
			} catch (IOException | RuntimeException ignored) {
				// locked or unreadable
			}
		}
		List<Path> out = new ArrayList<>();
		for (String name : names) {
			Path jar = cache.resolve(name);
			if (!name.toLowerCase(Locale.ROOT).startsWith("nrc-") && Files.isRegularFile(jar)) {
				out.add(jar);
			}
		}
		return out;
	}
}
