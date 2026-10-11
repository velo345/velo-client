package net.veloclient.velo.client.modules.pvp;

/** Key state and the local-only arm swing used by {@link OldAnimationsModule}. */
final class OldAnimationsInput {

	private OldAnimationsInput() {
	}

	static boolean attackDown() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().options.attackKey.isPressed();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().options.keyAttack.isDown();
		*///?}
	}

	static boolean useDown() {
		//? if <26.1 {
		return net.minecraft.client.MinecraftClient.getInstance().options.useKey.isPressed();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().options.keyUse.isDown();
		*///?}
	}

	/** Starts the arm swing animation on your screen only - never sends a swing packet. */
	static void localSwing() {
		//? if <26.1 {
		var player = net.minecraft.client.MinecraftClient.getInstance().player;
		if (player != null) {
			((net.minecraft.entity.LivingEntity) player).swingHand(net.minecraft.util.Hand.MAIN_HAND, false);
		}
		//?} else if <26.3 {
		/*var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null) {
			((net.minecraft.world.entity.LivingEntity) player).swing(net.minecraft.world.InteractionHand.MAIN_HAND, false);
		}
		*///?} else {
		/*var player = net.minecraft.client.Minecraft.getInstance().player;
		if (player != null) {
			player.swing(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
		}
		*///?}
	}
}
