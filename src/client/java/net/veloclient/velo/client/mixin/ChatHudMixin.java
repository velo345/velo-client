package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
*///?}
import net.veloclient.velo.client.gui.VeloBadge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Prepends the Velo badge (see {@link VeloBadge}) to the local player's own
 * chat lines. There's no isolated "draw the name" call to hook here the way
 * {@link PlayerListHudMixin} does for the tab list - the line text is
 * already fully formatted by the time it's ready to render, coming back
 * from the server rather than reconstructed locally - so this rewrites the
 * message's text right as it's added to the chat log instead, via a
 * custom-font glyph that then renders through Minecraft's completely normal
 * text pipeline with no separate render hook needed. Targets the
 * player-message-specific overload (real Mojmap name:
 * {@code addPlayerMessage}, confirmed via javap) rather than a generic
 * "add any text" one, so this can't also fire for system/server messages.
 */
//? if <26.1 {
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {

	@ModifyVariable(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"), argsOnly = true)
	private Text velo$prefixOwnMessage(Text message) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || !VeloBadge.isOwnName(message.getString(), client.player.getGameProfile().name())) {
			return message;
		}
		return VeloBadge.prefixWithBadge(message);
	}
}
//?} else {
/*@Mixin(ChatComponent.class)
public abstract class ChatHudMixin {

	@ModifyVariable(method = "addPlayerMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
			at = @At("HEAD"), argsOnly = true)
	private Component velo$prefixOwnMessage(Component message) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !VeloBadge.isOwnName(message.getString(), client.player.getGameProfile().name())) {
			return message;
		}
		return VeloBadge.prefixWithBadge(message);
	}
}
*///?}
