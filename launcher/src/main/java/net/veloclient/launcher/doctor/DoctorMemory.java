package net.veloclient.launcher.doctor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.veloclient.launcher.instance.InstancePaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Doctor remembers per profile ({@code doctor.json}): every fix it applied and for which problem / crash,
 * whether it helped (the next start worked) or not (the same crash came back), the profile as it was when the game
 * last worked, and the last crash that hasn't been solved yet. That's what keeps it from suggesting a fix twice for
 * the same problem, and what lets it notice when the player fixed something themselves.
 */
final class DoctorMemory {

	static final class Attempt {
		/** A crash signature, or "issue:<id>" for a problem found before starting. */
		String problem;
		String fixKey;
		String label;
		long at;
		/** pending, helped, failed */
		String outcome = "pending";
	}

	static final class State {
		long at;
		String fingerprint;
		List<Map<String, Object>> mods = new ArrayList<>();
		String graphics;
		Integer ramMaxMb;
	}

	static final class Failure {
		String signature;
		String summary;
		State before;
		/** The crash / log text, trimmed - goes with a learned fix to the owner panel. */
		String excerpt;
		boolean reported;
	}

	static final class Data {
		List<Attempt> attempts = new ArrayList<>();
		State lastGood;
		Failure lastFailure;
		/** The last fix this player found themselves (sent to the server) - goes along with bug reports. */
		com.google.gson.JsonObject lastLearned;
		/** Warnings the player started past ("Play anyway"), waiting to see whether the game really breaks. */
		List<String> playedAnyway = new ArrayList<>();
		/** Warnings that turned out wrong (the game ran fine anyway) - never shown again for this profile. */
		Set<String> falseAlarms = new java.util.LinkedHashSet<>();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private DoctorMemory() {
	}

	private static Path file(String instanceId) {
		return InstancePaths.dir(instanceId).resolve("doctor.json");
	}

	static synchronized Data load(String instanceId) {
		try {
			Path file = file(instanceId);
			if (Files.exists(file)) {
				Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
				if (data != null) {
					if (data.attempts == null) {
						data.attempts = new ArrayList<>();
					}
					return data;
				}
			}
		} catch (Exception ignored) {
			// Unreadable: start fresh - worst case a fix is offered once more.
		}
		return new Data();
	}

	static synchronized void save(String instanceId, Data data) {
		try {
			// Only the last 200 attempts - plenty to never repeat a fix, small enough to read instantly.
			if (data.attempts.size() > 200) {
				data.attempts = new ArrayList<>(data.attempts.subList(data.attempts.size() - 200, data.attempts.size()));
			}
			Path file = file(instanceId);
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("doctor.json.tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception ignored) {
			// Memory is a nice-to-have.
		}
	}

	/** Fix keys that were applied for {@code problem} and didn't help. */
	static Set<String> failedFixes(Data data, String problem) {
		Set<String> out = new HashSet<>();
		for (Attempt a : data.attempts) {
			if (a.problem.equals(problem) && "failed".equals(a.outcome)) {
				out.add(a.fixKey);
			}
		}
		return out;
	}

	static long failures(Data data, String problem) {
		return data.attempts.stream().filter(a -> a.problem.equals(problem) && "failed".equals(a.outcome)).count();
	}

	static void recordApplied(Data data, String problem, Diagnosis.Fix fix) {
		Attempt a = new Attempt();
		a.problem = problem;
		a.fixKey = fix.key();
		a.label = fix.label();
		a.at = System.currentTimeMillis();
		data.attempts.add(a);
	}

	/** Every fix still waiting for a verdict on {@code problem} gets {@code outcome}. */
	static void settle(Data data, String problem, String outcome) {
		for (Attempt a : data.attempts) {
			if ("pending".equals(a.outcome) && (problem == null || a.problem.equals(problem))) {
				a.outcome = outcome;
			}
		}
	}

	static boolean hasPending(Data data) {
		return data.attempts.stream().anyMatch(a -> "pending".equals(a.outcome));
	}

	static State state(Snapshot s) {
		State state = new State();
		state.at = System.currentTimeMillis();
		state.fingerprint = s.fingerprint();
		state.mods = s.modList();
		state.graphics = s.graphicsApi();
		state.ramMaxMb = s.ramMaxMb();
		return state;
	}
}
