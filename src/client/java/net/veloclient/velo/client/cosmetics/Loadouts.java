package net.veloclient.velo.client.cosmetics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.aura.AuraManager;
import net.veloclient.velo.client.cosmetics.wings.WingsManager;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Saved looks for the Locker: cape, wings, aura, bodywear (with each item's colour, height and distance), kill effect,
 * skin and name style plus how they're worn (wing size/position/flap, aura colour, size, speed and density), switched
 * in one click. Every change you make goes straight into the look you have on. Same file and format as the launcher's Locker loadouts
 * ({@code config/loadouts.json}), so a look saved in either shows up in both.
 */
public final class Loadouts {

	/**
	 * {@code nameStyle}: the Velo+ name style in its wire form and {@code nameStyleOn} whether it's switched on. {@code wear}:
	 * slot -> bodywear item, with {@code wearColors} / {@code wearOffsets} / {@code wearDepths} per item; {@code killFx}: the
	 * kill effect ("" for none); {@code skin}: a skin library id. Each is null in looks saved before it existed, which
	 * then leave that part as it is.
	 */
	public record Loadout(String name, String cape, String wings, String aura, WingsManager.Settings wingsSettings,
			AuraEffects.Settings auraSettings, String nameStyle, Boolean nameStyleOn, java.util.Map<String, String> wear,
			java.util.Map<String, Integer> wearColors, java.util.Map<String, Integer> wearOffsets, java.util.Map<String, Integer> wearDepths,
			String killFx, String skin, java.util.Map<String, String> extras) {

		static Loadout current(String name) {
			String wings = WingsManager.equippedId();
			String aura = AuraManager.equippedId();
			java.util.Map<String, String> wear = net.veloclient.velo.client.cosmetics.wear.WearManager.equippedAll();
			java.util.Map<String, Integer> colors = new java.util.TreeMap<>();
			java.util.Map<String, Integer> offsets = new java.util.TreeMap<>();
			java.util.Map<String, Integer> depths = new java.util.TreeMap<>();
			for (String id : wear.values()) {
				putNonZero(colors, id, net.veloclient.velo.client.cosmetics.wear.WearManager.color(id));
				putNonZero(offsets, id, net.veloclient.velo.client.cosmetics.wear.WearManager.offset(id));
				putNonZero(depths, id, net.veloclient.velo.client.cosmetics.wear.WearManager.depth(id));
			}
			String killFx = net.veloclient.velo.client.cosmetics.killfx.KillFxManager.equippedId();
			String skin = wantedSkin != null ? wantedSkin : net.veloclient.velo.client.skins.SkinLibrary.equippedId();
			return new Loadout(name, CapeManager.equipped().map(CapeDefinition::id).orElse(null), wings, aura,
					wings == null ? null : WingsManager.settings(wings), aura == null ? null : AuraManager.settings(aura),
					net.veloclient.velo.client.namestyle.NameStyles.own().encode(), net.veloclient.velo.client.namestyle.NameStyles.ownEnabled(),
					new java.util.TreeMap<>(wear), colors, offsets, depths, killFx == null ? "" : killFx, skin, currentExtras());
		}

		private static void putNonZero(java.util.Map<String, Integer> map, String id, int value) {
			if (value != 0) {
				map.put(id, value);
			}
		}

		/** Whether this is exactly what you wear right now. */
		public boolean isWorn() {
			String wornWings = WingsManager.equippedId();
			String wornAura = AuraManager.equippedId();
			return Objects.equals(cape, CapeManager.equipped().map(CapeDefinition::id).orElse(null)) && Objects.equals(wings, wornWings)
					&& Objects.equals(aura, wornAura)
					&& (wingsSettings == null || wings == null || wingsSettings.equals(WingsManager.settings(wings)))
					&& (auraSettings == null || aura == null || auraSettings.equals(AuraManager.settings(aura)))
					&& (nameStyleOn == null || nameStyleOn == net.veloclient.velo.client.namestyle.NameStyles.ownEnabled()
							&& (!nameStyleOn || Objects.equals(nameStyle, net.veloclient.velo.client.namestyle.NameStyles.own().encode())))
					&& (wear == null || wear.equals(net.veloclient.velo.client.cosmetics.wear.WearManager.equippedAll()))
					&& (killFx == null || killFx.equals(Objects.requireNonNullElse(net.veloclient.velo.client.cosmetics.killfx.KillFxManager.equippedId(), "")))
					&& (skin == null || skin.equals(wantedSkin != null ? wantedSkin : net.veloclient.velo.client.skins.SkinLibrary.equippedId()))
					&& extrasWorn(extras);
		}
	}

	/** Something a look also keeps that the core doesn't know (the dev build's pet): what's on now, and putting it on. */
	private record Extra(java.util.function.Supplier<String> current, java.util.function.Consumer<String> wear) {
	}

	private static final java.util.Map<String, Extra> EXTRAS = new java.util.LinkedHashMap<>();

	/** Registers part of a look under {@code key}; call {@link #changed()} whenever it changes. */
	public static void registerExtra(String key, java.util.function.Supplier<String> current, java.util.function.Consumer<String> wear) {
		EXTRAS.put(key, new Extra(current, wear));
	}

	private static java.util.Map<String, String> currentExtras() {
		if (EXTRAS.isEmpty()) {
			return null;
		}
		java.util.Map<String, String> out = new java.util.TreeMap<>();
		EXTRAS.forEach((key, extra) -> out.put(key, Objects.requireNonNullElse(extra.current().get(), "")));
		return out;
	}

	private static boolean extrasWorn(java.util.Map<String, String> extras) {
		if (extras == null) {
			return true;
		}
		for (var e : EXTRAS.entrySet()) {
			String saved = extras.get(e.getKey());
			if (saved != null && !saved.equals(Objects.requireNonNullElse(e.getValue().current().get(), ""))) {
				return false;
			}
		}
		return true;
	}

	/** The skin a look asked for while its upload to Mojang runs (or was refused - it stays the look's skin). */
	private static volatile String wantedSkin;

	/** A skin was put on (the Skins screen or a look's upload): that's the skin now. */
	public static void skinChanged(String id) {
		wantedSkin = null;
		changed();
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final java.util.concurrent.atomic.AtomicBoolean SYNC_PENDING = new java.util.concurrent.atomic.AtomicBoolean();
	private static volatile boolean wearing;
	private static List<Loadout> cached;
	private static long cachedStamp = -1;

	private Loadouts() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("loadouts.json");
	}

	private static Path activeFile() {
		return VeloPaths.config().resolve("loadout-active.txt");
	}

	/** Every saved look (re-read when the launcher changed the file). */
	public static synchronized List<Loadout> all() {
		long stamp;
		try {
			stamp = Files.exists(file()) ? Files.getLastModifiedTime(file()).toMillis() : 0;
		} catch (Exception e) {
			stamp = 0;
		}
		if (cached == null || stamp != cachedStamp) {
			cachedStamp = stamp;
			cached = new ArrayList<>();
			try {
				if (stamp != 0) {
					List<Loadout> read = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<List<Loadout>>() {
					}.getType());
					if (read != null) {
						read.stream().filter(l -> l != null && l.name() != null).forEach(cached::add);
					}
				}
			} catch (Exception ignored) {
				// Unreadable: no loadouts.
			}
		}
		return List.copyOf(cached);
	}

	private static synchronized void write(List<Loadout> list) {
		cached = new ArrayList<>(list);
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(list), StandardCharsets.UTF_8);
			cachedStamp = Files.getLastModifiedTime(file()).toMillis();
		} catch (Exception ignored) {
			// Not fatal: it just isn't kept.
		}
	}

	/** Saves what you wear now as a new look ("Look 3"); returns its name. */
	public static String saveCurrent() {
		List<Loadout> list = new ArrayList<>(all());
		int n = list.size() + 1;
		while (true) {
			String candidate = "Look " + n;
			if (list.stream().noneMatch(l -> l.name().equalsIgnoreCase(candidate))) {
				break;
			}
			n++;
		}
		String name = "Look " + n;
		list.add(Loadout.current(name));
		write(list);
		setActive(name);
		return name;
	}

	/**
	 * Something you wear changed (an item, a colour, a size, the name style): the look you have on (picked or saved
	 * last) takes it, so every look keeps its own items and settings - same as the launcher's Locker. Sliders call this
	 * every frame while dragged, so it's written once things settle.
	 */
	public static void changed() {
		if (wearing || !SYNC_PENDING.compareAndSet(false, true)) {
			return;
		}
		java.util.concurrent.CompletableFuture.delayedExecutor(150, java.util.concurrent.TimeUnit.MILLISECONDS)
				.execute(() -> net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
					SYNC_PENDING.set(false);
					if (!wearing) {
						syncActive();
					}
				}));
	}

	private static void syncActive() {
		String name = active();
		if (name.isEmpty()) {
			return;
		}
		List<Loadout> list = new ArrayList<>(all());
		for (int i = 0; i < list.size(); i++) {
			if (list.get(i).name().equals(name)) {
				Loadout now = Loadout.current(name);
				if (!now.equals(list.get(i))) {
					list.set(i, now);
					write(list);
				}
				return;
			}
		}
	}

	/** The look you have on, "" for none. */
	public static String active() {
		try {
			return Files.exists(activeFile()) ? Files.readString(activeFile(), StandardCharsets.UTF_8).strip() : "";
		} catch (Exception e) {
			return "";
		}
	}

	/** Puts the look on; returns what couldn't be (not owned / no longer in your library), empty when all of it is on. */
	public static List<String> wear(Loadout loadout) {
		wearing = true;
		try {
			return wearNow(loadout);
		} finally {
			wearing = false;
		}
	}

	private static List<String> wearNow(Loadout loadout) {
		List<String> missing = new ArrayList<>();
		setActive("");
		// The Locker's try-on previews would hide what's really worn now.
		CapeManager.clearPreviewOverride();
		WingsManager.clearPreview();
		AuraManager.clearPreview();
		if (loadout.cape() == null) {
			CapeManager.unequip();
		} else {
			CapeManager.loadLibrary();
			if (CapeManager.library().containsKey(loadout.cape())) {
				CapeManager.equip(loadout.cape());
			} else {
				missing.add("cape");
			}
		}
		if (loadout.wings() != null && loadout.wingsSettings() != null) {
			WingsManager.setSettings(loadout.wings(), loadout.wingsSettings());
		}
		if (!WingsManager.equip(loadout.wings())) {
			missing.add("wings \"" + loadout.wings() + "\"");
		}
		if (loadout.aura() != null && loadout.auraSettings() != null) {
			AuraManager.setSettings(loadout.aura(), loadout.auraSettings());
		}
		if (!AuraManager.equip(loadout.aura())) {
			missing.add("aura \"" + loadout.aura() + "\"");
		}
		if (loadout.nameStyleOn() != null) {
			var style = net.veloclient.velo.client.namestyle.NameStyle.decode(loadout.nameStyle());
			net.veloclient.velo.client.namestyle.NameStyles.save(style == null ? net.veloclient.velo.client.namestyle.NameStyles.own() : style,
					loadout.nameStyleOn() && net.veloclient.velo.client.namestyle.NameStyles.plus());
		}
		if (loadout.wear() != null) {
			net.veloclient.velo.client.cosmetics.wear.WearManager.clearPreview();
			for (String slot : net.veloclient.velo.client.cosmetics.wear.WearManager.SLOTS) {
				String id = loadout.wear().get(slot);
				if (id != null) {
					var colors = loadout.wearColors();
					var offsets = loadout.wearOffsets();
					var depths = loadout.wearDepths();
					net.veloclient.velo.client.cosmetics.wear.WearManager.setColor(id, colors == null ? 0 : colors.getOrDefault(id, 0));
					net.veloclient.velo.client.cosmetics.wear.WearManager.setOffset(id, offsets == null ? 0 : offsets.getOrDefault(id, 0));
					net.veloclient.velo.client.cosmetics.wear.WearManager.setDepth(id, depths == null ? 0 : depths.getOrDefault(id, 0));
				}
				if (!net.veloclient.velo.client.cosmetics.wear.WearManager.equip(slot, id)) {
					missing.add("bodywear \"" + id + "\"");
				}
			}
		}
		if (loadout.killFx() != null) {
			String fx = loadout.killFx().isEmpty() ? null : loadout.killFx();
			if (!net.veloclient.velo.client.cosmetics.killfx.KillFxManager.equip(fx)) {
				missing.add("kill effect \"" + fx + "\"");
			}
		}
		if (loadout.skin() != null && !loadout.skin().equals(net.veloclient.velo.client.skins.SkinLibrary.equippedId())) {
			var skin = net.veloclient.velo.client.skins.SkinLibrary.all().stream().filter(s -> s.id().equals(loadout.skin())).findFirst().orElse(null);
			if (skin == null) {
				missing.add("skin");
			} else {
				// Uploading to Mojang takes a moment (and can be refused for changing too often): off the render thread.
				wantedSkin = skin.id();
				Thread.ofVirtual().start(() -> {
					try {
						net.veloclient.velo.client.gui.VeloSkinsScreen.upload(skin);
					} catch (Exception e) {
						net.veloclient.velo.VeloClient.LOGGER.info("Loadout \"{}\": couldn't change the skin: {}", loadout.name(), e.getMessage());
					}
				});
			}
		}
		if (loadout.extras() != null) {
			EXTRAS.forEach((key, extra) -> {
				String value = loadout.extras().get(key);
				if (value != null) {
					extra.wear().accept(value);
				}
			});
		}
		net.veloclient.velo.client.network.VeloServerClient.syncSoon();
		setActive(loadout.name());
		if (!missing.isEmpty()) {
			net.veloclient.velo.VeloClient.LOGGER.info("Loadout \"{}\": couldn't put on {}", loadout.name(), missing);
		}
		return missing;
	}

	/** Replaces the look with what you wear now. */
	public static void update(String name) {
		List<Loadout> list = new ArrayList<>(all());
		list.replaceAll(l -> l.name().equals(name) ? Loadout.current(name) : l);
		write(list);
	}

	public static void delete(String name) {
		List<Loadout> list = new ArrayList<>(all());
		list.removeIf(l -> l.name().equals(name));
		write(list);
	}

	private static void setActive(String name) {
		try {
			Files.createDirectories(activeFile().getParent());
			Files.writeString(activeFile(), name == null ? "" : name, StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Only the launcher's live sync misses it.
		}
	}
}
