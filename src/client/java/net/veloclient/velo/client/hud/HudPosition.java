package net.veloclient.velo.client.hud;

/**
 * On-screen position of a HUD element, stored as a fraction of the screen
 * (0.0-1.0) so layouts survive resolution changes. Backs the drag-to-reposition
 * + snap-to-grid editor described in design spec sections 5 and 7.
 */
public final class HudPosition {

	private float xFraction;
	private float yFraction;
	private float scale = 1.0f;

	public HudPosition(float xFraction, float yFraction) {
		this.xFraction = xFraction;
		this.yFraction = yFraction;
	}

	public float scale() {
		return scale;
	}

	public void setScale(float scale) {
		this.scale = Math.clamp(scale, 0.5f, 3.0f);
	}

	public int resolveX(int screenWidth, int elementWidth) {
		return Math.round(xFraction * (screenWidth - elementWidth));
	}

	public int resolveY(int screenHeight, int elementHeight) {
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
