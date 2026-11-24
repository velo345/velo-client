package net.veloclient.velo.client.modules.performance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

/**
 * Start-up: vanilla computes every block state's shape cache (collision shape, face sturdiness, occlusion, light) one
 * after another while {@code Blocks} initialises - ~30,000 states, 2.2 s of the main thread in a 137-mod profile
 * (profiled 2026-11-24, the biggest single block of the whole start). Here those calls are collected instead and run on
 * all cores right after {@code Blocks} finished initialising (Bootstrap, straight after {@code FireBlock.bootStrap()},
 * the call that triggers it) - never inside the class initialiser, where a worker touching {@code Blocks} would wait
 * forever for the main thread.
 *
 * <p>Only parallel when it's known to be safe: every mixin on the block state classes must come from a mod on the list
 * below (FerriteCore's shape de-duplication shares plain hash maps - FerriteDedupLockMixin puts a lock around just
 * those few map calls). Anything else (Lithium, ModernFix, an unknown mod) or any error: the old one-by-one way.
 * {@code -Dvelo.noParallelBlockStates} turns it off.
 */
public final class ParallelBlockStates {

	/** FerriteCore's de-duplication maps (see FerriteDedupLockMixin). */
	public static final Object FERRITE_LOCK = new Object();
	private static final boolean OFF = Boolean.getBoolean("velo.noParallelBlockStates");
	private static final List<String> SAFE_MIXIN_PREFIXES = List.of("malte0811.ferritecore.", "net.veloclient.", "net.fabricmc.fabric.");

	private static volatile boolean armed;
	private static List<Object> deferred;

	private ParallelBlockStates() {
	}

	/** Bootstrap, right before the call that initialises {@code Blocks}. */
	public static void arm() {
		deferred = new ArrayList<>(32_768);
		armed = true;
	}

	/** Blocks' initialiser, for each state: true = collected (the caller skips vanilla's call), false = vanilla goes on. */
	public static boolean defer(Object state) {
		if (!armed) {
			return false;
		}
		deferred.add(state);
		return true;
	}

	/** Bootstrap, right after {@code Blocks} is initialised: computes every collected state's cache. */
	public static void flush() {
		if (!armed) {
			return;
		}
		armed = false;
		List<Object> states = deferred;
		deferred = null;
		if (states == null || states.isEmpty()) {
			return;
		}
		long start = System.nanoTime();
		int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, 12));
		String unsafe = OFF ? "turned off" : threads > 1 ? unsafeMixin() : "one core";
		String how;
		if (unsafe == null) {
			ForkJoinPool pool = new ForkJoinPool(threads);
			try {
				pool.submit(() -> states.parallelStream().forEach(ParallelBlockStates::init)).get(60, TimeUnit.SECONDS);
				how = "on " + threads + " threads";
			} catch (Throwable t) {
				net.veloclient.velo.VeloClient.LOGGER.warn("[Velo] parallel block state caches failed - computing them one by one", t);
				states.forEach(ParallelBlockStates::init);
				how = "one by one after an error";
			} finally {
				pool.shutdownNow();
			}
		} else {
			states.forEach(ParallelBlockStates::init);
			how = "one by one (" + unsafe + ")";
		}
		net.veloclient.velo.VeloClient.LOGGER.info("[Velo] block state caches: {} states in {} ms, {}", states.size(),
				(System.nanoTime() - start) / 1_000_000, how);
	}

	private static void init(Object state) {
		//? if >=26.1 {
		/*((net.minecraft.world.level.block.state.BlockState) state).initCache();
		*///?} else {
		((net.minecraft.block.BlockState) state).initShapeCache();
		//?}
	}

	/** The first mixin on the block state classes that isn't known to be thread-safe, or null when all are. */
	private static String unsafeMixin() {
		try {
			for (String cls : List.of("net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase",
					"net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache", "net/minecraft/world/level/block/state/BlockState")) {
				var info = org.spongepowered.asm.mixin.transformer.ClassInfo.forName(cls);
				if (info == null) {
					continue;
				}
				for (var mixin : info.getAppliedMixins()) {
					String name = mixin.getClassName();
					if (SAFE_MIXIN_PREFIXES.stream().noneMatch(name::startsWith)) {
						return "mixin " + name;
					}
				}
			}
			return null;
		} catch (Throwable t) {
			return "mixin check failed: " + t;
		}
	}
}
