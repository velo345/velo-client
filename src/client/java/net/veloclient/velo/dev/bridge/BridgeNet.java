package net.veloclient.velo.dev.bridge;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The {@code velo:bridge} plugin channel: raw UTF-8 JSON both ways (exactly what a Bukkit plugin's sendPluginMessage
 * writes - no length prefix). Registering the receiver also makes Fabric announce the channel on join, which is how
 * a server can tell a player uses Velo before the hello even arrives.
 */
final class BridgeNet {

	private BridgeNet() {
	}

	//? if <26.1 {
	record Payload(byte[] data) implements net.minecraft.network.packet.CustomPayload {
		static final Id<Payload> ID = new Id<>(net.minecraft.util.Identifier.of("velo", "bridge"));
		static final net.minecraft.network.codec.PacketCodec<net.minecraft.network.RegistryByteBuf, Payload> CODEC =
				net.minecraft.network.codec.PacketCodec.ofStatic((buf, p) -> buf.writeBytes(p.data()), buf -> {
					byte[] bytes = new byte[buf.readableBytes()];
					buf.readBytes(bytes);
					return new Payload(bytes);
				});

		@Override
		public Id<? extends net.minecraft.network.packet.CustomPayload> getId() {
			return ID;
		}
	}

	static void init(Consumer<String> onMessage) {
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(Payload.ID, Payload.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(Payload.ID, Payload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> onMessage.accept(new String(payload.data(), StandardCharsets.UTF_8)));
	}

	static boolean canSend() {
		return ClientPlayNetworking.canSend(Payload.ID);
	}
	//?} else {
	/*record Payload(byte[] data) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
		static final Type<Payload> ID = new Type<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo", "bridge"));
		static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Payload> CODEC =
				net.minecraft.network.codec.StreamCodec.of((buf, p) -> buf.writeBytes(p.data()), buf -> {
					byte[] bytes = new byte[buf.readableBytes()];
					buf.readBytes(bytes);
					return new Payload(bytes);
				});

		@Override
		public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
			return ID;
		}
	}

	static void init(Consumer<String> onMessage) {
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay().register(Payload.ID, Payload.CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay().register(Payload.ID, Payload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(Payload.ID, (payload, context) -> onMessage.accept(new String(payload.data(), StandardCharsets.UTF_8)));
	}

	static boolean canSend() {
		return ClientPlayNetworking.canSend(Payload.ID);
	}
	*///?}

	static void send(String json) {
		if (canSend()) {
			ClientPlayNetworking.send(new Payload(json.getBytes(StandardCharsets.UTF_8)));
		}
	}
}
