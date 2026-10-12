package net.veloclient.velo.client.gui.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.clips.ClipRecorder;
import net.veloclient.velo.client.gui.FileManagerOpener;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Your clips and screenshots, without leaving the game: a thumbnail grid per tab and a full viewer -
 * clips play with pause, scrubbing and frame stepping (with the fight recap alongside when the clip
 * came from a fight), screenshots page with the arrow keys. Open folder and delete on both.
 */
public final class MediaScreen extends VeloWindow {

	/** A clip (with its sidecar) or a screenshot. */
	public record Item(Path file, boolean clip, long modified, Path thumbnail, JsonObject meta) {
		String title() {
			String name = file.getFileName().toString();
			return name.substring(0, name.lastIndexOf('.'));
		}
	}

	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
	private static final int TILE_W = 168;
	private static final int TILE_H = 94;
	private static final int GAP = 10;

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private List<Item> clips = List.of();
	private List<Item> screenshots = List.of();
	private boolean clipsTab = true;
	private double scroll;
	private Item viewing;
	private ClipPlayer clipPlayer;
	private boolean confirmDelete;
	private String status;
	private int scrubX;
	private int scrubY;
	private int scrubW;
	private boolean scrubbing;

	public MediaScreen(Screen parent) {
		this(parent, null);
	}

	/** Opens straight into the viewer for {@code open} (e.g. a clip from a fight recap). */
	public MediaScreen(Screen parent, Path open) {
		super(Text.literal("Clips & Screenshots"), 820, 500);
		returnTo(parent);
		reload();
		if (open != null) {
			for (Item item : clips) {
				if (item.file().equals(open)) {
					view(item);
				}
			}
		}
	}

	public static List<Item> scan(Path dir, boolean clip) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(p -> {
				String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
				return clip ? n.endsWith(".avi") : n.endsWith(".png") || n.endsWith(".jpg");
			}).map(p -> {
				long modified = 0;
				try {
					modified = Files.getLastModifiedTime(p).toMillis();
				} catch (Exception ignored) {
					// Unknown.
				}
				Path thumb = p;
				JsonObject meta = null;
				if (clip) {
					String base = p.getFileName().toString().replaceFirst("\\.avi$", "");
					thumb = p.resolveSibling(base + ".jpg");
					try {
						Path json = p.resolveSibling(base + ".json");
						if (Files.exists(json)) {
							meta = JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
						}
					} catch (Exception ignored) {
						// No sidecar.
					}
				}
				return new Item(p, clip, modified, thumb, meta);
			}).sorted(Comparator.comparingLong(Item::modified).reversed()).toList();
		} catch (Exception e) {
			return List.of();
		}
	}

	private void reload() {
		clips = scan(ClipRecorder.clipsDir(), true);
		screenshots = scan(FabricLoader.getInstance().getGameDir().resolve("screenshots"), false);
	}

	private void view(Item item) {
		closePlayer();
		viewing = item;
		confirmDelete = false;
		if (item.clip()) {
			clipPlayer = new ClipPlayer(item.file());
		}
	}

	private void closePlayer() {
		if (clipPlayer != null) {
			clipPlayer.release();
			clipPlayer = null;
		}
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		if (viewing != null) {
			renderViewer(context, mouseX, mouseY);
		} else {
			renderGallery(context, mouseX, mouseY);
		}
	}

	private void renderGallery(DrawContext context, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		hits.add(VeloUi.pill(context, x, y, 110, 20, "Clips (" + clips.size() + ")", clipsTab ? 1 : 0, mouseX, mouseY, () -> {
			clipsTab = true;
			scroll = 0;
		}));
		hits.add(VeloUi.pill(context, x + 116, y, 130, 20, "Screenshots (" + screenshots.size() + ")", clipsTab ? 0 : 1, mouseX, mouseY, () -> {
			clipsTab = false;
			scroll = 0;
		}));
		Path folder = clipsTab ? ClipRecorder.clipsDir() : FabricLoader.getInstance().getGameDir().resolve("screenshots");
		hits.add(VeloUi.pill(context, x + width - 100, y, 100, 20, "Open folder", 0, mouseX, mouseY, () -> openFolder(folder)));
		String hint = clipsTab
				? (ClipRecorder.active() ? String.format(Locale.ROOT, "Recording - %.0fs buffered. Save with the Clips key.", ClipRecorder.bufferedSeconds())
						: "Turn on the Clips module to keep the last 30 seconds ready to save.")
				: "F2 takes a screenshot.";
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(hint, width - 360), x + 256, y + 6, VeloUi.muted());

		List<Item> items = clipsTab ? clips : screenshots;
		int top = y + 30;
		int bottom = contentBottom();
		int columns = Math.max(1, (width + GAP) / (TILE_W + GAP));
		int tileW = (width - GAP * (columns - 1)) / columns;
		int tileH = Math.round(tileW * 9 / 16f);
		int rowH = tileH + 26;
		int rows = (items.size() + columns - 1) / columns;
		double maxScroll = Math.max(0, rows * (rowH + GAP) - (bottom - top));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		if (items.isEmpty()) {
			String empty = clipsTab ? "No clips yet." : "No screenshots yet.";
			context.drawCenteredTextWithShadow(this.textRenderer, empty, x + width / 2, top + 60, VeloUi.muted());
			return;
		}
		context.enableScissor(x, top, x + width, bottom);
		for (int i = 0; i < items.size(); i++) {
			Item item = items.get(i);
			int tx = x + (i % columns) * (tileW + GAP);
			int ty = top + (i / columns) * (rowH + GAP) - (int) scroll;
			if (ty + rowH < top || ty > bottom) {
				continue;
			}
			boolean hover = VeloUi.inside(mouseX, mouseY, tx, ty, tileW, rowH) && mouseY >= top && mouseY < bottom;
			VeloDraw.fillRounded(context, tx, ty, tileW, rowH, 6, hover ? VeloUi.withAlpha(theme.accentStart(), 0x40) : VeloStyle.card());
			MediaTextures.Tex tex = Files.exists(item.thumbnail()) ? MediaTextures.get(item.thumbnail(), 320) : null;
			if (tex != null) {
				MediaTextures.drawCover(context, tex, tx + 3, ty + 3, tileW - 6, tileH - 6);
			} else {
				VeloDraw.fillRounded(context, tx + 3, ty + 3, tileW - 6, tileH - 6, 4, 0xFF1A1A22);
			}
			if (item.clip()) {
				String length = item.meta() != null && item.meta().has("seconds") ? Math.round(item.meta().get("seconds").getAsDouble()) + "s" : "clip";
				int lw = this.textRenderer.getWidth(length) + 8;
				VeloDraw.fillRounded(context, tx + tileW - lw - 6, ty + tileH - 18, lw, 12, 3, 0xC0000000);
				context.drawTextWithShadow(this.textRenderer, length, tx + tileW - lw - 2, ty + tileH - 16, 0xFFFFFFFF);
				VeloDraw.fillCircle(context, tx + tileW / 2, ty + tileH / 2 - 2, 11, 0xA0000000);
				VeloDraw.chevron(context, tx + tileW / 2 + 1, ty + tileH / 2 - 2, 5, 2, 1, 0xFFFFFFFF);
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(label(item), tileW - 10), tx + 6, ty + tileH + 2, theme.text());
			context.drawTextWithShadow(this.textRenderer, WHEN.format(Instant.ofEpochMilli(item.modified())), tx + 6, ty + tileH + 13, VeloUi.muted());
			if (ty >= top - rowH && ty < bottom) {
				int clipTop = Math.max(ty, top);
				int clipBottom = Math.min(ty + rowH, bottom);
				if (clipBottom > clipTop) {
					hits.add(new VeloUi.Hit(tx, clipTop, tileW, clipBottom - clipTop, () -> view(item)));
				}
			}
		}
		context.disableScissor();
	}

	private static String label(Item item) {
		if (item.clip() && item.meta() != null && item.meta().has("recap")) {
			JsonObject recap = item.meta().getAsJsonObject("recap");
			String outcome = recap.has("outcome") ? recap.get("outcome").getAsString() : "";
			String opponent = recap.has("opponent") && !recap.get("opponent").isJsonNull() ? recap.get("opponent").getAsString() : "?";
			return (outcome.equals("WON") ? "Won vs " : outcome.equals("LOST") ? "Lost vs " : "Fight vs ") + opponent;
		}
		if (item.clip() && item.meta() != null && item.meta().has("server")) {
			return item.meta().get("server").getAsString();
		}
		return item.title();
	}

	private void renderViewer(DrawContext context, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		hits.add(VeloUi.pill(context, x, y, 70, 20, "< Back", 0, mouseX, mouseY, this::backToGallery));
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(label(viewing), width - 330), x + 80, y + 6, theme.text());
		hits.add(VeloUi.pill(context, x + width - 210, y, 100, 20, "Open folder", 0, mouseX, mouseY,
				() -> openFolder(viewing.file().getParent())));
		hits.add(VeloUi.pill(context, x + width - 104, y, 104, 20, confirmDelete ? "Really delete?" : "Delete", 3, mouseX, mouseY, this::delete));

		boolean recap = viewing.clip() && viewing.meta() != null && viewing.meta().has("recap");
		int sideW = recap ? 170 : 0;
		int areaX = x;
		int areaY = y + 28;
		int areaW = width - (recap ? sideW + 10 : 0);
		int areaH = contentBottom() - areaY - (viewing.clip() ? 30 : 26);
		VeloDraw.fillRounded(context, areaX, areaY, areaW, areaH, 6, 0xFF0B0B0F);
		if (viewing.clip()) {
			MediaTextures.Tex tex = clipPlayer == null ? null : clipPlayer.frame();
			if (tex != null) {
				MediaTextures.drawFit(context, tex, areaX + 2, areaY + 2, areaW - 4, areaH - 4);
			}
			if (clipPlayer != null && clipPlayer.error() != null) {
				context.drawCenteredTextWithShadow(this.textRenderer, clipPlayer.error(), areaX + areaW / 2, areaY + areaH / 2, 0xFFFF7070);
			} else if (tex == null) {
				context.drawCenteredTextWithShadow(this.textRenderer, "Loading...", areaX + areaW / 2, areaY + areaH / 2, VeloUi.muted());
			}
			hits.add(new VeloUi.Hit(areaX, areaY, areaW, areaH, () -> {
				if (clipPlayer != null) {
					clipPlayer.togglePlay();
				}
			}));
			renderControls(context, mouseX, mouseY, x, contentBottom() - 22, areaW);
			if (recap) {
				renderRecap(context, viewing.meta().getAsJsonObject("recap"), x + width - sideW, areaY, sideW);
			}
		} else {
			MediaTextures.Tex tex = MediaTextures.get(viewing.file(), 1920);
			if (tex != null) {
				MediaTextures.drawFit(context, tex, areaX + 2, areaY + 2, areaW - 4, areaH - 4);
			} else {
				context.drawCenteredTextWithShadow(this.textRenderer, "Loading...", areaX + areaW / 2, areaY + areaH / 2, VeloUi.muted());
			}
			int index = screenshots.indexOf(viewing);
			int by = contentBottom() - 20;
			hits.add(VeloUi.pill(context, x, by, 90, 20, "< Previous", 0, mouseX, mouseY, () -> page(-1)));
			hits.add(VeloUi.pill(context, x + width - 90, by, 90, 20, "Next >", 0, mouseX, mouseY, () -> page(1)));
			String counter = (index + 1) + " / " + screenshots.size() + "   ·   " + WHEN.format(Instant.ofEpochMilli(viewing.modified()));
			context.drawCenteredTextWithShadow(this.textRenderer, counter, x + width / 2, by + 6, VeloUi.muted());
		}
		if (status != null) {
			context.drawTextWithShadow(this.textRenderer, status, x, contentBottom() + 4, VeloUi.muted());
		}
	}

	private void renderControls(DrawContext context, int mouseX, int mouseY, int x, int y, int width) {
		Theme theme = ThemeManager.active();
		boolean playing = clipPlayer != null && clipPlayer.playing();
		hits.add(VeloUi.pill(context, x, y, 56, 20, playing ? "Pause" : "Play", 1, mouseX, mouseY, () -> {
			if (clipPlayer != null) {
				clipPlayer.togglePlay();
			}
		}));
		hits.add(VeloUi.pill(context, x + 60, y, 22, 20, "<", 0, mouseX, mouseY, () -> {
			if (clipPlayer != null) {
				clipPlayer.step(-1);
			}
		}));
		hits.add(VeloUi.pill(context, x + 86, y, 22, 20, ">", 0, mouseX, mouseY, () -> {
			if (clipPlayer != null) {
				clipPlayer.step(1);
			}
		}));
		String time = clipPlayer == null ? "" : String.format(Locale.ROOT, "%.1f / %.1fs", clipPlayer.currentSeconds(), clipPlayer.seconds());
		int timeW = this.textRenderer.getWidth(time);
		scrubX = x + 116;
		scrubY = y + 7;
		scrubW = width - 116 - timeW - 10;
		VeloDraw.fillRounded(context, scrubX, scrubY, scrubW, 6, 3, 0xFF2A2A33);
		double progress = clipPlayer == null ? 0 : clipPlayer.progress();
		VeloDraw.fillRounded(context, scrubX, scrubY, Math.max(6, (int) (scrubW * progress)), 6, 3, theme.accentStart() | 0xFF000000);
		VeloDraw.fillCircle(context, scrubX + (int) (scrubW * progress), scrubY + 3, 5, 0xFFFFFFFF);
		context.drawTextWithShadow(this.textRenderer, time, x + width - timeW, y + 6, VeloUi.muted());
	}

	private void renderRecap(DrawContext context, JsonObject recap, int x, int y, int width) {
		Theme theme = ThemeManager.active();
		VeloDraw.fillRounded(context, x, y, width, contentBottom() - y, 6, VeloStyle.card());
		String outcome = str(recap, "outcome");
		int color = "WON".equals(outcome) ? 0xFF6FE39A : "LOST".equals(outcome) ? 0xFFFF5555 : 0xFFFFC53D;
		String headline = "WON".equals(outcome) ? "Victory" : "LOST".equals(outcome) ? "Defeat" : "Fight";
		context.drawTextWithShadow(this.textRenderer, headline, x + 8, y + 8, color);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim("vs " + str(recap, "opponent"), width - 16), x + 8, y + 20, theme.text());
		int ry = y + 38;
		for (String[] row : FightRecapRows.rows(recap)) {
			context.drawTextWithShadow(this.textRenderer, row[0], x + 8, ry, VeloUi.muted());
			int w = this.textRenderer.getWidth(row[1]);
			context.drawTextWithShadow(this.textRenderer, row[1], x + width - 8 - w, ry, theme.text());
			ry += 13;
		}
	}

	static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	private void page(int direction) {
		int index = screenshots.indexOf(viewing);
		if (screenshots.isEmpty()) {
			return;
		}
		int next = Math.floorMod(index + direction, screenshots.size());
		view(screenshots.get(next));
	}

	private void backToGallery() {
		closePlayer();
		viewing = null;
		confirmDelete = false;
		status = null;
	}

	private void delete() {
		if (!confirmDelete) {
			confirmDelete = true;
			return;
		}
		Item item = viewing;
		backToGallery();
		try {
			MediaTextures.forget(item.file());
			MediaTextures.forget(item.thumbnail());
			Files.deleteIfExists(item.file());
			if (item.clip()) {
				String base = item.file().getFileName().toString().replaceFirst("\\.avi$", "");
				Files.deleteIfExists(item.file().resolveSibling(base + ".jpg"));
				Files.deleteIfExists(item.file().resolveSibling(base + ".json"));
			}
		} catch (Exception e) {
			status = "Couldn't delete: " + e.getMessage();
		}
		reload();
	}

	private void openFolder(Path folder) {
		try {
			Files.createDirectories(folder);
		} catch (Exception ignored) {
			// Opening will report it.
		}
		FileManagerOpener.open(folder.toFile(), message -> status = message);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (viewing != null && viewing.clip() && clipPlayer != null
				&& VeloUi.inside(click.x(), click.y(), scrubX - 4, scrubY - 6, scrubW + 8, 18)) {
			scrubbing = true;
			clipPlayer.seek((click.x() - scrubX) / Math.max(1, scrubW));
			return true;
		}
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (scrubbing && clipPlayer != null) {
			clipPlayer.seek((click.x() - scrubX) / Math.max(1, scrubW));
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		scrubbing = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (viewing == null) {
			scroll -= verticalAmount * 30;
		}
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		int key = input.key();
		if (viewing != null) {
			if (key == net.veloclient.velo.client.util.Keys.ESCAPE) {
				backToGallery();
				return true;
			}
			if (key == net.veloclient.velo.client.util.Keys.SPACE && clipPlayer != null) {
				clipPlayer.togglePlay();
				return true;
			}
			if (key == net.veloclient.velo.client.util.Keys.LEFT || key == net.veloclient.velo.client.util.Keys.RIGHT) {
				int direction = key == net.veloclient.velo.client.util.Keys.LEFT ? -1 : 1;
				if (clipPlayer != null) {
					clipPlayer.step(direction);
				} else {
					page(direction);
				}
				return true;
			}
		}
		return super.keyPressed(input);
	}

	@Override
	public void removed() {
		closePlayer();
		MediaTextures.releaseAll();
		super.removed();
	}
}
