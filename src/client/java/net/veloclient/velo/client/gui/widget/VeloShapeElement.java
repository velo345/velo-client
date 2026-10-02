package net.veloclient.velo.client.gui.widget;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
//? if <26.1 {
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
//? if <26.1 {
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
		for (int v = 0; v < quadCount * 4; v++) {
			vertices.vertex(pose, xy[v * 2], xy[v * 2 + 1]).color(colors[v]);
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
		for (int v = 0; v < quadCount * 4; v++) {
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
