package net.veloclient.velo.client.gui.window;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The one-window Velo menu (dev build): every {@link MenuPage} draws in the same frame (same size, same spot - dragging
 * moves all of them) with this icon rail on the left. Picking a rail entry swaps the page inside: the rail stays where it
 * is, its highlight glides to the new entry and the new page slides in from the direction you moved - no second window
 * popping up.
 */
public final class MenuShell {

	public static final int RAIL = 30;
	public static final int GAP = 12;
	/** The frame every page shares (the Locker's size, the biggest page). */
	public static final int WIDTH = 940;
	public static final int HEIGHT = 580;

	/** Where the frame was dragged to, shared by all pages; MIN_VALUE = centred. */
	static int savedX = Integer.MIN_VALUE;
	static int savedY = Integer.MIN_VALUE;

	private static String fromId;
	private static long switchedAt;
	/** The highlight's current y, carried across pages so it glides from the old entry to the new one. */
	private static float pillY = Float.NaN;

	private record Entry(String id, String icon, String label, boolean bottom, Function<Screen, Screen> open) {
	}

	/** Per-window drawing state: where the icons are, hover progress of their name tags. */
	public static final class State {
		final List<int[]> boxes = new ArrayList<>();
		final Map<Integer, Float> hover = new HashMap<>();
		long nanos;
		List<Entry> entries = List.of();
	}

	private MenuShell() {
	}

	private static List<Entry> entries() {
		List<Entry> out = new ArrayList<>();
		out.add(new Entry("modules", "grid", "Modules", false, p -> net.veloclient.velo.client.gui.ModMenuScreen.forView(false)));
		if (net.veloclient.velo.client.gui.ModMenuScreen.addonsAvailable()) {
			out.add(new Entry("addons", "extensions", "Addons", false, p -> net.veloclient.velo.client.gui.ModMenuScreen.forView(true)));
		}
		out.add(new Entry("mods", "extensions", "Mods", false, net.veloclient.velo.client.mods.ModConfigs::modList));
		out.add(new Entry("skins", "skins", "Locker", false, p -> net.veloclient.velo.client.gui.CapeEquipScreen.open(p, 1)));
		out.add(new Entry("friends", "friends", "Friends", false, p -> new net.veloclient.velo.client.gui.FriendsScreen(p)));
		out.add(new Entry("clips", "clips", "Clips & Screenshots", false, p -> new net.veloclient.velo.client.gui.media.MediaScreen(p)));
		out.add(new Entry("fight_recap", "fight_recap", "Fight Recap (K)", false, p -> new net.veloclient.velo.client.gui.media.FightRecapScreen(p)));
		out.add(new Entry("map", "map", "World Map (M)", false, p -> new net.veloclient.velo.client.worldmap.WorldMapScreen(p)));
		out.add(new Entry("waypoints", "waypoints", "Waypoints", false, p -> new net.veloclient.velo.client.gui.WaypointsScreen(p)));
		out.add(new Entry("queue", "queue", "Background Queue", false, p -> new net.veloclient.velo.client.gui.BackgroundQueueSessionsScreen(p, null)));
		out.add(new Entry("schematics", "schematics", "Schematics", false, p -> new net.veloclient.velo.client.gui.SchematicsScreen(p)));
		out.add(new Entry("settings", "settings", "Settings", true, p -> new net.veloclient.velo.client.gui.SettingsTabScreen(p)));
		out.add(new Entry("redstone", "redstone", "Redstone Mode (R)", true, p -> {
			MinecraftClient.getInstance().setScreen(null);
			net.veloclient.velo.client.redstone.RedstoneMode.enter();
			return null;
		}));
		out.add(new Entry("profiles", "profiles", "Profiles", true, p -> new net.veloclient.velo.client.gui.ProfileScreen(p)));
		out.add(new Entry("globe", "globe", "Cloud Sync (Velo+)", true, p -> new net.veloclient.velo.client.gui.CloudSyncScreen(p)));
		out.add(new Entry("party", "party", "World Hosting", true, p -> new net.veloclient.velo.client.gui.WorldHostScreen(p)));
		return out;
	}

	private static int indexOf(List<Entry> entries, String id) {
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).id().equals(id)) {
				return i;
			}
		}
		return -1;
	}

	/** Draws the rail at {@code x} between {@code top} and {@code bottom}, highlighting {@code activeId}. */
	static void draw(DrawContext context, State state, int x, int top, int bottom, String activeId, int mouseX, int mouseY, float alpha) {
		state.entries = entries();
		state.boxes.clear();
		List<Entry> rail = state.entries;
		int a = Math.round(255 * alpha);
		VeloDraw.fillRounded(context, x, top, RAIL, bottom - top, 9, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x08 * alpha)));
		long lower = rail.stream().filter(Entry::bottom).count();
		int size = Math.max(18, Math.min(24, (bottom - top - 16 - (rail.size() + 1) * 3) / rail.size()));
		int ty = top + 5;
		int by = bottom - 5 - (int) lower * (size + 3) + 3;
		int[] activeBox = null;
		for (int i = 0; i < rail.size(); i++) {
			Entry item = rail.get(i);
			int iy;
			if (item.bottom()) {
				iy = by;
				by += size + 3;
			} else {
				iy = ty;
				ty += size + 3;
			}
			int ix = x + (RAIL - size) / 2;
			state.boxes.add(new int[] {ix, iy, size, i});
			if (item.id().equals(activeId)) {
				activeBox = new int[] {ix, iy, size};
			}
		}
		// The highlight glides to the active entry (it remembers where it was on the previous page).
		if (activeBox != null) {
			if (Float.isNaN(pillY)) {
				pillY = activeBox[1];
			}
			long now = System.nanoTime();
			float dt = VeloStyle.frameDelta(state.nanos == 0 ? now - 16_000_000L : state.nanos, now);
			pillY += (activeBox[1] - pillY) * Math.min(1f, dt * 14f);
			if (Math.abs(activeBox[1] - pillY) < 0.3f) {
				pillY = activeBox[1];
			}
			int py = Math.round(pillY);
			int s = activeBox[2];
			VeloDraw.shadow(context, activeBox[0], py, s, s, 7, 8, 2, VeloUi.withAlpha(MenuKit.accent(), Math.round(0x50 * alpha)));
			VeloDraw.fillRounded(context, activeBox[0], py, s, s, 7, VeloUi.withAlpha(MenuKit.accent(), Math.round(0x30 * alpha)));
			VeloDraw.strokeRounded(context, activeBox[0], py, s, s, 7, VeloUi.withAlpha(MenuKit.accent(), Math.round(0x90 * alpha)));
		}
		for (int[] box : state.boxes) {
			Entry item = rail.get(box[3]);
			int ix = box[0];
			int iy = box[1];
			boolean active = item.id().equals(activeId);
			boolean hover = MenuKit.inside(mouseX, mouseY, ix, iy, size, size);
			if (!active && hover) {
				VeloDraw.fillRounded(context, ix, iy, size, size, 7, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x14 * alpha)));
			}
			boolean redstone = item.icon().equals("redstone");
			int tint = active ? 0xFFFFFFFF : redstone ? 0xFFFF6B73 : hover ? 0xFFE4E4E8 : 0xFF9A9AA6;
			int icon = Math.round(size * 0.55f);
			MenuKit.icon(context, item.icon(), ix + (size - icon) / 2, iy + (size - icon) / 2, icon, VeloUi.withAlpha(tint, a));
			if (item.id().equals("friends") && friendsBadge() > 0) {
				VeloDraw.fillCircle(context, ix + size - 3f, iy + 3f, 3.5f, VeloUi.withAlpha(MenuKit.accent(), a));
			}
		}
		// a hairline between the page entries and the ones at the bottom
		if (!state.boxes.isEmpty()) {
			int firstBottom = state.boxes.stream().filter(b -> rail.get(b[3]).bottom()).mapToInt(b -> b[1]).min().orElse(bottom);
			context.fill(x + 7, firstBottom - 5, x + RAIL - 7, firstBottom - 4, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x18 * alpha)));
		}
	}

	/** Entry names slide out of the hovered icon; draw over everything. */
	static void drawLabels(DrawContext context, TextRenderer renderer, State state, int mouseX, int mouseY) {
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(state.nanos == 0 ? now - 16_000_000L : state.nanos, now);
		state.nanos = now;
		for (int[] box : state.boxes) {
			boolean over = MenuKit.inside(mouseX, mouseY, box[0], box[1], box[2], box[2]);
			float t = VeloAnim.step(state.hover.getOrDefault(box[3], 0f), over ? 1f : 0f, dt * 1.4f);
			state.hover.put(box[3], t);
			if (t < 0.02f || box[3] >= state.entries.size()) {
				continue;
			}
			float eased = 1f - (1f - t) * (1f - t) * (1f - t);
			Text label = MenuKit.uiBold(state.entries.get(box[3]).label());
			int full = renderer.getWidth(label) + 16;
			int w = Math.max(8, Math.round(full * eased));
			int tx = box[0] + box[2] + 6 + Math.round((1f - eased) * -6);
			int ty = box[1] + (box[2] - 18) / 2;
			int alpha = Math.round(255 * eased);
			VeloDraw.shadow(context, tx, ty, w, 18, 6, 10, 3, VeloUi.withAlpha(0xFF000000, Math.round(0x80 * eased)));
			VeloDraw.fillRounded(context, tx, ty, w, 18, 6, VeloUi.withAlpha(0xFF1C1C23, alpha));
			VeloDraw.strokeRounded(context, tx, ty, w, 18, 6, VeloUi.withAlpha(MenuKit.accent(), Math.round(0x70 * eased)));
			context.enableScissor(tx, ty, tx + w, ty + 18);
			MenuKit.text(context, renderer, label, tx + 8, ty + 5, VeloUi.withAlpha(MenuKit.TEXT, alpha));
			context.disableScissor();
		}
	}

	/** A click on the rail: opens that page in place. True when the click hit an entry. */
	static boolean click(Screen from, State state, String activeId, double mouseX, double mouseY) {
		for (int[] box : state.boxes) {
			if (mouseX >= box[0] && mouseX < box[0] + box[2] && mouseY >= box[1] && mouseY < box[1] + box[2] && box[3] < state.entries.size()) {
				Entry entry = state.entries.get(box[3]);
				if (entry.id().equals(activeId)) {
					return true;
				}
				net.veloclient.velo.client.sound.VeloUiSounds.click(1.0f);
				fromId = activeId;
				switchedAt = System.nanoTime();
				Screen next = entry.open().apply(from);
				if (next != null) {
					MinecraftClient.getInstance().setScreen(next);
				}
				return true;
			}
		}
		return false;
	}

	/** Dev-only (screenshot tour): switch to a rail entry as if it was clicked. */
	public static void navigateForTour(Screen from, String fromRailId, String id) {
		for (Entry entry : entries()) {
			if (entry.id().equals(id)) {
				fromId = fromRailId;
				switchedAt = System.nanoTime();
				Screen next = entry.open().apply(from);
				if (next != null) {
					MinecraftClient.getInstance().setScreen(next);
				}
				return;
			}
		}
	}

	/**
	 * Vertical offset for a page that just came in: it slides in from the side of the rail you moved towards (down the
	 * rail = from below), settling in ~0.26 s. 0 once settled or when the page wasn't switched to from the rail.
	 */
	static float slideIn(String activeId) {
		if (fromId == null) {
			return 0f;
		}
		float t = (System.nanoTime() - switchedAt) / 260_000_000f;
		if (t >= 1f) {
			fromId = null;
			return 0f;
		}
		List<Entry> entries = entries();
		int from = indexOf(entries, fromId);
		int to = indexOf(entries, activeId);
		if (from < 0 || to < 0 || from == to) {
			return 0f;
		}
		float eased = 1f - (float) Math.pow(1f - t, 3);
		return (to > from ? 1f : -1f) * 18f * (1f - eased);
	}

	private static int friendsBadge() {
		int unread = net.veloclient.velo.client.network.SocialClient.totalUnread();
		var state = net.veloclient.velo.client.network.SocialClient.snapshot();
		return unread + (state == null ? 0 : state.incoming.size());
	}
}
