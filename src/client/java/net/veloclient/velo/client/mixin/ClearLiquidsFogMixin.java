package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.rendering.ClearLiquidsModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear Water & Lava: pushes the water / lava / powder-snow fog out to the render distance. */
//? if <1.21.2 {
/*// 1.21.1: fog still came from BackgroundRenderer - not supported there.
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class ClearLiquidsFogMixin {
}
*///?} else if <1.21.11 {
/*@Mixin({net.minecraft.client.render.fog.WaterFogModifier.class, net.minecraft.client.render.fog.LavaFogModifier.class,
		net.minecraft.client.render.fog.PowderSnowFogModifier.class})
public abstract class ClearLiquidsFogMixin {

	@Inject(method = "applyStartEndModifier", at = @At("TAIL"), require = 0)
	private void velo$clear(net.minecraft.client.render.fog.FogData data, net.minecraft.entity.Entity entity, net.minecraft.util.math.BlockPos pos,
			net.minecraft.client.world.ClientWorld world, float viewDistance, net.minecraft.client.render.RenderTickCounter tick, CallbackInfo ci) {
		velo$apply(this, data, viewDistance);
	}

	private static void velo$apply(Object self, net.minecraft.client.render.fog.FogData data, float viewDistance) {
		String medium = self instanceof net.minecraft.client.render.fog.WaterFogModifier ? "water"
				: self instanceof net.minecraft.client.render.fog.LavaFogModifier ? "lava" : "snow";
		float end = ClearLiquidsModule.end(medium, data.environmentalEnd, viewDistance);
		if (!Float.isNaN(end)) {
			data.environmentalStart = Math.max(data.environmentalStart, end * 0.6f);
			data.environmentalEnd = end;
		}
	}
}
*///?} else if <26.1 {
@Mixin({net.minecraft.client.render.fog.WaterFogModifier.class, net.minecraft.client.render.fog.LavaFogModifier.class,
		net.minecraft.client.render.fog.PowderSnowFogModifier.class})
public abstract class ClearLiquidsFogMixin {

	@Inject(method = "applyStartEndModifier", at = @At("TAIL"), require = 0)
	private void velo$clear(net.minecraft.client.render.fog.FogData data, net.minecraft.client.render.Camera camera,
			net.minecraft.client.world.ClientWorld world, float viewDistance, net.minecraft.client.render.RenderTickCounter tick, CallbackInfo ci) {
		String medium = (Object) this instanceof net.minecraft.client.render.fog.WaterFogModifier ? "water"
				: (Object) this instanceof net.minecraft.client.render.fog.LavaFogModifier ? "lava" : "snow";
		float end = ClearLiquidsModule.end(medium, data.environmentalEnd, viewDistance);
		if (!Float.isNaN(end)) {
			data.environmentalStart = Math.max(data.environmentalStart, end * 0.6f);
			data.environmentalEnd = end;
		}
	}
}
//?} else {
/*@Mixin({net.minecraft.client.renderer.fog.environment.WaterFogEnvironment.class,
		net.minecraft.client.renderer.fog.environment.LavaFogEnvironment.class,
		net.minecraft.client.renderer.fog.environment.PowderedSnowFogEnvironment.class})
public abstract class ClearLiquidsFogMixin {

	@Inject(method = "setupFog", at = @At("TAIL"), require = 0)
	private void velo$clear(net.minecraft.client.renderer.fog.FogData data, net.minecraft.client.Camera camera,
			net.minecraft.client.multiplayer.ClientLevel level, float viewDistance, net.minecraft.client.DeltaTracker tick, CallbackInfo ci) {
		String medium = (Object) this instanceof net.minecraft.client.renderer.fog.environment.WaterFogEnvironment ? "water"
				: (Object) this instanceof net.minecraft.client.renderer.fog.environment.LavaFogEnvironment ? "lava" : "snow";
		float end = ClearLiquidsModule.end(medium, data.environmentalEnd, viewDistance);
		if (!Float.isNaN(end)) {
			data.environmentalStart = Math.max(data.environmentalStart, end * 0.6f);
			data.environmentalEnd = end;
		}
	}
}
*///?}
