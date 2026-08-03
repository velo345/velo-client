package net.veloclient.velo.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.VeloBadge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draws a small Velo Client badge before the local player's own name in the
 * tab (player list) HUD - the same "which client is this player using" badge
 * Lunar/NoRisk-style clients show, purely a local cosmetic never sent to the
 * server or visible to anyone else. {@link VeloBadge} draws the identical
 * badge in chat ({@link ChatHudMixin}) and above the player's own head
 * ({@link PlayerNameTagBadgeMixin}).
 *
 * <p>{@code PlayerListHud#render} calls {@code DrawContext#drawTextWithShadow
 * (TextRenderer, Text, int, int, int)} three times: the player name (what
 * this redirects, {@code ordinal = 0}), the right-aligned score text, and
 * the heart-count fallback text - all three share the exact same method
 * descriptor, so without the ordinal this would also (wrongly) fire for the
 * score column.
 */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V",
			ordinal = 0))
	private void velo$drawNameWithBadge(DrawContext context, TextRenderer textRenderer, Text text, int x, int y, int color) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || !VeloBadge.isOwnName(text.getString(), client.player.getGameProfile().name())) {
			context.drawTextWithShadow(textRenderer, text, x, y, color);
			return;
		}
		// Before the name (like Lunar/NoRisk), not after it - the name
		// itself is shifted right by the badge's width plus a gap so
		// nothing overlaps, matching how those clients reserve the space.
		int shift = VeloBadge.SIZE + 3;
		VeloBadge.draw(context, x, y - 1);
		context.drawTextWithShadow(textRenderer, text, x + shift, y, color);
	}
}
