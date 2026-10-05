package net.veloclient.velo.client.stats;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Every statistic Minecraft's own Statistics screen shows - general (custom) stats, per-item and
 * per-mob - read from the numbers the server sends, as plain rows the Velo stats screen can sort
 * and search. All version-specific API lives here.
 */
public final class StatsData {

	/** A general stat: its id path (e.g. "play_time"), translated name, raw value and vanilla's formatted value. */
	public record General(String id, String name, int value, String formatted) {
	}

	public record ItemRow(ItemStack icon, String name, int mined, int crafted, int used, int broken, int pickedUp, int dropped) {
		public int total() {
			return mined + crafted + used + broken + pickedUp + dropped;
		}
	}

	public record MobRow(ItemStack icon, String name, int killed, int killedBy) {
	}

	public record Snapshot(List<General> general, List<ItemRow> items, List<MobRow> mobs) {
	}

	private StatsData() {
	}

	//? if <26.1 {
	public static void request(MinecraftClient client) {
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket(
					net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket.Mode.REQUEST_STATS));
		}
	}

	public static Snapshot read(MinecraftClient client) {
		var stats = client.player.getStatHandler();
		List<General> general = new ArrayList<>();
		for (var id : net.minecraft.registry.Registries.CUSTOM_STAT) {
			var stat = net.minecraft.stat.Stats.CUSTOM.getOrCreateStat(id);
			int value = stats.getStat(stat);
			general.add(new General(id.getPath(), net.minecraft.text.Text.translatable("stat." + id.toString().replace(':', '.')).getString(),
					value, stat.format(value)));
		}
		List<ItemRow> items = new ArrayList<>();
		for (var item : net.minecraft.registry.Registries.ITEM) {
			int mined = item instanceof net.minecraft.item.BlockItem block ? stats.getStat(net.minecraft.stat.Stats.MINED, block.getBlock()) : 0;
			int crafted = stats.getStat(net.minecraft.stat.Stats.CRAFTED, item);
			int used = stats.getStat(net.minecraft.stat.Stats.USED, item);
			int broken = stats.getStat(net.minecraft.stat.Stats.BROKEN, item);
			int picked = stats.getStat(net.minecraft.stat.Stats.PICKED_UP, item);
			int dropped = stats.getStat(net.minecraft.stat.Stats.DROPPED, item);
			if (mined + crafted + used + broken + picked + dropped > 0) {
				ItemStack stack = new ItemStack(item);
				items.add(new ItemRow(stack, stack.getName().getString(), mined, crafted, used, broken, picked, dropped));
			}
		}
		List<MobRow> mobs = new ArrayList<>();
		for (var type : net.minecraft.registry.Registries.ENTITY_TYPE) {
			int killed = stats.getStat(net.minecraft.stat.Stats.KILLED, type);
			int killedBy = stats.getStat(net.minecraft.stat.Stats.KILLED_BY, type);
			if (killed + killedBy > 0) {
				var egg = net.minecraft.item.SpawnEggItem.forEntity(type);
				mobs.add(new MobRow(egg != null ? new ItemStack(egg) : ItemStack.EMPTY, type.getName().getString(), killed, killedBy));
			}
		}
		return new Snapshot(general, items, mobs);
	}
	//?} else {
	/*public static void request(net.minecraft.client.Minecraft client) {
		if (client.getConnection() != null) {
			client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
					net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.REQUEST_STATS));
		}
	}

	public static Snapshot read(net.minecraft.client.Minecraft client) {
		var stats = client.player.getStats();
		List<General> general = new ArrayList<>();
		for (var id : net.minecraft.core.registries.BuiltInRegistries.CUSTOM_STAT) {
			var stat = net.minecraft.stats.Stats.CUSTOM.get(id);
			int value = stats.getValue(stat);
			general.add(new General(id.getPath(), net.minecraft.network.chat.Component.translatable("stat." + id.toString().replace(':', '.')).getString(),
					value, stat.format(value)));
		}
		List<ItemRow> items = new ArrayList<>();
		for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
			int mined = item instanceof net.minecraft.world.item.BlockItem block ? stats.getValue(net.minecraft.stats.Stats.BLOCK_MINED, block.getBlock()) : 0;
			int crafted = stats.getValue(net.minecraft.stats.Stats.ITEM_CRAFTED, item);
			int used = stats.getValue(net.minecraft.stats.Stats.ITEM_USED, item);
			int broken = stats.getValue(net.minecraft.stats.Stats.ITEM_BROKEN, item);
			int picked = stats.getValue(net.minecraft.stats.Stats.ITEM_PICKED_UP, item);
			int dropped = stats.getValue(net.minecraft.stats.Stats.ITEM_DROPPED, item);
			if (mined + crafted + used + broken + picked + dropped > 0) {
				net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item);
				items.add(new ItemRow(stack, stack.getHoverName().getString(), mined, crafted, used, broken, picked, dropped));
			}
		}
		List<MobRow> mobs = new ArrayList<>();
		for (var type : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE) {
			int killed = stats.getValue(net.minecraft.stats.Stats.ENTITY_KILLED, type);
			int killedBy = stats.getValue(net.minecraft.stats.Stats.ENTITY_KILLED_BY, type);
			if (killed + killedBy > 0) {
				net.minecraft.world.item.ItemStack icon = net.minecraft.world.item.SpawnEggItem.byId(type)
						.map(holder -> new net.minecraft.world.item.ItemStack(holder.value()))
						.orElse(net.minecraft.world.item.ItemStack.EMPTY);
				mobs.add(new MobRow(icon, type.getDescription().getString(), killed, killedBy));
			}
		}
		return new Snapshot(general, items, mobs);
	}
	*///?}
}
