package net.veloclient.velo.client.modules.performance;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Shows which known rendering-optimization mods are detected, so it's
 * visible that Velo Client is deferring to them rather than double-optimizing
 * (design spec section 6.1). Detection only - never disables or reconfigures
 * another mod.
 */
public final class OptimizationModCompatModule extends AbstractModule implements HudModule {

	private static final String[][] KNOWN_MODS = {
			{"sodium", "Sodium"},
			{"lithium", "Lithium"},
			{"iris", "Iris"},
			{"starlight", "Starlight"},
	};

	private final HudPosition position = new HudPosition(0.02f, 0.68f);

	public OptimizationModCompatModule() {
		super("optimization-mod-compat", "Optimization Mod Detector",
				"Shows which rendering-optimization mods (Sodium/Lithium/Iris/Starlight) are detected.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		StringBuilder text = new StringBuilder();
		for (String[] mod : KNOWN_MODS) {
			if (FabricLoader.getInstance().isModLoaded(mod[0])) {
				if (!text.isEmpty()) {
					text.append(", ");
				}
				text.append(mod[1]);
			}
		}
		String line = text.isEmpty() ? "No optimization mods detected" : "Detected: " + text;
		context.drawTextWithShadow(client.textRenderer, line, x, y, 0xFFAAAAAA);
	}

	@Override
	public int width() {
		return 250;
	}

	@Override
	public int height() {
		return MinecraftClient.getInstance().textRenderer.fontHeight;
	}
}
