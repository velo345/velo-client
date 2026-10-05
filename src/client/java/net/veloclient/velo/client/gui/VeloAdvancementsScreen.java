package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.advancements.AdvancementSource;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Velo's Advancements screen, shown instead of vanilla's. Left: every tab with its progress, plus
 * your overall total. Middle: the tab's tree (drag to pan, scroll to zoom) or a plain list, with
 * search and done / to-do filters. Right: the selected advancement - what it asks for, progress,
 * and which criteria are still missing.
 */
public final class VeloAdvancementsScreen extends VeloWindow {

	private int sidebar() {
		return Math.max(110, Math.min(150, contentWidth() / 4));
	}
	private static final float SPACING = 30f;
	private static boolean openVanillaOnce;

	private enum Filter { ALL, DONE, TODO }

	private final AdvancementSource source = new AdvancementSource();
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget search;
	private String tab;
	private String selected;
	private Filter filter = Filter.ALL;
	private boolean listView;
	private float panX = 30;
	private float panY = 40;
	private float zoom = 1f;
	private boolean dragging;
	private double dragDistance;
	private float listScroll;
	private float sideScroll;

	public VeloAdvancementsScreen(Screen parent) {
		super(Text.literal("Advancements"), 780, 470);
		returnTo(parent);
	}

	/** Dev-only (screenshot tour): selects the second advancement of the tab. */
	public void selectForTour() {
		source.nodes.values().stream().filter(n -> n.parentId() != null).findFirst().ifPresent(n -> selected = n.id());
	}

	/** Swaps vanilla's Advancements screen for this one. */
	public static void maybeReplace(MinecraftClient client, Screen screen) {
		//? if <26.1 {
		boolean vanilla = screen instanceof net.minecraft.client.gui.screen.advancement.AdvancementsScreen;
		//?} else {
		/*boolean vanilla = screen instanceof net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
		*///?}
		if (!vanilla) {
			return;
		}
		if (openVanillaOnce) {
			openVanillaOnce = false;
			return;
		}
		if (!VeloScreens.advancements()) {
			return;
		}
		// The vanilla screen's own parent (pause menu, or nothing when opened with the key). Never a
		// remembered "last screen": that could be the Advancements screen itself, so Back reopened it.
		Screen parent = ((net.veloclient.velo.client.mixin.AdvancementsScreenAccessor) screen).velo$parent();
		while (parent instanceof VeloAdvancementsScreen velo) {
			parent = velo.returnScreen();
		}
		Screen target = parent;
		client.execute(() -> client.setScreen(new VeloAdvancementsScreen(target)));
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		String previous = search != null ? search.getText() : "";
		int mainX = contentX() + sidebar() + 12;
		search = new TextFieldWidget(this.textRenderer, mainX, contentY(), Math.min(170, contentWidth() - sidebar() - 140), 16, Text.literal("Search"));
		search.setPlaceholder(Text.literal("Search advancements..."));
		search.setText(previous);
		addDrawableChild(search);
	}

	@Override
	protected void init() {
		super.init();
		source.attach();
	}

	@Override
	public void removed() {
		source.detach();
		super.removed();
	}

	private String query() {
		return search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
	}

	private boolean done(String id) {
		AdvancementSource.Progress p = source.progress.get(id);
		return p != null && p.done();
	}

	private boolean visible(AdvancementSource.Node node) {
		String q = query();
		if (!q.isEmpty() && !(node.title().toLowerCase(Locale.ROOT).contains(q) || node.description().toLowerCase(Locale.ROOT).contains(q))) {
			return false;
		}
		return switch (filter) {
			case ALL -> true;
			case DONE -> done(node.id());
			case TODO -> !done(node.id());
		};
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		if (tab == null || !source.nodes.containsKey(tab)) {
			tab = source.roots.isEmpty() ? null : source.roots.get(0);
			if (tab != null) {
				source.selectTab(tab);
			}
		}
		drawSidebar(context, mouseX, mouseY);
		int mainX = contentX() + sidebar() + 12;
		int mainW = contentWidth() - sidebar() - 12;
		int top = contentY() + 24;
		int bottom = contentBottom();

		// Toolbar: search, then filters.
		int px = mainX + (search != null ? search.getWidth() : 170) + 6;
		for (Filter f : Filter.values()) {
			String label = switch (f) {
				case ALL -> "All";
				case DONE -> "Done";
				case TODO -> "To do";
			};
			int w = this.textRenderer.getWidth(label) + 16;
			hits.add(VeloUi.pill(context, px, contentY(), w, 16, label, filter == f ? 1 : 0, mouseX, mouseY, () -> filter = f));
			px += w + 4;
		}
		int half = (sidebar() - 4) / 2;
		hits.add(VeloUi.pill(context, contentX(), bottom - 16, half, 16, listView ? "Tree view" : "List view", 0, mouseX, mouseY,
				() -> listView = !listView));
		hits.add(VeloUi.pill(context, contentX() + half + 4, bottom - 16, half, 16, "Vanilla", 0, mouseX, mouseY, this::openVanilla));

		VeloDraw.fillRounded(context, mainX, top, mainW, bottom - top, 8, VeloStyle.sunken());
		if (tab == null) {
			context.drawTextWithShadow(this.textRenderer, "No advancements yet.", mainX + 10, top + 10, VeloStyle.textMuted());
		} else if (listView) {
			drawList(context, mainX, top, mainW, bottom - top, mouseX, mouseY);
		} else {
			drawTree(context, mainX, top, mainW, bottom - top, mouseX, mouseY);
		}
		if (selected != null && source.nodes.containsKey(selected)) {
			int dw = Math.min(180, mainW / 2);
			int dx = mainX + mainW - dw - 6;
			drawDetails(context, dx, top + 6, dw, bottom - top - 12);
			hits.add(0, VeloUi.pill(context, dx + dw - 20, top + 10, 16, 16, "x", 0, mouseX, mouseY, () -> selected = null));
		}
	}

	private void drawSidebar(DrawContext context, int mouseX, int mouseY) {
		int x = contentX();
		int y = contentY();
		int total = source.nodes.size();
		long completed = source.nodes.keySet().stream().filter(this::done).count();
		context.drawTextWithShadow(this.textRenderer, "Total", x, y + 2, VeloStyle.textMuted());
		String totalText = completed + " / " + total;
		context.drawTextWithShadow(this.textRenderer, totalText, x + sidebar() - this.textRenderer.getWidth(totalText), y + 2, VeloStyle.text());
		bar(context, x, y + 13, sidebar(), total == 0 ? 0 : completed / (float) total);
		int listTop = y + 24;
		context.enableScissor(x, listTop, x + sidebar(), contentBottom() - 20);
		int ry = listTop - Math.round(sideScroll);
		for (String rootId : source.roots) {
			AdvancementSource.Node root = source.nodes.get(rootId);
			if (root == null) {
				continue;
			}
			int tabTotal = 0;
			int tabDone = 0;
			for (AdvancementSource.Node n : source.nodes.values()) {
				if (rootId.equals(n.rootId())) {
					tabTotal++;
					tabDone += done(n.id()) ? 1 : 0;
				}
			}
			boolean active = rootId.equals(tab);
			boolean hovered = VeloUi.inside(mouseX, mouseY, x, ry, sidebar(), 34) && mouseY >= listTop;
			VeloDraw.fillRounded(context, x, ry, sidebar(), 34, 7, active ? VeloStyle.accentSoft(0.22f) : hovered ? VeloStyle.cardHover() : VeloStyle.card());
			if (active) {
				VeloDraw.fillRounded(context, x + 1, ry + 8, 3, 18, 1, VeloStyle.accent());
			}
			context.drawItemWithoutEntity(root.icon(), x + 7, ry + 9);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(root.title(), sidebar() - 32), x + 28, ry + 6, VeloStyle.text());
			bar(context, x + 28, ry + 20, sidebar() - 52, tabTotal == 0 ? 0 : tabDone / (float) tabTotal);
			String count = tabDone + "/" + tabTotal;
			context.drawTextWithShadow(this.textRenderer, count, x + sidebar() - 6 - this.textRenderer.getWidth(count), ry + 17, VeloStyle.textFaint());
			if (ry + 34 > listTop) {
				hits.add(new VeloUi.Hit(x, Math.max(ry, listTop), sidebar(), 34, () -> {
					tab = rootId;
					selected = null;
					panX = 30;
					panY = 40;
					listScroll = 0;
					source.selectTab(rootId);
				}));
			}
			ry += 38;
		}
		context.disableScissor();
	}

	private void bar(DrawContext context, int x, int y, int w, float fraction) {
		VeloDraw.fillRounded(context, x, y, w, 4, 2, 0x40FFFFFF);
		if (fraction > 0) {
			VeloDraw.fillRounded(context, (float) x, y, Math.max(4, w * fraction), 4, 2, fraction >= 1 ? 0xFF6FE0A4 : VeloStyle.accent());
		}
	}

	// ---- Tree ----

	private void drawTree(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		List<AdvancementSource.Node> tabNodes = source.nodes.values().stream().filter(n -> tab.equals(n.rootId())).toList();
		float minX = Float.MAX_VALUE;
		float minY = Float.MAX_VALUE;
		for (AdvancementSource.Node n : tabNodes) {
			minX = Math.min(minX, n.x());
			minY = Math.min(minY, n.y());
		}
		float ox = x + panX - minX * SPACING * zoom;
		float oy = y + panY - minY * SPACING * zoom;
		float step = SPACING * zoom;
		int size = Math.round(22 * zoom);
		context.enableScissor(x, y, x + w, y + h);
		// Connections (elbow lines, like vanilla).
		for (AdvancementSource.Node n : tabNodes) {
			AdvancementSource.Node parent = n.parentId() == null ? null : source.nodes.get(n.parentId());
			if (parent == null) {
				continue;
			}
			float cx = ox + n.x() * step;
			float cy = oy + n.y() * step;
			float px = ox + parent.x() * step;
			float py = oy + parent.y() * step;
			float midX = (cx + px) / 2f;
			int color = done(n.id()) ? VeloAnim.lerpArgb(VeloStyle.accent(), 0xFFFFFFFF, 0.2f) : 0x50FFFFFF;
			float t = Math.max(1f, 1.5f * zoom);
			VeloDraw.line(context, px, py, midX, py, t, color);
			VeloDraw.line(context, midX, py, midX, cy, t, color);
			VeloDraw.line(context, midX, cy, cx, cy, t, color);
		}
		AdvancementSource.Node hovered = null;
		for (AdvancementSource.Node n : tabNodes) {
			float cx = ox + n.x() * step;
			float cy = oy + n.y() * step;
			int nx = Math.round(cx - size / 2f);
			int ny = Math.round(cy - size / 2f);
			boolean show = visible(n);
			boolean isDone = done(n.id());
			boolean isSelected = n.id().equals(selected);
			boolean isHovered = VeloUi.inside(mouseX, mouseY, nx, ny, size, size) && VeloUi.inside(mouseX, mouseY, x, y, w, h);
			if (isHovered) {
				hovered = n;
			}
			int fill = isDone ? VeloAnim.lerpArgb(VeloStyle.accent(), 0xFF000000, 0.35f) : 0xFF26262C;
			int border = switch (n.frame()) {
				case CHALLENGE -> 0xFFB06BFF;
				case GOAL -> 0xFFF4B82E;
				default -> isDone ? VeloStyle.accent() : 0x60FFFFFF;
			};
			int radius = n.frame() == AdvancementSource.Frame.TASK ? Math.max(3, size / 5) : size / 2;
			if (!show) {
				fill = VeloAnim.lerpArgb(fill, 0xFF101012, 0.7f);
				border = 0x20FFFFFF;
			}
			VeloDraw.fillRounded(context, nx, ny, size, size, radius, fill);
			VeloDraw.strokeRounded(context, nx, ny, size, size, radius, isSelected || isHovered ? 0xFFFFFFFF : border);
			if (zoom >= 0.6f) {
				context.getMatrices().pushMatrix();
				context.getMatrices().translate(cx - 8 * zoom, cy - 8 * zoom);
				context.getMatrices().scale(zoom, zoom);
				context.drawItemWithoutEntity(n.icon(), 0, 0);
				context.getMatrices().popMatrix();
			}
			if (show && !isDone) {
				AdvancementSource.Progress p = source.progress.get(n.id());
				if (p != null && p.percent() > 0 && p.percent() < 1) {
					VeloDraw.fillRounded(context, (float) nx + 2, ny + size - 4, (size - 4) * p.percent(), 2, 1, 0xFF6FE0A4);
				}
			}
		}
		context.disableScissor();
		if (hovered != null) {
			String title = hovered.title();
			int tw = this.textRenderer.getWidth(title) + 10;
			int tx = Math.min(mouseX + 10, x + w - tw);
			VeloDraw.fillRounded(context, tx, mouseY + 10, tw, 14, 5, 0xF0101014);
			context.drawTextWithShadow(this.textRenderer, title, tx + 5, mouseY + 13, VeloStyle.text());
			AdvancementSource.Node target = hovered;
			hits.add(new VeloUi.Hit(Math.round(ox + target.x() * step - size / 2f), Math.round(oy + target.y() * step - size / 2f), size, size,
					() -> selected = target.id()));
		}
		context.drawTextWithShadow(this.textRenderer, "Drag to move  ·  scroll to zoom", x + 6, y + h - 12, VeloStyle.textFaint());
	}

	// ---- List ----

	private void drawList(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		List<AdvancementSource.Node> rows = source.nodes.values().stream()
				.filter(n -> tab.equals(n.rootId()) && visible(n))
				.sorted(Comparator.comparing((AdvancementSource.Node n) -> done(n.id())).thenComparing(AdvancementSource.Node::title))
				.toList();
		float max = Math.max(0, rows.size() * 34 + 8 - h);
		listScroll = Math.clamp(listScroll, 0, max);
		context.enableScissor(x, y, x + w, y + h);
		int ry = y + 6 - Math.round(listScroll);
		for (AdvancementSource.Node n : rows) {
			boolean isDone = done(n.id());
			boolean hovered = VeloUi.inside(mouseX, mouseY, x + 6, ry, w - 12, 30) && mouseY >= y && mouseY < y + h;
			VeloDraw.fillRounded(context, x + 6, ry, w - 12, 30, 7, n.id().equals(selected) ? VeloStyle.accentSoft(0.2f)
					: hovered ? VeloStyle.cardHover() : VeloStyle.card());
			context.drawItemWithoutEntity(n.icon(), x + 12, ry + 7);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(n.title(), w - 120), x + 34, ry + 5, isDone ? 0xFF6FE0A4 : VeloStyle.text());
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(n.description(), w - 120), x + 34, ry + 17, VeloStyle.textFaint());
			AdvancementSource.Progress p = source.progress.get(n.id());
			String state = isDone ? "Done" : p != null && !p.fraction().isEmpty() ? p.fraction() : "To do";
			context.drawTextWithShadow(this.textRenderer, state, x + w - 14 - this.textRenderer.getWidth(state), ry + 11,
					isDone ? 0xFF6FE0A4 : VeloStyle.textMuted());
			if (ry + 30 > y && ry < y + h) {
				String id = n.id();
				hits.add(new VeloUi.Hit(x + 6, Math.max(ry, y), w - 12, 30, () -> selected = id));
			}
			ry += 34;
		}
		if (rows.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "Nothing matches.", x + 10, y + 10, VeloStyle.textMuted());
		}
		context.disableScissor();
	}

	// ---- Details ----

	private void drawDetails(DrawContext context, int x, int y, int w, int h) {
		VeloDraw.fillRounded(context, x, y, w, h, 8, 0xF0141418);
		VeloDraw.strokeRounded(context, x, y, w, h, 8, VeloStyle.border());
		AdvancementSource.Node n = selected == null ? null : source.nodes.get(selected);
		if (n == null) {
			context.drawTextWithShadow(this.textRenderer, "Select an advancement", x + 10, y + 10, VeloStyle.textMuted());
			context.drawTextWithShadow(this.textRenderer, "to see what it needs.", x + 10, y + 22, VeloStyle.textFaint());
			return;
		}
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x + 10, y + 10);
		context.getMatrices().scale(2f, 2f);
		context.drawItemWithoutEntity(n.icon(), 0, 0);
		context.getMatrices().popMatrix();
		String type = switch (n.frame()) {
			case CHALLENGE -> "Challenge";
			case GOAL -> "Goal";
			default -> "Task";
		};
		int typeColor = switch (n.frame()) {
			case CHALLENGE -> 0xFFB06BFF;
			case GOAL -> 0xFFF4B82E;
			default -> VeloStyle.textMuted();
		};
		context.drawTextWithShadow(this.textRenderer, type, x + 48, y + 14, typeColor);
		boolean isDone = done(n.id());
		context.drawTextWithShadow(this.textRenderer, isDone ? "Completed" : "Not done yet", x + 48, y + 26, isDone ? 0xFF6FE0A4 : VeloStyle.textFaint());
		int ty = y + 50;
		for (String line : VeloUi.wrap(n.title(), w - 20)) {
			context.drawTextWithShadow(this.textRenderer, net.veloclient.velo.client.gui.title.TitleScreenTheme.tileFont(line), x + 10, ty,
					VeloStyle.text());
			ty += 11;
		}
		ty += 4;
		for (String line : VeloUi.wrap(n.description(), w - 20)) {
			context.drawTextWithShadow(this.textRenderer, line, x + 10, ty, VeloStyle.textMuted());
			ty += 10;
		}
		AdvancementSource.Progress p = source.progress.get(n.id());
		if (p == null) {
			return;
		}
		ty += 8;
		bar(context, x + 10, ty, w - 20, p.done() ? 1 : p.percent());
		ty += 10;
		int total = p.obtained().size() + p.remaining().size();
		if (total > 1) {
			context.drawTextWithShadow(this.textRenderer, p.obtained().size() + " of " + total + " done", x + 10, ty, VeloStyle.textMuted());
			ty += 14;
			if (!p.remaining().isEmpty()) {
				context.drawTextWithShadow(this.textRenderer, "Still missing:", x + 10, ty, VeloStyle.text());
				ty += 11;
				for (String criterion : p.remaining()) {
					if (ty > y + h - 12) {
						context.drawTextWithShadow(this.textRenderer, "...", x + 14, ty, VeloStyle.textFaint());
						break;
					}
					context.drawTextWithShadow(this.textRenderer, VeloUi.trim("• " + pretty(criterion), w - 24), x + 14, ty, 0xFFFF9A9A);
					ty += 10;
				}
			}
		}
	}

	/** "minecraft:cherry_grove" -> "Cherry grove". */
	private static String pretty(String criterion) {
		String s = criterion.contains(":") ? criterion.substring(criterion.indexOf(':') + 1) : criterion;
		s = s.replace('_', ' ').replace('/', ' ');
		return s.isEmpty() ? criterion : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private void openVanilla() {
		openVanillaOnce = true;
		//? if <26.1 {
		Screen vanilla = new net.minecraft.client.gui.screen.advancement.AdvancementsScreen(
				MinecraftClient.getInstance().getNetworkHandler().getAdvancementHandler(), this);
		//?} else {
		/*Screen vanilla = new net.minecraft.client.gui.screens.advancements.AdvancementsScreen(
				net.minecraft.client.Minecraft.getInstance().getConnection().getAdvancements(), this);
		*///?}
		this.client.setScreen(vanilla);
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
		int mainX = contentX() + sidebar() + 12;
		int mainW = contentWidth() - sidebar() - 12;
		if (!listView && VeloUi.inside(click.x(), click.y(), mainX, contentY() + 24, mainW, contentBottom() - contentY() - 24)) {
			dragging = true;
			dragDistance = 0;
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (dragging) {
			panX += (float) offsetX;
			panY += (float) offsetY;
			dragDistance += Math.abs(offsetX) + Math.abs(offsetY);
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		dragging = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int mainX = contentX() + sidebar() + 12;
		if (mouseX < mainX) {
			sideScroll = Math.max(0, sideScroll - (float) verticalAmount * 24);
			return true;
		}
		if (listView) {
			listScroll -= (float) verticalAmount * 30;
			return true;
		}
		// Zoom toward the cursor.
		float old = zoom;
		zoom = Math.clamp(zoom * (verticalAmount > 0 ? 1.15f : 1 / 1.15f), 0.4f, 2.5f);
		float relX = (float) mouseX - mainX;
		float relY = (float) mouseY - (contentY() + 24);
		panX = relX - (relX - panX) * (zoom / old);
		panY = relY - (relY - panY) * (zoom / old);
		return true;
	}
}
