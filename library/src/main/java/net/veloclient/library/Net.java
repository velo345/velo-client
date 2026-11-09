package net.veloclient.library;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbound HTTP for the Library: short timeouts, a size cap on every answer, and a per-host budget so a flood of
 * Library calls can never turn into a flood against Mojang, Modrinth or anyone else (over the budget we fail fast).
 */
final class Net {

	static final String UA = "VeloLibrary/1.0 (+https://velo-client.com/library)";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	/** requests per second and burst, per host */
	private static final Map<String, double[]> BUDGET = Map.of(
			"api.mojang.com", new double[] {3, 20},
			"sessionserver.mojang.com", new double[] {4, 30},
			"api.minecraftservices.com", new double[] {3, 20},
			"textures.minecraft.net", new double[] {10, 50},
			"api.modrinth.com", new double[] {4, 30},
			"hangar.papermc.io", new double[] {3, 20},
			"api.spiget.org", new double[] {3, 20},
			"bytebin.lucko.me", new double[] {1, 6});
	private static final double[] DEFAULT_BUDGET = {2, 10};
	private static final Map<String, double[]> STATE = new ConcurrentHashMap<>();

	record Response(int status, byte[] body, Map<String, java.util.List<String>> headers) {
		String text() {
			return new String(body, java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	private Net() {
	}

	private static void spend(String host) {
		double[] budget = BUDGET.getOrDefault(host, DEFAULT_BUDGET);
		double[] s = STATE.computeIfAbsent(host, h -> new double[] {budget[1], System.currentTimeMillis()});
		synchronized (s) {
			long now = System.currentTimeMillis();
			s[0] = Math.min(budget[1], s[0] + (now - s[1]) / 1000.0 * budget[0]);
			s[1] = now;
			if (s[0] < 1) {
				throw new Http.Fail(503, host + " is being asked too often right now - try again shortly", 10);
			}
			s[0] -= 1;
		}
	}

	static Response get(String url, int maxBytes) throws IOException {
		return send(HttpRequest.newBuilder(URI.create(url)).GET(), url, maxBytes);
	}

	static Response postJson(String url, String json, int maxBytes) throws IOException {
		return send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json)), url, maxBytes);
	}

	private static Response send(HttpRequest.Builder b, String url, int maxBytes) throws IOException {
		String host = URI.create(url).getHost();
		if (!host.equals("127.0.0.1")) {
			spend(host);
		}
		try {
			HttpResponse<InputStream> r = HTTP.send(b.header("User-Agent", UA).timeout(Duration.ofSeconds(10)).build(),
					HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream in = r.body()) {
				byte[] body = in.readNBytes(maxBytes + 1);
				if (body.length > maxBytes) {
					throw new IOException("answer from " + host + " too large");
				}
				return new Response(r.statusCode(), body, r.headers().map());
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		}
	}

	/**
	 * Addresses players type (server status, banners) must not reach our own machine or private networks - that
	 * would let anyone probe internal services through us.
	 */
	static boolean isPublic(InetAddress a) {
		if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) {
			return false;
		}
		byte[] b = a.getAddress();
		if (b.length == 4) {
			int x = b[0] & 255, y = b[1] & 255;
			return !(x == 0 || x == 10 || x == 127 || (x == 100 && y >= 64 && y <= 127) || (x == 169 && y == 254)
					|| (x == 172 && y >= 16 && y <= 31) || (x == 192 && y == 168) || (x == 198 && (y == 18 || y == 19)) || x >= 224);
		}
		int first = b[0] & 255;
		return !((first & 0xfe) == 0xfc || (first == 0xfe && (b[1] & 0xc0) == 0x80) || first == 0xff);
	}
}
