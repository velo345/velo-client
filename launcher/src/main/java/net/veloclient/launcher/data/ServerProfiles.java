package net.veloclient.launcher.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.social.LauncherSocial;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mod rules for known servers (set by the Velo owner in the owner panel): which mods a server doesn't allow and which
 * it recommends. Only ever a warning - the player can start anyway and hide it per server, because a rule can be
 * wrong. Cached on disk, refreshed hourly.
 */
public final class ServerProfiles {

	public record Rule(String match, String value, String label) {
	}

	public record Profile(String id, String name, List<String> addresses, List<Rule> recommend, List<Rule> forbid, String note) {
	}

	/** One installed mod that breaks a rule. */
	public record Hit(Path file, String modName, String rule) {
	}

	public record Check(Profile profile, List<Hit> forbidden, List<Rule> missing) {
		public boolean clean() {
			return forbidden.isEmpty() && missing.isEmpty();
		}
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private static final long REFRESH_MS = 3600_000L;
	private static volatile List<Profile> profiles;
	private static volatile long fetchedAt;
	private static final Map<Path, String[]> JAR_IDS = new ConcurrentHashMap<>();

	private ServerProfiles() {
	}

	private static Path cache() {
		return VeloPaths.root().resolve("cache").resolve("server-profiles.json");
	}

	private static Path hiddenFile() {
		return VeloPaths.config().resolve("server-warnings-hidden.json");
	}

	public static List<Profile> all() {
		List<Profile> now = profiles;
		if (now == null) {
			try {
				now = Files.exists(cache()) ? parse(Files.readString(cache(), StandardCharsets.UTF_8)) : List.of();
				fetchedAt = Files.exists(cache()) ? Files.getLastModifiedTime(cache()).toMillis() : 0;
			} catch (Exception e) {
				now = List.of();
			}
			profiles = now;
		}
		if (System.currentTimeMillis() - fetchedAt > REFRESH_MS) {
			fetchedAt = System.currentTimeMillis();
			Thread.ofVirtual().start(ServerProfiles::refresh);
		}
		return now;
	}

	private static void refresh() {
		String base = LauncherSocial.baseUrl();
		if (base == null) {
			return;
		}
		try {
			HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/servers/profiles")).timeout(Duration.ofSeconds(15)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 200) {
				profiles = parse(r.body());
				Files.createDirectories(cache().getParent());
				Files.writeString(cache(), r.body(), StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			LauncherLog.info("Server profiles: " + e);
		}
	}

	private static List<Profile> parse(String json) {
		List<Profile> out = new ArrayList<>();
		for (JsonElement e : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("profiles")) {
			JsonObject o = e.getAsJsonObject();
			List<String> addresses = new ArrayList<>();
			o.getAsJsonArray("addresses").forEach(a -> addresses.add(a.getAsString()));
			out.add(new Profile(o.get("id").getAsString(), o.get("name").getAsString(), addresses, rules(o, "recommend"), rules(o, "forbid"),
					o.has("note") && !o.get("note").isJsonNull() ? o.get("note").getAsString() : null));
		}
		return out;
	}

	private static List<Rule> rules(JsonObject o, String key) {
		List<Rule> out = new ArrayList<>();
		if (o.has(key) && o.get(key).isJsonArray()) {
			for (JsonElement e : o.getAsJsonArray(key)) {
				JsonObject r = e.getAsJsonObject();
				out.add(new Rule(r.get("match").getAsString(), r.get("value").getAsString(),
						r.has("label") && !r.get("label").isJsonNull() ? r.get("label").getAsString() : r.get("value").getAsString()));
			}
		}
		return out;
	}

	/** The profile for a server address ("play.hypixel.net:25565"), or null. "*.hypixel.net" also matches hypixel.net itself. */
	public static Profile forAddress(String address) {
		if (address == null || address.isBlank()) {
			return null;
		}
		String a = address.strip().toLowerCase(Locale.ROOT);
		String host = a.replaceFirst(":\\d+$", "");
		for (Profile p : all()) {
			for (String pattern : p.addresses()) {
				String pat = pattern.toLowerCase(Locale.ROOT);
				boolean withPort = pat.contains(":");
				String subject = withPort ? a : host;
				if (pat.startsWith("*.") ? subject.endsWith(pat.substring(1)) || subject.equals(pat.substring(2)) : subject.equals(pat)) {
					return p;
				}
			}
		}
		return null;
	}

	/** Enabled mods the server doesn't allow, and recommended ones that aren't there. */
	public static Check check(Instance instance, Profile profile) {
		Map<String, InstalledAsset> assets = InstalledAssetStore.asMap(InstalledAssetStore.loadAll(instance.id(), InstalledAssetStore.Kind.MOD));
		List<Hit> forbidden = new ArrayList<>();
		Set<Rule> present = new LinkedHashSet<>();
		try (Stream<Path> files = Files.list(InstancePaths.modsDir(instance.id()))) {
			for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
				InstalledAsset asset = assets.get(file.getFileName().toString());
				String[] ids = jarIds(file);
				String name = asset != null ? asset.title() : ids[1] != null ? ids[1] : file.getFileName().toString();
				for (Rule rule : profile.forbid()) {
					if (matches(rule, file, asset, ids)) {
						forbidden.add(new Hit(file, name, rule.label()));
						break;
					}
				}
				for (Rule rule : profile.recommend()) {
					if (matches(rule, file, asset, ids)) {
						present.add(rule);
					}
				}
			}
		} catch (Exception ignored) {
			// No mods folder.
		}
		List<Rule> missing = profile.recommend().stream().filter(r -> !present.contains(r)).toList();
		return new Check(profile, forbidden, missing);
	}

	/** Rule labels that apply to this mod on any of the player's saved servers - for the mod list badges. */
	public static List<String> badgesFor(Path file, InstalledAsset asset) {
		List<String> out = new ArrayList<>();
		String[] ids = jarIds(file);
		Set<String> seen = new LinkedHashSet<>();
		for (SavedServer server : SavedServerStore.loadAll()) {
			Profile p = forAddress(server.host() + (server.port() == 25565 ? "" : ":" + server.port()));
			if (p == null || !seen.add(p.id()) || hidden(p.id())) {
				continue;
			}
			for (Rule rule : p.forbid()) {
				if (matches(rule, file, asset, ids)) {
					out.add("Not allowed on " + p.name());
					break;
				}
			}
		}
		return out;
	}

	static boolean matches(Rule rule, Path file, InstalledAsset asset, String[] ids) {
		String v = rule.value().toLowerCase(Locale.ROOT);
		return switch (rule.match()) {
			case "id" -> v.equals(ids[0]);
			case "modrinth" -> asset != null && (v.equalsIgnoreCase(asset.projectId()) || asset.title() != null && v.equals(asset.title().toLowerCase(Locale.ROOT).replace(' ', '-')));
			default -> file.getFileName().toString().toLowerCase(Locale.ROOT).contains(v) || ids[1] != null && ids[1].toLowerCase(Locale.ROOT).contains(v)
					|| asset != null && asset.title() != null && asset.title().toLowerCase(Locale.ROOT).contains(v);
		};
	}

	/** {fabric id, display name} of a jar (cached by path). */
	private static String[] jarIds(Path file) {
		return JAR_IDS.computeIfAbsent(file, f -> {
			try (ZipFile zip = new ZipFile(f.toFile())) {
				ZipEntry entry = zip.getEntry("fabric.mod.json");
				if (entry != null) {
					try (InputStream in = zip.getInputStream(entry)) {
						JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
						return new String[] {json.has("id") ? json.get("id").getAsString().toLowerCase(Locale.ROOT) : null,
								json.has("name") ? json.get("name").getAsString() : null};
					}
				}
			} catch (Exception ignored) {
				// Not readable.
			}
			return new String[] {null, null};
		});
	}

	public static boolean hidden(String profileId) {
		try {
			if (Files.exists(hiddenFile())) {
				JsonArray list = JsonParser.parseString(Files.readString(hiddenFile(), StandardCharsets.UTF_8)).getAsJsonArray();
				for (JsonElement e : list) {
					if (e.getAsString().equals(profileId)) {
						return true;
					}
				}
			}
		} catch (Exception ignored) {
			// Unreadable: warn.
		}
		return false;
	}

	public static void hide(String profileId) {
		try {
			JsonArray list = Files.exists(hiddenFile()) ? JsonParser.parseString(Files.readString(hiddenFile(), StandardCharsets.UTF_8)).getAsJsonArray() : new JsonArray();
			list.add(profileId);
			Files.createDirectories(hiddenFile().getParent());
			Files.writeString(hiddenFile(), list.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			LauncherLog.info("Server warnings: couldn't save: " + e);
		}
	}
}
