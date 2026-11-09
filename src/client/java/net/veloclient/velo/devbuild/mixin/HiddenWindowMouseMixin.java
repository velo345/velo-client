package net.veloclient.velo.devbuild.mixin;

import org.spongepowered.asm.mixin.Mixin;

// AI runs with a hidden window (-Dvelo.hiddenWindow): the game never captures the mouse - on Windows a hidden window still trapped the cursor.
//? if <26.2 {
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class HiddenWindowMouseMixin {
}
//?} else {
/*@Mixin(net.minecraft.client.MouseHandler.class)
public abstract class HiddenWindowMouseMixin {

	@org.spongepowered.asm.mixin.injection.Inject(method = "grabMouse", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
	private void velo$noGrabWhenHidden(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (net.veloclient.velo.devbuild.rewind.RewindApi.windowHidden()) {
			ci.cancel();
		}
	}
}
*///?}
