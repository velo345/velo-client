package net.veloclient.panel;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Browsing and editing files, but only inside the configured roots ({@code PANEL_FILE_ROOTS}, e.g. the server's
 * data folder and the website). Paths from the browser are relative to a root; anything with {@code ..}, an
 * absolute path, or a symlink leading outside its root is refused. Writes go to a temp file first and are moved
 * into place, so a half-written JSON file is never seen by the server.
 */
final class FileManager {

	static final long MAX_EDIT_BYTES = 2L * 1024 * 1024;
	static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

	record Root(String id, String label, Path path, boolean readOnly) {
	}

	private final Map<String, Root> roots = new LinkedHashMap<>();

	/** {@code "Server data=/opt/velo/server/data;Backups=/opt/velo/backups:ro"}. */
	FileManager(String spec) {
		if (spec == null) {
			return;
		}
		int i = 0;
		for (String part : spec.split(";")) {
			int eq = part.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String label = part.substring(0, eq).trim();
			String path = part.substring(eq + 1).trim();
			boolean ro = path.endsWith(":ro");
			if (ro) {
				path = path.substring(0, path.length() - 3);
			}
			String id = "r" + i++;
			roots.put(id, new Root(id, label, Path.of(path).toAbsolutePath().normalize(), ro));
		}
	}

	List<Map<String, Object>> roots() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Root r : roots.values()) {
			out.add(Map.of("id", r.id(), "label", r.label(), "path", r.path().toString(), "readOnly", r.readOnly(), "exists", Files.isDirectory(r.path())));
		}
		return out;
	}

	private Root root(String id) {
		Root r = roots.get(id);
		if (r == null) {
			throw new Http.Fail(404, "No such folder");
		}
		return r;
	}

	/** Resolves a browser path inside its root, refusing anything that leaves it. */
	Path resolve(String rootId, String relative) throws IOException {
		Root root = root(rootId);
		String rel = relative == null ? "" : relative.replace('\\', '/');
		if (rel.indexOf('\0') >= 0 || rel.startsWith("/") || rel.matches("(^|.*/)\\.\\.(/.*|$)")) {
			throw new Http.Fail(400, "Invalid path");
		}
		Path rootReal = root.path().toRealPath();
		Path target = rootReal.resolve(rel).normalize();
		if (!target.startsWith(rootReal)) {
			throw new Http.Fail(400, "Invalid path");
		}
		// The deepest part of the path that exists must still be inside the root once symlinks are followed.
		Path existing = target;
		while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
			existing = existing.getParent();
		}
		if (existing == null || !existing.toRealPath().startsWith(rootReal)) {
			throw new Http.Fail(400, "Invalid path");
		}
		return target;
	}

	private void writable(String rootId) {
		if (root(rootId).readOnly()) {
			throw new Http.Fail(403, "This folder is read-only in the panel");
		}
	}

	private boolean isRoot(String rootId, Path p) throws IOException {
		return p.equals(root(rootId).path().toRealPath());
	}

	List<Map<String, Object>> list(String rootId, String relative) throws IOException {
		Path dir = resolve(rootId, relative);
		if (!Files.isDirectory(dir)) {
			throw new Http.Fail(404, "Not a folder");
		}
		List<Map<String, Object>> out = new ArrayList<>();
		try (Stream<Path> entries = Files.list(dir)) {
			for (Path p : entries.sorted(Comparator.comparing((Path q) -> !Files.isDirectory(q)).thenComparing(q -> q.getFileName().toString().toLowerCase())).toList()) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("name", p.getFileName().toString());
				boolean directory = Files.isDirectory(p);
				row.put("dir", directory);
				row.put("link", Files.isSymbolicLink(p));
				try {
					PosixFileAttributes a = Files.readAttributes(p, PosixFileAttributes.class);
					row.put("size", directory ? 0 : a.size());
					row.put("modified", a.lastModifiedTime().toMillis());
					row.put("mode", PosixFilePermissions.toString(a.permissions()));
					row.put("owner", a.owner().getName());
				} catch (IOException | UnsupportedOperationException e) {
					row.put("size", directory ? 0 : Files.size(p));
					row.put("modified", Files.getLastModifiedTime(p).toMillis());
				}
				out.add(row);
			}
		}
		return out;
	}

	/** A text file's content for the editor ({@code null} text when it isn't UTF-8 text or too big). */
	Map<String, Object> read(String rootId, String relative) throws IOException {
		Path file = resolve(rootId, relative);
		if (!Files.isRegularFile(file)) {
			throw new Http.Fail(404, "Not a file");
		}
		long size = Files.size(file);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("size", size);
		out.put("modified", Files.getLastModifiedTime(file).toMillis());
		String text = null;
		if (size <= MAX_EDIT_BYTES) {
			byte[] bytes = Files.readAllBytes(file);
			if (!looksBinary(bytes)) {
				try {
					text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
				} catch (CharacterCodingException e) {
					text = null;
				}
			}
		}
		out.put("text", text);
		out.put("editable", text != null && !root(rootId).readOnly());
		return out;
	}

	private static boolean looksBinary(byte[] bytes) {
		int n = Math.min(bytes.length, 8000);
		for (int i = 0; i < n; i++) {
			if (bytes[i] == 0) {
				return true;
			}
		}
		return false;
	}

	Path download(String rootId, String relative) throws IOException {
		Path file = resolve(rootId, relative);
		if (!Files.isRegularFile(file)) {
			throw new Http.Fail(404, "Not a file");
		}
		return file;
	}

	/** Writes (creates or replaces) a file atomically, keeping the old file's permissions. */
	void write(String rootId, String relative, byte[] content, Long expectedModified) throws IOException {
		writable(rootId);
		Path file = resolve(rootId, relative);
		if (Files.isDirectory(file)) {
			throw new Http.Fail(400, "That's a folder");
		}
		if (!Files.isDirectory(file.getParent())) {
			throw new Http.Fail(404, "The folder doesn't exist");
		}
		if (expectedModified != null && Files.exists(file) && Files.getLastModifiedTime(file).toMillis() != expectedModified) {
			throw new Http.Fail(409, "The file changed on the server since you opened it - reload it first");
		}
		Path tmp = Files.createTempFile(file.getParent(), ".panel-", ".tmp");
		try {
			Files.write(tmp, content);
			if (Files.exists(file)) {
				try {
					Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(file));
				} catch (IOException | UnsupportedOperationException ignored) {
					// Keeps the temp file's default permissions.
				}
			} else {
				try {
					Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-rw-r--"));
				} catch (IOException | UnsupportedOperationException ignored) {
					// Keeps the default.
				}
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	void mkdir(String rootId, String relative) throws IOException {
		writable(rootId);
		Files.createDirectories(resolve(rootId, relative));
	}

	void rename(String rootId, String from, String to) throws IOException {
		writable(rootId);
		Path source = resolve(rootId, from);
		Path target = resolve(rootId, to);
		if (isRoot(rootId, source)) {
			throw new Http.Fail(400, "Can't move the folder itself");
		}
		if (Files.exists(target)) {
			throw new Http.Fail(409, "Something with that name already exists");
		}
		Files.move(source, target);
	}

	/** Deletes a file, or a folder with everything in it when {@code recursive}. */
	int delete(String rootId, String relative, boolean recursive) throws IOException {
		writable(rootId);
		Path target = resolve(rootId, relative);
		if (isRoot(rootId, target)) {
			throw new Http.Fail(400, "Can't delete the folder itself");
		}
		if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new Http.Fail(404, "Not found");
		}
		if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
			if (!recursive) {
				try {
					Files.delete(target);
					return 1;
				} catch (DirectoryNotEmptyException e) {
					throw new Http.Fail(409, "The folder isn't empty");
				}
			}
			List<Path> all;
			try (Stream<Path> walk = Files.walk(target)) {
				all = walk.sorted(Comparator.reverseOrder()).toList();
			}
			for (Path p : all) {
				Files.delete(p);
			}
			return all.size();
		}
		Files.delete(target);
		return 1;
	}
}
