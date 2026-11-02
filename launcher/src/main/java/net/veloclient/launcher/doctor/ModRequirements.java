package net.veloclient.launcher.doctor;

import com.google.gson.JsonElement;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What a profile's enabled mods ask of the platform itself - the Java version and the Fabric Loader - read from their
 * fabric.mod.json (nested jars included), for the launch to pick a Java and a loader every mod accepts.
 */
public final class ModRequirements {

	private ModRequirements() {
	}

	private static List<JsonElement> requirements(Path modsDir, String key) {
		List<JsonElement> out = new ArrayList<>();
		for (ModScanner.ModInfo mod : ModScanner.scan(modsDir)) {
			if (mod.enabled()) {
				collect(mod, key, out);
			}
		}
		return out;
	}

	private static void collect(ModScanner.ModInfo mod, String key, List<JsonElement> out) {
		if (mod.depends() != null && mod.depends().containsKey(key)) {
			out.add(mod.depends().get(key));
		}
		if (mod.nested() != null) {
			for (ModScanner.ModInfo inner : mod.nested()) {
				collect(inner, key, out);
			}
		}
	}

	/** Whether every enabled mod that depends on {@code modId} accepts {@code version} of it. */
	public static boolean acceptsMod(Path modsDir, String modId, String version) {
		for (JsonElement requirement : requirements(modsDir, modId)) {
			if (!VersionPredicate.matches(requirement, version)) {
				return false;
			}
		}
		return true;
	}

	/** Whether any enabled mod asks for a particular version of {@code modId}. */
	public static boolean constrains(Path modsDir, String modId) {
		return !requirements(modsDir, modId).isEmpty();
	}

	/** The version in a jar's fabric.mod.json, or null. */
	public static String versionOf(Path jar) {
		ModScanner.ModInfo info = ModScanner.readJar(jar);
		return info == null ? null : info.version();
	}

	/** Whether every enabled mod accepts Java {@code feature} (e.g. 25). */
	public static boolean acceptsJava(Path modsDir, int feature) {
		for (JsonElement requirement : requirements(modsDir, "java")) {
			if (!VersionPredicate.matches(requirement, String.valueOf(feature))) {
				return false;
			}
		}
		return true;
	}

	/** Whether every enabled mod accepts Fabric Loader {@code version}. */
	public static boolean acceptsLoader(Path modsDir, String version) {
		for (JsonElement requirement : requirements(modsDir, "fabricloader")) {
			if (!VersionPredicate.matches(requirement, version)) {
				return false;
			}
		}
		return true;
	}
}
