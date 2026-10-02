package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
*///?}
import net.veloclient.velo.client.modules.queue.BackgroundQueueManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * When a proxy moves a backgrounded connection to another backend (typically: the queue popped),
 * the server starts a reconfiguration. Vanilla answers that by clearing the client's world - which
 * for a background connection would be the world you're playing in right now. For a ghost this
 * only clears the ghost's own handler; the rest of reconfiguration (registries, the new play
 * handler) runs as normal on its own connection.
 */
//? if <26.1 {
@Mixin(ClientPlayNetworkHandler.class)
public abstract class GhostReconfigureGuardMixin {

	@Redirect(method = "onEnterReconfiguration", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/MinecraftClient;enterReconfiguration(Lnet/minecraft/client/gui/screen/Screen;)V"))
	private void velo$keepForegroundWorld(MinecraftClient client, Screen screen) {
		ClientPlayNetworkHandler self = (ClientPlayNetworkHandler) (Object) this;
		if (BackgroundQueueManager.isGhost(self)) {
			self.clearWorld();
			BackgroundQueueManager.onGhostReconfiguring(self);
		} else {
			client.enterReconfiguration(screen);
		}
	}
}
//?} else {
/*@Mixin(ClientPacketListener.class)
public abstract class GhostReconfigureGuardMixin {

	@Redirect(method = "handleConfigurationStart", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/Minecraft;clearClientLevel(Lnet/minecraft/client/gui/screens/Screen;)V"))
	private void velo$keepForegroundWorld(Minecraft client, Screen screen) {
		ClientPacketListener self = (ClientPacketListener) (Object) this;
		if (BackgroundQueueManager.isGhost(self)) {
			self.clearLevel();
			BackgroundQueueManager.onGhostReconfiguring(self);
		} else {
			client.clearClientLevel(screen);
		}
	}
}
*///?}
