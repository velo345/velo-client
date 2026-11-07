package net.veloclient.velo.client.modules.performance;

import net.minecraft.client.MinecraftClient;

/**
 * Velo Turbo: decides per setup whether Gnetum's HUD cache helps. Gnetum draws the HUD into its own image at 60 Hz and
 * lays that image over every frame. With a busy HUD (many modules, chat, a minimap) that saves a lot - +10% on the
 * owner's profile. With a light one the full-screen blend every frame costs more than redrawing the few elements - a fresh
 * profile was 4.5% faster without it (3305 vs 3164 FPS, 2026-11-07), where Velo's own HUD frame cache does the job.
 *
 * <p>So it is measured on the spot: a few seconds after joining a world (uncapped FPS, no VSync, no screen open), the
 * cache is switched off and on in short turns while the FPS counter is read, and the faster setting stays. It runs again
 * when the set of enabled HUD modules changes. Only Gnetum's in-memory switch is touched - never its saved config - and a
 * player who turned Gnetum off themselves keeps it off. {@code -Dvelo.noHudCacheTuner} turns this off.
 */
public final class HudCacheTuner {

	private static final boolean DISABLED = Boolean.getBoolean("velo.noHudCacheTuner");
	/**
	 * Ticks per phase (3 s) and the phases: on, off, on, off, on, off. The first pair is thrown away - right after joining,
	 * chunks still load and the FPS climb (it once read 1222 vs 2324 FPS for no reason but that); the last two pairs decide.
	 */
	private static final int PHASE_TICKS = 60;
	private static final int PHASES = 6;
	/** Off must beat on by this much over both rounds - otherwise the cache stays (it never hurts much). */
	private static final double MARGIN = 1.015;

	private static Object enabledFlag;   // Gnetum's TwoStateBoolean "enabled"
	private static Object on;
	private static Object off;
	private static boolean lookedUp;
	private static int waitTicks = -1;
	private static int phase = -1;
	private static int phaseTick;
	private static final double[] fpsSum = new double[PHASES];
	private static final int[] fpsCount = new int[PHASES];
	private static int hudKey;
	private static boolean decidedOff;

	private HudCacheTuner() {
	}

	/** Called every client tick. */
	public static void tick(MinecraftClient client) {
		if (DISABLED || !PerformanceBoostModule.turbo || !gnetum()) {
			return;
		}
		boolean playing = client.player != null && net.veloclient.velo.client.util.ClientCompat.currentScreen() == null;
		if (!playing || !uncapped(client)) {
			if (phase >= 0) {
				abort();   // measurements with a menu open or capped FPS mean nothing
			}
			if (client.player == null) {
				waitTicks = -1;
			}
			return;
		}
		int key = enabledHudModules();
		if (waitTicks < 0 || key != hudKey) {
			// a world was joined or the HUD changed: measure again in a moment
			hudKey = key;
			if (phase >= 0) {
				abort();
			}
			waitTicks = 200;
			return;
		}
		if (waitTicks > 0) {
			if (--waitTicks == 0) {
				start();
			}
			return;
		}
		if (phase < 0) {
			return;
		}
		// the first second of a phase settles (Gnetum's catch-up, the FPS counter's window)
		if (++phaseTick > 20 && phaseTick % 10 == 0) {
			fpsSum[phase] += client.getCurrentFps();
			fpsCount[phase]++;
		}
		if (phaseTick >= PHASE_TICKS) {
			phase++;
			phaseTick = 0;
			if (phase >= PHASES) {
				finish();
			} else {
				set(phase % 2 == 0);
			}
		}
	}

	private static void start() {
		java.util.Arrays.fill(fpsSum, 0);
		java.util.Arrays.fill(fpsCount, 0);
		phase = 0;
		phaseTick = 0;
		set(true);
	}

	private static void abort() {
		phase = -1;
		set(!decidedOff);
	}

	private static void finish() {
		double on1 = avg(2);
		double off1 = avg(3);
		double on2 = avg(4);
		double off2 = avg(5);
		// both rounds together: one round within noise (3059 vs 3091) must not decide against a clear other one
		decidedOff = off1 + off2 > (on1 + on2) * MARGIN;
		phase = -1;
		set(!decidedOff);
		org.slf4j.LoggerFactory.getLogger("velo-client").info("HUD cache tuner: with Gnetum {} / {} FPS, without {} / {} FPS -> {}",
				Math.round(on1), Math.round(on2), Math.round(off1), Math.round(off2), decidedOff ? "off (light HUD)" : "on");
	}

	private static double avg(int i) {
		return fpsCount[i] == 0 ? 0 : fpsSum[i] / fpsCount[i];
	}

	private static boolean uncapped(MinecraftClient client) {
		//? if <1.21.2 {
		return false;
		//?} else {
		/*return client.options.getMaxFps().getValue() >= 260 && !client.options.getEnableVsync().getValue();
		*///?}
	}

	private static int enabledHudModules() {
		int h = 1;
		for (var module : net.veloclient.velo.module.ModuleRegistry.all()) {
			if (module instanceof net.veloclient.velo.client.hud.HudModule && module.isEnabled()) {
				h = h * 31 + module.id().hashCode();
			}
		}
		return h;
	}

	/** True while Gnetum is installed and caching the HUD right now (then Velo's whole-HUD cache stays out of its way). */
	public static boolean gnetumCaching() {
		if (!gnetum()) {
			return false;
		}
		try {
			return enabledFlag.getClass().getField("value").get(enabledFlag) == on;
		} catch (Throwable t) {
			return true;   // unsure: leave the HUD to Gnetum
		}
	}

	/** Finds Gnetum's switch once; false without Gnetum or when the player turned it off themselves. */
	private static boolean gnetum() {
		if (!lookedUp) {
			lookedUp = true;
			try {
				if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("gnetum")) {
					return false;
				}
				Class<?> gnetum = Class.forName("me.decce.gnetum.Gnetum");
				Object config = gnetum.getField("config").get(null);
				Object flag = config.getClass().getField("enabled").get(config);
				java.lang.reflect.Field value = flag.getClass().getField("value");
				@SuppressWarnings({"unchecked", "rawtypes"})
				Class<? extends Enum> values = (Class<? extends Enum>) value.getType();
				Object onValue = Enum.valueOf(values, "ON");
				if (value.get(flag) != onValue) {
					return false;   // turned off by the player: leave it alone
				}
				enabledFlag = flag;
				on = onValue;
				off = Enum.valueOf(values, "OFF");
			} catch (Throwable t) {
				org.slf4j.LoggerFactory.getLogger("velo-client").info("HUD cache tuner: Gnetum not usable ({})", t.toString());
				enabledFlag = null;
			}
		}
		return enabledFlag != null;
	}

	private static void set(boolean cache) {
		try {
			enabledFlag.getClass().getField("value").set(enabledFlag, cache ? on : off);
		} catch (Throwable t) {
			enabledFlag = null;   // something changed in Gnetum: stop touching it
		}
	}
}
