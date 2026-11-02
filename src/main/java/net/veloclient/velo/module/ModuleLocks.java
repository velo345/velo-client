package net.veloclient.velo.module;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Modules a server switched off (Velo Bridge "disabled-modules"): off while playing there, can't be switched back on
 * there, and back to how they were when leaving. The reason says who did it ("Hypixel turned this off").
 */
public final class ModuleLocks {

	private static final Map<String, String> REASONS = new LinkedHashMap<>();
	/** Whether each locked module was on before the server locked it. */
	private static final Map<String, Boolean> WAS_ON = new LinkedHashMap<>();

	private ModuleLocks() {
	}

	public static synchronized boolean locked(String moduleId) {
		return REASONS.containsKey(moduleId);
	}

	public static synchronized String reason(String moduleId) {
		return REASONS.get(moduleId);
	}

	public static synchronized Set<String> lockedIds() {
		return Set.copyOf(REASONS.keySet());
	}

	public static synchronized void lock(String moduleId, String reason) {
		ModuleRegistry.get(moduleId).ifPresent(module -> {
			if (!REASONS.containsKey(moduleId)) {
				WAS_ON.put(moduleId, module.isEnabled());
			}
			REASONS.put(moduleId, reason);
			if (module.isEnabled()) {
				module.setEnabled(false);
			}
		});
	}

	/** Leaving the server: every locked module goes back to how it was. */
	public static synchronized void unlockAll() {
		Map<String, Boolean> restore = new LinkedHashMap<>(WAS_ON);
		REASONS.clear();
		WAS_ON.clear();
		restore.forEach((id, on) -> ModuleRegistry.get(id).ifPresent(m -> {
			if (on && !m.isEnabled()) {
				m.setEnabled(true);
			}
		}));
	}
}
