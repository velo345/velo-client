package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.text.Style;
//?} else {
/*import net.minecraft.network.chat.Style;
*///?}
import net.veloclient.velo.client.namestyle.NameStyles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Name Styles: every glyph the game draws passes through here (with its style, before its glyph is looked up and
 * coloured); the letters of a styled name get this frame's colour - see {@link NameStyles#restyle}.
 */
//? if <26.1 {
@Mixin(targets = "net.minecraft.client.font.TextRenderer$Drawer")
public abstract class NameStyleGlyphMixin {

	@ModifyVariable(method = "accept(ILnet/minecraft/text/Style;I)Z", at = @At("HEAD"), argsOnly = true)
	private Style velo$nameStyle(Style style) {
		return NameStyles.restyle(style);
	}
}
//?} else {
/*@Mixin(targets = "net.minecraft.client.gui.Font$PreparedTextBuilder")
public abstract class NameStyleGlyphMixin {

	@ModifyVariable(method = "accept(ILnet/minecraft/network/chat/Style;I)Z", at = @At("HEAD"), argsOnly = true)
	private Style velo$nameStyle(Style style) {
		return NameStyles.restyle(style);
	}
}
*///?}
