package net.veloclient.velo.client.cosmetics;

/**
 * Where a gaze cape should look: the nearest player standing behind its wearer, as a gaze on the cape's outside face
 * (x -1..1 = the viewer's left..right, y -1..1 = down..up), or null when nobody is behind. Kept apart from
 * {@link GazeCapes} because every call here differs between the Yarn (1.21.11) and Mojang (26.x) names.
 */
final class GazeTargets {

	private GazeTargets() {
	}

	/** Gaze from the wearer's back to a point {@code d} away (world axes), given the wearer's body yaw in degrees. */
	private static float[] gaze(double dx, double dy, double dz, float bodyYaw) {
		double yaw = Math.toRadians(bodyYaw);
		// Minecraft faces (-sin yaw, cos yaw); its back is the opposite, its left (seen from behind: the viewer's right) is (cos, sin).
		double backX = Math.sin(yaw), backZ = -Math.cos(yaw);
		double leftX = Math.cos(yaw), leftZ = Math.sin(yaw);
		double behind = dx * backX + dz * backZ;
		double side = dx * leftX + dz * leftZ;
		double flat = Math.sqrt(dx * dx + dz * dz);
		if (behind <= .25 * flat || flat < .3) {
			return null;
		}
		float gx = (float) (Math.atan2(side, behind) / (Math.PI / 3));
		float gy = (float) (Math.atan2(dy, flat) / (Math.PI / 4));
		return new float[] {Math.max(-1f, Math.min(1f, gx)), Math.max(-1f, Math.min(1f, gy))};
	}

	//? if <26.1 {
	static float[] find(int entityId, double range) {
		net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
		if (client.world == null) {
			return null;
		}
		net.minecraft.entity.Entity entity = client.world.getEntityById(entityId);
		if (!(entity instanceof net.minecraft.entity.player.PlayerEntity wearer)) {
			return null;
		}
		double cx = wearer.getX(), cy = wearer.getY() + 1.1, cz = wearer.getZ();
		float[] best = null;
		double bestDist = range * range;
		for (net.minecraft.entity.player.PlayerEntity p : client.world.getPlayers()) {
			if (p == wearer || p.isSpectator() || p.isInvisible()) {
				continue;
			}
			double dx = p.getX() - cx, dy = p.getEyeY() - cy, dz = p.getZ() - cz;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 < bestDist) {
				float[] g = gaze(dx, dy, dz, wearer.getBodyYaw());
				if (g != null) {
					best = g;
					bestDist = d2;
				}
			}
		}
		return best;
	}
	//?} else {
	/*static float[] find(int entityId, double range) {
		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		net.minecraft.world.entity.Entity entity = mc.level.getEntity(entityId);
		if (!(entity instanceof net.minecraft.world.entity.player.Player wearer)) {
			return null;
		}
		double cx = wearer.getX(), cy = wearer.getY() + 1.1, cz = wearer.getZ();
		float[] best = null;
		double bestDist = range * range;
		for (net.minecraft.world.entity.player.Player p : mc.level.players()) {
			if (p == wearer || p.isSpectator() || p.isInvisible()) {
				continue;
			}
			double dx = p.getX() - cx, dy = p.getEyeY() - cy, dz = p.getZ() - cz;
			double d2 = dx * dx + dy * dy + dz * dz;
			if (d2 < bestDist) {
				float[] g = gaze(dx, dy, dz, wearer.yBodyRot);
				if (g != null) {
					best = g;
					bestDist = d2;
				}
			}
		}
		return best;
	}
	*///?}
}
