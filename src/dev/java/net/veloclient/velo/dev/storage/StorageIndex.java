package net.veloclient.velo.dev.storage;

import net.veloclient.velo.dev.storage.StorageStore.Container;
import net.veloclient.velo.dev.storage.StorageStore.Item;
import net.veloclient.velo.dev.storage.StorageStore.Offer;
import net.veloclient.velo.dev.storage.StorageStore.Station;
import net.veloclient.velo.dev.storage.StorageStore.Trader;
import net.veloclient.velo.dev.storage.StorageStore.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Storage Memory flattened for searching: every item (and everything inside shulker boxes and bundles, however deep)
 * becomes a {@link Hit} that knows which container holds it and through which boxes. Party members' inventories and
 * ender chests (Party Sync) are hits too. Items are grouped by {@link Item#key()}: a renamed or custom server item
 * is its own entry; recipes and routes only count plain vanilla ones (renamed is fine, a custom model isn't).
 */
public final class StorageIndex {

	public enum Where { CONTAINER, ENDER, INVENTORY, PARTY_INVENTORY, PARTY_ENDER }

	/** One stored stack. {@code path}: the boxes / bundles it sits in, outermost first (item ids). {@code owner}: party member. */
	public record Hit(Item item, Where where, Container container, List<String> path, String owner) {
	}

	/** A trade on offer, with who offers it. */
	public record TradeHit(Trader trader, Offer offer) {
	}

	/** One stop of a route: where, and what to take there (item id -> count). {@code ender}: any ender chest. */
	public record Stop(String label, String dim, double x, double y, double z, Container container, boolean ender, Map<String, Integer> take) {
	}

	/**
	 * The shortest walk found past containers that together hold enough. {@code fromInventory}: what you already carry;
	 * {@code missing}: what's still short after every stop (item / ingredient label -> count); {@code party}: who in
	 * your party has some of what's missing.
	 */
	public record Route(List<Stop> stops, Map<String, Integer> fromInventory, Map<String, Integer> missing, double length, List<String> party) {
	}

	private static final Map<String, String> NAMES = new HashMap<>();
	private static final Map<String, String> ENCHANT_NAMES = new HashMap<>();

	private final List<Hit> hits = new ArrayList<>();
	private final List<TradeHit> trades = new ArrayList<>();
	private final World world;
	private final boolean here;

	public StorageIndex(World world, boolean here, List<StoragePartySync.Share> party) {
		this.world = world;
		this.here = here;
		if (world == null) {
			return;
		}
		for (Container container : world.containers.values()) {
			for (Item item : container.items) {
				add(item, Where.CONTAINER, container, List.of(), null);
			}
		}
		if (world.ender != null) {
			for (Item item : world.ender) {
				add(item, Where.ENDER, null, List.of(), null);
			}
		}
		if (here) {
			for (Item item : StorageCapture.inventory()) {
				add(item, Where.INVENTORY, null, List.of(), null);
			}
		}
		for (StoragePartySync.Share share : party) {
			for (Item item : share.inventory()) {
				add(item, Where.PARTY_INVENTORY, null, List.of(), share.name());
			}
			if (share.data().ender != null) {
				for (Item item : share.data().ender) {
					add(item, Where.PARTY_ENDER, null, List.of(), share.name());
				}
			}
		}
		for (Trader trader : world.traders.values()) {
			for (Offer offer : trader.offers) {
				trades.add(new TradeHit(trader, offer));
			}
		}
	}

	private void add(Item item, Where where, Container container, List<String> path, String owner) {
		if (item == null || item.id == null) {
			return;
		}
		hits.add(new Hit(item, where, container, path, owner));
		if (item.in != null) {
			List<String> inner = new ArrayList<>(path);
			inner.add(item.id);
			for (Item nested : item.in) {
				add(nested, where, container, List.copyOf(inner), owner);
			}
		}
	}

	public World world() {
		return world;
	}

	public boolean here() {
		return here;
	}

	public List<Hit> hits() {
		return hits;
	}

	public List<TradeHit> trades() {
		return trades;
	}

	// ---- Names ----

	/** Item id of a key ({@code id} or {@code id|custom name}). */
	public static String idOf(String key) {
		int bar = key.indexOf('|');
		return bar < 0 ? key : key.substring(0, bar);
	}

	/** Custom name of a key, or null. */
	public static String customOf(String key) {
		int bar = key.indexOf('|');
		return bar < 0 ? null : key.substring(bar + 1);
	}

	/** Localized item name (cached). */
	public static String name(String id) {
		return NAMES.computeIfAbsent(id, k -> {
			var stack = StorageMc.stack(k, 1);
			return StorageMc.isEmpty(stack) ? StorageStore.pretty(k) : StorageMc.displayName(stack);
		});
	}

	/** What a key is called: its custom name, else the item's name. */
	public static String keyName(String key) {
		String custom = customOf(key);
		return custom != null ? custom : name(key);
	}

	/** "Mending", "Sharpness V" for {@code id=level}. */
	public static String enchantLabel(String entry) {
		String cached = ENCHANT_NAMES.get(entry);
		if (cached != null) {
			return cached;
		}
		String id = enchantId(entry);
		int level = enchantLevel(entry);
		String name = StorageMc.enchantName(id, level);
		if (name == null) {
			// Not in a world (no registry): don't cache the fallback.
			return StorageStore.pretty(id) + (level > 1 ? " " + level : "");
		}
		ENCHANT_NAMES.put(entry, name);
		return name;
	}

	public static String enchantId(String entry) {
		int eq = entry.lastIndexOf('=');
		return eq < 0 ? entry : entry.substring(0, eq);
	}

	public static int enchantLevel(String entry) {
		int eq = entry.lastIndexOf('=');
		return eq < 0 ? 1 : parseInt(entry.substring(eq + 1));
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	// ---- Matching ----

	/** Does the item match the search (name, id, custom name or an enchantment)? */
	public static boolean matches(Item item, String query) {
		if (query.isEmpty()) {
			return true;
		}
		if (name(item.id).toLowerCase(Locale.ROOT).contains(query) || item.id.contains(query.replace(' ', '_'))) {
			return true;
		}
		if (item.n != null && item.n.toLowerCase(Locale.ROOT).contains(query)) {
			return true;
		}
		if (item.e != null) {
			for (String enchant : item.e) {
				if (enchantLabel(enchant).toLowerCase(Locale.ROOT).contains(query)) {
					return true;
				}
			}
		}
		return false;
	}

	public static boolean matches(Offer offer, String query) {
		return offer.r != null && matches(offer.r, query) || offer.a != null && matches(offer.a, query) || offer.b != null && matches(offer.b, query);
	}

	/** Plain enough to count as {@code id} in a recipe: same item, no custom model (a renamed one still crafts). */
	private static boolean usable(Item item, String id) {
		return item.id.equals(id) && item.m == null;
	}

	/** Key -> total count over the matching hits, biggest first. */
	public Map<String, Integer> totals(String query) {
		Map<String, Integer> totals = new HashMap<>();
		for (Hit hit : hits) {
			if (matches(hit.item(), query)) {
				totals.merge(hit.item().key(), hit.item().c, Integer::sum);
			}
		}
		Map<String, Integer> sorted = new LinkedHashMap<>();
		totals.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(e -> keyName(e.getKey())))
				.forEach(e -> sorted.put(e.getKey(), e.getValue()));
		return sorted;
	}

	/** A stored item of this key (for its model / enchantments when drawing), or null. */
	public Item sample(String key) {
		for (Hit hit : hits) {
			if (hit.item().key().equals(key)) {
				return hit.item();
			}
		}
		return null;
	}

	public int count(String key) {
		int total = 0;
		for (Hit hit : hits) {
			if (hit.item().key().equals(key)) {
				total += hit.item().c;
			}
		}
		return total;
	}

	/** How many of {@code id} you could use in a recipe (yours: containers, ender chest, inventory). */
	public int usableCount(String id) {
		int total = 0;
		for (Hit hit : hits) {
			if (hit.owner() == null && usable(hit.item(), id)) {
				total += hit.item().c;
			}
		}
		return total;
	}

	/** Where {@code key} is, nearest first (inventory, ender chest, containers by distance, then your party). */
	public List<Hit> where(String key) {
		List<Hit> list = new ArrayList<>();
		for (Hit hit : hits) {
			if (hit.item().key().equals(key)) {
				list.add(hit);
			}
		}
		list.sort(Comparator.comparingDouble(this::rank));
		return list;
	}

	/** The nearest usable {@code id} of yours, or null. */
	public Hit nearestUsable(String id) {
		Hit best = null;
		for (Hit hit : hits) {
			if (hit.owner() == null && usable(hit.item(), id) && (best == null || rank(hit) < rank(best))) {
				best = hit;
			}
		}
		return best;
	}

	public double rank(Hit hit) {
		return switch (hit.where()) {
			case INVENTORY -> -2;
			case ENDER -> -1;
			case CONTAINER -> distance(hit.container().dim, hit.container().x + 0.5, hit.container().y + 0.5, hit.container().z + 0.5);
			case PARTY_INVENTORY, PARTY_ENDER -> 2e9;
		};
	}

	/** Trades whose result is {@code key}, nearest first. */
	public List<TradeHit> sellers(String key) {
		List<TradeHit> list = new ArrayList<>();
		for (TradeHit trade : trades) {
			if (trade.offer().r != null && trade.offer().r.key().equals(key)) {
				list.add(trade);
			}
		}
		list.sort(Comparator.comparingDouble(t -> distance(t.trader().dim, t.trader().x, t.trader().y, t.trader().z)));
		return list;
	}

	/** The nearest recorded block of this station type, or null. */
	public Station nearestStation(String block) {
		return nearestStation(block, null);
	}

	private Station nearestStation(String block, double[] from) {
		if (world == null || block == null) {
			return null;
		}
		Station best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Station station : world.stations.values()) {
			if (station.block.equals(block)) {
				double d = from == null ? distance(station.dim, station.x + 0.5, station.y + 0.5, station.z + 0.5)
						: here(station.dim) ? dist(from, station.x + 0.5, station.y + 0.5, station.z + 0.5) : 1e9;
				if (d < bestDistance) {
					bestDistance = d;
					best = station;
				}
			}
		}
		return best;
	}

	// ---- Distances ----

	private boolean here(String dim) {
		return here && dim != null && dim.equals(StorageStore.here());
	}

	private static double dist(double[] a, double x, double y, double z) {
		double dx = x - a[0];
		double dy = y - a[1];
		double dz = z - a[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** Blocks from you; huge for another dimension / world so those sort last. */
	public double distance(String dim, double x, double y, double z) {
		double[] me = StorageMc.playerPos();
		if (me == null || !here(dim)) {
			return 1e9;
		}
		return dist(me, x, y, z);
	}

	/** "23m", "Nether", "other world". */
	public String distanceLabel(String dim, double x, double y, double z) {
		if (!here) {
			return "other world";
		}
		double d = distance(dim, x, y, z);
		if (d >= 1e9) {
			return StorageStore.dimensionLabel(dim);
		}
		return Math.round(d) + "m";
	}

	// ---- Routes ----

	/** One place to take from: its position and what it holds of the wanted items. */
	private static final class Source {
		final Stop where;
		final Map<String, Integer> has = new HashMap<>();

		Source(Stop where) {
			this.where = where;
		}
	}

	/**
	 * The shortest walk that collects {@code amounts[i]} of any of {@code options[i]} - what you carry first, then
	 * containers (and your ender chest at the nearest ender chest block) in this dimension. Greedy: each next stop is
	 * the one with the best "metres per share of what's still missing", so one far chest holding everything beats
	 * three near ones holding a bit each; the chosen stops are then walked nearest-first. Null when you're not in this
	 * world.
	 */
	public Route route(List<List<String>> options, List<Integer> amounts, List<String> labels) {
		double[] me = StorageMc.playerPos();
		if (!here || me == null || world == null) {
			return null;
		}
		int n = options.size();
		int[] remaining = new int[n];
		for (int i = 0; i < n; i++) {
			remaining[i] = Math.max(0, amounts.get(i));
		}
		// What you already carry.
		Map<String, Integer> carried = new HashMap<>();
		for (Hit hit : hits) {
			if (hit.where() == Where.INVENTORY && hit.item().m == null) {
				carried.merge(hit.item().id, hit.item().c, Integer::sum);
			}
		}
		Map<String, Integer> fromInventory = new LinkedHashMap<>();
		take(options, remaining, carried, fromInventory);

		// Containers here, plus the ender chest at the nearest ender chest block.
		String dim = StorageStore.here();
		List<Source> sources = new ArrayList<>();
		Map<Container, Source> byContainer = new HashMap<>();
		Source ender = null;
		Station enderBlock = nearestStation("minecraft:ender_chest", me);
		for (Hit hit : hits) {
			if (hit.item().m != null) {
				continue;
			}
			Source source = null;
			if (hit.where() == Where.CONTAINER && dim.equals(hit.container().dim)) {
				Container c = hit.container();
				source = byContainer.computeIfAbsent(c, k -> {
					Source s = new Source(new Stop(StorageHighlight.describe(k.block), k.dim, k.x + 0.5, k.y + 0.5, k.z + 0.5, k, false, new LinkedHashMap<>()));
					sources.add(s);
					return s;
				});
			} else if (hit.where() == Where.ENDER && enderBlock != null) {
				if (ender == null) {
					ender = new Source(new Stop(name("minecraft:ender_chest"), enderBlock.dim, enderBlock.x + 0.5, enderBlock.y + 0.5, enderBlock.z + 0.5,
							null, true, new LinkedHashMap<>()));
					sources.add(ender);
				}
				source = ender;
			}
			if (source != null) {
				source.has.merge(hit.item().id, hit.item().c, Integer::sum);
			}
		}

		List<Source> chosen = new ArrayList<>();
		double[] at = me.clone();
		while (total(remaining) > 0) {
			Source best = null;
			double bestScore = Double.MAX_VALUE;
			int need = total(remaining);
			for (Source source : sources) {
				if (chosen.contains(source)) {
					continue;
				}
				int covered = covers(options, remaining, source.has);
				if (covered <= 0) {
					continue;
				}
				double score = (dist(at, source.where.x(), source.where.y(), source.where.z()) + 2) / ((double) covered / need);
				if (score < bestScore) {
					bestScore = score;
					best = source;
				}
			}
			if (best == null) {
				break;
			}
			take(options, remaining, best.has, best.where.take());
			chosen.add(best);
			at = new double[] {best.where.x(), best.where.y(), best.where.z()};
		}

		// Walk the chosen stops nearest-first from where you stand.
		List<Stop> stops = new ArrayList<>();
		double length = 0;
		at = me.clone();
		List<Source> left = new ArrayList<>(chosen);
		while (!left.isEmpty()) {
			Source next = null;
			double nextDistance = Double.MAX_VALUE;
			for (Source source : left) {
				double d = dist(at, source.where.x(), source.where.y(), source.where.z());
				if (d < nextDistance) {
					nextDistance = d;
					next = source;
				}
			}
			left.remove(next);
			stops.add(next.where);
			length += nextDistance;
			at = new double[] {next.where.x(), next.where.y(), next.where.z()};
		}

		Map<String, Integer> missing = new LinkedHashMap<>();
		List<String> party = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (remaining[i] <= 0) {
				continue;
			}
			missing.put(labels.get(i), remaining[i]);
			Map<String, Integer> mates = new LinkedHashMap<>();
			for (Hit hit : hits) {
				if (hit.owner() != null && hit.item().m == null && options.get(i).contains(hit.item().id)) {
					mates.merge(hit.owner(), hit.item().c, Integer::sum);
				}
			}
			for (Map.Entry<String, Integer> mate : mates.entrySet()) {
				party.add(mate.getKey() + " has " + mate.getValue() + " " + labels.get(i));
			}
		}
		return new Route(stops, fromInventory, missing, length, party);
	}

	private static int total(int[] remaining) {
		int sum = 0;
		for (int r : remaining) {
			sum += r;
		}
		return sum;
	}

	/** How much of what's still missing {@code has} could cover (shared items counted once). */
	private static int covers(List<List<String>> options, int[] remaining, Map<String, Integer> has) {
		Map<String, Integer> left = new HashMap<>(has);
		int covered = 0;
		for (int i = 0; i < options.size(); i++) {
			int want = remaining[i];
			for (String option : options.get(i)) {
				if (want <= 0) {
					break;
				}
				int available = left.getOrDefault(option, 0);
				int use = Math.min(want, available);
				if (use > 0) {
					left.put(option, available - use);
					want -= use;
					covered += use;
				}
			}
		}
		return covered;
	}

	/** Takes what's still missing out of {@code has} (mutated), noting it in {@code into}. */
	private static void take(List<List<String>> options, int[] remaining, Map<String, Integer> has, Map<String, Integer> into) {
		for (int i = 0; i < options.size(); i++) {
			for (String option : options.get(i)) {
				if (remaining[i] <= 0) {
					break;
				}
				int available = has.getOrDefault(option, 0);
				int use = Math.min(remaining[i], available);
				if (use > 0) {
					has.put(option, available - use);
					remaining[i] -= use;
					into.merge(option, use, Integer::sum);
				}
			}
		}
	}
}
