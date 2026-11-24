package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Dev only ({@code -Dvelo.skyboxTest=<png path>}): in a world, turns Custom Skybox on, waits a few seconds, saves a
 * screenshot to that path and quits - to check the sky on a version without the screenshot tour (1.21.x).
 */
public final class SkyboxSmoke {

	private static int ticks;
	private static boolean shot;

	private SkyboxSmoke() {
	}

	public static void register() {
		String target = System.getProperty("velo.skyboxTest");
		if (target == null) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			//? if <26.1 {
			boolean inWorld = client.world != null && client.player != null;
			//?} else {
			/*boolean inWorld = client.level != null && client.player != null;
			*///?}
			if (!inWorld || shot) {
				return;
			}
			ticks++;
			if (ticks == 20) {
				net.veloclient.velo.module.ModuleRegistry.get("custom-skybox").ifPresent(m -> m.setEnabled(true));
				//? if <26.1 {
				client.player.setPitch(-25);
				//?}
			}
			if (ticks == 160) {
				shot = true;
				//? if >=1.21.10 && <26.1 {
				net.minecraft.client.util.ScreenshotRecorder.takeScreenshot(client.getFramebuffer(), image -> {
					try {
						image.writeTo(java.nio.file.Path.of(target));
						net.veloclient.velo.VeloClient.LOGGER.info("[skybox test] screenshot {}", target);
					} catch (Exception e) {
						net.veloclient.velo.VeloClient.LOGGER.error("[skybox test] screenshot failed", e);
					} finally {
						image.close();
						client.execute(client::scheduleStop);
					}
				});
				//?}
			}
		});
	}
}
