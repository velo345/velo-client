package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind: the editor outline colours (white = selected, red = hidden).
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindTeamColorMixin {
}
//?} else {
/*@Mixin(net.minecraft.world.entity.Entity.class)
public abstract class RewindTeamColorMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "getTeamColor", at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
	private void velo$rewindOutline(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Integer> cir) {
		int c = net.veloclient.velo.devbuild.rewind.RewindApply.outlineColor((net.minecraft.world.entity.Entity) (Object) this);
		if (c >= 0) {
			cir.setReturnValue(c);
		}
	}
}
*///?}
