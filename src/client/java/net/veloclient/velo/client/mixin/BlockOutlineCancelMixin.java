package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.render.WorldRenderer;
//?} else if <26.2 {
/*import net.minecraft.client.renderer.LevelRenderer;
*///?} else {
/*import net.minecraft.client.renderer.LevelRenderer;
*///?}
import net.veloclient.velo.client.modules.rendering.BlockOutlineModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels vanilla's own hardcoded-color/width selected-block outline while
 * {@link BlockOutlineModule} is active, so its own customizable-color/width
 * outline (drawn separately via GizmoDrawing) is the only one visible
 * instead of the two overlapping.
 *
 * <p>A real 3-way split, not just &lt;26.1/else: 26.2 rebuilt this specific
 * method as part of a wider "submit once, render later" rendering pipeline
 * (renamed class members like {@code SubmitNodeCollector} confirm it's not
 * just a rename) - the old {@code renderBlockOutline(BufferSource,
 * PoseStack, boolean, LevelRenderState)} from 26.1 doesn't exist there
 * anymore, replaced by {@code submitBlockOutline(PoseStack,
 * SubmitNodeCollector, LevelRenderState)}, verified via javap against the
 * real 26.2 client jar (not assumed from 26.1 parity - that assumption is
 * exactly what broke this the first time, a confirmed
 * "no targets matching" Mixin apply failure at real game launch on 26.2).
 * Cancelling either one has the same effect either way: the outline's
 * geometry never gets submitted for rendering in the first place.
 */
//? if <26.1 {
@Mixin(WorldRenderer.class)
public abstract class BlockOutlineCancelMixin {

	@Inject(method = "renderTargetBlockOutline", at = @At("HEAD"), cancellable = true)
	private void velo$cancelVanillaOutline(CallbackInfo ci) {
		if (BlockOutlineModule.shouldReplaceVanillaOutline()) {
			ci.cancel();
		}
	}
}
//?} else if <26.2 {
/*@Mixin(LevelRenderer.class)
public abstract class BlockOutlineCancelMixin {

	@Inject(method = "renderBlockOutline", at = @At("HEAD"), cancellable = true)
	private void velo$cancelVanillaOutline(CallbackInfo ci) {
		if (BlockOutlineModule.shouldReplaceVanillaOutline()) {
			ci.cancel();
		}
	}
}
*///?} else {
/*@Mixin(LevelRenderer.class)
public abstract class BlockOutlineCancelMixin {

	@Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
	private void velo$cancelVanillaOutline(CallbackInfo ci) {
		if (BlockOutlineModule.shouldReplaceVanillaOutline()) {
			ci.cancel();
		}
	}
}
*///?}
