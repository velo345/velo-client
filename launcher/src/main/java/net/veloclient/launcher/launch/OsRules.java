package net.veloclient.launcher.launch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Set;

/**
 * Evaluates the {@code rules} arrays used throughout Mojang's version JSON
 * (library os-filtering, conditional JVM/game arguments) - see
 * wiki.vg/minecraft.wiki's "Launching the game" page for the format this
 * mirrors.
 */
public final class OsRules {

	private OsRules() {
	}

	/** No optional launcher features (custom resolution, demo mode, ...) are supported, so none are ever active. */
	public static final Set<String> NO_FEATURES = Set.of();

	public static boolean isAllowed(JsonArray rules, Set<String> activeFeatures) {
		if (rules == null || rules.isEmpty()) {
			return true;
		}
		boolean allowed = false;
		for (var element : rules) {
			JsonObject rule = element.getAsJsonObject();
			if (!ruleApplies(rule, activeFeatures)) {
				continue;
			}
			allowed = "allow".equals(rule.get("action").getAsString());
		}
		return allowed;
	}

	private static boolean ruleApplies(JsonObject rule, Set<String> activeFeatures) {
		if (rule.has("os")) {
			JsonObject os = rule.getAsJsonObject("os");
			if (os.has("name") && !os.get("name").getAsString().equals(currentOsName())) {
				return false;
			}
			if (os.has("arch") && !archMatches(os.get("arch").getAsString())) {
				return false;
			}
		}
		if (rule.has("features")) {
			JsonObject features = rule.getAsJsonObject("features");
			for (String key : features.keySet()) {
				boolean required = features.get(key).getAsBoolean();
				if (required != activeFeatures.contains(key)) {
					return false;
				}
			}
		}
		return true;
	}

	public static String currentOsName() {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		if (os.contains("win")) {
			return "windows";
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return "osx";
		}
		return "linux";
	}

	private static boolean archMatches(String ruleArch) {
		String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
		boolean jvmIsArm = arch.contains("aarch64") || arch.contains("arm");
		boolean jvmIs64Bit = arch.contains("64");
		String rule = ruleArch.toLowerCase(java.util.Locale.ROOT);
		// Apple Silicon / Windows on ARM: an "arm64" rule must not also match Intel/AMD machines (and vice versa),
		// otherwise both CPUs' natives or JVM arguments land on the command line.
		if (rule.equals("arm64") || rule.equals("aarch64")) {
			return jvmIsArm && jvmIs64Bit;
		}
		if (rule.equals("x86_64") || rule.equals("amd64") || rule.equals("x64")) {
			return !jvmIsArm && jvmIs64Bit;
		}
		boolean ruleIs32Bit = rule.equals("x86") || rule.equals("32");
		return jvmIs64Bit != ruleIs32Bit;
	}
}
