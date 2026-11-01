package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
*///?}
import net.veloclient.velo.client.gui.VeloBadge;
import net.veloclient.velo.client.modules.qol.NickHiderModule;
import net.veloclient.velo.client.namestyle.NameStyles;
import net.veloclient.velo.client.network.VeloUserRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Everything Velo does to a player's in-world name tag, in one place and in a fixed order:
 * <ol>
 * <li>Nick Hider scrambles other players' names into obfuscated (§k) text of the same length;</li>
 * <li>otherwise Name Styles mark the player's name;</li>
 * <li>the Velo badge (see {@link VeloBadge}) goes in front - always for you, and for other players online with
 * Velo Client (see {@link VeloUserRegistry#isOnline}).</li>
 * </ol>
 * These used to be three separate {@code @Inject(RETURN, cancellable)} mixins on the same method. Setting a return
 * value cancels, and a cancelled RETURN injection returns right away - so whichever ran first won and the others
 * never ran. Name Styles always set one, so other players never got their badge. One callback can't race itself.
 * {@code EntityRenderer<T>#getDisplayName} (Mojmap: {@code getNameTag}) is used for every entity type, so only
 * players are touched.
 */
//? if <26.1 {
@Mixin(EntityRenderer.class)
public abstract class PlayerNameTagBadgeMixin {

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void velo$playerNameTag(Entity entity, CallbackInfoReturnable<Text> cir) {
		Text original = cir.getReturnValue();
		if (original == null || !(entity instanceof PlayerEntity player)) {
			return;
		}
		boolean self = entity == MinecraftClient.getInstance().player;
		String name = player.getGameProfile().name();
		Text tag;
		if (!self && NickHiderModule.isActive()) {
			tag = Text.literal(original.getString()).setStyle(Style.EMPTY.withFormatting(Formatting.OBFUSCATED));
		} else {
			tag = NameStyles.tagName(original, name, NameStyles.NAME_TAG);
		}
		if (self || VeloUserRegistry.isOnline(player.getUuid(), name)) {
			boolean plus = self ? net.veloclient.velo.client.network.StoreClient.plus() : VeloUserRegistry.isPlus(player.getUuid(), name);
			tag = VeloBadge.prefixWithBadge(tag, plus);
		}
		if (tag != original) {
			cir.setReturnValue(tag);
		}
	}
}
//?} else {
/*@Mixin(EntityRenderer.class)
public abstract class PlayerNameTagBadgeMixin {

	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void velo$playerNameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
		Component original = cir.getReturnValue();
		if (original == null || !(entity instanceof Player player)) {
			return;
		}
		boolean self = entity == Minecraft.getInstance().player;
		String name = player.getGameProfile().name();
		Component tag;
		if (!self && NickHiderModule.isActive()) {
			tag = Component.literal(original.getString()).setStyle(Style.EMPTY.applyFormat(ChatFormatting.OBFUSCATED));
		} else {
			tag = NameStyles.tagName(original, name, NameStyles.NAME_TAG);
		}
		if (self || VeloUserRegistry.isOnline(player.getUUID(), name)) {
			boolean plus = self ? net.veloclient.velo.client.network.StoreClient.plus() : VeloUserRegistry.isPlus(player.getUUID(), name);
			tag = VeloBadge.prefixWithBadge(tag, plus);
		}
		if (tag != original) {
			cir.setReturnValue(tag);
		}
	}
}
*///?}
