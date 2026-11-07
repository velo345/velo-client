package net.veloclient.velo.client.modules.performance;

/**
 * Velo Turbo deferred sky (26.2 Vulkan): the overworld sky is drawn after the opaque world instead of before it.
 *
 * <p>Vanilla paints the sky disc, the sunrise fan, sun, moon and stars over the whole screen first and then draws the
 * terrain over most of it - every covered sky pixel is wasted GPU work (~25 us/frame on the bench, more than the
 * clouds). Here the sky pass is held back and run inside the main pass right after opaque terrain and solid entities,
 * before anything translucent (water, clouds, particles still blend over the finished sky). For that run the
 * projection's z row is zeroed, so every sky fragment sits exactly on the far plane (reversed Z: depth 0), and the sky
 * pipelines get twins that test depth >= stored without writing it: only pixels nothing was drawn on become sky.
 * Colors and fog come from positions, not depth, so the result looks the same. Off with Velo Shaders and in the End.
 *
 * <p>Opt-in ({@code -Dvelo.deferredSky=true}): slower on the bench scene - kept for scenes with little visible sky.
 */
public final class DeferredSky {

	/** True while the held-back sky runs: SkyRenderer's pipelines are swapped for the depth-tested twins. */
	public static boolean drawing;

	private DeferredSky() {
	}

	public static boolean enabled() {
		// Opt-in: measured 2026-11-07 (Chalet, mostly sky in view) 2993 vs 3124 FPS without - the extra projection upload and
		// the depth-tested sky cost more than the covered sky pixels saved there.
		return PerformanceBoostModule.turbo && Boolean.getBoolean("velo.deferredSky")
				&& !net.veloclient.velo.client.modules.rendering.VeloShadersModule.active();
	}

	//? if >=26.2 && <26.3 {
	/*private static final java.util.Map<com.mojang.blaze3d.pipeline.RenderPipeline, com.mojang.blaze3d.pipeline.RenderPipeline> TWINS =
			new java.util.IdentityHashMap<>();
	private static net.minecraft.client.renderer.ProjectionMatrixBuffer farPlane;
	private static final org.joml.Matrix4f MATRIX = new org.joml.Matrix4f();

	// Runs the held-back sky with every fragment on the far plane.
	public static void run(org.joml.Matrix4f projection, Runnable sky) {
		if (farPlane == null) {
			farPlane = new net.minecraft.client.renderer.ProjectionMatrixBuffer("velo far-plane sky");
		}
		MATRIX.set(projection);
		// z_clip = 0 for every vertex -> depth 0 = the far plane with reversed Z; x, y and w are untouched.
		MATRIX.m02(0f).m12(0f).m22(0f).m32(0f);
		var type = com.mojang.blaze3d.systems.RenderSystem.getProjectionType();
		com.mojang.blaze3d.systems.RenderSystem.backupProjectionMatrix();
		com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(farPlane.getBuffer(MATRIX), type);
		drawing = true;
		try {
			sky.run();
		} finally {
			drawing = false;
			com.mojang.blaze3d.systems.RenderSystem.restoreProjectionMatrix();
		}
	}

	// The same pipeline with a depth test that only passes where nothing was drawn (depth still the cleared 0).
	public static com.mojang.blaze3d.pipeline.RenderPipeline twin(com.mojang.blaze3d.pipeline.RenderPipeline p) {
		if (!drawing) {
			return p;
		}
		return TWINS.computeIfAbsent(p, DeferredSky::build);
	}

	private static com.mojang.blaze3d.pipeline.RenderPipeline build(com.mojang.blaze3d.pipeline.RenderPipeline p) {
		var b = com.mojang.blaze3d.pipeline.RenderPipeline.builder()
				.withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "far_sky/" + p.getLocation().getPath().replace('/', '_')))
				.withVertexShader(p.getVertexShader())
				.withFragmentShader(p.getFragmentShader())
				.withPolygonMode(p.getPolygonMode())
				.withCull(p.isCull())
				.withPrimitiveTopology(p.getPrimitiveTopology())
				.withDepthStencilState(new com.mojang.blaze3d.pipeline.DepthStencilState(com.mojang.blaze3d.platform.CompareOp.GREATER_THAN_OR_EQUAL, false));
		for (var layout : p.getBindGroupLayouts()) {
			b.withBindGroupLayout(layout);
		}
		var targets = p.getColorTargetStates();
		for (int i = 0; i < targets.length; i++) {
			if (targets[i] != null) {
				b.withColorTargetState(i, targets[i]);
			} else {
				b.withUnusedColorTargetState(i);
			}
		}
		var formats = p.getVertexFormatBindings();
		for (int i = 0; i < formats.length; i++) {
			if (formats[i] != null) {
				b.withVertexBinding(i, formats[i]);
			}
		}
		var defines = p.getShaderDefines();
		defines.values().forEach((k, v) -> {
			if (v.matches("-?\\d+")) {
				b.withShaderDefine(k, Integer.parseInt(v));
			} else {
				b.withShaderDefine(k, Float.parseFloat(v));
			}
		});
		defines.flags().forEach(b::withShaderDefine);
		return b.build();
	}
	*///?}
}
