package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.rendering.ScreenStretchModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if <26.1 {
import org.spongepowered.asm.mixin.injection.ModifyArg;
//?} else {
/*import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Screen Stretch: the 3D projection uses the module's aspect instead of the window's (see {@link ScreenStretchModule}).
 * 1.21.11 builds it in GameRenderer#getBasicProjectionMatrix (world and hand alike); 26.x keeps a Projection object
 * per view (the world's moved out of GameRenderer in 26.3), so its setupPerspective is the one place for all of them.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.GameRenderer.class)
public abstract class StretchProjectionMixin {

	@ModifyArg(method = "getBasicProjectionMatrix", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4f;perspective(FFFF)Lorg/joml/Matrix4f;"), index = 1)
	private float velo$stretch(float aspect) {
		return ScreenStretchModule.aspect(aspect);
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.Projection.class)
public abstract class StretchProjectionMixin {

	@Unique
	private boolean velo$stretching;

	@Inject(method = "setupPerspective", at = @At("HEAD"), cancellable = true)
	private void velo$stretch(float zNear, float zFar, float fov, float width, float height, CallbackInfo ci) {
		if (velo$stretching || height <= 0) {
			return;
		}
		float aspect = ScreenStretchModule.aspect(width / height);
		if (aspect == width / height) {
			return;
		}
		velo$stretching = true;
		try {
			((net.minecraft.client.renderer.Projection) (Object) this).setupPerspective(zNear, zFar, fov, height * aspect, height);
		} finally {
			velo$stretching = false;
		}
		ci.cancel();
	}
}
*///?}
