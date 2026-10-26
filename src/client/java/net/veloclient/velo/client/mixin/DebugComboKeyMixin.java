package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * F3 + another key only runs that key's function (hitboxes, chunk borders, pie chart, F3+V version dump...) and never
 * opens the F3 screen. Vanilla toggles the screen when F3 is released unless the combo counted as a debug action -
 * which it doesn't when the action was refused (reduced debug info on servers, no permission) or the key has no
 * debug function at all, so the screen popped open. Any key pressed while F3 is held now marks F3 as a modifier.
 */
//? if >=26.1 {
/*@Mixin(net.minecraft.client.KeyboardHandler.class)
public abstract class DebugComboKeyMixin {

	@org.spongepowered.asm.mixin.Shadow
	private boolean usedDebugKeyAsModifier;

	@org.spongepowered.asm.mixin.injection.Inject(method = "keyPress", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), require = 0)
	private void velo$comboOnly(long handle, int action, net.minecraft.client.input.KeyEvent event,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (action != 1) {
			return;
		}
		net.minecraft.client.Options options = net.minecraft.client.Minecraft.getInstance().options;
		if (options == null || !options.keyDebugModifier.isDown() || options.keyDebugModifier.matches(event)) {
			return;
		}
		if (options.keyDebugModifier.same(options.keyDebugOverlay)) {
			usedDebugKeyAsModifier = true;
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class DebugComboKeyMixin {
	// 26.1+ (debug key mappings); 1.21.11 keeps vanilla's behaviour.
}
//?}
