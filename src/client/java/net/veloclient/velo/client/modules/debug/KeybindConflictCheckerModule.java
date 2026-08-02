package net.veloclient.velo.client.modules.debug;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Flags keybinds (vanilla + any mod's) bound to the same key, so conflicts are obvious (design spec section 6.4). */
public final class KeybindConflictCheckerModule extends AbstractModule implements HudModule {

	private final HudPosition position = new HudPosition(0.3f, 0.02f);

	public KeybindConflictCheckerModule() {
		super("keybind-conflict-checker", "Keybind Conflict Checker",
				"Lists keybinds that are bound to the same key as another binding.",
				ModuleCategory.DEBUG, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		List<String> conflicts = findConflicts(client);
		int lineHeight = client.textRenderer.fontHeight + 1;
		int rowY = y;
		for (String line : conflicts) {
			context.drawTextWithShadow(client.textRenderer, line, x, rowY, 0xFFFF5555);
			rowY += lineHeight;
		}
		if (conflicts.isEmpty()) {
			context.drawTextWithShadow(client.textRenderer, "No keybind conflicts", x, y, 0xFF55FF55);
		}
	}

	private static List<String> findConflicts(MinecraftClient client) {
		Map<String, List<KeyBinding>> byKey = new HashMap<>();
		for (KeyBinding binding : client.options.allKeys) {
			if (binding.isUnbound()) {
				continue;
			}
			byKey.computeIfAbsent(binding.getBoundKeyTranslationKey(), k -> new ArrayList<>()).add(binding);
		}
		List<String> lines = new ArrayList<>();
		for (List<KeyBinding> group : byKey.values()) {
			if (group.size() < 2) {
				continue;
			}
			StringBuilder names = new StringBuilder();
			for (KeyBinding binding : group) {
				if (!names.isEmpty()) {
					names.append(" / ");
				}
				names.append(net.minecraft.text.Text.translatable(binding.getId()).getString());
			}
			lines.add(group.get(0).getBoundKeyLocalizedText().getString() + ": " + names);
		}
		return lines;
	}

	@Override
	public int width() {
		return 260;
	}

	@Override
	public int height() {
		return 5 * (MinecraftClient.getInstance().textRenderer.fontHeight + 1);
	}
}
