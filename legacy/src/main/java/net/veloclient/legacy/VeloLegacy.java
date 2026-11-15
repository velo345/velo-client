package net.veloclient.legacy;

import net.ornithemc.osl.entrypoints.api.client.ClientModInitializer;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;
import net.veloclient.legacy.net.VeloNetwork;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Velo Client on 1.8.9: the R-Shift menu with HUD/PvP/performance modules, Velo cloaks, badges and Velo Network. */
public final class VeloLegacy implements ClientModInitializer {

	public static final Logger LOGGER = LogManager.getLogger("Velo Client");
	private static boolean menuKeyDown;

	@Override
	public void initClient() {
		MinecraftClientEvents.READY.register(minecraft -> {
			net.veloclient.legacy.feature.Modules.register();
			VeloNetwork.start();
			if (Boolean.getBoolean("velo.mixinAudit")) {
				org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
				LOGGER.info("Velo mixin audit done");
			}
		});
		net.veloclient.legacy.dev.LegacyTour.install();
		MinecraftClientEvents.TICK_END.register(minecraft -> {
			net.veloclient.legacy.feature.Modules.tick(minecraft);
			// Right Shift opens the Velo menu (edge-triggered; only in game, no other screen open)
			boolean down = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT);
			if (down && !menuKeyDown && minecraft.screen == null && minecraft.world != null && !net.veloclient.legacy.feature.Modules.all().isEmpty()) {
				minecraft.openScreen(new net.veloclient.legacy.menu.VeloMenuScreen());
			}
			menuKeyDown = down;
		});
		MinecraftClientEvents.STOP.register(minecraft -> {
			VeloNetwork.stop();
			net.veloclient.legacy.feature.Modules.saveNow();
		});
		LOGGER.info("Velo Client for 1.8.9 loaded");
	}
}
