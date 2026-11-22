package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullResult;
import net.veloclient.velo.client.modules.performance.SodiumTreePool;
*///?}

/**
 * Velo Turbo: marks Sodium's cull pass (on its "Async Cull Thread") for {@link
 * net.veloclient.velo.client.modules.performance.SodiumTreePool} - trees built in between use pooled arrays, the
 * portal trees' arrays go back when the pass returns. Only with Sodium on 26.2+ (VeloMixinPlugin).
 */
//? if >=26.2 {
/*@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask", remap = false)
public abstract class TurboSodiumCullPassMixin {

	@Inject(method = "runTask()Lnet/caffeinemc/mods/sodium/client/render/chunk/async/CullResult;", at = @At("HEAD"), require = 0)
	private void velo$passStart(CallbackInfoReturnable<CullResult> cir) {
		SodiumTreePool.beginPass();
	}

	@Inject(method = "runTask()Lnet/caffeinemc/mods/sodium/client/render/chunk/async/CullResult;", at = @At("RETURN"), require = 0)
	private void velo$passEnd(CallbackInfoReturnable<CullResult> cir) {
		CullResult result = cir.getReturnValue();
		if (result == null) {
			SodiumTreePool.endPass(null, null, null, false);
			return;
		}
		// NOT result.getCullTree*(): those apply Sodium's pending patches, which the render thread is editing right now
		// (this is the cull thread) - that crashed with ConcurrentModificationException / NPE in LinkedList. The trees are
		// only needed as identity markers, so they're read straight from the result's fields.
		Object[] trees = velo$trees(result);
		SodiumTreePool.endPass(trees == null ? null : trees[0], trees == null ? null : trees[1], trees == null ? null : trees[2], trees != null);
	}

	private static volatile java.lang.reflect.Field[] velo$fields;
	private static volatile boolean velo$noFields;

	private static Object[] velo$trees(CullResult result) {
		if (velo$noFields) {
			return null;
		}
		try {
			java.lang.reflect.Field[] fields = velo$fields;
			if (fields == null || fields[0].getDeclaringClass() != result.getClass()) {
				Class<?> c = result.getClass();
				fields = new java.lang.reflect.Field[] {c.getDeclaredField("val$wideTree"), c.getDeclaredField("val$regularTree"),
						c.getDeclaredField("val$localTree")};
				for (var f : fields) {
					f.setAccessible(true);
				}
				velo$fields = fields;
			}
			return new Object[] {fields[0].get(result), fields[1].get(result), fields[2].get(result)};
		} catch (ReflectiveOperationException | RuntimeException e) {
			velo$noFields = true; // another Sodium build: result arrays are just not recycled
			return null;
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboSodiumCullPassMixin {
	// 26.2+ with Sodium only (VeloMixinPlugin skips it elsewhere).
}
//?}
