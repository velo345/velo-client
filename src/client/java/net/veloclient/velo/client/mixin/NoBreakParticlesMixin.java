package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.rendering.NoBreakParticlesModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No Break Particles: the break burst and the mining crumbs (26.3 split the crumbs from the hit sound - only those go). */
//? if <26.1 {
@Mixin(net.minecraft.client.world.ClientWorld.class)
public abstract class NoBreakParticlesMixin {

	@Inject(method = "addBlockBreakParticles", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noBreak(net.minecraft.util.math.BlockPos pos, net.minecraft.block.BlockState state, CallbackInfo ci) {
		if (NoBreakParticlesModule.hideBreak()) {
			ci.cancel();
		}
	}

	@Inject(method = "spawnBlockBreakingParticle", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noMining(net.minecraft.util.math.BlockPos pos, net.minecraft.util.math.Direction side, CallbackInfo ci) {
		if (NoBreakParticlesModule.hideMining()) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.multiplayer.ClientLevel.class)
public abstract class NoBreakParticlesMixin {

	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noBreak(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, CallbackInfo ci) {
		if (NoBreakParticlesModule.hideBreak()) {
			ci.cancel();
		}
	}

	// 26.3: addBreakingParticles (the hit sound moved next to it); 26.1/26.2: addBreakingBlockEffect. Only one exists.
	@Inject(method = "addBreakingParticles", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noMiningSplit(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction side,
			net.minecraft.world.level.block.state.BlockState state, CallbackInfo ci) {
		if (NoBreakParticlesModule.hideMining()) {
			ci.cancel();
		}
	}

	@Inject(method = "addBreakingBlockEffect", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noMining(net.minecraft.core.BlockPos pos, net.minecraft.core.Direction side, CallbackInfo ci) {
		if (NoBreakParticlesModule.hideMining()) {
			ci.cancel();
		}
	}
}
*///?}
