package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.hud.HeartsModule;
import net.veloclient.velo.client.modules.hud.HungerModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the Hearts and Hunger modules draw the health and food rows instead of vanilla (same
 * arguments, same layout - only the look changes). Each module returns false when it's off, and
 * vanilla draws as normal. require = 0: if a future Minecraft renames these, the modules simply stop
 * applying instead of the game failing to start. The HUD class is InGameHud on 1.21.11, Gui on 26.1
 * and Hud on 26.2.
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.InGameHud.class)
public abstract class StatusBarsMixin {

	@Shadow private int ticks;

	@Inject(method = "renderHealthBar", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$hearts(net.minecraft.client.gui.DrawContext context, net.minecraft.entity.player.PlayerEntity player, int x, int y,
			int lines, int regeneratingHeartIndex, float maxHealth, int lastHealth, int health, int absorption, boolean blinking, CallbackInfo ci) {
		if (HeartsModule.render(context, player, x, y, lines, regeneratingHeartIndex, maxHealth, lastHealth, health, absorption, blinking, ticks)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderArmor", at = @At("HEAD"), cancellable = true, require = 0)
	private static void velo$armor(net.minecraft.client.gui.DrawContext context, net.minecraft.entity.player.PlayerEntity player, int y,
			int rows, int rowHeight, int x, CallbackInfo ci) {
		if (net.veloclient.velo.client.modules.hud.ArmorBarModule.render(context, player, y, rows, rowHeight, x)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderFood", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$food(net.minecraft.client.gui.DrawContext context, net.minecraft.entity.player.PlayerEntity player, int top, int right,
			CallbackInfo ci) {
		if (HungerModule.render(context, player, top, right, ticks)) {
			ci.cancel();
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class StatusBarsMixin {

	@Shadow private int tickCount;

	@Inject(method = "extractHearts", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$hearts(net.minecraft.client.gui.GuiGraphicsExtractor context, net.minecraft.world.entity.player.Player player, int x, int y,
			int lines, int regeneratingHeartIndex, float maxHealth, int lastHealth, int health, int absorption, boolean blinking, CallbackInfo ci) {
		if (HeartsModule.render(context, player, x, y, lines, regeneratingHeartIndex, maxHealth, lastHealth, health, absorption, blinking, tickCount)) {
			ci.cancel();
		}
	}

	@Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true, require = 0)
	private static void velo$armor(net.minecraft.client.gui.GuiGraphicsExtractor context, net.minecraft.world.entity.player.Player player, int y,
			int rows, int rowHeight, int x, CallbackInfo ci) {
		if (net.veloclient.velo.client.modules.hud.ArmorBarModule.render(context, player, y, rows, rowHeight, x)) {
			ci.cancel();
		}
	}

	@Inject(method = "extractFood", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$food(net.minecraft.client.gui.GuiGraphicsExtractor context, net.minecraft.world.entity.player.Player player, int top, int right,
			CallbackInfo ci) {
		if (HungerModule.render(context, player, top, right, tickCount)) {
			ci.cancel();
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.gui.Hud.class)
public abstract class StatusBarsMixin {

	@Shadow private int tickCount;

	@Inject(method = "extractHearts", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$hearts(net.minecraft.client.gui.GuiGraphicsExtractor context, net.minecraft.world.entity.player.Player player, int x, int y,
			int lines, int regeneratingHeartIndex, float maxHealth, int lastHealth, int health, int absorption, boolean blinking, CallbackInfo ci) {
		if (HeartsModule.render(context, player, x, y, lines, regeneratingHeartIndex, maxHealth, lastHealth, health, absorption, blinking, tickCount)) {
			ci.cancel();
		}
	}

	@Inject(method = "extractArmor", at = @At("HEAD"), cancellable = true, require = 0)
	private static void velo$armor(net.minecraft.client.gui.GuiGraphicsExtractor context, net.minecraft.world.entity.player.Player player, int y,
			int rows, int rowHeight, int x, CallbackInfo ci) {
		if (net.veloclient.velo.client.modules.hud.ArmorBarModule.render(context, player, y, rows, rowHeight, x)) {
			ci.cancel();
		}
	}

	@Inject(method = "extractFood", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$food(net.minecraft.client.gui.GuiGraphicsExtractor context, net.minecraft.world.entity.player.Player player, int top, int right,
			CallbackInfo ci) {
		if (HungerModule.render(context, player, top, right, tickCount)) {
			ci.cancel();
		}
	}
}
*///?}
