package net.veloclient.velo.client.util;

import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Collection;

/**
 * An element list for one vanilla GUI layer that also remembers which of 8x8 screen cells its
 * elements cover, as a 64-bit mask.
 *
 * Vanilla places every new GUI element by scanning, layer by layer from the top, every element
 * already placed for one whose bounds intersect (quadratic per frame - one of the largest costs on
 * the render thread with a busy HUD or menu). With the mask, a list whose cells don't overlap the
 * new element's cells is skipped with one AND. The result is exactly vanilla's: two rectangles
 * that intersect (vanilla's test is strict) always share at least one cell, so a list is only
 * skipped when none of its elements could intersect. Cells are fixed per list at creation, and
 * anything off-screen clamps into the edge cells, so the mask stays conservative.
 */
public final class GuiCellMaskList<E> extends ArrayList<E> {

	private static final int GRID = 8;

	private final int cellWidth;
	private final int cellHeight;
	private long mask;

	public GuiCellMaskList() {
		MinecraftClient client = MinecraftClient.getInstance();
		int width = client.getWindow().getScaledWidth();
		int height = client.getWindow().getScaledHeight();
		this.cellWidth = Math.max(1, (width + GRID - 1) / GRID);
		this.cellHeight = Math.max(1, (height + GRID - 1) / GRID);
	}

	/** False only when no element of this list can intersect the given rectangle. */
	public boolean mayIntersect(int left, int top, int right, int bottom) {
		return (mask & cells(left, top, right, bottom)) != 0;
	}

	private long cells(int left, int top, int right, int bottom) {
		if (right <= left || bottom <= top) {
			return 0L;
		}
		int col0 = clamp(Math.floorDiv(left, cellWidth));
		int col1 = clamp(Math.floorDiv(right - 1, cellWidth));
		int row0 = clamp(Math.floorDiv(top, cellHeight));
		int row1 = clamp(Math.floorDiv(bottom - 1, cellHeight));
		long rowBits = ((1L << (col1 - col0 + 1)) - 1) << col0;
		long result = 0L;
		for (int row = row0; row <= row1; row++) {
			result |= rowBits << (row * GRID);
		}
		return result;
	}

	private static int clamp(int cell) {
		return cell < 0 ? 0 : Math.min(cell, GRID - 1);
	}

	private void include(Object element) {
		//? if <26.1 {
		if (element instanceof net.minecraft.client.gui.render.state.GuiElementRenderState state) {
			net.minecraft.client.gui.ScreenRect bounds = state.bounds();
			if (bounds != null) {
				mask |= cells(bounds.getLeft(), bounds.getTop(), bounds.getRight(), bounds.getBottom());
			}
		}
		//?} else {
		/*if (element instanceof net.minecraft.client.renderer.state.gui.ScreenArea area) {
			net.minecraft.client.gui.navigation.ScreenRectangle bounds = area.bounds();
			if (bounds != null) {
				mask |= cells(bounds.left(), bounds.top(), bounds.right(), bounds.bottom());
			}
		}
		*///?}
	}

	@Override
	public boolean add(E element) {
		include(element);
		return super.add(element);
	}

	@Override
	public void add(int index, E element) {
		include(element);
		super.add(index, element);
	}

	@Override
	public E set(int index, E element) {
		include(element);
		return super.set(index, element);
	}

	@Override
	public boolean addAll(Collection<? extends E> elements) {
		elements.forEach(this::include);
		return super.addAll(elements);
	}

	@Override
	public boolean addAll(int index, Collection<? extends E> elements) {
		elements.forEach(this::include);
		return super.addAll(index, elements);
	}
}
