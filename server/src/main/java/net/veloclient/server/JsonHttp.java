package net.veloclient.server;

import com.google.gson.Gson;
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

	private static final Gson GSON = new Gson();

	private JsonHttp() {
	}

	static <T> T readBody(HttpExchange exchange, Class<T> type) throws IOException {
		byte[] bytes;
		try (InputStream in = exchange.getRequestBody()) {
			bytes = in.readNBytes(MAX_BODY_BYTES + 1);
		}
		if (bytes.length > MAX_BODY_BYTES) {
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
