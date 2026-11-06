package net.veloclient.velo.client.cosmetics.render;

/**
 * Cosmetic meshes kept on the GPU (26.2): uploaded once, then drawn every frame with only a matrix, the light and the
 * hurt overlay - instead of re-posing and re-writing every vertex on the CPU each frame.
 *
 * <p>Why: in F5 the cosmetics took about half of the render thread (JFR, 2026-11-06: wings 22 %, bodywear 16 %; most
 * of it {@link CosmeticVertexWriter} transforming and writing ~8,000 vertices per item per frame) - ~760 FPS instead of
 * ~1,440 without them, with the GPU mostly idle. Here a bodywear item costs one small uniform write and one draw.
 *
 * <p>How: the feature renderers still submit through vanilla's {@code submitCustomGeometry}; in the callback (vanilla's
 * prepare stage) {@link #record} notes the draw instead of writing vertices, and {@link #flush} (GpuCosmeticsMixin,
 * right after vanilla draws the custom feature groups) draws them through {@code velo-client:pipeline/cosmetic_cutout} -
 * vanilla's entity_cutout pipeline with {@code velo-client:core/cosmetic.vsh}, which applies the pose on the GPU (see the
 * shader) and keeps vanilla's fragment shader, so it looks the same. Offscreen passes (Velo Shaders' shadow map, GUI
 * previews: a render target override is set) and anything that fails keep the CPU path; one failure turns the GPU path
 * off for the session.
 */
public final class GpuCosmetics {

	private GpuCosmetics() {
	}

	//? if >=26.2 && <26.3 {
	/*private static final int STRIDE = 36; // DefaultVertexFormat.ENTITY
	private static volatile boolean failed = Boolean.getBoolean("velo.cpuCosmetics");
	private static com.mojang.blaze3d.pipeline.RenderPipeline pipeline;

	private record GpuMesh(com.mojang.blaze3d.buffers.GpuBuffer vertices, int quadCount) {
	}

	private record Draw(GpuMesh mesh, net.minecraft.client.renderer.rendertype.RenderType type, org.joml.Matrix4f pose, int light, int overlay,
			int argb, Runnable upload) {
	}

	// A mesh whose shape changes (wings: re-posed 75 times a second): one buffer per wearer and part, rewritten only when
	// the shape object changes - each frame in between costs just the matrix.
	private static final class Dynamic {
		com.mojang.blaze3d.buffers.GpuBuffer buffer;
		int quadCount;
		Object version;
	}

	private static final java.util.LinkedHashMap<Object, Dynamic> DYNAMIC = new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<Object, Dynamic> eldest) {
			if (size() > 96) {
				if (eldest.getValue().buffer != null) {
					eldest.getValue().buffer.close();
				}
				return true;
			}
			return false;
		}
	};

	private record MeshKey(Object mesh, float squeeze) {
	}

	private static final java.util.Map<MeshKey, GpuMesh> MESHES = new java.util.HashMap<>();
	private static final java.util.List<Draw> PENDING = new java.util.ArrayList<>();

	// Notes a draw of a bodywear-style mesh (8 floats per vertex: position, uv, normal; {@code quads} = corner indices) for
	// this frame. False when it can't go through the GPU path - then the caller writes the vertices as before.
	public static boolean record(Object meshIdentity, int[] quads, float[] data, float scale, float squeeze,
			net.minecraft.client.renderer.rendertype.RenderType type, org.joml.Matrix4f pose, int light, int overlay) {
		if (failed || quads.length == 0 || quads.length % 4 != 0 || offscreen()) {
			return false;
		}
		try {
			MeshKey key = new MeshKey(meshIdentity, squeeze);
			GpuMesh mesh = MESHES.get(key);
			if (mesh == null) {
				mesh = upload(quads, data, scale, squeeze);
				MESHES.put(key, mesh);
			}
			PENDING.add(new Draw(mesh, type, new org.joml.Matrix4f(pose), light, overlay, 0xFFFFFFFF, null));
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	// A changing mesh (wings): {@code version} is the shape object - a new one means new vertices. Positions/normals come
	// from {@code data} (stride, offsets), uv from {@code uv}; {@code argb} tints the whole mesh (recoloured pairs).
	public static boolean recordDynamic(Object key, Object version, int[] quads, int vertexCount, float[] data, int stride, int posOff, int nrmOff,
			float[] uv, int uvStride, int uvOff, int argb, net.minecraft.client.renderer.rendertype.RenderType type, org.joml.Matrix4f pose,
			int light, int overlay) {
		if (failed || quads.length == 0 || quads.length % 4 != 0 || offscreen()) {
			return false;
		}
		try {
			Dynamic dyn = DYNAMIC.computeIfAbsent(key, k -> new Dynamic());
			Runnable upload = null;
			if (dyn.version != version || dyn.buffer == null || dyn.quadCount != quads.length / 4) {
				int count = quads.length;
				if (dyn.buffer == null || dyn.quadCount != count / 4) {
					if (dyn.buffer != null) {
						dyn.buffer.close();
					}
					dyn.buffer = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Velo cosmetic (animated)",
							com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, (long) count * STRIDE);
					dyn.quadCount = count / 4;
				}
				dyn.version = version;
				com.mojang.blaze3d.buffers.GpuBuffer target = dyn.buffer;
				// written in flush, outside any render pass
				upload = () -> {
					java.nio.ByteBuffer bytes = org.lwjgl.system.MemoryUtil.memAlloc(count * STRIDE);
					try {
						fill(bytes, quads, data, stride, posOff, nrmOff, uv, uvStride, uvOff, 1f, 0f);
						bytes.flip();
						com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().writeToBuffer(target.slice(), bytes);
					} finally {
						org.lwjgl.system.MemoryUtil.memFree(bytes);
					}
				};
			}
			PENDING.add(new Draw(new GpuMesh(dyn.buffer, dyn.quadCount), type, new org.joml.Matrix4f(pose), light, overlay, argb, upload));
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	// Draws what this frame recorded (render thread, during the feature pass).
	public static void flush() {
		if (PENDING.isEmpty()) {
			return;
		}
		if (failed || offscreen()) {
			PENDING.clear();
			return;
		}
		try {
			var indices = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(com.mojang.blaze3d.PrimitiveTopology.QUADS);
			org.joml.Matrix4f view = com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrixCopy();
			var uniforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms();
			for (Draw draw : PENDING) {
				if (draw.upload() != null) {
					draw.upload().run();
				}
			}
			for (Draw draw : PENDING) {
				int c = draw.argb();
				org.joml.Vector4f tint = new org.joml.Vector4f((c >> 16 & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c & 0xFF) / 255f, (c >>> 24) / 255f);
				int indexCount = draw.mesh().quadCount() * 6;
				var indexBuffer = indices.getBuffer(indexCount);
				var prepared = draw.type().prepare();
				int overlayU = draw.overlay() & 0xFFFF;
				int overlayV = draw.overlay() >>> 16;
				var transforms = uniforms.writeTransform(new org.joml.Matrix4f(view).mul(draw.pose()), tint,
						new org.joml.Vector3f(draw.light() & 0xFFFF, draw.light() >>> 16, overlayU + overlayV * 16), draw.pose());
				new net.minecraft.client.renderer.rendertype.PreparedRenderType(pipeline(), prepared.outputTarget(), transforms,
						prepared.scissorState(), prepared.textures())
						.drawFromBuffer(draw.mesh().vertices(), indexBuffer, indices.type(), 0, 0, indexCount);
			}
		} catch (Throwable t) {
			fail(t);
		} finally {
			PENDING.clear();
		}
	}

	private static boolean offscreen() {
		return com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride != null
				|| com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride != null;
	}

	private static com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
		if (pipeline == null) {
			pipeline = com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.veloclient.velo.client.mixin.GpuCosmeticsPipelinesAccessor.velo$globals())
					.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.MATRICES_PROJECTION)
					.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.FOG)
					.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.LIGHTING)
					.withVertexShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "core/cosmetic"))
					.withFragmentShader("core/entity")
					.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER0_SAMPLER2)
					.withVertexBinding(0, com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY)
					.withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.QUADS)
					.withDepthStencilState(com.mojang.blaze3d.pipeline.DepthStencilState.DEFAULT)
					.withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "pipeline/cosmetic_cutout"))
					.withShaderDefine("ALPHA_CUTOUT", 0.1f)
					.withShaderDefine("PER_FACE_LIGHTING")
					.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
					.withCull(false)
					.build();
		}
		return pipeline;
	}

	// The mesh in its own space, scaled (and slim-arm squeezed) like CosmeticVertexWriter does it, one quad per 4 corners.
	private static GpuMesh upload(int[] quads, float[] data, float scale, float squeeze) {
		int count = quads.length;
		java.nio.ByteBuffer bytes = org.lwjgl.system.MemoryUtil.memAlloc(count * STRIDE);
		try {
			fill(bytes, quads, data, 8, 0, 5, data, 8, 3, scale, squeeze);
			bytes.flip();
			var buffer = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Velo cosmetic mesh",
					com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, bytes);
			return new GpuMesh(buffer, count / 4);
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(bytes);
		}
	}

	// ENTITY-format vertices (36 bytes) for the corners in {@code quads}; light and overlay come from the draw's uniform.
	private static void fill(java.nio.ByteBuffer bytes, int[] quads, float[] data, int stride, int posOff, int nrmOff, float[] uv, int uvStride,
			int uvOff, float scale, float squeeze) {
		int noOverlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
		for (int v : quads) {
			int i = v * stride;
			float x = data[i + posOff];
			if (squeeze != 0) {
				x = squeeze + (x - squeeze) * 0.75f;
			}
			bytes.putFloat(x * scale).putFloat(data[i + posOff + 1] * scale).putFloat(data[i + posOff + 2] * scale);
			bytes.putInt(0xFFFFFFFF);                         // colour: the draw's tint (ColorModulator)
			int iu = v * uvStride + uvOff;
			bytes.putFloat(uv[iu]).putFloat(uv[iu + 1]);
			bytes.putInt(noOverlay);
			bytes.putInt(0);
			float nx = data[i + nrmOff], ny = data[i + nrmOff + 1], nz = data[i + nrmOff + 2];
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			float inv = len > 1e-6f ? 127f / len : 0f;
			bytes.put((byte) (int) (nx * inv)).put((byte) (int) (ny * inv)).put((byte) (int) (nz * inv)).put((byte) 0);
		}
	}

	private static void fail(Throwable t) {
		if (!failed) {
			failed = true;
			net.veloclient.velo.VeloClient.LOGGER.error("Velo GPU cosmetics failed - back to the CPU path for this session", t);
		}
	}
	*///?} else {
	public static boolean record(Object meshIdentity, int[] quads, float[] data, float scale, float squeeze, Object type, Object pose,
			int light, int overlay) {
		return false;
	}

	public static boolean recordDynamic(Object key, Object version, int[] quads, int vertexCount, float[] data, int stride, int posOff, int nrmOff,
			float[] uv, int uvStride, int uvOff, int argb, Object type, Object pose, int light, int overlay) {
		return false;
	}

	public static void flush() {
	}
	//?}
}
