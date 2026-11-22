package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.veloclient.velo.client.modules.performance.SodiumTreePool;
*///?}

/**
 * Velo Turbo: a Sodium cull tree built inside a cull pass takes its 32 KB array from {@link
 * net.veloclient.velo.client.modules.performance.SodiumTreePool} instead of allocating a new one (the constructor's
 * {@code new long[4096]} becomes {@code new long[0]}, the pooled array goes in at the end of the constructor). Outside
 * a cull pass nothing changes. Only with Sodium on 26.2+ (VeloMixinPlugin).
 */
//? if >=26.2 {
/*@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.tree.Tree", remap = false)
public abstract class TurboSodiumTreePoolMixin {

	@Shadow
	@Final
	@Mutable
	protected long[] tree;

	@ModifyConstant(method = "<init>", constant = @Constant(intValue = 4096), require = 0)
	private int velo$skipArray(int size) {
		return size == SodiumTreePool.SIZE && SodiumTreePool.pooling() ? 0 : size;
	}

	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void velo$pooledArray(int offsetX, int offsetY, int offsetZ, CallbackInfo ci) {
		if (this.tree.length == 0) {
			this.tree = SodiumTreePool.take(getClass().getName().endsWith("$FlatTree"));
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumTreePoolMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
