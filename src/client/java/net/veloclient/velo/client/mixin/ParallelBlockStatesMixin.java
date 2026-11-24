package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Collects each block state's cache computation in {@code Blocks}' initialiser (26.2: in the initialiser itself, 26.3: in
 * a lambda it runs) for {@link net.veloclient.velo.client.modules.performance.ParallelBlockStates}, which computes them on
 * all cores right afterwards. Not armed (anything but the normal start-up path): vanilla's call as before.
 */
//? if >=26.1 {
/*@Mixin(net.minecraft.world.level.block.Blocks.class)
public abstract class ParallelBlockStatesMixin {

	@com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = {"<clinit>", "*"}, at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;initCache()V"), require = 0)
	private static void velo$defer(net.minecraft.world.level.block.state.BlockState state,
			com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
		if (!net.veloclient.velo.client.modules.performance.ParallelBlockStates.defer(state)) {
			original.call(state);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class ParallelBlockStatesMixin {
	// 26.x only for now
}
//?}
