package net.veloclient.launcher.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The auras in the launcher: the five the game can draw ({@link AuraEffects}), named and priced by
 * the server's catalog, and which one is equipped and how each is worn - in the same
 * {@code config/cosmetics-aura.json} the game reads.
 */
public final class AuraCatalog {

	public static final String ITEM_PREFIX = "aura_";
	public static final List<String> IDS = List.of("orbit", "ember", "frost", "void", "storm", "matrix", "inferno", "bloom", "heartbeat", "aurora");

	public record AuraType(String id, String name, String description, int price, boolean inStore, String accent) {
		public String itemId() {
			return ITEM_PREFIX + id;
		}

		public AuraEffects.Kind kind() {
			return AuraEffects.kind(id);
		}
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

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private AuraCatalog() {
	}

	public static List<AuraType> all() {
		List<JsonObject> server = WingsCatalog.serverItems("aura");
		List<AuraType> out = new ArrayList<>();
		for (String id : IDS) {
			JsonObject o = server.stream().filter(i -> (ITEM_PREFIX + id).equals(i.get("id").getAsString())).findFirst().orElse(null);
			String accent = String.format("#%06x", AuraEffects.accent(AuraEffects.kind(id), AuraEffects.Settings.DEFAULT));
			String[] fallback = FALLBACK.get(id);
			if (o == null) {
				out.add(new AuraType(id, fallback[0], fallback[1], 2000, false, accent));
			} else {
				out.add(new AuraType(id, str(o, "name", fallback[0]), str(o, "description", fallback[1]),
						o.has("price") ? o.get("price").getAsInt() : 2000, o.has("inStore") && o.get("inStore").getAsBoolean(), str(o, "accent", accent)));
			}
		}
		return out;
	}

	public static List<AuraType> forSale() {
		return all().stream().filter(AuraType::inStore).toList();
	}

	public static Optional<AuraType> byId(String id) {
		return all().stream().filter(a -> a.id().equals(id)).findFirst();
	}

	public static boolean owns(String id) {
		return StoreOwnership.owns(ITEM_PREFIX + id);
	}

	// ---- Equipped and settings (config/cosmetics-aura.json, shared with the game) ----

	private static Path file() {
		return VeloPaths.config().resolve("cosmetics-aura.json");
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

	private static void write(JsonObject o) {
		try {
			VeloPaths.ensureDirectories();
			Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new RuntimeException("Couldn't save the aura settings", e);
		}
	}

	public static String equipped() {
		// Dev-only: the launcher tour shows an aura without touching your real config.
		if (System.getProperty("velo.tourAura") != null && System.getProperty("velo.launcherTour") != null) {
			return System.getProperty("velo.tourAura");
		}
		JsonObject o = read();
		return o.has("auraId") && !o.get("auraId").isJsonNull() ? o.get("auraId").getAsString() : null;
	}

	/** Equips an aura (null takes it off) - the game picks it up the next time it starts. */
	public static void equip(String id) {
		JsonObject o = read();
		if (id == null) {
			o.add("auraId", com.google.gson.JsonNull.INSTANCE);
		} else {
			o.addProperty("auraId", id);
		}
		write(o);
	}

	public static AuraEffects.Settings settings(String id) {
		JsonObject o = read();
		if (o.has("perAura") && o.get("perAura").isJsonObject() && o.getAsJsonObject("perAura").has(id)) {
			JsonObject s = o.getAsJsonObject("perAura").getAsJsonObject(id);
			return new AuraEffects.Settings(f(s, "size", 1f), f(s, "speed", 1f), f(s, "density", 1f), s.has("color") ? s.get("color").getAsInt() : 0);
		}
		return AuraEffects.Settings.DEFAULT;
	}

	public static void setSettings(String id, AuraEffects.Settings settings) {
		JsonObject o = read();
		JsonObject per = o.has("perAura") && o.get("perAura").isJsonObject() ? o.getAsJsonObject("perAura") : new JsonObject();
		if (settings == null || settings.equals(AuraEffects.Settings.DEFAULT)) {
			per.remove(id);
		} else {
			JsonObject s = new JsonObject();
			s.addProperty("size", settings.size());
			s.addProperty("speed", settings.speed());
			s.addProperty("density", settings.density());
			s.addProperty("color", settings.color());
			per.add(id, s);
		}
		o.add("perAura", per);
		write(o);
	}

	private static float f(JsonObject o, String key, float fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsFloat() : fallback;
	}

	private static String str(JsonObject o, String key, String fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
	}
}
