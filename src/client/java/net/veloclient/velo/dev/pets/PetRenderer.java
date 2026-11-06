package net.veloclient.velo.dev.pets;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws your pet in the world each frame, after the entities: every cuboid and skinned membrane of its {@link PetModel}
 * posed by {@link PetBrain}, lit by the world at its position, with a second glowing pass ({@code eyes} render type)
 * for the emissive texture. Quads go out with corners [0, 2, 3, 1] of each box face (three.js' BoxGeometry order),
 * membrane triangles as quads with the last corner repeated.
 */
public final class PetRenderer {

	private PetRenderer() {
	}

	/** One material's quads this frame: x, y, z, u, v, nx, ny, nz per corner, camera-relative. */
	private static final class Batch {
		final int material;
		float[] data = new float[4096];
		int size;

		Batch(int material) {
			this.material = material;
		}

		void put(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
			if (size + 8 > data.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
			}
			data[size++] = x;
			data[size++] = y;
			data[size++] = z;
			data[size++] = u;
			data[size++] = v;
			data[size++] = nx;
			data[size++] = ny;
			data[size++] = nz;
		}
	}

	/** Corner order [0, 2, 3, 1] of each box face (made once, not per face per frame). */
	private static final int[][] FACE_ORDER = new int[6][];

	static {
		for (int face = 0; face < 6; face++) {
			int base = face * 4;
			FACE_ORDER[face] = new int[] {base, base + 2, base + 3, base + 1};
		}
	}

	/** The drawn pet's lowest and highest point last frame, in blocks above the player's feet (riding sizes and seats by it). */
	static volatile float lastBottom;
	static volatile float lastTop = 1f;
	/**
	 * While riding: the pet's shape in its own frame at ride size 1 (blocks): {minY, maxY, centreX, centreZ, backTop}, backTop
	 * being the highest point over the middle of its body (head and tail are at the ends, so they're left out).
	 */
	static volatile float[] rideShape;

	/** This frame's batches for {@code brain}'s pose, offset by (ox, oy, oz) = player position - camera. */
	static List<Batch> build(PetBrain brain, float ox, float oy, float oz) {
		PetModel model = brain.model;
		PetPose pose = brain.out;
		int n = model.nodes.length;
		Matrix4f root = new Matrix4f().translation(ox + pose.rootP[0], oy + pose.rootP[1], oz + pose.rootP[2])
				.rotate(new org.joml.Quaternionf(pose.rootQ[0], pose.rootQ[1], pose.rootQ[2], pose.rootQ[3])).scale(pose.rootS[0], pose.rootS[1], pose.rootS[2]);
		Matrix4f[] chain = new Matrix4f[n];   // each node relative to the root
		boolean[] shown = new boolean[n];
		for (int i = 0; i < n; i++) {
			PetModel.Node node = model.nodes[i];
			Matrix4f local = new Matrix4f().translationRotateScale(pose.p[i * 3], pose.p[i * 3 + 1], pose.p[i * 3 + 2],
					pose.q[i * 4], pose.q[i * 4 + 1], pose.q[i * 4 + 2], pose.q[i * 4 + 3], pose.s[i * 3], pose.s[i * 3 + 1], pose.s[i * 3 + 2]);
			chain[i] = node.parent < 0 ? local : new Matrix4f(chain[node.parent]).mul(local);
			shown[i] = pose.v[i] > .5f && (node.parent < 0 || shown[node.parent]);
		}
		Batch[] batches = new Batch[model.materials.length];
		Vector3f[] c = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
		Matrix4f world = new Matrix4f();
		for (int i = 0; i < n; i++) {
			PetModel.Node node = model.nodes[i];
			if (node.type == PetModel.BONE || !shown[i]) {
				continue;
			}
			Batch batch = batches[node.material] == null ? (batches[node.material] = new Batch(node.material)) : batches[node.material];
			if (node.type == PetModel.BOX) {
				root.mul(chain[i], world);
				for (int face = 0; face < 6; face++) {
					int[] order = FACE_ORDER[face];
					for (int k = 0; k < 4; k++) {
						int vi = order[k];
						world.transformPosition(node.pos[vi * 3], node.pos[vi * 3 + 1], node.pos[vi * 3 + 2], c[k]);
					}
					emitQuad(batch, c, node.uv, order);
				}
			} else {
				emitSkin(batch, node, root, chain, c);
			}
		}
		List<Batch> out = new ArrayList<>();
		float minY = Float.MAX_VALUE;
		float maxY = -Float.MAX_VALUE;
		for (Batch batch : batches) {
			if (batch != null && batch.size > 0) {
				out.add(batch);
				for (int i = 1; i < batch.size; i += 8) {
					minY = Math.min(minY, batch.data[i]);
					maxY = Math.max(maxY, batch.data[i]);
				}
			}
		}
		if (minY <= maxY) {
			lastBottom = minY - oy;
			lastTop = maxY - oy;
		}
		if ((PetsModule.riding() || PetsModule.wantsRide()) && brain == PetsModule.ownBrain()) {
			measureRide(out, brain, ox, oy, oz);
		}
		return out;
	}

	/** Undoes the ride frame (facing, offset, size) for every drawn corner and stores the shape it spans. */
	private static void measureRide(List<Batch> batches, PetBrain brain, float ox, float oy, float oz) {
		float[] f = PetsModule.rideFrame();
		float a = brain.facing();
		float cos = (float) Math.cos(a);
		float sin = (float) Math.sin(a);
		float size = Math.max(0.01f, f[3]);
		float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
		int count = 0;
		for (Batch batch : batches) {
			for (int i = 0; i + 2 < batch.size; i += 8) {
				float dx = batch.data[i] - ox;
				float dz = batch.data[i + 2] - oz;
				float lx = ((dx * cos - dz * sin) - f[0]) / size;
				float ly = ((batch.data[i + 1] - oy) - f[1]) / size;
				float lz = ((dx * sin + dz * cos) - f[2]) / size;
				minX = Math.min(minX, lx); maxX = Math.max(maxX, lx);
				minY = Math.min(minY, ly); maxY = Math.max(maxY, ly);
				minZ = Math.min(minZ, lz); maxZ = Math.max(maxZ, lz);
				count++;
			}
		}
		if (count == 0) {
			return;
		}
		float cx = (minX + maxX) / 2;
		float cz = (minZ + maxZ) / 2;
		float rx = Math.max(0.05f, (maxX - minX) * 0.22f);
		float rz = Math.max(0.05f, (maxZ - minZ) * 0.22f);
		float back = -Float.MAX_VALUE;
		for (Batch batch : batches) {
			for (int i = 0; i + 2 < batch.size; i += 8) {
				float dx = batch.data[i] - ox;
				float dz = batch.data[i + 2] - oz;
				float lx = ((dx * cos - dz * sin) - f[0]) / size;
				float lz = ((dx * sin + dz * cos) - f[2]) / size;
				if (Math.abs(lx - cx) <= rx && Math.abs(lz - cz) <= rz) {
					back = Math.max(back, ((batch.data[i + 1] - oy) - f[1]) / size);
				}
			}
		}
		// Big faces (a ghost's round body) have their corners far from the middle, so the corner test above misses the
		// top and the rider sinks in: any upward face that covers the middle counts with its highest corner too.
		for (Batch batch : batches) {
			for (int q = 0; q + 31 < batch.size; q += 32) {
				if (batch.data[q + 6] < 0.4f) {
					continue;
				}
				float qMinX = Float.MAX_VALUE, qMaxX = -Float.MAX_VALUE, qMinZ = Float.MAX_VALUE, qMaxZ = -Float.MAX_VALUE, qTop = -Float.MAX_VALUE;
				for (int i = q; i < q + 32; i += 8) {
					float dx = batch.data[i] - ox;
					float dz = batch.data[i + 2] - oz;
					float lx = ((dx * cos - dz * sin) - f[0]) / size;
					float lz = ((dx * sin + dz * cos) - f[2]) / size;
					qMinX = Math.min(qMinX, lx); qMaxX = Math.max(qMaxX, lx);
					qMinZ = Math.min(qMinZ, lz); qMaxZ = Math.max(qMaxZ, lz);
					qTop = Math.max(qTop, ((batch.data[i + 1] - oy) - f[1]) / size);
				}
				if (cx >= qMinX && cx <= qMaxX && cz >= qMinZ && cz <= qMaxZ) {
					back = Math.max(back, qTop);
				}
			}
		}
		if (back == -Float.MAX_VALUE) {
			back = minY + (maxY - minY) * 0.7f;
		}
		rideShape = new float[] {minY, maxY, cx, cz, back};
	}

	private static void emitQuad(Batch batch, Vector3f[] c, float[] uv, int[] order) {
		Vector3f normal = new Vector3f(c[1]).sub(c[0]).cross(new Vector3f(c[3]).sub(c[0]));
		if (normal.lengthSquared() < 1e-12f) {
			normal.set(0, 1, 0);
		}
		normal.normalize();
		for (int k = 0; k < 4; k++) {
			int vi = order[k];
			// three.js UVs have v going up; texture rows go down
			batch.put(c[k].x, c[k].y, c[k].z, uv[vi * 2], 1 - uv[vi * 2 + 1], normal.x, normal.y, normal.z);
		}
	}

	/** A skinned membrane: each vertex is the weighted sum of its bones' (pose x inverse bind) transforms. */
	private static void emitSkin(Batch batch, PetModel.Node node, Matrix4f root, Matrix4f[] chain, Vector3f[] c) {
		int bones = node.bones.length;
		Matrix4f[] skin = new Matrix4f[bones];
		for (int j = 0; j < bones; j++) {
			skin[j] = new Matrix4f(root).mul(chain[node.bones[j]]).mul(new Matrix4f().set(node.boneInverses[j]));
		}
		int count = node.pos.length / 3;
		float[] posed = new float[count * 3];
		Vector3f tmp = new Vector3f();
		for (int vi = 0; vi < count; vi++) {
			float px = 0;
			float py = 0;
			float pz = 0;
			for (int k = 0; k < 4; k++) {
				float w = node.skinWeight[vi * 4 + k];
				if (w <= 0) {
					continue;
				}
				skin[node.skinIndex[vi * 4 + k]].transformPosition(node.pos[vi * 3], node.pos[vi * 3 + 1], node.pos[vi * 3 + 2], tmp);
				px += tmp.x * w;
				py += tmp.y * w;
				pz += tmp.z * w;
			}
			posed[vi * 3] = px;
			posed[vi * 3 + 1] = py;
			posed[vi * 3 + 2] = pz;
		}
		int[] corner = new int[4];
		for (int t = 0; t + 2 < node.index.length; t += 3) {
			for (int k = 0; k < 4; k++) {
				corner[k] = node.index[t + Math.min(k, 2)];
				c[k].set(posed[corner[k] * 3], posed[corner[k] * 3 + 1], posed[corner[k] * 3 + 2]);
			}
			Vector3f normal = new Vector3f(c[1]).sub(c[0]).cross(new Vector3f(c[2]).sub(c[0]));
			if (normal.lengthSquared() < 1e-12f) {
				normal.set(0, 1, 0);
			}
			normal.normalize();
			for (int k = 0; k < 4; k++) {
				batch.put(c[k].x, c[k].y, c[k].z, node.uv[corner[k] * 2], 1 - node.uv[corner[k] * 2 + 1], normal.x, normal.y, normal.z);
			}
		}
	}

	private static int argb(float a, float r, float g, float b) {
		return Math.round(clamp01(a) * 255) << 24 | Math.round(clamp01(r) * 255) << 16 | Math.round(clamp01(g) * 255) << 8 | Math.round(clamp01(b) * 255);
	}

	private static float clamp01(float v) {
		return Math.max(0, Math.min(1, v));
	}

	/** How strongly the glow pass draws for a material's emissive intensity (the stage's values run about 1..3). */
	private static float glow(float intensity) {
		return Math.min(1f, intensity * 0.6f);
	}

	// ---------------------------------------------------------------- effects

	/** The pictures the baked sprites use, in the exporter's order. */
	private static final String[] SPRITES = {"z", "smoke", "heart", "note", "star", "bubble"};

	/** Camera-facing quads: x, y, z, u, v and the colour as ARGB per corner. */
	private static final class FxBatch {
		float[] data = new float[2048];
		int[] colors = new int[512];
		int size;
		int corners;

		void put(float x, float y, float z, float u, float v, int color) {
			if (size + 5 > data.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
			}
			if (corners + 1 > colors.length) {
				colors = java.util.Arrays.copyOf(colors, colors.length * 2);
			}
			data[size++] = x;
			data[size++] = y;
			data[size++] = z;
			data[size++] = u;
			data[size++] = v;
			colors[corners++] = color;
		}

		void quad(Vector3f p, Vector3f right, Vector3f up, float half, int color) {
			float rx = right.x * half;
			float ry = right.y * half;
			float rz = right.z * half;
			float ux = up.x * half;
			float uy = up.y * half;
			float uz = up.z * half;
			put(p.x - rx + ux, p.y - ry + uy, p.z - rz + uz, 0, 0, color);
			put(p.x - rx - ux, p.y - ry - uy, p.z - rz - uz, 0, 1, color);
			put(p.x + rx - ux, p.y + ry - uy, p.z + rz - uz, 1, 1, color);
			put(p.x + rx + ux, p.y + ry + uy, p.z + rz + uz, 1, 0, color);
		}
	}

	/** This frame's effects: [0] the glow dots, [1..] one batch per sprite picture. Camera-relative like {@link #build}. */
	static FxBatch[] buildFx(PetBrain brain, float ox, float oy, float oz) {
		FxBatch[] out = new FxBatch[1 + SPRITES.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = new FxBatch();
		}
		// the camera's right and up axes, so every quad faces it
		float[] rot = net.veloclient.velo.client.pvp.PvpCompat.cameraRotation();
		double yaw = Math.toRadians(rot[0]);
		double pitch = Math.toRadians(rot[1]);
		Vector3f forward = new Vector3f((float) (-Math.sin(yaw) * Math.cos(pitch)), (float) -Math.sin(pitch), (float) (Math.cos(yaw) * Math.cos(pitch)));
		Vector3f right = new Vector3f(-forward.z, 0, forward.x).normalize();
		Vector3f up = new Vector3f(right).cross(forward).normalize();
		Vector3f p = new Vector3f();
		Vector3f scale = new Vector3f();
		for (PetBrain.FxSource source : brain.fx) {
			PetModel.Clip clip = source.clip;
			source.space.getScale(scale);
			float unit = scale.x;
			short[] d = clip.dots;
			for (int i = clip.dotStart[source.frame]; i < clip.dotStart[source.frame + 1]; i += 7) {
				source.space.transformPosition(d[i] / 8f, d[i + 1] / 8f, d[i + 2] / 8f, p).add(ox, oy, oz);
				float r = d[i + 3] / 100f;
				float g = d[i + 4] / 100f;
				float b = d[i + 5] / 100f;
				float peak = Math.max(r, Math.max(g, b));
				if (peak < 0.01f) {
					continue;
				}
				// the stage drew these additively and brighter than white: keep the hue, let brightness become opacity
				int color = argb(Math.min(1, peak) * source.weight, r / peak, g / peak, b / peak);
				// gl_PointSize = size * height * 0.9 / depth at a 30 degree field of view = 0.48 * size pixels of model
				out[0].quad(p, right, up, d[i + 6] / 100f * 0.482f * unit * 0.5f, color);
			}
			short[] s = clip.sprites;
			for (int i = clip.spriteStart[source.frame]; i < clip.spriteStart[source.frame + 1]; i += 9) {
				int kind = s[i];
				if (kind < 0 || kind >= SPRITES.length) {
					continue;
				}
				source.space.transformPosition(s[i + 1] / 8f, s[i + 2] / 8f, s[i + 3] / 8f, p).add(ox, oy, oz);
				int color = argb(s[i + 5] / 1000f * source.weight, s[i + 6] / 255f, s[i + 7] / 255f, s[i + 8] / 255f);
				out[1 + kind].quad(p, right, up, s[i + 4] / 100f * unit * 0.5f, color);
			}
		}
		return out;
	}

	@FunctionalInterface
	private interface FxSink {
		void accept(float x, float y, float z, int color, float u, float v);
	}

	private static void emitFx(FxBatch batch, Matrix4f pose, FxSink sink) {
		Vector3f p = new Vector3f();
		for (int i = 0, c = 0; i < batch.size; i += 5, c++) {
			pose.transformPosition(batch.data[i], batch.data[i + 1], batch.data[i + 2], p);
			sink.accept(p.x, p.y, p.z, batch.colors[c], batch.data[i + 3], batch.data[i + 4]);
		}
	}

	// ---------------------------------------------------------------- per version

	//? if <26.1 {
	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(context ->
				submit(context.matrices(), context.commandQueue()));
	}

	private static net.minecraft.util.Identifier id(String path) {
		return net.minecraft.util.Identifier.of("velo-client", path);
	}

	private static void submit(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue) {
		for (PetsModule.Frame frame : PetsModule.frames()) {
			submitOne(matrices, queue, frame);
		}
	}

	private static void submitOne(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue,
			PetsModule.Frame frame) {
		PetModel model = frame.brain().model;
		PetPose pose = frame.brain().out;
		int light = light(frame.x(), frame.y(), frame.z());
		var overlay = net.minecraft.client.render.OverlayTexture.DEFAULT_UV;
		for (Batch batch : build(frame.brain(), frame.ox(), frame.oy(), frame.oz())) {
			PetModel.Material m = model.materials[batch.material];
			float alpha = pose.opacity[batch.material];
			float[] data = batch.data;
			int size = batch.size;
			var texture = m.skin && frame.skin() != null ? (net.minecraft.util.Identifier) frame.skin() : m.color >= 0 ? id(model.textures[m.color]) : null;
			if (texture != null) {
				var layer = m.transparent ? net.minecraft.client.render.RenderLayers.entityTranslucent(texture)
						: net.minecraft.client.render.RenderLayers.entityCutoutNoCull(texture);
				int color = argb(m.transparent ? alpha : 1, 1, 1, 1);
				queue.submitCustom(matrices, layer, (entry, consumer) -> emit(data, size, entry.getPositionMatrix(), (x, y, z, u, v, nx, ny, nz) ->
						consumer.vertex(x, y, z, color, u, v, overlay, light, nx, ny, nz)));
			}
			float glow = glow(pose.emissive[batch.material]) * (m.transparent ? alpha : 1);
			if (m.emissive >= 0 && glow > 0.01f) {
				float[] tint = pose.emissiveColor;
				int color = argb(glow, tint[batch.material * 3], tint[batch.material * 3 + 1], tint[batch.material * 3 + 2]);
				queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.eyes(id(model.textures[m.emissive])), (entry, consumer) ->
						emit(data, size, entry.getPositionMatrix(), (x, y, z, u, v, nx, ny, nz) -> consumer.vertex(x, y, z, color, u, v, overlay, 0xF000F0, nx, ny, nz)));
			}
		}
		FxBatch[] fx = buildFx(frame.brain(), frame.ox(), frame.oy(), frame.oz());
		for (int i = 0; i < fx.length; i++) {
			FxBatch batch = fx[i];
			if (batch.size == 0) {
				continue;
			}
			var layer = i == 0 ? net.minecraft.client.render.RenderLayers.eyes(id("textures/pets/fx_glow.png"))
					: net.minecraft.client.render.RenderLayers.entityTranslucent(id("textures/pets/fx_" + SPRITES[i - 1] + ".png"));
			queue.submitCustom(matrices, layer, (entry, consumer) -> emitFx(batch, entry.getPositionMatrix(), (x, y, z, color, u, v) ->
					consumer.vertex(x, y, z, color, u, v, overlay, 0xF000F0, 0, 1, 0)));
		}
	}
	//?} else {
	/*public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(context ->
				submit(context.poseStack(), context.submitNodeCollector()));
	}

	private static net.minecraft.resources.Identifier id(String path) {
		return net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", path);
	}

	private static void submit(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue) {
		for (PetsModule.Frame frame : PetsModule.frames()) {
			submitOne(matrices, queue, frame);
		}
	}

	private static void submitOne(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue,
			PetsModule.Frame frame) {
		PetModel model = frame.brain().model;
		PetPose pose = frame.brain().out;
		int light = light(frame.x(), frame.y(), frame.z());
		int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
		for (Batch batch : build(frame.brain(), frame.ox(), frame.oy(), frame.oz())) {
			PetModel.Material m = model.materials[batch.material];
			float alpha = pose.opacity[batch.material];
			float[] data = batch.data;
			int size = batch.size;
			var texture = m.skin && frame.skin() != null ? (net.minecraft.resources.Identifier) frame.skin() : m.color >= 0 ? id(model.textures[m.color]) : null;
			if (texture != null) {
				var layer = m.transparent ? net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(texture)
						: net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(texture);
				int color = argb(m.transparent ? alpha : 1, 1, 1, 1);
				queue.submitCustomGeometry(matrices, layer, (pose2, consumer) -> {
					Matrix4f pm = new Matrix4f(pose2.pose());
					if (!net.veloclient.velo.client.cosmetics.render.CosmeticVertexWriter.writeSequential(consumer, size / 8, data, 8, 0, 5, 3,
							pm, new org.joml.Matrix3f(pm), color, overlay, light)) {
						emit(data, size, pm, (x, y, z, u, v, nx, ny, nz) -> consumer.addVertex(x, y, z, color, u, v, overlay, light, nx, ny, nz));
					}
				});
			}
			float glow = glow(pose.emissive[batch.material]) * (m.transparent ? alpha : 1);
			if (m.emissive >= 0 && glow > 0.01f) {
				float[] tint = pose.emissiveColor;
				int color = argb(glow, tint[batch.material * 3], tint[batch.material * 3 + 1], tint[batch.material * 3 + 2]);
				queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(id(model.textures[m.emissive])), (pose2, consumer) ->
						{
							Matrix4f pm = new Matrix4f(pose2.pose());
							if (!net.veloclient.velo.client.cosmetics.render.CosmeticVertexWriter.writeSequential(consumer, size / 8, data, 8, 0, 5, 3,
									pm, new org.joml.Matrix3f(pm), color, overlay, 0xF000F0)) {
								emit(data, size, pm, (x, y, z, u, v, nx, ny, nz) -> consumer.addVertex(x, y, z, color, u, v, overlay, 0xF000F0, nx, ny, nz));
							}
						});
			}
		}
		FxBatch[] fx = buildFx(frame.brain(), frame.ox(), frame.oy(), frame.oz());
		for (int i = 0; i < fx.length; i++) {
			FxBatch batch = fx[i];
			if (batch.size == 0) {
				continue;
			}
			var layer = i == 0 ? net.minecraft.client.renderer.rendertype.RenderTypes.eyes(id("textures/pets/fx_glow.png"))
					: net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(id("textures/pets/fx_" + SPRITES[i - 1] + ".png"));
			queue.submitCustomGeometry(matrices, layer, (pose2, consumer) -> emitFx(batch, new Matrix4f(pose2.pose()), (x, y, z, color, u, v) ->
					consumer.addVertex(x, y, z, color, u, v, overlay, 0xF000F0, 0, 1, 0)));
		}
	}
	*///?}

	/** The world's packed block and sky light at (x, y, z). */
	//? if <26.1 {
	private static int light(double x, double y, double z) {
		var world = net.minecraft.client.MinecraftClient.getInstance().world;
		return world == null ? 0xF000F0
				: net.minecraft.client.render.WorldRenderer.getLightmapCoordinates(world, net.minecraft.util.math.BlockPos.ofFloored(x, y, z));
	}
	//?} else if <26.2 {
	/*private static int light(double x, double y, double z) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		return level == null ? 0xF000F0
				: net.minecraft.client.renderer.LevelRenderer.getLightCoords(level, net.minecraft.core.BlockPos.containing(x, y, z));
	}
	*///?} else {
	/*private static int light(double x, double y, double z) {
		var level = net.minecraft.client.Minecraft.getInstance().level;
		return level == null ? 0xF000F0
				: net.minecraft.util.LightCoordsUtil.getLightCoords(level, net.minecraft.core.BlockPos.containing(x, y, z));
	}
	*///?}

	@FunctionalInterface
	private interface VertexSink {
		void accept(float x, float y, float z, float u, float v, float nx, float ny, float nz);
	}

	private static void emit(float[] data, int size, Matrix4f pose, VertexSink sink) {
		Vector3f p = new Vector3f();
		Vector3f nrm = new Vector3f();
		for (int i = 0; i < size; i += 8) {
			pose.transformPosition(data[i], data[i + 1], data[i + 2], p);
			pose.transformDirection(data[i + 5], data[i + 6], data[i + 7], nrm).normalize();
			sink.accept(p.x, p.y, p.z, data[i + 3], data[i + 4], nrm.x, nrm.y, nrm.z);
		}
	}
}
