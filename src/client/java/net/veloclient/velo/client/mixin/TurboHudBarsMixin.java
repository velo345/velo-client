package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.veloclient.velo.client.hud.HudFrameCache;
import org.spongepowered.asm.mixin.Unique;
*///?}

/**
 * Velo Turbo (26.2+, with the HUD canvas): vanilla's hotbar, hearts, food, armour and XP bars are the biggest part of the
 * HUD's extraction cost. Their state only changes on client ticks (plus the selected slot and the item pop animation), so
 * between ticks the last extraction's elements are handed to the recorder again instead of extracting every frame.
 * Everything else (crosshair, chat, Velo's modules...) is extracted as before.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.gui.Hud.class)
public abstract class TurboHudBarsMixin {

	@Unique
	private final HudFrameCache.Segment velo$bars = new HudFrameCache.Segment();

	@WrapMethod(method = "extractHotbarAndDecorations")
	private void velo$barsMemo(net.minecraft.client.gui.GuiGraphicsExtractor graphics, net.minecraft.client.DeltaTracker delta,
			Operation<Void> original) {
		if (!HudFrameCache.canvasRecording()) {
			original.call(graphics, delta);
			return;
		}
		long key = velo$key(graphics);
		if (key != Long.MIN_VALUE && HudFrameCache.segmentReplay(velo$bars, key)) {
			return;
		}
		HudFrameCache.segmentBegin(velo$bars);
		boolean completed = false;
		try {
			original.call(graphics, delta);
			completed = true;
		} finally {
			HudFrameCache.segmentEnd(velo$bars, key, completed);
		}
	}

	@Unique
	private static long velo$key(net.minecraft.client.gui.GuiGraphicsExtractor graphics) {
		var client = net.minecraft.client.Minecraft.getInstance();
		var player = client.player;
		if (player == null) {
			return Long.MIN_VALUE;
		}
		var inventory = player.getInventory();
		for (int i = 0; i < 9; i++) {
			if (inventory.getItem(i).getPopTime() > 0) {
				return Long.MIN_VALUE;   // the pop animation moves with every frame
			}
		}
		long h = player.tickCount;
		h = h * 31 + inventory.getSelectedSlot();
		h = h * 31 + graphics.guiWidth();
		h = h * 31 + graphics.guiHeight();
		h = h * 31 + net.veloclient.velo.client.util.ClientCompat.guiScale();
		return h;
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboHudBarsMixin {
	// HUD canvas segments: 26.2+ only (VeloMixinPlugin skips TurboHud* before that).
}
//?}
