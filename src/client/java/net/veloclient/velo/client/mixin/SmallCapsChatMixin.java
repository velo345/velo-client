package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
*///?}
import net.veloclient.velo.client.modules.qol.SmallCapsModule;
import net.veloclient.velo.client.util.SmallCapsConverter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Renders small caps in chat when {@link SmallCapsModule}'s Chat toggle is
 * on. Same player-message-specific overload as {@code ChatHudMixin} targets,
 * for the same reason: it's the one method that's already message-text-only
 * rather than a locally-reconstructed line.
 *
 * <p>Runs at a lower {@code priority} than {@code ChatHudMixin} (900 vs. the
 * default 1000 - lower applies first) so this transforms the raw message
 * BEFORE the Velo badge gets prepended, not after. Getting this backwards
 * was a real, confirmed bug: flattening the badge-prefixed text down to one
 * plain literal (this mixin's own {@code message.getString()}) collapses
 * the badge glyph and the message onto the badge's own custom-font style,
 * turning the badge into an unreadable tofu/box - small caps should only
 * ever touch the actual chat text, never Velo's own UI glyphs. Applying
 * first avoids the badge existing yet at all when this runs.
 */
//? if <26.1 {
@Mixin(value = ChatHud.class, priority = 900)
public abstract class SmallCapsChatMixin {

	@ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"), argsOnly = true)
	private Text velo$applySmallCaps(Text message) {
		if (!SmallCapsModule.shouldTransformChat()) {
			return message;
		}
		return Text.literal(SmallCapsConverter.toSmallCaps(message.getString())).setStyle(message.getStyle());
	}
}
//?} else {
/*@Mixin(value = ChatComponent.class, priority = 900)
public abstract class SmallCapsChatMixin {

	@ModifyVariable(method = "addPlayerMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component velo$applySmallCaps(Component message) {
		if (!SmallCapsModule.shouldTransformChat()) {
			return message;
		}
		return Component.literal(SmallCapsConverter.toSmallCaps(message.getString())).setStyle(message.getStyle());
	}
}
*///?}
