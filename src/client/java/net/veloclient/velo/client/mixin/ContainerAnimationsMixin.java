package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.rendering.ContainerAnimationsModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Container Animations: scales/slides the container screen in (and out before it closes), and lets items that moved
 * between slots fly there (found by comparing the slots every frame - see {@link ContainerAnimationsModule#track}).
 * The window texture is moved by ContainerBackgroundMixin. Hooks the contents pass (renderMain / extractContents), not
 * the whole render: the player inventory (a recipe book screen) calls that directly and skips the base render, which
 * left its close animation never finishing (Escape had to be pressed twice) and its items never flying.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.screen.ingame.HandledScreen.class)
public abstract class ContainerAnimationsMixin {

	@Shadow
	protected int x;
	@Shadow
	protected int y;
	@Shadow
	protected int backgroundWidth;
	@Shadow
	protected int backgroundHeight;
	@Shadow
	@Final
	protected net.minecraft.screen.ScreenHandler handler;

	@Unique
	private boolean velo$pushed;
	@Unique
	private boolean velo$reallyClose;
	@Unique
	private final net.veloclient.velo.client.modules.rendering.ContainerSlotView velo$slots = new net.veloclient.velo.client.modules.rendering.ContainerSlotView();

	@Inject(method = "renderMain", at = @At("HEAD"), require = 0)
	private void velo$begin(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		velo$pushed = false;
		if (!ContainerAnimationsModule.active()) {
			return;
		}
		if (ContainerAnimationsModule.closeDue(this)) {
			velo$reallyClose = true;
			((net.minecraft.client.gui.screen.Screen) (Object) this).close();
			velo$reallyClose = false;
			return;
		}
		velo$pushed = velo$push(context);
	}

	@Unique
	private boolean velo$push(net.minecraft.client.gui.DrawContext context) {
		float[] f = ContainerAnimationsModule.frame(this);
		if (!ContainerAnimationsModule.animating(f)) {
			return false;
		}
		float cx = x + backgroundWidth / 2f;
		float cy = y + backgroundHeight / 2f;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(cx + f[1], cy + f[2]);
		context.getMatrices().scale(f[0], f[0]);
		context.getMatrices().translate(-cx, -cy);
		return true;
	}

	@Inject(method = "renderBackground", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawBackground(Lnet/minecraft/client/gui/DrawContext;FII)V"), require = 0)
	private void velo$beginWindow(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		velo$pushed = ContainerAnimationsModule.active() && velo$push(context);
	}

	@Inject(method = "renderBackground", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screen/ingame/HandledScreen;drawBackground(Lnet/minecraft/client/gui/DrawContext;FII)V", shift = At.Shift.AFTER), require = 0)
	private void velo$endWindow(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (velo$pushed) {
			context.getMatrices().popMatrix();
			velo$pushed = false;
		}
	}

	@Inject(method = "renderMain", at = @At("RETURN"), require = 0)
	private void velo$end(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (velo$pushed) {
			context.getMatrices().popMatrix();
			velo$pushed = false;
		}
		if (!ContainerAnimationsModule.itemMovement()) {
			return;
		}
		long now = System.nanoTime();
		ContainerAnimationsModule.track(velo$slots.set(handler, x, y,
				(Object) this instanceof net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen), now);
		java.util.Iterator<ContainerAnimationsModule.Flight> it = ContainerAnimationsModule.FLIGHTS.iterator();
		while (it.hasNext()) {
			ContainerAnimationsModule.Flight fl = it.next();
			int[] at = ContainerAnimationsModule.flightPosition(fl, now);
			if (at == null) {
				it.remove();
				continue;
			}
			net.minecraft.item.ItemStack stack = (net.minecraft.item.ItemStack) fl.stack();
			context.drawItemWithoutEntity(stack, at[0], at[1]);
			context.drawStackOverlay(((net.minecraft.client.gui.screen.Screen) (Object) this).getTextRenderer(), stack, at[0], at[1]);
		}
	}

	@Inject(method = "drawSlot", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$hideIncoming(net.minecraft.client.gui.DrawContext context, net.minecraft.screen.slot.Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		if (ContainerAnimationsModule.itemMovement() && ContainerAnimationsModule.hiddenUntilLanded(slot.id)) {
			ci.cancel();
		}
	}

	@Inject(method = "close", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$close(CallbackInfo ci) {
		if (!velo$reallyClose && ContainerAnimationsModule.closeAnimation() && ContainerAnimationsModule.beginClose(this)) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.screens.inventory.AbstractContainerScreen.class)
public abstract class ContainerAnimationsMixin implements ContainerAnimationsModule.Window {

	@Shadow
	protected int leftPos;
	@Shadow
	protected int topPos;
	@Shadow
	@Final
	protected int imageWidth;
	@Shadow
	@Final
	protected int imageHeight;
	@Shadow
	@Final
	protected net.minecraft.world.inventory.AbstractContainerMenu menu;

	@Unique
	private boolean velo$pushed;
	@Unique
	private boolean velo$reallyClose;
	@Unique
	private final net.veloclient.velo.client.modules.rendering.ContainerSlotView velo$slots = new net.veloclient.velo.client.modules.rendering.ContainerSlotView();

	@Inject(method = "extractContents", at = @At("HEAD"), require = 0)
	private void velo$begin(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		velo$pushed = false;
		if (!ContainerAnimationsModule.active()) {
			return;
		}
		if (ContainerAnimationsModule.closeDue(this)) {
			velo$reallyClose = true;
			((net.minecraft.client.gui.screens.Screen) (Object) this).onClose();
			velo$reallyClose = false;
			return;
		}
		velo$pushed = velo$push(context);
	}

	@Override
	public boolean velo$push(Object graphics) {
		net.minecraft.client.gui.GuiGraphicsExtractor context = (net.minecraft.client.gui.GuiGraphicsExtractor) graphics;
		float[] f = ContainerAnimationsModule.frame(this);
		if (!ContainerAnimationsModule.animating(f)) {
			return false;
		}
		float cx = leftPos + imageWidth / 2f;
		float cy = topPos + imageHeight / 2f;
		context.pose().pushMatrix();
		context.pose().translate(cx + f[1], cy + f[2]);
		context.pose().scale(f[0], f[0]);
		context.pose().translate(-cx, -cy);
		return true;
	}

	@Inject(method = "extractContents", at = @At("RETURN"), require = 0)
	private void velo$end(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (velo$pushed) {
			context.pose().popMatrix();
			velo$pushed = false;
		}
		if (!ContainerAnimationsModule.itemMovement()) {
			return;
		}
		long now = System.nanoTime();
		ContainerAnimationsModule.track(velo$slots.set(menu, leftPos, topPos,
				(Object) this instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen), now);
		java.util.Iterator<ContainerAnimationsModule.Flight> it = ContainerAnimationsModule.FLIGHTS.iterator();
		while (it.hasNext()) {
			ContainerAnimationsModule.Flight fl = it.next();
			int[] at = ContainerAnimationsModule.flightPosition(fl, now);
			if (at == null) {
				it.remove();
				continue;
			}
			net.minecraft.world.item.ItemStack stack = (net.minecraft.world.item.ItemStack) fl.stack();
			context.item(stack, at[0], at[1]);
			context.itemDecorations(net.minecraft.client.Minecraft.getInstance().font, stack, at[0], at[1]);
		}
	}

	@Inject(method = "extractSlot", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$hideIncoming(net.minecraft.client.gui.GuiGraphicsExtractor context, net.minecraft.world.inventory.Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
		if (ContainerAnimationsModule.itemMovement() && ContainerAnimationsModule.hiddenUntilLanded(slot.index)) {
			ci.cancel();
		}
	}

	@Inject(method = "onClose", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$close(CallbackInfo ci) {
		if (!velo$reallyClose && ContainerAnimationsModule.closeAnimation() && ContainerAnimationsModule.beginClose(this)) {
			ci.cancel();
		}
	}
}
*///?}
