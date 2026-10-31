package net.veloclient.velo.client.privacy;

import net.minecraft.client.MinecraftClient;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Anti Coords Leak: items that pop out of broken blocks fly the way the server sends them, and on servers like 2b2t
 * that direction in a stream or screenshot can help track down a base. While coordinates are hidden, every new dropped
 * item gets a random horizontal direction on your screen (same speed). The client only simulates the flight, so the
 * server's next position update puts the item where it really is.
 */
public final class DropScrambler {

	private static final double OWN_DROP_RADIUS = 2.0;

	private static final Set<Integer> seen = new HashSet<>();
	private static final Random random = new Random();

	private DropScrambler() {
	}

	/** Once per client tick; {@code active} false forgets everything. Your own drops (thrown from your eyes) stay put. */
	public static void tick(boolean active) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!active || client.world == null || client.player == null) {
			seen.clear();
			return;
		}
		Set<Integer> alive = new HashSet<>();
		//? if <26.1 {
		Iterable<?> entities = client.world.getEntities();
		//?} else {
		/*Iterable<?> entities = client.level.entitiesForRendering();
		*///?}
		for (Object entity : entities) {
			//? if <26.1 {
			if (!(entity instanceof net.minecraft.entity.ItemEntity item)) {
				continue;
			}
			//?} else {
			/*if (!(entity instanceof net.minecraft.world.entity.item.ItemEntity item)) {
				continue;
			}
			*///?}
			int id = item.getId();
			alive.add(id);
			if (!seen.add(id)) {
				continue;
			}
			//? if <26.1 {
			int age = item.age;
			var eye = client.player.getEyePos();
			boolean own = item.getEntityPos().distanceTo(eye) < OWN_DROP_RADIUS;
			var velocity = item.getVelocity();
			//?} else {
			/*int age = item.tickCount;
			var eye = client.player.getEyePosition();
			boolean own = item.position().distanceTo(eye) < OWN_DROP_RADIUS;
			var velocity = item.getDeltaMovement();
			*///?}
			// Only fresh drops: items already lying around when you arrive keep their place.
			if (age > 3 || own) {
				continue;
			}
			double speed = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
			double angle = random.nextDouble() * Math.PI * 2;
			//? if <26.1 {
			item.setVelocity(Math.cos(angle) * speed, velocity.y, Math.sin(angle) * speed);
			//?} else {
			/*item.setDeltaMovement(Math.cos(angle) * speed, velocity.y, Math.sin(angle) * speed);
			*///?}
		}
		seen.retainAll(alive);
	}
}
