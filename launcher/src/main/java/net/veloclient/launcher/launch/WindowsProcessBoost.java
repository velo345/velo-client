package net.veloclient.launcher.launch;

import net.veloclient.launcher.LauncherLog;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Tells Windows to treat the game as the foreground job from its very first millisecond (Windows only).
 *
 * <p>A freshly started game has no window for the first ten to twenty seconds (JVM, Fabric, mod init, resource
 * reload). Windows files a process without a window under "background work": on a CPU with fast and slow cores that
 * means the slow ones at a low clock, and Windows 11 additionally throttles its execution speed (EcoQoS) and ignores
 * its timer-resolution requests. Measured by others on a hybrid laptop, the same start took about 7 s longer for
 * that reason alone. We start the process ourselves, so we can say what it is before it does any work:
 * <ul>
 *   <li>priority class one step above normal - frames and loading win an argument for the cores against the
 *       launcher, a browser or Discord (not "high": that starves audio and the very things players talk through);</li>
 *   <li>power throttling off, both execution speed (no EcoQoS) and timer resolution (the frame limiter and
 *       {@code Thread.sleep} keep their precision while the window is behind another or the game is loading).</li>
 * </ul>
 * Everything is best-effort: any failure is logged at debug level and the launch goes on as before. Never runs
 * outside Windows. {@code -Dvelo.noProcessBoost=true} switches it off.
 */
final class WindowsProcessBoost {

	private static final int PROCESS_SET_INFORMATION = 0x0200;
	private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
	private static final int ABOVE_NORMAL_PRIORITY_CLASS = 0x8000;
	/** PROCESS_INFORMATION_CLASS.ProcessPowerThrottling */
	private static final int PROCESS_POWER_THROTTLING = 4;
	private static final int THROTTLING_CURRENT_VERSION = 1;
	private static final int THROTTLING_EXECUTION_SPEED = 0x1;
	private static final int THROTTLING_IGNORE_TIMER_RESOLUTION = 0x4;

	private static final int BELOW_NORMAL_PRIORITY_CLASS = 0x4000;
	private static final int NORMAL_PRIORITY_CLASS = 0x20;

	private WindowsProcessBoost() {
	}

	/** What to do to the process. */
	enum Mode {
		/** A game that starts now: above normal, power throttling off - only when the player left the setting on. */
		PLAY,
		/** A hidden, waiting game (Instant Play): below normal, Windows' throttling left as it is. */
		PARKED,
		/** A waiting game that is shown now with the boost setting off: back to plain normal priority. */
		RESTORE
	}

	/** Applies the player's setting to a game that starts or is shown now; returns what was done ("" when nothing). */
	static String forPlaying(long pid, boolean wasParked) {
		if (net.veloclient.launcher.data.LauncherSettings.get().processBoost) {
			return apply(pid, Mode.PLAY);
		}
		return wasParked ? apply(pid, Mode.RESTORE) : "";
	}

	/**
	 * @return what was applied, for the log ("priority+throttling-off", "" when nothing)
	 */
	static String apply(long pid, Mode mode) {
		if (!OsRules.currentOsName().equals("windows") || Boolean.getBoolean("velo.noProcessBoost")) {
			return "";
		}
		try (Arena arena = Arena.ofConfined()) {
			Linker linker = Linker.nativeLinker();
			SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
			MethodHandle openProcess = linker.downcallHandle(kernel32.find("OpenProcess").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			MethodHandle closeHandle = linker.downcallHandle(kernel32.find("CloseHandle").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
			MethodHandle setPriorityClass = linker.downcallHandle(kernel32.find("SetPriorityClass").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

			MemorySegment process = (MemorySegment) openProcess.invokeExact(PROCESS_SET_INFORMATION | PROCESS_QUERY_LIMITED_INFORMATION,
					0, (int) pid);
			if (process.equals(MemorySegment.NULL) || process.address() == 0) {
				return "";
			}
			StringBuilder applied = new StringBuilder();
			try {
				int priorityClass = switch (mode) {
					case PARKED -> BELOW_NORMAL_PRIORITY_CLASS;
					case RESTORE -> NORMAL_PRIORITY_CLASS;
					case PLAY -> ABOVE_NORMAL_PRIORITY_CLASS;
				};
				if ((int) setPriorityClass.invokeExact(process, priorityClass) != 0) {
					applied.append(mode == Mode.PARKED ? "below-normal" : mode == Mode.RESTORE ? "normal-priority" : "priority");
				}
				if (mode != Mode.PLAY) {
					return applied.toString();
				}
				// Windows 10 1709+ / 11. ControlMask says which switches we take over, StateMask 0 = "do not throttle".
				var setInformation = kernel32.find("SetProcessInformation");
				if (setInformation.isPresent()) {
					MethodHandle set = linker.downcallHandle(setInformation.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT,
							ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
					MemorySegment state = arena.allocate(12, 4);
					state.set(ValueLayout.JAVA_INT, 0, THROTTLING_CURRENT_VERSION);
					state.set(ValueLayout.JAVA_INT, 4, THROTTLING_EXECUTION_SPEED | THROTTLING_IGNORE_TIMER_RESOLUTION);
					state.set(ValueLayout.JAVA_INT, 8, 0);
					if ((int) set.invokeExact(process, PROCESS_POWER_THROTTLING, state, 12) != 0) {
						applied.append(applied.length() == 0 ? "" : "+").append("throttling-off");
					} else {
						// Windows 10 before 1809 knows only the execution-speed bit; the combined mask is refused there.
						state.set(ValueLayout.JAVA_INT, 4, THROTTLING_EXECUTION_SPEED);
						if ((int) set.invokeExact(process, PROCESS_POWER_THROTTLING, state, 12) != 0) {
							applied.append(applied.length() == 0 ? "" : "+").append("throttling-off(speed)");
						}
					}
				}
			} finally {
				int ignored = (int) closeHandle.invokeExact(process);
			}
			return applied.toString();
		} catch (Throwable e) {
			LauncherLog.warn("Couldn't boost the game process", e);
			return "";
		}
	}
}
