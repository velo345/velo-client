package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;
import net.veloclient.velo.client.util.BulkVertexSink;
import org.lwjgl.system.MemoryUtil;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * Transforms each distinct corner of an entity-model cube once per draw instead of once per face
 * vertex (26.x; on 1.21.11 Sodium already covers this).
 *
 * Vanilla's Cube#compile runs the pose matrix over all 24 face vertices of a cube, but a cube has
 * only 8 distinct corners - every corner is shared by three faces. Every mob, player, armor piece
 * and block entity model goes through here each frame, so this was one of the largest render-
 * thread costs with many entities around. Same inputs through the same matrix call give the
 * bit-identical vertex positions, so the output is exactly vanilla's.
 */
//? if >=26.1 {
/*@Mixin(ModelPart.Cube.class)
public abstract class ModelCubeCornerMixin {

	@Shadow
	@Final
	public ModelPart.Polygon[] polygons;

	// Distinct corner positions (x, y, z triples, already divided into world units) and, for each
	// face vertex in polygon order, the index of its corner. Built on first use; cubes never change.
	@Unique
	private float[] velo$corners;
	@Unique
	private int[] velo$cornerOf;

	@Inject(method = "compile", at = @At("HEAD"), cancellable = true, require = 0)
	private void velo$compileSharedCorners(PoseStack.Pose pose, VertexConsumer builder, int lightCoords, int overlayCoords,
			int color, CallbackInfo ci) {
		if (!PerformanceBoostModule.fastDrawPath) {
			return;
		}
		int[] cornerOf = velo$cornerOf;
		float[] corners = velo$corners;
		if (cornerOf == null) {
			velo$buildCorners();
			cornerOf = velo$cornerOf;
			corners = velo$corners;
		}
		int cornerCount = corners.length / 3;
		Matrix4f matrix = pose.pose();
		Vector3f scratch = new Vector3f();
		float[] transformed = new float[corners.length];
		for (int i = 0; i < cornerCount; i++) {
			int at = i * 3;
			matrix.transformPosition(corners[at], corners[at + 1], corners[at + 2], scratch);
			transformed[at] = scratch.x();
			transformed[at + 1] = scratch.y();
			transformed[at + 2] = scratch.z();
		}
		if (builder instanceof BulkVertexSink sink) {
			long pointer = sink.velo$reserveEntityVertices(cornerOf.length);
			if (pointer != -1L) {
				velo$writeDirect(pointer, pose, transformed, cornerOf, color, overlayCoords, lightCoords, scratch);
				ci.cancel();
				return;
			}
		}
		int k = 0;
		for (ModelPart.Polygon polygon : polygons) {
			Vector3f normal = pose.transformNormal(polygon.normal(), scratch);
			float nx = normal.x();
			float ny = normal.y();
			float nz = normal.z();
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				int at = cornerOf[k++] * 3;
				builder.addVertex(transformed[at], transformed[at + 1], transformed[at + 2], color, vertex.u(), vertex.v(),
						overlayCoords, lightCoords, nx, ny, nz);
			}
		}
		ci.cancel();
	}

	// Writes every face vertex straight into reserved ENTITY-format memory, byte for byte what
	// BufferBuilder's own entity fast path writes per vertex (26.2 only; see BulkVertexSink).
	@Unique
	private void velo$writeDirect(long pointer, PoseStack.Pose pose, float[] transformed, int[] cornerOf, int color,
			int overlayCoords, int lightCoords, Vector3f scratch) {
		int abgr = ARGB.toABGR(color);
		int k = 0;
		for (ModelPart.Polygon polygon : polygons) {
			Vector3f normal = pose.transformNormal(polygon.normal(), scratch);
			byte nx = velo$normalByte(normal.x());
			byte ny = velo$normalByte(normal.y());
			byte nz = velo$normalByte(normal.z());
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				int at = cornerOf[k++] * 3;
				MemoryUtil.memPutFloat(pointer, transformed[at]);
				MemoryUtil.memPutFloat(pointer + 4L, transformed[at + 1]);
				MemoryUtil.memPutFloat(pointer + 8L, transformed[at + 2]);
				MemoryUtil.memPutInt(pointer + 12L, abgr);
				MemoryUtil.memPutFloat(pointer + 16L, vertex.u());
				MemoryUtil.memPutFloat(pointer + 20L, vertex.v());
				MemoryUtil.memPutInt(pointer + 24L, overlayCoords);
				MemoryUtil.memPutInt(pointer + 28L, lightCoords);
				MemoryUtil.memPutByte(pointer + 32L, nx);
				MemoryUtil.memPutByte(pointer + 33L, ny);
				MemoryUtil.memPutByte(pointer + 34L, nz);
				pointer += BulkVertexSink.ENTITY_VERTEX_SIZE;
			}
		}
	}

	@Unique
	private static byte velo$normalByte(float c) {
		return (byte) ((int) (Mth.clamp(c, -1.0F, 1.0F) * 127.0F) & 0xFF);
	}

	@Unique
	private void velo$buildCorners() {
		int vertexCount = 0;
		for (ModelPart.Polygon polygon : polygons) {
			vertexCount += polygon.vertices().length;
		}
		float[] unique = new float[vertexCount * 3];
		int[] cornerOf = new int[vertexCount];
		int uniqueCount = 0;
		int k = 0;
		for (ModelPart.Polygon polygon : polygons) {
			for (ModelPart.Vertex vertex : polygon.vertices()) {
				float x = vertex.worldX();
				float y = vertex.worldY();
				float z = vertex.worldZ();
				int found = -1;
				for (int i = 0; i < uniqueCount; i++) {
					int at = i * 3;
					// Bitwise equality, so -0.0/0.0 or NaN never merge two positions vanilla would transform differently.
					if (Float.floatToRawIntBits(unique[at]) == Float.floatToRawIntBits(x)
							&& Float.floatToRawIntBits(unique[at + 1]) == Float.floatToRawIntBits(y)
							&& Float.floatToRawIntBits(unique[at + 2]) == Float.floatToRawIntBits(z)) {
						found = i;
						break;
					}
				}
				if (found < 0) {
					found = uniqueCount++;
					unique[found * 3] = x;
					unique[found * 3 + 1] = y;
					unique[found * 3 + 2] = z;
				}
				cornerOf[k++] = found;
			}
		}
		velo$corners = java.util.Arrays.copyOf(unique, uniqueCount * 3);
		velo$cornerOf = cornerOf; // published last: a concurrent first call just builds it twice
	}
}
*///?} else {
@Mixin(net.minecraft.client.render.WorldRenderer.class)
public abstract class ModelCubeCornerMixin {
}
//?}
