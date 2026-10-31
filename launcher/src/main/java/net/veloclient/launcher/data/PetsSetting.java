package net.veloclient.launcher.data;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Pets (dev builds only): the catalog the Locker shows and which pet you picked. The pick goes to
 * {@code config/pets.json} in the launcher's folder, where the dev client's Pets module reads it.
 */
public final class PetsSetting {

	public record Pet(String id, String name, String accent) {
	}

	public static final List<Pet> ALL = List.of(
			new Pet("ember", "Ember Drake", "#ff3a2a"), new Pet("frost", "Frost Drake", "#38b6ff"), new Pet("shadow", "Shadow Drake", "#8a3cff"),
			new Pet("void", "Void Drake", "#c040ff"), new Pet("bone", "Bone Drake", "#6dff4a"), new Pet("ghost", "Boo the Ghost", "#78d8ff"),
			new Pet("owl", "Aurora Owl", "#3ff0c8"), new Pet("panda", "Momo the Red Panda", "#ff8a3a"), new Pet("jelly", "Nebula Jelly", "#ff5ad8"),
			new Pet("moon", "Luna the Pyjama Moon", "#7e8cff"), new Pet("shark", "Finn the Sky Shark", "#3aa8ff"),
			new Pet("reindeer", "Jingle the Reindeer", "#ff3b3b"), new Pet("drone", "Velo Drone", "#ff3040"), new Pet("kitsune", "Yuki the Kitsune", "#5ab4ff"),
			new Pet("penguin", "Pebble the Penguin", "#ffb028"), new Pet("hedgehog", "Piko the Hedgehog", "#d8a060"), new Pet("toucan", "Toco the Toucan", "#ffb020"),
			new Pet("monkey", "Kiki the Capuchin", "#e0a060"), new Pet("tiger", "Raja the Tiger Cub", "#ff9a2a"), new Pet("mini", "Mini-Me", "#ffc23a"));

	/** Run after the pick changes (the worn loadout takes it). */
	public static final List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	private PetsSetting() {
	}

	public static Optional<Pet> byId(String id) {
		return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
	}

	private static Path file() {
		return VeloPaths.config().resolve("pets.json");
	}

	/** The pet you picked, or null for none. */
	public static String equipped() {
		try {
			if (Files.exists(file())) {
				JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
				return json.has("pet") && !json.get("pet").isJsonNull() ? json.get("pet").getAsString() : null;
			}
		} catch (IOException | RuntimeException ignored) {
			// none
		}
		return null;
	}

	public static void equip(String id) {
		JsonObject json = new JsonObject();
		json.addProperty("pet", id);
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), json.toString(), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
			// stays as it was
		}
		LISTENERS.forEach(Runnable::run);
	}

	public static String name(String id) {
		return id == null ? "None" : byId(id).map(Pet::name).orElse("None");
	}
}
