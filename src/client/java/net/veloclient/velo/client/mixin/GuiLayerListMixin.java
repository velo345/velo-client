package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.util.GuiCellMaskList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;

/**
 * Gives every vanilla GUI layer's element lists a screen-cell mask ({@link GuiCellMaskList}) so
 * {@link GuiLayerIntersectMixin} can skip lists that can't overlap a new element.
 */
//? if <26.1 {
@Mixin(targets = "net.minecraft.client.gui.render.state.GuiRenderState$Layer")
public abstract class GuiLayerListMixin {

	@Redirect(method = {"addItem", "addText", "addSpecialElement", "addSimpleElement"},
			at = @At(value = "NEW", target = "java/util/ArrayList"), require = 0)
	private ArrayList<?> velo$maskedList() {
		return new GuiCellMaskList<>();
	}
}
//?} else {
/*@Mixin(targets = "net.minecraft.client.renderer.state.gui.GuiRenderState$Node")
public abstract class GuiLayerListMixin {

	@Redirect(method = {"addItem", "addText", "addPicturesInPictureState", "addGuiElement"},
			at = @At(value = "NEW", target = "java/util/ArrayList"), require = 0)
	private ArrayList<?> velo$maskedList() {
		return new GuiCellMaskList<>();
	}
}
*///?}
