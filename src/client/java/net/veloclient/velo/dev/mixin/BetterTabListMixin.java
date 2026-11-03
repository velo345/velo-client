package net.veloclient.velo.dev.mixin;

import net.veloclient.velo.dev.tweaks.BetterTabListModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Better Tab List: the ping column's width (vanilla's constant 13), the rows per column (20), whether heads are drawn
 * (the first boolean the method stores) and the ping itself.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.PlayerListHud.class)
public abstract class BetterTabListMixin {

	@ModifyConstant(method = "render", constant = @Constant(intValue = 13), require = 0)
	private int velo$pingColumn(int vanilla) {
		return BetterTabListModule.pingColumn(vanilla);
	}

	@ModifyConstant(method = "render", constant = @Constant(intValue = 20), require = 0)
	private int velo$rows(int vanilla) {
		return BetterTabListModule.rows(vanilla);
	}

	@ModifyVariable(method = "render", at = @At("STORE"), ordinal = 0, require = 0)
	private boolean velo$heads(boolean vanilla) {
		return BetterTabListModule.heads(vanilla);
	}

	@Inject(method = "renderLatencyIcon", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$ping(net.minecraft.client.gui.DrawContext context, int width, int x, int y, net.minecraft.client.network.PlayerListEntry entry,
			CallbackInfo ci) {
		if (BetterTabListModule.drawPing(context, width, x, y, BetterTabListModule.shownLatency(entry.getProfile().id(), entry.getLatency()))) {
			ci.cancel();
		}
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.components.PlayerTabOverlay.class)
public abstract class BetterTabListMixin {

	@ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = 13), require = 0)
	private int velo$pingColumn(int vanilla) {
		return BetterTabListModule.pingColumn(vanilla);
	}

	@ModifyConstant(method = "extractRenderState", constant = @Constant(intValue = 20), require = 0)
	private int velo$rows(int vanilla) {
		return BetterTabListModule.rows(vanilla);
	}

	@ModifyVariable(method = "extractRenderState", at = @At("STORE"), ordinal = 0, require = 0)
	private boolean velo$heads(boolean vanilla) {
		return BetterTabListModule.heads(vanilla);
	}

	@Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$ping(net.minecraft.client.gui.GuiGraphicsExtractor graphics, int slotWidth, int xo, int yo,
			net.minecraft.client.multiplayer.PlayerInfo info, CallbackInfo ci) {
		if (BetterTabListModule.drawPing(graphics, slotWidth, xo, yo, BetterTabListModule.shownLatency(info.getProfile().id(), info.getLatency()))) {
			ci.cancel();
		}
	}
}
*///?}
