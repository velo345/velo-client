package net.veloclient.velo.addons.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.veloclient.velo.client.util.VehicleLagSimulator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if <26.1 {
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityPassengersSetS2CPacket;
import net.minecraft.util.math.Vec3d;
//?} else {
/*import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.phys.Vec3;
*///?}

/**
 * Owns every VehicleMoveC2SPacket that leaves the connection while the Fly Boat lag
 * test ({@link VehicleLagSimulator}) is active, instead of FlyBoatModule additionally
 * sending its own packets on top of vanilla's own automatic per-tick send
 * (ClientPlayerEntity#tick always sends one VehicleMoveC2SPacket per tick while riding,
 * on its own - confirmed by decompiling it) - that doubling is why anticheats with a
 * basic per-tick packet-count check kicked instantly regardless of position or fall
 * rate. This intercepts vanilla's one real packet and either lets a decoy through in
 * its place (buffering window) or bursts the buffered real packets in one go (window
 * end), so exactly one VehicleMoveC2SPacket goes out per tick, same as an unmodified
 * client, except during the deliberate burst.
 */
@Mixin(ClientConnection.class)
public abstract class FlyBoatVehicleMoveMixin {

	@Unique
	private static boolean velo$bypass;

	@Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
	private void velo$interceptVehicleMove(Packet<?> packet, CallbackInfo ci) {
		if (velo$bypass || !VehicleLagSimulator.isActive()) return;

		//? if <26.1 {
		if (!(packet instanceof VehicleMoveC2SPacket p)) return;
		//?} else {
		/*if (!(packet instanceof ServerboundMoveVehiclePacket p)) return;
		*///?}

		ClientConnection self = (ClientConnection) (Object) this;
		VehicleLagSimulator.queue(p);

		velo$bypass = true;
		try {
			if (VehicleLagSimulator.isBurstTick()) {
				for (Object queued : VehicleLagSimulator.drainForBurst()) {
					self.send((Packet<?>) queued);
				}
			} else {
				boolean grounded = p.onGround();
				var decoy = VehicleLagSimulator.nextDecoyPosition(p.position().x, p.position().y, p.position().z, grounded);
				//? if <26.1 {
				self.send(new VehicleMoveC2SPacket(new Vec3d(decoy.x(), decoy.y(), decoy.z()), p.yaw(), p.pitch(), grounded));
				//?} else {
				/*self.send(new ServerboundMoveVehiclePacket(new Vec3(decoy.x(), decoy.y(), decoy.z()), p.yRot(), p.xRot(), grounded));
				*///?}
			}
		} finally {
			velo$bypass = false;
		}

		ci.cancel();
	}

	@Inject(method = "channelRead0", at = @At("HEAD"))
	private void velo$onPacketReceived(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
		if (!VehicleLagSimulator.isActive()) return;

		//? if <26.1 {
		if (packet instanceof EntityPassengersSetS2CPacket p) {
			velo$checkForDismount(p.getEntityId(), p.getPassengerIds());
		}
		//?} else {
		/*if (packet instanceof ClientboundSetPassengersPacket p) {
			velo$checkForDismount(p.getVehicle(), p.getPassengers());
		}
		*///?}
	}

	@Unique
	private static void velo$checkForDismount(int vehicleEntityId, int[] passengerIds) {
		var player = MinecraftClient.getInstance().player;
		if (player == null) return;
		var vehicle = player.getVehicle();
		if (vehicle == null || vehicleEntityId != vehicle.getId()) return;

		for (int id : passengerIds) {
			if (id == player.getId()) return;
		}

		VehicleLagSimulator.setActive(false);
	}
}
