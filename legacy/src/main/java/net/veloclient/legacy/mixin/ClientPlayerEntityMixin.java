package net.veloclient.legacy.mixin;

import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.resource.Identifier;
import net.veloclient.legacy.cape.Capes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A Velo cloak replaces the vanilla cape texture (the vanilla cape model and physics draw it). */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {

	@Inject(method = "getCapeTextureLocation", at = @At("HEAD"), cancellable = true)
	private void velo$cape(CallbackInfoReturnable<Identifier> cir) {
		Identifier velo = Capes.textureFor((ClientPlayerEntity) (Object) this);
		if (velo != null) {
			cir.setReturnValue(velo);
		}
	}
}
