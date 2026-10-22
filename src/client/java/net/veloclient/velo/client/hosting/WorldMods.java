package net.veloclient.velo.client.hosting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which mods a hosted world needs - only the ones that change the world itself (like Essential does): mods the host
 * runs on both sides (new blocks, items, mobs, dimensions...). Client-only mods (minimaps, HUDs, shaders, performance),
 * pure libraries, Minecraft, Fabric and Velo are ignored, and so are extra mods on the guest's side - a server only
 * turns you away when it has content you don't. Guests compare before joining; a difference opens the warning with
 * "Relaunch with matching mods" (the launcher fetches them from Modrinth by each jar's SHA-1).
 */
public final class WorldMods {

	/** One mod: id, version, display name, jar file name and its SHA-1 (null for mods bundled inside another jar). */
	/** {@code library}: comes along on a relaunch (other mods need it) but isn't compared. */
	public record Mod(String id, String version, String name, String file, String sha1, boolean library) {
	}

	/** What's different from the host: missing here, a different version here, or only here. */
	public record Diff(List<Mod> missing, List<String[]> different, List<Mod> extra) {
		/** Extra mods on your side never stop you joining; only missing or other-version world mods do. */
		public boolean matches() {
			return missing.isEmpty() && different.isEmpty();
		}
	}

	private static final Set<String> BUILT_IN = Set.of("minecraft", "java", "fabricloader", "mixinextras", "velo-client", "velo-addons");

	private WorldMods() {
	}

	/** This game's content mods (top-level jars only). */
	public static List<Mod> content() {
		List<Mod> out = new ArrayList<>();
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			var meta = mod.getMetadata();
			String id = meta.getId();
			if (BUILT_IN.contains(id) || id.startsWith("fabric-") || id.equals("fabric-api") || id.equals("fabric") || mod.getContainingMod().isPresent()
					|| !meta.getEnvironment().matches(EnvType.SERVER)) {
				continue;
			}
			String file = null;
			String sha1 = null;
			List<Path> paths = mod.getOrigin().getKind() == net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH ? mod.getOrigin().getPaths() : List.of();
			if (paths.size() == 1 && Files.isRegularFile(paths.get(0))) {
				file = paths.get(0).getFileName().toString();
				try {
					sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(paths.get(0))));
				} catch (Exception ignored) {
					// Unreadable: compared by id and version only.
				}
			}
			out.add(new Mod(id, meta.getVersion().getFriendlyString(), meta.getName(), file, sha1, library(meta)));
		}
		return out;
	}

	/** Libraries (Mod Menu's "library" badge, or a lib in the name) bring nothing to the world on their own. */
	private static boolean library(net.fabricmc.loader.api.metadata.ModMetadata meta) {
		try {
			var modmenu = meta.getCustomValue("modmenu");
			if (modmenu != null && modmenu.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.OBJECT) {
				var badges = modmenu.getAsObject().get("badges");
				if (badges != null && badges.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.ARRAY) {
					for (var badge : badges.getAsArray()) {
						if (badge.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.STRING && badge.getAsString().equals("library")) {
							return true;
						}
					}
				}
			}
		} catch (RuntimeException ignored) {
			// Odd metadata: treat it as a real mod.
		}
		String id = meta.getId();
		return id.endsWith("lib") || id.endsWith("-api") || id.endsWith("config") || id.equals("cloth-config") || id.equals("architectury");
	}

	public static JsonArray toJson(List<Mod> mods) {
		JsonArray array = new JsonArray();
		for (Mod mod : mods) {
			JsonObject o = new JsonObject();
			o.addProperty("id", mod.id());
			o.addProperty("version", mod.version());
			o.addProperty("name", mod.name());
			if (mod.file() != null) {
				o.addProperty("file", mod.file());
			}
			if (mod.sha1() != null) {
				o.addProperty("sha1", mod.sha1());
			}
			if (mod.library()) {
				o.addProperty("library", true);
			}
			array.add(o);
		}
		return array;
	}

	public static List<Mod> fromJson(JsonArray array) {
		List<Mod> out = new ArrayList<>();
		if (array == null) {
			return out;
		}
		for (JsonElement e : array) {
			JsonObject o = e.getAsJsonObject();
			out.add(new Mod(str(o, "id"), str(o, "version"), str(o, "name"), str(o, "file"), str(o, "sha1"),
					o.has("library") && o.get("library").getAsBoolean()));
		}
		return out;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	/** How this game differs from what the host runs. */
	public static Diff compare(List<Mod> host) {
		Map<String, Mod> mine = new LinkedHashMap<>();
		for (Mod mod : content()) {
			if (!mod.library()) {
				mine.put(mod.id(), mod);
			}
		}
		List<Mod> missing = new ArrayList<>();
		List<String[]> different = new ArrayList<>();
		for (Mod mod : host) {
			if (mod.library()) {
				continue;
			}
			Mod have = mine.remove(mod.id());
			if (have == null) {
				missing.add(mod);
			} else if (mod.version() != null && !mod.version().equals(have.version())) {
				different.add(new String[] {mod.name() == null ? mod.id() : mod.name(), have.version(), mod.version()});
			}
		}
		return new Diff(missing, different, new ArrayList<>(mine.values()));
	}

	/** Whether this game was started by the Velo launcher (its profiles live under the Velo folder) - only then can it relaunch. */
	public static boolean launchedByVelo() {
		Path game = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
		return game.startsWith(net.veloclient.velo.config.VeloPaths.root().resolve("instances").toAbsolutePath().normalize());
	}
}
