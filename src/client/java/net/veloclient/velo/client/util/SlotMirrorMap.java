package net.veloclient.velo.client.util;

import net.veloclient.velo.client.modules.performance.PerformanceBoostModule;

import java.util.HashMap;

/**
 * A HashMap that mirrors every entry into its key when the key is an {@link AllocationSlots}, and
 * answers {@link #get} from the key's own slots. Used for vanilla's terrain buffer allocation maps
 * (UberGpuBuffer), which are only ever changed through put/remove/clear - all mirrored here - so
 * the slots always agree with the map; anything the slots can't answer falls back to the map.
 */
public final class SlotMirrorMap<K, V> extends HashMap<K, V> {

	public SlotMirrorMap(int initialCapacity) {
		super(initialCapacity);
	}

	@Override
	@SuppressWarnings("unchecked")
	public V get(Object key) {
		if (PerformanceBoostModule.fastDrawPath && key instanceof AllocationSlots slots) {
			Object value = slots.velo$slotGet(this);
			if (value != AllocationSlots.UNKNOWN) {
				return (V) value;
			}
		}
		return super.get(key);
	}

	@Override
	public V put(K key, V value) {
		if (key instanceof AllocationSlots slots) {
			slots.velo$slotPut(this, value);
		}
		return super.put(key, value);
	}

	@Override
	public V remove(Object key) {
		if (key instanceof AllocationSlots slots) {
			slots.velo$slotRemove(this);
		}
		return super.remove(key);
	}

	@Override
	public void clear() {
		for (K key : keySet()) {
			if (key instanceof AllocationSlots slots) {
				slots.velo$slotRemove(this);
			}
		}
		super.clear();
	}
}
