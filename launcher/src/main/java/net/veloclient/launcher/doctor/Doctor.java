package net.veloclient.launcher.doctor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.veloclient.launcher.doctor.Diagnosis.Action;
import net.veloclient.launcher.doctor.Diagnosis.Fix;
import net.veloclient.launcher.doctor.Diagnosis.Issue;
import net.veloclient.launcher.doctor.Diagnosis.Severity;
import net.veloclient.launcher.instance.Instance;
import net.veloclient.launcher.instance.InstanceStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Velo Doctor: finds what will crash (or did crash) a profile, explains it in one sentence, and fixes it - one problem
 * at a time or all at once. Remembers what it tried per problem so it never offers a fix that already failed, learns
 * from what players fix themselves (after the owner approves it), and says "send a report" when it's out of ideas.
 */
public final class Doctor {

	/** A problem and the fix picked for it. */
	public record Chosen(Issue issue, Fix fix) {
	}

	public record Outcome(List<Fix> applied, List<String> failures, Diagnosis after) {
	}

	private static final Gson GSON = new Gson();
	/** After this many failed fixes for one crash, the Doctor stops guessing and asks for a report. */
	private static final int GIVE_UP_AFTER = 2;

	private Doctor() {
	}

	/** Problems you can see before starting (no game run needed). */
	public static Diagnosis check(Instance instance) {
		Snapshot s = Snapshot.take(instance);
		DoctorMemory.Data memory = DoctorMemory.load(instance.id());
		return assemble(s, memory, Rules.check(s), null, null, List.of());
	}

	/** After a crash: what the crash says, what the profile shows, and fixes other players' crashes taught us. */
	public static Diagnosis afterCrash(Instance instance, String crash, String log) {
		return afterCrash(instance, crash, log, 0);
	}

	/** {@code exitCode} explains crashes that left no error at all (the process was simply gone). */
	public static Diagnosis afterCrash(Instance instance, String crash, String log, int exitCode) {
		Snapshot s = Snapshot.take(instance);
		DoctorMemory.Data memory = DoctorMemory.load(instance.id());
		CrashAnalyzer.Result result = CrashAnalyzer.analyze(s, crash, log);
		String text = crash != null ? crash : log;
		List<Issue> crashIssues = new ArrayList<>(LearnedRules.match(s, result.signature(), text));
		crashIssues.addAll(result.issues());
		if (crashIssues.isEmpty() && exitCode != 0 && (text == null || !CrashAnalyzer.errorsOnly(text).matches("(?s).*\\b\\w+(Exception|Error)\\b.*"))) {
			crashIssues.add(suddenExit(s, exitCode));
		}
		if (crashIssues.isEmpty() && result.signature() != null) {
			Issue sinceGood = sinceLastGood(s, memory);
			if (sinceGood != null) {
				crashIssues.add(sinceGood);
			}
		}
		return assemble(s, memory, Rules.check(s), result.signature(), result.summary(), crashIssues);
	}

	private static Diagnosis assemble(Snapshot s, DoctorMemory.Data memory, List<Issue> staticIssues, String signature, String summary,
			List<Issue> crashIssues) {
		if (memory.falseAlarms != null && !memory.falseAlarms.isEmpty()) {
			staticIssues = staticIssues.stream().filter(i -> !memory.falseAlarms.contains(i.id())).toList();
		}
		Map<String, Issue> merged = new LinkedHashMap<>();
		for (Issue issue : crashIssues) {
			merged.putIfAbsent(issue.id(), filterTried(issue, DoctorMemory.failedFixes(memory, problemKey(issue, signature))));
		}
		for (Issue issue : staticIssues) {
			merged.putIfAbsent(issue.id(), filterTried(issue, DoctorMemory.failedFixes(memory, problemKey(issue, null))));
		}
		List<Issue> issues = new ArrayList<>(merged.values());
		issues.sort(Comparator.comparingInt(i -> i.severity().ordinal()));
		boolean needsReport = false;
		if (signature != null) {
			boolean anyFix = crashIssues.isEmpty() ? issues.stream().anyMatch(i -> i.severity() == Severity.CRASH && i.fixable())
					: issues.stream().anyMatch(i -> crashIssues.stream().anyMatch(c -> c.id().equals(i.id())) && i.fixable());
			needsReport = !anyFix || DoctorMemory.failures(memory, signature) >= GIVE_UP_AFTER
					|| issues.stream().anyMatch(i -> i.id().equals("culprit-velo"));
		}
		return new Diagnosis(issues, signature, summary, needsReport);
	}

	/** Crash findings are remembered per crash; pre-launch problems per problem. */
	private static String problemKey(Issue issue, String signature) {
		return signature != null && !isStatic(issue) ? signature : "issue:" + issue.id();
	}

	private static boolean isStatic(Issue issue) {
		String id = issue.id();
		return !(id.startsWith("culprit") || id.startsWith("learned:") || id.startsWith("missing-") || id.equals("oom") || id.equals("vulkan-crash")
				|| id.equals("opengl-driver") || id.equals("shader-crash") || id.equals("packs-crash") || id.equals("since-last-good")
				|| id.equals("sudden-exit") || id.equals("gpu-driver-crash") || id.equals("native-oom"));
	}

	private static Issue filterTried(Issue issue, Set<String> failed) {
		if (failed.isEmpty()) {
			return issue;
		}
		List<Fix> fresh = new ArrayList<>();
		List<Fix> tried = new ArrayList<>();
		for (Fix fix : issue.fixes()) {
			(failed.contains(fix.key()) ? tried : fresh).add(fix);
		}
		return new Issue(issue.id(), issue.severity(), issue.title(), issue.detail(), fresh, tried);
	}

	/** The player pressed "Play anyway" past these warnings - if the game then runs fine, they were false alarms. */
	public static void playingAnyway(Instance instance, Diagnosis diagnosis) {
		DoctorMemory.Data memory = DoctorMemory.load(instance.id());
		if (memory.playedAnyway == null) {
			memory.playedAnyway = new ArrayList<>();
		}
		memory.playedAnyway.clear();
		diagnosis.issues().stream().filter(i -> i.severity() == Severity.CRASH).forEach(i -> memory.playedAnyway.add(i.id()));
		DoctorMemory.save(instance.id(), memory);
	}

	/** "Fix all": one consistent plan for every fixable problem (see {@link Planner}). */
	public static List<Chosen> planAll(Instance instance, Diagnosis diagnosis) {
		Snapshot s = Snapshot.take(instance);
		List<Issue> fixable = diagnosis.issues().stream().filter(Issue::fixable).toList();
		Planner.Plan plan = Planner.plan(s, fixable, Set.of());
		List<Chosen> out = new ArrayList<>();
		for (Fix fix : plan.fixes()) {
			Issue owner = fixable.stream().filter(i -> i.fixes().stream().anyMatch(f -> f.key().equals(fix.key()))).findFirst()
					.orElse(new Issue("follow-up:" + fix.key(), Severity.CRASH, fix.label(), "", List.of(fix), List.of()));
			out.add(new Chosen(owner, fix));
		}
		return out;
	}

	/**
	 * Applies the fixes, remembers them, and checks the profile again: a pre-launch problem that's still there
	 * counts its fix as failed right away; crash fixes get their verdict when the game next runs (or crashes).
	 */
	public static Outcome apply(Instance instance, Diagnosis diagnosis, List<Chosen> chosen, BiConsumer<String, Double> progress) {
		FixRunner.Result result = FixRunner.run(instance, chosen.stream().map(Chosen::fix).toList(), progress);
		Instance fresh = InstanceStore.loadAll().stream().filter(i -> i.id().equals(instance.id())).findFirst().orElse(instance);
		DoctorMemory.Data memory = DoctorMemory.load(instance.id());
		Diagnosis after = check(fresh);
		for (Chosen c : chosen) {
			String problem = problemKey(c.issue(), diagnosis.crashSignature());
			if (!result.applied().contains(c.fix())) {
				// It couldn't even run (nothing newer to update to, not on Modrinth...): remembered as failed, so pressing
				// Fix again offers the next option instead of the same one forever.
				DoctorMemory.recordApplied(memory, problem, c.fix());
				DoctorMemory.settle(memory, problem, "failed");
				continue;
			}
			DoctorMemory.recordApplied(memory, problem, c.fix());
			if (problem.startsWith("issue:")) {
				boolean stillThere = after.issues().stream().anyMatch(i -> i.id().equals(c.issue().id()));
				DoctorMemory.settle(memory, problem, stillThere ? "failed" : "helped");
			}
		}
		DoctorMemory.save(instance.id(), memory);
		return new Outcome(result.applied(), result.failures(), check(fresh));
	}

	/**
	 * The game closed. A clean run settles pending fixes as helped and - if the player solved a crash the Doctor
	 * couldn't - sends what they changed as a fix to learn from. A crash marks pending fixes for the same crash as
	 * failed and remembers the profile as it was.
	 */
	public static void onGameExit(Instance instance, int exitCode, long ranMillis, boolean stopped, String crash, String log) {
		if (stopped) {
			return;
		}
		Snapshot s = Snapshot.take(instance);
		DoctorMemory.Data memory = DoctorMemory.load(instance.id());
		if (exitCode == 0) {
			if (ranMillis < 30_000) {
				return; // closed right away - says nothing either way
			}
			DoctorMemory.Failure failure = memory.lastFailure;
			if (failure != null) {
				boolean doctorFixed = memory.attempts.stream().anyMatch(a -> a.problem.equals(failure.signature) && "pending".equals(a.outcome));
				if (!doctorFixed && failure.before != null) {
					JsonArray diff = diff(failure.before, DoctorMemory.state(s));
					if (!diff.isEmpty()) {
						JsonObject candidate = new JsonObject();
						candidate.addProperty("signature", failure.signature);
						candidate.addProperty("summary", failure.summary);
						candidate.addProperty("minecraft", instance.mcVersion());
						candidate.addProperty("excerpt", failure.excerpt);
						candidate.add("diff", diff);
						JsonArray tried = new JsonArray();
						memory.attempts.stream().filter(a -> a.problem.equals(failure.signature)).forEach(a -> tried.add(a.label + " (" + a.outcome + ")"));
						candidate.add("tried", tried);
						memory.lastLearned = candidate;
						LearnedRules.submit(candidate);
					}
				}
			}
			DoctorMemory.settle(memory, null, "helped");
			memory.lastFailure = null;
			memory.lastGood = DoctorMemory.state(s);
			if (memory.playedAnyway != null && !memory.playedAnyway.isEmpty()) {
				// It ran fine despite the warning: the Doctor was wrong there and stops saying it.
				if (memory.falseAlarms == null) {
					memory.falseAlarms = new java.util.LinkedHashSet<>();
				}
				memory.falseAlarms.addAll(memory.playedAnyway);
				memory.playedAnyway.clear();
			}
		} else {
			String text = crash != null ? crash : log;
			String signature = text == null ? "exit:" + exitCode : CrashAnalyzer.signature(text);
			DoctorMemory.settle(memory, signature, "failed");
			// A different crash now: the fixes for the earlier problems did their job.
			DoctorMemory.settle(memory, null, "helped");
			if (memory.playedAnyway != null) {
				memory.playedAnyway.clear(); // the warning was right
			}
			DoctorMemory.Failure failure = new DoctorMemory.Failure();
			failure.signature = signature;
			failure.summary = net.veloclient.launcher.ui.CrashReportDialog.causeOf(crash, log);
			failure.before = DoctorMemory.state(s);
			failure.excerpt = text == null ? null : text.substring(0, Math.min(text.length(), 6000));
			memory.lastFailure = failure;
		}
		DoctorMemory.save(instance.id(), memory);
	}

	/** What goes with a bug report: what the Doctor found, tried, and what the player changed that worked. */
	public static JsonObject reportData(Instance instance, Diagnosis diagnosis) {
		DoctorMemory.Data memory = DoctorMemory.load(instance.id());
		JsonObject out = new JsonObject();
		if (diagnosis != null) {
			out.addProperty("signature", diagnosis.crashSignature());
			JsonArray found = new JsonArray();
			diagnosis.issues().forEach(i -> found.add(i.severity() + ": " + i.title()));
			out.add("found", found);
		}
		JsonArray attempts = new JsonArray();
		memory.attempts.stream().skip(Math.max(0, memory.attempts.size() - 20))
				.forEach(a -> attempts.add(a.label + " -> " + a.outcome + " (" + a.problem + ")"));
		out.add("attempts", attempts);
		if (memory.lastLearned != null) {
			out.add("learned", memory.lastLearned);
		}
		return out;
	}

	/**
	 * The game was just gone - no exception, no crash report (Windows exit codes like -805306369). That's the graphics
	 * driver or memory nearly every time.
	 */
	private static Issue suddenExit(Snapshot s, int exitCode) {
		List<Fix> fixes = new ArrayList<>();
		if (net.veloclient.launcher.launch.GraphicsApi.supported(s.instance().mcVersion())) {
			String other = net.veloclient.launcher.launch.GraphicsApi.OPENGL.equals(s.graphicsApi())
					? net.veloclient.launcher.launch.GraphicsApi.VULKAN : net.veloclient.launcher.launch.GraphicsApi.OPENGL;
			fixes.add(new Fix("Switch to " + (other.equals(net.veloclient.launcher.launch.GraphicsApi.OPENGL) ? "OpenGL" : "Vulkan"),
					"The other graphics API often avoids a driver problem", List.of(new Action.SetGraphicsApi(other))));
		}
		long systemMb = net.veloclient.launcher.launch.MemoryPlanner.totalSystemMb();
		int renderDistance = net.veloclient.launcher.launch.MemoryPlanner.readRenderDistance(
				net.veloclient.launcher.instance.InstancePaths.gameDir(s.instance().id()));
		// Windows ends a program that stopped responding with 0xCFFFFFFF - on small PCs that's the RAM running full.
		boolean hung = exitCode == -805306369;
		boolean smallPc = systemMb > 0 && systemMb < 10_000;
		int safeDistance = smallPc ? 8 : 12;
		if (renderDistance > safeDistance && (hung || smallPc)) {
			fixes.add(0, new Fix("Lower the render distance to " + safeDistance, "From " + renderDistance + " chunks - far less memory and work",
					List.of(new Action.LowerRenderDistance(safeDistance))));
		}
		if (s.ramMaxMb() != null) {
			fixes.add(new Fix("Use automatic memory", "A fixed amount can be too much or too little for your PC", List.of(new Action.SetMemory(0))));
		}
		String why;
		if (hung) {
			why = "Windows closed it because it stopped responding (exit code " + exitCode + ")."
					+ (smallPc ? " Your PC has " + Math.round(systemMb / 1024.0) + " GB of RAM, which runs full quickly - close the browser"
							+ " and other programs while playing." : " That's usually a full memory or the graphics driver.");
		} else if (exitCode == -1073740791) {
			why = "A program part inside the game stopped it on purpose (exit code " + exitCode + ", 0xC0000409). Almost always the"
					+ " graphics driver or an overlay (Discord, MSI Afterburner / RivaTuner, Overwolf) - update the driver from the"
					+ " NVIDIA / AMD / Intel website and turn overlays off.";
		} else if (exitCode == -1073741819) {
			why = "Something inside the game read memory it wasn't allowed to (exit code " + exitCode + ", 0xC0000005) - nearly always"
					+ " the graphics driver. Update it from the NVIDIA / AMD / Intel website.";
		} else {
			why = "No crash report (exit code " + exitCode + "). That's almost always the graphics driver or memory - update the graphics"
					+ " driver (NVIDIA / AMD / Intel website); lowering the render distance helps too.";
		}
		return new Issue("sudden-exit", Severity.CRASH, "Minecraft closed without an error", why, fixes, List.of());
	}

	/** Nothing pointed at a culprit: offer turning off what was added since the game last worked. */
	private static Issue sinceLastGood(Snapshot s, DoctorMemory.Data memory) {
		if (memory.lastGood == null) {
			return null;
		}
		Map<String, Boolean> before = new HashMap<>();
		for (Map<String, Object> m : memory.lastGood.mods) {
			before.put(String.valueOf(m.get("file")), Boolean.TRUE.equals(m.get("enabled")));
		}
		List<Action> off = new ArrayList<>();
		List<String> names = new ArrayList<>();
		for (ModScanner.ModInfo mod : s.enabledMods()) {
			String file = mod.file().getFileName().toString();
			if (!Boolean.TRUE.equals(before.get(file)) && !file.startsWith("velo-client-") && !file.startsWith("fabric-api-")
					&& !file.startsWith("velo-addons-")) {
				off.add(new Action.Disable(mod.file(), mod.displayName()));
				names.add(mod.displayName());
			}
		}
		if (off.isEmpty()) {
			return null;
		}
		return new Issue("since-last-good", Severity.CRASH, "It crashed after you added " + (names.size() == 1 ? names.get(0) : names.size() + " mods"),
				"We couldn't pin it on one mod, but it worked before " + String.join(", ", names.subList(0, Math.min(4, names.size())))
						+ (names.size() > 4 ? " and more" : "") + " came in.",
				List.of(new Fix("Turn " + (names.size() == 1 ? names.get(0) : "those " + names.size() + " mods") + " off", "Turn them back on one by one to find the bad one", off)),
				List.of());
	}

	/** What changed between two states, as ops the owner panel can turn into a rule. */
	static JsonArray diff(DoctorMemory.State before, DoctorMemory.State after) {
		JsonArray out = new JsonArray();
		Map<String, Map<String, Object>> a = byFile(before.mods);
		Map<String, Map<String, Object>> b = byFile(after.mods);
		Map<String, Map<String, Object>> aById = byId(before.mods);
		for (Map.Entry<String, Map<String, Object>> e : a.entrySet()) {
			Map<String, Object> was = e.getValue();
			Map<String, Object> now = b.get(e.getKey());
			boolean wasOn = Boolean.TRUE.equals(was.get("enabled"));
			if (now == null) {
				Map<String, Object> replacement = byId(after.mods).get(String.valueOf(was.get("id")));
				if (replacement != null && was.get("id") != null) {
					out.add(op("update", was, Objects.toString(was.get("version")) + " -> " + replacement.get("version")));
				} else if (wasOn) {
					out.add(op("remove", was, null));
				}
			} else if (wasOn && !Boolean.TRUE.equals(now.get("enabled"))) {
				out.add(op("disable", was, null));
			} else if (!wasOn && Boolean.TRUE.equals(now.get("enabled"))) {
				out.add(op("enable", was, null));
			}
		}
		for (Map.Entry<String, Map<String, Object>> e : b.entrySet()) {
			Map<String, Object> now = e.getValue();
			if (!a.containsKey(e.getKey()) && (now.get("id") == null || !aById.containsKey(String.valueOf(now.get("id"))))
					&& Boolean.TRUE.equals(now.get("enabled"))) {
				out.add(op("install", now, null));
			}
		}
		if (!Objects.equals(before.graphics, after.graphics) && after.graphics != null) {
			JsonObject g = new JsonObject();
			g.addProperty("op", "graphics");
			g.addProperty("value", after.graphics);
			out.add(g);
		}
		if (!Objects.equals(before.ramMaxMb, after.ramMaxMb)) {
			JsonObject m = new JsonObject();
			m.addProperty("op", "memory");
			m.addProperty("value", after.ramMaxMb == null ? "0" : String.valueOf(after.ramMaxMb));
			out.add(m);
		}
		return out;
	}

	private static JsonObject op(String op, Map<String, Object> mod, String note) {
		JsonObject o = new JsonObject();
		o.addProperty("op", op);
		if (mod.get("id") != null) {
			o.addProperty("mod", String.valueOf(mod.get("id")));
		}
		o.addProperty("name", String.valueOf(mod.get("name")));
		if (mod.get("project") != null) {
			o.addProperty("project", String.valueOf(mod.get("project")));
		}
		if (note != null) {
			o.addProperty("note", note);
		}
		return o;
	}

	private static Map<String, Map<String, Object>> byFile(List<Map<String, Object>> mods) {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		if (mods != null) {
			for (Map<String, Object> m : mods) {
				out.put(String.valueOf(m.get("file")), m);
			}
		}
		return out;
	}

	private static Map<String, Map<String, Object>> byId(List<Map<String, Object>> mods) {
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		if (mods != null) {
			for (Map<String, Object> m : mods) {
				if (m.get("id") != null) {
					out.put(String.valueOf(m.get("id")), m);
				}
			}
		}
		return out;
	}

	/** For the UI: the files a fix touches (to animate their rows). */
	public static List<Path> files(Fix fix) {
		List<Path> out = new ArrayList<>();
		for (Action a : fix.actions()) {
			switch (a) {
				case Action.Disable d -> out.add(d.file());
				case Action.Enable e -> out.add(e.file());
				case Action.Remove r -> out.add(r.file());
				case Action.Update u -> out.add(u.file());
				default -> {
				}
			}
		}
		return out;
	}

	static String json(Object o) {
		return GSON.toJson(o);
	}
}
