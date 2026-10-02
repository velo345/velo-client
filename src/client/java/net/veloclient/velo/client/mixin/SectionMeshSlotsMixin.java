package net.veloclient.velo.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
/*import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.veloclient.velo.client.util.AllocationSlots;
import org.spongepowered.asm.mixin.Unique;
*///?}

/**
 * Lets a compiled chunk section mesh hold its own terrain buffer allocations (26.x): one slot per
 * uber buffer that holds it - three block layers times vertex + index buffer. See
 * {@link net.veloclient.velo.client.util.SlotMirrorMap}. Plain fields rather than arrays, so the
 * lookup touches nothing but the mesh object the renderer just read anyway.
 */
//? if >=26.1 {
/*@Mixin(CompiledSectionMesh.class)
public abstract class SectionMeshSlotsMixin implements AllocationSlots {

	@Unique
	private Object velo$owner0, velo$owner1, velo$owner2, velo$owner3, velo$owner4, velo$owner5;
	@Unique
	private Object velo$value0, velo$value1, velo$value2, velo$value3, velo$value4, velo$value5;
	// Set once more owners than slots ever held this mesh; from then on the maps answer.
	@Unique
	private boolean velo$overflow;

	@Override
	public Object velo$slotGet(Object owner) {
		if (velo$overflow) {
			return UNKNOWN;
		}
		if (velo$owner0 == owner) {
			return velo$value0;
		}
		if (velo$owner1 == owner) {
			return velo$value1;
		}
		if (velo$owner2 == owner) {
			return velo$value2;
		}
		if (velo$owner3 == owner) {
			return velo$value3;
		}
		if (velo$owner4 == owner) {
			return velo$value4;
		}
		if (velo$owner5 == owner) {
			return velo$value5;
		}
		return null; // every put for this key went through a slot, so the map has nothing either
	}

	@Override
	public void velo$slotPut(Object owner, Object value) {
		if (velo$overflow) {
			return;
		}
		// Existing slot for this owner first - claiming a freed earlier slot instead would leave a
		// stale duplicate behind.
		if (velo$owner0 == owner) {
			velo$value0 = value;
		} else if (velo$owner1 == owner) {
			velo$value1 = value;
		} else if (velo$owner2 == owner) {
			velo$value2 = value;
		} else if (velo$owner3 == owner) {
			velo$value3 = value;
		} else if (velo$owner4 == owner) {
			velo$value4 = value;
		} else if (velo$owner5 == owner) {
			velo$value5 = value;
		} else if (velo$owner0 == null) {
			velo$owner0 = owner;
			velo$value0 = value;
		} else if (velo$owner1 == null) {
			velo$owner1 = owner;
			velo$value1 = value;
		} else if (velo$owner2 == null) {
			velo$owner2 = owner;
			velo$value2 = value;
		} else if (velo$owner3 == null) {
			velo$owner3 = owner;
			velo$value3 = value;
		} else if (velo$owner4 == null) {
			velo$owner4 = owner;
			velo$value4 = value;
		} else if (velo$owner5 == null) {
			velo$owner5 = owner;
			velo$value5 = value;
		} else {
			velo$overflow = true;
		}
	}

	@Override
	public void velo$slotRemove(Object owner) {
		if (velo$owner0 == owner) {
			velo$owner0 = null;
			velo$value0 = null;
		} else if (velo$owner1 == owner) {
			velo$owner1 = null;
			velo$value1 = null;
		} else if (velo$owner2 == owner) {
			velo$owner2 = null;
			velo$value2 = null;
		} else if (velo$owner3 == owner) {
			velo$owner3 = null;
			velo$value3 = null;
		} else if (velo$owner4 == owner) {
			velo$owner4 = null;
			velo$value4 = null;
		} else if (velo$owner5 == owner) {
			velo$owner5 = null;
			velo$value5 = null;
		}
	}
}
*///?} else {
@Mixin(net.minecraft.client.render.WorldRenderer.class)
public abstract class SectionMeshSlotsMixin {
}
//?}
