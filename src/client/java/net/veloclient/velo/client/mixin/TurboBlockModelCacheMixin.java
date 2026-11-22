package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.model.BlockDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Block model memo (TNT Optimizer's switch, and always with Velo Turbo since 2026-11-21): block-shaped entities (primed TNT, falling blocks, block displays, minecart contents
 * ...) rebuild their block model every frame - the model set lookup plus the whole Fabric model pipeline (quads into a
 * mutable view and back). Profiled 2026-11-11 in a TNT farm: 60 primed TNT cost 7% of the render thread. The result
 * only depends on the block state and the display context (the model seed is a constant), so the first build is
 * remembered and later ones copy it - until the model set changes (resource reload). Render thread only, off with -Dvelo.blockModelMemo=false.
 */
//? if >=26.2 {
/*@Mixin(net.minecraft.client.renderer.block.BlockModelResolver.class)
public abstract class TurboBlockModelCacheMixin {

	@Unique
	private static final boolean ON = !"false".equals(System.getProperty("velo.blockModelMemo"));
	@Unique
	private static final int MAX_ENTRIES = 512;

	@Shadow
	@Final
	private net.minecraft.client.resources.model.ModelManager modelManager;

	@Unique
	private final IdentityHashMap<BlockState, IdentityHashMap<BlockDisplayContext, Object[]>> velo$memo = new IdentityHashMap<>();
	@Unique
	private Object velo$modelSet;
	@Unique
	private int velo$entries;
	@Unique
	private boolean velo$recording;

	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void velo$fromMemo(BlockModelRenderState state, BlockState block, BlockDisplayContext context, CallbackInfo ci) {
		velo$recording = false;
		// TNT Optimizer's switch, or Velo Turbo (block displays: server spawns are built from hundreds of them)
		if (!ON || !(net.veloclient.velo.client.modules.performance.TntOptimizerModule.reuseModels()
				|| net.veloclient.velo.client.modules.performance.PerformanceBoostModule.turbo)
				|| !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
			return;
		}
		Object set = modelManager.getBlockModelSet();
		if (set != velo$modelSet) {
			velo$memo.clear();
			velo$entries = 0;
			velo$modelSet = set;
		}
		IdentityHashMap<BlockDisplayContext, Object[]> byContext = velo$memo.get(block);
		Object[] m = byContext == null ? null : byContext.get(context);
		if (m == null) {
			velo$recording = velo$entries < MAX_ENTRIES;
			return;
		}
		state.clear();
		TurboBlockModelStateAccessor a = (TurboBlockModelStateAccessor) state;
		@SuppressWarnings("unchecked")
		List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = (List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart>) m[0];
		a.velo$setModelParts(parts == null ? null : new ArrayList<>(parts));
		a.velo$setTransformation((org.joml.Matrix4fc) m[1]);
		a.velo$setRenderType((net.minecraft.client.renderer.rendertype.RenderType) m[2]);
		a.velo$setSpecialRenderer((net.minecraft.client.renderer.special.SpecialModelRenderer<?>) m[3]);
		a.velo$setSpecialTransformation((org.joml.Matrix4fc) m[4]);
		state.tintLayers().addAll((it.unimi.dsi.fastutil.ints.IntList) m[5]);
		state.blockLightCoords = (Integer) m[6];
		if (m[7] != null && !velo$setMesh(state, m[7])) {
			return; // couldn't hand the geometry over: let vanilla build it (never an empty model)
		}
		ci.cancel();
	}

	@Inject(method = "update", at = @At("RETURN"))
	private void velo$toMemo(BlockModelRenderState state, BlockState block, BlockDisplayContext context, CallbackInfo ci) {
		if (!velo$recording) {
			return;
		}
		velo$recording = false;
		// Fabric API keeps emitted geometry in its own mesh field (a new mesh each update, nulled by clear()). A memo of only
		// the vanilla fields replayed an EMPTY model (primed TNT went invisible, 2026-11-15), so the mesh is copied into the
		// memo and handed back on every hit - one copy per block state, shared read-only (nothing writes a mesh after it
		// was filled: update() clears the state first, which only nulls the field).
		Object meshCopy = null;
		if (velo$fabricMeshInUse(state)) {
			meshCopy = velo$copyMesh(state);
			if (meshCopy == null) {
				return; // can't copy it: never memoized
			}
		}
		TurboBlockModelStateAccessor a = (TurboBlockModelStateAccessor) state;
		List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts = a.velo$modelParts();
		Object[] m = {parts == null ? null : List.copyOf(parts), a.velo$transformation(), a.velo$renderType(), a.velo$specialRenderer(),
				a.velo$specialTransformation(), new it.unimi.dsi.fastutil.ints.IntArrayList(state.tintLayers()), state.blockLightCoords, meshCopy};
		velo$memo.computeIfAbsent(block, k -> new IdentityHashMap<>()).put(context, m);
		velo$entries++;
	}

	// A filled copy of the state's Fabric mesh (null when there's nothing to copy or no way to).
	@Unique
	private static Object velo$copyMesh(BlockModelRenderState state) {
		java.lang.reflect.Field f = velo$meshField;
		if (f == null) {
			return null;
		}
		try {
			Object mesh = f.get(state);
			if (!(mesh instanceof net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView view)) {
				return null;
			}
			var copy = net.fabricmc.fabric.api.client.renderer.v1.Renderer.get().mutableMesh();
			view.outputTo(copy.emitter());
			return copy.size() == view.size() && f.getType().isInstance(copy) ? copy : null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	@Unique
	private static boolean velo$setMesh(BlockModelRenderState state, Object mesh) {
		java.lang.reflect.Field f = velo$meshField;
		if (f == null) {
			return false;
		}
		try {
			f.set(state, mesh);
			return true;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	@Unique
	private static java.lang.reflect.Field velo$meshField;
	@Unique
	private static boolean velo$meshLookedUp;

	// True when Fabric's mixin-added mesh field holds quads - or when it can't be checked at all: Fabric API is always
	// loaded, so a missing/renamed field means "unknown", and an unknown result must never be memoized (invisible TNT).
	@Unique
	private static boolean velo$fabricMeshInUse(BlockModelRenderState state) {
		if (!velo$meshLookedUp) {
			velo$meshLookedUp = true;
			try {
				for (java.lang.reflect.Field f : BlockModelRenderState.class.getDeclaredFields()) {
					if (net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView.class.isAssignableFrom(f.getType())) {
						f.setAccessible(true);
						velo$meshField = f;
						break;
					}
				}
			} catch (RuntimeException e) {
				velo$meshField = null;
			}
		}
		java.lang.reflect.Field f = velo$meshField;
		if (f == null) {
			return true;
		}
		try {
			Object mesh = f.get(state);
			return mesh != null && ((net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView) mesh).size() > 0;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return true;
		}
	}
}
*///?} else {
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class TurboBlockModelCacheMixin {
	// 26.2+ only.
}
//?}
