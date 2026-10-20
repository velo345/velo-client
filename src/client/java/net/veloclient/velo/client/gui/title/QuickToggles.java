package net.veloclient.velo.client.gui.title;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.veloclient.velo.client.profile.VeloProfileStore;
import net.veloclient.velo.config.VeloPaths;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The pause menu's quick toggles: your favourite modules (pinned, a few useful ones to start with) and the ones
 * you switched most recently. Saved in {@code quick-toggles.json}; toggling saves the active profile like the
 * Velo menu does.
 */
public final class QuickToggles {

	private static final int MAX_RECENT = 8;
	private static final List<String> DEFAULT_FAVORITES = List.of("free-look", "zoom", "toggle-sprint", "minimap");
	private static final Gson GSON = new Gson();

	private static final class Data {
		List<String> favorites = new ArrayList<>(DEFAULT_FAVORITES);
		List<String> recent = new ArrayList<>();
	}

	private static Data data;

	private QuickToggles() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("quick-toggles.json");
	}

	private static Data data() {
		if (data == null) {
			try {
				if (Files.exists(file())) {
					data = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), new TypeToken<Data>() {
					}.getType());
				}
			} catch (Exception ignored) {
				data = null;
			}
			if (data == null) {
				data = new Data();
			}
			if (data.favorites == null) {
				data.favorites = new ArrayList<>(DEFAULT_FAVORITES);
			}
			if (data.recent == null) {
				data.recent = new ArrayList<>();
			}
		}
		return data;
	}

	private static void save() {
		if (System.getProperty("velo.screenshotTour") != null) {
			return;
		}
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data()), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Kept for this session.
		}
	}

	/** Favourite modules that exist in this install, in order. */
	public static List<Module> favorites() {
		return resolve(data().favorites);
	}

	/** Recently switched modules (newest first), or the favourites while there are none yet. */
	public static List<Module> recent() {
		List<Module> list = resolve(data().recent);
		return list.isEmpty() ? favorites() : list;
	}

	private static List<Module> resolve(List<String> ids) {
		List<Module> out = new ArrayList<>();
		for (String id : ids) {
			ModuleRegistry.get(id).ifPresent(out::add);
		}
		return out;
	}

	public static boolean isFavorite(String id) {
		return data().favorites.contains(id);
	}

	public static void setFavorite(String id, boolean favorite) {
		data().favorites.remove(id);
		if (favorite) {
			data().favorites.add(id);
		}
		save();
	}

	/** Remembers a module someone just switched (from any Velo menu). */
	public static void touch(String id) {
		data().recent.remove(id);
		data().recent.add(0, id);
		while (data().recent.size() > MAX_RECENT) {
			data().recent.remove(data().recent.size() - 1);
		}
		save();
	}

	public static void toggle(Module module) {
		module.setEnabled(!module.isEnabled());
		touch(module.id());
		VeloProfileStore.saveActive();
	}
}
