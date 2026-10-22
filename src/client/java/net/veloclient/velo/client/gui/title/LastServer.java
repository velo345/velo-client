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

	private static final long RECHECK_MS = 60_000;
	private static volatile String reachableAddress;
	private static volatile String checkedAddress;
	private static volatile long checkedAt;
	private static volatile boolean checking;

	/**
	 * Whether the server answers right now - the "Jump back in" card only offers servers you can actually get to. Checked
	 * in the background (resolve + connect, a few seconds at most), again every minute while the title screen asks.
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
		try {
			//? if <26.1 {
			var resolved = net.minecraft.client.network.AllowedAddressResolver.DEFAULT.resolve(net.minecraft.client.network.ServerAddress.parse(address));
			if (resolved.isEmpty()) {
				return false;
			}
			java.net.InetSocketAddress target = resolved.get().getInetSocketAddress();
			//?} else {
			/*var resolved = net.minecraft.client.multiplayer.resolver.ServerNameResolver.DEFAULT.resolveAddress(
					net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address));
			if (resolved.isEmpty()) {
				return false;
			}
			java.net.InetSocketAddress target = resolved.get().asInetSocketAddress();
			*///?}
			try (java.net.Socket socket = new java.net.Socket()) {
				socket.connect(target, 3000);
				return true;
			}
		} catch (Exception e) {
			return false;
		}
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
		byte[] favicon = faviconBytes();
		Entry entry = new Entry(name == null || name.isBlank() ? address : name, address,
				favicon == null ? null : Base64.getEncoder().encodeToString(favicon), System.currentTimeMillis());
		cached = entry;
		loaded = true;
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(entry), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Only the title card misses it.
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
