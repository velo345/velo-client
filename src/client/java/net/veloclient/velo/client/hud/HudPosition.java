package net.veloclient.velo.client.hud;

/**
 * On-screen position of a HUD element, stored as a fraction of the screen
 * (0.0-1.0) so layouts survive resolution changes. Backs the drag-to-reposition
 * + snap-to-grid editor described in design spec sections 5 and 7.
 *
 * <p>Until the player drags it somewhere ({@link #placed()}), an element is laid out automatically
 * by {@link HudAutoLayout}: stacked into its screen corner ({@link #corner()}, derived from the
 * built-in default) below/above its neighbours - so nothing ever appears in the middle of the
 * screen or on top of another element.
 */
public final class HudPosition {

	private float xFraction;
	private float yFraction;
	private float scale = 1.0f;
	private final float defaultXFraction;
	private final float defaultYFraction;
	private final Corner corner;
	private boolean placed;
	/** Pixel position from the last automatic layout pass, valid for {@link #autoScreenW} x {@link #autoScreenH}. */
	private int autoX = Integer.MIN_VALUE;
	private int autoY;
	private int autoScreenW;
	private int autoScreenH;

	/** Where an automatically placed element stacks. */
	public enum Corner { TOP_LEFT, LEFT_SIDE, TOP_RIGHT, RIGHT_SIDE, BOTTOM_RIGHT, TOP_CENTER }

	public HudPosition(float xFraction, float yFraction) {
		this(xFraction, yFraction, cornerFor(xFraction, yFraction));
	}

	public HudPosition(float xFraction, float yFraction, Corner corner) {
		this.xFraction = xFraction;
		this.yFraction = yFraction;
		this.defaultXFraction = xFraction;
		this.defaultYFraction = yFraction;
		this.corner = corner;
	}

	/** Bottom-left is chat and the centre is the game itself, so those map to the sides instead. */
	private static Corner cornerFor(float x, float y) {
		boolean left = x < 0.34f;
		boolean right = x > 0.66f;
		boolean top = y < 0.34f;
		boolean bottom = y > 0.66f;
		if (left) {
			return top ? Corner.TOP_LEFT : Corner.LEFT_SIDE;
		}
		if (right && !top && !bottom) {
			return Corner.RIGHT_SIDE;
		}
		return top ? Corner.TOP_RIGHT : Corner.BOTTOM_RIGHT;
	}

	public Corner corner() {
		return corner;
	}

	public boolean placed() {
		return placed;
	}

	public void setPlaced(boolean placed) {
		this.placed = placed;
	}

	public boolean isDefault(float x, float y) {
		return Math.abs(x - defaultXFraction) < 0.005f && Math.abs(y - defaultYFraction) < 0.005f;
	}

	public void setAuto(int x, int y, int screenW, int screenH) {
		autoX = x;
		autoY = y;
		autoScreenW = screenW;
		autoScreenH = screenH;
	}

	private boolean autoValid(int screenW, int screenH) {
		return !placed && autoX != Integer.MIN_VALUE && autoScreenW == screenW && autoScreenH == screenH;
	}

	/** Back to the element's built-in default spot and size - the HUD editor's "Reset Layout". */
	public void resetToDefault() {
		set(defaultXFraction, defaultYFraction);
		scale = 1.0f;
		placed = false;
	}

	public float scale() {
		return scale;
	}

	public void setScale(float scale) {
		this.scale = Math.clamp(scale, 0.5f, 3.0f);
	}

	public int resolveX(int screenWidth, int elementWidth) {
		if (autoValid(screenWidth, autoScreenH)) {
			return autoX;
		}
		return Math.round(xFraction * (screenWidth - elementWidth));
	}

	public int resolveY(int screenHeight, int elementHeight) {
		if (autoValid(autoScreenW, screenHeight)) {
			return autoY;
		}
		return Math.round(yFraction * (screenHeight - elementHeight));
	}

	public void set(float xFraction, float yFraction) {
		this.xFraction = Math.clamp(xFraction, 0f, 1f);
		this.yFraction = Math.clamp(yFraction, 0f, 1f);
	}

	/** Rounds to the nearest grid cell, e.g. 0.02 = 50x50 on-screen grid. */
	public void snapToGrid(float gridSize) {
		set(Math.round(xFraction / gridSize) * gridSize, Math.round(yFraction / gridSize) * gridSize);
	}

	public float xFraction() {
		return xFraction;
	}

	public float yFraction() {
		return yFraction;
	}
}
