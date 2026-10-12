package net.veloclient.launcher.party;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.launch.GameVersion;
import net.veloclient.launcher.modrinth.ModrinthClient;

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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * "Use the leader's mods": the leader shares their profile's mod list (each jar identified by its
 * SHA-1 and resolved to a Modrinth download), members apply it to their own selected profile
 * <em>temporarily</em>. Applying moves the member's own mods to {@code party-backup/} inside that
 * profile, installs the leader's (from a shared download cache), and switches the profile's
 * Minecraft version if the leader plays another one; restoring puts everything back exactly. The
 * launcher's own jars (Velo Client, Velo Addons, Fabric API) are never touched - they're installed
 * per version at every launch anyway.
 */
public final class PartyModSync {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	/** Result of applying a sync: which of the leader's mods couldn't be downloaded (not on Modrinth). */
	public record Applied(int installed, List<String> missing) {
	}

	private PartyModSync() {
	}

	private static boolean managedByLauncher(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		return n.startsWith("velo-client-") || n.startsWith("velo-addons-") || n.startsWith("fabric-api-");
	}

	private static boolean isModFile(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return n.endsWith(".jar") || n.endsWith(".jar.disabled");
	}

	/** Leader side: the sync payload for {@code instance} (blocking - looks every jar up on Modrinth). */
	public static JsonObject buildSync(Instance instance, Consumer<String> status) throws IOException {
		Path mods = InstancePaths.modsDir(instance.id());
		JsonArray list = new JsonArray();
		if (Files.isDirectory(mods)) {
			List<Path> jars;
			try (Stream<Path> files = Files.list(mods)) {
				jars = files.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
						.filter(p -> !managedByLauncher(p.getFileName().toString())).sorted().toList();
			}
			int i = 0;
			for (Path jar : jars) {
				status.accept("Looking up " + jar.getFileName() + " (" + (++i) + "/" + jars.size() + ")");
				String sha1 = sha1(Files.readAllBytes(jar));
				JsonObject mod = new JsonObject();
				mod.addProperty("name", jar.getFileName().toString());
				mod.addProperty("file", jar.getFileName().toString());
				mod.addProperty("sha1", sha1);
				mod.addProperty("size", Files.size(jar));
				ModrinthClient.versionByFileSha1(sha1).ifPresent(version -> {
					mod.addProperty("projectId", version.projectId());
					mod.addProperty("versionId", version.id());
					for (ModrinthClient.VersionFile file : version.files()) {
						if (sha1.equalsIgnoreCase(file.sha1())) {
							mod.addProperty("url", file.url());
						}
					}
				});
				list.add(mod);
			}
		}
		JsonObject sync = new JsonObject();
		sync.addProperty("profile", instance.name());
		sync.addProperty("mcVersion", instance.mcVersion());
		sync.add("mods", list);
		return sync;
	}

	private static Path stateFile(Instance instance) {
		return InstancePaths.dir(instance.id()).resolve("party-sync.json");
	}

	private static Path backupDir(Instance instance) {
		return InstancePaths.dir(instance.id()).resolve("party-backup");
	}

	public static boolean isSynced(Instance instance) {
		return Files.exists(stateFile(instance));
	}

	/** {@code setAt} of the sync currently applied to {@code instance}, or 0. */
	public static long appliedSyncTime(Instance instance) {
		try {
			JsonObject state = GSON.fromJson(Files.readString(stateFile(instance), StandardCharsets.UTF_8), JsonObject.class);
			return state.has("setAt") ? state.get("setAt").getAsLong() : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	/** Every profile that still has a party sync applied (to restore after a launcher restart). */
	public static List<Instance> syncedInstances() {
		List<Instance> list = new ArrayList<>();
		for (Instance instance : InstanceStore.loadAll()) {
			if (isSynced(instance)) {
				list.add(instance);
			}
		}
		return list;
	}

	/** Member side: puts the leader's mods on {@code instance}, keeping the player's own safe. Blocking. */
	public static synchronized Applied apply(Instance instance, JsonObject sync, String leaderName, Consumer<String> status) throws IOException {
		if (isSynced(instance)) {
			restore(instance, status);
			String id = instance.id();
			instance = InstanceStore.loadAll().stream().filter(i -> i.id().equals(id)).findFirst().orElse(instance);
		}
		Path mods = InstancePaths.modsDir(instance.id());
		Path backup = backupDir(instance).resolve("mods");
		Files.createDirectories(mods);
		Files.createDirectories(backup);
		// 1. Download first - if that fails, nothing on the profile has changed yet.
		Path cache = VeloPaths.root().resolve("cache").resolve("party-mods");
		Files.createDirectories(cache);
		List<String> missing = new ArrayList<>();
		List<Path[]> toInstall = new ArrayList<>();
		JsonArray list = sync.has("mods") ? sync.getAsJsonArray("mods") : new JsonArray();
		int i = 0;
		for (JsonElement element : list) {
			JsonObject mod = element.getAsJsonObject();
			String file = str(mod, "file");
			String sha1 = str(mod, "sha1");
			String url = str(mod, "url");
			if (file == null || sha1 == null || file.contains("/") || file.contains("\\") || file.contains("..")) {
				continue;
			}
			Path cached = cache.resolve(sha1.toLowerCase(Locale.ROOT) + ".jar");
			if (!Files.exists(cached)) {
				if (url == null || !url.startsWith("https://cdn.modrinth.com/")) {
					missing.add(file);
					continue;
				}
				status.accept("Downloading " + file + " (" + (++i) + "/" + list.size() + ")");
				try {
					download(url, cached, sha1);
				} catch (IOException e) {
					LauncherLog.warn("Party sync: couldn't download " + file, e);
					missing.add(file);
					continue;
				}
			}
			toInstall.add(new Path[] {cached, mods.resolve(file)});
		}

		// 2. Move the player's own mods aside.
		status.accept("Backing up your mods");
		try (Stream<Path> files = Files.list(mods)) {
			for (Path own : files.filter(PartyModSync::isModFile).filter(p -> !managedByLauncher(p.getFileName().toString())).toList()) {
				Files.move(own, backup.resolve(own.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		// 3. Install the leader's.
		for (Path[] pair : toInstall) {
			Files.copy(pair[0], pair[1], StandardCopyOption.REPLACE_EXISTING);
		}
		// 4. Same Minecraft version as the leader, if it's one this launcher supports.
		String originalVersion = instance.mcVersion();
		String leaderVersion = str(sync, "mcVersion");
		if (leaderVersion != null && !leaderVersion.equals(originalVersion)) {
			try {
				GameVersion.byId(leaderVersion);
				InstanceStore.save(new Instance(instance.id(), instance.name(), leaderVersion, instance.icon(), instance.createdAtEpochMillis(),
						instance.ramMinMb(), instance.ramMaxMb(), instance.extraJvmArgs()));
			} catch (IllegalArgumentException e) {
				status.accept("The leader plays Minecraft " + leaderVersion + ", which this launcher doesn't support - keeping " + originalVersion);
			}
		}
		JsonObject state = new JsonObject();
		state.addProperty("originalMcVersion", originalVersion);
		state.addProperty("leader", leaderName);
		state.addProperty("profile", str(sync, "profile"));
		state.addProperty("setAt", sync.has("setAt") ? sync.get("setAt").getAsLong() : System.currentTimeMillis());
		state.addProperty("appliedAt", System.currentTimeMillis());
		Files.writeString(stateFile(instance), GSON.toJson(state), StandardCharsets.UTF_8);
		return new Applied(toInstall.size(), missing);
	}

	/** Puts the player's own mods and Minecraft version back. Blocking; safe to call when not synced. */
	public static synchronized void restore(Instance instance, Consumer<String> status) throws IOException {
		Path stateFile = stateFile(instance);
		if (!Files.exists(stateFile)) {
			return;
		}
		status.accept("Restoring your mods");
		Path mods = InstancePaths.modsDir(instance.id());
		Path backup = backupDir(instance).resolve("mods");
		Files.createDirectories(mods);
		try (Stream<Path> files = Files.list(mods)) {
			for (Path jar : files.filter(PartyModSync::isModFile).filter(p -> !managedByLauncher(p.getFileName().toString())).toList()) {
				Files.deleteIfExists(jar);
			}
		}
		if (Files.isDirectory(backup)) {
			try (Stream<Path> files = Files.list(backup)) {
				for (Path own : files.toList()) {
					Files.move(own, mods.resolve(own.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			Files.deleteIfExists(backup);
			Files.deleteIfExists(backupDir(instance));
		}
		JsonObject state = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), JsonObject.class);
		String original = str(state, "originalMcVersion");
		if (original != null && !original.equals(instance.mcVersion())) {
			InstanceStore.save(new Instance(instance.id(), instance.name(), original, instance.icon(), instance.createdAtEpochMillis(),
					instance.ramMinMb(), instance.ramMaxMb(), instance.extraJvmArgs()));
		}
		Files.deleteIfExists(stateFile);
	}

	private static void download(String url, Path target, String sha1) throws IOException {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3))
					.header("User-Agent", "VeloClientLauncher").GET().build();
			HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() != 200) {
				throw new IOException("HTTP " + response.statusCode());
			}
			Path part = target.resolveSibling(target.getFileName() + ".part");
			try (InputStream in = response.body()) {
				Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!sha1.equalsIgnoreCase(sha1(Files.readAllBytes(part)))) {
				Files.deleteIfExists(part);
				throw new IOException("Checksum mismatch");
			}
			Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	private static String sha1(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}
}
