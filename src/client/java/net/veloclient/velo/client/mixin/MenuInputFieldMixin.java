package net.veloclient.velo.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.veloclient.velo.client.gui.VeloFonts;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Inside Velo menus, input fields get the Velo look (recessed rounded box, accent ring when
 * focused) instead of vanilla's gray beveled sprite - every Velo screen benefits without each one
 * drawing its own field background. Outside Velo menus vanilla's sprite is drawn untouched.
 *
 * <p>Also: a borderless field (screens that draw their own box around it) puts its text at the
 * very top of the widget in vanilla; inside Velo menus it's shifted to the vertical center.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.widget.TextFieldWidget.class)
public abstract class MenuInputFieldMixin {

	@Redirect(method = "renderWidget", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/util/Identifier;IIII)V"))
	private void velo$menuField(net.minecraft.client.gui.DrawContext context, RenderPipeline pipeline,
			net.minecraft.util.Identifier sprite, int x, int y, int width, int height) {
		var self = (net.minecraft.client.gui.widget.ClickableWidget) (Object) this;
		if (VeloFonts.menuScope()) {
			VeloStyle.drawInputField(context, x, y, width, height, self.isFocused(), self.isHovered());
		} else {
			context.drawGuiTexture(pipeline, sprite, x, y, width, height);
		}
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "renderWidget", at = @At("HEAD"))
	private void velo$centerStart(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		var self = (net.minecraft.client.gui.widget.TextFieldWidget) (Object) this;
		velo$centered = VeloFonts.menuScope() && !self.drawsBackground() && self.getHeight() > 9;
		if (velo$centered) {
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(0f, (self.getHeight() - 8) / 2f);
		}
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "renderWidget", at = @At("RETURN"))
	private void velo$centerEnd(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (velo$centered) {
			context.getMatrices().popMatrix();
			velo$centered = false;
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private boolean velo$centered;
}
//?} else {
/*@Mixin(net.minecraft.client.gui.components.EditBox.class)
public abstract class MenuInputFieldMixin {

	@Redirect(method = "extractWidgetRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void velo$menuField(net.minecraft.client.gui.GuiGraphicsExtractor context, RenderPipeline pipeline,
			net.minecraft.resources.Identifier sprite, int x, int y, int width, int height) {
		var self = (net.minecraft.client.gui.components.AbstractWidget) (Object) this;
		if (VeloFonts.menuScope()) {
			VeloStyle.drawInputField(context, x, y, width, height, self.isFocused(), self.isHovered());
		} else {
			context.blitSprite(pipeline, sprite, x, y, width, height);
		}
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "extractWidgetRenderState", at = @At("HEAD"))
	private void velo$centerStart(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		var self = (net.minecraft.client.gui.components.EditBox) (Object) this;
		velo$centered = VeloFonts.menuScope() && !self.isBordered() && self.getHeight() > 9;
		if (velo$centered) {
			context.pose().pushMatrix();
			context.pose().translate(0f, (self.getHeight() - 8) / 2f);
		}
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "extractWidgetRenderState", at = @At("RETURN"))
	private void velo$centerEnd(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
		if (velo$centered) {
			context.pose().popMatrix();
			velo$centered = false;
		}
	}

	@org.spongepowered.asm.mixin.Unique
	private boolean velo$centered;
}
*///?}
