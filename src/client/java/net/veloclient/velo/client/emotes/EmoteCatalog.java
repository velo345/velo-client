package net.veloclient.velo.client.emotes;

import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;
import net.veloclient.velo.client.network.StoreClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Emotes as Velo Store items ({@code emote_<id>}): what the server's catalog says about each (name, text, price,
 * whether it's for sale), whether you own it, and your emote wheel - eight slots, saved in
 * {@code config/cosmetics-emotes.json}. Emotes are bought, never free; the wheel only ever offers ones you own.
 */
public final class EmoteCatalog {

	public static final String ITEM_PREFIX = "emote_";
	public static final int SLOTS = 8;
	private static final int DEFAULT_ACCENT = 0xFF8B7CF6;

	public record EmoteType(String id, String name, String description, int price, boolean inStore, int accent) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}
	}

	/** {@code known}: owned emotes that were already offered to the wheel (so a slot you cleared stays empty). */
	private record WheelState(String[] slots, String[] known) {
	}

	private static String[] slots;
	private static final java.util.Set<String> KNOWN = new java.util.LinkedHashSet<>();

	private EmoteCatalog() {
	}

	// ---- Catalog ----

	/** Every emote known: the server's (in its order), then any bundled one it didn't list. */
	public static List<EmoteType> catalog() {
		Map<String, EmoteType> out = new LinkedHashMap<>();
		for (CosmeticsCatalog.Item item : CosmeticsCatalog.items()) {
			add(out, item);
		}
		for (CosmeticsCatalog.Item item : CosmeticsCatalog.lookedUp()) {
			add(out, item);
		}
		EmoteLibrary.bundledNames().forEach((id, name) -> out.putIfAbsent(id, new EmoteType(id, name, "", 0, false, DEFAULT_ACCENT)));
		return new ArrayList<>(out.values());
	}

	private static void add(Map<String, EmoteType> out, CosmeticsCatalog.Item item) {
		if ("emote".equals(item.type()) && item.id().startsWith(ITEM_PREFIX)) {
			String id = item.id().substring(ITEM_PREFIX.length());
			out.putIfAbsent(id, new EmoteType(id, item.name(), item.description(), item.price(), item.inStore(),
					item.accent() != null ? item.accent() : DEFAULT_ACCENT));
		}
	}

	public static List<EmoteType> forSale() {
		return catalog().stream().filter(EmoteType::inStore).toList();
	}

	public static Optional<EmoteType> type(String id) {
		return catalog().stream().filter(t -> t.id().equals(id)).findFirst();
	}

	public static String name(String id) {
		return id == null ? "" : type(id).map(EmoteType::name).orElse(id);
	}

	public static int accent(String id) {
		return type(id).map(EmoteType::accent).orElse(DEFAULT_ACCENT);
	}

	public static boolean owns(String id) {
		return id != null && StoreClient.owns(ITEM_PREFIX + id);
	}

	/** The emotes you own, in catalog order. */
	public static List<String> owned() {
		return catalog().stream().map(EmoteType::id).filter(EmoteCatalog::owns).toList();
	}

	// ---- The wheel ----

	private static synchronized String[] slots() {
		if (slots == null) {
			WheelState state = net.veloclient.velo.config.ConfigManager.load("cosmetics-emotes", WheelState.class, new WheelState(null, null));
			slots = state != null && state.slots() != null ? Arrays.copyOf(state.slots(), SLOTS) : new String[SLOTS];
			if (state != null && state.known() != null) {
				KNOWN.addAll(Arrays.asList(state.known()));
			}
		}
		return slots;
	}

	private static synchronized void save() {
		net.veloclient.velo.config.ConfigManager.save("cosmetics-emotes", new WheelState(slots().clone(), KNOWN.toArray(new String[0])));
	}

	/** What a wheel slot holds (null = empty, or an emote you no longer own). New emotes you own fill empty slots. */
	public static synchronized String slot(int index) {
		autoFill();
		String id = slots()[index];
		return owns(id) ? id : null;
	}

	/** Puts an emote in a slot (null clears it); an emote already elsewhere on the wheel moves here. */
	public static synchronized void setSlot(int index, String id) {
		String[] s = slots();
		if (id != null) {
			for (int i = 0; i < s.length; i++) {
				if (id.equals(s[i])) {
					s[i] = s[index];
				}
			}
		}
		s[index] = id;
		save();
	}

	/** Which slot holds this emote, or -1. */
	public static synchronized int slotOf(String id) {
		String[] s = slots();
		for (int i = 0; i < s.length; i++) {
			if (id != null && id.equals(s[i])) {
				return i;
			}
		}
		return -1;
	}

	/** An owned emote that isn't on the wheel yet goes into the first empty slot (just bought it: it's ready to use). */
	private static void autoFill() {
		if (!StoreClient.walletLoaded()) {
			return;
		}
		String[] s = slots();
		boolean changed = false;
		for (String id : owned()) {
			if (!KNOWN.add(id)) {
				continue;
			}
			changed = true;
			if (slotOf(id) >= 0) {
				continue;
			}
			for (int i = 0; i < s.length; i++) {
				if (s[i] == null || !owns(s[i])) {
					s[i] = id;
					break;
				}
			}
		}
		if (changed) {
			save();
		}
	}
}
