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
 * Prepends the Velo badge (see {@link VeloBadge}) to a player's in-world
 * nametag - always for the local player (own third person, a mirror mod,
 * etc.), and also for other players currently online with Velo Client on
 * the configured server, if any (see {@link
 * net.veloclient.velo.client.network.VeloUserRegistry#isOnline}).
 * {@code EntityRenderer<T>}'s own display-name lookup (Yarn:
 * {@code getDisplayName}, real Mojmap name: {@code getNameTag}, verified
 * via javap) is generic over every entity type it's used for, not just
 * players, so this only touches the return value for an actual player.
 */
//? if <26.1 {
@Mixin(EntityRenderer.class)
public abstract class PlayerNameTagBadgeMixin {

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void velo$prefixOwnNameTag(Entity entity, CallbackInfoReturnable<Text> cir) {
		if (!velo$shouldBadge(entity)) {
			return;
		}
		Text original = cir.getReturnValue();
		if (original == null) {
			return;
		}
		cir.setReturnValue(VeloBadge.prefixWithBadge(original));
	}

	private static boolean velo$shouldBadge(Entity entity) {
		if (entity == MinecraftClient.getInstance().player) {
			return true;
		}
		if (!(entity instanceof net.minecraft.entity.player.PlayerEntity player)) {
			return false;
		}
		return net.veloclient.velo.client.network.VeloUserRegistry.isOnline(player.getUuid());
	}
}
//?} else {
/*@Mixin(EntityRenderer.class)
public abstract class PlayerNameTagBadgeMixin {

	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void velo$prefixOwnNameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
		if (!velo$shouldBadge(entity)) {
			return;
		}
		Component original = cir.getReturnValue();
		if (original == null) {
			return;
		}
		cir.setReturnValue(VeloBadge.prefixWithBadge(original));
	}

	private static boolean velo$shouldBadge(Entity entity) {
		if (entity == Minecraft.getInstance().player) {
			return true;
		}
		if (!(entity instanceof net.minecraft.world.entity.player.Player player)) {
			return false;
		}
		return net.veloclient.velo.client.network.VeloUserRegistry.isOnline(player.getUUID());
	}
}
*///?}
