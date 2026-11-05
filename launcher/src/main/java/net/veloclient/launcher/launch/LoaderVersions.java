package net.veloclient.launcher.launch;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.veloclient.launcher.instance.InstancePaths;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The Fabric Loader a profile runs. By default the one Velo is tested with ({@link GameVersion#fabricLoaderVersion()});
 * a profile can pick another (a mod that wants a newer loader...), kept in {@code fabric-loader.txt} next to the
 * profile so the profile format stays as it is.
 */
public final class LoaderVersions {

	/** Velo Client's own fabric.mod.json needs at least this. */
	public static final String MINIMUM = "0.19.3";

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	/** One loader build: its version and whether Fabric marks it stable. */
	public record Build(String version, boolean stable) {
	}

	private LoaderVersions() {
	}

	private static Path file(String instanceId) {
		return InstancePaths.dir(instanceId).resolve("fabric-loader.txt");
	}

	/** The loader version this profile launches with. */
	public static String forInstance(String instanceId, GameVersion version) {
		try {
			Path f = file(instanceId);
			if (Files.exists(f)) {
				String v = Files.readString(f, StandardCharsets.UTF_8).strip();
				if (v.matches("[0-9][0-9A-Za-z.+\\-]*") && compare(v, MINIMUM) >= 0) {
					return v;
				}
			}
		} catch (IOException ignored) {
			// unreadable: the default
		}
		return version.fabricLoaderVersion();
	}

	/** Picks a loader for the profile; null (or the default) goes back to Velo's tested one. */
	public static void set(String instanceId, GameVersion version, String loader) throws IOException {
		Path f = file(instanceId);
		if (loader == null || loader.equals(version.fabricLoaderVersion())) {
			Files.deleteIfExists(f);
		} else {
			Files.createDirectories(f.getParent());
			Files.writeString(f, loader, StandardCharsets.UTF_8);
		}
	}

	/**
	 * The loader to launch with: the profile's own pick if it has one; otherwise Velo's tested build - unless a mod needs
	 * a newer one, then the newest stable build every enabled mod accepts (remembered for the profile, so the Doctor
	 * and the profile page show it). Falls back to the tested build when Fabric can't be reached.
	 */
	public static String resolveFor(String instanceId, GameVersion version, Path modsDir) {
		String chosen = forInstance(instanceId, version);
		if (Files.exists(file(instanceId)) || net.veloclient.launcher.doctor.ModRequirements.acceptsLoader(modsDir, chosen)) {
			return chosen;
		}
		try {
			for (Build build : available(version.id())) {
				if (build.stable() && compare(build.version(), chosen) > 0
						&& net.veloclient.launcher.doctor.ModRequirements.acceptsLoader(modsDir, build.version())) {
					set(instanceId, version, build.version());
					net.veloclient.launcher.LauncherLog.info("A mod needs a newer Fabric Loader - this profile now uses " + build.version());
					return build.version();
				}
			}
		} catch (IOException e) {
			net.veloclient.launcher.LauncherLog.warn("Couldn't look up Fabric Loader builds", e);
		}
		return chosen;
	}

	/** Every loader Fabric offers for {@code mcVersion} that Velo runs on, newest first. */
	public static List<Build> available(String mcVersion) throws IOException {
		String base = "https://meta.fabricmc.net";
		for (GameVersion v : GameVersion.values()) {
			if (v.id().equals(mcVersion)) {
				base = v.metaBase();
			}
		}
		String url = base + "/v2/versions/loader/" + mcVersion;
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
		try {
			HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() >= 400) {
				throw new IOException("HTTP " + response.statusCode() + " from Fabric Meta");
			}
			List<Build> out = new ArrayList<>();
			for (JsonElement e : JsonParser.parseString(response.body()).getAsJsonArray()) {
				var loader = e.getAsJsonObject().getAsJsonObject("loader");
				String v = loader.get("version").getAsString();
				if (compare(v, MINIMUM) >= 0) {
					out.add(new Build(v, loader.has("stable") && loader.get("stable").getAsBoolean()));
				}
			}
			return out;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted", e);
		}
	}

	/** Compares dotted versions number by number ("0.19.10" > "0.19.5"); anything after +/- is ignored. */
	public static int compare(String a, String b) {
		String[] x = a.split("[+\\-]")[0].split("\\.");
		String[] y = b.split("[+\\-]")[0].split("\\.");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length ? parse(x[i]) : 0;
			int q = i < y.length ? parse(y[i]) : 0;
			if (p != q) {
				return Integer.compare(p, q);
			}
		}
		return 0;
	}

	private static int parse(String s) {
		try {
			return Integer.parseInt(s.replaceAll("\\D.*", ""));
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
