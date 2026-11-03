package net.veloclient.launcher.doctor;

import net.veloclient.launcher.doctor.Diagnosis.Action;
import net.veloclient.launcher.doctor.Diagnosis.Fix;
import net.veloclient.launcher.doctor.Diagnosis.Issue;
import net.veloclient.launcher.doctor.Diagnosis.Severity;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Fix all" as one plan, worked out before anything is touched - so it never ends up suggesting X, then Y, then Z,
 * then X again:
 * <ul>
 * <li>Each round takes the best fix of every fixable problem, applies it to an imagined copy of the profile and checks
 * that copy again. New problems caused by a fix (turning a mod off that another one needs...) are solved in the next
 * round, until nothing fixable is left.</li>
 * <li>Every file / setting gets decided once. A later fix that would undo an earlier decision is skipped for the next
 * option of that problem - nothing is ever turned off and on again within one plan.</li>
 * <li>A state that was already checked ends the planning (no cycles), and it stops after a few rounds regardless.</li>
 * </ul>
 * Fixes the Doctor already tried for the same problem are excluded by the caller, so a second "Fix all" after a
 * failed one picks something else instead of the same thing again.
 */
final class Planner {

	private static final int MAX_ROUNDS = 6;

	record Plan(List<Fix> fixes, List<Issue> unsolved) {
	}

	private Planner() {
	}

	static Plan plan(Snapshot start, List<Issue> issues, Set<String> excludedFixKeys) {
		Map<String, String> decided = new HashMap<>(); // target -> action key
		List<Fix> chosen = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Snapshot state = start;
		List<Issue> current = issues;
		Map<String, Issue> unsolved = new LinkedHashMap<>();
		for (int round = 0; round < MAX_ROUNDS; round++) {
			if (!seen.add(state.fingerprint() + "|" + chosen.size())) {
				break;
			}
			boolean progressed = false;
			for (Issue issue : current) {
				if (!issue.fixable()) {
					if (issue.severity() != Severity.TIP) {
						unsolved.putIfAbsent(issue.id(), issue);
					}
					continue;
				}
				Fix pick = null;
				for (Fix fix : issue.fixes()) {
					if (excludedFixKeys.contains(fix.key()) || !compatible(fix, decided)) {
						continue;
					}
					pick = fix;
					break;
				}
				if (pick == null) {
					unsolved.putIfAbsent(issue.id(), issue);
					continue;
				}
				if (alreadyChosen(pick, chosen)) {
					continue;
				}
				for (Action action : pick.actions()) {
					decided.put(action.target(), action.key());
				}
				chosen.add(pick);
				unsolved.remove(issue.id());
				progressed = true;
			}
			if (!progressed) {
				break;
			}
			state = simulate(start, chosen);
			List<Issue> next = new ArrayList<>(Rules.check(state));
			// Crash findings can't be re-checked without running the game: they count as handled once a fix was picked.
			current = next;
		}
		return new Plan(chosen, new ArrayList<>(unsolved.values()));
	}

	/** A fix fits when none of its actions contradicts what's already decided for the same file / setting. */
	private static boolean compatible(Fix fix, Map<String, String> decided) {
		for (Action action : fix.actions()) {
			String before = decided.get(action.target());
			if (before != null && !before.equals(action.key()) && !compatibleKinds(before, action.key())) {
				return false;
			}
		}
		return true;
	}

	/** Updating a file that's being kept on is fine; turning off a file that's being updated is not. */
	private static boolean compatibleKinds(String a, String b) {
		return a.startsWith("update:") && b.startsWith("enable:") || a.startsWith("enable:") && b.startsWith("update:");
	}

	private static boolean alreadyChosen(Fix fix, List<Fix> chosen) {
		for (Fix c : chosen) {
			if (c.key().equals(fix.key())) {
				return true;
			}
		}
		return false;
	}

	/** The profile as it would be after {@code fixes} - only in memory. */
	static Snapshot simulate(Snapshot s, List<Fix> fixes) {
		Map<Path, Boolean> enabledOverride = new HashMap<>();
		Set<Path> removed = new HashSet<>();
		Set<Path> updated = new HashSet<>();
		List<ModScanner.ModInfo> installed = new ArrayList<>();
		String api = s.graphicsApi();
		Integer ram = s.ramMaxMb();
		String loader = s.loaderVersion();
		for (Fix fix : fixes) {
			for (Action action : fix.actions()) {
				switch (action) {
					case Action.Disable d -> enabledOverride.put(d.file(), false);
					case Action.Enable e -> enabledOverride.put(e.file(), true);
					case Action.Remove r -> removed.add(r.file());
					case Action.Update u -> updated.add(u.file());
					case Action.Install i -> installed.add(new ModScanner.ModInfo(Path.of("(install) " + i.project()), true, i.providesId(), i.name(),
							"*", null, Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), List.of(), ModScanner.Problem.NONE));
					case Action.SetGraphicsApi g -> api = g.value();
					case Action.SetMemory m -> ram = m.maxMb() == 0 ? null : m.maxMb();
					case Action.DisableShaders ignored -> {
					}
					case Action.LowerRenderDistance ignored -> {
					}
					case Action.SetLoader l -> loader = l.version() == null ? loader : l.version();
				}
			}
		}
		List<ModScanner.ModInfo> mods = new ArrayList<>();
		for (ModScanner.ModInfo mod : s.mods()) {
			if (removed.contains(mod.file())) {
				continue;
			}
			boolean on = enabledOverride.getOrDefault(mod.file(), mod.enabled());
			if (updated.contains(mod.file())) {
				// The new version is assumed to fit this Minecraft version (an update that can't, turns the mod off).
				mods.add(new ModScanner.ModInfo(mod.file(), on, mod.id(), mod.name(), "*", mod.environment(), Map.of(), Map.of(), Map.of(),
						mod.provides(), mod.mixinConfigs(), mod.packages(), mod.nested(), mod.problem()));
			} else {
				mods.add(on == mod.enabled() ? mod : new ModScanner.ModInfo(mod.file(), on, mod.id(), mod.name(), mod.version(), mod.environment(),
						mod.depends(), mod.breaks(), mod.conflicts(), mod.provides(), mod.mixinConfigs(), mod.packages(), mod.nested(), mod.problem()));
			}
		}
		mods.addAll(installed);
		List<Snapshot.Pack> packs = new ArrayList<>();
		for (Snapshot.Pack pack : s.packs()) {
			boolean on = enabledOverride.getOrDefault(pack.file(), pack.enabled());
			if (!removed.contains(pack.file())) {
				packs.add(new Snapshot.Pack(pack.file(), on, on && pack.broken(), pack.name()));
			}
		}
		return new Snapshot(s.instance(), mods, packs, s.shaders(), api, ram, s.javaMajor(), loader, s.modAssets());
	}
}
