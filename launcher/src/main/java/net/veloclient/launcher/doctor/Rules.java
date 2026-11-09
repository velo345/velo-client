package net.veloclient.launcher.doctor;

import com.google.gson.JsonElement;
import net.veloclient.launcher.data.VeloBuiltins;
import net.veloclient.launcher.doctor.Diagnosis.Action;
import net.veloclient.launcher.doctor.Diagnosis.Fix;
import net.veloclient.launcher.doctor.Diagnosis.Issue;
import net.veloclient.launcher.doctor.Diagnosis.Severity;
import net.veloclient.launcher.launch.GraphicsApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Problems you can see before pressing Play: broken or wrong-loader jars, the same mod twice, missing or switched-off
 * dependencies, mods made for another Minecraft / Java / loader version, mods that break each other, Iris on Vulkan,
 * broken resource packs, memory set too low or too high. Every problem comes with ways to fix it, the safest
 * first. Reads only the snapshot - no files, no network - so it runs on every change.
 */
final class Rules {

	/** Fabric API and its modules are put in by the launcher for every profile; Velo's own jars too. */
	private static boolean managed(String id) {
		return id.equals("fabric") || id.equals("fabric-api") || id.startsWith("fabric-") && !id.equals("fabric-language-kotlin")
				|| id.equals("velo-client") || id.equals("velo-addons");
	}

	/** Mods known to break each other that don't declare it (or declare it too loosely). */
	private static final String[][] KNOWN_CLASHES = {
			{"optifabric", "sodium", "OptiFine (OptiFabric) and Sodium both replace the renderer - they can't run together."},
			{"optifabric", "iris", "OptiFine (OptiFabric) and Iris both handle shaders - they can't run together."},
			{"canvas", "sodium", "Canvas and Sodium both replace the renderer - they can't run together."},
	};

	private Rules() {
	}

	static List<Issue> check(Snapshot s) {
		List<Issue> issues = new ArrayList<>();
		Map<String, String> provided = s.provided();
		List<ModScanner.ModInfo> enabled = s.enabledMods();

		for (ModScanner.ModInfo mod : s.mods()) {
			if (!mod.enabled()) {
				continue;
			}
			String file = mod.file().getFileName().toString();
			switch (mod.problem()) {
				case NOT_A_JAR -> issues.add(issue("broken:" + file, Severity.CRASH, file + " is damaged",
						"It isn't a readable mod file (a download that broke off, most likely). The game can't start with it.",
						fix("Remove it", "Moved to the profile's backups", new Action.Remove(mod.file(), file))));
				case FORGE, NEOFORGE -> issues.add(issue("wrong-loader:" + file, Severity.CRASH, file + " is a " + (mod.problem() == ModScanner.Problem.FORGE ? "Forge" : "NeoForge") + " mod",
						"Velo profiles run Fabric - this mod needs a different loader and stops the game from starting. Look for its Fabric version on Modrinth.",
						fix("Turn it off", null, new Action.Disable(mod.file(), file)), fix("Remove it", null, new Action.Remove(mod.file(), file))));
				case QUILT_ONLY -> issues.add(issue("wrong-loader:" + file, Severity.CRASH, file + " only works on Quilt",
						"Velo profiles run Fabric. This mod needs Quilt and stops the game from starting.",
						fix("Turn it off", null, new Action.Disable(mod.file(), file))));
				case NO_METADATA -> issues.add(issue("not-a-mod:" + file, Severity.TIP, file + " isn't a Fabric mod",
						"Fabric ignores it, so it does nothing here.", fix("Turn it off", null, new Action.Disable(mod.file(), file))));
				default -> {
				}
			}
		}

		// The same mod twice.
		Map<String, List<ModScanner.ModInfo>> byId = new LinkedHashMap<>();
		for (ModScanner.ModInfo mod : enabled) {
			if (mod.id() != null && !managed(mod.id())) {
				byId.computeIfAbsent(mod.id(), k -> new ArrayList<>()).add(mod);
			}
		}
		byId.forEach((id, copies) -> {
			if (copies.size() < 2) {
				return;
			}
			List<ModScanner.ModInfo> sorted = new ArrayList<>(copies);
			sorted.sort(Comparator.comparing((ModScanner.ModInfo m) -> m.version() == null ? "0" : m.version(), VersionPredicate::compare).reversed());
			List<Fix> fixes = new ArrayList<>();
			for (ModScanner.ModInfo keep : sorted) {
				List<Action> off = new ArrayList<>();
				for (ModScanner.ModInfo other : sorted) {
					if (other != keep) {
						off.add(new Action.Disable(other.file(), other.file().getFileName().toString()));
					}
				}
				fixes.add(new Fix((keep == sorted.get(0) ? "Keep the newest (" : "Keep ") + "v" + keep.version() + (keep == sorted.get(0) ? ")" : ""),
						"The other copies are turned off", off));
			}
			issues.add(new Issue("duplicate:" + id, Severity.CRASH, sorted.get(0).displayName() + " is installed " + copies.size() + " times",
					"Two copies of one mod stop the game from starting. Keep one.", fixes, List.of()));
		});

		Map<String, Integer> dependents = new LinkedHashMap<>();
		for (ModScanner.ModInfo mod : enabled) {
			for (String dep : mod.depends().keySet()) {
				dependents.merge(dep, 1, Integer::sum);
			}
		}

		for (ModScanner.ModInfo mod : enabled) {
			if (mod.id() == null || managed(mod.id())) {
				continue;
			}
			String name = mod.displayName();
			if ("server".equals(mod.environment())) {
				issues.add(issue("server-only:" + mod.id(), Severity.PROBLEM, name + " only works on servers",
						"It's made for a server, not for playing - it can stop the game from starting.", off(mod)));
			}
			for (Map.Entry<String, JsonElement> dep : mod.depends().entrySet()) {
				String id = dep.getKey();
				if (managed(id)) {
					continue;
				}
				String have = provided.get(id);
				if (have != null) {
					if (VersionPredicate.matches(dep.getValue(), have)) {
						continue;
					}
					String want = describe(dep.getValue());
					switch (id) {
						case "minecraft" -> issues.add(issue("wrong-mc:" + mod.id(), Severity.CRASH, name + " is made for Minecraft " + want,
								"This profile is Minecraft " + s.instance().mcVersion() + " - this version of " + name + " won't start on it.",
								fix("Update " + name, "Switches to its version for " + s.instance().mcVersion() + " (or turns it off if there's none yet)",
										new Action.Update(mod.file(), name, mod.id(), true)), off(mod)));
						case "java" -> issues.add(issue("wrong-java:" + mod.id(), Severity.CRASH, name + " needs Java " + want,
								"Minecraft " + s.instance().mcVersion() + " runs on Java " + s.javaMajor() + ".",
								fix("Update " + name, null, new Action.Update(mod.file(), name, mod.id(), true)), off(mod)));
						case "fabricloader", "fabric-loader" -> issues.add(issue("wrong-loader-version:" + mod.id(), Severity.CRASH,
								name + " needs Fabric Loader " + want, "Velo uses Fabric Loader " + s.loaderVersion() + " - this version of the mod expects another one.",
								fix("Update " + name, null, new Action.Update(mod.file(), name, mod.id(), true)), off(mod)));
						default -> {
							ModScanner.ModInfo depMod = s.enabledById(id);
							String depName = depMod != null ? depMod.displayName() : id;
							List<Fix> fixes = new ArrayList<>();
							if (depMod != null) {
								fixes.add(fix("Update " + depName, "Gets the newest " + depName + " for " + s.instance().mcVersion(),
										new Action.Update(depMod.file(), depName, id, false)));
							}
							fixes.add(fix("Update " + name, null, new Action.Update(mod.file(), name, mod.id(), false)));
							fixes.add(off(mod));
							issues.add(new Issue("dep-version:" + mod.id() + ":" + id, Severity.CRASH, name + " needs " + depName + " " + want,
									"You have " + depName + " " + have + ". The game won't start until they fit together.", fixes, List.of()));
						}
					}
					continue;
				}
				List<ModScanner.ModInfo> off = s.disabledProviders(id);
				if (!off.isEmpty()) {
					ModScanner.ModInfo depMod = off.get(0);
					issues.add(issue("dep-disabled:" + mod.id() + ":" + id, Severity.CRASH, name + " needs " + depMod.displayName() + ", which is turned off",
							"Without it the game can't start.",
							fix("Turn " + depMod.displayName() + " on", null, new Action.Enable(depMod.file(), depMod.displayName())), off(mod)));
				} else {
					String depName = prettyId(id);
					issues.add(issue("dep-missing:" + mod.id() + ":" + id, Severity.CRASH, name + " needs " + depName + ", which isn't installed",
							"Without it the game can't start.",
							fix("Install " + depName, "From Modrinth, the version for " + s.instance().mcVersion(), new Action.Install(id, depName, id, requirementText(dep.getValue()))), off(mod)));
				}
			}
			for (Map.Entry<String, JsonElement> b : mod.breaks().entrySet()) {
				clash(issues, s, provided, dependents, mod, b.getKey(), b.getValue(), Severity.CRASH);
			}
			for (Map.Entry<String, JsonElement> c : mod.conflicts().entrySet()) {
				clash(issues, s, provided, dependents, mod, c.getKey(), c.getValue(), Severity.PROBLEM);
			}
			String foreign = foreignClient(mod);
			if (foreign != null) {
				issues.add(issue("foreign-client:" + mod.id(), Severity.CRASH, name + " only works in the " + foreign + " launcher",
						"It was copied over from " + foreign + " and needs that launcher's own files - here it stops the game from starting.", off(mod)));
				continue;
			}
			String builtin = VeloBuiltins.featureFor(mod.id(), mod.name());
			if (builtin != null) {
				issues.add(issue("velo-has:" + mod.id(), Severity.TIP, "Velo already has " + builtin,
						name + " does what Velo's " + builtin + " already does - running both can double up or clash.", off(mod)));
			}
		}

		for (String[] clash : KNOWN_CLASHES) {
			ModScanner.ModInfo a = s.enabledById(clash[0]);
			ModScanner.ModInfo b = s.enabledById(clash[1]);
			if (a != null && b != null) {
				issues.add(issue("clash:" + clash[0] + ":" + clash[1], Severity.CRASH, a.displayName() + " and " + b.displayName() + " don't work together",
						clash[2], off(a), off(b)));
			}
		}

		ModScanner.ModInfo iris = s.enabledById("iris");
		if (iris != null && GraphicsApi.supported(s.instance().mcVersion()) && !GraphicsApi.OPENGL.equals(s.graphicsApi())) {
			// Shaders switched on: they silently don't work - a real problem. Switched off: Iris just idles.
			boolean shadersOn = irisShadersOn(s);
			issues.add(issue("iris-vulkan", shadersOn ? Severity.PROBLEM : Severity.TIP, "Iris needs OpenGL",
					shadersOn ? "This profile runs on Vulkan, where your shaders can't work." : "This profile runs on Vulkan - Iris does nothing there.",
					fix("Switch to OpenGL", "Applies on the next start", new Action.SetGraphicsApi(GraphicsApi.OPENGL)), off(iris)));
		}
		if (!s.shaders().isEmpty() && iris == null && s.enabledById("oculus") == null) {
			List<Action> install = new ArrayList<>(List.of(new Action.Install("iris", "Iris", "iris")));
			if (GraphicsApi.supported(s.instance().mcVersion())) {
				install.add(new Action.SetGraphicsApi(GraphicsApi.OPENGL));
			}
			issues.add(issue("shaders-no-iris", Severity.TIP, "Shader packs need Iris",
					"You have shader packs installed, but nothing that can run them.", new Fix("Install Iris", null, install)));
		}

		for (Snapshot.Pack pack : s.packs()) {
			if (pack.broken()) {
				issues.add(issue("pack-broken:" + pack.name(), Severity.PROBLEM, "The resource pack " + pack.name().replaceAll("\u00a7.", "") + " is broken",
						"It's empty or has no readable pack.mcmeta, so the game skips it or fails while loading.",
						fix("Turn it off", null, new Action.Disable(pack.file(), pack.name()))));
			}
		}

		long modCount = enabled.size();
		if (s.ramMaxMb() != null) {
			long physicalMb = physicalMemoryMb();
			if (s.ramMaxMb() < 2048 && modCount > 30) {
				issues.add(issue("memory-low", Severity.PROBLEM, "Not enough memory for " + modCount + " mods",
						"Only " + s.ramMaxMb() + " MB is set - big mod lists run out and crash.",
						fix("Use automatic memory", "Velo picks the right amount for your PC and mods", new Action.SetMemory(0))));
			} else if (physicalMb > 0 && s.ramMaxMb() > physicalMb * 0.8) {
				issues.add(issue("memory-high", Severity.PROBLEM, "Memory is set higher than your PC can give",
						s.ramMaxMb() + " MB of " + physicalMb + " MB - the system starts swapping and the game stutters or crashes.",
						fix("Use automatic memory", null, new Action.SetMemory(0))));
			}
		}
		return issues;
	}

	private static void clash(List<Issue> issues, Snapshot s, Map<String, String> provided, Map<String, Integer> dependents, ModScanner.ModInfo mod,
			String otherId, JsonElement requirement, Severity severity) {
		String have = provided.get(otherId);
		ModScanner.ModInfo other = s.enabledById(otherId);
		// "*" = a version the planner only imagines (it installs one that fits) - it never matches a "breaks" range.
		if (have == null || "*".equals(have) || other == null || other == mod || !VersionPredicate.matches(requirement, have)) {
			return;
		}
		// Turn off whichever fewer other mods need; on a tie the one that declared the clash (usually the add-on).
		boolean offOther = dependents.getOrDefault(otherId, 0) < dependents.getOrDefault(mod.id(), 0);
		Fix first = offOther ? off(other) : off(mod);
		Fix second = offOther ? off(mod) : off(other);
		issues.add(issue((severity == Severity.CRASH ? "breaks:" : "conflicts:") + mod.id() + ":" + otherId, severity,
				mod.displayName() + " doesn't work with " + other.displayName() + (severity == Severity.CRASH ? "" : " " + have),
				severity == Severity.CRASH ? "Its author says the game breaks with both installed. Keep one." : "Its author says they clash - expect bugs with both.",
				first, second));
	}

	private static Fix off(ModScanner.ModInfo mod) {
		return fix("Turn " + mod.displayName() + " off", "It stays installed - turn it back on any time", new Action.Disable(mod.file(), mod.displayName()));
	}

	private static Fix fix(String label, String detail, Action... actions) {
		return new Fix(label, detail, List.of(actions));
	}

	private static Issue issue(String id, Severity severity, String title, String detail, Fix... fixes) {
		return new Issue(id, severity, title, detail, List.of(fixes), List.of());
	}

	/** The raw range ("&gt;=0.29.5- &lt;0.30.0-") for picking a fitting version; null for "any". */
	static String requirementText(JsonElement requirement) {
		if (requirement == null || requirement.isJsonNull()) {
			return null;
		}
		String r = requirement.isJsonPrimitive() ? requirement.getAsString() : requirement.isJsonArray() && requirement.getAsJsonArray().size() == 1
				? requirement.getAsJsonArray().get(0).getAsString() : null;
		return r == null || r.isBlank() || r.equals("*") ? null : r;
	}

	/** {@code ">=0.5.8"} -> "0.5.8 or newer" - what the player reads. */
	static String describe(JsonElement requirement) {
		String r = requirement == null || !requirement.isJsonPrimitive() ? String.valueOf(requirement) : requirement.getAsString();
		r = r.replace("\"", "").replace("[", "").replace("]", "").strip();
		if (r.startsWith(">=")) {
			return r.substring(2) + " or newer";
		}
		if (r.startsWith("~") || r.startsWith("^")) {
			return r.substring(1);
		}
		return r;
	}

	/** "cloth-config" -> "Cloth Config" (good enough until Modrinth answers with the real title). */
	static String prettyId(String id) {
		Set<String> upper = Set.of("api", "lib", "ui", "hud", "gui", "jei", "emi", "rei", "yacl");
		StringBuilder out = new StringBuilder();
		for (String part : id.split("[-_]")) {
			if (part.isEmpty()) {
				continue;
			}
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(upper.contains(part) ? part.toUpperCase() : Character.toUpperCase(part.charAt(0)) + part.substring(1));
		}
		return out.toString();
	}

	/** Mods that belong to another client's launcher (imported by "Switch from another client"), or null. */
	private static String foreignClient(ModScanner.ModInfo mod) {
		String id = mod.id() == null ? "" : mod.id().toLowerCase(java.util.Locale.ROOT);
		for (String pkg : mod.packages()) {
			if (pkg.startsWith("net.digitalingot")) {
				return "Feather";
			}
			if (pkg.startsWith("com.moonsworth") || pkg.startsWith("gg.lunarclient")) {
				return "Lunar";
			}
			if (pkg.startsWith("net.badlion")) {
				return "Badlion";
			}
		}
		return id.equals("feather") || id.startsWith("feather-") ? "Feather" : null;
	}

	static boolean irisShadersOn(Snapshot s) {
		try {
			java.nio.file.Path props = net.veloclient.launcher.instance.InstancePaths.gameDir(s.instance().id()).resolve("config").resolve("iris.properties");
			if (!java.nio.file.Files.exists(props)) {
				return false;
			}
			java.util.Properties p = new java.util.Properties();
			try (var in = java.nio.file.Files.newInputStream(props)) {
				p.load(in);
			}
			return "true".equals(p.getProperty("enableShaders")) && !p.getProperty("shaderPack", "").isBlank();
		} catch (Exception e) {
			return false;
		}
	}

	private static long physicalMemoryMb() {
		try {
			var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
			return os.getTotalMemorySize() / (1024 * 1024);
		} catch (Throwable e) {
			return 0;
		}
	}
}
