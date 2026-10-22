package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
*///?}
import net.veloclient.velo.client.namestyle.NameStyles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Name Styles above heads: a player's name tag gets their name marked (before the Velo badge is put in front). */
//? if <26.1 {
@Mixin(value = EntityRenderer.class, priority = 900)
public abstract class NameStyleTagMixin {

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void velo$nameStyle(Entity entity, CallbackInfoReturnable<Text> cir) {
		if (entity instanceof PlayerEntity player) {
			cir.setReturnValue(NameStyles.tagName(cir.getReturnValue(), player.getGameProfile().name(), NameStyles.NAME_TAG));
		}
	}
}
//?} else {
/*@Mixin(value = EntityRenderer.class, priority = 900)
public abstract class NameStyleTagMixin {

	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void velo$nameStyle(Entity entity, CallbackInfoReturnable<Component> cir) {
		if (entity instanceof Player player) {
			cir.setReturnValue(NameStyles.tagName(cir.getReturnValue(), player.getGameProfile().name(), NameStyles.NAME_TAG));
		}
	}
}
*///?}
