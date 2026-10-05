package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.hud.SpriteTints;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** The XP level number's color (vanilla's green, 0xFF80FF20) for the XP Bar module. */
//? if <26.1 {
@Mixin(net.minecraft.client.gui.hud.bar.Bar.class)
public interface XpLevelColorMixin {

	@ModifyConstant(method = "drawExperienceLevel", constant = @Constant(intValue = -8323296), require = 0)
	private static int velo$levelColor(int original) {
		int color = SpriteTints.xpLevelColor();
		return color != 0 ? color : original;
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.gui.contextualbar.ContextualBarRenderer.class)
public interface XpLevelColorMixin {

	@ModifyConstant(method = "extractExperienceLevel", constant = @Constant(intValue = -8323296), require = 0)
	private static int velo$levelColor(int original) {
		int color = SpriteTints.xpLevelColor();
		return color != 0 ? color : original;
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.gui.contextualbar.ContextualBar.class)
public interface XpLevelColorMixin {

	@ModifyConstant(method = "extractExperienceLevel", constant = @Constant(intValue = -8323296), require = 0)
	private static int velo$levelColor(int original) {
		int color = SpriteTints.xpLevelColor();
		return color != 0 ? color : original;
	}
}
*///?}
