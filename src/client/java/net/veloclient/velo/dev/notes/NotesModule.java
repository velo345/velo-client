package net.veloclient.velo.dev.notes;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * World Notes (dev build): notes floating in the world - text, a checklist, a whiteboard and a picture - private or
 * shared with friends / your party, who can tick them off and get pinged with @name. O opens the note you look at, or
 * the notes list. Every part can be switched off here.
 */
public final class NotesModule extends AbstractModule implements Configurable, net.veloclient.velo.client.gui.window.ModuleShortcut {

	public static final KeyBinding OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.world_notes", InputUtil.Type.KEYSYM, net.veloclient.velo.client.util.Keys.O, VeloKeybinds.CATEGORY));

	private static final List<String> STYLE_NAMES = List.of("Paper", "Sticky", "Glass", "Sign", "Neon");
	private static NotesModule instance;
	private static boolean showInWorld = true;
	private static boolean showOthers = true;
	private static boolean sharedOn = true;
	private static boolean mentionPings = true;
	private static boolean othersPictures = true;
	/** Far notes as small pins (off: notes only show up close). */
	private static boolean farPins;
	private static double distance = 48;
	private static double scale = 1.0;
	private static String defaultStyle = "Paper";
	private static int defaultColor = Note.COLORS[0];

	public NotesModule() {
		super("world-notes", "World Notes",
				"Notes floating in the world with checklists, a whiteboard and pictures - private or shared with friends and your party. O opens them.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
		NotesRenderer.register();
		ClientTickEvents.START_CLIENT_TICK.register(client -> NotePlacer.tick());
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
		SocialClient.addEventListener((type, data) -> {
			if (active()) {
				NoteStore.onEvent(type, data);
			}
		});
	}

	public static boolean active() {
		return instance != null && instance.isEnabled();
	}

	public static boolean showInWorld() {
		return active() && showInWorld;
	}

	public static boolean showOthers() {
		return showOthers;
	}

	public static boolean sharedOn() {
		return active() && sharedOn;
	}

	public static boolean mentionPings() {
		return mentionPings;
	}

	public static boolean othersPictures() {
		return othersPictures;
	}

	public static boolean farPins() {
		return farPins;
	}

	public static double distance() {
		return distance;
	}

	public static double scale() {
		return scale;
	}

	public static String defaultStyle() {
		return defaultStyle.toLowerCase(java.util.Locale.ROOT);
	}

	public static int defaultColor() {
		return defaultColor;
	}

	public static void setShowInWorld(boolean value) {
		showInWorld = value;
	}

	private void onTick(MinecraftClient client) {
		if (!isEnabled()) {
			while (OPEN_KEY.wasPressed()) {
				// swallow while off
			}
			return;
		}
		NoteStore.tick();
		while (OPEN_KEY.wasPressed()) {
			if (client.player == null || ClientCompat.currentScreen() != null || NotePlacer.active()) {
				continue;
			}
			Note aimed = NotesRenderer.aimed();
			if (aimed != null) {
				NotesScreens.open(aimed);
			} else {
				NotesScreens.list();
			}
		}
	}

	@Override
	public String shortcutLabel() {
		return "Open World Notes";
	}

	@Override
	public void openShortcut(net.minecraft.client.gui.screen.Screen parent) {
		MinecraftClient.getInstance().setScreen(new NotesListScreen(parent));
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				KeybindConfig.field("Notes Key", OPEN_KEY),
				new ConfigField.ToggleField("Show Notes In The World", () -> showInWorld, v -> showInWorld = v),
				new ConfigField.ToggleField("Show Notes From Friends & Party", () -> showOthers, v -> showOthers = v),
				new ConfigField.ToggleField("Shared Notes (sync with the Velo server)", () -> sharedOn, v -> sharedOn = v),
				new ConfigField.ToggleField("Mention Pings", () -> mentionPings, v -> mentionPings = v),
				new ConfigField.ToggleField("Show Pictures From Others", () -> othersPictures, v -> othersPictures = v),
				new ConfigField.ToggleField("Show Far Notes As Small Pins", () -> farPins, v -> farPins = v),
				new ConfigField.SliderField("Draw Distance", 8, 128, () -> distance, v -> distance = Math.round(v), v -> Math.round(v) + " blocks"),
				new ConfigField.SliderField("Note Size", 0.5, 2.0, () -> scale, v -> scale = v, v -> Math.round(v * 100) + "%"),
				new ConfigField.ChoiceField("Style For New Notes", STYLE_NAMES, () -> defaultStyle, v -> defaultStyle = v),
				new ConfigField.ColorField("Colour For New Notes", () -> defaultColor, v -> defaultColor = v | 0xFF000000, false));
	}
}
