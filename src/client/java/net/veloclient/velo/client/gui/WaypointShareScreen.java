package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.social.FriendStatus;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.waypoints.Waypoint;
import net.veloclient.velo.client.waypoints.WaypointManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pick friends to send a waypoint to - they get it in chat (and as a popup) with an "Add to my waypoints" button. */
public final class WaypointShareScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 26;

	private final Waypoint waypoint;
	private final Set<String> chosen = new LinkedHashSet<>();
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private double scroll;
	private String status = "";
	private boolean sending;

	public WaypointShareScreen(Screen parent, Waypoint waypoint) {
		super(Text.literal("Share \"" + waypoint.name + "\""), 320, 330);
		returnTo(parent);
		this.waypoint = waypoint;
	}

	@Override
	protected void layoutContent() {
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int width = contentWidth();
		int y = contentY();
		SocialClient.State state = SocialClient.snapshot();
		if (state == null) {
			for (String line : VeloUi.wrap("Not connected to Velo Network - " + net.veloclient.velo.client.network.VeloServerClient.status(), width)) {
				context.drawTextWithShadow(this.textRenderer, line, x, y, VeloUi.muted());
				y += 11;
			}
			return;
		}
		if (state.friends.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "Add some friends first (Friends menu).", x, y, VeloUi.muted());
			return;
		}
		context.drawTextWithShadow(this.textRenderer, "Choose who gets it:", x, y, VeloUi.muted());
		int listTop = y + 14;
		int listBottom = contentBottom() - 30;
		int contentHeight = state.friends.size() * (ROW_HEIGHT + 2);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - (listBottom - listTop))));
		context.enableScissor(x, listTop, x + width, listBottom);
		int rowY = listTop - (int) scroll;
		for (SocialClient.Friend friend : state.friends) {
			boolean picked = chosen.contains(friend.uuid);
			boolean visible = rowY + ROW_HEIGHT > listTop && rowY < listBottom;
			boolean hovered = visible && mouseY >= listTop && mouseY < listBottom && VeloUi.inside(mouseX, mouseY, x, rowY, width, ROW_HEIGHT);
			VeloDraw.fillRounded(context, x, rowY, width, ROW_HEIGHT, 5, picked ? VeloUi.withAlpha(ThemeManager.active().accentStart(), 0x60)
					: hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x14) : VeloUi.withAlpha(0xFFFFFFFF, 0x08));
			VeloDraw.fillRounded(context, x + 6, rowY + 7, 12, 12, 3, picked ? ThemeManager.active().accentStart() : 0xFF3A3A40);
			if (picked) {
				context.drawTextWithShadow(this.textRenderer, "v", x + 9, rowY + 8, 0xFFFFFFFF);
			}
			PlayerHeads.draw(context, friend.uuid, friend.username, x + 24, rowY + 5, 16);
			context.drawTextWithShadow(this.textRenderer, friend.username, x + 46, rowY + 4, ThemeManager.active().text());
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(FriendStatus.describe(friend), width - 56), x + 46, rowY + 14, VeloUi.muted());
			if (visible) {
				int top = Math.max(rowY, listTop);
				hits.add(new VeloUi.Hit(x, top, width, Math.min(rowY + ROW_HEIGHT, listBottom) - top, () -> {
					if (!chosen.remove(friend.uuid)) {
						chosen.add(friend.uuid);
					}
				}));
			}
			rowY += ROW_HEIGHT + 2;
		}
		context.disableScissor();

		int bottom = contentBottom();
		if (!status.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, width - 130), x, bottom - 14, VeloUi.muted());
		}
		String label = sending ? "Sending..." : chosen.isEmpty() ? "Share" : "Share with " + chosen.size();
		VeloUi.Hit share = VeloUi.pill(context, x + width - 120, bottom - 20, 120, 20, label, chosen.isEmpty() ? 0 : 1, mouseX, mouseY, this::share);
		if (!chosen.isEmpty() && !sending) {
			hits.add(share);
		}
	}

	private void share() {
		sending = true;
		int count = chosen.size();
		SocialClient.shareWaypoint(List.copyOf(chosen), WaypointManager.toShared(waypoint)).thenAccept(error ->
				MinecraftClient.getInstance().execute(() -> {
					sending = false;
					if (error == null) {
						status = "Shared with " + count + " friend" + (count == 1 ? "" : "s");
						chosen.clear();
					} else {
						status = error;
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

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= verticalAmount * 16;
		return true;
	}
}
