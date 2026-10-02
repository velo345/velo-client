package net.veloclient.velo.client.util;

/**
 * A key object that carries its own values for a handful of maps (see {@link SlotMirrorMap}):
 * terrain section meshes remember their GPU buffer allocations themselves, so the per-section,
 * per-frame lookup never has to hash into a map holding thousands of meshes.
 */
public interface AllocationSlots {

	/** Returned by {@link #velo$slotGet} when this key's slots can't answer for {@code owner}. */
	Object UNKNOWN = new Object();

	/** The value stored for {@code owner}, null if none, or {@link #UNKNOWN} if the slots overflowed. */
	Object velo$slotGet(Object owner);

	void velo$slotPut(Object owner, Object value);

	void velo$slotRemove(Object owner);
}
