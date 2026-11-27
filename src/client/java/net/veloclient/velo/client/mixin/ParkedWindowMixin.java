package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if <26.3 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
//?}

/**
 * Instant Play (see {@link net.veloclient.velo.client.util.ParkedGame}): a waiting game builds its window hidden from the
 * start, so nothing flashes on screen while it loads in the background. GLFW versions only (1.21.1 - 26.2); 26.3's SDL
 * window isn't parked (the launcher doesn't start it that way).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.util.Window.class)
public abstract class ParkedWindowMixin {

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false), require = 0)
	private long velo$hidden(int width, int height, CharSequence title, long monitor, long share, Operation<Long> original) {
		if (net.veloclient.velo.client.util.ParkedGame.PARKED) {
			org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
		}
		long handle = original.call(width, height, title, monitor, share);
		net.veloclient.velo.client.util.ParkedGame.windowCreated(handle);
		return handle;
	}

	@WrapOperation(method = "updateWindowRegion", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V", remap = false), require = 0)
	private void velo$deferMonitor(long window, long monitor, int x, int y, int width, int height, int refresh, Operation<Void> original) {
		// GLFW on X11 maps the window in glfwSetWindowMonitor: a waiting game keeps it for the reveal.
		if (!net.veloclient.velo.client.util.ParkedGame.deferMonitor(window, monitor, x, y, width, height, refresh)) {
			original.call(window, monitor, x, y, width, height, refresh);
		}
	}
}
//?} else if <26.3 {
/*@Mixin(com.mojang.blaze3d.platform.Window.class)
public abstract class ParkedWindowMixin {

	@WrapOperation(method = "createGlfwWindow", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwCreateWindow(IILjava/lang/CharSequence;JJ)J", remap = false), require = 0)
	private static long velo$hidden(int width, int height, CharSequence title, long monitor, long share, Operation<Long> original) {
		if (net.veloclient.velo.client.util.ParkedGame.PARKED) {
			org.lwjgl.glfw.GLFW.glfwWindowHint(org.lwjgl.glfw.GLFW.GLFW_VISIBLE, org.lwjgl.glfw.GLFW.GLFW_FALSE);
		}
		long handle = original.call(width, height, title, monitor, share);
		net.veloclient.velo.client.util.ParkedGame.windowCreated(handle);
		return handle;
	}

	@WrapOperation(method = "setMode", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V", remap = false), require = 0)
	private void velo$deferMonitor(long window, long monitor, int x, int y, int width, int height, int refresh, Operation<Void> original) {
		// GLFW on X11 maps the window in glfwSetWindowMonitor: a waiting game keeps it for the reveal.
		if (!net.veloclient.velo.client.util.ParkedGame.deferMonitor(window, monitor, x, y, width, height, refresh)) {
			original.call(window, monitor, x, y, width, height, refresh);
		}
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class ParkedWindowMixin {
	// 26.3 draws into an SDL window: not parked (the launcher doesn't start it that way).
}
*///?}
