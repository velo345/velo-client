package net.veloclient.velo.client.gui.media;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.SocialClient;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Someone's shared clip (a velo-client.com/c/ link a friend sent), played in the game with its sound. Copy the link,
 * or report it to the Velo staff (what's wrong + a few words) when it breaks the rules.
 */
public final class ClipWatchScreen extends VeloWindow {

	private static final String[][] CATEGORIES = {{"inappropriate", "Inappropriate"}, {"harassment", "Harassment"}, {"cheating", "Cheating"},
			{"scam", "Scam"}, {"copyright", "Not theirs"}, {"spam", "Spam"}, {"other", "Other"}};
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());

	private final String clipId;
	private final String sender;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private ClipPlayerPanel player;
	private JsonObject info;
	private String status = "";
	private int statusColor = 0xFF8A8A95;
	private boolean reporting;
	private boolean reported;
	private String category = "inappropriate";
	private TextFieldWidget reasonField;

	public ClipWatchScreen(Screen parent, String clipId, String sender) {
		super(Text.literal("Shared clip"), 720, 470);
		returnTo(parent);
		this.clipId = clipId;
		this.sender = sender;
		SocialClient.request("/v1/clips/info?id=" + clipId, null).thenAccept(reply -> MinecraftClient.getInstance().execute(() -> {
			if (!reply.ok()) {
				status = "This clip is gone - it was deleted or its time ran out.";
				statusColor = 0xFFFF7070;
				return;
			}
			info = reply.json();
			player = new ClipPlayerPanel(info.get("url").getAsString() + ".mp4", true);
		}));
	}

	@Override
	protected void layoutContent() {
		String keep = reasonField == null ? "" : reasonField.getText();
		this.clearChildren();
		if (reporting) {
			reasonField = new TextFieldWidget(this.textRenderer, contentX(), contentBottom() - 44, contentWidth() - 120, 18, Text.literal("Reason"));
			reasonField.setPlaceholder(VeloUi.placeholder("What's wrong with it? (optional)"));
			reasonField.setMaxLength(500);
			reasonField.setText(keep);
			addDrawableChild(reasonField);
		} else {
			reasonField = null;
		}
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		String title = info == null ? "Loading..." : info.get("title").getAsString();
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(title, width - 230), x, y + 2, 0xFFF0F0F4);
		if (info != null) {
			String by = "by " + info.get("username").getAsString() + (sender != null && !sender.equalsIgnoreCase(info.get("username").getAsString())
					? " · sent by " + sender : "") + " · " + info.get("views").getAsLong() + " views";
			if (info.has("expiresAt")) {
				by += " · online until " + DATE.format(Instant.ofEpochMilli(info.get("expiresAt").getAsLong()));
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(by, width - 230), x, y + 13, 0xFF8A8A95);
			String url = info.get("url").getAsString();
			hits.add(VeloUi.pill(context, x + width - 220, y, 104, 20, "Copy link", 0, mouseX, mouseY, () -> {
				MinecraftClient.getInstance().keyboard.setClipboard(url);
				status = "Link copied";
				statusColor = 0xFF6FE39A;
			}));
			boolean mine = info.get("username").getAsString().equalsIgnoreCase(net.veloclient.velo.client.gui.title.MenuInfo.username());
			if (!mine) {
				hits.add(VeloUi.pill(context, x + width - 110, y, 110, 20, reported ? "Reported" : "Report", 3, mouseX, mouseY, () -> {
					if (!reported) {
						reporting = !reporting;
						layoutContent();
					}
				}));
			}
		}
		int top = y + 28;
		int bottom = contentBottom() - (reporting ? 54 : 14);
		if (player != null) {
			player.render(context, this.textRenderer, x, top, width, bottom - top, mouseX, mouseY);
		} else {
			VeloDraw.fillRounded(context, x, top, width, bottom - top, 8, 0xFF07070A);
		}
		if (reporting) {
			int cy = contentBottom() - 66;
			int cx = x;
			for (String[] c : CATEGORIES) {
				int w = this.textRenderer.getWidth(c[1]) + 16;
				hits.add(VeloUi.pill(context, cx, cy, w, 16, c[1], category.equals(c[0]) ? 3 : 0, mouseX, mouseY, () -> category = c[0]));
				cx += w + 4;
			}
			hits.add(VeloUi.pill(context, x + width - 112, contentBottom() - 44, 112, 18, "Send report", 3, mouseX, mouseY, this::sendReport));
		}
		if (!status.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, width), x, contentBottom() - 10, statusColor);
		} else if (!reporting) {
			MenuKit.icon(context, "flag", x, contentBottom() - 11, 9, 0xFF6E6E78);
			context.drawTextWithShadow(this.textRenderer, "Breaks the rules? Report it - Velo staff look at every report.", x + 13, contentBottom() - 10, 0xFF6E6E78);
		}
	}

	private void sendReport() {
		String reason = reasonField == null ? "" : reasonField.getText();
		status = "Sending...";
		statusColor = 0xFF8A8A95;
		SocialClient.reportClip(clipId, category, reason).thenAccept(reply -> MinecraftClient.getInstance().execute(() -> {
			if (reply.ok()) {
				reported = true;
				reporting = false;
				layoutContent();
				status = "Thanks - staff will take a look.";
				statusColor = 0xFF6FE39A;
			} else {
				status = "Couldn't send: " + reply.error();
				statusColor = 0xFFFF7070;
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
		if (player != null && player.mouseClicked(click.x(), click.y())) {
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (player != null && player.mouseDragged(click.x(), click.y())) {
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (player != null) {
			player.mouseReleased();
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (player != null && (reasonField == null || !reasonField.isFocused()) && player.keyPressed(input.key())) {
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void removed() {
		if (player != null) {
			player.release();
			player = null;
		}
		super.removed();
	}
}
