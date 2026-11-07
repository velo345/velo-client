package net.veloclient.velo.client.modules.performance;

/**
 * Velo Turbo entity visibility cache (26.2+): each entity remembers its last frustum / culling answer together with
 * the camera and its own position at that moment. Vanilla asks every entity again every frame (renderer lookup,
 * distance, frustum box, Sodium's section check, EntityCulling) - 14% of a standing frame on the bench. When neither
 * the camera nor the entity moved and the answer is younger than 5 ms, it is reused: at high FPS nearly every check,
 * at low FPS none (each frame is older than 5 ms), and anything else that can change visibility (invisibility, a block
 * placed in between, zoom) shows within 5 ms. Implemented on Entity by TurboEntityVisFieldsMixin.
 */
public interface EntityVisCache {

	/** How long an answer may be reused while nothing moved. */
	long MAX_AGE_NANOS = 5_000_000L;

	long velo$visTime();

	boolean velo$visMatches(double camX, double camY, double camZ, float xRot, float yRot, float fov, double x, double y, double z);

	boolean velo$visResult();

	void velo$visStore(long time, double camX, double camY, double camZ, float xRot, float yRot, float fov, double x, double y, double z,
			boolean result);
}
