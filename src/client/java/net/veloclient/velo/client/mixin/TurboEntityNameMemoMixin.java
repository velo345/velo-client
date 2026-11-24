package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.11 && <26.1 {
import org.spongepowered.asm.mixin.Unique;
//?} else if >=26.2 {
/*import org.spongepowered.asm.mixin.Unique;
*///?}

/** Velo Turbo: remembers each entity's last name tag component (see TurboEntityNameTagMixin). 26.2+ (VeloMixinPlugin). */
//? if >=1.21.11 && <26.1 {
@Mixin(net.minecraft.entity.Entity.class)
public abstract class TurboEntityNameMemoMixin implements net.veloclient.velo.client.util.StableName {

	@Unique
	private Object velo$lastName;

	@Override
	public Object velo$lastName() {
		return velo$lastName;
	}

	@Override
	public void velo$lastName(Object name) {
		velo$lastName = name;
	}
}
//?} else if >=26.2 {
/*@Mixin(net.minecraft.world.entity.Entity.class)
public abstract class TurboEntityNameMemoMixin implements net.veloclient.velo.client.util.StableName {

	@Unique
	private Object velo$lastName;

	@Override
	public Object velo$lastName() {
		return velo$lastName;
	}

	@Override
	public void velo$lastName(Object name) {
		velo$lastName = name;
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboEntityNameMemoMixin {
	// 26.2+ only (VeloMixinPlugin skips it elsewhere).
}
*///?}
