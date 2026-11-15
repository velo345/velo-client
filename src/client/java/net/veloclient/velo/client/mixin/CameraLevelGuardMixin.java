package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

/**
 * Crash guard (26.2+): GameRenderer.extract draws the level as soon as Minecraft has one, but the camera only gets
 * that level on its next update - in the frame right after a join (seen with server switches, quick play and Dynamic FPS
 * reordering the frame) Camera.getCameraEntityPartialTicks read a null level and the game crashed ("Cannot invoke
 * Level.tickRateManager() because this.level is null", 5 reports on 26.3 between 2026-11-12 and 11-13). Without a
 * level nothing can be frozen, so the normal partial tick is the right answer for that one frame. extractRenderState
 * reads the level once more (the block at the camera), so there the camera first gets Minecraft's level - what its next
 * update would set anyway.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.Camera.class)
public abstract class CameraLevelGuardMixin {

	@Shadow
	private net.minecraft.world.level.Level level;

	@Shadow
	public abstract void setLevel(net.minecraft.client.multiplayer.ClientLevel level);

	@Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
	private void velo$levelBeforeExtract(CallbackInfo ci) {
		if (level == null) {
			net.minecraft.client.multiplayer.ClientLevel current = net.minecraft.client.Minecraft.getInstance().level;
			if (current != null) {
				setLevel(current);
			}
		}
	}

	@Inject(method = "getCameraEntityPartialTicks", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$noLevelYet(net.minecraft.client.DeltaTracker delta, CallbackInfoReturnable<Float> cir) {
		if (level == null) {
			cir.setReturnValue(delta.getGameTimeDeltaPartialTick(true));
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class CameraLevelGuardMixin {
	// 26.2+ only.
}
//?}
