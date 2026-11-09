package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

// Velo Rewind backgrounds: no terrain when Sodium draws it.
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindSodiumChunkMixin {
}
//?} else {
/*@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class RewindSodiumChunkMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "drawChunkLayer", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true,
			remap = false, require = 0)
	private void velo$rewindNoTerrain(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.devbuild.rewind.RewindApply.hideTerrain()) {
			ci.cancel();
		}
	}
}
*///?}
