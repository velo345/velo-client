package net.veloclient.velo.client.skins;

//? if <26.1 {
import net.minecraft.util.Identifier;
//?} else {
/*import net.minecraft.resources.Identifier;
*///?}

/**
 * The skin the Locker's Skin tab is trying on: while set, your own player renders with it (see
 * {@code LocalPlayerSkinCapeMixin}), so the live preview shows it together with your cape, wings and aura.
 *
 * <p>Also holds the skin you just equipped from the Locker for the rest of the game session: Mojang
 * has it right away, but the server you're on (or your own world) only hands out the new profile
 * when you rejoin - until then your own player already wears it.
 */
public final class SkinTryOn {

	private static volatile Identifier texture;
	private static volatile boolean slim;
	private static volatile Identifier equipped;
	private static volatile boolean equippedSlim;

	private SkinTryOn() {
	}

	public static void set(Identifier skin, boolean slimArms) {
		texture = skin;
		slim = slimArms;
	}

	public static void clear() {
		texture = null;
	}

	public static void setEquipped(Identifier skin, boolean slimArms) {
		equipped = skin;
		equippedSlim = slimArms;
	}

	/** The texture to wear instead of your real skin, or null. */
	public static Identifier texture() {
		Identifier trying = texture;
		return trying != null ? trying : equipped;
	}

	public static boolean slim() {
		return texture != null ? slim : equippedSlim;
	}
}
