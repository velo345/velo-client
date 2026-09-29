package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.network.ClientPlayNetworkHandler;
//?} else {
/*import net.minecraft.client.multiplayer.ClientPacketListener;
*///?}
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.modules.servertools.ChunkLoadProfilerModule;
import net.veloclient.velo.client.util.ChunkLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times how long the client itself takes to process each incoming chunk packet (parse + build the
 * client-side chunk), for {@code ChunkLoadProfilerModule}'s relative load-time heatmap - purely a
 * read of already-arriving network data timed on this client, nothing queried beyond what the
 * server already sent. {@code @Unique} start-time field rather than two independent injections
 * computing their own timestamps, since a connection only ever processes one packet at a time.
 *
 * <p>Yarn's {@code ClientPlayNetworkHandler#onChunkData(ChunkDataS2CPacket)} and Mojmap's {@code
 * ClientPacketListener#handleLevelChunkWithLight(ClientboundLevelChunkWithLightPacket)} are the
 * same packet handler under different names (verified via javap), each exposing the chunk's
 * position via {@code getChunkX()/getChunkZ()} (Yarn) or {@code getX()/getZ()} (Mojmap).
 */
//? if <26.1 {
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ChunkLoadTimingMixin {

	@Unique
	private long velo$chunkLoadStartNanos;

	@Inject(method = "onChunkData", at = @At("HEAD"))
	private void velo$beforeChunkData(net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket packet, CallbackInfo ci) {
		if (ChunkLoadProfilerModule.isRunning()) {
			velo$chunkLoadStartNanos = System.nanoTime();
		}
	}

	@Inject(method = "onChunkData", at = @At("RETURN"))
	private void velo$afterChunkData(net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket packet, CallbackInfo ci) {
		if (!ChunkLoadProfilerModule.isRunning()) {
			return;
		}
		long elapsed = System.nanoTime() - velo$chunkLoadStartNanos;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world != null) {
			String dimension = client.world.getRegistryKey().getValue().toString();
			ChunkLoadTracker.recordLoad(dimension, packet.getChunkX(), packet.getChunkZ(), elapsed);
		}
	}
}
//?} else {
/*@Mixin(ClientPacketListener.class)
public abstract class ChunkLoadTimingMixin {

	@Unique
	private long velo$chunkLoadStartNanos;

	@Inject(method = "handleLevelChunkWithLight", at = @At("HEAD"))
	private void velo$beforeChunkData(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		if (ChunkLoadProfilerModule.isRunning()) {
			velo$chunkLoadStartNanos = System.nanoTime();
		}
	}

	@Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
	private void velo$afterChunkData(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		if (!ChunkLoadProfilerModule.isRunning()) {
			return;
		}
		long elapsed = System.nanoTime() - velo$chunkLoadStartNanos;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.level != null) {
			String dimension = client.level.dimension().identifier().toString();
			ChunkLoadTracker.recordLoad(dimension, packet.getX(), packet.getZ(), elapsed);
		}
	}
}
*///?}
