package net.veloclient.legacy.dev;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screen.menu.TitleScreen;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.gen.WorldGeneratorType;
import net.ornithemc.osl.lifecycle.api.client.MinecraftClientEvents;
import net.veloclient.legacy.VeloLegacy;

/**
 * Dev only ({@code VELO_LEGACY_TOUR=1} or {@code -Dvelo.legacyTour=true}): opens a flat creative world, takes
 * screenshots of your cloak from behind and of the tab list, then closes the game. Screenshots land in the game's
 * {@code screenshots/} as {@code velo-tour-*.png}.
 */
public final class LegacyTour {

	public static volatile boolean forceTab;
	private static int ticks;
	private static boolean started;

	private LegacyTour() {
	}

	public static boolean enabled() {
		return "1".equals(System.getenv("VELO_LEGACY_TOUR")) || Boolean.getBoolean("velo.legacyTour");
	}

	public static void install() {
		if (!enabled()) {
			return;
		}
		MinecraftClientEvents.TICK_END.register(LegacyTour::tick);
	}

	private static void tick(Minecraft mc) {
		if (!started) {
			if (mc.screen instanceof TitleScreen) {
				started = true;
				mc.startGame("velotour", "velotour", new WorldSettings(4242L, WorldSettings.GameMode.CREATIVE, false, false, WorldGeneratorType.FLAT));
			}
			return;
		}
		if (mc.player == null || mc.world == null) {
			return;
		}
		ticks++;
		if (ticks == 60) {
			mc.options.perspective = 1;
			mc.player.yaw = 0;
			mc.player.pitch = 10;
		}
		if (ticks == 140) {
			shot(mc, "cape");
		}
		if (ticks == 150) {
			mc.options.perspective = 0;
			forceTab = true;
		}
		if (ticks == 170) {
			shot(mc, "tab");
		}
		if (ticks == 180) {
			VeloLegacy.LOGGER.info("Velo tour done");
			mc.stop();
		}
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.take(mc.gameDir, "velo-tour-" + name + ".png", mc.width, mc.height, mc.getRenderTarget());
		VeloLegacy.LOGGER.info("Velo tour screenshot {}", name);
	}
}
