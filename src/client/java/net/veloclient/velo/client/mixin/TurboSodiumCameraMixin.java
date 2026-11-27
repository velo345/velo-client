package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.client.modules.performance.SodiumCameraReuse;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Velo Turbo "Terrain Culling Window" (see {@link net.veloclient.velo.client.modules.performance.SodiumCameraReuse}):
 * Sodium 0.9's setupTerrain runs with a slightly larger frustum, and its camera-changed notification (which throws the
 * render lists and the visibility search away) is skipped while the camera stays inside the tolerance. Sodium on 26.2+
 * only (VeloMixinPlugin); require = 0 everywhere: another Sodium build just doesn't get the optimisation.
 */
//? if >=26.2 {
/*@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class TurboSodiumCameraMixin {

	@WrapMethod(method = "setupTerrain", require = 0)
	private void velo$terrainWindow(net.minecraft.client.Camera camera, net.caffeinemc.mods.sodium.client.render.viewport.Viewport viewport,
			net.caffeinemc.mods.sodium.client.util.FogParameters fog, boolean useOcclusionCulling, boolean updateChunksImmediately,
			org.joml.Matrix4f cullMatrix, Operation<Void> original) {
		if (!SodiumCameraReuse.active() || updateChunksImmediately) {
			original.call(camera, viewport, fog, useOcclusionCulling, updateChunksImmediately, cullMatrix);
			return;
		}
		var use = SodiumCameraReuse.prepare(camera, viewport, fog.cullDistance(), cullMatrix);
		try {
			original.call(camera, use, fog, useOcclusionCulling, updateChunksImmediately, cullMatrix);
		} finally {
			SodiumCameraReuse.done();
		}
	}

	@WrapOperation(method = "setupTerrain", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;notifyChangedCamera()V"), require = 0)
	private void velo$keepLists(net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager manager, Operation<Void> original) {
		if (!SodiumCameraReuse.suppressed()) {
			original.call(manager);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumCameraMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
