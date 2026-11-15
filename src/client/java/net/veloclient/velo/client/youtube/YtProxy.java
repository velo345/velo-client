package net.veloclient.velo.client.youtube;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny local relay between FFmpeg and YouTube's video servers. FFmpeg opens a stream with an open-ended
 * {@code Range: bytes=0-} request, which YouTube answers with 403 for most of its clients - yt-dlp itself downloads in
 * bounded pieces. This relay listens on 127.0.0.1 only, accepts FFmpeg's requests (including seeks) and fetches the asked
 * part in bounded pieces of {@link #PIECE} bytes. Plain sockets with daemon threads: nothing here can keep the game from
 * closing.
 */
final class YtProxy {

	private static final long PIECE = 4L * 1024 * 1024;
	private static final Pattern CLEN = Pattern.compile("[?&]clen=(\\d+)");
	private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");
	/** The newest links, oldest dropped first (a random drop could hit the video that's playing - FFmpeg then got 404s and hung). */
	private static final Map<String, Target> TARGETS = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Target> eldest) {
			return size() > 48;
		}
	});
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private static ServerSocket server;

	private record Target(String url, String userAgent, long[] length) {
	}

	private YtProxy() {
	}

	/** A local link FFmpeg can open for {@code url} (only YouTube's googlevideo links go through the relay). */
	static synchronized String local(String url, String userAgent) throws IOException {
		if (url == null || !url.contains("googlevideo.com/videoplayback")) {
			return url;
		}
		if (server == null || server.isClosed()) {
			server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
			Thread accept = new Thread(YtProxy::acceptLoop, "Velo YouTube relay");
			accept.setDaemon(true);
			accept.start();
		}
		String key = Long.toHexString(Double.doubleToLongBits(Math.random())) + Integer.toHexString(url.hashCode());
		Matcher m = CLEN.matcher(url);
		TARGETS.put(key, new Target(url, userAgent, new long[] {m.find() ? Long.parseLong(m.group(1)) : -1}));
		return "http://127.0.0.1:" + server.getLocalPort() + "/" + key;
	}

	private static void acceptLoop() {
		ServerSocket s = server;
		while (s != null && !s.isClosed()) {
			try {
				Socket client = s.accept();
				Thread t = new Thread(() -> serve(client), "Velo YouTube relay request");
				t.setDaemon(true);
				t.start();
			} catch (IOException e) {
				return;
			}
		}
	}

	private static void serve(Socket client) {
		try (client) {
			client.setSoTimeout(30_000);
			BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
			String requestLine = in.readLine();
			if (requestLine == null) {
				return;
			}
			String range = null;
			String line;
			while ((line = in.readLine()) != null && !line.isEmpty()) {
				int colon = line.indexOf(':');
				if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("range")) {
					range = line.substring(colon + 1).trim();
				}
			}
			String[] parts = requestLine.split(" ");
			boolean head = parts[0].equalsIgnoreCase("HEAD");
			String key = parts.length > 1 ? parts[1].replaceFirst("^/", "") : "";
			Target target = TARGETS.get(key);
			OutputStream out = client.getOutputStream();
			if (target == null) {
				out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				return;
			}
			long total = length(target);
			long from = 0;
			long to = total - 1;
			boolean partial = false;
			if (range != null) {
				Matcher m = RANGE.matcher(range);
				if (m.find()) {
					from = Long.parseLong(m.group(1));
					if (!m.group(2).isEmpty()) {
						to = Math.min(to, Long.parseLong(m.group(2)));
					}
					partial = true;
				}
			}
			if (total <= 0 || from > to) {
				out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
				return;
			}
			if (head) {
				String h = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\nContent-Length: " + total
						+ "\r\nConnection: close\r\n\r\n";
				out.write(h.getBytes(StandardCharsets.ISO_8859_1));
				return;
			}
			// the first piece before answering: when YouTube refuses (expired / blocked link), FFmpeg gets a real error
			// instead of "OK" and a cut-off body it would retry forever
			long firstEnd = Math.min(to, from + PIECE - 1);
			HttpResponse<InputStream> first = fetch(target, from, firstEnd);
			if (first.statusCode() >= 400) {
				first.body().close();
				out.write(("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
				return;
			}
			String headers = (partial ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n")
					+ "Content-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n"
					+ (partial ? "Content-Range: bytes " + from + "-" + to + "/" + total + "\r\n" : "")
					+ "Content-Length: " + (to - from + 1) + "\r\nConnection: close\r\n\r\n";
			out.write(headers.getBytes(StandardCharsets.ISO_8859_1));
			byte[] buffer = new byte[64 * 1024];
			for (long at = from; at <= to; at += PIECE) {
				long end = Math.min(to, at + PIECE - 1);
				HttpResponse<InputStream> piece = at == from ? first : fetch(target, at, end);
				if (piece.statusCode() >= 400) {
					piece.body().close();
					return;
				}
				try (InputStream body = piece.body()) {
					int n;
					while ((n = body.read(buffer)) > 0) {
						out.write(buffer, 0, n);
					}
				}
			}
			out.flush();
		} catch (Exception ignored) {
			// FFmpeg hung up (seek, stop) - normal
		}
	}

	private static HttpResponse<InputStream> fetch(Target target, long from, long to) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target.url() + "&range=" + from + "-" + to)).timeout(Duration.ofSeconds(30)).GET();
		if (target.userAgent() != null) {
			request.header("User-Agent", target.userAgent());
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
	}

	/** The stream's full size: from its clen parameter, else asked once with a one-byte range. */
	private static long length(Target target) {
		if (target.length()[0] > 0) {
			return target.length()[0];
		}
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target.url())).timeout(Duration.ofSeconds(20)).header("Range", "bytes=0-0").GET();
			if (target.userAgent() != null) {
				request.header("User-Agent", target.userAgent());
			}
			HttpResponse<Void> r = HTTP.send(request.build(), HttpResponse.BodyHandlers.discarding());
			String cr = r.headers().firstValue("Content-Range").orElse("");
			int slash = cr.lastIndexOf('/');
			if (slash > 0) {
				target.length()[0] = Long.parseLong(cr.substring(slash + 1).trim());
			}
		} catch (Exception ignored) {
			// unknown size
		}
		return target.length()[0];
	}
}
