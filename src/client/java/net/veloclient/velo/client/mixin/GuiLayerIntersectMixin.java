package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import net.veloclient.velo.client.util.GuiCellMaskList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Short-circuits vanilla's per-list "does anything here intersect the new element" scan when the
 * list's cell mask proves nothing can (see {@link GuiCellMaskList} for why that's exact).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.render.state.GuiRenderState.class)
public abstract class GuiLayerIntersectMixin {

	@Inject(method = "anyIntersect", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$skipDisjointList(net.minecraft.client.gui.ScreenRect bounds, List<?> states,
			CallbackInfoReturnable<Boolean> cir) {
		if (PerformanceBoostModule.fastDrawPath && states instanceof GuiCellMaskList<?> masked
				&& !masked.mayIntersect(bounds.getLeft(), bounds.getTop(), bounds.getRight(), bounds.getBottom())) {
			cir.setReturnValue(false);
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.state.gui.GuiRenderState.class)
public abstract class GuiLayerIntersectMixin {

	@Inject(method = "hasIntersection", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$skipDisjointList(net.minecraft.client.gui.navigation.ScreenRectangle bounds, List<?> states,
			CallbackInfoReturnable<Boolean> cir) {
		if (PerformanceBoostModule.fastDrawPath && states instanceof GuiCellMaskList<?> masked
				&& !masked.mayIntersect(bounds.left(), bounds.top(), bounds.right(), bounds.bottom())) {
			cir.setReturnValue(false);
		}
	}
}
*///?}
