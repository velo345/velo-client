package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
//?} else {
/*import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
*///?}
import net.veloclient.velo.client.modules.queue.BackgroundQueueManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a backgrounded {@code ClientPlayNetworkHandler} from hijacking the
 * foreground session if the server sends a fresh join while it's
 * backgrounded - this happens when a proxy-based queue swaps you from the
 * limbo/queue backend to the real one (the common case for a queue that
 * pops while the player is away playing something else). Vanilla's own join
 * handling unconditionally overwrites {@code MinecraftClient.world}/{@code
 * .player} with the new join's data with no awareness that a completely
 * unrelated session (singleplayer, or another server) might currently be
 * the one actually on screen - left unguarded, a queue popping in the
 * background would silently rip control away from whatever the player was
 * actually doing.
 *
 * <p>{@link BackgroundQueueManager#isBackground} is checked (not "is this
 * the client's current network handler") specifically to sidestep a
 * chicken-and-egg case: on this player's very first join to a proxy queue,
 * {@code client.player} is still null when this fires, so "is this handler
 * the current one" can't be answered the normal way yet.
 */
//? if <26.1 {
@Mixin(ClientPlayNetworkHandler.class)
public abstract class QueueHandlerJoinGuardMixin {

	@Inject(method = "onGameJoin", at = @At("HEAD"), cancellable = true)
	private void velo$guardBackgroundJoin(GameJoinS2CPacket packet, CallbackInfo ci) {
		if (BackgroundQueueManager.isBackground((ClientPlayNetworkHandler) (Object) this)) {
			BackgroundQueueManager.onBackgroundJoinIntercepted((ClientPlayNetworkHandler) (Object) this, packet);
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(ClientPacketListener.class)
public abstract class QueueHandlerJoinGuardMixin {

	@Inject(method = "handleLogin", at = @At("HEAD"), cancellable = true)
	private void velo$guardBackgroundJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
		if (BackgroundQueueManager.isBackground((ClientPacketListener) (Object) this)) {
			BackgroundQueueManager.onBackgroundJoinIntercepted((ClientPacketListener) (Object) this, packet);
			ci.cancel();
		}
	}
}
*///?}
