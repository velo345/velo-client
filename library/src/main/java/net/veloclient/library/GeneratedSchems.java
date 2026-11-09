package net.veloclient.library;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Schematics made on the website (map art, pixel art, text) under a short code, so {@code /velo schem <code>} loads
 * them in the game without publishing them. Only gzip'd .litematic files (checked), at most 3 MB; 60 days after the
 * last use they're gone.
 */
final class GeneratedSchems {

	private static final String ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789";
	private static final SecureRandom RANDOM = new SecureRandom();
	private final Path dir;

	GeneratedSchems(Path dataDir) throws IOException {
		dir = Files.createDirectories(dataDir.resolve("generated-schematics"));
	}

	Map<String, Object> save(byte[] gz, String name) throws IOException {
		if (gz.length < 30 || gz.length > 3 << 20 || (gz[0] & 255) != 0x1f || (gz[1] & 255) != 0x8b) {
			throw new Http.Fail(400, "That isn't a .litematic file");
		}
		long unzipped = 0;
		byte[] head = new byte[3];
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
			if (in.readNBytes(head, 0, 3) < 3 || head[0] != 10) {
				throw new Http.Fail(400, "That isn't a .litematic file");
			}
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) > 0) {
				unzipped += n;
				if (unzipped > 128L << 20) {
					throw new Http.Fail(400, "That schematic is too big");
				}
			}
		}
		try (var list = Files.list(dir)) {
			if (list.count() > 100_000) {
				throw new Http.Fail(507, "Storage is full right now");
			}
		}
		String id;
		do {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
			}
			id = sb.toString();
		} while (Files.exists(dir.resolve(id + ".litematic")));
		String clean = name == null ? "" : name.replaceAll("[^A-Za-z0-9 _.-]", "_").strip();
		Files.write(dir.resolve(id + ".litematic"), gz);
		Files.writeString(dir.resolve(id + ".name"), clean.isEmpty() ? "generated" : clean.length() > 48 ? clean.substring(0, 48) : clean, StandardCharsets.UTF_8);
		return Http.map("code", id, "command", "/velo schem " + id);
	}

	Object[] get(String id) throws IOException {
		if (id == null || !id.matches("[a-z0-9]{6,10}")) {
			return null;
		}
		Path f = dir.resolve(id + ".litematic");
		if (!Files.exists(f)) {
			return null;
		}
		Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
		Path n = dir.resolve(id + ".name");
		return new Object[] {Files.readAllBytes(f), (Files.exists(n) ? Files.readString(n) : "generated") + ".litematic"};
	}

	void sweep() {
		long cutoff = System.currentTimeMillis() - 60L * 86_400_000L;
		try (var list = Files.list(dir)) {
			list.filter(p -> p.toString().endsWith(".litematic")).filter(p -> {
				try {
					return Files.getLastModifiedTime(p).toMillis() < cutoff;
				} catch (IOException e) {
					return false;
				}
			}).forEach(p -> {
				p.toFile().delete();
				p.resolveSibling(p.getFileName().toString().replace(".litematic", ".name")).toFile().delete();
			});
		} catch (IOException ignored) {
			// next time
		}
	}
}
