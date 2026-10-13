package net.veloclient.velo.client.redstone;

import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.modules.redstone.TickJournal;
import net.veloclient.velo.client.util.ClientCompat;

/**
 * Tick Mode inside Redstone Mode (singleplayer): entering freezes the world - you still move and
 * build - and the arrow keys drive time: right steps one game tick (hold to keep stepping, Shift
 * for 10), left steps back (hold / Shift for 10), up plays while held (Shift+up sprints ahead),
 * down rewinds a second. Built on vanilla's tick freeze/step/sprint; rewinding uses
 * {@link TickJournal} (blocks and block-entity contents near you, not entities or pending
 * scheduled ticks).
 */
public final class TickControls {

	private static boolean on;
	private static int stepHeld;
	private static int backHeld;
	private static boolean playing;
	private static long stepped;

	private TickControls() {
	}

	public static boolean on() {
		return on;
	}

	public static boolean playing() {
		return playing;
	}

	public static long stepped() {
		return stepped;
	}

	public static void enter(int historyTicks, int radius) {
		if (on || !ClientCompat.isSingleplayer()) {
			return;
		}
		on = true;
		TickJournal.radius = radius;
		TickJournal.maxTicks = historyTicks;
		TickJournal.clear();
		TickJournal.enabled = true;
		stepped = 0;
		playing = false;
		server(manager -> setFrozen(manager, true));
	}

	public static void exit() {
		if (!on) {
			return;
		}
		on = false;
		TickJournal.enabled = false;
		TickJournal.clear();
		playing = false;
		server(manager -> {
			stopSprint(manager);
			setFrozen(manager, false);
		});
	}

	private static boolean shift() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		return client.player != null && client.player.isSneaking();
		//?} else {
		/*return client.player != null && client.player.isShiftKeyDown();
		*///?}
	}

	/** Called every client tick while Tick Mode is on and no screen is open. */
	public static void tick(int sprintTicks) {
		if (!on) {
			return;
		}
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

	/** Freezes or unfreezes the world (used by timeline playback too). */
	public static void freeze(boolean frozen) {
		server(manager -> setFrozen(manager, frozen));
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
