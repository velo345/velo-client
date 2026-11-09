package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaving a world that was opened to LAN / World Hosting froze the game for good (26.2+): the integrated server's
 * stopServer -> teardownPublishedState refreshes the local player's chat abilities on the SERVER thread, which re-wraps
 * every chat line. Wrapping can meet a glyph that was never drawn yet, and baking that glyph uploads to the font texture -
 * "Rendersystem called from wrong thread". The exception kills the server thread before it finishes halting, and the render
 * thread waits on that halt forever (window can't even be closed). Off the render thread the re-wrap is handed to it
 * instead (it runs once the halt is done).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.ChatHud.class)
public abstract class ChatRefreshThreadMixin {

	@Inject(method = "refresh", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$onRenderThread(CallbackInfo ci) {
		net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
		if (!client.isOnThread()) {
			ci.cancel();
			client.execute(() -> ((net.minecraft.client.gui.hud.ChatHud) (Object) this).reset());
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.components.ChatComponent.class)
public abstract class ChatRefreshThreadMixin {

	@Inject(method = "refreshTrimmedMessages", at = @At("HEAD"), cancellable = true)
	private void velo$onRenderThread(CallbackInfo ci) {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (!client.isSameThread()) {
			ci.cancel();
			client.execute(((net.minecraft.client.gui.components.ChatComponent) (Object) this)::rescaleChat);
		}
	}
}
*///?}
