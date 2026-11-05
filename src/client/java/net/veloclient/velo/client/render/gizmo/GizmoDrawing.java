package net.veloclient.velo.client.render.gizmo;

//? if <1.21.11 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/^*
 * 1.21.10 has no Gizmo system (it came in 1.21.11): this is the same API the overlays call there - boxes, lines, points,
 * labels - drawn straight away with the render event's own matrices and buffers. The overlays call it from
 * {@code WorldRenderEvents.BEFORE_DEBUG_RENDER}; whatever is collected is drawn at the end of that same event.
 ^/
public final class GizmoDrawing {

	/^* What {@code ignoreOcclusion()} is called on: decides whether the shape shows through walls. ^/
	public static final class VisibilityConfigurable {
		final Shape shape;

		VisibilityConfigurable(Shape shape) {
			this.shape = shape;
		}

		public VisibilityConfigurable ignoreOcclusion() {
			shape.throughWalls = true;
			return this;
		}
	}

	abstract static class Shape {
		boolean throughWalls;

		abstract void draw(MatrixStack matrices, VertexConsumerProvider consumers, boolean throughWallsPass);
	}

	private static final List<Shape> FRAME = new ArrayList<>();

	static {
		// A phase after the default one: every overlay adds its shapes first, then they're drawn.
		net.minecraft.util.Identifier late = net.minecraft.util.Identifier.of("velo-client", "gizmo_flush");
		WorldRenderEvents.BEFORE_DEBUG_RENDER.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, late);
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(late, GizmoDrawing::flush);
	}

	private GizmoDrawing() {
	}

	/^* Called once at startup so the flush listener exists before any overlay registers its own. ^/
	public static void init() {
	}

	private static VisibilityConfigurable add(Shape shape) {
		FRAME.add(shape);
		return new VisibilityConfigurable(shape);
	}

	public static VisibilityConfigurable box(Box box, DrawStyle style) {
		return add(new BoxShape(box, style));
	}

	public static VisibilityConfigurable box(Box box, DrawStyle style, boolean ignored) {
		return box(box, style);
	}

	public static VisibilityConfigurable box(BlockPos pos, DrawStyle style) {
		return box(new Box(pos), style);
	}

	public static VisibilityConfigurable box(BlockPos pos, float inflate, DrawStyle style) {
		return box(new Box(pos).expand(inflate), style);
	}

	public static VisibilityConfigurable line(Vec3d from, Vec3d to, int color) {
		return line(from, to, color, 1f);
	}

	public static VisibilityConfigurable line(Vec3d from, Vec3d to, int color, float width) {
		return add(new LineShape(from, to, color));
	}

	public static VisibilityConfigurable point(Vec3d pos, int color, float size) {
		double r = Math.max(0.02, size * 0.0125);
		return add(new BoxShape(new Box(pos.subtract(r, r, r), pos.add(r, r, r)), DrawStyle.filled(color)));
	}

	public static VisibilityConfigurable blockLabel(String text, BlockPos pos, int line, int color, float scale) {
		return add(new TextShape(text, Vec3d.ofCenter(pos).add(0, 0.6 + line * 0.25, 0), color, scale * 0.025f));
	}

	public static VisibilityConfigurable entityLabel(Entity entity, int line, String text, int color, float scale) {
		return add(new TextShape(text, entity.getEntityPos().add(0, entity.getHeight() + 0.5 + line * 0.25, 0), color, scale * 0.025f));
	}

	public static VisibilityConfigurable text(String text, Vec3d pos, TextGizmo.Style style) {
		return add(new TextShape(text, pos, style.color(), style.scale() * 0.025f));
	}

	private static void flush(WorldRenderContext context) {
		if (FRAME.isEmpty()) {
			return;
		}
		MatrixStack matrices = context.matrices();
		VertexConsumerProvider consumers = context.consumers();
		if (matrices == null || consumers == null) {
			FRAME.clear();
			return;
		}
		Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
		matrices.push();
		matrices.translate(-cam.x, -cam.y, -cam.z);
		try {
			for (Shape shape : FRAME) {
				shape.draw(matrices, consumers, false);
			}
			// Through walls: drawn again without the depth test (see VeloGizmoLayers).
			for (Shape shape : FRAME) {
				if (shape.throughWalls) {
					shape.draw(matrices, consumers, true);
				}
			}
		} finally {
			matrices.pop();
			FRAME.clear();
		}
	}

	/^* The two drawing helpers moved from WorldRenderer to VertexRendering in 1.21.2. ^/
	private static final class Draw {
		static void filledBox(MatrixStack m, VertexConsumer v, Box b, float[] c) {
			//? if <1.21.2 {
			/^net.minecraft.client.render.WorldRenderer.renderFilledBox(m, v, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, c[0], c[1], c[2], c[3]);
			^///?} else {
			net.minecraft.client.render.VertexRendering.drawFilledBox(m, v, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, c[0], c[1], c[2], c[3]);
			//?}
		}

		static void boxLines(MatrixStack m, VertexConsumer v, Box b, float[] c) {
			//? if <1.21.2 {
			/^net.minecraft.client.render.WorldRenderer.drawBox(m, v, b, c[0], c[1], c[2], c[3]);
			^///?} else {
			net.minecraft.client.render.VertexRendering.drawBox(m.peek(), v, b, c[0], c[1], c[2], c[3]);
			//?}
		}
	}

	private static float[] rgba(int argb) {
		return new float[] {(argb >> 16 & 255) / 255f, (argb >> 8 & 255) / 255f, (argb & 255) / 255f, (argb >>> 24) / 255f};
	}

	private static final class BoxShape extends Shape {
		final Box box;
		final DrawStyle style;

		BoxShape(Box box, DrawStyle style) {
			this.box = box;
			this.style = style;
		}

		@Override
		void draw(MatrixStack m, VertexConsumerProvider consumers, boolean noDepth) {
			if (style.fill() != 0) {
				float[] c = rgba(style.fill());
				VertexConsumer fill = consumers.getBuffer(noDepth ? VeloGizmoLayers.filledNoDepth() : RenderLayer.getDebugFilledBox());
				Draw.filledBox(m, fill, box, c);
			}
			if (style.stroke() != 0) {
				float[] c = rgba(style.stroke());
				VertexConsumer lines = consumers.getBuffer(noDepth ? VeloGizmoLayers.linesNoDepth() : RenderLayer.getLines());
				Draw.boxLines(m, lines, box, c);
			}
		}
	}

	private static final class LineShape extends Shape {
		final Vec3d from;
		final Vec3d to;
		final int color;

		LineShape(Vec3d from, Vec3d to, int color) {
			this.from = from;
			this.to = to;
			this.color = color;
		}

		@Override
		void draw(MatrixStack m, VertexConsumerProvider consumers, boolean noDepth) {
			VertexConsumer lines = consumers.getBuffer(noDepth ? VeloGizmoLayers.linesNoDepth() : RenderLayer.getLines());
			Vec3d d = to.subtract(from);
			double len = d.length();
			if (len < 1e-6) {
				return;
			}
			float nx = (float) (d.x / len);
			float ny = (float) (d.y / len);
			float nz = (float) (d.z / len);
			MatrixStack.Entry e = m.peek();
			lines.vertex(e, (float) from.x, (float) from.y, (float) from.z).color(color).normal(e, nx, ny, nz);
			lines.vertex(e, (float) to.x, (float) to.y, (float) to.z).color(color).normal(e, nx, ny, nz);
		}
	}

	private static final class TextShape extends Shape {
		final String text;
		final Vec3d pos;
		final int color;
		final float scale;

		TextShape(String text, Vec3d pos, int color, float scale) {
			this.text = text;
			this.pos = pos;
			this.color = color;
			this.scale = scale;
		}

		@Override
		void draw(MatrixStack m, VertexConsumerProvider consumers, boolean noDepth) {
			if (noDepth != throughWalls) {
				return; // text draws once, in its own pass
			}
			MinecraftClient client = MinecraftClient.getInstance();
			TextRenderer font = client.textRenderer;
			m.push();
			m.translate(pos.x, pos.y, pos.z);
			m.multiply(client.gameRenderer.getCamera().getRotation());
			m.scale(scale, -scale, scale);
			float x = -font.getWidth(text) / 2f;
			font.draw(text, x, 0, color, false, m.peek().getPositionMatrix(), consumers,
					noDepth ? TextRenderer.TextLayerType.SEE_THROUGH : TextRenderer.TextLayerType.NORMAL, 0, 0xF000F0);
			m.pop();
		}
	}
}
*///?}
