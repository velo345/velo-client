package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.11 && <26.1 {
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else if >=26.2 {
/*import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo: fonts reloaded - drop every remembered glyph list (see
 * {@link net.veloclient.velo.client.modules.performance.FontPrepareMemo}). 26.2+ (VeloMixinPlugin).
 */
//? if >=1.21.11 && <26.1 {
@Mixin(net.minecraft.client.font.FontManager.class)
public abstract class TurboFontReloadMixin {

	@Inject(method = "reload(Lnet/minecraft/client/font/FontManager$ProviderIndex;Lnet/minecraft/util/profiler/Profiler;)V", at = @At("HEAD"))
	private void velo$dropPreparedText(CallbackInfo ci) {
		net.veloclient.velo.client.modules.performance.FontPrepareMemo.clear();
		net.veloclient.velo.client.modules.performance.GpuHeldMeshes.clear(); // models are baked again in the same reload
		net.veloclient.velo.client.modules.performance.GpuModelMeshes.clear();
	}
}
//?} else if >=26.2 {
/*@Mixin(net.minecraft.client.gui.font.FontManager.class)
public abstract class TurboFontReloadMixin {

	@Inject(method = "apply", at = @At("HEAD"))
	private void velo$dropPreparedText(CallbackInfo ci) {
		net.veloclient.velo.client.modules.performance.FontPrepareMemo.clear();
		net.veloclient.velo.client.modules.performance.GpuHeldMeshes.clear(); // models are baked again in the same reload
		net.veloclient.velo.client.modules.performance.GpuModelMeshes.clear();
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboFontReloadMixin {
	// 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
*///?}
