package net.veloclient.velo.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.veloclient.velo.client.modules.performance.FullBrightModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Full Bright (design spec section 6.1) makes the local player's client
 * think it always has Night Vision - reusing vanilla's own tested lightmap
 * rendering path for "see clearly regardless of light level" instead of
 * hand-rolling lightmap-shader math. This only affects what {@code
 * LivingEntity#hasStatusEffect} returns on the client's own player instance;
 * it never touches the real status effect list, never sends anything to the
 * server, and the server's own copy of this entity is a separate object on a
 * separate JVM entirely unaffected by this.
 */
@Mixin(LivingEntity.class)
public abstract class FullBrightMixin {

	// Vanilla code that checks hasStatusEffect(NIGHT_VISION) - e.g.
	// GameRenderer#getNightVisionStrength, used every frame to update the
	// lightmap - always follows up with getStatusEffect(NIGHT_VISION) and
	// calls a method on the result unconditionally. Spoofing only
	// hasStatusEffect() left that second call dereferencing a real (null)
	// effect instance and crashed the client; a fake instance with a huge
	// duration has to be spoofed here too so both calls agree.
	private static final StatusEffectInstance FAKE_NIGHT_VISION = new StatusEffectInstance(StatusEffects.NIGHT_VISION, 999999);

	@Inject(method = "hasStatusEffect", at = @At("HEAD"), cancellable = true)
	private void velo$fullBrightHas(RegistryEntry<StatusEffect> effect, CallbackInfoReturnable<Boolean> cir) {
		if (velo$shouldSpoof(effect)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getStatusEffect", at = @At("HEAD"), cancellable = true)
	private void velo$fullBrightGet(RegistryEntry<StatusEffect> effect, CallbackInfoReturnable<StatusEffectInstance> cir) {
		if (velo$shouldSpoof(effect)) {
			cir.setReturnValue(FAKE_NIGHT_VISION);
		}
	}

	private boolean velo$shouldSpoof(RegistryEntry<StatusEffect> effect) {
		if (!FullBrightModule.isActive() || !effect.matches(StatusEffects.NIGHT_VISION)) {
			return false;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		return client.player != null && (Object) this == client.player;
	}
}
