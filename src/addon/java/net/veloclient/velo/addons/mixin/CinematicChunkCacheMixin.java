package net.veloclient.velo.addons.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.veloclient.velo.client.modules.qol.CinematicCameraModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bobby-style client-side chunk cache, scoped to just {@link CinematicCameraModule}: while it's
 * active, the packet that tells the client "the server stopped tracking this chunk for you, drop
 * it" is simply ignored, so a chunk the client has already received keeps its already-built mesh
 * and keeps rendering instead of turning to void the moment the (stationary) player's own view-
 * distance radius no longer covers it - since the camera, not the player, is what's actually
 * moving away and needs somewhere to fly through. Unlike the real Bobby mod this isn't a
 * persistent on-disk cache (nothing here survives a disconnect/relog) and a kept chunk is a
 * frozen snapshot, not a live one - if something changes in it while the server thinks nobody's
 * watching, that update never arrives, so distant terrain can go stale the longer a shot runs.
 * That's an accepted, documented tradeoff for a short recording session, not a bug.
 *
 * <p>Yarn's {@code ClientPlayNetworkHandler#onUnloadChunk(UnloadChunkS2CPacket)} and Mojmap's
 * {@code ClientPacketListener#handleForgetLevelChunk(ClientboundForgetLevelChunkPacket)} are the
 * same packet handler under different names (verified via javap) - cancelling it outright is
 * simpler and more complete than trying to intercept every place {@code ClientChunkManager} might
 * otherwise be told to drop the chunk's data.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class CinematicChunkCacheMixin {

	//? if <26.1 {
	@Inject(method = "onUnloadChunk", at = @At("HEAD"), cancellable = true)
	private void velo$keepChunkLoaded(net.minecraft.network.packet.s2c.play.UnloadChunkS2CPacket packet, CallbackInfo ci) {
		if (CinematicCameraModule.isChunkCachingEnabled()) {
			ci.cancel();
		}
	}
	//?} else {
	/*@Inject(method = "handleForgetLevelChunk", at = @At("HEAD"), cancellable = true)
	private void velo$keepChunkLoaded(net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket packet, CallbackInfo ci) {
		if (CinematicCameraModule.isChunkCachingEnabled()) {
			ci.cancel();
		}
	}
	*///?}
}
