package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
/*import com.mojang.blaze3d.vertex.UberGpuBuffer;
import net.veloclient.velo.client.util.SlotMirrorMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
*///?}

/**
 * Swaps the terrain uber buffer's allocation map for a {@link net.veloclient.velo.client.util.SlotMirrorMap}
 * (26.x), so every visible section's per-frame "where is my vertex/index data" lookup is answered
 * by the section mesh itself instead of a hash lookup per layer and buffer (~10% of the render
 * thread before).
 */
//? if >=26.1 {
/*@Mixin(UberGpuBuffer.class)
public abstract class UberBufferSlotsMixin {

	@Shadow
	@Final
	@Mutable
	private Map<Object, Object> allocationMap;

	@Inject(method = "<init>", at = @At("RETURN"), require = 0)
	private void velo$slotMirroredMap(CallbackInfo ci) {
		SlotMirrorMap<Object, Object> mirrored = new SlotMirrorMap<>(256);
		allocationMap.forEach(mirrored::put); // empty at construction; put() (not putAll) keeps the slots in sync
		allocationMap = mirrored;
	}
}
*///?} else {
@Mixin(net.minecraft.client.render.WorldRenderer.class)
public abstract class UberBufferSlotsMixin {
}
//?}
