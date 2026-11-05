package net.veloclient.velo.client.mods;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.VeloClient;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Everything Velo's Mods page knows about the installed mods: name, version, authors, icon, whether they're a library,
 * their settings screen (from their Mod Menu integration, read by reflection so it works with the real Mod Menu or
 * {@link ModMenuApiShim}) and whether they can be switched off (a jar in the mods folder: renamed to .disabled,
 * applies on the next start).
 */
public final class ModConfigs {

	/** One installed mod. {@code jar}: its file in the mods folder (null for built-in or nested ones). */
	public record Mod(String id, String name, String version, String description, String authors, String iconPath, ModContainer container,
			Path jar, boolean library, boolean core) {
	}

	/** Never offered as "turn off": the game and Velo itself need them. */
	private static final Set<String> CORE = Set.of("minecraft", "java", "fabricloader", "fabric-api", "velo-client", "mixinextras", "osl", "pylon");

	private static Map<String, Function<Screen, Screen>> factories;
	/** Switched off / on this session (applies on the next start). */
	private static final Map<String, Boolean> PENDING = new HashMap<>();

	private ModConfigs() {
	}

	/** Every top-level mod (nested libraries folded into their parent), sorted by name, Velo first. */
	public static List<Mod> mods() {
		List<Mod> out = new ArrayList<>();
		Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods").toAbsolutePath().normalize();
		for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
			if (c.getContainingMod().isPresent()) {
				continue;
			}
			var meta = c.getMetadata();
			String id = meta.getId();
			if (id.equals("java") || id.equals("minecraft") || id.equals("fabricloader")) {
				continue;
			}
			Path jar = null;
			if (c.getOrigin().getKind() == ModOrigin.Kind.PATH) {
				for (Path p : c.getOrigin().getPaths()) {
					Path abs = p.toAbsolutePath().normalize();
					if (abs.getParent() != null && abs.getParent().equals(modsDir) && abs.getFileName().toString().endsWith(".jar")) {
						jar = abs;
					}
				}
			}
			StringBuilder authors = new StringBuilder();
			meta.getAuthors().forEach(a -> authors.append(authors.isEmpty() ? "" : ", ").append(a.getName()));
			boolean library = id.startsWith("fabric-") && !id.equals("fabric-api") || meta.getType().equals("builtin")
					|| meta.containsCustomValue("fabric-loom:generated");
			// Mod Menu's own badge: {"custom": {"modmenu": {"badges": ["library"]}}}
			var custom = meta.containsCustomValue("modmenu") ? meta.getCustomValue("modmenu") : null;
			if (custom != null && custom.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.OBJECT) {
				var badges = custom.getAsObject().get("badges");
				if (badges != null && badges.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.ARRAY) {
					for (var badge : badges.getAsArray()) {
						if (badge.getType() == net.fabricmc.loader.api.metadata.CustomValue.CvType.STRING && "library".equals(badge.getAsString())) {
							library = true;
						}
					}
				}
			}
			out.add(new Mod(id, meta.getName(), meta.getVersion().getFriendlyString(), meta.getDescription(), authors.toString(),
					meta.getIconPath(64).orElse(null), c, jar, library, CORE.contains(id)));
		}
		out.sort(Comparator.<Mod, Boolean>comparing(m -> !m.id().equals("velo-client")).thenComparing(m -> m.name().toLowerCase(Locale.ROOT)));
		return out;
	}

	/** How many mods have a settings screen (log line at start). */
	public static int settingsCount() {
		return factories().size();
	}

	/** The mod's settings screen opener, or null when it has none. */
	public static Function<Screen, Screen> configFor(String modId) {
		if (modId.equals("velo-client")) {
			return parent -> new net.veloclient.velo.client.gui.ModMenuScreen();
		}
		return factories().get(modId);
	}

	private static synchronized Map<String, Function<Screen, Screen>> factories() {
		if (factories != null) {
			return factories;
		}
		factories = new HashMap<>();
		if (!ModMenuApiShim.available()) {
			return factories;
		}
		for (EntrypointContainer<Object> entry : FabricLoader.getInstance().getEntrypointContainers("modmenu", Object.class)) {
			String owner = entry.getProvider().getMetadata().getId();
			try {
				Object api = entry.getEntrypoint();
				Object own = call(api, "getModConfigScreenFactory");
				if (own != null) {
					factories.put(owner, opener(own));
				}
				Object provided = call(api, "getProvidedConfigScreenFactories");
				if (provided instanceof Map<?, ?> map) {
					for (Map.Entry<?, ?> e : map.entrySet()) {
						if (e.getKey() instanceof String id && e.getValue() != null) {
							factories.putIfAbsent(id, opener(e.getValue()));
						}
					}
				}
			} catch (Throwable e) {
				VeloClient.LOGGER.debug("Velo Mods: no settings screen from {}", owner, e);
			}
		}
		return factories;
	}

	private static Object call(Object target, String name) throws Exception {
		Method m = target.getClass().getMethod(name);
		m.setAccessible(true);
		return m.invoke(target);
	}

	private static Function<Screen, Screen> opener(Object factory) {
		return parent -> {
			try {
				for (Method m : factory.getClass().getMethods()) {
					if (m.getName().equals("create") && m.getParameterCount() == 1) {
						m.setAccessible(true);
						return (Screen) m.invoke(factory, parent);
					}
				}
			} catch (Throwable e) {
				VeloClient.LOGGER.warn("Velo Mods: a mod's settings screen failed to open", e);
			}
			return null;
		};
	}

	/** On / off as it will be after the next start. */
	public static boolean enabledNextStart(Mod mod) {
		Boolean pending = PENDING.get(mod.id());
		return pending == null || pending;
	}

	/** True when something changed this session that only applies after a restart. */
	public static boolean restartNeeded() {
		return !PENDING.isEmpty();
	}

	/** Turns a mod off (or back on) for the next start by renaming its jar. Returns an error message or null. */
	public static String setEnabled(Mod mod, boolean on) {
		if (mod.jar() == null || mod.core()) {
			return "This one can't be switched off";
		}
		Path enabled = mod.jar();
		Path disabled = enabled.resolveSibling(enabled.getFileName() + ".disabled");
		try {
			if (on && Files.exists(disabled)) {
				Files.move(disabled, enabled, StandardCopyOption.REPLACE_EXISTING);
			} else if (!on && Files.exists(enabled)) {
				Files.move(enabled, disabled, StandardCopyOption.REPLACE_EXISTING);
			}
			if (on) {
				PENDING.remove(mod.id());
			} else {
				PENDING.put(mod.id(), false);
			}
			return null;
		} catch (Exception e) {
			// Windows keeps a running game's jars locked: the launcher can do it while the game is closed.
			return "Couldn't change it while the game runs - use the launcher's Mods tab";
		}
	}

	// ---- What the Mod Menu API's static helpers do here ----

	/** The mod list to open: the player's own Mod Menu when they installed it, else Velo's Mods page. */
	public static Screen modList(Screen parent) {
		if (FabricLoader.getInstance().isModLoaded("modmenu")) {
			try {
				Class<?> api = Class.forName("com.terraformersmc.modmenu.api.ModMenuApi");
				Method create = api.getMethod("createModsScreen", Screen.class);
				Object screen = create.invoke(null, parent);
				if (screen instanceof Screen s) {
					return s;
				}
			} catch (Throwable e) {
				VeloClient.LOGGER.debug("Velo Mods: Mod Menu's list didn't open, showing Velo's", e);
			}
		}
		return new net.veloclient.velo.client.gui.VeloModsScreen(parent);
	}

	/** {@code ModMenuApi.createModsScreen(parent)}: Velo's Mods page. */
	public static Screen openModsScreen(Screen parent) {
		return new net.veloclient.velo.client.gui.VeloModsScreen(parent);
	}

	public static Text modsButtonText() {
		return Text.literal("Mods");
	}
}
