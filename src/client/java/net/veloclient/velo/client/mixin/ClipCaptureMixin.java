package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.render.GameRenderer;
//?} else {
/*import net.minecraft.client.renderer.GameRenderer;
*///?}
import net.veloclient.velo.client.clips.ClipRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** End of a rendered frame (world + HUD + screens are in the main target): hands it to the clip recorder. */
@Mixin(GameRenderer.class)
public abstract class ClipCaptureMixin {

	@Inject(method = "render", at = @At("RETURN"))
	private void velo$captureClipFrame(CallbackInfo ci) {
		ClipRecorder.onFrameRendered();
	}
}
