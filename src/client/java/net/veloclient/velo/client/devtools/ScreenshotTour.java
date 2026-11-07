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

	// ---- Real-game runs (launched by the launcher's dev auto-launch, other mods installed) ----
	// -Dvelo.screenshotTour.boot: shoots the title screen as the game opens it (with every other mod's
	// buttons), then -Dvelo.screenshotTour.world=<save> opens that world for the in-world scenes.
	// -Dvelo.screenshotTour.noDemo: no demo friends/waypoints (never writes demo data into a real world).
	private static final boolean BOOT = System.getProperty("velo.screenshotTour.boot") != null;
	private static int bootTicks;
	private static boolean bootDone;

	private static void bootTick(MinecraftClient client) {
		if (!(net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.minecraft.client.gui.screen.TitleScreen)) {
			return;
		}
		bootTicks++;
		switch (bootTicks) {
			case 4 -> shot(client, "boot-title-opening");
			case 200 -> shot(client, "boot-title");
			case 210 -> {
				bootDone = true;
				String world = System.getProperty("velo.screenshotTour.world");
				if (world == null) {
					stop(client);
					return;
				}
				//? if <26.1 {
				client.createIntegratedServerLoader().start(world, () -> { });
				//?} else {
				/*client.createWorldOpenFlows().openWorld(world, () -> { });
				*///?}
			}
			default -> {
			}
		}
	}

	private static void tick(MinecraftClient client) {
		if (BOOT && !bootDone && client.player == null) {
			bootTick(client);
			return;
		}
		if (client.player == null || client.world == null) {
			return;
		}
		ticksInWorld++;
		int t = ticksInWorld;
		if (t == 100 && !prepared && System.getProperty("velo.screenshotTour.noDemo") != null) {
			prepared = true;
		}
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
				setChoice("hunger", "Food Icon Style", "Velo Style");
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
	private static net.veloclient.velo.client.gui.store.StoreScreen storeForTour;
	private static java.util.ArrayDeque<Object[]> queue;
	private static net.veloclient.velo.client.gui.CapeEquipScreen lockerForTour;
	private static int nextAt;

	/** A few chat lines with emoji and :shortcodes: for the Emoji Chat screenshot. */
	private static void emojiChat(MinecraftClient client) {
		String[] lines = {"<Velo2k> gg everyone :fire: :fire:", "<Steve> that clutch was insane \uD83D\uDE02\uD83D\uDC80",
				"<Alex> :tada: Velo 0.9.8 is out! :rocket: :heart:", "<Notch> nice build :eyes: :100:"};
		for (String line : lines) {
			//? if <26.1 {
			client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal(line));
			//?} else {
			/*if (client.player != null) client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
			*///?}
		}
	}

	/** Each scene = steps of {delayTicks, Runnable}. Add a scene here instead of running the whole tour. */
	private static List<Object[]> scene(MinecraftClient client, String name) {
		return switch (name) {
			case "perfboost" -> {
				var dash = new net.veloclient.velo.client.gui.window.ModuleConfigScreen(ModuleRegistry.get("performance-boost").orElseThrow(), null);
				List<Object[]> steps = new java.util.ArrayList<>();
				steps.add(new Object[] {2, (Runnable) () -> client.setScreen(dash)});
				String[] sections = {"Preset", "Frame Rate", "View Distance", "Visuals", "Velo Engine", "Cosmetics"};
				for (String section : sections) {
					steps.add(new Object[] {2, (Runnable) () -> dash.selectSectionForTour(section)});
					steps.add(new Object[] {30, (Runnable) () -> shot(client, "perfboost-" + section.toLowerCase(java.util.Locale.ROOT).replace(' ', '-'))});
				}
				yield steps;
			}
			case "veloplus" -> {
				var nameScreen = new net.veloclient.velo.client.gui.NameStyleScreen(null);
				yield List.of(
						new Object[] {0, (Runnable) () -> {
							net.veloclient.velo.client.namestyle.NameStyles.save(net.veloclient.velo.client.namestyle.NameStyle.PRESETS.get(0).style(), true);
						}},
						new Object[] {5, (Runnable) () -> {
							String me = client.player == null ? "Player" : client.player.getGameProfile().name();
							String[] lines = {"<" + me + "> gg, that was close", "\u00a77[\u00a7cVIP\u00a77] \u00a7a" + me + "\u00a77: \u00a7fserver-coloured name",
									"<Steve> clean", "<Alex> nice name " + me};
							for (String line : lines) {
								//? if <26.1 {
								client.inGameHud.getChatHud().addMessage(net.minecraft.text.Text.literal(line));
								//?} else {
								/*if (client.player != null) client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
								*///?}
							}
						}},
						new Object[] {20, (Runnable) () -> shot(client, "veloplus-chat")},
						new Object[] {2, (Runnable) () -> client.setScreen(nameScreen)},
						new Object[] {40, (Runnable) () -> shot(client, "veloplus-namestyle")},
						new Object[] {2, (Runnable) () -> nameScreen.scrollForTour(560)},
						new Object[] {15, (Runnable) () -> shot(client, "veloplus-namestyle-colours")},
						new Object[] {2, (Runnable) () -> nameScreen.scrollForTour(1100)},
						new Object[] {15, (Runnable) () -> shot(client, "veloplus-namestyle-fonts")},
						new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.CloudSyncScreen(null))},
						new Object[] {40, (Runnable) () -> shot(client, "veloplus-cloud")},
						new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.WorldHostScreen(null))},
						new Object[] {30, (Runnable) () -> shot(client, "veloplus-host")},
						new Object[] {2, (Runnable) () -> client.setScreen(net.veloclient.velo.client.gui.WorldHostScreen.discover(null))},
						new Object[] {40, (Runnable) () -> shot(client, "veloplus-discover")},
						new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.media.MediaScreen(null))},
						new Object[] {30, (Runnable) () -> shot(client, "veloplus-clips")});
			}
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
			// Redstone Mode with a schematic locked in place (hologram blocks), then Replace's suggestions.
			// -Dvelo.tourSchematic=<file> picks the schematic (.litematic / .veloschem).
			case "redstone" -> List.of(
					new Object[] {40, (Runnable) () -> {
						aim(client, 35f);
						net.veloclient.velo.client.redstone.RedstoneMode.enter();
						String file = System.getProperty("velo.tourSchematic");
						if (file != null) {
							net.veloclient.velo.client.redstone.SchematicIO.loadToClipboard(java.nio.file.Path.of(file));
						}
					}},
					new Object[] {60, (Runnable) () -> {
						var p = net.veloclient.velo.client.redstone.Placements.following();
						if (p != null) {
							// Put it ahead of the player, its front facing the camera, and look at it.
							double[] eye = net.veloclient.velo.client.redstone.RmWorld.eyeAndLook();
							int[] feet = net.veloclient.velo.client.redstone.RmWorld.playerBlock();
							var v = p.view();
							double len = Math.max(1e-3, Math.hypot(eye[3], eye[5]));
							double cx = eye[0] + eye[3] / len * (v.sz / 2.0 + 18);
							double cz = eye[2] + eye[5] / len * (v.sz / 2.0 + 18);
							p.x = (int) Math.floor(cx - v.sx / 2.0);
							p.z = (int) Math.floor(cz - v.sz / 2.0);
							p.y = feet[1];
							p.locked = true;
						}
						aim(client, 8f);
					}},
					new Object[] {40, (Runnable) () -> shot(client, "redstone-hologram")},
					new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.redstone.ReplaceScreen())},
					new Object[] {25, (Runnable) () -> typeForTour(client, "redst")},
					new Object[] {20, (Runnable) () -> shot(client, "redstone-replace")},
					new Object[] {2, (Runnable) () -> ModuleRegistry.get("phantom")
							.ifPresent(m -> client.setScreen(new net.veloclient.velo.client.gui.window.ModuleConfigScreen(m, null)))},
					new Object[] {25, (Runnable) () -> shot(client, "module-phantom")},
					new Object[] {2, (Runnable) () -> ModuleRegistry.get("fly-speed")
							.ifPresent(m -> client.setScreen(new net.veloclient.velo.client.gui.window.ModuleConfigScreen(m, null)))},
					new Object[] {25, (Runnable) () -> shot(client, "module-flyspeed")});
			case "store" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(null))},
					new Object[] {60, (Runnable) () -> shot(client, "store-featured")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.cosmetics.wings.WingsManager.type(System.getProperty("velo.tourWings", "phoenix")).ifPresent(type ->
							client.setScreen(new net.veloclient.velo.client.gui.store.WingDetailScreen(null, type)))},
					new Object[] {60, (Runnable) () -> shot(client, "store-wing")});
			case "storefull" -> List.of(
					new Object[] {0, (Runnable) () -> {
						storeForTour = new net.veloclient.velo.client.gui.store.StoreScreen(null);
						client.setScreen(storeForTour);
					}},
					new Object[] {60, (Runnable) () -> shot(client, "store-featured")},
					new Object[] {2, (Runnable) () -> storeForTour.scrollForTour(-8)},
					new Object[] {30, (Runnable) () -> shot(client, "store-featured-scrolled")},
					new Object[] {2, (Runnable) () -> storeForTour.scrollForTour(-30)},
					new Object[] {30, (Runnable) () -> shot(client, "store-featured-bottom")},
					new Object[] {2, (Runnable) () -> {
						net.veloclient.velo.client.gui.store.StoreScreen.showBundles();
						storeForTour = new net.veloclient.velo.client.gui.store.StoreScreen(null);
						client.setScreen(storeForTour);
					}},
					new Object[] {30, (Runnable) () -> shot(client, "store-bundles")},
					new Object[] {2, (Runnable) () -> storeForTour.openFirstBundleForTour()},
					new Object[] {40, (Runnable) () -> shot(client, "store-bundle")},
					new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.store.OwnerScreen(null))},
					new Object[] {40, (Runnable) () -> shot(client, "owner")});
			case "auras" -> {
				List<Object[]> steps = new java.util.ArrayList<>();
				steps.add(new Object[] {0, (Runnable) () -> {
					net.veloclient.velo.client.gui.store.StoreScreen.showAuras();
					client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(null));
				}});
				steps.add(new Object[] {60, (Runnable) () -> shot(client, "store-auras")});
				for (String id : net.veloclient.velo.client.cosmetics.aura.AuraManager.IDS) {
					steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.cosmetics.aura.AuraManager.type(id).ifPresent(type ->
							client.setScreen(new net.veloclient.velo.client.gui.store.AuraDetailScreen(null, type)))});
					steps.add(new Object[] {50, (Runnable) () -> shot(client, "aura-" + id)});
				}
				yield steps;
			}
			case "killfx" -> {
				// The Store's Kill FX tab, a few effect pages, then each effect played for real in front of you.
				List<Object[]> steps = new java.util.ArrayList<>();
				steps.add(new Object[] {0, (Runnable) () -> {
					net.veloclient.velo.client.gui.store.StoreScreen.showKillFx();
					client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(null));
				}});
				steps.add(new Object[] {60, (Runnable) () -> shot(client, "store-killfx")});
				steps.add(new Object[] {2, (Runnable) () -> client.setScreen(net.veloclient.velo.client.gui.CapeEquipScreen.open(null,
						net.veloclient.velo.client.gui.CapeEquipScreen.KILLFX_TAB))});
				steps.add(new Object[] {40, (Runnable) () -> shot(client, "locker-killfx")});
				for (String id : List.of("orbital", "phoenix", "grave")) {
					steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.cosmetics.killfx.KillFxManager.type(id).ifPresent(type ->
							client.setScreen(new net.veloclient.velo.client.gui.store.KillFxDetailScreen(null, type)))});
					steps.add(new Object[] {25, (Runnable) () -> shot(client, "killfx-page-" + id)});
				}
				steps.add(new Object[] {2, (Runnable) () -> client.setScreen(null)});
				String only = System.getProperty("velo.tourKillFx", "");
				for (String id : net.veloclient.velo.client.cosmetics.killfx.KillFxManager.IDS) {
					if (!only.isEmpty() && !List.of(only.split(",")).contains(id)) {
						continue;
					}
					steps.add(new Object[] {20, (Runnable) () -> {
						var player = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
						float yaw = (float) Math.toRadians(net.veloclient.velo.client.pvp.PvpCompat.cameraRotation()[0]);
						double x = net.veloclient.velo.client.pvp.PvpCompat.x(player) - Math.sin(yaw) * 6;
						double z = net.veloclient.velo.client.pvp.PvpCompat.z(player) + Math.cos(yaw) * 6;
						net.veloclient.velo.client.cosmetics.killfx.KillFxManager.play(id, x, net.veloclient.velo.client.pvp.PvpCompat.y(player), z, false);
					}});
					steps.add(new Object[] {8, (Runnable) () -> shot(client, "killfx-" + id + "-a")});
					steps.add(new Object[] {12, (Runnable) () -> shot(client, "killfx-" + id + "-b")});
				}
				yield steps;
			}
			case "title" -> {
				// The title panorama + theme editor (accent buttons) for every preset. setActive saves
				// theme.json, so run this with -Duser.home=<temp>.
				List<Object[]> steps = new java.util.ArrayList<>();
				for (String theme : net.veloclient.velo.client.theme.ThemePresets.all().keySet()) {
					String slug = theme.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
					steps.add(new Object[] {2, (Runnable) () -> {
						net.veloclient.velo.client.theme.ThemeManager.setActive(net.veloclient.velo.client.theme.ThemePresets.all().get(theme));
						client.setScreen(new net.minecraft.client.gui.screen.TitleScreen());
					}});
					steps.add(new Object[] {45, (Runnable) () -> shot(client, "title-" + slug)});
					steps.add(new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.ThemeEditorScreen(null))});
					steps.add(new Object[] {25, (Runnable) () -> shot(client, "themes-" + slug)});
				}
				yield steps;
			}
			case "replace" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.redstone.ReplaceScreen())},
					new Object[] {30, (Runnable) () -> shot(client, "replace")});
			case "capes" -> {
				// -Dvelo.tourCapes=cape_a,cape_b: each cape's Store page (its art downloaded first).
				List<Object[]> steps = new java.util.ArrayList<>();
				String[] ids = System.getProperty("velo.tourCapes", "").split(",");
				steps.add(new Object[] {0, (Runnable) () -> net.veloclient.velo.client.cosmetics.CosmeticsCatalog.refresh(true)});
				steps.add(new Object[] {60, (Runnable) () -> {
					for (String id : ids) {
						net.veloclient.velo.client.store.StoreCatalog.byId(id).ifPresent(net.veloclient.velo.client.store.StoreAssets::ready);
					}
				}});
				for (String id : ids) {
					steps.add(new Object[] {60, (Runnable) () -> net.veloclient.velo.client.store.StoreCatalog.byId(id).ifPresent(item ->
							client.setScreen(new net.veloclient.velo.client.gui.store.StoreItemDetailScreen(null, item)))});
					steps.add(new Object[] {40, (Runnable) () -> shot(client, "cape-" + id)});
				}
				yield steps;
			}
			case "plus" -> List.of(
					new Object[] {0, (Runnable) () -> {
						net.veloclient.velo.client.gui.store.StoreScreen.showPlus();
						client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(null));
					}},
					new Object[] {40, (Runnable) () -> shot(client, "store-plus")});
			case "emoji" -> List.of(
					new Object[] {0, (Runnable) () -> emojiChat(client)},
					new Object[] {10, (Runnable) () -> {
						//? if <26.1 {
						client.setScreen(new net.minecraft.client.gui.screen.ChatScreen(":fi", false));
						//?} else {
						/*client.setScreen(new net.minecraft.client.gui.screens.ChatScreen(":fi", false));
						*///?}
					}},
					new Object[] {25, (Runnable) () -> shot(client, "emoji-chat")});
			case "friends" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.FriendsScreen())},
					new Object[] {30, (Runnable) () -> shot(client, "friends")});
			case "locker" -> List.of(
					new Object[] {0, (Runnable) () -> {
						lockerForTour = new net.veloclient.velo.client.gui.CapeEquipScreen(null);
						client.setScreen(lockerForTour);
					}},
					new Object[] {40, (Runnable) () -> shot(client, "locker")},
					new Object[] {2, (Runnable) () -> lockerForTour.showTabForTour(1)},
					// -Dvelo.tourWings=<id>: those wings on the preview (two shots: they move).
					new Object[] {2, (Runnable) () -> {
						String wings = System.getProperty("velo.tourWings");
						if (wings != null) {
							net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(wings);
						}
					}},
					new Object[] {40, (Runnable) () -> shot(client, "locker-wings")},
					new Object[] {12, (Runnable) () -> shot(client, "locker-wings-later")});
			case "storecapes" -> List.of(
					new Object[] {0, (Runnable) () -> {
						net.veloclient.velo.client.gui.store.StoreScreen.showCapes();
						client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(null));
					}},
					new Object[] {60, (Runnable) () -> shot(client, "store-capes")});
			case "title1" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.minecraft.client.gui.screen.TitleScreen())},
					new Object[] {45, (Runnable) () -> shot(client, "title")});
			case "huddefault" -> List.of(
					new Object[] {0, (Runnable) () -> {
						client.setScreen(null);
						for (var module : ModuleRegistry.all()) {
							if (module instanceof net.veloclient.velo.client.hud.HudModule hud) {
								hud.position().resetToDefault();
							}
						}
						for (String id : List.of("fps-counter", "ping-display", "coordinates", "clock", "keystrokes", "mouse-buttons",
								"armor-durability", "potion-timers", "totem-counter", "minimap", "scoreboard-hud", "cps-counter")) {
							ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(true));
						}
						for (String id : List.of("entity-count-overlay", "held-item", "target-hud")) {
							ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(false));
						}
						statusBarsSetup(client);
					}},
					new Object[] {40, (Runnable) () -> shot(client, "hud-default")});
			case "recipebook" -> List.of(
					new Object[] {0, (Runnable) () -> statusBarsSetup(client)},
					new Object[] {30, (Runnable) () -> openInventory(client)},
					new Object[] {10, (Runnable) () -> toggleRecipeBook(client)},
					new Object[] {20, (Runnable) () -> shot(client, "recipebook-open")});
			case "pause" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true))},
					new Object[] {3, (Runnable) () -> shot(client, "pause-opening")},
					new Object[] {30, (Runnable) () -> shot(client, "pause")});
			case "packlib" -> List.of(
					new Object[] {0, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openPacks(client)},
					new Object[] {40, (Runnable) () -> shot(client, "packlib-toolbar")},
					new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.packs.PackLibraryScreen(
							net.veloclient.velo.client.util.ClientCompat.currentScreen()))},
					new Object[] {160, (Runnable) () -> shot(client, "packlib")});
			case "packs" -> List.of(
					new Object[] {0, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openPacks(client)},
					new Object[] {40, (Runnable) () -> shot(client, "packs-list")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openBrowser(client, false)},
					new Object[] {40, (Runnable) () -> shot(client, "packs-info")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.browserCategory(false,
							net.veloclient.velo.client.gui.packs.PackContents.Category.BLOCKS)},
					new Object[] {40, (Runnable) () -> shot(client, "packs-blocks")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.browserCategory(true,
							net.veloclient.velo.client.gui.packs.PackContents.Category.ITEMS)},
					new Object[] {80, (Runnable) () -> shot(client, "packs-game-items")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openEditor(client, "minecraft:textures/block/oak_log.png", false, false)},
					new Object[] {60, (Runnable) () -> shot(client, "editor-block")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openEditor(client, "minecraft:textures/item/diamond_sword.png", true, false)},
					new Object[] {40, (Runnable) () -> shot(client, "editor-item")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openEditor(client, "minecraft:textures/entity/creeper/creeper.png", false, false)},
					new Object[] {40, (Runnable) () -> shot(client, "editor-entity")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openEditor(client, "minecraft:textures/block/sea_lantern.png", true, true)},
					new Object[] {6, (Runnable) () -> shot(client, "editor-colorize-anim")},
					new Object[] {40, (Runnable) () -> shot(client, "editor-colorize")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openBrowser(client, true)},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.browserCategory(true,
							net.veloclient.velo.client.gui.packs.PackContents.Category.SOUNDS)},
					new Object[] {80, (Runnable) () -> shot(client, "packs-sounds")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openSound(client, "minecraft:entity.creeper.primed")},
					new Object[] {60, (Runnable) () -> shot(client, "sound-editor")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openSound(client, "minecraft:music.menu")},
					new Object[] {120, (Runnable) () -> shot(client, "sound-music")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openCode(client, "minecraft:models/block/stairs.json")},
					new Object[] {40, (Runnable) () -> shot(client, "code-json")},
					new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openCode(client, "minecraft:shaders/core/terrain.fsh")},
					new Object[] {40, (Runnable) () -> shot(client, "code-glsl")});
			case "bugreport" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.BugReportScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "bugreport")});
			// Settings > Velo screens set to Vanilla for the title screen and the pause menu, then back to Velo.
			case "vanillamenus" -> List.of(
					new Object[] {0, (Runnable) () -> {
						net.veloclient.velo.client.gui.VeloScreens.setTitle(false);
						net.veloclient.velo.client.gui.VeloScreens.setPause(false);
						client.setScreen(new net.minecraft.client.gui.screen.TitleScreen());
					}},
					new Object[] {40, (Runnable) () -> shot(client, "vanilla-title")},
					new Object[] {2, (Runnable) () -> client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true))},
					new Object[] {30, (Runnable) () -> shot(client, "vanilla-pause")},
					new Object[] {2, (Runnable) () -> {
						net.veloclient.velo.client.gui.VeloScreens.setTitle(true);
						net.veloclient.velo.client.gui.VeloScreens.setPause(true);
						client.setScreen(new net.minecraft.client.gui.screen.GameMenuScreen(true));
					}},
					new Object[] {30, (Runnable) () -> shot(client, "velo-pause")});
			case "settings" -> List.of(
					new Object[] {0, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.SettingsTabScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "settings")});
			// Velo Bridge on a real server: the test server's console fires toast / progress / waypoint / countdown /
			// tint+vignette at fixed times after the join (see the bridge test script); this only waits and shoots.
			case "bridge" -> List.of(
					// Clean shots: only the server's UI (profiles aren't saved during a tour, so nothing sticks).
					new Object[] {0, (Runnable) () -> {
						ModuleRegistry.byCategory(net.veloclient.velo.module.ModuleCategory.HUD).forEach(m -> m.setEnabled(false));
						for (String id : List.of("minimap", "keystrokes", "entity-count-overlay", "coordinates", "fps-counter", "gpu-utilization")) {
							ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(false));
						}
					}},
					new Object[] {70, (Runnable) () -> shot(client, "bridge-1-modhints")},
					new Object[] {2, (Runnable) () -> client.setScreen(null)},
					new Object[] {50, (Runnable) () -> shot(client, "bridge-2-toast-progress-waypoint")},
					new Object[] {80, (Runnable) () -> shot(client, "bridge-3-countdown")},
					new Object[] {30, (Runnable) () -> shot(client, "bridge-3b-countdown")},
					new Object[] {80, (Runnable) () -> shot(client, "bridge-4-tint-vignette")});
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
			case "screens" -> List.of(
					new Object[] {0, (Runnable) () -> ModuleRegistry.get("keystrokes")
							.ifPresent(m -> client.setScreen(new net.veloclient.velo.client.gui.window.ModuleConfigScreen(m, null)))},
					new Object[] {30, (Runnable) () -> shot(client, "screen-module-keystrokes")},
					new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.HudEditScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "screen-hud-editor")},
					new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.SettingsTabScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "screen-settings")},
					new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.ProfileScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "screen-profiles")},
					new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.WaypointsScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "screen-waypoints")},
					new Object[] {2, (Runnable) () -> client.setScreen(net.veloclient.velo.client.gui.CapeEquipScreen.open(null, 0))},
					new Object[] {40, (Runnable) () -> shot(client, "screen-locker-skin")},
					new Object[] {2, (Runnable) () -> client.setScreen(net.veloclient.velo.client.gui.CapeEquipScreen.open(null, 2))},
					new Object[] {40, (Runnable) () -> shot(client, "screen-locker-wings")},
					new Object[] {2, (Runnable) () -> ModuleRegistry.get("wings-cosmetics")
							.ifPresent(m -> client.setScreen(new net.veloclient.velo.client.gui.window.ModuleConfigScreen(m, null)))},
					new Object[] {30, (Runnable) () -> shot(client, "screen-module-wings")},
					new Object[] {2, (Runnable) () -> {
						net.veloclient.velo.client.gui.VeloKeybindsScreen.expandForTour();
						client.setScreen(new net.veloclient.velo.client.gui.VeloKeybindsScreen(null));
					}},
					new Object[] {40, (Runnable) () -> shot(client, "screen-controls")},
					new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.HudEditScreen(null))},
					new Object[] {30, (Runnable) () -> shot(client, "screen-hud-editor-2")});
			case "hud" -> List.of(
					new Object[] {0, (Runnable) () -> {
						for (String id : List.of("small-caps", "entity-count-overlay", "target-hud", "height-limit", "held-item")) {
							ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(false));
						}
						for (String id : List.of("hud-style", "fps-counter", "ping-display", "clock", "coordinates", "keystrokes", "potion-timers",
								"armor-durability", "mouse-buttons")) {
							ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(true));
						}
						setChoice("hud-style", "Font", "Inter");
						setChoice("hud-style", "Background", "None");
						setChoice("potion-timers", "Style", "List");
						setChoice("armor-durability", "Style", "Bars");
						setChoice("keystrokes", "Style", "Glass");
						hudSetup(client);
					}},
					new Object[] {40, (Runnable) () -> shot(client, "hud-inter")},
					new Object[] {2, (Runnable) () -> {
						setChoice("hud-style", "Font", "Sora");
						setChoice("potion-timers", "Style", "Compact");
					}},
					new Object[] {10, (Runnable) () -> shot(client, "hud-sora-compact")},
					new Object[] {2, (Runnable) () -> {
						setChoice("hud-style", "Font", "Minecraft");
						setChoice("keystrokes", "Style", "Minecraft");
					}},
					new Object[] {10, (Runnable) () -> shot(client, "hud-minecraft")},
					new Object[] {2, (Runnable) () -> {
						setChoice("hud-style", "Font", "Inter");
						setChoice("keystrokes", "Style", "Glass");
						setChoice("potion-timers", "Style", "List");
						client.setScreen(new net.veloclient.velo.client.gui.HudEditScreen(null));
					}},
					new Object[] {30, (Runnable) () -> shot(client, "hud-editor")});
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
			case "skin" -> List.<Object[]>of(
					new Object[] {0, (Runnable) net.veloclient.velo.client.gui.VeloSkinsScreen::wearEquippedForTour});
			case "hero" -> hero(client);
			case "vibe-hud" -> vibeHud(client);
			case "vibe-orbit" -> vibeOrbit(client);
			case "vibe-names" -> vibeNames(client);
			case "vibe-scout" -> vibeScout(client);
			case "rs" -> redstoneShort(client);
			case "vibe-packs" -> vibePacks(client);
			case "vibe-packs-code" -> vibePacksCode(client);
			case "rs-layout" -> rsLayout(client);
			case "shadercine" -> shaderCinematics(client);
			default -> {
				// "theme:Velo_Dark" switches the menu theme for the scenes after it (saves theme.json - use -Duser.home=<temp>).
				if (name.startsWith("theme:")) {
					var preset = net.veloclient.velo.client.theme.ThemePresets.all().get(name.substring(6).replace('_', ' '));
					yield preset == null ? List.of() : List.<Object[]>of(new Object[] {0, (Runnable) () -> net.veloclient.velo.client.theme.ThemeManager.setActive(preset)});
				}
				// Unreleased preview features (dev build): "preview:guide" -> net.veloclient.velo.preview.PreviewTour.
				if (name.startsWith("preview:")) {
					try {
						Class<?> tour = Class.forName("net.veloclient.velo.preview.PreviewTour");
						@SuppressWarnings("unchecked")
						List<Object[]> steps = (List<Object[]>) tour.getMethod("scene", String.class, java.util.function.Consumer.class)
								.invoke(null, name.substring(8), (java.util.function.Consumer<String>) shotName -> shot(client, shotName));
						yield steps;
					} catch (ReflectiveOperationException e) {
						VeloClient.LOGGER.warn("Velo screenshot tour: no preview scene {} ({})", name, e.toString());
						yield List.of();
					}
				}
				// Dev-build features bring their own scenes ("dev:notes" -> net.veloclient.velo.dev.DevTour).
				if (name.startsWith("dev:")) {
					try {
						Class<?> tour = Class.forName("net.veloclient.velo.dev.DevTour");
						@SuppressWarnings("unchecked")
						List<Object[]> steps = (List<Object[]>) tour.getMethod("scene", String.class, java.util.function.Consumer.class)
								.invoke(null, name.substring(4), (java.util.function.Consumer<String>) shotName -> shot(client, shotName));
						yield steps;
					} catch (ReflectiveOperationException e) {
						VeloClient.LOGGER.warn("Velo screenshot tour: no dev scene {} ({})", name, e.toString());
						yield List.of();
					}
				}
				VeloClient.LOGGER.warn("Velo screenshot tour: unknown scene {}", name);
				yield List.of();
			}
		};
	}

	// ---- Frame sequences for promo edits: one shot per tick. A shot shows the state set one tick earlier,
	// so step k saves frame k-1 and then sets frame k (vibe-log.txt says where the cursor is per frame). ----

	private static net.veloclient.velo.client.gui.HudEditScreen hudEditForTour;
	private static final StringBuilder VIBE_LOG = new StringBuilder();

	private static void vibeLog(String line) {
		VIBE_LOG.append(line).append('\n');
		try {
			Files.createDirectories(folder);
			Files.writeString(folder.resolve("vibe-log.txt"), VIBE_LOG);
		} catch (Exception e) {
			VeloClient.LOGGER.warn("Velo screenshot tour: vibe log", e);
		}
	}

	private static float ease(float t) {
		t = Math.clamp(t, 0f, 1f);
		return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
	}

	/** Frames {@code prefix-000..} from {@code count} calls of {@code frame} (frame index in, state for it set). */
	private static void sequence(List<Object[]> steps, MinecraftClient client, String prefix, int count, java.util.function.IntConsumer frame) {
		for (int k = 0; k <= count; k++) {
			int f = k;
			steps.add(new Object[] {1, (Runnable) () -> {
				if (f > 0) {
					rawShot(client, String.format(java.util.Locale.ROOT, "%s-%03d", prefix, f - 1));
					vibeLog(String.format(java.util.Locale.ROOT, "time %s %d %d", prefix, f - 1, System.nanoTime() / 1_000_000));
				}
				if (f < count) {
					frame.accept(f);
				}
			}});
		}
	}

	/** Runs commands as the server (26.2 only, like {@link #hudSetup}). */
	private static void serverCommands(MinecraftClient client, String... commands) {
		//? if >=26.2 {
		/*var server = client.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> {
				var source = server.createCommandSourceStack();
				String me = client.player.getGameProfile().name();
				for (String command : commands) {
					server.getCommands().performPrefixedCommand(source, command.replace("@s", me));
				}
			});
		}
		*///?}
	}

	private static void clearChat(MinecraftClient client) {
		//? if >=26.2 {
		/*client.gui.hud.getChat().clearMessages(false);
		*///?}
	}

	private static final String[] VIBE_WORLD = {"time set 11800", "weather clear", "gamerule doDaylightCycle false", "gamerule advance_time false",
			"gamerule advance_weather false"};

	private static void setHudHidden(MinecraftClient client, boolean hidden) {
		//? if >=26.2 {
		/*if (client.gui.hud.isHidden() != hidden) {
			client.gui.hud.toggle();
		}
		*///?}
	}

	/**
	 * Website hero shots: golden hour, no HUD, the orbit camera around your player from a few angles - first in the equipped
	 * look, then in -Dvelo.tourHeroCape (cloak name) / -Dvelo.tourHeroWings / -Dvelo.tourHeroAura if given.
	 */
	private static List<Object[]> hero(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		float[] yaws = {150f, 200f, 120f, 30f};
		double[] dist = {3.4, 4.2, 5.2, 3.8};
		steps.add(new Object[] {0, (Runnable) () -> {
			net.veloclient.velo.client.cosmetics.CosmeticsCatalog.refresh(true);
			net.veloclient.velo.client.cosmetics.CapeManager.loadLibrary();
			serverCommands(client, VIBE_WORLD);
			serverCommands(client, "effect clear @s", "clear @s");
			String[] spot = System.getProperty("velo.tourSpot", "").split(",");
			if (spot.length == 3) {
				serverCommands(client, "spreadplayers " + spot[0] + " " + spot[1] + " 0 1 false @s", "rotate @s " + spot[2] + " 8");
			}
			ModuleRegistry.get("free-look").ifPresent(m -> m.setEnabled(true));
			setHudHidden(client, true);
			//? if >=26.2 {
			/*client.options.fov().set(50);
			client.player.setXRot(10f);
			*///?}
		}});
		java.util.function.Consumer<String> angles = prefix -> {
			for (int i = 0; i < yaws.length; i++) {
				int k = i;
				steps.add(new Object[] {k == 0 ? 60 : 4, (Runnable) () ->
						net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, yaws[k], dist[k])});
				steps.add(new Object[] {30, (Runnable) () -> shot(client, prefix + "-" + k)});
			}
		};
		angles.accept("hero-equipped");
		String cape = System.getProperty("velo.tourHeroCape");
		String wings = System.getProperty("velo.tourHeroWings");
		String aura = System.getProperty("velo.tourHeroAura");
		if (cape != null || wings != null || aura != null) {
			steps.add(new Object[] {2, (Runnable) () -> {
				if (cape != null) {
					net.veloclient.velo.client.cosmetics.CapeManager.library().values().stream()
							.filter(d -> d.name().equalsIgnoreCase(cape)).findFirst()
							.ifPresent(net.veloclient.velo.client.cosmetics.CapeManager::setPreviewOverride);
				}
				if (wings != null) {
					net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(wings);
				}
				if (aura != null) {
					net.veloclient.velo.client.cosmetics.aura.AuraManager.setPreview(aura);
				}
			}});
			angles.accept("hero-styled");
		}
		steps.add(new Object[] {2, (Runnable) () -> {
			net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(false, 0f);
			net.veloclient.velo.client.cosmetics.aura.AuraManager.clearPreview();
			net.veloclient.velo.client.cosmetics.wings.WingsManager.clearPreview();
			net.veloclient.velo.client.cosmetics.CapeManager.clearPreviewOverride();
			setHudHidden(client, false);
		}});
		return steps;
	}

	/** Drags Keystrokes across the screen in the HUD editor, lets go, then resizes it. */
	private static List<Object[]> vibeHud(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		steps.add(new Object[] {0, (Runnable) () -> {
			for (String id : List.of("small-caps", "entity-count-overlay", "target-hud", "height-limit", "held-item")) {
				ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(false));
			}
			for (String id : List.of("hud-style", "fps-counter", "ping-display", "clock", "coordinates", "keystrokes", "potion-timers",
					"armor-durability", "mouse-buttons", "cps-counter")) {
				ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(true));
			}
			for (var module : ModuleRegistry.all()) {
				if (module instanceof net.veloclient.velo.client.hud.HudModule hud) {
					hud.position().resetToDefault();
				}
			}
			setChoice("hud-style", "Font", "Inter");
			setChoice("hud-style", "Background", "Glass");
			setChoice("potion-timers", "Style", "List");
			setChoice("armor-durability", "Style", "Bars");
			setChoice("keystrokes", "Style", "Glass");
			hudSetup(client);
			serverCommands(client, VIBE_WORLD);
		}});
		steps.add(new Object[] {20, (Runnable) () -> clearChat(client)});
		// Fonts and styles, one still each.
		String[][] looks = {{"Inter", "Glass"}, {"Sora", "Glass"}, {"Space Grotesk", "Solid"}, {"Poppins", "Glass"}, {"JetBrains Mono", "None"},
				{"Audiowide", "Glass"}, {"Minecraft", "Solid"}};
		for (String[] look : looks) {
			steps.add(new Object[] {30, (Runnable) () -> {
				setChoice("hud-style", "Font", look[0]);
				setChoice("hud-style", "Background", look[1]);
			}});
			steps.add(new Object[] {12, (Runnable) () -> shot(client, "look-" + look[0].toLowerCase(java.util.Locale.ROOT).replace(' ', '-'))});
		}
		steps.add(new Object[] {2, (Runnable) () -> {
			setChoice("hud-style", "Font", "Inter");
			setChoice("hud-style", "Background", "Glass");
			hudEditForTour = new net.veloclient.velo.client.gui.HudEditScreen(null);
			client.setScreen(hudEditForTour);
		}});
		steps.add(new Object[] {40, (Runnable) () -> shot(client, "hudedit")});
		// {grabX, grabY, startX, startY} once the editor knows where Keystrokes is.
		float[] at = new float[6];
		java.util.function.Supplier<net.veloclient.velo.client.hud.HudModule> keys = () -> ModuleRegistry.get("keystrokes")
				.filter(m -> m instanceof net.veloclient.velo.client.hud.HudModule).map(m -> (net.veloclient.velo.client.hud.HudModule) m).orElseThrow();
		sequence(steps, client, "hud", 112, f -> {
			var screen = hudEditForTour;
			var hud = keys.get();
			int[] a = screen.areaForTour(hud);
			float cx;
			float cy;
			if (f == 0) {
				at[0] = a[0];
				at[1] = a[1];
				at[2] = screen.width * 0.58f;
				at[3] = screen.height * 0.40f;
				vibeLog("gui " + screen.width + " " + screen.height);
			}
			float grabDX = a[2] * 0.5f;
			float grabDY = a[3] * 0.5f;
			if (f < 12) {
				// cursor glides in from the middle of the screen
				float t = ease(f / 11f);
				cx = screen.width * 0.5f + (at[0] + grabDX - screen.width * 0.5f) * t;
				cy = screen.height * 0.62f + (at[1] + grabDY - screen.height * 0.62f) * t;
			} else if (f < 56) {
				float t = ease((f - 14) / 41f);
				int x = Math.round(at[0] + (at[2] - at[0]) * t);
				int y = Math.round(at[1] + (at[3] - at[1]) * t);
				screen.dragForTour(hud, x, y);
				cx = x + grabDX;
				cy = y + grabDY;
			} else if (f < 64) {
				if (f == 56) {
					screen.dragForTour(null, 0, 0);
				}
				cx = a[0] + grabDX;
				cy = a[1] + grabDY;
			} else if (f < 76) {
				float t = ease((f - 64) / 11f);
				cx = a[0] + grabDX + (a[2] - grabDX) * t;
				cy = a[1] + grabDY + (a[3] - grabDY) * t;
			} else if (f < 102) {
				float t = ease((f - 77) / 23f);
				screen.resizeForTour(hud, 1f + 0.55f * t);
				int[] now = screen.areaForTour(hud);
				cx = now[0] + now[2];
				cy = now[1] + now[3];
			} else {
				if (f == 102) {
					screen.resizeForTour(null, 1f);
				}
				cx = a[0] + a[2];
				cy = a[1] + a[3];
			}
			vibeLog(String.format(java.util.Locale.ROOT, "hud %d %.1f %.1f", f, cx, cy));
		});
		steps.add(new Object[] {2, (Runnable) () -> {
			keys.get().position().resetToDefault();
			hudEditForTour = null;
		}});
		return steps;
	}

	/** -Dvelo.tourScout=x,z;x,z... - drops you on the ground at each spot, looking at -Dvelo.tourLookAt=x,z, and shoots it. */
	private static List<Object[]> vibeScout(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		String[] look = System.getProperty("velo.tourLookAt", "0,0").split(",");
		double lx = Double.parseDouble(look[0]);
		double lz = Double.parseDouble(look[1]);
		steps.add(new Object[] {0, (Runnable) () -> {
			serverCommands(client, VIBE_WORLD);
			serverCommands(client, "gamemode creative @s");
			setHudHidden(client, true);
		}});
		String[] spots = System.getProperty("velo.tourScout", "").split(";");
		for (int i = 0; i < spots.length; i++) {
			String[] xz = spots[i].split(",");
			int n = i;
			steps.add(new Object[] {5, (Runnable) () -> serverCommands(client, "spreadplayers " + xz[0] + " " + xz[1] + " 0 1 false @s")});
			steps.add(new Object[] {50, (Runnable) () -> {
				var player = client.player;
				float yaw = (float) Math.toDegrees(Math.atan2(-(lx - player.getX()), lz - player.getZ()));
				serverCommands(client, String.format(java.util.Locale.ROOT, "rotate @s %.1f 5", yaw));
			}});
			steps.add(new Object[] {15, (Runnable) () -> {
				shot(client, "scout-" + n);
				vibeLog(String.format(java.util.Locale.ROOT, "scout %d %.1f %.1f %.1f", n, client.player.getX(), client.player.getY(), client.player.getZ()));
			}});
		}
		return steps;
	}

	/** Orbits the camera around you wearing cosmetics: capes, wings, then one aura in every colour. */
	private static List<Object[]> vibeOrbit(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		String[] capes = System.getProperty("velo.tourCapes", "").split(",");
		String[] wings = System.getProperty("velo.tourWings", "").split(",");
		String[] auras = System.getProperty("velo.tourAuras", "storm,inferno,aurora").split(",");
		steps.add(new Object[] {0, (Runnable) () -> {
			net.veloclient.velo.client.cosmetics.CosmeticsCatalog.refresh(true);
			net.veloclient.velo.client.cosmetics.CapeManager.loadLibrary();
			serverCommands(client, VIBE_WORLD);
			serverCommands(client, "effect clear @s", "clear @s");
			// -Dvelo.tourSpot=x,z,yaw: stand on the ground there, facing yaw.
			String[] spot = System.getProperty("velo.tourSpot", "").split(",");
			if (spot.length == 3) {
				serverCommands(client, "spreadplayers " + spot[0] + " " + spot[1] + " 0 1 false @s", "rotate @s " + spot[2] + " 8");
			}
			ModuleRegistry.get("free-look").ifPresent(m -> m.setEnabled(true));
			setHudHidden(client, true);
			//? if >=26.2 {
			/*client.options.fov().set(50);
			client.player.setXRot(12f);
			*///?}
		}});
		float[] yaw = {0f};
		// Each look: 48 frames of a slow half orbit.
		java.util.function.BiConsumer<String, Runnable> look = (prefix, dress) -> {
			float from = prefix.contains("aura") ? 125f : -62f;
			steps.add(new Object[] {60, dress});
			sequence(steps, client, prefix, 48, f -> {
				net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, from + yaw[0] + f * 2.6f, 3.6);
			});
			steps.add(new Object[] {0, (Runnable) () -> yaw[0] = yaw[0] == 0f ? -14f : 0f});
		};
		for (String cape : capes) {
			if (cape.isBlank()) {
				continue;
			}
			look.accept("orbit-cape-" + cape, () -> {
				net.veloclient.velo.client.cosmetics.aura.AuraManager.setPreview(null);
				net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(null);
				net.veloclient.velo.client.cosmetics.CapeManager.library().values().stream()
						.filter(d -> d.name().equalsIgnoreCase(cape.replace('_', ' '))).findFirst()
						.ifPresent(net.veloclient.velo.client.cosmetics.CapeManager::setPreviewOverride);
			});
		}
		for (String wing : wings) {
			if (wing.isBlank()) {
				continue;
			}
			look.accept("orbit-wings-" + wing, () -> {
				net.veloclient.velo.client.cosmetics.CapeManager.clearPreviewOverride();
				net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(wing);
			});
		}
		// Auras: one each, then the first one through every colour (8 frames per colour).
		for (String aura : auras) {
			look.accept("orbit-aura-" + aura, () -> {
				net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(null);
				net.veloclient.velo.client.cosmetics.aura.AuraManager.setSettings(aura, null);
				net.veloclient.velo.client.cosmetics.aura.AuraManager.setPreview(aura);
			});
		}
		steps.add(new Object[] {20, (Runnable) () -> net.veloclient.velo.client.cosmetics.aura.AuraManager.setPreview(auras[0])});
		int colours = net.veloclient.velo.client.cosmetics.aura.AuraEffects.RAINBOW + 1;
		sequence(steps, client, "orbit-colours", colours * 8, f -> {
			if (f % 8 == 0) {
				net.veloclient.velo.client.cosmetics.aura.AuraManager.setSettings(auras[0], new net.veloclient.velo.client.cosmetics.aura.AuraEffects.Settings(1.2f, 1f, 1.3f, f / 8));
			}
			net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 150f + f * 0.9f, 3.4);
		});
		steps.add(new Object[] {2, (Runnable) () -> {
			net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(false, 0f);
			net.veloclient.velo.client.cosmetics.aura.AuraManager.setSettings(auras[0], null);
			net.veloclient.velo.client.cosmetics.aura.AuraManager.clearPreview();
			net.veloclient.velo.client.cosmetics.wings.WingsManager.clearPreview();
			net.veloclient.velo.client.cosmetics.CapeManager.clearPreviewOverride();
			setHudHidden(client, false);
		}});
		return steps;
	}

	/** The Locker's Name Style page, switching through presets (10 animated frames each). */
	private static List<Object[]> vibeNames(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		String[] presets = System.getProperty("velo.tourNameStyles", "Velo,Inferno,Aurora,Rainbow,Neon Pink,Gold Rush,Glitch,Galaxy,Vaporwave,Ice & Fire").split(",");
		var screen = new net.veloclient.velo.client.gui.NameStyleScreen(null);
		steps.add(new Object[] {0, (Runnable) () -> client.setScreen(screen)});
		steps.add(new Object[] {40, (Runnable) () -> shot(client, "name-screen")});
		for (String preset : presets) {
			int index = -1;
			for (int i = 0; i < net.veloclient.velo.client.namestyle.NameStyle.PRESETS.size(); i++) {
				if (net.veloclient.velo.client.namestyle.NameStyle.PRESETS.get(i).name().equals(preset.trim())) {
					index = i;
				}
			}
			if (index < 0) {
				continue;
			}
			int pick = index;
			String slug = preset.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]+", "-");
			steps.add(new Object[] {1, (Runnable) () -> screen.presetForTour(pick)});
			sequence(steps, client, "name-" + slug, 12, f -> {
			});
		}
		return steps;
	}

	// ---- Redstone Mode short: load a .litematic, place it, paste it, build guide, materials, wiring, rewind, save ----

	private static int rx;
	private static int ry;
	private static int rz;
	private static net.veloclient.velo.client.redstone.Placements.Placement rsPlacement;
	private static final List<int[]> RS_WRONG = new ArrayList<>();

	/** Puts you (flying) at x/y/z looking at tx/ty/tz - world coordinates, feet position. */
	private static void lookFrom(MinecraftClient client, double x, double y, double z, double tx, double ty, double tz) {
		double ex = x, ey = y + 1.62, ez = z;
		float yaw = (float) Math.toDegrees(Math.atan2(-(tx - ex), tz - ez));
		float pitch = (float) -Math.toDegrees(Math.atan2(ty - ey, Math.hypot(tx - ex, tz - ez)));
		//? if >=26.2 {
		/*var p = client.player;
		p.getAbilities().flying = true;
		p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		p.snapTo(x, y, z, yaw, pitch);
		p.setYHeadRot(yaw);
		*///?}
	}

	/** Camera path through keys {frame, x, y, z, tx, ty, tz} (relative to the base), eased between keys. */
	private static void camPath(MinecraftClient client, double[][] keys, int f) {
		int i = 0;
		while (i < keys.length - 2 && f > keys[i + 1][0]) {
			i++;
		}
		double[] a = keys[i];
		double[] b = keys[Math.min(keys.length - 1, i + 1)];
		double t = b[0] == a[0] ? 1 : Math.max(0, Math.min(1, (f - a[0]) / (b[0] - a[0])));
		t = 0.5 - 0.5 * Math.cos(Math.PI * t);
		double[] v = new double[7];
		for (int k = 1; k < 7; k++) {
			v[k] = a[k] + (b[k] - a[k]) * t;
		}
		lookFrom(client, rx + v[1], ry + v[2], rz + v[3], rx + v[4], ry + v[5], rz + v[6]);
	}

	private static void rsCommands(MinecraftClient client, String... commands) {
		String[] out = new String[commands.length];
		for (int i = 0; i < commands.length; i++) {
			// "{x+3}" style offsets from the base
			StringBuilder sb = new StringBuilder();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([xyz])([-+]\\d+)?\\}").matcher(commands[i]);
			while (m.find()) {
				int base = switch (m.group(1)) {
					case "x" -> rx;
					case "y" -> ry;
					default -> rz;
				};
				m.appendReplacement(sb, String.valueOf(base + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)))));
			}
			m.appendTail(sb);
			out[i] = sb.toString();
		}
		serverCommands(client, out);
	}

	/** Ground height (motion blocking) at x/z, 64 when unknown. */
	private static int groundAt(MinecraftClient client, double x, double z) {
		//? if >=26.2 {
		/*return client.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
		*///?} else {
		return 64;
		//?}
	}

	/** Camera = the (flying, hidden) player: puts the eye at x/y/z looking yaw/pitch. */
	private static void camAt(MinecraftClient client, double x, double y, double z, float yaw, float pitch) {
		//? if >=26.2 {
		/*var p = client.player;
		p.getAbilities().flying = true;
		p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		p.snapTo(x, y - 1.62, z, yaw, pitch);
		p.setYHeadRot(yaw);
		*///?}
	}

	/**
	 * Velo Shaders footage for trailers: slow, still-camera shots at golden hour around the "Chalet house" spot
	 * ({@code -Dvelo.tourSpot=x,z}): a pan over the forest into the sun, a dolly towards the house, a slide through the trees
	 * and you with wings in third person. One frame per tick = 60 fps footage.
	 */
	private static List<Object[]> shaderCinematics(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		String[] spot = System.getProperty("velo.tourSpot", "-151,2332").split(",");
		double sx = Double.parseDouble(spot[0]), sz = Double.parseDouble(spot[1]);
		int[] g = {64};
		steps.add(new Object[] {0, (Runnable) () -> {
			serverCommands(client, VIBE_WORLD);
			serverCommands(client, "time set 12350", "gamemode creative @s", "effect clear @s");
			ModuleRegistry.get("velo-shaders").ifPresent(m -> m.setEnabled(true));
			setChoice("velo-shaders", "Preset", System.getProperty("velo.tourShaderPreset", "Cinematic"));
			setHudHidden(client, true);
			//? if >=26.2 {
			/*client.options.fov().set(70);
			client.options.framerateLimit().set(60);
			*///?}
			camAt(client, sx + 40, 120, sz, 90, 10);
		}});
		steps.add(new Object[] {120, (Runnable) () -> g[0] = groundAt(client, sx, sz)});
		// 1: high over the forest, turning slowly into the sunset
		steps.add(new Object[] {2, (Runnable) () -> camAt(client, sx + 45, g[0] + 34, sz + 6, 78, 12)});
		steps.add(new Object[] {40, (Runnable) () -> { }});
		sequence(steps, client, "sh-sky", 180, f -> camAt(client, sx + 45 - f * 0.08, g[0] + 34 + f * 0.02, sz + 6, 78 + f * 0.11f, 12 - f * 0.04f));
		// 2: low dolly towards the house
		steps.add(new Object[] {2, (Runnable) () -> camAt(client, sx + 26, groundAt(client, sx + 26, sz + 12) + 3, sz + 12, 115, 2)});
		steps.add(new Object[] {40, (Runnable) () -> { }});
		sequence(steps, client, "sh-house", 180, f -> {
			double cx = sx + 26 - f * 0.05, cz = sz + 12 - f * 0.02;
			camAt(client, cx, groundAt(client, sx + 26, sz + 12) + 3.2, cz, 112 + f * 0.03f, 2 - f * 0.01f);
		});
		// 3: sliding sideways through the trees, against the sun
		steps.add(new Object[] {2, (Runnable) () -> camAt(client, sx - 30, groundAt(client, sx - 30, sz - 8) + 2.5, sz - 8, 90, -4)});
		steps.add(new Object[] {40, (Runnable) () -> { }});
		sequence(steps, client, "sh-forest", 180, f -> camAt(client, sx - 30, groundAt(client, sx - 30, sz - 8) + 2.5, sz - 8 + f * 0.035, 90 - f * 0.02f, -4));
		// 4: you in third person with wings, slow half orbit
		steps.add(new Object[] {2, (Runnable) () -> {
			net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(System.getProperty("velo.tourWings", "phoenix"));
			ModuleRegistry.get("free-look").ifPresent(m -> m.setEnabled(true));
			//? if >=26.2 {
			/*var p = client.player;
			p.getAbilities().flying = false;
			*///?}
			serverCommands(client, "tp @s " + (sx + 12) + " " + (groundAt(client, sx + 12, sz + 4) + 1) + " " + (sz + 4) + " 90 6");
		}});
		steps.add(new Object[] {40, (Runnable) () -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 140f, 4.2)});
		sequence(steps, client, "sh-player", 180, f -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 140f + f * 0.25f, 4.2 - f * 0.004));
		steps.add(new Object[] {2, (Runnable) () -> {
			net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(false, 0f);
			net.veloclient.velo.client.cosmetics.wings.WingsManager.clearPreview();
			setHudHidden(client, false);
		}});
		return steps;
	}

	/** Edit HUD Layout in Redstone Mode with a schematic loaded: drags the Schematics panel around by hand-made mouse events. */
	private static List<Object[]> rsLayout(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		steps.add(new Object[] {40, (Runnable) () -> {
			net.veloclient.velo.client.redstone.RedstoneMode.enter();
			String file = System.getProperty("velo.tourSchematic");
			if (file != null) {
				net.veloclient.velo.client.redstone.SchematicIO.loadToClipboard(java.nio.file.Path.of(file));
			}
		}});
		steps.add(new Object[] {60, (Runnable) () -> {
			var p = net.veloclient.velo.client.redstone.Placements.following();
			if (p != null) {
				int[] feet = net.veloclient.velo.client.redstone.RmWorld.playerBlock();
				p.x = feet[0] + 10;
				p.y = feet[1];
				p.z = feet[2] + 10;
				p.locked = true;
			}
		}});
		steps.add(new Object[] {20, (Runnable) () -> shot(client, "rsl-0-world")});
		steps.add(new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.gui.HudEditScreen(null))});
		steps.add(new Object[] {30, (Runnable) () -> shot(client, "rsl-1-editor")});
		//? if >=26.2 {
		/*double[] at = {0, 0};
		steps.add(new Object[] {2, (Runnable) () -> {
			var panel = net.veloclient.velo.client.redstone.RmHud.allPanels().stream().filter(h -> h instanceof net.veloclient.velo.module.Module m && m.id().equals("rm-schematics")).findFirst().orElse(null);
			var screen = net.veloclient.velo.client.util.ClientCompat.currentScreen();
			if (panel == null || screen == null) {
				vibeLog("rsl: no panel/screen");
				return;
			}
			int w = Math.round(panel.width() * panel.position().scale());
			int h = Math.round(panel.height() * panel.position().scale());
			at[0] = panel.position().resolveX(screen.width, w) + 20;
			at[1] = panel.position().resolveY(screen.height, h) + 10;
			vibeLog("rsl: screen " + screen.width + "x" + screen.height + " panel " + w + "x" + h + " at " + at[0] + "," + at[1]);
			screen.mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0], at[1], new net.minecraft.client.input.MouseButtonInfo(0, 0)), false);
		}});
		for (int i = 1; i <= 12; i++) {
			int k = i;
			steps.add(new Object[] {2, (Runnable) () -> {
				var screen = net.veloclient.velo.client.util.ClientCompat.currentScreen();
				double x = at[0] + k * 15, y = at[1] + k * 6;
				screen.mouseDragged(new net.minecraft.client.input.MouseButtonEvent(x, y, new net.minecraft.client.input.MouseButtonInfo(0, 0)), 15, 6);
				if (k % 4 == 0) {
					var panel = net.veloclient.velo.client.redstone.RmHud.allPanels().stream().filter(h -> h instanceof net.veloclient.velo.module.Module m && m.id().equals("rm-schematics")).findFirst().get();
					vibeLog("rsl: drag " + k + " pos " + panel.position().resolveX(screen.width, Math.round(panel.width() * panel.position().scale()))
							+ "," + panel.position().resolveY(screen.height, Math.round(panel.height() * panel.position().scale())) + " h " + panel.height());
					shot(client, "rsl-2-drag" + k);
				}
			}});
		}
		steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.util.ClientCompat.currentScreen().mouseReleased(new net.minecraft.client.input.MouseButtonEvent(at[0] + 180, at[1] + 72,
				new net.minecraft.client.input.MouseButtonInfo(0, 0)))});
		*///?}
		steps.add(new Object[] {20, (Runnable) () -> shot(client, "rsl-3-released")});
		steps.add(new Object[] {2, (Runnable) () -> client.setScreen(null)});
		steps.add(new Object[] {30, (Runnable) () -> shot(client, "rsl-4-world")});
		return steps;
	}

	/** Logs where the (post-composited) cursor is for frame {@code f} of a sequence, in GUI units; {@code down} = pressed. */
	private static void cursorLog(String prefix, int f, float x, float y, boolean down) {
		vibeLog(String.format(java.util.Locale.ROOT, "cur %s %d %.1f %.1f %d", prefix, f, x, y, down ? 1 : 0));
	}

	private static float[] glide(float[] a, float[] b, float t) {
		float e = ease(t);
		return new float[] {a[0] + (b[0] - a[0]) * e, a[1] + (b[1] - a[1]) * e};
	}

	/** Spins the floating sword next to the creeper (item display, yaw in degrees). */
	private static void swordYaw(MinecraftClient client, float yaw) {
		serverCommands(client, String.format(java.util.Locale.ROOT, "data merge entity @e[type=item_display,tag=velotour,limit=1] {Rotation:[%.1ff,0f]}", yaw));
	}

	/** The world shot used before and after the pack: push in on the creeper between the sea lantern pillars. */
	private static void packsWorld(List<Object[]> steps, MinecraftClient client, String prefix) {
		double[][] keys = {{0, -2.5, 0.25, 2.6, 5.5, 1.2, 0.5}, {110, 1.6, 0.05, 1.0, 5.5, 1.3, 0.5}};
		steps.add(new Object[] {2, (Runnable) () -> {
			client.setScreen(null);
			camPath(client, keys, 0);
		}});
		steps.add(new Object[] {20, (Runnable) () -> camPath(client, keys, 0)});
		sequence(steps, client, prefix, 111, f -> {
			camPath(client, keys, f);
			swordYaw(client, -90 + f * 3.2f);
		});
	}

	/** A shader line typed into terrain.fsh (never saved; the copy is removed from the pack afterwards). */
	private static void packsCode(List<Object[]> steps, MinecraftClient client) {
		steps.add(new Object[] {4, (Runnable) () -> {
			net.veloclient.velo.client.gui.packs.PackTour.openCode(client, "minecraft:shaders/core/terrain.fsh");
		}});
		// after the first frame, once the editor knows its text width (else it scrolls sideways)
		steps.add(new Object[] {6, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.code().caretForTour(90, 0)});
		steps.add(new Object[] {20, (Runnable) () -> {
		}});
		String line = "    color.rgb *= vec3(1.0, 0.35, 0.9); // velo tint\n";
		sequence(steps, client, "pk-code", 70, f -> {
			int at = Math.min(line.length(), f * 2);
			int before = Math.min(line.length(), Math.max(0, f - 1) * 2);
			if (at > before) {
				net.veloclient.velo.client.gui.packs.PackTour.code().typeForTour(line.substring(before, at));
			}
		});
	}

	/** Only the code editor shot of {@link #vibePacks}. */
	private static List<Object[]> vibePacksCode(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		steps.add(new Object[] {0, (Runnable) () -> {
			lookFrom(client, -154, 126, 2331.5, -121, 131, 2331.5);
			setHudHidden(client, true);
			net.veloclient.velo.client.gui.packs.PackTour.openPacks(client);
		}});
		steps.add(new Object[] {20, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openBrowser(client, false)});
		steps.add(new Object[] {20, (Runnable) () -> {
		}});
		packsCode(steps, client);
		steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.removeFile("minecraft:shaders/core/terrain.fsh")});
		return steps;
	}

	/** Resource pack editor promo: the world before, the pack menu and editors driven frame by frame, the world after the reload. */
	private static List<Object[]> vibePacks(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		steps.add(new Object[] {0, (Runnable) () -> {
			net.veloclient.velo.client.gui.packs.PackTour.resetPack();
			serverCommands(client, VIBE_WORLD);
			serverCommands(client, "gamemode creative @s", "difficulty easy", "gamerule send_command_feedback false", "effect clear @s", "clear @s",
					"spreadplayers -151 2332 0 1 false @s");
			setHudHidden(client, true);
			//? if >=26.2 {
			/*client.options.fov().set(70);
			client.options.framerateLimit().set(60);
			*///?}
		}});
		steps.add(new Object[] {50, (Runnable) () -> {
			rx = (int) Math.floor(client.player.getX());
			ry = (int) Math.floor(client.player.getY());
			rz = (int) Math.floor(client.player.getZ());
			vibeLog("packs base " + rx + " " + ry + " " + rz);
			clearChat(client);
			rsCommands(client, "kill @e[type=creeper]", "kill @e[type=item_display,tag=velotour]",
					"fill {x-3} {y} {z-5} {x+9} {y+6} {z+5} air", "fill {x-3} {y-1} {z-5} {x+9} {y-1} {z+5} grass_block",
					"fill {x+6} {y} {z-3} {x+6} {y+2} {z-3} sea_lantern", "fill {x+6} {y} {z+3} {x+6} {y+2} {z+3} sea_lantern");
			serverCommands(client, String.format(java.util.Locale.ROOT,
					"summon creeper %.1f %d %.1f {NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[90f,0f]}", rx + 5.5, ry, rz + 0.5));
			serverCommands(client, String.format(java.util.Locale.ROOT,
					"summon item_display %.1f %.2f %.1f {Tags:[\"velotour\"],item:{id:\"minecraft:diamond_sword\",count:1},item_display:\"fixed\","
							+ "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[1.1f,1.1f,1.1f]}}",
					rx + 4.0, ry + 1.25, rz + 2.6));
		}});
		// ---- the world before ----
		packsWorld(steps, client, "pk-world0");

		// ---- pack menu + browser ----
		steps.add(new Object[] {2, (Runnable) () -> {
			lookFrom(client, rx - 3, ry + 1, rz + 0.5, rx + 30, ry + 5, rz + 0.5);
			net.veloclient.velo.client.gui.packs.PackTour.openPacks(client);
		}});
		steps.add(new Object[] {30, (Runnable) () -> vibeLog("gui " + net.veloclient.velo.client.util.ClientCompat.currentScreen().width + " " + net.veloclient.velo.client.util.ClientCompat.currentScreen().height)});
		sequence(steps, client, "pk-menu", 40, f -> {
		});
		steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openBrowser(client, true)});
		net.veloclient.velo.client.gui.packs.PackContents.Category[] cats = {
				net.veloclient.velo.client.gui.packs.PackContents.Category.BLOCKS, net.veloclient.velo.client.gui.packs.PackContents.Category.ITEMS,
				net.veloclient.velo.client.gui.packs.PackContents.Category.ENTITIES, net.veloclient.velo.client.gui.packs.PackContents.Category.PARTICLES,
				net.veloclient.velo.client.gui.packs.PackContents.Category.PAINTINGS, net.veloclient.velo.client.gui.packs.PackContents.Category.INTERFACE,
				net.veloclient.velo.client.gui.packs.PackContents.Category.ENVIRONMENT};
		// every category once so their thumbnails are loaded before the frames
		for (var c : cats) {
			steps.add(new Object[] {25, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.browserCategory(true, c)});
		}
		steps.add(new Object[] {25, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.browserCategory(true, cats[0])});
		sequence(steps, client, "pk-cats", cats.length * 6, f -> {
			if (f % 6 == 0) {
				net.veloclient.velo.client.gui.packs.PackTour.browserCategory(true, cats[f / 6]);
			}
		});
		steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.browserCategory(true,
				net.veloclient.velo.client.gui.packs.PackContents.Category.ITEMS)});
		// scroll down once so thumbnails further down are loaded too
		steps.add(new Object[] {20, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.browser().scrollForTour(1400)});
		steps.add(new Object[] {40, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.browser().scrollForTour(0)});
		sequence(steps, client, "pk-scroll", 90, f -> net.veloclient.velo.client.gui.packs.PackTour.browser().scrollForTour(1400 * ease(f / 89f)));
		steps.add(new Object[] {2, (Runnable) () -> {
			net.veloclient.velo.client.gui.packs.PackTour.browser().scrollForTour(0);
			net.veloclient.velo.client.gui.packs.PackTour.browserCategory(true, net.veloclient.velo.client.gui.packs.PackContents.Category.ENTITIES);
		}});
		String query = "creeper";
		sequence(steps, client, "pk-search", 40, f -> {
			if (f >= 4 && f < 4 + query.length() * 3 && (f - 4) % 3 == 0) {
				net.veloclient.velo.client.gui.packs.PackTour.browser().searchForTour(query.substring(0, (f - 4) / 3 + 1));
			}
		});

		// ---- creeper: red eyes ----
		steps.add(new Object[] {2, (Runnable) () -> {
			net.veloclient.velo.client.gui.packs.PackTour.browser().searchForTour("");
			net.veloclient.velo.client.gui.packs.PackTour.openEditor(client, "minecraft:textures/entity/creeper/creeper.png", false, false);
		}});
		steps.add(new Object[] {40, (Runnable) () -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			ed.toolForTour("PENCIL", 1);
			ed.colorForTour(0xFFFF2B36);
			ed.viewForTour(0, 0, 0);
			ed.previewForTour(0, 6, 1.25f);
		}});
		int[][] eyes = {{9, 10}, {10, 10}, {9, 11}, {10, 11}, {13, 10}, {14, 10}, {13, 11}, {14, 11}};
		int[] eyeColors = {0xFFFF4A52, 0xFFFF4A52, 0xFFD3101C, 0xFFD3101C, 0xFFFF4A52, 0xFFFF4A52, 0xFFD3101C, 0xFFD3101C};
		float[] fitZoom = new float[1];
		sequence(steps, client, "pk-eyes", 170, f -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			if (f == 0) {
				float[] a = ed.screenPointForTour(0, 0);
				float[] b = ed.screenPointForTour(1, 0);
				fitZoom[0] = b[0] - a[0];
			}
			// zoom from the whole texture onto the face, paint, zoom back out
			float z;
			if (f < 22) {
				float e = ease(f / 21f);
				z = fitZoom[0] + (34 - fitZoom[0]) * e;
				ed.viewForTour(z, 32 + (12 - 32) * e, 16 + (12 - 16) * e);
			} else if (f >= 140) {
				float e = ease((f - 140) / 29f);
				z = 34 + (fitZoom[0] - 34) * e;
				ed.viewForTour(z, 12 + (32 - 12) * e, 12 + (16 - 12) * e);
			} else {
				ed.viewForTour(34, 12, 12);
			}
			ed.previewForTour(-18 + f * 0.22f, 6, 1.25f);
			// eight pixels, 13 frames each: glide over, press on frame 8
			float[] cur;
			boolean down = false;
			if (f < 24) {
				float[] from = {ed.width * 0.62f, ed.height * 0.78f};
				cur = glide(from, ed.screenPointForTour(eyes[0][0] + 0.5f, eyes[0][1] + 0.5f), f / 23f);
			} else if (f < 24 + eyes.length * 13) {
				int k = (f - 24) / 13;
				int g = (f - 24) % 13;
				float[] at = ed.screenPointForTour(eyes[k][0] + 0.5f, eyes[k][1] + 0.5f);
				float[] prev = k == 0 ? at : ed.screenPointForTour(eyes[k - 1][0] + 0.5f, eyes[k - 1][1] + 0.5f);
				cur = glide(prev, at, Math.min(1, g / 7f));
				if (g == 8) {
					ed.colorForTour(eyeColors[k]);
					ed.strokeForTour(eyes[k][0], eyes[k][1], eyes[k][0], eyes[k][1], true);
				}
				down = g >= 8 && g <= 10;
			} else {
				int[] last = eyes[eyes.length - 1];
				cur = ed.screenPointForTour(last[0] + 0.5f, last[1] + 0.5f);
				cur = glide(cur, new float[] {cur[0] + 40, cur[1] + 60}, (f - 128) / 20f);
			}
			cursorLog("pk-eyes", f, cur[0], cur[1], down);
		});
		// a full turn of the creeper with its new eyes
		sequence(steps, client, "pk-creeper3d", 120, f -> net.veloclient.velo.client.gui.packs.PackTour.editor().previewForTour(-40 + f * 3f, 8, 1.3f));
		steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.editor().saveForTour()});

		// ---- diamond sword: magic wand + colorize ----
		steps.add(new Object[] {4, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openEditor(client, "minecraft:textures/item/diamond_sword.png", false, false)});
		steps.add(new Object[] {40, (Runnable) () -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			ed.toolForTour("WAND", 1);
			ed.viewForTour(0, 0, 0);
			ed.previewForTour(30, 10, 1.1f);
			ed.colorForTour(0xFFE3262B);
		}});
		sequence(steps, client, "pk-wand", 36, f -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			ed.previewForTour(30 + f * 2.5f, 10, 1.1f);
			float[] at = ed.screenPointForTour(12.5f, 3.5f);
			float[] cur = glide(new float[] {at[0] + 90, at[1] + 80}, at, f / 16f);
			if (f == 18) {
				ed.wandForTour(12, 3, 0.3f);
			}
			cursorLog("pk-wand", f, cur[0], cur[1], f >= 18 && f <= 20);
		});
		sequence(steps, client, "pk-swordhue", 90, f -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			ed.previewForTour(120 + f * 2.5f, 10, 1.1f);
			float hue = 185 + 175 * ease(f / 80f);
			ed.filterForTour("COLORIZE", hue % 360, 0.85f, 0.02f);
		});
		steps.add(new Object[] {2, (Runnable) () -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			ed.filterForTour("COLORIZE", 358, 0.85f, 0.02f);
			ed.applyFilterForTour();
			ed.deselectForTour();
			ed.tabForTour(0);
		}});
		sequence(steps, client, "pk-sword3d", 90, f -> net.veloclient.velo.client.gui.packs.PackTour.editor().previewForTour(f * 4f, 12, 1.15f));
		steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.editor().saveForTour()});

		// ---- sea lantern: colorize every frame, then play the animation ----
		steps.add(new Object[] {4, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openEditor(client, "minecraft:textures/block/sea_lantern.png", false, false)});
		steps.add(new Object[] {40, (Runnable) () -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			ed.viewForTour(0, 0, 0);
			ed.previewForTour(35, 28, 1f);
			ed.colorForTour(0xFFB04DFF);
			ed.filterForTour("COLORIZE", 0, 0.8f, 0.05f);
		}});
		sequence(steps, client, "pk-hue", 140, f -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			ed.filterForTour("COLORIZE", (f * 360f / 120f) % 360, 0.8f, 0.05f);
		});
		steps.add(new Object[] {2, (Runnable) () -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			ed.filterForTour("COLORIZE", 278, 0.8f, 0.05f);
			ed.applyFilterForTour();
			ed.tabForTour(0);
			ed.playForTour(true);
		}});
		steps.add(new Object[] {20, (Runnable) () -> {
		}});
		sequence(steps, client, "pk-anim", 120, f -> net.veloclient.velo.client.gui.packs.PackTour.editor().previewForTour(35 + f * 3f, 26, 1.05f));
		steps.add(new Object[] {2, (Runnable) () -> {
			var ed = net.veloclient.velo.client.gui.packs.PackTour.editor();
			ed.playForTour(false);
			ed.saveForTour();
		}});

		// ---- sounds: trim the menu music, play it ----
		steps.add(new Object[] {4, (Runnable) () -> {
			net.veloclient.velo.client.gui.packs.PackTour.openBrowser(client, true);
			net.veloclient.velo.client.gui.packs.PackTour.browserCategory(true, net.veloclient.velo.client.gui.packs.PackContents.Category.SOUNDS);
		}});
		sequence(steps, client, "pk-sounds", 40, f -> net.veloclient.velo.client.gui.packs.PackTour.browser().scrollForTour(f < 10 ? 0 : 500 * ease((f - 10) / 29f)));
		steps.add(new Object[] {4, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.openSound(client, "minecraft:music.menu")});
		steps.add(new Object[] {120, (Runnable) () -> {
		}});
		sequence(steps, client, "pk-sound", 120, f -> {
			var snd = net.veloclient.velo.client.gui.packs.PackTour.sound();
			if (f < 50) {
				float e = ease(f / 49f);
				snd.trimForTour(48 * e, 306.5 - 96 * e);
			}
			if (f == 52) {
				snd.playForTour();
			}
		});
		steps.add(new Object[] {2, (Runnable) net.veloclient.velo.client.gui.packs.AudioPreview::stop});

		packsCode(steps, client);
		steps.add(new Object[] {2, (Runnable) () -> {
			net.veloclient.velo.client.gui.packs.PackTour.removeFile("minecraft:shaders/core/terrain.fsh");
			net.veloclient.velo.client.gui.packs.PackTour.openBrowser(client, false);
			net.veloclient.velo.client.gui.packs.PackTour.browserCategory(false, net.veloclient.velo.client.gui.packs.PackContents.Category.BLOCKS);
		}});
		steps.add(new Object[] {20, (Runnable) () -> {
		}});
		sequence(steps, client, "pk-inpack", 30, f -> {
		});

		// ---- apply + reload, then the same world shot ----
		steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.gui.packs.PackTour.applyAndReload()});
		steps.add(new Object[] {200, (Runnable) () -> client.setScreen(null)});
		packsWorld(steps, client, "pk-world1");
		// close-up orbit around the creeper's new eyes
		sequence(steps, client, "pk-orbit", 120, f -> {
			double ang = Math.toRadians(180 - 50 + 100 * ease(f / 119f));
			double r = 2.3;
			lookFrom(client, rx + 5.5 + Math.cos(ang) * r, ry - 0.1, rz + 0.5 + Math.sin(ang) * r, rx + 5.5, ry + 1.35, rz + 0.5);
			swordYaw(client, f * 3.2f);
		});
		return steps;
	}

	private static List<Object[]> redstoneShort(MinecraftClient client) {
		List<Object[]> steps = new ArrayList<>();
		String schematic = System.getProperty("velo.tourSchematic", "1-pumpkin-2-mel-z3hupun1.litematic");
		var rs = new Object() {
			net.veloclient.velo.client.redstone.SchematicPickScreen pick;
			net.veloclient.velo.client.redstone.SchematicSaveScreen save;
		};
		// ---- setup: a fresh flat spot, daytime, no chat spam ----
		steps.add(new Object[] {0, (Runnable) () -> {
			serverCommands(client, "gamemode creative @s", "gamerule send_command_feedback false", "time set 5000", "weather clear",
					"gamerule advance_time false", "gamerule advance_weather false",
					"spreadplayers " + (8000 + (System.currentTimeMillis() / 1000 % 400) * 200) + " 6000 0 1 false @s");
			setHudHidden(client, false);
			//? if >=26.2 {
			/*client.options.fov().set(60);
			// 60 fps is plenty for one shot per tick and keeps the rest of the PC usable
			client.options.framerateLimit().set(60);
			*///?}
		}});
		steps.add(new Object[] {60, (Runnable) () -> {
			rx = (int) Math.floor(client.player.getX());
			ry = (int) Math.floor(client.player.getY());
			rz = (int) Math.floor(client.player.getZ());
			vibeLog("rs base " + rx + " " + ry + " " + rz);
			clearChat(client);
			net.veloclient.velo.client.redstone.RmSettings.get().pasteSpeed = 90;
			// wiring set: a lever on stone, a lamp further on, two walls in the way
			rsCommands(client, "setblock {x+18} {y} {z+8} stone", "setblock {x+18} {y+1} {z+8} lever[face=floor,facing=south]",
					"setblock {x+31} {y} {z+29} redstone_lamp",
					"fill {x+21} {y} {z+14} {x+29} {y+2} {z+14} stone_bricks", "fill {x+24} {y} {z+21} {x+33} {y+2} {z+21} stone_bricks");
			net.veloclient.velo.client.redstone.RedstoneMode.enter();
			lookFrom(client, rx + 0.5, ry + 6, rz - 6, rx + 0.5, ry, rz + 39.5);
		}});
		// ---- 1. pick the schematic (search) ----
		steps.add(new Object[] {40, (Runnable) () -> {
			clearChat(client);
			rs.pick = new net.veloclient.velo.client.redstone.SchematicPickScreen(null);
			client.setScreen(rs.pick);
		}});
		String query = "pumpkin";
		sequence(steps, client, "rs-pick", 54, f -> {
			if (f >= 14 && f < 14 + query.length() * 3 && (f - 14) % 3 == 0) {
				typeForTour(client, query.substring(0, (f - 14) / 3 + 1));
			}
		});
		// ---- 2. it follows your crosshair, then locks ----
		steps.add(new Object[] {2, (Runnable) () -> {
			client.setScreen(null);
			net.veloclient.velo.client.redstone.SchematicIO.loadToClipboard(net.veloclient.velo.client.redstone.SchematicIO.litematicFolder().resolve(schematic));
			lookFrom(client, rx + 0.5, ry + 9, rz + 2, rx - 34, ry, rz + 62);
		}});
		double[][] place = {{0, .5, 9, 2, -34, 0, 62}, {50, .5, 8, 6, .5, 0, 39.5}, {70, .5, 8, 5, .5, 4, 39.5}};
		steps.add(new Object[] {25, (Runnable) () -> rsPlacement = net.veloclient.velo.client.redstone.Placements.following()});
		sequence(steps, client, "rs-place", 72, f -> {
			camPath(client, place, f);
			if (f == 52 && rsPlacement != null) {
				var v = rsPlacement.view();
				rsPlacement.x = rx - v.sx / 2;
				rsPlacement.y = ry;
				rsPlacement.z = rz + 22;
				rsPlacement.locked = true;
				vibeLog("rs placement " + v.sx + " " + v.sy + " " + v.sz);
			}
		});
		// ---- 3. paste it, orbiting ----
		sequence(steps, client, "rs-paste", 150, f -> {
			var p = rsPlacement;
			if (p == null) {
				return;
			}
			var v = p.view();
			double cx = p.x - rx + v.sx / 2.0, cz = p.z - rz + v.sz / 2.0;
			double ang = Math.toRadians(-118 + f * 0.36);
			double r = 34 - f * 0.03;
			double y = 9 + f * 0.05;
			lookFrom(client, rx + cx + Math.cos(ang) * r, ry + y, rz + cz + Math.sin(ang) * r, rx + cx, ry + v.sy * 0.42, rz + cz);
			if (f == 6) {
				net.veloclient.velo.client.redstone.RmOps.paste(v, p.x, p.y, p.z);
			}
		});
		// ---- 4. build guide: some wrong and missing blocks on the front ----
		steps.add(new Object[] {30, (Runnable) () -> {
			var p = rsPlacement;
			var v = p.view();
			RS_WRONG.clear();
			List<String> cmds = new ArrayList<>();
			int n = 0;
			for (int y = 1; y < Math.min(6, v.sy) && n < 7; y++) {
				for (int dx = 0; dx < v.sx && n < 7; dx += 3) {
					int x = p.x + (v.sx / 2 + (dx % 2 == 0 ? dx / 2 : -dx / 2)) % v.sx;
					int z = p.z;
					Object want = net.veloclient.velo.client.redstone.Placements.stateAt(x, p.y + y, z);
					if (want != null && !net.veloclient.velo.client.redstone.RmWorld.isAir(want)) {
						cmds.add("setblock " + x + " " + (p.y + y) + " " + z + (n % 3 == 2 ? " air" : n % 2 == 0 ? " minecraft:stone" : " minecraft:oak_planks"));
						RS_WRONG.add(new int[] {x, p.y + y, z, n % 3 == 2 ? 0 : 1});
						n++;
					}
				}
			}
			serverCommands(client, cmds.toArray(new String[0]));
			vibeLog("rs wrong " + RS_WRONG.size());
		}});
		sequence(steps, client, "rs-guide", 100, f -> {
			var p = rsPlacement;
			int[] w = RS_WRONG.stream().filter(a -> a[3] == 1).findFirst().orElse(new int[] {p.x + 10, p.y + 2, p.z, 1});
			double tx = w[0] + .5 - rx, ty = w[1] + .5 - ry, tz = w[2] + .5 - rz;
			double[][] keys = {{0, tx - 9, ty + 4, tz - 16, tx, ty, tz}, {60, tx - 1.2, ty - 1.4, tz - 3.6, tx, ty, tz}, {100, tx - .4, ty - 1.3, tz - 3.4, tx, ty, tz}};
			camPath(client, keys, f);
		});
		// ---- 5. material list ----
		steps.add(new Object[] {2, (Runnable) () -> client.setScreen(new net.veloclient.velo.client.redstone.MaterialListScreen(rsPlacement))});
		sequence(steps, client, "rs-materials", 50, f -> {
		});
		// ---- 6. wiring: lever to lamp, around the walls ----
		steps.add(new Object[] {2, (Runnable) () -> {
			client.setScreen(null);
			net.veloclient.velo.client.redstone.RedstoneMode.selectTool(net.veloclient.velo.client.redstone.RmState.Tool.WIRE);
			net.veloclient.velo.client.redstone.RedstoneMode.setToolSlot(true);
		}});
		double[][] wire = {{0, 13, 6, -2, 18.5, .5, 8.5}, {22, 15, 4, 2, 18.5, .5, 8.5}, {55, 26, 6, 18, 31.5, .5, 29.5}, {85, 25, 22, 12, 25, 0, 20},
				{125, 25, 22, 12, 25, 0, 20}, {150, 27, 4, 23, 31.5, .5, 29.5}, {175, 27.5, 3.5, 24, 31.5, .5, 29.5}};
		sequence(steps, client, "rs-wire", 176, f -> {
			camPath(client, wire, f);
			if (f == 22) {
				net.veloclient.velo.client.redstone.RmState.wireStart = new int[] {rx + 18, ry, rz + 8, 5};
			} else if (f == 55) {
				net.veloclient.velo.client.redstone.RmState.wireEnd = new int[] {rx + 31, ry, rz + 29, 2};
				net.veloclient.velo.client.redstone.Wiring.plan(false);
			} else if (f == 110) {
				net.veloclient.velo.client.redstone.Wiring.build();
			} else if (f == 150) {
				rsCommands(client, "setblock {x+18} {y+1} {z+8} lever[face=floor,facing=south,powered=true]");
			} else if (f == 151) {
				// a command-set lever doesn't update the wire; swapping the block under it does
				rsCommands(client, "setblock {x+18} {y} {z+8} smooth_stone");
			}
		});
		// ---- 7. blow up a corner, then rewind it ----
		steps.add(new Object[] {2, (Runnable) () -> {
			var p = rsPlacement;
			var v = p.view();
			net.veloclient.velo.client.redstone.RedstoneMode.selectTool(net.veloclient.velo.client.redstone.RmState.Tool.SELECT);
			net.veloclient.velo.client.redstone.RmState.cornerA = new int[] {p.x - 3, p.y, p.z - 4};
			net.veloclient.velo.client.redstone.RmState.cornerB = new int[] {p.x + v.sx + 2, p.y + Math.min(v.sy, 16), p.z + 10};
		}});
		sequence(steps, client, "rs-rewind", 150, f -> {
			var p = rsPlacement;
			var v = p.view();
			double cx = p.x - rx + v.sx / 2.0, cz = p.z - rz;
			double[][] keys = {{0, cx - 9, 7, cz - 17, cx, 5, cz + 3}, {150, cx - 4, 6, cz - 13, cx, 5, cz + 3}};
			camPath(client, keys, f);
			if (f == 2) {
				net.veloclient.velo.client.redstone.Timeline.toggle();
			} else if (f == 6) {
				int mx = p.x + v.sx / 2;
				serverCommands(client, "summon tnt " + (mx - 4) + " " + (p.y + 2) + " " + (p.z + 1) + " {fuse:24}",
						"summon tnt " + (mx + 3) + " " + (p.y + 3) + " " + (p.z + 1) + " {fuse:26}",
						"summon tnt " + mx + " " + (p.y + 6) + " " + (p.z + 1) + " {fuse:25}",
						"summon tnt " + (mx - 2) + " " + (p.y + 9) + " " + (p.z + 1) + " {fuse:27}",
						"summon tnt " + (mx + 2) + " " + (p.y + 1) + " " + (p.z + 2) + " {fuse:24}",
						"summon tnt " + (mx + 5) + " " + (p.y + 7) + " " + (p.z + 1) + " {fuse:28}");
			} else if (f == 70) {
				net.veloclient.velo.client.redstone.Timeline.toggle();
			} else if (f >= 85 && f <= 140) {
				net.veloclient.velo.client.redstone.Timeline.seekFraction(1 - ease((f - 85) / 55f));
			}
		});
		steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.redstone.Timeline.toggle()});
		// ---- your player, third person, by the lit lamp with the farm behind ----
		steps.add(new Object[] {4, (Runnable) () -> {
			//? if >=26.2 {
			/*client.player.getAbilities().flying = false;
			*///?}
			lookFrom(client, rx + 27.5, ry, rz + 26.5, rx + 0.5, ry + 8, rz + 30);
			ModuleRegistry.get("free-look").ifPresent(m -> m.setEnabled(true));
		}});
		sequence(steps, client, "rs-me", 90, f -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(true, 150f + f * 0.9f, 4.2));
		steps.add(new Object[] {2, (Runnable) () -> net.veloclient.velo.client.modules.qol.FreeLookModule.forceForTour(false, 0f)});
		// ---- 8. save it for Litematica friends ----
		steps.add(new Object[] {10, (Runnable) () -> {
			var p = rsPlacement;
			var v = p.view();
			net.veloclient.velo.client.redstone.RmState.cornerA = new int[] {p.x, p.y, p.z};
			net.veloclient.velo.client.redstone.RmState.cornerB = new int[] {p.x + v.sx - 1, p.y + v.sy - 1, p.z + v.sz - 1};
			double cx = p.x + v.sx / 2.0, cz = p.z + v.sz / 2.0;
			lookFrom(client, cx - 30, p.y + 20, cz - 30, cx, p.y + 6, cz);
			rs.save = new net.veloclient.velo.client.redstone.SchematicSaveScreen();
			client.setScreen(rs.save);
		}});
		sequence(steps, client, "rs-save", 60, f -> {
			if (f == 3) {
				typeForTour(client, "pumpkin-melon-farm");
			} else if (f == 24) {
				rs.save.litematicForTour(true);
			}
		});
		return steps;
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
		// After a hitch the game runs the missed ticks back to back without drawing in between; skip
		// those, so frame sequences never shoot the same picture twice or jump the camera.
		long now = System.nanoTime();
		if (now - lastSceneTick < 30_000_000L) {
			return;
		}
		lastSceneTick = now;
		while (!queue.isEmpty() && t >= nextAt + (int) queue.peek()[0]) {
			Object[] step = queue.poll();
			nextAt = t;
			((Runnable) step[1]).run();
			if ((int) step[0] > 0) {
				break;
			}
		}
	}

	private static long lastSceneTick;

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

	/** Looks down by {@code pitch} degrees (so the crosshair hits the ground for placements). */
	private static void aim(MinecraftClient client, float pitch) {
		//? if >=26.2 {
		/*client.player.setXRot(pitch);
		*///?}
	}

	/** Types into the focused text field of the open screen. */
	private static void typeForTour(MinecraftClient client, String text) {
		//? if >=26.2 {
		/*if (client.gui.screen() != null && client.gui.screen().getFocused() instanceof net.minecraft.client.gui.components.EditBox box) {
			box.setValue(text);
		}
		*///?}
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

	/** "hud" scene: a few effects and damaged diamond armor so Potion Timers and Armor have something to show. */
	private static void hudSetup(MinecraftClient client) {
		//? if >=26.2 {
		/*var server = client.getSingleplayerServer();
		String name = client.player.getGameProfile().name();
		if (server != null) {
			server.execute(() -> {
				var source = server.createCommandSourceStack();
				for (String command : List.of("effect give " + name + " minecraft:speed 300 1", "effect give " + name + " minecraft:strength 102",
						"effect give " + name + " minecraft:fire_resistance 8", "time set day", "weather clear",
						"item replace entity " + name + " armor.head with minecraft:diamond_helmet[minecraft:damage=40]",
						"item replace entity " + name + " armor.chest with minecraft:diamond_chestplate[minecraft:damage=300]",
						"item replace entity " + name + " armor.legs with minecraft:diamond_leggings[minecraft:damage=420]",
						"item replace entity " + name + " armor.feet with minecraft:diamond_boots[minecraft:damage=10]")) {
					server.getCommands().performPrefixedCommand(source, command);
				}
			});
		}
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

	/** Opens the recipe book of the open inventory screen, the way its button does (26.2+: reflection on the component). */
	private static void toggleRecipeBook(MinecraftClient client) {
		//? if >=26.2 {
		/*Object screen = client.gui.screen();
		try {
			for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
				for (var field : c.getDeclaredFields()) {
					if (field.getType().getSimpleName().equals("RecipeBookComponent")) {
						field.setAccessible(true);
						Object book = field.get(screen);
						if (!(boolean) book.getClass().getMethod("isVisible").invoke(book)) {
							book.getClass().getMethod("toggleVisibility").invoke(book);
						}
						// what the book's button does next: re-place the screen around the open book
						((net.minecraft.client.gui.screens.Screen) screen).resize(client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
						return;
					}
				}
			}
		} catch (ReflectiveOperationException e) {
			net.veloclient.velo.VeloClient.LOGGER.warn("Tour: couldn't open the recipe book", e);
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

	private static final java.util.concurrent.ExecutorService RAW_WRITER = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "velo-tour-writer");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		return thread;
	});

	/** Like {@link #shot}, but saves raw RGBA bytes ({@code name_<w>x<h>.rgba}) off the render thread - fast enough for one per tick. */
	private static void rawShot(MinecraftClient client, String name) {
		//? if >=26.2 {
		/*try {
			Files.createDirectories(folder);
			net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
				try (image) {
					var bytes = image.getPixelBytes();
					byte[] copy = new byte[bytes.remaining()];
					bytes.get(copy);
					Path file = folder.resolve(name + "_" + image.getWidth() + "x" + image.getHeight() + ".rgba");
					RAW_WRITER.submit(() -> {
						try {
							Files.write(file, copy);
						} catch (Exception e) {
							VeloClient.LOGGER.error("Velo screenshot tour: saving {} failed", file, e);
						}
					});
				}
			});
		} catch (Exception e) {
			VeloClient.LOGGER.error("Velo screenshot tour: {} failed", name, e);
		}
		*///?}
	}

	private static void stop(MinecraftClient client) {
		RAW_WRITER.shutdown();
		try {
			RAW_WRITER.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS);
		} catch (InterruptedException ignored) {
			// quitting anyway
		}
		VeloClient.LOGGER.info("Velo screenshot tour finished");
		//? if >=26.1 {
		/*client.stop();
		*///?} else {
		client.scheduleStop();
		//?}
	}
}
