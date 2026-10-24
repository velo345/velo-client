package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=26.2 {
/*import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Velo Shaders' swaying plants: Sodium writes one material per quad, but a grass blade's base has
 * to stay put while its tip moves - so each vertex carries a sway code in its colour's alpha
 * (255 = rigid, 253 = leaves, 252 = plant tip, 251 = half height of a tall plant, 248 = light source), which the chunk
 * shader decodes and resets to opaque. A 1-4/255 alpha difference is invisible.
 */
@Pseudo
//? if >=26.2 {
/*@Mixin(value = BlockRenderer.class, remap = false)
public abstract class VeloFoliageSodiumMixin {

	@Shadow
	@Final
	private ChunkVertexEncoder.Vertex[] vertices;

	@Unique
	private static final int LEAVES = 2;
	@Unique
	private static final int TIP = 3;
	@Unique
	private static final int HALF = 4;

	@Inject(method = "bufferQuad", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V"),
			require = 0)
	private void velo$swayCodes(CallbackInfo ci) {
		if (!VeloShadersModule.active()) {
			return;
		}
		BlockState state = ((VeloBlockRenderContextAccessor) this).velo$state();
		if (state == null) {
			return;
		}
		Block block = state.getBlock();
		if (state.getLightEmission() > 0) {
			// a light source: the chunk shader lets its bright texels glow (code 7 = alpha 248)
			for (ChunkVertexEncoder.Vertex v : vertices) {
				v.color = (v.color & 0x00FFFFFF) | ((255 - 7) << 24);
			}
			return;
		}
		boolean leaves = block instanceof LeavesBlock || block instanceof VineBlock;
		boolean plant = block instanceof VegetationBlock || block instanceof SeagrassBlock || block instanceof TallSeagrassBlock
				|| block instanceof KelpBlock || block instanceof KelpPlantBlock;
		if (!leaves && !plant) {
			return;
		}
		boolean upperHalf = block instanceof DoublePlantBlock && state.hasProperty(DoublePlantBlock.HALF)
				&& state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER;
		boolean lowerHalf = block instanceof DoublePlantBlock && !upperHalf;
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (ChunkVertexEncoder.Vertex v : vertices) {
			minY = Math.min(minY, v.y);
			maxY = Math.max(maxY, v.y);
		}
		float midY = (minY + maxY) * 0.5f;
		for (ChunkVertexEncoder.Vertex v : vertices) {
			int code;
			if (leaves) {
				code = LEAVES;
			} else {
				boolean top = maxY - minY > 0.05f && v.y > midY; // upper edge of a standing quad
				if (upperHalf) {
					code = top ? TIP : HALF;
				} else if (lowerHalf) {
					code = top ? HALF : 0;
				} else {
					code = top ? TIP : 0;
				}
			}
			if (code != 0) {
				v.color = (v.color & 0x00FFFFFF) | ((255 - code) << 24);
			}
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloFoliageSodiumMixin {
	// Velo Shaders' swaying plants: 26.2 with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
