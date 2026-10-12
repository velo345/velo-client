package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.redstone.FastGrowModule;
import net.veloclient.velo.client.modules.redstone.TickJournal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Singleplayer redstone tools on the integrated server: Tick Mode's journal and Fast Grow's random-tick scale. */
//? if <26.1 {
@Mixin(net.minecraft.server.world.ServerWorld.class)
public abstract class RedstoneServerLevelMixin {

	@Inject(method = "tick", at = @At("HEAD"))
	private void velo$tickStart(java.util.function.BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
		if (TickJournal.enabled) {
			TickJournal.onLevelTick((net.minecraft.server.world.ServerWorld) (Object) this);
		}
	}

	@ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int velo$fastGrow(int randomTickSpeed) {
		return FastGrowModule.scale(randomTickSpeed);
	}
}
//?} else {
/*@Mixin(net.minecraft.server.level.ServerLevel.class)
public abstract class RedstoneServerLevelMixin {

	@Inject(method = "tick", at = @At("HEAD"))
	private void velo$tickStart(java.util.function.BooleanSupplier haveTime, CallbackInfo ci) {
		if (TickJournal.enabled) {
			TickJournal.onLevelTick((net.minecraft.server.level.ServerLevel) (Object) this);
		}
	}

	@ModifyVariable(method = "tickChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int velo$fastGrow(int randomTickSpeed) {
		return FastGrowModule.scale(randomTickSpeed);
	}
}
*///?}
