package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.11 && <26.1 {
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.injection.At;
import net.veloclient.velo.client.modules.performance.DisplayCulling;
//?} else if >=26.2 {
/*import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.injection.At;
import net.veloclient.velo.client.modules.performance.DisplayCulling;
*///?}

/**
 * Velo Turbo: display entities the server left without a culling size get a generous one, so the ones that can't be
 * seen aren't drawn (see {@link net.veloclient.velo.client.modules.performance.DisplayCulling}). Only ever turns a
 * "draw" into "skip" for displays vanilla wouldn't have tested at all. 26.2+ (VeloMixinPlugin).
 */
//? if >=1.21.11 && <26.1 {
@Mixin(net.minecraft.client.render.entity.EntityRenderer.class)
public abstract class TurboEntityDisplayCullMixin {

	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"), require = 0)
	private boolean velo$cullDisplays(boolean visible, net.minecraft.entity.Entity entity, net.minecraft.client.render.Frustum frustum,
			double cameraX, double cameraY, double cameraZ) {
		if (!visible || !(entity instanceof net.minecraft.entity.decoration.DisplayEntity display) || display.shouldRender()) {
			return visible;
		}
		return DisplayCulling.visible(display, frustum);
	}
}
//?} else if >=26.3 {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class TurboEntityDisplayCullMixin {

	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"), require = 0)
	private boolean velo$cullDisplays(boolean visible, net.minecraft.world.entity.Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
			double cameraX, double cameraY, double cameraZ, float partialTick) {
		if (!visible || !(entity instanceof net.minecraft.world.entity.Display display) || display.affectedByCulling()) {
			return visible;
		}
		return DisplayCulling.visible(display, frustum);
	}
}
*///?} else if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.entity.EntityRenderer.class)
public abstract class TurboEntityDisplayCullMixin {

	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"), require = 0)
	private boolean velo$cullDisplays(boolean visible, net.minecraft.world.entity.Entity entity, net.minecraft.client.renderer.culling.Frustum frustum,
			double cameraX, double cameraY, double cameraZ) {
		if (!visible || !(entity instanceof net.minecraft.world.entity.Display display) || display.affectedByCulling()) {
			return visible;
		}
		return DisplayCulling.visible(display, frustum);
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboEntityDisplayCullMixin {
	// 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
*///?}
