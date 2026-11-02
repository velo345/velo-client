package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.killfx.KillFxEffects;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Store's kill effects in the launcher ({@link KillFxEffects}), named and priced by the server's catalog, and which
 * one is equipped and in which colour - in the same {@code config/cosmetics-killfx.json} the game reads.
 */
public final class KillFxCatalog {

	public static final String ITEM_PREFIX = "killfx_";
	public static final List<String> IDS = List.of("shatter", "orbital", "blackhole", "phoenix", "reaper", "glitch", "supernova", "frostbite",
			"meteor", "tornado", "grave", "thunder", "shadow", "katana", "jackpot");

	public record KillFxType(String id, String name, String description, int price, boolean inStore, String accent) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}

		public KillFxEffects.Kind kind() {
			return KillFxEffects.kind(id);
		}
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

	/** The free kill effects (vanilla particles, played by the game's Kill Effects module): id -> name. Always yours. */
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

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private KillFxCatalog() {
	}

	public static List<KillFxType> all() {
		List<JsonObject> server = WingsCatalog.serverItems("killfx");
		List<KillFxType> out = new ArrayList<>();
		for (String id : IDS) {
			JsonObject o = server.stream().filter(i -> (ITEM_PREFIX + id).equals(i.get("id").getAsString())).findFirst().orElse(null);
			String accent = String.format("#%06x", KillFxEffects.accent(KillFxEffects.kind(id), AuraEffects.Settings.DEFAULT));
			String[] fallback = FALLBACK.get(id);
			if (o == null) {
				out.add(new KillFxType(id, fallback[0], fallback[1], 1800, false, accent));
			} else {
				out.add(new KillFxType(id, str(o, "name", fallback[0]), str(o, "description", fallback[1]),
						o.has("price") ? o.get("price").getAsInt() : 1800, o.has("inStore") && o.get("inStore").getAsBoolean(), str(o, "accent", accent)));
			}
		}
		return out;
	}

	public static List<KillFxType> forSale() {
		return all().stream().filter(KillFxType::inStore).toList();
	}

	public static Optional<KillFxType> byId(String id) {
		return all().stream().filter(a -> a.id().equals(id)).findFirst();
	}

	public static boolean owns(String id) {
		return FREE.containsKey(id) || StoreOwnership.owns(ITEM_PREFIX + id);
	}

	/** A Store or free kill effect's name ("None" for null). */
	public static String name(String id) {
		if (id == null) {
			return "None";
		}
		if (FREE.containsKey(id)) {
			return FREE.get(id);
		}
		return byId(id).map(KillFxType::name).orElse(id);
	}

	/** How loud kill effects are (0-1.5), in the game and the previews here. */
	public static float volume() {
		JsonObject o = read();
		return o.has("volume") && !o.get("volume").isJsonNull() ? Math.max(0, Math.min(1.5f, o.get("volume").getAsFloat())) : 1f;
	}

	public static void setVolume(float volume) {
		JsonObject o = read();
		o.addProperty("volume", Math.max(0, Math.min(1.5f, volume)));
		write(o);
	}

	/** The free Color Burst / Confetti Rain colour (ARGB). */
	public static int freeColor() {
		JsonObject o = read();
		return o.has("freeColor") && !o.get("freeColor").isJsonNull() ? o.get("freeColor").getAsInt() : 0xFFFF3030;
	}

	public static void setFreeColor(int argb) {
		JsonObject o = read();
		o.addProperty("freeColor", argb);
		write(o);
	}

	// ---- Equipped and colours (config/cosmetics-killfx.json, shared with the game) ----

	private static Path file() {
		return VeloPaths.accountConfig("cosmetics-killfx.json");
	}

	private static JsonObject read() {
		try {
			if (Files.exists(file())) {
				return JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (Exception ignored) {
			// Start over.
		}
		return new JsonObject();
	}

	/** Run after the kill effect settings changed (the Locker keeps the worn loadout in step). */
	public static final java.util.List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	private static void write(JsonObject o) {
		try {
			VeloPaths.ensureDirectories();
			Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException("Couldn't save the kill effect settings", e);
		}
		LISTENERS.forEach(Runnable::run);
	}

	public static String equipped() {
		JsonObject o = read();
		return o.has("effectId") && !o.get("effectId").isJsonNull() ? o.get("effectId").getAsString() : null;
	}

	/** Equips a kill effect - from the Store or a free one; null plays none. The game picks it up the next time it starts. */
	public static void equip(String id) {
		JsonObject o = read();
		if (id == null) {
			o.add("effectId", com.google.gson.JsonNull.INSTANCE);
		} else {
			o.addProperty("effectId", id);
		}
		write(o);
	}

	public static int color(String id) {
		JsonObject o = read();
		if (o.has("colors") && o.get("colors").isJsonObject() && o.getAsJsonObject("colors").has(id)) {
			return o.getAsJsonObject("colors").get(id).getAsInt();
		}
		return 0;
	}

	public static void setColor(String id, int color) {
		JsonObject o = read();
		JsonObject colors = o.has("colors") && o.get("colors").isJsonObject() ? o.getAsJsonObject("colors") : new JsonObject();
		if (color == 0) {
			colors.remove(id);
		} else {
			colors.addProperty(id, color);
		}
		o.add("colors", colors);
		write(o);
	}

	public static AuraEffects.Settings settings(String id) {
		return new AuraEffects.Settings(1f, 1f, 1f, color(id));
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}
}
