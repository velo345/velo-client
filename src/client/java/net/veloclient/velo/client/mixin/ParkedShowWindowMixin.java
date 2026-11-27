package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Instant Play (see {@link net.veloclient.velo.client.util.ParkedGame}): 26.2's game shows its window itself at the end
 * of its constructor; a waiting game keeps it hidden until the launcher says so. Earlier versions never show it on their
 * own (the window is created visible, which {@code ParkedWindowMixin} turns off).
 */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.Minecraft.class)
public abstract class ParkedShowWindowMixin {

	@WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V", remap = false), require = 0)
	private void velo$keepHidden(long window, Operation<Void> original) {
		if (!net.veloclient.velo.client.util.ParkedGame.waiting()) {
			original.call(window);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class ParkedShowWindowMixin {
	// Only 26.2 shows its window from the Minecraft constructor.
}
//?}
