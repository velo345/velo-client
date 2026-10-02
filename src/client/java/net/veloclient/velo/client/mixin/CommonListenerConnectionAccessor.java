package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.ClientConnection;
//?} else {
/*import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The connection behind any client-side play/configuration listener - the base class keeps it in a
 * protected field with no public getter. Background Queue uses it to recognize packets that belong
 * to a backgrounded connection, whichever protocol phase it's in.
 */
//? if <26.1 {
@Mixin(ClientCommonNetworkHandler.class)
public interface CommonListenerConnectionAccessor {
	@Accessor("connection")
	ClientConnection velo$connection();
}
//?} else {
/*@Mixin(ClientCommonPacketListenerImpl.class)
public interface CommonListenerConnectionAccessor {
	@Accessor("connection")
	Connection velo$connection();
}
*///?}
