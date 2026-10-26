package net.veloclient.velo.client.cosmetics.render;

import net.veloclient.velo.client.emotes.EmoteEffects;
import net.veloclient.velo.client.emotes.EmoteFx;
import net.veloclient.velo.client.emotes.EmoteProps;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

/**
 * Draws the emotes' effects ({@link EmoteFx}) and props around emoting players - yours, other Velo players' and the
 * previews'. Sprites face the camera (letters stay readable), glowing ones are drawn additively with the {@code eyes}
 * render type, solid ones (smoke, bats, letters...) alpha-blended and full bright; props are textured models attached
 * to the ground in front of the player or to a hand / the head, so they move with the pose.
 *
 * <p>Effects live in body space (x right, y up, z forwards, origin at the feet). The renderer's space is the model's
 * (y down, facing -Z, right = -X, 1.501 above the feet), so a point (x, y, z) is drawn at (-x, 1.501 - y, -z).
 */
//? if <26.1 {
public final class EmoteFxRenderer extends net.minecraft.client.render.entity.feature.FeatureRenderer<
		net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> {
//?} else {
/*public final class EmoteFxRenderer extends net.minecraft.client.renderer.entity.layers.RenderLayer<
		net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> {
*///?}

	private static final int LIGHT = 0xF000F0;
	private static final String ATLAS = "textures/cosmetics/emote/fx.png";

	/** Sprites for one player: x y z u v color per vertex, 4 per quad, every quad both ways round. */
	private static final class Mesh implements EmoteFx.Out {
		final Buffer glow = new Buffer();
		final Buffer solid = new Buffer();
		// the camera's right and up in body space
		final float rx;
		final float ry;
		final float rz;
		final float ux;
		final float uy;
		final float uz;

		Mesh(float[] right, float[] up) {
			rx = right[0];
			ry = right[1];
			rz = right[2];
			ux = up[0];
			uy = up[1];
			uz = up[2];
		}

		private static int color(int rgb, float alpha) {
			return (Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24) | (rgb & 0xFFFFFF);
		}

		private Buffer buffer(int cell) {
			return EmoteFx.solid(cell) ? solid : glow;
		}

		@Override
		public void sprite(float x, float y, float z, float size, int rgb, float alpha, int cell, float angle, float offset) {
			if (alpha <= 0.01f || size <= 0.001f) {
				return;
			}
			float cx = x + rx * offset;
			float cy = y + ry * offset;
			float cz = z + rz * offset;
			float c = (float) Math.cos(angle) * size;
			float s = (float) Math.sin(angle) * size;
			// rotated right / up
			float ax = rx * c + ux * s;
			float ay = ry * c + uy * s;
			float az = rz * c + uz * s;
			float bx = -rx * s + ux * c;
			float by = -ry * s + uy * c;
			float bz = -rz * s + uz * c;
			buffer(cell).quad(new float[] {cx - ax + bx, cy - ay + by, cz - az + bz, cx - ax - bx, cy - ay - by, cz - az - bz,
					cx + ax - bx, cy + ay - by, cz + az - bz, cx + ax + bx, cy + ay + by, cz + az + bz}, cell, color(rgb, alpha));
		}

		@Override
		public void flat(float x, float y, float z, float size, int rgb, float alpha, int cell, float angle) {
			if (alpha <= 0.01f || size <= 0.001f) {
				return;
			}
			float c = (float) Math.cos(angle) * size;
			float s = (float) Math.sin(angle) * size;
			buffer(cell).quad(new float[] {x - c + s, y, z - s - c, x - c - s, y, z - s + c, x + c - s, y, z + s + c, x + c + s, y, z + s - c}, cell,
					color(rgb, alpha));
		}

		@Override
		public void beam(float x1, float y1, float z1, float x2, float y2, float z2, float width, int rgb, float alpha) {
			if (alpha <= 0.01f) {
				return;
			}
			Vector3f d = new Vector3f(x2 - x1, y2 - y1, z2 - z1);
			if (d.lengthSquared() < 1e-8f) {
				return;
			}
			Vector3f n1 = new Vector3f(d).cross(0, 0, 1);
			if (n1.lengthSquared() < 1e-6f) {
				n1.set(1, 0, 0);
			}
			n1.normalize(width / 2);
			Vector3f n2 = new Vector3f(d).cross(n1).normalize(width / 2);
			int color = color(rgb, alpha);
			for (Vector3f n : new Vector3f[] {n1, n2}) {
				glow.quad(new float[] {x1 - n.x, y1 - n.y, z1 - n.z, x2 - n.x, y2 - n.y, z2 - n.z, x2 + n.x, y2 + n.y, z2 + n.z, x1 + n.x, y1 + n.y,
						z1 + n.z}, EmoteFx.BEAM, color);
			}
		}
	}

	private static final class Buffer {
		float[] data = new float[6 * 4 * 256];
		int size;

		private void vertex(float x, float y, float z, float u, float v, int color) {
			if (size + 6 > data.length) {
				data = java.util.Arrays.copyOf(data, data.length * 2);
			}
			// body space -> model space
			data[size++] = -x;
			data[size++] = 1.501f - y;
			data[size++] = -z;
			data[size++] = u;
			data[size++] = v;
			data[size++] = Float.intBitsToFloat(color);
		}

		/** Corners top-left, bottom-left, bottom-right, top-right of atlas {@code cell}; both windings. */
		void quad(float[] c, int cell, int color) {
			float u0 = (cell % 8) / 8f;
			float v0 = (cell / 8) / 4f;
			float u1 = u0 + 1 / 8f;
			float v1 = v0 + 1 / 4f;
			vertex(c[0], c[1], c[2], u0, v0, color);
			vertex(c[3], c[4], c[5], u0, v1, color);
			vertex(c[6], c[7], c[8], u1, v1, color);
			vertex(c[9], c[10], c[11], u1, v0, color);
			vertex(c[9], c[10], c[11], u1, v0, color);
			vertex(c[6], c[7], c[8], u1, v1, color);
			vertex(c[3], c[4], c[5], u0, v1, color);
			vertex(c[0], c[1], c[2], u0, v0, color);
		}

		float[] copy() {
			return java.util.Arrays.copyOf(data, size);
		}
	}

	private interface Sink {
		void accept(float x, float y, float z, int color, float u, float v);
	}

	private interface PropSink {
		void accept(float x, float y, float z, float u, float v, float nx, float ny, float nz);
	}

	private static void emit(float[] g, Matrix4f pose, Sink sink) {
		Vector3f p = new Vector3f();
		for (int i = 0; i < g.length; i += 6) {
			pose.transformPosition(g[i], g[i + 1], g[i + 2], p);
			sink.accept(p.x, p.y, p.z, Float.floatToRawIntBits(g[i + 5]), g[i + 3], g[i + 4]);
		}
	}

	/** A prop's triangles (as quads, last corner twice) through {@code local} then the entry's matrices. */
	private static void emitProp(EmoteProps.Mesh mesh, Matrix4f local, Matrix4f pose, Matrix3f normal, PropSink sink) {
		Matrix4f full = new Matrix4f(pose).mul(local);
		Matrix3f n3 = new Matrix3f(normal).mul(new Matrix3f(local).normal());
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		float[] v = mesh.vertices();
		int[] tris = mesh.triangles();
		for (int t = 0; t < tris.length; t += 3) {
			for (int corner = 0; corner < 4; corner++) {
				int i = tris[t + Math.min(corner, 2)] * 8;
				full.transformPosition(v[i], v[i + 1], v[i + 2], p);
				n3.transform(v[i + 3], v[i + 4], v[i + 5], n);
				float len = n.length();
				if (len > 1e-6f) {
					n.div(len);
				}
				sink.accept(p.x, p.y, p.z, v[i + 6], v[i + 7], n.x, n.y, n.z);
			}
		}
	}

	/**
	 * The prop's own transform after its attachment: ground props stand at their body-space spot; a held prop sits at
	 * the end of the arm (in the arm's space, model pixels / 16). Then glTF space (y up, front +Z) is turned into the
	 * model's (half a turn around x), and the prop's yaw / pitch / roll and size are applied.
	 */
	private static Matrix4f propMatrix(EmoteFx.PropView p) {
		Matrix4f m = new Matrix4f();
		switch (p.at()) {
			case "ground" -> m.translate(-p.x(), 1.501f - p.y(), -p.z());
			case "rhand" -> m.translate(-1 / 16f, 10 / 16f, 0);
			case "lhand" -> m.translate(1 / 16f, 10 / 16f, 0);
			case "head" -> m.translate(0, -8 / 16f, 0);
			default -> {
			}
		}
		m.rotateX((float) Math.PI);
		if (!"ground".equals(p.at())) {
			m.translate(p.x(), p.y(), p.z());
		}
		m.rotateY((float) Math.toRadians(p.yaw()));
		m.rotateX((float) Math.toRadians(p.pitch()));
		m.rotateZ((float) Math.toRadians(p.roll()));
		m.scale(Math.max(0.0001f, p.scale()));
		return m;
	}

	/** The camera's right and up in this player's body space ({body yaw} turns world into body). */
	private static float[][] billboard(boolean gui, float bodyYaw) {
		if (gui) {
			// previews look at the player's front: the viewer's right is the player's left
			return new float[][] {{-1, 0, 0}, {0, 1, 0}};
		}
		float[] rot = net.veloclient.velo.client.pvp.PvpCompat.cameraRotation();
		double cy = Math.toRadians(rot[0]);
		double cp = Math.toRadians(rot[1]);
		double[] right = {-Math.cos(cy), 0, -Math.sin(cy)};
		double[] fwd = {-Math.sin(cy) * Math.cos(cp), -Math.sin(cp), Math.cos(cy) * Math.cos(cp)};
		double[] up = {right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0]};
		double by = Math.toRadians(bodyYaw);
		double[] bRight = {-Math.cos(by), 0, -Math.sin(by)};
		double[] bFwd = {-Math.sin(by), 0, Math.cos(by)};
		return new float[][] {toBody(right, bRight, bFwd), toBody(up, bRight, bFwd)};
	}

	private static float[] toBody(double[] v, double[] r, double[] f) {
		return new float[] {(float) (v[0] * r[0] + v[2] * r[2]), (float) v[1], (float) (v[0] * f[0] + v[2] * f[2])};
	}

	/** This frame's sprites for a player (null when nothing shows), after advancing their effects. */
	private static Mesh sprites(EmoteFx.Emitter emitter, EmoteFx.Anchors anchors, boolean gui, float bodyYaw) {
		float dt = emitter.tick(System.nanoTime());
		emitter.update(dt, anchors);
		float[][] axes = billboard(gui, bodyYaw);
		Mesh mesh = new Mesh(axes[0], axes[1]);
		if (EmoteEffects.particlesOn()) {
			emitter.draw(mesh, anchors);
		}
		return mesh;
	}

	private static boolean guiOpen() {
		return net.veloclient.velo.client.util.ClientCompat.currentScreen() != null;
	}

	//? if <26.1 {
	public EmoteFxRenderer(net.minecraft.client.render.entity.feature.FeatureRendererContext<
			net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> context) {
		super(context);
	}

	@SuppressWarnings("unchecked")
	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
			if (entityType != net.minecraft.entity.EntityType.PLAYER || !(entityRenderer instanceof net.minecraft.client.render.entity.feature.FeatureRendererContext)) {
				return;
			}
			helper.register(new EmoteFxRenderer((net.minecraft.client.render.entity.feature.FeatureRendererContext<
					net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel>) entityRenderer));
		});
	}

	@Override
	public void render(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue, int light,
			net.minecraft.client.render.entity.state.PlayerEntityRenderState state, float limbAngle, float limbDistance) {
		var client = net.minecraft.client.MinecraftClient.getInstance();
		if (state.invisible) {
			return;
		}
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		UUID uuid = null;
		if (!menu) {
			if (client.world == null || !(client.world.getEntityById(state.id) instanceof net.minecraft.entity.player.PlayerEntity player)) {
				return;
			}
			uuid = player.getUuid();
		}
		EmoteFx.Emitter emitter = menu ? EmoteEffects.previewEmitter() : EmoteEffects.emitter(uuid);
		if (emitter == null) {
			return;
		}
		boolean preview = menu || emitter == EmoteEffects.previewEmitter();
		EmoteFx.Anchors anchors = EmoteEffects.anchors(uuid, preview);
		Mesh mesh = sprites(emitter, anchors, menu || preview && guiOpen(), state.bodyYaw);
		var atlas = net.minecraft.util.Identifier.of("velo-client", ATLAS);
		float[] solid = mesh.solid.copy();
		float[] glow = mesh.glow.copy();
		if (solid.length > 0) {
			queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityTranslucent(atlas), (entry, consumer) ->
					emit(solid, entry.getPositionMatrix(), (x, y, z, color, u, v) -> consumer.vertex(x, y, z, color, u, v,
							net.minecraft.client.render.OverlayTexture.DEFAULT_UV, LIGHT, 0, 1, 0)));
		}
		if (glow.length > 0) {
			queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.eyes(atlas), (entry, consumer) ->
					emit(glow, entry.getPositionMatrix(), (x, y, z, color, u, v) -> consumer.vertex(x, y, z, color, u, v,
							net.minecraft.client.render.OverlayTexture.DEFAULT_UV, LIGHT, 0, 1, 0)));
		}
		List<EmoteFx.PropView> props = emitter.props(anchors);
		for (EmoteFx.PropView p : props) {
			EmoteProps.Mesh model = EmoteProps.get(p.id());
			if (model == null) {
				continue;
			}
			matrices.push();
			var parts = this.getContextModel();
			switch (p.at()) {
				case "rhand" -> parts.rightArm.applyTransform(matrices);
				case "lhand" -> parts.leftArm.applyTransform(matrices);
				case "head" -> parts.head.applyTransform(matrices);
				case "chest" -> parts.body.applyTransform(matrices);
				default -> {
				}
			}
			Matrix4f local = propMatrix(p);
			var texture = net.minecraft.util.Identifier.of("velo-client", EmoteProps.texturePath(p.id()));
			queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(texture), (entry, consumer) ->
					emitProp(model, local, entry.getPositionMatrix(), entry.getNormalMatrix(), (x, y, z, u, v, nx, ny, nz) ->
							consumer.vertex(x, y, z, 0xFFFFFFFF, u, v, net.minecraft.client.render.OverlayTexture.DEFAULT_UV, light, nx, ny, nz)));
			matrices.pop();
		}
	}
	//?} else {
	/*public EmoteFxRenderer(net.minecraft.client.renderer.entity.RenderLayerParent<
			net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> context) {
		super(context);
	}

	public static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			if (!(entityRenderer instanceof net.minecraft.client.renderer.entity.player.AvatarRenderer<?> avatarRenderer)) {
				return;
			}
			registrationHelper.register(new EmoteFxRenderer(avatarRenderer));
		});
	}

	@Override
	public void submit(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue, int light,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float limbAngle, float limbDistance) {
		var client = net.minecraft.client.Minecraft.getInstance();
		if (state.isInvisible) {
			return;
		}
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		UUID uuid = null;
		if (!menu) {
			if (client.level == null || !(client.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player player)) {
				return;
			}
			uuid = player.getUUID();
		}
		EmoteFx.Emitter emitter = menu ? EmoteEffects.previewEmitter() : EmoteEffects.emitter(uuid);
		if (emitter == null) {
			return;
		}
		boolean preview = menu || emitter == EmoteEffects.previewEmitter();
		EmoteFx.Anchors anchors = EmoteEffects.anchors(uuid, preview);
		Mesh mesh = sprites(emitter, anchors, menu || preview && guiOpen(), state.bodyRot);
		var atlas = net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", ATLAS);
		float[] solid = mesh.solid.copy();
		float[] glow = mesh.glow.copy();
		if (solid.length > 0) {
			queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(atlas), (pose2, consumer) ->
					emit(solid, new Matrix4f(pose2.pose()), (x, y, z, color, u, v) -> consumer.addVertex(x, y, z, color, u, v,
							net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, LIGHT, 0, 1, 0)));
		}
		if (glow.length > 0) {
			queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(atlas), (pose2, consumer) ->
					emit(glow, new Matrix4f(pose2.pose()), (x, y, z, color, u, v) -> consumer.addVertex(x, y, z, color, u, v,
							net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, LIGHT, 0, 1, 0)));
		}
		List<EmoteFx.PropView> props = emitter.props(anchors);
		for (EmoteFx.PropView p : props) {
			EmoteProps.Mesh model = EmoteProps.get(p.id());
			if (model == null) {
				continue;
			}
			matrices.pushPose();
			var parts = this.getParentModel();
			switch (p.at()) {
				case "rhand" -> parts.rightArm.translateAndRotate(matrices);
				case "lhand" -> parts.leftArm.translateAndRotate(matrices);
				case "head" -> parts.head.translateAndRotate(matrices);
				case "chest" -> parts.body.translateAndRotate(matrices);
				default -> {
				}
			}
			Matrix4f local = propMatrix(p);
			var texture = net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", EmoteProps.texturePath(p.id()));
			queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(texture), (pose2, consumer) ->
					emitProp(model, local, new Matrix4f(pose2.pose()), new Matrix3f(pose2.normal()), (x, y, z, u, v, nx, ny, nz) ->
							consumer.addVertex(x, y, z, 0xFFFFFFFF, u, v, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, light, nx, ny, nz)));
			matrices.popPose();
		}
	}
	*///?}
}
