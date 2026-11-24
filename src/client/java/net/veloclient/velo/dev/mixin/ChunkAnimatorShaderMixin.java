package net.veloclient.velo.dev.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.11 && <26.1 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.veloclient.velo.dev.tweaks.ChunkAnimatorModule;
import org.spongepowered.asm.mixin.injection.At;
//?} else if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.veloclient.velo.dev.tweaks.ChunkAnimatorModule;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/** Chunk Animator: hands every shader source vanilla's shader manager loads to the module before it's stored. */
//? if >=1.21.11 && <26.1 {
// Sodium 0.8 (1.21.11) reads its shaders itself, not through vanilla's ShaderLoader
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader", remap = false)
public abstract class ChunkAnimatorShaderMixin {

	@com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "getShaderSource", at = @At("RETURN"), require = 0, remap = false)
	private static String velo$patch(String source, net.minecraft.util.Identifier location) {
		if (source != null && "sodium".equals(location.getNamespace())) {
			return ChunkAnimatorModule.patch(location.getPath(), source);
		}
		return source;
	}
}
//?} else if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.ShaderManager.class)
public abstract class ChunkAnimatorShaderMixin {

	@WrapOperation(method = "loadShader", at = @At(value = "INVOKE",
			target = "Lcom/google/common/collect/ImmutableMap$Builder;put(Ljava/lang/Object;Ljava/lang/Object;)Lcom/google/common/collect/ImmutableMap$Builder;"),
			require = 0)
	private static com.google.common.collect.ImmutableMap.Builder<Object, Object> velo$patch(com.google.common.collect.ImmutableMap.Builder<Object, Object> builder,
			Object key, Object value, Operation<com.google.common.collect.ImmutableMap.Builder<Object, Object>> original,
			@Local(argsOnly = true, ordinal = 0) net.minecraft.resources.Identifier location) {
		if (value instanceof String source && "sodium".equals(location.getNamespace())) {
			value = ChunkAnimatorModule.patch(location.getPath(), source);
		}
		return original.call(builder, key, value);
	}
}
*///?} else {
/*@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class ChunkAnimatorShaderMixin {
	// Chunk Animator: 26.2+ (Sodium's section times); nothing to patch here.
}
*///?}
