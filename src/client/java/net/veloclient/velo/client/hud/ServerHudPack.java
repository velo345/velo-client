package net.veloclient.velo.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

/**
 * Whether the server's resource pack restyles or hides the status bars - MCC Island and others hide vanilla's hearts and
 * food by making those sprites see-through or moving them with the pack, depending on the game mode. Velo's Hearts /
 * Hunger modules then step aside, so the server's own HUD wins (bug report 2026-11-20: hearts stayed under MCC's
 * action bar). Decided on every world/dimension change and reload, re-checked every second mid-game (see refresh);
 * only the topmost pack per file counts.
 */
public final class ServerHudPack {

	private static java.lang.ref.WeakReference<Object> checkedWorld = new java.lang.ref.WeakReference<>(null);
	private static boolean checked;
	private static boolean reloadPending;
	private static long checkedAt;
	private static int heartsVotes;
	private static int foodVotes;
	private static int armorVotes;
	private static boolean hearts;
	private static boolean food;
	private static boolean armor;

	private ServerHudPack() {
	}

	public static boolean overridesHearts() {
		refresh();
		return hearts;
	}

	public static boolean overridesFood() {
		refresh();
		return food;
	}

	/** The pack restyles or hides the armor row (same treatment as hearts, owner 1.5.1). */
	public static boolean overridesArmor() {
		refresh();
		return armor;
	}

	/**
	 * MCC Island hides the bars in some games and keeps them in others, so this is checked all the time - but never
	 * trusted from a single look:
	 * - new world / dimension, or a resource reload just finished: decided at once (nothing on screen to flicker yet);
	 * - mid-game: re-checked every second, and a different answer only takes over once it came out the same twice in a
	 *   row - one odd reading (a half-swapped pack list) can't make the hearts blink;
	 * - while a reload runs, or when the check throws: the last answer stays.
	 */
	private static void refresh() {
		var client = MinecraftClient.getInstance();
		Object world;
		boolean overlay;
		//? if <26.1 {
		world = client.world;
		overlay = client.getOverlay() != null;
		//?} else if <26.2 {
		/*world = client.level;
		overlay = client.getOverlay() != null;
		*///?} else {
		/*world = client.level;
		overlay = client.gui.overlay() != null;
		*///?}
		if (overlay) {
			reloadPending = true; // decide again once it's done
			return;
		}
		long now = System.nanoTime();
		boolean fresh = !checked || reloadPending || checkedWorld.get() != world;
		if (!fresh && now - checkedAt < 1_000_000_000L) {
			return;
		}
		checkedAt = now;
		boolean h;
		boolean f;
		boolean a;
		try {
			h = fromServer("hud/heart/full") || fromServer("hud/heart/container");
			f = fromServer("hud/food_full") || fromServer("hud/food_empty");
			a = fromServer("hud/armor_full") || fromServer("hud/armor_empty");
		} catch (RuntimeException | LinkageError e) {
			return; // keep the last answer, try again in a second
		}
		if (fresh) {
			if (h != hearts || f != food || !checked) {
				log((checked ? (reloadPending ? "after reload" : "new world/dimension") : "first check"), h, f);
			}
			checked = true;
			reloadPending = false;
			checkedWorld = new java.lang.ref.WeakReference<>(world);
			if (a != armor) {
				net.veloclient.velo.VeloClient.LOGGER.info("Velo server HUD: armor {} by the server pack", a ? "taken over" : "not touched");
			}
			hearts = h;
			food = f;
			armor = a;
			heartsVotes = 0;
			foodVotes = 0;
			armorVotes = 0;
			return;
		}
		// mid-game: switch only on the second reading in a row that disagrees
		heartsVotes = h != hearts ? heartsVotes + 1 : 0;
		if (heartsVotes >= 2) {
			log("mid-game (hearts)", h, food);
			hearts = h;
			heartsVotes = 0;
		}
		armorVotes = a != armor ? armorVotes + 1 : 0;
		if (armorVotes >= 2) {
			net.veloclient.velo.VeloClient.LOGGER.info("Velo server HUD: armor {} by the server pack (mid-game)", a ? "taken over" : "not touched");
			armor = a;
			armorVotes = 0;
		}
		foodVotes = f != food ? foodVotes + 1 : 0;
		if (foodVotes >= 2) {
			log("mid-game (food)", hearts, f);
			food = f;
			foodVotes = 0;
		}
	}

	/** Every change of the decision goes to latest.log - to see whether (and why) it flips on a server. */
	private static void log(String why, boolean h, boolean f) {
		net.veloclient.velo.VeloClient.LOGGER.info("Velo server HUD: hearts {} / food {} by the server pack ({})",
				h ? "taken over" : "not touched", f ? "taken over" : "not touched", why);
	}

	/** True if the sprite {@code textures/gui/sprites/<path>.png} comes from a server's resource pack. */
	private static boolean fromServer(String path) {
		var manager = MinecraftClient.getInstance().getResourceManager();
		var id = Identifier.of("minecraft", "textures/gui/sprites/" + path + ".png");
		//? if <26.1 {
		var resource = manager.getResource(id);
		return resource.isPresent() && resource.get().getPack().getInfo().source() == net.minecraft.resource.ResourcePackSource.SERVER;
		//?} else {
		/*var resource = manager.getResource(id);
		return resource.isPresent() && resource.get().source().location().source() == net.minecraft.server.packs.repository.PackSource.SERVER;
		*///?}
	}
}
