package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.chat.Component;
*///?}
import net.veloclient.velo.client.gui.VeloBadge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prepends the Velo badge (see {@link VeloBadge}) to the local player's own
 * in-world nametag - only ever visible to the local client itself (third
 * person, a mirror mod, etc.), same reasoning as everywhere else this badge
 * shows: purely local, nothing synced to the server or other players.
 * {@code EntityRenderer<T>}'s own display-name lookup (Yarn:
 * {@code getDisplayName}, real Mojmap name: {@code getNameTag}, verified
 * via javap) is generic over every entity type it's used for, not just
 * players, so this only touches the return value when the entity in
 * question actually is the local player.
 */
//? if <26.1 {
@Mixin(EntityRenderer.class)
public abstract class PlayerNameTagBadgeMixin {

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void velo$prefixOwnNameTag(Entity entity, CallbackInfoReturnable<Text> cir) {
		if (entity != MinecraftClient.getInstance().player) {
			return;
		}
		Text original = cir.getReturnValue();
		if (original == null) {
			return;
		}
		cir.setReturnValue(VeloBadge.prefixWithBadge(original));
	}
}
//?} else {
/*@Mixin(EntityRenderer.class)
public abstract class PlayerNameTagBadgeMixin {

	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void velo$prefixOwnNameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
		if (entity != Minecraft.getInstance().player) {
			return;
		}
		Component original = cir.getReturnValue();
		if (original == null) {
			return;
		}
		cir.setReturnValue(VeloBadge.prefixWithBadge(original));
	}
}
*///?}
