package net.veloclient.velo.client.gui.widget;

//? if >=1.21.2 {
import com.mojang.blaze3d.pipeline.RenderPipeline;
//?}
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
//? if <1.21.2 {
/*import net.minecraft.client.render.VertexConsumer;
*///?} else if <26.1 {
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.ScreenRect;
import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.TextureSetup;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
*///?}

/**
 * One GUI element made of many quads (float corners, one color per vertex) - plain rectangles,
 * and the anti-aliased rounded shapes {@link VeloDraw} builds (a polygon plus a one-pixel alpha
 * fringe that the vertex-color interpolation turns into a smooth edge).
 *
 * Vanilla files every GUI element into a layer tree by testing its bounds against every element
 * already placed (GuiRenderState#findAppropriateNode) - quadratic in the element count. A rounded
 * panel drawn as a dozen-plus separate fill() rows paid that test a dozen-plus times, which made
 * Velo's HUD and menus one of the biggest CPU costs of a frame. Submitting the whole shape as a
 * single element pays it once and produces the exact same pixels (the GUI mesh is plain quads,
 * so one element can carry any number of them).
 */
//? if <1.21.2 {
/*/^* 1.21.1: no GUI render states - the quads go straight into the GUI buffer with the current matrix. ^/
final class VeloShapeElement {

	static void submit(DrawContext context, float[] xy, int[] colors, int quadCount, int minX, int minY, int maxX, int maxY) {
		org.joml.Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
		VertexConsumer vertices = context.getVertexConsumers().getBuffer(net.minecraft.client.render.RenderLayer.getGui());
		for (int v = 0; v < quadCount * 4; v++) {
			vertices.vertex(matrix, xy[v * 2], xy[v * 2 + 1], 0).color(colors[v]);
		}
		context.draw();
	}
}
*///?} else if <26.1 {
record VeloShapeElement(Matrix3x2fc pose, float[] xy, int[] colors, int quadCount, ScreenRect scissorArea, ScreenRect bounds)
		implements SimpleGuiElementRenderState {

	static void submit(DrawContext context, float[] xy, int[] colors, int quadCount, int minX, int minY, int maxX, int maxY) {
		Matrix3x2f pose = new Matrix3x2f(context.getMatrices());
		ScreenRect scissor = context.scissorStack.peekLast();
		ScreenRect bounds = new ScreenRect(minX, minY, maxX - minX, maxY - minY).transformEachVertex(pose);
		if (scissor != null) {
			bounds = scissor.intersection(bounds);
		}
		if (bounds == null) {
			return;
		}
		context.state.addSimpleElement(new VeloShapeElement(pose, java.util.Arrays.copyOf(xy, quadCount * 8),
				java.util.Arrays.copyOf(colors, quadCount * 4), quadCount, scissor, bounds));
	}

	@Override
	public void setupVertices(VertexConsumer vertices) {
		// A Matrix3x2f copy: 1.21.10 only takes the class, newer versions the interface too.
		org.joml.Matrix3x2f matrix = new org.joml.Matrix3x2f(pose);
		for (int v = 0; v < quadCount * 4; v++) {
			vertices.vertex(matrix, xy[v * 2], xy[v * 2 + 1]).color(colors[v]);
		}
	}

	@Override
	public RenderPipeline pipeline() {
		return RenderPipelines.GUI;
	}

	@Override
	public TextureSetup textureSetup() {
		return TextureSetup.empty();
	}
}
//?} else {
/*record VeloShapeElement(Matrix3x2fc pose, float[] xy, int[] colors, int quadCount, ScreenRectangle scissorArea, ScreenRectangle bounds)
		implements GuiElementRenderState {

	static void submit(DrawContext context, float[] xy, int[] colors, int quadCount, int minX, int minY, int maxX, int maxY) {
		Matrix3x2f pose = new Matrix3x2f(context.getMatrices());
		ScreenRectangle scissor = context.scissorStack.peek();
		ScreenRectangle bounds = new ScreenRectangle(minX, minY, maxX - minX, maxY - minY).transformMaxBounds(pose);
		if (scissor != null) {
			bounds = scissor.intersection(bounds);
		}
		if (bounds == null) {
			return;
		}
		context.guiRenderState.addGuiElement(new VeloShapeElement(pose, java.util.Arrays.copyOf(xy, quadCount * 8),
				java.util.Arrays.copyOf(colors, quadCount * 4), quadCount, scissor, bounds));
	}

	@Override
	public void buildVertices(VertexConsumer vertices) {
		int count = quadCount * 4;
		// Velo Turbo: all vertices in one reservation, transformed inline (was a begin/validate/Vector2f per vertex).
		if (vertices instanceof net.veloclient.velo.client.util.BulkVertexSink sink) {
			long p = sink.velo$reservePositionColorVertices(count);
			if (p != -1L) {
				float m00 = pose.m00(), m01 = pose.m01(), m10 = pose.m10(), m11 = pose.m11(), m20 = pose.m20(), m21 = pose.m21();
				for (int v = 0; v < count; v++, p += 16) {
					float x = xy[v * 2];
					float y = xy[v * 2 + 1];
					org.lwjgl.system.MemoryUtil.memPutFloat(p, m00 * x + m10 * y + m20);
					org.lwjgl.system.MemoryUtil.memPutFloat(p + 4, m01 * x + m11 * y + m21);
					org.lwjgl.system.MemoryUtil.memPutFloat(p + 8, 0f);
					int argb = colors[v];
					org.lwjgl.system.MemoryUtil.memPutInt(p + 12, argb & 0xFF00FF00 | (argb >>> 16) & 0xFF | (argb & 0xFF) << 16);
				}
				return;
			}
		}
		for (int v = 0; v < count; v++) {
			vertices.addVertexWith2DPose(pose, xy[v * 2], xy[v * 2 + 1]).setColor(colors[v]);
		}
	}

	@Override
	public RenderPipeline pipeline() {
		return RenderPipelines.GUI;
	}

	@Override
	public TextureSetup textureSetup() {
		return TextureSetup.noTexture();
	}
}
*///?}
