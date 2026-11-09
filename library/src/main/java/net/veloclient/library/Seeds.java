package net.veloclient.library;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Seed maps: biome tiles and structure positions come from a small native helper around cubiomes (MIT), run as a
 * child process with a hard timeout ({@code <data>/bin/seedmap}); slime chunks are Minecraft's own formula, here in Java.
 * Without the helper only slime chunks and the portal maths work.
 */
final class Seeds {

	static final List<String> STRUCTURES = List.of("village", "desert_pyramid", "jungle_temple", "swamp_hut", "igloo", "ocean_ruin", "shipwreck",
			"monument", "mansion", "outpost", "ruined_portal", "ancient_city", "trail_ruins", "trial_chambers", "fortress", "bastion", "end_city");

	private final Path binary;

	Seeds(Path dataDir) {
		binary = dataDir.resolve("bin").resolve("seedmap");
	}

	boolean available() {
		return Files.isExecutable(binary);
	}

	static long seedOf(String s) {
		if (s == null || s.isBlank()) {
			throw new Http.Fail(400, "Give a seed");
		}
		String t = s.strip();
		try {
			return Long.parseLong(t);
		} catch (NumberFormatException e) {
			return t.hashCode(); // text seeds work like in the game's world creation screen
		}
	}

	static String version(String v) {
		if (v == null) {
			return "1.21";
		}
		if (v.startsWith("26.") || v.startsWith("1.21")) {
			return "1.21";
		}
		return v.matches("1\\.\\d{1,2}(\\.\\d{1,2})?") ? v.substring(0, v.indexOf('.', 2) > 0 ? v.indexOf('.', 2) : v.length()) : "1.21";
	}

	/** A biome tile: w x h biome cells at {@code scale} blocks per cell, as PNG. */
	byte[] tile(String mc, long seed, int dim, int scale, int x, int z, int w, int h) throws IOException {
		byte[] rgb = run(List.of("tile", mc, String.valueOf(seed), String.valueOf(dim), String.valueOf(scale), String.valueOf(x), String.valueOf(z),
				String.valueOf(w), String.valueOf(h)), w * h * 3 + 16);
		if (rgb.length < w * h * 3) {
			throw new Http.Fail(500, "The seed map couldn't draw that area");
		}
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < w * h; i++) {
			img.setRGB(i % w, i / w, (rgb[i * 3] & 255) << 16 | (rgb[i * 3 + 1] & 255) << 8 | (rgb[i * 3 + 2] & 255));
		}
		return Banner.png(img);
	}

	List<Map<String, Object>> structures(String mc, long seed, int dim, int x0, int z0, int x1, int z1, List<String> types) throws IOException {
		String out = new String(run(List.of("structs", mc, String.valueOf(seed), String.valueOf(dim), String.valueOf(x0), String.valueOf(z0),
				String.valueOf(x1), String.valueOf(z1), String.join(",", types)), 1 << 20), StandardCharsets.UTF_8);
		List<Map<String, Object>> list = new ArrayList<>();
		for (String line : out.split("\n")) {
			String[] p = line.trim().split(" ");
			if (p.length == 3) {
				list.add(Http.map("type", p[0], "x", Integer.parseInt(p[1]), "z", Integer.parseInt(p[2])));
			}
		}
		return list;
	}

	List<Map<String, Object>> strongholds(String mc, long seed, int count) throws IOException {
		String out = new String(run(List.of("strongholds", mc, String.valueOf(seed), String.valueOf(count)), 64 * 1024), StandardCharsets.UTF_8);
		List<Map<String, Object>> list = new ArrayList<>();
		for (String line : out.split("\n")) {
			String[] p = line.trim().split(" ");
			if (p.length == 2) {
				list.add(Http.map("x", Integer.parseInt(p[0]), "z", Integer.parseInt(p[1])));
			}
		}
		return list;
	}

	Map<String, Object> spawn(String mc, long seed) throws IOException {
		String[] p = new String(run(List.of("spawn", mc, String.valueOf(seed)), 256), StandardCharsets.UTF_8).trim().split(" ");
		return p.length == 2 ? Http.map("x", Integer.parseInt(p[0]), "z", Integer.parseInt(p[1])) : null;
	}

	private byte[] run(List<String> args, int max) throws IOException {
		if (!available()) {
			throw new Http.Fail(503, "The seed map isn't switched on yet");
		}
		List<String> cmd = new ArrayList<>();
		cmd.add(binary.toString());
		cmd.addAll(args);
		Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try (InputStream in = p.getInputStream()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[64 * 1024];
			long deadline = System.currentTimeMillis() + 8000;
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, Math.min(n, Math.max(0, max - out.size())));
				if (System.currentTimeMillis() > deadline) {
					break;
				}
			}
			if (!p.waitFor(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS)) {
				p.destroyForcibly();
				throw new Http.Fail(504, "That area took too long - zoom in");
			}
			return out.toByteArray();
		} catch (InterruptedException e) {
			p.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}

	/** Minecraft's slime chunk test (Java Edition). */
	static boolean slimeChunk(long seed, int x, int z) {
		Random r = new Random(seed + (long) (x * x * 0x4c1906) + (long) (x * 0x5ac0db) + (long) (z * z) * 0x4307a7L + (long) (z * 0x5f24f) ^ 0x3ad8025fL);
		return r.nextInt(10) == 0;
	}

	static List<int[]> slimeChunks(long seed, int cx0, int cz0, int cx1, int cz1) {
		List<int[]> out = new ArrayList<>();
		for (int z = cz0; z <= cz1; z++) {
			for (int x = cx0; x <= cx1; x++) {
				if (slimeChunk(seed, x, z)) {
					out.add(new int[] {x, z});
				}
			}
		}
		return out;
	}
}
