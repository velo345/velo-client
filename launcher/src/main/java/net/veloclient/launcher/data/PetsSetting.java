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
 * Pets: the catalog the Locker shows and which pet you picked. The pick goes to
 * {@code config/accounts/<uuid>/pets.json} (per account), where the client's Pets module reads it.
 */
public final class PetsSetting {

	public record Pet(String id, String name, String accent) {
	}

	/** Seasonal pets: only offered in their month (the reindeer comes for Christmas). */
	private static boolean inSeason(String id) {
		return !"reindeer".equals(id) || java.time.LocalDate.now().getMonthValue() == 12;
	}

	public static final List<Pet> ALL = java.util.stream.Stream.of(
			new Pet("ember", "Ember Drake", "#ff3a2a"), new Pet("frost", "Frost Drake", "#38b6ff"), new Pet("shadow", "Shadow Drake", "#8a3cff"),
			new Pet("void", "Void Drake", "#c040ff"), new Pet("bone", "Bone Drake", "#6dff4a"), new Pet("ghost", "Boo the Ghost", "#78d8ff"),
			new Pet("owl", "Aurora Owl", "#3ff0c8"), new Pet("panda", "Momo the Red Panda", "#ff8a3a"), new Pet("jelly", "Nebula Jelly", "#ff5ad8"),
			new Pet("moon", "Luna the Pyjama Moon", "#7e8cff"), new Pet("shark", "Finn the Sky Shark", "#3aa8ff"),
			new Pet("reindeer", "Jingle the Reindeer", "#ff3b3b"), new Pet("kitsune", "Yuki the Kitsune", "#5ab4ff"),
			new Pet("penguin", "Pebble the Penguin", "#ffb028"), new Pet("hedgehog", "Piko the Hedgehog", "#d8a060"), new Pet("toucan", "Toco the Toucan", "#ffb020"),
			new Pet("monkey", "Kiki the Capuchin", "#e0a060"), new Pet("tiger", "Raja the Tiger Cub", "#ff9a2a"), new Pet("mini", "Mini-Me", "#ffc23a")).filter(p -> inSeason(p.id())).toList();

	/** Run after the pick changes (the worn loadout takes it). */
	public static final List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	private PetsSetting() {
	}

	/** A pet as a Store item ("pet_<id>" in the server's catalog): price, whether it's for sale, and its preview files (sha256). */
	public record StorePet(Pet pet, String itemId, int price, boolean inStore, String description, String sheetSha, String previewSha) {
	}

	/** The pets the server sells (or the owner can see), in the usual order; empty until the catalog has loaded. */
	public static java.util.List<StorePet> storePets() {
		java.util.List<com.google.gson.JsonObject> server = WingsCatalog.serverItems("pet");
		java.util.List<StorePet> out = new java.util.ArrayList<>();
		for (Pet pet : ALL) {
			for (com.google.gson.JsonObject o : server) {
				if (("pet_" + pet.id()).equals(o.get("id").getAsString())) {
					com.google.gson.JsonObject files = o.has("files") && o.get("files").isJsonObject() ? o.getAsJsonObject("files") : new com.google.gson.JsonObject();
					out.add(new StorePet(pet, "pet_" + pet.id(), o.has("price") ? o.get("price").getAsInt() : 2400,
							o.has("inStore") && o.get("inStore").getAsBoolean(), o.has("description") ? o.get("description").getAsString() : "",
							files.has("sheet") ? files.get("sheet").getAsString() : null, files.has("preview") ? files.get("preview").getAsString() : null));
				}
			}
		}
		return out;
	}

	public static Optional<StorePet> storePet(String id) {
		return storePets().stream().filter(p -> p.pet().id().equals(id)).findFirst();
	}

	/** Bought in the Store (the owner has every pet). */
	public static boolean owns(String id) {
		return StoreOwnership.owns("pet_" + id);
	}

	public static Optional<Pet> byId(String id) {
		return ALL.stream().filter(p -> p.id().equals(id)).findFirst();
	}

	private static Path file() {
		return VeloPaths.accountConfig("pets.json");
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
