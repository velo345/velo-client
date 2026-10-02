package net.veloclient.velo.client.modules.performance;

import net.veloclient.velo.VeloClient;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Disk cache behind the Shader Cache module (Vulkan backend, 26.2+).
 *
 * <p>Vanilla's Vulkan renderer turns every GLSL shader into SPIR-V with shaderc on every launch
 * and every resource reload, and creates every graphics pipeline without a {@code VkPipelineCache},
 * so the driver also recompiles every pipeline from scratch each time. This keeps both results on
 * disk instead:
 * <ul>
 * <li>{@code spirv/<sha256>.spv} - the compiled SPIR-V, keyed by the exact shader source (after
 * resource packs), stage, Minecraft version and compiler settings. A changed shader just gets a
 * new key; stale entries age out by the size cap.</li>
 * <li>{@code vk-pipelines.bin} - the driver's own pipeline cache blob. The driver validates its
 * header (vendor, device, driver version) and ignores data from another GPU or driver, so a
 * driver update can never load stale binaries.</li>
 * </ul>
 * Nothing is kept in memory beyond what vanilla already holds: entries are read when a shader is
 * needed and written once. The whole folder is capped at {@link #MAX_CACHE_BYTES} (oldest files go
 * first).
 */
public final class ShaderCache {

	/** Bump whenever the meaning of a cached file changes (key layout, compiler options). */
	private static final String FORMAT = "velo-shader-cache-v1";
	private static final long MAX_CACHE_BYTES = 128L * 1024 * 1024;

	public static volatile boolean enabled = true;

	private static final AtomicInteger HITS = new AtomicInteger();
	private static final AtomicInteger MISSES = new AtomicInteger();
	private static final AtomicLong COMPILE_NANOS = new AtomicLong();
	private static final AtomicLong LOAD_NANOS = new AtomicLong();
	private static final AtomicInteger PIPELINES = new AtomicInteger();
	private static final AtomicLong PIPELINE_NANOS = new AtomicLong();
	private static volatile boolean pruned;

	private ShaderCache() {
	}

	public static Path root() {
		return VeloPaths.root().resolve("cache").resolve("shaders");
	}

	public static Path spirvDir() {
		return root().resolve("spirv");
	}

	public static Path pipelineCacheFile() {
		return root().resolve("vk-pipelines.bin");
	}

	/** Content key for one shader compilation. */
	public static String key(String minecraftVersion, String filename, String stage, String source) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String part : List.of(FORMAT, minecraftVersion, filename, stage, source)) {
				digest.update(part.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** The cached SPIR-V for {@code key}, or null. Validates the SPIR-V magic so a damaged file is just a miss. */
	public static byte[] readSpirv(String key) {
		long start = System.nanoTime();
		Path file = spirvDir().resolve(key + ".spv");
		try {
			if (!Files.exists(file)) {
				return null;
			}
			byte[] bytes = Files.readAllBytes(file);
			// SPIR-V words are 4 bytes; the first word is the magic number 0x07230203.
			boolean valid = bytes.length >= 20 && bytes.length % 4 == 0
					&& ((bytes[0] & 0xFF) == 0x03 && (bytes[1] & 0xFF) == 0x02 && (bytes[2] & 0xFF) == 0x23 && (bytes[3] & 0xFF) == 0x07);
			if (!valid) {
				Files.deleteIfExists(file);
				return null;
			}
			return bytes;
		} catch (IOException e) {
			return null;
		} finally {
			LOAD_NANOS.addAndGet(System.nanoTime() - start);
		}
	}

	public static void writeSpirv(String key, byte[] spirv) {
		writeAtomically(spirvDir().resolve(key + ".spv"), spirv);
		pruneOnce();
	}

	public static void writeAtomically(Path file, byte[] bytes) {
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(temp, bytes);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			VeloClient.LOGGER.debug("Velo shader cache: couldn't write {}", file, e);
		}
	}

	public static void recordHit() {
		HITS.incrementAndGet();
	}

	public static void recordCompile(long nanos) {
		MISSES.incrementAndGet();
		COMPILE_NANOS.addAndGet(nanos);
	}

	public static void recordPipeline(long nanos) {
		PIPELINES.incrementAndGet();
		PIPELINE_NANOS.addAndGet(nanos);
	}

	public static String summary() {
		int hits = HITS.get();
		int misses = MISSES.get();
		double compileMs = COMPILE_NANOS.get() / 1_000_000.0;
		double loadMs = LOAD_NANOS.get() / 1_000_000.0;
		return String.format(java.util.Locale.ROOT,
				"shaders: %d from cache (%.0f ms), %d compiled (%.0f ms); pipelines: %d built in %.0f ms",
				hits, loadMs, misses, compileMs, PIPELINES.get(), PIPELINE_NANOS.get() / 1_000_000.0);
	}

	/** Size of everything cached, for the settings screen. */
	public static long diskBytes() {
		try (Stream<Path> files = Files.walk(root())) {
			return files.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
		} catch (IOException e) {
			return 0;
		}
	}

	public static void clear() {
		try (Stream<Path> files = Files.walk(root())) {
			files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException ignored) {
			// Nothing cached yet.
		}
	}

	/** Keeps the folder under {@link #MAX_CACHE_BYTES} by deleting the least recently written SPIR-V first. */
	private static void pruneOnce() {
		if (pruned) {
			return;
		}
		pruned = true;
		Thread.ofVirtual().start(() -> {
			try (Stream<Path> files = Files.list(spirvDir())) {
				List<Path> sorted = files.filter(p -> p.toString().endsWith(".spv"))
						.sorted(Comparator.comparingLong(p -> p.toFile().lastModified())).toList();
				long total = sorted.stream().mapToLong(p -> p.toFile().length()).sum();
				for (Path file : sorted) {
					if (total <= MAX_CACHE_BYTES * 3 / 4) {
						break;
					}
					total -= file.toFile().length();
					Files.deleteIfExists(file);
				}
			} catch (IOException ignored) {
				// Best effort.
			}
		});
	}
}
