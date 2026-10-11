package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Anti Farm Lag: the same block/neutral sound plays at most a few times per tick in farm mode. */
//? if <26.1 {
@Mixin(net.minecraft.client.sound.SoundManager.class)
public abstract class AntiFarmLagSoundMixin {

	@Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$limit(net.minecraft.client.sound.SoundInstance sound,
			CallbackInfoReturnable<net.minecraft.client.sound.SoundSystem.PlayResult> cir) {
		if (AntiFarmLagModule.active() && AntiFarmLagModule.dropSound(String.valueOf(sound.getId()), sound.getCategory().getName())) {
			cir.setReturnValue(net.minecraft.client.sound.SoundSystem.PlayResult.NOT_STARTED);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.sounds.SoundManager.class)
public abstract class AntiFarmLagSoundMixin {

	@Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$limit(net.minecraft.client.resources.sounds.SoundInstance sound,
			CallbackInfoReturnable<net.minecraft.client.sounds.SoundEngine.PlayResult> cir) {
		if (AntiFarmLagModule.active() && AntiFarmLagModule.dropSound(String.valueOf(sound.getIdentifier()), sound.getSource().getName())) {
			cir.setReturnValue(net.minecraft.client.sounds.SoundEngine.PlayResult.NOT_STARTED);
		}
	}
}
*///?}
