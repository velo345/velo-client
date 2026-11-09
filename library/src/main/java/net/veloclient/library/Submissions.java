package net.veloclient.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "Add your server" on velo-client.com, written to the GitHub repo: the owner gets a code, puts it in their MOTD for a
 * moment (proof they run the server), fills in name, logo and links, and the Library opens a pull request on
 * github.com/velo345/server-mappings with the files (LIBRARY_GITHUB_TOKEN: a token with contents + pull request write
 * on that repo). Without a token the entries wait in a queue for the owner.
 */
final class Submissions {

	private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,40}$");
	private static final Pattern ADDRESS = Pattern.compile("^(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+(:[0-9]{1,5})?$");
	private static final Set<String> GAME_TYPES = Set.of("smp", "pvp", "minigames", "skyblock", "survival", "factions", "prison", "creative", "hardcore",
			"anarchy", "roleplay", "modded", "lifesteal", "practice", "bedwars", "events", "other");
	private static final Set<String> REGIONS = Set.of("NA", "EU", "SA", "AS", "OC", "AF");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

	record Challenge(String code, String address, String uuid, long created) {
	}

	static final class Queued {
		String id;
		String by;
		long at;
		JsonObject metadata;
		String logo;
		String banner;
		String status;
		String pr;
	}

	private final Map<String, Challenge> challenges = new HashMap<>();
	private final JsonFile<List<Queued>> queue;
	private final String repo;
	private final String token;
	private final Path deployKey;
	private final Path gitDir;

	Submissions(Path dataDir, String repo, String token, String deployKey) {
		this.repo = repo;
		this.token = token;
		this.deployKey = deployKey == null ? null : Path.of(deployKey);
		this.gitDir = dataDir.resolve("git");
		this.queue = new JsonFile<>(dataDir.resolve("mapping-submissions.json"), new TypeToken<List<Queued>>() {
		}.getType(), ArrayList::new);
	}

	/** Step 1: a code for the MOTD (valid 30 minutes). */
	synchronized Map<String, Object> start(Accounts.User u, String address) {
		String a = clean(address);
		if (a == null || !ADDRESS.matcher(a).matches() || a.startsWith("*.")) {
			throw new Http.Fail(400, "Enter the address players join with, e.g. play.example.net");
		}
		challenges.values().removeIf(c -> System.currentTimeMillis() - c.created() > 30 * 60_000);
		String code = "velo-" + Long.toString(Math.abs(RANDOM.nextLong()), 36).substring(0, 6).toUpperCase(Locale.ROOT);
		challenges.put(u.uuid() + "|" + a, new Challenge(code, a, u.uuid(), System.currentTimeMillis()));
		return Http.map("code", code, "address", a, "expiresInMinutes", 30);
	}

	/** Step 2: is the code in the server's MOTD right now? */
	boolean verify(Accounts.User u, String address, ServerPing ping) throws Exception {
		String a = clean(address);
		Challenge c;
		synchronized (this) {
			c = challenges.get(u.uuid() + "|" + a);
		}
		if (c == null || System.currentTimeMillis() - c.created() > 30 * 60_000) {
			throw new Http.Fail(400, "Get a new code first - the old one expired");
		}
		ServerPing.Result r = ServerPing.fresh(a);
		if (!r.online()) {
			throw new Http.Fail(400, a + " didn't answer the status ping (" + r.error() + ")");
		}
		return Text.toPlain(r.motd()).toLowerCase(Locale.ROOT).contains(c.code().toLowerCase(Locale.ROOT));
	}

	/** Step 3: check the details, then open the pull request (or queue it). */
	Map<String, Object> submit(Accounts.User u, JsonObject body, ServerPing ping, Mappings mappings) throws Exception {
		String address = clean(str(body, "address"));
		if (!verify(u, address, ping)) {
			throw new Http.Fail(400, "The code isn't in your MOTD yet - add it, save, and try again (the server list may need a minute)");
		}
		JsonObject meta = metadata(body, address);
		String id = meta.get("id").getAsString();
		Mappings.Server existing = mappings.get(id);
		if (existing != null) {
			boolean owns = false;
			for (JsonElement e : existing.meta().getAsJsonArray("addresses")) {
				String x = e.getAsString();
				owns |= x.equals(address) || x.startsWith("*.") && address.endsWith(x.substring(1));
			}
			if (!owns) {
				throw new Http.Fail(409, "The id " + id + " belongs to another server - pick another");
			}
		}
		Mappings.Server clash = null;
		for (JsonElement e : meta.getAsJsonArray("addresses")) {
			Mappings.Server s = mappings.lookup(e.getAsString().replace("*.", "x."));
			if (s != null && !s.id().equals(id)) {
				clash = s;
			}
		}
		if (clash != null) {
			throw new Http.Fail(409, "One of those addresses is already mapped to " + clash.meta().get("name").getAsString());
		}
		byte[] logo = image(str(body, "logo"), 512 * 1024, "logo", true);
		byte[] banner = image(str(body, "banner"), 2 * 1024 * 1024, "banner", false);
		if (logo == null || banner == null) {
			throw new Http.Fail(400, "A logo and a banner are both needed - they're what players see in Discord and on the server card");
		}
		synchronized (this) {
			challenges.remove(u.uuid() + "|" + address);
		}
		Queued q = new Queued();
		q.id = id;
		q.by = u.username();
		q.at = System.currentTimeMillis();
		q.metadata = meta;
		q.logo = Base64.getEncoder().encodeToString(logo);
		q.banner = Base64.getEncoder().encodeToString(banner);
		if (token == null && deployKey != null && java.nio.file.Files.isReadable(deployKey)) {
			String branch = pushBranch(meta, logo, banner, u.username(), existing != null);
			q.status = "pr";
			q.pr = "https://github.com/" + repo + "/pulls";
			synchronized (queue.get()) {
				queue.get().add(q);
				queue.changed();
			}
			return Http.map("status", "pr", "pr", "https://github.com/" + repo + "/pulls?q=head%3A" + branch.replace("/", "%2F"),
					"message", "Done - your entry is on its way to GitHub as a pull request and goes live once it's merged.");
		}
		if (token == null) {
			q.status = "queued";
			synchronized (queue.get()) {
				queue.get().add(q);
				queue.changed();
			}
			return Http.map("status", "queued", "message", "Thanks! Your server is in the review queue and shows up after the next sync.");
		}
		String pr = pullRequest(meta, logo, banner, u.username(), existing != null);
		q.status = "pr";
		q.pr = pr;
		synchronized (queue.get()) {
			queue.get().add(q);
			queue.changed();
		}
		return Http.map("status", "pr", "pr", pr, "message", "Done - your entry is a pull request on GitHub and goes live once it's merged.");
	}

	private static JsonObject metadata(JsonObject b, String address) {
		JsonObject m = new JsonObject();
		String id = str(b, "id");
		if (id == null || !ID.matcher(id).matches()) {
			throw new Http.Fail(400, "The id: 2-41 lower-case letters, digits or -");
		}
		m.addProperty("id", id);
		String name = text(str(b, "name"), 40);
		if (name == null || name.length() < 2) {
			throw new Http.Fail(400, "Give your server a name");
		}
		m.addProperty("name", name);
		String desc = text(str(b, "description"), 160);
		if (desc != null && !desc.isBlank()) {
			m.addProperty("description", desc);
		}
		JsonArray addresses = new JsonArray();
		List<String> seen = new ArrayList<>();
		for (String a : (str(b, "addresses") == null ? address : str(b, "addresses") + "," + address).split("[,\\s]+")) {
			String c = clean(a);
			if (c == null || c.isEmpty() || seen.contains(c)) {
				continue;
			}
			if (!ADDRESS.matcher(c).matches()) {
				throw new Http.Fail(400, "Not an address: " + a);
			}
			seen.add(c);
			addresses.add(c);
		}
		if (seen.size() > 30) {
			throw new Http.Fail(400, "At most 30 addresses");
		}
		m.add("addresses", addresses);
		m.addProperty("primaryAddress", address);
		url(b, m, "website", "^https://.{3,200}$");
		url(b, m, "discord", "^https://(discord\\.gg|discord\\.com/invite)/[A-Za-z0-9-]{2,40}$");
		url(b, m, "store", "^https://.{3,200}$");
		String color = str(b, "primaryColor");
		if (color != null && color.matches("^#[0-9a-fA-F]{6}$")) {
			m.addProperty("primaryColor", color);
		}
		JsonArray types = new JsonArray();
		for (String t : (str(b, "gameTypes") == null ? "" : str(b, "gameTypes")).split(",")) {
			if (GAME_TYPES.contains(t.trim())) {
				types.add(t.trim());
			}
		}
		if (!types.isEmpty()) {
			m.add("gameTypes", types);
		}
		JsonArray regions = new JsonArray();
		for (String t : (str(b, "regions") == null ? "" : str(b, "regions")).split(",")) {
			if (REGIONS.contains(t.trim())) {
				regions.add(t.trim());
			}
		}
		if (!regions.isEmpty()) {
			m.add("regions", regions);
		}
		String rec = text(str(b, "version"), 20);
		if (rec != null && rec.matches("^[0-9.]{3,12}$")) {
			JsonObject v = new JsonObject();
			v.addProperty("recommended", rec);
			m.add("minecraftVersions", v);
		}
		String details = text(str(b, "presenceDetails"), 100), state = text(str(b, "presenceState"), 100);
		if (details != null && !details.isBlank() || state != null && !state.isBlank()) {
			JsonObject p = new JsonObject();
			if (details != null && !details.isBlank()) {
				p.addProperty("details", details);
			}
			if (state != null && !state.isBlank()) {
				p.addProperty("state", state);
			}
			p.addProperty("largeImageText", name);
			m.add("presence", p);
		}
		return m;
	}

	/** A PNG (data URL or base64): logo square 128-1024 px, banner wide (16:9 to 5:1, 640-2560 px wide). */
	private static byte[] image(String b64, int maxBytes, String what, boolean square) {
		if (b64 == null || b64.isBlank()) {
			return null;
		}
		byte[] data;
		try {
			data = Base64.getDecoder().decode(b64.replaceFirst("^data:image/png;base64,", ""));
		} catch (IllegalArgumentException e) {
			throw new Http.Fail(400, "The " + what + " isn't a PNG");
		}
		if (data.length > maxBytes) {
			throw new Http.Fail(400, "The " + what + " must be under " + maxBytes / 1024 + " KB");
		}
		BufferedImage img;
		try {
			img = ImageIO.read(new ByteArrayInputStream(data));
		} catch (Exception e) {
			img = null;
		}
		if (img == null) {
			throw new Http.Fail(400, "The " + what + " isn't a PNG");
		}
		if (square && (img.getWidth() != img.getHeight() || img.getWidth() < 128 || img.getWidth() > 1024)) {
			throw new Http.Fail(400, "The logo must be square, 128 to 1024 pixels");
		}
		double ratio = img.getWidth() / (double) img.getHeight();
		if (!square && (ratio < 1.7 || ratio > 5 || img.getWidth() < 640 || img.getWidth() > 2560)) {
			throw new Http.Fail(400, "The banner must be wide (16:9 to 5:1), 640 to 2560 pixels across - e.g. 1920x1080 or 1600x480");
		}
		return data;
	}

	private static void url(JsonObject b, JsonObject m, String key, String pattern) {
		String v = str(b, key);
		if (v == null || v.isBlank()) {
			return;
		}
		if (!v.trim().matches(pattern)) {
			throw new Http.Fail(400, "That " + key + " link doesn't look right");
		}
		m.addProperty(key, v.trim());
	}

	private static String text(String s, int max) {
		if (s == null) {
			return null;
		}
		String t = s.replaceAll("[\\p{Cntrl}]", "").replaceAll("§.", "").strip();
		return t.length() > max ? t.substring(0, max) : t;
	}

	private static String clean(String a) {
		return a == null ? null : a.trim().toLowerCase(Locale.ROOT).replaceFirst(":25565$", "").replaceFirst("\\.$", "");
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	// ---- GitHub through git + a deploy key (write access to this one repo only); the repo's Submissions action opens the PR

	private synchronized String pushBranch(JsonObject meta, byte[] logo, byte[] banner, String by, boolean update) throws Exception {
		String id = meta.get("id").getAsString();
		String branch = "submit/" + id + "-" + Long.toString(System.currentTimeMillis(), 36);
		Path work = java.nio.file.Files.createDirectories(gitDir).resolve("work-" + System.nanoTime());
		java.nio.file.Files.createDirectories(work);
		try {
			git(work, "init", "-q");
			git(work, "remote", "add", "origin", "git@github.com:" + repo + ".git");
			git(work, "fetch", "-q", "--depth", "1", "origin", "main");
			git(work, "checkout", "-q", "-b", branch, "FETCH_HEAD");
			Path folder = java.nio.file.Files.createDirectories(work.resolve("servers").resolve(id));
			java.nio.file.Files.writeString(folder.resolve("metadata.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(meta) + "\n");
			java.nio.file.Files.write(folder.resolve("logo.png"), logo);
			java.nio.file.Files.write(folder.resolve("banner.png"), banner);
			git(work, "add", "-A");
			String title = (update ? "Update " : "Add ") + meta.get("name").getAsString();
			String body = "Submitted on velo-client.com by **" + by + "** - ownership checked: the verification code was in the MOTD of `"
					+ meta.get("primaryAddress").getAsString() + "`.\n\nAddresses: " + meta.getAsJsonArray("addresses");
			git(work, "-c", "user.name=Velo Library", "-c", "user.email=library@velo-client.com", "commit", "-q", "-m", title, "-m", body);
			git(work, "push", "-q", "origin", "HEAD:refs/heads/" + branch);
			return branch;
		} finally {
			try (var walk = java.nio.file.Files.walk(work)) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
		}
	}

	private void git(Path dir, String... args) throws Exception {
		List<String> cmd = new ArrayList<>();
		cmd.add("git");
		cmd.addAll(List.of(args));
		ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
		pb.environment().put("HOME", gitDir.toString());
		pb.environment().put("GIT_TERMINAL_PROMPT", "0");
		pb.environment().put("GIT_SSH_COMMAND", "ssh -F /dev/null -i " + deployKey + " -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="
				+ gitDir.resolve("known_hosts") + " -o ConnectTimeout=15");
		Process p = pb.start();
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new Http.Fail(504, "GitHub took too long - try again");
		}
		if (p.exitValue() != 0) {
			System.out.println("[library] git " + String.join(" ", args) + " failed: " + out.strip());
			throw new Http.Fail(502, "Couldn't reach GitHub right now - try again in a minute");
		}
	}

	// ---- GitHub

	private String pullRequest(JsonObject meta, byte[] logo, byte[] banner, String by, boolean update) throws Exception {
		String id = meta.get("id").getAsString();
		JsonObject ref = gh("GET", "/repos/" + repo + "/git/ref/heads/main", null);
		String sha = ref.getAsJsonObject("object").get("sha").getAsString();
		String branch = (update ? "update-" : "add-") + id + "-" + Long.toString(System.currentTimeMillis(), 36);
		JsonObject newRef = new JsonObject();
		newRef.addProperty("ref", "refs/heads/" + branch);
		newRef.addProperty("sha", sha);
		gh("POST", "/repos/" + repo + "/git/refs", newRef);
		putFile("servers/" + id + "/metadata.json", new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(meta).getBytes(StandardCharsets.UTF_8), branch,
				(update ? "Update " : "Add ") + meta.get("name").getAsString());
		putFile("servers/" + id + "/logo.png", logo, branch, "Logo for " + meta.get("name").getAsString());
		putFile("servers/" + id + "/banner.png", banner, branch, "Banner for " + meta.get("name").getAsString());
		JsonObject pr = new JsonObject();
		pr.addProperty("title", (update ? "Update " : "Add ") + meta.get("name").getAsString());
		pr.addProperty("head", branch);
		pr.addProperty("base", "main");
		pr.addProperty("body", "Submitted on velo-client.com by **" + by + "** - ownership checked: the verification code was in the MOTD of `"
				+ meta.get("primaryAddress").getAsString() + "`.\n\nAddresses: " + meta.getAsJsonArray("addresses"));
		return gh("POST", "/repos/" + repo + "/pulls", pr).get("html_url").getAsString();
	}

	private void putFile(String path, byte[] content, String branch, String message) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("message", message);
		body.addProperty("content", Base64.getEncoder().encodeToString(content));
		body.addProperty("branch", branch);
		try {
			JsonObject existing = gh("GET", "/repos/" + repo + "/contents/" + path + "?ref=" + branch, null);
			body.addProperty("sha", existing.get("sha").getAsString());
		} catch (Http.Fail notThere) {
			// new file
		}
		gh("PUT", "/repos/" + repo + "/contents/" + path, body);
	}

	private JsonObject gh(String method, String path, JsonObject body) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://api.github.com" + path)).timeout(Duration.ofSeconds(20))
				.header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json").header("User-Agent", Net.UA)
				.header("X-GitHub-Api-Version", "2022-11-28");
		b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()));
		HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() >= 300) {
			throw new Http.Fail(r.statusCode() == 404 ? 404 : 502, "GitHub said " + r.statusCode());
		}
		return r.body().isBlank() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
	}

	/** Owner view of everything submitted (queued ones included). */
	List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (queue.get()) {
			for (Queued q : queue.get()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", q.id);
				m.put("by", q.by);
				m.put("at", q.at);
				m.put("status", q.status);
				m.put("pr", q.pr);
				m.put("metadata", q.metadata);
				m.put("hasLogo", q.logo != null);
				out.add(m);
			}
		}
		return out;
	}

	/** Queued entries with their logos, for tools/server/mappings-sync.sh (owner opens the PRs from their PC). */
	List<Queued> queued() {
		synchronized (queue.get()) {
			return queue.get().stream().filter(q -> "queued".equals(q.status)).toList();
		}
	}

	void markSynced(String id) {
		synchronized (queue.get()) {
			queue.get().forEach(q -> {
				if (q.id.equals(id) && "queued".equals(q.status)) {
					q.status = "synced";
				}
			});
			queue.changed();
		}
	}

	void save() {
		queue.saveIfDirty();
	}
}
