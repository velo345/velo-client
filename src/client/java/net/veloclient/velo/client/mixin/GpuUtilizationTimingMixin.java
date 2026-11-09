package net.veloclient.velo.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.client.modules.performance.GpuUtilizationModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GPU Utilization HUD: vanilla only times the GPU while F3's GPU line is showing (otherwise it
 * reports 0%) - it also times it while the HUD module is on.
 */
//? if >=26.1 {
/*@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class GpuUtilizationTimingMixin {

	@WrapOperation(method = {"runTick(Z)V", "renderFrame(Z)V"}, require = 1, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/debug/DebugScreenEntryList;isCurrentlyEnabled(Lnet/minecraft/resources/Identifier;)Z"))
	private boolean velo$timeGpu(net.minecraft.client.gui.components.debug.DebugScreenEntryList list,
			net.minecraft.resources.Identifier id, Operation<Boolean> original) {
		return original.call(list, id)
				|| GpuUtilizationModule.showing && id.equals(net.minecraft.client.gui.components.debug.DebugScreenEntries.GPU_UTILIZATION);
	}
}
*///?} else if >=1.21.2 {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class GpuUtilizationTimingMixin {

	@WrapOperation(method = "render(Z)V", require = 1, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/hud/debug/DebugHudProfile;isEntryVisible(Lnet/minecraft/util/Identifier;)Z"))
	private boolean velo$timeGpu(net.minecraft.client.gui.hud.debug.DebugHudProfile list,
			net.minecraft.util.Identifier id, Operation<Boolean> original) {
		return original.call(list, id)
				|| GpuUtilizationModule.showing && id.equals(net.minecraft.client.gui.hud.debug.DebugHudEntries.GPU_UTILIZATION);
	}

	// Reported 2026-11-08 (1.21.11): "GlTimerQuery is closed" thrown from vanilla's read of the GPU timer - with timing
	// forced on, a query can be closed (resize / device reset) before vanilla reads it. A closed query now just reads as
	// "not ready" / 0 instead of crashing the game.
	@WrapOperation(method = "render(Z)V", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gl/GlTimer$Query;isResultAvailable()Z"))
	private boolean velo$safeAvailable(net.minecraft.client.gl.GlTimer.Query query, Operation<Boolean> original) {
		try {
			return original.call(query);
		} catch (IllegalStateException closed) {
			return false;
		}
	}

	@WrapOperation(method = "render(Z)V", require = 0, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gl/GlTimer$Query;queryResult()J"))
	private long velo$safeResult(net.minecraft.client.gl.GlTimer.Query query, Operation<Long> original) {
		try {
			return original.call(query);
		} catch (IllegalStateException closed) {
			return 0L;
		}
	}
}
//?} else {
/*// 1.21.1: hooks a system that came later - left out on 1.21.1.
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class GpuUtilizationTimingMixin {
}
*///?}
