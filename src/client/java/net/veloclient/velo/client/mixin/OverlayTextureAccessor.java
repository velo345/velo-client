package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The overlay texture's backing image, repainted by Hit Color ({@code HitColorTexture}). */
//? if <26.1 {
@Mixin(net.minecraft.client.render.OverlayTexture.class)
public interface OverlayTextureAccessor {

	@Accessor("texture")
	net.minecraft.client.texture.NativeImageBackedTexture velo$texture();
}
//?} else {
/*@Mixin(net.minecraft.client.renderer.texture.OverlayTexture.class)
public interface OverlayTextureAccessor {

	@Accessor("texture")
	net.minecraft.client.renderer.texture.DynamicTexture velo$texture();
}
*///?}
