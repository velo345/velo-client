package net.veloclient.velo.client.modules.redstone;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Tick Mode (singleplayer): while on, the world is frozen - you still move and build - and the
 * arrow keys drive time: right steps one game tick (hold to keep stepping, Shift for 10), left
 * steps back one tick (hold to keep rewinding, Shift for 10), up plays at normal speed while
 * held (Shift+up: sprint ahead as fast as the PC can), down rewinds a whole second. Built on
 * vanilla's own tick freeze/step/sprint; rewinding uses {@link TickJournal} (blocks and block
 * entity contents near you, not entities or pending scheduled ticks).
 */
public final class TickControlModule extends AbstractModule implements HudModule, Configurable {

	public static final KeyBinding STEP_KEY = key("tick_step", net.veloclient.velo.client.util.Keys.RIGHT);
	public static final KeyBinding BACK_KEY = key("tick_back", net.veloclient.velo.client.util.Keys.LEFT);
	public static final KeyBinding PLAY_KEY = key("tick_play", net.veloclient.velo.client.util.Keys.UP);
	public static final KeyBinding REWIND_KEY = key("tick_rewind", net.veloclient.velo.client.util.Keys.DOWN);

	private static KeyBinding key(String id, int code) {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.velo-client." + id, InputUtil.Type.KEYSYM, code, VeloKeybinds.CATEGORY));
	}

	private final HudPosition position = new HudPosition(0.5f, 0.06f);
	private double history = 200;
	private double radius = 32;
	private double sprintTicks = 200;
	private int stepHeld;
	private int backHeld;
	private boolean playing;
	private long stepped;

	public TickControlModule() {
		super("tick-control", "Tick Mode", "Singleplayer: freeze the world, step ticks forward/back with the arrow keys, play or sprint ahead.",
				ModuleCategory.REDSTONE, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
	}

	private static boolean shift() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		return client.player != null && client.player.isSneaking();
		//?} else {
		/*return client.player != null && client.player.isShiftKeyDown();
		*///?}
	}

	@Override
	public void onEnable() {
		if (!ClientCompat.isSingleplayer()) {
			NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Tick Mode", "Only works in singleplayer worlds.", 3000, null, null, null));
			return;
		}
		TickJournal.radius = (int) Math.round(radius);
		TickJournal.maxTicks = (int) Math.round(history);
		TickJournal.clear();
		TickJournal.enabled = true;
		stepped = 0;
		server(manager -> setFrozen(manager, true));
	}

	@Override
	public void onDisable() {
		TickJournal.enabled = false;
		TickJournal.clear();
		playing = false;
		server(manager -> {
			stopSprint(manager);
			setFrozen(manager, false);
		});
	}

	private void onTick() {
		if (!isEnabled()) {
			return;
		}
		if (!ClientCompat.isSingleplayer()) {
			return;
		}
		TickJournal.maxTicks = (int) Math.round(history);
		TickJournal.radius = (int) Math.round(radius);
		boolean inScreen = ClientCompat.currentScreen() != null;
		// Step forward: tap = 1 (Shift: 10), hold to keep stepping.
		while (STEP_KEY.wasPressed()) {
			step(shift() ? 10 : 1);
			stepHeld = 0;
		}
		if (!inScreen && STEP_KEY.isPressed() && ++stepHeld > 6) {
			step(1);
		} else if (!STEP_KEY.isPressed()) {
			stepHeld = 0;
		}
		// Step back.
		while (BACK_KEY.wasPressed()) {
			rewind(shift() ? 10 : 1);
			backHeld = 0;
		}
		if (!inScreen && BACK_KEY.isPressed() && ++backHeld > 6) {
			rewind(1);
		} else if (!BACK_KEY.isPressed()) {
			backHeld = 0;
		}
		while (REWIND_KEY.wasPressed()) {
			rewind(20);
		}
		// Play while held; Shift+play sprints ahead.
		while (PLAY_KEY.wasPressed()) {
			if (shift()) {
				int ticks = (int) Math.round(sprintTicks);
				server(manager -> sprint(manager, ticks));
				stepped += ticks;
			}
		}
		boolean wantPlay = !inScreen && PLAY_KEY.isPressed() && !shift();
		if (wantPlay != playing) {
			playing = wantPlay;
			server(manager -> setFrozen(manager, !wantPlay));
		}
		if (playing) {
			stepped++;
		}
	}

	private void step(int ticks) {
		stepped += ticks;
		server(manager -> step(manager, ticks));
	}

	private void rewind(int ticks) {
		server(manager -> {
			for (int i = 0; i < ticks; i++) {
				if (!TickJournal.rewindRecorded()) {
					break;
				}
			}
		});
		stepped = Math.max(0, stepped - ticks);
	}

	// ---- integrated server access ----

	//? if <26.1 {
	private static void server(java.util.function.Consumer<net.minecraft.server.ServerTickManager> action) {
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
	/*private static void server(java.util.function.Consumer<net.minecraft.server.ServerTickRateManager> action) {
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

	// ---- HUD ----

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		String state = playing ? "▶ playing" : "❚❚ frozen";
		String line1 = "TICK MODE  " + state + "   +" + stepped + " ticks   " + TickJournal.available() + " to rewind";
		String line2 = "→ step   ← back   ↑ play (Shift: sprint)   ↓ rewind 1s";
		int w = Math.max(client.textRenderer.getWidth(line1), client.textRenderer.getWidth(line2)) + 12;
		VeloDraw.fillRounded(context, x, y, w, 26, 5, 0xB0101016);
		context.drawTextWithShadow(client.textRenderer, line1, x + 6, y + 4, playing ? 0xFF6FE39A : 0xFFFFC53D);
		context.drawTextWithShadow(client.textRenderer, line2, x + 6, y + 15, 0xFFB8B8C8);
	}

	@Override
	public int width() {
		return 250;
	}

	@Override
	public int height() {
		return 26;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Step Forward", STEP_KEY),
				KeybindConfig.field("Step Back", BACK_KEY),
				KeybindConfig.field("Play While Held", PLAY_KEY),
				KeybindConfig.field("Rewind 1 Second", REWIND_KEY),
				new ConfigField.SliderField("Rewind History (ticks)", 20, 1200, () -> history, v -> history = Math.round(v / 20) * 20,
						v -> Math.round(v) + " (" + Math.round(v / 20) + "s)"),
				new ConfigField.SliderField("Rewind Radius (blocks)", 8, 96, () -> radius, v -> radius = Math.round(v / 8) * 8,
						v -> String.valueOf(Math.round(v))),
				new ConfigField.SliderField("Sprint Length (ticks)", 20, 6000, () -> sprintTicks, v -> sprintTicks = Math.round(v / 20) * 20,
						v -> String.valueOf(Math.round(v))));
	}
}
