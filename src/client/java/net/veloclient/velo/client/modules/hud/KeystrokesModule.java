package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Classic KeystrokeMod-style WASD + sneak/jump/sprint grid, reading only your
 * own input state. Key labels use short, locale-independent codes built from
 * {@link KeyBinding#getBoundKeyTranslationKey()} rather than the localized
 * display text truncated to 3 characters, which produced misleading
 * duplicates - Left Shift and Left Control both localize to strings starting
 * "Left ..." and truncated identically to "Lef". Mouse buttons and CPS live
 * in the separate {@link MouseButtonsModule} so each can be moved/scaled on
 * its own.
 */
public final class KeystrokesModule extends AbstractModule implements HudModule {

	private static final int KEY_SIZE = 20;
	private static final int GAP = 3;
	private final HudPosition position = new HudPosition(0.02f, 0.53f);

	public KeystrokesModule() {
		super("keystrokes", "Keystrokes", "Shows WASD, sneak, jump and sprint key state.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		GameOptions options = MinecraftClient.getInstance().options;

		int wasdWidth = 3 * (KEY_SIZE + GAP) - GAP;
		drawKey(context, x + (wasdWidth - KEY_SIZE) / 2, y, options.forwardKey);
		drawKey(context, x, y + KEY_SIZE + GAP, options.leftKey);
		drawKey(context, x + (wasdWidth - KEY_SIZE) / 2, y + KEY_SIZE + GAP, options.backKey);
		drawKey(context, x + 2 * (KEY_SIZE + GAP), y + KEY_SIZE + GAP, options.rightKey);
		drawKey(context, x, y + 2 * (KEY_SIZE + GAP), options.sneakKey);
		drawKey(context, x + (wasdWidth - KEY_SIZE) / 2, y + 2 * (KEY_SIZE + GAP), options.jumpKey);
		drawKey(context, x + 2 * (KEY_SIZE + GAP), y + 2 * (KEY_SIZE + GAP), options.sprintKey);
	}

	private void drawKey(DrawContext context, int x, int y, KeyBinding binding) {
		Theme theme = ThemeManager.active();
		MinecraftClient client = MinecraftClient.getInstance();
		boolean pressed = binding.isPressed();
		int background = pressed ? theme.accentStart() : (theme.surfaceWithOpacity() & 0x00FFFFFF) | 0x99000000;
		VeloDraw.fillRounded(context, x, y, KEY_SIZE, KEY_SIZE, 3, background);

		String label = shortKeyLabel(binding);
		int textColor = pressed ? 0xFFFFFFFF : theme.text();
		int textWidth = client.textRenderer.getWidth(label);
		float scale = textWidth > KEY_SIZE - 4 ? (KEY_SIZE - 4) / (float) textWidth : 1f;
		int scaledWidth = Math.round(textWidth * scale);
		int scaledHeight = Math.round(client.textRenderer.fontHeight * scale);
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x + (KEY_SIZE - scaledWidth) / 2f, y + (KEY_SIZE - scaledHeight) / 2f);
		context.getMatrices().scale(scale, scale);
		context.drawTextWithShadow(client.textRenderer, label, 0, 0, textColor);
		context.getMatrices().popMatrix();
	}

	/** Short, locale-independent key label - avoids two different keys truncating to the same misleading text. */
	static String shortKeyLabel(KeyBinding binding) {
		String key = binding.getBoundKeyTranslationKey();
		if (key == null || key.isEmpty()) {
			return "?";
		}
		String tail = key.substring(key.lastIndexOf('.') + 1).toLowerCase();
		return switch (tail) {
			case "shift" -> "SHIFT";
			case "control" -> "CTRL";
			case "space" -> "SPACE";
			case "menu" -> "MENU";
			case "return", "enter" -> "ENTR";
			default -> tail.length() <= 3 ? tail.toUpperCase() : tail.substring(0, 3).toUpperCase();
		};
	}

	@Override
	public int width() {
		return 3 * (KEY_SIZE + GAP) - GAP;
	}

	@Override
	public int height() {
		return 3 * (KEY_SIZE + GAP) - GAP;
	}
}
