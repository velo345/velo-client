package net.veloclient.velo.dev.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Storage Memory: the hashed world seed the server sent on join / respawn. Behind a proxy every backend world (hub,
 * survival, skyblock...) has its own, so it tells sub-servers apart that all share one address.
 */
//? if <26.1 {
@Mixin(net.minecraft.world.biome.source.BiomeAccess.class)
public interface BiomeSeedAccessor {

	@Accessor("seed")
	long velo$seed();
}
//?} else {
/*@Mixin(net.minecraft.world.level.biome.BiomeManager.class)
public interface BiomeSeedAccessor {

	@Accessor("biomeZoomSeed")
	long velo$seed();
}
*///?}
