package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.qol.ZoomModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Zoom: mouse look scaled to the zoom (narrower view, smaller turn per pixel) - aiming feels the same at every zoom
 * and the camera stops the moment the mouse stops, unlike vanilla's cinematic camera which keeps gliding.
 */
//? if <26.1 {
@Mixin(net.minecraft.entity.Entity.class)
public abstract class ZoomSensitivityMixin {

	@ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
	private double velo$zoomX(double dx) {
		return (Object) this == net.minecraft.client.MinecraftClient.getInstance().player ? ZoomModule.look(0, dx) : dx;
	}

	@ModifyVariable(method = "changeLookDirection", at = @At("HEAD"), ordinal = 1, argsOnly = true, require = 0)
	private double velo$zoomY(double dy) {
		return (Object) this == net.minecraft.client.MinecraftClient.getInstance().player ? ZoomModule.look(1, dy) : dy;
	}
}
//?} else {
/*@Mixin(net.minecraft.world.entity.Entity.class)
public abstract class ZoomSensitivityMixin {

	@ModifyVariable(method = "turn", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
	private double velo$zoomX(double dx) {
		return (Object) this == net.minecraft.client.Minecraft.getInstance().player ? ZoomModule.look(0, dx) : dx;
	}

	@ModifyVariable(method = "turn", at = @At("HEAD"), ordinal = 1, argsOnly = true, require = 0)
	private double velo$zoomY(double dy) {
		return (Object) this == net.minecraft.client.Minecraft.getInstance().player ? ZoomModule.look(1, dy) : dy;
	}
}
*///?}
