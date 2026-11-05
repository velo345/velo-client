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
 * Draws a small Velo Client badge before a player's name in the tab (player
 * list) HUD - the same "which client is this player using" badge
 * Lunar/NoRisk-style clients show. Always shown for the local player; also
 * shown for other players currently online with Velo Client on the
 * configured server, if any (see {@link VeloBadge#isKnownVeloName}).
 * {@link VeloBadge} draws the identical badge in chat ({@link ChatHudMixin})
 * and above players' heads ({@link PlayerNameTagBadgeMixin}).
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
	//? if <1.21.2 {
	/*private int velo$drawNameWithBadge(DrawContext context, TextRenderer textRenderer, Text text, int x, int y, int color) {
		velo$drawName(context, textRenderer, text, x, y, color);
		return x + textRenderer.getWidth(text);
	}

	private void velo$drawName(DrawContext context, TextRenderer textRenderer, Text text, int x, int y, int color) {
	*///?} else {
	private void velo$drawNameWithBadge(DrawContext context, TextRenderer textRenderer, Text text, int x, int y, int color) {
	//?}
		Object[] row = net.veloclient.velo.client.gui.TabNames.rowFor(text);
		int room = net.veloclient.velo.client.gui.TabNames.room(x);
		if (row == null) {
			net.veloclient.velo.client.gui.TabNames.draw(context, textRenderer, text, x, y, color, room);
			return;
		}
		text = (Text) row[0];
		if (!(Boolean) row[1]) {
			net.veloclient.velo.client.gui.TabNames.draw(context, textRenderer, text, x, y, color, room);
			return;
		}
		// Before the name (like Lunar/NoRisk), not after it - the name is shifted right by the badge's width plus a gap
		// (TabNames measured the column with it), vertically centered against the font height.
		boolean plus = (Boolean) row[2];
		int shift = net.veloclient.velo.client.gui.TabNames.badgeShift(plus);
		int badgeY = y + (textRenderer.fontHeight - VeloBadge.SIZE) / 2;
		VeloBadge.draw(context, x, badgeY, plus);
		net.veloclient.velo.client.gui.TabNames.draw(context, textRenderer, text, x + shift, y, color, room - shift);
	}
}
