package net.veloclient.legacy.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GameGui;
import net.veloclient.legacy.feature.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Velo HUD on 1.8.9: drawn at the end of the vanilla HUD. */
@Mixin(GameGui.class)
public abstract class GameGuiHudMixin {

	@Inject(method = "render", at = @At("TAIL"))
	private void velo$hud(float tickDelta, CallbackInfo ci) {
		Modules.renderHud(Minecraft.getInstance());
	}
}
