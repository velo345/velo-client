package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
//?} else {
/*import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes setters for the play network handler's own private world reference
 * and its "world cleared" flag - both normally only ever nulled/set-true by
 * vanilla's {@code unloadWorld()}/{@code clearWorld()}, with no way back in.
 * Needed by {@code BackgroundQueueManager} to reattach a still-alive,
 * previously-stashed {@code ClientWorld} to its original handler after a
 * "soft disconnect" (see that class's javadoc) - without this, the handler
 * would keep silently dropping every chunk/entity update packet after a
 * promote, since most of its packet handlers early-return once their world
 * reference is null.
 *
 * <p>Deliberately declared {@code required: false} in {@code
 * velo-client.client.optional.mixins.json} (not the main required mixin
 * config) - unlike this repo's other mixins, this one targets private
 * implementation-detail fields that aren't exercised by any existing code
 * path here, so there's real version-to-version rename risk; if it fails to
 * apply on some future Minecraft update, the queue module's chunk/entity
 * data just stays stale after a promote instead of the field mismatch
 * crashing the whole client on startup.
 */
//? if <26.1 {
@Mixin(ClientPlayNetworkHandler.class)
public interface QueueHandlerAccessorMixin {
	@Mutable
	@Accessor("world")
	void velo$setWorld(ClientWorld world);

	@Mutable
	@Accessor("worldCleared")
	void velo$setWorldCleared(boolean cleared);
}
//?} else {
/*@Mixin(ClientPacketListener.class)
public interface QueueHandlerAccessorMixin {
	@Mutable
	@Accessor("level")
	void velo$setWorld(ClientLevel world);

	@Mutable
	@Accessor("worldCleared")
	void velo$setWorldCleared(boolean cleared);
}
*///?}
