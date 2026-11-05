package net.veloclient.legacy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.overlay.PlayerTabOverlay;
import net.minecraft.client.network.PlayerInfo;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.veloclient.legacy.gui.Badge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashMap;
import java.util.Map;

/**
 * The Velo badge in the tab list: Velo users' names get room in front ({@code getDisplayName}, which vanilla also
 * sizes the columns with, so nothing overlaps the ping) and the badge is drawn there.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

	/** This frame's padded names -> badge kind. */
	@Unique
	private final Map<String, Integer> velo$badged = new HashMap<>();

	@Inject(method = "render", at = @At("HEAD"))
	private void velo$begin(int width, Scoreboard scoreboard, ScoreboardObjective objective, CallbackInfo ci) {
		velo$badged.clear();
	}

	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void velo$pad(PlayerInfo player, CallbackInfoReturnable<String> cir) {
		int kind = Badge.kind(player.getProfile().getId(), player.getProfile().getName());
		if (kind != 0) {
			String padded = Badge.padding(kind) + cir.getReturnValue();
			velo$badged.put(padded, kind);
			cir.setReturnValue(padded);
		}
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/TextRenderer;drawWithShadow(Ljava/lang/String;FFI)I"))
	private int velo$badge(TextRenderer textRenderer, String text, float x, float y, int color, Operation<Integer> original) {
		int result = original.call(textRenderer, text, x, y, color);
		String key = text.startsWith("§o") ? text.substring(2) : text;
		Integer kind = velo$badged.get(key);
		if (kind != null) {
			Badge.draw(kind, (int) x, (int) y);
		}
		return result;
	}
}
