package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The website's Creators page (velo-client.com/creators): who's listed lives in {@code <data>/creators.json} (owner
 * edits it through /v1/admin/creators), and everything else updates itself - channel name, subscribers, avatar, banner
 * and the latest videos come from yt-dlp every few hours. Pictures are kept on disk ({@code <data>/creator-art}) and
 * served from our own origin, so the page needs no third-party images.
 */
final class CreatorService {

	/** What the owner sets for a creator: just the channel (and optionally an accent colour and the order) - everything shown updates itself. */
	record Creator(String id, String channel, String color, Integer order) {
	}

	record Video(String id, String title, long views, double duration) {
	}

	/** What yt-dlp found last time. */
	record Live(String name, long subscribers, boolean hasAvatar, boolean hasBanner, List<Video> videos, long checkedAt, String error) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private final Path file;
	private final Path artDir;
	private final String ytDlp;
	private volatile List<Creator> creators = List.of();
	private final Map<String, Live> live = new ConcurrentHashMap<>();

	CreatorService(Path dataDir, String ytDlp) {
		this.file = dataDir.resolve("creators.json");
		this.artDir = dataDir.resolve("creator-art");
		this.ytDlp = ytDlp;
		load();
	}

	private void load() {
		try {
			if (Files.isRegularFile(file)) {
				JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				List<Creator> list = GSON.fromJson(root.get("creators"), new TypeToken<List<Creator>>() { }.getType());
				creators = list == null ? List.of() : List.copyOf(list);
				if (root.has("live")) {
					Map<String, Live> saved = GSON.fromJson(root.get("live"), new TypeToken<Map<String, Live>>() { }.getType());
					if (saved != null) {
						live.putAll(saved);
					}
				}
			} else {
				// the first creator, so the page has someone right away
				creators = List.of(new Creator("nurhyper", "https://www.youtube.com/@hypermc26", "#2f7bff", 1));
				save();
			}
		} catch (Exception e) {
			System.err.println("[velo] creators.json unreadable: " + e);
		}
	}

	private synchronized void save() {
		try {
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.add("creators", GSON.toJsonTree(creators));
			root.add("live", GSON.toJsonTree(live));
			Path tmp = file.resolveSibling("creators.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.err.println("[velo] couldn't save creators.json: " + e);
		}
	}

	List<Creator> creators() {
		return creators;
	}

	/** Replaces the list (owner). Creators that are new get looked up right away. */
	void setCreators(List<Creator> list) throws ApiError {
		List<Creator> clean = new ArrayList<>();
		for (Creator c : list == null ? List.<Creator>of() : list) {
			if (c == null || c.id() == null || !c.id().matches("[a-z0-9_-]{2,40}") || c.channel() == null || !c.channel().startsWith("https://")) {
				throw new ApiError(400, "Every creator needs an id (a-z, 0-9, - or _) and a https channel link");
			}
			clean.add(c);
		}
		creators = List.copyOf(clean);
		live.keySet().retainAll(clean.stream().map(Creator::id).toList());
		save();
		Thread.ofVirtual().start(this::refreshMissing);
	}

	/** The public list for the website. */
	List<Map<String, Object>> views() {
		List<Creator> list = new ArrayList<>(creators);
		list.sort((a, b) -> Integer.compare(a.order() == null ? 999 : a.order(), b.order() == null ? 999 : b.order()));
		List<Map<String, Object>> out = new ArrayList<>();
		for (Creator c : list) {
			Live l = live.get(c.id());
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", c.id());
			m.put("channel", c.channel());
			m.put("name", l != null && l.name() != null ? l.name() : c.id());
			m.put("color", c.color() != null && c.color().matches("#[0-9a-fA-F]{6}") ? c.color() : "#ff2d3c");
			long stamp = l == null ? 0 : l.checkedAt() / 1000;
			m.put("subscribers", l == null ? 0 : l.subscribers());
			m.put("avatar", l != null && l.hasAvatar() ? "/v1/creators/art/" + c.id() + "/avatar?v=" + stamp : null);
			m.put("banner", l != null && l.hasBanner() ? "/v1/creators/art/" + c.id() + "/banner?v=" + stamp : null);
			List<Map<String, Object>> videos = new ArrayList<>();
			if (l != null && l.videos() != null) {
				for (Video v : l.videos()) {
					videos.add(Map.of("id", v.id(), "title", v.title(), "url", "https://www.youtube.com/watch?v=" + v.id(),
							"thumb", "/v1/creators/art/" + c.id() + "/v-" + v.id(), "views", v.views(), "duration", v.duration()));
				}
			}
			m.put("videos", videos);
			m.put("updatedAt", l == null ? 0 : l.checkedAt());
			out.add(m);
		}
		return out;
	}

	/** A stored picture: avatar, banner or v-<videoId>. */
	byte[] art(String creatorId, String name) {
		if (creatorId == null || name == null || !creatorId.matches("[a-z0-9_-]{2,40}") || !name.matches("avatar|banner|v-[A-Za-z0-9_-]{11}")) {
			return null;
		}
		try {
			Path p = artDir.resolve(creatorId).resolve(name + ".jpg");
			return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
		} catch (IOException e) {
			return null;
		}
	}

	void refreshMissing() {
		for (Creator c : creators) {
			if (!live.containsKey(c.id())) {
				refresh(c);
			}
		}
	}

	/** Every few hours: all creators. */
	void refreshAll() {
		for (Creator c : creators) {
			refresh(c);
		}
	}

	private void refresh(Creator c) {
		Live before = live.get(c.id());
		try {
			String base = c.channel().replaceAll("/+$", "").replaceAll("/(videos|featured|shorts|streams)$", "");
			JsonObject o = runJson(List.of("--flat-playlist", "-J", "--playlist-end", "4", base + "/videos"));
			Path dir = artDir.resolve(c.id());
			Files.createDirectories(dir);
			String avatarUrl = null;
			String bannerUrl = null;
			if (o.has("thumbnails") && o.get("thumbnails").isJsonArray()) {
				for (JsonElement e : o.getAsJsonArray("thumbnails")) {
					JsonObject t = e.getAsJsonObject();
					String id = str(t, "id", "");
					if (id.equals("avatar_uncropped")) {
						avatarUrl = str(t, "url", null);
					} else if (id.equals("banner_uncropped")) {
						bannerUrl = str(t, "url", null);
					}
				}
			}
			boolean hasAvatar = avatarUrl != null && download(avatarUrl + (avatarUrl.contains("=") ? "" : "=s400"), dir.resolve("avatar.jpg"));
			boolean hasBanner = bannerUrl != null && download(bannerUrl + (bannerUrl.contains("=") ? "" : "=w1600"), dir.resolve("banner.jpg"));
			List<Video> videos = new ArrayList<>();
			JsonArray entries = o.has("entries") && o.get("entries").isJsonArray() ? o.getAsJsonArray("entries") : new JsonArray();
			for (JsonElement e : entries) {
				JsonObject v = e.getAsJsonObject();
				String id = str(v, "id", null);
				if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) {
					continue;
				}
				download("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", dir.resolve("v-" + id + ".jpg"));
				videos.add(new Video(id, str(v, "title", "Video"), num(v, "view_count"), num(v, "duration")));
				if (videos.size() >= 4) {
					break;
				}
			}
			// old thumbnails go
			try (var files = Files.list(dir)) {
				List<String> keep = videos.stream().map(v -> "v-" + v.id() + ".jpg").toList();
				for (Path p : files.toList()) {
					String n = p.getFileName().toString();
					if (n.startsWith("v-") && !keep.contains(n)) {
						Files.deleteIfExists(p);
					}
				}
			}
			String name = str(o, "channel", str(o, "uploader", c.id()));
			live.put(c.id(), new Live(name, num(o, "channel_follower_count"), hasAvatar || before != null && before.hasAvatar(),
					hasBanner || before != null && before.hasBanner(), videos.isEmpty() && before != null ? before.videos() : videos,
					System.currentTimeMillis(), null));
		} catch (Exception e) {
			System.err.println("[velo] creator " + c.id() + " refresh failed: " + e.getMessage());
			if (before != null) {
				live.put(c.id(), new Live(before.name(), before.subscribers(), before.hasAvatar(), before.hasBanner(), before.videos(),
						before.checkedAt(), String.valueOf(e.getMessage())));
			}
		}
		save();
	}

	private static boolean download(String url, Path to) {
		try {
			HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
			if (r.statusCode() != 200 || r.body().length < 200 || r.body().length > 8 * 1024 * 1024) {
				return false;
			}
			// pictures are re-encoded as JPEG, so nothing but an image is ever served from here
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(r.body()));
			if (image == null) {
				return false;
			}
			java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(image.getWidth(), image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
			rgb.getGraphics().drawImage(image, 0, 0, null);
			Path tmp = to.resolveSibling(to.getFileName() + ".tmp");
			javax.imageio.ImageIO.write(rgb, "jpg", tmp.toFile());
			Files.move(tmp, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private JsonObject runJson(List<String> args) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add(ytDlp);
		cmd.add("--quiet");
		cmd.add("--no-warnings");
		cmd.addAll(args);
		Path err = Files.createTempFile("velo-creators", ".err");
		Process p = new ProcessBuilder(cmd).redirectError(err.toFile()).start();
		byte[] out = p.getInputStream().readAllBytes();
		if (!p.waitFor(120, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IOException("yt-dlp timed out");
		}
		String problem = Files.readString(err, StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("ERROR")).findFirst().orElse(null);
		Files.deleteIfExists(err);
		String text = new String(out, StandardCharsets.UTF_8).strip();
		if (p.exitValue() != 0 || text.isEmpty()) {
			throw new IOException(problem != null ? problem : "yt-dlp exit " + p.exitValue());
		}
		String[] lines = text.split("\n");
		return JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
	}

	private static long num(JsonObject o, String key) {
		try {
			return o != null && o.has(key) && !o.get(key).isJsonNull() ? Math.round(o.get(key).getAsDouble()) : 0;
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
