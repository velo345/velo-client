package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anti Farm Lag's rebuild coalescing: every "this section needs re-meshing" goes through this one
 * private method, which the module may delay for sections that keep changing; delayed ones are
 * re-marked through the same method a few ticks later ({@link AntiFarmLagModule#setFlusher}).
 */
//? if <26.1 {
@Mixin(net.minecraft.client.render.WorldRenderer.class)
public abstract class AntiFarmLagSectionMixin {

	@Unique
	private static final boolean VELO_SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

	@Shadow
	private void scheduleChunkRender(int x, int y, int z, boolean important) {
	}

	@Inject(method = "scheduleChunkRender(IIIZ)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$coalesce(int x, int y, int z, boolean important, CallbackInfo ci) {
		if (VELO_SODIUM) {
			// Sodium overwrites this path; AntiFarmLagSodiumMixin handles its rebuild queue instead.
			return;
		}
		AntiFarmLagModule.setFlusher(this::velo$flush);
		int decision = AntiFarmLagModule.sectionDecision(x, y, z, important);
		if (decision == AntiFarmLagModule.DEFER) {
			ci.cancel();
		} else if (decision == AntiFarmLagModule.ALLOW_ASYNC) {
			ci.cancel();
			velo$flush(x, y, z);
		}
	}

	@Unique
	private void velo$flush(int x, int y, int z) {
		boolean was = AntiFarmLagModule.flushing;
		AntiFarmLagModule.flushing = true;
		try {
			scheduleChunkRender(x, y, z, false);
		} finally {
			AntiFarmLagModule.flushing = was;
		}
	}
}
//?} else if <26.2 {
/*@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class AntiFarmLagSectionMixin {

	@Unique
	private static final boolean VELO_SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

	@Shadow
	private void setSectionDirty(int x, int y, int z, boolean important) {
	}

	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$coalesce(int x, int y, int z, boolean important, CallbackInfo ci) {
		if (VELO_SODIUM) {
			// Sodium overwrites this path; AntiFarmLagSodiumMixin handles its rebuild queue instead.
			return;
		}
		AntiFarmLagModule.setFlusher(this::velo$flush);
		int decision = AntiFarmLagModule.sectionDecision(x, y, z, important);
		if (decision == AntiFarmLagModule.DEFER) {
			ci.cancel();
		} else if (decision == AntiFarmLagModule.ALLOW_ASYNC) {
			ci.cancel();
			velo$flush(x, y, z);
		}
	}

	@Unique
	private void velo$flush(int x, int y, int z) {
		boolean was = AntiFarmLagModule.flushing;
		AntiFarmLagModule.flushing = true;
		try {
			setSectionDirty(x, y, z, false);
		} finally {
			AntiFarmLagModule.flushing = was;
		}
	}
}
*///?} else {
/*@Mixin(net.minecraft.client.renderer.extract.LevelExtractor.class)
public abstract class AntiFarmLagSectionMixin {

	@Unique
	private static final boolean VELO_SODIUM = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");

	@Shadow
	private void setSectionDirty(int x, int y, int z, boolean important) {
	}

	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$coalesce(int x, int y, int z, boolean important, CallbackInfo ci) {
		if (VELO_SODIUM) {
			// Sodium overwrites this path; AntiFarmLagSodiumMixin handles its rebuild queue instead.
			return;
		}
		AntiFarmLagModule.setFlusher(this::velo$flush);
		int decision = AntiFarmLagModule.sectionDecision(x, y, z, important);
		if (decision == AntiFarmLagModule.DEFER) {
			ci.cancel();
		} else if (decision == AntiFarmLagModule.ALLOW_ASYNC) {
			ci.cancel();
			velo$flush(x, y, z);
		}
	}

	@Unique
	private void velo$flush(int x, int y, int z) {
		boolean was = AntiFarmLagModule.flushing;
		AntiFarmLagModule.flushing = true;
		try {
			setSectionDirty(x, y, z, false);
		} finally {
			AntiFarmLagModule.flushing = was;
		}
	}
}
*///?}
