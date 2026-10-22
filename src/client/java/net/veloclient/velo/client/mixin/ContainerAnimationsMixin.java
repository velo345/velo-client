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
 * Container Animations: scales/slides the container screen in (and out before it closes), and remembers shift-clicks
 * so the moved stack can fly from its old slot to wherever it landed.
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
	private net.minecraft.screen.slot.Slot velo$from;
	@Unique
	private net.minecraft.item.ItemStack velo$moved;
	@Unique
	private int[] velo$before;
	@Unique
	private long velo$movedAt;

	@Inject(method = "render", at = @At("HEAD"), require = 0)
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
		float[] f = ContainerAnimationsModule.frame(this);
		float cx = x + backgroundWidth / 2f;
		float cy = y + backgroundHeight / 2f;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(cx, cy + f[1]);
		context.getMatrices().scale(f[0], f[0]);
		context.getMatrices().translate(-cx, -cy);
		velo$pushed = true;
	}

	@Inject(method = "render", at = @At("RETURN"), require = 0)
	private void velo$end(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (velo$pushed) {
			context.getMatrices().popMatrix();
			velo$pushed = false;
		}
		if (!ContainerAnimationsModule.itemMovement()) {
			return;
		}
		long now = System.nanoTime();
		if (velo$moved != null) {
			boolean found = false;
			for (int i = 0; i < handler.slots.size() && i < velo$before.length; i++) {
				net.minecraft.screen.slot.Slot s = handler.slots.get(i);
				if (s != velo$from && net.minecraft.item.ItemStack.areItemsAndComponentsEqual(s.getStack(), velo$moved)
						&& s.getStack().getCount() > velo$before[i]) {
					ContainerAnimationsModule.FLIGHTS.add(new ContainerAnimationsModule.Flight(velo$moved.copy(), x + velo$from.x, y + velo$from.y,
							x + s.x, y + s.y, now));
					found = true;
				}
			}
			if (found || now - velo$movedAt > 400_000_000L) {
				velo$moved = null;
			}
		}
		long length = ContainerAnimationsModule.flightMillis() * 1_000_000L;
		ContainerAnimationsModule.FLIGHTS.removeIf(fl -> now - fl.start() > length);
		for (ContainerAnimationsModule.Flight fl : ContainerAnimationsModule.FLIGHTS) {
			float t = (now - fl.start()) / (float) length;
			float e = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
			int px = Math.round(fl.fromX() + (fl.toX() - fl.fromX()) * e);
			int py = Math.round(fl.fromY() + (fl.toY() - fl.fromY()) * e - (float) Math.sin(Math.PI * e) * 10);
			context.drawItemWithoutEntity((net.minecraft.item.ItemStack) fl.stack(), px, py);
		}
	}

	@Inject(method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V", at = @At("HEAD"), require = 0)
	private void velo$click(net.minecraft.screen.slot.Slot slot, int slotId, int button, net.minecraft.screen.slot.SlotActionType action, CallbackInfo ci) {
		if (!ContainerAnimationsModule.itemMovement() || slot == null || action != net.minecraft.screen.slot.SlotActionType.QUICK_MOVE || !slot.hasStack()) {
			return;
		}
		velo$from = slot;
		velo$moved = slot.getStack().copy();
		velo$before = new int[handler.slots.size()];
		for (int i = 0; i < velo$before.length; i++) {
			net.minecraft.item.ItemStack s = handler.slots.get(i).getStack();
			velo$before[i] = net.minecraft.item.ItemStack.areItemsAndComponentsEqual(s, velo$moved) ? s.getCount() : 0;
		}
		velo$movedAt = System.nanoTime();
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
public abstract class ContainerAnimationsMixin {

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
	private net.minecraft.world.inventory.Slot velo$from;
	@Unique
	private net.minecraft.world.item.ItemStack velo$moved;
	@Unique
	private int[] velo$before;
	@Unique
	private long velo$movedAt;

	@Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
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
		float[] f = ContainerAnimationsModule.frame(this);
		float cx = leftPos + imageWidth / 2f;
		float cy = topPos + imageHeight / 2f;
		context.pose().pushMatrix();
		context.pose().translate(cx, cy + f[1]);
		context.pose().scale(f[0], f[0]);
		context.pose().translate(-cx, -cy);
		velo$pushed = true;
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
	private void velo$end(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		if (velo$pushed) {
			context.pose().popMatrix();
			velo$pushed = false;
		}
		if (!ContainerAnimationsModule.itemMovement()) {
			return;
		}
		long now = System.nanoTime();
		if (velo$moved != null) {
			boolean found = false;
			for (int i = 0; i < menu.slots.size() && i < velo$before.length; i++) {
				net.minecraft.world.inventory.Slot s = menu.slots.get(i);
				if (s != velo$from && net.minecraft.world.item.ItemStack.isSameItemSameComponents(s.getItem(), velo$moved)
						&& s.getItem().getCount() > velo$before[i]) {
					ContainerAnimationsModule.FLIGHTS.add(new ContainerAnimationsModule.Flight(velo$moved.copy(), leftPos + velo$from.x, topPos + velo$from.y,
							leftPos + s.x, topPos + s.y, now));
					found = true;
				}
			}
			if (found || now - velo$movedAt > 400_000_000L) {
				velo$moved = null;
			}
		}
		long length = ContainerAnimationsModule.flightMillis() * 1_000_000L;
		ContainerAnimationsModule.FLIGHTS.removeIf(fl -> now - fl.start() > length);
		for (ContainerAnimationsModule.Flight fl : ContainerAnimationsModule.FLIGHTS) {
			float t = (now - fl.start()) / (float) length;
			float e = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
			int px = Math.round(fl.fromX() + (fl.toX() - fl.fromX()) * e);
			int py = Math.round(fl.fromY() + (fl.toY() - fl.fromY()) * e - (float) Math.sin(Math.PI * e) * 10);
			context.item((net.minecraft.world.item.ItemStack) fl.stack(), px, py);
		}
	}

	@Inject(method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V", at = @At("HEAD"), require = 0)
	private void velo$click(net.minecraft.world.inventory.Slot slot, int slotId, int button, net.minecraft.world.inventory.ContainerInput action, CallbackInfo ci) {
		if (!ContainerAnimationsModule.itemMovement() || slot == null || action != net.minecraft.world.inventory.ContainerInput.QUICK_MOVE || !slot.hasItem()) {
			return;
		}
		velo$from = slot;
		velo$moved = slot.getItem().copy();
		velo$before = new int[menu.slots.size()];
		for (int i = 0; i < velo$before.length; i++) {
			net.minecraft.world.item.ItemStack s = menu.slots.get(i).getItem();
			velo$before[i] = net.minecraft.world.item.ItemStack.isSameItemSameComponents(s, velo$moved) ? s.getCount() : 0;
		}
		velo$movedAt = System.nanoTime();
	}

	@Inject(method = "onClose", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$close(CallbackInfo ci) {
		if (!velo$reallyClose && ContainerAnimationsModule.closeAnimation() && ContainerAnimationsModule.beginClose(this)) {
			ci.cancel();
		}
	}
}
*///?}
