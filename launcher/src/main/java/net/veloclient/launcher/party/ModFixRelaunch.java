package net.veloclient.launcher.party;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.modrinth.ModrinthClient;

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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * A server's mod hints, fixed (Velo Bridge "mod-rules" -> the game's "Remove/Add & relaunch"): the game leaves
 * {@code config/pending-mod-fix.json} (its folder, pid, Minecraft version, the server, jars to move away, Modrinth
 * projects to add) and closes. Once it has exited this moves the forbidden jars to {@code velo-disabled-mods/} next to
 * the mods folder (nothing is deleted - put them back any time), installs the recommended mods for that Minecraft
 * version from Modrinth (SHA-1 checked), and starts the profile again straight onto the server.
 */
public final class ModFixRelaunch {

	private static boolean started;
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

	private ModFixRelaunch() {
	}

	/** @param launch starts the instance, joining the server (nullable address), on the FX thread */
	public static synchronized void start(BiConsumer<Instance, String> launch, BiConsumer<String, String> message) {
		if (started) {
			return;
		}
		started = true;
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "velo-mod-fix");
			t.setDaemon(true);
			return t;
		}).scheduleWithFixedDelay(() -> check(launch, message), 3, 3, TimeUnit.SECONDS);
	}

	private static Path note() {
		return VeloPaths.config().resolve("pending-mod-fix.json");
	}

	private static void check(BiConsumer<Instance, String> launch, BiConsumer<String, String> message) {
		try {
			if (!Files.exists(note())) {
				return;
			}
			JsonObject json = JsonParser.parseString(Files.readString(note(), StandardCharsets.UTF_8)).getAsJsonObject();
			if (System.currentTimeMillis() - json.get("at").getAsLong() > 10 * 60_000) {
				Files.deleteIfExists(note());
				return;
			}
			if (ProcessHandle.of(json.get("pid").getAsLong()).map(ProcessHandle::isAlive).orElse(false)) {
				return; // still closing
			}
			Files.deleteIfExists(note());
			Path gameDir = Path.of(json.get("gameDir").getAsString()).toAbsolutePath().normalize();
			Instance instance = null;
			for (Instance i : InstanceStore.loadAll()) {
				if (InstancePaths.gameDir(i.id()).toAbsolutePath().normalize().equals(gameDir)) {
					instance = i;
				}
			}
			if (instance == null) {
				Platform.runLater(() -> message.accept("Couldn't fix the mods", "That game didn't run from a Velo profile."));
				return;
			}
			Path mods = InstancePaths.modsDir(instance.id()).toAbsolutePath().normalize();
			Path disabled = mods.resolveSibling("velo-disabled-mods");
			List<String> moved = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray("remove")) {
				Path jar = mods.resolve(e.getAsString()).normalize();
				if (!jar.startsWith(mods) || !Files.isRegularFile(jar)) {
					continue;
				}
				Files.createDirectories(disabled);
				Files.move(jar, disabled.resolve(jar.getFileName()), StandardCopyOption.REPLACE_EXISTING);
				moved.add(jar.getFileName().toString());
			}
			String mc = json.has("mc") ? json.get("mc").getAsString() : instance.mcVersion();
			List<String> added = new ArrayList<>();
			List<String> failed = new ArrayList<>();
			for (JsonElement e : json.getAsJsonArray("add")) {
				JsonObject o = e.getAsJsonObject();
				String slug = o.get("modrinth").getAsString();
				String label = o.has("label") ? o.get("label").getAsString() : slug;
				try {
					List<ModrinthClient.ProjectVersion> versions = ModrinthClient.versions(slug, mc, "mod", "fabric");
					ModrinthClient.ProjectVersion v = versions.stream().filter(x -> "release".equals(x.versionType())).findFirst()
							.orElse(versions.isEmpty() ? null : versions.get(0));
					if (v == null || v.primaryFile().isEmpty()) {
						failed.add(label);
						continue;
					}
					download(v.primaryFile().get(), mods);
					added.add(label);
				} catch (Exception ex) {
					LauncherLog.warn("Mod fix: couldn't install " + slug, ex);
					failed.add(label);
				}
			}
			LauncherLog.info("Mod fix for " + instance.name() + ": moved " + moved + ", added " + added + (failed.isEmpty() ? "" : ", failed " + failed));
			String server = json.has("server") ? json.get("server").getAsString() : null;
			Instance target = instance;
			Platform.runLater(() -> {
				if (!failed.isEmpty()) {
					message.accept("Some mods couldn't be added", String.join(", ", failed) + " - not on Modrinth for Minecraft " + mc + ".");
				}
				launch.accept(target, server);
			});
		} catch (Exception e) {
			LauncherLog.warn("Mod fix relaunch failed", e);
			try {
				Files.deleteIfExists(note());
			} catch (Exception ignored) {
				// next time
			}
			Platform.runLater(() -> message.accept("Couldn't fix the mods", String.valueOf(e.getMessage())));
		}
	}

	private static void download(ModrinthClient.VersionFile file, Path mods) throws Exception {
		Path target = mods.resolve(file.filename()).normalize();
		if (!target.startsWith(mods)) {
			throw new IllegalArgumentException("bad file name");
		}
		Path tmp = target.resolveSibling(file.filename() + ".part");
		HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(file.url())).timeout(Duration.ofMinutes(2)).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (r.statusCode() != 200) {
			throw new IllegalStateException("HTTP " + r.statusCode());
		}
		MessageDigest sha = MessageDigest.getInstance("SHA-1");
		try (InputStream in = r.body(); var out = Files.newOutputStream(tmp)) {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0) {
				sha.update(buf, 0, n);
				out.write(buf, 0, n);
			}
		}
		String got = HexFormat.of().formatHex(sha.digest());
		if (file.sha1() != null && !file.sha1().equalsIgnoreCase(got)) {
			Files.deleteIfExists(tmp);
			throw new IllegalStateException("download didn't match its SHA-1");
		}
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
	}
}
