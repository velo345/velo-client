package net.veloclient.velo.dev.storage;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.veloclient.velo.dev.storage.StorageStore.Container;
import net.veloclient.velo.dev.storage.StorageStore.Item;
import net.veloclient.velo.dev.storage.StorageStore.Station;
import net.veloclient.velo.dev.storage.StorageStore.Trader;
import net.veloclient.velo.dev.storage.StorageStore.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fills Storage Memory while you play. A container is only remembered when its screen opens right after you
 * right-clicked a real container block whose size matches the screen - server menus (shops, /warp GUIs, crate
 * previews) open from commands, NPCs or signs and never pass that. On top, a screen full of priced / "click to buy"
 * items or filler panes is treated as a menu and skipped. Villager trades come from the merchant screen of the
 * villager you just right-clicked, minus server NPCs (Citizens UUIDs, renamed villagers, custom-named trade items).
 * Work stations are picked up from the blocks around you, and anything that's gone from where it was is forgotten.
 */
public final class StorageCapture {

	/** Opening a screen this long after the click still counts as "opened by that click". */
	private static final long CLICK_WINDOW_MS = 2500;
	private static final int SCAN_RADIUS = 8;
	private static final Pattern SHOP_WORDS = Pattern.compile(
			"(?i)(price|preis|\\bbuy\\b|kaufen|\\bsell\\b|verkauf|\\$\\s?\\d|\\d\\s?\\$|€|\\bcoins?\\b|münzen|balance|in stock|auf lager|purchase)");
	public static final Set<String> STATIONS = Set.of("minecraft:crafting_table", "minecraft:furnace", "minecraft:blast_furnace",
			"minecraft:smoker", "minecraft:stonecutter", "minecraft:smithing_table", "minecraft:anvil", "minecraft:chipped_anvil",
			"minecraft:damaged_anvil", "minecraft:grindstone", "minecraft:loom", "minecraft:cartography_table", "minecraft:fletching_table",
			"minecraft:enchanting_table", "minecraft:brewing_stand", "minecraft:lectern", "minecraft:composter", "minecraft:cauldron",
			"minecraft:water_cauldron", "minecraft:lava_cauldron", "minecraft:powder_snow_cauldron", "minecraft:campfire",
			"minecraft:soul_campfire", "minecraft:crafter", "minecraft:beacon", "minecraft:ender_chest");

	private static int[] lastBlock;
	private static long lastBlockTime;
	private static StorageMc.EntityInfo lastTrader;
	private static long lastTraderTime;

	/** The screen being recorded right now, and what it belongs to. */
	private static Screen session;
	private static int[] sessionPos;
	private static boolean sessionEnder;
	private static StorageMc.EntityInfo sessionTrader;
	private static int sessionTicks;
	/** Last thing that was skipped and why (shown in the Storage menu's footer). */
	private static String lastSkip = "";
	private static int scanTicks;

	private StorageCapture() {
	}

	public static String lastSkip() {
		return lastSkip;
	}

	public static void register() {
		StorageMc.onInteract(pos -> {
			lastBlock = pos;
			lastBlockTime = System.currentTimeMillis();
		}, trader -> {
			lastTrader = trader;
			lastTraderTime = System.currentTimeMillis();
		});
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			// A re-init (resize) of the screen we're already recording keeps its session; per-screen events are reset
			// on every init, so the close hook is registered again either way.
			if (screen != session && StorageMemoryModule.active()) {
				begin(screen);
			}
			ScreenEvents.remove(screen).register(StorageCapture::end);
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	private static boolean recentClick() {
		return lastBlock != null && System.currentTimeMillis() - lastBlockTime < CLICK_WINDOW_MS;
	}

	private static void begin(Screen screen) {
		if (StorageMc.isMerchantScreen(screen)) {
			if (lastTrader == null || System.currentTimeMillis() - lastTraderTime > CLICK_WINDOW_MS) {
				return;
			}
			StorageMc.EntityInfo trader = lastTrader;
			lastTrader = null;
			if (trader.npc()) {
				lastSkip = "Skipped a server NPC trader";
				return;
			}
			start(screen, null, false, trader);
			return;
		}
		if (!StorageMc.isContainerScreen(screen)) {
			return;
		}
		int slots = StorageMc.containerSlots(screen).size();
		if (recentClick()) {
			int[] pos = lastBlock;
			lastBlock = null;
			if (StorageMc.isEnderChest(pos[0], pos[1], pos[2])) {
				if (slots % 9 == 0 && slots > 0) {
					start(screen, pos, true, null);
				}
				return;
			}
			int size = StorageMc.containerSize(pos[0], pos[1], pos[2]);
			if (size <= 0) {
				lastSkip = "Skipped a menu that wasn't opened from a container block";
				return;
			}
			int[] other = StorageMc.otherChestHalf(pos[0], pos[1], pos[2]);
			if (slots != size && !(other != null && slots == size * 2)) {
				lastSkip = "Skipped a menu whose size doesn't match the block (" + slots + " vs " + size + " slots)";
				return;
			}
			start(screen, pos, false, null);
			return;
		}
		// /ec and similar: the server opened your real ender chest (vanilla's own title key, plugins use literal titles).
		if ("container.enderchest".equals(StorageMc.titleKey(screen)) && slots % 9 == 0 && slots > 0) {
			start(screen, null, true, null);
		}
	}

	private static void start(Screen screen, int[] pos, boolean ender, StorageMc.EntityInfo trader) {
		session = screen;
		sessionPos = pos;
		sessionEnder = ender;
		sessionTrader = trader;
		sessionTicks = 0;
		StorageHighlight.onContainerOpened(pos);
	}

	private static void end(Screen screen) {
		if (screen != session) {
			return;
		}
		// The screen's handler still holds the final contents here.
		if (sessionTicks >= 2) {
			snapshot();
		}
		session = null;
		sessionTrader = null;
		sessionPos = null;
		StorageStore.flush(false);
	}

	private static void tick() {
		if (session != null) {
			sessionTicks++;
			// Contents arrive a moment after the screen opens; keep the record current while it's open.
			if (sessionTicks == 3 || sessionTicks % 10 == 0) {
				snapshot();
			}
		}
		if (!StorageMemoryModule.active() || !StorageMc.inWorld()) {
			return;
		}
		if (++scanTicks >= 40) {
			scanTicks = 0;
			scan();
		}
		StorageStore.flush(false);
	}

	private static void snapshot() {
		World world = StorageStore.current();
		if (world == null || session == null) {
			return;
		}
		String dim = StorageStore.here();
		long now = System.currentTimeMillis();
		if (sessionTrader != null) {
			List<StorageMc.Trade> trades = StorageMc.trades(session);
			if (trades.isEmpty()) {
				return;
			}
			StorageMc.EntityInfo info = sessionTrader;
			// Plugin shops (Shopkeepers & co.) open their own merchant screen: no level bar, or a villager without a
			// job that still trades. Names and custom items don't matter - your renamed villagers count.
			boolean jobless = info.profession().endsWith(":none") || info.profession().endsWith(":nitwit");
			if (!info.wandering() && (!StorageMc.merchantLeveled(session) || jobless)) {
				lastSkip = "Skipped a plugin shop trader";
				session = null;
				return;
			}
			Trader trader = new Trader();
			trader.uuid = info.uuid();
			trader.dim = dim;
			trader.x = info.x();
			trader.y = info.y();
			trader.z = info.z();
			trader.profession = info.profession();
			trader.level = info.level();
			trader.name = info.named() ? info.name() : null;
			trader.wandering = info.wandering();
			trader.time = now;
			for (StorageMc.Trade trade : trades) {
				StorageStore.Offer offer = new StorageStore.Offer();
				offer.a = item(trade.first(), 0);
				offer.b = StorageMc.isEmpty(trade.second()) ? null : item(trade.second(), 0);
				offer.r = item(trade.result(), 0);
				offer.uses = trade.uses();
				offer.max = trade.maxUses();
				trader.offers.add(offer);
			}
			world.traders.put(trader.uuid, trader);
			StorageStore.markCurrentDirty();
			return;
		}
		List<ItemStack> stacks = StorageMc.containerSlots(session);
		String menu = menuReason(stacks);
		if (menu != null) {
			lastSkip = "Skipped a container that looks like a server menu (" + menu + ")";
			if (sessionPos != null) {
				StorageStore.removeContainer(world, StorageStore.posKey(dim, sessionPos[0], sessionPos[1], sessionPos[2]));
			}
			session = null;
			return;
		}
		List<Item> items = new ArrayList<>();
		for (ItemStack stack : stacks) {
			if (!StorageMc.isEmpty(stack)) {
				items.add(item(stack, 0));
			}
		}
		if (sessionEnder) {
			world.ender = items;
			world.enderTime = now;
			StorageStore.markCurrentDirty();
			return;
		}
		int[] pos = sessionPos;
		int[] other = StorageMc.otherChestHalf(pos[0], pos[1], pos[2]);
		// One record per double chest, kept under the half you clicked; drop a stale one under the other half.
		if (other != null) {
			world.containers.remove(StorageStore.posKey(dim, other[0], other[1], other[2]));
		}
		Container container = new Container();
		container.dim = dim;
		container.x = pos[0];
		container.y = pos[1];
		container.z = pos[2];
		container.other = other;
		container.block = StorageMc.blockId(pos[0], pos[1], pos[2]);
		container.time = now;
		container.items = items;
		String key = StorageStore.posKey(dim, pos[0], pos[1], pos[2]);
		world.containers.put(key, container);
		world.gone.remove(key);
		StorageStore.markCurrentDirty();
	}

	/**
	 * Why these slots look like a plugin menu rather than storage, or null. Only strong signs count - renamed items and
	 * custom server items (names, lore, models) are normal storage.
	 */
	private static String menuReason(List<ItemStack> stacks) {
		int priced = 0;
		int fillers = 0;
		int used = 0;
		for (ItemStack stack : stacks) {
			if (StorageMc.isEmpty(stack)) {
				continue;
			}
			used++;
			List<String> lore = StorageMc.lore(stack);
			String custom = StorageMc.customName(stack);
			if (lore.stream().anyMatch(line -> SHOP_WORDS.matcher(line).find())) {
				priced++;
			}
			if (custom != null && custom.isBlank() && StorageMc.itemId(stack).endsWith("glass_pane")) {
				fillers++;
			}
		}
		if (priced >= 3 && priced * 3 >= used) {
			return priced + " priced items";
		}
		if (fillers >= 4) {
			return "filler panes";
		}
		return null;
	}

	static Item item(ItemStack stack, int depth) {
		Item item = new Item();
		item.id = StorageMc.itemId(stack);
		item.c = StorageMc.count(stack);
		item.n = StorageMc.customName(stack);
		item.m = StorageMc.itemModel(stack);
		List<String> enchants = StorageMc.enchants(stack);
		item.e = enchants.isEmpty() ? null : enchants;
		if (depth < 3) {
			List<Item> inside = new ArrayList<>();
			for (ItemStack nested : StorageMc.nested(stack)) {
				if (!StorageMc.isEmpty(nested)) {
					inside.add(item(nested, depth + 1));
				}
			}
			item.in = inside.isEmpty() ? null : inside;
		}
		return item;
	}

	/** Your inventory as stored items (live, never saved). */
	public static List<Item> inventory() {
		List<Item> items = new ArrayList<>();
		for (ItemStack stack : StorageMc.playerInventory()) {
			if (!StorageMc.isEmpty(stack)) {
				items.add(item(stack, 0));
			}
		}
		return items;
	}

	/** Forgets containers / stations near you that aren't there any more, and picks up work stations around you. */
	private static void scan() {
		World world = StorageStore.current();
		double[] me = StorageMc.playerPos();
		String dim = StorageStore.here();
		if (world == null || me == null || dim == null) {
			return;
		}
		int px = (int) Math.floor(me[0]);
		int py = (int) Math.floor(me[1]);
		int pz = (int) Math.floor(me[2]);
		boolean changed = false;
		for (Iterator<Map.Entry<String, Container>> it = world.containers.entrySet().iterator(); it.hasNext(); ) {
			Container container = it.next().getValue();
			if (!dim.equals(container.dim) || Math.abs(container.x - px) > 12 || Math.abs(container.y - py) > 12 || Math.abs(container.z - pz) > 12
					|| !StorageMc.loaded(container.x, container.y, container.z)) {
				continue;
			}
			if (StorageMc.containerSize(container.x, container.y, container.z) < 0) {
				it.remove();
				world.gone.put(StorageStore.posKey(container.dim, container.x, container.y, container.z), System.currentTimeMillis());
				changed = true;
			}
		}
		long now = System.currentTimeMillis();
		for (int x = px - SCAN_RADIUS; x <= px + SCAN_RADIUS; x++) {
			for (int z = pz - SCAN_RADIUS; z <= pz + SCAN_RADIUS; z++) {
				if (!StorageMc.loaded(x, py, z)) {
					continue;
				}
				for (int y = py - SCAN_RADIUS; y <= py + SCAN_RADIUS; y++) {
					String key = StorageStore.posKey(dim, x, y, z);
					String block = StorageMc.blockId(x, y, z);
					String station = block == null ? null : normalizeStation(block);
					Station known = world.stations.get(key);
					if (station == null) {
						if (known != null) {
							world.stations.remove(key);
							changed = true;
						}
						continue;
					}
					if (known == null || !station.equals(known.block)) {
						Station added = new Station();
						added.dim = dim;
						added.x = x;
						added.y = y;
						added.z = z;
						added.block = station;
						added.time = now;
						world.stations.put(key, added);
						changed = true;
					}
				}
			}
		}
		if (changed) {
			StorageStore.markCurrentDirty();
		}
	}

	private static String normalizeStation(String block) {
		if (!STATIONS.contains(block)) {
			return null;
		}
		if (block.endsWith("_cauldron")) {
			return "minecraft:cauldron";
		}
		if (block.endsWith("anvil")) {
			return "minecraft:anvil";
		}
		return block;
	}
}
