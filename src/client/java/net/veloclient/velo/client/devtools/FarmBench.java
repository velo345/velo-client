package net.veloclient.velo.client.devtools;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Dev-only ({@code -Dvelo.benchFarm=on|off}, singleplayer): a flickering redstone farm for profiling. Three seconds after
 * joining it builds 120 cells above the player through the integrated server - an observer clock (two observers looking
 * at each other) feeding a line of redstone dust, whose floor powers 6 pistons and 6 dispensers, about 5 times a second -
 * points the camera at it and turns Anti Farm Lag on or off. Frame stats come from MoveBench's window log.
 * It changes the world: run it on a copy.
 */
public final class FarmBench {

	private static int ticks;

	private FarmBench() {
	}

	public static void register() {
		String mode = System.getProperty("velo.benchFarm");
		if (mode == null) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			//? if >=26.2 {
			/*var player = client.player;
			if (player == null) {
				return;
			}
			ticks++;
			if (ticks > 60) {
				player.getAbilities().flying = true;
				player.setXRot(-40f);
			}
			if (ticks % 200 == 0) {
				var running = client.getSingleplayerServer();
				net.veloclient.velo.VeloClient.LOGGER.info("Velo farm bench: farm mode active = {}, server tick {} ms",
						net.veloclient.velo.client.modules.performance.AntiFarmLagModule.active(),
						running == null ? -1f : running.getCurrentSmoothedTickTime());
			}
			if (ticks == 200) {
				MoveBench.recordOnly();
			}
			if (ticks != 60) {
				return;
			}
			net.veloclient.velo.module.ModuleRegistry.get("anti-farm-lag").ifPresent(m -> m.setEnabled(mode.equals("on")));
			var server = client.getSingleplayerServer();
			if (server == null) {
				return;
			}
			var dimension = player.level().dimension();
			int px = player.getBlockX();
			int py = player.getBlockY() + 8;
			int pz = player.getBlockZ();
			server.execute(() -> {
				var level = server.getLevel(dimension);
				var blocks = new Object() {
					void set(int x, int y, int z, net.minecraft.world.level.block.state.BlockState state) {
						level.setBlock(new net.minecraft.core.BlockPos(x, y, z), state, 3);
					}
				};
				var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
				var dust = net.minecraft.world.level.block.Blocks.REDSTONE_WIRE.defaultBlockState();
				var piston = net.minecraft.world.level.block.Blocks.PISTON.defaultBlockState()
						.setValue(net.minecraft.world.level.block.piston.PistonBaseBlock.FACING, net.minecraft.core.Direction.SOUTH);
				var dispenser = net.minecraft.world.level.block.Blocks.DISPENSER.defaultBlockState()
						.setValue(net.minecraft.world.level.block.DispenserBlock.FACING, net.minecraft.core.Direction.NORTH);
				var east = net.minecraft.world.level.block.Blocks.OBSERVER.defaultBlockState()
						.setValue(net.minecraft.world.level.block.ObserverBlock.FACING, net.minecraft.core.Direction.EAST);
				var west = net.minecraft.world.level.block.Blocks.OBSERVER.defaultBlockState()
						.setValue(net.minecraft.world.level.block.ObserverBlock.FACING, net.minecraft.core.Direction.WEST);
				for (int cx = -5; cx < 5; cx++) {
					for (int cz = -6; cz < 6; cz++) {
						int x = px + cx * 9;
						int z = pz + cz * 6;
						for (int i = 2; i < 8; i++) {
							blocks.set(x + i, py - 1, z, stone);
							blocks.set(x + i, py - 1, z + 1, piston);
							blocks.set(x + i, py - 1, z + 2, stone);
							blocks.set(x + i, py - 1, z - 1, dispenser);
							blocks.set(x + i, py, z, dust);
						}
						blocks.set(x, py - 1, z, stone);
						blocks.set(x + 1, py - 1, z, stone);
						blocks.set(x, py, z, east);
						blocks.set(x + 1, py, z, west);
					}
				}
			});
			net.veloclient.velo.VeloClient.LOGGER.info("Velo farm bench: 120 cells placed, Anti Farm Lag {}", mode);
			*///?}
		});
	}
}
