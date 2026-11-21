package net.veloclient.velo.client.util;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Sodium's VertexBufferWriter, found by name at runtime: Sodium 0.9.3-beta.5 renamed {@code tryOf} / {@code push} to
 * {@code sodium$tryOf} / {@code sodium$push}, and a mixin compiled against the old names crashed every 26.3 player
 * (NoSuchMethodError) as soon as particles showed up. Either name works here; when neither exists the direct write just
 * turns itself off and vanilla draws the particles.
 */
public final class SodiumWriter {

	private static final String CLASS = "net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter";
	private static MethodHandle tryOf;
	private static MethodHandle push;
	private static volatile boolean resolved;
	private static volatile boolean available;

	private SodiumWriter() {
	}

	private static synchronized void resolve() {
		if (resolved) {
			return;
		}
		resolved = true;
		try {
			Class<?> writer = Class.forName(CLASS);
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			for (java.lang.reflect.Method m : writer.getMethods()) {
				String n = m.getName();
				if ((n.equals("tryOf") || n.equals("sodium$tryOf")) && m.getParameterCount() == 1 && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
					tryOf = lookup.unreflect(m).asType(MethodType.methodType(Object.class, Object.class));
				} else if ((n.equals("push") || n.equals("sodium$push")) && m.getParameterCount() == 4 && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
					push = lookup.unreflect(m).asType(MethodType.methodType(void.class, Object.class, Object.class, long.class, int.class, Object.class));
				}
			}
			available = tryOf != null && push != null;
		} catch (Throwable t) {
			available = false;
		}
	}

	/** The writer for {@code consumer}, or null (not a Sodium buffer, or this Sodium has no such API). */
	public static Object tryOf(Object consumer) {
		if (!resolved) {
			resolve();
		}
		if (!available) {
			return null;
		}
		try {
			return (Object) tryOf.invokeExact(consumer);
		} catch (Throwable t) {
			available = false;
			return null;
		}
	}

	/** Pushes {@code count} vertices from {@code ptr}; false when it failed (the caller then stops using it). */
	public static boolean push(Object writer, Object stack, long ptr, int count, Object format) {
		try {
			push.invokeExact(writer, stack, ptr, count, format);
			return true;
		} catch (Throwable t) {
			available = false;
			return false;
		}
	}
}
