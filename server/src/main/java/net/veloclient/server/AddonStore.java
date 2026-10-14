package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Velo Client Addons jars, kept on the server for Velo+ members only (never published on GitHub):
 * one per Minecraft version in {@code data/addons/}, uploaded by the owner after a build
 * ({@code tools/release/publish_addons.py}), downloaded by the launcher for members.
 */
final class AddonStore {

	static final int MAX_JAR_BYTES = 80 * 1024 * 1024;
	private static final Pattern MC = Pattern.compile("[0-9a-z.\\-]{1,16}");
	private static final Pattern VERSION = Pattern.compile("[0-9a-zA-Z.+\\-]{1,40}");
	private static final Gson GSON = new GsonBuilder().create();

	record Entry(String mc, String version, String sha256, long size, long uploadedAt) {
	}

	private final Path dir;

	AddonStore(Path dataDir) throws IOException {
		this.dir = dataDir.resolve("addons");
		Files.createDirectories(dir);
	}

	static boolean validMc(String mc) {
		return mc != null && MC.matcher(mc).matches();
	}

	synchronized Entry put(String mc, String version, byte[] jar) throws Exception {
		if (!validMc(mc) || version == null || !VERSION.matcher(version).matches()) {
			throw new IllegalArgumentException("mc and version are required (letters, digits, . + -)");
		}
		// A jar is a zip: PK header - nothing else is stored.
		if (jar.length < 1000 || jar[0] != 'P' || jar[1] != 'K') {
			throw new IllegalArgumentException("That isn't a jar file");
		}
		Entry entry = new Entry(mc, version, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jar)), jar.length, System.currentTimeMillis());
		Path tmp = Files.createTempFile(dir, "upload", ".tmp");
		Files.write(tmp, jar);
		Files.move(tmp, dir.resolve(mc + ".jar"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		Files.writeString(dir.resolve(mc + ".json"), GSON.toJson(entry), StandardCharsets.UTF_8);
		return entry;
	}

	synchronized Entry entry(String mc) throws IOException {
		Path meta = validMc(mc) ? dir.resolve(mc + ".json") : null;
		return meta != null && Files.exists(meta) ? GSON.fromJson(Files.readString(meta, StandardCharsets.UTF_8), Entry.class) : null;
	}

	synchronized byte[] jar(String mc) throws IOException {
		Path jar = validMc(mc) ? dir.resolve(mc + ".jar") : null;
		return jar != null && Files.exists(jar) ? Files.readAllBytes(jar) : null;
	}

	synchronized List<Entry> list() throws IOException {
		List<Entry> out = new ArrayList<>();
		try (var files = Files.list(dir)) {
			for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
				out.add(GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Entry.class));
			}
		}
		return out;
	}
}
