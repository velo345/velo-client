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
		if (SCENES != null) {
			runScenes(client, t);
			return;
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
			case 580 -> ModuleRegistry.all().stream().filter(m -> m.id().equals("performance-boost")).findFirst()
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
			case 815 -> {
				net.veloclient.velo.client.network.StoreClient.loadDemo();
				client.setScreen(new net.veloclient.velo.client.gui.store.BuyCoinsScreen(null));
			}
			case 850 -> shot(client, "18-velo-coins");
			case 855 -> client.setScreen(new net.veloclient.velo.client.gui.ModMenuScreen());
			case 880 -> shot(client, "19-mod-menu-sidebar");
			case 885 -> client.setScreen(null);
			case 920 -> shot(client, "20-hud-auto-layout");
			// A fresh default layout with a busy set of HUD modules - nothing may overlap.
			case 925 -> {
				for (var module : ModuleRegistry.all()) {
					if (module instanceof net.veloclient.velo.client.hud.HudModule hud) {
						hud.position().resetToDefault();
					}
				}
				for (String id : List.of("fps-counter", "ping-display", "coordinates", "clock", "keystrokes", "mouse-buttons",
						"armor-durability", "potion-timers", "totem-counter", "minimap", "held-item", "scoreboard-hud")) {
					ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(true));
				}
			}
			case 945 -> shot(client, "21-hud-default-layout");
			case 950 -> {
				// The new styles, in the free-look shots.
				setChoice("mouse-buttons", "Style", "Mouse");
				setChoice("armor-durability", "Style", "Inventory Slots");
				ModuleRegistry.get("free-look").ifPresent(m -> m.setEnabled(true));
				net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 0f);
			}
			case 975 -> shot(client, "22-freelook-front");
			case 980 -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 180f);
			case 1005 -> shot(client, "23-freelook-back");
			case 1010 -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 90f);
			case 1035 -> shot(client, "24-freelook-side");
			case 1040 -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(false, 0f);
			// Hearts & Hunger: survival with partial health, absorption, food and saturation.
			case 1045 -> {
				statusBarsSetup(client);
				ModuleRegistry.get("hearts").ifPresent(m -> m.setEnabled(true));
				ModuleRegistry.get("hunger").ifPresent(m -> m.setEnabled(true));
				ModuleRegistry.get("armor-bar").ifPresent(m -> m.setEnabled(true));
				ModuleRegistry.get("xp-bar").ifPresent(m -> m.setEnabled(true));
				ModuleRegistry.get("hotbar").ifPresent(m -> m.setEnabled(true));
				setColor("hearts", "Heart Color", 0xFF3D8BFF);
			}
			case 1075 -> shot(client, "25-status-bars");
			case 1080 -> {
				setChoice("hunger", "Food Icon Style", "Recolored");
				setColor("hunger", "Food Color (Recolored)", 0xFFE0A030);
				setColor("hearts", "Heart Color", 0xFFE3262B);
			}
			case 1100 -> shot(client, "26-status-bars-recolored");
			case 1105 -> {
				importTestIcons();
				setChoice("hearts", "Style", "Custom Drawing");
				setChoice("hunger", "Food Icon Style", "Custom Drawing");
				setChoice("armor-bar", "Style", "Custom Drawing");
			}
			case 1130 -> shot(client, "27-status-bars-custom");
			case 1140 -> net.veloclient.velo.client.gui.StatusIconEditor.open(null, "heart", "Heart", 0xFFE3262B);
			case 1165 -> shot(client, "28-pixel-editor");
			case 1170 -> net.veloclient.velo.client.crosshair.CrosshairManager.library().values().stream().findFirst()
					.ifPresent(def -> client.setScreen(net.veloclient.velo.client.gui.CrosshairEditorScreen.create(null, def)));
			case 1195 -> shot(client, "29-crosshair-editor");
			case 1200 -> client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(null));
			case 1225 -> shot(client, "30a-store-anim");
			case 1229 -> shot(client, "30b-store-anim");
			case 1232 -> client.setScreen(new net.veloclient.velo.client.gui.ThemeEditorScreen(null));
			case 1255 -> shot(client, "31-theme-editor");
			case 1260 -> {
				statsForTour = new net.veloclient.velo.client.gui.VeloStatsScreen(null);
				client.setScreen(statsForTour);
			}
			case 1290 -> shot(client, "32-stats-overview");
			case 1295 -> {
				if (statsForTour != null) {
					statsForTour.showTabForTour(2);
				}
			}
			case 1310 -> shot(client, "33-stats-items");
			case 1315 -> client.setScreen(new net.veloclient.velo.client.gui.VeloKeybindsScreen(null));
			case 1340 -> shot(client, "34-keybinds");
			case 1345 -> client.setScreen(null);
			case 1350 -> {
				restoreCreative(client);
				try {
					net.veloclient.velo.client.hud.StatusBarIcons.delete("heart");
					net.veloclient.velo.client.hud.StatusBarIcons.delete("food");
					net.veloclient.velo.client.hud.StatusBarIcons.delete("armor");
				} catch (Exception ignored) {
					// best effort
				}
			}
			case 1360 -> stop(client);
			default -> {
			}
		}
	}

	// ---- Targeted runs: -Dvelo.screenshotTour.scenes=rshift,stats  (only these, then quit) ----

	private static final String SCENES = System.getProperty("velo.screenshotTour.scenes");
	private static net.veloclient.velo.client.gui.VeloAdvancementsScreen advForTour;
	private static net.veloclient.velo.client.gui.ModMenuScreen menuForTour;
	private static java.util.ArrayDeque<Object[]> queue;
	private static int nextAt;

	/** Each scene = steps of {delayTicks, Runnable}. Add a scene here instead of running the whole tour. */
	private static List<Object[]> scene(MinecraftClient client, String name) {
		return switch (name) {
			case "rshift" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.ModMenuScreen())},
					new Object[] {30, (Runnable) () -> shot(client, "rshift")});
			case "rshift-menu" -> List.of(
					new Object[] {0, (Runnable) () -> {
						var menu = new net.veloclient.velo.client.gui.ModMenuScreen();
						client.setScreen(menu);
						menuForTour = menu;
					}},
					new Object[] {20, (Runnable) () -> menuForTour.openMenuForTour()},
					new Object[] {25, (Runnable) () -> shot(client, "rshift-menu")});
			case "stats" -> List.of(
					new Object[] {0, (Runnable) () -> {
						statsForTour = new net.veloclient.velo.client.gui.VeloStatsScreen(null);
						client.setScreen(statsForTour);
					}},
					new Object[] {30, (Runnable) () -> shot(client, "stats-overview")},
					new Object[] {2, (Runnable) () -> statsForTour.showTabForTour(1)},
					new Object[] {15, (Runnable) () -> shot(client, "stats-general")});
			case "keybinds" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.VeloKeybindsScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "keybinds")});
			case "themes" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.ThemeEditorScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "themes")});
			case "worldmap" -> List.of(
					new Object[] {200, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.worldmap.WorldMapScreen(null))},
					new Object[] {40, (Runnable) () -> shot(client, "worldmap-surface")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.worldmap.WorldMapScreen.tourLayer(1)},
					new Object[] {30, (Runnable) () -> shot(client, "worldmap-topography")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.worldmap.WorldMapScreen.tourLayer(2)},
					new Object[] {30, (Runnable) () -> shot(client, "worldmap-biomes")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.worldmap.WorldMapScreen.tourLayer(0)});
			case "advancements" -> List.of(
					new Object[] {0, (Runnable) () -> {
						advForTour = new net.veloclient.velo.client.gui.VeloAdvancementsScreen(null);
						client.setScreen(advForTour);
					}},
					new Object[] {30, (Runnable) () -> shot(client, "advancements")},
					new Object[] {2, (Runnable) () -> advForTour.selectForTour()},
					new Object[] {15, (Runnable) () -> shot(client, "advancements-details")});
			// Run with -Duser.home=<temp> holding test skins, so the real library is never touched.
			case "skins" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.VeloSkinsScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "skins")});
			case "pause" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true))},
					new Object[] {30, (Runnable) () -> shot(client, "pause")});
			case "bugreport" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.BugReportScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "bugreport")});
			case "settings" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.SettingsTabScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "settings")});
			case "bossbar" -> List.of(
					new Object[] {0, (Runnable) () -> {
						ModuleRegistry.get("boss-bar").ifPresent(m -> m.setEnabled(true));
						client.setScreen(new net.veloclient.velo.client.gui.HudEditScreen(null));
					}},
					new Object[] {30, (Runnable) () -> shot(client, "bossbar-hud-edit")});
			case "f3" -> List.of(
					new Object[] {0, (Runnable) () -> {
						ModuleRegistry.get("better-f3").ifPresent(m -> m.setEnabled(true));
						toggleF3(client, true);
					}},
					new Object[] {30, (Runnable) () -> shot(client, "better-f3")},
					new Object[] {2, (Runnable) () -> toggleF3(client, false)});
			case "spawnradius" -> List.of(
					new Object[] {0, (Runnable) () -> {
						ModuleRegistry.get("spawn-radius").ifPresent(m -> m.setEnabled(true));
						ModuleRegistry.get("simulation-distance").ifPresent(m -> m.setEnabled(true));
					}},
					new Object[] {40, (Runnable) () -> shot(client, "spawn-radius")},
					new Object[] {2, (Runnable) () -> {
						ModuleRegistry.get("spawn-radius").ifPresent(m -> m.setEnabled(false));
					}},
					new Object[] {20, (Runnable) () -> shot(client, "simulation-distance")},
					new Object[] {2, (Runnable) () -> ModuleRegistry.get("simulation-distance").ifPresent(m -> m.setEnabled(false))});
			case "shulker" -> List.of(
					new Object[] {0, (Runnable) () -> giveShulker(client)},
					new Object[] {20, (Runnable) () -> openInventory(client)},
					new Object[] {20, (Runnable) () -> shulkerTour(client, false)},
					new Object[] {10, (Runnable) () -> shot(client, "shulker-preview")},
					new Object[] {2, (Runnable) () -> shulkerTour(client, true)},
					new Object[] {10, (Runnable) () -> shot(client, "shulker-edit")},
					new Object[] {2, (Runnable) () -> {
						// Take the first item out with a real click on the panel (singleplayer edit path).
						net.veloclient.velo.client.modules.qol.ShulkerPreview.tourClickFirst(net.veloclient.velo.client.util.ClientCompat.currentScreen());
					}},
					new Object[] {15, (Runnable) () -> shot(client, "shulker-edit-took")},
					new Object[] {2, (Runnable) () -> restoreCreative(client)});
			case "f3edit" -> List.of(
					new Object[] {0, (Runnable) () -> {
						ModuleRegistry.get("better-f3").ifPresent(m -> m.setEnabled(true));
						toggleF3(client, true);
						client.setScreen(new net.veloclient.velo.client.gui.HudEditScreen(null));
					}},
					new Object[] {30, (Runnable) () -> shot(client, "f3-edit")},
					new Object[] {2, (Runnable) () -> toggleF3(client, false)});
			case "mouse" -> List.of(
					new Object[] {0, (Runnable) () -> {
						ModuleRegistry.get("mouse-buttons").ifPresent(m -> m.setEnabled(true));
						setChoice("mouse-buttons", "Style", "Mouse");
					}},
					new Object[] {20, (Runnable) () -> shot(client, "mouse-buttons")});
			case "advback" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true))},
					new Object[] {10, (Runnable) () -> openVanillaAdvancements(client)},
					new Object[] {30, (Runnable) () -> VeloClient.LOGGER.info("Velo tour advback: open = {}", screenName(client))},
					new Object[] {2, (Runnable) () -> {
						if (net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.window.VeloWindow w) {
							w.closeForTour();
						}
					}},
					new Object[] {40, (Runnable) () -> VeloClient.LOGGER.info("Velo tour advback: after Back = {}", screenName(client))});
			default -> {
				VeloClient.LOGGER.warn("Velo screenshot tour: unknown scene {}", name);
				yield List.of();
			}
		};
	}

	private static void runScenes(MinecraftClient client, int t) {
		if (t < 120) {
			return;
		}
		if (queue == null) {
			queue = new java.util.ArrayDeque<>();
			for (String name : SCENES.split(",")) {
				queue.addAll(scene(client, name.trim()));
				queue.add(new Object[] {2, (Runnable) () -> client.setScreen(null)});
			}
			queue.add(new Object[] {10, (Runnable) () -> stop(client)});
			nextAt = t;
		}
		while (!queue.isEmpty() && t >= nextAt + (int) queue.peek()[0]) {
			Object[] step = queue.poll();
			nextAt = nextAt + (int) step[0];
			((Runnable) step[1]).run();
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

	private static net.veloclient.velo.client.gui.VeloStatsScreen statsForTour;

	private static void setColor(String moduleId, String label, int value) {
		ModuleRegistry.get(moduleId).ifPresent(m -> {
			if (m instanceof net.veloclient.velo.module.Configurable configurable) {
				for (var field : configurable.configFields()) {
					if (field instanceof net.veloclient.velo.module.ConfigField.ColorField color && color.label().equals(label)) {
						color.set().accept(value);
					}
				}
			}
		});
	}

	private static String screenName(MinecraftClient client) {
		var screen = net.veloclient.velo.client.util.ClientCompat.currentScreen();
		return screen == null ? "none (game)" : screen.getClass().getSimpleName();
	}

	private static void toggleF3(MinecraftClient client, boolean on) {
		//? if >=26.2 {
		/*client.debugEntries.setOverlayVisible(on);
		*///?}
	}

	private static void openVanillaAdvancements(MinecraftClient client) {
		//? if >=26.2 {
		/*client.gui.setScreen(new net.minecraft.client.gui.screens.advancements.AdvancementsScreen(client.getConnection().getAdvancements(),
				client.gui.screen()));
		*///?}
	}

	private static void giveShulker(MinecraftClient client) {
		//? if >=26.2 {
		/*var server = client.getSingleplayerServer();
		var uuid = client.player.getUUID();
		if (server != null) {
			server.execute(() -> {
				var player = server.getPlayerList().getPlayer(uuid);
				if (player == null) {
					return;
				}
				var items = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
				net.minecraft.world.item.Item[] pool = {net.minecraft.world.item.Items.DIAMOND, net.minecraft.world.item.Items.GOLDEN_APPLE,
						net.minecraft.world.item.Items.ENDER_PEARL, net.minecraft.world.item.Items.OAK_LOG, net.minecraft.world.item.Items.TORCH,
						net.minecraft.world.item.Items.DIAMOND_PICKAXE, net.minecraft.world.item.Items.COOKED_BEEF, net.minecraft.world.item.Items.REDSTONE};
				for (int i = 0; i < 27; i++) {
					items.add(i % 4 == 3 ? net.minecraft.world.item.ItemStack.EMPTY
							: new net.minecraft.world.item.ItemStack(pool[i % pool.length], 1 + (i * 7) % 64 % pool[i % pool.length].getDefaultMaxStackSize()));
				}
				var box = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DYED_SHULKER_BOX.purple());
				box.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(items));
				player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
				player.getInventory().setItem(9, box);
				player.getInventory().setItem(10, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD, 12));
			});
		}
		*///?}
	}

	private static void openInventory(MinecraftClient client) {
		//? if >=26.2 {
		/*client.gui.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(client.player));
		*///?}
	}

	private static void shulkerTour(MinecraftClient client, boolean pinned) {
		//? if >=26.2 {
		/*if (client.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
			for (var slot : screen.getMenu().slots) {
				if (net.veloclient.velo.client.modules.qol.ShulkerPreview.isShulker(slot.getItem())) {
					net.veloclient.velo.client.modules.qol.ShulkerPreview.tourShow(screen, slot, pinned);
					return;
				}
			}
		}
		*///?}
	}

	private static void statusBarsSetup(MinecraftClient client) {
		//? if >=26.2 {
		/*var server = client.getSingleplayerServer();
		var uuid = client.player.getUUID();
		if (server != null) {
			server.execute(() -> {
				var player = server.getPlayerList().getPlayer(uuid);
				if (player != null) {
					player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
					player.setHealth(13f);
					player.setAbsorptionAmount(6f);
					player.getFoodData().setFoodLevel(13);
					player.getFoodData().setSaturation(7f);
					player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
							new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_CHESTPLATE));
					player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,
							new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BOOTS));
					player.giveExperienceLevels(7);
					player.giveExperiencePoints(20);
				}
			});
		}
		*///?}
	}

	private static void restoreCreative(MinecraftClient client) {
		//? if >=26.2 {
		/*var server = client.getSingleplayerServer();
		var uuid = client.player.getUUID();
		if (server != null) {
			server.execute(() -> {
				var player = server.getPlayerList().getPlayer(uuid);
				if (player != null) {
					player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
				}
			});
		}
		*///?}
	}

	/** Writes two throwaway test icons (a big 96px star and a wide 120x60 pill) and imports them. */
	private static void importTestIcons() {
		//? if >=26.2 {
		/*try {
			Path dir = Files.createTempDirectory("velo-icons");
			var star = new com.mojang.blaze3d.platform.NativeImage(96, 96, true);
			for (int y = 0; y < 96; y++) {
				for (int x = 0; x < 96; x++) {
					double dx = x - 47.5, dy = y - 47.5;
					double angle = Math.atan2(dy, dx);
					double radius = 30 + 16 * Math.cos(angle * 5);
					double d = Math.sqrt(dx * dx + dy * dy);
					star.setPixel(x, y, d < radius - 6 ? 0xFF7B3FF2 : d < radius ? 0xFF1A0B33 : 0);
				}
			}
			star.writeToFile(dir.resolve("star.png"));
			star.close();
			var pill = new com.mojang.blaze3d.platform.NativeImage(120, 60, true);
			for (int y = 0; y < 60; y++) {
				for (int x = 0; x < 120; x++) {
					double cx = Math.max(30, Math.min(90, x));
					double d = Math.hypot(x - cx, y - 29.5);
					pill.setPixel(x, y, d < 24 ? (x < 60 ? 0xFF3DDC84 : 0xFFF2F2F2) : d < 29 ? 0xFF0B3320 : 0);
				}
			}
			pill.writeToFile(dir.resolve("pill.png"));
			pill.close();
			for (String[] pair : new String[][] {{"heart", "star.png"}, {"food", "pill.png"}, {"armor", "star.png"}}) {
				try (var in = Files.newInputStream(dir.resolve(pair[1]))) {
					var fitted = net.veloclient.velo.client.hud.StatusBarIcons.fit(com.mojang.blaze3d.platform.NativeImage.read(in), 18);
					net.veloclient.velo.client.hud.StatusBarIcons.save(pair[0],
							java.util.Map.of(net.veloclient.velo.client.hud.StatusBarIcons.State.FULL, fitted));
					fitted.close();
				}
			}
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo screenshot tour: test icons failed", e);
		}
		*///?}
	}

	private static void setChoice(String moduleId, String label, String value) {
		ModuleRegistry.get(moduleId).ifPresent(m -> {
			if (m instanceof net.veloclient.velo.module.Configurable configurable) {
				for (var field : configurable.configFields()) {
					if (field instanceof net.veloclient.velo.module.ConfigField.ChoiceField choice && choice.label().equals(label)) {
						choice.set().accept(value);
					}
				}
			}
		});
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
