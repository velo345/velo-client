package net.veloclient.legacy.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.living.player.PlayerEntity;
import net.veloclient.legacy.gui.Badge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The Velo badge in front of a Velo user's name tag (the scoreboard line under it stays as it is). */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

	@Unique
	private int velo$kind;

	@Shadow
	public abstract net.minecraft.client.render.TextRenderer getTextRenderer();

	@WrapMethod(method = "renderNameTag(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V")
	private void velo$pad(Entity entity, String name, double dx, double dy, double dz, int distance, Operation<Void> original) {
		int kind = 0;
		if (entity instanceof PlayerEntity player && name.contains(player.getGameProfile().getName())) {
			kind = Badge.kind(player.getUuid(), player.getGameProfile().getName());
		}
		velo$kind = kind;
		try {
			original.call(entity, kind == 0 ? name : Badge.padding(kind) + name, dx, dy, dz, distance);
		} finally {
			velo$kind = 0;
		}
	}

	@Inject(method = "renderNameTag(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/platform/GlStateManager;enableLighting()V"))
	private void velo$badge(Entity entity, String name, double dx, double dy, double dz, int distance, CallbackInfo ci) {
		if (velo$kind != 0) {
			int y = name.contains("deadmau5") ? -10 : 0;
			Badge.draw(velo$kind, -getTextRenderer().getWidth(name) / 2, y);
		}
	}
}
