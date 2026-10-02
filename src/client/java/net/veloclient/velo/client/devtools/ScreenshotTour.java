package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.gui.BackgroundQueueSessionsScreen;
import net.veloclient.velo.client.gui.FriendsScreen;
import net.veloclient.velo.client.gui.WaypointEditScreen;
import net.veloclient.velo.client.gui.WaypointsScreen;
import net.veloclient.velo.client.modules.queue.BackgroundQueueManager;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.client.waypoints.Waypoint;
import net.veloclient.velo.client.waypoints.WaypointIcons;
import net.veloclient.velo.client.waypoints.WaypointManager;
import net.veloclient.velo.module.ModuleRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-only: with {@code -Dvelo.screenshotTour=<folder>} (and a world opened via quick play), fills
 * friends/background-queue demo data, places sample waypoints, then opens each new screen and saves
 * a screenshot of it into the folder before quitting. Used to review UI changes without clicking
 * through them by hand. Does nothing unless that property is set.
 */
public final class ScreenshotTour {

	private static Path folder;
	private static int ticksInWorld;
	private static boolean prepared;
	private static String demoQueueKey;

	private ScreenshotTour() {
	}

	public static void initIfRequested() {
		String target = System.getProperty("velo.screenshotTour");
		if (target == null || target.isBlank()) {
			return;
		}
		folder = Path.of(target);
		VeloClient.LOGGER.info("Velo screenshot tour enabled -> {}", folder);
		ClientTickEvents.END_CLIENT_TICK.register(ScreenshotTour::tick);
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null) {
			return;
		}
		ticksInWorld++;
		int t = ticksInWorld;
		if (t == 100 && !prepared) {
			prepared = true;
			prepare(client);
		}
		switch (t) {
			case 160 -> shot(client, "01-world-waypoints-hud");
			case 170 -> showPopups();
			case 190 -> shot(client, "02-popups-while-playing");
			case 196 -> {
				client.setScreen(new WaypointsScreen(null));
			}
			case 199 -> shot(client, "02b-popups-over-a-menu");
			case 200 -> {
				NotificationOverlay.clear();
				FriendsScreen.openChat(DEMO_FRIENDS[0][0]);
			}
			case 240 -> shot(client, "03-friends-chat");
			case 250 -> FriendsScreen.openRequests();
			case 270 -> shot(client, "04-friends-requests");
			case 280 -> FriendsScreen.openNotifications();
			case 300 -> shot(client, "05-friends-notification-settings");
			case 310 -> client.setScreen(new WaypointsScreen(null));
			case 350 -> shot(client, "06-waypoints-menu");
			case 360 -> {
				List<Waypoint> here = WaypointManager.visibleHere();
				if (!here.isEmpty()) {
					client.setScreen(WaypointEditScreen.edit(null, here.get(0)));
				}
			}
			case 400 -> shot(client, "07-waypoint-editor");
			case 410 -> client.setScreen(new BackgroundQueueSessionsScreen(null, demoQueueKey));
			case 450 -> shot(client, "08-background-queue");
			case 460 -> client.setScreen(null);
			case 520 -> shot(client, "09-hud-modules");
			case 540 -> client.setScreen(new net.veloclient.velo.client.gui.ModMenuScreen());
			case 575 -> shot(client, "10-mod-menu");
			case 580 -> ModuleRegistry.all().stream().filter(m -> m.id().equals("fps-counter")).findFirst()
					.ifPresent(m -> client.setScreen(new net.veloclient.velo.client.gui.window.ModuleConfigScreen(m, null)));
			case 610 -> shot(client, "11-module-settings");
			case 615 -> client.setScreen(new net.veloclient.velo.client.gui.SettingsTabScreen(null));
			case 640 -> shot(client, "12-settings");
			case 645 -> client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true));
			case 665 -> shot(client, "13-pause-menu");
			case 680 -> client.setScreen(new net.veloclient.velo.client.gui.CapeEquipScreen(null));
			case 710 -> shot(client, "14-capes");
			case 715 -> client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(null));
			case 745 -> shot(client, "15-store");
			case 750 -> net.veloclient.velo.client.store.StoreCatalog.all().stream().findFirst().ifPresent(item ->
					client.setScreen(new net.veloclient.velo.client.gui.store.StoreItemDetailScreen(null, item)));
			case 780 -> shot(client, "16-store-item");
			case 785 -> client.setScreen(new net.veloclient.velo.client.gui.SchematicsScreen(null));
			case 810 -> shot(client, "17-schematics");
			case 820 -> stop(client);
			default -> {
			}
		}
	}

	private static final String[][] DEMO_FRIENDS = {
			{"069a79f444e94726a5befca90e38aaf5", "Notch", "server", "play.hypixel.net"},
			{"853c80ef3c3749fdaa49938b674adae6", "jeb_", "singleplayer", "Survival Island"},
			{"61699b2ed3274a019f1e0ea8c3f06bc6", "Dinnerbone", "launcher", null},
			{"e6b5c088068044df9e1b9bf11792291b", "Grumm", null, null},
	};

	private static void prepare(MinecraftClient client) {
		// Demo friends.
		SocialClient.State state = new SocialClient.State();
		state.me = new SocialClient.Me();
		state.me.uuid = client.player.getUuid().toString().replace("-", "");
		state.me.username = client.player.getName().getString();
		List<SocialClient.Friend> friends = new ArrayList<>();
		for (String[] row : DEMO_FRIENDS) {
			SocialClient.Friend friend = new SocialClient.Friend();
			friend.uuid = row[0];
			friend.username = row[1];
			friend.online = row[2] != null;
			if (row[2] != null) {
				friend.activity = new SocialClient.Activity();
				friend.activity.kind = row[2];
				friend.activity.detail = row[3];
			}
			friend.lastSeen = System.currentTimeMillis() - 3 * 3600_000L;
			friend.unread = row[1].equals("jeb_") ? 2 : 0;
			friends.add(friend);
		}
		state.friends = friends;
		SocialClient.Request request = new SocialClient.Request();
		request.uuid = "7125ba8b1c864508b92bb5c042ccfe2b";
		request.username = "KrisJelbring";
		request.time = System.currentTimeMillis() - 5 * 60_000;
		state.incoming = List.of(request);
		SocialClient.Request outgoing = new SocialClient.Request();
		outgoing.uuid = "f8cdb6839e9043eea81939f85d9c5d69";
		outgoing.username = "Marc";
		outgoing.time = System.currentTimeMillis() - 26 * 3600_000L;
		state.outgoing = List.of(outgoing);
		state.blocked = List.of();
		Map<String, List<SocialClient.Message>> conversations = new HashMap<>();
		List<SocialClient.Message> chat = new ArrayList<>();
		long now = System.currentTimeMillis();
		chat.add(message(DEMO_FRIENDS[0][0], state.me.uuid, "yo, you on tonight?", now - 40 * 60_000));
		chat.add(message(state.me.uuid, DEMO_FRIENDS[0][0], "yeah, just finishing my base", now - 39 * 60_000));
		chat.add(message(DEMO_FRIENDS[0][0], state.me.uuid, "nice - I'm in the bedwars queue on hypixel, join me when you're done", now - 3 * 60_000));
		SocialClient.Message shared = message(state.me.uuid, DEMO_FRIENDS[0][0], "Shared a waypoint: Base", now - 2 * 60_000);
		shared.kind = "waypoint";
		shared.waypoint = Map.of("name", "Base", "x", 214.5, "y", 71.0, "z", -388.5, "dimension", "minecraft:overworld", "color", 0xFF0090FF, "icon", "home");
		chat.add(shared);
		SocialClient.Message theirs = message(DEMO_FRIENDS[0][0], state.me.uuid, "Shared a waypoint: Ancient City", now - 60_000);
		theirs.kind = "waypoint";
		theirs.waypoint = Map.of("name", "Ancient City", "x", -1204.0, "y", -42.0, "z", 655.0, "dimension", "minecraft:overworld", "color", 0xFF12A594, "icon", "spawner");
		chat.add(theirs);
		conversations.put(DEMO_FRIENDS[0][0], chat);
		SocialClient.loadDemo(state, conversations);

		// Demo background session.
		BackgroundQueueManager.addDemoSession("2b2t", "2b2t.org", 214,
				List.of("! Running in the background - you're still connected.", "<Steve> anyone selling elytras?",
						"Position in queue: 216", "<Alex> 2b2t is full again lol", "Position in queue: 214",
						"> /msg Alex o/", "Alex whispers to you: hey!"), "Position in queue: 214 - estimated time: 18m");
		demoQueueKey = "2b2t";

		// Sample waypoints around the player.
		double x = Math.floor(client.player.getX());
		double y = Math.floor(client.player.getY());
		double z = Math.floor(client.player.getZ());
		String world = WaypointManager.currentWorldKey();
		String dimension = net.veloclient.velo.client.util.ClientCompat.dimensionId();
		if (world != null && WaypointManager.inWorld(world).isEmpty()) {
			WaypointManager.add(new Waypoint("Base", world, dimension, x + 18.5, y, z - 34.5, 0xFF0090FF, "home"));
			WaypointManager.add(new Waypoint("Diamond Mine", world, dimension, x - 42.5, y - 3, z - 70.5, 0xFF12A594, "pickaxe"));
			WaypointManager.add(new Waypoint("Villager Trading", world, dimension, x + 75.5, y + 2, z - 160.5, 0xFF46A758, "village"));
			WaypointManager.add(new Waypoint("Nether Portal", world, dimension, x - 140.5, y, z - 420.5, 0xFF8E4EC6, "portal"));
			WaypointManager.add(new Waypoint("Spawn", world, dimension, x + 900.5, y, z + 40.5, 0xFFFFC53D, WaypointIcons.NONE));
			WaypointManager.recordDeath(x - 12, y, z - 22);
		}

		// HUD modules worth showing.
		for (String id : List.of("held-item", "totem-counter", "armor-durability", "minimap", "waypoints", "friends")) {
			ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(true));
		}
		equipDemoItems(client);
	}

	private static SocialClient.Message message(String from, String to, String text, long time) {
		SocialClient.Message message = new SocialClient.Message();
		message.id = time;
		message.from = from;
		message.to = to;
		message.text = text;
		message.time = time;
		message.kind = "text";
		return message;
	}

	private static void showPopups() {
		NotificationOverlay.show(new NotificationOverlay.Toast(DEMO_FRIENDS[1][0], "jeb_", "jeb_  (2 new)",
				"found a woodland mansion!! come look", 20000, () -> FriendsScreen.openChat(DEMO_FRIENDS[1][0]), null, null));
		NotificationOverlay.show(new NotificationOverlay.Toast("7125ba8b1c864508b92bb5c042ccfe2b", "KrisJelbring", "KrisJelbring",
				"wants to be your friend", 20000, FriendsScreen::openRequests, () -> {
				}, () -> {
				}));
		NotificationOverlay.show(new NotificationOverlay.Toast(DEMO_FRIENDS[0][0], "Notch", "Notch is online",
				"Playing on play.hypixel.net", 20000, () -> FriendsScreen.openChat(DEMO_FRIENDS[0][0]), () -> {
				}, () -> {
				}).labels("Join", "Chat"));
	}

	private static void equipDemoItems(MinecraftClient client) {
		//? if >=26.2 {
		/*var player = client.player;
		player.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE, 12));
		player.getInventory().setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING, 1));
		player.getInventory().setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING, 1));
		player.getInventory().setItem(40, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING, 1));
		player.getInventory().setSelectedSlot(0);
		var chest = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE);
		chest.setDamageValue(300);
		player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET));
		player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, chest);
		player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_LEGGINGS));
		player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_BOOTS));
		player.setYRot(180f);
		player.setXRot(4f);
		*///?}
	}

	private static void shot(MinecraftClient client, String name) {
		//? if >=26.2 {
		/*try {
			Files.createDirectories(folder);
			Path file = folder.resolve(name + ".png");
			net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
				try (image) {
					image.writeToFile(file);
					VeloClient.LOGGER.info("Velo screenshot tour saved {}", file);
				} catch (Exception e) {
					VeloClient.LOGGER.error("Velo screenshot tour: saving {} failed", file, e);
				}
			});
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo screenshot tour: {} failed", name, e);
		}
		*///?} else {
		VeloClient.LOGGER.warn("Velo screenshot tour only captures on 26.2 ({} skipped)", name);
		//?}
	}

	private static void stop(MinecraftClient client) {
		VeloClient.LOGGER.info("Velo screenshot tour finished");
		//? if >=26.1 {
		/*client.stop();
		*///?} else {
		client.scheduleStop();
		//?}
	}
}
