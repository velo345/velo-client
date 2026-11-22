package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Dev-only ({@code -Dvelo.benchSpawn=true}, singleplayer; combine with {@code -Dvelo.benchCrowd=N} for players): a
 * typical server spawn for profiling, built through the integrated server three seconds after joining - hologram
 * stacks (text displays, some re-texted every 5 ticks like animated server holograms), named armour-stand NPCs, signs,
 * banners, player heads, item displays, a 15-line sidebar, a boss bar and a particle fountain. The camera then turns
 * slowly on the spot (no flying) and MoveBench logs the frame stats. It changes the world: run it on a copy.
 */
public final class SpawnBench {

	private static int ticks;

	private SpawnBench() {
	}

	public static void register() {
		if (!Boolean.getBoolean("velo.benchSpawn")) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			//? if >=26.2 {
			/*var player = client.player;
			if (player == null) {
				return;
			}
			ticks++;
			// -Dvelo.benchEnable=id,id: turns these modules on once the world is there (feature checks in the harness)
			if (ticks == 40) {
				for (String id : System.getProperty("velo.benchEnable", "").split(",")) {
					if (!id.isBlank()) {
						var found = net.veloclient.velo.module.ModuleRegistry.get(id.trim());
						found.ifPresent(m -> m.setEnabled(true));
						net.veloclient.velo.VeloClient.LOGGER.info("[bench] enable {} -> {}", id, found.isPresent());
					}
				}
				for (String id : System.getProperty("velo.benchDisable", "").split(",")) {
					if (!id.isBlank()) {
						net.veloclient.velo.module.ModuleRegistry.get(id.trim()).ifPresent(m -> m.setEnabled(false));
					}
				}
			}
			// -Dvelo.lyricsPreset=Name: that Lyrics preset (perf checks)
			if (ticks == 41 && System.getProperty("velo.lyricsPreset") != null) {
				net.veloclient.velo.client.lyrics.LyricsModule.applyPreset(System.getProperty("velo.lyricsPreset").replace('_', ' '));
				if (Boolean.getBoolean("velo.lyricsNoBeat")) {
					net.veloclient.velo.client.lyrics.LyricsModule.editForTour(st -> st.beatSync = false);
				}
			}
			// -Dvelo.benchScreen=lyrics: opens the Lyrics Studio (UI checks)
			if (ticks == 200 && "lyrics".equals(System.getProperty("velo.benchScreen"))) {
				net.veloclient.velo.client.lyrics.LyricsModule.openStudio();
			}
			if (ticks == 200 && "heart".equals(System.getProperty("velo.benchScreen"))) {
				net.veloclient.velo.client.gui.StatusIconEditor.open(null, "heart", "Heart", 0xFFE8262E);
			}
			// -Dvelo.benchSpawnStill=true: fixed camera (pixel comparisons between runs); else turning 30 degrees a second
			if (ticks > 80) {
				if (Boolean.getBoolean("velo.benchSpawnStill")) {
					player.setYRot(Float.parseFloat(System.getProperty("velo.benchYaw", "0")));
					player.setXRot(Float.parseFloat(System.getProperty("velo.benchPitch", "20")));
				} else {
					player.setYRot(player.getYRot() + 1.5f);
					player.setXRot(5f);
				}
			}
			// -Dvelo.benchShots=N: N screenshots one tick apart after tick 300 (flicker checks), next to benchShot
			int burst = Integer.getInteger("velo.benchShots", 0);
			if (burst > 0 && ticks > 300 && ticks <= 300 + burst && System.getProperty("velo.benchShot") != null) {
				String file = System.getProperty("velo.benchShot").replace(".png", "-" + (ticks - 300) + ".png");
				net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
					try (image) {
						image.writeToFile(java.nio.file.Path.of(file));
					} catch (Exception e) {
						net.veloclient.velo.VeloClient.LOGGER.warn("spawn bench screenshot failed", e);
					}
				});
			}
			String shotFile = System.getProperty("velo.benchShot");
			if (shotFile != null && ticks == 300) {
				net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
					try (image) {
						image.writeToFile(java.nio.file.Path.of(shotFile));
					} catch (Exception e) {
						net.veloclient.velo.VeloClient.LOGGER.warn("spawn bench screenshot failed", e);
					}
				});
			}
			var server = client.getSingleplayerServer();
			if (server == null) {
				return;
			}
			// -Dvelo.benchTime=18000: time of day (night shots)
			if (ticks == 62 && System.getProperty("velo.benchTime") != null && client.getSingleplayerServer() != null) {
				String time = System.getProperty("velo.benchTime");
				client.getSingleplayerServer().execute(() -> run(client.getSingleplayerServer(), "time set " + time));
				client.getSingleplayerServer().execute(() -> run(client.getSingleplayerServer(), "gamerule doDaylightCycle false"));
			}
			if (ticks == 60) {
				int px = player.getBlockX();
				int py = player.getBlockY();
				int pz = player.getBlockZ();
				server.execute(() -> build(server, px, py, pz));
			}
			boolean still = Boolean.getBoolean("velo.benchSpawnStill");
			if (!still && ticks > 80 && ticks % 5 == 0) {
				int frame = ticks / 5;
				server.execute(() -> run(server, "execute as @e[tag=velo_anim] run data merge entity @s {text:{text:\"✦ SALE ✦ " + frame % 100
						+ "%\",color:\"" + (frame % 2 == 0 ? "gold" : "yellow") + "\",bold:true}}"));
			}
			if (!still && ticks > 80) {
				server.execute(() -> run(server, "execute as @a at @s run particle minecraft:end_rod ~ ~3 ~8 1 1 1 0.02 6 force"));
			}
			if (ticks == 200) {
				MoveBench.recordOnly();
				server.execute(() -> {
					int count = 0;
					for (var ignored : server.overworld().getAllEntities()) {
						count++;
					}
					net.veloclient.velo.VeloClient.LOGGER.info("Velo spawn bench: {} entities in the world", count);
				});
			}
			*///?}
		});
	}

	//? if >=26.2 {
	/*private static void run(net.minecraft.server.MinecraftServer server, String command) {
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
	}

	private static void build(net.minecraft.server.MinecraftServer server, int px, int py, int pz) {
		run(server, "gamerule doDaylightCycle false");
		run(server, "time set 6000");
		run(server, "kill @e[type=!player]");
		// hologram stacks: 12 stacks of 5 lines, 3 of them animated
		String[] colors = {"gold", "aqua", "light_purple", "green", "red", "yellow"};
		for (int s = 0; s < 12; s++) {
			double a = s / 12.0 * Math.PI * 2;
			double x = px + 0.5 + Math.cos(a) * 9;
			double z = pz + 0.5 + Math.sin(a) * 9;
			for (int line = 0; line < 5; line++) {
				String tag = s % 4 == 0 && line == 0 ? ",Tags:[\"velo_anim\"]" : "";
				run(server, String.format(java.util.Locale.ROOT,
						"summon text_display %.2f %.2f %.2f {billboard:\"center\",text:{text:\"Line %d of hologram %d - play now!\",color:\"%s\",bold:%s}%s}",
						x, py + 3.2 - line * 0.3, z, line + 1, s + 1, colors[(s + line) % colors.length], line == 0, tag));
			}
		}
		// NPCs: named armour stands with armour and a held item
		for (int i = 0; i < 24; i++) {
			double a = i / 24.0 * Math.PI * 2 + 0.13;
			run(server, String.format(java.util.Locale.ROOT,
					"summon armor_stand %.2f %d %.2f {CustomName:{text:\"[NPC] Game %d\",color:\"aqua\"},CustomNameVisible:1b,ShowArms:1b,"
							+ "equipment:{head:{id:\"diamond_helmet\",count:1},chest:{id:\"iron_chestplate\",count:1},mainhand:{id:\"diamond_sword\",count:1}}}",
					px + 0.5 + Math.cos(a) * 6, py, pz + 0.5 + Math.sin(a) * 6, i + 1));
		}
		// signs, banners, heads, item displays on a ring of stone posts
		for (int i = 0; i < 40; i++) {
			double a = i / 40.0 * Math.PI * 2;
			int x = px + (int) Math.round(Math.cos(a) * 13);
			int z = pz + (int) Math.round(Math.sin(a) * 13);
			run(server, "setblock " + x + " " + py + " " + z + " stone");
			run(server, "setblock " + x + " " + (py + 1) + " " + z
					+ " oak_sign{front_text:{messages:[\"Server shop\",\"Ranks & keys\",\"/store\",\"Line " + i + "\"]}}");
			if (i < 16) {
				run(server, "setblock " + x + " " + (py + 2) + " " + z + " stone");
				run(server, "setblock " + x + " " + (py + 3) + " " + z
						+ " white_banner{patterns:[{color:\"red\",pattern:\"stripe_top\"},{color:\"blue\",pattern:\"cross\"},{color:\"yellow\",pattern:\"border\"}]}");
			} else if (i < 40) {
				run(server, "setblock " + x + " " + (py + 2) + " " + z + " player_head");
			}
		}
		for (int i = 0; i < 16; i++) {
			double a = i / 16.0 * Math.PI * 2 + 0.2;
			run(server, String.format(java.util.Locale.ROOT,
					"summon item_display %.2f %.2f %.2f {billboard:\"center\",item:{id:\"%s\",count:1}}",
					px + 0.5 + Math.cos(a) * 4, py + 2.0, pz + 0.5 + Math.sin(a) * 4, i % 2 == 0 ? "diamond_sword" : "golden_apple"));
		}
		// block displays: tinted (grass, leaves), translucent (glass), shaped (stairs), emissive (lantern), special renderers
		// (chest, bed), plants - every kind of block model the memo has to get right
		String[] blocks = {"grass_block", "oak_leaves", "glass", "red_stained_glass", "oak_stairs", "lantern", "chest", "red_bed",
				"poppy", "sunflower", "glowstone", "sea_lantern", "redstone_wire", "oak_fence", "anvil", "bell"};
		for (int i = 0; i < blocks.length; i++) {
			double a = i / (double) blocks.length * Math.PI * 2 + 0.05;
			run(server, String.format(java.util.Locale.ROOT,
					"summon block_display %.2f %.2f %.2f {block_state:{Name:\"minecraft:%s\"},transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[-0.5f,0f,-0.5f],scale:[1f,1f,1f]}}",
					px + 0.5 + Math.cos(a) * 3.2, (double) py, pz + 0.5 + Math.sin(a) * 3.2, blocks[i]));
		}
		// the cases the block model memo once broke (invisible primed TNT): fused TNT, block-carrying minecarts, an enderman's
		// block - in front of the fixed still camera (it looks south)
		run(server, String.format(java.util.Locale.ROOT, "summon tnt %.2f %d %.2f {fuse:32000s,NoGravity:1b}", px - 1.5, py, pz + 5.0));
		run(server, String.format(java.util.Locale.ROOT, "summon tnt_minecart %.2f %d %.2f {NoGravity:1b}", px + 0.5, py, pz + 6.0));
		run(server, String.format(java.util.Locale.ROOT, "summon chest_minecart %.2f %d %.2f {NoGravity:1b}", px + 2.5, py, pz + 6.0));
		run(server, String.format(java.util.Locale.ROOT,
				"summon enderman %.2f %d %.2f {NoAI:1b,Rotation:[180f,0f],carriedBlockState:{Name:\"minecraft:melon\"}}", px - 3.5, py, pz + 7.0));
		// sidebar and boss bar
		run(server, "scoreboard objectives add velo_side dummy {text:\"MY SERVER\",color:\"gold\",bold:true}");
		run(server, "scoreboard objectives setdisplay sidebar velo_side");
		for (int i = 0; i < 15; i++) {
			run(server, "scoreboard players set line" + i + " velo_side " + i);
		}
		run(server, "bossbar add velo_bar {text:\"Welcome to the server - season 5\",color:\"aqua\"}");
		run(server, "bossbar set velo_bar players @a");
		net.veloclient.velo.VeloClient.LOGGER.info("Velo spawn bench: built around {} {} {}", px, py, pz);
	}
	*///?}
}
