package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Dev-only ({@code -Dvelo.benchCrowd=N}): once in a world, N fake players (client-side only) stand in rings around you,
 * every one wearing wings, an aura, an animated cloak and bodywear (as other Velo users) - a crowded lobby for comparing
 * Cosmetics Quality levels ({@code -Dvelo.cosmeticsQuality=High|Medium|Low}). 26.2+.
 */
public final class CrowdBench {

	private static int ticks;

	private CrowdBench() {
	}

	public static void register() {
		int n = Integer.getInteger("velo.benchCrowd", 0);
		if (n <= 0) {
			return;
		}
		String wings = System.getProperty("velo.benchCrowdWings", "phoenix");
		String aura = System.getProperty("velo.benchCrowdAura", "ember");
		String cape = System.getProperty("velo.benchCrowdCape", "cape_red_lightning");
		String wear = System.getProperty("velo.benchCrowdWear", "top_hat,sneakers");
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			//? if >=26.2 {
			/*if (client.player == null || client.level == null) {
				return;
			}
			ticks++;
			if (ticks == 60) {
				net.minecraft.world.phys.Vec3 at = client.player.position();
				for (int i = 0; i < n; i++) {
					java.util.UUID id = new java.util.UUID(0x7e10c0de00000000L | i, i * 31L + 7);
					var player = new net.minecraft.client.player.RemotePlayer(client.level, new com.mojang.authlib.GameProfile(id, "Crowd" + i));
					double ring = 4 + (i / 8) * 3, a = (i % 8) / 8.0 * Math.PI * 2 + (i / 8) * 0.4;
					double x = at.x + Math.cos(a) * ring, z = at.z + Math.sin(a) * ring;
					player.setId(-100_000 - i);
					player.snapTo(x, at.y, z, (float) Math.toDegrees(Math.atan2(at.z - z, at.x - x)) - 90f, 0f);
					client.level.addEntity(player);
				}
			}
			String shot = System.getProperty("velo.benchShot");
			if (shot != null && ticks == 600) {
				java.nio.file.Path file = java.nio.file.Path.of(shot);
				net.minecraft.client.Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
					try (image) {
						image.writeToFile(file);
					} catch (Exception e) {
						net.veloclient.velo.VeloClient.LOGGER.error("bench screenshot failed", e);
					}
				});
			}
			if (ticks >= 60 && ticks % 40 == 0) {
				// keep them listed as Velo users (a poll from the network would replace the registry)
				java.util.Map<String, net.veloclient.velo.client.network.VeloUserRegistry.Entry> entries = new java.util.HashMap<>();
				for (int i = 0; i < n; i++) {
					java.util.UUID id = new java.util.UUID(0x7e10c0de00000000L | i, i * 31L + 7);
					entries.put(id.toString().replace("-", ""), new net.veloclient.velo.client.network.VeloUserRegistry.Entry("Crowd" + i, cape, wings, aura,
							false, null, wear, false));
				}
				net.veloclient.velo.client.network.VeloUserRegistry.replaceAll(entries);
			}
			*///?}
		});
	}
}
