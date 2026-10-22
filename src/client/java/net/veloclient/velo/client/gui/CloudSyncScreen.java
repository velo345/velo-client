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
public final class CloudSyncScreen extends VeloWindow {

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
	private static final int ROW_H = 34;

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

		// Header card: on/off, last result, usage.
		VeloDraw.fillRounded(context, x, y, w, 58, 10, 0xFF131318);
		VeloDraw.strokeRounded(context, x, y, w, 58, 10, plus ? 0x17FFFFFF : VeloUi.withAlpha(0xFFFF2D3C, 0x70));
		MenuKit.icon(context, "profiles", x + 14, y + 14, 14, MenuKit.accent());
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold("Sync this PC"), x + 36, y + 12, MenuKit.TEXT);
		String sub = !plus ? "Cloud Sync is part of Velo+ - settings, HUD, profiles, waypoints and keybinds on every PC."
				: state.lastResult() == null ? "Your setup is backed up after sign-in, every 10 minutes and when the game closes."
				: state.lastResult() + (state.lastSyncAt() > 0 ? " · " + VeloUi.ago(state.lastSyncAt()) : "");
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(sub), w - 150), x + 36, y + 25, MenuKit.TEXT_2);
		if (plus) {
			MenuKit.toggle(context, x + w - 30, y + 14, state.enabled() ? 1f : 0f);
			hits.add(new Hit(x + w - 60, y + 8, 56, 22, () -> CloudSync.setEnabled(!state.enabled())));
			if (listing != null) {
				int barX = x + 36;
				int barW = w - 150;
				float used = Math.min(1f, listing.usedBytes() / (float) Math.max(1, listing.maxBytes()));
				VeloDraw.fillRounded(context, barX, y + 41, barW, 4, 2, 0xFF2A2A31);
				VeloDraw.fillRounded(context, barX, y + 41, Math.max(4, (int) (barW * used)), 4, 2, MenuKit.accent());
				String usage = listing.backups().size() + "/" + listing.maxCount() + " · " + mb(listing.usedBytes()) + " of " + mb(listing.maxBytes());
				MenuKit.text(context, this.textRenderer, MenuKit.small(usage), barX + barW + 8, y + 39, MenuKit.TEXT_3);
			}
		} else {
			pill(context, x + w - 96, y + 18, 84, 20, "Get Velo+", true, mouseX, mouseY, () -> {
				net.veloclient.velo.client.gui.store.StoreScreen.showPlus();
				this.client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(this));
			});
		}
		y += 68;

		// Actions.
		if (plus) {
			pill(context, x, y, 110, 22, CloudSync.busy() || working ? "Working..." : "Back up now", true, mouseX, mouseY, () -> {
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
			pill(context, x + 118, y, 80, 22, "Refresh", false, mouseX, mouseY, this::reload);
		}
		if (!status.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), w - 210), x + 208, y + 7, MenuKit.TEXT_2);
		}
		y += 32;
		MenuKit.text(context, this.textRenderer, MenuKit.small("RESTORE POINTS"), x + 2, y, MenuKit.TEXT_3);
		y += 14;

		// The list.
		listTop = y;
		listBottom = contentBottom();
		List<CloudSync.Backup> backups = listing == null ? List.of() : listing.backups();
		int maxScroll = Math.max(0, backups.size() * (ROW_H + 6) - (listBottom - listTop));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		if (backups.isEmpty() && listing != null) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(plus ? "No restore points yet - your first one is made after sign-in."
					: "Subscribe to Velo+ and your first backup is made right away."), x + 2, y + 6, MenuKit.TEXT_3);
		}
		context.enableScissor(x, listTop, x + w, listBottom);
		int ry = listTop - (int) scroll;
		for (int i = 0; i < backups.size(); i++) {
			CloudSync.Backup b = backups.get(i);
			if (ry + ROW_H >= listTop && ry <= listBottom) {
				row(context, b, i == 0, x, ry, w, mouseX, mouseY, plus);
			}
			ry += ROW_H + 6;
		}
		context.disableScissor();
	}

	private void row(DrawContext context, CloudSync.Backup b, boolean newest, int x, int y, int w, int mouseX, int mouseY, boolean plus) {
		boolean hovered = MenuKit.inside(mouseX, mouseY, x, y, w, ROW_H) && mouseY >= listTop && mouseY < listBottom;
		VeloDraw.fillRounded(context, x, y, w, ROW_H, 8, hovered ? 0xFF1A1A21 : 0xFF131318);
		VeloDraw.strokeRounded(context, x, y, w, ROW_H, 8, b.pinned() ? VeloUi.withAlpha(MenuKit.GOLD, 0x80) : 0x17FFFFFF);
		VeloDraw.fillCircle(context, x + 12f, y + ROW_H / 2f, 3f, newest ? MenuKit.GREEN : b.pinned() ? MenuKit.GOLD : 0x40FFFFFF);
		String title = (b.label() != null ? b.label() : WHEN.format(Instant.ofEpochMilli(b.createdAt())));
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(title), w - 300), x + 24, y + 7, MenuKit.TEXT);
		String meta = (b.label() != null ? WHEN.format(Instant.ofEpochMilli(b.createdAt())) + " · " : "") + (b.device() == null ? "Unknown PC" : b.device())
				+ " · " + b.files() + " files · " + kb(b.size()) + (b.auto() ? " · automatic" : "") + (newest ? " · newest" : "");
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.small(meta), w - 300), x + 24, y + 20, MenuKit.TEXT_3);
		if (!plus) {
			return;
		}
		int bx = x + w - 10;
		boolean confirmingDelete = b.id().equals(confirmDelete);
		bx -= confirmingDelete ? 74 : 56;
		pill(context, bx, y + 7, confirmingDelete ? 74 : 56, 20, confirmingDelete ? "Delete it" : "Delete", false, mouseX, mouseY, () -> {
			if (!b.id().equals(confirmDelete)) {
				confirmDelete = b.id();
				confirmRestore = null;
				return;
			}
			confirmDelete = null;
			act(() -> CloudSync.delete(b.id()), "Deleted");
		});
		bx -= 52;
		pill(context, bx, y + 7, 46, 20, b.pinned() ? "Unpin" : "Pin", false, mouseX, mouseY,
				() -> act(() -> CloudSync.pin(b.id(), !b.pinned(), null), b.pinned() ? "Unpinned" : "Pinned - it's kept until you delete it"));
		boolean confirming = b.id().equals(confirmRestore);
		bx -= confirming ? 104 : 68;
		pill(context, bx, y + 7, confirming ? 98 : 62, 20, confirming ? "Yes, restore" : "Restore", confirming, mouseX, mouseY, () -> {
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
		VeloDraw.fillRounded(context, x, y, w, h, 7, primary ? MenuKit.accent() : hovered ? 0x1AFFFFFF : 0x0AFFFFFF);
		if (!primary) {
			VeloDraw.strokeRounded(context, x, y, w, h, 7, hovered ? 0x38FFFFFF : 0x17FFFFFF);
		}
		Text text = MenuKit.uiBold(label);
		MenuKit.text(context, this.textRenderer, text, x + (w - this.textRenderer.getWidth(text)) / 2, y + (h - 8) / 2,
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
