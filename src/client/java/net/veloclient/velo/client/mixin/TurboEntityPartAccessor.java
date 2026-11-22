package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import org.spongepowered.asm.mixin.gen.Accessor;
*///?}

/** A model part's own cubes and its children (private in Mojang's names) - GpuModelMeshes walks the part tree. */
//? if >=26.2 && <26.3 {
/*@Mixin(net.minecraft.client.model.geom.ModelPart.class)
public interface TurboEntityPartAccessor {

	@Accessor("cubes")
	java.util.List<net.minecraft.client.model.geom.ModelPart.Cube> velo$cubes();

	@Accessor("children")
	java.util.Map<String, net.minecraft.client.model.geom.ModelPart> velo$children();
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public interface TurboEntityPartAccessor {
	// 26.2 only (VeloMixinPlugin skips it elsewhere).
}
//?}
