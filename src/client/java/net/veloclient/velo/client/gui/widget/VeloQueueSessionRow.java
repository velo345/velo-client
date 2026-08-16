package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.modules.queue.BackgroundQueueManager;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.function.Consumer;

/**
 * One background "ghost" session in {@link
 * net.veloclient.velo.client.gui.BackgroundQueueSessionsScreen}'s list:
 * server icon, name/address, a live status line, and three actions
 * (Peek/Switch/Terminate) laid out as a bottom strip - same click-zone
 * pattern as {@link VeloCrosshairTile}. Reads its summary fresh from {@link
 * BackgroundQueueManager} every render call instead of caching it, so
 * position/ETA text updates live without the screen needing to rebuild its
 * widget list on every packet.
 */
public final class VeloQueueSessionRow extends ClickableWidget {

	private static final int ICON_SIZE = 24;
	private static final int ACTION_STRIP_HEIGHT = 20;

	private final String key;
	private final Runnable onPeekToggle;
	private final Runnable onSwitch;
	private final Consumer<String> onTerminate;

	public VeloQueueSessionRow(int x, int y, int width, int height, String key,
			Runnable onPeekToggle, Runnable onSwitch, Consumer<String> onTerminate) {
		super(x, y, width, height, Text.literal(key));
		this.key = key;
		this.onPeekToggle = onPeekToggle;
		this.onSwitch = onSwitch;
		this.onTerminate = onTerminate;
	}

	private int stripY() {
		return getY() + getHeight() - ACTION_STRIP_HEIGHT;
	}

	@Override
	public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
		if (click.y() < stripY()) {
			return;
		}
		BackgroundQueueManager.SessionSummary summary = BackgroundQueueManager.summaryFor(key);
		boolean singleplayer = summary != null && summary.singleplayer();
		int third = getWidth() / 3;
		double localX = click.x() - getX();
		if (localX < third) {
			if (!singleplayer) {
				onPeekToggle.run();
			}
		} else if (localX < third * 2) {
			onSwitch.run();
		} else {
			onTerminate.accept(key);
		}
	}

	@Override
	protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
		Theme theme = ThemeManager.active();
		BackgroundQueueManager.SessionSummary summary = BackgroundQueueManager.summaryFor(key);
		boolean peeked = key.equals(BackgroundQueueManager.peekedKey());

		VeloDraw.fillRounded(context, getX(), getY(), getWidth(), getHeight(), 5, theme.surfaceWithOpacity());
		VeloDraw.strokeRounded(context, getX(), getY(), getWidth(), getHeight(), 5, peeked ? theme.accentStart() : 0x22FFFFFF);

		if (summary == null) {
			// Removed (promoted/terminated) since the last frame - the
			// screen will rebuild the row list shortly; render nothing
			// rather than a stale/misleading row in the meantime.
			return;
		}

		boolean singleplayer = summary.singleplayer();
		TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
		int iconX = getX() + 6;
		int iconY = getY() + 6;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, BackgroundQueueManager.sessionIcon(key),
				iconX, iconY, 0f, 0f, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

		int textX = iconX + ICON_SIZE + 8;
		int textWidth = getWidth() - (textX - getX()) - 8;
		String name = singleplayer ? "Singleplayer: " + summary.displayName()
				: trimToWidth(summary.displayName().equals(summary.address())
						? summary.address() : summary.displayName() + " (" + summary.address() + ")", textWidth);
		context.drawTextWithShadow(textRenderer, trimToWidth(name, textWidth), textX, getY() + 6, theme.text());

		String statusLine;
		if (singleplayer) {
			statusLine = "Ready to resume";
		} else if (summary.poppedReady()) {
			statusLine = "Queue popped - ready to switch!";
		} else if (summary.status() != null && summary.status().known()) {
			String pos = summary.status().position() >= 0 ? "Position #" + summary.status().position() : summary.status().rawText();
			statusLine = summary.status().etaText().isEmpty() ? pos : pos + "  ETA " + summary.status().etaText();
		} else {
			statusLine = "Waiting for status...";
		}
		int statusColor = summary.poppedReady() ? 0xFF55FF55 : (theme.text() & 0x00FFFFFF) | 0xAA000000;
		context.drawTextWithShadow(textRenderer, trimToWidth(statusLine, textWidth), textX, getY() + 6 + textRenderer.fontHeight + 2, statusColor);

		int strip = stripY();
		int third = getWidth() / 3;
		boolean hoverPeek = isHovered() && mouseY >= strip && mouseX < getX() + third;
		boolean hoverSwitch = isHovered() && mouseY >= strip && mouseX >= getX() + third && mouseX < getX() + third * 2;
		boolean hoverTerminate = isHovered() && mouseY >= strip && mouseX >= getX() + third * 2;

		drawAction(context, textRenderer, getX(), strip, third - 1, singleplayer ? "-" : (peeked ? "Unpeek" : "Peek"),
				!singleplayer && hoverPeek, singleplayer ? 0x11FFFFFF : (peeked ? theme.accentStart() : 0x33FFFFFF));
		drawAction(context, textRenderer, getX() + third, strip, third - 1, singleplayer ? "Resume" : "Switch", hoverSwitch, 0x3355FF55);
		drawAction(context, textRenderer, getX() + third * 2, strip, getWidth() - third * 2, singleplayer ? "Forget" : "Terminate", hoverTerminate, 0x33FF5555);
	}

	private static void drawAction(DrawContext context, TextRenderer textRenderer, int x, int y, int width, String label, boolean hovered, int baseColor) {
		int color = hovered ? (baseColor | 0x55000000) + 0x33000000 : baseColor;
		VeloDraw.fillRounded(context, x, y, width, ACTION_STRIP_HEIGHT, 3, color);
		int labelWidth = textRenderer.getWidth(label);
		context.drawTextWithShadow(textRenderer, label, x + (width - labelWidth) / 2,
				y + (ACTION_STRIP_HEIGHT - textRenderer.fontHeight) / 2, 0xFFFFFFFF);
	}

	private static String trimToWidth(String text, int maxWidth) {
		var renderer = MinecraftClient.getInstance().textRenderer;
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
	protected void appendClickableNarrations(NarrationMessageBuilder builder) {
		builder.put(NarrationPart.TITLE, getMessage());
	}
}
