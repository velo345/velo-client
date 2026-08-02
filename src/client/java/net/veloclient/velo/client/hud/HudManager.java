package net.veloclient.velo.client.hud;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;

/**
 * Draws every enabled {@link HudModule} each frame via Fabric API's layered
 * {@link HudElementRegistry} - the current HUD rendering system in this
 * Minecraft version. Attached directly after the vanilla hotbar layer so it
 * always composites, regardless of accessibility options like subtitles.
 */
public final class HudManager {

	private static final Identifier HUD_LAYER_ID = Identifier.of("velo-client", "hud");

	private HudManager() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, HUD_LAYER_ID, HudManager::onHudRender);
		// Suppress vanilla's own status effect icons (top-right corner)
		// while our Potion Effect Timers module is enabled, so effects
		// aren't shown twice in two different styles at once.
		HudElementRegistry.replaceElement(VanillaHudElements.STATUS_EFFECTS, vanilla -> (context, tickCounter) -> {
			boolean suppressed = ModuleRegistry.get("potion-timers").map(Module::isEnabled).orElse(false);
			if (!suppressed) {
				vanilla.render(context, tickCounter);
			}
		});
	}

	private static void onHudRender(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.options.hudHidden) {
			return;
		}
		// World (and the hotbar, since we're attached right after it) has
		// already drawn to the framebuffer by this point in the frame, but
		// every other vanilla HUD element (health/food/XP/chat/etc) and all
		// our own modules below still haven't - applying the blur pass here
		// blurs the world without smearing anything drawn on top of it.
		net.veloclient.velo.client.modules.performance.PolyBlurModule.applyIfActive();
		float tickDelta = tickCounter.getTickProgress(true);
		int screenWidth = client.getWindow().getScaledWidth();
		int screenHeight = client.getWindow().getScaledHeight();

		for (Module module : ModuleRegistry.all()) {
			if (!(module instanceof HudModule hud) || !hud.isEnabled()) {
				continue;
			}
			renderScaled(context, hud, screenWidth, screenHeight, tickDelta);
		}
	}

	/** Renders one HUD element at its configured position, scaled outward from its own top-left corner. */
	public static void renderScaled(DrawContext context, HudModule hud, int screenWidth, int screenHeight, float tickDelta) {
		float scale = hud.position().scale();
		int scaledWidth = Math.round(hud.width() * scale);
		int scaledHeight = Math.round(hud.height() * scale);
		int x = hud.position().resolveX(screenWidth, scaledWidth);
		int y = hud.position().resolveY(screenHeight, scaledHeight);

		if (scale == 1.0f) {
			hud.render(context, x, y, tickDelta);
			return;
		}
		context.getMatrices().pushMatrix();
		context.getMatrices().translate((float) x, (float) y);
		context.getMatrices().scale(scale, scale);
		context.getMatrices().translate((float) -x, (float) -y);
		hud.render(context, x, y, tickDelta);
		context.getMatrices().popMatrix();
	}
}
