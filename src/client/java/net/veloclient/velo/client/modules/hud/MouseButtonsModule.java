package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.GameOptions;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Mouse button state with live CPS (split out of {@link KeystrokesModule} so
 * it can be positioned/scaled independently) - three flat opacity boxes
 * (left button, right button, body) and the CPS numbers, no outline shape
 * behind them.
 */
public final class MouseButtonsModule extends AbstractModule implements HudModule, Configurable {

	private static final int MOUSE_WIDTH = 34;
	private static final int MOUSE_HEIGHT = 54;
	private static final int INSET = 2;
	private static final int TOP_HEIGHT = MOUSE_HEIGHT / 2 - INSET;
	private final HudPosition position = new HudPosition(0.02f, 0.66f);

	private boolean showCps = true;
	// Off by default - the plain body box below the two buttons is mostly
	// dead space and most CPS/click overlays (this one included, before this
	// setting existed) look cleaner without it.
	private boolean showBodyBox = false;
	private int pressedColor = 0xFFFF4444;
	private int idleColor = 0xCC1E1212;

	public MouseButtonsModule() {
		super("mouse-buttons", "Mouse Buttons", "Shows left/right mouse button state with live CPS.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		CpsTracker.ensureRegistered();
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		GameOptions options = client.options;
		boolean leftPressed = options.attackKey.isPressed();
		boolean rightPressed = options.useKey.isPressed();

		int inset = INSET;
		int bodyW = MOUSE_WIDTH - inset * 2;
		int halfW = bodyW / 2;
		int topH = TOP_HEIGHT;

		int leftColor = leftPressed ? pressedColor : idleColor;
		int rightColor = rightPressed ? pressedColor : idleColor;

		context.fill(x + inset, y + inset, x + inset + halfW - 1, y + inset + topH, leftColor);
		context.fill(x + inset + halfW + 1, y + inset, x + MOUSE_WIDTH - inset, y + inset + topH, rightColor);
		if (showBodyBox) {
			int bottomColor = (idleColor & 0x00FFFFFF) | 0x88000000;
			context.fill(x + inset, y + inset + topH + 2, x + MOUSE_WIDTH - inset, y + MOUSE_HEIGHT - inset, bottomColor);
		}

		if (showCps) {
			String leftCps = String.valueOf(CpsTracker.leftCps());
			String rightCps = String.valueOf(CpsTracker.rightCps());
			context.drawTextWithShadow(client.textRenderer, leftCps,
					x + inset + (halfW - client.textRenderer.getWidth(leftCps)) / 2, y + inset + topH / 2 - 3, 0xFFFFFFFF);
			context.drawTextWithShadow(client.textRenderer, rightCps,
					x + inset + halfW + 2 + (halfW - client.textRenderer.getWidth(rightCps)) / 2, y + inset + topH / 2 - 3, 0xFFFFFFFF);
		}
	}

	@Override
	public int width() {
		return MOUSE_WIDTH;
	}

	@Override
	public int height() {
		return showBodyBox ? MOUSE_HEIGHT : TOP_HEIGHT + INSET * 2;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Show CPS Numbers", () -> showCps, v -> showCps = v),
				new ConfigField.ToggleField("Show Body Box", () -> showBodyBox, v -> showBodyBox = v),
				new ConfigField.ColorField("Pressed Color", () -> pressedColor, v -> pressedColor = v, true),
				new ConfigField.ColorField("Idle Color", () -> idleColor, v -> idleColor = v, true));
	}
}
