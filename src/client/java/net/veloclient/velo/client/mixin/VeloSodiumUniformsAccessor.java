package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/** Lets Velo Shaders' shadow pass write Sodium's per-frame globals a second time. */
@Pseudo
//? if >=26.2 {
/*@Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager.class, remap = false)
public interface VeloSodiumUniformsAccessor {

	@Accessor(value = "hasUpdatedThisFrame", remap = false)
	void velo$setUpdatedThisFrame(boolean updated);
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public interface VeloSodiumUniformsAccessor {
}
//?}
