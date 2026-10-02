package net.veloclient.velo.client.social;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.veloclient.velo.client.gui.PlayerHeads;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.util.ClientCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * The small popups in the bottom-right corner for friend messages, friend requests, friends coming
 * online, shared waypoints and background-queue events: sender's face, name and a preview.
 *
 * <p>They draw on the HUD while playing and on top of any open screen (chat, inventory, pause
 * menu...) - while a screen is open the mouse is free, so a popup can be clicked to open the right
 * menu, and a friend request can be accepted/denied right on it. While playing (mouse captured),
 * the Friends key (default O) opens whatever the newest popup is about.
 */
public final class NotificationOverlay {

	private static final int WIDTH = 206;
	private static final int BASE_HEIGHT = 38;
	private static final int ACTION_ROW = 18;
	private static final int MARGIN = 8;
	private static final int GAP = 5;
	private static final int MAX_VISIBLE = 4;
	private static final long SLIDE_MILLIS = 220;
	private static final long FADE_MILLIS = 350;

	/** One popup. {@code onAccept}/{@code onDeny} non-null turns it into a request with buttons. */
	public static final class Toast {
		final String uuid;
		final String name;
		final String title;
		final String body;
		final Runnable onOpen;
		final Runnable onAccept;
		final Runnable onDeny;
		final long lifeMillis;
		final long createdAt = System.currentTimeMillis();
		long expiresAt;
		long dismissedAt = -1;
		String key;
		String acceptLabel = "Accept";
		String denyLabel = "Deny";

		/** Renames the two buttons (e.g. "Join" / "Chat" instead of "Accept" / "Deny"). */
		public Toast labels(String accept, String deny) {
			this.acceptLabel = accept;
			this.denyLabel = deny;
			return this;
		}

		public Toast(String uuid, String name, String title, String body, long lifeMillis,
				Runnable onOpen, Runnable onAccept, Runnable onDeny) {
			this.uuid = uuid;
			this.name = name;
			this.title = title;
			this.body = body;
			this.lifeMillis = lifeMillis;
			this.onOpen = onOpen;
			this.onAccept = onAccept;
			this.onDeny = onDeny;
			this.expiresAt = createdAt + lifeMillis;
		}

		boolean hasActions() {
			return onAccept != null && onDeny != null;
		}

		int height() {
			return BASE_HEIGHT + (hasActions() ? ACTION_ROW : 0);
		}

		long endsAt() {
			return dismissedAt > 0 ? dismissedAt : expiresAt;
		}
	}

	private static final List<Toast> TOASTS = new ArrayList<>();
	private static final List<VeloUi.Hit> HITS = new ArrayList<>();
	private static String hintKey = "O";

	private NotificationOverlay() {
	}

	public static void setHintKey(String keyName) {
		hintKey = keyName == null || keyName.isBlank() ? null : keyName;
	}

	/** Removes still-visible popups with the same {@code key} (e.g. "msg:<uuid>") so a new one replaces them instead of stacking. */
	public static void replaceKeyed(String key, Toast toast) {
		toast.key = key;
		TOASTS.removeIf(t -> key.equals(t.key) && t.dismissedAt < 0);
		show(toast);
	}

	/** Must be called on the render thread. */
	public static void show(Toast toast) {
		TOASTS.add(toast);
		while (TOASTS.size() > 12) {
			TOASTS.remove(0);
		}
	}

	/** Removes every popup at once. */
	public static void clear() {
		TOASTS.clear();
	}

	public static void dismiss(Toast toast) {
		if (toast.dismissedAt < 0) {
			toast.dismissedAt = System.currentTimeMillis() + FADE_MILLIS;
		}
	}

	/** The newest still-visible popup, for the Friends key while playing. */
	public static Toast newest() {
		long now = System.currentTimeMillis();
		for (int i = TOASTS.size() - 1; i >= 0; i--) {
			Toast toast = TOASTS.get(i);
			if (toast.endsAt() > now && toast.onOpen != null) {
				return toast;
			}
		}
		return null;
	}

	public static void open(Toast toast) {
		dismiss(toast);
		toast.onOpen.run();
	}

	/** HUD pass - skipped while a screen is open, since {@link #renderOverScreen} draws them on top of it instead. */
	public static void renderHud(DrawContext context, int screenWidth, int screenHeight) {
		if (ClientCompat.currentScreen() != null) {
			return;
		}
		draw(context, screenWidth, screenHeight, Integer.MIN_VALUE, Integer.MIN_VALUE, true);
	}

	public static void renderOverScreen(Screen screen, DrawContext context, int mouseX, int mouseY) {
		draw(context, screen.width, screen.height, mouseX, mouseY, false);
	}

	/** @return true if the click hit a popup (and was handled), so the screen underneath shouldn't get it. */
	public static boolean click(double mouseX, double mouseY) {
		for (VeloUi.Hit hit : List.copyOf(HITS)) {
			if (hit.contains(mouseX, mouseY)) {
				hit.action().run();
				return true;
			}
		}
		return false;
	}

	private static void draw(DrawContext context, int screenWidth, int screenHeight, int mouseX, int mouseY, boolean playing) {
		HITS.clear();
		long now = System.currentTimeMillis();
		TOASTS.removeIf(t -> t.endsAt() + FADE_MILLIS < now);
		if (TOASTS.isEmpty()) {
			return;
		}
		Theme theme = ThemeManager.active();
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int bottom = screenHeight - MARGIN;
		int shown = 0;
		for (int i = TOASTS.size() - 1; i >= 0 && shown < MAX_VISIBLE; i--, shown++) {
			Toast toast = TOASTS.get(i);
			int height = toast.height();
			float slideIn = Math.min(1f, (now - toast.createdAt) / (float) SLIDE_MILLIS);
			slideIn = 1f - (1f - slideIn) * (1f - slideIn) * (1f - slideIn);
			long untilEnd = toast.endsAt() - now;
			float fade = untilEnd > 0 ? 1f : Math.max(0f, 1f + untilEnd / (float) FADE_MILLIS);
			int x = screenWidth - MARGIN - WIDTH + Math.round((1f - slideIn) * (WIDTH + MARGIN + 4));
			int y = bottom - height;
			bottom = y - GAP;
			int alpha = Math.round(255 * fade);
			if (alpha < 8) {
				continue;
			}
			boolean hovered = VeloUi.inside(mouseX, mouseY, x, y, WIDTH, height);
			if (hovered && toast.dismissedAt < 0) {
				// Don't let a popup the player is reading time out from under the cursor.
				toast.expiresAt = Math.max(toast.expiresAt, now + 1500);
			}

			int surface = theme.surface() | 0xFF000000;
			int bg = VeloAnim.lerpArgb(surface, 0xFF000000, 0.25f);
			if (hovered) {
				bg = VeloAnim.lerpArgb(bg, 0xFFFFFFFF, 0.05f);
			}
			VeloDraw.fillRounded(context, x + 2, y + 3, WIDTH, height, 7, VeloUi.withAlpha(0xFF000000, alpha / 3));
			VeloDraw.fillRounded(context, x, y, WIDTH, height, 7, VeloUi.withAlpha(bg, Math.min(alpha, 0xF2)));
			VeloDraw.strokeRounded(context, x, y, WIDTH, height, 7, VeloUi.withAlpha(theme.accentStart(), Math.min(alpha, 0x70)));
			VeloDraw.fillRounded(context, x + 1, y + 6, 3, BASE_HEIGHT - 12, 1, VeloUi.withAlpha(theme.accentStart(), alpha));

			int headSize = 22;
			int headX = x + 10;
			int headY = y + (BASE_HEIGHT - headSize) / 2;
			if (toast.uuid != null) {
				PlayerHeads.draw(context, toast.uuid, toast.name, headX, headY, headSize);
			} else {
				VeloDraw.fillRounded(context, headX, headY, headSize, headSize, 4, VeloUi.withAlpha(theme.accentStart(), alpha));
				context.drawTextWithShadow(textRenderer, "!", headX + (headSize - textRenderer.getWidth("!")) / 2,
						headY + (headSize - 8) / 2, 0xFFFFFFFF);
			}

			int textX = headX + headSize + 8;
			int textWidth = WIDTH - (textX - x) - 8;
			context.drawTextWithShadow(textRenderer, VeloUi.trim(toast.title, textWidth), textX, y + 8,
					VeloUi.withAlpha(theme.accentStart() | 0xFF000000, alpha));
			context.drawTextWithShadow(textRenderer, VeloUi.trim(toast.body, textWidth), textX, y + 21,
					VeloUi.withAlpha(theme.text(), Math.min(alpha, 0xDD)));

			if (toast.hasActions()) {
				int buttonY = y + BASE_HEIGHT - 4;
				int buttonWidth = (WIDTH - 20 - 6) / 2;
				if (playing) {
					String hint = hintKey == null ? "Open chat or a menu to respond" : "Press " + hintKey + " to respond";
					context.drawTextWithShadow(textRenderer, VeloUi.trim(hint, WIDTH - 20), x + 10, buttonY + 4, VeloUi.muted());
				} else {
					HITS.add(VeloUi.pill(context, x + 10, buttonY, buttonWidth, 14, toast.acceptLabel, 2, mouseX, mouseY, () -> {
						dismiss(toast);
						toast.onAccept.run();
					}));
					HITS.add(VeloUi.pill(context, x + 10 + buttonWidth + 6, buttonY, buttonWidth, 14, toast.denyLabel, toast.denyLabel.equals("Deny") ? 3 : 0, mouseX, mouseY, () -> {
						dismiss(toast);
						toast.onDeny.run();
					}));
				}
			} else if (playing && toast.onOpen != null && hintKey != null && i == TOASTS.size() - 1) {
				String hint = "[" + hintKey + "]";
				context.drawTextWithShadow(textRenderer, hint, x + WIDTH - 8 - textRenderer.getWidth(hint), y + 8, VeloUi.muted());
			}

			// Close button, then the body itself (opens the related menu).
			String close = "x";
			int closeX = x + WIDTH - 12;
			if (!playing) {
				boolean closeHovered = VeloUi.inside(mouseX, mouseY, closeX - 3, y + 3, 12, 12);
				context.drawTextWithShadow(textRenderer, close, closeX, y + 5, closeHovered ? theme.accentStart() : VeloUi.muted());
				HITS.add(new VeloUi.Hit(closeX - 3, y + 3, 12, 12, () -> dismiss(toast)));
				if (toast.onOpen != null) {
					HITS.add(new VeloUi.Hit(x, y, WIDTH, BASE_HEIGHT, () -> open(toast)));
				}
			}
		}
	}
}
