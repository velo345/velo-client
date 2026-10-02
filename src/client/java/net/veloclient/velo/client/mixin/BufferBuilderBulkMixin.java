package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.2 {
/*import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import net.veloclient.velo.client.util.BulkVertexSink;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.ByteOrder;
*///?}

/**
 * Bulk vertex reservation for {@link net.veloclient.velo.client.util.BulkVertexSink} (26.2). Does
 * exactly what {@code count} calls of vanilla's beginVertex would - same validation, same vertex
 * count and last-vertex pointer afterwards - in one buffer reservation.
 */
//? if >=26.2 {
/*@Mixin(BufferBuilder.class)
public abstract class BufferBuilderBulkMixin implements BulkVertexSink {

	@Shadow
	@Final
	private ByteBufferBuilder buffer;
	@Shadow
	private long vertexPointer;
	@Shadow
	private int vertices;
	@Shadow
	@Final
	private PrimitiveTopology primitiveTopology;
	@Shadow
	@Final
	private boolean entityFormat;
	@Shadow
	@Final
	private int vertexSize;
	@Shadow
	private int elementsToFill;
	@Shadow
	private boolean building;

	@Override
	public long velo$reserveEntityVertices(int count) {
		// Anything unusual takes vanilla's path, which also produces vanilla's errors.
		if (count <= 0 || !entityFormat || !building || elementsToFill != 0 || ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN
				|| vertexSize != ENTITY_VERTEX_SIZE || primitiveTopology == PrimitiveTopology.LINES
				|| vertices + count > 16777215) {
			return -1L;
		}
		long pointer = buffer.reserve(vertexSize * count);
		vertices += count;
		vertexPointer = pointer + (long) vertexSize * (count - 1);
		return pointer;
	}
}
*///?} else if >=26.1 {
/*@Mixin(net.minecraft.client.renderer.LevelRenderer.class)
public abstract class BufferBuilderBulkMixin {
}
*///?} else {
@Mixin(net.minecraft.client.render.WorldRenderer.class)
public abstract class BufferBuilderBulkMixin {
}
//?}
