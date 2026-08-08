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
 * on.
 *
 * <p>On 1.21.11, {@code ChatHud}'s public single-arg {@code addMessage(Text)}
 * (used for every system/server message) delegates internally to the same
 * 3-arg {@code addMessage(Text, MessageSignatureData, MessageIndicator)}
 * this targets, so one injection already covers player chat, server
 * broadcasts, join/leave, command feedback, everything.
 *
 * <p>26.1/26.2 mojmap split that single method into three distinct public
 * entry points - {@code addClientSystemMessage}, {@code
 * addServerSystemMessage} and {@code addPlayerMessage} - confirmed via
 * javap. All three still funnel into one shared <em>private</em> {@code
 * addMessage(Component, MessageSignature, GuiMessageSource, GuiMessageTag)},
 * so that private method is targeted instead of {@code addPlayerMessage}
 * alone - the earlier player-only targeting was a real, confirmed bug: the
 * vast majority of real servers push their chat as
 * {@code addServerSystemMessage} (plugin-reformatted chat, broadcasts,
 * join/leave, command feedback), which never touched
 * {@code addPlayerMessage} at all, so small caps silently never applied to
 * almost anything seen on a real server while still working fine in
 * singleplayer's simpler traffic mix. Unlike {@code ChatHudMixin} (which
 * deliberately stays player-only so the badge doesn't try to attach to
 * system lines), small caps has no such restriction - every chat line
 * should be eligible.
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
		return SmallCapsConverter.toSmallCapsStyled(message);
	}
}
//?} else {
/*@Mixin(value = ChatComponent.class, priority = 900)
public abstract class SmallCapsChatMixin {

	@ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component velo$applySmallCaps(Component message) {
		if (!SmallCapsModule.shouldTransformChat()) {
			return message;
		}
		return SmallCapsConverter.toSmallCapsStyled(message);
	}
}
*///?}
