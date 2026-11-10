package net.veloclient.launcher;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Hands freed native memory back to the OS on Linux (glibc). Java frees JavaFX image buffers, GTK/Pango text layouts
 * and decoder buffers through malloc, but glibc keeps the pages in its arenas - the launcher's RSS then stays at its
 * peak long after a heavy page. {@code malloc_trim(0)} after the idle GC releases them. A no-op anywhere else.
 */
public final class NativeMemory {

	private static final MethodHandle MALLOC_TRIM = find();

	private NativeMemory() {
	}

	private static MethodHandle find() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("linux") || Boolean.getBoolean("velo.noMallocTrim")) {
			return null;
		}
		try {
			Linker linker = Linker.nativeLinker();
			return linker.defaultLookup().find("malloc_trim")
					.map(symbol -> linker.downcallHandle(symbol, FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)))
					.orElse(null);
		} catch (Throwable e) {
			return null; // not glibc (musl), or native access off
		}
	}

	/** Releases freed malloc memory back to the OS (Linux/glibc); cheap enough to call after every idle GC. */
	public static void trim() {
		if (MALLOC_TRIM == null) {
			return;
		}
		try {
			int ignored = (int) MALLOC_TRIM.invokeExact(0L);
		} catch (Throwable e) {
			// nothing to release
		}
	}
}
