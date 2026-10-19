package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Report someone's messages (friends chat or party chat) to the Velo Client team: what it is, an optional note, and - only if you
 * tick it - the recent messages of that chat so staff can see what happened.
 */
public final class ReportScreen extends VeloWindow {

	private static final String[][] CATEGORIES = {
			{"spam", "Spam"}, {"scam", "Scam"}, {"insult", "Insulting"}, {"harassment", "Harassment"}, {"inappropriate", "Inappropriate"}, {"other", "Other"}};

	private final String targetUuid;
	private final String targetName;
	private final String context;
	/** Party chat: who to report (the party's other members); null for a direct chat. */
	private final List<String[]> candidates;
	private String chosenUuid;
	private String chosenName;
	private String category = "spam";
	private boolean attach = true;
	private TextFieldWidget reason;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private String status = "";
	private boolean sending;
	private boolean sent;

	/** A direct chat with {@code uuid}. */
	public ReportScreen(Screen parent, String uuid, String name) {
		this(parent, uuid, name, "dm", null);
	}

	/** Party chat: pick who from {@code members} ({uuid, name}). */
	public static ReportScreen party(Screen parent, List<String[]> members) {
		return new ReportScreen(parent, null, null, "party", members);
	}

	private ReportScreen(Screen parent, String uuid, String name, String context, List<String[]> candidates) {
		super(Text.literal("Report"), 340, 300);
		returnTo(parent);
		this.targetUuid = uuid;
		this.targetName = name;
		this.context = context;
		this.candidates = candidates;
		this.chosenUuid = uuid;
		this.chosenName = name;
		if (candidates != null && !candidates.isEmpty()) {
			chosenUuid = candidates.get(0)[0];
			chosenName = candidates.get(0)[1];
		}
	}

	@Override
	protected void layoutContent() {
		String keep = reason == null ? "" : reason.getText();
		this.clearChildren();
		int top = contentY() + (candidates != null ? 98 : 74);
		reason = new TextFieldWidget(this.textRenderer, contentX(), top, contentWidth(), 18, Text.literal("Note"));
		reason.setPlaceholder(Text.literal("What happened? (optional)"));
		reason.setMaxLength(500);
		reason.setText(keep);
		addDrawableChild(reason);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		if (sent) {
			context.drawTextWithShadow(this.textRenderer, "Thanks - the Velo Client team will look at it.", x, y + 10, ThemeManager.active().text());
			for (String line : VeloUi.wrap("You can also block " + chosenName + " so they can't message you any more.", width)) {
				y += 12;
				context.drawTextWithShadow(this.textRenderer, line, x, y + 12, VeloUi.muted());
			}
			hits.add(VeloUi.pill(context, x + width - 70, contentBottom() - 20, 70, 20, "Close", 1, mouseX, mouseY, this::requestClose));
			return;
		}
		if (candidates != null) {
			context.drawTextWithShadow(this.textRenderer, "Who?", x, y, VeloUi.muted());
			int px = x;
			for (String[] member : candidates) {
				int w = this.textRenderer.getWidth(member[1]) + 14;
				boolean on = member[0].equals(chosenUuid);
				hits.add(VeloUi.pill(context, px, y + 11, w, 16, member[1], on ? 1 : 0, mouseX, mouseY, () -> {
					chosenUuid = member[0];
					chosenName = member[1];
				}));
				px += w + 4;
			}
			y += 32;
		} else {
			context.drawTextWithShadow(this.textRenderer, "Reporting " + targetName, x, y, ThemeManager.active().text());
			y += 14;
		}
		context.drawTextWithShadow(this.textRenderer, "What is it?", x, y, VeloUi.muted());
		int cx = x;
		int cy = y + 11;
		for (String[] c : CATEGORIES) {
			int w = this.textRenderer.getWidth(c[1]) + 14;
			if (cx + w > x + width) {
				cx = x;
				cy += 20;
			}
			boolean on = c[0].equals(category);
			hits.add(VeloUi.pill(context, cx, cy, w, 16, c[1], on ? 1 : 0, mouseX, mouseY, () -> category = c[0]));
			cx += w + 4;
		}
		int boxY = reason.getY() + 26;
		VeloDraw.fillRounded(context, x, boxY, 12, 12, 3, attach ? ThemeManager.active().accentStart() : 0xFF3A3A40);
		if (attach) {
			context.drawTextWithShadow(this.textRenderer, "v", x + 3, boxY + 2, ThemeManager.active().onAccent());
		}
		hits.add(new VeloUi.Hit(x, boxY - 2, width, 16, () -> attach = !attach));
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Attach the recent messages of this chat for staff", width - 18), x + 18, boxY + 2,
				ThemeManager.active().text());
		int noteY = boxY + 20;
		for (String line : VeloUi.wrap("Chats aren't watched by anyone - chat at your own risk. Reports are read by people, so not every message"
				+ " can be checked or acted on.", width)) {
			context.drawTextWithShadow(this.textRenderer, line, x, noteY, VeloUi.muted());
			noteY += 10;
		}
		if (!status.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, width - 90), x, contentBottom() - 14, 0xFFFF8A8A);
		}
		hits.add(VeloUi.pill(context, x + width - 80, contentBottom() - 20, 80, 20, sending ? "Sending..." : "Send report", 3, mouseX, mouseY, this::send));
	}

	private void send() {
		if (sending || chosenUuid == null) {
			return;
		}
		sending = true;
		SocialClient.reportPlayer(chosenUuid, category, reason.getText(), context, attach).thenAccept(reply ->
				MinecraftClient.getInstance().execute(() -> {
					sending = false;
					if (reply.ok()) {
						sent = true;
						this.clearChildren();
					} else {
						status = reply.error();
					}
				}));
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
}
