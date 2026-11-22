package net.veloclient.velo.client.modules.performance;

/**
 * Velo Turbo - big item models kept on the GPU. Servers give items detailed 3D models through their resource packs; on
 * LuckySMP's spawn (2026-11-21) the off-hand item many players carry has ~800 quads, and 3,000-10,000 item quads were
 * transformed, written and uploaded again every single frame - 15% of the frame for items, plus most of the 13% upload.
 *
 * <p>Now a model with at least {@value #MIN_QUADS} quads is written once (by vanilla's own quad writer, in the model's
 * space) into a GPU buffer, and each frame only gets a matrix, the light and the overlay
 * ({@code velo-client:core/held_mesh}, vanilla's core/item shader with the pose applied on the GPU; the fragment shader is
 * vanilla's). Translucent quads are still sorted back to front every frame - per item, the items far to near (vanilla
 * sorts all items' quads together; this only differs where two items stick into each other - owner's choice 2026-11-21).
 *
 * <p>Stays on vanilla's path: small models, enchantment glint, glowing outlines, quads that emit light, mirrored poses,
 * anything that isn't vanilla's item pipeline, offscreen passes (shadow map, GUI), and everything whenever a resource
 * pack replaces core/item. One failure turns it off for the session. Off with {@code -Dvelo.gpuHeld=false}. 26.2.
 */
public final class GpuHeldMeshes {

	public static final boolean ON = !"false".equals(System.getProperty("velo.gpuHeld"));
	private static final int MIN_QUADS = 48;

	private GpuHeldMeshes() {
	}

	//? if >=26.2 && <26.3 {
	/*private static volatile boolean failed;
	private static com.mojang.blaze3d.pipeline.RenderPipeline cutoutPipeline;
	private static com.mojang.blaze3d.pipeline.RenderPipeline translucentPipeline;
	// null = not checked since the last reload
	private static Boolean packShaders;

	// A run of quads with one render type inside a mesh; translucent ones keep their quad centres for sorting.
	private record Part(net.minecraft.client.renderer.rendertype.RenderType type, boolean translucent, int firstQuad, int quadCount, float[] centres,
			SortCache sort) {
	}

	// The last few back-to-front orders of a translucent part, each with where the camera stood in the model's own space.
	// The order only depends on that position: a held item barely moves relative to the camera between frames (your own
	// hand, players standing around), so the sort is reused while the camera is within SORT_REUSE of a remembered spot.
	private static final class SortCache {
		final float[] cam = new float[SORT_SLOTS * 3];
		final int[][] orders = new int[SORT_SLOTS][];
		int next;
	}

	private static final int SORT_SLOTS = 8;
	private static final float SORT_REUSE = 0.01f;
	private static final org.joml.Matrix4f INVERSE = new org.joml.Matrix4f();

	private static final class Mesh {
		com.mojang.blaze3d.buffers.GpuBuffer vertices;
		Part[] parts;
	}

	private static final Mesh UNSUPPORTED = new Mesh();

	private static final class Key {
		final Object firstQuad;
		final int size;
		final int[] tints;
		final int hash;

		Key(Object firstQuad, int size, int[] tints) {
			this.firstQuad = firstQuad;
			this.size = size;
			this.tints = tints == null ? new int[0] : tints.clone();
			this.hash = System.identityHashCode(firstQuad) * 31 + size * 17 + java.util.Arrays.hashCode(this.tints);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && k.firstQuad == firstQuad && k.size == size && java.util.Arrays.equals(k.tints, tints);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	// lookup key without copying the tints (asked for every big item every frame); HashMap calls probe.equals(storedKey)
	private static final class Probe {
		Object firstQuad;
		int size;
		int[] tints;
		int hash;

		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && k.firstQuad == firstQuad && k.size == size && java.util.Arrays.equals(k.tints, tints == null ? EMPTY : tints);
		}

		@Override
		public int hashCode() {
			return hash;
		}
	}

	private static final int[] EMPTY = new int[0];

	private static final java.util.LinkedHashMap<Key, Mesh> MESHES = new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<Key, Mesh> eldest) {
			if (size() > 192) {
				close(eldest.getValue());
				return true;
			}
			return false;
		}
	};

	private static final class Draw {
		Mesh mesh;
		final org.joml.Matrix4f pose = new org.joml.Matrix4f();
		int light;
		int overlay;
		float distance;
		boolean solidDone;
		com.mojang.blaze3d.buffers.GpuBufferSlice transforms;
	}

	private static final java.util.ArrayList<Draw> PENDING = new java.util.ArrayList<>();
	private static final java.util.ArrayDeque<Draw> SPARE = new java.util.ArrayDeque<>();
	private static final com.mojang.blaze3d.vertex.PoseStack IDENTITY = new com.mojang.blaze3d.vertex.PoseStack();
	private static final org.joml.Vector4f WHITE = new org.joml.Vector4f(1f, 1f, 1f, 1f);
	private static final boolean PROFILE = Boolean.getBoolean("velo.renderProfile");
	private static long lastLog;
	public static int drawn;
	public static int built;

	// Vanilla's item writer for this submit (ItemFeatureRenderer.prepareMainSubmit) is skipped when this returns true.
	public static boolean record(net.minecraft.client.renderer.feature.ItemFeatureRenderer.Submit submit) {
		if (!ON || failed || !GpuMeshesModule.active) {
			return false;
		}
		var quads = submit.quads();
		int n = quads.size();
		if (n < MIN_QUADS || submit.outlineColor() != 0
				|| submit.foilType() != null && submit.foilType() != net.minecraft.client.renderer.item.ItemStackRenderState.FoilType.NONE
				|| offscreen()) {
			return false;
		}
		org.joml.Matrix4f pose = submit.pose().pose();
		if (pose.determinant3x3() <= 0f) {
			return false; // mirrored (or flat): vanilla's writer handles those
		}
		try {
			if (packShaders()) {
				return false;
			}
			Mesh mesh = find(quads.get(0), n, submit.tintLayers());
			if (mesh == null) {
				Key key = new Key(quads.get(0), n, submit.tintLayers());
				mesh = build(quads, submit.tintLayers());
				MESHES.put(key, mesh);
				built++;
			}
			if (mesh == UNSUPPORTED) {
				return false;
			}
			Draw d = SPARE.isEmpty() ? new Draw() : SPARE.poll();
			d.mesh = mesh;
			d.pose.set(pose);
			d.light = submit.lightCoords();
			d.overlay = submit.overlayCoords();
			d.distance = pose.m30() * pose.m30() + pose.m31() * pose.m31() + pose.m32() * pose.m32();
			d.solidDone = false;
			d.transforms = null;
			PENDING.add(d);
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	private static final Probe PROBE = new Probe();

	private static Mesh find(Object firstQuad, int size, int[] tints) {
		PROBE.firstQuad = firstQuad;
		PROBE.size = size;
		PROBE.tints = tints;
		PROBE.hash = System.identityHashCode(firstQuad) * 31 + size * 17 + java.util.Arrays.hashCode(tints == null ? EMPTY : tints);
		Mesh mesh = MESHES.get(PROBE);
		PROBE.firstQuad = null;
		return mesh;
	}

	private static Mesh build(java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad> quads, int[] tints) {
		// group the quads by render type, first appearance first (vanilla draws each render type as its own draw anyway)
		java.util.LinkedHashMap<net.minecraft.client.renderer.rendertype.RenderType, java.util.List<net.minecraft.client.resources.model.geometry.BakedQuad>> byType =
				new java.util.LinkedHashMap<>();
		for (var quad : quads) {
			var info = quad.materialInfo();
			if (info.lightEmission() > 0) {
				return UNSUPPORTED;
			}
			var type = info.itemRenderType();
			if (type.format() != com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY) {
				return UNSUPPORTED;
			}
			var pipeline = type.prepare().pipeline();
			if (pipeline != net.minecraft.client.renderer.RenderPipelines.ITEM_CUTOUT && pipeline != net.minecraft.client.renderer.RenderPipelines.ITEM_TRANSLUCENT) {
				return UNSUPPORTED;
			}
			byType.computeIfAbsent(type, t -> new java.util.ArrayList<>()).add(quad);
		}
		int n = quads.size();
		var bytes = new com.mojang.blaze3d.vertex.ByteBufferBuilder(n * 4 * 36 + 256);
		try {
			var builder = new com.mojang.blaze3d.vertex.BufferBuilder(bytes, com.mojang.blaze3d.PrimitiveTopology.QUADS,
					com.mojang.blaze3d.vertex.DefaultVertexFormat.ENTITY);
			var instance = new com.mojang.blaze3d.vertex.QuadInstance();
			instance.setLightCoords(net.minecraft.util.LightCoordsUtil.FULL_BRIGHT);
			instance.setOverlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
			var pose = IDENTITY.last();
			java.util.List<Part> parts = new java.util.ArrayList<>();
			int at = 0;
			for (var e : byType.entrySet()) {
				var list = e.getValue();
				boolean translucent = e.getKey().prepare().pipeline() == net.minecraft.client.renderer.RenderPipelines.ITEM_TRANSLUCENT;
				float[] centres = translucent ? new float[list.size() * 3] : null;
				for (int i = 0; i < list.size(); i++) {
					var quad = list.get(i);
					var info = quad.materialInfo();
					int idx = info.isTinted() ? info.tintIndex() : -1;
					instance.setColor(idx < 0 || tints == null || idx >= tints.length ? -1 : tints[idx]);
					builder.putBakedQuad(pose, quad, instance);
					if (centres != null) {
						float cx = 0f, cy = 0f, cz = 0f;
						for (int v = 0; v < 4; v++) {
							var p = quad.position(v);
							cx += p.x();
							cy += p.y();
							cz += p.z();
						}
						centres[i * 3] = cx / 4f;
						centres[i * 3 + 1] = cy / 4f;
						centres[i * 3 + 2] = cz / 4f;
					}
				}
				parts.add(new Part(e.getKey(), translucent, at, list.size(), centres, translucent ? new SortCache() : null));
				at += list.size();
			}
			try (var data = builder.buildOrThrow()) {
				Mesh mesh = new Mesh();
				mesh.vertices = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Velo held mesh",
						com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX, data.vertexBuffer());
				mesh.parts = parts.toArray(new Part[0]);
				return mesh;
			}
		} finally {
			bytes.close();
		}
	}

	// ---------------------------------------------------------------- drawing (render thread, vanilla's item group just executed)

	private static com.mojang.blaze3d.buffers.GpuBuffer sortedIndices;
	private static java.nio.ByteBuffer indexBytes;
	private static int[] order = new int[1024];

	public static void flush(boolean translucentPhase) {
		if (PROFILE) {
			long now = System.nanoTime();
			if (now - lastLog > 10_000_000_000L) {
				lastLog = now;
				net.veloclient.velo.VeloClient.LOGGER.info("Velo GPU item meshes: {} draws, {} meshes built, {} kept, failed {}", drawn, built, MESHES.size(), failed);
				drawn = 0;
				built = 0;
			}
		}
		if (PENDING.isEmpty()) {
			return;
		}
		if (failed || offscreen()) {
			recycleAll();
			return;
		}
		try {
			if (translucentPhase) {
				draw(false); // solid parts first if their group didn't run yet - cutout never after translucent
				draw(true);
				recycleAll();
			} else {
				draw(false);
			}
		} catch (Throwable t) {
			fail(t);
			recycleAll();
		}
	}

	// End of the feature pass: whatever an item group didn't draw yet (it never ran) still gets drawn - never lost.
	public static void flushAll() {
		if (PENDING.isEmpty()) {
			return;
		}
		flush(true);
	}

	private static void draw(boolean translucent) {
		var uniforms = com.mojang.blaze3d.systems.RenderSystem.getDynamicUniforms();
		org.joml.Matrix4f view = com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrixCopy();
		java.util.List<Draw> list = new java.util.ArrayList<>(PENDING.size());
		for (Draw d : PENDING) {
			if (!translucent && d.solidDone) {
				continue;
			}
			list.add(d);
		}
		if (translucent) {
			list.sort((a, b) -> Float.compare(b.distance, a.distance)); // far to near
		}
		// everything that writes GPU memory happens before the pass opens: transforms, sorted indices, the index ring
		int maxQuads = 0;
		int sortedQuadTotal = 0;
		for (Draw d : list) {
			if (d.transforms == null) {
				int o = d.overlay;
				d.transforms = uniforms.writeTransform(new org.joml.Matrix4f(view).mul(d.pose), WHITE,
						new org.joml.Vector3f(d.light & 0xFFFF, d.light >>> 16, (o & 0xFFFF) + (o >>> 16) * 16), d.pose);
			}
			for (Part p : d.mesh.parts) {
				if (p.translucent() == translucent) {
					maxQuads = Math.max(maxQuads, p.quadCount());
					sortedQuadTotal += translucent ? p.quadCount() : 0;
				}
			}
		}
		if (maxQuads == 0) {
			markSolid(list, translucent);
			return;
		}
		com.mojang.blaze3d.buffers.GpuBuffer indexBuffer;
		com.mojang.blaze3d.IndexType indexType;
		int[] firstIndex = null;
		if (translucent) {
			firstIndex = writeSortedIndices(list, sortedQuadTotal);
			indexBuffer = sortedIndices;
			indexType = com.mojang.blaze3d.IndexType.INT;
		} else {
			var sequential = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(com.mojang.blaze3d.PrimitiveTopology.QUADS);
			indexBuffer = sequential.getBuffer(maxQuads * 6);
			indexType = sequential.type();
		}
		var pipeline = translucent ? translucentPipeline() : cutoutPipeline();
		// prepare() writes uniforms: every render type's prepared state before the pass opens
		java.util.IdentityHashMap<net.minecraft.client.renderer.rendertype.RenderType, net.minecraft.client.renderer.rendertype.PreparedRenderType> preparedTypes =
				new java.util.IdentityHashMap<>();
		for (Draw d : list) {
			for (Part p : d.mesh.parts) {
				if (p.translucent() == translucent) {
					preparedTypes.computeIfAbsent(p.type(), t -> t.prepare());
				}
			}
		}
		var encoder = com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
		com.mojang.blaze3d.systems.RenderPass pass = null;
		Object passTarget = null;
		Object boundType = null;
		int sortedAt = 0;
		try {
			for (Draw d : list) {
				for (Part p : d.mesh.parts) {
					if (p.translucent() != translucent) {
						continue;
					}
					var prepared = preparedTypes.get(p.type());
					var target = prepared.outputTarget().getRenderTarget();
					if (pass == null || target != passTarget) {
						if (pass != null) {
							pass.close();
						}
						pass = encoder.createRenderPass(() -> "Velo held meshes", target.getColorTextureView(), java.util.Optional.empty(),
								target.useDepth ? target.getDepthTextureView() : null, java.util.OptionalDouble.empty());
						pass.setPipeline(pipeline);
						com.mojang.blaze3d.systems.RenderSystem.bindDefaultUniforms(pass);
						if (prepared.scissorState().enabled()) {
							var s = prepared.scissorState();
							pass.enableScissor(s.x(), s.y(), s.width(), s.height());
						}
						pass.setIndexBuffer(indexBuffer, indexType);
						passTarget = target;
						boundType = null;
					}
					if (boundType != p.type()) {
						for (var texture : prepared.textures()) {
							pass.bindTexture(texture.name(), texture.textureView(), texture.sampler());
						}
						boundType = p.type();
					}
					pass.setUniform("DynamicTransforms", d.transforms);
					pass.setVertexBuffer(0, d.mesh.vertices.slice());
					int first = translucent ? firstIndex[sortedAt++] : 0;
					pass.drawIndexed(p.quadCount() * 6, 1, first, p.firstQuad() * 4, 0);
					drawn++;
				}
			}
		} finally {
			if (pass != null) {
				pass.close();
			}
		}
		markSolid(list, translucent);
	}

	private static void markSolid(java.util.List<Draw> list, boolean translucent) {
		if (!translucent) {
			for (Draw d : list) {
				d.solidDone = true;
			}
		}
	}

	// Back-to-front quad order for every translucent part (in draw order), all in one index buffer: returns each part's
	// first index. Distances are measured after the pose, like vanilla's sorting does.
	private static int[] writeSortedIndices(java.util.List<Draw> list, int totalQuads) {
		long bytes = (long) totalQuads * 6 * 4;
		if (sortedIndices == null || sortedIndices.size() < bytes) {
			if (sortedIndices != null) {
				sortedIndices.close();
			}
			long size = Math.max(64 * 1024, Long.highestOneBit(Math.max(1, bytes - 1)) << 1);
			sortedIndices = com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(() -> "Velo held mesh order",
					com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST, size);
		}
		if (indexBytes == null || indexBytes.capacity() < bytes) {
			if (indexBytes != null) {
				org.lwjgl.system.MemoryUtil.memFree(indexBytes);
			}
			indexBytes = org.lwjgl.system.MemoryUtil.memAlloc((int) Math.max(64 * 1024, bytes * 2));
		}
		indexBytes.clear();
		int parts = 0;
		for (Draw d : list) {
			for (Part p : d.mesh.parts) {
				if (p.translucent()) {
					parts++;
				}
			}
		}
		int[] first = new int[parts];
		int partIndex = 0;
		int written = 0;
		for (Draw d : list) {
			org.joml.Matrix4f m = d.pose;
			for (Part p : d.mesh.parts) {
				if (!p.translucent()) {
					continue;
				}
				int q = p.quadCount();
				// the camera (the pose's origin space: camera-relative) seen from the model's own space
				INVERSE.set(m).invertAffine();
				float lx = INVERSE.m30(), ly = INVERSE.m31(), lz = INVERSE.m32();
				SortCache cache = p.sort();
				int[] sorted = null;
				for (int s = 0; s < SORT_SLOTS && cache != null; s++) {
					int[] o = cache.orders[s];
					if (o != null && o.length == q) {
						float dx = cache.cam[s * 3] - lx, dy = cache.cam[s * 3 + 1] - ly, dz = cache.cam[s * 3 + 2] - lz;
						if (dx * dx + dy * dy + dz * dz < SORT_REUSE * SORT_REUSE) {
							sorted = o;
							break;
						}
					}
				}
				if (sorted == null) {
					if (order.length < q) {
						order = new int[q * 2];
					}
					if (bits.length < q) {
						bits = new int[q * 2];
						spare = new int[q * 2];
					}
					float[] c = p.centres();
					float m00 = m.m00(), m01 = m.m01(), m02 = m.m02(), m10 = m.m10(), m11 = m.m11(), m12 = m.m12();
					float m20 = m.m20(), m21 = m.m21(), m22 = m.m22(), m30 = m.m30(), m31 = m.m31(), m32 = m.m32();
					for (int i = 0; i < q; i++) {
						float x = c[i * 3], y = c[i * 3 + 1], z = c[i * 3 + 2];
						float wx = m00 * x + m10 * y + m20 * z + m30;
						float wy = m01 * x + m11 * y + m21 * z + m31;
						float wz = m02 * x + m12 * y + m22 * z + m32;
						// squared distances are never negative, so their float bits sort like the numbers
						bits[i] = Float.floatToRawIntBits(wx * wx + wy * wy + wz * wz);
						order[i] = i;
					}
					radixSort(q);
					sorted = new int[q];
					for (int i = 0; i < q; i++) {
						sorted[i] = order[q - 1 - i]; // largest distance first
					}
					if (cache != null) {
						int slot = cache.next;
						cache.next = (slot + 1) % SORT_SLOTS;
						cache.cam[slot * 3] = lx;
						cache.cam[slot * 3 + 1] = ly;
						cache.cam[slot * 3 + 2] = lz;
						cache.orders[slot] = sorted;
					}
				}
				first[partIndex++] = written;
				for (int i = 0; i < q; i++) {
					int v = sorted[i] * 4;
					indexBytes.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
				}
				written += q * 6;
			}
		}
		indexBytes.flip();
		com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder().writeToBuffer(sortedIndices.slice(0, indexBytes.remaining()), indexBytes);
		return first;
	}

	private static int[] bits = new int[1024];
	private static int[] spare = new int[1024];
	private static final int[] COUNT = new int[2048];

	// Sorts order[0..n) by bits[order[i]] ascending: LSD radix, 3 passes of 11 bits, stable, no allocation. ~10x faster
	// than a comparison sort on these sizes (the quicksort was 8% of the frame at LuckySMP's spawn).
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
		// three passes: the result sits in 'from' (= spare); keep the arrays' roles straight for the caller
		if (from != order) {
			System.arraycopy(from, 0, order, 0, n);
		}
	}

	private static void recycleAll() {
		for (Draw d : PENDING) {
			d.mesh = null;
			d.transforms = null;
			if (SPARE.size() < 512) {
				SPARE.add(d);
			}
		}
		PENDING.clear();
	}

	private static com.mojang.blaze3d.pipeline.RenderPipeline cutoutPipeline() {
		if (cutoutPipeline == null) {
			cutoutPipeline = com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.veloclient.velo.client.mixin.GpuCosmeticsPipelinesAccessor.velo$heldBase())
					.withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "pipeline/held_cutout"))
					.withVertexShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "core/held_mesh"))
					.withShaderDefine("ALPHA_CUTOUT", 0.1f)
					.build();
		}
		return cutoutPipeline;
	}

	private static com.mojang.blaze3d.pipeline.RenderPipeline translucentPipeline() {
		if (translucentPipeline == null) {
			translucentPipeline = com.mojang.blaze3d.pipeline.RenderPipeline.builder(net.veloclient.velo.client.mixin.GpuCosmeticsPipelinesAccessor.velo$heldBase())
					.withLocation(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "pipeline/held_translucent"))
					.withVertexShader(net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", "core/held_mesh"))
					.withShaderDefine("ALPHA_CUTOUT", 0.1f)
					.withColorTargetState(new com.mojang.blaze3d.pipeline.ColorTargetState(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT))
					.build();
		}
		return translucentPipeline;
	}

	// A resource pack with its own core/item shader (special effects) must keep vanilla's path - our vertex shader would
	// skip whatever it does.
	private static boolean packShaders() {
		if (packShaders == null) {
			var manager = net.minecraft.client.Minecraft.getInstance().getResourceManager();
			boolean replaced = false;
			for (String file : new String[] {"shaders/core/item.vsh", "shaders/core/item.fsh"}) {
				var resource = manager.getResource(net.minecraft.resources.Identifier.withDefaultNamespace(file));
				if (resource.isPresent() && !"vanilla".equals(resource.get().sourcePackId())) {
					replaced = true;
				}
			}
			packShaders = replaced;
			if (replaced) {
				net.veloclient.velo.VeloClient.LOGGER.info("Velo GPU item meshes: a resource pack replaces core/item - items stay on the normal path");
			}
		}
		return packShaders;
	}

	private static boolean offscreen() {
		return com.mojang.blaze3d.systems.RenderSystem.outputColorTextureOverride != null
				|| com.mojang.blaze3d.systems.RenderSystem.outputDepthTextureOverride != null;
	}

	private static void close(Mesh mesh) {
		if (mesh != null && mesh.vertices != null) {
			mesh.vertices.close();
		}
	}

	// Resources reloaded: models were baked again (new quads) and packs may have changed - start over.
	public static void clear() {
		for (Mesh mesh : MESHES.values()) {
			close(mesh);
		}
		MESHES.clear();
		packShaders = null;
		recycleAll();
	}

	private static void fail(Throwable t) {
		if (!failed) {
			failed = true;
			net.veloclient.velo.VeloClient.LOGGER.error("Velo GPU item meshes failed - back to the normal path for this session", t);
		}
	}
	public static boolean supported() {
		return true;
	}
	*///?} else {
	public static void clear() {
	}

	public static boolean supported() {
		return false;
	}
	//?}
}
