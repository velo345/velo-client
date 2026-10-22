package net.veloclient.panel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Small helpers around the JDK HTTP server: JSON in/out, query strings, cookies, the visitor's address. */
final class Http {

	static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

	/** Thrown by handlers to answer with a status and a message the UI shows. */
	static final class Fail extends RuntimeException {
		final int status;

		Fail(int status, String message) {
			super(message);
			this.status = status;
		}
	}

	private Http() {
	}

	static void json(HttpExchange ex, int status, Object body) throws IOException {
		bytes(ex, status, "application/json; charset=utf-8", GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
	}

	static void error(HttpExchange ex, int status, String message) throws IOException {
		json(ex, status, Map.of("error", message == null ? "Error" : message));
	}

	static void bytes(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
		ex.getResponseHeaders().set("Content-Type", contentType);
		ex.getResponseHeaders().set("Cache-Control", "no-store");
		ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
		if (body.length > 0) {
			try (OutputStream out = ex.getResponseBody()) {
				out.write(body);
			}
		}
		ex.close();
	}

	/** Reads at most {@code max} bytes of request body; larger bodies are refused with 413. */
	static byte[] body(HttpExchange ex, long max) throws IOException {
		String length = ex.getRequestHeaders().getFirst("Content-Length");
		if (length != null) {
			try {
				if (Long.parseLong(length.trim()) > max) {
					throw new Fail(413, "That's too big (limit " + (max / 1024 / 1024) + " MB)");
				}
			} catch (NumberFormatException ignored) {
				// Checked while reading below.
			}
		}
		try (InputStream in = ex.getRequestBody()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[64 * 1024];
			long total = 0;
			int n;
			while ((n = in.read(buffer)) > 0) {
				total += n;
				if (total > max) {
					throw new Fail(413, "That's too big (limit " + (max / 1024 / 1024) + " MB)");
				}
				out.write(buffer, 0, n);
			}
			return out.toByteArray();
		}
	}

	static <T> T read(HttpExchange ex, Class<T> type) throws IOException {
		byte[] raw = body(ex, 256 * 1024);
		try {
			T value = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), type);
			if (value == null) {
				throw new Fail(400, "Empty request");
			}
			return value;
		} catch (com.google.gson.JsonParseException e) {
			throw new Fail(400, "Malformed request");
		}
	}

	static Map<String, String> query(HttpExchange ex) {
		Map<String, String> out = new HashMap<>();
		String raw = ex.getRequestURI().getRawQuery();
		if (raw == null) {
			return out;
		}
		for (String part : raw.split("&")) {
			int eq = part.indexOf('=');
			if (eq > 0) {
				out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
			} else if (!part.isEmpty()) {
				out.put(URLDecoder.decode(part, StandardCharsets.UTF_8), "");
			}
		}
		return out;
	}

	static String cookie(HttpExchange ex, String name) {
		for (String header : ex.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
			for (String part : header.split(";")) {
				String p = part.trim();
				if (p.startsWith(name + "=")) {
					return p.substring(name.length() + 1);
				}
			}
		}
		return null;
	}

	/** nginx is the only thing that can reach the panel (it listens on 127.0.0.1) and sets X-Real-IP. */
	static String clientIp(HttpExchange ex) {
		String real = ex.getRequestHeaders().getFirst("X-Real-IP");
		return real != null && !real.isBlank() ? real.trim() : ex.getRemoteAddress().getAddress().getHostAddress();
	}
}
