package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Dev-only ({@code -Dvelo.benchCosmetics=wings,aura,cloak}, e.g. "phoenix,ember,Velo Neon"; "none" for none): once in a
 * world, holds the camera in third person (F5) and previews those cosmetics on your own player, so a profiling run can
 * compare F5 with and without them.
 */
public final class CosmeticsBench {

	private static int ticks;
	/** "off": bodywear hidden too (it has no module switch). */
	public static volatile boolean hideWear;

	private CosmeticsBench() {
	}

	public static void register() {
		String spec = System.getProperty("velo.benchCosmetics");
		if (spec == null) {
			return;
		}
		String[] parts = spec.split(",");
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null) {
				return;
			}
			if (++ticks == 40 && spec.equals("off")) {
				hideWear = true;
				for (String id : new String[] {"wings-cosmetics", "aura-cosmetics", "cape-cosmetics", "wear-cosmetics", "bodywear", "pets"}) {
					net.veloclient.velo.module.ModuleRegistry.get(id).ifPresent(m -> m.setEnabled(false));
				}
			}
			if (ticks == 40 && !spec.equals("none") && !spec.equals("off")) {
				if (parts.length > 0 && !parts[0].isBlank()) {
					net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(parts[0].trim());
				}
				if (parts.length > 1 && !parts[1].isBlank()) {
					net.veloclient.velo.client.cosmetics.aura.AuraManager.setPreview(parts[1].trim());
				}
				if (parts.length > 2 && !parts[2].isBlank()) {
					net.veloclient.velo.client.cosmetics.CapeManager.loadLibrary();
					net.veloclient.velo.client.cosmetics.CapeManager.library().values().stream()
							.filter(d -> d.name().equalsIgnoreCase(parts[2].trim())).findFirst()
							.ifPresent(net.veloclient.velo.client.cosmetics.CapeManager::setPreviewOverride);
				}
			}
			if (ticks >= 20) {
				//? if <26.1 {
				client.options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
				//?} else {
				/*client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
				*///?}
			}
		});
	}
}
