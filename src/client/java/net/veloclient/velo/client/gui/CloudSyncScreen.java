package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cloud.CloudSync;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Velo+ Cloud Sync: whether it's on, what happened last, how much of your cloud is used, "Back up now", and every
 * restore point (newest first) to restore, pin (kept no matter what) or delete. Restoring backs up what you have first.
 */
public final class CloudSyncScreen extends VeloWindow implements net.veloclient.velo.client.gui.window.MenuPage {

	@Override
	public String railId() {
		return "globe";
	}

	@Override
	public int shellMaxContentWidth() {
		return 700;
	}


	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
	private static final int ROW_H = 34;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH).withZone(ZoneId.systemDefault());
	private static final int DAY_H = 20;

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private CloudSync.Listing listing;
	private String status = "Loading your restore points...";
	private boolean working;
	/** The restore point whose Restore / Delete wants a second click to confirm. */
	private String confirmRestore;
	private String confirmDelete;
	private double scroll;
	private int listTop;
	private int listBottom;

	public CloudSyncScreen(Screen parent) {
		super(Text.literal("Cloud Sync"), 640, 470);
		returnTo(parent);
		reload();
	}

	private void reload() {
		Thread.ofVirtual().name("velo-cloud-list").start(() -> {
			try {
				CloudSync.Listing fresh = CloudSync.list();
				MinecraftClient.getInstance().execute(() -> {
					listing = fresh;
					if (!working) {
						status = "";
					}
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> status = "Couldn't load: " + e.getMessage());
			}
		});
	}

	@Override
	protected void layoutContent() {
		// Drawn and hit-tested by hand.
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		boolean plus = listing != null ? listing.plus() : net.veloclient.velo.client.network.StoreClient.plus();
		CloudSync.State state = CloudSync.state();

		// Header card (glass): round icon, on/off and the last result, usage, the switch.
		int cardH = plus && listing != null ? 64 : 52;
		VeloDraw.fillRounded(context, x, y, w, cardH, 12, 0x14FFFFFF);
		VeloDraw.strokeRounded(context, x, y, w, cardH, 12, 0x1CFFFFFF);
		VeloDraw.fillRounded(context, x + 12, y + 12, 28, 28, 14, state.enabled() && plus ? 0xFFFFFFFF : 0x1FFFFFFF);
		navIcon(context, "d_cloud", x + 19, y + 19, 14, state.enabled() && plus ? 0xFF141416 : 0xFFFFFFFF);
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold(!plus ? "Cloud Sync" : state.enabled() ? "Cloud Sync is on" : "Cloud Sync is off"),
				x + 50, y + 13, MenuKit.TEXT);
		String sub = !plus ? "Part of Velo+ - settings, HUD, profiles, waypoints and keybinds on every PC."
				: state.lastResult() == null ? "Backed up after sign-in, every 10 minutes and when the game closes."
				: state.lastResult() + (state.lastSyncAt() > 0 ? "  ·  " + VeloUi.ago(state.lastSyncAt()) : "");
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(sub), w - 160), x + 50, y + 26, MenuKit.TEXT_2);
		if (plus) {
			MenuKit.toggle(context, x + w - 30, y + 16, state.enabled() ? 1f : 0f);
			hits.add(new Hit(x + w - 60, y + 8, 56, 26, () -> CloudSync.setEnabled(!state.enabled())));
			if (listing != null) {
				int barX = x + 50;
				int barW = w - 50 - 14;
				float used = Math.min(1f, listing.usedBytes() / (float) Math.max(1, listing.maxBytes()));
				VeloDraw.fillRounded(context, barX, y + 44, barW, 4, 2, 0x24FFFFFF);
				VeloDraw.fillRounded(context, barX, y + 44, Math.max(4, (int) (barW * used)), 4, 2, MenuKit.accent());
				String usage = listing.backups().size() + " of " + listing.maxCount() + " restore points  ·  " + mb(listing.usedBytes()) + " of " + mb(listing.maxBytes());
				MenuKit.text(context, this.textRenderer, MenuKit.small(usage), barX, y + 52, MenuKit.TEXT_3);
			}
		} else {
			pill(context, x + w - 96, y + 16, 84, 20, "Get Velo+", true, mouseX, mouseY, () -> {
				net.veloclient.velo.client.gui.store.StoreScreen.showPlus();
				this.client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(this));
			});
		}
		y += cardH + 10;

		// Actions: back up now, refresh (round icon), and what just happened.
		if (plus) {
			pill(context, x, y, 112, 22, CloudSync.busy() || working ? "Working..." : "Back up now", true, mouseX, mouseY, () -> {
				if (working || CloudSync.busy()) {
					return;
				}
				working = true;
				status = "Backing up...";
				CloudSync.backupNow(null, error -> {
					working = false;
					status = error == null ? "Backed up" : "Couldn't back up: " + error;
					reload();
				});
			});
			iconButton(context, "d_reset", x + 118, y, 22, mouseX, mouseY, this::reload);
		}
		if (!status.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), w - 160), x + 150, y + 7, MenuKit.TEXT_2);
		}
		y += 32;

		// The restore points, grouped by day; actions show on hover.
		listTop = y;
		listBottom = contentBottom();
		List<CloudSync.Backup> backups = listing == null ? List.of() : listing.backups();
		int total = 0;
		String lastDay = null;
		for (CloudSync.Backup b : backups) {
			String day = dayLabel(b.createdAt());
			if (!day.equals(lastDay)) {
				total += DAY_H;
				lastDay = day;
			}
			total += ROW_H + 4;
		}
		int maxScroll = Math.max(0, total - (listBottom - listTop));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		if (backups.isEmpty() && listing != null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(plus ? "No restore points yet - your first one is made after sign-in."
					: "Subscribe to Velo+ and your first backup is made right away."), x + 2, y + 6, MenuKit.TEXT_3);
		}
		context.enableScissor(x, listTop, x + w, listBottom);
		int ry = listTop - (int) scroll;
		lastDay = null;
		for (int i = 0; i < backups.size(); i++) {
			CloudSync.Backup b = backups.get(i);
			String day = dayLabel(b.createdAt());
			if (!day.equals(lastDay)) {
				if (ry + DAY_H >= listTop && ry <= listBottom) {
					MenuKit.text(context, this.textRenderer, MenuKit.small(day.toUpperCase(Locale.ROOT)), x + 4, ry + 8, MenuKit.TEXT_3);
				}
				ry += DAY_H;
				lastDay = day;
			}
			if (ry + ROW_H >= listTop && ry <= listBottom) {
				row(context, b, i == 0, x, ry, w, mouseX, mouseY, plus);
			}
			ry += ROW_H + 4;
		}
		context.disableScissor();
		if (maxScroll > 0) {
			int areaH = listBottom - listTop;
			int barH = Math.max(20, areaH * areaH / (areaH + maxScroll));
			int barY = listTop + (int) ((areaH - barH) * (scroll / maxScroll));
			VeloDraw.fillRounded(context, x + w + 4, barY, 3, barH, 1.5f, 0x40FFFFFF);
		}
	}

	private static String dayLabel(long millis) {
		java.time.LocalDate day = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate();
		java.time.LocalDate today = java.time.LocalDate.now();
		return day.equals(today) ? "Today" : day.equals(today.minusDays(1)) ? "Yesterday" : DAY.format(Instant.ofEpochMilli(millis));
	}

	/** A Velo nav icon at its exact pixel size (crisp). */
	private void navIcon(DrawContext context, String key, int x, int y, int size, int color) {
		int gs = net.veloclient.velo.client.util.ClientCompat.guiScale();
		net.minecraft.util.Identifier tex = net.veloclient.velo.client.gui.drawer.CrispIcons.get(net.veloclient.velo.client.gui.widget.VeloNavIcons.of(key), size * gs);
		if (tex == null) {
			return;
		}
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x, y);
		context.getMatrices().scale(1f / gs, 1f / gs);
		context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, size * gs, size * gs, size * gs, size * gs, size * gs, size * gs, color);
		context.getMatrices().popMatrix();
	}

	/** A round glass button with an icon. */
	private void iconButton(DrawContext context, String icon, int x, int y, int size, int mouseX, int mouseY, Runnable action) {
		boolean hovered = MenuKit.inside(mouseX, mouseY, x, y, size, size);
		VeloDraw.fillRounded(context, x, y, size, size, size / 2f, hovered ? 0x2EFFFFFF : 0x16FFFFFF);
		int is = Math.round(size * 0.55f);
		navIcon(context, icon, x + (size - is) / 2, y + (size - is) / 2, is, 0xFFFFFFFF);
		hits.add(new Hit(x, y, size, size, action));
	}

	private void row(DrawContext context, CloudSync.Backup b, boolean newest, int x, int y, int w, int mouseX, int mouseY, boolean plus) {
		boolean hovered = MenuKit.inside(mouseX, mouseY, x, y, w, ROW_H) && mouseY >= listTop && mouseY < listBottom;
		boolean confirmingDelete = b.id().equals(confirmDelete);
		boolean confirming = b.id().equals(confirmRestore);
		VeloDraw.fillRounded(context, x, y, w, ROW_H, 10, hovered || confirming || confirmingDelete ? 0x1FFFFFFF : 0x10FFFFFF);
		if (b.pinned()) {
			VeloDraw.strokeRounded(context, x, y, w, ROW_H, 10, VeloUi.withAlpha(MenuKit.GOLD, 0x70));
		}
		VeloDraw.fillCircle(context, x + 14f, y + ROW_H / 2f, 3f, newest ? MenuKit.GREEN : b.pinned() ? MenuKit.GOLD : 0x40FFFFFF);
		String title = b.label() != null ? b.label() + "  ·  " + TIME.format(Instant.ofEpochMilli(b.createdAt())) : TIME.format(Instant.ofEpochMilli(b.createdAt()));
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(title), w - 200), x + 26, y + 8, MenuKit.TEXT);
		String meta = (b.device() == null ? "Unknown PC" : b.device()) + "  ·  " + b.files() + " files  ·  " + kb(b.size()) + (b.auto() ? "  ·  automatic" : "");
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.small(meta), w - 200), x + 26, y + 21, MenuKit.TEXT_3);
		if (!plus) {
			return;
		}
		int bx = x + w - 8;
		if (!hovered && !confirming && !confirmingDelete) {
			// at rest: just what's special about it
			String tag = newest ? "NEWEST" : b.pinned() ? "PINNED" : null;
			if (tag != null) {
				Text t = MenuKit.small(tag);
				int tw = this.textRenderer.getWidth(t) + 12;
				VeloDraw.fillRounded(context, bx - tw, y + (ROW_H - 14) / 2, tw, 14, 7, newest ? VeloUi.withAlpha(MenuKit.GREEN, 0x33) : VeloUi.withAlpha(MenuKit.GOLD, 0x33));
				MenuKit.text(context, this.textRenderer, t, bx - tw + 6, y + (ROW_H - 14) / 2 + 4, newest ? MenuKit.GREEN : MenuKit.GOLD);
			}
			return;
		}
		int by = y + (ROW_H - 22) / 2;
		if (confirmingDelete) {
			bx -= 74;
			pill(context, bx, by, 74, 22, "Delete it", false, mouseX, mouseY, () -> {
				confirmDelete = null;
				act(() -> CloudSync.delete(b.id()), "Deleted");
			});
		} else {
			bx -= 22;
			iconButton(context, "d_x", bx, by, 22, mouseX, mouseY, () -> {
				confirmDelete = b.id();
				confirmRestore = null;
				status = "Click Delete it to remove this restore point.";
			});
		}
		bx -= 28;
		iconButton(context, b.pinned() ? "d_pin_on" : "d_pin", bx, by, 22, mouseX, mouseY,
				() -> act(() -> CloudSync.pin(b.id(), !b.pinned(), null), b.pinned() ? "Unpinned" : "Pinned - it's kept until you delete it"));
		bx -= confirming ? 98 : 80;
		pill(context, bx, by, confirming ? 92 : 74, 22, confirming ? "Yes, restore" : "Restore", true, mouseX, mouseY, () -> {
			if (!b.id().equals(confirmRestore)) {
				confirmRestore = b.id();
				confirmDelete = null;
				status = "Click again to restore - what you have now is backed up first.";
				return;
			}
			confirmRestore = null;
			working = true;
			status = "Restoring...";
			CloudSync.restore(b, error -> {
				working = false;
				status = error == null ? "Restored - modules, HUD and keybinds are back now; theme and waypoints follow on your next start."
						: "Couldn't restore: " + error;
				reload();
			});
		});
	}

	@FunctionalInterface
	private interface Call {
		void run() throws Exception;
	}

	private void act(Call call, String done) {
		Thread.ofVirtual().start(() -> {
			try {
				call.run();
				MinecraftClient.getInstance().execute(() -> status = done);
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> status = e.getMessage());
			}
			reload();
		});
	}

	private void pill(DrawContext context, int x, int y, int w, int h, String label, boolean primary, int mouseX, int mouseY, Runnable action) {
		boolean hovered = MenuKit.inside(mouseX, mouseY, x, y, w, h);
		int fill = primary ? (hovered ? net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(MenuKit.accent(), 0xFF000000, 0.08f) : MenuKit.accent())
				: hovered ? 0x2EFFFFFF : 0x16FFFFFF;
		VeloDraw.fillRounded(context, x, y, w, h, h / 2f, fill);
		Text text = MenuKit.uiBold(label);
		MenuKit.text(context, this.textRenderer, text, x + (w - this.textRenderer.getWidth(text)) / 2, y + (h - 8) / 2 + 1,
				primary ? MenuKit.onAccent() : MenuKit.TEXT);
		hits.add(new Hit(x, y, w, h, action));
	}

	private static String mb(long bytes) {
		return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
	}

	private static String kb(long bytes) {
		return bytes < 1024 * 1024 ? (bytes + 1023) / 1024 + " KB" : mb(bytes);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (mouseY >= listTop) {
			scroll -= verticalAmount * 24;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}
}
