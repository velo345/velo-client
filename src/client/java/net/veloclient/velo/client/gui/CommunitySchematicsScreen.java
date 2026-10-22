package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloCommunitySchematicTile;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.schematics.CommunitySchematicsClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Community schematics, shared by Velo Client players: Recommended (good and new ones first), New,
 * Top and your own, with search. Pick one to see it bigger with its description and tags, upvote it,
 * and download it - it goes straight into your schematics folder (Litematica's for .litematic, Velo's
 * for .veloschem) and can be loaded into Redstone Mode right away. "Share yours" uploads one of your
 * own ({@link ShareSchematicScreen}). Opened from the Schematics screen and Redstone Mode's File menu.
 */
public final class CommunitySchematicsScreen extends VeloWindow {

	private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();
	private static final String[][] SORTS = {{"hot", "Recommended"}, {"new", "New"}, {"top", "Top"}, {"mine", "Mine"}};
	private static final int TILE_WIDTH = 132;
	private static final int TILE_GAP = 10;

	private String sort = "hot";
	private String query = "";
	private TextFieldWidget search;
	private List<CommunitySchematicsClient.Item> items = List.of();
	private int total;
	private int page;
	private boolean loading;
	private String status = "";
	private VeloScrollRegion scrollRegion;
	private int columns = 1;
	/** The schematic shown in detail, or null for the grid. */
	private CommunitySchematicsClient.Item open;
	private volatile Identifier openPreview;
	private Path downloaded;
	private int generation;

	public CommunitySchematicsScreen(net.minecraft.client.gui.screen.Screen parent) {
		super(Text.literal("Community Schematics"), 640, 450);
		returnTo(parent);
		refresh();
	}

	private void refresh() {
		if (CommunitySchematicsClient.base() == null) {
			status = "Velo Network isn't set up - turn on the Velo Network module to browse shared schematics";
			return;
		}
		loading = true;
		int gen = ++generation;
		String s = sort;
		String q = query;
		int p = page;
		IO.submit(() -> {
			try {
				CommunitySchematicsClient.Page result = CommunitySchematicsClient.list(s, q, null, p);
				MinecraftClient.getInstance().execute(() -> {
					if (gen != generation) {
						return;
					}
					items = result.items();
					total = result.total();
					loading = false;
					status = total == 0 ? ("mine".equals(s) ? "You haven't shared anything yet - \"Share yours\" uploads one" : "Nothing found")
							: total + " schematics";
					layoutContent();
				});
			} catch (Exception e) {
				String message = CommunitySchematicsClient.describe(e);
				MinecraftClient.getInstance().execute(() -> {
					loading = false;
					status = message;
					layoutContent();
				});
			}
		});
	}

	@Override
	protected void layoutContent() {
		if (search != null) {
			query = search.getText();
		}
		this.clearChildren();
		scrollRegion = null;
		if (open != null) {
			layoutDetail();
			return;
		}
		int x = contentX();
		int y = contentY();
		int tabW = 86;
		for (int i = 0; i < SORTS.length; i++) {
			String key = SORTS[i][0];
			VeloButton tab = new VeloButton(x + i * (tabW + 4), y, tabW, 18, Text.literal(SORTS[i][1]), b -> {
				sort = key;
				page = 0;
				refresh();
				layoutContent();
			});
			if (key.equals(sort)) {
				tab.primary();
			}
			addDrawableChild(tab);
		}
		VeloButton share = new VeloButton(x + contentWidth() - 110, y, 110, 18, Text.literal("+ Share yours"),
				b -> this.client.setScreen(new ShareSchematicScreen(this)));
		share.primary();
		addDrawableChild(share);
		y += 24;
		search = new TextFieldWidget(this.textRenderer, x, y, contentWidth() - 76, 16, Text.literal("Search"));
		search.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder("Search titles, tags, authors..."));
		search.setText(query);
		addDrawableChild(search);
		addDrawableChild(new VeloButton(x + contentWidth() - 70, y - 1, 70, 18, Text.literal(loading ? "..." : "Search"), b -> {
			query = search.getText();
			page = 0;
			refresh();
		}));
		y += 24;
		int gridHeight = contentBottom() - y - 26;
		columns = Math.max(1, (contentWidth() + TILE_GAP) / (TILE_WIDTH + TILE_GAP));
		int tileWidth = (contentWidth() - (columns - 1) * TILE_GAP - 8) / columns;
		scrollRegion = new VeloScrollRegion(x, y, contentWidth(), gridHeight);
		for (CommunitySchematicsClient.Item item : items) {
			VeloCommunitySchematicTile tile = new VeloCommunitySchematicTile(tileWidth, item, () -> openDetail(item));
			addSelectableChild(tile);
			scrollRegion.addRow(tile);
		}
		scrollRegion.layoutGrid(columns, tileWidth, tileWidth * 3 / 4 + 26, TILE_GAP);
		int pages = Math.max(1, (total + 29) / 30);
		if (pages > 1) {
			addDrawableChild(new VeloButton(x + contentWidth() - 150, contentBottom() - 20, 70, 20, Text.literal("< Back"), b -> {
				page = Math.max(0, page - 1);
				refresh();
			}));
			addDrawableChild(new VeloButton(x + contentWidth() - 74, contentBottom() - 20, 74, 20, Text.literal("More >"), b -> {
				page = Math.min(pages - 1, page + 1);
				refresh();
			}));
		}
	}

	private void openDetail(CommunitySchematicsClient.Item item) {
		open = item;
		openPreview = null;
		downloaded = null;
		RemoteTextureLoader.load(CommunitySchematicsClient.previewUrl(item.id()), id -> openPreview = id);
		layoutContent();
	}

	private void layoutDetail() {
		CommunitySchematicsClient.Item item = open;
		int x = contentX();
		int imageW = 300;
		int bx = x + imageW + 14;
		int bw = contentWidth() - imageW - 14;
		int y = contentBottom() - 20;
		addDrawableChild(new VeloButton(x, y, 90, 20, Text.literal("< Back"), b -> {
			open = null;
			layoutContent();
		}));
		if (downloaded == null) {
			VeloButton get = new VeloButton(bx, contentY() + 190, bw, 20, Text.literal("Download to my schematics"), b -> download(item));
			get.primary();
			addDrawableChild(get);
		} else {
			VeloButton load = new VeloButton(bx, contentY() + 190, bw, 20, Text.literal("Load in Redstone Mode"), b -> {
				net.veloclient.velo.client.redstone.SchematicIO.loadToClipboard(downloaded);
				closeToGame();
			});
			load.primary();
			addDrawableChild(load);
		}
		if (!item.mine()) {
			addDrawableChild(new VeloButton(bx, contentY() + 214, bw, 20,
					Text.literal(item.voted() ? "▲ Upvoted (" + item.votes() + ") - take back" : "▲ Upvote (" + item.votes() + ")"), b -> vote(item)));
		} else {
			addDrawableChild(new VeloButton(bx, contentY() + 214, bw, 20, Text.literal("Delete my upload"),
					b -> this.client.setScreen(new VeloConfirmScreen(this, "Delete schematic",
							"Remove \"" + item.title() + "\" from the community? Your own file stays.", "Delete", () -> delete(item)))));
		}
	}

	private void download(CommunitySchematicsClient.Item item) {
		status = "Downloading...";
		IO.submit(() -> {
			try {
				Path file = CommunitySchematicsClient.download(item);
				MinecraftClient.getInstance().execute(() -> {
					downloaded = file;
					status = "Saved as " + file.getFileName() + ("litematic".equals(item.format()) ? " - Litematica sees it too" : "");
					layoutContent();
				});
			} catch (Exception e) {
				String message = CommunitySchematicsClient.describe(e);
				MinecraftClient.getInstance().execute(() -> status = message);
			}
		});
	}

	private void vote(CommunitySchematicsClient.Item item) {
		IO.submit(() -> {
			try {
				CommunitySchematicsClient.Item updated = CommunitySchematicsClient.vote(item.id(), !item.voted());
				MinecraftClient.getInstance().execute(() -> {
					replace(updated);
					layoutContent();
				});
			} catch (Exception e) {
				String message = CommunitySchematicsClient.describe(e);
				MinecraftClient.getInstance().execute(() -> status = message);
			}
		});
	}

	private void delete(CommunitySchematicsClient.Item item) {
		IO.submit(() -> {
			try {
				CommunitySchematicsClient.delete(item.id());
				MinecraftClient.getInstance().execute(() -> {
					open = null;
					status = "Removed \"" + item.title() + "\"";
					refresh();
				});
			} catch (Exception e) {
				String message = CommunitySchematicsClient.describe(e);
				MinecraftClient.getInstance().execute(() -> status = message);
			}
		});
	}

	private void replace(CommunitySchematicsClient.Item updated) {
		List<CommunitySchematicsClient.Item> next = new ArrayList<>(items);
		next.replaceAll(i -> i.id().equals(updated.id()) ? updated : i);
		items = next;
		if (open != null && open.id().equals(updated.id())) {
			open = updated;
		}
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (search != null && search.isFocused() && (input.key() == net.veloclient.velo.client.util.Keys.ENTER
				|| input.key() == net.veloclient.velo.client.util.Keys.KP_ENTER)) {
			query = search.getText();
			page = 0;
			refresh();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			int tileWidth = (contentWidth() - (columns - 1) * TILE_GAP - 8) / columns;
			scrollRegion.layoutGrid(columns, tileWidth, tileWidth * 3 / 4 + 26, TILE_GAP);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (open != null) {
			renderDetail(context);
		} else if (scrollRegion != null) {
			int tileWidth = (contentWidth() - (columns - 1) * TILE_GAP - 8) / columns;
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbarGrid(context, columns, tileWidth * 3 / 4 + 26, TILE_GAP);
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(loading ? "Loading..." : status, contentWidth() - (open == null ? 170 : 100)),
				contentX() + (open == null ? 0 : 100), contentBottom() - 13, VeloStyle.textMuted());
	}

	private void renderDetail(DrawContext context) {
		CommunitySchematicsClient.Item item = open;
		int x = contentX();
		int y = contentY();
		int imageW = 300;
		int imageH = 225;
		VeloDraw.fillRounded(context, x, y, imageW, imageH, 6, 0xFF1A1A22);
		if (openPreview != null) {
			context.drawTexture(RenderPipelines.GUI_TEXTURED, openPreview, x, y, 0f, 0f, imageW, imageH, imageW, imageH, imageW, imageH);
		}
		int bx = x + imageW + 14;
		int bw = contentWidth() - imageW - 14;
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(item.title(), bw), bx, y, VeloStyle.text());
		String when = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy").format(
				java.time.Instant.ofEpochMilli(item.createdAt()).atZone(java.time.ZoneId.systemDefault()));
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim("by " + item.author() + "  ·  " + when, bw), bx, y + 13, VeloStyle.textMuted());
		String info = ("litematic".equals(item.format()) ? "Litematica" : "Velo schematic") + "  ·  " + Math.max(1, item.fileSize() / 1024) + " KB  ·  "
				+ item.downloads() + " downloads  ·  ▲ " + item.votes();
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(info, bw), bx, y + 26, VeloStyle.textFaint());
		int ty = y + 42;
		int tx = bx;
		for (String tag : item.tags()) {
			int w = this.textRenderer.getWidth(tag) + 10;
			if (tx + w > bx + bw) {
				tx = bx;
				ty += 15;
			}
			VeloDraw.fillRounded(context, tx, ty, w, 13, 4, 0xFF2A2A36);
			context.drawTextWithShadow(this.textRenderer, tag, tx + 5, ty + 3, 0xFFCFCFE0);
			tx += w + 4;
		}
		ty += item.tags().isEmpty() ? 0 : 20;
		List<String> lines = VeloUi.wrap(item.description().isEmpty() ? "No description." : item.description(), bw);
		for (int i = 0; i < lines.size() && ty + i * 10 < y + 182; i++) {
			context.drawTextWithShadow(this.textRenderer, lines.get(i), bx, ty + i * 10, VeloStyle.text());
		}
	}
}
