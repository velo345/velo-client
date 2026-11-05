package net.veloclient.launcher.doctor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.launcher.data.InstalledAsset;
import net.veloclient.launcher.data.InstalledAssetStore;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstancePaths;
import net.veloclient.launcher.launch.GameVersion;
import net.veloclient.launcher.launch.GraphicsApi;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Everything about a profile the Doctor looks at, read once: mods (with their metadata), resource packs, shader
 * packs, the graphics API, memory, the Minecraft / Java / loader versions. Rules only ever read a snapshot, so the
 * planner can check "what if" states without touching any file.
 */
record Snapshot(Instance instance, List<ModScanner.ModInfo> mods, List<Pack> packs, List<Path> shaders, String graphicsApi,
		Integer ramMaxMb, int javaMajor, String loaderVersion, Map<String, InstalledAsset> modAssets) {

	record Pack(Path file, boolean enabled, boolean broken, String name) {
	}

	static Snapshot take(Instance instance) {
		List<ModScanner.ModInfo> mods = ModScanner.scan(InstancePaths.modsDir(instance.id()));
		List<Pack> packs = new ArrayList<>();
		try (Stream<Path> files = Files.list(InstancePaths.resourcePacksDir(instance.id()))) {
			for (Path file : files.sorted().toList()) {
				String name = file.getFileName().toString();
				boolean enabled = !name.endsWith(".disabled");
				packs.add(new Pack(file, enabled, enabled && brokenPack(file), name.replace(".disabled", "")));
			}
		} catch (Exception ignored) {
			// No packs.
		}
		List<Path> shaders = new ArrayList<>();
		try (Stream<Path> files = Files.list(InstancePaths.shaderPacksDir(instance.id()))) {
			files.filter(p -> !p.getFileName().toString().endsWith(".disabled") && !p.getFileName().toString().endsWith(".txt")).sorted()
					.forEach(shaders::add);
		} catch (Exception ignored) {
			// No shaders.
		}
		GameVersion version = GameVersion.byId(instance.mcVersion());
		String loader = version == null ? "0.19.5" : net.veloclient.launcher.launch.LoaderVersions.forInstance(instance.id(), version);
		Map<String, InstalledAsset> assets = InstalledAssetStore.asMap(InstalledAssetStore.loadAll(instance.id(), InstalledAssetStore.Kind.MOD));
		String api = GraphicsApi.supported(instance.mcVersion()) ? GraphicsApi.effective(instance.id()) : GraphicsApi.OPENGL;
		Integer ram = instance.ramMaxMb() == null || net.veloclient.launcher.launch.MemoryPlanner.isLegacyDefault(instance) ? null : instance.ramMaxMb();
		return new Snapshot(instance, mods, packs, shaders, api, ram, javaFor(instance.mcVersion()), loader, assets);
	}

	/** The Java the launcher runs this version with. */
	static int javaFor(String mcVersion) {
		return mcVersion != null && mcVersion.startsWith("1.") ? 21 : 25;
	}

	/** A pack without a readable pack.mcmeta is skipped by the game at best. */
	private static boolean brokenPack(Path file) {
		if (Files.isDirectory(file)) {
			return !Files.exists(file.resolve("pack.mcmeta"));
		}
		if (!file.getFileName().toString().endsWith(".zip")) {
			return false;
		}
		try (ZipFile zip = new ZipFile(file.toFile())) {
			ZipEntry meta = zip.getEntry("pack.mcmeta");
			if (meta == null) {
				return true;
			}
			try (InputStream in = zip.getInputStream(meta)) {
				JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
				return !json.has("pack");
			}
		} catch (Exception e) {
			return true;
		}
	}

	List<ModScanner.ModInfo> enabledMods() {
		return mods.stream().filter(m -> m.enabled() && m.problem() == ModScanner.Problem.NONE).toList();
	}

	/** id -> version of everything that's there when the game starts: enabled mods, what they carry, and the platform. */
	Map<String, String> provided() {
		Map<String, String> out = new HashMap<>();
		out.put("minecraft", normalizedMc());
		out.put("java", String.valueOf(javaMajor));
		out.put("fabricloader", loaderVersion);
		out.put("fabric-loader", loaderVersion);
		// What the loader itself carries (MixinExtras 0.5.5 in 0.19.5), read from its jar - a guessed version here made
		// the Doctor report a missing MixinExtras that the game had all along. Unknown = can't be wrong ("*").
		ModScanner.ModInfo loader = loaderJar();
		if (loader != null && loader.problem() == ModScanner.Problem.NONE) {
			for (ModScanner.ModInfo nested : loader.nested()) {
				addProvided(out, nested, nested.version());
			}
		} else {
			out.put("mixinextras", "*");
		}
		for (ModScanner.ModInfo mod : enabledMods()) {
			addProvided(out, mod, mod.version());
		}
		return out;
	}

	private static void addProvided(Map<String, String> out, ModScanner.ModInfo mod, String parentVersion) {
		String version = mod.version() != null ? mod.version() : parentVersion;
		if (mod.id() != null) {
			out.merge(mod.id(), version == null ? "0" : version, (a, b) -> "*".equals(a) || "*".equals(b) ? "*" : VersionPredicate.compare(a, b) >= 0 ? a : b);
		}
		for (String p : mod.provides()) {
			out.putIfAbsent(p, version == null ? "0" : version);
		}
		for (ModScanner.ModInfo n : mod.nested()) {
			addProvided(out, n, version);
		}
	}

	private ModScanner.ModInfo loaderJar() {
		java.nio.file.Path jar = net.veloclient.launcher.data.VeloPaths.sharedRoot().resolve("gamedata").resolve("libraries")
				.resolve("net/fabricmc/fabric-loader").resolve(loaderVersion).resolve("fabric-loader-" + loaderVersion + ".jar");
		return Files.exists(jar) ? ModScanner.readJar(jar) : null;
	}

	/** Mods declare "~1.21.11" or "26.3"; the game reports exactly that. */
	private String normalizedMc() {
		return instance.mcVersion();
	}

	/** Disabled jars that would provide {@code id} if they were on. */
	List<ModScanner.ModInfo> disabledProviders(String id) {
		return mods.stream().filter(m -> !m.enabled() && m.problem() == ModScanner.Problem.NONE && m.allIds().contains(id)).toList();
	}

	/** The enabled jar whose own id (or a provided id) is {@code id}. */
	ModScanner.ModInfo enabledById(String id) {
		for (ModScanner.ModInfo mod : enabledMods()) {
			if (id.equals(mod.id()) || mod.provides().contains(id)) {
				return mod;
			}
		}
		return null;
	}

	/** The Modrinth record for a jar, if the launcher installed or identified it. */
	InstalledAsset assetOf(Path file) {
		return modAssets.get(file.getFileName().toString().replace(".disabled", ""));
	}

	/** Changes when anything the Doctor looks at changes - mods (on/off, versions), packs, graphics, memory. */
	String fingerprint() {
		StringBuilder sb = new StringBuilder(instance.mcVersion()).append('|').append(graphicsApi).append('|').append(ramMaxMb);
		for (ModScanner.ModInfo mod : mods) {
			sb.append('|').append(mod.file().getFileName()).append(':').append(mod.version());
		}
		for (Pack pack : packs) {
			sb.append('|').append(pack.file().getFileName());
		}
		for (Path shader : shaders) {
			sb.append('|').append(shader.getFileName());
		}
		return hash(sb.toString());
	}

	/** What a later "what changed since?" compares against - stored in the Doctor's memory. */
	List<Map<String, Object>> modList() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (ModScanner.ModInfo mod : mods) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("file", mod.file().getFileName().toString().replace(".disabled", ""));
			m.put("id", mod.id());
			m.put("name", mod.displayName());
			m.put("version", mod.version());
			m.put("enabled", mod.enabled());
			InstalledAsset asset = assetOf(mod.file());
			if (asset != null) {
				m.put("project", asset.projectId());
			}
			out.add(m);
		}
		return out;
	}

	static String hash(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (Exception e) {
			return Integer.toHexString(text.hashCode());
		}
	}
}
