package net.veloclient.legacy;

import net.ornithemc.osl.entrypoints.api.client.ClientModInitializer;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;
import net.veloclient.legacy.net.VeloNetwork;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Velo Client on 1.8.9: Velo cloaks, the Velo badge in the tab list and on name tags, and Velo Network. */
public final class VeloLegacy implements ClientModInitializer {

	public static final Logger LOGGER = LogManager.getLogger("Velo Client");

	@Override
	public void initClient() {
		MinecraftClientEvents.READY.register(minecraft -> {
			VeloNetwork.start();
			if (Boolean.getBoolean("velo.mixinAudit")) {
				org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
				LOGGER.info("Velo mixin audit done");
			}
		});
		net.veloclient.legacy.dev.LegacyTour.install();
		MinecraftClientEvents.STOP.register(minecraft -> VeloNetwork.stop());
		LOGGER.info("Velo Client for 1.8.9 loaded");
	}
}
