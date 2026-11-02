package net.veloclient.launcher.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceIcon;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.instance.InstanceStore;
import net.veloclient.launcher.instance.BuiltinIcons;
import net.veloclient.launcher.launch.Downloader;
import net.veloclient.launcher.modrinth.ModrinthClient;
import net.veloclient.launcher.social.LauncherSocial;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Profiles as codes: {@link #share} sends a profile's recipe (Minecraft version + the Modrinth versions of its mods,
 * resource packs and shaders, optionally its settings) to the Velo server and gets "VELO-7XK2Q9" back;
 * {@link #fetch} + {@link #install} rebuild it from Modrinth on another PC. Files that aren't on Modrinth can't travel
 * (no jars pass through Velo) - they're counted so the sharer knows.
 */
public final class ProfileShare {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public record Shared(String code, int skipped) {
	}

	private ProfileShare() {
	}

	public static Shared share(Instance instance, boolean withSettings) throws IOException {
		String base = LauncherSocial.baseUrl();
		String token = LauncherSocial.sessionToken();
		if (base == null || token == null) {
			throw new IOException("Not connected to the Velo server yet - try again in a moment");
		}
		JsonObject body = new JsonObject();
		body.addProperty("name", instance.name());
		body.addProperty("minecraft", instance.mcVersion());
		int skipped = 0;
		for (Object[] kind : new Object[][] {{"mods", InstalledAssetStore.Kind.MOD, InstancePaths.modsDir(instance.id())},
				{"resourcepacks", InstalledAssetStore.Kind.RESOURCE_PACK, InstancePaths.resourcePacksDir(instance.id())},
				{"shaders", InstalledAssetStore.Kind.SHADER_PACK, InstancePaths.shaderPacksDir(instance.id())}}) {
			Map<String, InstalledAsset> known = InstalledAssetStore.asMap(InstalledAssetStore.loadAll(instance.id(), (InstalledAssetStore.Kind) kind[1]));
			JsonArray list = new JsonArray();
			try (Stream<Path> files = Files.list((Path) kind[2])) {
				for (Path file : files.filter(Files::isRegularFile).toList()) {
					String name = file.getFileName().toString();
					boolean enabled = !name.endsWith(".disabled");
					String enabledName = enabled ? name : name.substring(0, name.length() - ".disabled".length());
					if (enabledName.startsWith("velo-client-") || enabledName.startsWith("fabric-api-") || enabledName.startsWith("velo-addons-")) {
						continue; // the launcher puts these in by itself
					}
					InstalledAsset asset = known.get(enabledName);
					if (asset == null) {
						skipped++;
						continue;
					}
					JsonObject o = new JsonObject();
					o.addProperty("project", asset.projectId());
					o.addProperty("version", asset.versionId());
					o.addProperty("title", asset.title());
					o.addProperty("enabled", enabled);
					list.add(o);
				}
			} catch (IOException ignored) {
				// Empty folder.
			}
			body.add((String) kind[0], list);
		}
		body.addProperty("skipped", skipped);
		Path options = InstancePaths.gameDir(instance.id()).resolve("options.txt");
		if (withSettings && Files.exists(options)) {
			body.addProperty("options", Files.readString(options, StandardCharsets.UTF_8));
		}
		JsonObject answer = send(HttpRequest.newBuilder(URI.create(base + "/v1/profiles/share")).header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())));
		return new Shared(answer.get("code").getAsString(), skipped);
	}

	/** {name, minecraft, by, mods[], resourcepacks[], shaders[], options?, skipped}. */
	public static JsonObject fetch(String code) throws IOException {
		String base = LauncherSocial.baseUrl();
		if (base == null) {
			throw new IOException("The Velo server is turned off in the launcher settings");
		}
		String clean = code.strip().toUpperCase(java.util.Locale.ROOT).replaceFirst("^VELO-?", "").replaceAll("[^A-Z0-9]", "");
		if (clean.length() != 6) {
			throw new IOException("A profile code looks like VELO-7XK2Q9");
		}
		return send(HttpRequest.newBuilder(URI.create(base + "/v1/profiles/shared/" + clean)).GET());
	}

	/** Builds the shared profile as a new profile on this PC, downloading everything from Modrinth. */
	public static Instance install(JsonObject shared, String name, BiConsumer<String, Double> progress) throws IOException {
		Instance instance = InstanceStore.createNew(name, shared.get("minecraft").getAsString(), InstanceIcon.builtin(BuiltinIcons.DEFAULT));
		Object[][] kinds = {{"mods", InstalledAssetStore.Kind.MOD, InstancePaths.modsDir(instance.id())},
				{"resourcepacks", InstalledAssetStore.Kind.RESOURCE_PACK, InstancePaths.resourcePacksDir(instance.id())},
				{"shaders", InstalledAssetStore.Kind.SHADER_PACK, InstancePaths.shaderPacksDir(instance.id())}};
		int total = 0;
		for (Object[] kind : kinds) {
			total += shared.has((String) kind[0]) ? shared.getAsJsonArray((String) kind[0]).size() : 0;
		}
		int done = 0;
		int failed = 0;
		for (Object[] kind : kinds) {
			if (!shared.has((String) kind[0])) {
				continue;
			}
			Path folder = (Path) kind[2];
			Files.createDirectories(folder);
			for (JsonElement e : shared.getAsJsonArray((String) kind[0])) {
				JsonObject o = e.getAsJsonObject();
				String title = o.has("title") ? o.get("title").getAsString() : o.get("project").getAsString();
				progress.accept("Downloading " + title + "...", done++ / (double) Math.max(1, total));
				try {
					ModrinthClient.ProjectVersion version = ModrinthClient.getVersion(o.get("version").getAsString())
							.orElseThrow(() -> new IOException("gone from Modrinth"));
					ModrinthClient.VersionFile file = version.primaryFile().orElseThrow(() -> new IOException("no file"));
					Path dest = folder.resolve(file.filename());
					Downloader.ensure(URI.create(file.url()), dest, file.sha1(), file.size(), n -> { });
					ModrinthClient.getProject(version.projectId()).ifPresent(p -> InstalledAssetStore.record(instance.id(), (InstalledAssetStore.Kind) kind[1],
							new InstalledAsset(p.id(), version.id(), file.filename(), p.title(), p.description(), p.iconUrl(), version.versionNumber(),
									((InstalledAssetStore.Kind) kind[1]).modrinthProjectType())));
					if (o.has("enabled") && !o.get("enabled").getAsBoolean()) {
						Files.move(dest, dest.resolveSibling(file.filename() + ".disabled"), StandardCopyOption.REPLACE_EXISTING);
					}
				} catch (IOException | RuntimeException ex) {
					failed++;
					net.veloclient.launcher.LauncherLog.info("Shared profile: " + title + " failed: " + ex.getMessage());
				}
			}
		}
		if (shared.has("options") && !shared.get("options").isJsonNull()) {
			Path options = InstancePaths.gameDir(instance.id()).resolve("options.txt");
			Files.createDirectories(options.getParent());
			Files.writeString(options, shared.get("options").getAsString(), StandardCharsets.UTF_8);
		}
		progress.accept(failed == 0 ? "Done" : "Done - " + failed + " couldn't be downloaded", 1.0);
		return instance;
	}

	private static JsonObject send(HttpRequest.Builder builder) throws IOException {
		try {
			HttpResponse<String> r = HTTP.send(builder.timeout(Duration.ofSeconds(20)).header("Accept", "application/json").build(),
					HttpResponse.BodyHandlers.ofString());
			JsonObject json = r.body().isBlank() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
			if (r.statusCode() / 100 != 2) {
				throw new IOException(json.has("error") ? json.get("error").getAsString() : "HTTP " + r.statusCode());
			}
			return json;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}
}
