package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.AtmosphericFogModifier;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.world.ClientWorld;
//?} else {
/*import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
*///?}
import net.veloclient.velo.client.modules.rendering.TimeWeatherFogModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides ordinary open-air fog's color and start/end distances while
 * {@link TimeWeatherFogModule}'s Fog Mode is "Custom" - deliberately only
 * this one fog environment (not water/lava/powder-snow, which have their
 * own separate ones) since those represent a genuinely different medium the
 * player is standing in, not "the normal sky fog" this module customizes.
 */
//? if <26.1 {
@Mixin(AtmosphericFogModifier.class)
public abstract class AtmosphericFogMixin {

	@Inject(method = "getFogColor", at = @At("RETURN"), cancellable = true)
	private void velo$overrideColor(ClientWorld world, Camera camera, int viewDistance, float skyDarkness,
			CallbackInfoReturnable<Integer> cir) {
		if (TimeWeatherFogModule.isFogColorOverrideActive()) {
			cir.setReturnValue(TimeWeatherFogModule.fogColorOverrideValue());
		}
	}

	@Inject(method = "applyStartEndModifier", at = @At("TAIL"))
	private void velo$overrideDistance(FogData data, Camera camera, ClientWorld clientWorld, float f,
			RenderTickCounter renderTickCounter, CallbackInfo ci) {
		if (!TimeWeatherFogModule.isFogDistanceOverrideActive()) {
			return;
		}
		double thickness = TimeWeatherFogModule.fogThicknessValue();
		data.environmentalStart = (float) (data.environmentalStart / thickness);
		data.environmentalEnd = (float) (data.environmentalEnd / thickness);
	}
}
//?} else {
/*@Mixin(AtmosphericFogEnvironment.class)
public abstract class AtmosphericFogMixin {

	@Inject(method = "getBaseColor", at = @At("RETURN"), cancellable = true)
	private void velo$overrideColor(ClientLevel level, Camera camera, int viewDistance, float skyDarkness,
			CallbackInfoReturnable<Integer> cir) {
		if (TimeWeatherFogModule.isFogColorOverrideActive()) {
			cir.setReturnValue(TimeWeatherFogModule.fogColorOverrideValue());
		}
	}

	@Inject(method = "setupFog", at = @At("TAIL"))
	private void velo$overrideDistance(FogData data, Camera camera, ClientLevel level, float f,
			DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!TimeWeatherFogModule.isFogDistanceOverrideActive()) {
			return;
		}
		double thickness = TimeWeatherFogModule.fogThicknessValue();
		data.environmentalStart = (float) (data.environmentalStart / thickness);
		data.environmentalEnd = (float) (data.environmentalEnd / thickness);
	}
}
*///?}
