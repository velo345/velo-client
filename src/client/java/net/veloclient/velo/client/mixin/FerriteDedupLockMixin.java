package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * FerriteCore de-duplicates block state shapes through plain (not thread-safe) hash maps, around each state's cache
 * computation. With {@link net.veloclient.velo.client.modules.performance.ParallelBlockStates} those computations run on
 * several threads, so just these two short map steps take a lock - the expensive shape work between them stays parallel.
 * Only with FerriteCore installed ({@code @Pseudo}).
 */
//? if >=26.1 {
/*@Pseudo
@Mixin(targets = "malte0811.ferritecore.impl.BlockStateCacheImpl", remap = false)
public abstract class FerriteDedupLockMixin {

	@com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "deduplicateCachePre", remap = false, require = 0)
	private static void velo$lockPre(net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase state,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
		synchronized (net.veloclient.velo.client.modules.performance.ParallelBlockStates.FERRITE_LOCK) {
			original.call(state);
		}
	}

	@com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "deduplicateCachePost", remap = false, require = 0)
	private static void velo$lockPost(net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase state,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
		synchronized (net.veloclient.velo.client.modules.performance.ParallelBlockStates.FERRITE_LOCK) {
			original.call(state);
		}
	}
}
*///?} else {
@Pseudo
@Mixin(targets = "malte0811.ferritecore.impl.BlockStateCacheImpl", remap = false)
public abstract class FerriteDedupLockMixin {

	@com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "deduplicateCachePre", remap = false, require = 0)
	private static void velo$lockPre(net.minecraft.block.AbstractBlock.AbstractBlockState state,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
		synchronized (net.veloclient.velo.client.modules.performance.ParallelBlockStates.FERRITE_LOCK) {
			original.call(state);
		}
	}

	@com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "deduplicateCachePost", remap = false, require = 0)
	private static void velo$lockPost(net.minecraft.block.AbstractBlock.AbstractBlockState state,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
		synchronized (net.veloclient.velo.client.modules.performance.ParallelBlockStates.FERRITE_LOCK) {
			original.call(state);
		}
	}
}
//?}
