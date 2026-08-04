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
import net.veloclient.velo.client.modules.qol.NickHiderModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scrambles every other player's in-world nametag into obfuscated (§k)
 * gibberish when {@link NickHiderModule} is active - purely local rendering,
 * same hook point as {@link PlayerNameTagBadgeMixin} (generic across every
 * entity type {@code EntityRenderer<T>} renders, so this only touches the
 * return value when the entity in question is actually another player).
 * Reusing vanilla's own obfuscated-text style rather than hand-rolling
 * random glyphs means the client's normal text renderer already animates it
 * every frame for free, with the same real character count as the original
 * name so it doesn't visually stand out by width.
 */
//? if <26.1 {
@Mixin(EntityRenderer.class)
public abstract class NickHiderMixin {

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void velo$scrambleOtherNameTag(Entity entity, CallbackInfoReturnable<Text> cir) {
		if (!(entity instanceof PlayerEntity) || entity == MinecraftClient.getInstance().player || !NickHiderModule.isActive()) {
			return;
		}
		Text original = cir.getReturnValue();
		if (original == null) {
			return;
		}
		cir.setReturnValue(Text.literal(original.getString()).setStyle(Style.EMPTY.withFormatting(Formatting.OBFUSCATED)));
	}
}
//?} else {
/*@Mixin(EntityRenderer.class)
public abstract class NickHiderMixin {

	@Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
	private void velo$scrambleOtherNameTag(Entity entity, CallbackInfoReturnable<Component> cir) {
		if (!(entity instanceof Player) || entity == Minecraft.getInstance().player || !NickHiderModule.isActive()) {
			return;
		}
		Component original = cir.getReturnValue();
		if (original == null) {
			return;
		}
		cir.setReturnValue(Component.literal(original.getString()).setStyle(Style.EMPTY.applyFormat(ChatFormatting.OBFUSCATED)));
	}
}
*///?}
