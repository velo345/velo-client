package net.veloclient.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Server mappings (github.com/velo345/server-mappings, like Lunar's ServerMappings): servers add their name, logo and
 * addresses by pull request; the Library pulls the repo every 30 minutes and answers "which server is this address?" -
 * the game uses it for Discord Rich Presence. Until the repo is reachable, the copy bundled in the jar is used.
 */
final class Mappings {

	record Server(String id, JsonObject meta, byte[] logo, byte[] banner) {
	}

	private final String repo;
	private volatile Map<String, Server> servers = Map.of();
	private volatile Map<String, String> exact = Map.of();
	private volatile Map<String, String> wildcard = Map.of();
	private volatile String source = "none";

	Mappings(String repo) {
		this.repo = repo;
		loadBundled();
	}

	String source() {
		return source;
	}

	int count() {
		return servers.size();
	}

	/** Pulls the repo's main branch as a tarball (one request); keeps the old data when that fails. */
	void refresh() {
		try {
			Net.Response r = Net.get("https://codeload.github.com/" + repo + "/tar.gz/refs/heads/main", 64 << 20);
			if (r.status() != 200) {
				return;
			}
			Map<String, byte[]> files = untar(r.body());
			Map<String, Server> loaded = new LinkedHashMap<>();
			for (var e : files.entrySet()) {
				String[] p = e.getKey().split("/");
				// <repo>-main/servers/<id>/metadata.json
				if (p.length == 4 && p[1].equals("servers") && p[3].equals("metadata.json")) {
					String id = p[2];
					String base = p[0] + "/servers/" + id + "/";
					Server s = parse(id, e.getValue(), files.get(base + "logo.png"), files.get(base + "banner.png"));
					if (s != null) {
						loaded.put(id, s);
					}
				}
			}
			if (!loaded.isEmpty()) {
				install(loaded, "github:" + repo);
			}
		} catch (Exception e) {
			System.out.println("[library] server mappings refresh failed: " + e.getMessage());
		}
	}

	private void loadBundled() {
		try (InputStream idx = Mappings.class.getResourceAsStream("/server-mappings/index.txt")) {
			if (idx == null) {
				return;
			}
			Map<String, Server> loaded = new LinkedHashMap<>();
			for (String id : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\\s+")) {
				if (id.isBlank()) {
					continue;
				}
				Server s = parse(id, res("/server-mappings/servers/" + id + "/metadata.json"), res("/server-mappings/servers/" + id + "/logo.png"),
						res("/server-mappings/servers/" + id + "/banner.png"));
				if (s != null) {
					loaded.put(id, s);
				}
			}
			install(loaded, "bundled");
		} catch (IOException e) {
			System.out.println("[library] no bundled server mappings: " + e.getMessage());
		}
	}

	private static byte[] res(String path) throws IOException {
		try (InputStream in = Mappings.class.getResourceAsStream(path)) {
			return in == null ? null : in.readAllBytes();
		}
	}

	private static Server parse(String id, byte[] json, byte[] logo, byte[] background) {
		try {
			if (json == null || !id.matches("[a-z0-9][a-z0-9-]{1,40}")) {
				return null;
			}
			JsonObject meta = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
			if (!meta.has("name") || !meta.has("addresses")) {
				return null;
			}
			return new Server(id, meta, logo, background);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void install(Map<String, Server> loaded, String from) {
		Map<String, String> ex = new HashMap<>();
		Map<String, String> wc = new HashMap<>();
		for (Server s : loaded.values()) {
			for (var a : s.meta().getAsJsonArray("addresses")) {
				String addr = a.getAsString().toLowerCase(Locale.ROOT).replaceFirst(":25565$", "");
				if (addr.startsWith("*.")) {
					wc.putIfAbsent(addr.substring(2), s.id());
				} else {
					ex.putIfAbsent(addr, s.id());
				}
			}
		}
		servers = loaded;
		exact = ex;
		wildcard = wc;
		source = from;
		System.out.println("[library] server mappings: " + loaded.size() + " servers (" + from + ")");
	}

	/** The server an address belongs to: exact match first, then *.domain for every parent domain. */
	Server lookup(String address) {
		if (address == null) {
			return null;
		}
		String a = address.trim().toLowerCase(Locale.ROOT).replaceFirst(":25565$", "").replaceFirst("\\.$", "");
		String id = exact.get(a);
		if (id == null) {
			String host = a.replaceFirst(":\\d+$", "");
			id = exact.get(host);
			for (String d = host; id == null && d.contains("."); d = d.substring(d.indexOf('.') + 1)) {
				id = wildcard.get(d);
				if (id == null && d.equals(host)) {
					continue;
				}
			}
		}
		return id == null ? null : servers.get(id);
	}

	Server get(String id) {
		return servers.get(id);
	}

	List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Server s : servers.values()) {
			out.add(json(s));
		}
		return out;
	}

	static Map<String, Object> json(Server s) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (var e : s.meta().entrySet()) {
			m.put(e.getKey(), Http.GSON.fromJson(e.getValue(), Object.class));
		}
		m.put("id", s.id());
		m.put("logo", "https://velo-client.com/v2/servers/" + s.id() + "/logo.png");
		m.put("hasLogo", s.logo() != null);
		if (s.banner() != null) {
			m.put("banner", "https://velo-client.com/v2/servers/" + s.id() + "/banner.png");
		}
		return m;
	}

	/** A minimal tar reader (ustar names, regular files only) for GitHub's tarballs. */
	private static Map<String, byte[]> untar(byte[] gz) throws IOException {
		Map<String, byte[]> out = new HashMap<>();
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
			byte[] header = new byte[512];
			String longName = null;
			while (in.readNBytes(header, 0, 512) == 512) {
				if (header[0] == 0) {
					break;
				}
				String name = cstr(header, 0, 100);
				String prefix = cstr(header, 345, 155);
				long size = Long.parseLong(cstr(header, 124, 12).trim().isEmpty() ? "0" : cstr(header, 124, 12).trim(), 8);
				char type = (char) header[156];
				byte[] data = in.readNBytes((int) size);
				int pad = (int) ((512 - size % 512) % 512);
				in.readNBytes(pad);
				if (type == 'x' || type == 'g') {
					String pax = new String(data, StandardCharsets.UTF_8);
					int at = pax.indexOf(" path=");
					if (type == 'x' && at >= 0) {
						longName = pax.substring(at + 6, pax.indexOf('\n', at));
					}
					continue;
				}
				String full = longName != null ? longName : prefix.isEmpty() ? name : prefix + "/" + name;
				longName = null;
				if ((type == '0' || type == 0) && size <= 4L << 20 && out.size() < 20_000) {
					out.put(full, data);
				}
			}
		}
		return out;
	}

	private static String cstr(byte[] b, int off, int len) {
		int end = off;
		while (end < off + len && b[end] != 0) {
			end++;
		}
		return new String(b, off, end - off, StandardCharsets.US_ASCII);
	}
}
