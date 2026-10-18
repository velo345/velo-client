package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.PhantomModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Phantom's No Clip: Player#tick sets "no physics" to "is spectator" every tick; right after that,
 * it's switched on while you fly in creative - on the client (movement) and on the integrated
 * server (which then accepts moves through blocks, like a spectator's).
 */
//? if <26.1 {
@Mixin(net.minecraft.entity.player.PlayerEntity.class)
public abstract class PhantomNoClipMixin {

	@Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/player/PlayerEntity;noClip:Z",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER, ordinal = 0), require = 0)
	private void velo$noClip(CallbackInfo ci) {
		var self = (net.minecraft.entity.player.PlayerEntity) (Object) this;
		if (PhantomModule.noClip(self.getUuid(), self.getAbilities().flying, self.isCreative())) {
			self.noClip = true;
		}
	}

	/**
	 * Flying through blocks used to crouch or crawl the player (vanilla's pose logic sees a body that doesn't fit and shrinks it
	 * unless you're a spectator), which lowered the camera and slowed you down. While Phantom flies you through, you stay standing.
	 */
	@Inject(method = "updatePose", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$standing(CallbackInfo ci) {
		var self = (net.minecraft.entity.player.PlayerEntity) (Object) this;
		if (PhantomModule.noClip(self.getUuid(), self.getAbilities().flying, self.isCreative())) {
			self.setPose(net.minecraft.entity.EntityPose.STANDING);
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.world.entity.player.Player.class)
public abstract class PhantomNoClipMixin {

	@Inject(method = "tick", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Player;noPhysics:Z",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD, shift = At.Shift.AFTER, ordinal = 0), require = 0)
	private void velo$noClip(CallbackInfo ci) {
		var self = (net.minecraft.world.entity.player.Player) (Object) this;
		if (PhantomModule.noClip(self.getUUID(), self.getAbilities().flying, self.isCreative())) {
			self.noPhysics = true;
		}
	}

	// See the Yarn branch: no crouching/crawling while Phantom flies you through blocks.
	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$standing(CallbackInfo ci) {
		var self = (net.minecraft.world.entity.player.Player) (Object) this;
		if (PhantomModule.noClip(self.getUUID(), self.getAbilities().flying, self.isCreative())) {
			self.setPose(net.minecraft.world.entity.Pose.STANDING);
			ci.cancel();
		}
	}
}
*///?}
