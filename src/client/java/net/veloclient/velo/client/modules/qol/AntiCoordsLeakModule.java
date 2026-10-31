package net.veloclient.velo.client.modules.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.veloclient.velo.client.modules.rendering.AntiDeepslateModule;
import net.veloclient.velo.client.privacy.ScreenShareDetector;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Anti Coords Leak: while you stream or screen share, every coordinate on screen - chat, F3, the HUD, waypoints,
 * Litematica's menus and overlays, minimaps, any mod - turns into Minecraft's scrambled magic glyphs (see
 * {@link net.veloclient.velo.client.privacy.CoordScrambler}). Automatic (the default) switches it on by itself within
 * about a second of Discord starting to share your screen or the game ({@link ScreenShareDetector}) and off again when
 * you stop; On keeps it on; Off never. It can also switch on {@link AntiDeepslateModule} meanwhile, so deepslate
 * rotations, ores and bedrock patterns can't give your base away either - and while hiding, dropped items fly off in
 * random directions ({@link net.veloclient.velo.client.privacy.DropScrambler}), water has one colour in every biome and
 * the minimap is blurred, faked or empty ({@link net.veloclient.velo.client.privacy.MinimapPrivacy}).
 */
public final class AntiCoordsLeakModule extends AbstractModule implements Configurable {

	public static final String OFF = "Off";
	public static final String AUTO = "Automatic";
	public static final String ON = "On";
	private static final List<String> MODES = List.of(AUTO, ON, OFF);
	public static final String SCRAMBLED = "Scrambled";
	public static final String FAKE = "Fake coordinates";
	/** Vanilla's plains water colour - what every biome's water looks like while hiding. */
	public static final int WATER_COLOR = 0x3F76E4;
	private static final List<String> MINIMAP_STYLES = List.of(net.veloclient.velo.client.privacy.MinimapPrivacy.BLURRED,
			net.veloclient.velo.client.privacy.MinimapPrivacy.FAKE, net.veloclient.velo.client.privacy.MinimapPrivacy.HIDDEN,
			net.veloclient.velo.client.privacy.MinimapPrivacy.NORMAL);

	/** Read on the render thread for every line of text - kept as one volatile flag. */
	private static volatile boolean hiding;

	private String mode = AUTO;
	private boolean antiDeepslate = true;
	private boolean notify = true;
	private String style = SCRAMBLED;
	private boolean scrambleDrops = true;
	private boolean sameWater = true;
	private String minimap = net.veloclient.velo.client.privacy.MinimapPrivacy.BLURRED;
	/** Read for every water colour lookup (chunk builder threads). */
	private static volatile boolean uniformWater;

	public AntiCoordsLeakModule() {
		super("anti-coords-leak", "Anti Coords Leak",
				"Hides every coordinate on screen (chat, F3, HUD, waypoints, Litematica, any mod) while you screen share on Discord - "
						+ "automatically, or always on.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		ClientTickEvents.END_CLIENT_TICK.register(client -> update());
	}

	/** True while coordinates are being hidden right now. */
	public static boolean hiding() {
		return hiding;
	}

	/** True while water should look the same in every biome. */
	public static boolean uniformWater() {
		return uniformWater;
	}

	/** Water colours are baked into the chunk meshes: switching them rebuilds the chunks. */
	private static void setUniformWater(boolean on) {
		if (uniformWater != on) {
			uniformWater = on;
			net.veloclient.velo.client.modules.performance.PerformanceBoostModule.reloadRendering();
		}
	}

	private void update() {
		net.veloclient.velo.client.privacy.CoordScrambler.fake = FAKE.equals(style);
		net.veloclient.velo.client.privacy.MinimapPrivacy.style = minimap;
		boolean on = isEnabled() && !mode.equals(OFF);
		ScreenShareDetector.watch(on && mode.equals(AUTO));
		boolean now = on && (mode.equals(ON) || ScreenShareDetector.sharing());
		net.veloclient.velo.client.privacy.DropScrambler.tick(now && scrambleDrops);
		if (now == hiding) {
			return;
		}
		hiding = now;
		AntiDeepslateModule.setForced(now && antiDeepslate);
		setUniformWater(now && sameWater);
		if (notify && mode.equals(AUTO)) {
			NotificationOverlay.show(new NotificationOverlay.Toast(null, null, now ? "Screen share detected" : "Screen share ended",
					now ? "Coordinates are hidden" + (antiDeepslate ? ", Anti Deepslate is on" : "") : "Coordinates are visible again",
					3000, null, null, null));
		}
	}

	@Override
	public void onDisable() {
		ScreenShareDetector.watch(false);
		if (hiding) {
			hiding = false;
			AntiDeepslateModule.setForced(false);
			setUniformWater(false);
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Hide Coordinates", MODES, () -> mode, v -> mode = v),
				new ConfigField.HeaderField(status()),
				// Fake coordinates: believable numbers that follow you as you move (a fixed offset per session), instead of glyphs.
				new ConfigField.ChoiceField("Show Them As", List.of(SCRAMBLED, FAKE), () -> style, v -> {
					style = v;
					net.veloclient.velo.client.privacy.CoordScrambler.fake = FAKE.equals(v);
				}),
				new ConfigField.ToggleField("Also Turn On Anti Deepslate", () -> antiDeepslate, v -> {
					antiDeepslate = v;
					AntiDeepslateModule.setForced(hiding && v);
				}),
				new ConfigField.ToggleField("Dropped Items Fly In Random Directions", () -> scrambleDrops, v -> scrambleDrops = v),
				new ConfigField.ToggleField("Same Water Color In Every Biome", () -> sameWater, v -> {
					sameWater = v;
					setUniformWater(hiding && v);
				}),
				new ConfigField.ChoiceField("Minimap While Hidden", MINIMAP_STYLES, () -> minimap, v -> {
					minimap = v;
					net.veloclient.velo.client.privacy.MinimapPrivacy.style = v;
				}),
				new ConfigField.ToggleField("Notify When Sharing Starts / Ends", () -> notify, v -> notify = v));
	}

	private String status() {
		if (mode.equals(OFF)) {
			return "Coordinates always show";
		}
		if (mode.equals(ON)) {
			return "Coordinates are always hidden";
		}
		return ScreenShareDetector.sharing() ? "Sharing now (" + ScreenShareDetector.source() + ") - coordinates hidden"
				: "Hides coordinates while Discord shares your screen";
	}
}
