package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter;
import net.caffeinemc.mods.sodium.api.vertex.format.common.ParticleVertex;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Unique;
*///?}

/**
 * Velo Turbo - particle direct write (Sodium): Sodium writes each particle quad by opening a MemoryStack frame
 * ({@code MemoryStack.stackPush()} = a ThreadLocal lookup), building the 4 vertices in it and pushing them. With the
 * tens of thousands of particles of a TNT array that ThreadLocal lookup alone was ~21% of the render thread (profiled
 * 2026-11-11). This writes the same 4 vertices (same corners, UVs, ABGR colour and light as Sodium) into one reused
 * native scratch and pushes them with a stack owned by the render thread - no per-particle ThreadLocal, no per-particle
 * stack frame. Anything that isn't a Sodium-writable consumer, or not on the render thread, takes the original path.
 * Off with -Dvelo.particleDirectWrite=false.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.state.level.QuadParticleRenderState.class)
public abstract class TurboSodiumParticleWriteMixin {

	@Unique
	private static final boolean ON = !"false".equals(System.getProperty("velo.particleDirectWrite"));
	@Unique
	private static final Quaternionf VELO$Q = new Quaternionf();
	@Unique
	private static final Vector3f VELO$V = new Vector3f();
	// Quads collected before one push: every push reserves buffer space (16% of the render thread when done per quad).
	@Unique
	private static final int BATCH_QUADS = 512;
	@Unique
	private static long velo$scratch;
	@Unique
	private static MemoryStack velo$stack;
	// While a layer is being built: its writer and how many quads wait in the scratch.
	@Unique
	private static VertexBufferWriter velo$batchWriter;
	@Unique
	private static VertexConsumer velo$batchConsumer;
	@Unique
	private static int velo$batched;
	@Unique
	private static boolean velo$inLayer;

	@WrapMethod(method = "buildLayer", require = 0)
	private void velo$batchLayer(net.minecraft.client.particle.SingleQuadParticle.Layer layer, VertexConsumer consumer, Operation<Void> original) {
		if (!ON || velo$inLayer || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
			original.call(layer, consumer);
			return;
		}
		velo$inLayer = true;
		try {
			original.call(layer, consumer);
		} finally {
			velo$flush();
			velo$inLayer = false;
			velo$batchWriter = null;
			velo$batchConsumer = null;
		}
	}

	@WrapMethod(method = "renderRotatedQuad", require = 0)
	private void velo$directWrite(VertexConsumer consumer, float x, float y, float z, float qx, float qy, float qz, float qw, float size,
			float u0, float u1, float v0, float v1, int color, int light, Operation<Void> original) {
		VertexBufferWriter writer = ON && net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo
				&& com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread() ? VertexBufferWriter.tryOf(consumer) : null;
		if (writer == null) {
			velo$flush();
			original.call(consumer, x, y, z, qx, qy, qz, qw, size, u0, u1, v0, v1, color, light);
			return;
		}
		if (velo$scratch == 0L) {
			velo$scratch = MemoryUtil.nmemAlloc((long) BATCH_QUADS * 4L * ParticleVertex.STRIDE);
			velo$stack = MemoryStack.create(16 * 1024);
		}
		if (consumer != velo$batchConsumer) {
			velo$flush();
			velo$batchConsumer = consumer;
			velo$batchWriter = writer;
		}
		Quaternionf q = VELO$Q.set(qx, qy, qz, qw);
		int abgr = ColorARGB.toABGR(color);
		long p = velo$scratch + (long) velo$batched * 4L * ParticleVertex.STRIDE;
		velo$corner(p, q, 1f, -1f, size, x, y, z, u1, v1, abgr, light);
		velo$corner(p + ParticleVertex.STRIDE, q, 1f, 1f, size, x, y, z, u1, v0, abgr, light);
		velo$corner(p + 2L * ParticleVertex.STRIDE, q, -1f, 1f, size, x, y, z, u0, v0, abgr, light);
		velo$corner(p + 3L * ParticleVertex.STRIDE, q, -1f, -1f, size, x, y, z, u0, v1, abgr, light);
		velo$batched++;
		// outside a layer build (a mod calling in directly) every quad goes out at once, as before
		if (!velo$inLayer || velo$batched == BATCH_QUADS) {
			velo$flush();
		}
	}

	@Unique
	private static void velo$flush() {
		if (velo$batched > 0 && velo$batchWriter != null) {
			velo$batchWriter.push(velo$stack, velo$scratch, velo$batched * 4, ParticleVertex.FORMAT);
		}
		velo$batched = 0;
	}

	@Unique
	private static void velo$corner(long ptr, Quaternionf q, float cx, float cy, float size, float x, float y, float z, float u, float v,
			int abgr, int light) {
		Vector3f p = VELO$V.set(cx, cy, 0f).rotate(q).mul(size).add(x, y, z);
		ParticleVertex.put(ptr, p.x, p.y, p.z, u, v, abgr, light);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumParticleWriteMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
