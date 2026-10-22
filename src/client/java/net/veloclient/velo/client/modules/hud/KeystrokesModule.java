package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

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
public final class KeystrokesModule extends AbstractModule implements HudModule, Configurable {

	private static final int KEY_SIZE = 20;
	private static final int GAP = 3;
	private final HudPosition position = new HudPosition(0.02f, 0.53f);

	private static final List<String> STYLES = List.of("Boxes", "Glass", "Outline", "Minecraft");

	private String style = "Glass";
	private boolean showLabels = true;
	private boolean showExtendedKeys = true;
	private int pressedColor = 0xFFFF4444;
	private int idleColor = 0x99101010;

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
		if (showExtendedKeys) {
			drawKey(context, x, y + 2 * (KEY_SIZE + GAP), options.sneakKey);
			drawKey(context, x + (wasdWidth - KEY_SIZE) / 2, y + 2 * (KEY_SIZE + GAP), options.jumpKey);
			drawKey(context, x + 2 * (KEY_SIZE + GAP), y + 2 * (KEY_SIZE + GAP), options.sprintKey);
		}
	}

	private void drawKey(DrawContext context, int x, int y, KeyBinding binding) {
		MinecraftClient client = MinecraftClient.getInstance();
		boolean pressed = binding.isPressed();
		int textColor = 0xFFFFFFFF;
		switch (style) {
			case "Glass" -> {
				// Frosted keys; a pressed key fills with the HUD accent and glows.
				int accent = net.veloclient.velo.client.hud.HudLook.on() ? net.veloclient.velo.client.hud.HudLook.accent() : pressedColor;
				if (pressed) {
					VeloDraw.shadow(context, x, y, KEY_SIZE, KEY_SIZE, 6, 8, 2, (accent & 0xFFFFFF) | 0x90000000);
					VeloDraw.fillRounded(context, x, y, KEY_SIZE, KEY_SIZE, 6, accent | 0xFF000000);
				} else {
					// Light frosted key: enough to read the letter, little of the world hidden behind it.
					VeloDraw.fillRounded(context, x, y, KEY_SIZE, KEY_SIZE, 6, 0x38101016);
					VeloDraw.strokeRounded(context, x, y, KEY_SIZE, KEY_SIZE, 6, 0x40FFFFFF);
				}
			}
			case "Outline" -> {
				VeloDraw.fillRounded(context, x, y, KEY_SIZE, KEY_SIZE, 5, pressed ? 0x40FFFFFF : 0x00000000);
				VeloDraw.strokeRounded(context, x, y, KEY_SIZE, KEY_SIZE, 5, pressed ? 0xFFFFFFFF : 0x90FFFFFF);
			}
			case "Minecraft" -> {
				// The vanilla button look: light top-left edge, dark bottom-right, lighter when held.
				context.fill(x, y, x + KEY_SIZE, y + KEY_SIZE, pressed ? 0xFFC6C6C6 : 0xFF6F6F6F);
				context.fill(x, y, x + KEY_SIZE, y + 1, pressed ? 0xFFFFFFFF : 0xFF9A9A9A);
				context.fill(x, y, x + 1, y + KEY_SIZE, pressed ? 0xFFFFFFFF : 0xFF9A9A9A);
				context.fill(x, y + KEY_SIZE - 1, x + KEY_SIZE, y + KEY_SIZE, pressed ? 0xFF8B8B8B : 0xFF3F3F3F);
				context.fill(x + KEY_SIZE - 1, y, x + KEY_SIZE, y + KEY_SIZE, pressed ? 0xFF8B8B8B : 0xFF3F3F3F);
				textColor = pressed ? 0xFF3F3F3F : 0xFFFFFFFF;
			}
			default -> VeloDraw.fillRounded(context, x, y, KEY_SIZE, KEY_SIZE, 3, pressed ? pressedColor : idleColor);
		}

		if (!showLabels) {
			return;
		}
		String label = shortKeyLabel(binding);
		if (net.veloclient.velo.client.hud.HudLook.on() && !style.equals("Minecraft")) {
			var text = net.veloclient.velo.client.hud.HudLook.value(label);
			int w = net.veloclient.velo.client.hud.HudLook.width(text);
			float s = w > KEY_SIZE - 4 ? (KEY_SIZE - 4) / (float) w : 1f;
			context.getMatrices().pushMatrix();
			// TTF advances have no trailing gap (unlike Minecraft's font): centre the full width, not snapped to pixels.
			context.getMatrices().translate(x + (KEY_SIZE - w * s) / 2f, centred(y, GLYPH_HEIGHT, s));
			context.getMatrices().scale(s, s);
			net.veloclient.velo.client.hud.HudLook.text(context, text, 0, 0, textColor);
			context.getMatrices().popMatrix();
			return;
		}
		int textWidth = client.textRenderer.getWidth(label);
		float scale = textWidth > KEY_SIZE - 4 ? (KEY_SIZE - 4) / (float) textWidth : 1f;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(centred(x, textWidth - 1, scale), centred(y, GLYPH_HEIGHT, scale));
		context.getMatrices().scale(scale, scale);
		context.drawTextWithShadow(client.textRenderer, label, 0, 0, textColor);
		context.getMatrices().popMatrix();
	}

	/** Capital letters stand 7 px tall on the baseline (the line box adds a descender row, the width a trailing gap). */
	private static final int GLYPH_HEIGHT = 7;

	/** Where text {@code size} wide/tall (at {@code scale}) starts so its glyphs sit in the middle of the key - on whole pixels when unscaled (rounded down: the shadow takes the pixel below/right). */
	private static float centred(int start, int size, float scale) {
		float offset = (KEY_SIZE - size * scale) / 2f;
		return start + (scale == 1f ? (float) Math.floor(offset) : offset);
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
		int rows = showExtendedKeys ? 3 : 2;
		return rows * (KEY_SIZE + GAP) - GAP;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ToggleField("Show Key Labels", () -> showLabels, v -> showLabels = v),
				new ConfigField.ToggleField("Show Sneak/Jump/Sprint Row", () -> showExtendedKeys, v -> showExtendedKeys = v),
				new ConfigField.ColorField("Pressed Color", () -> pressedColor, v -> pressedColor = v, true),
				new ConfigField.ColorField("Idle Color", () -> idleColor, v -> idleColor = v, true));
	}
}
