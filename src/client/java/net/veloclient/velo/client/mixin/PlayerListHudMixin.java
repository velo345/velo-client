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
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			context.drawTextWithShadow(textRenderer, text, x, y, color);
			return;
		}
		// The same row name comes back every frame (TabListCache keeps it): decide its Name Style and badge once.
		Object[] row = (Object[]) net.veloclient.velo.client.util.TabListCache.row(text);
		if (row == null) {
			// Name Styles, also when another mod kept NameStyleTabMixin from tagging the row.
			Text tagged = net.veloclient.velo.client.namestyle.NameStyles.tagTabRow(text);
			String plain = tagged.getString();
			String own = client.player.getGameProfile().name();
			boolean known = VeloBadge.isKnownVeloName(plain, own);
			row = new Object[] {tagged, known, known && VeloBadge.isPlus(plain, own)};
			net.veloclient.velo.client.util.TabListCache.storeRow(text, row);
		}
		text = (Text) row[0];
		if (!(Boolean) row[1]) {
			context.drawTextWithShadow(textRenderer, text, x, y, color);
			return;
		}
		// Before the name (like Lunar/NoRisk), not after it - the name
		// itself is shifted right by the badge's width plus a gap so
		// nothing overlaps, matching how those clients reserve the space.
		// Vertically centered against the actual row/font height rather
		// than a fixed offset, so it stays centered regardless of VeloBadge.SIZE.
		boolean plus = (Boolean) row[2];
		int shift = VeloBadge.width(plus) + 3;
		int badgeY = y + (textRenderer.fontHeight - VeloBadge.SIZE) / 2;
		VeloBadge.draw(context, x, badgeY, plus);
		context.drawTextWithShadow(textRenderer, text, x + shift, y, color);
	}
}
