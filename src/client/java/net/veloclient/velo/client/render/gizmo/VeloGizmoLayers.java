package net.veloclient.velo.client.render.gizmo;

//? if >=1.21.2 && <1.21.11 {
/*import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormats;

import java.util.OptionalDouble;

/^* Lines and filled boxes without the depth test, for shapes that show through walls (1.21.10 only). ^/
final class VeloGizmoLayers {

	private static final RenderPipeline LINES_NO_DEPTH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
			.withLocation("velo-client:pipeline/gizmo_lines_no_depth")
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withDepthWrite(false)
			.build());
	private static final RenderPipeline FILLED_NO_DEPTH = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
			.withLocation("velo-client:pipeline/gizmo_filled_no_depth")
			.withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLE_STRIP)
			.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
			.withDepthWrite(false)
			.withCull(false)
			.build());

	private static final RenderLayer LINES = RenderLayer.of("velo_gizmo_lines_no_depth", 1536, LINES_NO_DEPTH,
			RenderLayer.MultiPhaseParameters.builder().lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(2.0))).build(false));
	private static final RenderLayer FILLED = RenderLayer.of("velo_gizmo_filled_no_depth", 1536, false, true, FILLED_NO_DEPTH,
			RenderLayer.MultiPhaseParameters.builder().build(false));

	private VeloGizmoLayers() {
	}

	static RenderLayer linesNoDepth() {
		return LINES;
	}

	static RenderLayer filledNoDepth() {
		return FILLED;
	}
}
*///?}
//? if <1.21.2 {
/*import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

import java.util.OptionalDouble;

/^* Lines and filled boxes without the depth test, for shapes that show through walls (1.21.1: built from render phases). ^/
final class VeloGizmoLayers {

	private static final RenderLayer LINES = RenderLayer.of("velo_gizmo_lines_no_depth", VertexFormats.LINES, VertexFormat.DrawMode.LINES, 1536,
			RenderLayer.MultiPhaseParameters.builder().program(RenderPhase.LINES_PROGRAM).lineWidth(new RenderPhase.LineWidth(OptionalDouble.of(2.0)))
					.layering(RenderPhase.VIEW_OFFSET_Z_LAYERING).transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY).depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
					.cull(RenderPhase.DISABLE_CULLING).writeMaskState(RenderPhase.COLOR_MASK).build(false));
	private static final RenderLayer FILLED = RenderLayer.of("velo_gizmo_filled_no_depth", VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLE_STRIP,
			1536, false, true, RenderLayer.MultiPhaseParameters.builder().program(RenderPhase.COLOR_PROGRAM).transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY)
					.depthTest(RenderPhase.ALWAYS_DEPTH_TEST).cull(RenderPhase.DISABLE_CULLING).writeMaskState(RenderPhase.COLOR_MASK).build(false));

	private VeloGizmoLayers() {
	}

	static RenderLayer linesNoDepth() {
		return LINES;
	}

	static RenderLayer filledNoDepth() {
		return FILLED;
	}
}
*///?}
