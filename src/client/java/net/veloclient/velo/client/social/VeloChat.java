package net.veloclient.velo.client.social;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.pvp.PvpCompat;

/** One-line replies from Velo's own commands, shown in chat only for you ("[Velo] ..."). */
public final class VeloChat {

	private static final int TAG = 0xFFFF5A5A;
	private static final int TEXT = 0xFFE8E8F0;
	private static final int ERROR = 0xFFFF8A8A;

	private VeloChat() {
	}

	public static void info(String message) {
		show(message, TEXT);
	}

	public static void error(String message) {
		show(message, ERROR);
	}

	private static void show(String message, int color) {
		MinecraftClient.getInstance().execute(() -> {
			//? if <26.1 {
			var player = MinecraftClient.getInstance().player;
			if (player != null) {
				player.sendMessage(PvpCompat.literal("[Velo] ", TAG).append(PvpCompat.literal(message, color)), false);
			}
			//?} else {
			/*var player = net.minecraft.client.Minecraft.getInstance().player;
			if (player != null) {
				player.sendSystemMessage(PvpCompat.literal("[Velo] ", TAG).append(PvpCompat.literal(message, color)));
			}
			*///?}
		});
	}
}
