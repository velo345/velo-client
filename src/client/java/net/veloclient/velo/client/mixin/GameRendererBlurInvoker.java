package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.2 && <26.1 {
import org.spongepowered.asm.mixin.gen.Invoker;
//?}

/** Vanilla's menu blur, callable for the HUD frost ({@link net.veloclient.velo.client.gui.glass.HudFrost}); 26.x has it public. */
//? if >=1.21.2 && <26.1 {
@Mixin(net.minecraft.client.render.GameRenderer.class)
public interface GameRendererBlurInvoker {

	@Invoker("renderBlur")
	void velo$renderBlur();
}
//?} else {
/*@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class GameRendererBlurInvoker {
}
*///?}
