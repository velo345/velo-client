package net.veloclient.library;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java players: name/UUID lookups, skins and capes, plus the history we build ourselves (Mojang removed the public
 * name history in 2022): every lookup records the names and skins we see, so the history grows from now on.
 */
final class Players {

	static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
	static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{32}$|^[0-9a-fA-F-]{36}$");

	record Profile(String uuid, String name, String skinUrl, boolean slim, String capeUrl, long fetched) {
	}

	record Textures(BufferedImage skin, boolean slim, BufferedImage cape, String skinHash) {
	}

	static final class History {
		List<Map<String, Object>> names = new ArrayList<>();
		List<Map<String, Object>> skins = new ArrayList<>();
		long firstSeen;
		long lookups;
		/** when the background check last asked Mojang for this account's name */
		long checked;
	}

	/** Mojang frees a changed name 37 days after the change. */
	static final long HOLD = 37L * 86_400_000L;

	private final Cache.Memo<Profile> profiles = new Cache.Memo<>(20_000);
	private final Cache.Memo<String> nameToUuid = new Cache.Memo<>(50_000);
	private final Cache.Memo<Textures> textures = new Cache.Memo<>(3000);
	private final Cache.Memo<byte[]> textureBytes = new Cache.Memo<>(4000);
	private final Path skinDir;
	private final JsonFile<Map<String, History>> history;
	private BufferedImage steve;
	private BufferedImage alex;

	Players(Path dataDir) throws IOException {
		skinDir = Files.createDirectories(dataDir.resolve("skins"));
		history = new JsonFile<>(dataDir.resolve("player-history.json"), new TypeToken<Map<String, History>>() {
		}.getType(), HashMap::new);
	}

	void setDefaults(BufferedImage steve, BufferedImage alex) {
		this.steve = steve;
		this.alex = alex;
	}

	/** By name or UUID; null when no such Java account exists. */
	Profile profile(String query) throws IOException {
		if (query == null) {
			return null;
		}
		query = query.trim();
		String uuid;
		if (UUID.matcher(query).matches()) {
			uuid = query.replace("-", "").toLowerCase(java.util.Locale.ROOT);
		} else if (NAME.matcher(query).matches()) {
			uuid = uuidOf(query);
			if (uuid == null) {
				return null;
			}
		} else {
			throw new Http.Fail(400, "That isn't a Minecraft name or UUID");
		}
		Profile cached = profiles.get(uuid);
		if (cached != null) {
			return cached;
		}
		Net.Response r = Net.get("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid, 64 * 1024);
		if (r.status() == 204 || r.status() == 404) {
			return null;
		}
		if (r.status() != 200) {
			throw new Http.Fail(502, "Mojang didn't answer (" + r.status() + ") - try again shortly", 10);
		}
		JsonObject o = JsonParser.parseString(r.text()).getAsJsonObject();
		String name = o.get("name").getAsString();
		String skinUrl = null;
		String capeUrl = null;
		boolean slim = false;
		for (var p : o.getAsJsonArray("properties")) {
			JsonObject prop = p.getAsJsonObject();
			if (!"textures".equals(prop.get("name").getAsString())) {
				continue;
			}
			JsonObject t = JsonParser.parseString(new String(Base64.getDecoder().decode(prop.get("value").getAsString()), StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonObject("textures");
			if (t != null && t.has("SKIN")) {
				JsonObject s = t.getAsJsonObject("SKIN");
				skinUrl = s.get("url").getAsString().replace("http://", "https://");
				slim = s.has("metadata") && "slim".equals(s.getAsJsonObject("metadata").get("model").getAsString());
			}
			if (t != null && t.has("CAPE")) {
				capeUrl = t.getAsJsonObject("CAPE").get("url").getAsString().replace("http://", "https://");
			}
		}
		Profile p = new Profile(uuid, name, skinUrl, slim, capeUrl, System.currentTimeMillis());
		profiles.put(uuid, p, 5 * 60_000);
		nameToUuid.put(name.toLowerCase(java.util.Locale.ROOT), uuid, 10 * 60_000);
		return p;
	}

	/** null = no account has this name right now. */
	String uuidOf(String name) throws IOException {
		String key = name.toLowerCase(java.util.Locale.ROOT);
		String cached = nameToUuid.get(key);
		if (cached != null) {
			return cached.isEmpty() ? null : cached;
		}
		Net.Response r = Net.get("https://api.mojang.com/users/profiles/minecraft/" + name, 16 * 1024);
		if (r.status() == 204 || r.status() == 404) {
			nameToUuid.put(key, "", 60_000);
			return null;
		}
		if (r.status() != 200) {
			throw new Http.Fail(502, "Mojang didn't answer (" + r.status() + ") - try again shortly", 10);
		}
		String uuid = JsonParser.parseString(r.text()).getAsJsonObject().get("id").getAsString();
		nameToUuid.put(key, uuid, 10 * 60_000);
		return uuid;
	}

	/** Skin + cape images (the default Steve/Alex when the player has none). */
	Textures textures(Profile p) throws IOException {
		Textures cached = textures.get(p.uuid());
		if (cached != null) {
			return cached;
		}
		BufferedImage skin = null;
		String hash = null;
		if (p.skinUrl() != null) {
			byte[] png = texture(p.skinUrl());
			skin = png == null ? null : ImageIO.read(new ByteArrayInputStream(png));
			hash = p.skinUrl().substring(p.skinUrl().lastIndexOf('/') + 1);
		}
		if (skin == null) {
			skin = p.slim() ? alex : steve;
		}
		BufferedImage cape = null;
		if (p.capeUrl() != null) {
			byte[] png = texture(p.capeUrl());
			cape = png == null ? null : ImageIO.read(new ByteArrayInputStream(png));
		}
		Textures t = new Textures(SkinRenderer.normalise(skin), p.slim(), cape, hash);
		textures.put(p.uuid(), t, 5 * 60_000);
		return t;
	}

	byte[] texture(String url) throws IOException {
		if (!url.startsWith("https://textures.minecraft.net/texture/")) {
			return null;
		}
		String hash = url.substring(url.lastIndexOf('/') + 1).replaceAll("[^0-9a-fA-F]", "");
		byte[] mem = textureBytes.get(hash);
		if (mem != null) {
			return mem;
		}
		Path f = skinDir.resolve(hash + ".png");
		if (Files.exists(f)) {
			byte[] b = Files.readAllBytes(f);
			textureBytes.put(hash, b, 3_600_000);
			return b;
		}
		Net.Response r = Net.get(url, 256 * 1024);
		if (r.status() != 200 || ImageIO.read(new ByteArrayInputStream(r.body())) == null) {
			return null;
		}
		Files.write(f, r.body());
		textureBytes.put(hash, r.body(), 3_600_000);
		return r.body();
	}

	/** Remembers what this lookup showed (name, skin) and returns the history so far. */
	History record(Profile p) {
		Map<String, History> all = history.get();
		synchronized (all) {
			History h = all.computeIfAbsent(p.uuid(), k -> {
				History n = new History();
				n.firstSeen = System.currentTimeMillis();
				return n;
			});
			h.lookups++;
			long now = System.currentTimeMillis();
			note(h, p.name(), now);
			h.checked = now;
			if (p.skinUrl() != null) {
				String hash = p.skinUrl().substring(p.skinUrl().lastIndexOf('/') + 1);
				if (h.skins.isEmpty() || !hash.equals(h.skins.get(0).get("hash"))) {
					h.skins.add(0, new HashMap<>(Map.of("hash", hash, "slim", p.slim(), "seen", now)));
					while (h.skins.size() > 24) {
						h.skins.remove(h.skins.size() - 1);
					}
				}
			}
			history.changed();
			return h;
		}
	}

	/** A name seen on an account right now: same as before -> "still seen", different -> a name change (the old one drops). */
	private static void note(History h, String name, long now) {
		if (h.names.isEmpty() || !name.equals(h.names.get(0).get("name"))) {
			Map<String, Object> e = new HashMap<>();
			e.put("name", name);
			e.put("seen", now);
			e.put("last", now);
			h.names.add(0, e);
			while (h.names.size() > 30) {
				h.names.remove(h.names.size() - 1);
			}
		} else {
			h.names.get(0).put("last", now);
		}
	}

	private static long num(Object o) {
		return o instanceof Number n ? n.longValue() : 0;
	}

	int known() {
		return history.get().size();
	}

	/** Players looked up most often (for "popular" on the page), never more than a handful. */
	List<Map<String, Object>> popular(int n) {
		Map<String, History> all = history.get();
		synchronized (all) {
			return all.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().lookups, a.getValue().lookups)).limit(n)
					.map(e -> Http.map("uuid", e.getKey(), "name", e.getValue().names.isEmpty() ? null : e.getValue().names.get(0).get("name"),
							"lookups", e.getValue().lookups))
					.toList();
		}
	}

	private long savedAt;

	/** The history gets big - written at most every 5 minutes (and on shutdown). */
	void save() {
		if (System.currentTimeMillis() - savedAt < 5 * 60_000L) {
			return;
		}
		savedAt = System.currentTimeMillis();
		history.saveIfDirty();
	}

	void saveNow() {
		history.saveIfDirty();
	}

	static String sha1(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
