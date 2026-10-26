package net.veloclient.velo.client.gui.title;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.RemoteTextureLoader;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * The server you played on last, for the title screen's "Jump back in" card: name, address and the server's
 * own icon, remembered when you join (realms and singleplayer are skipped).
 */
public final class LastServer {

	public record Entry(String serverName, String address, String favicon, long joinedAt) {
	}

	private static final Gson GSON = new Gson();
	private static Entry cached;
	private static boolean loaded;
	private static Identifier icon;
	private static String iconFor;

	private LastServer() {
	}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(LastServer::capture));
	}

	private static final long RECHECK_MS = 5 * 60_000;
	private static volatile String reachableAddress;
	private static volatile String checkedAddress;
	private static volatile long checkedAt;
	private static volatile boolean checking;

	/**
	 * Whether the server answers right now - the "Jump back in" card only offers servers you can actually get to. Checked
	 * in the background (resolve + status ping, a few seconds at most), again every five minutes while the title screen asks.
	 */
	public static boolean reachable(Entry entry) {
		String address = entry.address();
		long now = System.currentTimeMillis();
		if (!checking && (!address.equals(checkedAddress) || now - checkedAt > RECHECK_MS)) {
			checking = true;
			checkedAddress = address;
			checkedAt = now;
			Thread thread = new Thread(() -> {
				boolean up = probe(address);
				reachableAddress = up ? address : null;
				checking = false;
			}, "Velo last-server check");
			thread.setDaemon(true);
			thread.start();
		}
		return address.equals(reachableAddress);
	}

	private static boolean probe(String address) {
		return status(address) != null;
	}

	/** The server's status JSON (MOTD, players, favicon) from a real status ping, or null when it doesn't answer. */
	private static String status(String address) {
		try {
			//? if <26.1 {
			var resolved = net.minecraft.client.network.AllowedAddressResolver.DEFAULT.resolve(net.minecraft.client.network.ServerAddress.parse(address));
			if (resolved.isEmpty()) {
				return null;
			}
			java.net.InetSocketAddress target = resolved.get().getInetSocketAddress();
			//?} else {
			/*var resolved = net.minecraft.client.multiplayer.resolver.ServerNameResolver.DEFAULT.resolveAddress(
					net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address));
			if (resolved.isEmpty()) {
				return null;
			}
			java.net.InetSocketAddress target = resolved.get().asInetSocketAddress();
			*///?}
			// A real status ping (what the server list sends), never a bare connect + close: anti-bot proxies
			// (DonutShield, luckysmp...) read handshake-less connections as a port scan and block the IP for a while,
			// so the next real join just gets "connection reset".
			String host = address.contains(":") && !address.startsWith("[") ? address.substring(0, address.lastIndexOf(':')) : address;
			try (java.net.Socket socket = new java.net.Socket()) {
				socket.connect(target, 3000);
				socket.setSoTimeout(3000);
				java.io.ByteArrayOutputStream handshake = new java.io.ByteArrayOutputStream();
				writeVarInt(handshake, 0x00);
				writeVarInt(handshake, net.minecraft.SharedConstants.getProtocolVersion());
				byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
				writeVarInt(handshake, hostBytes.length);
				handshake.write(hostBytes);
				handshake.write(target.getPort() >> 8);
				handshake.write(target.getPort());
				writeVarInt(handshake, 1);
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				writeVarInt(out, handshake.size());
				out.write(handshake.toByteArray());
				out.write(1);
				out.write(0x00);
				socket.getOutputStream().write(out.toByteArray());
				socket.getOutputStream().flush();
				java.io.InputStream in = socket.getInputStream();
				readVarInt(in); // packet length
				if (readVarInt(in) != 0x00) {
					return null;
				}
				return new String(in.readNBytes(readVarInt(in)), StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			return null;
		}
	}

	private static int readVarInt(java.io.InputStream in) throws java.io.IOException {
		int value = 0;
		for (int shift = 0; shift < 35; shift += 7) {
			int b = in.read();
			if (b < 0) {
				throw new java.io.EOFException();
			}
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new java.io.IOException("VarInt too long");
	}

	private static void writeVarInt(java.io.ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}

	private static Path file() {
		return VeloPaths.config().resolve("last-server.json");
	}

	private static long sessionStart;

	/** When you joined the current world or server (epoch ms), 0 when not in one. */
	public static long sessionStart() {
		return sessionStart;
	}

	private static void capture() {
		sessionStart = System.currentTimeMillis();
		String address = ClientCompat.currentServerAddress();
		if (address == null || address.isBlank() || ClientCompat.isRealm() || System.getProperty("velo.screenshotTour") != null) {
			return;
		}
		String name = ClientCompat.currentServerName();
		// The joined ServerData only has an icon when you came from the server list and it had pinged that entry - a direct
		// connect, a partner/"Jump back in" join or a list that hasn't loaded yet leave it empty. Fall back to the saved list,
		// then to the icon remembered last time, then to our own status ping (what the list sends, so proxies answer it too).
		byte[] bytes = faviconBytes();
		if (bytes == null) {
			bytes = MenuInfo.savedServerIcon(address);
		}
		String favicon = bytes == null ? null : Base64.getEncoder().encodeToString(bytes);
		Entry previous = get();
		if (favicon == null && previous != null && address.equalsIgnoreCase(previous.address())) {
			favicon = previous.favicon();
		}
		save(new Entry(name == null || name.isBlank() ? address : name, address, favicon, System.currentTimeMillis()));
		if (favicon == null) {
			Thread thread = new Thread(() -> {
				String icon = faviconFromStatus(status(address));
				Entry current = cached;
				if (icon != null && current != null && address.equals(current.address())) {
					save(new Entry(current.serverName(), current.address(), icon, current.joinedAt()));
				}
			}, "Velo server icon");
			thread.setDaemon(true);
			thread.start();
		}
	}

	private static void save(Entry entry) {
		cached = entry;
		loaded = true;
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(entry), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Only the title card misses it.
		}
	}

	/** The base64 PNG from a status response's {@code "favicon": "data:image/png;base64,..."}, or null. */
	private static String faviconFromStatus(String json) {
		if (json == null) {
			return null;
		}
		try {
			var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			if (!root.has("favicon")) {
				return null;
			}
			String favicon = root.get("favicon").getAsString().replace("\n", "");
			int comma = favicon.indexOf(',');
			return comma >= 0 ? favicon.substring(comma + 1) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static byte[] faviconBytes() {
		try {
			//? if <26.1 {
			var entry = net.minecraft.client.MinecraftClient.getInstance().getCurrentServerEntry();
			return entry == null ? null : entry.getFavicon();
			//?} else {
			/*var entry = net.minecraft.client.Minecraft.getInstance().getCurrentServer();
			return entry == null ? null : entry.getIconBytes();
			*///?}
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The last server - or, before you've joined one with Velo, the first one in your server list - or null. */
	public static Entry get() {
		if (!loaded) {
			loaded = true;
			try {
				if (Files.exists(file())) {
					cached = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Entry.class);
				}
			} catch (Exception ignored) {
				cached = null;
			}
			if (cached == null) {
				Object[] first = MenuInfo.firstSavedServer();
				if (first != null && first[1] != null) {
					byte[] icon = (byte[]) first[2];
					cached = new Entry(first[0] == null ? (String) first[1] : (String) first[0], (String) first[1],
							icon == null ? null : Base64.getEncoder().encodeToString(icon), 0);
				}
			}
		}
		return cached;
	}

	/** The server's own icon (registered on first use), or null while loading / when it has none. */
	public static Identifier icon() {
		Entry entry = get();
		if (entry == null || entry.favicon() == null) {
			return null;
		}
		if (!entry.favicon().equals(iconFor)) {
			iconFor = entry.favicon();
			icon = null;
			try {
				byte[] png = Base64.getDecoder().decode(entry.favicon());
				RemoteTextureLoader.fromBytes("last_server_" + Integer.toHexString(entry.address().hashCode()), png, id -> icon = id);
			} catch (IllegalArgumentException ignored) {
				// Broken icon: the logo stands in.
			}
		}
		return icon;
	}

	public static void join() {
		Entry entry = get();
		if (entry != null) {
			ClientCompat.joinServer(entry.address(), entry.serverName());
		}
	}
}
