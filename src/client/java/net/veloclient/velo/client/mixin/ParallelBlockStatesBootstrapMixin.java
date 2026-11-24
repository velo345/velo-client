package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Arms {@link net.veloclient.velo.client.modules.performance.ParallelBlockStates} right before {@code FireBlock.bootStrap()}
 * (the call that initialises {@code Blocks}) and computes the collected block state caches right after it - before
 * anything else in the game reads them.
 */
//? if >=26.1 {
/*@Mixin(net.minecraft.server.Bootstrap.class)
public abstract class ParallelBlockStatesBootstrapMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "bootStrap", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/FireBlock;bootStrap()V"), require = 0)
	private static void velo$arm(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		net.veloclient.velo.client.modules.performance.ParallelBlockStates.arm();
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "bootStrap", at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/FireBlock;bootStrap()V", shift = org.spongepowered.asm.mixin.injection.At.Shift.AFTER), require = 0)
	private static void velo$flush(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		net.veloclient.velo.client.modules.performance.ParallelBlockStates.flush();
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class ParallelBlockStatesBootstrapMixin {
	// 26.x only for now
}
//?}
