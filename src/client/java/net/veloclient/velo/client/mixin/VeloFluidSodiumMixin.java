package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=26.2 {
/*import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Shaders: marks fluid vertices for the chunk shader (colour alpha, like the plant codes):
 * 250 = still water, 249 = lava, 239..224 = flowing water in one of 16 directions - so water and
 * lava get their own procedural look, and flowing water streams the way Minecraft says it flows.
 */
@Pseudo
//? if >=26.2 {
/*@Mixin(value = DefaultFluidRenderer.class, remap = false)
public abstract class VeloFluidSodiumMixin {

	@Shadow
	@Final
	private ChunkVertexEncoder.Vertex[] vertices;

	@Unique
	private int velo$code;
	@Unique
	private boolean velo$water;

	@Inject(method = "render", at = @At("HEAD"), require = 0)
	private void velo$fluidType(CallbackInfo ci, @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) FluidState fluidState) {
		velo$water = fluidState.is(FluidTags.WATER);
		velo$code = !VeloShadersModule.active() ? 0 : velo$water ? 5 : fluidState.is(FluidTags.LAVA) ? 6 : 0;
	}

	// The flow Sodium samples for the top face: flowing water gets its direction (16 steps) in the code.
	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/material/FluidState;getFlow(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/Vec3;"),
			require = 0)
	private net.minecraft.world.phys.Vec3 velo$flow(FluidState state, net.minecraft.world.level.BlockGetter level, net.minecraft.core.BlockPos pos,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.world.phys.Vec3> original) {
		net.minecraft.world.phys.Vec3 flow = original.call(state, level, pos);
		if (velo$water && velo$code != 0 && (flow.x != 0.0 || flow.z != 0.0)) {
			int dir = Math.floorMod(Math.round((float) (Math.atan2(flow.z, flow.x) / (Math.PI * 2.0) * 16.0)), 16);
			velo$code = 16 + dir;
		}
		return flow;
	}


	@Inject(method = "writeQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/model/quad/ModelQuadView;getSprite()Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"),
			require = 0)
	private void velo$markFluid(CallbackInfo ci) {
		if (velo$code == 0) {
			return;
		}
		for (ChunkVertexEncoder.Vertex v : vertices) {
			v.color = (v.color & 0x00FFFFFF) | ((255 - velo$code) << 24);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloFluidSodiumMixin {
	// Velo Shaders' fluid marks: 26.2 with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
