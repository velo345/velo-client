package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
//?} else {
/*import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
*///?}
import net.veloclient.velo.client.clips.AutoClips;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clips Pro: a "VICTORY" / "You won" title from the server saves a clip (see {@link AutoClips}). */
//? if <26.1 {
@Mixin(ClientPlayNetworkHandler.class)
public abstract class AutoClipTitleMixin {

	@Inject(method = "onTitle", at = @At("TAIL"))
	private void velo$winTitle(TitleS2CPacket packet, CallbackInfo ci) {
		AutoClips.onTitle(packet.text() == null ? null : packet.text().getString());
	}

	@Inject(method = "onSubtitle", at = @At("TAIL"))
	private void velo$winSubtitle(SubtitleS2CPacket packet, CallbackInfo ci) {
		AutoClips.onTitle(packet.text() == null ? null : packet.text().getString());
	}
}
//?} else {
/*@Mixin(ClientPacketListener.class)
public abstract class AutoClipTitleMixin {

	@Inject(method = "setTitleText", at = @At("TAIL"))
	private void velo$winTitle(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
		AutoClips.onTitle(packet.text() == null ? null : packet.text().getString());
	}

	@Inject(method = "setSubtitleText", at = @At("TAIL"))
	private void velo$winSubtitle(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
		AutoClips.onTitle(packet.text() == null ? null : packet.text().getString());
	}
}
*///?}
