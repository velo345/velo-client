package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 && <26.3 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.buffers.GpuBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
*///?}

/**
 * Velo Turbo staged buffer pool (26.2): vanilla's StagedVertexBuffer pool gets a used buffer back once the GPU is done
 * with it, but at the end of every frame destroys every recycled buffer that wasn't reused in that same frame. At high
 * FPS (and with a third frame in flight) recycled buffers often come back one frame too late, so the pool keeps
 * destroying buffers and allocating fresh ones (vmaCreateBuffer + a destroy every frame). Here an idle buffer stays in
 * the pool for up to {@link #KEEP_FRAMES} frames (at most {@link #MAX_IDLE} of them) before it's destroyed, so steady
 * frames reuse the same few buffers.
 * {@code -Dvelo.stagedPoolKeep=false} restores vanilla.
 */
//? if >=26.2 && <26.3 {
/*@Mixin(targets = "net.minecraft.client.renderer.StagedVertexBuffer$GpuBufferPool")
public abstract class TurboStagedPoolMixin {

	@Unique
	private static final boolean ON = !"false".equals(System.getProperty("velo.stagedPoolKeep"));
	@Unique
	private static final int KEEP_FRAMES = 30;
	@Unique
	private static final int MAX_IDLE = 12;

	@Shadow
	@Final
	private List<GpuBuffer> available;

	@Unique
	private IdentityHashMap<GpuBuffer, Long> velo$idleSince = new IdentityHashMap<>();
	@Unique
	private IdentityHashMap<GpuBuffer, Long> velo$idleNext = new IdentityHashMap<>();
	@Unique
	private long velo$frame;
	@Unique
	private boolean velo$keptThisFrame;

	@WrapOperation(method = "endFrame", at = @At(value = "INVOKE", target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V"))
	private void velo$keepIdleBuffers(List<GpuBuffer> list, Consumer<GpuBuffer> destroy, Operation<Void> original) {
		velo$keptThisFrame = false;
		if (!ON || list != available) {
			original.call(list, destroy);
			return;
		}
		long frame = ++velo$frame;
		int kept = 0;
		IdentityHashMap<GpuBuffer, Long> next = velo$idleNext;
		next.clear();
		// Newest at the end: walk backwards so the most recently recycled buffers are the ones kept under the cap.
		for (int i = list.size() - 1; i >= 0; i--) {
			GpuBuffer buffer = list.get(i);
			Long since = velo$idleSince.get(buffer);
			long idle = since == null ? frame : since;
			if (frame - idle > KEEP_FRAMES || kept >= MAX_IDLE) {
				destroy.accept(buffer);
				list.remove(i);
			} else {
				next.put(buffer, idle);
				kept++;
			}
		}
		velo$idleNext = velo$idleSince;
		velo$idleSince = next;
		velo$keptThisFrame = true;
	}

	@WrapOperation(method = "endFrame", at = @At(value = "INVOKE", target = "Ljava/util/List;clear()V", ordinal = 1))
	private void velo$dontDropKept(List<GpuBuffer> list, Operation<Void> original) {
		if (velo$keptThisFrame) {
			velo$keptThisFrame = false;
			return;
		}
		original.call(list);
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.Minecraft")
public abstract class TurboStagedPoolMixin {
	// 26.2 only for now (VeloMixinPlugin skips it elsewhere).
}
//?}
