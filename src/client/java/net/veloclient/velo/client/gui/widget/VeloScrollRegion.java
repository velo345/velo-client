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
	private static final int SCROLLBAR_WIDTH = 3;

	private final int x;
	private final int y;
	private final int width;
	private final int height;
	private final List<ClickableWidget> rows = new ArrayList<>();
	/** Where the content should scroll to... */
	private double scrollOffset;
	/** ...and where it's drawn right now - eased toward {@link #scrollOffset} every frame for smooth scrolling. */
	private double shownOffset;
	private Runnable lastLayout;
	private long lastNanos;

	public VeloScrollRegion(int x, int y, int width, int height) {
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		LIVE.put(this, Boolean.TRUE);
	}

	/** Every region still referenced by a screen - lets VeloWindow clip clicks to them. */
	private static final java.util.Map<VeloScrollRegion, Boolean> LIVE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

	/**
	 * True when {@code element} is a row of some scroll region but the point lies outside that
	 * region's visible rectangle - e.g. the hidden lower half of a partly scrolled-in tile sitting
	 * under the window's bottom buttons. Such clicks must not reach the row.
	 */
	public static boolean clipsClick(Object element, double mouseX, double mouseY) {
		synchronized (LIVE) {
			for (VeloScrollRegion region : LIVE.keySet()) {
				if (region.rows.contains(element)) {
					return !region.contains(mouseX, mouseY);
				}
			}
		}
		return false;
	}

	public void clearRows() {
		rows.clear();
	}

	/** Current scroll offset, so a caller can preserve it across a rebuild (e.g. {@code layoutContent()} after a button click) instead of it silently resetting to the top. */
	public double scrollOffset() {
		return scrollOffset;
	}

	public void setScrollOffset(double scrollOffset) {
		this.scrollOffset = Math.max(0, scrollOffset);
		this.shownOffset = this.scrollOffset;
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
		lastLayout = () -> layout(rowHeight, gap);
		int contentHeight = rows.isEmpty() ? 0 : rows.size() * (rowHeight + gap) - gap;
		int maxScroll = Math.max(0, contentHeight - height);
		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
		shownOffset = Math.max(0, Math.min(shownOffset, maxScroll));

		int cursor = y - (int) Math.round(shownOffset);
		for (ClickableWidget row : rows) {
			// Rendered (scissor-clipped) as soon as there's any overlap at
			// all, but only made *clickable* once at least a quarter of the
			// row is actually visible - a row barely poking into the
			// viewport by a pixel or two used to still count as "active"
			// with its full hitbox, which could overlap and steal clicks
			// meant for something below the scroll region entirely
			// (e.g. a "Done" button) until scrolled well past it.
			boolean overlaps = cursor + rowHeight > y && cursor < y + height;
			int visibleTop = Math.max(cursor, y);
			int visibleBottom = Math.min(cursor + rowHeight, y + height);
			boolean sufficientlyVisible = (visibleBottom - visibleTop) >= rowHeight * 0.25;
			row.setY(overlaps ? cursor : OFFSCREEN_Y);
			row.visible = overlaps;
			row.active = overlaps && sufficientlyVisible;
			cursor += rowHeight + gap;
		}
	}

	/** Renders every row clipped to this region's rectangle, so a row that's only partially inside gets cut off cleanly instead of spilling past the edge. */
	public void renderRows(DrawContext context, int mouseX, int mouseY, float delta) {
		if (width <= 0 || height <= 0) {
			return; // squeezed to nothing (tiny window / huge GUI scale) - nothing can be visible
		}
		animate();
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
		int requestedColumns = columns;
		lastLayout = () -> layoutGrid(requestedColumns, cellWidth, cellHeight, gap);
		columns = Math.max(1, columns);
		int rowCount = (int) Math.ceil(rows.size() / (double) columns);
		int contentHeight = rowCount == 0 ? 0 : rowCount * (cellHeight + gap) - gap;
		int maxScroll = Math.max(0, contentHeight - height);
		scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
		shownOffset = Math.max(0, Math.min(shownOffset, maxScroll));

		for (int i = 0; i < rows.size(); i++) {
			ClickableWidget cell = rows.get(i);
			int col = i % columns;
			int row = i / columns;
			int cellX = x + col * (cellWidth + gap);
			int cellY = y - (int) Math.round(shownOffset) + row * (cellHeight + gap);
			boolean overlaps = cellY + cellHeight > y && cellY < y + height;
			int visibleTop = Math.max(cellY, y);
			int visibleBottom = Math.min(cellY + cellHeight, y + height);
			boolean sufficientlyVisible = (visibleBottom - visibleTop) >= cellHeight * 0.25;
			cell.setX(cellX);
			cell.setY(overlaps ? cellY : OFFSCREEN_Y);
			cell.visible = overlaps;
			cell.active = overlaps && sufficientlyVisible;
		}
	}


	public void renderScrollbarGrid(DrawContext context, int columns, int cellHeight, int gap) {
		columns = Math.max(1, columns);
		int rowCount = (int) Math.ceil(rows.size() / (double) columns);
		int contentHeight = rowCount == 0 ? 0 : rowCount * (cellHeight + gap) - gap;
		if (contentHeight <= height) {
			return;
		}
		drawScrollbar(context, contentHeight);
	}

	public boolean scroll(double mouseX, double mouseY, double amount) {
		if (!contains(mouseX, mouseY)) {
			return false;
		}
		scrollOffset = Math.max(0, scrollOffset - amount * 22);
		if (lastNanos == 0) {
			// Owner draws rows itself (never calls renderRows), so nothing would ease the offset.
			shownOffset = scrollOffset;
		}
		return true;
	}

	/** Eases the drawn offset toward the target and re-lays-out rows while it moves. */
	private void animate() {
		long now = System.nanoTime();
		float dt = VeloStyle.frameDelta(lastNanos, now);
		lastNanos = now;
		if (Math.abs(shownOffset - scrollOffset) < 0.25) {
			if (shownOffset != scrollOffset) {
				shownOffset = scrollOffset;
				if (lastLayout != null) {
					lastLayout.run();
				}
			}
			return;
		}
		shownOffset = VeloAnim.step((float) shownOffset, (float) scrollOffset, dt * 1.5f);
		if (lastLayout != null) {
			lastLayout.run();
		}
	}

	private void drawScrollbar(DrawContext context, int contentHeight) {
		Theme theme = ThemeManager.active();
		float trackX = x + width - SCROLLBAR_WIDTH;
		float thumbHeight = Math.max(18f, (float) height * height / contentHeight);
		int maxScroll = contentHeight - height;
		float thumbY = y + (maxScroll > 0 ? (float) (shownOffset / maxScroll * (height - thumbHeight)) : 0f);
		VeloDraw.fillRounded(context, trackX, y, SCROLLBAR_WIDTH, height, SCROLLBAR_WIDTH / 2f, VeloUi.withAlpha(theme.text(), 0x0C));
		VeloDraw.fillRounded(context, trackX, thumbY, SCROLLBAR_WIDTH, thumbHeight, SCROLLBAR_WIDTH / 2f, VeloUi.withAlpha(theme.text(), 0x48));
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
		drawScrollbar(context, contentHeight);
	}
}
