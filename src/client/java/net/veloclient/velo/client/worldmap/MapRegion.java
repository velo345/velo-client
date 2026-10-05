package net.veloclient.velo.client.worldmap;

/**
 * One 256x256-block square of the map: per column the block color (0 = not explored yet), the
 * top-block height and a biome index (into the store's palette). {@link #version} goes up on every
 * change so textures know when to rebuild.
 */
final class MapRegion {

	static final int SIZE = 256;

	final int rx;
	final int rz;
	final int[] rgb = new int[SIZE * SIZE];
	final short[] height = new short[SIZE * SIZE];
	final short[] biome = new short[SIZE * SIZE];
	volatile int version;
	boolean dirty;

	MapRegion(int rx, int rz) {
		this.rx = rx;
		this.rz = rz;
	}

	static long key(int rx, int rz) {
		return ((long) rx << 32) ^ (rz & 0xFFFFFFFFL);
	}

	void set(int localX, int localZ, int color, int y, int biomeIndex) {
		int i = localZ * SIZE + localX;
		int argb = color == 0 ? 0 : 0xFF000000 | color;
		if (rgb[i] != argb || height[i] != (short) y || biome[i] != (short) biomeIndex) {
			rgb[i] = argb;
			height[i] = (short) y;
			biome[i] = (short) biomeIndex;
			dirty = true;
			version++;
		}
	}
}
