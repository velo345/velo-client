package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*import net.minecraft.client.texture.NativeImage;

/^* 1.21.1's NativeImage pixels are ABGR ints (getColor / setColor); 1.21.2+ added the ARGB accessors Velo uses. ^/
public final class ImageCompat {

	private ImageCompat() {
	}

	private static int swap(int c) {
		return c & 0xFF00FF00 | (c & 0xFF) << 16 | (c >> 16) & 0xFF;
	}

	public static int getColorArgb(NativeImage image, int x, int y) {
		return swap(image.getColor(x, y));
	}

	/^* The native pixel address (1.21.1 keeps it private: the first long field is it). ^/
	public static long pointer(NativeImage image) {
		try {
			for (var f : NativeImage.class.getDeclaredFields()) {
				if (f.getType() == long.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
					f.setAccessible(true);
					return f.getLong(image);
				}
			}
		} catch (ReflectiveOperationException ignored) {
		}
		return 0;
	}

	public static void setColorArgb(NativeImage image, int x, int y, int argb) {
		image.setColor(x, y, swap(argb));
	}
}
*///?}
