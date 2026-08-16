package net.veloclient.velo.client.modules.queue;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.BackgroundQueueSessionsScreen;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.keybind.ChordKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets a player send their current server (or singleplayer world) to the
 * background as a "ghost"/resume point while they play something else - one
 * per server, so several can run at once (handy for comparing queue times
 * across servers, or PvP-tier-test servers like Luxonity/MCTiers/MCPvP.club).
 * All the actual controls live in {@link BackgroundQueueSessionsScreen}
 * (opened via "Manage Background Sessions..." below); the keybinds here are
 * multi-key chords (e.g. Ctrl+Shift+Q) you can optionally bind as shortcuts
 * on top of that - none are bound by default.
 *
 * <p><b>Switching preserves where you came from</b>: pressing Switch (or the
 * keybind) on a background session doesn't just discard whatever you were
 * doing first - it captures that too (another server becomes its own ghost;
 * singleplayer becomes a "resume point" you can jump back to) before
 * switching, so you can freely bounce between several servers and your
 * singleplayer world without losing your way back to any of them.
 *
 * <p><b>How the queue-position text gets parsed</b>: there's no standard
 * protocol packet for "your queue position" - every server's queue plugin
 * phrases it differently in chat/tab-list text, so this has to pattern-match
 * on that text. "Auto-detect" (the default) picks a preset from the
 * session's server address automatically (2b2t/Hypixel/Luxonity/MCTiers/
 * MCPvP.club are recognized); "Generic" catches common phrasing like
 * "position: 12" or "queue #12" for anything else; "Custom" lets you paste
 * your own regex with one capture group around the number (e.g. {@code
 * queue position: (\d+)} - test it against a real message from your
 * server's queue in chat first). If nothing matches, the session still runs
 * fine in the background - you'll just see "Waiting for status..." instead
 * of a position, which is purely cosmetic.
 *
 * <p>Tagged {@link SafetyTag#CHECK_SERVER_RULES}: holding a connection open
 * in the background to keep a queue slot is a real, packet-faithful use of
 * the protocol (nothing is spoofed), but some servers' rules specifically
 * disallow multi-client/queue-holding tools regardless of mechanism - same
 * caution level as the Hitbox Visualizer module.
 */
public final class BackgroundQueueModule extends AbstractModule implements Configurable, HudModule {

	private final HudPosition position = new HudPosition(0.02f, 0.7f);

	private boolean overlayVisible = true;
	private String statusPreset = "Auto-detect";
	private String customRegex = "";
	private boolean soundOnPop = true;
	private boolean soundOnSwitch = true;
	private boolean mirrorToChat = true;

	private int hudColor = 0xFF7FD9FF;

	private List<Integer> sendToBackgroundChord = List.of();
	private List<Integer> toggleOverlayChord = List.of();
	private List<Integer> cyclePeekChord = List.of();
	private List<Integer> switchSessionChord = List.of();
	private List<Integer> terminatePeekedChord = List.of();

	public BackgroundQueueModule() {
		super("background-queue", "Background Queue Session",
				"Send your current server (or singleplayer world) to the background to hold your spot (e.g. a "
						+ "queue) while you play something else - one \"ghost\" per server, so you can hold several "
						+ "at once. Switching to one automatically backgrounds/remembers wherever you switched "
						+ "from too, so you always have a way back. Open \"Manage Background Sessions...\" below "
						+ "for all the controls (send to background, peek, switch, terminate) - the keybinds are "
						+ "optional multi-key-chord shortcuts, not required.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.CHECK_SERVER_RULES, false);
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		BackgroundQueueManager.setOnPopped(this::onQueuePopped);
		BackgroundQueueManager.setOnText(this::onBackgroundText);
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
	}

	/** Only the *peeked* session's chat is mirrored - not every background session's, which would just spam the chat log once more than one ghost is running. */
	private void onBackgroundText(String key, String text) {
		if (!mirrorToChat || !BackgroundQueueManager.isPeeked(key)) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		//? if <26.1 {
		client.player.sendMessage(Text.literal("[" + key + "] ").append(Text.literal(text)), false);
		//?} else {
		/*client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[" + key + "] ").append(net.minecraft.network.chat.Component.literal(text)));
		*///?}
	}

	private boolean sendToBackgroundHeld;
	private boolean toggleOverlayHeld;
	private boolean cyclePeekHeld;
	private boolean switchSessionHeld;
	private boolean terminatePeekedHeld;

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			return;
		}
		sendToBackgroundHeld = pollEdge(sendToBackgroundChord, sendToBackgroundHeld, BackgroundQueueManager::demote);
		toggleOverlayHeld = pollEdge(toggleOverlayChord, toggleOverlayHeld, () -> overlayVisible = !overlayVisible);
		cyclePeekHeld = pollEdge(cyclePeekChord, cyclePeekHeld, this::cyclePeek);
		switchSessionHeld = pollEdge(switchSessionChord, switchSessionHeld, this::onSwitchSessionPressed);
		terminatePeekedHeld = pollEdge(terminatePeekedChord, terminatePeekedHeld, () -> {
			String peeked = BackgroundQueueManager.peekedKey();
			if (peeked != null) {
				BackgroundQueueManager.terminate(peeked);
			}
		});
	}

	private static boolean pollEdge(List<Integer> chord, boolean wasHeld, Runnable onPress) {
		boolean held = ChordKeybinds.isHeld(chord);
		if (held && !wasHeld) {
			onPress.run();
		}
		return held;
	}

	/** Cycles the HUD's peeked session to the next one in the list - with several ghosts running, this is the keybind-only way to look through them. Singleplayer resume points aren't peekable (no live chat to show). */
	private void cyclePeek() {
		List<BackgroundQueueManager.SessionSummary> sessions = BackgroundQueueManager.sessions().stream()
				.filter(s -> !s.singleplayer()).toList();
		if (sessions.isEmpty()) {
			BackgroundQueueManager.clearPeeked();
			return;
		}
		String current = BackgroundQueueManager.peekedKey();
		int index = current == null ? -1 : indexOfKey(sessions, current);
		int next = (index + 1) % sessions.size();
		BackgroundQueueManager.setPeeked(sessions.get(next).key());
	}

	private static int indexOfKey(List<BackgroundQueueManager.SessionSummary> sessions, String key) {
		for (int i = 0; i < sessions.size(); i++) {
			if (sessions.get(i).key().equals(key)) {
				return i;
			}
		}
		return -1;
	}

	/** Keybind-only convenience: with no ghosts running, backgrounds the current server; with exactly one, switches to it. Ambiguous with several running - use the management screen for those. */
	private void onSwitchSessionPressed() {
		List<BackgroundQueueManager.SessionSummary> sessions = BackgroundQueueManager.sessions();
		if (sessions.isEmpty()) {
			if (soundOnSwitch) {
				playButtonClick();
			}
			BackgroundQueueManager.demote();
		} else if (sessions.size() == 1) {
			if (soundOnSwitch) {
				playButtonClick();
			}
			BackgroundQueueManager.promote(sessions.get(0).key());
		}
	}

	// SoundEvents constants are inconsistently typed in this Minecraft
	// version - some are the plain SoundEvent, some are wrapped
	// (RegistryEntry<SoundEvent> here, Holder<SoundEvent> on 26.x) - hence
	// the overloaded playSound rather than a single shared parameter type.
	//? if <26.1 {
	private static void playOrbPickup() {
		playSound(net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP);
	}

	private static void playButtonClick() {
		playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK);
	}

	private static void playSound(net.minecraft.sound.SoundEvent sound) {
		MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(sound, 1.0f));
	}

	private static void playSound(net.minecraft.registry.entry.RegistryEntry<net.minecraft.sound.SoundEvent> sound) {
		MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.ui(sound, 1.0f));
	}
	//?} else {
	/*private static void playOrbPickup() {
		playSound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP);
	}

	private static void playButtonClick() {
		playSound(net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK);
	}

	private static void playSound(net.minecraft.sounds.SoundEvent sound) {
		MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, 1.0f, 1.0f));
	}

	private static void playSound(net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound) {
		MinecraftClient.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound.value(), 1.0f, 1.0f));
	}
	*///?}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		if (!overlayVisible) {
			return;
		}
		TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
		BackgroundQueueManager.SessionSummary peeked = BackgroundQueueManager.peekedSummary();
		if (peeked == null) {
			int count = BackgroundQueueManager.sessions().size();
			if (count == 0) {
				return;
			}
			context.drawTextWithShadow(renderer, Text.literal(count + " background session" + (count == 1 ? "" : "s") + " running"), x, y, hudColor);
			return;
		}

		int lineY = y;
		context.drawTextWithShadow(renderer, Text.literal("Peeking: " + peeked.key()), x, lineY, hudColor);
		lineY += renderer.fontHeight + 2;

		String statusLine;
		if (peeked.poppedReady()) {
			statusLine = "Queue popped - ready to switch!";
		} else if (peeked.status() != null && peeked.status().known()) {
			String pos = peeked.status().position() >= 0 ? "Position #" + peeked.status().position() : peeked.status().rawText();
			statusLine = peeked.status().etaText().isEmpty() ? pos : pos + "  ETA " + peeked.status().etaText();
		} else {
			statusLine = "Waiting for status...";
		}
		context.drawTextWithShadow(renderer, Text.literal(statusLine), x, lineY, hudColor);
		lineY += renderer.fontHeight + 2;

		for (String message : peeked.recentMessages()) {
			context.drawTextWithShadow(renderer, Text.literal(trim(message, 220, renderer)), x, lineY, (hudColor & 0x00FFFFFF) | 0xCCFFFFFF);
			lineY += renderer.fontHeight + 1;
		}
	}

	private static String trim(String text, int maxWidth, TextRenderer renderer) {
		if (renderer.getWidth(text) <= maxWidth) {
			return text;
		}
		String trimmed = text;
		while (trimmed.length() > 1 && renderer.getWidth(trimmed + "..") > maxWidth) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed + "..";
	}

	@Override
	public int width() {
		if (!overlayVisible) {
			return 0;
		}
		return 220;
	}

	@Override
	public int height() {
		if (!overlayVisible) {
			return 0;
		}
		BackgroundQueueManager.SessionSummary peeked = BackgroundQueueManager.peekedSummary();
		int fontHeight = MinecraftClient.getInstance().textRenderer.fontHeight;
		if (peeked == null) {
			return fontHeight + 2;
		}
		return (fontHeight + 2) * 2 + (fontHeight + 1) * Math.max(1, peeked.recentMessages().size());
	}

	@Override
	public List<ConfigField> configFields() {
		List<ConfigField> fields = new ArrayList<>();
		fields.add(new ConfigField.ActionButtonField("Manage Background Sessions...", BackgroundQueueModule::openManagementScreen));
		fields.add(new ConfigField.ChoiceField("Status Format", QueueStatusParser.PRESET_NAMES, () -> statusPreset, v -> {
			statusPreset = v;
			BackgroundQueueManager.setParsingConfig(statusPreset, customRegex);
		}));
		fields.add(new ConfigField.TextField("Custom Regex (needs one number capture group, only used when Status Format = Custom)",
				"e.g. queue position: (\\d+)", () -> customRegex, v -> {
					customRegex = v;
					BackgroundQueueManager.setParsingConfig(statusPreset, customRegex);
				}));
		fields.add(new ConfigField.ToggleField("Show HUD Overlay", () -> overlayVisible, v -> overlayVisible = v));
		fields.add(new ConfigField.ColorField("HUD Color", () -> hudColor, v -> hudColor = v, true));
		fields.add(new ConfigField.ToggleField("Play Sound on Queue Pop", () -> soundOnPop, v -> soundOnPop = v));
		fields.add(new ConfigField.ToggleField("Play Sound on Switch", () -> soundOnSwitch, v -> soundOnSwitch = v));
		fields.add(new ConfigField.ToggleField("Show Peeked Session's Chat in Your Chat (Experimental)", () -> mirrorToChat, v -> mirrorToChat = v));
		fields.add(new ConfigField.ChordKeybindField("Send Current Server to Background", () -> sendToBackgroundChord, v -> sendToBackgroundChord = v));
		fields.add(new ConfigField.ChordKeybindField("Toggle Overlay", () -> toggleOverlayChord, v -> toggleOverlayChord = v));
		fields.add(new ConfigField.ChordKeybindField("Cycle Peeked Session", () -> cyclePeekChord, v -> cyclePeekChord = v));
		fields.add(new ConfigField.ChordKeybindField("Switch Session (only when 0 or 1 running)", () -> switchSessionChord, v -> switchSessionChord = v));
		fields.add(new ConfigField.ChordKeybindField("Terminate Peeked Session", () -> terminatePeekedChord, v -> terminatePeekedChord = v));
		return fields;
	}

	private static void openManagementScreen() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		var parent = client.currentScreen;
		//?} else if <26.2 {
		/*var parent = client.screen;
		*///?} else {
		/*var parent = client.gui.screen();
		*///?}
		client.setScreen(new BackgroundQueueSessionsScreen(parent));
	}
}
