package net.veloclient.velo.devbuild.mixin;

import net.veloclient.velo.devbuild.ShieldIndicatorModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shield Indicator: the shield-break sound tells which other player's shield an axe just disabled. */
//? if <1.21.2 {
/*@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class ShieldSoundMixin {
}
*///?} else if <26.1 {
@Mixin(net.minecraft.client.sound.SoundManager.class)
public abstract class ShieldSoundMixin {

	@Inject(method = "play(Lnet/minecraft/client/sound/SoundInstance;)Lnet/minecraft/client/sound/SoundSystem$PlayResult;", at = @At("HEAD"))
	private void velo$shieldBreak(net.minecraft.client.sound.SoundInstance sound, CallbackInfoReturnable<net.minecraft.client.sound.SoundSystem.PlayResult> cir) {
		if (ShieldIndicatorModule.active() != null && "item.shield.break".equals(sound.getId().getPath())) {
			ShieldIndicatorModule.onShieldBreakSound(sound.getX(), sound.getY(), sound.getZ());
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.sounds.SoundManager.class)
public abstract class ShieldSoundMixin {

	@Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;", at = @At("HEAD"))
	private void velo$shieldBreak(net.minecraft.client.resources.sounds.SoundInstance sound, CallbackInfoReturnable<net.minecraft.client.sounds.SoundEngine.PlayResult> cir) {
		if (ShieldIndicatorModule.active() != null && "item.shield.break".equals(sound.getIdentifier().getPath())) {
			ShieldIndicatorModule.onShieldBreakSound(sound.getX(), sound.getY(), sound.getZ());
		}
	}
}
*///?}
