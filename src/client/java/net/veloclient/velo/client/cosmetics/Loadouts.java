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
 * Saved looks for the Locker: cape, wings, aura and name style plus how they're worn (wing size/position/flap, aura
 * colour, size, speed and density), switched in one click. Same file and format as the launcher's Locker loadouts
 * ({@code config/loadouts.json}), so a look saved in either shows up in both.
 */
public final class Loadouts {

	/**
	 * {@code nameStyle}: the Velo+ name style in its wire form and {@code nameStyleOn} whether it's switched on; both null
	 * in looks saved before name styles existed, which then leave your name style as it is.
	 */
	public record Loadout(String name, String cape, String wings, String aura, WingsManager.Settings wingsSettings,
			AuraEffects.Settings auraSettings, String nameStyle, Boolean nameStyleOn) {

		static Loadout current(String name) {
			String wings = WingsManager.equippedId();
			String aura = AuraManager.equippedId();
			return new Loadout(name, CapeManager.equipped().map(CapeDefinition::id).orElse(null), wings, aura,
					wings == null ? null : WingsManager.settings(wings), aura == null ? null : AuraManager.settings(aura),
					net.veloclient.velo.client.namestyle.NameStyles.own().encode(), net.veloclient.velo.client.namestyle.NameStyles.ownEnabled());
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
							&& (!nameStyleOn || Objects.equals(nameStyle, net.veloclient.velo.client.namestyle.NameStyles.own().encode())));
		}
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
		java.util.concurrent.CompletableFuture.delayedExecutor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
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
