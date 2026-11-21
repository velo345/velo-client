package net.veloclient.velo.client.cosmetics;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cosmetic files (wing meshes, textures, bodywear) read and decoded off the render thread. Seeing a player with a new
 * cosmetic used to read and decode its PNG / mesh right inside that frame - 10-40 ms per item, so joining a busy server
 * stuttered once per cosmetic. Now the first ask starts the job and the cosmetic simply isn't drawn for the few frames
 * it takes; the caller registers the result (textures need the render thread) once {@link #poll} hands it over.
 */
public final class AsyncLoad {

	private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
		Thread t = new Thread(r, "velo-cosmetic-load");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});
	private static final ConcurrentHashMap<String, CompletableFuture<Object>> JOBS = new ConcurrentHashMap<>();

	private AsyncLoad() {
	}

	/**
	 * The finished result for {@code key} - handed over once (then forgotten: keep it), or null while the job still runs
	 * (it's started on the first call). A job that returned null counts as "not yet" and is started again on a later
	 * call; one that threw rethrows here (as a RuntimeException) so the caller can remember the failure.
	 */
	@SuppressWarnings("unchecked")
	public static <T> T poll(String key, Callable<T> job) {
		CompletableFuture<Object> future = JOBS.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> {
			try {
				return job.call();
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, POOL));
		if (!future.isDone()) {
			return null;
		}
		JOBS.remove(key, future);
		try {
			return (T) future.join();
		} catch (CompletionException e) {
			throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
		}
	}
}
