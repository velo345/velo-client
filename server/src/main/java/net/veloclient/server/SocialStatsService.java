package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Views of the owner's TikToks, YouTube Shorts and Instagram Reels for the panel ({@code data/socials.json}). Tracked accounts (a
 * TikTok profile or a YouTube channel's Shorts) pick up their new videos by themselves; single video links can be added
 * too. Every few hours each video gets a snapshot {time, views, likes, comments, shares}, so the panel can show totals,
 * growth and a curve per video.
 *
 * <p>Numbers come from yt-dlp (free, no API keys, works for both) - set {@code VELO_YTDLP} if it isn't on the PATH. With
 * {@code VELO_YOUTUBE_API_KEY} (free, Google Cloud) YouTube numbers come from the official Data API instead.
 *
 * <p>Instagram blocks tools outright, so Reels come from the official Instagram API (with Instagram Login): the owner
 * pastes a long-lived token of their professional account into the panel once ({@code data/instagram.json}, refreshed
 * here every week so it never runs out). Every Reel of that account is then picked up by itself, with views and shares.
 */
final class SocialStatsService {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	/** Snapshots kept per video: hourly-ish polling for ~2 months. */
	private static final int MAX_SAMPLES = 500;
	private static final int MAX_VIDEOS = 400;

	static final class Account {
		String platform;
		String url;
		String name;
		long addedAt;
		long checkedAt;
		String error;
		/** Not an error, just how it is (e.g. "No Shorts yet"). */
		String note;
	}

	static final class Video {
		String id;
		String platform;
		String url;
		String title;
		String uploader;
		String thumbnail;
		long published;
		long addedAt;
		/** {time, views, likes, comments, shares} */
		List<long[]> samples = new ArrayList<>();
		String error;
	}

	static final class Data {
		List<Account> accounts = new ArrayList<>();
		Map<String, Video> videos = new LinkedHashMap<>();
		long lastRun;
	}

	/** The connected Instagram account. The token never leaves the server. */
	static final class Instagram {
		String token;
		String username;
		long refreshedAt;
		String error;
	}

	private static final String IG = "https://graph.instagram.com/v23.0";
	private static final long IG_REFRESH_MILLIS = 7L * 86_400_000L;

	private final Path file;
	private final String ytDlp;
	private final String youtubeKey;
	private final Path instagramFile;
	private Data data;
	private Instagram instagram;
	private volatile boolean running;

	SocialStatsService(Path dataDir, String ytDlp, String youtubeKey) throws IOException {
		this.file = dataDir.resolve("socials.json");
		this.ytDlp = ytDlp == null || ytDlp.isBlank() ? "yt-dlp" : ytDlp;
		this.youtubeKey = youtubeKey == null || youtubeKey.isBlank() ? null : youtubeKey;
		Data loaded = Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class) : null;
		data = loaded == null ? new Data() : loaded;
		this.instagramFile = dataDir.resolve("instagram.json");
		Instagram ig = Files.exists(instagramFile) ? GSON.fromJson(Files.readString(instagramFile, StandardCharsets.UTF_8), Instagram.class) : null;
		instagram = ig == null || ig.token == null ? null : ig;
	}

	private synchronized void saveInstagram() {
		try {
			if (instagram == null) {
				Files.deleteIfExists(instagramFile);
				return;
			}
			Path tmp = instagramFile.resolveSibling(instagramFile.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(instagram), StandardCharsets.UTF_8);
			Files.move(tmp, instagramFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[socials] instagram save failed: " + e.getMessage());
		}
	}

	private synchronized void save() {
		try {
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.out.println("[socials] save failed: " + e.getMessage());
		}
	}

	static String platformOf(String url) {
		String u = url.toLowerCase(java.util.Locale.ROOT);
		if (u.contains("tiktok.com")) {
			return "tiktok";
		}
		if (u.contains("youtube.com") || u.contains("youtu.be")) {
			return "youtube";
		}
		if (u.contains("instagram.com")) {
			return "instagram";
		}
		return null;
	}

	/** A profile / channel (not a single video). */
	static boolean isAccountUrl(String url) {
		String u = url.toLowerCase(java.util.Locale.ROOT);
		if (u.contains("tiktok.com")) {
			return u.matches(".*tiktok\\.com/@[^/?]+/?(\\?.*)?$");
		}
		if (u.contains("instagram.com")) {
			return instagramShortcode(url) == null && u.matches(".*instagram\\.com/[a-z0-9._]+/?(reels/?)?(\\?.*)?$");
		}
		return u.contains("/@") && !u.contains("/shorts/") && !u.contains("watch?v=") || u.contains("/channel/") || u.contains("/c/");
	}

	// ---- owner actions ----

	synchronized Map<String, Object> add(String url) throws ApiError {
		url = url == null ? "" : url.strip();
		String platform = platformOf(url);
		if (platform == null || !url.startsWith("http")) {
			throw new ApiError(400, "Paste a TikTok, YouTube or Instagram link (a video, a profile or a channel)");
		}
		if (isAccountUrl(url)) {
			String accountUrl = platform.equals("youtube") && !url.contains("/shorts") ? url.replaceAll("/+$", "") + "/shorts"
					: platform.equals("instagram") ? "https://www.instagram.com/" + url.replaceAll("(?i).*instagram\\.com/([A-Za-z0-9._]+).*", "$1") + "/"
					: url;
			for (Account a : data.accounts) {
				if (a.url.equalsIgnoreCase(accountUrl)) {
					throw new ApiError(409, "Already tracked");
				}
			}
			Account a = new Account();
			a.platform = platform;
			a.url = accountUrl;
			a.name = platform.equals("instagram") ? "@" + accountUrl.replaceAll(".*instagram\\.com/([^/]+)/", "$1")
					: accountUrl.replaceAll(".*/(@[^/?]+).*", "$1");
			a.addedAt = System.currentTimeMillis();
			data.accounts.add(a);
		} else {
			Video v = new Video();
			String code = platform.equals("instagram") ? instagramShortcode(url) : null;
			v.id = platform + ":" + (code != null ? code : url);
			v.platform = platform;
			v.url = url;
			v.addedAt = System.currentTimeMillis();
			data.videos.putIfAbsent(v.id, v);
		}
		save();
		refreshSoon();
		return summary();
	}

	/** Connects the owner's Instagram professional account with a long-lived token (or disconnects it with a blank one). */
	Map<String, Object> connectInstagram(String token) throws ApiError {
		token = token == null ? "" : token.strip();
		if (token.isEmpty()) {
			synchronized (this) {
				instagram = null;
				saveInstagram();
				return summary();
			}
		}
		JsonObject me;
		try {
			me = igGet(IG + "/me?fields=user_id,username", token);
		} catch (Exception e) {
			throw new ApiError(400, "Instagram didn't accept that token: " + shortError(e));
		}
		synchronized (this) {
			Instagram ig = new Instagram();
			ig.token = token;
			ig.username = str(me, "username", null);
			ig.refreshedAt = 0; // swap it for a fresh 60-day one on the next run
			instagram = ig;
			saveInstagram();
			String accountUrl = "https://www.instagram.com/" + ig.username + "/";
			if (ig.username != null && data.accounts.stream().noneMatch(a -> a.url.equalsIgnoreCase(accountUrl))) {
				Account a = new Account();
				a.platform = "instagram";
				a.url = accountUrl;
				a.name = "@" + ig.username;
				a.addedAt = System.currentTimeMillis();
				data.accounts.add(a);
				save();
			}
		}
		refreshSoon();
		return summary();
	}

	synchronized Map<String, Object> remove(String key) {
		data.accounts.removeIf(a -> a.url.equals(key));
		data.videos.remove(key);
		save();
		return summary();
	}

	void refreshSoon() {
		Thread t = new Thread(this::poll, "velo-socials-refresh");
		t.setDaemon(true);
		t.start();
	}

	// ---- polling ----

	/** Finds new videos of every tracked account, then snapshots every video. Runs every few hours and after an add. */
	void poll() {
		if (running) {
			return;
		}
		running = true;
		try {
			List<Account> accounts;
			synchronized (this) {
				accounts = new ArrayList<>(data.accounts);
			}
			for (Account a : accounts) {
				if (!a.platform.equals("instagram")) {
					discover(a);
				}
			}
			instagramPoll(accounts);
			List<Video> videos;
			synchronized (this) {
				videos = new ArrayList<>(data.videos.values());
			}
			if (youtubeKey != null) {
				youtubeApi(videos.stream().filter(v -> v.platform.equals("youtube")).toList());
			}
			for (Video v : videos) {
				if (v.platform.equals("instagram")) {
					continue; // measured through the Instagram API above
				}
				if (youtubeKey != null && v.platform.equals("youtube") && v.error == null && !v.samples.isEmpty()
						&& System.currentTimeMillis() - v.samples.get(v.samples.size() - 1)[0] < 3_600_000L) {
					continue; // just measured through the API
				}
				snapshot(v);
			}
			synchronized (this) {
				data.lastRun = System.currentTimeMillis();
			}
			save();
		} finally {
			running = false;
		}
	}

	private void discover(Account a) {
		try {
			JsonObject list = runJson(List.of("--flat-playlist", "-J", "--playlist-end", "60", a.url));
			int found = 0;
			for (JsonElement e : list.has("entries") && list.get("entries").isJsonArray() ? list.getAsJsonArray("entries") : new JsonArray()) {
				JsonObject entry = e.getAsJsonObject();
				String url = str(entry, "url", str(entry, "webpage_url", null));
				if (url == null) {
					continue;
				}
				if (a.platform.equals("youtube") && !url.startsWith("http")) {
					url = "https://www.youtube.com/shorts/" + url;
				}
				String id = a.platform + ":" + str(entry, "id", url);
				synchronized (this) {
					if (!data.videos.containsKey(id) && data.videos.size() < MAX_VIDEOS) {
						Video v = new Video();
						v.id = id;
						v.platform = a.platform;
						v.url = url;
						v.title = str(entry, "title", null);
						v.addedAt = System.currentTimeMillis();
						data.videos.put(id, v);
						found++;
					}
				}
			}
			synchronized (this) {
				if (list.has("uploader") || list.has("channel")) {
					a.name = str(list, "uploader", str(list, "channel", a.name));
				}
				a.checkedAt = System.currentTimeMillis();
				a.error = null;
				a.note = list.has("entries") && list.getAsJsonArray("entries").isEmpty() ? "No Shorts yet" : null;
			}
			if (found > 0) {
				System.out.println("[socials] " + found + " new video(s) on " + a.url);
			}
		} catch (Exception e) {
			synchronized (this) {
				a.checkedAt = System.currentTimeMillis();
				String message = String.valueOf(e.getMessage());
				if (message.contains("does not have a shorts tab") || message.contains("does not have a videos tab")) {
					// A channel without Shorts yet: nothing wrong, they're picked up once there are some.
					a.error = null;
					a.note = "No Shorts yet";
				} else {
					// TikTok only lists a profile's videos to signed-in browsers: single video links still work.
					a.error = a.platform.equals("tiktok") ? "TikTok doesn't list profiles to tools right now - add your TikToks by video link"
							: shortError(e);
				}
			}
		}
	}

	private void snapshot(Video v) {
		try {
			JsonObject j = runJson(List.of("-j", "--skip-download", "--no-playlist", v.url));
			synchronized (this) {
				v.title = str(j, "title", v.title);
				v.uploader = str(j, "uploader", str(j, "channel", v.uploader));
				v.thumbnail = str(j, "thumbnail", v.thumbnail);
				long ts = j.has("timestamp") && !j.get("timestamp").isJsonNull() ? j.get("timestamp").getAsLong() * 1000 : 0;
				if (ts > 0) {
					v.published = ts;
				}
				add(v, num(j, "view_count"), num(j, "like_count"), num(j, "comment_count"), num(j, "repost_count"));
				v.error = null;
			}
		} catch (Exception e) {
			synchronized (this) {
				v.error = shortError(e);
			}
		}
	}

	// ---- Instagram (official API) ----

	/** Lists the connected account's media, picks up new Reels for a tracked profile and measures every tracked Reel. */
	private void instagramPoll(List<Account> accounts) {
		Instagram ig;
		List<Video> tracked;
		synchronized (this) {
			ig = instagram;
			tracked = data.videos.values().stream().filter(v -> v.platform.equals("instagram")).toList();
		}
		List<Account> igAccounts = accounts.stream().filter(a -> a.platform.equals("instagram")).toList();
		if (igAccounts.isEmpty() && tracked.isEmpty()) {
			return;
		}
		if (ig == null) {
			synchronized (this) {
				String why = "Instagram blocks tools - connect your account with a token below";
				igAccounts.forEach(a -> { a.error = why; a.checkedAt = System.currentTimeMillis(); });
				tracked.forEach(v -> v.error = why);
			}
			return;
		}
		try {
			refreshInstagramToken(ig);
			Map<String, JsonObject> media = new LinkedHashMap<>();
			String next = IG + "/me/media?fields=id,caption,media_type,media_product_type,permalink,thumbnail_url,timestamp,like_count,comments_count&limit=50";
			for (int page = 0; page < 4 && next != null; page++) {
				JsonObject list = igGet(next, ig.token);
				for (JsonElement e : list.getAsJsonArray("data")) {
					JsonObject m = e.getAsJsonObject();
					String code = instagramShortcode(str(m, "permalink", ""));
					if (code != null) {
						media.put(code, m);
					}
				}
				JsonObject paging = list.has("paging") ? list.getAsJsonObject("paging") : null;
				next = paging != null ? str(paging, "next", null) : null;
			}
			int found = 0;
			synchronized (this) {
				boolean ownTracked = igAccounts.stream().anyMatch(a -> a.url.equalsIgnoreCase("https://www.instagram.com/" + ig.username + "/"));
				for (Map.Entry<String, JsonObject> e : media.entrySet()) {
					JsonObject m = e.getValue();
					boolean reel = "REELS".equals(str(m, "media_product_type", "")) || "VIDEO".equals(str(m, "media_type", ""));
					String id = "instagram:" + e.getKey();
					if (ownTracked && reel && !data.videos.containsKey(id) && data.videos.size() < MAX_VIDEOS) {
						Video v = new Video();
						v.id = id;
						v.platform = "instagram";
						v.url = str(m, "permalink", null);
						v.addedAt = System.currentTimeMillis();
						data.videos.put(id, v);
						found++;
					}
				}
				for (Account a : igAccounts) {
					boolean own = a.url.equalsIgnoreCase("https://www.instagram.com/" + ig.username + "/");
					a.checkedAt = System.currentTimeMillis();
					a.error = own ? null : "Only the connected account (@" + ig.username + ") can be read";
					a.note = own && media.isEmpty() ? "No Reels yet" : null;
				}
				tracked = data.videos.values().stream().filter(v -> v.platform.equals("instagram")).toList();
				ig.error = null;
			}
			if (found > 0) {
				System.out.println("[socials] " + found + " new Reel(s) on @" + ig.username);
			}
			for (Video v : tracked) {
				JsonObject m = media.get(v.id.substring("instagram:".length()));
				if (m == null) {
					synchronized (this) {
						v.error = "Not a post of @" + ig.username;
					}
					continue;
				}
				long views = 0, shares = 0;
				try {
					JsonObject insights = igGet(IG + "/" + str(m, "id", "") + "/insights?metric=views,shares", ig.token);
					for (JsonElement e : insights.getAsJsonArray("data")) {
						JsonObject metric = e.getAsJsonObject();
						long value = metric.has("values") ? num(metric.getAsJsonArray("values").get(0).getAsJsonObject(), "value")
								: metric.has("total_value") ? num(metric.getAsJsonObject("total_value"), "value") : 0;
						if ("views".equals(str(metric, "name", ""))) {
							views = value;
						} else if ("shares".equals(str(metric, "name", ""))) {
							shares = value;
						}
					}
				} catch (Exception e) {
					synchronized (this) {
						v.error = "Insights: " + shortError(e);
					}
				}
				synchronized (this) {
					String caption = str(m, "caption", null);
					v.title = caption == null ? "Reel" : caption.lines().findFirst().orElse("Reel");
					v.uploader = ig.username;
					v.thumbnail = str(m, "thumbnail_url", v.thumbnail);
					String ts = str(m, "timestamp", null);
					if (ts != null) {
						v.published = java.time.OffsetDateTime.parse(ts, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant().toEpochMilli();
					}
					add(v, views, num(m, "like_count"), num(m, "comments_count"), shares);
					if (v.error != null && !v.error.startsWith("Insights")) {
						v.error = null;
					}
				}
			}
		} catch (Exception e) {
			synchronized (this) {
				ig.error = shortError(e);
				igAccounts.forEach(a -> { a.error = "Instagram: " + shortError(e); a.checkedAt = System.currentTimeMillis(); });
			}
		}
		saveInstagram();
	}

	/** Long-lived tokens last 60 days; swapping one for a fresh one each week keeps it going forever. */
	private void refreshInstagramToken(Instagram ig) {
		if (System.currentTimeMillis() - ig.refreshedAt < IG_REFRESH_MILLIS) {
			return;
		}
		try {
			JsonObject r = igGet("https://graph.instagram.com/refresh_access_token?grant_type=ig_refresh_token", ig.token);
			String token = str(r, "access_token", null);
			if (token != null) {
				synchronized (this) {
					ig.token = token;
					ig.refreshedAt = System.currentTimeMillis();
				}
				saveInstagram();
			}
		} catch (Exception e) {
			System.out.println("[socials] Instagram token refresh: " + shortError(e));
		}
	}

	private static JsonObject igGet(String url, String token) throws Exception {
		String full = url.contains("access_token=") ? url : url + (url.contains("?") ? "&" : "?") + "access_token=" + java.net.URLEncoder.encode(token, StandardCharsets.UTF_8);
		HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(full)).timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		if (response.statusCode() != 200 || json.has("error")) {
			JsonObject err = json.has("error") && json.get("error").isJsonObject() ? json.getAsJsonObject("error") : null;
			throw new IOException(err != null ? str(err, "message", "HTTP " + response.statusCode()) : "HTTP " + response.statusCode());
		}
		return json;
	}

	/** The post code of an instagram.com/reel/CODE/ or /p/CODE/ link, or null. */
	static String instagramShortcode(String url) {
		var m = java.util.regex.Pattern.compile("instagram\\.com/(?:[A-Za-z0-9._]+/)?(?:reels?|p|tv)/([A-Za-z0-9_-]+)").matcher(url);
		return m.find() ? m.group(1) : null;
	}

	private static void add(Video v, long views, long likes, long comments, long shares) {
		v.samples.add(new long[] {System.currentTimeMillis(), views, likes, comments, shares});
		while (v.samples.size() > MAX_SAMPLES) {
			v.samples.remove(0);
		}
	}

	/** Official YouTube Data API v3 (50 videos per request), when a key is set. */
	private void youtubeApi(List<Video> videos) {
		List<Video> batch = new ArrayList<>();
		for (Video v : videos) {
			String id = youtubeId(v.url);
			if (id != null) {
				batch.add(v);
			}
			if (batch.size() == 50 || v == videos.get(videos.size() - 1)) {
				youtubeBatch(batch);
				batch.clear();
			}
		}
	}

	private void youtubeBatch(List<Video> batch) {
		if (batch.isEmpty()) {
			return;
		}
		try {
			String ids = String.join(",", batch.stream().map(v -> youtubeId(v.url)).toList());
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/youtube/v3/videos?part=snippet,statistics&id="
					+ ids + "&key=" + youtubeKey)).timeout(Duration.ofSeconds(20)).build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				throw new IOException("YouTube API HTTP " + response.statusCode());
			}
			JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
			for (JsonElement e : json.getAsJsonArray("items")) {
				JsonObject item = e.getAsJsonObject();
				String id = item.get("id").getAsString();
				JsonObject st = item.getAsJsonObject("statistics");
				JsonObject sn = item.getAsJsonObject("snippet");
				synchronized (this) {
					for (Video v : batch) {
						if (id.equals(youtubeId(v.url))) {
							v.title = str(sn, "title", v.title);
							v.uploader = str(sn, "channelTitle", v.uploader);
							v.published = java.time.Instant.parse(sn.get("publishedAt").getAsString()).toEpochMilli();
							add(v, num(st, "viewCount"), num(st, "likeCount"), num(st, "commentCount"), 0);
							v.error = null;
						}
					}
				}
			}
		} catch (Exception e) {
			System.out.println("[socials] YouTube API: " + shortError(e));
		}
	}

	static String youtubeId(String url) {
		var m = java.util.regex.Pattern.compile("(?:shorts/|v=|youtu\\.be/)([A-Za-z0-9_-]{11})").matcher(url);
		return m.find() ? m.group(1) : null;
	}

	private JsonObject runJson(List<String> args) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add(ytDlp);
		cmd.add("--quiet");
		cmd.add("--no-warnings");
		cmd.addAll(args);
		Path err = Files.createTempFile("velo-ytdlp", ".err");
		Process p = new ProcessBuilder(cmd).redirectError(err.toFile()).start();
		byte[] out = p.getInputStream().readAllBytes();
		if (!p.waitFor(120, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new IOException("yt-dlp timed out");
		}
		String text = new String(out, StandardCharsets.UTF_8).strip();
		String problem = Files.readString(err, StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("ERROR")).findFirst()
				.map(l -> l.replaceFirst("^ERROR: (\\[[^\\]]+\\] )?([^:]+: )?", "")).orElse(null);
		Files.deleteIfExists(err);
		if (p.exitValue() != 0 || text.isEmpty()) {
			throw new IOException(problem != null ? problem : "yt-dlp couldn't read it (exit " + p.exitValue() + ")");
		}
		String[] lines = text.split("\n");
		return JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
	}

	private static long num(JsonObject o, String key) {
		try {
			return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0;
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private static String shortError(Exception e) {
		String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		return m.length() > 160 ? m.substring(0, 160) : m;
	}

	// ---- panel view ----

	/** Totals, per-platform numbers, a daily total-views curve and every video with its latest numbers and 24 h growth. */
	synchronized Map<String, Object> summary() {
		long now = System.currentTimeMillis();
		long day = 86_400_000L;
		long[] total = new long[5];
		long[] total24 = new long[5];
		Map<String, long[]> byPlatform = new LinkedHashMap<>();
		List<Map<String, Object>> videos = new ArrayList<>();
		// daily total views: for each day, the sum of every video's last snapshot up to that day's end
		int days = 30;
		long[] curve = new long[days];
		for (Video v : data.videos.values()) {
			long[] last = v.samples.isEmpty() ? null : v.samples.get(v.samples.size() - 1);
			long[] dayAgo = sampleAt(v, now - day);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", v.id);
			row.put("platform", v.platform);
			row.put("url", v.url);
			row.put("title", v.title);
			row.put("uploader", v.uploader);
			row.put("thumbnail", v.thumbnail);
			row.put("published", v.published);
			row.put("error", v.error);
			if (last != null) {
				row.put("views", last[1]);
				row.put("likes", last[2]);
				row.put("comments", last[3]);
				row.put("shares", last[4]);
				row.put("views24h", dayAgo == null ? last[1] : last[1] - dayAgo[1]);
				row.put("measured", last[0]);
				row.put("curve", v.samples.stream().map(s -> new long[] {s[0], s[1]}).toList());
				long[] p = byPlatform.computeIfAbsent(v.platform, k -> new long[5]);
				for (int i = 1; i < 5; i++) {
					total[i] += last[i];
					p[i] += last[i];
					total24[i] += dayAgo == null ? last[i] : last[i] - dayAgo[i];
				}
				p[0]++;
				total[0]++;
				for (int d = 0; d < days; d++) {
					long[] at = sampleAt(v, now - (days - 1 - d) * day);
					curve[d] += at == null ? 0 : at[1];
				}
			}
			videos.add(row);
		}
		videos.sort((a, b) -> Long.compare((long) b.getOrDefault("views", 0L), (long) a.getOrDefault("views", 0L)));
		List<long[]> totalCurve = new ArrayList<>();
		for (int d = 0; d < days; d++) {
			totalCurve.add(new long[] {now - (days - 1 - d) * day, curve[d]});
		}
		Map<String, Object> platforms = new LinkedHashMap<>();
		byPlatform.forEach((k, p) -> platforms.put(k, Map.of("videos", p[0], "views", p[1], "likes", p[2], "comments", p[3], "shares", p[4])));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("totals", Map.of("videos", total[0], "views", total[1], "likes", total[2], "comments", total[3], "shares", total[4],
				"views24h", total24[1], "likes24h", total24[2]));
		out.put("platforms", platforms);
		out.put("curve", totalCurve);
		out.put("accounts", data.accounts);
		out.put("videos", videos);
		out.put("lastRun", data.lastRun);
		out.put("running", running);
		out.put("youtubeApi", youtubeKey != null);
		Map<String, Object> ig = new LinkedHashMap<>();
		ig.put("connected", instagram != null);
		if (instagram != null) {
			ig.put("username", instagram.username);
			ig.put("error", instagram.error);
		}
		out.put("instagram", ig);
		return out;
	}

	/** The last snapshot taken at or before {@code time}, or null. */
	private static long[] sampleAt(Video v, long time) {
		long[] best = null;
		for (long[] s : v.samples) {
			if (s[0] <= time) {
				best = s;
			} else {
				break;
			}
		}
		return best;
	}
}
