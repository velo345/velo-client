package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
//? if >=26.2 {
/*import net.veloclient.velo.client.modules.rendering.VeloShadersModule;
import net.veloclient.velo.client.modules.rendering.VeloShadowMap;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
*///?}

/**
 * Velo Shaders with Sodium: Sodium draws terrain with its own shaders and its own globals block
 * ({@code u_Globals}, 184 bytes used of a 256-byte slot). The spare tail from offset 192 carries
 * the first 64 bytes of Velo's shader data (VeloSun, VeloCam, VeloSky, VeloPack) - the pack's
 * {@code sodium:globals.glsl} declares them there. Copied from this frame's vanilla Globals data.
 */
@Pseudo
//? if >=26.2 {
/*@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager$GlobalUniforms", remap = false)
public abstract class VeloShadersSodiumMixin {

	@Unique
	private static final int VELO_OFFSET = 192;
	@Unique
	private static int velo$start;

	@Inject(method = "write(Ljava/nio/ByteBuffer;)V", at = @At("HEAD"), require = 0)
	private void velo$rememberStart(ByteBuffer buffer, CallbackInfo ci) {
		velo$start = buffer.position();
	}

	@Inject(method = "write(Ljava/nio/ByteBuffer;)V", at = @At("RETURN"), require = 0)
	private void velo$appendShaderData(ByteBuffer buffer, CallbackInfo ci) {
		if (!VeloShadersModule.active()) {
			return; // shaders off: Sodium's own shader never reads it
		}
		int at = velo$start + VELO_OFFSET;
		// Sodium's builder stops the limit at its own 184 bytes; the mapped slot is the whole 256-byte block
		// (capacity), and that is the range bound to the shader. Never past it - that's the next slot.
		if (at + 64 > buffer.capacity()) {
			return;
		}
		int limit = buffer.limit();
		buffer.limit(buffer.capacity());
		float[] f = VeloShadersModule.FRAME_FLOATS;
		for (int i = 0; i < 12; i++) {
			buffer.putFloat(at + i * 4, f[i]);
		}
		int[] p = VeloShadersModule.FRAME_PACKS;
		for (int i = 0; i < 4; i++) {
			buffer.putInt(at + 48 + i * 4, p[i]);
		}
		if (VeloShadowMap.rendering) {
			// flag 16: the shadow pass - the chunk shader skips its lighting and colour work
			buffer.putInt(at + 48 + 12, p[3] | (16 << 24));
		}
		buffer.limit(limit);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class VeloShadersSodiumMixin {
	// Velo Shaders: 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
