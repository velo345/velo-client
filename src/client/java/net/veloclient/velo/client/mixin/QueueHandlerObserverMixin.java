package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListHeaderS2CPacket;
import net.minecraft.network.packet.s2c.play.ProfilelessChatMessageS2CPacket;
//?} else {
/*import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
*///?}
import net.veloclient.velo.client.modules.queue.BackgroundQueueManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Read-only tap (same non-cancelling shape as {@code ClientConnectionMixin})
 * feeding {@link BackgroundQueueManager} the raw chat/tab-list text every
 * handler receives - {@code onBackgroundText} itself is a no-op for a
 * handler that isn't currently one of the tracked background sessions, so
 * this doesn't need its own "is this backgrounded" guard.
 *
 * <p><b>Known coverage gap (this is why the module's chat-mirror setting is
 * marked experimental)</b>: this taps system/announcement-style messages
 * (plain text, no signature) - what most queue plugins actually send - plus
 * tab-list header/footer. It deliberately does *not* tap regular signed
 * player chat ({@code onChatMessage}/{@code ChatboundPlayerChatPacket}):
 * that packet only carries an encrypted/signed reference, and resolving it
 * to plain text means replicating a chunk of vanilla's signature-
 * verification pipeline, which needs more verification than this pass had
 * room for to do safely. Boss bar text isn't tapped either, for the same
 * "more verification needed" reason - {@code BossBarS2CPacket} is a
 * polymorphic add/update-name/update-progress/remove action, not a simple
 * "here's the title" packet.
 */
//? if <26.1 {
@Mixin(ClientPlayNetworkHandler.class)
public abstract class QueueHandlerObserverMixin {

	@Inject(method = "onGameMessage", at = @At("HEAD"))
	private void velo$onGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
		BackgroundQueueManager.onBackgroundText((ClientPlayNetworkHandler) (Object) this, packet.content().getString());
	}

	@Inject(method = "onProfilelessChatMessage", at = @At("HEAD"))
	private void velo$onProfilelessChatMessage(ProfilelessChatMessageS2CPacket packet, CallbackInfo ci) {
		BackgroundQueueManager.onBackgroundText((ClientPlayNetworkHandler) (Object) this, packet.message().getString());
	}

	@Inject(method = "onPlayerListHeader", at = @At("HEAD"))
	private void velo$onPlayerListHeader(PlayerListHeaderS2CPacket packet, CallbackInfo ci) {
		String header = packet.header().getString();
		String footer = packet.footer().getString();
		BackgroundQueueManager.onBackgroundText((ClientPlayNetworkHandler) (Object) this, (header + " " + footer).trim());
	}
}
//?} else {
/*@Mixin(ClientPacketListener.class)
public abstract class QueueHandlerObserverMixin {

	@Inject(method = "handleSystemChat", at = @At("HEAD"))
	private void velo$onGameMessage(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		BackgroundQueueManager.onBackgroundText((ClientPacketListener) (Object) this, packet.content().getString());
	}

	@Inject(method = "handleDisguisedChat", at = @At("HEAD"))
	private void velo$onProfilelessChatMessage(ClientboundDisguisedChatPacket packet, CallbackInfo ci) {
		BackgroundQueueManager.onBackgroundText((ClientPacketListener) (Object) this, packet.message().getString());
	}

	@Inject(method = "handleTabListCustomisation", at = @At("HEAD"))
	private void velo$onPlayerListHeader(ClientboundTabListPacket packet, CallbackInfo ci) {
		String header = packet.header().getString();
		String footer = packet.footer().getString();
		BackgroundQueueManager.onBackgroundText((ClientPacketListener) (Object) this, (header + " " + footer).trim());
	}
}
*///?}
