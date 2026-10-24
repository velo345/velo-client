package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/** The block state Sodium's renderer is meshing (for Velo Shaders' swaying plants). */
@Pseudo
//? if >=26.2 {
/*@Mixin(value = net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext.class, remap = false)
public interface VeloBlockRenderContextAccessor {

	@Accessor(value = "state", remap = false)
	net.minecraft.world.level.block.state.BlockState velo$state();
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public interface VeloBlockRenderContextAccessor {
}
//?}
