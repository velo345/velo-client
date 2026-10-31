package net.veloclient.velo.dev.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.dev.storage.StorageStore.Container;
import net.veloclient.velo.dev.storage.StorageStore.Item;
import net.veloclient.velo.dev.storage.StorageStore.Station;
import net.veloclient.velo.dev.storage.StorageStore.Trader;
import net.veloclient.velo.dev.storage.StorageStore.World;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Party Sync for Storage Memory (opt-in, only inside your Velo party): while it's on, the world you're in - containers,
 * work stations, traders, plus your inventory and ender chest - goes to the Velo server for your party members, and
 * theirs comes back. A container someone opened more recently than you replaces your copy (so when a mate takes
 * something out, you see it), broken ones disappear for everyone, and their inventories / ender chests show up in the
 * search as "Steve's inventory". Only multiplayer worlds are shared (singleplayer save names aren't unique).
 */
public final class StoragePartySync {

	/** One member's shared storage. */
	public record Share(String uuid, String name, String server, String world, long at, World data, List<Item> inventory) {
	}

	/** What goes over the wire (gzip + base64). */
	private static final class Payload {
		int v = 1;
		World world;
		List<Item> inventory;
	}

	private static final Gson GSON = new Gson();
	private static final long POLL_MS = 5000;
	private static final long UPLOAD_GAP_MS = 8000;
	private static final long HEARTBEAT_MS = 10 * 60_000;

	private static final Map<String, Share> SHARES = new ConcurrentHashMap<>();
	private static long since;
	private static long nextPoll;
	private static long nextUpload;
	private static long lastUpload;
	private static int uploadedVersion = -1;
	private static int uploadedInventory;
	private static boolean polling;
	private static boolean uploading;
	/** Where we last shared to (to stop sharing there). */
	private static String sharedServer;
	/** Bumped whenever members' shares change (screens rebuild their index). */
	private static int version;

	private StoragePartySync() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	/** Party Sync is on and there's a party to sync with. */
	public static boolean running() {
		return StorageMemoryModule.active() && StorageMemoryModule.partySync() && SocialClient.inParty();
	}

	private static boolean shareable(String serverKey) {
		return serverKey != null && (serverKey.startsWith("mp:") || serverKey.startsWith("realm:"));
	}

	/** Members sharing the given world right now. */
	public static List<Share> sharesFor(String serverKey, String worldKey) {
		List<Share> list = new ArrayList<>();
		if (!running() || serverKey == null || worldKey == null) {
			return list;
		}
		for (Share share : SHARES.values()) {
			if (serverKey.equals(share.server()) && worldKey.equals(share.world())) {
				list.add(share);
			}
		}
		return list;
	}

	public static int version() {
		return version;
	}

	public static int sharingCount() {
		return running() ? SHARES.size() : 0;
	}

	private static void tick() {
		long now = System.currentTimeMillis();
		String serverKey = StorageStore.currentServerKey();
		if (!running() || !shareable(serverKey)) {
			if (sharedServer != null) {
				SocialClient.partyStorageShare(sharedServer, null, null);
				sharedServer = null;
				uploadedVersion = -1;
			}
			if (!running() && !SHARES.isEmpty()) {
				SHARES.clear();
				version++;
			}
			if (!running()) {
				since = 0;
			}
			return;
		}
		if (now >= nextPoll && !polling) {
			nextPoll = now + POLL_MS;
			poll();
		}
		World world = StorageStore.current();
		if (world == null || uploading || now < nextUpload) {
			return;
		}
		List<Item> inventory = StorageCapture.inventory();
		int inventoryHash = GSON.toJson(inventory).hashCode();
		boolean changed = StorageStore.version() != uploadedVersion || inventoryHash != uploadedInventory || !serverKey.equals(sharedServer);
		if (!changed && now - lastUpload < HEARTBEAT_MS) {
			return;
		}
		upload(serverKey, StorageStore.currentWorldKey(), world, inventory, inventoryHash);
	}

	private static void upload(String serverKey, String worldKey, World world, List<Item> inventory, int inventoryHash) {
		uploading = true;
		nextUpload = System.currentTimeMillis() + UPLOAD_GAP_MS;
		Payload payload = new Payload();
		payload.world = world;
		payload.inventory = inventory;
		// Serialized here (main thread owns the store), compressed off-thread.
		String json = GSON.toJson(payload);
		int version = StorageStore.version();
		java.util.concurrent.CompletableFuture.supplyAsync(() -> encode(json))
				.thenCompose(data -> SocialClient.partyStorageShare(serverKey, worldKey, data))
				.whenComplete((error, throwable) -> MinecraftClient.getInstance().execute(() -> {
					uploading = false;
					if (throwable == null && error == null) {
						uploadedVersion = version;
						uploadedInventory = inventoryHash;
						sharedServer = serverKey;
						lastUpload = System.currentTimeMillis();
					} else {
						// Too big / rate limited / offline: try again later.
						nextUpload = System.currentTimeMillis() + 60_000;
					}
				}));
	}

	private static void poll() {
		polling = true;
		SocialClient.partyStorageShares(since).thenApply(StoragePartySync::decodeAll).whenComplete((result, throwable) ->
				MinecraftClient.getInstance().execute(() -> {
					polling = false;
					if (result != null && throwable == null) {
						apply(result);
					}
				}));
	}

	/** Off the main thread: the server's answer with every new document decoded. */
	private static Polled decodeAll(JsonObject response) {
		if (response == null || !response.has("shares")) {
			return null;
		}
		List<Share> shares = new ArrayList<>();
		Set<String> present = new HashSet<>();
		for (JsonElement element : response.getAsJsonArray("shares")) {
			JsonObject o = element.getAsJsonObject();
			String uuid = string(o, "uuid");
			if (uuid == null) {
				continue;
			}
			present.add(uuid);
			String data = string(o, "data");
			if (data == null) {
				continue;
			}
			try {
				Payload payload = GSON.fromJson(decode(data), Payload.class);
				if (payload == null || payload.world == null) {
					continue;
				}
				sanitize(payload.world);
				shares.add(new Share(uuid, string(o, "name"), string(o, "server"), string(o, "world"), o.get("at").getAsLong(),
						payload.world, payload.inventory == null ? List.of() : payload.inventory));
			} catch (Exception ignored) {
				// a broken document: skip it
			}
		}
		long now = response.has("now") ? response.get("now").getAsLong() : 0;
		return new Polled(now, shares, present);
	}

	private record Polled(long now, List<Share> shares, Set<String> present) {
	}

	private static void apply(Polled polled) {
		since = polled.now();
		int before = SHARES.size();
		SHARES.keySet().retainAll(polled.present());
		if (SHARES.size() != before || !polled.shares().isEmpty()) {
			version++;
		}
		boolean changed = false;
		for (Share share : polled.shares()) {
			SHARES.put(share.uuid(), share);
			changed |= merge(share);
		}
		if (changed) {
			StorageStore.markDirty(StorageStore.currentServerKey());
		}
	}

	/** Takes over what the member saw more recently than you. True when your storage changed. */
	private static boolean merge(Share share) {
		if (share.server() == null || share.world() == null || !share.server().equals(StorageStore.currentServerKey())) {
			return false;
		}
		StorageStore.Server server = StorageStore.server(share.server());
		World mine = server.worlds.get(share.world());
		World theirs = share.data();
		boolean changed = false;
		if (mine == null) {
			mine = new World();
			mine.firstSeen = System.currentTimeMillis();
			mine.lastSeen = mine.firstSeen;
			mine.name = theirs.name != null ? theirs.name + " (" + share.name() + ")" : share.name() + "'s world";
			server.worlds.put(share.world(), mine);
			changed = true;
		}
		for (Map.Entry<String, Long> gone : theirs.gone.entrySet()) {
			Container have = mine.containers.get(gone.getKey());
			if (have != null && gone.getValue() > have.time) {
				mine.containers.remove(gone.getKey());
				changed = true;
			}
			Long known = mine.gone.get(gone.getKey());
			if (known == null || known < gone.getValue()) {
				mine.gone.put(gone.getKey(), gone.getValue());
			}
		}
		for (Map.Entry<String, Container> entry : theirs.containers.entrySet()) {
			Container their = entry.getValue();
			Long gone = mine.gone.get(entry.getKey());
			if (gone != null && gone >= their.time) {
				continue;
			}
			Container have = mine.containers.get(entry.getKey());
			if (have == null || their.time > have.time) {
				if (their.by == null) {
					their.by = share.name();
				}
				mine.containers.put(entry.getKey(), their);
				mine.gone.remove(entry.getKey());
				changed = true;
			}
		}
		for (Map.Entry<String, Trader> entry : theirs.traders.entrySet()) {
			Trader their = entry.getValue();
			Trader have = mine.traders.get(entry.getKey());
			if (have == null || their.time > have.time) {
				if (their.by == null) {
					their.by = share.name();
				}
				mine.traders.put(entry.getKey(), their);
				changed = true;
			}
		}
		for (Map.Entry<String, Station> entry : theirs.stations.entrySet()) {
			if (!mine.stations.containsKey(entry.getKey())) {
				mine.stations.put(entry.getKey(), entry.getValue());
				changed = true;
			}
		}
		return changed;
	}

	/** Fills in missing collections so a member on an older build can't crash the merge. */
	private static void sanitize(World world) {
		if (world.containers == null) {
			world.containers = new java.util.LinkedHashMap<>();
		}
		if (world.traders == null) {
			world.traders = new java.util.LinkedHashMap<>();
		}
		if (world.stations == null) {
			world.stations = new java.util.LinkedHashMap<>();
		}
		if (world.gone == null) {
			world.gone = new java.util.LinkedHashMap<>();
		}
		world.containers.values().removeIf(c -> c == null || c.dim == null || c.items == null);
		world.traders.values().removeIf(t -> t == null || t.dim == null || t.offers == null || t.uuid == null);
		world.stations.values().removeIf(s -> s == null || s.dim == null || s.block == null);
		world.gone.values().removeIf(t -> t == null);
	}

	private static String string(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static String encode(String json) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
				gzip.write(json.getBytes(StandardCharsets.UTF_8));
			}
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static String decode(String data) throws Exception {
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
			// Capped: a member's document can't blow up memory.
			byte[] out = gzip.readNBytes(32 * 1024 * 1024);
			return new String(out, StandardCharsets.UTF_8);
		}
	}
}
