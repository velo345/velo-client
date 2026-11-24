package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind: the clip's volume tracks (all audio, players, mobs, blocks, ...) scale the game's sounds while a replay plays.
//? if >=1.21.11 && <26.1 {
import org.spongepowered.asm.mixin.Mixin;

// Velo Rewind: the clip's volume tracks (all audio, players, mobs, blocks, ...) scale the game's sounds while a replay plays.
@Mixin(net.minecraft.client.sound.SoundSystem.class)
public abstract class RewindSoundMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "getAdjustedVolume(FLnet/minecraft/sound/SoundCategory;)F",
			at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
	private void velo$rewindVolume(float volume, net.minecraft.sound.SoundCategory source,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
		float gain = net.veloclient.velo.devbuild.rewind.RewindSound.categoryGain(source.ordinal());
		if (gain != 1f) {
			cir.setReturnValue(cir.getReturnValueF() * gain);
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class RewindSoundMixin {
}
*///?} else {
/*@Mixin(net.minecraft.client.sounds.SoundEngine.class)
public abstract class RewindSoundMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F",
			at = @org.spongepowered.asm.mixin.injection.At("RETURN"), cancellable = true)
	private void velo$rewindVolume(float volume, net.minecraft.sounds.SoundSource source,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
		float gain = net.veloclient.velo.devbuild.rewind.RewindSound.categoryGain(source.ordinal());
		if (gain != 1f) {
			cir.setReturnValue(cir.getReturnValueF() * gain);
		}
	}
}
*///?}
