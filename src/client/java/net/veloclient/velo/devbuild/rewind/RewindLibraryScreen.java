package net.veloclient.velo.devbuild.rewind;

/**
 * Velo Rewind's library (title screen > Velo Rewind): every recording in {@code velo-rewind/} as a card - its picture, name,
 * date and length - newest first, in Velo's own look (MenuKit panels, Inter type). Click a card to watch it; right-click
 * for rename / show in folder / delete.
 */
//? if <26.2 {
public final class RewindLibraryScreen {

	private RewindLibraryScreen() {
	}
}
//?} else {
/*import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class RewindLibraryScreen extends Screen {

	private record Entry(Path file, String name, long modified, int ticks, long size, String server) {
	}

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean in(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private static final Pattern TICKS = Pattern.compile("\"ticks\":\\s*(\\d+)");
	private static final Pattern SERVER = Pattern.compile("\"server\":\\s*\"([^\"]*)\"");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
	private static final int CARD_W = 172;
	private static final int THUMB_H = 97;
	private static final int CARD_H = THUMB_H + 36;
	private static final int GAP = 18;
	private static final int TOP = 72;

	private final Screen parent;
	private final List<Entry> entries = new ArrayList<>();
	private final java.util.Map<Path, Identifier> thumbs = new ConcurrentHashMap<>();
	private final java.util.Set<Path> loading = ConcurrentHashMap.newKeySet();
	private final List<Hit> hits = new ArrayList<>();
	private double scroll;
	private double scrollShown;
	private Entry menu;
	private int menuX;
	private int menuY;
	private long totalBytes;

	public RewindLibraryScreen(Screen parent) {
		super(Component.literal("Velo Rewind"));
		this.parent = parent;
	}

	public static Path folder() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("velo-rewind");
	}

	@Override
	protected void init() {
		reload();
	}

	private void reload() {
		entries.clear();
		totalBytes = 0;
		Path dir = folder();
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> files = Files.list(dir)) {
			for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".vrewind")).toList()) {
				int ticks = 0;
				String server = "";
				try (RewindReader r = new RewindReader(f)) {
					String meta = r.meta();
					Matcher m = TICKS.matcher(meta);
					if (m.find()) {
						ticks = Integer.parseInt(m.group(1));
					}
					Matcher s = SERVER.matcher(meta);
					if (s.find()) {
						server = s.group(1);
					}
				} catch (Exception ignored) {
				}
				long size = Files.size(f);
				totalBytes += size;
				entries.add(new Entry(f, f.getFileName().toString().replaceFirst("\\.vrewind$", ""), Files.getLastModifiedTime(f).toMillis(),
						ticks, size, server));
			}
		} catch (Exception ignored) {
		}
		entries.sort((a, b) -> Long.compare(b.modified(), a.modified()));
	}

	private int columns() {
		return Math.max(1, Math.min(5, (width - 40 + GAP) / (CARD_W + GAP)));
	}

	private int left() {
		int cols = columns();
		return (width - (cols * CARD_W + (cols - 1) * GAP)) / 2;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		g.fill(0, 0, width, height, 0xB80A0A0D);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		hits.clear();
		scrollShown += (scroll - scrollShown) * 0.35;
		var font = this.font;
		int x0 = left();
		int cols = columns();
		int contentW = cols * CARD_W + (cols - 1) * GAP;

		// ---- header ----
		MenuKit.logo(g, x0, 18, 22);
		MenuKit.text(g, font, MenuKit.displayLg("Velo Rewind"), x0 + 30, 19, MenuKit.TEXT);
		String sub = entries.size() + (entries.size() == 1 ? " recording" : " recordings") + "  ·  " + mb(totalBytes);
		MenuKit.text(g, font, MenuKit.mono(sub), x0 + 30, 40, MenuKit.TEXT_2);
		int bx = pill(g, font, x0 + contentW, 20, "Back", "close", mouseX, mouseY, this::onClose);
		pill(g, font, bx - 6, 20, "Open folder", "mods_folder", mouseX, mouseY,
				() -> net.veloclient.velo.client.gui.FileManagerOpener.open(folder().toFile(), status -> { }));
		MenuKit.hairline(g, x0, TOP - 10, contentW);

		if (entries.isEmpty()) {
			int cy = height / 2 - 20;
			VeloDraw.fillCircle(g, width / 2f, cy, 18f, 0x14FFFFFF);
			MenuKit.icon(g, "record_start", width / 2 - 8, cy - 8, 16, 0xFFFF5A5F);
			Component t = MenuKit.uiBold("No recordings yet");
			MenuKit.text(g, font, t, (width - MenuKit.width(font, t)) / 2, cy + 28, MenuKit.TEXT);
			Component h = MenuKit.ui("Press F6 in a world - or Start Recording in the pause menu.");
			MenuKit.text(g, font, h, (width - MenuKit.width(font, h)) / 2, cy + 42, MenuKit.TEXT_2);
			drawMenu(g, font, mouseX, mouseY);
			return;
		}

		// ---- cards ----
		g.enableScissor(0, TOP - 6, width, height);
		boolean menuOpen = menu != null;
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			int x = x0 + (i % cols) * (CARD_W + GAP);
			int y = TOP + (i / cols) * (CARD_H + GAP) - (int) Math.round(scrollShown);
			if (y + CARD_H < TOP - 6 || y > height) {
				continue;
			}
			boolean over = !menuOpen && mouseY >= TOP - 6 && MenuKit.inside(mouseX, mouseY, x, y, CARD_W, CARD_H);
			float hover = MenuKit.hover(e.file(), over);
			int cy = y - Math.round(hover * 2);
			VeloDraw.shadow(g, x, cy, CARD_W, CARD_H, 10, 12 + Math.round(hover * 6), 4, 0x60000000);
			VeloDraw.fillRounded(g, x, cy, CARD_W, CARD_H, 10, 0xF0141419);
			// picture (inset), fading into the card at the bottom
			Identifier tex = thumb(e.file());
			int px = x + 4;
			int py = cy + 4;
			int pw = CARD_W - 8;
			int ph = THUMB_H - 4;
			if (tex != null) {
				g.blit(RenderPipelines.GUI_TEXTURED, tex, px, py, 0, 0, pw, ph, pw, ph);
				g.fillGradient(px, py + ph - 22, px + pw, py + ph, 0x00000000, 0x90000000);
			} else {
				VeloDraw.fillRounded(g, px, py, pw, ph, 7, 0xFF1E1E26);
				MenuKit.icon(g, "replay_editor", px + pw / 2 - 8, py + ph / 2 - 8, 16, 0xFF55555F);
			}
			// length badge
			int s = e.ticks() / 20;
			Component len = MenuKit.mono(String.format("%d:%02d", s / 60, s % 60));
			int lw = MenuKit.width(font, len) + 10;
			VeloDraw.fillRounded(g, px + pw - lw - 5, py + ph - 15, lw, 11, 4, 0xC0000000);
			MenuKit.text(g, font, len, px + pw - lw, py + ph - 13, MenuKit.TEXT);
			// play button on hover
			if (hover > 0.01f) {
				VeloDraw.fillCircle(g, px + pw / 2f, py + ph / 2f, 13f + hover * 2f, VeloAnim.lerpArgb(0x00000000, (MenuKit.accent() & 0xFFFFFF) | 0xE6000000, hover));
				MenuKit.icon(g, "play", px + pw / 2 - 4, py + ph / 2 - 5, 10, VeloAnim.lerpArgb(0x00FFFFFF, 0xFFFFFFFF, hover));
			}
			// name, date and server
			MenuKit.text(g, font, MenuKit.fit(font, MenuKit.uiBold(e.name()), CARD_W - 16), x + 8, cy + THUMB_H + 6, MenuKit.TEXT);
			String date = DATE.format(Instant.ofEpochMilli(e.modified()).atZone(ZoneId.systemDefault()));
			String where = e.server().isEmpty() ? "" : "  ·  " + e.server();
			MenuKit.text(g, font, MenuKit.fit(font, MenuKit.mono(date + where), CARD_W - 16), x + 8, cy + THUMB_H + 19, MenuKit.TEXT_3);
			VeloDraw.strokeRounded(g, x, cy, CARD_W, CARD_H, 10, VeloAnim.lerpArgb(MenuKit.HAIRLINE, MenuKit.accent(), hover));
			hits.add(new Hit(x, Math.max(cy, TOP - 6), CARD_W, CARD_H, () -> RewindPlayer.open(e.file(), this)));
		}
		g.disableScissor();
		drawMenu(g, font, mouseX, mouseY);
	}

	// A rounded pill button ending at {@code right}; returns its left edge.
	private int pill(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, int right, int y, String text, String icon,
			int mouseX, int mouseY, Runnable action) {
		Component label = MenuKit.uiBold(text);
		int w = MenuKit.width(font, label) + 30;
		int x = right - w;
		float hover = MenuKit.hover("rewind-pill-" + text, menu == null && MenuKit.inside(mouseX, mouseY, x, y, w, 20));
		VeloDraw.fillRounded(g, x, y, w, 20, 10, VeloAnim.lerpArgb(0x14FFFFFF, 0x2AFFFFFF, hover));
		VeloDraw.strokeRounded(g, x, y, w, 20, 10, VeloAnim.lerpArgb(MenuKit.HAIRLINE, 0x60FFFFFF, hover));
		MenuKit.icon(g, icon, x + 9, y + 6, 8, MenuKit.TEXT_2);
		MenuKit.text(g, font, label, x + 21, y + (20 - MenuKit.H_UI) / 2, MenuKit.TEXT);
		hits.add(new Hit(x, y, w, 20, action));
		return x;
	}

	// The right-click menu of a card; while it's open it takes every click.
	private void drawMenu(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, int mouseX, int mouseY) {
		if (menu == null) {
			return;
		}
		Entry e = menu;
		String[][] items = {{"Watch", "play"}, {"Rename", "settings"}, {"Show in folder", "mods_folder"}, {"Delete", "trash"}};
		Runnable[] actions = {
				() -> RewindPlayer.open(e.file(), this),
				() -> minecraft.setScreen(RewindSaveScreen.rename(e.file(), this)),
				() -> net.veloclient.velo.client.gui.FileManagerOpener.open(folder().toFile(), status -> { }),
				() -> {
					try {
						Files.deleteIfExists(e.file());
					} catch (Exception ignored) {
					}
					reload();
				}};
		int w = 132;
		int h = 8 + items.length * 18;
		int x = Math.min(menuX, width - w - 6);
		int y = Math.min(menuY, height - h - 6);
		MenuKit.panel(g, x, y, w, h, 8);
		List<Hit> menuHits = new ArrayList<>();
		for (int i = 0; i < items.length; i++) {
			int ry = y + 4 + i * 18;
			float hover = MenuKit.hover("rewind-menu-" + i, MenuKit.inside(mouseX, mouseY, x + 4, ry, w - 8, 18));
			boolean danger = i == items.length - 1;
			if (hover > 0.01f) {
				VeloDraw.fillRounded(g, x + 4, ry, w - 8, 18, 5, (Math.round((danger ? 0x40 : 0x16) * hover) << 24) | (danger ? 0xFF5A5F : 0xFFFFFF));
			}
			MenuKit.icon(g, items[i][1], x + 11, ry + 5, 8, danger ? MenuKit.RED_TEXT : MenuKit.TEXT_2);
			MenuKit.text(g, font, MenuKit.uiBold(items[i][0]), x + 25, ry + (18 - MenuKit.H_UI) / 2, danger ? MenuKit.RED_TEXT : MenuKit.TEXT);
			Runnable action = actions[i];
			menuHits.add(new Hit(x + 4, ry, w - 8, 18, () -> {
				menu = null;
				action.run();
			}));
		}
		hits.clear();
		hits.addAll(menuHits);
	}

	// The card's picture, loaded off the game thread the first time it's needed.
	private Identifier thumb(Path file) {
		Identifier id = thumbs.get(file);
		if (id != null || !loading.add(file)) {
			return id;
		}
		Thread t = new Thread(() -> {
			try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
				var entry = zip.getEntry("thumb.png");
				if (entry == null) {
					return;
				}
				byte[] png = zip.getInputStream(entry).readAllBytes();
				Minecraft.getInstance().execute(() -> {
					try {
						NativeImage image = NativeImage.read(png);
						Identifier key = Identifier.fromNamespaceAndPath("velo-client", "rewind/thumb_" + Integer.toHexString(file.toString().hashCode()));
						Minecraft.getInstance().getTextureManager().register(key, new DynamicTexture(() -> "rewind thumb", image));
						thumbs.put(file, key);
					} catch (Exception ignored) {
					}
				});
			} catch (Exception ignored) {
			}
		}, "Velo Rewind thumbs");
		t.setDaemon(true);
		t.start();
		return null;
	}

	private static String mb(long bytes) {
		return bytes >= 1L << 30 ? String.format("%.1f GB", bytes / 1073741824.0) : String.format("%.1f MB", bytes / 1048576.0);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (menu != null) {
			for (Hit h : List.copyOf(hits)) {
				if (h.in(mx, my)) {
					h.action().run();
					return true;
				}
			}
			menu = null;
			return true;
		}
		if (event.button() == 1) {
			Entry e = cardAt(mx, my);
			if (e != null) {
				menu = e;
				menuX = (int) mx;
				menuY = (int) my;
				return true;
			}
		}
		for (Hit h : List.copyOf(hits)) {
			if (h.in(mx, my)) {
				h.action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private Entry cardAt(double mx, double my) {
		int cols = columns();
		int x0 = left();
		if (my < TOP - 6) {
			return null;
		}
		for (int i = 0; i < entries.size(); i++) {
			int x = x0 + (i % cols) * (CARD_W + GAP);
			int y = TOP + (i / cols) * (CARD_H + GAP) - (int) Math.round(scrollShown);
			if (mx >= x && mx < x + CARD_W && my >= y && my < y + CARD_H) {
				return entries.get(i);
			}
		}
		return null;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (menu != null && event.key() == net.veloclient.velo.client.util.Keys.ESCAPE) {
			menu = null;
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double dx, double dy) {
		int rows = (entries.size() + columns() - 1) / columns();
		double max = Math.max(0, rows * (CARD_H + GAP) - (height - TOP - 10));
		scroll = Math.max(0, Math.min(max, scroll - dy * 36));
		menu = null;
		return true;
	}

	@Override
	public void removed() {
		for (Identifier id : thumbs.values()) {
			Minecraft.getInstance().getTextureManager().release(id);
		}
		thumbs.clear();
		loading.clear();
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
*///?}
