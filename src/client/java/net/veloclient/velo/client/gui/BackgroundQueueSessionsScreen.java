package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.modules.hud.ScoreboardHudModule;
import net.veloclient.velo.client.modules.queue.BackgroundQueueManager;
import net.veloclient.velo.client.modules.queue.BackgroundQueueModule;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.util.ClientCompat;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Background Queue menu (default key J). Left: what you're playing now (with "Send to
 * background") and every background session with its live status. Right: the selected session -
 * switch to it, peek at it on the HUD, or leave it for good; its live chat (you can type into it),
 * action bar, title and scoreboard.
 */
public final class BackgroundQueueSessionsScreen extends VeloWindow {

	private static final int MAX_LEFT_WIDTH = 196;
	private static final int ROW_HEIGHT = 34;

	private String selected;
	private TextFieldWidget chatField;
	private String chatValue = "";
	private double chatScroll;
	private boolean confirmLeave;
	private long confirmUntil;
	private final List<VeloUi.Hit> hits = new ArrayList<>();

	public BackgroundQueueSessionsScreen(Screen parent, String select) {
		super(Text.literal("Background Queue"), 640, 400);
		returnTo(parent);
		this.selected = select;
	}

	private double listScroll;

	private int leftWidth() {
		return Math.max(130, Math.min(MAX_LEFT_WIDTH, (int) (contentWidth() * 0.4f)));
	}

	@Override
	protected void layoutContent() {
		if (chatField != null) {
			chatValue = chatField.getText();
		}
		this.clearChildren();
		int x = contentX() + leftWidth() + 12;
		chatField = new TextFieldWidget(this.textRenderer, x + 8, contentBottom() - 15, contentWidth() - leftWidth() - 12 - 84, 14, Text.literal("Chat"));
		chatField.setDrawsBackground(false);
		chatField.setMaxLength(256);
		chatField.setPlaceholder(Text.literal(VeloUi.trim("Chat or /command on this server...", chatField.getWidth() - 8)));
		chatField.setText(chatValue);
		addDrawableChild(chatField);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		List<BackgroundQueueManager.SessionSummary> sessions = BackgroundQueueManager.sessions();
		if (selected != null && sessions.stream().noneMatch(s -> s.key().equals(selected))) {
			selected = null;
		}
		if (selected == null && !sessions.isEmpty()) {
			selected = sessions.get(0).key();
		}
		renderLeft(context, sessions, mouseX, mouseY);
		BackgroundQueueManager.SessionSummary current = selected == null ? null : BackgroundQueueManager.summaryFor(selected);
		chatField.visible = current != null && !current.singleplayer() && current.endedReason() == null;
		renderRight(context, current, mouseX, mouseY);
	}

	private void renderLeft(DrawContext context, List<BackgroundQueueManager.SessionSummary> sessions, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		int x = contentX();
		int y = contentY();

		// "Now playing" card.
		VeloDraw.fillRounded(context, x, y, leftWidth(), 50, 7, VeloUi.withAlpha(theme.accentStart(), 0x30));
		context.drawTextWithShadow(this.textRenderer, "NOW PLAYING", x + 8, y + 6, VeloUi.muted());
		MinecraftClient client = MinecraftClient.getInstance();
		boolean inWorld = client.getNetworkHandler() != null;
		String now = !inWorld ? "Nothing - pick a session below"
				: ClientCompat.isSingleplayer() ? "Singleplayer: " + String.valueOf(ClientCompat.singleplayerWorldName())
				: String.valueOf(ClientCompat.currentServerName());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(now, leftWidth() - 16), x + 8, y + 17, theme.text());
		VeloUi.Hit send = VeloUi.pill(context, x + 6, y + 30, leftWidth() - 12, 15,
				ClientCompat.isSingleplayer() ? "Save & keep a resume point" : "Send to background", inWorld ? 1 : 0, mouseX, mouseY, () -> {
					String key = BackgroundQueueManager.demote();
					if (key != null) {
						selected = key;
					}
				});
		if (inWorld) {
			hits.add(send);
		}

		int listTop = y + 58;
		context.drawTextWithShadow(this.textRenderer, "IN THE BACKGROUND", x + 2, listTop, VeloUi.muted());
		listTop += 12;
		if (sessions.isEmpty()) {
			int lineY = listTop + 4;
			for (String line : VeloUi.wrap("Nothing yet. \"Send to background\" keeps this server connected (and your queue spot) while you play somewhere else.", leftWidth() - 6)) {
				context.drawTextWithShadow(this.textRenderer, line, x + 2, lineY, VeloUi.muted());
				lineY += 11;
			}
		}
		int listBottom = contentBottom() - 16;
		int contentHeight = sessions.size() * (ROW_HEIGHT + 3);
		listScroll = Math.max(0, Math.min(listScroll, Math.max(0, contentHeight - (listBottom - listTop))));
		int hitsBefore = hits.size();
		context.enableScissor(x, listTop, x + leftWidth(), Math.max(listTop, listBottom));
		int rowY = listTop - (int) listScroll;
		for (var summary : sessions) {
			if (rowY > listBottom || rowY + ROW_HEIGHT < listTop) {
				rowY += ROW_HEIGHT + 3;
				continue;
			}
			boolean active = summary.key().equals(selected);
			boolean hovered = mouseY >= listTop && mouseY < listBottom && VeloUi.inside(mouseX, mouseY, x, rowY, leftWidth(), ROW_HEIGHT);
			int bg = active ? VeloAnim.lerpArgb(theme.accentStart(), 0xFF000000, 0.55f) : hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x14) : VeloUi.withAlpha(0xFFFFFFFF, 0x08);
			VeloDraw.fillRounded(context, x, rowY, leftWidth(), ROW_HEIGHT, 6, bg);
			int iconSource = BackgroundQueueManager.sessionIconSourceSize();
			context.drawTexture(RenderPipelines.GUI_TEXTURED, BackgroundQueueManager.sessionIcon(), x + 6, rowY + 7, 0f, 0f,
					20, 20, iconSource, iconSource, iconSource, iconSource);
			VeloDraw.fillCircle(context, x + 24, rowY + 26, 3, 0xFF101014);
			VeloDraw.fillCircle(context, x + 24, rowY + 26, 2, BackgroundQueueModule.statusColor(summary));
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(summary.displayName(), leftWidth() - 40), x + 32, rowY + 7, 0xFFFFFFFF);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(BackgroundQueueModule.statusLine(summary), leftWidth() - 40), x + 32, rowY + 19,
					VeloUi.withAlpha(BackgroundQueueModule.statusColor(summary), 0xE0));
			String key = summary.key();
			hits.add(new VeloUi.Hit(x, rowY, leftWidth(), ROW_HEIGHT, () -> {
				selected = key;
				chatScroll = 0;
				confirmLeave = false;
			}));
			rowY += ROW_HEIGHT + 3;
		}
		context.disableScissor();
		for (int i = hits.size() - 1; i >= hitsBefore; i--) {
			VeloUi.Hit hit = hits.get(i);
			int y1 = Math.max(hit.y(), listTop);
			int y2 = Math.min(hit.y() + hit.height(), listBottom);
			if (y2 <= y1) {
				hits.remove(i);
			} else {
				hits.set(i, new VeloUi.Hit(hit.x(), y1, hit.width(), y2 - y1, hit.action()));
			}
		}

		// Shortcut hint.
		String menuKey = BackgroundQueueModule.OPEN_MENU.isUnbound() ? "-" : BackgroundQueueModule.OPEN_MENU.getBoundKeyLocalizedText().getString();
		String switchKey = BackgroundQueueModule.QUICK_SWITCH.isUnbound() ? "not set" : BackgroundQueueModule.QUICK_SWITCH.getBoundKeyLocalizedText().getString();
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Menu: " + menuKey + "   Quick switch: " + switchKey, leftWidth()),
				x + 2, contentBottom() - 8, VeloUi.withAlpha(theme.text(), 0x70));
	}

	private void renderRight(DrawContext context, BackgroundQueueManager.SessionSummary summary, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		int x = contentX() + leftWidth() + 12;
		int width = contentWidth() - leftWidth() - 12;
		int y = contentY();
		int bottom = contentBottom();
		VeloDraw.fillRounded(context, x, y, width, bottom - y, 7, VeloUi.withAlpha(0xFF000000, 0x40));
		context.enableScissor(x, y, x + width, bottom);
		try {
			renderRightBody(context, summary, x, y, width, bottom, mouseX, mouseY);
		} finally {
			context.disableScissor();
		}
	}

	private void renderRightBody(DrawContext context, BackgroundQueueManager.SessionSummary summary, int x, int y, int width, int bottom,
			int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();

		String notice = BackgroundQueueManager.lastNotice();
		if (summary == null) {
			int lineY = y + 40;
			String[] help = {
					"Hold your spot on a server while you play somewhere else.",
					"",
					"1. Join the server (or its queue).",
					"2. Press \"Send to background\". You stay connected - your queue position keeps counting down.",
					"3. Play anything else. Its chat shows up in your chat, and here.",
					"4. Press Switch whenever you like - you're back instantly, exactly where you were.",
			};
			for (String paragraph : help) {
				for (String line : VeloUi.wrap(paragraph, width - 40)) {
					context.drawTextWithShadow(this.textRenderer, line, x + 20, lineY, paragraph.startsWith("Hold") ? theme.text() : VeloUi.muted());
					lineY += 11;
				}
				lineY += 2;
			}
			if (!notice.isEmpty()) {
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(notice, width - 20), x + 10, bottom - 12, 0xFFFFC53D);
			}
			return;
		}

		// Header - the buttons get their own row when the panel is too narrow to share it with the name.
		int buttonsWidth = summary.singleplayer() ? 160 : 222;
		boolean stacked = width - buttonsWidth < 150;
		int textWidth = stacked ? width - 20 : width - buttonsWidth - 16;
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(summary.displayName(), textWidth), x + 10, y + 8, 0xFFFFFFFF);
		String sub = summary.singleplayer() ? "Singleplayer world" : summary.address() + "  -  in background " + VeloUi.ago(summary.since()).replace(" ago", "");
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(sub, textWidth), x + 10, y + 20, VeloUi.muted());
		int buttonY = stacked ? y + 32 : y + 8;
		int shift = stacked ? 24 : 0;

		int bx = x + width - 8;
		bx -= 64;
		boolean armed = confirmLeave && System.currentTimeMillis() < confirmUntil;
		String key = summary.key();
		hits.add(VeloUi.pill(context, bx, buttonY, 64, 18, armed ? "Sure?" : summary.singleplayer() ? "Forget" : "Leave", armed ? 3 : 0, mouseX, mouseY, () -> {
			if (armed) {
				BackgroundQueueManager.terminate(key);
				confirmLeave = false;
			} else {
				confirmLeave = true;
				confirmUntil = System.currentTimeMillis() + 3000;
			}
		}));
		if (!summary.singleplayer()) {
			bx -= 58;
			boolean peeked = BackgroundQueueManager.isPeeked(key);
			hits.add(VeloUi.pill(context, bx, buttonY, 54, 18, peeked ? "Unpeek" : "Peek", peeked ? 1 : 0, mouseX, mouseY, () -> {
				if (BackgroundQueueManager.isPeeked(key)) {
					BackgroundQueueManager.clearPeeked();
				} else {
					BackgroundQueueManager.setPeeked(key);
				}
			}));
		}
		bx -= 84;
		String switchLabel = summary.singleplayer() ? "Resume" : summary.endedReason() != null ? "Rejoin" : "Switch";
		hits.add(VeloUi.pill(context, bx, buttonY, 80, 18, switchLabel, 2, mouseX, mouseY, () -> BackgroundQueueManager.promote(key)));

		// Status strip.
		int stripY = y + 34 + shift;
		int statusColor = BackgroundQueueModule.statusColor(summary);
		VeloDraw.fillRounded(context, x + 8, stripY, width - 16, 20, 5, VeloUi.withAlpha(statusColor, 0x30));
		VeloDraw.fillCircle(context, x + 18, stripY + 10, 3, statusColor);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(BackgroundQueueModule.statusLine(summary), width - 40), x + 26, stripY + 6, 0xFFFFFFFF);

		int infoY = stripY + 26;
		long now = System.currentTimeMillis();
		if (summary.actionBar() != null && now - summary.actionBarTime() < 30_000) {
			context.drawTextWithShadow(this.textRenderer, "Action bar", x + 10, infoY, VeloUi.muted());
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(summary.actionBar(), width - 80), x + 70, infoY, 0xFFFFE08A);
			infoY += 12;
		}
		if (summary.title() != null && now - summary.titleTime() < 60_000) {
			String title = summary.title() + (summary.subtitle() != null && !summary.subtitle().isBlank() ? " - " + summary.subtitle() : "");
			context.drawTextWithShadow(this.textRenderer, "Title", x + 10, infoY, VeloUi.muted());
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(title, width - 80), x + 70, infoY, 0xFFFFFFFF);
			infoY += 12;
		}

		// Scoreboard (right column) + chat log (left column).
		var scoreboard = BackgroundQueueManager.scoreboardFor(key);
		Text sidebarTitle = scoreboard == null ? null : ScoreboardHudModule.sidebarTitle(scoreboard);
		int sideWidth = sidebarTitle == null || width < 280 ? 0 : 130;
		if (sideWidth > 0) {
			int sx = x + width - sideWidth - 8;
			List<Text[]> rows = ScoreboardHudModule.sidebarRows(scoreboard);
			int maxRows = Math.max(0, (bottom - 30 - infoY - 18) / 10);
			if (rows.size() > maxRows) {
				rows = rows.subList(0, maxRows);
			}
			int sideHeight = 14 + rows.size() * 10 + 4;
			VeloDraw.fillRounded(context, sx, infoY, sideWidth, sideHeight, 5, VeloUi.withAlpha(0xFF000000, 0x50));
			context.drawCenteredTextWithShadow(this.textRenderer, sidebarTitle, sx + sideWidth / 2, infoY + 4, 0xFFFFFFFF);
			int ry = infoY + 15;
			for (Text[] row : rows) {
				int scoreWidth = this.textRenderer.getWidth(row[1]);
				context.enableScissor(sx + 2, ry - 1, sx + sideWidth - 6 - scoreWidth, ry + 9);
				context.drawTextWithShadow(this.textRenderer, row[0], sx + 4, ry, 0xFFFFFFFF);
				context.disableScissor();
				context.drawTextWithShadow(this.textRenderer, row[1], sx + sideWidth - 4 - scoreWidth, ry, 0xFFFFFFFF);
				ry += 10;
			}
		}

		int logTop = infoY;
		int logBottom = bottom - 24;
		int logWidth = width - 16 - (sideWidth > 0 ? sideWidth + 8 : 0);
		drawLog(context, summary, x + 8, logTop, logWidth, logBottom - logTop);

		if (summary.endedReason() != null || summary.singleplayer()) {
			String hint = summary.singleplayer() ? "Resume reopens the world where you saved it."
					: "This connection has ended - Rejoin connects again.";
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(hint, width - 20), x + 10, bottom - 14, VeloUi.muted());
		} else {
			VeloDraw.fillRounded(context, x + 4, bottom - 19, width - 76, 18, 5, VeloUi.withAlpha(0xFF000000, chatField.isFocused() ? 0x70 : 0x50));
			hits.add(VeloUi.pill(context, x + width - 68, bottom - 19, 62, 18, "Send", 1, mouseX, mouseY, this::sendChat));
		}
		if (!notice.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(notice, width - 20), x + 10, logTop - 10 < y ? y + 2 : logBottom - 10, 0xFFFFC53D);
		}
	}

	private void drawLog(DrawContext context, BackgroundQueueManager.SessionSummary summary, int x, int top, int width, int height) {
		List<String> lines = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		for (var line : summary.log()) {
			int color = switch (line.kind()) {
				case "system" -> 0xFFFFC53D;
				case "you" -> 0xFF7FD9FF;
				default -> 0xFFE4E6EB;
			};
			String stamp = VeloUi.clock(line.time()) + "  ";
			List<String> wrapped = VeloUi.wrap(line.text(), width - 8 - this.textRenderer.getWidth(stamp));
			for (int i = 0; i < wrapped.size(); i++) {
				lines.add((i == 0 ? stamp : "       ") + wrapped.get(i));
				colors.add(color);
			}
		}
		int total = lines.size() * 10;
		chatScroll = Math.max(0, Math.min(chatScroll, Math.max(0, total - height)));
		context.enableScissor(x, top, x + width, top + height);
		if (lines.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "No chat yet - it appears here live.", x + 4, top + 4, VeloUi.muted());
		}
		int y = top + height - total + (int) chatScroll;
		for (int i = 0; i < lines.size(); i++) {
			if (y + 10 >= top && y <= top + height) {
				context.drawTextWithShadow(this.textRenderer, lines.get(i), x + 4, y, colors.get(i));
			}
			y += 10;
		}
		context.disableScissor();
	}

	private void sendChat() {
		if (selected == null || chatField == null) {
			return;
		}
		String error = BackgroundQueueManager.sendChat(selected, chatField.getText());
		if (error == null) {
			chatField.setText("");
			chatValue = "";
			chatScroll = 0;
		}
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseX < contentX() + leftWidth()) {
			listScroll -= verticalAmount * 16;
		} else {
			chatScroll += verticalAmount * 12;
		}
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if ((input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER) && chatField != null && chatField.isFocused()) {
			sendChat();
			return true;
		}
		return super.keyPressed(input);
	}
}
