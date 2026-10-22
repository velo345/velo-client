package net.veloclient.velo.client.skins;

//? if <26.1 {
import net.minecraft.util.Identifier;
//?} else {
/*import net.minecraft.resources.Identifier;
*///?}

/**
 * The skin the Locker's Skin tab is trying on: while set, your own player renders with it (see
 * {@code LocalPlayerSkinCapeMixin}), so the live preview shows it together with your cape, wings and aura.
 */
public final class SkinTryOn {

	private static volatile Identifier texture;
	private static volatile boolean slim;

	private SkinTryOn() {
	}

	public static void set(Identifier skin, boolean slimArms) {
		texture = skin;
		slim = slimArms;
	}

	public static void clear() {
		texture = null;
	}

	/** The texture to wear instead of your real skin, or null. */
	public static Identifier texture() {
		return texture;
	}

	public static boolean slim() {
		return slim;
	}
}
