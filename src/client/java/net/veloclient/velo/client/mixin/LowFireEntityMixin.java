package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.pvp.LowFireModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Low Fire on other players and mobs: every flame quad on a burning entity goes through one static vertex method;
 * scaling its height (y, measured up from the feet) keeps the fire low so the entity stays visible.
 */
//? if <1.21.2 {
/*@Mixin(net.minecraft.client.render.entity.EntityRenderDispatcher.class)
public abstract class LowFireEntityMixin {

	@ModifyVariable(method = "drawFireVertex", at = @At("HEAD"), ordinal = 1, argsOnly = true, require = 0)
	private static float velo$lowFlames(float y) {
		return y * LowFireModule.entityFireScale();
	}
}
*///?} else if <26.1 {
@Mixin(net.minecraft.client.render.command.FireCommandRenderer.class)
public abstract class LowFireEntityMixin {

	@ModifyVariable(method = "vertex", at = @At("HEAD"), ordinal = 1, argsOnly = true, require = 0)
	private static float velo$lowFlames(float y) {
		return y * LowFireModule.entityFireScale();
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.feature.FlameFeatureRenderer.class)
public abstract class LowFireEntityMixin {

	@ModifyVariable(method = "fireVertex", at = @At("HEAD"), ordinal = 1, argsOnly = true, require = 0)
	private static float velo$lowFlames(float y) {
		return y * LowFireModule.entityFireScale();
	}
}
*///?}
