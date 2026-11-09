package net.veloclient.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** One request: path parts after /v2/, query, the caller's address and key, plus the response helpers. */
final class Http {

	static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	/** Thrown anywhere in a route to answer with that status and message. */
	static final class Fail extends RuntimeException {
		final int status;
		final long retryAfterSeconds;

		Fail(int status, String message) {
			this(status, message, 0);
		}

		Fail(int status, String message, long retryAfterSeconds) {
			super(message, null, false, false);
			this.status = status;
			this.retryAfterSeconds = retryAfterSeconds;
		}
	}

	final HttpExchange ex;
	final String method;
	final String[] parts;
	final Map<String, String> query;
	final String ip;
	final String token;
	Guard.Identity identity;

	Http(HttpExchange ex) {
		this.ex = ex;
		this.method = ex.getRequestMethod().toUpperCase(java.util.Locale.ROOT);
		String path = ex.getRequestURI().getRawPath();
		String rest = path.startsWith("/v2/") ? path.substring(4) : path.replaceFirst("^/+", "");
		String[] raw = rest.isEmpty() ? new String[0] : rest.split("/");
		parts = new String[raw.length];
		for (int i = 0; i < raw.length; i++) {
			parts[i] = URLDecoder.decode(raw[i], StandardCharsets.UTF_8);
		}
		query = parseQuery(ex.getRequestURI().getRawQuery());
		String real = ex.getRequestHeaders().getFirst("X-Real-IP");
		ip = real != null && !real.isBlank() ? real.trim() : ex.getRemoteAddress().getAddress().getHostAddress();
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		String t = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : query.get("key");
		token = t == null || t.isBlank() ? null : t;
	}

	String part(int i) {
		return i < parts.length ? parts[i] : null;
	}

	String q(String name, String fallback) {
		String v = query.get(name);
		return v == null || v.isBlank() ? fallback : v;
	}

	int qi(String name, int fallback, int min, int max) {
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(q(name, String.valueOf(fallback)).trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	double qd(String name, double fallback, double min, double max) {
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(q(name, String.valueOf(fallback)).trim())));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	boolean qb(String name, boolean fallback) {
		String v = query.get(name);
		return v == null ? fallback : v.isEmpty() || v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes");
	}

	byte[] body(int max) throws IOException {
		try (InputStream in = ex.getRequestBody()) {
			byte[] b = in.readNBytes(max + 1);
			if (b.length > max) {
				throw new Fail(413, "Too large (max " + (max / 1024 / 1024 > 0 ? max / 1024 / 1024 + " MB" : max / 1024 + " KB") + ")");
			}
			return b;
		}
	}

	<T> T json(Class<T> type, int max) throws IOException {
		try {
			T t = GSON.fromJson(new String(body(max), StandardCharsets.UTF_8), type);
			if (t == null) {
				throw new Fail(400, "Empty body");
			}
			return t;
		} catch (JsonSyntaxException e) {
			throw new Fail(400, "Malformed JSON");
		}
	}

	void send(int status, String type, byte[] bytes, int cacheSeconds) throws IOException {
		var h = ex.getResponseHeaders();
		h.set("Content-Type", type);
		h.set("X-Content-Type-Options", "nosniff");
		h.set("Cache-Control", cacheSeconds > 0 ? "public, max-age=" + cacheSeconds : "no-store");
		if ("GET".equals(method)) {
			h.set("Access-Control-Allow-Origin", "*");
		}
		if ("HEAD".equals(method)) {
			ex.sendResponseHeaders(status, -1);
			return;
		}
		ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (var out = ex.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	void json(int status, Object body, int cacheSeconds) throws IOException {
		send(status, "application/json; charset=utf-8", GSON.toJson(body).getBytes(StandardCharsets.UTF_8), cacheSeconds);
	}

	void png(byte[] png, int cacheSeconds) throws IOException {
		send(200, "image/png", png, cacheSeconds);
	}

	static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	private static Map<String, String> parseQuery(String raw) {
		Map<String, String> out = new LinkedHashMap<>();
		if (raw == null || raw.isEmpty()) {
			return out;
		}
		for (String pair : raw.split("&")) {
			if (out.size() > 40) {
				break;
			}
			int eq = pair.indexOf('=');
			String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
			String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
			out.putIfAbsent(k, v.length() > 2000 ? v.substring(0, 2000) : v);
		}
		return out;
	}
}
