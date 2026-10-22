package net.veloclient.velo.client.gui.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
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
 * Clips Pro clips play right here with their sound ({@link ClipPlayerPanel}: drag the timeline's end handles to trim,
 * then save the trimmed part as a new clip or share just that part), older clips with pause, scrubbing and frame
 * stepping; the fight recap sits alongside when the clip came from a fight. Share (Velo+) gives a link to copy or send
 * to a Velo friend. Screenshots page with the arrow keys. Open folder and delete on both.
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
	private ClipPlayerPanel proPlayer;
	private boolean savingTrim;
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
				return clip ? n.endsWith(".avi") || n.endsWith(".mp4") && !n.endsWith(".tmp.mp4") : n.endsWith(".png") || n.endsWith(".jpg");
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
					String base = p.getFileName().toString().replaceFirst("\\.(avi|mp4)$", "");
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
		lastUrl = null;
		// Clips Pro clips are real videos with sound (FFmpeg plays them here); older clips are Velo's own MJPEG files.
		if (item.clip() && pro(item)) {
			proPlayer = new ClipPlayerPanel(item.file().toString(), true);
		} else if (item.clip()) {
			clipPlayer = new ClipPlayer(item.file());
		}
	}

	private static boolean pro(Item item) {
		return item.file().getFileName().toString().endsWith(".mp4");
	}

	private boolean sharing;
	/** The link just made (a trimmed part isn't kept in the clip's sidecar). */
	private String lastUrl;
	/** The share choices (copy the link, or send it to a friend) are open. */
	private boolean sharePanel;

	/** Copy link, or one click per friend (online first) to send it in Velo friends chat. */
	private void renderSharePanel(DrawContext context, int mouseX, int mouseY, int right, int top) {
		String url = lastUrl != null ? lastUrl : net.veloclient.velo.client.clips.ClipShare.sharedUrl(viewing.meta());
		if (url == null) {
			sharePanel = false;
			return;
		}
		var state = net.veloclient.velo.client.network.SocialClient.snapshot();
		List<net.veloclient.velo.client.network.SocialClient.Friend> friends = state == null ? List.of()
				: state.friends.stream().sorted(java.util.Comparator.comparing((net.veloclient.velo.client.network.SocialClient.Friend f) -> !f.online)
						.thenComparing(f -> f.username.toLowerCase(Locale.ROOT))).limit(8).toList();
		int w = 230;
		int h = 66 + Math.max(1, friends.size()) * 22;
		int x = right - w;
		VeloDraw.shadow(context, x, top, w, h, 8, 14, 4, 0x66000000);
		VeloDraw.fillRounded(context, x, top, w, h, 8, 0xF7131318);
		VeloDraw.strokeRounded(context, x, top, w, h, 8, 0x30FFFFFF);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(url, w - 16), x + 8, top + 8, VeloUi.muted());
		hits.add(iconPill(context, x + 8, top + 22, w - 16, "copy", "Copy link", 1, mouseX, mouseY, () -> {
			MinecraftClient.getInstance().keyboard.setClipboard(url);
			status = "Link copied: " + url;
			sharePanel = false;
		}));
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(retentionNote(viewing.meta()), w - 16), x + 8, top + 48, 0xFF8A8A95);
		int fy = top + 62;
		if (friends.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "Add friends to send it to them.", x + 8, fy + 4, VeloUi.muted());
		}
		for (var friend : friends) {
			String label = (friend.online ? "● " : "") + "Send to " + friend.username;
			hits.add(iconPill(context, x + 8, fy, w - 16, "send", VeloUi.trim(label, w - 50), 0, mouseX, mouseY, () -> {
				sharePanel = false;
				status = "Sending to " + friend.username + "...";
				String text = "Check out my clip: " + url;
				net.veloclient.velo.client.network.SocialClient.sendMessage(friend.uuid, text).thenAccept(error ->
						this.client.execute(() -> status = error == null ? "Sent to " + friend.username : "Couldn't send: " + error));
			}));
			fy += 22;
		}
	}

	private void share() {
		if (sharing || viewing == null) {
			return;
		}
		String known = lastUrl != null ? lastUrl : net.veloclient.velo.client.clips.ClipShare.sharedUrl(viewing.meta());
		if (known != null && (proPlayer == null || !proPlayer.trimmed() || lastUrl != null)) {
			sharePanel = !sharePanel;
			return;
		}
		sharing = true;
		Item item = viewing;
		double from = proPlayer != null && proPlayer.trimmed() ? proPlayer.trimFrom() : -1;
		double to = proPlayer != null && proPlayer.trimmed() ? proPlayer.trimTo() : -1;
		net.veloclient.velo.client.clips.ClipShare.share(item.file(), item.meta(), from, to, line -> status = line, url -> {
			sharing = false;
			if (url != null) {
				lastUrl = url;
				status = "Link copied: " + url;
				sharePanel = true;
				reload();
				for (Item fresh : clips) {
					if (fresh.file().equals(item.file()) && viewing != null && viewing.file().equals(item.file())) {
						viewing = fresh;
					}
				}
			}
		});
	}

	private void closePlayer() {
		if (clipPlayer != null) {
			clipPlayer.release();
			clipPlayer = null;
		}
		if (proPlayer != null) {
			proPlayer.release();
			proPlayer = null;
		}
	}

	/** Saves the part between the trim handles as a new clip next to the original (thumbnail and details too). */
	private void saveTrimmed() {
		if (savingTrim || proPlayer == null || !proPlayer.trimmed()) {
			return;
		}
		savingTrim = true;
		Item item = viewing;
		double from = proPlayer.trimFrom();
		double to = proPlayer.trimTo();
		status = "Saving the trimmed part...";
		Thread.ofVirtual().name("velo-clip-trim").start(() -> {
			String result;
			try {
				String base = item.file().getFileName().toString().replaceFirst("\\.mp4$", "");
				String name = base + "-trim-" + System.currentTimeMillis() / 1000;
				Path out = item.file().resolveSibling(name + ".mp4");
				net.veloclient.velo.client.clips.core.ClipTools.trim(net.veloclient.velo.client.clips.FfmpegTool.binary(), item.file(), out, from, to);
				net.veloclient.velo.client.clips.core.ClipTools.thumbnail(net.veloclient.velo.client.clips.FfmpegTool.binary(), out, (to - from) * 2 / 3,
						out.resolveSibling(name + ".jpg"));
				JsonObject meta = item.meta() == null ? new JsonObject() : item.meta().deepCopy();
				meta.remove("shareUrl");
				meta.remove("shareExpires");
				meta.addProperty("seconds", to - from);
				meta.addProperty("trimmedFrom", item.file().getFileName().toString());
				Files.writeString(out.resolveSibling(name + ".json"), meta.toString(), StandardCharsets.UTF_8);
				result = "Saved the trimmed clip (" + Math.round(to - from) + "s)";
			} catch (Exception e) {
				result = "Couldn't trim: " + e.getMessage();
			}
			String message = result;
			MinecraftClient.getInstance().execute(() -> {
				savingTrim = false;
				status = message;
				reload();
			});
		});
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
				? (!net.veloclient.velo.client.clips.ProClipRecorder.status().isEmpty() ? net.veloclient.velo.client.clips.ProClipRecorder.status()
						: ClipRecorder.active() ? String.format(Locale.ROOT, "%s - %.0fs buffered. Save with the Clips key.",
								net.veloclient.velo.client.clips.ProClipRecorder.active() ? "Clips Pro recording" : "Recording", ClipRecorder.bufferedSeconds())
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
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(label(viewing), width - (proPlayer != null && proPlayer.trimmed() ? 540 : 420)), x + 80, y + 6, theme.text());
		hits.add(iconPill(context, x + width - 214, y, 100, "mods_folder", "Folder", 0, mouseX, mouseY,
				() -> openFolder(viewing.file().getParent())));
		if (viewing.clip()) {
			boolean member = net.veloclient.velo.client.namestyle.NameStyles.plus();
			boolean trimmed = proPlayer != null && proPlayer.trimmed();
			String shareLabel = !member ? "Share · Velo+" : sharing ? "Sharing..." : trimmed ? "Share part" : "Share";
			hits.add(iconPill(context, x + width - 324, y, 104, "share", shareLabel, 1, mouseX, mouseY, () -> {
				if (member) {
					share();
				} else {
					status = "Share links are part of Velo+ (shared clips stay online for "
							+ net.veloclient.velo.client.clips.core.ClipTools.DEFAULT_RETENTION_DAYS + " days)";
				}
			}));
			if (trimmed) {
				hits.add(iconPill(context, x + width - 444, y, 114, "scissors", savingTrim ? "Saving..." : "Save trimmed", 1, mouseX, mouseY, this::saveTrimmed));
			}
		}
		hits.add(iconPill(context, x + width - 108, y, 108, "trash", confirmDelete ? "Really delete?" : "Delete", 3, mouseX, mouseY, this::delete));

		boolean recap = viewing.clip() && viewing.meta() != null && viewing.meta().has("recap");
		int sideW = recap ? 170 : 0;
		int areaX = x;
		int areaY = y + 28;
		int areaW = width - (recap ? sideW + 10 : 0);
		int areaH = contentBottom() - areaY - (viewing.clip() ? 30 : 26);
		VeloDraw.fillRounded(context, areaX, areaY, areaW, areaH, 6, 0xFF0B0B0F);
		if (viewing.clip() && pro(viewing) && proPlayer != null) {
			proPlayer.render(context, this.textRenderer, areaX, areaY, areaW, contentBottom() - areaY - 14, mouseX, mouseY);
			if (status == null) {
				String hint = "Space play/pause · ←/→ 5s · M mute · drag the timeline's ends to trim";
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(hint, areaW), x, contentBottom() + 4, 0xFF6E6E78);
			}
			if (recap) {
				renderRecap(context, viewing.meta().getAsJsonObject("recap"), x + width - sideW, areaY, sideW);
			}
		} else if (viewing.clip()) {
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
		if (sharePanel && viewing != null) {
			// Drawn last and its buttons checked first (hits are tried in order, so move them to the front).
			int before = hits.size();
			renderSharePanel(context, mouseX, mouseY, x + width - 216, y + 24);
			List<VeloUi.Hit> panel = new ArrayList<>(hits.subList(before, hits.size()));
			hits.subList(before, hits.size()).clear();
			hits.addAll(0, panel);
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

	/** "Online until 21 Dec - shared clips are deleted after 60 days" (Velo+ shares are kept two months). */
	private static String retentionNote(JsonObject meta) {
		long expires = meta != null && meta.has("shareExpires") ? meta.get("shareExpires").getAsLong() : 0;
		int days = net.veloclient.velo.client.clips.core.ClipTools.DEFAULT_RETENTION_DAYS;
		if (expires > 0) {
			return "Online until " + DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault())
					.format(Instant.ofEpochMilli(expires)) + " · then deleted";
		}
		return "Shared clips stay online for " + days + " days";
	}

	/** A pill button with an icon in front of its label. */
	private VeloUi.Hit iconPill(DrawContext context, int x, int y, int w, String icon, String label, int tone, int mouseX, int mouseY, Runnable action) {
		VeloUi.Hit hit = VeloUi.pill(context, x, y, w, 20, "", tone, mouseX, mouseY, action);
		int labelW = this.textRenderer.getWidth(label);
		int total = 12 + 5 + labelW;
		int ix = x + (w - total) / 2;
		net.veloclient.velo.client.gui.title.MenuKit.icon(context, icon, ix, y + 4, 12, 0xFFFFFFFF);
		context.drawTextWithShadow(this.textRenderer, label, ix + 17, y + 6, 0xFFFFFFFF);
		return hit;
	}

	private void backToGallery() {
		sharePanel = false;
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
				String base = item.file().getFileName().toString().replaceFirst("\\.(avi|mp4)$", "");
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
		if (proPlayer != null && !sharePanel && proPlayer.mouseClicked(click.x(), click.y())) {
			return true;
		}
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
		if (proPlayer != null && proPlayer.mouseDragged(click.x(), click.y())) {
			return true;
		}
		if (scrubbing && clipPlayer != null) {
			clipPlayer.seek((click.x() - scrubX) / Math.max(1, scrubW));
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (proPlayer != null) {
			proPlayer.mouseReleased();
		}
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
			if (proPlayer != null && proPlayer.keyPressed(key)) {
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
