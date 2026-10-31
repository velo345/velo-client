package net.veloclient.velo.dev.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Block Break Progress: how far the block you're mining is (0..1), and which block it is. */
//? if <26.1 {
@Mixin(net.minecraft.client.network.ClientPlayerInteractionManager.class)
public interface BreakProgressAccessor {

	@Accessor("currentBreakingProgress")
	float velo$progress();

	@Accessor("currentBreakingPos")
	net.minecraft.util.math.BlockPos velo$pos();

	@Accessor("breakingBlock")
	boolean velo$breaking();
}
//?} else {
/*@Mixin(net.minecraft.client.multiplayer.MultiPlayerGameMode.class)
public interface BreakProgressAccessor {

	@Accessor("destroyProgress")
	float velo$progress();

	@Accessor("destroyBlockPos")
	net.minecraft.core.BlockPos velo$pos();

	@Accessor("isDestroying")
	boolean velo$breaking();
}
*///?}
