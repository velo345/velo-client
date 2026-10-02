package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.network.SocialStatusCache;
import net.veloclient.velo.client.network.VeloServerClient;
import net.veloclient.velo.client.social.FriendStatus;
import net.veloclient.velo.client.social.SocialNotifications;
import net.veloclient.velo.client.social.SocialSettings;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.waypoints.WaypointIcons;
import net.veloclient.velo.client.waypoints.WaypointManager;
import net.veloclient.velo.module.ModuleRegistry;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Friends &amp; messages, in game (the launcher has the same thing in its Friends tab). Left: your
 * own status, "add friend", and your friends with live presence. Right: a chat with the selected
 * friend, or the Requests / Blocked / Notifications views picked from the bar on top.
 */
public final class FriendsScreen extends VeloWindow {

	private enum View { CHAT, REQUESTS, BLOCKED, NOTIFICATIONS }

	private static final int MAX_LEFT_WIDTH = 188;
	private static final int ROW_HEIGHT = 30;
	private static final int NAV_HEIGHT = 18;

	private static FriendsScreen openInstance;

	private View view = View.CHAT;
	private String selected;
	private TextFieldWidget addField;
	private TextFieldWidget chatField;
	private String addFieldValue = "";
	private String chatFieldValue = "";
	private String feedback = "";
	private boolean feedbackError;
	private long feedbackUntil;
	private double friendsScroll;
	private double chatScroll;
	private double rightScroll;
	private String confirmAction;
	private long confirmUntil;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private final java.util.Set<String> loadedConversations = new java.util.HashSet<>();

	public FriendsScreen() {
		super(Text.literal("Friends"), 620, 400);
	}

	// ---- Entry points (popups, keybind, menus) ----

	public static void open() {
		show(new FriendsScreen());
	}

	public static void openChat(String uuid) {
		FriendsScreen screen = openInstance != null && ClientCompat.currentScreen() == openInstance ? openInstance : new FriendsScreen();
		screen.view = View.CHAT;
		screen.select(uuid);
		show(screen);
	}

	public static void openRequests() {
		FriendsScreen screen = openInstance != null && ClientCompat.currentScreen() == openInstance ? openInstance : new FriendsScreen();
		screen.view = View.REQUESTS;
		show(screen);
	}

	public static void openNotifications() {
		FriendsScreen screen = openInstance != null && ClientCompat.currentScreen() == openInstance ? openInstance : new FriendsScreen();
		screen.view = View.NOTIFICATIONS;
		show(screen);
	}

	private static void show(FriendsScreen screen) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (ClientCompat.currentScreen() != screen) {
			client.setScreen(screen);
		}
	}

	/** Whether {@code uuid}'s chat is on screen right now (its messages then don't pop up). */
	public static boolean isChatOpen(String uuid) {
		FriendsScreen screen = openInstance;
		return screen != null && ClientCompat.currentScreen() == screen && screen.view == View.CHAT && uuid.equals(screen.selected);
	}

	private void select(String uuid) {
		selected = uuid;
		chatScroll = 0;
		if (uuid != null) {
			SocialNotifications.clearBurst(uuid);
			if (loadedConversations.add(uuid)) {
				SocialClient.loadConversation(uuid);
			}
			SocialClient.markRead(uuid);
		}
	}

	// ---- Layout ----

	@Override
	protected void layoutContent() {
		openInstance = this;
		if (addField != null) {
			addFieldValue = addField.getText();
		}
		if (chatField != null) {
			chatFieldValue = chatField.getText();
		}
		this.clearChildren();

		int leftX = contentX();
		addField = new TextFieldWidget(this.textRenderer, leftX + 6, contentY() + 40, leftWidth() - 58, 14, Text.literal("Add friend"));
		addField.setDrawsBackground(false);
		addField.setPlaceholder(Text.literal(VeloUi.trim("Username or UUID", addField.getWidth() - 8)));
		addField.setMaxLength(36);
		addField.setText(addFieldValue);
		addDrawableChild(addField);

		int rightX = rightX();
		int rightWidth = rightWidth();
		chatField = new TextFieldWidget(this.textRenderer, rightX + 8, contentBottom() - 15, rightWidth - 64, 14, Text.literal("Message"));
		chatField.setDrawsBackground(false);
		chatField.setPlaceholder(Text.literal(VeloUi.trim("Write a message...", chatField.getWidth() - 8)));
		chatField.setMaxLength(500);
		chatField.setText(chatFieldValue);
		chatField.visible = view == View.CHAT && SocialClient.friend(selected) != null;
		addDrawableChild(chatField);
	}

	/** The friends column shrinks on small GUI sizes (Auto GUI scale on 1080p is only 480x270). */
	private int leftWidth() {
		return Math.max(130, Math.min(MAX_LEFT_WIDTH, (int) (contentWidth() * 0.42f)));
	}

	private int rightX() {
		return contentX() + leftWidth() + 10;
	}

	private int rightWidth() {
		return contentWidth() - leftWidth() - 10;
	}

	// ---- Rendering ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		if (chatField != null) {
			chatField.visible = view == View.CHAT && SocialClient.friend(selected) != null && SocialClient.isReady();
		}
		if (addField != null) {
			addField.visible = SocialClient.isReady();
		}
		if (!SocialClient.isReady()) {
			renderNotConnected(context, mouseX, mouseY);
			return;
		}
		renderLeft(context, mouseX, mouseY);
		renderRight(context, mouseX, mouseY);
	}

	private void renderNotConnected(DrawContext context, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		int centerX = contentX() + contentWidth() / 2;
		int y = contentY() + 70;
		boolean networkOn = ModuleRegistry.get("velo-network").map(m -> m.isEnabled()).orElse(false);
		boolean signedIn = VeloServerClient.sessionToken() != null;
		String title = !networkOn ? "Velo Network is turned off" : signedIn ? "Loading your friends..." : "Connecting to Velo Network...";
		String socialError = SocialClient.lastError();
		String detail = !networkOn ? "Friends and messages need Velo Network."
				: socialError != null ? VeloServerClient.status() + " - friends: " + socialError
				: VeloServerClient.status();
		context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(VeloUi.trim(title, contentWidth() - 20)), centerX, y, theme.text());
		int lineY = y + 16;
		for (String line : VeloUi.wrap(detail, contentWidth() - 80)) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), centerX, lineY, VeloUi.muted());
			lineY += 11;
		}
		if (!networkOn) {
			hits.add(VeloUi.pill(context, centerX - 60, lineY + 10, 120, 18, "Turn on Velo Network", 1, mouseX, mouseY,
					() -> ModuleRegistry.get("velo-network").ifPresent(m -> m.setEnabled(true))));
		} else {
			hits.add(VeloUi.pill(context, centerX - 40, lineY + 10, 80, 18, "Retry now", 0, mouseX, mouseY, VeloServerClient::retryNow));
		}
	}

	private void renderLeft(DrawContext context, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		SocialClient.State state = SocialClient.snapshot();
		int x = contentX();
		int y = contentY();

		// Your own card + status switch.
		VeloDraw.fillRounded(context, x, y, leftWidth(), 32, 6, panelColor());
		String myUuid = state.me != null ? state.me.uuid : null;
		String myName = state.me != null ? state.me.username : "You";
		PlayerHeads.draw(context, myUuid, myName, x + 6, y + 6, 20);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(myName, leftWidth() - 106), x + 32, y + 6, theme.text());
		boolean invisible = state.me != null ? state.me.invisible : SocialStatusCache.invisible();
		int dot = invisible ? FriendStatus.OFFLINE_COLOR : FriendStatus.ONLINE_COLOR;
		VeloDraw.fillCircle(context, x + 35, y + 21, 2, dot);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(invisible ? "Appearing offline" : "Online", leftWidth() - 116), x + 41, y + 17, VeloUi.muted());
		int pillWidth = 62;
		hits.add(VeloUi.pill(context, x + leftWidth() - pillWidth - 6, y + 8, pillWidth, 16, invisible ? "Go online" : "Hide me", 0,
				mouseX, mouseY, () -> report(SocialClient.setInvisible(!invisible),
						!invisible ? "You now appear offline to friends" : "Friends can see you again")));

		// Add friend.
		int addY = y + 38;
		VeloDraw.fillRounded(context, x, addY - 2, leftWidth() - 50, 18, 5, fieldColor(addField != null && addField.isFocused()));
		hits.add(VeloUi.pill(context, x + leftWidth() - 46, addY - 2, 46, 18, "Add", 1, mouseX, mouseY, this::submitAdd));

		// Feedback line.
		int listTop = addY + 22;
		if (!feedback.isEmpty() && System.currentTimeMillis() < feedbackUntil) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(feedback, leftWidth()), x, listTop,
					feedbackError ? 0xFFFF7070 : 0xFF6FE39A);
			listTop += 12;
		}

		// Requests banner.
		if (!state.incoming.isEmpty()) {
			boolean hovered = VeloUi.inside(mouseX, mouseY, x, listTop, leftWidth(), 16);
			VeloDraw.fillRounded(context, x, listTop, leftWidth(), 16, 5,
					hovered ? VeloAnim.lerpArgb(theme.accentStart(), 0xFFFFFFFF, 0.12f) : theme.accentStart());
			String banner = state.incoming.size() == 1 ? "1 friend request" : state.incoming.size() + " friend requests";
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(banner, leftWidth() - 12), x + 8, listTop + 4, 0xFFFFFFFF);
			hits.add(new VeloUi.Hit(x, listTop, leftWidth(), 16, () -> view = View.REQUESTS));
			listTop += 20;
		}

		// Friends list.
		long onlineCount = state.friends.stream().filter(f -> f.online).count();
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim("FRIENDS  " + onlineCount + "/" + state.friends.size() + " online", leftWidth() - 4),
				x + 2, listTop, VeloUi.muted());
		listTop += 12;
		int listBottom = contentBottom();
		if (listBottom - listTop < 12) {
			return;
		}
		int listHeight = listBottom - listTop;
		List<SocialClient.Friend> friends = state.friends;
		int contentHeight = friends.size() * (ROW_HEIGHT + 2);
		friendsScroll = Math.max(0, Math.min(friendsScroll, Math.max(0, contentHeight - listHeight)));
		if (friends.isEmpty()) {
			int lineY = listTop + 8;
			for (String line : VeloUi.wrap("No friends yet. Type a username above and press Add.", leftWidth() - 8)) {
				context.drawTextWithShadow(this.textRenderer, line, x + 4, lineY, VeloUi.muted());
				lineY += 11;
			}
		}
		context.enableScissor(x, listTop, x + leftWidth(), listBottom);
		int rowY = listTop - (int) friendsScroll;
		for (SocialClient.Friend friend : friends) {
			if (rowY + ROW_HEIGHT >= listTop && rowY <= listBottom) {
				drawFriendRow(context, friend, x, rowY, mouseX, mouseY, listTop, listBottom);
			}
			rowY += ROW_HEIGHT + 2;
		}
		context.disableScissor();
		drawScrollbar(context, x + leftWidth() - 2, listTop, listHeight, contentHeight, friendsScroll);
	}

	private void drawFriendRow(DrawContext context, SocialClient.Friend friend, int x, int y, int mouseX, int mouseY, int clipTop, int clipBottom) {
		Theme theme = ThemeManager.active();
		boolean isSelected = view == View.CHAT && friend.uuid.equals(selected);
		boolean hovered = mouseY >= clipTop && mouseY < clipBottom && VeloUi.inside(mouseX, mouseY, x, y, leftWidth(), ROW_HEIGHT);
		if (isSelected || hovered) {
			int bg = isSelected ? VeloAnim.lerpArgb(theme.accentStart(), 0xFF000000, 0.55f) : VeloUi.withAlpha(0xFFFFFFFF, 0x12);
			VeloDraw.fillRounded(context, x, y, leftWidth() - 4, ROW_HEIGHT, 5, bg);
		}
		if (isSelected) {
			VeloDraw.fillRounded(context, x, y + 5, 2, ROW_HEIGHT - 10, 1, theme.accentStart());
		}
		PlayerHeads.draw(context, friend.uuid, friend.username, x + 6, y + 5, 20);
		VeloDraw.fillCircle(context, x + 25, y + 24, 3, 0xFF101010);
		VeloDraw.fillCircle(context, x + 25, y + 24, 2, FriendStatus.dotColor(friend));
		int textX = x + 32;
		int badgeSpace = friend.unread > 0 ? 22 : 0;
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(friend.username, leftWidth() - 40 - badgeSpace), textX, y + 5,
				friend.online ? theme.text() : VeloUi.withAlpha(theme.text(), 0xAA));
		String status = FriendStatus.describe(friend);
		if (SocialSettings.isMuted(friend.uuid)) {
			status = "(muted) " + status;
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, leftWidth() - 40), textX, y + 17, VeloUi.muted());
		if (friend.unread > 0) {
			String count = friend.unread > 99 ? "99+" : String.valueOf(friend.unread);
			int badgeWidth = Math.max(14, this.textRenderer.getWidth(count) + 6);
			int badgeX = x + leftWidth() - 8 - badgeWidth;
			VeloDraw.fillRounded(context, badgeX, y + 4, badgeWidth, 11, 5, theme.accentStart());
			context.drawTextWithShadow(this.textRenderer, count, badgeX + (badgeWidth - this.textRenderer.getWidth(count)) / 2, y + 6, 0xFFFFFFFF);
		}
		if (hovered) {
			hits.add(new VeloUi.Hit(x, Math.max(y, clipTop), leftWidth(), Math.min(ROW_HEIGHT, clipBottom - Math.max(y, clipTop)), () -> {
				view = View.CHAT;
				select(friend.uuid);
				layoutContent();
			}));
		}
	}

	private void renderRight(DrawContext context, int mouseX, int mouseY) {
		int x = rightX();
		int width = rightWidth();
		int y = contentY();
		VeloDraw.fillRounded(context, x, y, width, contentBottom() - y, 7, panelColor());

		// Segmented nav.
		SocialClient.State state = SocialClient.snapshot();
		View[] views = View.values();
		boolean narrow = width < 300;
		String[] labels = {"Chat", (narrow ? "Req." : "Requests") + (state.incoming.isEmpty() ? "" : " (" + state.incoming.size() + ")"),
				"Blocked", narrow ? "Popups" : "Notifications"};
		int segWidth = (width - 12) / views.length;
		for (int i = 0; i < views.length; i++) {
			View target = views[i];
			int segX = x + 6 + i * segWidth;
			boolean active = view == target;
			boolean hovered = VeloUi.inside(mouseX, mouseY, segX, y + 6, segWidth - 4, NAV_HEIGHT);
			Theme theme = ThemeManager.active();
			int bg = active ? theme.accentStart() : hovered ? VeloUi.withAlpha(0xFFFFFFFF, 0x18) : VeloUi.withAlpha(0xFF000000, 0x30);
			VeloDraw.fillRounded(context, segX, y + 6, segWidth - 4, NAV_HEIGHT, 5, bg);
			String label = VeloUi.trim(labels[i], segWidth - 10);
			context.drawTextWithShadow(this.textRenderer, label, segX + (segWidth - 4 - this.textRenderer.getWidth(label)) / 2, y + 11,
					active ? 0xFFFFFFFF : theme.text());
			hits.add(new VeloUi.Hit(segX, y + 6, segWidth - 4, NAV_HEIGHT, () -> {
				view = target;
				rightScroll = 0;
				layoutContent();
			}));
		}

		int bodyTop = y + 6 + NAV_HEIGHT + 8;
		if (view == View.CHAT) {
			renderChat(context, x, bodyTop, width, mouseX, mouseY);
			return;
		}
		// The list views scroll inside the panel and are clipped to it; clicks outside the visible
		// part are ignored so a scrolled-away button can't be hit through the panel edge.
		int bodyBottom = contentBottom() - 4;
		int hitsBefore = hits.size();
		context.enableScissor(x, bodyTop, x + width, bodyBottom);
		int top = bodyTop - (int) rightScroll;
		int end = switch (view) {
			case REQUESTS -> renderRequests(context, x, top, width, mouseX, mouseY);
			case BLOCKED -> renderBlocked(context, x, top, width, mouseX, mouseY);
			default -> renderNotificationSettings(context, x, top, width, mouseX, mouseY);
		};
		context.disableScissor();
		clipHits(hitsBefore, bodyTop, bodyBottom);
		int contentHeight = end - top;
		rightScroll = Math.max(0, Math.min(rightScroll, Math.max(0, contentHeight - (bodyBottom - bodyTop))));
		drawScrollbar(context, x + width - 2, bodyTop, bodyBottom - bodyTop, contentHeight, rightScroll);
	}

	/** Drops/shrinks hits registered since {@code from} so only their part inside [top, bottom) stays clickable. */
	private void clipHits(int from, int top, int bottom) {
		for (int i = hits.size() - 1; i >= from; i--) {
			VeloUi.Hit hit = hits.get(i);
			int y1 = Math.max(hit.y(), top);
			int y2 = Math.min(hit.y() + hit.height(), bottom);
			if (y2 <= y1) {
				hits.remove(i);
			} else if (y1 != hit.y() || y2 != hit.y() + hit.height()) {
				hits.set(i, new VeloUi.Hit(hit.x(), y1, hit.width(), y2 - y1, hit.action()));
			}
		}
	}

	private void renderChat(DrawContext context, int x, int top, int width, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		SocialClient.Friend friend = SocialClient.friend(selected);
		if (friend == null) {
			SocialClient.State state = SocialClient.snapshot();
			String hint = state.friends.isEmpty()
					? "Add a friend on the left - they'll get a request they can accept in the launcher or in game."
					: "Pick a friend on the left to chat.";
			int lineY = top + 60;
			for (String line : VeloUi.wrap(hint, width - 40)) {
				context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), x + width / 2, lineY, VeloUi.muted());
				lineY += 11;
			}
			return;
		}

		// Header: who, where, actions (actions drop to their own row when the panel is narrow).
		String joinableHere = joinableAddress(friend);
		int buttonsWidth = 46 + 58 + 52 + (joinableHere != null ? 52 : 0) + 8;
		boolean stacked = width - buttonsWidth < 150;
		int textWidth = stacked ? width - 50 : width - buttonsWidth - 52;
		PlayerHeads.draw(context, friend.uuid, friend.username, x + 8, top, 24);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(friend.username, textWidth), x + 38, top + 2, theme.text());
		VeloDraw.fillCircle(context, x + 41, top + 17, 2, FriendStatus.dotColor(friend));
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(FriendStatus.describe(friend), textWidth - 9), x + 47, top + 13, VeloUi.muted());
		int buttonRow = stacked ? top + 28 : top + 3;
		int buttonX = x + width - 8;
		boolean muted = SocialSettings.isMuted(friend.uuid);
		// Shrink the buttons evenly when even a row of their own is too narrow for them.
		int buttonCount = joinableHere != null ? 4 : 3;
		float fit = Math.min(1f, (width - 16 - 4f * buttonCount) / (buttonsWidth - 8f));
		int blockWidth = Math.round(46 * fit);
		int unfriendWidth = Math.round(54 * fit);
		int muteWidth = Math.round(48 * fit);
		int joinWidth = Math.round(48 * fit);
		buttonX -= blockWidth;
		hits.add(confirmPill(context, buttonX, buttonRow, blockWidth, "Block", "block:" + friend.uuid, mouseX, mouseY,
				() -> report(SocialClient.block(friend.uuid), "Blocked " + friend.username)));
		buttonX -= unfriendWidth + 4;
		hits.add(confirmPill(context, buttonX, buttonRow, unfriendWidth, "Unfriend", "unfriend:" + friend.uuid, mouseX, mouseY,
				() -> report(SocialClient.unfriend(friend.uuid), "Removed " + friend.username)));
		buttonX -= muteWidth + 4;
		hits.add(VeloUi.pill(context, buttonX, buttonRow, muteWidth, 16, muted ? "Unmute" : "Mute", 0, mouseX, mouseY, () -> {
			SocialSettings.setMuted(friend.uuid, !muted);
			flash(muted ? "Popups from " + friend.username + " are back on" : "No more popups from " + friend.username, false);
		}));
		String joinable = joinableAddress(friend);
		if (joinable != null) {
			boolean sameServer = joinable.equalsIgnoreCase(String.valueOf(ClientCompat.currentServerAddress()))
					&& ClientCompat.dimensionId() != null;
			buttonX -= joinWidth + 4;
			VeloUi.Hit join = VeloUi.pill(context, buttonX, buttonRow, joinWidth, 16, sameServer ? "Here" : "Join", sameServer ? 0 : 2,
					mouseX, mouseY, () -> ClientCompat.joinServer(joinable, friend.username + "'s server"));
			if (!sameServer) {
				hits.add(join);
			}
		}

		int chatTop = top + (stacked ? 50 : 30);
		context.fill(x + 6, chatTop - 3, x + width - 6, chatTop - 2, VeloUi.withAlpha(theme.text(), 0x20));
		int chatBottom = contentBottom() - 22;
		if (chatBottom - chatTop < 12) {
			return;
		}
		drawMessages(context, friend, x + 6, chatTop, width - 12, chatBottom - chatTop, mouseX, mouseY);

		// Input bar.
		int inputY = contentBottom() - 18;
		VeloDraw.fillRounded(context, x + 4, inputY, width - 60, 18, 5, fieldColor(chatField != null && chatField.isFocused()));
		hits.add(VeloUi.pill(context, x + width - 52, inputY, 46, 18, "Send", 1, mouseX, mouseY, this::submitMessage));
	}

	private void drawMessages(DrawContext context, SocialClient.Friend friend, int x, int top, int width, int height, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		List<SocialClient.Message> messages = SocialClient.conversation(friend.uuid);
		String me = SocialClient.snapshot().me != null ? SocialClient.snapshot().me.uuid : "";
		int maxBubble = (int) (width * 0.72f);

		// Lay out bottom-up so the newest message sits right above the input.
		record Laid(SocialClient.Message message, boolean mine, List<String> lines, int bubbleWidth, int height, boolean showTime) {
		}
		List<Laid> laid = new ArrayList<>();
		int total = 0;
		for (int i = 0; i < messages.size(); i++) {
			SocialClient.Message message = messages.get(i);
			boolean mine = message.from.equals(me);
			SocialClient.Message next = i + 1 < messages.size() ? messages.get(i + 1) : null;
			boolean showTime = next == null || !next.from.equals(message.from) || next.time - message.time > 5 * 60_000;
			List<String> lines;
			int bubbleWidth;
			int bubbleHeight;
			if (message.isWaypoint()) {
				lines = List.of();
				bubbleWidth = Math.min(maxBubble, 190);
				bubbleHeight = mine ? 34 : 52;
			} else {
				lines = VeloUi.wrap(message.text, maxBubble - 12);
				int widest = 0;
				for (String line : lines) {
					widest = Math.max(widest, this.textRenderer.getWidth(line));
				}
				bubbleWidth = widest + 12;
				bubbleHeight = lines.size() * 10 + 8;
			}
			int h = bubbleHeight + (showTime ? 11 : 3);
			laid.add(new Laid(message, mine, lines, bubbleWidth, h, showTime));
			total += h;
		}
		chatScroll = Math.max(0, Math.min(chatScroll, Math.max(0, total - height)));

		context.enableScissor(x, top, x + width, top + height);
		if (messages.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Say hi to " + friend.username + "!"),
					x + width / 2, top + height / 2 - 4, VeloUi.muted());
		}
		int cursor = top + height + (int) chatScroll;
		for (int i = laid.size() - 1; i >= 0; i--) {
			Laid item = laid.get(i);
			cursor -= item.height();
			if (cursor > top + height || cursor + item.height() < top) {
				continue;
			}
			int bubbleHeight = item.height() - (item.showTime() ? 11 : 3);
			int bubbleX = item.mine() ? x + width - item.bubbleWidth() - 2 : x + 2;
			int bubbleColor = item.mine() ? VeloAnim.lerpArgb(theme.accentStart(), 0xFF000000, 0.25f) : VeloUi.withAlpha(0xFFFFFFFF, 0x1C);
			VeloDraw.fillRounded(context, bubbleX, cursor, item.bubbleWidth(), bubbleHeight, 6, bubbleColor);
			if (item.message().isWaypoint()) {
				drawWaypointCard(context, item.message(), item.mine(), friend, bubbleX, cursor, item.bubbleWidth(), mouseX, mouseY, top, top + height);
			} else {
				int lineY = cursor + 4;
				for (String line : item.lines()) {
					context.drawTextWithShadow(this.textRenderer, line, bubbleX + 6, lineY, 0xFFFFFFFF);
					lineY += 10;
				}
			}
			if (item.showTime()) {
				String time = VeloUi.clock(item.message().time);
				int timeX = item.mine() ? x + width - 2 - this.textRenderer.getWidth(time) : x + 4;
				context.drawTextWithShadow(this.textRenderer, time, timeX, cursor + bubbleHeight + 2, VeloUi.withAlpha(theme.text(), 0x66));
			}
		}
		context.disableScissor();
		drawScrollbar(context, x + width, top, height, total, Math.max(0, total - height) - chatScroll);
	}

	private void drawWaypointCard(DrawContext context, SocialClient.Message message, boolean mine, SocialClient.Friend friend,
			int x, int y, int width, int mouseX, int mouseY, int clipTop, int clipBottom) {
		Map<String, Object> waypoint = message.waypoint;
		String icon = waypoint.get("icon") instanceof String s ? s : WaypointIcons.NONE;
		var stack = WaypointIcons.stack(icon);
		int color = waypoint.get("color") instanceof Number n ? n.intValue() | 0xFF000000 : 0xFFFFFFFF;
		if (stack != null) {
			context.drawItemWithoutEntity(stack, x + 6, y + 6);
		} else {
			VeloDraw.fillCircle(context, x + 14, y + 14, 6, color);
		}
		String name = waypoint.get("name") instanceof String s ? s : "Waypoint";
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(name, width - 34), x + 28, y + 5, color);
		String coords = String.format(Locale.ROOT, "%d, %d, %d  %s", (int) Math.floor(number(waypoint.get("x"))),
				(int) Math.floor(number(waypoint.get("y"))), (int) Math.floor(number(waypoint.get("z"))),
				WaypointManager.dimensionLabel(waypoint.get("dimension") instanceof String d ? d : "minecraft:overworld"));
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(coords, width - 34), x + 28, y + 17, VeloUi.withAlpha(0xFFFFFFFF, 0xB0));
		if (!mine) {
			boolean visible = y + 32 >= clipTop && y + 48 <= clipBottom;
			VeloUi.Hit hit = VeloUi.pill(context, x + 6, y + 32, width - 12, 15, "Add to my waypoints", 1, mouseX, mouseY, () -> {
				WaypointManager.importShared(waypoint, friend.username);
				flash("Added \"" + name + "\" to your waypoints", false);
			});
			if (visible) {
				hits.add(hit);
			}
		}
	}

	/** The server address a friend is playing on, if it's one you could join (not singleplayer/Realms). */
	static String joinableAddress(SocialClient.Friend friend) {
		if (friend == null || !friend.online || friend.activity == null || !"server".equals(friend.activity.kind)) {
			return null;
		}
		String address = friend.activity.detail;
		return address == null || address.isBlank() ? null : address;
	}

	private static double number(Object value) {
		return value instanceof Number n ? n.doubleValue() : 0;
	}

	private int renderRequests(DrawContext context, int x, int top, int width, int mouseX, int mouseY) {
		SocialClient.State state = SocialClient.snapshot();
		int y = top;
		context.drawTextWithShadow(this.textRenderer, "INCOMING", x + 10, y, VeloUi.muted());
		y += 12;
		if (state.incoming.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "No pending requests.", x + 10, y + 2, VeloUi.withAlpha(ThemeManager.active().text(), 0x80));
			y += 16;
		}
		for (SocialClient.Request request : state.incoming) {
			drawPlayerRow(context, x + 6, y, width - 12, request.uuid, request.username, "Sent " + VeloUi.ago(request.time));
			int actions = actionsWidthFor(width - 12);
			int each = (actions - 8) / 2;
			hits.add(VeloUi.pill(context, x + width - 10 - actions, y + 7, each, 16, "Accept", 2, mouseX, mouseY,
					() -> report(SocialClient.respond(request.uuid, true), "You're now friends with " + request.username)));
			hits.add(VeloUi.pill(context, x + width - 10 - each, y + 7, each, 16, "Deny", 3, mouseX, mouseY,
					() -> report(SocialClient.respond(request.uuid, false), "Declined " + request.username)));
			y += ROW_HEIGHT + 2;
		}
		y += 8;
		context.drawTextWithShadow(this.textRenderer, "SENT", x + 10, y, VeloUi.muted());
		y += 12;
		if (state.outgoing.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "Nothing waiting on others.", x + 10, y + 2, VeloUi.withAlpha(ThemeManager.active().text(), 0x80));
		}
		for (SocialClient.Request request : state.outgoing) {
			drawPlayerRow(context, x + 6, y, width - 12, request.uuid, request.username, "Waiting - sent " + VeloUi.ago(request.time));
			int cancelWidth = Math.min(54, actionsWidthFor(width - 12));
			hits.add(VeloUi.pill(context, x + width - 10 - cancelWidth, y + 7, cancelWidth, 16, "Cancel", 0, mouseX, mouseY,
					() -> report(SocialClient.cancelRequest(request.uuid), "Cancelled request to " + request.username)));
			y += ROW_HEIGHT + 2;
		}
		return y + 16;
	}

	private int renderBlocked(DrawContext context, int x, int top, int width, int mouseX, int mouseY) {
		SocialClient.State state = SocialClient.snapshot();
		int y = top;
		for (String line : VeloUi.wrap("Blocked players can't message you or send friend requests until you unblock them. They aren't told.", width - 20)) {
			context.drawTextWithShadow(this.textRenderer, line, x + 10, y, VeloUi.muted());
			y += 11;
		}
		y += 6;
		if (state.blocked.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "You haven't blocked anyone.", x + 10, y + 2, VeloUi.withAlpha(ThemeManager.active().text(), 0x80));
		}
		for (SocialClient.PlayerRef ref : state.blocked) {
			drawPlayerRow(context, x + 6, y, width - 12, ref.uuid, ref.username, "Blocked");
			int unblockWidth = Math.min(60, actionsWidthFor(width - 12));
			hits.add(VeloUi.pill(context, x + width - 10 - unblockWidth, y + 7, unblockWidth, 16, "Unblock", 0, mouseX, mouseY,
					() -> report(SocialClient.unblock(ref.uuid), "Unblocked " + ref.username)));
			y += ROW_HEIGHT + 2;
		}
		return y + 16;
	}

	private int renderNotificationSettings(DrawContext context, int x, int top, int width, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		int y = top;
		boolean dnd = SocialSettings.doNotDisturb();
		y = switchRow(context, x + 10, y, width - 20, "Do not disturb", "Silence every friend popup at once.", dnd,
				mouseX, mouseY, () -> SocialSettings.setDoNotDisturb(!dnd));
		y += 4;
		context.fill(x + 10, y, x + width - 10, y + 1, VeloUi.withAlpha(theme.text(), 0x20));
		y += 6;
		y = switchRow(context, x + 10, y, width - 20, "Messages", "A popup when a friend messages you.", SocialSettings.messages(),
				mouseX, mouseY, () -> SocialSettings.setMessages(!SocialSettings.messages()));
		y = switchRow(context, x + 10, y, width - 20, "Friend requests", "Accept or deny right on the popup.", SocialSettings.requests(),
				mouseX, mouseY, () -> SocialSettings.setRequests(!SocialSettings.requests()));
		y = switchRow(context, x + 10, y, width - 20, "Friends coming online", "When a friend starts playing.", SocialSettings.online(),
				mouseX, mouseY, () -> SocialSettings.setOnline(!SocialSettings.online()));
		y = switchRow(context, x + 10, y, width - 20, "Shared waypoints", "When a friend sends you a waypoint.", SocialSettings.waypoints(),
				mouseX, mouseY, () -> SocialSettings.setWaypoints(!SocialSettings.waypoints()));
		y = switchRow(context, x + 10, y, width - 20, "Popup sound", "A soft chime with each popup.", SocialSettings.sound(),
				mouseX, mouseY, () -> SocialSettings.setSound(!SocialSettings.sound()));
		y += 4;
		for (String line : VeloUi.wrap("To mute one friend only, open their chat and press Mute.", width - 20)) {
			context.drawTextWithShadow(this.textRenderer, line, x + 10, y, VeloUi.muted());
			y += 11;
		}
		return y + 8;
	}

	private int switchRow(DrawContext context, int x, int y, int width, String title, String subtitle, boolean on,
			int mouseX, int mouseY, Runnable toggle) {
		Theme theme = ThemeManager.active();
		boolean hovered = VeloUi.inside(mouseX, mouseY, x, y, width, 26);
		if (hovered) {
			VeloDraw.fillRounded(context, x - 4, y - 2, width + 8, 28, 5, VeloUi.withAlpha(0xFFFFFFFF, 0x0E));
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(title, width - 40), x, y + 2, theme.text());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(subtitle, width - 40), x, y + 13, VeloUi.muted());
		int trackX = x + width - 30;
		int trackY = y + 6;
		VeloDraw.fillRounded(context, trackX, trackY, 28, 14, 7, on ? theme.accentStart() : 0xFF3A3A40);
		int knobX = on ? trackX + 16 : trackX + 2;
		VeloDraw.fillRounded(context, knobX, trackY + 2, 10, 10, 5, 0xFFFFFFFF);
		hits.add(new VeloUi.Hit(x, y, width, 26, toggle));
		return y + 30;
	}

	private void drawPlayerRow(DrawContext context, int x, int y, int width, String uuid, String name, String subtitle) {
		VeloDraw.fillRounded(context, x, y, width, ROW_HEIGHT, 5, VeloUi.withAlpha(0xFFFFFFFF, 0x0C));
		PlayerHeads.draw(context, uuid, name, x + 6, y + 5, 20);
		int textWidth = Math.max(20, width - 40 - actionsWidthFor(width) - 4);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(name, textWidth), x + 32, y + 5, ThemeManager.active().text());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(subtitle, textWidth), x + 32, y + 17, VeloUi.muted());
	}

	private static int actionsWidthFor(int rowWidth) {
		return Math.min(130, rowWidth / 2);
	}

	/** A pill that needs a second click within 3s ("Unfriend" -> "Sure?") before doing anything destructive. */
	private VeloUi.Hit confirmPill(DrawContext context, int x, int y, int width, String label, String key, int mouseX, int mouseY, Runnable action) {
		boolean armed = key.equals(confirmAction) && System.currentTimeMillis() < confirmUntil;
		return VeloUi.pill(context, x, y, width, 16, armed ? "Sure?" : label, armed ? 3 : 0, mouseX, mouseY, () -> {
			if (armed) {
				confirmAction = null;
				action.run();
			} else {
				confirmAction = key;
				confirmUntil = System.currentTimeMillis() + 3000;
			}
		});
	}

	private void drawScrollbar(DrawContext context, int x, int top, int height, int contentHeight, double offset) {
		if (contentHeight <= height || height <= 0) {
			return;
		}
		int thumbHeight = Math.max(14, height * height / contentHeight);
		int maxOffset = contentHeight - height;
		int thumbY = top + (int) ((height - thumbHeight) * Math.max(0, Math.min(1, offset / maxOffset)));
		context.fill(x - 2, thumbY, x, thumbY + thumbHeight, VeloUi.withAlpha(ThemeManager.active().accentStart(), 0xAA));
	}

	private int panelColor() {
		return VeloUi.withAlpha(0xFF000000, 0x40);
	}

	private int fieldColor(boolean focused) {
		Theme theme = ThemeManager.active();
		return focused ? VeloAnim.lerpArgb(VeloUi.withAlpha(0xFF000000, 0x70), theme.accentStart(), 0.18f) : VeloUi.withAlpha(0xFF000000, 0x60);
	}

	// ---- Actions ----

	private void submitAdd() {
		String target = addField.getText().strip();
		if (target.isEmpty()) {
			flash("Type a username or UUID first", true);
			return;
		}
		SocialClient.sendRequest(target).thenAccept(error -> MinecraftClient.getInstance().execute(() -> {
			if (error == null) {
				addField.setText("");
				addFieldValue = "";
				flash("Friend request sent to " + target, false);
			} else {
				flash(error, true);
			}
		}));
	}

	private void submitMessage() {
		SocialClient.Friend friend = SocialClient.friend(selected);
		String text = chatField.getText().strip();
		if (friend == null || text.isEmpty()) {
			return;
		}
		chatField.setText("");
		chatFieldValue = "";
		chatScroll = 0;
		SocialClient.sendMessage(friend.uuid, text).thenAccept(error -> MinecraftClient.getInstance().execute(() -> {
			if (error != null) {
				chatField.setText(text);
				flash(error, true);
			}
		}));
	}

	private void report(java.util.concurrent.CompletableFuture<String> future, String success) {
		future.thenAccept(error -> MinecraftClient.getInstance().execute(() -> flash(error == null ? success : error, error != null)));
	}

	private void flash(String message, boolean error) {
		feedback = message;
		feedbackError = error;
		feedbackUntil = System.currentTimeMillis() + 5000;
	}

	// ---- Input ----

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
		if (mouseX < rightX()) {
			friendsScroll -= verticalAmount * 16;
		} else if (view == View.CHAT) {
			chatScroll += verticalAmount * 16;
		} else {
			rightScroll -= verticalAmount * 16;
		}
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		int key = input.key();
		if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
			if (chatField != null && chatField.isFocused()) {
				submitMessage();
				return true;
			}
			if (addField != null && addField.isFocused()) {
				submitAdd();
				return true;
			}
		}
		return super.keyPressed(input);
	}

	@Override
	public void removed() {
		if (openInstance == this) {
			openInstance = null;
		}
		super.removed();
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// Keep the open chat marked read as new messages arrive while you're looking at it.
		SocialClient.Friend friend = SocialClient.friend(selected);
		if (view == View.CHAT && friend != null && friend.unread > 0) {
			SocialClient.markRead(friend.uuid);
		}
		super.render(context, mouseX, mouseY, delta);
	}
}
