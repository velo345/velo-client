package net.veloclient.velo.client.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draws a small Velo Client badge next to the local player's own entry in
 * the tab (player list) HUD - the same "which client is this player using"
 * badge Lunar/Feather-style clients show, purely a local cosmetic never sent
 * to the server or visible to anyone else.
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

	private static final Identifier BADGE_TEXTURE = Identifier.of("velo-client", "textures/icon/badge.png");

	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V",
			ordinal = 0))
	private void velo$drawNameWithBadge(DrawContext context, TextRenderer textRenderer, Text text, int x, int y, int color) {
		context.drawTextWithShadow(textRenderer, text, x, y, color);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		String ownName = client.player.getGameProfile().name();
		if (!text.getString().contains(ownName)) {
			return;
		}
		// After the name, not before it - x-11 sat right on top of the skin
		// head icon that's already drawn just to the left of the name.
		int badgeX = x + textRenderer.getWidth(text) + 3;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, BADGE_TEXTURE, badgeX, y - 1, 0f, 0f, 10, 10, 10, 10);
	}
}
