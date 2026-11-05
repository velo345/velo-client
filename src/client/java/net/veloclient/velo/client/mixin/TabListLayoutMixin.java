package net.veloclient.velo.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.veloclient.velo.client.gui.TabNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Tab list layout ({@link TabNames}): vanilla measures each name (first width call in render), measures each score
 * (second), fills each row's background (third fill) and then draws the score between the name and the ping. The name
 * is measured as Velo draws it, the row bounds are noted, and the score is kept clear of the ping when the row is
 * narrower than vanilla planned. Same call order on every version (checked 1.21.1 - 26.3).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.PlayerListHud.class)
public abstract class TabListLayoutMixin {

	@Inject(method = "render", at = @At("HEAD"))
	private void velo$begin(net.minecraft.client.gui.DrawContext context, int width, net.minecraft.scoreboard.Scoreboard scoreboard,
			net.minecraft.scoreboard.ScoreboardObjective objective, CallbackInfo ci) {
		TabNames.begin(objective != null, objective != null && objective.getRenderType() == net.minecraft.scoreboard.ScoreboardCriterion.RenderType.HEARTS);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/font/TextRenderer;getWidth(Lnet/minecraft/text/StringVisitable;)I", ordinal = 0))
	private int velo$nameWidth(net.minecraft.client.font.TextRenderer textRenderer, net.minecraft.text.StringVisitable name, Operation<Integer> original) {
		return TabNames.measure(textRenderer, name);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/font/TextRenderer;getWidth(Lnet/minecraft/text/StringVisitable;)I", ordinal = 1))
	private int velo$scoreWidth(net.minecraft.client.font.TextRenderer textRenderer, net.minecraft.text.StringVisitable score, Operation<Integer> original) {
		return TabNames.scoreWidth(textRenderer, original.call(textRenderer, score));
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V", ordinal = 2))
	private void velo$row(net.minecraft.client.gui.DrawContext context, int x1, int y1, int x2, int y2, int color, Operation<Void> original) {
		TabNames.row(x1, x2);
		original.call(context, x1, y1, x2, y2, color);
	}

	@ModifyArgs(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/hud/PlayerListHud;renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;ILnet/minecraft/client/gui/hud/PlayerListHud$ScoreDisplayEntry;IILjava/util/UUID;Lnet/minecraft/client/gui/DrawContext;)V"))
	private void velo$scoreRange(Args args) {
		int[] range = TabNames.scoreRange(args.get(3), args.get(4));
		args.set(3, range[0]);
		args.set(4, range[1]);
	}
}
//?} else {
/*@Mixin(net.minecraft.client.gui.components.PlayerTabOverlay.class)
public abstract class TabListLayoutMixin {

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void velo$begin(net.minecraft.client.gui.GuiGraphicsExtractor context, int width, net.minecraft.world.scores.Scoreboard scoreboard,
			net.minecraft.world.scores.Objective objective, CallbackInfo ci) {
		TabNames.begin(objective != null, objective != null && objective.getRenderType() == net.minecraft.world.scores.criteria.ObjectiveCriteria.RenderType.HEARTS);
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I", ordinal = 0))
	private int velo$nameWidth(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText name, Operation<Integer> original) {
		return TabNames.measure(font, name);
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I", ordinal = 1))
	private int velo$scoreWidth(net.minecraft.client.gui.Font font, net.minecraft.network.chat.FormattedText score, Operation<Integer> original) {
		return TabNames.scoreWidth(font, original.call(font, score));
	}

	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V", ordinal = 2))
	private void velo$row(net.minecraft.client.gui.GuiGraphicsExtractor context, int x1, int y1, int x2, int y2, int color, Operation<Void> original) {
		TabNames.row(x1, x2);
		original.call(context, x1, y1, x2, y2, color);
	}

	@ModifyArgs(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;extractTablistScore(Lnet/minecraft/world/scores/Objective;ILnet/minecraft/client/gui/components/PlayerTabOverlay$ScoreDisplayEntry;IILjava/util/UUID;Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"))
	private void velo$scoreRange(Args args) {
		int[] range = TabNames.scoreRange(args.get(3), args.get(4));
		args.set(3, range[0]);
		args.set(4, range[1]);
	}
}
*///?}
