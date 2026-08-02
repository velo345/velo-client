package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;

/**
 * A scrollable viewport for a vertical stack of {@link ClickableWidget}s.
 * Vanilla's widget system has no built-in scrolling container, so this
 * tracks an offset, repositions/clips child widgets into a fixed screen
 * rectangle each frame, and draws a minimal accent scrollbar. Widgets
 * scrolled outside the viewport are pushed far off-screen (not just outside
 * the scissor rect) so they can never intercept a click meant for something
 * else drawn at the same screen coordinates outside this region.
 */
public final class VeloScrollRegion {

	private static final int OFFSCREEN_Y = -10_000;
	private static final int SCROLLBAR_WIDTH = 4;

	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final List<ClickableWidget> rows = new ArrayList<>();
	private double scrollOffset;

	public VeloScrollRegion(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}

	public void clearRows() {
		rows.clear();
	}

	/** Copy of the current rows, for removing them from a Screen's child list before rebuilding. */
	public List<ClickableWidget> rowsSnapshot() {
		return List.copyOf(rows);
	}

	/** Registers a widget as a row; its x/width are left as-is, its y is managed by this region. */
	public void addRow(ClickableWidget widget) {
		rows.add(widget);
	}

	public int viewportWidth() {
		return width - SCROLLBAR_WIDTH - 4;
	}

	public int x() {
		return x;
	}

	/** Lays out rows top-to-bottom starting at the region's top, each {@code rowHeight} tall with {@code gap} between. */
	public void layout(int rowHeight, int gap) {
		int contentHeight = rows.isEmpty() ? 0 : rows.size() * (rowHeight + gap) - gap;
		int maxScroll = Math.max(0, contentHeight - height);
		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

		int cursor = y - (int) scrollOffset;
		for (ClickableWidget row : rows) {
			// Any overlap keeps a row "active" (clickable/scrollable to) -
			// actual pixel-perfect clipping of a row that's only partially
			// inside the viewport is done at render time via a GPU scissor
			// (see renderRows), not by hiding it outright.
			boolean overlaps = cursor + rowHeight > y && cursor < y + height;
			row.setY(overlaps ? cursor : OFFSCREEN_Y);
			row.visible = overlaps;
			row.active = overlaps;
			cursor += rowHeight + gap;
		}
	}

	/** Renders every row clipped to this region's rectangle, so a row that's only partially inside gets cut off cleanly instead of spilling past the edge. */
	public void renderRows(DrawContext context, int mouseX, int mouseY, float delta) {
		renderScissorStart(context);
		for (ClickableWidget row : rows) {
			if (row.visible) {
				row.render(context, mouseX, mouseY, delta);
			}
		}
		renderScissorEnd(context);
	}

	/**
	 * Lays out cells in a wrapping grid (Lunar/Feather-style module tiles)
	 * instead of a single column - {@code columns} per row, each
	 * {@code cellWidth}x{@code cellHeight}, {@code gap} between both axes.
	 */
	public void layoutGrid(int columns, int cellWidth, int cellHeight, int gap) {
		columns = Math.max(1, columns);
		int rowCount = (int) Math.ceil(rows.size() / (double) columns);
		int contentHeight = rowCount == 0 ? 0 : rowCount * (cellHeight + gap) - gap;
		int maxScroll = Math.max(0, contentHeight - height);
		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

		for (int i = 0; i < rows.size(); i++) {
			ClickableWidget cell = rows.get(i);
			int col = i % columns;
			int row = i / columns;
			int cellX = x + col * (cellWidth + gap);
			int cellY = y - (int) scrollOffset + row * (cellHeight + gap);
			boolean overlaps = cellY + cellHeight > y && cellY < y + height;
			cell.setX(cellX);
			cell.setY(overlaps ? cellY : OFFSCREEN_Y);
			cell.visible = overlaps;
			cell.active = overlaps;
		}
	}


	public void renderScrollbarGrid(DrawContext context, int columns, int cellHeight, int gap) {
		columns = Math.max(1, columns);
		int rowCount = (int) Math.ceil(rows.size() / (double) columns);
		int contentHeight = rowCount == 0 ? 0 : rowCount * (cellHeight + gap) - gap;
		if (contentHeight <= height) {
			return;
		}
		Theme theme = ThemeManager.active();
		int trackX = x + width - SCROLLBAR_WIDTH;
		context.fill(trackX, y, trackX + SCROLLBAR_WIDTH, y + height, 0x33000000);
		int thumbHeight = Math.max(16, (int) ((double) height * height / contentHeight));
		int maxScroll = contentHeight - height;
		int thumbY = y + (maxScroll > 0 ? (int) (scrollOffset / maxScroll * (height - thumbHeight)) : 0);
		context.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, theme.accentStart());
	}

	public boolean scroll(double mouseX, double mouseY, double amount) {
		if (!contains(mouseX, mouseY)) {
			return false;
		}
		scrollOffset -= amount * 16;
		return true;
	}

	private boolean contains(double mouseX, double mouseY) {
		return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
	}

	public void renderScissorStart(DrawContext context) {
		context.enableScissor(x, y, x + width, y + height);
	}

	public void renderScissorEnd(DrawContext context) {
		context.disableScissor();
	}

	public void renderScrollbar(DrawContext context, int rowHeight, int gap) {
		int contentHeight = rows.isEmpty() ? 0 : rows.size() * (rowHeight + gap) - gap;
		if (contentHeight <= height) {
			return;
		}
		Theme theme = ThemeManager.active();
		int trackX = x + width - SCROLLBAR_WIDTH;
		context.fill(trackX, y, trackX + SCROLLBAR_WIDTH, y + height, 0x33000000);
		int thumbHeight = Math.max(16, (int) ((double) height * height / contentHeight));
		int maxScroll = contentHeight - height;
		int thumbY = y + (maxScroll > 0 ? (int) (scrollOffset / maxScroll * (height - thumbHeight)) : 0);
		context.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbHeight, theme.accentStart());
	}
}
