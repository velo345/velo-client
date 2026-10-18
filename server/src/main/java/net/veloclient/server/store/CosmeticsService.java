package net.veloclient.server.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every cosmetic the clients can show - wings and capes - with its art, so adding one never needs a
 * client update: the clients download the catalog and any art they don't ship with.
 *
 * <p>Stored in {@code cosmetics/catalog.json} in the data directory, the art content-addressed under
 * {@code cosmetics/files/<sha256>} (clients cache it forever and check the hash). Each item has a
 * {@link #STATUS_RELEASED released} / {@link #STATUS_TESTING testing} / {@link #STATUS_HIDDEN hidden}
 * status and an optional time window: only released items inside their window are sold. Testing
 * and hidden items can still be given by the owner and are shown on whoever owns them - so a pair
 * can be tried out with everyone seeing it before anyone can buy it, and a limited collection can
 * be taken out of the store again without taking it away from the people who bought it.
 *
 * <p>Items the server ships with (bundled under {@code /cosmetics-seed/} in the jar) are added on
 * start when missing; anything already in the catalog is never overwritten, so the owner's edits
 * (status, price, window...) stay.
 */
public final class CosmeticsService {

	public static final String STATUS_RELEASED = "released";
	public static final String STATUS_TESTING = "testing";
	public static final String STATUS_HIDDEN = "hidden";
	private static final Set<String> STATUSES = Set.of(STATUS_RELEASED, STATUS_TESTING, STATUS_HIDDEN);
	private static final Set<String> TYPES = Set.of("wings", "cape", "aura");
	private static final Pattern ITEM_ID = Pattern.compile("(wings|cape|aura)_[a-z0-9_-]{1,40}");
	private static final Pattern COLLECTION_ID = Pattern.compile("[a-z0-9_-]{1,40}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	public static final int MAX_FILE_BYTES = 12 * 1024 * 1024;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * One cosmetic. {@code files}: role -> sha256 (wings: mesh, texture, preview; capes: gif). Fields an
	 * owner edit leaves out stay null, so only what's given changes.
	 */
	public static final class Item {
		public String id;
		public String type;
		public String name;
		public String description;
		public int price;
		public String status;
		public String collection;
		/** Epoch millis; null = no limit. Only matters for released items. */
		public Long availableFrom;
		public Long availableUntil;
		public String accent;
		public String style;
		public Float scale;
		public Float offsetY;
		/** Drawn full-bright (neon). */
		public Boolean glow;
		/** How it's earned instead of bought ("discord" = linking your Discord account); such an item is never sold, whatever its status. */
		public String unlock;
		public Map<String, String> files;
		public long addedAt;
		/** Seed revision: when the bundled seed's revision is higher, the item's art (and text) is refreshed from it - owner-set price/status/window stay. */
		public int rev;

		Item copy() {
			Item c = GSON.fromJson(GSON.toJson(this), Item.class);
			return c;
		}
	}

	/** A themed drop, e.g. "Afterlife". */
	public static final class Collection {
		public String id;
		public String name;
		public String tag = "COLLECTION";
		public String description = "";
	}

	private static final class Data {
		String featured;
		List<Collection> collections = new ArrayList<>();
		List<Item> items = new ArrayList<>();
	}

	/** The seed bundled in the jar: the same shape plus where each file is ({@code files} are paths inside the seed). */
	private static final class Seed {
		String featured;
		List<Collection> collections = new ArrayList<>();
		List<Item> items = new ArrayList<>();
	}

	private final Path dir;
	private final Path filesDir;
	private final Path catalogFile;
	private Data data;

	public CosmeticsService(Path dataDir) throws IOException {
		this.dir = dataDir.resolve("cosmetics");
		this.filesDir = dir.resolve("files");
		this.catalogFile = dir.resolve("catalog.json");
		Files.createDirectories(filesDir);
		data = Files.exists(catalogFile) ? GSON.fromJson(Files.readString(catalogFile, StandardCharsets.UTF_8), Data.class) : null;
		if (data == null) {
			data = new Data();
		}
		if (data.collections == null) {
			data.collections = new ArrayList<>();
		}
		if (data.items == null) {
			data.items = new ArrayList<>();
		}
	}

	// ---- Seeding ----

	/**
	 * Adds the bundled items/collections that aren't in the catalog yet. {@code prices} (from an older
	 * store.json) win over the seed's prices, so owner-set prices survive the move to this catalog.
	 */
	public synchronized void seedFromClasspath(String root, Map<String, Integer> prices) throws IOException {
		Seed seed;
		try (InputStream in = CosmeticsService.class.getResourceAsStream(root + "seed.json")) {
			if (in == null) {
				return;
			}
			seed = GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), Seed.class);
		}
		boolean changed = false;
		if (data.featured == null && seed.featured != null) {
			data.featured = seed.featured;
			changed = true;
		}
		for (Collection c : seed.collections) {
			if (collection(c.id) == null) {
				data.collections.add(c);
				changed = true;
			}
		}
		// The "Neon" collection became the "Future" collection: rename it unless the owner already edited it.
		Collection neon = collection("neon");
		if (neon != null && "Neon".equals(neon.name) && "NEON COLLECTION".equals(neon.tag)) {
			for (Collection c : seed.collections) {
				if (c.id.equals("neon")) {
					neon.name = c.name;
					neon.tag = c.tag;
					neon.description = c.description;
					changed = true;
				}
			}
		}
		for (Item s : seed.items) {
			Item existing = find(s.id);
			if (existing != null) {
				if (s.rev > existing.rev) {
					// Redrawn art / reworded text shipped with a newer server: refresh it, keep everything the owner set.
					Map<String, String> hashes = seedFiles(root, s);
					if (hashes != null) {
						existing.files = hashes;
						if (s.description != null) {
							existing.description = s.description;
						}
						existing.rev = s.rev;
						changed = true;
					}
				}
				continue;
			}
			Item item = s.copy();
			Map<String, String> hashes = seedFiles(root, s);
			if (hashes == null) {
				continue;
			}
			item.files = hashes;
			if (prices != null && prices.containsKey(item.id)) {
				item.price = prices.get(item.id);
			}
			item.addedAt = System.currentTimeMillis();
			data.items.add(item);
			changed = true;
		}
		if (changed) {
			save();
		}
	}

	/** Stores a seed item's bundled files and returns role -> sha256, or null when one is missing from the jar. */
	private Map<String, String> seedFiles(String root, Item s) throws IOException {
		Map<String, String> hashes = new LinkedHashMap<>();
		for (Map.Entry<String, String> file : s.files.entrySet()) {
			try (InputStream in = CosmeticsService.class.getResourceAsStream(root + file.getValue())) {
				if (in == null) {
					System.out.println("[cosmetics] Seed file missing: " + file.getValue());
					return null;
				}
				hashes.put(file.getKey(), storeFile(in.readAllBytes()));
			} catch (StoreService.StoreException e) {
				throw new IOException("Bad seed file " + file.getValue() + ": " + e.getMessage());
			}
		}
		return hashes;
	}

	// ---- Reading ----

	private Item find(String id) {
		for (Item item : data.items) {
			if (item.id.equals(id)) {
				return item;
			}
		}
		return null;
	}

	private Collection collection(String id) {
		for (Collection c : data.collections) {
			if (c.id.equals(id)) {
				return c;
			}
		}
		return null;
	}

	/** A copy of the item, or null. */
	public synchronized Item item(String id) {
		Item item = id == null ? null : find(id);
		return item == null ? null : item.copy();
	}

	public synchronized boolean exists(String id) {
		return id != null && find(id) != null;
	}

	/** Released and inside its time window: shown in the store and can be bought. */
	public static boolean inStore(Item item, long now) {
		return item.unlock == null && STATUS_RELEASED.equals(item.status) && (item.availableFrom == null || item.availableFrom <= now)
				&& (item.availableUntil == null || item.availableUntil > now);
	}

	public synchronized boolean buyable(String id) {
		Item item = find(id);
		return item != null && inStore(item, System.currentTimeMillis());
	}

	/** What a client may see of an item: everything except the owner-only status details. */
	public record ItemView(String id, String type, String name, String description, int price, String collection, String accent,
			String style, Float scale, Float offsetY, Boolean glow, Map<String, String> files, boolean inStore, Long availableUntil, String status,
			String unlock) {
	}

	public record CollectionView(String id, String name, String tag, String description) {
	}

	public record CatalogView(String featured, List<CollectionView> collections, List<ItemView> items) {
	}

	private static ItemView view(Item i, long now, boolean withStatus) {
		boolean inStore = inStore(i, now);
		return new ItemView(i.id, i.type, i.name, i.description, i.price, i.collection, i.accent, i.style, i.scale, i.offsetY, i.glow,
				Map.copyOf(i.files), inStore, inStore ? i.availableUntil : null, withStatus ? i.status : null, i.unlock);
	}

	/**
	 * The catalog for one viewer: what's in the store, plus whatever they own (in any status); an owner
	 * sees everything with its status. Collections are only listed when something in them is visible.
	 */
	public synchronized CatalogView catalog(Set<String> owned, boolean owner) {
		long now = System.currentTimeMillis();
		List<ItemView> items = new ArrayList<>();
		Set<String> usedCollections = new java.util.HashSet<>();
		for (Item i : data.items) {
			if (owner || inStore(i, now) || owned.contains(i.id)) {
				items.add(view(i, now, owner));
				if (i.collection != null) {
					usedCollections.add(i.collection);
				}
			}
		}
		List<CollectionView> collections = new ArrayList<>();
		for (Collection c : data.collections) {
			if (owner || usedCollections.contains(c.id)) {
				collections.add(new CollectionView(c.id, c.name, c.tag, c.description));
			}
		}
		return new CatalogView(data.featured, collections, items);
	}

	/** What's needed to draw an item on someone else (any status - testers are seen by everyone). */
	public synchronized ItemView renderInfo(String id) {
		Item item = find(id);
		if (item == null) {
			return null;
		}
		ItemView v = view(item, System.currentTimeMillis(), false);
		return new ItemView(v.id(), v.type(), v.name(), v.description(), 0, v.collection(), v.accent(), v.style(), v.scale(), v.offsetY(), v.glow(),
				v.files(), v.inStore(), null, null, v.unlock());
	}

	/** Released items in their window, for the old {@code /v1/store/catalog} price list. */
	public synchronized List<StoreConfig.Item> storeItems() {
		long now = System.currentTimeMillis();
		List<StoreConfig.Item> out = new ArrayList<>();
		for (Item i : data.items) {
			if (inStore(i, now)) {
				out.add(new StoreConfig.Item(i.id, i.name, i.price));
			}
		}
		return out;
	}

	public record AdminView(String featured, List<Collection> collections, List<Item> items) {
	}

	public synchronized AdminView admin() {
		List<Item> items = new ArrayList<>();
		for (Item i : data.items) {
			items.add(i.copy());
		}
		return new AdminView(data.featured, List.copyOf(data.collections), items);
	}

	// ---- Files ----

	/** The bytes of a stored file, or null. */
	public byte[] file(String sha256) throws IOException {
		if (sha256 == null || !SHA256.matcher(sha256).matches()) {
			return null;
		}
		Path path = filesDir.resolve(sha256);
		return Files.exists(path) ? Files.readAllBytes(path) : null;
	}

	public static String contentType(byte[] bytes) {
		if (bytes.length >= 4 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
			return "image/png";
		}
		if (bytes.length >= 4 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8') {
			return "image/gif";
		}
		return "application/octet-stream";
	}

	/** Stores an uploaded file (PNG, GIF or a wing mesh only) and returns its sha256. */
	public String storeFile(byte[] bytes) throws IOException, StoreService.StoreException {
		boolean mesh = bytes.length >= 4 && bytes[0] == 'V' && bytes[1] == 'M' && bytes[2] == 'S' && bytes[3] == 'H';
		if (bytes.length == 0 || bytes.length > MAX_FILE_BYTES || !mesh && contentType(bytes).startsWith("application/")) {
			throw new StoreService.StoreException(400, "Only PNG, GIF or .vmesh files up to 12 MB");
		}
		String sha;
		try {
			sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		Path path = filesDir.resolve(sha);
		if (!Files.exists(path)) {
			Path tmp = Files.createTempFile(filesDir, "upload", ".tmp");
			Files.write(tmp, bytes);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		return sha;
	}

	// ---- Owner edits ----

	/**
	 * Adds or changes an item. Only the fields given are changed on an existing item (a null leaves
	 * it as it is); {@code clearWindow} removes the time window. Files must already be uploaded.
	 */
	public synchronized Item upsert(Item change, boolean clearWindow) throws IOException, StoreService.StoreException {
		if (change == null || change.id == null || !ITEM_ID.matcher(change.id).matches()) {
			throw new StoreService.StoreException(400, "Item ids look like wings_<name>, cape_<name> or aura_<name> (lowercase, digits, _ or -)");
		}
		Item item = find(change.id);
		boolean created = item == null;
		if (created) {
			item = new Item();
			item.id = change.id;
			item.type = change.id.startsWith("wings_") ? "wings" : change.id.startsWith("aura_") ? "aura" : "cape";
			item.name = change.id;
			item.description = "";
			item.status = STATUS_TESTING;
			item.files = new LinkedHashMap<>();
			item.addedAt = System.currentTimeMillis();
		}
		if (change.type != null && !TYPES.contains(change.type)) {
			throw new StoreService.StoreException(400, "type is wings, cape or aura");
		}
		if (change.status != null) {
			if (!STATUSES.contains(change.status)) {
				throw new StoreService.StoreException(400, "status is released, testing or hidden");
			}
			item.status = change.status;
		}
		if (change.name != null && !change.name.isBlank()) {
			item.name = change.name.strip();
		}
		if (change.description != null) {
			item.description = change.description.strip();
		}
		if (change.price > 0) {
			if (change.price > 1_000_000) {
				throw new StoreService.StoreException(400, "Price is at most 1,000,000 coins");
			}
			item.price = change.price;
		}
		if (change.collection != null) {
			if (change.collection.isBlank()) {
				item.collection = null;
			} else if (collection(change.collection) == null) {
				throw new StoreService.StoreException(404, "No collection " + change.collection + " - create it first");
			} else {
				item.collection = change.collection;
			}
		}
		if (clearWindow) {
			item.availableFrom = null;
			item.availableUntil = null;
		}
		if (change.availableFrom != null) {
			item.availableFrom = change.availableFrom <= 0 ? null : change.availableFrom;
		}
		if (change.availableUntil != null) {
			item.availableUntil = change.availableUntil <= 0 ? null : change.availableUntil;
		}
		if (change.accent != null) {
			if (!change.accent.matches("#[0-9a-fA-F]{6}")) {
				throw new StoreService.StoreException(400, "accent is a colour like #ff8a1f");
			}
			item.accent = change.accent.toLowerCase(Locale.ROOT);
		}
		if (change.style != null) {
			if (!Set.of("feathered", "bat", "crystal").contains(change.style)) {
				throw new StoreService.StoreException(400, "style is feathered, bat or crystal");
			}
			item.style = change.style;
		}
		if (change.scale != null) {
			item.scale = Math.max(0.1f, Math.min(4f, change.scale));
		}
		if (change.glow != null) {
			item.glow = change.glow;
		}
		if (change.unlock != null) {
			item.unlock = change.unlock.isBlank() ? null : change.unlock;
		}
		if (change.offsetY != null) {
			item.offsetY = Math.max(-1f, Math.min(1f, change.offsetY));
		}
		if (change.files != null && !change.files.isEmpty()) {
			for (Map.Entry<String, String> file : change.files.entrySet()) {
				if (file.getValue() == null || !SHA256.matcher(file.getValue()).matches() || !Files.exists(filesDir.resolve(file.getValue()))) {
					throw new StoreService.StoreException(400, "Upload " + file.getKey() + " first (unknown file)");
				}
			}
			item.files = new LinkedHashMap<>(item.files);
			item.files.putAll(change.files);
		}
		// Auras are drawn by code in the clients - no files.
		List<String> required = "wings".equals(item.type) ? List.of("mesh", "texture") : "cape".equals(item.type) ? List.of("gif") : List.of();
		for (String role : required) {
			if (!item.files.containsKey(role)) {
				throw new StoreService.StoreException(400, "A " + item.type + " item needs its " + role + " file");
			}
		}
		if (created) {
			if (item.price <= 0) {
				item.price = 2500;
			}
			data.items.add(item);
		}
		save();
		return item.copy();
	}

	public synchronized Collection upsertCollection(Collection change) throws IOException, StoreService.StoreException {
		if (change == null || change.id == null || !COLLECTION_ID.matcher(change.id).matches()) {
			throw new StoreService.StoreException(400, "Collection ids are lowercase letters, digits, _ or -");
		}
		Collection c = collection(change.id);
		if (c == null) {
			c = new Collection();
			c.id = change.id;
			c.name = change.id;
			data.collections.add(0, c);
		}
		if (change.name != null && !change.name.isBlank()) {
			c.name = change.name.strip();
		}
		if (change.tag != null && !change.tag.isBlank()) {
			c.tag = change.tag.strip().toUpperCase(Locale.ROOT);
		}
		if (change.description != null) {
			c.description = change.description.strip();
		}
		save();
		return c;
	}

	/** The collection the store features (null = the newest one with something in the store). */
	public synchronized void setFeatured(String collectionId) throws IOException, StoreService.StoreException {
		if (collectionId != null && !collectionId.isBlank() && collection(collectionId) == null) {
			throw new StoreService.StoreException(404, "No such collection");
		}
		data.featured = collectionId == null || collectionId.isBlank() ? null : collectionId;
		save();
	}

	private void save() throws IOException {
		Path tmp = dir.resolve("catalog.json.tmp");
		Files.writeString(tmp, GSON.toJson(data), StandardCharsets.UTF_8);
		Files.move(tmp, catalogFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
