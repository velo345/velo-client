package net.veloclient.velo.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which of vanilla's screens Velo replaces with its own (Settings > Velo screens). Each one can be
 * switched back to the vanilla screen on its own; stored in {@code config/screens.json}.
 */
public final class VeloScreens {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final class Data {
		boolean advancements = true;
		boolean controls = true;
		boolean statistics = true;
	}

	private static Data data;

	private VeloScreens() {
	}

	private static Path file() {
		return VeloPaths.config().resolve("screens.json");
	}

	private static Data data() {
		if (data == null) {
			try {
				data = Files.exists(file()) ? GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), Data.class) : null;
			} catch (Exception ignored) {
				data = null;
			}
			if (data == null) {
				data = new Data();
			}
		}
		return data;
	}

	private static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(data()), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// Not saved this time.
		}
	}

	public static boolean advancements() {
		return data().advancements;
	}

	public static boolean controls() {
		return data().controls;
	}

	public static boolean statistics() {
		return data().statistics;
	}

	public static void setAdvancements(boolean on) {
		data().advancements = on;
		save();
	}

	public static void setControls(boolean on) {
		data().controls = on;
		save();
	}

	public static void setStatistics(boolean on) {
		data().statistics = on;
		save();
	}
}
