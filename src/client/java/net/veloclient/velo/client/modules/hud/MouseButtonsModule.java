package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.GameOptions;
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
 * Mouse button state with live CPS, in one of three styles: two flat boxes, a plain text line
 * ("4 | 2 CPS" - what the old CPS Counter module showed, merged in here), or a drawn mouse whose
 * buttons light up while held.
 */
public final class MouseButtonsModule extends AbstractModule implements HudModule, Configurable {

	public static final String STYLE_BOXES = "Boxes";
	public static final String STYLE_TEXT = "Text";
	public static final String STYLE_MOUSE = "Mouse";
	private static final List<String> STYLES = List.of(STYLE_BOXES, STYLE_TEXT, STYLE_MOUSE);

	private static final int BOX_WIDTH = 34;
	private static final int BOX_HEIGHT = 54;
	private static final int INSET = 2;
	private static final int TOP_HEIGHT = BOX_HEIGHT / 2 - INSET;
	private static final int MOUSE_W = 40;
	private static final int MOUSE_H = 56;
	private static final int MOUSE_SPLIT = 27;

	private final HudPosition position = new HudPosition(0.02f, 0.66f);

	private String style = STYLE_BOXES;
	private boolean showCps = true;
	// Off by default - the plain body box below the two buttons is mostly dead space.
	private boolean showBodyBox = false;
	private int pressedColor = 0xFFFF4444;
	private int idleColor = 0xCC1E1212;
	private int textColor = 0xFFFFFFFF;

	public MouseButtonsModule() {
		super("mouse-buttons", "Mouse Buttons", "Shows mouse button state (left, right and middle in the Mouse style) with live CPS - as boxes, text or a mouse.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		CpsTracker.ensureRegistered();
	}

	/** Used by the profile migration of the removed CPS Counter module. */
	public void setStyle(String style) {
		if (STYLES.contains(style)) {
			this.style = style;
		}
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
		switch (style) {
			case STYLE_TEXT -> renderText(context, client, x, y, leftPressed, rightPressed);
			case STYLE_MOUSE -> renderMouse(context, client, x, y, leftPressed, rightPressed);
			default -> renderBoxes(context, client, x, y, leftPressed, rightPressed);
		}
	}

	private void renderBoxes(DrawContext context, MinecraftClient client, int x, int y, boolean leftPressed, boolean rightPressed) {
		int bodyW = BOX_WIDTH - INSET * 2;
		int halfW = bodyW / 2;
		context.fill(x + INSET, y + INSET, x + INSET + halfW - 1, y + INSET + TOP_HEIGHT, leftPressed ? pressedColor : idleColor);
		context.fill(x + INSET + halfW + 1, y + INSET, x + BOX_WIDTH - INSET, y + INSET + TOP_HEIGHT, rightPressed ? pressedColor : idleColor);
		if (showBodyBox) {
			int bottomColor = (idleColor & 0x00FFFFFF) | 0x88000000;
			context.fill(x + INSET, y + INSET + TOP_HEIGHT + 2, x + BOX_WIDTH - INSET, y + BOX_HEIGHT - INSET, bottomColor);
		}
		if (showCps) {
			String leftCps = String.valueOf(CpsTracker.leftCps());
			String rightCps = String.valueOf(CpsTracker.rightCps());
			context.drawTextWithShadow(client.textRenderer, leftCps,
					x + INSET + (halfW - client.textRenderer.getWidth(leftCps)) / 2, y + INSET + TOP_HEIGHT / 2 - 3, textColor);
			context.drawTextWithShadow(client.textRenderer, rightCps,
					x + INSET + halfW + 2 + (halfW - client.textRenderer.getWidth(rightCps)) / 2, y + INSET + TOP_HEIGHT / 2 - 3, textColor);
		}
	}

	private void renderText(DrawContext context, MinecraftClient client, int x, int y, boolean leftPressed, boolean rightPressed) {
		String left = String.valueOf(CpsTracker.leftCps());
		String right = String.valueOf(CpsTracker.rightCps());
		int cx = x;
		context.drawTextWithShadow(client.textRenderer, left, cx, y, leftPressed ? pressedColor | 0xFF000000 : textColor);
		cx += client.textRenderer.getWidth(left);
		context.drawTextWithShadow(client.textRenderer, " | ", cx, y, (textColor & 0x00FFFFFF) | 0x99000000);
		cx += client.textRenderer.getWidth(" | ");
		context.drawTextWithShadow(client.textRenderer, right, cx, y, rightPressed ? pressedColor | 0xFF000000 : textColor);
		cx += client.textRenderer.getWidth(right);
		context.drawTextWithShadow(client.textRenderer, " CPS", cx, y, textColor);
	}

	/** Middle mouse button straight from the mouse (works whatever pick-block is bound to). */
	private static boolean middlePressed(MinecraftClient client) {
		long handle = client.getWindow().getHandle();
		return org.lwjgl.glfw.GLFW.glfwGetMouseButton(handle, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
	}

	private void renderMouse(DrawContext context, MinecraftClient client, int x, int y, boolean leftPressed, boolean rightPressed) {
		int radius = MOUSE_W / 2 - 1;
		int half = MOUSE_W / 2;
		boolean middle = middlePressed(client);
		// Body, then each pressed button lit up by clipping the same rounded shape to its half.
		VeloDraw.fillRounded(context, x, y, MOUSE_W, MOUSE_H, radius, idleColor);
		if (leftPressed) {
			context.enableScissor(x, y, x + half, y + MOUSE_SPLIT);
			VeloDraw.fillRounded(context, x, y, MOUSE_W, MOUSE_H, radius, pressedColor);
			context.disableScissor();
		}
		if (rightPressed) {
			context.enableScissor(x + half, y, x + MOUSE_W, y + MOUSE_SPLIT);
			VeloDraw.fillRounded(context, x, y, MOUSE_W, MOUSE_H, radius, pressedColor);
			context.disableScissor();
		}
		int line = 0x55FFFFFF;
		context.fill(x + half, y + 1, x + half + 1, y + MOUSE_SPLIT, line);
		context.fill(x + 2, y + MOUSE_SPLIT, x + MOUSE_W - 2, y + MOUSE_SPLIT + 1, line);
		// Scroll wheel in the middle of the split - lights up on a middle click.
		int wheelW = 5;
		int wheelH = 10;
		int wheelX = x + half - 2;
		int wheelY = y + 4;
		VeloDraw.fillRounded(context, wheelX - 1, wheelY - 1, wheelW + 2, wheelH + 2, 3, (idleColor & 0x00FFFFFF) | 0xFF000000);
		VeloDraw.fillRounded(context, wheelX, wheelY, wheelW, wheelH, 2, middle ? pressedColor | 0xFF000000 : 0xCCFFFFFF);
		VeloDraw.strokeRounded(context, x, y, MOUSE_W, MOUSE_H, radius, 0x40FFFFFF);
		if (showCps) {
			// CPS inside each button, beside the wheel.
			String left = String.valueOf(CpsTracker.leftCps());
			String right = String.valueOf(CpsTracker.rightCps());
			int textY = y + MOUSE_SPLIT - client.textRenderer.fontHeight - 4;
			int leftCenter = x + (half - 3) / 2 + 2;
			int rightCenter = x + half + 3 + (half - 3) / 2 - 1;
			context.drawTextWithShadow(client.textRenderer, left, leftCenter - client.textRenderer.getWidth(left) / 2, textY, textColor);
			context.drawTextWithShadow(client.textRenderer, right, rightCenter - client.textRenderer.getWidth(right) / 2, textY, textColor);
			// "CPS" under the split, small and faint.
			String label = "CPS";
			context.drawTextWithShadow(client.textRenderer, label, x + half - client.textRenderer.getWidth(label) / 2 + 1, y + MOUSE_SPLIT + (MOUSE_H - MOUSE_SPLIT - client.textRenderer.fontHeight) / 2,
					(textColor & 0x00FFFFFF) | 0x80000000);
		}
	}

	@Override
	public int width() {
		return switch (style) {
			case STYLE_TEXT -> MinecraftClient.getInstance().textRenderer.getWidth("00 | 00 CPS");
			case STYLE_MOUSE -> MOUSE_W;
			default -> BOX_WIDTH;
		};
	}

	@Override
	public int height() {
		return switch (style) {
			case STYLE_TEXT -> MinecraftClient.getInstance().textRenderer.fontHeight;
			case STYLE_MOUSE -> MOUSE_H;
			default -> showBodyBox ? BOX_HEIGHT : TOP_HEIGHT + INSET * 2;
		};
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ToggleField("Show CPS Numbers", () -> showCps, v -> showCps = v),
				new ConfigField.ToggleField("Show Body Box (Boxes style)", () -> showBodyBox, v -> showBodyBox = v),
				new ConfigField.ColorField("Pressed Color", () -> pressedColor, v -> pressedColor = v, true),
				new ConfigField.ColorField("Idle Color", () -> idleColor, v -> idleColor = v, true),
				new ConfigField.ColorField("Text Color", () -> textColor, v -> textColor = v, true));
	}
}
