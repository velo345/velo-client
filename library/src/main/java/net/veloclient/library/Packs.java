package net.veloclient.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resource pack hosting for servers: upload a pack, get a direct download link, its SHA-1 and ready
 * server.properties lines. The files themselves are served by nginx straight from disk ({@code /packs/<sha1>.zip}),
 * so players downloading a pack never touch this JVM. Uploads need a velo-client.com sign-in; quotas keep the disk safe.
 */
final class Packs {

	static final long MAX_BYTES = 50L << 20;
	static final int PER_USER = 10;
	static final long DISK_CAP = 25L << 30;

	static final class Pack {
		String sha1;
		String name;
		String owner;
		String ownerName;
		long size;
		long uploaded;
		int format;
		String description;
		boolean icon;
	}

	private final Path dir;
	private final Path uploads;
	private final JsonFile<Map<String, Pack>> index;
	private final String publicBase;

	Packs(Path dataDir, String publicBase) throws IOException {
		dir = Files.createDirectories(dataDir.resolve("packs"));
		readable(dir, "rwxr-xr-x");
		// half-uploaded files live outside the public folder
		uploads = Files.createDirectories(dataDir.resolve("pack-uploads"));
		index = new JsonFile<>(dataDir.resolve("packs.json"), new TypeToken<Map<String, Pack>>() {
		}.getType(), HashMap::new);
		this.publicBase = publicBase;
	}

	Pack upload(InputStream body, String name, Accounts.User user) throws IOException {
		Map<String, Pack> all = index.get();
		synchronized (all) {
			long mine = all.values().stream().filter(p -> p.owner.equals(user.uuid())).count();
			if (mine >= PER_USER) {
				throw new Http.Fail(400, "You already host " + PER_USER + " packs - delete one first");
			}
			long used = all.values().stream().mapToLong(p -> p.size).sum();
			if (used > DISK_CAP) {
				throw new Http.Fail(507, "Pack hosting is full right now - try again later");
			}
		}
		Path tmp = Files.createTempFile(uploads, "upload-", ".zip");
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-1");
			long size = 0;
			try (InputStream in = body; OutputStream out = new DigestOutputStream(Files.newOutputStream(tmp), sha)) {
				byte[] buf = new byte[64 * 1024];
				int n;
				while ((n = in.read(buf)) > 0) {
					size += n;
					if (size > MAX_BYTES) {
						throw new Http.Fail(413, "Packs can be up to 50 MB");
					}
					out.write(buf, 0, n);
				}
			}
			if (size < 30) {
				throw new Http.Fail(400, "That file is empty");
			}
			String hash = HexFormat.of().formatHex(sha.digest());
			Pack p = new Pack();
			p.sha1 = hash;
			p.size = size;
			p.owner = user.uuid();
			p.ownerName = user.username();
			p.uploaded = System.currentTimeMillis();
			p.name = clean(name, 60);
			try (ZipFile zip = new ZipFile(tmp.toFile())) {
				ZipEntry mcmeta = zip.getEntry("pack.mcmeta");
				if (mcmeta == null) {
					boolean nested = zip.stream().anyMatch(e -> e.getName().endsWith("/pack.mcmeta"));
					throw new Http.Fail(400, nested ? "pack.mcmeta is inside a folder - zip the pack's contents, not the folder around them"
							: "No pack.mcmeta in the zip - that isn't a resource pack");
				}
				try (InputStream in = zip.getInputStream(mcmeta)) {
					JsonObject meta = JsonParser.parseString(new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("pack");
					p.format = meta.has("pack_format") ? meta.get("pack_format").getAsInt()
							: meta.has("min_format") ? meta.get("min_format").getAsJsonArray().get(0).getAsInt() : 0;
					JsonElement d = meta.get("description");
					p.description = d == null ? "" : clean(d.isJsonPrimitive() ? d.getAsString() : Text.toPlain(Text.fromComponent(d)), 200);
				} catch (RuntimeException e) {
					throw new Http.Fail(400, "pack.mcmeta isn't valid JSON");
				}
				ZipEntry icon = zip.getEntry("pack.png");
				if (icon != null && icon.getSize() < 512 * 1024) {
					try (InputStream in = zip.getInputStream(icon)) {
						Files.write(dir.resolve(hash + ".png"), in.readNBytes(512 * 1024));
						p.icon = true;
					}
				}
			} catch (java.util.zip.ZipException e) {
				throw new Http.Fail(400, "That isn't a zip file");
			}
			if (p.name == null || p.name.isBlank()) {
				p.name = "Resource pack";
			}
			Path target = dir.resolve(hash + ".zip");
			if (!Files.exists(target)) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			}
			// nginx serves these straight from disk (as another user)
			readable(target, "rw-r--r--");
			if (p.icon) {
				readable(dir.resolve(hash + ".png"), "rw-r--r--");
			}
			Map<String, Pack> all2 = index.get();
			synchronized (all2) {
				all2.putIfAbsent(hash, p);
				index.changed();
			}
			return all2.get(hash);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	Pack get(String sha1) {
		if (sha1 == null || !sha1.matches("[0-9a-f]{40}")) {
			return null;
		}
		Map<String, Pack> all = index.get();
		synchronized (all) {
			return all.get(sha1);
		}
	}

	List<Map<String, Object>> mine(Accounts.User u) {
		Map<String, Pack> all = index.get();
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (all) {
			for (Pack p : all.values()) {
				if (p.owner.equals(u.uuid())) {
					out.add(json(p));
				}
			}
		}
		out.sort((a, b) -> Long.compare((long) b.get("uploaded"), (long) a.get("uploaded")));
		return out;
	}

	boolean delete(String sha1, Accounts.User u, boolean owner) throws IOException {
		Map<String, Pack> all = index.get();
		synchronized (all) {
			Pack p = all.get(sha1);
			if (p == null || (!p.owner.equals(u.uuid()) && !owner)) {
				return false;
			}
			all.remove(sha1);
			index.changed();
		}
		Files.deleteIfExists(dir.resolve(sha1 + ".zip"));
		Files.deleteIfExists(dir.resolve(sha1 + ".png"));
		return true;
	}

	byte[] icon(String sha1) throws IOException {
		Path p = dir.resolve(sha1 + ".png");
		return Files.exists(p) ? Files.readAllBytes(p) : null;
	}

	Map<String, Object> json(Pack p) {
		String url = publicBase + "/packs/" + p.sha1 + ".zip";
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("sha1", p.sha1);
		m.put("name", p.name);
		m.put("description", p.description);
		m.put("size", p.size);
		m.put("format", p.format);
		m.put("versions", versions(p.format));
		m.put("uploaded", p.uploaded);
		m.put("by", p.ownerName);
		m.put("url", url);
		m.put("icon", p.icon ? "/v2/packs/" + p.sha1 + "/icon.png" : null);
		m.put("serverProperties", "resource-pack=" + url.replace(":", "\\:") + "\nresource-pack-sha1=" + p.sha1
				+ "\nrequire-resource-pack=false\nresource-pack-prompt=");
		return m;
	}

	static String versions(int f) {
		return switch (f) {
			case 1 -> "1.6.1 – 1.8.9";
			case 2 -> "1.9 – 1.10.2";
			case 3 -> "1.11 – 1.12.2";
			case 4 -> "1.13 – 1.14.4";
			case 5 -> "1.15 – 1.16.1";
			case 6 -> "1.16.2 – 1.16.5";
			case 7 -> "1.17.x";
			case 8 -> "1.18.x";
			case 9 -> "1.19 – 1.19.2";
			case 12 -> "1.19.3";
			case 13 -> "1.19.4";
			case 15 -> "1.20 – 1.20.1";
			case 18 -> "1.20.2";
			case 22 -> "1.20.3 – 1.20.4";
			case 32 -> "1.20.5 – 1.20.6";
			case 34 -> "1.21 – 1.21.1";
			case 42 -> "1.21.2 – 1.21.3";
			case 46 -> "1.21.4";
			case 55 -> "1.21.5";
			case 63 -> "1.21.6";
			case 64 -> "1.21.7 – 1.21.8";
			default -> f > 64 ? "1.21.9 or newer" : f <= 0 ? "unknown" : "format " + f;
		};
	}

	private static String clean(String s, int max) {
		if (s == null) {
			return null;
		}
		String t = s.replaceAll("§.", "").replaceAll("[\\p{Cntrl}&&[^\n]]", "").strip();
		return t.length() > max ? t.substring(0, max) : t;
	}

	void save() {
		index.saveIfDirty();
	}

	private static void readable(Path p, String perms) {
		try {
			Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString(perms));
		} catch (Exception ignored) {
			// not a POSIX file system (local dev on Windows)
		}
	}
}
