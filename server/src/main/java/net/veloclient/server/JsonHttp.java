package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Tiny JSON request/response helpers shared by every {@link com.sun.net.httpserver.HttpHandler} here. */
final class JsonHttp {

	// Nothing this API accepts is anywhere near this size - a generous cap
	// just to stop a malformed/hostile client from making the server buffer
	// an unbounded body into memory.
	private static final int MAX_BODY_BYTES = 16 * 1024;

	// serializeNulls: a null capeId has to reach the client as an explicit null (heartbeat uses it
	// to signal "your cloak was not accepted"), not as a missing field.
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	private JsonHttp() {
	}

	static <T> T readBody(HttpExchange exchange, Class<T> type) throws IOException {
		return readBody(exchange, type, MAX_BODY_BYTES);
	}

	/** {@link #readBody(HttpExchange, Class)} for the few endpoints that take bigger documents (news posts, bug reports). */
	static <T> T readBody(HttpExchange exchange, Class<T> type, int maxBytes) throws IOException {
		byte[] bytes;
		try (InputStream in = exchange.getRequestBody()) {
			bytes = in.readNBytes(maxBytes + 1);
		}
		if (bytes.length > maxBytes) {
			throw new IOException("Request body too large");
		}
		String json = new String(bytes, StandardCharsets.UTF_8);
		try {
			T parsed = GSON.fromJson(json, type);
			if (parsed == null) {
				throw new IOException("Empty request body");
			}
			return parsed;
		} catch (JsonSyntaxException e) {
			throw new IOException("Malformed JSON body");
		}
	}

	/** Reads a raw (non-JSON) body of at most {@code maxBytes}, e.g. an uploaded cape image. */
	static byte[] readRawBody(HttpExchange exchange, int maxBytes) throws IOException {
		byte[] bytes;
		try (InputStream in = exchange.getRequestBody()) {
			bytes = in.readNBytes(maxBytes + 1);
		}
		if (bytes.length > maxBytes) {
			throw new IOException("Request body too large");
		}
		return bytes;
	}

	/** The {@code Authorization: Bearer <token>} value, or null. */
	static String bearerToken(HttpExchange exchange) {
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null || !header.startsWith("Bearer ")) {
			return null;
		}
		String token = header.substring("Bearer ".length()).trim();
		return token.isEmpty() ? null : token;
	}

	static void writeBytes(HttpExchange exchange, int status, String contentType, byte[] bytes, String cacheControl) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		if (cacheControl != null) {
			exchange.getResponseHeaders().set("Cache-Control", cacheControl);
		}
		exchange.sendResponseHeaders(status, bytes.length);
		try (var out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	/**
	 * Streams a file from disk (a small buffer, not the whole file in memory - art files are up to 12 MB and many
	 * players load them at once). {@code etag}: the content hash, answered with 304 when the client has it.
	 */
	static void writeFile(HttpExchange exchange, java.nio.file.Path path, long size, String contentType, String etag, String cacheControl)
			throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		if (cacheControl != null) {
			exchange.getResponseHeaders().set("Cache-Control", cacheControl);
		}
		if (etag != null) {
			exchange.getResponseHeaders().set("ETag", "\"" + etag + "\"");
			String ifNone = exchange.getRequestHeaders().getFirst("If-None-Match");
			if (ifNone != null && ifNone.contains(etag)) {
				exchange.sendResponseHeaders(304, -1);
				exchange.close();
				return;
			}
		}
		exchange.sendResponseHeaders(200, size);
		try (var out = exchange.getResponseBody()) {
			java.nio.file.Files.copy(path, out);
		}
	}

	static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (var out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	static void writeError(HttpExchange exchange, int status, String message) throws IOException {
		writeJson(exchange, status, new ErrorBody(message));
	}

	record ErrorBody(String error) {
	}
}
