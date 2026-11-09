package net.veloclient.library;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.imageio.ImageIO;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The Velo Library API (velo-client.com/library, /v2/...): player lookups and renders, item icons, server status,
 * banners, MOTDs, spark analysis, plugin updates, pack hosting, seed maps, community presets and API keys.
 *
 * <p>Runs as its own process next to velo-server and must never hurt it: see {@link Guard} (kill switch, limits, load
 * shedding), {@link Net} (outbound budgets) and {@link Accounts} (the only, throttled, calls to velo-server).
 */
public final class LibraryApp {

	/** What a route answers; GET answers with a positive cache time are reused for everyone. */
	record Out(int status, String type, byte[] body, int cacheSeconds) {
		static Out json(Object o, int cache) {
			return new Out(200, "application/json; charset=utf-8", Http.GSON.toJson(o).getBytes(StandardCharsets.UTF_8), cache);
		}

		static Out png(byte[] b, int cache) {
			return new Out(200, "image/png", b, cache);
		}
	}

	private final Path data;
	private final Guard guard;
	private final Cache cache = new Cache(96L << 20);
	private final Accounts accounts;
	private final Players players;
	private final Assets assets;
	private final ServerPing ping = new ServerPing();
	private final Spark spark = new Spark();
	private final Plugins plugins = new Plugins();
	private final Packs packs;
	private final Presets presets;
	private final Seeds seeds;
	private final Mappings mappings = new Mappings(env("LIBRARY_MAPPINGS_REPO", "velo345/server-mappings"));
	private final Submissions submissions;
	private final Designs designs;
	private final GeneratedSchems generated;
	private final String adminToken;
	private final Font inter;
	private final Font interBold;
	private volatile Banner banner;

	private LibraryApp(Path data) throws IOException {
		this.data = data;
		guard = new Guard(data);
		Set<String> owners = new HashSet<>();
		for (String o : env("LIBRARY_OWNERS", "").split(",")) {
			if (!o.isBlank()) {
				owners.add(o.trim().replace("-", "").toLowerCase(java.util.Locale.ROOT));
			}
		}
		accounts = new Accounts(data, env("LIBRARY_VELO_SERVER", "http://127.0.0.1:8787"), owners);
		players = new Players(data);
		assets = new Assets(data);
		packs = new Packs(data, env("LIBRARY_PUBLIC", "https://velo-client.com"));
		presets = new Presets(data);
		seeds = new Seeds(data);
		adminToken = env("LIBRARY_ADMIN_TOKEN", null);
		designs = new Designs(data);
		generated = new GeneratedSchems(data);
		submissions = new Submissions(data, env("LIBRARY_MAPPINGS_REPO", "velo345/server-mappings"), env("LIBRARY_GITHUB_TOKEN", null),
				env("LIBRARY_GIT_DEPLOY_KEY", null));
		inter = font("/fonts/Inter-SemiBold.ttf");
		interBold = font("/fonts/Inter-ExtraBold.ttf");
	}

	private static Font font(String res) {
		try (InputStream in = LibraryApp.class.getResourceAsStream(res)) {
			return Font.createFont(Font.TRUETYPE_FONT, in);
		} catch (Exception e) {
			return new Font(Font.SANS_SERIF, Font.BOLD, 12);
		}
	}

	private static String env(String k, String fallback) {
		String v = System.getenv(k);
		return v == null || v.isBlank() ? fallback : v.trim();
	}

	public static void main(String[] args) throws IOException {
		System.setProperty("java.awt.headless", "true");
		Path data = Files.createDirectories(Path.of(env("LIBRARY_DATA_DIR", "library-data")));
		LibraryApp app = new LibraryApp(data);
		int port = Integer.parseInt(env("LIBRARY_PORT", "8791"));
		HttpServer server = HttpServer.create(new InetSocketAddress(env("LIBRARY_BIND", "127.0.0.1"), port), 64);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.createContext("/v2/", app::handle);
		var timers = Executors.newScheduledThreadPool(2, r -> {
			Thread t = new Thread(r, "library-timer");
			t.setDaemon(true);
			return t;
		});
		timers.execute(app::refreshAssets);
		timers.scheduleAtFixedRate(app::refreshAssets, 24, 24, TimeUnit.HOURS);
		timers.scheduleAtFixedRate(app::housekeeping, 30, 30, TimeUnit.SECONDS);
		timers.scheduleAtFixedRate(() -> app.ping.sample(app.guard), 5, 5, TimeUnit.MINUTES);
		timers.scheduleAtFixedRate(app.mappings::refresh, 0, 30, TimeUnit.MINUTES);

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			app.save();
			app.players.saveNow();
		}, "library-save"));
		server.start();
		System.out.println("Velo Library listening on " + port + " (data " + data.toAbsolutePath() + ", seed map " + (app.seeds.available() ? "on" : "off") + ")");
	}

	private void refreshAssets() {
		assets.update();
		if (assets.ready()) {
			players.setDefaults(assets.defaultSkin(false), assets.defaultSkin(true));
			banner = new Banner(assets, new Text.McFont(assets.texture("font/ascii"), inter), inter, interBold);
			warmIcons();
			System.out.println("[library] assets " + assets.version() + ", " + assets.count() + " items");
		}
	}

	/** Every item icon at the sizes the website uses goes into the cache once, so pages full of icons cost nothing. */
	private void warmIcons() {
		int n = 0;
		for (Map<String, Object> it : assets.search("", 5000)) {
			String id = (String) it.get("id");
			for (int size : new int[] {32, 64}) {
				try {
					BufferedImage icon = assets.icon(id, size);
					if (icon != null) {
						cache.put("/v2/item/" + id + ".png?size=" + size + "&", 200, "image/png", Banner.png(icon), 86400);
						n++;
					}
				} catch (Exception ignored) {
					// that one renders on demand then
				}
			}
		}
		System.out.println("[library] warmed " + n + " item icons");
	}

	private void housekeeping() {
		guard.sweep();
		if (System.currentTimeMillis() % 3_600_000 < 30_000) {
			designs.sweep();
			generated.sweep();
		}
		save();
	}

	private void save() {
		accounts.save();
		players.save();
		packs.save();
		presets.save();
		submissions.save();
	}

	// ---------------------------------------------------------------- dispatch

	private void handle(HttpExchange ex) {
		Http h = new Http(ex);
		try {
			if (h.method.equals("OPTIONS")) {
				var hd = ex.getResponseHeaders();
				hd.set("Access-Control-Allow-Origin", "*");
				hd.set("Access-Control-Allow-Methods", "GET, HEAD");
				hd.set("Access-Control-Allow-Headers", "Authorization");
				hd.set("Access-Control-Max-Age", "86400");
				ex.sendResponseHeaders(204, -1);
				return;
			}
			h.identity = accounts.identify(h);
			String root = h.part(0) == null ? "" : h.part(0).replaceAll("\\.(png|gif|json)$", "");
			Guard.Category cat = category(root, h);
			guard.checkOpen(cat);
			boolean get = h.method.equals("GET") || h.method.equals("HEAD");
			String key = get ? cacheKey(h) : null;
			if (key != null) {
				Cache.Entry hit = cache.get(key);
				if (hit != null) {
					ex.getResponseHeaders().set("X-Cache", "HIT");
					h.send(hit.status(), hit.type(), hit.body(), hit.maxAge());
					return;
				}
			}
			guard.charge(h.identity, Guard.Category.REQUEST);
			if (cat != Guard.Category.REQUEST) {
				guard.charge(h.identity, cat);
			}
			ex.getResponseHeaders().set("X-RateLimit-Remaining", String.valueOf(guard.remaining(h.identity, cat)));
			ex.getResponseHeaders().set("X-RateLimit-Tier", h.identity.tier().name().toLowerCase(java.util.Locale.ROOT));
			Out out = guard.slot(cat, () -> route(root, h));
			if (key != null && out.status() == 200 && out.cacheSeconds() > 0) {
				cache.put(key, out.status(), out.type(), out.body(), out.cacheSeconds());
			}
			h.send(out.status(), out.type(), out.body(), out.cacheSeconds());
		} catch (Http.Fail f) {
			fail(h, f.status, f.getMessage(), f.retryAfterSeconds);
		} catch (Throwable t) {
			System.out.println("[library] " + ex.getRequestURI() + ": " + t);
			fail(h, 500, "Something went wrong on our side", 0);
		} finally {
			ex.close();
		}
	}

	private static void fail(Http h, int status, String message, long retry) {
		try {
			if (retry > 0) {
				h.ex.getResponseHeaders().set("Retry-After", String.valueOf(retry));
			}
			h.json(status, Http.map("error", message, "status", status), 0);
		} catch (IOException | RuntimeException ignored) {
			// client went away
		}
	}

	private static String cacheKey(Http h) {
		if (h.part(0) == null || Set.of("me", "keys", "packs", "admin", "health").contains(h.part(0)) || (h.part(0).equals("presets") && h.token != null)) {
			return null;
		}
		StringBuilder k = new StringBuilder(h.ex.getRequestURI().getRawPath()).append('?');
		h.query.entrySet().stream().filter(e -> !e.getKey().equals("key")).sorted(Map.Entry.comparingByKey())
				.forEach(e -> k.append(e.getKey()).append('=').append(e.getValue()).append('&'));
		return k.toString();
	}

	private static Guard.Category category(String root, Http h) {
		return switch (root) {
			case "item", "items" -> Guard.Category.ICON;
			case "render", "head", "banner", "motd" -> Guard.Category.RENDER;
			case "mcbanner", "sprite", "blockfaces", "blocktex" -> Guard.Category.ICON;
			case "seed" -> Guard.Category.MAP;
			case "server" -> Guard.Category.PING;
			case "spark", "plugins" -> Guard.Category.HEAVY;
			case "presets", "packs", "keys", "servers", "designs", "schem" -> h.method.equals("GET") ? Guard.Category.REQUEST : Guard.Category.WRITE;
			default -> Guard.Category.REQUEST;
		};
	}

	private Out route(String root, Http h) throws Exception {
		return switch (root) {
			case "health" -> Out.json(Http.map("ok", true, "assets", assets.version(), "items", assets.count(), "seedMap", seeds.available()), 0);
			case "player" -> player(h);
			case "name" -> name(h);

			case "skin" -> skin(h, false);
			case "cape" -> skin(h, true);
			case "texture" -> {
				String hash = h.part(1) == null ? "" : h.part(1).replace(".png", "");
				if (!hash.matches("[0-9a-f]{20,80}")) {
					throw new Http.Fail(400, "Not a texture hash");
				}
				byte[] png = players.texture("https://textures.minecraft.net/texture/" + hash);
				if (png == null) {
					throw new Http.Fail(404, "No such texture");
				}
				yield Out.png(png, 86400 * 7);
			}
			case "head" -> head(h);
			case "render" -> render(h);
			case "poses" -> Out.json(Http.map("poses", SkinRenderer.POSES), 86400);
			case "item" -> item(h);
			case "items" -> Out.json(Http.map("version", assets.version(), "count", assets.count(), "items", assets.search(h.q("q", ""), h.qi("limit", 2000, 1, 2000))), 3600);
			case "server" -> server(h);
			case "motd" -> motd(h);
			case "banner" -> banner(h);
			case "mcbanner" -> mcBanner(h);
			case "blockfaces" -> {
				if (!assets.ready()) {
					throw new Http.Fail(503, "Still starting up", 60);
				}
				Map<String, Object> out = new java.util.LinkedHashMap<>();
				String[] ids = h.q("ids", "").split(",");
				if (ids.length > 400) {
					throw new Http.Fail(400, "At most 400 blocks at once");
				}
				for (String id : ids) {
					String clean = id.trim().toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "");
					if (!clean.isEmpty()) {
						out.put(clean, assets.faces(clean));
					}
				}
				yield Out.json(out, 86400);
			}
			case "blocktex" -> {
				String name = h.part(1) == null ? "" : h.part(1).replace(".png", "");
				BufferedImage img = name.matches("[a-z0-9_]{1,64}") && assets.ready() ? assets.texture("block/" + name) : null;
				if (img == null) {
					throw new Http.Fail(404, "No such texture");
				}
				yield Out.png(Banner.png(img), 86400 * 7);
			}
			case "sprite" -> {
				// vanilla HUD sprites (hearts, food, armor, crosshair) as starting points for the HUD designer
				String rel = String.join("/", java.util.Arrays.copyOfRange(h.parts, 1, h.parts.length)).replace(".png", "");
				if (!rel.matches("[a-z0-9_/]{3,80}") || rel.contains("..")) {
					throw new Http.Fail(400, "Bad sprite path");
				}
				BufferedImage img = assets.ready() ? assets.texture("gui/sprites/hud/" + rel) : null;
				if (img == null) {
					throw new Http.Fail(404, "No such sprite");
				}
				yield Out.png(Banner.png(img), 86400);
			}
			case "servers" -> serverMappings(h);
			case "schem" -> {
				if (h.method.equals("POST")) {
					yield Out.json(generated.save(h.body(3 << 20), h.q("name", "generated")), 0);
				}
				Object[] f = generated.get(h.part(1) == null ? null : h.part(1).replace(".litematic", ""));
				if (f == null) {
					throw new Http.Fail(404, "No schematic with that code");
				}
				h.ex.getResponseHeaders().set("X-Velo-File-Name", (String) f[1]);
				h.ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + f[1] + "\"");
				yield new Out(200, "application/octet-stream", (byte[]) f[0], 3600);
			}
			case "designs" -> {
				if (h.method.equals("POST")) {
					yield Out.json(designs.save(h.json(JsonObject.class, 64 * 1024)), 0);
				}
				yield Out.json(designs.get(h.part(1)), 300);
			}
			case "spark" -> spark(h);
			case "plugins" -> pluginCheck(h);
			case "gradient" -> gradient(h);
			case "flags" -> Out.json(Tools.flags(h.qi("ram", 8, 1, 256), h.q("software", "paper").toLowerCase(java.util.Locale.ROOT),
					h.q("gc", "g1").toLowerCase(java.util.Locale.ROOT), h.q("os", "linux").toLowerCase(java.util.Locale.ROOT)), 86400);
			case "color" -> color(h);
			case "text" -> text(h);
			case "seed" -> seed(h);
			case "presets" -> presetRoutes(h);
			case "packs" -> packRoutes(h);
			case "keys" -> keyRoutes(h);
			case "me" -> me(h);
			case "admin" -> admin(h);
			default -> throw new Http.Fail(404, "No such endpoint - see velo-client.com/library/api");
		};
	}

	// ---------------------------------------------------------------- players

	private Players.Profile requireProfile(String q) throws IOException {
		if (q == null) {
			throw new Http.Fail(400, "Name or UUID missing");
		}
		Players.Profile p = players.profile(q.replaceAll("\\.(png|gif|json)$", ""));
		if (p == null) {
			throw new Http.Fail(404, "No Java account with that name");
		}
		return p;
	}

	private Out player(Http h) throws IOException {
		Players.Profile p = requireProfile(h.part(1));
		Players.History hist = players.record(p);
		String u = p.uuid();
		String dashed = u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16) + "-" + u.substring(16, 20) + "-" + u.substring(20);
		List<Map<String, Object>> skins = new ArrayList<>();
		for (Map<String, Object> s : hist.skins) {
			skins.add(Http.map("hash", s.get("hash"), "seen", s.get("seen"), "slim", s.get("slim"),
					"url", "https://textures.minecraft.net/texture/" + s.get("hash")));
		}
		return Out.json(Http.map("uuid", u, "uuidDashed", dashed, "name", p.name(), "model", p.slim() ? "slim" : "classic",
				"skin", p.skinUrl() == null ? null : "/v2/skin/" + u + ".png", "cape", p.capeUrl() == null ? null : "/v2/cape/" + u + ".png",
				"capeTexture", p.capeUrl(), "head", "/v2/head/" + u + ".png", "render", "/v2/render/" + u + ".png",
				"names", hist.names, "skins", skins, "firstSeenHere", hist.firstSeen, "lookups", hist.lookups,
				"giveHead", "/give @p minecraft:player_head[profile={name:\"" + p.name() + "\"}]"), 120);
	}

	private Out name(Http h) throws IOException {
		String n = h.part(1);
		if (n == null || !Players.NAME.matcher(n).matches()) {
			throw new Http.Fail(400, "Names are 1-16 letters, digits or _");
		}
		String uuid = players.uuidOf(n);
		boolean valid = n.length() >= 3;
		return Out.json(Http.map("name", n, "status", uuid != null ? "taken" : valid ? "available" : "invalid", "uuid", uuid,
				"note", uuid == null && valid ? "Nobody uses it right now. Names freed recently can be blocked for a while; Mojang may also reserve some." : null), 60);
	}

	private Out skin(Http h, boolean cape) throws IOException {
		Players.Profile p = requireProfile(h.part(1));
		String url = cape ? p.capeUrl() : p.skinUrl();
		if (url == null) {
			if (cape) {
				throw new Http.Fail(404, "No cape");
			}
			return Out.png(Banner.png(assets.defaultSkin(p.slim())), 600);
		}
		byte[] png = players.texture(url);
		if (png == null) {
			throw new Http.Fail(502, "Couldn't get the texture from Mojang");
		}
		return Out.png(png, 600);
	}

	private Out head(Http h) throws IOException {
		Players.Profile p = requireProfile(h.part(1));
		Players.Textures t = players.textures(p);
		int size = h.qi("size", 128, 8, 1024);
		boolean overlay = h.qb("overlay", true);
		BufferedImage img;
		if (h.q("style", "flat").equals("3d")) {
			img = SkinRenderer.render(t, SkinRenderer.pose("stand", 0), new SkinRenderer.Options(size, h.qd("yaw", -30, -180, 180), h.qd("pitch", 18, -60, 60),
					overlay, false, 0, true, false, h.qb("layers3d", true)));
		} else {
			img = SkinRenderer.face(t.skin(), size, overlay);
		}
		return Out.png(Banner.png(img), 600);
	}

	private Out render(Http h) throws IOException {
		String last = h.part(1);
		boolean gif = last != null && last.endsWith(".gif");
		Players.Textures t;
		if (h.method.equals("POST")) {
			// your own skin file (skin editors, previews): POST /v2/render/custom.png?slim=true with the PNG as body
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(h.body(256 * 1024)));
			if (img == null || img.getWidth() % 64 != 0 || (img.getHeight() != img.getWidth() && img.getHeight() * 2 != img.getWidth()) || img.getWidth() > 1024) {
				throw new Http.Fail(400, "Send a 64x64 or 64x32 skin PNG (HD multiples up to 1024 work too)");
			}
			t = new Players.Textures(SkinRenderer.normalise(img), h.qb("slim", false), null, null);
		} else {
			Players.Profile p = requireProfile(last);
			t = players.textures(p);
			if (h.query.containsKey("slim")) {
				t = new Players.Textures(t.skin(), h.qb("slim", false), t.cape(), t.skinHash());
			}
		}
		String pose = h.q("pose", "stand").toLowerCase(java.util.Locale.ROOT);
		if (!SkinRenderer.POSES.contains(pose) && !pose.equals("custom")) {
			throw new Http.Fail(400, "Unknown pose - see /v2/poses");
		}
		int bg = 0;
		String bgq = h.q("bg", null);
		if (bgq != null && bgq.matches("#?[0-9a-fA-F]{6}")) {
			bg = 0xff000000 | Integer.parseInt(bgq.replace("#", ""), 16);
		}
		boolean overlay = h.qb("overlay", true), capeOn = h.qb("cape", true), layers3d = h.qb("layers3d", true);
		double yaw = h.qd("yaw", -25, -180, 180), pitch = h.qd("pitch", 10, -60, 60);
		if (!gif) {
			int size = h.qi("size", 512, 32, 2048);
			SkinRenderer.Pose ps = SkinRenderer.pose(pose, 0.15);
			SkinRenderer.customise(ps, h.query);
			BufferedImage img = SkinRenderer.render(t, ps, new SkinRenderer.Options(size, yaw, pitch, overlay, capeOn, bg, false, false, layers3d));
			if (upsideDown(last)) {
				img = rotate180(img);
			}
			return Out.png(Banner.png(img), 600);
		}
		int size = h.qi("size", 256, 32, 384);
		int frames = h.qi("frames", 16, 4, 32);
		boolean spin = h.qb("spin", pose.equals("stand"));
		List<BufferedImage> list = new ArrayList<>();
		// one framing for the whole animation: measured over every frame first, then each frame drawn into it
		double[] frame = SkinRenderer.newFrame();
		for (int pass = 0; pass < 2; pass++) {
			frame[4] = pass;
			for (int i = 0; i < frames; i++) {
				double tt = i / (double) frames;
				SkinRenderer.Pose ps = SkinRenderer.pose(pose, tt);
				SkinRenderer.customise(ps, h.query);
				BufferedImage f = SkinRenderer.render(t, ps, new SkinRenderer.Options(size, spin ? yaw + tt * 360 : yaw, pitch, overlay, capeOn,
						bg == 0 ? 0xff0e0e12 : bg, false, false, layers3d), frame);
				if (pass == 1) {
					list.add(f);
				}
			}
		}
		return new Out(200, "image/gif", Banner.gif(list, 70), 600);
	}

	/** Like in the game: Dinnerbone and Grumm stand on their heads. */
	private boolean upsideDown(String query) {
		if (query == null) {
			return false;
		}
		try {
			Players.Profile p = players.profile(query.replaceAll("\\.(png|gif)$", ""));
			return p != null && (p.name().equals("Dinnerbone") || p.name().equals("Grumm"));
		} catch (Exception e) {
			return false;
		}
	}

	private static BufferedImage rotate180(BufferedImage src) {
		BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.drawImage(src, src.getWidth(), src.getHeight(), -src.getWidth(), -src.getHeight(), null);
		g.dispose();
		return out;
	}

	// ---------------------------------------------------------------- items

	private Out item(Http h) throws IOException {
		if (!assets.ready()) {
			throw new Http.Fail(503, "Item icons are still being prepared - try again in a minute", 60);
		}
		String q = h.part(1);
		if ("search".equals(q)) {
			return Out.json(Http.map("items", assets.search(h.q("q", ""), h.qi("limit", 50, 1, 200))), 3600);
		}
		String id = assets.resolve(q);
		if (id == null) {
			throw new Http.Fail(404, "No item called " + (q == null ? "" : q.replace(".png", "")));
		}
		if (q.endsWith(".json")) {
			return Out.json(Http.map("id", id, "name", assets.displayName(id), "icon", "/v2/item/" + id + ".png", "version", assets.version()), 86400);
		}
		int size = h.qi("size", 64, 16, 512);
		BufferedImage icon = assets.icon(id, size);
		if (icon == null) {
			throw new Http.Fail(404, "No icon for " + id);
		}
		return Out.png(Banner.png(icon), 86400);
	}

	// ---------------------------------------------------------------- servers

	private Out server(Http h) throws IOException {
		String addr = h.part(1);
		if (addr == null) {
			throw new Http.Fail(400, "Server address missing");
		}
		ServerPing.Result r = ping.ping(addr);
		if ("icon.png".equals(h.part(2))) {
			if (r.favicon() == null) {
				throw new Http.Fail(404, "This server has no icon");
			}
			return Out.png(r.favicon(), 300);
		}
		return Out.json(ServerPing.json(r, ping), 30);
	}

	private Banner banner() {
		Banner b = banner;
		if (b == null) {
			throw new Http.Fail(503, "Still starting up - try again in a minute", 60);
		}
		return b;
	}

	private Out motd(Http h) throws IOException {
		List<Text.Run> runs;
		String mm = h.q("mm", null), legacy = h.q("legacy", null);
		if (mm != null) {
			runs = Text.fromMiniMessage(mm.length() > 600 ? mm.substring(0, 600) : mm);
		} else if (legacy != null) {
			runs = Text.fromLegacy(legacy.replace('&', '§'), new Text.Run("", 0xAAAAAA, false, false, false, false, false));
		} else {
			throw new Http.Fail(400, "Give mm= (MiniMessage) or legacy=");
		}
		if (h.part(1) != null && h.part(1).equals("convert")) {
			return Out.json(Text.formats(runs), 3600);
		}
		BufferedImage icon = null;
		String iconQ = h.q("icon", null);
		if (iconQ != null && assets.ready()) {
			String id = assets.resolve(iconQ);
			icon = id == null ? null : assets.icon(id, 64);
		}
		String name = h.q("name", "A Minecraft Server");
		return Out.png(banner().motd(name.length() > 40 ? name.substring(0, 40) : name, runs, icon, h.q("players", "0/100"), h.qi("scale", 2, 1, 4)), 3600);
	}

	private Out banner(Http h) throws IOException {
		String target = h.part(1);
		Banner.Spec spec = Banner.spec(h);
		ServerPing.Result live = null;
		if (target != null && !target.startsWith("preview")) {
			String addr = target.replaceAll("\\.(png|gif)$", "");
			live = ping.ping(addr);
		}
		byte[] out = banner().render(spec, live);
		return new Out(200, spec.animate() ? "image/gif" : "image/png", out, live == null ? 3600 : 60);
	}

	/** Server mappings: /v2/servers, /v2/servers/lookup?address=, /v2/servers/<id>, /v2/servers/<id>/logo.png. */
	private Out serverMappings(Http h) throws Exception {
		String id = h.part(1);
		if ("submit".equals(id)) {
			Accounts.User u = accounts.requireUser(h);
			String step = h.part(2) == null ? "" : h.part(2);
			if (step.equals("start")) {
				return Out.json(submissions.start(u, str(h.json(JsonObject.class, 4096), "address")), 0);
			}
			if (step.equals("verify")) {
				return Out.json(Http.map("verified", submissions.verify(u, str(h.json(JsonObject.class, 4096), "address"), ping)), 0);
			}
			return Out.json(submissions.submit(u, h.json(JsonObject.class, 4 << 20), ping, mappings), 0);
		}
		if (id == null) {
			return Out.json(Http.map("servers", mappings.list(), "source", mappings.source(), "repo", "https://github.com/velo345/server-mappings"), 600);
		}
		Mappings.Server s = id.equals("lookup") ? mappings.lookup(h.q("address", null)) : mappings.get(id.replace(".json", ""));
		if (s == null) {
			throw new Http.Fail(404, id.equals("lookup") ? "No mapping for that address - add it at github.com/velo345/server-mappings" : "No such server");
		}
		String file = h.part(2);
		if ("logo.png".equals(file)) {
			if (s.logo() != null) {
				return Out.png(s.logo(), 3600);
			}
			// no logo in the repo: the server's own icon
			String addr = s.meta().has("primaryAddress") ? s.meta().get("primaryAddress").getAsString() : s.meta().getAsJsonArray("addresses").get(0).getAsString().replace("*.", "");
			ServerPing.Result r = ping.ping(addr);
			if (r.favicon() == null) {
				throw new Http.Fail(404, "No logo");
			}
			return Out.png(r.favicon(), 3600);
		}
		if ("banner.png".equals(file)) {
			if (s.banner() == null) {
				throw new Http.Fail(404, "No banner");
			}
			return Out.png(s.banner(), 3600);
		}
		return Out.json(Mappings.json(s), 600);
	}

	/** Minecraft banners: /v2/mcbanner.png?base=white&layers=stripe_top.red,cross.black, /patterns, /pattern/<id>.png, /give. */
	private Out mcBanner(Http h) throws IOException {
		if (!assets.ready()) {
			throw new Http.Fail(503, "Still starting up - try again in a minute", 60);
		}
		String what = h.part(1) == null ? "" : h.part(1);
		if (what.equals("patterns")) {
			return Out.json(Http.map("patterns", McBanner.patternList(), "dyes", McBanner.DYES,
					"tints", java.util.Arrays.stream(McBanner.TINT).mapToObj(t -> String.format("#%06x", t)).toList()), 86400);
		}
		if (what.equals("pattern")) {
			String id = h.part(2) == null ? "" : h.part(2).replace(".png", "");
			BufferedImage m = McBanner.PATTERNS.containsKey(id) || id.equals("base") || id.equals("banner_base") ? McBanner.mask(assets, id) : null;
			if (m == null) {
				throw new Http.Fail(404, "No such pattern");
			}
			return Out.png(Banner.png(m), 86400 * 7);
		}
		int base = McBanner.dye(h.q("base", "white"));
		List<McBanner.Layer> layers = McBanner.parse(h.q("layers", ""));
		if (what.equals("give")) {
			return Out.json(McBanner.commands(base, layers), 86400);
		}
		return Out.png(Banner.png(McBanner.render(assets, base, layers, h.qi("scale", 8, 1, 32))), 86400);
	}

	// ---------------------------------------------------------------- server owners

	private Out spark(Http h) throws IOException {
		String id = Spark.idOf(h.q("url", h.part(1)));
		if (id == null) {
			throw new Http.Fail(400, "Paste a spark.lucko.me link");
		}
		return Out.json(spark.analyze(id), 3600);
	}

	private Out pluginCheck(Http h) throws IOException {
		List<String[]> list = new ArrayList<>();
		if (h.method.equals("POST")) {
			JsonObject body = h.json(JsonObject.class, 64 * 1024);
			if (body.has("plugins") && body.get("plugins").isJsonArray()) {
				for (JsonElement e : body.getAsJsonArray("plugins")) {
					if (e.isJsonPrimitive()) {
						list.add(splitNameVersion(e.getAsString()));
					} else if (e.isJsonObject() && e.getAsJsonObject().has("name")) {
						JsonObject o = e.getAsJsonObject();
						list.add(new String[] {o.get("name").getAsString(), o.has("version") ? o.get("version").getAsString() : null});
					}
				}
			} else if (body.has("text")) {
				for (String part : body.get("text").getAsString().replaceAll("(?i)plugins?\\s*\\(\\d+\\):", "").split("[,\\n]")) {
					if (!part.isBlank()) {
						list.add(splitNameVersion(part.strip()));
					}
				}
			}
		} else {
			for (String part : h.q("names", "").split(",")) {
				if (!part.isBlank()) {
					list.add(splitNameVersion(part));
				}
			}
		}
		if (list.isEmpty()) {
			throw new Http.Fail(400, "List some plugins");
		}
		if (list.size() > 40) {
			list = list.subList(0, 40);
		}
		return Out.json(Http.map("plugins", plugins.check(list)), 0);
	}

	/** "LuckPerms 5.4.141", "LuckPerms-Bukkit-5.4.141.jar", "LuckPerms v5.4" -> name + version. */
	static String[] splitNameVersion(String s) {
		String t = s.replaceAll("(?i)\\.jar$", "").replaceAll("§.", "").strip();
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)[\\s_-]+v?(\\d+(?:\\.\\d+)+[\\w.+-]*)$").matcher(t);
		if (m.matches()) {
			return new String[] {m.group(1).replaceAll("(?i)[-_](bukkit|spigot|paper|folia)$", "").strip(), m.group(2)};
		}
		return new String[] {t, null};
	}

	private Out gradient(Http h) {
		String text = h.q("text", "Velo");
		String[] cs = h.q("colors", "ff6b73,f4b82e").split(",");
		int[] stops = new int[Math.min(8, cs.length)];
		for (int i = 0; i < stops.length; i++) {
			try {
				stops[i] = Integer.parseInt(cs[i].replace("#", "").trim(), 16);
			} catch (NumberFormatException e) {
				throw new Http.Fail(400, "colors must be hex, comma separated");
			}
		}
		if (stops.length == 1) {
			stops = new int[] {stops[0], stops[0]};
		}
		return Out.json(Tools.gradient(text, stops, h.q("mode", "wave"), h.qi("frames", 20, 1, 60), h.qb("bold", false), h.q("format", "tab")), 86400);
	}

	private Out color(Http h) {
		String hex = h.part(1) == null ? "" : h.part(1).replace("#", "");
		if (!hex.matches("[0-9a-fA-F]{6}")) {
			throw new Http.Fail(400, "Give a 6-digit hex colour");
		}
		int rgb = Integer.parseInt(hex, 16);
		int best = 0;
		double bestD = Double.MAX_VALUE;
		for (int i = 0; i < Text.COLORS.length; i++) {
			int c = Text.COLORS[i];
			double d = Math.pow(((c >> 16) & 255) - ((rgb >> 16) & 255), 2) + Math.pow(((c >> 8) & 255) - ((rgb >> 8) & 255), 2) + Math.pow((c & 255) - (rgb & 255), 2);
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		StringBuilder legacy = new StringBuilder("§x");
		for (char c : hex.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
			legacy.append('§').append(c);
		}
		return Out.json(Http.map("hex", "#" + hex.toLowerCase(java.util.Locale.ROOT), "rgb", List.of((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255),
				"int", rgb, "minimessage", "<#" + hex.toLowerCase(java.util.Locale.ROOT) + ">", "legacy", legacy.toString(),
				"ampersand", "&#" + hex.toLowerCase(java.util.Locale.ROOT), "nearestVanilla", Http.map("name", Text.NAMES[best], "code", "§" + "0123456789abcdef".charAt(best))), 86400);
	}

	private Out text(Http h) {
		String mm = h.q("mm", null);
		if (mm == null) {
			throw new Http.Fail(400, "Give mm=");
		}
		return Out.json(Text.formats(Text.fromMiniMessage(mm.length() > 2000 ? mm.substring(0, 2000) : mm)), 86400);
	}

	// ---------------------------------------------------------------- seed map

	private Out seed(Http h) throws IOException {
		long seed = Seeds.seedOf(h.part(1));
		String what = h.part(2) == null ? "info" : h.part(2);
		String mc = Seeds.version(h.q("mc", "26.3"));
		int dim = h.qi("dim", 0, -1, 1);
		switch (what) {
			case "slime" -> {
				int cx = h.qi("cx", 0, -1_875_000, 1_875_000), cz = h.qi("cz", 0, -1_875_000, 1_875_000), r = h.qi("r", 32, 1, 128);
				List<List<Integer>> list = new ArrayList<>();
				for (int[] c : Seeds.slimeChunks(seed, cx - r, cz - r, cx + r, cz + r)) {
					list.add(List.of(c[0], c[1]));
				}
				return Out.json(Http.map("seed", seed, "chunks", list), 86400);
			}
			case "tile.png" -> {
				int scale = h.qi("scale", 16, 1, 256);
				if (!Set.of(1, 4, 16, 64, 256).contains(scale)) {
					throw new Http.Fail(400, "scale is 1, 4, 16, 64 or 256");
				}
				return Out.png(seeds.tile(mc, seed, dim, scale, h.qi("x", 0, -2_000_000, 2_000_000), h.qi("z", 0, -2_000_000, 2_000_000),
						h.qi("w", 256, 16, 512), h.qi("h", 256, 16, 512)), 86400);
			}
			case "structures" -> {
				int x0 = h.qi("x0", -2048, -30_000_000, 30_000_000), z0 = h.qi("z0", -2048, -30_000_000, 30_000_000);
				int x1 = h.qi("x1", 2048, -30_000_000, 30_000_000), z1 = h.qi("z1", 2048, -30_000_000, 30_000_000);
				if ((long) (x1 - x0) * (z1 - z0) > 16_384L * 16_384L || x1 < x0 || z1 < z0) {
					throw new Http.Fail(400, "Area too large (max 16384 x 16384 blocks)");
				}
				List<String> types = new ArrayList<>();
				for (String t : h.q("types", "village,ancient_city,trial_chambers,monument,mansion,outpost").split(",")) {
					if (Seeds.STRUCTURES.contains(t)) {
						types.add(t);
					}
				}
				return Out.json(Http.map("structures", seeds.structures(mc, seed, dim, x0, z0, x1, z1, types)), 86400);
			}
			case "strongholds" -> {
				return Out.json(Http.map("strongholds", seeds.strongholds(mc, seed, h.qi("count", 12, 1, 128))), 86400);
			}
			case "spawn" -> {
				return Out.json(Http.map("spawn", seeds.spawn(mc, seed)), 86400);
			}
			default -> {
				return Out.json(Http.map("seed", seed, "version", mc, "biomes", seeds.available(), "structures", Seeds.STRUCTURES), 86400);
			}
		}
	}

	// ---------------------------------------------------------------- community

	private Out presetRoutes(Http h) throws IOException {
		String id = h.part(1), action = h.part(2);
		Accounts.User viewer = h.token != null ? accounts.user(h) : null;
		String vid = viewer == null ? null : viewer.uuid();
		if (h.method.equals("GET")) {
			if (id == null) {
				String tool = h.q("tool", null);
				return Out.json(Http.map("presets", presets.list(tool, h.q("sort", "popular"), h.q("q", null), h.qi("limit", 24, 1, 60), vid)), vid == null ? 30 : 0);
			}
			return Out.json(presets.get(id, vid), vid == null ? 30 : 0);
		}
		if (h.method.equals("POST") && id == null) {
			Accounts.User u = accounts.requireUser(h);
			JsonObject body = h.json(JsonObject.class, 16 * 1024);
			JsonObject d = body.has("data") && body.get("data").isJsonObject() ? body.getAsJsonObject("data") : null;
			Map<String, Object> created = presets.create(u, str(body, "tool"), str(body, "name"), d);
			cache.removePrefix("/v2/presets");
			return Out.json(created, 0);
		}
		if (h.method.equals("POST") && "use".equals(action)) {
			presets.use(id);
			return Out.json(Http.map("ok", true), 0);
		}
		if (h.method.equals("POST") && "report".equals(action)) {
			presets.report(id, vid != null ? vid : "ip:" + h.ip);
			cache.removePrefix("/v2/presets");
			return Out.json(Http.map("ok", true), 0);
		}
		if (h.method.equals("POST") && "like".equals(action)) {
			Map<String, Object> r = presets.like(id, accounts.requireUser(h));
			cache.removePrefix("/v2/presets");
			return Out.json(r, 0);
		}
		if (h.method.equals("DELETE")) {
			Accounts.User u = accounts.requireUser(h);
			if (!presets.delete(id, u, accounts.isOwner(u))) {
				throw new Http.Fail(404, "Not yours, or gone already");
			}
			cache.removePrefix("/v2/presets");
			return Out.json(Http.map("ok", true), 0);
		}
		throw new Http.Fail(405, "Not allowed");
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
	}

	private Out packRoutes(Http h) throws IOException {
		String id = h.part(1);
		if (h.method.equals("POST") && id == null) {
			Accounts.User u = accounts.requireUser(h);
			String len = h.ex.getRequestHeaders().getFirst("Content-Length");
			if (len != null && Long.parseLong(len) > Packs.MAX_BYTES) {
				throw new Http.Fail(413, "Packs can be up to 50 MB");
			}
			Packs.Pack p = packs.upload(h.ex.getRequestBody(), h.q("name", null), u);
			return Out.json(packs.json(p), 0);
		}
		if (h.method.equals("GET") && "mine".equals(id)) {
			return Out.json(Http.map("packs", packs.mine(accounts.requireUser(h))), 0);
		}
		if (h.method.equals("DELETE")) {
			Accounts.User u = accounts.requireUser(h);
			if (!packs.delete(id, u, accounts.isOwner(u))) {
				throw new Http.Fail(404, "Not yours, or gone already");
			}
			return Out.json(Http.map("ok", true), 0);
		}
		Packs.Pack p = packs.get(id);
		if (p == null) {
			throw new Http.Fail(404, "No such pack");
		}
		if ("icon.png".equals(h.part(2))) {
			byte[] icon = packs.icon(id);
			if (icon == null) {
				throw new Http.Fail(404, "No icon");
			}
			return Out.png(icon, 86400);
		}
		return Out.json(packs.json(p), 300);
	}

	private Out keyRoutes(Http h) throws IOException {
		Accounts.User u = accounts.requireUser(h);
		if (h.method.equals("GET")) {
			return Out.json(Http.map("keys", accounts.list(u), "tier", u.plus() ? "plus" : "key",
					"limits", Http.map("requests", Guard.Category.REQUEST.perMinute[u.plus() ? 2 : 1], "renders", Guard.Category.RENDER.perMinute[u.plus() ? 2 : 1],
							"pings", Guard.Category.PING.perMinute[u.plus() ? 2 : 1], "heavy", Guard.Category.HEAVY.perMinute[u.plus() ? 2 : 1])), 0);
		}
		if (h.method.equals("POST")) {
			JsonObject body = h.json(JsonObject.class, 4096);
			Accounts.KeyRecord k = accounts.create(u, str(body, "label"));
			return Out.json(Http.map("key", k.key, "label", k.label), 0);
		}
		if (h.method.equals("DELETE")) {
			if (!accounts.delete(u, h.part(1))) {
				throw new Http.Fail(404, "No such key");
			}
			return Out.json(Http.map("ok", true), 0);
		}
		throw new Http.Fail(405, "Not allowed");
	}

	private Out me(Http h) {
		Accounts.User u = accounts.user(h);
		if (u == null) {
			return Out.json(Http.map("signedIn", false), 0);
		}
		return Out.json(Http.map("signedIn", true, "uuid", u.uuid(), "username", u.username(), "plus", u.plus(), "owner", accounts.isOwner(u)), 0);
	}

	// ---------------------------------------------------------------- owner

	private Out admin(Http h) throws IOException {
		boolean ok = adminToken != null && adminToken.equals(h.ex.getRequestHeaders().getFirst("X-Library-Admin-Token"));
		if (!ok) {
			ok = accounts.isOwner(accounts.user(h));
		}
		if (!ok) {
			throw new Http.Fail(403, "Owners only");
		}
		String what = h.part(1) == null ? "" : h.part(1);
		switch (what) {
			case "presets" -> {
				if (h.method.equals("GET")) {
					return Out.json(Http.map("presets", presets.moderation()), 0);
				}
				String id = h.part(2), action = h.part(3);
				if ("hide".equals(action) || "unhide".equals(action)) {
					presets.setHidden(id, "hide".equals(action));
				} else if ("delete".equals(action)) {
					presets.delete(id, null, true);
				}
				cache.removePrefix("/v2/presets");
				return Out.json(Http.map("ok", true), 0);
			}
			case "switch" -> {
				// {"category": "all" | "render" | "ping" | "heavy" | "write" | "request", "off": true}
				JsonObject body = h.json(JsonObject.class, 1024);
				String c = str(body, "category");
				boolean off = body.has("off") && body.get("off").getAsBoolean();
				if (c == null || !Set.of("all", "render", "ping", "heavy", "write", "request").contains(c)) {
					throw new Http.Fail(400, "Unknown category");
				}
				Path f = data.resolve(c.equals("all") ? "disabled" : "disabled-" + c);
				if (off) {
					Files.writeString(f, "off since " + java.time.Instant.now());
				} else {
					Files.deleteIfExists(f);
				}
				return Out.json(Http.map("ok", true, "category", c, "off", off), 0);
			}
			case "submissions" -> {
				if (h.method.equals("POST") && h.part(3) != null && h.part(3).equals("synced")) {
					submissions.markSynced(h.part(2));
					return Out.json(Http.map("ok", true), 0);
				}
				return Out.json(Http.map("submissions", "queued".equals(h.part(2)) ? submissions.queued() : submissions.list()), 0);
			}
			case "status" -> {
				List<String> off = new ArrayList<>();
				try (var list = Files.list(data)) {
					list.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("disabled")).forEach(off::add);
				}
				return Out.json(Http.map("off", off, "assets", assets.version(), "seedMap", seeds.available(), "popular", players.popular(10)), 0);
			}
			default -> throw new Http.Fail(404, "No such admin action");
		}
	}

	@SuppressWarnings("unused")
	private static BufferedImage read(byte[] b) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(b));
	}

	@SuppressWarnings("unused")
	private static JsonArray arr(JsonElement e) {
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	@SuppressWarnings("unused")
	private static JsonObject parse(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}
}
