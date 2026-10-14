package net.veloclient.velo.client.cosmetics.aura;

import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Auras: animated effects around the player ({@link AuraEffects}), Velo Store items {@code aura_<id>}.
 * The animations are code, so only the five known ones exist; the server's catalog says which are
 * for sale and at what price. Which one is equipped and how each is worn ({@link AuraEffects.Settings})
 * is saved in {@code config/cosmetics-aura.json} and goes out with the Velo Network heartbeat, so
 * other Velo Client players see it too.
 */
public final class AuraManager {

	public static final String ITEM_PREFIX = "aura_";

	/** The five auras, in the order the Store shows them. */
	public static final List<String> IDS = List.of("orbit", "ember", "frost", "void", "storm", "matrix", "inferno", "bloom", "heartbeat", "aurora");

	public record AuraType(String id, String name, String description, int price, boolean inStore, int accent) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}

		public AuraEffects.Kind kind() {
			return AuraEffects.kind(id);
		}
	}

	public record Worn(String id, AuraEffects.Settings settings) {
	}

	private record EquipState(String auraId, Map<String, AuraEffects.Settings> perAura) {
	}

	private static final Map<String, String[]> FALLBACK = Map.of(
			"orbit", new String[] {"Celestial Orbit", "Three tilted rings of stars circle you, each trailing a comet tail."},
			"ember", new String[] {"Ember Spiral", "Embers spiral up from your feet in a double helix and drift away as you move."},
			"frost", new String[] {"Frost Halo", "A crown of glittering ice above your head and soft snow falling all around you."},
			"void", new String[] {"Void Rift", "A pulsing rune circle at your feet pulls dark wisps in from all around."},
			"storm", new String[] {"Storm Sparks", "Lightning arcs crackle around your body and throw off bright sparks."},
			"matrix", new String[] {"Code Rain", "Glowing green code rains down a cylinder of light around you - digits and glyphs flicker as they fall."},
			"inferno", new String[] {"Inferno", "A roaring column of fire licks up around you, white-hot at your feet, throwing sparks into the air."},
			"bloom", new String[] {"Bloom", "Vines spiral up around you, leaf out and burst into glowing flowers while fireflies blink and petals drift down."},
			"heartbeat", new String[] {"Heartbeat", "A ring of hearts pulses to a heartbeat around you as more hearts float up and fade."},
			"aurora", new String[] {"Aurora", "Northern-lights curtains wave around you in green, cyan and violet, with stars rising through them."});

	private static boolean loaded;
	private static String equippedId;
	private static final Map<String, AuraEffects.Settings> SETTINGS = new HashMap<>();
	private static String previewId;
	private static boolean previewActive;
	private static final Map<Object, AuraEffects.Emitter> EMITTERS = new HashMap<>();

	private AuraManager() {
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		EquipState state = net.veloclient.velo.config.ConfigManager.load("cosmetics-aura", EquipState.class, new EquipState(null, null));
		equippedId = state != null && state.auraId() != null && IDS.contains(state.auraId()) ? state.auraId() : null;
		if (state != null && state.perAura() != null) {
			state.perAura().forEach((id, s) -> {
				if (s != null && IDS.contains(id)) {
					SETTINGS.put(id, new AuraEffects.Settings(s.size(), s.speed(), s.density(), s.color()));
				}
			});
		}
	}

	private static void save() {
		net.veloclient.velo.config.ConfigManager.save("cosmetics-aura", new EquipState(equippedId, new HashMap<>(SETTINGS)));
	}

	// ---- Catalog ----

	/** Every aura with what the server says about it (name, price, whether it's for sale). */
	public static List<AuraType> catalog() {
		List<AuraType> out = new ArrayList<>();
		for (String id : IDS) {
			var item = CosmeticsCatalog.item(ITEM_PREFIX + id);
			String[] fallback = FALLBACK.get(id);
			int accent = 0xFF000000 | AuraEffects.accent(AuraEffects.kind(id), AuraEffects.Settings.DEFAULT);
			out.add(item.map(i -> new AuraType(id, i.name(), i.description(), i.price(), i.inStore(), i.accent() != null ? i.accent() : accent))
					.orElse(new AuraType(id, fallback[0], fallback[1], 2000, false, accent)));
		}
		return out;
	}

	/** What the Store sells right now. */
	public static List<AuraType> forSale() {
		return catalog().stream().filter(AuraType::inStore).toList();
	}

	public static Optional<AuraType> type(String id) {
		return catalog().stream().filter(t -> t.id().equals(id)).findFirst();
	}

	// ---- Owning and equipping ----

	public static boolean owns(String id) {
		if (id == null) {
			return false;
		}
		load();
		return net.veloclient.velo.client.network.StoreClient.owns(ITEM_PREFIX + id)
				|| !net.veloclient.velo.client.network.StoreClient.walletLoaded() && id.equals(equippedId);
	}

	public static synchronized String equippedId() {
		load();
		return equippedId;
	}

	/** Equips an aura you own (null takes it off); false if you don't own it. */
	public static synchronized boolean equip(String id) {
		load();
		if (id != null && (!IDS.contains(id) || !owns(id))) {
			return false;
		}
		equippedId = id;
		save();
		return true;
	}

	public static synchronized AuraEffects.Settings settings(String id) {
		load();
		AuraEffects.Settings s = id == null ? null : SETTINGS.get(id);
		return s != null ? s : AuraEffects.Settings.DEFAULT;
	}

	public static synchronized void setSettings(String id, AuraEffects.Settings value) {
		load();
		if (id == null) {
			return;
		}
		if (value == null || value.equals(AuraEffects.Settings.DEFAULT)) {
			SETTINGS.remove(id);
		} else {
			SETTINGS.put(id, value);
		}
		save();
	}

	/** Store / Cosmetics screens: show {@code id} (null = none) on you instead of the equipped aura until cleared. */
	public static void setPreview(String id) {
		previewId = id;
		previewActive = true;
	}

	public static void clearPreview() {
		previewActive = false;
		previewId = null;
	}

	/** What the local player shows right now. */
	public static Worn wornByLocalPlayer() {
		String id = previewActive ? previewId : equippedId();
		if (id == null || !previewActive && !owns(id)) {
			return null;
		}
		return new Worn(id, settings(id));
	}

	/** For the heartbeat: "id;size;speed;density;color", or null. */
	public static String networkValue() {
		String id = equippedId();
		return id == null || !owns(id) ? null : id + ";" + settings(id).encode();
	}

	/** Another player's {@link #networkValue()}. */
	public static Worn parseNetworkValue(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String[] parts = value.split(";");
		if (!IDS.contains(parts[0])) {
			return null;
		}
		return new Worn(parts[0], parts.length >= 5 ? AuraEffects.Settings.decode(parts, 1) : AuraEffects.Settings.DEFAULT);
	}

	// ---- Emitters ----

	/** The running aura for one player (or preview), made new when the aura changes. Render thread only. */
	public static synchronized AuraEffects.Emitter emitter(Object key, AuraEffects.Kind kind) {
		AuraEffects.Emitter emitter = EMITTERS.get(key);
		if (emitter == null || emitter.kind() != kind) {
			emitter = new AuraEffects.Emitter(kind);
			EMITTERS.put(key, emitter);
		}
		// Forget players not drawn for a while.
		if (EMITTERS.size() > 8) {
			long now = System.nanoTime();
			for (Iterator<AuraEffects.Emitter> it = EMITTERS.values().iterator(); it.hasNext(); ) {
				AuraEffects.Emitter e = it.next();
				if (e != emitter && now - e.lastNanos() > 5_000_000_000L) {
					it.remove();
				}
			}
		}
		return emitter;
	}
}
