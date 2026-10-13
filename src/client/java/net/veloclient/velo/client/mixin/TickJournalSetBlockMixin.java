package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.redstone.TickJournal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Records the block a tick is about to replace, for Tick Mode's rewind (a no-op unless Tick Mode records). */
//? if <26.1 {
@Mixin(net.minecraft.world.World.class)
public abstract class TickJournalSetBlockMixin {

	@Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("HEAD"))
	private void velo$journal(net.minecraft.util.math.BlockPos pos, net.minecraft.block.BlockState state, int flags, int maxUpdateDepth,
			CallbackInfoReturnable<Boolean> cir) {
		if (TickJournal.enabled) {
			TickJournal.onSetBlock((net.minecraft.world.World) (Object) this, pos);
		}
		if (net.veloclient.velo.client.redstone.ClockTracker.enabled && ((net.minecraft.world.World) (Object) this).isClient()) {
			net.veloclient.velo.client.redstone.ClockTracker.observe(this, pos.getX(), pos.getY(), pos.getZ(), state);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.world.level.Level.class)
public abstract class TickJournalSetBlockMixin {

	@Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
	private void velo$journal(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, int flags, int maxUpdateDepth,
			CallbackInfoReturnable<Boolean> cir) {
		if (TickJournal.enabled) {
			TickJournal.onSetBlock((net.minecraft.world.level.Level) (Object) this, pos);
		}
		if (net.veloclient.velo.client.redstone.ClockTracker.enabled && ((net.minecraft.world.level.Level) (Object) this).isClientSide()) {
			net.veloclient.velo.client.redstone.ClockTracker.observe(this, pos.getX(), pos.getY(), pos.getZ(), state);
		}
	}
}
*///?}
