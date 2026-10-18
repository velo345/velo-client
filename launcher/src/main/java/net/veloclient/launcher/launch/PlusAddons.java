package net.veloclient.launcher.launch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.data.VeloPaths;
import net.veloclient.launcher.social.StoreApi;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Velo Client Addons are part of Velo+: they're not in the launcher or on GitHub, the server hands
 * the jar to members. At every launch of a profile this
 * <ul>
 * <li>for a member who added them to the profile (opt-in, the Mods tab's Velo+ card): saves the signed proof the game
 * checks ({@code config/velo-plus.json}) and updates the Addons jar for the profile's Minecraft version (an
 * {@code .jar.disabled} copy stays disabled);</li>
 * <li>for someone who is not (or no longer) a member: disables any Addons jar in the profile;</li>
 * <li>when the server can't be reached: leaves everything as it is.</li>
 * </ul>
 */
public final class PlusAddons {

	private PlusAddons() {
	}

	/** What a profile's mods folder has of the Addons. */
	public enum State { NOT_INSTALLED, ENABLED, DISABLED,
		/** Parked as {@code .jar.mismatch}: built for a different Velo Client version than this profile has. */
		MISMATCH }

	public static State state(Path modsDir) {
		try (Stream<Path> files = Files.list(modsDir)) {
			List<Path> installed = files.filter(PlusAddons::isAddonsJar).toList();
			if (installed.isEmpty()) {
				return State.NOT_INSTALLED;
			}
			if (installed.stream().anyMatch(p -> p.getFileName().toString().endsWith(".jar"))) {
				return State.ENABLED;
			}
			return installed.stream().anyMatch(p -> p.getFileName().toString().endsWith(".mismatch")) ? State.MISMATCH : State.DISABLED;
		} catch (IOException e) {
			return State.NOT_INSTALLED;
		}
	}

	/** "Add to this profile": installs (or updates) the Addons jar and makes sure it is switched on. Members only; run off the UI thread. */
	public static void installNow(Path modsDir, GameVersion version) {
		setEnabled(modsDir, true);
		sync(modsDir, version, true);
		disableMismatched(modsDir);
	}

	/**
	 * For {@link State#MISMATCH}: {@code {needed client version, this profile's client version}}, or null when it can't tell.
	 * Reads the jars' fabric.mod.json - run off the UI thread.
	 */
	public static String[] mismatch(Path modsDir) {
		try (Stream<Path> files = Files.list(modsDir)) {
			List<Path> all = files.toList();
			String client = all.stream().filter(p -> {
				String n = p.getFileName().toString();
				return n.startsWith("velo-client-") && n.endsWith(".jar");
			}).map(PlusAddons::modVersion).filter(java.util.Objects::nonNull).findFirst().orElse(null);
			String wanted = all.stream().filter(p -> p.getFileName().toString().endsWith(".mismatch"))
					.map(PlusAddons::requiredClientVersion).filter(java.util.Objects::nonNull).findFirst().orElse(null);
			return client == null || wanted == null ? null : new String[] {wanted, client};
		} catch (IOException e) {
			return null;
		}
	}

	/** Switches an installed Addons jar on or off (a disabled one is kept, like any disabled mod). */
	public static void setEnabled(Path modsDir, boolean enabled) {
		try (Stream<Path> files = Files.list(modsDir)) {
			for (Path jar : files.filter(PlusAddons::isAddonsJar).toList()) {
				String name = jar.getFileName().toString();
				if (enabled && name.endsWith(".disabled")) {
					Files.move(jar, jar.resolveSibling(name.substring(0, name.length() - ".disabled".length())), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				} else if (!enabled && name.endsWith(".jar")) {
					Files.move(jar, jar.resolveSibling(name + ".disabled"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				} else if (!enabled && name.endsWith(".jar.mismatch")) {
					// Off on purpose now: once a matching build arrives it must stay off, not come back on.
					Files.move(jar, jar.resolveSibling(name.substring(0, name.length() - ".mismatch".length()) + ".disabled"),
							java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		} catch (IOException e) {
			LauncherLog.warn("Couldn't switch the Velo Client Addons", e);
		}
	}

	/** Removes the Addons jar(s) from a profile. */
	public static void remove(Path modsDir) {
		try (Stream<Path> files = Files.list(modsDir)) {
			for (Path jar : files.filter(PlusAddons::isAddonsJar).toList()) {
				Files.deleteIfExists(jar);
			}
		} catch (IOException e) {
			LauncherLog.warn("Couldn't remove the Velo Client Addons", e);
		}
	}

	/** At every launch: keeps Addons the player added up to date (or switches them off when Velo+ ended) - never adds them on its own. */
	static void sync(Path modsDir, GameVersion version) {
		sync(modsDir, version, false);
	}

	/** {@code install}: add them even if the profile doesn't have them yet (the player pressed "Add to this profile"). */
	private static void sync(Path modsDir, GameVersion version, boolean install) {
		boolean plus;
		try {
			plus = StoreApi.plusStatus().get("plus").getAsBoolean();
		} catch (Exception e) {
			return; // offline / signed out: no change
		}
		try (Stream<Path> files = Files.list(modsDir)) {
			List<Path> installed = files.filter(PlusAddons::isAddonsJar).toList();
			if (!plus) {
				for (Path jar : installed) {
					String name = jar.getFileName().toString();
					if (name.endsWith(".jar")) {
						Files.move(jar, jar.resolveSibling(name + ".disabled"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
						LauncherLog.info("Velo+ ended - disabled " + name);
					}
				}
				return;
			}
			if (installed.isEmpty() && !install) {
				return; // opt-in: only profiles the player added them to
			}
			// The proof the game checks before it loads the Addons' modules.
			try {
				JsonObject out = new JsonObject();
				out.addProperty("token", StoreApi.plusToken().get("token").getAsString());
				Files.createDirectories(VeloPaths.config());
				Files.writeString(VeloPaths.config().resolve("velo-plus.json"), out.toString(), StandardCharsets.UTF_8);
			} catch (Exception e) {
				LauncherLog.warn("Couldn't fetch the Velo+ proof", e);
			}
			JsonObject entry = null;
			for (JsonElement e : StoreApi.plusAddons().getAsJsonArray("addons")) {
				if (e.getAsJsonObject().get("mc").getAsString().equals(version.id())) {
					entry = e.getAsJsonObject();
				}
			}
			if (entry == null) {
				return; // no Addons published for this Minecraft version yet
			}
			boolean disabled = !installed.isEmpty() && installed.stream().allMatch(p -> p.getFileName().toString().endsWith(".disabled"));
			Path dest = modsDir.resolve("velo-addons-" + version.id() + ".jar" + (disabled ? ".disabled" : ""));
			String sha = entry.get("sha256").getAsString();
			if (installed.size() == 1 && installed.get(0).equals(dest) && sha.equals(sha256(Files.readAllBytes(dest)))) {
				return;
			}
			if (installed.size() == 1 && installed.get(0).getFileName().toString().endsWith(".mismatch")
					&& sha.equals(sha256(Files.readAllBytes(installed.get(0))))) {
				return; // the server still has the build that didn't fit: leave it parked
			}
			if (keepLocalBuild(installed, entry)) {
				return;
			}
			byte[] jar = cachedOrDownload(version.id(), sha);
			for (Path old : installed) {
				try {
					Files.delete(old);
				} catch (IOException e) {
					LauncherLog.warn("Couldn't remove old addons jar " + old.getFileName(), e);
				}
			}
			Files.write(dest, jar, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			LauncherLog.info("Installed Velo Client Addons for " + version.id());
		} catch (Exception e) {
			LauncherLog.warn("Couldn't sync the Velo Client Addons in " + modsDir, e);
		}
	}

	/**
	 * A locally built Addons jar that fits this profile's Velo Client stays when the server only has Addons for a
	 * different client version (e.g. testing a new build before its Addons are published) - swapping it for the
	 * server's copy would just park that one as a mismatch.
	 */
	private static boolean keepLocalBuild(List<Path> installed, JsonObject entry) {
		if (installed.size() != 1 || !installed.get(0).getFileName().toString().endsWith(".jar")) {
			return false;
		}
		String client = clientVersion(installed.get(0).getParent());
		String wanted = requiredClientVersion(installed.get(0));
		if (client == null || wanted == null || !wanted.equals(client)) {
			return false;
		}
		String serverVersion = entry.has("version") ? entry.get("version").getAsString() : null;
		return serverVersion == null || !client.startsWith(serverVersion + "+") && !client.equals(serverVersion);
	}

	private static String clientVersion(Path modsDir) {
		try (Stream<Path> files = Files.list(modsDir)) {
			for (Path p : files.toList()) {
				String name = p.getFileName().toString();
				if (name.startsWith("velo-client-") && name.endsWith(".jar")) {
					return modVersion(p);
				}
			}
		} catch (IOException e) {
			return null;
		}
		return null;
	}

	/**
	 * The Addons are built for one exact Velo Client version (their fabric.mod.json pins it). When the server's Addons are older
	 * or newer than the Velo Client in this profile - e.g. a new client is out but its Addons aren't released yet - Fabric
	 * refused to start the game at all. Such a jar is switched off instead (and back on once it matches again).
	 */
	static void disableMismatched(Path modsDir) {
		try (Stream<Path> files = Files.list(modsDir)) {
			List<Path> all = files.toList();
			String client = null;
			for (Path p : all) {
				String name = p.getFileName().toString();
				if (name.startsWith("velo-client-") && name.endsWith(".jar")) {
					client = modVersion(p);
				}
			}
			if (client == null) {
				return;
			}
			for (Path jar : all) {
				String name = jar.getFileName().toString();
				if (!isAddonsJar(jar)) {
					continue;
				}
				String wanted = requiredClientVersion(jar);
				boolean matches = wanted == null || wanted.equals(client);
				if (!matches && name.endsWith(".jar")) {
					Files.move(jar, jar.resolveSibling(name + ".mismatch"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					LauncherLog.info("Switched off " + name + ": it needs Velo Client " + wanted + ", this profile has " + client);
				}
			}
			// Parked earlier because of a mismatch and now matching again: back on.
			for (Path jar : all) {
				String name = jar.getFileName().toString();
				if (name.startsWith("velo-addons-") && name.endsWith(".jar.mismatch")) {
					String wanted = requiredClientVersion(jar);
					if (wanted == null || wanted.equals(client)) {
						Files.move(jar, jar.resolveSibling(name.substring(0, name.length() - ".mismatch".length())),
								java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
		} catch (IOException e) {
			LauncherLog.warn("Couldn't check the Addons version in " + modsDir, e);
		}
	}

	private static com.google.gson.JsonObject modJson(Path jar) {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			var entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return null;
			}
			try (var in = zip.getInputStream(entry)) {
				return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static String modVersion(Path jar) {
		var json = modJson(jar);
		return json != null && json.has("version") ? json.get("version").getAsString() : null;
	}

	/** The exact velo-client version an Addons jar depends on ("0.9.0+26.2"), or null when it doesn't pin one. */
	private static String requiredClientVersion(Path jar) {
		var json = modJson(jar);
		if (json == null || !json.has("depends") || !json.getAsJsonObject("depends").has("velo-client")) {
			return null;
		}
		var dep = json.getAsJsonObject("depends").get("velo-client");
		String value = dep.isJsonArray() ? dep.getAsJsonArray().get(0).getAsString() : dep.getAsString();
		value = value.replaceAll("^[=\\s]+", "").strip();
		return value.matches("[0-9][0-9A-Za-z.+\\-]*") ? value : null;
	}

	private static byte[] cachedOrDownload(String mc, String sha) throws Exception {
		Path cache = VeloPaths.root().resolve("cache").resolve("addons").resolve(mc + "-" + sha + ".jar");
		if (Files.exists(cache)) {
			return Files.readAllBytes(cache);
		}
		byte[] jar = StoreApi.plusAddonJar(mc);
		if (!sha.equals(sha256(jar))) {
			throw new IOException("The downloaded Addons jar doesn't match its checksum");
		}
		Files.createDirectories(cache.getParent());
		Files.write(cache, jar);
		return jar;
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static boolean isAddonsJar(Path path) {
		String name = path.getFileName().toString();
		return name.startsWith("velo-addons-") && (name.endsWith(".jar") || name.endsWith(".jar.disabled") || name.endsWith(".jar.mismatch"));
	}
}
