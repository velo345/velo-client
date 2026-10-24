package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * 26.3 reads key states from SDL's scancode table by index; a binding saved with a code outside it (GLFW's -1 for
 * "unbound" - Velo's own before 0.9.93, and other mods') crashed the game when joining a world, as vanilla polls
 * every bound key then. Such a key just reads as not pressed.
 */
//? if >=26.3 {
/*@Mixin(com.mojang.blaze3d.platform.InputConstants.class)
public abstract class InputKeyRangeMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "isKeyDown(I)Z", at = @org.spongepowered.asm.mixin.injection.At("HEAD"),
			cancellable = true, require = 0)
	private static void velo$outOfRange(int key, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
		if (key < 0 || key >= org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_COUNT) {
			cir.setReturnValue(false);
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class InputKeyRangeMixin {
	// 26.3 only (SDL key codes).
}
//?}
