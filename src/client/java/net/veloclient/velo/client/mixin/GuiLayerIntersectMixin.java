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
//? if <1.21.2 {
/*// 1.21.1: hooks a render system that came later - left out on 1.21.1.
@Mixin(net.minecraft.client.MinecraftClient.class)
public abstract class GuiLayerIntersectMixin {
}
*///?} else if <26.1 {
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

	@org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private List<?> strata;

	@org.spongepowered.asm.mixin.Shadow public abstract void up();

	@org.spongepowered.asm.mixin.Shadow private boolean hasIntersection(net.minecraft.client.gui.navigation.ScreenRectangle bounds,
			List<? extends net.minecraft.client.renderer.state.gui.ScreenArea> states) {
		throw new AssertionError();
	}

	/^*
	 * Vanilla's walk, with each layer's four lists tested by their cell masks first: a new HUD element used to make four
	 * hooked calls per layer (each working out the element's cells again) for every layer above the one it lands on -
	 * about 10% of the render thread with a busy HUD. Same layer chosen as vanilla: a masked list only answers "no" when
	 * no element in it can intersect, and every "maybe" runs vanilla's exact test.
	 ^/
	@Inject(method = "navigateToAboveHighestElementWithIntersectingBounds", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$maskedWalk(net.minecraft.client.gui.navigation.ScreenRectangle bounds,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (!PerformanceBoostModule.fastDrawPath || !net.veloclient.velo.client.util.GuiNodeWalk.READY || strata.isEmpty()) {
			return;
		}
		int left = bounds.left();
		int top = bounds.top();
		int right = bounds.right();
		int bottom = bounds.bottom();
		Object node = strata.get(strata.size() - 1);
		for (Object up = net.veloclient.velo.client.util.GuiNodeWalk.up(node); up != null; up = net.veloclient.velo.client.util.GuiNodeWalk.up(node)) {
			node = up;
		}
		boolean found = false;
		while (true) {
			found = velo$hits(bounds, net.veloclient.velo.client.util.GuiNodeWalk.elements(node), left, top, right, bottom)
					|| velo$hits(bounds, net.veloclient.velo.client.util.GuiNodeWalk.items(node), left, top, right, bottom)
					|| velo$hits(bounds, net.veloclient.velo.client.util.GuiNodeWalk.texts(node), left, top, right, bottom)
					|| velo$hits(bounds, net.veloclient.velo.client.util.GuiNodeWalk.pictures(node), left, top, right, bottom);
			Object parent = net.veloclient.velo.client.util.GuiNodeWalk.parent(node);
			if (found || parent == null) {
				break;
			}
			node = parent;
		}
		net.veloclient.velo.client.util.GuiNodeWalk.setCurrent(this, node);
		if (found) {
			up();
		}
		ci.cancel();
	}

	@SuppressWarnings("unchecked")
	private boolean velo$hits(net.minecraft.client.gui.navigation.ScreenRectangle bounds, List<?> states, int left, int top, int right, int bottom) {
		if (states == null || states.isEmpty()) {
			return false;
		}
		if (states instanceof GuiCellMaskList<?> masked && !masked.mayIntersect(left, top, right, bottom)) {
			return false;
		}
		return hasIntersection(bounds, (List<? extends net.minecraft.client.renderer.state.gui.ScreenArea>) states);
	}

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
