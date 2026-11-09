package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind backgrounds: without the world's sky / clouds / weather the frame shows the clear colour - set to green, blue
// or black for a green screen (terrain goes in RewindChunkMixin / RewindSodiumChunkMixin).
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindBackgroundMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class RewindBackgroundMixin {

	@org.spongepowered.asm.mixin.injection.ModifyVariable(method = "render", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), argsOnly = true)
	private org.joml.Vector4f velo$rewindClear(org.joml.Vector4f fog) {
		int rgb = net.veloclient.velo.devbuild.rewind.RewindApply.backgroundColor();
		if (rgb >= 0) {
			fog.set((rgb >> 16 & 0xFF) / 255f, (rgb >> 8 & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
		}
		return fog;
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = {"addSkyPass", "addCloudsPass", "addWeatherPass"},
			at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
	private void velo$rewindNoSky(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.devbuild.rewind.RewindApply.backgroundColor() >= 0) {
			ci.cancel();
		}
	}
}
*///?}
