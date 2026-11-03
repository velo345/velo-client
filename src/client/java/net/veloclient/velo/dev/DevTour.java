package net.veloclient.velo.dev;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.dev.notes.Note;
import net.veloclient.velo.dev.notes.NoteEditScreen;
import net.veloclient.velo.dev.notes.NotePlacer;
import net.veloclient.velo.dev.notes.NoteStore;
import net.veloclient.velo.dev.notes.NotesListScreen;
import net.veloclient.velo.dev.notes.NotesMc;
import net.veloclient.velo.dev.storage.StorageHighlight;
import net.veloclient.velo.dev.storage.StorageIndex;
import net.veloclient.velo.dev.storage.StorageScreen;
import net.veloclient.velo.dev.storage.StorageStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Dev-only screenshot scenes for dev-build features ({@code -Dvelo.screenshotTour.scenes=dev:storage,dev:notes}),
 * called by the core ScreenshotTour. Fills in demo data around you (run it with {@code -Dvelo.home=<temp dir>} so the
 * demo never mixes with real data).
 */
public final class DevTour {

	private DevTour() {
	}

	public static List<Object[]> scene(String name, Consumer<String> shot) {
		return switch (name) {
			case "storage" -> storage(shot);
			case "notes" -> notes(shot);
			case "notesview" -> notesView(shot);
			case "pets" -> pets(shot);
			case "perch" -> perch(shot);
			default -> List.of();
		};
	}

	private static Object[] step(int delay, Runnable run) {
		return new Object[] {delay, run};
	}

	private static MinecraftClient client() {
		return MinecraftClient.getInstance();
	}

	// ---- Pets: riding ----

	private static void hud(boolean hidden) {
		//? if <26.1 {
		client().options.hudHidden = hidden;
		//?}
		//? if >=26.2 {
		/*if (client().gui.hud.isHidden() != hidden) {
			client().gui.hud.toggle();
		}
		*///?}
	}

	/** Each pet ridden in turn: side, front, behind (orbit camera) and first person (the lifted view). */
	private static List<Object[]> pets(Consumer<String> shot) {
		List<Object[]> steps = new ArrayList<>();
		steps.add(step(0, () -> {
			tidyWorld();
			hud(true);
			net.veloclient.velo.module.ModuleRegistry.get("free-look").ifPresent(m -> m.setEnabled(true));
			// Nothing in the way of judging the seat: no armour, wings or aura, a normal field of view.
			net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(null);
			net.veloclient.velo.client.cosmetics.aura.AuraManager.setPreview(null);
			if (client().player != null) {
				//? if <26.1 {
				client().player.networkHandler.sendChatCommand("clear @s");
				//?} else {
				/*client().player.connection.sendCommand("clear @s");
				*///?}
			}
			//? if >=26.2 {
			/*client().options.fov().set(55);
			*///?}
		}));
		for (String pet : List.of("ember", "tiger", "owl", "jelly", "panda")) {
			steps.add(step(10, () -> net.veloclient.velo.dev.pets.PetsModule.tourRide(pet, true)));
			steps.add(step(40, () -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 90f, 3.6)));
			steps.add(step(25, () -> shot.accept("pet-ride-" + pet + "-side")));
			steps.add(step(2, () -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 150f, 3.6)));
			steps.add(step(20, () -> shot.accept("pet-ride-" + pet + "-front")));
			steps.add(step(2, () -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 20f, 3.6)));
			steps.add(step(20, () -> shot.accept("pet-ride-" + pet + "-back")));
			steps.add(step(2, () -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(false, 0f)));
			steps.add(step(20, () -> shot.accept("pet-ride-" + pet + "-first")));
		}
		steps.add(step(2, () -> {
			net.veloclient.velo.dev.pets.PetsModule.tourRide(null, false);
			hud(false);
		}));
		return steps;
	}

	/** Mini-Me hopping onto your shoulder (a picture every 4 ticks), then the held pose a while later. */
	private static List<Object[]> perch(Consumer<String> shot) {
		List<Object[]> steps = new ArrayList<>();
		steps.add(step(0, () -> {
			tidyWorld();
			hud(true);
			net.veloclient.velo.module.ModuleRegistry.get("free-look").ifPresent(m -> m.setEnabled(true));
			net.veloclient.velo.dev.pets.PetsModule.tourRide("mini", false);
			//? if >=26.2 {
			/*client().options.fov().set(55);
			*///?}
		}));
		steps.add(step(5, () -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 140f, 3.2)));
		steps.add(step(395, () -> shot.accept("perch-before")));
		for (int i = 0; i < 60; i++) {
			int k = i;
			steps.add(step(4, () -> shot.accept(String.format("perch-%02d", k))));
		}
		for (int i = 0; i < 6; i++) {
			int k = i;
			steps.add(step(40, () -> shot.accept("perch-held-" + k)));
		}
		steps.add(step(2, () -> {
			net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(false, 0f);
			net.veloclient.velo.dev.pets.PetsModule.tourRide(null, false);
			hud(false);
		}));
		return steps;
	}

	// ---- Storage Memory ----

	private static StorageScreen storageScreen;

	/** Daylight and no waypoint markers in the way of the pictures. */
	private static void tidyWorld() {
		net.veloclient.velo.module.ModuleRegistry.get("waypoints").ifPresent(m -> m.setEnabled(false));
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player != null) {
			//? if <26.1 {
			client.player.networkHandler.sendChatCommand("time set 1000");
			client.player.networkHandler.sendChatCommand("weather clear");
			//?} else {
			/*client.player.connection.sendCommand("time set 1000");
			client.player.connection.sendCommand("weather clear");
			*///?}
		}
	}

	private static List<Object[]> storage(Consumer<String> shot) {
		List<Object[]> steps = new ArrayList<>();
		steps.add(step(0, DevTour::tidyWorld));
		steps.add(step(10, DevTour::seedStorage));
		steps.add(step(5, () -> {
			StorageScreen.tourSetTab(0);
			storageScreen = new StorageScreen(null);
			client().setScreen(storageScreen);
		}));
		steps.add(step(30, () -> shot.accept("dev-storage-items")));
		steps.add(step(2, () -> storageScreen.tourSearch("iron")));
		steps.add(step(15, () -> shot.accept("dev-storage-search-iron")));
		steps.add(step(2, () -> {
			storageScreen.tourSearch("piston");
			storageScreen.tourSelect("minecraft:piston");
		}));
		steps.add(step(25, () -> shot.accept("dev-storage-recipe-route")));
		steps.add(step(2, () -> storageScreen.tourSearch("mending")));
		steps.add(step(15, () -> shot.accept("dev-storage-search-mending")));
		String[] tabs = {"containers", "traders", "enchants", "stations"};
		for (int i = 0; i < tabs.length; i++) {
			int index = i + 1;
			String tab = tabs[i];
			steps.add(step(2, () -> {
				storageScreen.tourSearch("");
				storageScreen.tourTab(index);
			}));
			steps.add(step(15, () -> shot.accept("dev-storage-" + tab)));
		}
		// Follow a route: the beam over the first stop and the line under the crosshair.
		steps.add(step(2, () -> {
			StorageStore.World world = StorageStore.current();
			StorageIndex index = new StorageIndex(world, true, List.of());
			StorageIndex.Route route = index.route(List.of(List.of("minecraft:iron_ingot"), List.of("minecraft:redstone")), List.of(70, 20),
					List.of("Iron Ingot", "Redstone"));
			if (route != null) {
				StorageHighlight.route(StorageStore.currentWorldKey(), route.stops(), Set.of("minecraft:iron_ingot", "minecraft:redstone"));
			}
			client().setScreen(null);
		}));
		steps.add(step(30, () -> shot.accept("dev-storage-route-world")));
		steps.add(step(2, StorageHighlight::clear));
		return steps;
	}

	private static StorageStore.Item item(String id, int count) {
		StorageStore.Item item = new StorageStore.Item();
		item.id = id;
		item.c = count;
		return item;
	}

	private static void seedStorage() {
		StorageStore.World world = StorageStore.current();
		double[] me = net.veloclient.velo.dev.storage.StorageMc.playerPos();
		String dim = StorageStore.here();
		if (world == null || me == null) {
			return;
		}
		int px = (int) Math.floor(me[0]);
		int py = (int) Math.floor(me[1]);
		int pz = (int) Math.floor(me[2]);
		long now = System.currentTimeMillis();
		// Out of the 12-block "is it still there" scan, so the demo containers stay.
		StorageStore.Item shulker = item("minecraft:blue_shulker_box", 1);
		StorageStore.Item bundle = item("minecraft:bundle", 1);
		bundle.in = new ArrayList<>(List.of(item("minecraft:redstone", 12), item("minecraft:gold_nugget", 30)));
		shulker.in = new ArrayList<>(List.of(item("minecraft:iron_ingot", 64), item("minecraft:redstone", 32), bundle));
		container(world, dim, px + 15, py, pz + 4, "minecraft:chest", now - 120_000, null,
				item("minecraft:iron_ingot", 23), item("minecraft:cobblestone", 64), item("minecraft:cobblestone", 64), shulker, item("minecraft:oak_log", 18));
		StorageStore.Item book = item("minecraft:enchanted_book", 1);
		book.e = new ArrayList<>(List.of("minecraft:mending=1"));
		container(world, dim, px - 14, py, pz + 9, "minecraft:barrel", now - 3_600_000, "Steve",
				item("minecraft:oak_planks", 40), item("minecraft:stick", 16), book, item("minecraft:glass", 32));
		StorageStore.Item sword = item("minecraft:diamond_sword", 1);
		sword.n = "Excalibur";
		sword.e = new ArrayList<>(List.of("minecraft:sharpness=5", "minecraft:unbreaking=3"));
		container(world, dim, px + 28, py + 1, pz - 22, "minecraft:chest", now - 86_400_000, null,
				item("minecraft:diamond", 9), sword, item("minecraft:iron_ingot", 40), item("minecraft:redstone", 6));
		world.ender = new ArrayList<>(List.of(item("minecraft:iron_block", 20), book, item("minecraft:ender_pearl", 16)));
		world.enderTime = now - 600_000;

		StorageStore.Trader librarian = new StorageStore.Trader();
		librarian.uuid = "demo-librarian";
		librarian.dim = dim;
		librarian.x = px - 8;
		librarian.y = py;
		librarian.z = pz - 16;
		librarian.profession = "minecraft:librarian";
		librarian.level = 3;
		librarian.time = now - 7_200_000;
		librarian.offers.add(offer(item("minecraft:emerald", 24), item("minecraft:book", 1), book));
		librarian.offers.add(offer(item("minecraft:paper", 24), null, item("minecraft:emerald", 1)));
		librarian.offers.add(offer(item("minecraft:emerald", 1), null, item("minecraft:glass", 4)));
		world.traders.put(librarian.uuid, librarian);

		station(world, dim, px + 10, py, pz + 2, "minecraft:crafting_table", now);
		station(world, dim, px + 11, py, pz + 3, "minecraft:furnace", now);
		station(world, dim, px - 10, py, pz - 3, "minecraft:enchanting_table", now);
		station(world, dim, px - 11, py, pz - 4, "minecraft:anvil", now);
		station(world, dim, px + 10, py, pz - 10, "minecraft:ender_chest", now);
		StorageStore.markCurrentDirty();
	}

	private static void container(StorageStore.World world, String dim, int x, int y, int z, String block, long time, String by, StorageStore.Item... items) {
		StorageStore.Container c = new StorageStore.Container();
		c.dim = dim;
		c.x = x;
		c.y = y;
		c.z = z;
		c.block = block;
		c.time = time;
		c.by = by;
		c.items = new ArrayList<>(List.of(items));
		world.containers.put(StorageStore.posKey(dim, x, y, z), c);
	}

	private static StorageStore.Offer offer(StorageStore.Item a, StorageStore.Item b, StorageStore.Item r) {
		StorageStore.Offer offer = new StorageStore.Offer();
		offer.a = a;
		offer.b = b;
		offer.r = r;
		offer.max = 12;
		return offer;
	}

	private static void station(StorageStore.World world, String dim, int x, int y, int z, String block, long time) {
		StorageStore.Station s = new StorageStore.Station();
		s.dim = dim;
		s.x = x;
		s.y = y;
		s.z = z;
		s.block = block;
		s.time = time;
		world.stations.put(StorageStore.posKey(dim, x, y, z), s);
	}

	// ---- World Notes ----

	private static final List<Note> DEMO = new ArrayList<>();
	private static NoteEditScreen editScreen;

	private static List<Object[]> notes(Consumer<String> shot) {
		List<Object[]> steps = new ArrayList<>();
		steps.add(step(0, DevTour::tidyWorld));
		steps.add(step(10, DevTour::seedNotes));
		steps.add(step(40, () -> shot.accept("dev-notes-world")));
		steps.add(step(2, () -> client().setScreen(new NotesListScreen(null))));
		steps.add(step(30, () -> shot.accept("dev-notes-list")));
		steps.add(step(2, () -> {
			editScreen = new NoteEditScreen(null, DEMO.get(0), false);
			client().setScreen(editScreen);
		}));
		steps.add(step(30, () -> shot.accept("dev-notes-edit-note")));
		steps.add(step(2, () -> editScreen.tourTab(1)));
		steps.add(step(15, () -> shot.accept("dev-notes-edit-look")));
		steps.add(step(2, () -> editScreen.tourTab(2)));
		steps.add(step(15, () -> shot.accept("dev-notes-edit-share")));
		steps.add(step(2, () -> {
			editScreen = new NoteEditScreen(null, DEMO.get(2), false);
			client().setScreen(editScreen);
		}));
		steps.add(step(10, () -> editScreen.tourTab(3)));
		steps.add(step(20, () -> shot.accept("dev-notes-edit-board")));
		steps.add(step(2, () -> NotePlacer.start(DEMO.get(1), placed -> { }, () -> { })));
		steps.add(step(25, () -> shot.accept("dev-notes-move")));
		steps.add(step(2, () -> client().setScreen(null)));
		return steps;
	}

	/**
	 * The world cards from several views - straight on, turned right / left, looking down, from far back - with one
	 * fixed wall note at an angle, to check they sit still in the world (no sliding / skewing while you turn).
	 */
	private static List<Object[]> notesView(Consumer<String> shot) {
		List<Object[]> steps = new ArrayList<>();
		float[] start = new float[2];
		steps.add(step(0, DevTour::tidyWorld));
		steps.add(step(10, () -> {
			seedNotes();
			if (DEMO.size() > 3) {
				Note wall = DEMO.get(3);
				wall.face = false;
				wall.yaw = look()[0] + 40; // a sign on a wall, turned 40 degrees away from you
			}
			float[] now = look();
			start[0] = now[0];
			start[1] = now[1];
		}));
		steps.add(step(40, () -> shot.accept("dev-notesview-front")));
		steps.add(step(2, () -> turn(start[0] + 25, start[1])));
		steps.add(step(8, () -> shot.accept("dev-notesview-right")));
		steps.add(step(2, () -> turn(start[0] - 35, start[1])));
		steps.add(step(8, () -> shot.accept("dev-notesview-left")));
		steps.add(step(2, () -> turn(start[0], start[1] + 30)));
		steps.add(step(8, () -> shot.accept("dev-notesview-down")));
		steps.add(step(2, () -> {
			turn(start[0], start[1]);
			back(14);
		}));
		steps.add(step(12, () -> shot.accept("dev-notesview-far")));
		steps.add(step(2, () -> turn(start[0] + 20, start[1])));
		steps.add(step(8, () -> shot.accept("dev-notesview-far-turned")));
		return steps;
	}

	private static float[] look() {
		var player = client().player;
		//? if <26.1 {
		return player == null ? new float[2] : new float[] {player.getYaw(), player.getPitch()};
		//?} else {
		/*return player == null ? new float[2] : new float[] {player.getYRot(), player.getXRot()};
		*///?}
	}

	private static void turn(float yaw, float pitch) {
		var player = client().player;
		if (player == null) {
			return;
		}
		//? if <26.1 {
		player.setYaw(yaw);
		player.setPitch(pitch);
		//?} else {
		/*player.setYRot(yaw);
		player.setXRot(pitch);
		*///?}
	}

	/** Steps the player {@code blocks} straight back (a server-side teleport, so it sticks). */
	private static void back(int blocks) {
		var player = client().player;
		if (player == null) {
			return;
		}
		float yaw = look()[0];
		//? if <26.1 {
		player.networkHandler.sendChatCommand("tp @s ^ ^ ^-" + blocks);
		//?} else {
		/*player.connection.sendCommand("tp @s ^ ^ ^-" + blocks);
		*///?}
	}

	/** Five notes in an arc in front of you, one per look. */
	private static void seedNotes() {
		DEMO.clear();
		double[] eye = NotesMc.eye();
		if (eye == null) {
			return;
		}
		double len = Math.max(0.001, Math.hypot(eye[3], eye[5]));
		double fx = eye[3] / len;
		double fz = eye[5] / len;
		double rx = -fz;
		double rz = fx;
		double[][] spots = {{-2.6, 4.2, 0.1}, {-0.8, 5.4, 0.5}, {0.9, 4.4, -0.1}, {2.7, 5.2, 0.35}, {4.0, 6.4, 0.8}};
		String[] styles = {"paper", "sticky", "glass", "sign", "neon"};
		int[] colors = {0xFFF5D76E, 0xFFF06292, 0xFF64B5F6, 0xFFE0E0E0, 0xFF4DD0E1};
		for (int i = 0; i < 5; i++) {
			Note note = NoteStore.draft();
			note.style = styles[i];
			note.color = colors[i];
			note.x = eye[0] + fx * spots[i][1] + rx * spots[i][0];
			note.y = eye[1] - 0.3 + spots[i][2];
			note.z = eye[2] + fz * spots[i][1] + rz * spots[i][0];
			DEMO.add(note);
		}
		Note todo = DEMO.get(0);
		todo.title = "Base to-do";
		todo.icon = "minecraft:iron_pickaxe";
		todo.text = "Iron farm needs 2 more golems. @Steve can you bring lava?";
		todo.checks.add(check("Wall around the farm", true, "Steve"));
		todo.checks.add(check("Villager breeder", true, "You"));
		todo.checks.add(check("Storage room sorting", false, null));
		todo.checks.add(check("Nether portal hub", false, null));

		Note ideas = DEMO.get(1);
		ideas.title = "Farm ideas";
		ideas.text = "Bamboo + kelp for fuel\nSugar cane next to the river";

		Note raid = DEMO.get(2);
		raid.title = "Raid plan";
		raid.icon = "minecraft:map";
		raid.text = "Meet at the gate, totems ready";
		raid.board.add(stroke(0xFF23211D, 3, 30, 40, 120, 40, 120, 120, 30, 120, 30, 40));
		raid.board.add(stroke(0xFFE5484D, 4, 140, 80, 200, 80, 190, 70, 200, 80, 190, 90));
		raid.board.add(stroke(0xFF3E8BFF, 6, 60, 80));
		raid.board.add(stroke(0xFF46A758, 3, 210, 30, 230, 50, 220, 70, 240, 100));

		Note shop = DEMO.get(3);
		shop.title = "Shop  ->";
		shop.text = "Diamonds 2 for 1 emerald";
		shop.face = false;
		double[] pos = net.veloclient.velo.dev.storage.StorageMc.playerPos();
		shop.yaw = pos == null ? 0 : pos[3] - 18;

		Note party = DEMO.get(4);
		party.title = "Party meetup 20:00";
		party.text = "Bring your elytra";
		party.unread = true;
		party.mine = false;
		party.local = false;
		party.share = "people";
		party.ownerName = "Alex";

		for (Note note : DEMO) {
			if (note.mine) {
				NoteStore.save(note, saved -> { }, error -> { });
			}
		}
		// The "from Alex" one only exists for the picture.
		NoteStore.tourAdd(party);
	}

	private static Note.Check check(String text, boolean done, String by) {
		Note.Check check = new Note.Check();
		check.id = Integer.toHexString(text.hashCode() & 0xFFFFFF);
		check.text = text;
		check.done = done;
		check.doneBy = by;
		return check;
	}

	private static Note.Stroke stroke(int color, int width, int... points) {
		Note.Stroke stroke = new Note.Stroke();
		stroke.c = color;
		stroke.w = width;
		stroke.p = points;
		return stroke;
	}
}
