package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Dev-only ({@code -Dvelo.benchItems=on|off}, singleplayer): an item farm's output for profiling. After joining it lays a
 * 48 x 48 stone floor below the player and rains dropped items onto it - 25 a tick in 12 kinds, each removed again after
 * 6 seconds, so about 3000 items are always falling, bouncing or lying around - points the camera at it and turns Anti
 * Farm Lag on or off. Frame stats come from MoveBench's window log. It changes the world: run it on a copy.
 */
public final class ItemBench {

	private static int ticks;
	/** Items spawned, oldest first (server thread only), each removed 120 ticks later. */
	private static final java.util.ArrayDeque<Object[]> SPAWNED = new java.util.ArrayDeque<>();

	private ItemBench() {
	}

	public static void register() {
		String mode = System.getProperty("velo.benchItems");
		if (mode == null) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			//? if >=26.2 {
			/*var player = client.player;
			var server = client.getSingleplayerServer();
			if (player == null || server == null) {
				return;
			}
			ticks++;
			if (ticks > 60) {
				player.getAbilities().flying = true;
				player.setXRot(45f);   // looking down onto the floor full of items
			}
			if (ticks == 200) {
				MoveBench.recordOnly();
			}
			if (ticks % 200 == 0) {
				net.veloclient.velo.VeloClient.LOGGER.info("Velo item bench: entities {}, item farm mode {}, item frames {}, slots {}",
						client.level.getEntityCount(), net.veloclient.velo.client.modules.performance.AntiFarmLagModule.itemFarm(),
						net.veloclient.velo.client.modules.performance.AntiFarmLagModule.itemFrames,
						net.veloclient.velo.client.modules.performance.AntiFarmLagModule.itemSlotsUsed());
			}
			if (ticks < 60) {
				return;
			}
			var dimension = player.level().dimension();
			int px = player.getBlockX();
			int py = player.getBlockY() - 7;   // the floor below the camera, items rain onto it from above
			int pz = player.getBlockZ();
			boolean build = ticks == 60;
			if (build) {
				net.veloclient.velo.module.ModuleRegistry.get("anti-farm-lag").ifPresent(m -> {
					m.setEnabled(mode.equals("on"));
					// -Dvelo.benchLabelStyle=<background>,<size>,<font>: try a count label look without clicking through settings
					String style = System.getProperty("velo.benchLabelStyle");
					if (style != null && m instanceof net.veloclient.velo.module.Configurable c) {
						String[] parts = style.split(",");
						for (var field : c.configFields()) {
							if (field instanceof net.veloclient.velo.module.ConfigField.ChoiceField choice && choice.label().equals("Label Background")) {
								choice.set().accept(parts[0]);
							} else if (field instanceof net.veloclient.velo.module.ConfigField.SliderField slider && slider.label().equals("Label Size")
									&& parts.length > 1) {
								slider.set().accept(Double.parseDouble(parts[1]));
							} else if (field instanceof net.veloclient.velo.module.ConfigField.ChoiceField choice && choice.label().equals("Label Font")
									&& parts.length > 2) {
								choice.set().accept(parts[2]);
							}
						}
					}
				});
			}
			server.execute(() -> {
				var level = server.getLevel(dimension);
				if (build) {
					var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
					for (int x = -24; x < 24; x++) {
						for (int z = -24; z < 24; z++) {
							level.setBlock(new net.minecraft.core.BlockPos(px + x, py, pz + z), stone, 2);
						}
					}
					// water sources every 8 blocks: the floor becomes flowing water and the items drift in streams, like a farm
					var water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
					for (int x = -20; x <= 20; x += 8) {
						for (int z = -20; z <= 20; z += 8) {
							level.setBlock(new net.minecraft.core.BlockPos(px + x, py + 1, pz + z), water, 3);
						}
					}
				}
				int now = ticks;
				while (!SPAWNED.isEmpty() && now - (Integer) SPAWNED.peekFirst()[1] > 120) {
					((net.minecraft.world.entity.Entity) SPAWNED.pollFirst()[0]).discard();
				}
				var random = level.getRandom();
				var kinds = new net.minecraft.world.item.Item[] {net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.ROTTEN_FLESH,
						net.minecraft.world.item.Items.BONE, net.minecraft.world.item.Items.ARROW, net.minecraft.world.item.Items.STRING,
						net.minecraft.world.item.Items.GUNPOWDER, net.minecraft.world.item.Items.IRON_INGOT, net.minecraft.world.item.Items.POPPY,
						net.minecraft.world.item.Items.SPIDER_EYE, net.minecraft.world.item.Items.SLIME_BALL, net.minecraft.world.item.Items.KELP,
						net.minecraft.world.item.Items.BAMBOO};
				for (int i = 0; i < 25; i++) {
					var stack = new net.minecraft.world.item.ItemStack(kinds[random.nextInt(kinds.length)], 1 + random.nextInt(3));
					var item = new net.minecraft.world.entity.item.ItemEntity(level, px - 22 + random.nextDouble() * 44, py + 6,
							pz - 22 + random.nextDouble() * 44, stack);
					item.setPickUpDelay(32767);
					level.addFreshEntity(item);
					SPAWNED.addLast(new Object[] {item, now});
				}
			});
			*///?}
		});
	}
}
