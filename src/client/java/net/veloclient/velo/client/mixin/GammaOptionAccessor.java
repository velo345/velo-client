package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Full Bright (2026-11-26, owner: "sieht wie Night Vision aus"): writes the brightness option's value directly, past
 * vanilla's 100 % limit - the slider's own setter refuses anything above 1.0. At ~16 every light level renders as full
 * daylight, in its real colours (Night Vision tinted everything blue-white and kept the dark spots dark).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.option.SimpleOption.class)
//?} else {
/*@Mixin(net.minecraft.client.OptionInstance.class)
*///?}
public interface GammaOptionAccessor {

	@Accessor("value")
	void velo$setRawValue(Object value);
}
