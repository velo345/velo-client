package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind backgrounds: no terrain (vanilla's chunk drawing; Sodium's is RewindSodiumChunkMixin).
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindChunkMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.chunk.ChunkSectionsToRender.class)
public abstract class RewindChunkMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "renderGroup", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
	private void velo$rewindNoTerrain(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.devbuild.rewind.RewindApply.hideTerrain()) {
			ci.cancel();
		}
	}
}
*///?}
