package net.veloclient.velo.client.modules.performance;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Stops rendering purely decorative things once they're far enough away that they're a few
 * pixels big anyway: armor stands (server hologram text is usually a stack of these), item frames
 * and paintings, dropped items, XP orbs, signs and other block entities (chests, banners, skulls,
 * shulker boxes...). Server lobbies and hubs are full of exactly these, and each one is drawn
 * separately every frame - vanilla keeps them all out to 64+ blocks, and neither Sodium nor
 * frustum culling reduces how many are submitted.
 *
 * <p>Players and mobs are never culled here (that would be a gameplay difference, not a
 * rendering one), and beacons / end gateways keep their vanilla distance since their beams are
 * meant to be seen from far away. Rendering-only: nothing about the world or the server changes.
 * The checks run in {@code RenderCullingEntityMixin} / {@code RenderCullingBlockEntityMixin}.
 */
public final class RenderCullingModule extends AbstractModule implements Configurable {

	/** Slider value meaning "don't cull this at all". */
	private static final int OFF = 128;

	private static volatile RenderCullingModule instance;

	private int armorStandDistance = 48;
	private int frameDistance = 48;
	private int itemDistance = 32;
	private int xpDistance = 24;
	private int signDistance = 32;
	private int blockEntityDistance = 48;

	// Squared thresholds, precomputed so the per-entity check is a couple of multiplies.
	private volatile double armorStandSq;
	private volatile double frameSq;
	private volatile double itemSq;
	private volatile double xpSq;
	private volatile double signSq;
	private volatile double blockEntitySq;

	public RenderCullingModule() {
		super("render-culling", "Render Culling",
				"Stops drawing far-away armor stands, item frames, dropped items, XP, signs and chests - big FPS win in busy lobbies.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
		recompute();
	}

	private void recompute() {
		armorStandSq = squared(armorStandDistance);
		frameSq = squared(frameDistance);
		itemSq = squared(itemDistance);
		xpSq = squared(xpDistance);
		signSq = squared(signDistance);
		blockEntitySq = squared(blockEntityDistance);
	}

	private static double squared(int distance) {
		return distance >= OFF ? Double.MAX_VALUE : (double) distance * distance;
	}

	private static RenderCullingModule active() {
		RenderCullingModule module = instance;
		return module != null && module.isEnabled() ? module : null;
	}

	/** Kinds the entity mixin maps vanilla entity types onto. */
	public enum EntityKind { ARMOR_STAND, FRAME, ITEM, XP }

	/** True if an entity of {@code kind} at squared distance {@code distanceSq} from the camera should be skipped. */
	public static boolean cullEntity(EntityKind kind, double distanceSq) {
		RenderCullingModule module = active();
		if (module == null) {
			return false;
		}
		double limit = switch (kind) {
			case ARMOR_STAND -> module.armorStandSq;
			case FRAME -> module.frameSq;
			case ITEM -> module.itemSq;
			case XP -> module.xpSq;
		};
		return distanceSq > limit;
	}

	/** Same for a block entity; {@code sign} selects the sign distance instead of the general one. */
	public static boolean cullBlockEntity(boolean sign, double distanceSq) {
		RenderCullingModule module = active();
		if (module == null) {
			return false;
		}
		return distanceSq > (sign ? module.signSq : module.blockEntitySq);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				slider("Armor Stands", () -> armorStandDistance, v -> armorStandDistance = v),
				slider("Item Frames & Paintings", () -> frameDistance, v -> frameDistance = v),
				slider("Dropped Items", () -> itemDistance, v -> itemDistance = v),
				slider("XP Orbs", () -> xpDistance, v -> xpDistance = v),
				slider("Signs", () -> signDistance, v -> signDistance = v),
				slider("Chests, Banners, Heads etc.", () -> blockEntityDistance, v -> blockEntityDistance = v));
	}

	private ConfigField slider(String label, java.util.function.IntSupplier get, java.util.function.IntConsumer set) {
		return new ConfigField.SliderField(label, 8, OFF, get::getAsInt, v -> {
			set.accept((int) Math.round(v));
			recompute();
		}, v -> (int) Math.round(v) >= OFF ? "Vanilla" : Math.round(v) + " blocks");
	}
}
