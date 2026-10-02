package net.veloclient.velo.client.modules.queue;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.BackgroundQueueSessionsScreen;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.social.NotificationOverlay;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.util.ModuleProfiler;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Hold your place on a server (a queue, or just your spot) while you play somewhere else: the
 * server stays connected in the background as a "ghost", its chat, action bar and scoreboard stay
 * live, and switching back hands you the same connection - see {@link BackgroundQueueManager}.
 * Everything is in the Background Queue menu (J); the other two keys are optional shortcuts.
 *
 * <p>Tagged {@link SafetyTag#CHECK_SERVER_RULES}: holding a connection open is packet-faithful
 * (nothing spoofed), but some servers' rules forbid queue-holding/multi-session tools.
 */
public final class BackgroundQueueModule extends AbstractModule implements Configurable, HudModule {

	public static final KeyBinding OPEN_MENU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.queue_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, VeloKeybinds.CATEGORY));
	public static final KeyBinding QUICK_SWITCH = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.queue_switch", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	private static final List<String> CHAT_MIRROR_OPTIONS = List.of("All sessions", "Peeked session only", "Off");

	private final HudPosition position = new HudPosition(0.02f, 0.7f);

	private boolean overlayVisible = true;
	private String statusPreset = "Auto-detect";
	private String customRegex = "";
	private boolean soundOnPop = true;
	private boolean soundOnSwitch = true;
	private String chatMirror = "All sessions";
	private int hudColor = 0xFF7FD9FF;

	public BackgroundQueueModule() {
		super("background-queue", "Background Queue Session",
				"Keep a server connected in the background so you hold your spot (e.g. in a queue) while you play "
						+ "elsewhere. Its chat and scoreboard stay live, and switching back is instant - no rejoin. "
						+ "Press J for the menu.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, false);
		ClientTickEvents.END_CLIENT_TICK.register(client ->
				ModuleProfiler.time(id(), ModuleProfiler.Phase.TICK, () -> onTick(client)));
		BackgroundQueueManager.setOnPopped(this::onQueuePopped);
		BackgroundQueueManager.setOnLine(this::onBackgroundLine);
		BackgroundQueueManager.setParsingConfig(statusPreset, customRegex);
	}

	@Override
	public void onDisable() {
		for (var session : BackgroundQueueManager.sessions()) {
			if (!session.singleplayer()) {
				BackgroundQueueManager.terminate(session.key());
			}
		}
	}

	private void onQueuePopped(String key) {
		if (soundOnPop) {
			playOrbPickup();
		}
		var summary = BackgroundQueueManager.summaryFor(key);
		String name = summary != null ? summary.displayName() : key;
		NotificationOverlay.show(new NotificationOverlay.Toast(null, null, "Your queue moved!",
				name + " sent you to the server - switch over when ready", 15000,
				() -> MinecraftClient.getInstance().setScreen(new BackgroundQueueSessionsScreen(null, key)),
				() -> BackgroundQueueManager.promote(key),
				() -> MinecraftClient.getInstance().setScreen(new BackgroundQueueSessionsScreen(null, key)))
				.labels("Switch now", "Later"));
	}

	/** Mirrors background chat into your chat, prefixed with the server's name. */
	private void onBackgroundLine(String key, BackgroundQueueManager.LogLine line) {
		if (!line.kind().equals("chat") || chatMirror.equals("Off")) {
			return;
		}
		if (chatMirror.equals("Peeked session only") && !BackgroundQueueManager.isPeeked(key)) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		var summary = BackgroundQueueManager.summaryFor(key);
		String name = summary != null ? summary.displayName() : key;
		var prefix = Text.literal("[" + name + "] ").setStyle(Style.EMPTY.withColor(hudColor & 0xFFFFFF));
		var message = prefix.append(Text.literal(line.text()).setStyle(Style.EMPTY.withColor(0xD0D4DC)));
		//? if <26.1 {
		client.player.sendMessage(message, false);
		//?} else {
		/*client.player.sendSystemMessage(message);
		*///?}
	}

	private void onTick(MinecraftClient client) {
		BackgroundQueueManager.tick();
		while (OPEN_MENU.wasPressed()) {
			if (isEnabled() && ClientCompat.currentScreen() == null) {
				client.setScreen(new BackgroundQueueSessionsScreen(null, null));
			}
		}
		while (QUICK_SWITCH.wasPressed()) {
			if (isEnabled() && ClientCompat.currentScreen() == null) {
				quickSwitch(client);
			}
		}
	}

	/** Nothing running: background this server. One running: switch to it. Several: open the menu to pick. */
	private void quickSwitch(MinecraftClient client) {
		var sessions = BackgroundQueueManager.sessions();
		if (soundOnSwitch) {
			playButtonClick();
		}
		if (sessions.isEmpty()) {
			BackgroundQueueManager.demote();
		} else if (sessions.size() == 1) {
			BackgroundQueueManager.promote(sessions.get(0).key());
		} else {
			client.setScreen(new BackgroundQueueSessionsScreen(null, null));
		}
	}

	//? if <26.1 {
	private static void playOrbPickup() {
		MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(
				net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f));
	}

	private static void playButtonClick() {
		MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(
				net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}
	//?} else {
	/*private static void playOrbPickup() {
		MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
				net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f));
	}

	private static void playButtonClick() {
		MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
				net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 1.0f));
	}
	*///?}

	public static String statusLine(BackgroundQueueManager.SessionSummary summary) {
		if (summary.singleplayer()) {
			return "Saved - Resume reopens the world";
		}
		if (summary.endedReason() != null) {
			return summary.endedReason();
		}
		if (summary.switching()) {
			return "Moving between servers...";
		}
		if (summary.poppedReady()) {
			return "Queue moved - ready to switch!";
		}
		if (summary.status() != null && summary.status().known()) {
			String pos = summary.status().position() >= 0 ? "Queue position #" + summary.status().position() : summary.status().rawText();
			return summary.status().etaText().isEmpty() ? pos : pos + " - ETA " + summary.status().etaText();
		}
		return "Connected - holding your spot";
	}

	public static int statusColor(BackgroundQueueManager.SessionSummary summary) {
		if (summary.singleplayer()) {
			return 0xFF8B8D98;
		}
		if (summary.endedReason() != null) {
			return 0xFFE5484D;
		}
		if (summary.switching()) {
			return 0xFFFFC53D;
		}
		if (summary.poppedReady()) {
			return 0xFF3E9BFF;
		}
		return 0xFF46D17A;
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		if (!overlayVisible || !BackgroundQueueManager.hasSessions()) {
			return;
		}
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		var peeked = BackgroundQueueManager.peekedSummary();
		List<BackgroundQueueManager.SessionSummary> shown = peeked != null ? List.of(peeked) : BackgroundQueueManager.sessions();
		int width = width();
		int height = height();
		VeloDraw.fillRounded(context, x, y, width, height, 6, 0xB0101014);
		VeloDraw.fillRounded(context, x, y + 4, 2, height - 8, 1, hudColor);
		int lineY = y + 5;
		context.drawTextWithShadow(textRenderer, peeked != null ? "BACKGROUND - PEEKING" : "BACKGROUND SESSIONS", x + 8, lineY, VeloUi.withAlpha(hudColor, 0xCC));
		lineY += 12;
		for (var summary : shown) {
			VeloDraw.fillCircle(context, x + 11, lineY + 4, 2, statusColor(summary));
			context.drawTextWithShadow(textRenderer, VeloUi.trim(summary.displayName(), width - 22), x + 17, lineY, 0xFFFFFFFF);
			lineY += 10;
			context.drawTextWithShadow(textRenderer, VeloUi.trim(statusLine(summary), width - 22), x + 17, lineY, VeloUi.withAlpha(0xFFFFFFFF, 0xA0));
			lineY += 11;
		}
		if (peeked != null) {
			if (peeked.actionBar() != null && System.currentTimeMillis() - peeked.actionBarTime() < 15000) {
				context.drawTextWithShadow(textRenderer, VeloUi.trim(peeked.actionBar(), width - 16), x + 8, lineY, hudColor);
				lineY += 11;
			}
			for (String message : peeked.recentMessages().subList(Math.max(0, peeked.recentMessages().size() - 4), peeked.recentMessages().size())) {
				context.drawTextWithShadow(textRenderer, VeloUi.trim(message, width - 16), x + 8, lineY, 0xFFD0D4DC);
				lineY += 10;
			}
		}
	}

	@Override
	public int width() {
		return 220;
	}

	@Override
	public int height() {
		if (!overlayVisible || !BackgroundQueueManager.hasSessions()) {
			return 0;
		}
		var peeked = BackgroundQueueManager.peekedSummary();
		if (peeked == null) {
			return 20 + BackgroundQueueManager.sessions().size() * 21;
		}
		boolean actionBar = peeked.actionBar() != null && System.currentTimeMillis() - peeked.actionBarTime() < 15000;
		return 20 + 21 + (actionBar ? 11 : 0) + Math.min(4, peeked.recentMessages().size()) * 10 + 2;
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		fields.add(new ConfigField.ActionButtonField("Open Background Queue Menu...", () -> MinecraftClient.getInstance().setScreen(
				new BackgroundQueueSessionsScreen(ClientCompat.currentScreen(), null))));
		fields.add(KeybindConfig.field("Menu Key", OPEN_MENU));
		fields.add(KeybindConfig.field("Quick Switch Key (background / switch back)", QUICK_SWITCH));
		fields.add(new ConfigField.ChoiceField("Background Chat In Your Chat", CHAT_MIRROR_OPTIONS, () -> chatMirror, v -> chatMirror = v));
		fields.add(new ConfigField.ToggleField("Show HUD Card", () -> overlayVisible, v -> overlayVisible = v));
		fields.add(new ConfigField.ColorField("HUD Color", () -> hudColor, v -> hudColor = v, true));
		fields.add(new ConfigField.ToggleField("Play Sound When Queue Moves", () -> soundOnPop, v -> soundOnPop = v));
		fields.add(new ConfigField.ToggleField("Play Sound on Switch", () -> soundOnSwitch, v -> soundOnSwitch = v));
		fields.add(new ConfigField.ChoiceField("Queue Position Format", QueueStatusParser.PRESET_NAMES, () -> statusPreset, v -> {
			statusPreset = v;
			BackgroundQueueManager.setParsingConfig(statusPreset, customRegex);
		}));
		fields.add(new ConfigField.TextField("Custom Regex (only for Queue Position Format = Custom; one number group)",
				"e.g. queue position: (\\d+)", () -> customRegex, v -> {
					customRegex = v;
					BackgroundQueueManager.setParsingConfig(statusPreset, customRegex);
				}));
		return fields;
	}
}
