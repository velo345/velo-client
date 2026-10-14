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
 * <li>for a member: saves the signed proof the game checks ({@code config/velo-plus.json}) and installs
 * or updates the Addons jar for the profile's Minecraft version (an {@code .jar.disabled} copy stays
 * disabled);</li>
 * <li>for someone who is not (or no longer) a member: disables any Addons jar in the profile;</li>
 * <li>when the server can't be reached: leaves everything as it is.</li>
 * </ul>
 */
final class PlusAddons {

	private PlusAddons() {
	}

	static void sync(Path modsDir, GameVersion version) {
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
		return name.startsWith("velo-addons-") && (name.endsWith(".jar") || name.endsWith(".jar.disabled"));
	}
}
