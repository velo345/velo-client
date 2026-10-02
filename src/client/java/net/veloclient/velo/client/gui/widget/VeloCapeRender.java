package net.veloclient.velo.client.gui.widget;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;

/**
 * Draws a cape as a turning 3D object in 2D menus: the cape box's outer panel and its edge are
 * each drawn as an affine-transformed (sheared) textured face, shaded like Minecraft's own faces,
 * with a soft shadow underneath. Far cheaper than a real 3D render per tile, and it reads as a
 * physical cape instead of a flat texture crop. Works with any cape texture in the standard
 * 64x32 layout at any resolution ({@code textureWidth} x {@code textureHeight}).
 */
public final class VeloCapeRender {

	private VeloCapeRender() {
	}

	/**
	 * @param yawDegrees 0 = outer panel facing the viewer; positive turns the right edge away,
	 *                   revealing the cape's side
	 */
	public static void draw(DrawContext context, Identifier texture, int textureWidth, int textureHeight,
			float centerX, float bottomY, float height, float yawDegrees) {
		float unit = textureWidth / 64f;
		double yaw = Math.toRadians(yawDegrees);
		float cos = (float) Math.abs(Math.cos(yaw));
		float sin = (float) Math.sin(yaw);
		float capeHeight = height;
		float capeWidth = capeHeight * 10f / 16f;
		float depth = capeHeight * 1.4f / 16f;

		float frontWidth = capeWidth * cos;
		float sideWidth = depth * Math.abs(sin);
		float total = frontWidth + sideWidth;
		// Edges going away from the viewer rise slightly - a cheap stand-in for perspective.
		float frontSlope = -0.10f * sin;
		float sideSlope = 0.32f * Math.signum(sin);
		float top = bottomY - capeHeight;

		// Soft ground shadow.
		VeloDraw.shadow(context, Math.round(centerX - total * 0.45f), Math.round(bottomY - 1), Math.round(total * 0.9f), 2, 1,
				Math.max(3, Math.round(capeWidth * 0.18f)), 0, 0x55000000);

		float left = centerX - total / 2f;
		if (sin >= 0) {
			face(context, texture, textureWidth, textureHeight, 1 * unit, 1 * unit, 10 * unit, 16 * unit,
					left, top, frontWidth, frontWidth * frontSlope, capeHeight, 0xFFF2F2F2);
			if (sideWidth > 0.3f) {
				face(context, texture, textureWidth, textureHeight, 11 * unit, 1 * unit, 1 * unit, 16 * unit,
						left + frontWidth, top + frontWidth * frontSlope, sideWidth, sideWidth * sideSlope, capeHeight, 0xFFA8A8A8);
			}
		} else {
			if (sideWidth > 0.3f) {
				face(context, texture, textureWidth, textureHeight, 0, 1 * unit, 1 * unit, 16 * unit,
						left, top + sideWidth * -sideSlope, sideWidth, sideWidth * sideSlope, capeHeight, 0xFFA8A8A8);
			}
			face(context, texture, textureWidth, textureHeight, 1 * unit, 1 * unit, 10 * unit, 16 * unit,
					left + sideWidth, top, frontWidth, -frontWidth * frontSlope, capeHeight, 0xFFF2F2F2);
		}
	}

	/**
	 * Gentle idle turn (faster and wider while hovered). Each tile advances its own {@code phase}
	 * by {@link #advancePhase} - deriving the angle from absolute time with a hover-dependent speed
	 * made the cape jump whenever the hover state changed.
	 */
	public static float idleYaw(float phase, float hover) {
		return (float) (28 + Math.sin(phase) * (10 + hover * 14));
	}

	public static float advancePhase(float phase, float hover, float dt) {
		return (float) ((phase + dt * (0.7f + hover * 0.6f)) % (Math.PI * 2));
	}

	/**
	 * Draws texture region (u, v, w, h) onto the parallelogram with top-left (x, y), top edge
	 * (width, rise) and vertical side of {@code height}.
	 */
	private static void face(DrawContext context, Identifier texture, int texW, int texH, float u, float v, float regionW,
			float regionH, float x, float y, float width, float rise, float height, int tint) {
		if (width <= 0.01f) {
			return;
		}
		int drawW = 64;
		int drawH = 64;
		context.getMatrices().pushMatrix();
		// x' = x + px * (width / drawW) ; y' = y + px * (rise / drawW) + py * (height / drawH)
		context.getMatrices().mul(new Matrix3x2f(width / drawW, rise / drawW, 0f, height / drawH, x, y));
		context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, 0, 0, u, v, drawW, drawH,
				Math.max(1, Math.round(regionW)), Math.max(1, Math.round(regionH)), texW, texH, tint);
		context.getMatrices().popMatrix();
	}
}
