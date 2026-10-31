package net.veloclient.velo.client.cosmetics.killfx;

import net.veloclient.velo.client.cosmetics.CosmeticsCatalog;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Store's kill effects ({@link KillFxEffects}), items {@code killfx_<id>}: what the server sells, which one you
 * play and in which colour (saved in {@code config/cosmetics-killfx.json}, shared with the launcher). The Kill Effects
 * module decides when a kill counts; with one of these equipped it plays that instead of its free vanilla effects.
 */
public final class KillFxManager {

	public static final String ITEM_PREFIX = "killfx_";

	/** The kill effects, in the order the Store shows them. */
	public static final List<String> IDS = List.of("shatter", "orbital", "blackhole", "phoenix", "reaper", "glitch", "supernova", "frostbite",
			"meteor", "tornado", "grave", "thunder", "shadow", "katana", "jackpot");

	public record KillFxType(String id, String name, String description, int price, boolean inStore, int accent) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}

		public KillFxEffects.Kind kind() {
			return KillFxEffects.kind(id);
		}
	}

	/** The free kill effects (vanilla particles and sounds, played by the Kill Effects module): id -> its name there. */
	public static final Map<String, String> FREE = new java.util.LinkedHashMap<>();

	static {
		FREE.put("free_lightning", "Lightning Strike");
		FREE.put("free_burst", "Color Burst");
		FREE.put("free_soul", "Soul Implosion");
		FREE.put("free_totem", "Totem Shatter");
		FREE.put("free_firework", "Firework Salute");
		FREE.put("free_void", "Void Pull");
		FREE.put("free_surge", "Electric Surge");
		FREE.put("free_confetti", "Confetti Rain");
	}

	private record EquipState(String effectId, Map<String, Integer> colors, Float volume, Integer freeColor) {
	}

	private static final Map<String, String[]> FALLBACK = Map.ofEntries(
			Map.entry("shatter", new String[] {"Shatter", "Your victim bursts into glowing crystal shards that fly out, bounce and fade."}),
			Map.entry("orbital", new String[] {"Orbital Strike", "A target ring locks on, then a beam from the sky slams down and scorches the ground."}),
			Map.entry("blackhole", new String[] {"Black Hole", "A black hole opens where they fell, swallows everything around it and collapses with a bang."}),
			Map.entry("phoenix", new String[] {"Phoenix", "A column of fire roars up and a phoenix bursts out of it, wings beating, soaring away in sparks."}),
			Map.entry("reaper", new String[] {"Soul Reaper", "A scythe slashes twice and the soul is torn out of its chains, rising away."}),
			Map.entry("glitch", new String[] {"Glitch", "The body breaks up into pixels that glitch and float away, with binary rain and scan lines."}),
			Map.entry("supernova", new String[] {"Supernova", "A star collapses, then explodes - rays, shock shells and a glowing nebula."}),
			Map.entry("frostbite", new String[] {"Frostbite", "Ice spikes erupt around the body, freeze it in a crystal and shatter."}),
			Map.entry("meteor", new String[] {"Meteor", "A burning meteor streaks down and smashes a crater, throwing rocks and smoke."}),
			Map.entry("tornado", new String[] {"Tornado", "A whirlwind spins up out of the ground, lifting debris, and blows apart."}),
			Map.entry("grave", new String[] {"R.I.P.", "A gravestone rises out of the ground among candles and flowers, and a bell tolls."}),
			Map.entry("thunder", new String[] {"Thunder God", "Three forked lightning bolts strike and arcs crawl over the ground."}),
			Map.entry("shadow", new String[] {"Shadow", "Shadow tendrils burst out of the ground and drag them down into dark smoke, red eyes watching."}),
			Map.entry("katana", new String[] {"Katana", "Three katana slashes cross in a flash and the body bursts into cherry blossoms."}),
			Map.entry("jackpot", new String[] {"Jackpot", "7-7-7 pops up and a fountain of coins rains down."}));

	private static boolean loaded;
	private static String equippedId;
	private static final Map<String, Integer> COLORS = new HashMap<>();
	private static float volume = 1f;
	private static int freeColor = 0xFFFF3030;

	private KillFxManager() {
	}

	private static synchronized void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		EquipState state = net.veloclient.velo.config.ConfigManager.load("cosmetics-killfx", EquipState.class, new EquipState(null, null, null, null));
		equippedId = state != null && state.effectId() != null && (IDS.contains(state.effectId()) || FREE.containsKey(state.effectId())) ? state.effectId() : null;
		if (state != null && state.volume() != null && Float.isFinite(state.volume())) {
			volume = Math.max(0, Math.min(1.5f, state.volume()));
		}
		if (state != null && state.freeColor() != null) {
			freeColor = state.freeColor();
		}
		if (state != null && state.colors() != null) {
			state.colors().forEach((id, c) -> {
				if (c != null && IDS.contains(id)) {
					COLORS.put(id, c);
				}
			});
		}
	}

	private static void save() {
		net.veloclient.velo.client.cosmetics.Loadouts.changed();
		net.veloclient.velo.config.ConfigManager.save("cosmetics-killfx", new EquipState(equippedId, new HashMap<>(COLORS), volume, freeColor));
	}

	// ---- Catalog ----

	public static List<KillFxType> catalog() {
		List<KillFxType> out = new ArrayList<>();
		for (String id : IDS) {
			var item = CosmeticsCatalog.item(ITEM_PREFIX + id);
			String[] fallback = FALLBACK.get(id);
			int accent = 0xFF000000 | KillFxEffects.accent(KillFxEffects.kind(id), AuraEffects.Settings.DEFAULT);
			out.add(item.map(i -> new KillFxType(id, i.name(), i.description(), i.price(), i.inStore(), i.accent() != null ? i.accent() : accent))
					.orElse(new KillFxType(id, fallback[0], fallback[1], 1800, false, accent)));
		}
		return out;
	}

	public static List<KillFxType> forSale() {
		return catalog().stream().filter(KillFxType::inStore).toList();
	}

	public static Optional<KillFxType> type(String id) {
		return catalog().stream().filter(t -> t.id().equals(id)).findFirst();
	}

	// ---- Owning, equipping, colour ----

	public static boolean owns(String id) {
		if (id == null) {
			return false;
		}
		if (FREE.containsKey(id)) {
			return true;
		}
		load();
		// The owner can wear every one (to try those still in testing on real kills).
		return net.veloclient.velo.client.network.StoreClient.owns(ITEM_PREFIX + id) || net.veloclient.velo.client.network.StoreClient.isOwner()
				|| !net.veloclient.velo.client.network.StoreClient.walletLoaded() && id.equals(equippedId);
	}

	public static synchronized String equippedId() {
		load();
		return equippedId;
	}

	/**
	 * Equips a kill effect you own - one from the Store or a free one ({@link #FREE}); null plays none - and switches
	 * the Kill Effects module on; false if you don't own it.
	 */
	public static synchronized boolean equip(String id) {
		load();
		if (id != null && !FREE.containsKey(id) && (!IDS.contains(id) || !owns(id))) {
			return false;
		}
		equippedId = id;
		save();
		if (id != null) {
			net.veloclient.velo.module.ModuleRegistry.get("kill-effects").ifPresent(m -> m.setEnabled(true));
		}
		return true;
	}

	/** How loud kill effects are (0-1.5). */
	public static synchronized float volume() {
		load();
		return volume;
	}

	public static synchronized void setVolume(float value) {
		load();
		volume = Math.max(0, Math.min(1.5f, value));
		save();
	}

	/** The colour of the free Color Burst / Confetti Rain (ARGB). */
	public static synchronized int freeColor() {
		load();
		return freeColor;
	}

	public static synchronized void setFreeColor(int argb) {
		load();
		freeColor = argb;
		save();
	}

	/** The free effect equipped (its name in the Kill Effects module), or null. */
	public static String freeEffect() {
		String id = equippedId();
		return id == null ? null : FREE.get(id);
	}

	/** The name of a Store or free kill effect id. */
	public static String name(String id) {
		if (id == null) {
			return "None";
		}
		if (FREE.containsKey(id)) {
			return FREE.get(id);
		}
		return type(id).map(KillFxType::name).orElse(id);
	}

	public static synchronized int color(String id) {
		load();
		return COLORS.getOrDefault(id, 0);
	}

	public static synchronized void setColor(String id, int color) {
		load();
		if (color == 0) {
			COLORS.remove(id);
		} else {
			COLORS.put(id, color);
		}
		save();
	}

	public static AuraEffects.Settings settings(String id) {
		return new AuraEffects.Settings(1f, 1f, 1f, color(id));
	}

	/** The effect played on your kills right now (equipped and owned), or null for the module's free ones. */
	public static String active() {
		String id = equippedId();
		return id != null && IDS.contains(id) && owns(id) ? id : null;
	}

	/** Plays your equipped kill effect where someone died (feet at x, y, z); false when none is equipped. */
	public static boolean playEquipped(double x, double y, double z, boolean sound) {
		String id = active();
		if (id == null) {
			return false;
		}
		play(id, x, y, z, sound);
		return true;
	}

	/** Plays {@code id} at (x, y, z) - your own kills, or trying one out from the Store. */
	public static void play(String id, double x, double y, double z, boolean sound) {
		KillFxEffects.Kind kind = KillFxEffects.kind(id);
		if (kind == null) {
			return;
		}
		net.veloclient.velo.client.cosmetics.render.KillFxRenderer.spawn(kind, settings(id), x, y, z);
		if (sound) {
			net.veloclient.velo.client.emotes.EmoteSounds.playAt(x, y + 1, z, KillFxEffects.sound(kind), 1.6f * volume(), 1f);
		}
	}
}
