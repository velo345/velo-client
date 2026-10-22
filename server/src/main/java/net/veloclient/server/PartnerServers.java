package net.veloclient.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Partner servers the owner lists (owner panel): shown on the game's title screen bar and at the top of Discover in the
 * game and the launcher, joined directly by address. Kept in {@code <data>/partners.json}.
 *
 * <p>Every few minutes each partner is pinged like the multiplayer list does (server list ping, SRV records too): its
 * own icon and player count go out with the list, so clients never ping them themselves. The owner can upload an icon
 * instead ({@code <data>/partner-icons/<id>.png}), which then wins over the server's.
 */
final class PartnerServers {

	/** {@code versions}: e.g. "1.21.11 - 26.3" (shown as is); {@code accent}: a #rrggbb card colour, optional. */
	record Partner(String id, String name, String address, String description, String versions, String accent) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	static final int MAX = 50;

	/** What the last ping found; {@code favicon} = PNG bytes or null. */
	record Status(boolean online, int players, int max, String motd, byte[] favicon, long checkedAt) {
	}

	private final Path file;
	private final Path iconDir;
	private volatile List<Partner> list = List.of();
	private final java.util.Map<String, Status> status = new java.util.concurrent.ConcurrentHashMap<>();

	PartnerServers(Path dataDir) throws IOException {
		this.file = dataDir.resolve("partners.json");
		this.iconDir = dataDir.resolve("partner-icons");
		if (Files.exists(file)) {
			List<Partner> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Partner>>() { }.getType());
			list = loaded == null ? List.of() : List.copyOf(loaded);
		}
	}

	List<Partner> all() {
		return list;
	}

	/** Replaces the whole list (the owner panel sends it in order); entries are checked and trimmed. */
	synchronized List<Partner> save(List<Partner> incoming) throws ApiError, IOException {
		if (incoming == null || incoming.size() > MAX) {
			throw new ApiError(400, "At most " + MAX + " partner servers");
		}
		List<Partner> clean = new ArrayList<>();
		for (Partner p : incoming) {
			String name = CloudBackupService.clean(p.name(), 40);
			String address = p.address() == null ? null : p.address().trim().toLowerCase(java.util.Locale.ROOT);
			if (name == null || address == null || !address.matches("[a-z0-9.-]{1,253}(:[0-9]{1,5})?")) {
				throw new ApiError(400, "Every partner needs a name and a server address like play.example.net");
			}
			String accent = p.accent() != null && p.accent().matches("#[0-9a-fA-F]{6}") ? p.accent() : null;
			String id = p.id() != null && p.id().matches("[a-z0-9-]{1,40}") ? p.id() : address.replaceAll("[^a-z0-9]+", "-");
			clean.add(new Partner(id, name, address, CloudBackupService.clean(p.description(), 140), CloudBackupService.clean(p.versions(), 30), accent));
		}
		Path temp = Files.createTempFile(file.getParent(), "partners-", ".tmp");
		Files.writeString(temp, GSON.toJson(clean), StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		list = List.copyOf(clean);
		dropStaleIcons();
		return list;
	}

	// ---- Status + icons ----

	Status status(String id) {
		return status.get(id);
	}

	/** The list as clients get it: each partner with its live status and whether it has an icon. */
	List<java.util.Map<String, Object>> views() {
		List<java.util.Map<String, Object>> out = new ArrayList<>();
		for (Partner p : list) {
			java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
			row.put("id", p.id());
			row.put("name", p.name());
			row.put("address", p.address());
			row.put("description", p.description());
			row.put("versions", p.versions());
			row.put("accent", p.accent());
			Status st = status.get(p.id());
			row.put("online", st != null && st.online());
			row.put("players", st == null ? 0 : st.players());
			row.put("max", st == null ? 0 : st.max());
			byte[] icon = icon(p.id());
			// The client caches icons by this, so a new icon shows up without a restart.
			row.put("icon", icon == null ? null : Integer.toHexString(java.util.Arrays.hashCode(icon)));
			row.put("customIcon", Files.exists(iconDir.resolve(p.id() + ".png")));
			row.put("iconSize", iconSize(icon));
			out.add(row);
		}
		return out;
	}

	/** The icon's width in pixels (0 without one). */
	private static int iconSize(byte[] png) {
		if (png == null || png.length < 24) {
			return 0;
		}
		// PNG: the IHDR width sits at bytes 16-19.
		return ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
	}

	/** Biggest side an uploaded icon is kept at (server list icons are only 64x64). */
	static final int ICON_MAX = 512;
	/** What clients get when they don't ask for a size: sharp at GUI scale 2-3, and what 0.9.9 clients fetch. */
	static final int ICON_DEFAULT = 128;
	private static final int MAX_UPLOAD_BYTES = 4 * 1024 * 1024;

	private final java.util.Map<String, byte[]> scaled = new java.util.concurrent.ConcurrentHashMap<>();

	/** The owner's uploaded icon, else the server's own, else null - at full size. */
	byte[] icon(String id) {
		if (id == null || !id.matches("[a-z0-9-]{1,40}")) {
			return null;
		}
		Path custom = iconDir.resolve(id + ".png");
		try {
			if (Files.exists(custom)) {
				return Files.readAllBytes(custom);
			}
		} catch (IOException ignored) {
			// Fall back to the pinged one.
		}
		Status st = status.get(id);
		return st == null ? null : st.favicon();
	}

	/** The icon at most {@code size} pixels wide (never scaled up; a server's own 64x64 icon stays 64x64). */
	byte[] icon(String id, int size) {
		byte[] full = icon(id);
		if (full == null) {
			return null;
		}
		int side = Math.max(16, Math.min(ICON_MAX, size));
		String key = id + "/" + side + "/" + java.util.Arrays.hashCode(full);
		byte[] cached = scaled.get(key);
		if (cached != null) {
			return cached;
		}
		try {
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(full));
			if (image == null || image.getWidth() <= side) {
				return full;
			}
			byte[] out = png(downscale(image, side));
			if (scaled.size() > 400) {
				scaled.clear();
			}
			scaled.put(key, out);
			return out;
		} catch (IOException e) {
			return full;
		}
	}

	/** Square, at most {@code side} px, shrunk in halving steps so detail stays crisp instead of aliased. */
	static java.awt.image.BufferedImage downscale(java.awt.image.BufferedImage image, int side) {
		int crop = Math.min(image.getWidth(), image.getHeight());
		java.awt.image.BufferedImage current = new java.awt.image.BufferedImage(crop, crop, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = current.createGraphics();
		g.drawImage(image, 0, 0, crop, crop, (image.getWidth() - crop) / 2, (image.getHeight() - crop) / 2,
				(image.getWidth() + crop) / 2, (image.getHeight() + crop) / 2, null);
		g.dispose();
		int size = crop;
		while (size > side) {
			int next = Math.max(side, size / 2);
			java.awt.image.BufferedImage smaller = new java.awt.image.BufferedImage(next, next, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D s2 = smaller.createGraphics();
			s2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			s2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
			s2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
			s2.drawImage(current, 0, 0, next, next, null);
			s2.dispose();
			current = smaller;
			size = next;
		}
		return current;
	}

	private static byte[] png(java.awt.image.BufferedImage image) throws IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	/** Owner upload: a PNG or JPEG (up to 4 MB), kept square at up to 512x512 - sharper than any server list icon; null removes it. */
	void setIcon(String id, byte[] png) throws ApiError, IOException {
		if (list.stream().noneMatch(p -> p.id().equals(id))) {
			throw new ApiError(404, "No such partner server");
		}
		Path target = iconDir.resolve(id + ".png");
		if (png == null || png.length == 0) {
			Files.deleteIfExists(target);
			return;
		}
		if (png.length > MAX_UPLOAD_BYTES) {
			throw new ApiError(413, "The icon can be at most 4 MB");
		}
		java.awt.image.BufferedImage image;
		try {
			image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
		} catch (IOException e) {
			image = null;
		}
		if (image == null || image.getWidth() > 4096 || image.getHeight() > 4096 || image.getWidth() < 16 || image.getHeight() < 16) {
			throw new ApiError(400, "The icon must be a PNG or JPEG between 16x16 and 4096x4096");
		}
		// Re-encoded (so nothing but pixels reaches players), square, at most 512x512.
		Files.createDirectories(iconDir);
		Path temp = Files.createTempFile(iconDir, id + "-", ".tmp");
		Files.write(temp, png(downscale(image, ICON_MAX)));
		Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Removes icons of partners that aren't on the list any more. */
	private void dropStaleIcons() {
		try (java.util.stream.Stream<Path> files = Files.exists(iconDir) ? Files.list(iconDir) : java.util.stream.Stream.empty()) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (name.endsWith(".png") && list.stream().noneMatch(p -> (p.id() + ".png").equals(name))) {
					Files.deleteIfExists(file);
				}
			}
		} catch (IOException ignored) {
			// Tried again on the next save.
		}
	}

	/** Pings every partner (in parallel, a few seconds each at most). */
	void refreshStatus() {
		List<Thread> threads = new ArrayList<>();
		for (Partner p : list) {
			threads.add(Thread.ofVirtual().start(() -> {
				Status fresh = ping(p.address());
				Status old = status.get(p.id());
				// Down for a moment: keep showing its icon.
				status.put(p.id(), !fresh.online() && old != null ? new Status(false, 0, 0, null, old.favicon(), fresh.checkedAt()) : fresh);
			}));
		}
		for (Thread t : threads) {
			try {
				t.join(8000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		status.keySet().removeIf(id -> list.stream().noneMatch(p -> p.id().equals(id)));
	}

	/** Server list ping (the status handshake every Minecraft version since 1.7 answers). */
	static Status ping(String address) {
		long now = System.currentTimeMillis();
		String host = address;
		int port = 25565;
		int colon = address.lastIndexOf(':');
		if (colon > 0) {
			host = address.substring(0, colon);
			port = Integer.parseInt(address.substring(colon + 1));
		} else {
			String[] srv = srv(host);
			if (srv != null) {
				host = srv[0];
				port = Integer.parseInt(srv[1]);
			}
		}
		try (java.net.Socket socket = new java.net.Socket()) {
			socket.connect(new java.net.InetSocketAddress(host, port), 4000);
			socket.setSoTimeout(5000);
			java.io.DataOutputStream out = new java.io.DataOutputStream(socket.getOutputStream());
			java.io.ByteArrayOutputStream handshake = new java.io.ByteArrayOutputStream();
			java.io.DataOutputStream h = new java.io.DataOutputStream(handshake);
			writeVarInt(h, 0);
			writeVarInt(h, 767);
			byte[] hostBytes = address.split(":")[0].getBytes(StandardCharsets.UTF_8);
			writeVarInt(h, hostBytes.length);
			h.write(hostBytes);
			h.writeShort(port);
			writeVarInt(h, 1);
			writeVarInt(out, handshake.size());
			out.write(handshake.toByteArray());
			out.write(new byte[] {1, 0});
			out.flush();
			java.io.DataInputStream in = new java.io.DataInputStream(socket.getInputStream());
			readVarInt(in);
			if (readVarInt(in) != 0) {
				return new Status(false, 0, 0, null, null, now);
			}
			int length = readVarInt(in);
			if (length <= 0 || length > 2 * 1024 * 1024) {
				return new Status(false, 0, 0, null, null, now);
			}
			byte[] json = in.readNBytes(length);
			com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
			int players = 0;
			int max = 0;
			if (o.has("players") && o.get("players").isJsonObject()) {
				com.google.gson.JsonObject pl = o.getAsJsonObject("players");
				players = pl.has("online") ? pl.get("online").getAsInt() : 0;
				max = pl.has("max") ? pl.get("max").getAsInt() : 0;
			}
			byte[] favicon = null;
			if (o.has("favicon") && o.get("favicon").isJsonPrimitive()) {
				String data = o.get("favicon").getAsString();
				int comma = data.indexOf(',');
				if (data.startsWith("data:image/png;base64,") && comma > 0) {
					favicon = java.util.Base64.getMimeDecoder().decode(data.substring(comma + 1));
				}
			}
			return new Status(true, players, max, null, favicon, now);
		} catch (Exception e) {
			return new Status(false, 0, 0, null, null, now);
		}
	}

	/** {host, port} from the {@code _minecraft._tcp} SRV record, or null. */
	private static String[] srv(String host) {
		try {
			java.util.Hashtable<String, String> env = new java.util.Hashtable<>();
			env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
			env.put("com.sun.jndi.dns.timeout.initial", "2000");
			env.put("com.sun.jndi.dns.timeout.retries", "1");
			javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
			javax.naming.directory.Attribute attr = ctx.getAttributes("_minecraft._tcp." + host, new String[] {"SRV"}).get("SRV");
			if (attr == null || attr.size() == 0) {
				return null;
			}
			String[] parts = attr.get(0).toString().split(" ");
			String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
			return new String[] {target, parts[2]};
		} catch (Exception e) {
			return null;
		}
	}

	private static void writeVarInt(java.io.DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	private static int readVarInt(java.io.DataInputStream in) throws IOException {
		int value = 0;
		int shift = 0;
		while (true) {
			int b = in.readUnsignedByte();
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
			shift += 7;
			if (shift > 35) {
				throw new IOException("VarInt too big");
			}
		}
	}
}
