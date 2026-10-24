package net.veloclient.velo.client.modules.rendering;

/**
 * Velo Shaders' sun shadow map (26.2, with Sodium): Sodium's chunk meshes are drawn a second time
 * from the sun (or moon) into this 2048x2048 depth map, through an orthographic camera centred on
 * the player - {@code VeloShadowSodiumMixin} runs that pass right before the frame's own terrain.
 * The light pass samples it (soft edges via PCF) for real, long shadows from trees and buildings,
 * and keeps its screen-space rays for fine contact shadows.
 *
 * <p>The ortho camera is snapped to whole shadow texels in the light's plane, so shadows don't
 * shimmer as the player walks. Matrices work in Sodium's camera-relative space.
 */
public final class VeloShadowMap {

	public static final int SIZE = 2048;
	/** Half-size of the shadowed area around the player, in blocks. */
	public static final float RADIUS = 96f;
	private static final float DEPTH = 256f;

	/** Inside the shadow pass: Sodium's draws go to the shadow map, its shader takes the cheap path. */
	public static volatile boolean rendering;

	// This frame's matrices (render thread).
	public static final org.joml.Matrix4f PROJECTION = new org.joml.Matrix4f();
	public static final org.joml.Matrix4f VIEW = new org.joml.Matrix4f();
	public static final org.joml.Matrix4f SHADOW = new org.joml.Matrix4f();
	public static boolean valid;
	/** Whether the shadow pass draws this frame (above 120 FPS it renders every other frame). */
	public static boolean renderThisFrame = true;
	private static long frames;
	private static double mapCamX;
	private static double mapCamY;
	private static double mapCamZ;
	// SHADOW shifted by the camera's movement since the map was drawn (what the light pass uses).
	public static final org.joml.Matrix4f SHADER = new org.joml.Matrix4f();

	// The near map: entities only (you, your cosmetics, mobs close by), drawn every frame at about
	// 4x the main map's detail so player shadows come out hard and in shape.
	public static final int NEAR_SIZE = 1024;
	public static final float NEAR_RADIUS = 12f;
	public static final org.joml.Matrix4f NEAR_PROJECTION = new org.joml.Matrix4f();
	public static final org.joml.Matrix4f NEAR_VIEW = new org.joml.Matrix4f();
	public static final org.joml.Matrix4f NEAR_SHADOW = new org.joml.Matrix4f();

	//? if >=26.2 {
	/*public static final net.minecraft.resources.Identifier TEXTURE_ID =
			net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "textures/effect/shadow_map.png");
	private static com.mojang.blaze3d.pipeline.TextureTarget target;
	private static final ShadowTexture TEXTURE = new ShadowTexture();
	public static final net.minecraft.resources.Identifier NEAR_TEXTURE_ID =
			net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "textures/effect/shadow_near.png");
	private static com.mojang.blaze3d.pipeline.TextureTarget nearTarget;
	private static final ShadowTexture NEAR_TEXTURE = new ShadowTexture();

	// Lets the post chain sample the depth texture by name (PostChainConfig texture input).
	private static final class ShadowTexture extends net.minecraft.client.renderer.texture.AbstractTexture {
		void set(com.mojang.blaze3d.pipeline.TextureTarget t) {
			this.texture = t.getDepthTexture();
			this.textureView = t.getDepthTextureView();
		}
	}

	// Creates the shadow map (render thread) and registers it as TEXTURE_ID.
	public static com.mojang.blaze3d.pipeline.TextureTarget target() {
		if (target == null) {
			target = VeloShaderCompat.newTarget("Velo shadow map", SIZE);
			TEXTURE.set(target);
			net.minecraft.client.Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, TEXTURE);
		}
		return target;
	}

	// The entity near map, registered as NEAR_TEXTURE_ID.
	public static com.mojang.blaze3d.pipeline.TextureTarget nearTarget() {
		if (nearTarget == null) {
			nearTarget = VeloShaderCompat.newTarget("Velo near shadow map", NEAR_SIZE);
			NEAR_TEXTURE.set(nearTarget);
			net.minecraft.client.Minecraft.getInstance().getTextureManager().register(NEAR_TEXTURE_ID, NEAR_TEXTURE);
		}
		return nearTarget;
	}
	*///?}

	/**
	 * Builds this frame's sun camera around the player. {@code lightDir}: direction to the light
	 * (world); camera position for texel snapping. False when the light is too low to cast.
	 */
	public static boolean update(float lx, float ly, float lz, double camX, double camY, double camZ, boolean zZeroToOne, int fps) {
		if (ly < 0.05f) {
			valid = false;
			return false;
		}
		frames++;
		org.joml.Vector3f dir = new org.joml.Vector3f(lx, ly, lz).normalize();
		// The sun crosses the sky in the X/Y plane, so Z is always a usable "up".
		org.joml.Vector3f up = new org.joml.Vector3f(0f, 0f, 1f);
		org.joml.Vector3f right = new org.joml.Vector3f(up).cross(dir).normalize();
		org.joml.Vector3f upOrtho = new org.joml.Vector3f(dir).cross(right).normalize();
		build(NEAR_VIEW, NEAR_PROJECTION, NEAR_RADIUS, NEAR_SIZE, dir, right, upOrtho, camX, camY, camZ, zZeroToOne);
		NEAR_SHADOW.set(NEAR_PROJECTION).mul(NEAR_VIEW);
		renderThisFrame = !valid || fps < 120 || (frames & 1) == 0;
		if (!renderThisFrame) {
			// Reuse last frame's map: shift its matrix by how far the camera moved (camera-relative space).
			SHADER.set(SHADOW).translate((float) (camX - mapCamX), (float) (camY - mapCamY), (float) (camZ - mapCamZ));
			return true;
		}
		mapCamX = camX;
		mapCamY = camY;
		mapCamZ = camZ;
		build(VIEW, PROJECTION, RADIUS, SIZE, dir, right, upOrtho, camX, camY, camZ, zZeroToOne);
		SHADOW.set(PROJECTION).mul(VIEW);
		SHADER.set(SHADOW);
		valid = true;
		return true;
	}

	// One sun camera around the player: radius blocks each way, size texels, centre snapped to whole texels.
	private static void build(org.joml.Matrix4f view, org.joml.Matrix4f projection, float radius, int size, org.joml.Vector3f dir,
			org.joml.Vector3f right, org.joml.Vector3f upOrtho, double camX, double camY, double camZ, boolean zZeroToOne) {
		// Snap the centre to whole texels in the light's plane (world-anchored, so no shimmer).
		double texel = 2.0 * radius / size;
		double pr = camX * right.x + camY * right.y + camZ * right.z;
		double pu = camX * upOrtho.x + camY * upOrtho.y + camZ * upOrtho.z;
		float dr = (float) (Math.floor(pr / texel) * texel - pr);
		float du = (float) (Math.floor(pu / texel) * texel - pu);
		org.joml.Vector3f center = new org.joml.Vector3f(right).mul(dr).add(new org.joml.Vector3f(upOrtho).mul(du));
		org.joml.Vector3f eye = new org.joml.Vector3f(dir).mul(DEPTH * 0.5f).add(center);
		view.identity().lookAt(eye, center, upOrtho);
		// Minecraft 26.2 renders with reversed Z (near = 1, far = 0, depth test GREATER_OR_EQUAL, cleared
		// to 0) and Sodium's pipeline uses that depth state: build the ortho the same way.
		projection.identity().ortho(-radius, radius, -radius, radius, 0f, DEPTH, zZeroToOne);
		if (zZeroToOne) {
			projection.set(new org.joml.Matrix4f().translate(0f, 0f, 1f).scale(1f, 1f, -1f).mul(projection));
		} else {
			projection.set(new org.joml.Matrix4f().scale(1f, 1f, -1f).mul(projection));
		}
	}

	private VeloShadowMap() {
	}
}
