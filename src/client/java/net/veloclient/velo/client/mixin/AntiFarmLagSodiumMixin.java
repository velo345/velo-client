package net.veloclient.velo.client.mixin;

import net.veloclient.velo.client.modules.performance.AntiFarmLagModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Anti Farm Lag with Sodium installed: Sodium replaces vanilla's section-dirty methods and queues
 * every rebuild through {@code RenderSectionManager#scheduleRebuild}, rebuilding "important"
 * (nearby) sections synchronously on the render thread - the main cost of observer clocks next to
 * you. Busy farm sections are coalesced and always built in the background here. Only applied when
 * Sodium is present ({@code @Pseudo}); no Sodium classes are needed to compile.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class AntiFarmLagSodiumMixin {

	@Shadow(remap = false)
	public abstract void scheduleRebuild(int x, int y, int z, boolean important);

	@Inject(method = "scheduleRebuild(IIIZ)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void velo$coalesce(int x, int y, int z, boolean important, CallbackInfo ci) {
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
			scheduleRebuild(x, y, z, false);
		} finally {
			AntiFarmLagModule.flushing = was;
		}
	}
}
