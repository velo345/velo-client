package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Anti Farm Lag's redstone sound volume. {@code play()} takes the starting volume straight from
 * {@code SoundInstance#getVolume} (the instance-based volume method is only used for later
 * updates), so both are scaled - a short sound like a dispenser click only ever plays once.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.sound.SoundSystem.class)
public abstract class AntiFarmLagVolumeMixin {

	@org.spongepowered.asm.mixin.injection.Redirect(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/sound/SoundInstance;getVolume()F"), require = 0)
	private float velo$startVolume(net.minecraft.client.sound.SoundInstance sound) {
		return sound.getVolume() * AntiFarmLagModule.redstoneVolume(String.valueOf(sound.getId()));
	}

	@Inject(method = "getAdjustedVolume(Lnet/minecraft/client/sound/SoundInstance;)F", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$redstoneVolume(net.minecraft.client.sound.SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		float scale = AntiFarmLagModule.redstoneVolume(String.valueOf(sound.getId()));
		if (scale < 1f) {
			cir.setReturnValue(cir.getReturnValueF() * scale);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.sounds.SoundEngine.class)
public abstract class AntiFarmLagVolumeMixin {

	@org.spongepowered.asm.mixin.injection.Redirect(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getVolume()F"), require = 0)
	private float velo$startVolume(net.minecraft.client.resources.sounds.SoundInstance sound) {
		return sound.getVolume() * AntiFarmLagModule.redstoneVolume(String.valueOf(sound.getIdentifier()));
	}

	@Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true, require = 0)
	private void velo$redstoneVolume(net.minecraft.client.resources.sounds.SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		float scale = AntiFarmLagModule.redstoneVolume(String.valueOf(sound.getIdentifier()));
		if (scale < 1f) {
			cir.setReturnValue(cir.getReturnValueF() * scale);
		}
	}
}
*///?}
