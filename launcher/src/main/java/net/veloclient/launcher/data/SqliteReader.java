package net.veloclient.launcher.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough of SQLite's file format to read whole tables of another launcher's database (NoRisk's app.db) without a
 * JDBC driver: table and index b-trees, overflow pages, and the write-ahead log (the other launcher may be open, its
 * newest rows then only live in {@code -wal}). Read-only; the files are copied into memory, never opened for writing.
 */
final class SqliteReader {

	private final ByteBuffer db;
	private final int pageSize;
	private final int usable;
	/** Newer copies of pages from the committed part of the WAL. */
	private final Map<Integer, byte[]> walPages = new HashMap<>();
	private final Map<String, Integer> roots = new HashMap<>();
	private final Map<String, String> sql = new HashMap<>();

	SqliteReader(Path file) throws IOException {
		byte[] bytes = Files.readAllBytes(file);
		if (bytes.length < 100 || !new String(bytes, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3")) {
			throw new IOException("not an SQLite database: " + file);
		}
		db = ByteBuffer.wrap(bytes);
		int size = db.getShort(16) & 0xFFFF;
		pageSize = size == 1 ? 65536 : size;
		usable = pageSize - (bytes[20] & 0xFF);
		readWal(file.resolveSibling(file.getFileName() + "-wal"));
		for (Map<String, Object> row : rows(1)) {
			Object type = row.get("c0");
			Object name = row.get("c1");
			Object root = row.get("c3");
			if (("table".equals(type) || "index".equals(type)) && name instanceof String n && root instanceof Long r) {
				roots.put(n, r.intValue());
				if (row.get("c4") instanceof String s) {
					sql.put(n, s);
				}
			}
		}
	}

	/** Every row of {@code table} as column-name -> value (Long, Double, String, byte[] or null). Empty if there's no such table. */
	List<Map<String, Object>> table(String table) throws IOException {
		Integer root = roots.get(table);
		if (root == null) {
			return List.of();
		}
		List<String> columns = columns(sql.getOrDefault(table, ""));
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> raw : rows(root)) {
			Map<String, Object> named = new LinkedHashMap<>();
			for (int i = 0; ; i++) {
				if (!raw.containsKey("c" + i)) {
					break;
				}
				named.put(i < columns.size() ? columns.get(i) : "c" + i, raw.get("c" + i));
			}
			out.add(named);
		}
		return out;
	}

	/** Column names from a CREATE TABLE statement, in order (constraints like PRIMARY KEY (...) skipped). */
	static List<String> columns(String create) {
		List<String> out = new ArrayList<>();
		int open = create.indexOf('(');
		int close = create.lastIndexOf(')');
		if (open < 0 || close <= open) {
			return out;
		}
		String body = create.substring(open + 1, close);
		int depth = 0;
		StringBuilder part = new StringBuilder();
		List<String> parts = new ArrayList<>();
		for (char c : body.toCharArray()) {
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			}
			if (c == ',' && depth == 0) {
				parts.add(part.toString());
				part.setLength(0);
			} else {
				part.append(c);
			}
		}
		parts.add(part.toString());
		for (String p : parts) {
			String t = p.strip();
			if (t.isEmpty()) {
				continue;
			}
			String first = t.split("\\s+")[0].replaceAll("[\"`\\[\\]]", "");
			String upper = first.toUpperCase(java.util.Locale.ROOT);
			if (upper.equals("PRIMARY") || upper.equals("UNIQUE") || upper.equals("FOREIGN") || upper.equals("CHECK") || upper.equals("CONSTRAINT")) {
				continue;
			}
			out.add(first);
		}
		return out;
	}

	private void readWal(Path wal) {
		try {
			if (!Files.isRegularFile(wal) || Files.size(wal) < 32) {
				return;
			}
			ByteBuffer w = ByteBuffer.wrap(Files.readAllBytes(wal));
			int magic = w.getInt(0);
			if ((magic & 0xFFFFFFFE) != 0x377F0682 || w.getInt(8) != pageSize) {
				return;
			}
			int salt1 = w.getInt(16);
			int salt2 = w.getInt(20);
			Map<Integer, byte[]> pending = new HashMap<>();
			for (int at = 32; at + 24 + pageSize <= w.capacity(); at += 24 + pageSize) {
				if (w.getInt(at + 8) != salt1 || w.getInt(at + 12) != salt2) {
					break; // an older, already checkpointed generation
				}
				byte[] page = new byte[pageSize];
				w.get(at + 24, page);
				pending.put(w.getInt(at), page);
				if (w.getInt(at + 4) != 0) { // commit frame: everything so far is real
					walPages.putAll(pending);
					pending.clear();
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// no usable WAL: the main file alone
		}
	}

	private ByteBuffer page(int number) {
		byte[] fromWal = walPages.get(number);
		if (fromWal != null) {
			return ByteBuffer.wrap(fromWal);
		}
		int offset = (number - 1) * pageSize;
		if (number < 1 || offset + pageSize > db.capacity()) {
			throw new IllegalStateException("page " + number + " outside the file");
		}
		return db.slice(offset, pageSize);
	}

	/** Records of a b-tree as c0, c1, ... (table and index trees alike). */
	private List<Map<String, Object>> rows(int root) {
		List<Map<String, Object>> out = new ArrayList<>();
		walk(root, out, 0);
		return out;
	}

	private void walk(int number, List<Map<String, Object>> out, int depth) {
		if (depth > 40) {
			return;
		}
		ByteBuffer p = page(number);
		int h = number == 1 ? 100 : 0;
		int type = p.get(h) & 0xFF;
		int cells = p.getShort(h + 3) & 0xFFFF;
		boolean interior = type == 0x05 || type == 0x02;
		int pointers = h + (interior ? 12 : 8);
		for (int i = 0; i < cells; i++) {
			int cell = p.getShort(pointers + 2 * i) & 0xFFFF;
			int[] pos = {cell};
			switch (type) {
				case 0x05 -> walk(p.getInt(cell), out, depth + 1); // table interior: child, then rowid
				case 0x0D -> {
					long payload = varint(p, pos);
					varint(p, pos); // rowid
					out.add(record(payload(p, pos[0], payload, false)));
				}
				case 0x02 -> {
					walk(p.getInt(cell), out, depth + 1);
					pos[0] = cell + 4;
					long payload = varint(p, pos);
					out.add(record(payload(p, pos[0], payload, true)));
				}
				case 0x0A -> {
					long payload = varint(p, pos);
					out.add(record(payload(p, pos[0], payload, true)));
				}
				default -> {
					return;
				}
			}
		}
		if (interior) {
			walk(p.getInt(h + 8), out, depth + 1);
		}
	}

	/** A cell's payload, following overflow pages when it doesn't fit on its page. */
	private byte[] payload(ByteBuffer p, int start, long size, boolean index) {
		int total = (int) size;
		int max = index ? (usable - 12) * 64 / 255 - 23 : usable - 35;
		byte[] out = new byte[total];
		if (total <= max) {
			p.get(start, out, 0, total);
			return out;
		}
		int min = (usable - 12) * 32 / 255 - 23;
		int k = min + (total - min) % (usable - 4);
		int local = k <= max ? k : min;
		p.get(start, out, 0, local);
		int next = p.getInt(start + local);
		int done = local;
		int guard = 0;
		while (done < total && next != 0 && guard++ < 100_000) {
			ByteBuffer o = page(next);
			int n = Math.min(usable - 4, total - done);
			o.get(4, out, done, n);
			done += n;
			next = o.getInt(0);
		}
		return out;
	}

	private static Map<String, Object> record(byte[] bytes) {
		ByteBuffer b = ByteBuffer.wrap(bytes);
		int[] pos = {0};
		long headerSize = varint(b, pos);
		List<Long> types = new ArrayList<>();
		while (pos[0] < headerSize) {
			types.add(varint(b, pos));
		}
		int at = (int) headerSize;
		Map<String, Object> row = new LinkedHashMap<>();
		for (int i = 0; i < types.size(); i++) {
			long t = types.get(i);
			Object value;
			int len;
			if (t == 0) {
				value = null;
				len = 0;
			} else if (t >= 1 && t <= 6) {
				len = t == 5 ? 6 : t == 6 ? 8 : (int) t == 4 ? 4 : (int) t;
				long v = 0;
				for (int j = 0; j < len; j++) {
					v = (v << 8) | (bytes[at + j] & 0xFF);
				}
				int bits = len * 8;
				value = bits < 64 ? (v << (64 - bits)) >> (64 - bits) : v; // sign-extend
			} else if (t == 7) {
				len = 8;
				value = b.getDouble(at);
			} else if (t == 8 || t == 9) {
				len = 0;
				value = t == 8 ? 0L : 1L;
			} else if (t >= 12 && t % 2 == 0) {
				len = (int) ((t - 12) / 2);
				byte[] blob = new byte[len];
				System.arraycopy(bytes, at, blob, 0, len);
				value = blob;
			} else if (t >= 13) {
				len = (int) ((t - 13) / 2);
				value = new String(bytes, at, len, StandardCharsets.UTF_8);
			} else {
				len = 0;
				value = null;
			}
			row.put("c" + i, value);
			at += len;
		}
		return row;
	}

	private static long varint(ByteBuffer b, int[] pos) {
		long v = 0;
		for (int i = 0; i < 9; i++) {
			int x = b.get(pos[0]++) & 0xFF;
			if (i == 8) {
				return (v << 8) | x;
			}
			v = (v << 7) | (x & 0x7F);
			if ((x & 0x80) == 0) {
				return v;
			}
		}
		return v;
	}
}
