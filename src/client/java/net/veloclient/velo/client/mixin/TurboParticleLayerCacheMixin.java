package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Turbo - particle layer cache: every particle added to the frame's particle state looked its layer's storage up in a
 * HashMap (hash + equals per particle). Particles of one group come in long runs of the same layer, so the last layer and
 * its storage are kept and the map is only asked when the layer changes; cleared with the state each frame. Off with -Dvelo.particleReuse=false.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.state.level.QuadParticleRenderState.class)
public abstract class TurboParticleLayerCacheMixin {

	@Unique
	private static final boolean ON = !"false".equals(System.getProperty("velo.particleReuse"));
	@Unique
	private Object velo$lastLayer;
	@Unique
	private Object velo$lastStorage;

	@WrapOperation(method = "add", at = @At(value = "INVOKE", target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"),
			require = 0)
	private Object velo$lastLayerStorage(java.util.Map<Object, Object> map, Object layer, java.util.function.Function<Object, Object> create,
			Operation<Object> original) {
		if (ON && layer == velo$lastLayer && velo$lastStorage != null) {
			return velo$lastStorage;
		}
		Object storage = original.call(map, layer, create);
		velo$lastLayer = layer;
		velo$lastStorage = storage;
		return storage;
	}

	@Inject(method = "clear", at = @At("HEAD"), require = 0)
	private void velo$forgetLayer(CallbackInfo ci) {
		velo$lastLayer = null;
		velo$lastStorage = null;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboParticleLayerCacheMixin {
	// 26.2+ only.
}
//?}
