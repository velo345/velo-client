package net.veloclient.legacy.mixin;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.veloclient.legacy.feature.PvpFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Freelook: the camera reads the Freelook direction instead of where the player looks. */
@Mixin(GameRenderer.class)
public abstract class GameRendererFreelookMixin {

	@Redirect(method = "transformCamera", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;yaw:F"), require = 0)
	private float velo$yaw(Entity entity) {
		return PvpFeatures.Freelook.active ? PvpFeatures.Freelook.yaw : entity.yaw;
	}

	@Redirect(method = "transformCamera", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;lastYaw:F"), require = 0)
	private float velo$lastYaw(Entity entity) {
		return PvpFeatures.Freelook.active ? PvpFeatures.Freelook.yaw : entity.lastYaw;
	}

	@Redirect(method = "transformCamera", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;pitch:F"), require = 0)
	private float velo$pitch(Entity entity) {
		return PvpFeatures.Freelook.active ? PvpFeatures.Freelook.pitch : entity.pitch;
	}

	@Redirect(method = "transformCamera", at = @At(value = "FIELD", target = "Lnet/minecraft/entity/Entity;lastPitch:F"), require = 0)
	private float velo$lastPitch(Entity entity) {
		return PvpFeatures.Freelook.active ? PvpFeatures.Freelook.pitch : entity.lastPitch;
	}
}
