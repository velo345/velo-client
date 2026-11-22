package net.veloclient.velo.client.modules.performance;

/**
 * Velo Turbo - entity models (players first) kept on the GPU. Vanilla walks every model's part tree each frame and writes
 * every cube's vertices through the part's matrix on the CPU (ModelPart.render -> Cube.compile); on LuckySMP's spawn
 * (2026-11-21, ~55 players) that was ~11% of the frame, plus its share of the upload.
 *
 * <p>Now a model's cubes are written once (by vanilla's own Cube.compile, in each part's own space, every vertex tagged
 * with its part) into a GPU buffer. Each frame vanilla still poses the model (setupAnim - emotes, Essential, everything
 * that moves parts runs as before) and the part tree is walked exactly like ModelPart.render walks it, but only the
 * parts' matrices are written ({@code velo-client:core/model_mesh}, vanilla's entity shader with the matrix per part; the
 * fragment shader is vanilla's). Translucent quads are sorted back to front per model, the models far to near (owner's
 * choice 2026-11-21, like GPU item meshes).
 *
 * <p>Only vanilla's entity_translucent (player skins, heads); everything else - outlines, armour trims (atlas sprites),
 * breaking cracks, glint, mirrored parts, models with more than 64 parts, offscreen passes, resource packs with their
 * own entity shader - stays on vanilla's path. Part of the GPU Item Meshes module. OPT-IN: {@code -Dvelo.gpuModels=true}.
 */
public final class GpuModelMeshes {

	// Opt-in (-Dvelo.gpuModels=true): measured 2026-11-21 it saves ~4% of the CPU at a busy spawn, but FPS didn't move
	// (singleplayer crowd A/B 763/760 off vs 736/770 on) - not worth the per-model sorting trade-off by default.
	public static final boolean ON = Boolean.getBoolean("velo.gpuModels");

	private GpuModelMeshes() {
	}

	//? if >=26.2 && <26.3 {
	/*private static final int MAX_PARTS = 64;
	private static final int BLOCK_BYTES = MAX_PARTS * 2 * 64; // mat4 PartPose[64] + mat4 PartNormal[64], std140
	private static final int STRIDE = 36; // DefaultVertexFormat.ENTITY
	private static volatile boolean failed;
	private static com.mojang.blaze3d.pipeline.RenderPipeline pipeline;
	private static Boolean packShaders;

	private static final class Mesh {
		com.mojang.blaze3d.buffers.GpuBuffer vertices;
		int quadCount;
		int partCount;
		float[] centres;
		int[] quadPart;
	}

	private static final Mesh UNSUPPORTED = new Mesh();

	// keyed by the model's root part: wrappers like Model.Simple are made new every frame around the same part tree.
	// Bounded - an evicted mesh's buffer is closed, so a part tree that does change every frame can never pile up.
	private static final java.util.LinkedHashMap<IdentityKey, Mesh> MESHES = new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<IdentityKey, Mesh> eldest) {
			if (size() > 256) {
				Mesh m = eldest.getValue();
				if (m != null && m.vertices != null) {
					m.vertices.close();
				}
				return true;
			}
			return false;
		}
	};

	private static final class IdentityKey {
		Object ref;

		IdentityKey(Object ref) {
			this.ref = ref;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof IdentityKey k && k.ref == ref;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(ref);
		}
	}

	private static final IdentityKey LOOKUP = new IdentityKey(null);
	private static final java.util.Map<Object, Boolean> TYPE_OK = new java.util.IdentityHashMap<>();

	private static final class Draw {
		Mesh mesh;
		final float[] parts = new float[MAX_PARTS * 32];
		net.minecraft.client.renderer.rendertype.RenderType type;
		int light;
		int overlay;
		int color;
		float distance;
		com.mojang.blaze3d.buffers.GpuBufferSlice transforms;
	}

	private static final java.util.ArrayList<Draw> PENDING = new java.util.ArrayList<>();
	private static final java.util.ArrayDeque<Draw> SPARE = new java.util.ArrayDeque<>();
	private static final com.mojang.blaze3d.vertex.PoseStack POSE = new com.mojang.blaze3d.vertex.PoseStack();
	private static final com.mojang.blaze3d.vertex.PoseStack IDENTITY = new com.mojang.blaze3d.vertex.PoseStack();
	private static final org.joml.Matrix4f SCRATCH = new org.joml.Matrix4f();
	private static final boolean PROFILE = Boolean.getBoolean("velo.renderProfile");
	private static long lastLog;
	private static int drawn;
	private static int built;
	private static boolean mirrored;

	// Vanilla's model writer for this submit (ModelFeatureRenderer.prepareModel) is skipped when this returns true.
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static boolean record(net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> submit) {
		if (!ON || failed || !GpuMeshesModule.active || submit.sprite() != null || submit.sheetedDecalPose() != null || offscreen()) {
			return false;
		}
		try {
			var type = submit.renderType();
			Boolean ok = TYPE_OK.get(type);
			if (ok == null) {
				ok = type.format() == com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY
						&& type.prepare().pipeline() == net.minecraft.client.renderer.RenderPipelines.ENTITY_TRANSLUCENT;
				TYPE_OK.put(type, ok);
			}
			if (!ok || packShaders()) {
				return false;
			}
			net.minecraft.client.model.Model model = submit.model();
			LOOKUP.ref = model.root();
			Mesh mesh = MESHES.get(LOOKUP);
			LOOKUP.ref = null;
			if (mesh == null) {
				mesh = build(model);
				MESHES.put(new IdentityKey(model.root()), mesh);
				built++;
				if (PROFILE && built < 40) {
					net.veloclient.velo.VeloClient.LOGGER.info("Velo GPU model meshes: built {} ({}) for {}", model.getClass().getName(),
							System.identityHashCode(model), type);
				}
			}
			if (mesh == UNSUPPORTED) {
				return false;
			}
			// pose it exactly like vanilla's prepareModel would before writing the vertices
			model.setupAnim(submit.state());
			Draw d = SPARE.isEmpty() ? new Draw() : SPARE.poll();
			POSE.last().set(submit.pose());
			mirrored = false;
			int count = walk(model.root(), d.parts, 0, true);
			if (count != mesh.partCount || mirrored) {
				SPARE.add(d);
				return false; // the part tree changed shape, or a mirrored part: vanilla draws it
			}
			var p = submit.pose().pose();
			d.mesh = mesh;
			d.type = type;
			d.light = submit.lightCoords();
			d.overlay = submit.overlayCoords();
			d.color = submit.tintedColor();
			d.distance = p.m30() * p.m30() + p.m31() * p.m31() + p.m32() * p.m32();
			d.transforms = null;
			PENDING.add(d);
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	// ModelPart.render's walk: a part is entered when visible and it has cubes or children; skipDraw hides only its own
	// cubes. Parts that aren't drawn get a zero matrix (their vertices collapse to a point outside nothing). Returns the
	// next part index - the same depth-first order build() numbered the parts in.
	private static int walk(net.minecraft.client.model.geom.ModelPart part, float[] out, int index, boolean parentEntered) {
		var access = (net.veloclient.velo.client.mixin.TurboEntityPartAccessor) (Object) part;
		var cubes = access.velo$cubes();
		var children = access.velo$children();
		int my = index++;
		boolean enter = parentEntered && part.visible && (!cubes.isEmpty() || !children.isEmpty());
		if (enter) {
			POSE.pushPose();
			part.translateAndRotate(POSE);
			if (!part.skipDraw && my < MAX_PARTS) {
				var last = POSE.last();
				if (last.pose().determinant3x3() < 0f) {
					mirrored = true;
				}
				last.pose().get(out, my * 16);
				SCRATCH.set(last.normal());
				SCRATCH.get(out, MAX_PARTS * 16 + my * 16);
			} else if (my < MAX_PARTS) {
				zero(out, my);
			}
		} else if (my < MAX_PARTS) {
			zero(out, my);
		}
		for (var child : children.values()) {
			index = walk(child, out, index, enter);
		}
		if (enter) {
			POSE.popPose();
		}
		return index;
	}

	private static void zero(float[] out, int part) {
		java.util.Arrays.fill(out, part * 16, part * 16 + 16, 0f);
		java.util.Arrays.fill(out, MAX_PARTS * 16 + part * 16, MAX_PARTS * 16 + part * 16 + 16, 0f);
	}

	private static int numberParts(net.minecraft.client.model.geom.ModelPart part, java.util.List<net.minecraft.client.model.geom.ModelPart> out) {
		out.add(part);
		for (var child : ((net.veloclient.velo.client.mixin.TurboEntityPartAccessor) (Object) part).velo$children().values()) {
			numberParts(child, out);
		}
		return out.size();
	}

	private static Mesh build(net.minecraft.client.model.Model<?> model) {
		java.util.List<net.minecraft.client.model.geom.ModelPart> parts = new java.util.ArrayList<>();
		numberParts(model.root(), parts);
		if (parts.size() > MAX_PARTS || parts.size() == 0) {
			return UNSUPPORTED;
		}
		int cubes = 0;
		for (var part : parts) {
			cubes += ((net.veloclient.velo.client.mixin.TurboEntityPartAccessor) (Object) part).velo$cubes().size();
		}
		if (cubes == 0) {
			return UNSUPPORTED;
		}
		var bytes = new com.mojang.blaze3d.vertex.ByteBufferBuilder(cubes * 6 * 4 * STRIDE + 256);
		try {
			var builder = new com.mojang.blaze3d.vertex.BufferBuilder(bytes, com.mojang.blaze3d.PrimitiveTopology.QUADS,
					com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY);
			var identity = IDENTITY.last();
			for (int i = 0; i < parts.size(); i++) {
				for (var cube : ((net.veloclient.velo.client.mixin.TurboEntityPartAccessor) (Object) parts.get(i)).velo$cubes()) {
					// the part number rides in the overlay slot (u = part); the real overlay comes from the uniform
					cube.compile(identity, builder, net.minecraft.util.LightCoordsUtil.FULL_BRIGHT, i, -1);
				}
			}
			try (var data = builder.buildOrThrow()) {
				java.nio.ByteBuffer vb = data.vertexBuffer();
				int vertices = vb.remaining() / STRIDE;
				if (vertices % 4 != 0 || vertices == 0) {
					return UNSUPPORTED;
				}
				Mesh mesh = new Mesh();
				mesh.quadCount = vertices / 4;
				mesh.partCount = parts.size();
				mesh.centres = new float[mesh.quadCount * 3];
				mesh.quadPart = new int[mesh.quadCount];
				int base = vb.position();
				for (int q = 0; q < mesh.quadCount; q++) {
					float cx = 0f, cy = 0f, cz = 0f;
					for (int v = 0; v < 4; v++) {
						int at = base + (q * 4 + v) * STRIDE;
						cx += vb.getFloat(at);
						cy += vb.getFloat(at + 4);
						cz += vb.getFloat(at + 8);
					}
					mesh.centres[q * 3] = cx / 4f;
					mesh.centres[q * 3 + 1] = cy / 4f;
					mesh.centres[q * 3 + 2] = cz / 4f;
					mesh.quadPart[q] = Math.min(MAX_PARTS - 1, vb.getShort(base + q * 4 * STRIDE + 24) & 0xFFFF);
				}
				mesh.vertices = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Velo model mesh",
						com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, vb);
				return mesh;
			}
		} finally {
			bytes.close();
		}
	}

	// ---------------------------------------------------------------- drawing

	private static com.mojang.blaze3d.buffers.GpuBuffer partsBuffer;
	private static com.mojang.blaze3d.buffers.GpuBuffer sortedIndices;
	private static java.nio.ByteBuffer partBytes;
	private static java.nio.ByteBuffer indexBytes;
	private static int[] order = new int[1024];
	private static int[] spare = new int[1024];
	private static int[] bits = new int[1024];
	private static final int[] COUNT = new int[2048];

	public static void flush(boolean translucentPhase) {
		if (PROFILE) {
			long now = System.nanoTime();
			if (now - lastLog > 10_000_000_000L) {
				lastLog = now;
				net.veloclient.velo.VeloClient.LOGGER.info("Velo GPU model meshes: {} draws, {} meshes built, {} kept, failed {}", drawn, built, MESHES.size(), failed);
				drawn = 0;
				built = 0;
			}
		}
		if (PENDING.isEmpty() || !translucentPhase) {
			return; // entity_translucent only: drawn with its translucent group
		}
		if (failed || offscreen()) {
			recycleAll();
			return;
		}
		try {
			draw();
		} catch (Throwable t) {
			fail(t);
		} finally {
			recycleAll();
		}
	}

	public static void flushAll() {
		flush(true);
	}

	private static void draw() {
		java.util.List<Draw> list = new java.util.ArrayList<>(PENDING);
		list.sort((a, b) -> Float.compare(b.distance, a.distance)); // far to near
		var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
		int align = Math.max(16, device.getDeviceInfo().limits().minUniformOffsetAlignment());
		int stride = (BLOCK_BYTES + align - 1) / align * align;
		long partTotal = (long) stride * list.size();
		if (partsBuffer == null || partsBuffer.size() < partTotal) {
			if (partsBuffer != null) {
				partsBuffer.close();
			}
			partsBuffer = device.createBuffer(() -> "Velo model parts", com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM
					| com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, Math.max(stride * 64L, Long.highestOneBit(partTotal) << 1));
		}
		if (partBytes == null || partBytes.capacity() < partTotal) {
			if (partBytes != null) {
				org.lwjgl.system.MemoryUtil.memFree(partBytes);
			}
			partBytes = org.lwjgl.system.MemoryUtil.memAlloc((int) Math.max(stride * 64L, partTotal * 2));
		}
		partBytes.clear();
		java.nio.FloatBuffer partFloats = partBytes.asFloatBuffer();
		var uniforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms();
		org.joml.Matrix4f view = com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrixCopy();
		int totalQuads = 0;
		for (int i = 0; i < list.size(); i++) {
			Draw d = list.get(i);
			int c = d.color;
			int o = d.overlay;
			d.transforms = uniforms.writeTransform(view, new org.joml.Vector4f((c >> 16 & 0xFF) / 255f, (c >> 8 & 0xFF) / 255f, (c & 0xFF) / 255f, (c >>> 24) / 255f),
					new org.joml.Vector3f(d.light & 0xFFFF, d.light >>> 16, (o & 0xFFFF) + (o >>> 16) * 16), new org.joml.Matrix4f());
			// only the parts this model has: poses at the block's start, normal matrices from PartNormal[0] on (bulk copies)
			int n = d.mesh.partCount * 16;
			partFloats.position(i * stride / 4);
			partFloats.put(d.parts, 0, n);
			partFloats.position((i * stride + MAX_PARTS * 64) / 4);
			partFloats.put(d.parts, MAX_PARTS * 16, n);
			totalQuads += d.mesh.quadCount;
		}
		partBytes.position(0).limit((int) partTotal);
		device.createCommandEncoder().writeToBuffer(partsBuffer.slice(0, partTotal), partBytes);
		int[] firstIndex = writeSortedIndices(list, totalQuads);
		// prepare() writes uniforms: all of it before the pass opens
		net.minecraft.client.renderer.rendertype.PreparedRenderType[] prepared = new net.minecraft.client.renderer.rendertype.PreparedRenderType[list.size()];
		for (int i = 0; i < list.size(); i++) {
			prepared[i] = list.get(i).type.prepare();
		}
		var encoder = device.createCommandEncoder();
		com.mojang.blaze3d.systems.RenderPass pass = null;
		Object passTarget = null;
		var pipe = pipeline();
		try {
			for (int i = 0; i < list.size(); i++) {
				Draw d = list.get(i);
				var target = prepared[i].outputTarget().getRenderTarget();
				if (pass == null || target != passTarget) {
					if (pass != null) {
						pass.close();
					}
					pass = encoder.createRenderPass(() -> "Velo model meshes", target.getColorTextureView(), java.util.Optional.empty(),
							target.useDepth ? target.getDepthTextureView() : null, java.util.OptionalDouble.empty());
					pass.setPipeline(pipe);
					com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);
					pass.setIndexBuffer(sortedIndices, com.mojang.blaze3d.IndexType.INT);
					passTarget = target;
				}
				for (var texture : prepared[i].textures()) {
					pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
				}
				pass.setUniform("DynamicTransforms", d.transforms);
				pass.setUniform("VeloParts", partsBuffer.slice((long) i * stride, BLOCK_BYTES));
				pass.setVertexBuffer(0, d.mesh.vertices.slice());
				pass.drawIndexed(d.mesh.quadCount * 6, 1, firstIndex[i], 0, 0);
				drawn++;
			}
		} finally {
			if (pass != null) {
				pass.close();
			}
		}
	}

	// Back-to-front quad order per model (distances after each quad's part matrix, like vanilla sorts the posed vertices).
	private static int[] writeSortedIndices(java.util.List<Draw> list, int totalQuads) {
		var device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
		long bytes = (long) totalQuads * 6 * 4;
		if (sortedIndices == null || sortedIndices.size() < bytes) {
			if (sortedIndices != null) {
				sortedIndices.close();
			}
			sortedIndices = device.createBuffer(() -> "Velo model mesh order", com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX
					| com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, Math.max(64 * 1024, Long.highestOneBit(Math.max(1, bytes - 1)) << 1));
		}
		if (indexBytes == null || indexBytes.capacity() < bytes) {
			if (indexBytes != null) {
				org.lwjgl.system.MemoryUtil.memFree(indexBytes);
			}
			indexBytes = org.lwjgl.system.MemoryUtil.memAlloc((int) Math.max(64 * 1024, bytes * 2));
		}
		indexBytes.clear();
		int[] first = new int[list.size()];
		int written = 0;
		for (int di = 0; di < list.size(); di++) {
			Draw d = list.get(di);
			Mesh m = d.mesh;
			int q = m.quadCount;
			if (order.length < q) {
				order = new int[q * 2];
				spare = new int[q * 2];
				bits = new int[q * 2];
			}
			float[] p = d.parts;
			for (int i = 0; i < q; i++) {
				int o = m.quadPart[i] * 16;
				float x = m.centres[i * 3], y = m.centres[i * 3 + 1], z = m.centres[i * 3 + 2];
				float wx = p[o] * x + p[o + 4] * y + p[o + 8] * z + p[o + 12];
				float wy = p[o + 1] * x + p[o + 5] * y + p[o + 9] * z + p[o + 13];
				float wz = p[o + 2] * x + p[o + 6] * y + p[o + 10] * z + p[o + 14];
				bits[i] = Float.floatToRawIntBits(wx * wx + wy * wy + wz * wz);
				order[i] = i;
			}
			radixSort(q);
			first[di] = written;
			for (int i = q - 1; i >= 0; i--) {
				int v = order[i] * 4;
				indexBytes.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
			}
			written += q * 6;
		}
		indexBytes.flip();
		device.createCommandEncoder().writeToBuffer(sortedIndices.slice(0, indexBytes.remaining()), indexBytes);
		return first;
	}

	private static void radixSort(int n) {
		if (n <= 256) {
			// small sets (a player model is ~70-100 quads): insertion sort beats clearing 3 x 2048 radix counters
			for (int i = 1; i < n; i++) {
				int idx = order[i];
				int key = bits[idx];
				int j = i - 1;
				while (j >= 0 && bits[order[j]] > key) {
					order[j + 1] = order[j];
					j--;
				}
				order[j + 1] = idx;
			}
			return;
		}
		int[] from = order;
		int[] to = spare;
		for (int shift = 0; shift < 33; shift += 11) {
			java.util.Arrays.fill(COUNT, 0);
			for (int i = 0; i < n; i++) {
				COUNT[(bits[from[i]] >>> shift) & 2047]++;
			}
			int sum = 0;
			for (int b = 0; b < 2048; b++) {
				int c = COUNT[b];
				COUNT[b] = sum;
				sum += c;
			}
			for (int i = 0; i < n; i++) {
				int idx = from[i];
				to[COUNT[(bits[idx] >>> shift) & 2047]++] = idx;
			}
			int[] t = from;
			from = to;
			to = t;
		}
		if (from != order) {
			System.arraycopy(from, 0, order, 0, n);
		}
	}

	private static void recycleAll() {
		for (Draw d : PENDING) {
			d.mesh = null;
			d.type = null;
			d.transforms = null;
			if (SPARE.size() < 256) {
				SPARE.add(d);
			}
		}
		PENDING.clear();
	}

	private static com.mojang.blaze3d.pipeline.RenderPipeline pipeline() {
		if (pipeline == null) {
			var parts = com.mojang.blaze3d.pipeline.BindGroupLayout.builder()
					.withUniform("VeloParts", com.mojang.blaze3d.shaders.UniformType.UNIFORM_BUFFER).build();
			pipeline = com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.veloclient.velo.client.mixin.GpuCosmeticsPipelinesAccessor.velo$entityBase())
					.withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "pipeline/model_translucent"))
					.withVertexShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "core/model_mesh"))
					.withShaderDefine("ALPHA_CUTOUT", 0.1f)
					.withShaderDefine("PER_FACE_LIGHTING")
					.withBindGroupLayout(net.minecraft.client.renderer.BindGroupLayouts.SAMPLER1)
					.withBindGroupLayout(parts)
					.withColorTargetState(new com.mojang.blaze3d.pipeline.ColorTargetState(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT))
					.withCull(false)
					.build();
		}
		return pipeline;
	}

	// A resource pack with its own entity shader keeps vanilla's path (Velo's own shader pack is fine: model_mesh.vsh
	// writes what its entity.fsh reads).
	private static boolean packShaders() {
		if (packShaders == null) {
			var manager = net.minecraft.client.Minecraft.getInstance().getResourceManager();
			boolean replaced = false;
			for (String file : new String[] {"shaders/core/entity.vsh", "shaders/core/entity.fsh"}) {
				var resource = manager.getResource(net.minecraft.resources.Identifier.withDefaultNamespace(file));
				if (resource.isPresent()) {
					String id = resource.get().sourcePackId();
					if (!"vanilla".equals(id) && !id.contains("velo_shaders")) {
						replaced = true;
					}
				}
			}
			packShaders = replaced;
			if (replaced) {
				net.veloclient.velo.VeloClient.LOGGER.info("Velo GPU model meshes: a resource pack replaces core/entity - models stay on the normal path");
			}
		}
		return packShaders;
	}

	private static boolean offscreen() {
		return com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride != null
				|| com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride != null;
	}

	public static void clear() {
		for (Mesh mesh : MESHES.values()) {
			if (mesh != null && mesh.vertices != null) {
				mesh.vertices.close();
			}
		}
		MESHES.clear();
		TYPE_OK.clear();
		packShaders = null;
		recycleAll();
	}

	private static void fail(Throwable t) {
		if (!failed) {
			failed = true;
			net.veloclient.velo.VeloClient.LOGGER.error("Velo GPU model meshes failed - back to the normal path for this session", t);
		}
	}
	*///?} else {
	public static void clear() {
	}
	//?}
}
