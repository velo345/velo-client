package net.veloclient.velo.devbuild.slots;

import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Numbered Inventories: a small number in the corner of every slot of an open inventory / chest - the container's slots
 * 1..n, your inventory 1-36 (hotbar 1-9 first, the way the hotbar keys count). Always, or only while Alt is held.
 */
public final class NumberedInventoryModule extends AbstractModule implements Configurable {

	private static final List<String> SHOW = List.of("Always", "While holding Alt");
	private static final List<String> WHICH = List.of("Everything", "Only the container", "Only my inventory");

	private String show = "Always";
	private String which = "Everything";
	private boolean empty = true;
	private String start = "0 (plugin configs)";
	private static final List<String> START = List.of("0 (plugin configs)", "1");
	private int color = 0xB4A4A4AC; // soft grey

	public NumberedInventoryModule() {
		super("numbered-inventories", "Numbered Inventories", "Numbers every slot of chests, menus and your inventory - from 0 like plugin configs (DeluxeMenus, shops) count them, or from 1.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	private static NumberedInventoryModule instance;

	@Override
	public void onEnable() {
		if (color == 0xE6FFFFFF) {
			color = 0xB4A4A4AC; // the old white default becomes the new grey one
		}
	}

	// From NumberedSlotMixin, right before a slot's item is drawn (slot coordinates; the slot pass's transform).
	public static void drawBehind(Object screen, net.minecraft.client.gui.DrawContext context, Object slot) {
		NumberedInventoryModule m = instance;
		if (m == null || !m.isEnabled() || !SlotList.isContainer(screen)) {
			return;
		}
		if (m.show.equals("While holding Alt") && !net.veloclient.velo.client.util.Keys.isKeyDown(net.veloclient.velo.client.util.Keys.LEFT_ALT)) {
			return;
		}
		SlotList.Numbered n = SlotList.numbered(screen, slot);
		if (n == null || (n.player() ? m.which.equals("Only the container") : m.which.equals("Only my inventory")) || !m.empty && n.empty()) {
			return;
		}
		var renderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer;
		// small, in the game's own font, in the slot's top left corner
		net.veloclient.velo.client.gui.widget.VeloStyle.pushScale(context, n.x(), n.y(), 0.6f);
		MenuKit.text(context, renderer, net.minecraft.text.Text.literal(Integer.toString(n.number() - (m.start.startsWith("0") ? 1 : 0))),
				n.x() + 1, n.y() + 1, m.color);
		net.veloclient.velo.client.gui.widget.VeloStyle.popScale(context);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Show", SHOW, () -> show, v -> show = v),
				new ConfigField.ChoiceField("Number", WHICH, () -> which, v -> which = v),
				new ConfigField.ChoiceField("Count From", START, () -> start, v -> start = v),
				new ConfigField.ToggleField("Number Empty Slots", () -> empty, v -> empty = v),
				new ConfigField.ColorField("Text Color", () -> color, v -> color = v, true));
	}
}
