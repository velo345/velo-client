package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind: outlines for the selected / hidden entities in the editor; a player's glow can be hidden.
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindGlowMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.Minecraft.class)
public abstract class RewindGlowMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "shouldEntityAppearGlowing", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
	private void velo$rewindGlow(net.minecraft.world.entity.Entity entity, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		Boolean glow = net.veloclient.velo.devbuild.rewind.RewindApply.glowing(entity);
		if (glow != null) {
			cir.setReturnValue(glow);
		}
	}
}
*///?}
