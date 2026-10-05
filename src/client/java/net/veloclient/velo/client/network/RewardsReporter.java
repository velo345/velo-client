package net.veloclient.velo.client.network;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Feeds daily-quest progress from Minecraft's own statistics - the numbers the game SERVER keeps
 * (mob kills, blocks mined/placed, distance), the same ones the Statistics screen shows. Every two
 * minutes in a world it requests them, then reports how much each grew since the last snapshot.
 * The baseline resets whenever the world/server changes, so switching servers never counts as
 * progress. The Velo server caps every report to what's humanly possible and pays quests only
 * after it has seen enough real in-game time (see StoreService).
 */
public final class RewardsReporter {

	private static final int INTERVAL_TICKS = 20 * 120;
	private static final int READ_DELAY_TICKS = 60;

	private static int ticks;
	private static int readAt = -1;
	private static Object worldKey;
	private static long[] baseline;

	private RewardsReporter() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(RewardsReporter::tick);
	}

	private static void tick(MinecraftClient client) {
		if (client.player == null || client.world == null || VeloServerClient.sessionToken() == null) {
			worldKey = null;
			baseline = null;
			return;
		}
		if (worldKey != client.world) {
			worldKey = client.world;
			baseline = null;
			ticks = INTERVAL_TICKS - 200; // first snapshot ~10 s after joining
			readAt = -1;
		}
		ticks++;
		if (ticks >= INTERVAL_TICKS) {
			ticks = 0;
			requestStats(client);
			readAt = READ_DELAY_TICKS;
		}
		if (readAt >= 0 && --readAt == 0) {
			readAt = -1;
			long[] now = snapshot(client);
			if (baseline != null) {
				JsonObject deltas = new JsonObject();
				add(deltas, "mob_kills", now[0] - baseline[0]);
				add(deltas, "blocks_mined", now[1] - baseline[1]);
				add(deltas, "blocks_placed", now[2] - baseline[2]);
				add(deltas, "distance_m", (now[3] - baseline[3]) / 100);
				if (deltas.size() > 0) {
					StoreClient.reportProgress(deltas);
				}
			}
			baseline = now;
		}
	}

	private static void add(JsonObject json, String key, long value) {
		if (value > 0) {
			json.addProperty(key, value);
		}
	}

	//? if <26.1 {
	private static void requestStats(MinecraftClient client) {
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket(
					net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket.Mode.REQUEST_STATS));
		}
	}

	/** {mob kills, blocks mined, blocks placed, distance in cm}. */
	private static long[] snapshot(MinecraftClient client) {
		var stats = client.player.getStatHandler();
		long kills = stats.getStat(net.minecraft.stat.Stats.CUSTOM, net.minecraft.stat.Stats.MOB_KILLS);
		long mined = 0;
		for (var block : net.minecraft.registry.Registries.BLOCK) {
			mined += stats.getStat(net.minecraft.stat.Stats.MINED, block);
		}
		long placed = 0;
		for (var item : net.minecraft.registry.Registries.ITEM) {
			if (item instanceof net.minecraft.item.BlockItem) {
				placed += stats.getStat(net.minecraft.stat.Stats.USED, item);
			}
		}
		long distance = 0;
		for (var id : new net.minecraft.util.Identifier[] {net.minecraft.stat.Stats.WALK_ONE_CM, net.minecraft.stat.Stats.SPRINT_ONE_CM,
				net.minecraft.stat.Stats.SWIM_ONE_CM, net.minecraft.stat.Stats.CROUCH_ONE_CM, net.minecraft.stat.Stats.AVIATE_ONE_CM,
				net.minecraft.stat.Stats.BOAT_ONE_CM, net.minecraft.stat.Stats.HORSE_ONE_CM, net.minecraft.stat.Stats.WALK_ON_WATER_ONE_CM,
				net.minecraft.stat.Stats.WALK_UNDER_WATER_ONE_CM}) {
			distance += stats.getStat(net.minecraft.stat.Stats.CUSTOM, id);
		}
		return new long[] {kills, mined, placed, distance};
	}
	//?} else {
	/*private static void requestStats(net.minecraft.client.Minecraft client) {
		if (client.getConnection() != null) {
			client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
					net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.REQUEST_STATS));
		}
	}

	private static long[] snapshot(net.minecraft.client.Minecraft client) {
		var stats = client.player.getStats();
		long kills = stats.getValue(net.minecraft.stats.Stats.CUSTOM, net.minecraft.stats.Stats.MOB_KILLS);
		long mined = 0;
		for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
			mined += stats.getValue(net.minecraft.stats.Stats.BLOCK_MINED, block);
		}
		long placed = 0;
		for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			if (item instanceof net.minecraft.world.item.BlockItem) {
				placed += stats.getValue(net.minecraft.stats.Stats.ITEM_USED, item);
			}
		}
		long distance = 0;
		for (var id : new net.minecraft.resources.Identifier[] {net.minecraft.stats.Stats.WALK_ONE_CM, net.minecraft.stats.Stats.SPRINT_ONE_CM,
				net.minecraft.stats.Stats.SWIM_ONE_CM, net.minecraft.stats.Stats.CROUCH_ONE_CM, net.minecraft.stats.Stats.AVIATE_ONE_CM,
				net.minecraft.stats.Stats.BOAT_ONE_CM, net.minecraft.stats.Stats.HORSE_ONE_CM, net.minecraft.stats.Stats.WALK_ON_WATER_ONE_CM,
				net.minecraft.stats.Stats.WALK_UNDER_WATER_ONE_CM}) {
			distance += stats.getValue(net.minecraft.stats.Stats.CUSTOM, id);
		}
		return new long[] {kills, mined, placed, distance};
	}
	*///?}
}
