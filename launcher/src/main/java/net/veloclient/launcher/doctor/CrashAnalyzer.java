package net.veloclient.launcher.doctor;

import net.veloclient.launcher.doctor.Diagnosis.Action;
import net.veloclient.launcher.doctor.Diagnosis.Fix;
import net.veloclient.launcher.doctor.Diagnosis.Issue;
import net.veloclient.launcher.doctor.Diagnosis.Severity;
import net.veloclient.launcher.launch.GraphicsApi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a crash report (or the end of the game log) and says who did it: out of memory, a mod's mixin failing, the
 * mod whose code is at the top of the stack, a class from a mod that's missing or turned off, a mod built for a newer
 * Java, Vulkan / OpenGL driver failures, resource packs or shaders breaking the load. Each finding becomes a problem
 * with fixes, like the pre-launch rules. Also gives the crash a signature - same cause, same signature, every time -
 * so fixes that didn't help are remembered per crash.
 */
final class CrashAnalyzer {

	record Result(String signature, String summary, List<Issue> issues) {
	}

	private static final Pattern EXCEPTION = Pattern.compile("(?m)^(?:Caused by: )?([\\w.$]+(?:Exception|Error|Throwable))(?::\\s?(.*))?$");
	/** "at knot//net.x.Y.m(", "at java.base/java.util.Z.m(", "at a.B.m(" - the loader prefix has one or two slashes. */
	private static final Pattern FRAME = Pattern.compile("(?m)^\\s+at (?:[\\w.\\-]*/+)?([\\w.$]+)\\.([\\w$<>\\-]+)\\(");
	/** Mixin-injected methods carry their mod id: "handler$zza000$sodium$onRender", "wrapOperation$bhl000$fabric-screen-api-v1$...". */
	private static final Pattern MIXIN_HANDLER = Pattern.compile("^(?:handler|modify|redirect|localvar|wrapOperation|wrapWithCondition|modifyExpressionValue|modifyReturnValue|modifyArg|modifyArgs|modifyVariable|modifyConstant)\\$[a-z0-9]+\\$([a-z0-9_\\-]+)\\$");
	private static final Pattern MIXIN_MOD = Pattern.compile("(?i)(?:Mixin apply for mod|from mod|mod) ([a-z0-9_\\-]+)(?: failed|\\b)");
	private static final Pattern MIXIN_CONFIG = Pattern.compile("([\\w\\-.]+\\.mixins?\\.json)");
	private static final Pattern MISSING_CLASS = Pattern.compile("(?:NoClassDefFoundError|ClassNotFoundException): ([\\w/$.]+)");
	private static final Pattern CLASS_VERSION = Pattern.compile("([\\w/$.]+) has been compiled by a more recent version of the Java Runtime \\(class file version (\\d+)");
	private static final Pattern VULKAN = Pattern.compile("(?i)(VK_ERROR_\\w+|vkCreate\\w+ failed|Vulkan (?:device|instance|initiali[sz]ation) (?:lost|failed)|VulkanException)");
	private static final Pattern OPENGL_DRIVER = Pattern.compile("(?i)(GLFW error 6554[23]|WGL: The driver does not appear to support OpenGL|OpenGL 3\\.\\d+ (?:is )?not supported|Couldn't set pixel format)");

	private static final Pattern ENTRYPOINT = Pattern.compile("entrypoint stage '\\w+' due to errors, provided by '([a-z0-9_\\-]+)'");
	/** Fabric's own "Incompatible mods found!" lines. */
	private static final Pattern FABRIC_MISSING = Pattern.compile(
			"- Mod '(.+?)' \\(([a-z0-9_\\-]+)\\) \\S+ requires (.+?) of (?:mod )?(?:'(.+?)' \\()?([a-z0-9_\\-]+)\\)?, which is missing!");
	private static final Pattern FABRIC_WRONG = Pattern.compile(
			"- Mod '(.+?)' \\(([a-z0-9_\\-]+)\\) \\S+ requires (.+?) of (?:mod )?'(.+?)' \\(([a-z0-9_\\-]+)\\), but only the wrong version is present: (\\S+?)!");
	private static final Pattern FABRIC_CLASH = Pattern.compile(
			"- Mod '(.+?)' \\(([a-z0-9_\\-]+)\\) \\S+ is incompatible with (.+?) of mod '(.+?)' \\(([a-z0-9_\\-]+)\\), yet a conflicting version is present");

	/** Libraries players often miss, by package -> Modrinth project. */
	private static final Map<String, String[]> LIBRARIES = new LinkedHashMap<>();

	static {
		LIBRARIES.put("me.shedaniel.clothconfig", new String[] {"cloth-config", "Cloth Config", "cloth-config"});
		LIBRARIES.put("me.shedaniel.autoconfig", new String[] {"cloth-config", "Cloth Config", "cloth-config"});
		LIBRARIES.put("dev.isxander.yacl", new String[] {"yacl", "YetAnotherConfigLib", "yet_another_config_lib_v3"});
		LIBRARIES.put("com.terraformersmc.modmenu", new String[] {"modmenu", "Mod Menu", "modmenu"});
		LIBRARIES.put("dev.architectury", new String[] {"architectury-api", "Architectury API", "architectury"});
		LIBRARIES.put("software.bernie.geckolib", new String[] {"geckolib", "GeckoLib", "geckolib"});
		LIBRARIES.put("eu.midnightdust.lib", new String[] {"midnightlib", "MidnightLib", "midnightlib"});
		LIBRARIES.put("fuzs.forgeconfigapiport", new String[] {"forge-config-api-port", "Forge Config API Port", "forgeconfigapiport"});
		LIBRARIES.put("net.fabricmc.language.kotlin", new String[] {"fabric-language-kotlin", "Fabric Language Kotlin", "fabric-language-kotlin"});
		LIBRARIES.put("kotlin", new String[] {"fabric-language-kotlin", "Fabric Language Kotlin", "fabric-language-kotlin"});
		LIBRARIES.put("com.llamalad7.mixinextras", new String[] {"mixinextras", "MixinExtras", "mixinextras"});
		LIBRARIES.put("net.caffeinemc.mods.sodium", new String[] {"sodium", "Sodium", "sodium"});
		LIBRARIES.put("me.jellysquid.mods.sodium", new String[] {"sodium", "Sodium", "sodium"});
		LIBRARIES.put("net.irisshaders.iris", new String[] {"iris", "Iris", "iris"});
		LIBRARIES.put("dev.emi.emi", new String[] {"emi", "EMI", "emi"});
		LIBRARIES.put("mezz.jei", new String[] {"jei", "JEI", "jei"});
		LIBRARIES.put("com.mrcrayfish.framework", new String[] {"framework", "Framework", "framework"});
		LIBRARIES.put("team.reborn.energy", new String[] {"tech-reborn-energy", "Energy", "team_reborn_energy"});
	}

	/** Packages that are never a mod's fault on their own. */
	private static boolean platform(String className) {
		return className.startsWith("java.") || className.startsWith("jdk.") || className.startsWith("sun.") || className.startsWith("com.sun.")
				|| className.startsWith("net.minecraft.") || className.startsWith("com.mojang.") || className.startsWith("net.fabricmc.loader.")
				|| className.startsWith("org.spongepowered.") || className.startsWith("org.lwjgl.") || className.startsWith("io.netty.")
				|| className.startsWith("com.google.") || className.startsWith("org.apache.") || className.startsWith("it.unimi.")
				|| className.startsWith("com.llamalad7.mixinextras.") || className.startsWith("org.objectweb.") || className.startsWith("cpw.");
	}

	private CrashAnalyzer() {
	}

	static Result analyze(Snapshot s, String crash, String log) {
		String text = crash != null ? crash : log;
		if (text == null || text.isBlank()) {
			return new Result(null, null, List.of());
		}
		String signature = signature(text);
		String summary = summary(crash, log);
		List<Issue> issues = new ArrayList<>();
		Map<String, ModScanner.ModInfo> byPackage = packageIndex(s);
		// Who-did-it only looks at real errors: logs are full of harmless WARN lines ("Error loading class: mezz/jei...",
		// "@Mixin target ... was not found ... velo-client") that used to blame mods that had nothing to do with it.
		String trace = errorsOnly(text);

		fabricResolution(s, text, issues);
		nativeCrash(s, text, issues);
		gameFiles(s, text, issues);

		if (text.contains("java.lang.OutOfMemoryError")) {
			int now = s.ramMaxMb() != null ? s.ramMaxMb() : net.veloclient.launcher.launch.MemoryPlanner.plan(s.instance()).maxMb();
			int more = Math.min(now + 2048, (int) (physicalMb() * 0.7));
			List<Fix> fixes = new ArrayList<>();
			if (more > now) {
				fixes.add(new Fix("Give it " + more + " MB", "Up from " + now + " MB", List.of(new Action.SetMemory(more))));
			}
			fixes.add(new Fix("Use automatic memory", null, List.of(new Action.SetMemory(0))));
			issues.add(new Issue("oom", Severity.CRASH, "Minecraft ran out of memory",
					"It had " + now + " MB and needed more - big mod lists, high render distance and shaders all add up.", fixes, List.of()));
		}

		// MixinExtras inside an older loader casting another mod's annotations (YACL & co.): fixed in the loader's newer one.
		// The loader that really ran (the log says), else the profile's setting.
		Matcher ranLoader = RAN_LOADER.matcher(text);
		String loaderRan = ranLoader.find() ? ranLoader.group(1) : s.loaderVersion();
		if (trace.contains("com.llamalad7.mixinextras") && trace.contains("ClassCastException")
				&& loaderRan != null && net.veloclient.launcher.launch.LoaderVersions.compare(loaderRan, "0.19.5") < 0) {
			issues.add(new Issue("mixinextras-loader", Severity.CRASH, "This Fabric Loader is too old for your mods",
					"Fabric Loader " + loaderRan + " ships a MixinExtras that breaks on some mods' code. 0.19.5 fixes it.",
					List.of(new Fix("Use Fabric Loader 0.19.5", "Velo's tested version", List.of(new Action.SetLoader("0.19.5")))), List.of()));
		}

		Matcher version = CLASS_VERSION.matcher(trace);
		if (version.find()) {
			int needs = Integer.parseInt(version.group(2)) - 44;
			ModScanner.ModInfo mod = owner(byPackage, version.group(1).replace('/', '.'));
			if (mod != null) {
				issues.add(new Issue("culprit-java:" + mod.id(), Severity.CRASH, mod.displayName() + " needs Java " + needs,
						"Minecraft " + s.instance().mcVersion() + " runs on Java " + s.javaMajor() + " - this version of the mod was built for a newer one.",
						List.of(update(mod, true), off(mod)), List.of()));
			}
		}

		if (VULKAN.matcher(text).find() && GraphicsApi.supported(s.instance().mcVersion()) && !GraphicsApi.OPENGL.equals(s.graphicsApi())) {
			issues.add(new Issue("vulkan-crash", Severity.CRASH, "Vulkan failed on your graphics card",
					"The graphics driver stopped working with Vulkan. OpenGL usually runs fine on the same PC.",
					List.of(new Fix("Switch to OpenGL", "Applies on the next start", List.of(new Action.SetGraphicsApi(GraphicsApi.OPENGL)))), List.of()));
		} else if (OPENGL_DRIVER.matcher(text).find()) {
			List<Fix> fixes = new ArrayList<>();
			if (GraphicsApi.supported(s.instance().mcVersion()) && GraphicsApi.OPENGL.equals(s.graphicsApi())) {
				fixes.add(new Fix("Switch to Vulkan", null, List.of(new Action.SetGraphicsApi(GraphicsApi.VULKAN))));
			}
			issues.add(new Issue("opengl-driver", Severity.CRASH, "Your graphics driver can't run Minecraft",
					"Minecraft couldn't get OpenGL from the graphics driver. Updating the driver (NVIDIA / AMD / Intel website) almost always fixes this.",
					fixes, List.of()));
		}

		Matcher missing = MISSING_CLASS.matcher(trace);
		String missingClass = null;
		while (missing.find()) {
			missingClass = missing.group(1); // the deepest "Caused by" is the real one
		}
		if (missingClass != null) {
			String cls = missingClass.replace('/', '.');
			ModScanner.ModInfo off = disabledOwner(s, cls);
			String[] lib = library(cls);
			if (off != null) {
				issues.add(new Issue("missing-class-off:" + off.id(), Severity.CRASH, off.displayName() + " is turned off, but another mod needs it",
						"A mod tried to use " + off.displayName() + " and the game crashed.",
						List.of(new Fix("Turn " + off.displayName() + " on", null, List.of(new Action.Enable(off.file(), off.displayName())))), List.of()));
			} else if (lib != null && s.enabledById(lib[2]) == null) {
				issues.add(new Issue("missing-lib:" + lib[2], Severity.CRASH, "A mod needs " + lib[1] + ", which isn't installed",
						"The game crashed looking for it.", List.of(new Fix("Install " + lib[1], null, List.of(new Action.Install(lib[0], lib[1], lib[2])))), List.of()));
			}
		}

		// Who's to blame: a failing mixin names its mod outright; otherwise the first mod frame on the stack.
		ModScanner.ModInfo culprit = null;
		Matcher config = MIXIN_CONFIG.matcher(trace);
		while (culprit == null && config.find()) {
			culprit = mixinOwner(s, config.group(1));
		}
		// "Could not execute entrypoint stage 'main' due to errors, provided by 'voicechat'" names the mod outright.
		Matcher entrypoint = ENTRYPOINT.matcher(trace);
		while (culprit == null && entrypoint.find()) {
			culprit = s.enabledById(entrypoint.group(1));
		}
		Matcher mixinMod = MIXIN_MOD.matcher(trace);
		while (culprit == null && mixinMod.find()) {
			culprit = s.enabledById(mixinMod.group(1));
		}
		if (culprit == null) {
			culprit = stackCulprit(trace, byPackage, s::enabledById);
		}
		if (text.contains("java.lang.OutOfMemoryError")) {
			culprit = null; // whatever happened to run when memory ran out isn't the cause
		}
		// Only the error itself: every log of an Iris player mentions net.irisshaders and "shader" somewhere, which made
		// unrelated crashes (a watchdog at shutdown...) read as "the shader pack crashed the game".
		String primary = primaryTrace(crash, trace);
		boolean shaderCrash = (culprit != null && "iris".equals(culprit.id()) || primary.contains("net.irisshaders"))
				&& primary.toLowerCase(java.util.Locale.ROOT).contains("shader");
		if (shaderCrash) {
			List<Fix> fixes = new ArrayList<>(List.of(new Fix("Turn shaders off", "The shader pack stays installed", List.of(new Action.DisableShaders()))));
			ModScanner.ModInfo iris = s.enabledById("iris");
			if (iris != null) {
				fixes.add(off(iris));
			}
			issues.add(new Issue("shader-crash", Severity.CRASH, "The shader pack crashed the game",
					"Iris couldn't run the selected shader pack - it may not support this Minecraft version or your graphics card.", fixes, List.of()));
		} else if (culprit != null) {
			if (isVelo(culprit)) {
				issues.add(new Issue("culprit-velo", Severity.CRASH, "Velo Client crashed",
						"This one is on us. Send the report below so we can fix it - it includes everything we need.", List.of(), List.of()));
			} else {
				issues.add(new Issue("culprit:" + culprit.id(), Severity.CRASH, culprit.displayName() + " crashed the game",
						"The crash happened inside " + culprit.displayName() + ". A newer version usually fixes it; otherwise turn it off.",
						List.of(update(culprit, true), off(culprit)), List.of()));
			}
		}

		if (issues.isEmpty() && resourceReload(text)) {
			List<Action> packsOff = new ArrayList<>();
			for (Snapshot.Pack pack : s.packs()) {
				if (pack.enabled()) {
					packsOff.add(new Action.Disable(pack.file(), pack.name()));
				}
			}
			if (!packsOff.isEmpty()) {
				issues.add(new Issue("packs-crash", Severity.CRASH, "A resource pack broke the game while loading",
						"Minecraft crashed while loading resource packs.",
						List.of(new Fix("Turn resource packs off", packsOff.size() + " packs - turn them back on one by one to find the bad one", packsOff)),
						List.of()));
			}
		}
		return new Result(signature, summary, issues);
	}

	/**
	 * Fabric names every missing / wrong / clashing mod when it refuses to start - read that directly, so the Doctor
	 * can fix it even when its own pre-launch check had nothing to go on. Same problem ids as {@link Rules}, so a
	 * problem found both ways shows once.
	 */
	private static void fabricResolution(Snapshot s, String text, List<Issue> issues) {
		if (!text.contains("Incompatible mods found") && !text.contains("Some of your mods are incompatible")) {
			return;
		}
		java.util.Set<String> seen = new java.util.HashSet<>();
		Matcher missing = FABRIC_MISSING.matcher(text);
		while (missing.find()) {
			String modName = missing.group(1);
			String modId = missing.group(2);
			String depId = missing.group(5);
			String depName = missing.group(4) != null ? missing.group(4) : Rules.prettyId(depId);
			if (!seen.add("missing:" + modId + ":" + depId)) {
				continue;
			}
			ModScanner.ModInfo mod = s.enabledById(modId);
			List<Fix> fixes = new ArrayList<>();
			List<ModScanner.ModInfo> off = s.disabledProviders(depId);
			if (!off.isEmpty()) {
				fixes.add(new Fix("Turn " + off.get(0).displayName() + " on", null, List.of(new Action.Enable(off.get(0).file(), off.get(0).displayName()))));
			} else if (!depId.equals("minecraft") && !depId.equals("java")) {
				fixes.add(new Fix("Install " + depName, "From Modrinth, a version that fits", List.of(new Action.Install(depId, depName, depId, fabricRange(missing.group(3))))));
			}
			if (mod != null) {
				fixes.add(off(mod));
			}
			issues.add(new Issue("dep-missing:" + modId + ":" + depId, Severity.CRASH, modName + " needs " + depName + ", which isn't installed",
					"Without it the game can't start.", fixes, List.of()));
		}
		Matcher wrong = FABRIC_WRONG.matcher(text);
		while (wrong.find()) {
			String modName = wrong.group(1);
			String modId = wrong.group(2);
			String depName = wrong.group(4);
			String depId = wrong.group(5);
			if (!seen.add("wrong:" + modId + ":" + depId)) {
				continue;
			}
			ModScanner.ModInfo mod = s.enabledById(modId);
			ModScanner.ModInfo dep = s.enabledById(depId);
			List<Fix> fixes = new ArrayList<>();
			if (depId.equals("minecraft")) {
				if (mod != null) {
					fixes.add(update(mod, true));
					fixes.add(off(mod));
				}
				issues.add(new Issue("wrong-mc:" + modId, Severity.CRASH, modName + " is made for another Minecraft version",
						"It needs " + wrong.group(3).replace("any version ", "") + ", this profile is " + s.instance().mcVersion() + ".", fixes, List.of()));
				continue;
			}
			if (depId.equals("fabricloader")) {
				// The loader isn't a file in mods/: switch the profile to a build that fits (the version Fabric names).
				Matcher at = VERSION_NUMBER.matcher(wrong.group(3));
				if (at.find()) {
					fixes.add(new Fix("Use Fabric Loader " + at.group(), "Instead of " + wrong.group(6) + " - only for this profile",
							List.of(new Action.SetLoader(at.group()))));
				}
				if (mod != null) {
					fixes.add(update(mod, false));
					fixes.add(off(mod));
				}
				issues.add(new Issue("dep-version:" + modId + ":fabricloader", Severity.CRASH, modName + " needs a newer Fabric Loader",
						"It wants " + wrong.group(3).replace("any version ", "") + ", this profile runs " + wrong.group(6) + ".", fixes, List.of()));
				continue;
			}
			if (depId.equals("java")) {
				Matcher at = VERSION_NUMBER.matcher(wrong.group(3));
				String wanted = at.find() ? at.group() : wrong.group(3);
				if (mod != null) {
					fixes.add(update(mod, true));
					fixes.add(off(mod));
				}
				issues.add(new Issue("dep-version:" + modId + ":java", Severity.CRASH, modName + " needs Java " + wanted,
						"It only runs on " + wrong.group(3).replace("any version ", "") + " of Java, the game had Java " + wrong.group(6)
								+ ". Velo starts the next launch with the Java the mod accepts when it can; a newer version of the mod fixes it for good.",
						fixes, List.of()));
				continue;
			}
			if (dep != null) {
				fixes.add(new Fix("Get a fitting " + depName, "Switches " + depName + " to a version " + modName + " accepts",
						List.of(new Action.Update(dep.file(), dep.displayName(), depId, false))));
			}
			if (mod != null) {
				fixes.add(update(mod, false));
				fixes.add(off(mod));
			}
			issues.add(new Issue("dep-version:" + modId + ":" + depId, Severity.CRASH, modName + " needs a different " + depName,
					"It wants " + wrong.group(3).replace("any version ", "") + ", you have " + wrong.group(6) + ".", fixes, List.of()));
		}
		Matcher clash = FABRIC_CLASH.matcher(text);
		while (clash.find()) {
			String aId = clash.group(2);
			String bId = clash.group(5);
			if (!seen.add("clash:" + aId + ":" + bId)) {
				continue;
			}
			ModScanner.ModInfo a = s.enabledById(aId);
			ModScanner.ModInfo b = s.enabledById(bId);
			List<Fix> fixes = new ArrayList<>();
			// First offer to turn off the one fewer other mods need (Sodium next to VulkanMod + its add-ons, say).
			boolean aFirst = a != null && b != null && dependents(s, aId) < dependents(s, bId);
			if (aFirst) {
				fixes.add(off(a));
				fixes.add(off(b));
			} else {
				if (b != null) {
					fixes.add(off(b));
				}
				if (a != null) {
					fixes.add(off(a));
				}
			}
			issues.add(new Issue("breaks:" + aId + ":" + bId, Severity.CRASH, clash.group(1) + " doesn't work with " + clash.group(4),
					"Fabric won't start with both installed. Keep one.", fixes, List.of()));
		}
	}

	/** How many enabled mods list {@code id} as a dependency. */
	private static int dependents(Snapshot s, String id) {
		int count = 0;
		for (ModScanner.ModInfo mod : s.enabledMods()) {
			if (mod.depends() != null && mod.depends().containsKey(id)) {
				count++;
			}
		}
		return count;
	}

	private static final Pattern RAN_LOADER = Pattern.compile("with Fabric Loader (\\d[\\w.+\\-]*)");
	private static final Pattern VERSION_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)*(?:[-+][\\w.]+)?");

	/**
	 * The error itself: in a crash report the part before the "detailed walkthrough" (the exception and its causes),
	 * otherwise the log's error lines.
	 */
	static String primaryTrace(String crash, String trace) {
		if (crash != null) {
			int end = crash.indexOf("A detailed walkthrough of the error");
			return end > 0 ? crash.substring(0, end) : crash;
		}
		return trace;
	}

	private static final Pattern LOG_NOISE = Pattern.compile("^\\[[\\d:.]+\\] \\[[^\\]]*/(?:WARN|INFO|DEBUG|TRACE)\\]");

	/** A stack trace's own lines: the exception, "at ...", "Caused by:", "... 3 more". */
	private static final Pattern TRACE_LINE = Pattern.compile("^(?:\\s+at |\\s*\\.\\.\\. \\d+ more|Caused by: |Suppressed: |\\s+Suppressed: |[\\w.$]+(?:Exception|Error|Throwable)(?::|$))");

	/**
	 * The text without INFO/WARN log lines - ERROR lines, their stack traces and crash report sections stay. A stack trace
	 * printed under a WARN/INFO line belongs to that line and goes too: "Velo friends: request timed out" with its trace
	 * (an offline PC) used to read as "Velo Client crashed".
	 */
	static String errorsOnly(String text) {
		StringBuilder out = new StringBuilder(text.length());
		boolean inNoise = false;
		for (String line : text.split("\\R")) {
			if (LOG_NOISE.matcher(line).find()) {
				inNoise = true;
				continue;
			}
			if (inNoise && TRACE_LINE.matcher(line).find()) {
				continue;
			}
			inNoise = false;
			out.append(line).append('\n');
		}
		return out.toString();
	}

	/**
	 * The JVM itself died (hs_err_pid*.log, "A fatal error has been detected"): usually the graphics driver or memory.
	 * Says which, from the problematic frame / the out-of-memory note.
	 */
	private static void nativeCrash(Snapshot s, String text, List<Issue> issues) {
		if (!text.contains("A fatal error has been detected by the Java Runtime Environment") && !text.contains("There is insufficient memory for the Java Runtime")) {
			return;
		}
		if (text.contains("There is insufficient memory") || text.contains("Native memory allocation (malloc) failed") || text.contains("Out of Memory Error")) {
			issues.add(new Issue("native-oom", Severity.CRASH, "Your PC ran out of memory",
					"Windows / Linux couldn't give Minecraft more memory - close other programs, or let Velo pick a smaller amount.",
					List.of(new Fix("Use automatic memory", "Velo picks an amount that fits your free memory", List.of(new Action.SetMemory(0)))), List.of()));
			return;
		}
		Matcher frame = Pattern.compile("Problematic frame:\\s*\\R#\\s*\\S+\\s+\\[([^+\\]]+)").matcher(text);
		String lib = frame.find() ? frame.group(1).toLowerCase(java.util.Locale.ROOT) : "";
		boolean gpu = lib.matches(".*(nvoglv|nvwgf|nvd3d|atio6|atig6|amdvlk|amdxx|ig\\d+icd|igvk|vulkan-1|libvulkan|libnvidia|radeonsi|iris_dri|opengl32|libgl|mesa).*");
		if (gpu) {
			List<Fix> fixes = new ArrayList<>();
			if (GraphicsApi.supported(s.instance().mcVersion())) {
				String other = GraphicsApi.OPENGL.equals(s.graphicsApi()) ? GraphicsApi.VULKAN : GraphicsApi.OPENGL;
				fixes.add(new Fix("Switch to " + (other.equals(GraphicsApi.OPENGL) ? "OpenGL" : "Vulkan"), "The other graphics API often avoids the driver bug",
						List.of(new Action.SetGraphicsApi(other))));
			}
			issues.add(new Issue("gpu-driver-crash", Severity.CRASH, "Your graphics driver crashed",
					"The crash happened inside " + lib + " - updating the graphics driver (NVIDIA / AMD / Intel website) fixes this almost always.",
					fixes, List.of()));
		}
	}

	private static final Pattern MOD_FILE_GONE = Pattern.compile("NoSuchFileException: (\\S*[\\\\/]mods[\\\\/]([^\\\\/\\r\\n]+\\.jar))");

	/**
	 * Files under the game broke rather than a mod: Fabric's remapped game jar half-written (the PC ran out of memory or
	 * disk, an antivirus held / removed the .tmp) - every later start reads the broken jar again, so clear it; or a mod
	 * jar that vanished between Fabric listing the folder and opening it.
	 */
	private static void gameFiles(Snapshot s, String text, List<Issue> issues) {
		String trace = errorsOnly(text);
		boolean brokenRemap = text.contains("remappedJars") && (trace.contains("ZipException") || trace.contains("zip END header")
				|| trace.contains("error remapping game jars") || trace.contains("NoSuchFileException") || trace.contains("AccessDeniedException"))
				|| trace.contains("error remapping game jars");
		if (brokenRemap) {
			issues.add(new Issue("fabric-cache", Severity.CRASH, "Fabric's game files are broken",
					"Fabric couldn't finish preparing Minecraft (low memory or disk space, or an antivirus took its temporary file)."
							+ " Velo clears them and Fabric builds them again on the next start - add the VeloClient folder to your"
							+ " antivirus exceptions if this keeps happening.",
					List.of(new Fix("Rebuild Fabric's game files", "Takes a minute on the next start", List.of(new Action.ClearFabricCache()))),
					List.of()));
		}
		Matcher gone = MOD_FILE_GONE.matcher(trace);
		if (gone.find() && trace.contains("ModResolutionException")) {
			issues.add(new Issue("mod-file-gone", Severity.CRASH, gone.group(2) + " disappeared while the game started",
					"Something removed or locked that mod file right after Fabric found it - usually an antivirus, or the mods"
							+ " folder changed while Minecraft was starting. Start again; add the VeloClient folder to your antivirus"
							+ " exceptions if it happens again.",
					List.of(), List.of()));
		}
		// Vanilla code dying on a null particle (firework sparks...): a particle mod returned none. Nothing else on the stack.
		if (trace.contains("Ticking Particle") || text.contains("Description: Ticking Particle")) {
			List<Action> off = new ArrayList<>();
			List<String> names = new ArrayList<>();
			for (ModScanner.ModInfo mod : s.enabledMods()) {
				String id = mod.id() == null ? "" : mod.id().toLowerCase(java.util.Locale.ROOT);
				String name = mod.displayName() == null ? "" : mod.displayName().toLowerCase(java.util.Locale.ROOT);
				if ((id.contains("particle") || name.contains("particle")) && !isVelo(mod)) {
					off.add(new Action.Disable(mod.file(), mod.displayName()));
					names.add(mod.displayName());
				}
			}
			if (!off.isEmpty()) {
				issues.add(new Issue("particle-mod", Severity.CRASH, "A particle mod broke a particle",
						"Minecraft crashed updating a particle another mod changed or removed - " + String.join(", ", names) + ".",
						List.of(new Fix("Turn " + (names.size() == 1 ? names.get(0) : "particle mods") + " off", null, off)), List.of()));
			}
		}
	}

	/** "any version between 0.29.5- (inclusive) and 0.30.0- (exclusive)" -> ">=0.29.5- <0.30.0-"; "version 5.1.0 or later" -> ">=5.1.0". */
	static String fabricRange(String words) {
		Matcher between = Pattern.compile("between (\\S+) \\(inclusive\\) and (\\S+) \\(exclusive\\)").matcher(words);
		if (between.find()) {
			return ">=" + between.group(1) + " <" + between.group(2);
		}
		Matcher later = Pattern.compile("version (\\S+) or later").matcher(words);
		if (later.find()) {
			return ">=" + later.group(1);
		}
		Matcher exact = Pattern.compile("^version (\\S+)$").matcher(words.strip());
		return exact.find() ? exact.group(1) : null;
	}

	/** Same cause, same signature: the exception type and the first few frames that aren't the JDK, numbers removed. */
	static String signature(String text) {
		StringBuilder key = new StringBuilder();
		Matcher exception = EXCEPTION.matcher(text);
		String root = null;
		while (exception.find()) {
			root = exception.group(1); // the last "Caused by" is the real one
		}
		key.append(root == null ? "none" : root);
		Matcher frame = FRAME.matcher(text);
		int frames = 0;
		while (frame.find() && frames < 4) {
			String cls = frame.group(1);
			if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")) {
				continue;
			}
			key.append('|').append(cls.replaceAll("\\$\\d+", "\\$")).append('.').append(frame.group(2).replaceAll("\\$\\d+$", ""));
			frames++;
		}
		if (root == null && frames == 0) {
			// No stack (a Fabric loader message): the first line that says what's wrong.
			String first = text.lines().filter(l -> l.contains("requires") || l.contains("incompatible") || l.contains("Error")).findFirst().orElse("unknown");
			key.append('|').append(first.replaceAll("\\d+", "#").strip());
		}
		return Snapshot.hash(key.toString());
	}

	private static String summary(String crash, String log) {
		try {
			return net.veloclient.launcher.ui.CrashReportDialog.causeOf(crash, log);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Map<String, ModScanner.ModInfo> packageIndex(Snapshot s) {
		Map<String, ModScanner.ModInfo> index = new LinkedHashMap<>();
		for (ModScanner.ModInfo mod : s.enabledMods()) {
			for (String pkg : mod.packages()) {
				index.putIfAbsent(pkg, mod);
			}
		}
		return index;
	}

	private static ModScanner.ModInfo owner(Map<String, ModScanner.ModInfo> byPackage, String className) {
		String[] parts = className.split("\\.");
		for (int depth = Math.min(3, parts.length - 1); depth >= 2; depth--) {
			ModScanner.ModInfo mod = byPackage.get(String.join(".", java.util.Arrays.copyOf(parts, depth)));
			if (mod != null) {
				return mod;
			}
		}
		return null;
	}

	private static ModScanner.ModInfo disabledOwner(Snapshot s, String className) {
		for (ModScanner.ModInfo mod : s.mods()) {
			if (!mod.enabled() && mod.problem() == ModScanner.Problem.NONE) {
				for (String pkg : mod.packages()) {
					if (className.startsWith(pkg + ".")) {
						return mod;
					}
				}
			}
		}
		return null;
	}

	private static String[] library(String className) {
		for (Map.Entry<String, String[]> e : LIBRARIES.entrySet()) {
			if (className.startsWith(e.getKey() + ".")) {
				return e.getValue();
			}
		}
		return null;
	}

	private static ModScanner.ModInfo mixinOwner(Snapshot s, String config) {
		for (ModScanner.ModInfo mod : s.enabledMods()) {
			if (mod.mixinConfigs().contains(config)) {
				return mod;
			}
			for (ModScanner.ModInfo nested : mod.nested()) {
				if (nested.mixinConfigs().contains(config)) {
					return mod;
				}
			}
		}
		return null;
	}

	/** The first mod frame of the deepest "Caused by" block, then of the outer exception. */
	private static ModScanner.ModInfo stackCulprit(String text, Map<String, ModScanner.ModInfo> byPackage,
			java.util.function.Function<String, ModScanner.ModInfo> byId) {
		List<String> blocks = new ArrayList<>();
		int causedBy = text.lastIndexOf("Caused by:");
		if (causedBy >= 0) {
			blocks.add(text.substring(causedBy));
		}
		blocks.add(text);
		for (String block : blocks) {
			Matcher frame = FRAME.matcher(block);
			int seen = 0;
			while (frame.find() && seen++ < 40) {
				String cls = frame.group(1);
				Matcher handler = MIXIN_HANDLER.matcher(frame.group(2));
				if (handler.find()) {
					ModScanner.ModInfo injected = byId.apply(handler.group(1));
					if (injected != null && !managedId(injected.id())) {
						return injected;
					}
				}
				if (platform(cls)) {
					continue;
				}
				ModScanner.ModInfo mod = owner(byPackage, cls);
				if (mod != null) {
					return mod;
				}
			}
		}
		return null;
	}

	/** Fabric API modules: their frames sit between every mod and the game, they're never the cause by themselves. */
	private static boolean managedId(String id) {
		return id == null || id.equals("fabric-api") || id.startsWith("fabric-") || id.equals("fabricloader") || id.equals("mixinextras");
	}

	private static boolean resourceReload(String text) {
		return text.contains("Reloading ResourceManager") && errorsOnly(text).contains("Exception") || text.contains("Caught error loading resourcepacks")
				|| text.contains("Error loading resource pack");
	}

	private static boolean isVelo(ModScanner.ModInfo mod) {
		return mod.id() != null && (mod.id().equals("velo-client") || mod.id().equals("velo-addons"));
	}

	private static Fix update(ModScanner.ModInfo mod, boolean orElseDisable) {
		return new Fix("Update " + mod.displayName(), "Gets the newest version (turns it off if there's none for this Minecraft version)",
				List.of(new Action.Update(mod.file(), mod.displayName(), mod.id(), orElseDisable)));
	}

	private static Fix off(ModScanner.ModInfo mod) {
		return new Fix("Turn " + mod.displayName() + " off", "It stays installed - turn it back on any time", List.of(new Action.Disable(mod.file(), mod.displayName())));
	}

	private static long physicalMb() {
		try {
			var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
			return os.getTotalMemorySize() / (1024 * 1024);
		} catch (Throwable e) {
			return 8192;
		}
	}
}
