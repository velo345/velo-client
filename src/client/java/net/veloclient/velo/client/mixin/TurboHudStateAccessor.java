package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/**
 * Velo Turbo HUD frame cache: lets HudFrameCache swap the extractor's render state for a recording proxy while Velo's
 * HUD modules draw. 26.2+ only (VeloMixinPlugin).
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.gui.GuiGraphicsExtractor.class)
public interface TurboHudStateAccessor {

	@Accessor("guiRenderState")
	net.minecraft.client.renderer.state.gui.GuiRenderState velo$state();

	@Mutable
	@Accessor("guiRenderState")
	void velo$setState(net.minecraft.client.renderer.state.gui.GuiRenderState state);
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public interface TurboHudStateAccessor {
	// HUD frame cache: 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
//?}
