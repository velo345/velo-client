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
 * Lets {@code BackgroundQueueManager} hand a backgrounded connection its world back when you switch
 * to it: the soft detach (vanilla's own reconfiguration path) nulls the handler's private world
 * reference, and nothing in vanilla ever sets it again except a fresh login.
 */
//? if <26.1 {
@Mixin(ClientPlayNetworkHandler.class)
public interface QueueHandlerAccessorMixin {
	@Mutable
	@Accessor("world")
	void velo$setWorld(ClientWorld world);
}
//?} else {
/*@Mixin(ClientPacketListener.class)
public interface QueueHandlerAccessorMixin {
	@Mutable
	@Accessor("level")
	void velo$setWorld(ClientLevel world);
}
*///?}
