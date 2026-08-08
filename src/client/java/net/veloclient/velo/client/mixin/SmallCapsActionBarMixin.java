package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.hud.InGameHud;
//?} else if <26.2 {
/*import net.minecraft.client.gui.Gui;
*///?} else {
/*import net.minecraft.client.gui.Hud;
*///?}
import net.minecraft.text.Text;
import net.veloclient.velo.client.modules.qol.SmallCapsModule;
import net.veloclient.velo.client.util.SmallCapsConverter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Renders small caps in the action bar when {@link SmallCapsModule}'s Action
 * Bar toggle is on - a separate {@code @ModifyVariable} on the same {@code
 * setOverlayMessage} method {@code ActionBarOverlayMixin} already {@code
 * @Inject}s (read-only there, so both apply cleanly with no conflict).
 */
//? if <26.1 {
@Mixin(InGameHud.class)
//?} else if <26.2 {
/*@Mixin(Gui.class)
*///?} else {
/*@Mixin(Hud.class)
*///?}
public abstract class SmallCapsActionBarMixin {

	@ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
	private Text velo$applySmallCaps(Text message) {
		if (!SmallCapsModule.shouldTransformActionBar()) {
			return message;
		}
		return SmallCapsConverter.toSmallCapsStyled(message);
	}
}
