package net.veloclient.velo.devbuild.rewind;

/** Velo Rewind: the few calls that differ between 26.2 and 26.3. */
//? if >=1.21.11 && <26.1 {
final class RewindCompat {

	private RewindCompat() {
	}

	// a private field by its yarn name (dev runs) or its intermediary name (the real game), made accessible
	static java.lang.reflect.Field field(Class<?> owner, String yarn, String intermediary) throws NoSuchFieldException {
		java.lang.reflect.Field f;
		try {
			f = owner.getDeclaredField(intermediary);
		} catch (NoSuchFieldException e) {
			f = owner.getDeclaredField(yarn);
		}
		f.setAccessible(true);
		return f;
	}

	static it.unimi.dsi.fastutil.ints.IntList removedIds(net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket p) {
		return p.getEntityIds();
	}

	static int chunkX(net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket c) {
		return c.getChunkX();
	}

	static int chunkZ(net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket c) {
		return c.getChunkZ();
	}

	static String openPng() {
		return org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Skin picture", "", null, "PNG", false);
	}

	// -1 = not swinging, 0 = main hand, 3 = off hand (the animate packet's ids)
	static net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo spectator(net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo i) {
		return new net.minecraft.network.packet.s2c.play.CommonPlayerSpawnInfo(i.dimensionType(), i.dimension(), i.seed(),
				net.minecraft.world.GameMode.SPECTATOR, net.minecraft.world.GameMode.SPECTATOR, i.isDebug(), i.isFlat(),
				i.lastDeathLocation(), i.portalCooldown(), i.seaLevel());
	}

	static int swing(net.minecraft.entity.LivingEntity e) {
		return !e.handSwinging ? -1 : e.preferredHand == net.minecraft.util.Hand.MAIN_HAND ? 0 : 3;
	}
}
//?} else if <26.2 {
/*final class RewindCompat {

	private RewindCompat() {
	}
}
*///?} else if <26.3 {
/*final class RewindCompat {

	private RewindCompat() {
	}

	static it.unimi.dsi.fastutil.ints.IntList removedIds(net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket p) {
		return p.getEntityIds();
	}

	static int chunkX(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket c) {
		return c.getX();
	}

	static int chunkZ(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket c) {
		return c.getZ();
	}

	static long dayTicks(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock> clock) {
		return level.clockManager().getTotalTicks(clock);
	}

	static String openPng() {
		return org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog("Skin picture", "", null, "PNG", false);
	}

	// -1 = not swinging, 0 = main hand, 3 = off hand (the animate packet's ids)
	static net.minecraft.network.protocol.game.CommonPlayerSpawnInfo spectator(net.minecraft.network.protocol.game.CommonPlayerSpawnInfo i) {
		return new net.minecraft.network.protocol.game.CommonPlayerSpawnInfo(i.dimensionType(), i.dimension(), i.seed(),
				net.minecraft.world.level.GameType.SPECTATOR, net.minecraft.world.level.GameType.SPECTATOR, i.isDebug(), i.isFlat(),
				i.lastDeathLocation(), i.portalCooldown(), i.seaLevel());
	}

	static int swing(net.minecraft.world.entity.LivingEntity e) {
		return !e.swinging ? -1 : e.swingingArm == net.minecraft.world.InteractionHand.MAIN_HAND ? 0 : 3;
	}
}
*///?} else {
/*final class RewindCompat {

	private RewindCompat() {
	}

	static net.minecraft.network.protocol.game.CommonPlayerSpawnInfo spectator(net.minecraft.network.protocol.game.CommonPlayerSpawnInfo i) {
		return new net.minecraft.network.protocol.game.CommonPlayerSpawnInfo(i.dimensionType(), i.dimension(), i.seed(),
				net.minecraft.world.level.GameType.SPECTATOR, java.util.Optional.of(net.minecraft.world.level.GameType.SPECTATOR), i.isDebug(), i.isFlat(),
				i.lastDeathLocation(), i.portalCooldown(), i.seaLevel());
	}

	static it.unimi.dsi.fastutil.ints.IntList removedIds(net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket p) {
		return p.entityIds();
	}

	static int chunkX(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket c) {
		return c.x();
	}

	static int chunkZ(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket c) {
		return c.z();
	}

	static long dayTicks(net.minecraft.client.multiplayer.ClientLevel level, net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock> clock) {
		return level.getOverworldClockTime();
	}

	// 26.3 has no tinyfd (SDL3): type the path instead
	static String openPng() {
		return null;
	}

	static int swing(net.minecraft.world.entity.LivingEntity e) {
		if (!e.isSwinging() || e.getCurrentSwing() == null) {
			return -1;
		}
		return e.getCurrentSwing().hand() == net.minecraft.world.InteractionHand.MAIN_HAND ? 0 : 3;
	}
}
*///?}
