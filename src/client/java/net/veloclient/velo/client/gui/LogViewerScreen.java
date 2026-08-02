package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.util.LogCapture;
import net.veloclient.velo.config.VeloPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** In-game log viewer with a text filter, word-wrapped lines and one-click export/copy (design spec section 6.3). */
public final class LogViewerScreen extends VeloWindow {

	private TextFieldWidget filterBox;
	private int scrollOffset;
	private Text status = Text.literal("");

	public LogViewerScreen(Screen parent) {
		super(Text.literal("Client Log Viewer"), 700, 460);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		filterBox = new TextFieldWidget(this.textRenderer, contentX(), contentY(), contentWidth() - 240, 18, Text.literal("Filter"));
		filterBox.setPlaceholder(Text.literal("Filter (case-insensitive substring)..."));
		filterBox.setDrawsBackground(false);
		addDrawableChild(filterBox);

		addDrawableChild(new VeloButton(contentX() + contentWidth() - 236, contentY(), 110, 18,
				Text.literal("Copy All"), b -> copyLog()));
		addDrawableChild(new VeloButton(contentX() + contentWidth() - 120, contentY(), 120, 18,
				Text.literal("Export Log"), b -> exportLog()));

		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, 100, 20, Text.literal("Close"), b -> requestClose()));
	}

	private void exportLog() {
		VeloPaths.ensureDirectories();
		String name = "session-" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").format(LocalDateTime.now()) + ".log";
		Path file = VeloPaths.logs().resolve(name);
		try {
			Files.write(file, LogCapture.snapshot(), StandardCharsets.UTF_8);
			status = Text.literal("Exported to " + file);
		} catch (IOException e) {
			status = Text.literal("Export failed: " + e.getMessage());
		}
	}

	private void copyLog() {
		String text = String.join("\n", LogCapture.snapshot());
		MinecraftClient.getInstance().keyboard.setClipboard(text);
		status = Text.literal("Copied " + LogCapture.snapshot().size() + " lines to clipboard");
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		// Positive verticalAmount is a wheel-up motion, which should reveal
		// earlier (further back) lines - scrollOffset counts how far back
		// from the newest line we're showing, so wheel-up must increase it.
		scrollOffset = Math.max(0, scrollOffset + (int) (verticalAmount * 3));
		return true;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		Theme theme = ThemeManager.active();

		String filter = filterBox.getText().toLowerCase();
		List<String> lines = LogCapture.snapshot();
		int lineHeight = this.textRenderer.fontHeight + 1;
		int top = contentY() + 26;
		int bottom = contentBottom() - 40;
		int maxRows = Math.max(1, (bottom - top) / lineHeight);
		int wrapWidth = contentWidth();

		List<String> wrapped = new ArrayList<>();
		for (String line : lines) {
			if (!filter.isEmpty() && !line.toLowerCase().contains(filter)) {
				continue;
			}
			wrapped.addAll(wrapLine(this.textRenderer, line, wrapWidth));
		}
		int total = wrapped.size();
		scrollOffset = Math.min(scrollOffset, Math.max(0, total - maxRows));
		int start = Math.max(0, total - maxRows - scrollOffset);
		int end = Math.min(total, start + maxRows);

		int y = top;
		context.enableScissor(contentX(), top, contentX() + wrapWidth, bottom);
		for (int i = start; i < end; i++) {
			context.drawTextWithShadow(this.textRenderer, wrapped.get(i), contentX(), y, 0xFFE0E0E0);
			y += lineHeight;
		}
		context.disableScissor();

		// Its own row above the Close button (contentBottom()-20..contentBottom())
		// - this used to sit right on top of that button's hitbox/label.
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 32, theme.text());
	}

	/** Word-wraps one log line to fit {@code maxWidth}, hard-breaking any single "word" (e.g. a long stack frame) that's wider than that on its own. */
	private static List<String> wrapLine(net.minecraft.client.font.TextRenderer renderer, String text, int maxWidth) {
		List<String> result = new ArrayList<>();
		if (renderer.getWidth(text) <= maxWidth) {
			result.add(text);
			return result;
		}
		StringBuilder current = new StringBuilder();
		for (String word : text.split(" ")) {
			String candidate = current.isEmpty() ? word : current + " " + word;
			if (renderer.getWidth(candidate) <= maxWidth) {
				current = new StringBuilder(candidate);
				continue;
			}
			if (!current.isEmpty()) {
				result.add(current.toString());
				current = new StringBuilder();
			}
			while (renderer.getWidth(word) > maxWidth && word.length() > 1) {
				int cut = word.length();
				while (cut > 1 && renderer.getWidth(word.substring(0, cut)) > maxWidth) {
					cut--;
				}
				result.add(word.substring(0, cut));
				word = word.substring(cut);
			}
			current = new StringBuilder(word);
		}
		if (!current.isEmpty()) {
			result.add(current.toString());
		}
		return result;
	}
}
