package net.veloclient.launcher.doctor;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What the Doctor found for a profile: problems in plain words, each with one or more ways to fix it (the first is
 * the recommended one). {@code crashSignature} identifies a crash across runs (same cause = same signature), so the
 * Doctor remembers which fixes it already tried for it.
 */
public record Diagnosis(List<Issue> issues, String crashSignature, String crashSummary, boolean needsReport) {

	public enum Severity {
		/** Stops the game from starting or crashed it. */
		CRASH,
		/** Breaks something, or may crash later. */
		PROBLEM,
		/** Worth knowing; nothing breaks. Not part of "Fix all". */
		TIP
	}

	/**
	 * @param tried fixes the Doctor already applied for this problem that didn't help - shown greyed out, never
	 *              suggested again for the same problem
	 */
	public record Issue(String id, Severity severity, String title, String detail, List<Fix> fixes, List<Fix> tried) {
		public boolean fixable() {
			return !fixes.isEmpty() && severity != Severity.TIP;
		}
	}

	public record Fix(String label, String detail, List<Action> actions) {
		/** Same actions = same fix, whatever the label says. */
		public String key() {
			return actions.stream().map(Action::key).sorted().collect(Collectors.joining("+"));
		}
	}

	/** One change to the profile. {@link #target()} is what it touches - two actions on one target never both run. */
	public sealed interface Action {
		String key();

		String target();

		record Disable(Path file, String name) implements Action {
			public String key() {
				return "disable:" + file.getFileName().toString().replace(".disabled", "");
			}

			public String target() {
				return "file:" + file.getFileName().toString().replace(".disabled", "");
			}
		}

		record Enable(Path file, String name) implements Action {
			public String key() {
				return "enable:" + file.getFileName().toString().replace(".disabled", "");
			}

			public String target() {
				return "file:" + file.getFileName().toString().replace(".disabled", "");
			}
		}

		/** Moves the file to the profile's backups folder. */
		record Remove(Path file, String name) implements Action {
			public String key() {
				return "remove:" + file.getFileName();
			}

			public String target() {
				return "file:" + file.getFileName().toString().replace(".disabled", "");
			}
		}

		/**
		 * Installs the newest version for this Minecraft version that fits {@code requirement} (a Fabric version range,
		 * nullable); {@code providesId} is the mod id it brings.
		 */
		record Install(String project, String name, String providesId, String requirement) implements Action {
			public Install(String project, String name, String providesId) {
				this(project, name, providesId, null);
			}

			public String key() {
				return "install:" + project;
			}

			public String target() {
				return "install:" + providesId;
			}
		}

		/** Replaces the file with the newest version made for this Minecraft version - or turns it off if there's none. */
		record Update(Path file, String name, String modId, boolean orElseDisable) implements Action {
			public String key() {
				return "update:" + modId;
			}

			public String target() {
				return "file:" + file.getFileName().toString().replace(".disabled", "");
			}
		}

		record SetGraphicsApi(String value) implements Action {
			public String key() {
				return "graphics:" + value;
			}

			public String target() {
				return "graphics";
			}
		}

		/** {@code maxMb} 0 = automatic. */
		record SetMemory(int maxMb) implements Action {
			public String key() {
				return "memory:" + maxMb;
			}

			public String target() {
				return "memory";
			}
		}

		/** Turns shaders off in Iris (the shader pack stays installed). */
		record DisableShaders() implements Action {
			public String key() {
				return "shaders:off";
			}

			public String target() {
				return "shaders";
			}
		}
	}
}
