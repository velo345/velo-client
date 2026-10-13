package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.modules.redstone.TickJournal;
import net.veloclient.velo.client.util.ClientCompat;

/**
 * Freezing in Redstone Mode (singleplayer). Nothing is frozen by default: F7 (or the snowflake in
 * the overlay) freezes the world while you build - you still move and place blocks - and while
 * frozen the arrow keys drive time: right steps one tick (hold to keep stepping, Shift for 10),
 * left steps back (hold / Shift for 10), up plays while held (Shift+up sprints ahead), down
 * rewinds a second. A timeline replay also freezes the world until you leave it. Built on
 * vanilla's tick freeze / step / sprint; stepping back uses {@link TickJournal}, which only
 * records while frozen.
 */
public final class TickControls {

	private static boolean frozen;
	private static boolean replay;
	private static int stepHeld;
	private static int backHeld;
	private static boolean playing;
	private static long stepped;
	private static boolean applied;

	private TickControls() {
	}

	/** Frozen by the player (F7). */
	public static boolean frozen() {
		return frozen;
	}

	public static boolean playing() {
		return playing;
	}

	public static long stepped() {
		return stepped;
	}

	public static void toggleFreeze() {
		setFrozen(!frozen);
	}

	public static void setFrozen(boolean on) {
		if (on && !ClientCompat.isSingleplayer()) {
			RmState.error("Freezing only works in singleplayer");
			return;
		}
		frozen = on;
		stepped = 0;
		playing = false;
		apply();
		RmState.info(on ? "World frozen - \u2192 step, \u2190 back, \u2191 play (Shift: sprint), \u2193 rewind 1s" : "World running");
	}

	/** Timeline replays freeze the world while they're open. */
	static void setReplay(boolean on) {
		replay = on;
		apply();
	}

	private static void apply() {
		boolean want = frozen || replay;
		boolean journal = frozen && !replay;
		if (journal != TickJournal.enabled) {
			TickJournal.clear();
			TickJournal.enabled = journal;
		}
		if (want != applied) {
			applied = want;
			server(manager -> {
				if (!want) {
					stopSprint(manager);
				}
				setFrozen(manager, want);
			});
		}
	}

	/** Leaving Redstone Mode / the world. */
	public static void exit() {
		frozen = false;
		replay = false;
		playing = false;
		TickJournal.enabled = false;
		TickJournal.clear();
		if (applied) {
			applied = false;
			server(manager -> {
				stopSprint(manager);
				setFrozen(manager, false);
			});
		}
	}

	private static boolean shift() {
		return RmKeys.shift();
	}

	/** Client tick while Redstone Mode is on and no screen is open. */
	public static void tick(int sprintTicks) {
		if (!frozen || replay) {
			RmKeys.drain(RmKeys.STEP, RmKeys.BACK, RmKeys.REWIND);
			if (!replay) {
				RmKeys.drain(RmKeys.PLAY);
			}
			return;
		}
		TickJournal.radius = 32;
		TickJournal.maxTicks = 1200;
		while (RmKeys.STEP.wasPressed()) {
			step(shift() ? 10 : 1);
			stepHeld = 0;
		}
		if (RmKeys.STEP.isPressed() && ++stepHeld > 6) {
			step(1);
		} else if (!RmKeys.STEP.isPressed()) {
			stepHeld = 0;
		}
		while (RmKeys.BACK.wasPressed()) {
			rewind(shift() ? 10 : 1);
			backHeld = 0;
		}
		if (RmKeys.BACK.isPressed() && ++backHeld > 6) {
			rewind(1);
		} else if (!RmKeys.BACK.isPressed()) {
			backHeld = 0;
		}
		while (RmKeys.REWIND.wasPressed()) {
			rewind(20);
		}
		while (RmKeys.PLAY.wasPressed()) {
			if (shift()) {
				server(manager -> sprint(manager, sprintTicks));
				stepped += sprintTicks;
			}
		}
		boolean wantPlay = RmKeys.PLAY.isPressed() && !shift();
		if (wantPlay != playing) {
			playing = wantPlay;
			server(manager -> setFrozen(manager, !wantPlay));
		}
		if (playing) {
			stepped++;
		}
	}

	public static void step(int ticks) {
		stepped += ticks;
		server(manager -> step(manager, ticks));
	}

	public static void rewind(int ticks) {
		server(manager -> {
			for (int i = 0; i < ticks; i++) {
				if (!TickJournal.rewindRecorded()) {
					break;
				}
			}
		});
		stepped = Math.max(0, stepped - ticks);
	}

	//? if <26.1 {
	static void server(java.util.function.Consumer<net.minecraft.server.ServerTickManager> action) {
		var server = MinecraftClient.getInstance().getServer();
		if (server != null) {
			server.execute(() -> action.accept(server.getTickManager()));
		}
	}

	private static void setFrozen(net.minecraft.server.ServerTickManager manager, boolean frozen) {
		manager.setFrozen(frozen);
	}

	private static void step(net.minecraft.server.ServerTickManager manager, int ticks) {
		manager.step(ticks);
	}

	private static void sprint(net.minecraft.server.ServerTickManager manager, int ticks) {
		manager.startSprint(ticks);
	}

	private static void stopSprint(net.minecraft.server.ServerTickManager manager) {
		manager.stopSprinting();
	}
	//?} else {
	/*static void server(java.util.function.Consumer<net.minecraft.server.ServerTickRateManager> action) {
		var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
		if (server != null) {
			server.execute(() -> action.accept(server.tickRateManager()));
		}
	}

	private static void setFrozen(net.minecraft.server.ServerTickRateManager manager, boolean frozen) {
		manager.setFrozen(frozen);
	}

	private static void step(net.minecraft.server.ServerTickRateManager manager, int ticks) {
		manager.stepGameIfPaused(ticks);
	}

	private static void sprint(net.minecraft.server.ServerTickRateManager manager, int ticks) {
		manager.requestGameToSprint(ticks);
	}

	private static void stopSprint(net.minecraft.server.ServerTickRateManager manager) {
		manager.stopSprinting();
	}
	*///?}
}
