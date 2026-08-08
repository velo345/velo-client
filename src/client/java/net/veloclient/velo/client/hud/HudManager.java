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
		// Replaces (rather than mixin-cancels) vanilla's own crosshair
		// element while a custom one is equipped and enabled - the crosshair
		// used to be a dedicated, directly mixin-able InGameHud method, but
		// the current HUD renderer draws every vanilla element (including
		// the crosshair) through this same registry, so replacing the
		// registered element is both the simpler and the only remaining way
		// to override it.
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (context, tickCounter) -> {
			var module = ModuleRegistry.get("custom-crosshair").orElse(null);
			MinecraftClient client = MinecraftClient.getInstance();
			if (!(module instanceof net.veloclient.velo.client.modules.qol.CustomCrosshairModule) || !module.isEnabled()
					|| !client.options.getPerspective().isFirstPerson()) {
				vanilla.render(context, tickCounter);
				return;
			}
			boolean hit = client.targetedEntity instanceof net.minecraft.entity.LivingEntity && client.targetedEntity.isAlive();
			net.veloclient.velo.client.modules.qol.CustomCrosshairModule.renderIfEquipped(context,
					context.getScaledWindowWidth(), context.getScaledWindowHeight(), hit);
		});
		// Same reasoning as CROSSHAIR above - vanilla's scoreboard sidebar
		// used to be a directly mixin-cancelable InGameHud method, but it's
		// drawn through this same registry now. Suppressed (not replaced)
		// while our own ScoreboardHudModule is enabled, so it doesn't draw
		// twice in two different positions/styles at once.
		HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, vanilla -> (context, tickCounter) -> {
			boolean handled = ModuleRegistry.get("scoreboard-hud").map(Module::isEnabled).orElse(false);
			if (!handled) {
				vanilla.render(context, tickCounter);
			}
		});
	}

	private static void onHudRender(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		if (client.options.hudHidden) {
			return;
		}
		//?} else if <26.2 {
		/*if (client.options.hideGui) {
			return;
		}
		*///?} else {
		/*if (client.gui.hud.isHidden()) {
			return;
		}
		*///?}
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

		// Scoreboard is deliberately excluded from HUD small caps - vanilla's
		// own scoreboard is exempt (there's no mixin over it, only a
		// suppress-and-replace above), so a small-capped ScoreboardHudModule
		// would be the only scoreboard styling that ever looks different,
		// which reads as inconsistent rather than as the intended effect.
		boolean smallCapsEligible = !hud.id().equals("scoreboard-hud");
		if (smallCapsEligible) {
			net.veloclient.velo.client.modules.qol.SmallCapsModule.setHudRenderActive(true);
		}
		try {
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
		} finally {
			if (smallCapsEligible) {
				net.veloclient.velo.client.modules.qol.SmallCapsModule.setHudRenderActive(false);
			}
		}
	}
}
