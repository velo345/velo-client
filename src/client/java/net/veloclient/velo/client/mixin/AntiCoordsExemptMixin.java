package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.privacy.CoordScrambler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anti Coords Leak leaves the scoreboard sidebar and the tab list alone: servers put deaths, kills, playtime, money...
 * there, never your coordinates. While one of them is being drawn, {@link CoordScrambler#exemptDepth} is up, and every
 * line it hands to the GUI is remembered as exempt ({@link AntiCoordsExemptLineMixin}) - the text itself is only
 * prepared later, when the frame's GUI is drawn.
 */
//? if <26.1 {
@Mixin({net.minecraft.client.gui.hud.InGameHud.class, net.minecraft.client.gui.hud.PlayerListHud.class})
public abstract class AntiCoordsExemptMixin {

	@Inject(method = {"renderScoreboardSidebar", "render(Lnet/minecraft/client/gui/DrawContext;ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V"},
			at = @At("HEAD"), require = 0)
	private void velo$exemptStart(CallbackInfo ci) {
		CoordScrambler.exemptDepth++;
	}

	@Inject(method = {"renderScoreboardSidebar", "render(Lnet/minecraft/client/gui/DrawContext;ILnet/minecraft/scoreboard/Scoreboard;Lnet/minecraft/scoreboard/ScoreboardObjective;)V"},
			at = @At("RETURN"), require = 0)
	private void velo$exemptEnd(CallbackInfo ci) {
		CoordScrambler.exemptDepth = Math.max(0, CoordScrambler.exemptDepth - 1);
	}
}
//?} else {
/*@Mixin(targets = {"net.minecraft.client.gui.Gui", "net.minecraft.client.gui.Hud", "net.minecraft.client.gui.components.PlayerTabOverlay"})
public abstract class AntiCoordsExemptMixin {

	@Inject(method = {"displayScoreboardSidebar", "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V"},
			at = @At("HEAD"), require = 0)
	private void velo$exemptStart(CallbackInfo ci) {
		CoordScrambler.exemptDepth++;
	}

	@Inject(method = {"displayScoreboardSidebar", "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/world/scores/Scoreboard;Lnet/minecraft/world/scores/Objective;)V"},
			at = @At("RETURN"), require = 0)
	private void velo$exemptEnd(CallbackInfo ci) {
		CoordScrambler.exemptDepth = Math.max(0, CoordScrambler.exemptDepth - 1);
	}
}
*///?}
