package net.veloclient.velo.client.util;

/**
 * Implemented on 26.2's BufferBuilder (see BufferBuilderBulkMixin): reserves room for several
 * whole vertices of the vanilla ENTITY vertex format at once, so a model cube can write its 24
 * vertices straight into the buffer instead of paying the per-vertex begin/validate/grow calls.
 */
public interface BulkVertexSink {

	/** Size of one ENTITY-format vertex: position, color, uv, overlay, light, normal (+1 pad byte). */
	int ENTITY_VERTEX_SIZE = 36;

	/**
	 * Reserves {@code count} ENTITY-format vertices and returns the address of the first, or -1 when
	 * this builder can't take the bulk path right now (other format, not building, unfinished
	 * vertex, ...) - the caller then writes through the normal VertexConsumer calls.
	 */
	long velo$reserveEntityVertices(int count);

	/** Size of one POSITION_COLOR vertex (the GUI's plain shapes): 3 floats + RGBA bytes. */
	int POSITION_COLOR_VERTEX_SIZE = 16;

	/**
	 * Reserves {@code count} POSITION_COLOR vertices (position at 0, colour as ABGR at 12) and returns the address of
	 * the first, or -1 when this builder can't take the bulk path (other format, unfinished vertex, ...).
	 */
	default long velo$reservePositionColorVertices(int count) {
		return -1L;
	}

	/**
	 * Reserves {@code count} whole vertices of whatever format this builder has (the caller writes every element by its
	 * offset), or -1 when it can't (unfinished vertex, lines, big-endian...).
	 */
	default long velo$reserveRawVertices(int count) {
		return -1L;
	}
}
