package net.veloclient.velo.client.mixin;

//? if >=26.2 && <26.3 {
/*import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.veloclient.velo.client.modules.performance.ShaderCache;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
*///?}
import org.spongepowered.asm.mixin.Mixin;

/**
 * Shader Cache, part 1 (Vulkan, 26.2+): vanilla compiles every GLSL shader to SPIR-V with shaderc
 * on every launch and resource reload. This serves the SPIR-V from {@code ShaderCache} instead when
 * the exact same source was compiled before, and stores whatever does get compiled. The module is
 * built from the bytes exactly as vanilla would, so nothing downstream can tell the difference.
 * Applied only where the class exists (see {@code VeloMixinPlugin}).
 */
//? if >=26.2 && <26.3 {
/*@Mixin(GlslCompiler.class)
public abstract class VulkanShaderCacheMixin {

	@Unique
	private static final ThreadLocal<String> VELO_PENDING_KEY = new ThreadLocal<>();
	@Unique
	private static final ThreadLocal<Long> VELO_STARTED = new ThreadLocal<>();
	@Unique
	private static final String VELO_MC_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");

	@Inject(method = "createIntermediary", at = @At("HEAD"), cancellable = true)
	private void velo$serveCachedSpirv(String filename, String source, ShaderType type, CallbackInfoReturnable<IntermediaryShaderModule> cir) {
		VELO_PENDING_KEY.remove();
		if (!ShaderCache.enabled) {
			return;
		}
		String key = ShaderCache.key(VELO_MC_VERSION, filename, type.name(), source);
		byte[] cached = ShaderCache.readSpirv(key);
		if (cached != null) {
			ByteBuffer spirv = MemoryUtil.memAlloc(cached.length);
			spirv.put(cached).flip();
			try {
				cir.setReturnValue(IntermediaryShaderModule.createFromSpirv(filename, spirv));
				ShaderCache.recordHit();
				return;
			} catch (ShaderCompileException | RuntimeException e) {
				// Unusable entry - compile it normally (which overwrites the entry).
				MemoryUtil.memFree(spirv);
			}
		}
		VELO_PENDING_KEY.set(key);
		VELO_STARTED.set(System.nanoTime());
	}

	@Redirect(method = "createIntermediary", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;createFromSpirv(Ljava/lang/String;Ljava/nio/ByteBuffer;)Lcom/mojang/blaze3d/vulkan/glsl/IntermediaryShaderModule;"))
	private IntermediaryShaderModule velo$storeCompiledSpirv(String filename, ByteBuffer spirv) throws ShaderCompileException {
		String key = VELO_PENDING_KEY.get();
		if (key != null) {
			VELO_PENDING_KEY.remove();
			Long started = VELO_STARTED.get();
			ShaderCache.recordCompile(started == null ? 0 : System.nanoTime() - started);
			byte[] bytes = new byte[spirv.remaining()];
			spirv.duplicate().get(bytes);
			ShaderCache.writeSpirv(key, bytes);
		}
		return IntermediaryShaderModule.createFromSpirv(filename, spirv);
	}
}
*///?} else if >=26.3 {
/*@Mixin(com.mojang.renderpearl.frontend.shaders.GlslCompiler.class)
public abstract class VulkanShaderCacheMixin {

	// 26.3 port: shaderc now resolves #includes itself during compileToSpv, so the cache key also
	// covers the shader defines and the active resource packs (an include can come from a pack).

	@org.spongepowered.asm.mixin.Unique
	private static final ThreadLocal<String> VELO_PENDING_KEY = new ThreadLocal<>();
	@org.spongepowered.asm.mixin.Unique
	private static final ThreadLocal<Long> VELO_STARTED = new ThreadLocal<>();
	@org.spongepowered.asm.mixin.Unique
	private static final String VELO_MC_VERSION = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft")
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");

	@org.spongepowered.asm.mixin.Unique
	private static String velo$packs() {
		try {
			return String.join(",", net.minecraft.client.Minecraft.getInstance().getResourcePackRepository().getSelectedIds());
		} catch (RuntimeException e) {
			return "?";
		}
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "compileToSpv", at = @org.spongepowered.asm.mixin.injection.At("HEAD"), cancellable = true)
	private void velo$serveCachedSpirv(String name, String source, com.mojang.renderpearl.api.pipeline.ShaderType type,
			net.minecraft.client.renderer.ShaderDefines defines, com.mojang.renderpearl.api.pipeline.ShaderSource includes,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<com.mojang.renderpearl.backend.api.SpvModule> cir) {
		VELO_PENDING_KEY.remove();
		if (!net.veloclient.velo.client.modules.performance.ShaderCache.enabled) {
			return;
		}
		String key = net.veloclient.velo.client.modules.performance.ShaderCache.key(VELO_MC_VERSION,
				name + "|" + defines + "|" + velo$packs(), type.name(), source);
		byte[] cached = net.veloclient.velo.client.modules.performance.ShaderCache.readSpirv(key);
		if (cached != null) {
			java.nio.ByteBuffer spirv = org.lwjgl.system.MemoryUtil.memCalloc(cached.length);
			spirv.put(cached).flip();
			cir.setReturnValue(new com.mojang.renderpearl.frontend.shaders.SPIRVModule(spirv, type));
			net.veloclient.velo.client.modules.performance.ShaderCache.recordHit();
			return;
		}
		VELO_PENDING_KEY.set(key);
		VELO_STARTED.set(System.nanoTime());
	}

	@org.spongepowered.asm.mixin.injection.Inject(method = "compileToSpv", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
	private void velo$storeCompiledSpirv(String name, String source, com.mojang.renderpearl.api.pipeline.ShaderType type,
			net.minecraft.client.renderer.ShaderDefines defines, com.mojang.renderpearl.api.pipeline.ShaderSource includes,
			org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<com.mojang.renderpearl.backend.api.SpvModule> cir) {
		String key = VELO_PENDING_KEY.get();
		if (key == null || cir.getReturnValue() == null) {
			return;
		}
		VELO_PENDING_KEY.remove();
		Long started = VELO_STARTED.get();
		net.veloclient.velo.client.modules.performance.ShaderCache.recordCompile(started == null ? 0 : System.nanoTime() - started);
		java.nio.ByteBuffer spirv = cir.getReturnValue().spv().duplicate();
		byte[] bytes = new byte[spirv.remaining()];
		spirv.get(bytes);
		net.veloclient.velo.client.modules.performance.ShaderCache.writeSpirv(key, bytes);
	}
}
*///?} else {
@Mixin(targets = "com.mojang.blaze3d.vulkan.glsl.GlslCompiler")
public abstract class VulkanShaderCacheMixin {
	// No Vulkan renderer before 26.2 - never applied (VeloMixinPlugin skips it).
}
//?}
