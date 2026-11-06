package net.veloclient.velo.client.cosmetics.render;

import net.veloclient.velo.client.cosmetics.wings.WingAnimator;
import net.veloclient.velo.client.cosmetics.wings.WingModel;
import net.veloclient.velo.client.cosmetics.wings.WingRigs;
import net.veloclient.velo.client.cosmetics.wings.WingsManager;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws equipped 3D wings on players, Lunar-style: attached to the body (so they follow sneaking
 * and turning), drawn together with any cape (they sit just outside it), animated by
 * {@link WingAnimator} from how the player moves. Your own wings show for you; other players' show
 * when they run Velo Client on the same Velo Network server (their choice comes with the online
 * list). Hidden while an elytra is worn.
 *
 * <p>Each triangle goes out as a quad with its last corner repeated, since the entity render types
 * draw quads.
 */
//? if <26.1 {
public final class WingsFeatureRenderer extends net.minecraft.client.render.entity.feature.FeatureRenderer<
		net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> {
//?} else {
/*public final class WingsFeatureRenderer extends net.minecraft.client.renderer.entity.layers.RenderLayer<
		net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> {
*///?}

	/**
	 * The posed wing per wearer, reused for up to 1/75 s: posing runs every vertex through the rig, and doing that on
	 * every frame at a few hundred FPS cost ~15% of a third-person frame for no visible difference.
	 */
	private record Posed(WingModel model, long nanos, float[][] geometry) {
	}

	private final java.util.Map<Integer, Posed> posedCache = new java.util.HashMap<>();
	/** Glow colours per posed geometry (same lifetime as {@link #posed}). Render thread. */
	private static final java.util.Map<float[][], int[][]> GLOW_COLORS = new java.util.WeakHashMap<>();
	private float[][] scratch;

	private float[][] posed(int key, WingModel model, WingAnimator.Pose pose, WingsManager.Worn worn, float offsetY) {
		long now = System.nanoTime();
		Posed cached = posedCache.get(key);
		if (cached != null && cached.model() == model && now - cached.nanos() < 13_333_333L) {
			return cached.geometry();
		}
		scratch = WingAnimator.deform(model, pose, worn.settings(), offsetY, scratch);
		float[][] geometry = copy(scratch);
		if (posedCache.size() > 256) {
			posedCache.clear();
		}
		posedCache.put(key, new Posed(model, now, geometry));
		return geometry;
	}

	//? if <26.1 {
	public WingsFeatureRenderer(net.minecraft.client.render.entity.feature.FeatureRendererContext<
			net.minecraft.client.render.entity.state.PlayerEntityRenderState, net.minecraft.client.render.entity.model.PlayerEntityModel> context) {
		super(context);
	}

	@SuppressWarnings("unchecked")
	public static void register() {
		WingsManager.load();
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
			if (entityType != net.minecraft.entity.EntityType.PLAYER || !(entityRenderer instanceof net.minecraft.client.render.entity.feature.FeatureRendererContext)) {
				return;
			}
			helper.register(new WingsFeatureRenderer((net.minecraft.client.render.entity.feature.FeatureRendererContext<
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
		// The title screen's menu avatar has no entity: it's you, standing still.
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		net.minecraft.entity.player.PlayerEntity player = menu || client.world == null ? null
				: client.world.getEntityById(state.id) instanceof net.minecraft.entity.player.PlayerEntity p ? p : null;
		if (player == null && !menu) {
			return;
		}
		boolean self = menu || player == client.player;
		net.veloclient.velo.client.cosmetics.wings.WingsPreview.Tag tag = net.veloclient.velo.client.cosmetics.wings.WingsPreview.get(state);
		WingsManager.Worn worn = tag != null ? tag.worn() : self ? WingsManager.wornByLocalPlayer()
				: WingsManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.wingsIdFor(player.getUuid(), player.getGameProfile().name()));
		String id = worn == null ? null : worn.id();
		// Wings module: hidden for you or for others (previews always show).
		if (tag == null && !menu && !net.veloclient.velo.client.modules.cosmetics.WingsCosmeticsModule.shows(self)) {
			return;
		}
		if (id == null || tag == null && player != null && player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.item.Items.ELYTRA) {
			return;
		}
		var type = WingsManager.type(id).orElse(null);
		WingModel model = type == null ? null : WingsManager.model(id);
		if (model == null) {
			return;
		}
		WingAnimator.Motion motion = net.veloclient.velo.client.cosmetics.wings.WingsPreview.Showcase.IDLE.motion;
		if (player != null) {
			double dx = player.getX() - player.lastX;
			double dz = player.getZ() - player.lastZ;
			motion = new WingAnimator.Motion(Math.sqrt(dx * dx + dz * dz), player.getY() - player.lastY, player.isOnGround(), player.isGliding(),
					player.getAbilities().flying, player.isSneaking(), player.isSwimming() || player.isTouchingWater() && !player.isOnGround());
		}
		if (tag != null && tag.showcase() != null) {
			motion = tag.showcase().motion;
		} else if (!net.veloclient.velo.client.modules.cosmetics.WingsCosmeticsModule.flaps()) {
			// Flapping off: the wings rest as if standing still.
			motion = new WingAnimator.Motion(0, 0, true, false, false, false, false);
		}
		WingAnimator.Pose pose = WingAnimator.pose(tag != null ? tag.key() : state.id, motion, type.style(), worn.settings());
		// Light Cosmetics (Performance Boost): just the wingbeat - no moving arms, glow, shine or particles.
		boolean plainWings = net.veloclient.velo.client.modules.performance.PerformanceBoostModule.lightCosmetics;
		// Someone else's wings far away: just the wingbeat - glow, shine and particles are invisible at that size anyway.
		plainWings |= !self && player != null && client.player != null && player.squaredDistanceTo(client.player) > 24 * 24;
		if (plainWings) {
			pose = WingAnimator.plain(pose);
		}
		// A recolourable pair (white art): the wearer's colour.
		int tint = WingRigs.recolorable(id) ? WingRigs.tint(worn.settings().color(), pose.seconds()) : -1;
		int wingColor = tint < 0 ? 0xFFFFFFFF : 0xFF000000 | tint;
		float[][] geometry = posed(state.id, model, pose, worn, type.offsetY());
		var texture = WingsManager.texture(id);
		if (texture == null) {
			return; // a server-only pair whose texture is still downloading
		}
		// Neon pairs glow: full brightness whatever the light around.
		int drawLight = type.glow() ? 0xF000F0 : light;
		matrices.push();
		this.getContextModel().body.applyTransform(matrices);
		queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.entityCutoutNoCull(texture), (entry, consumer) -> {
			Matrix4f position = entry.getPositionMatrix();
			Matrix3f normal = entry.getNormalMatrix();
			emit(model, geometry, position, normal, (x, y, z, u, v, nx, ny, nz) -> consumer.vertex(x, y, z, wingColor, u, v,
					net.minecraft.client.render.OverlayTexture.DEFAULT_UV, drawLight, nx, ny, nz));
		});
		if (model.shine != null && !plainWings) {
			float seconds = pose.seconds();
			queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.eyes(net.minecraft.util.Identifier.of("velo-client", SHINE_TEXTURE)),
					(entry, consumer) -> emitShine(model, geometry, seconds, entry.getPositionMatrix(), (x, y, z, color, u, v) ->
							consumer.vertex(x, y, z, color, u, v, net.minecraft.client.render.OverlayTexture.DEFAULT_UV, 0xF000F0, 0, 1, 0)));
		}
		if (model.effect != null && !plainWings) {
			float seconds = pose.seconds();
			float size = worn.settings().scale();
			queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.eyes(net.minecraft.util.Identifier.of("velo-client", SHINE_TEXTURE)),
					(entry, consumer) -> emitGlow(model, geometry, seconds, tint, entry.getPositionMatrix(), (x, y, z, color, u, v) ->
							consumer.vertex(x, y, z, color, u, v, net.minecraft.client.render.OverlayTexture.DEFAULT_UV, 0xF000F0, 0, 1, 0)));
			queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.eyes(net.minecraft.util.Identifier.of("velo-client", PARTICLE_TEXTURE)),
					(entry, consumer) -> emitParticles(model, geometry, seconds, size, tint, entry.getPositionMatrix(), (x, y, z, color, u, v) ->
							consumer.vertex(x, y, z, color, u, v, net.minecraft.client.render.OverlayTexture.DEFAULT_UV, 0xF000F0, 0, 1, 0)));
		}
		matrices.pop();
	}
	//?} else {
	/*public WingsFeatureRenderer(net.minecraft.client.renderer.entity.RenderLayerParent<
			net.minecraft.client.renderer.entity.state.AvatarRenderState, net.minecraft.client.model.player.PlayerModel> context) {
		super(context);
	}

	public static void register() {
		WingsManager.load();
		net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			// Avatar renderers draw players (and mannequins, which the entity check in submit skips).
			if (!(entityRenderer instanceof net.minecraft.client.renderer.entity.player.AvatarRenderer<?> avatarRenderer)) {
				return;
			}
			registrationHelper.register(new WingsFeatureRenderer(avatarRenderer));
		});
	}

	@Override
	public void submit(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue, int light,
			net.minecraft.client.renderer.entity.state.AvatarRenderState state, float limbAngle, float limbDistance) {
		var client = net.minecraft.client.Minecraft.getInstance();
		if (state.isInvisible) {
			return;
		}
		// The title screen's menu avatar has no entity: it's you, standing still.
		boolean menu = net.veloclient.velo.client.gui.widget.MenuAvatar.is(state.id);
		net.minecraft.world.entity.player.Player player = menu || client.level == null ? null
				: client.level.getEntity(state.id) instanceof net.minecraft.world.entity.player.Player p ? p : null;
		if (player == null && !menu) {
			return;
		}
		boolean self = menu || player == client.player;
		net.veloclient.velo.client.cosmetics.wings.WingsPreview.Tag tag = net.veloclient.velo.client.cosmetics.wings.WingsPreview.get(state);
		WingsManager.Worn worn = tag != null ? tag.worn() : self ? WingsManager.wornByLocalPlayer()
				: WingsManager.parseNetworkValue(net.veloclient.velo.client.network.VeloUserRegistry.wingsIdFor(player.getUUID(), player.getGameProfile().name()));
		String id = worn == null ? null : worn.id();
		// Wings module: hidden for you or for others (previews always show).
		if (tag == null && !menu && !net.veloclient.velo.client.modules.cosmetics.WingsCosmeticsModule.shows(self)) {
			return;
		}
		if (id == null || tag == null && player != null && player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() == net.minecraft.world.item.Items.ELYTRA) {
			return;
		}
		var type = WingsManager.type(id).orElse(null);
		WingModel model = type == null ? null : WingsManager.model(id);
		if (model == null) {
			return;
		}
		WingAnimator.Motion motion = net.veloclient.velo.client.cosmetics.wings.WingsPreview.Showcase.IDLE.motion;
		if (player != null) {
			double dx = player.getX() - player.xo;
			double dz = player.getZ() - player.zo;
			motion = new WingAnimator.Motion(Math.sqrt(dx * dx + dz * dz), player.getY() - player.yo, player.onGround(), player.isFallFlying(),
					player.getAbilities().flying, player.isShiftKeyDown(), player.isSwimming() || player.isInWater() && !player.onGround());
		}
		if (tag != null && tag.showcase() != null) {
			motion = tag.showcase().motion;
		} else if (!net.veloclient.velo.client.modules.cosmetics.WingsCosmeticsModule.flaps()) {
			// Flapping off: the wings rest as if standing still.
			motion = new WingAnimator.Motion(0, 0, true, false, false, false, false);
		}
		WingAnimator.Pose pose = WingAnimator.pose(tag != null ? tag.key() : state.id, motion, type.style(), worn.settings());
		// Light Cosmetics (Performance Boost): just the wingbeat - no moving arms, glow, shine or particles.
		boolean plainWings = net.veloclient.velo.client.modules.performance.PerformanceBoostModule.lightCosmetics;
		// Someone else's wings far away: just the wingbeat - glow, shine and particles are invisible at that size anyway.
		plainWings |= !self && player != null && client.player != null && player.distanceToSqr(client.player) > 24 * 24;
		if (plainWings) {
			pose = WingAnimator.plain(pose);
		}
		// A recolourable pair (white art): the wearer's colour.
		int tint = WingRigs.recolorable(id) ? WingRigs.tint(worn.settings().color(), pose.seconds()) : -1;
		int wingColor = tint < 0 ? 0xFFFFFFFF : 0xFF000000 | tint;
		float[][] geometry = posed(state.id, model, pose, worn, type.offsetY());
		var texture = WingsManager.texture(id);
		if (texture == null) {
			return; // a server-only pair whose texture is still downloading
		}
		// Neon pairs glow: full brightness whatever the light around.
		int drawLight = type.glow() ? 0xF000F0 : light;
		matrices.pushPose();
		this.getParentModel().body.translateAndRotate(matrices);
		queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(texture), (pose2, consumer) -> {
			Matrix4f position = new Matrix4f(pose2.pose());
			Matrix3f normal = new Matrix3f(pose2.normal());
			boolean fast = true;
			for (int h = 0; h < model.halves.length && fast; h++) {
				WingModel.Half half = model.halves[h];
				fast = CosmeticVertexWriter.writeQuads(consumer, MeshLod.quadIndices(half.triangles), half.vertexCount(), geometry[h], 6, 0, 3,
						half.uv, 2, 0, 1f, 0f, position, normal, wingColor, null, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, drawLight);
			}
			if (!fast) {
				emit(model, geometry, position, normal, (x, y, z, u, v, nx, ny, nz) -> consumer.addVertex(x, y, z, wingColor, u, v,
						net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, drawLight, nx, ny, nz));
			}
		});
		if (model.shine != null && !plainWings) {
			float seconds = pose.seconds();
			queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(
					net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", SHINE_TEXTURE)),
					(pose2, consumer) -> emitShine(model, geometry, seconds, new Matrix4f(pose2.pose()), (x, y, z, color, u, v) ->
							consumer.addVertex(x, y, z, color, u, v, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0xF000F0, 0, 1, 0)));
		}
		if (model.effect != null && !plainWings) {
			float seconds = pose.seconds();
			float size = worn.settings().scale();
			queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(
					net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", SHINE_TEXTURE)),
					(pose2, consumer) -> {
						if (!glowBulk(model, geometry, seconds, tint, new Matrix4f(pose2.pose()), new Matrix3f(pose2.normal()), consumer)) {
							emitGlow(model, geometry, seconds, tint, new Matrix4f(pose2.pose()), (x, y, z, color, u, v) ->
									consumer.addVertex(x, y, z, color, u, v, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0xF000F0, 0, 1, 0));
						}
					});
			queue.submitCustomGeometry(matrices, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(
					net.minecraft.resources.Identifier.fromNamespaceAndPath("velo-client", PARTICLE_TEXTURE)),
					(pose2, consumer) -> emitParticles(model, geometry, seconds, size, tint, new Matrix4f(pose2.pose()), (x, y, z, color, u, v) ->
							consumer.addVertex(x, y, z, color, u, v, net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY, 0xF000F0, 0, 1, 0)));
		}
		matrices.popPose();
	}
	*///?}

	/** White on the left half (the glints are vertex colour only), a four-pointed star on the right. */
	private static final String SHINE_TEXTURE = "textures/cosmetics/shine.png";

	/** The auras' particle atlas (white shapes, tinted per particle). */
	private static final String PARTICLE_TEXTURE = "textures/cosmetics/aura/atlas.png";

	/**
	 * A pair's glow ({@link WingRigs#glowColor}) over the parts of its texture that glow, in {@code tint} (RGB) if it's
	 * been recoloured (-1: its own); dark triangles are left out.
	 */
	private static void emitGlow(WingModel model, float[][] geometry, float seconds, int tint, Matrix4f position, ShineSink sink) {
		WingRigs.Glow glow = model.effect.glow();
		if (glow == null) {
			return;
		}
		if (tint >= 0) {
			glow = WingRigs.tinted(glow, tint);
		}
		Vector3f p = new Vector3f();
		for (int h = 0; h < model.halves.length; h++) {
			WingModel.Half half = model.halves[h];
			if (half.glowMask == null) {
				continue;
			}
			float[] g = geometry[h];
			// once per vertex (it used to be once per triangle corner), and only when the wing was posed anew
			int[][] cachedColors = GLOW_COLORS.computeIfAbsent(geometry, k -> new int[model.halves.length][]);
			int[] color = cachedColors[h];
			if (color == null) {
				int n = half.vertexCount();
				color = new int[n];
				for (int v = 0; v < n; v++) {
					color[v] = WingRigs.glowColor(glow, half.glowKey[v], half.glowMask[v], seconds);
				}
				cachedColors[h] = color;
			}
			emitColoredQuads(MeshLod.quadIndices(half.triangles), color, g, position, p, sink);
		}
	}

	private static final float[] GLOW_UV = {0.25f, 0.5f};

	/** The glow pass through the bulk writer (26.2+); false = take the vertex-by-vertex path. */
	private static boolean glowBulk(WingModel model, float[][] geometry, float seconds, int tint, Matrix4f position, Matrix3f normal, Object consumer) {
		WingRigs.Glow glow = model.effect.glow();
		if (glow == null) {
			return true;
		}
		if (tint >= 0) {
			glow = WingRigs.tinted(glow, tint);
		}
		int[][] cachedColors = GLOW_COLORS.computeIfAbsent(geometry, k -> new int[model.halves.length][]);
		for (int h = 0; h < model.halves.length; h++) {
			WingModel.Half half = model.halves[h];
			if (half.glowMask == null) {
				continue;
			}
			int[] color = cachedColors[h];
			if (color == null) {
				color = new int[half.vertexCount()];
				for (int v = 0; v < color.length; v++) {
					color[v] = WingRigs.glowColor(glow, half.glowKey[v], half.glowMask[v], seconds);
				}
				cachedColors[h] = color;
			}
			// only the quads that visibly glow (the rest was written, uploaded and depth-sorted for nothing)
			int[] lit = litQuads(MeshLod.quadIndices(half.triangles), color);
			if (lit.length == 0) {
				continue;
			}
			if (!CosmeticVertexWriter.writeQuads(consumer, lit, half.vertexCount(), geometry[h], 6, 0, 3,
					GLOW_UV, 0, 0, 1f, 0f, position, normal, 0, color, 0xA0000 /* no overlay */, 0xF000F0)) {
				return h == 0 ? false : true;
			}
		}
		return true;
	}

	/** The quads with any corner bright enough to see (alpha >= 4). */
	private static int[] litQuads(int[] quads, int[] color) {
		int[] out = new int[quads.length];
		int m = 0;
		for (int q = 0; q < quads.length; q += 4) {
			if ((color[quads[q]] >>> 24) >= 4 || (color[quads[q + 1]] >>> 24) >= 4 || (color[quads[q + 2]] >>> 24) >= 4 || (color[quads[q + 3]] >>> 24) >= 4) {
				System.arraycopy(quads, q, out, m, 4);
				m += 4;
			}
		}
		return m == out.length ? quads : java.util.Arrays.copyOf(out, m);
	}

	/** Quads of a full-bright pass: one colour per vertex, quads that are (almost) invisible left out. */
	private static void emitColoredQuads(int[] quads, int[] color, float[] g, Matrix4f position, Vector3f p, ShineSink sink) {
		for (int q = 0; q < quads.length; q += 4) {
			int max = Math.max(Math.max(color[quads[q]] >>> 24, color[quads[q + 1]] >>> 24), Math.max(color[quads[q + 2]] >>> 24, color[quads[q + 3]] >>> 24));
			if (max < 4) {
				continue;
			}
			for (int corner = 0; corner < 4; corner++) {
				int v = quads[q + corner];
				int i = v * 6;
				position.transformPosition(g[i], g[i + 1], g[i + 2], p);
				sink.accept(p.x, p.y, p.z, color[v], 0.25f, 0.5f);
			}
		}
	}

	/**
	 * A pair's particles ({@link WingRigs#particle}): flat sprites from the aura atlas, each starting on one of the
	 * half's source vertices where the wing is now and drifting off from there, both sides drawn.
	 */
	private static void emitParticles(WingModel model, float[][] geometry, float seconds, float scale, int tint, Matrix4f position, ShineSink sink) {
		Vector3f p = new Vector3f();
		float[] out = new float[7];
		float inset = 0.5f / (net.veloclient.velo.client.cosmetics.aura.AuraEffects.ATLAS_CELL * net.veloclient.velo.client.cosmetics.aura.AuraEffects.ATLAS_COLUMNS);
		int columns = net.veloclient.velo.client.cosmetics.aura.AuraEffects.ATLAS_COLUMNS;
		int rows = net.veloclient.velo.client.cosmetics.aura.AuraEffects.ATLAS_ROWS;
		for (int h = 0; h < model.halves.length; h++) {
			WingModel.Half half = model.halves[h];
			if (half.sources == null) {
				continue;
			}
			float[] g = geometry[h];
			for (WingRigs.Particles kind : model.effect.particles()) {
				int cell = WingRigs.cell(kind.kind());
				float u0 = (cell % columns) / (float) columns + inset;
				float v0 = (cell / columns) / (float) rows + inset;
				float u1 = u0 + 1f / columns - 2 * inset;
				float v1 = v0 + 1f / rows - 2 * inset;
				for (int k = 0; k < kind.count(); k++) {
					WingRigs.particle(kind, k, h, seconds, half.sources.length, out);
					if (out[5] < 0.02f || out[4] <= 0) {
						continue;
					}
					int i = half.sources[(int) out[0]] * 6;
					float cx = g[i] + out[1] * scale;
					float cy = g[i + 1] + out[2] * scale;
					float cz = g[i + 2] + out[3] * scale;
					float s = out[4] * scale;
					float cos = (float) Math.cos(out[6]) * s;
					float sin = (float) Math.sin(out[6]) * s;
					int color = Math.round(Math.min(1f, out[5]) * 255) << 24 | ((tint >= 0 ? tint : kind.color()) & 0xFFFFFF);
					// Corners (x, y) with the texture's top at -y (body y points down).
					float[][] corners = {{-cos + sin, -sin - cos, u0, v0}, {cos + sin, sin - cos, u1, v0}, {cos - sin, sin + cos, u1, v1},
							{-cos - sin, -sin + cos, u0, v1}};
					for (int n = 0; n < 8; n++) {
						float[] q = corners[n < 4 ? n : 7 - n];
						position.transformPosition(cx + q[0], cy + q[1], cz, p);
						sink.accept(p.x, p.y, p.z, color, q[2], q[3]);
					}
				}
			}
		}
	}

	@FunctionalInterface
	private interface ShineSink {
		void accept(float x, float y, float z, int color, float u, float v);
	}

	/**
	 * The shine pass of a polished pair, full bright over the wings: per corner, mirror highlights from where the
	 * feather faces now ({@link WingRigs#specular}) and the glint sweeping across ({@link WingRigs#glint}), tinted at
	 * the edges and white-hot in the core; triangles nothing shines on are left out. Then the twinkling stars.
	 */
	private static void emitShine(WingModel model, float[][] geometry, float seconds, Matrix4f position, ShineSink sink) {
		int tint = model.shine.color();
		Vector3f p = new Vector3f();
		float[] star = new float[3];
		for (int h = 0; h < model.halves.length; h++) {
			WingModel.Half half = model.halves[h];
			float[] g = geometry[h];
			int vertices = half.vertexCount();
			int[] color = new int[vertices];
			for (int v = 0; v < vertices; v++) {
				int i = v * 6;
				float band = WingRigs.band(half.geometry[i], half.geometry[i + 1], half.side);
				float hot = WingRigs.glintCore(band, seconds) + WingRigs.specular(g[i + 3], g[i + 4], g[i + 5]) * 0.55f;
				float halo = WingRigs.glint(band, seconds) * 0.45f;
				float a = Math.min(1f, hot + halo);
				// The hotter, the whiter.
				float white = Math.min(1f, hot * 1.4f);
				int r = Math.round(((tint >> 16) & 0xFF) + (255 - ((tint >> 16) & 0xFF)) * white);
				int gr = Math.round(((tint >> 8) & 0xFF) + (255 - ((tint >> 8) & 0xFF)) * white);
				int b = Math.round((tint & 0xFF) + (255 - (tint & 0xFF)) * white);
				color[v] = Math.round(a * 235) << 24 | r << 16 | gr << 8 | b;
			}
			emitColoredQuads(MeshLod.quadIndices(half.triangles), color, g, position, p, sink);
			// Stars: a flat four-pointed sprite on the feather, a little behind it so it isn't hidden by it.
			for (int k = 0; k < WingRigs.STARS; k++) {
				WingRigs.star(k, h, seconds, half.vertexCount(), star);
				if (star[1] < 0.02f) {
					continue;
				}
				int i = (int) star[0] * 6;
				float size = 0.15f * star[1];
				float cos = (float) Math.cos(star[2]) * size;
				float sin = (float) Math.sin(star[2]) * size;
				int c = Math.round(star[1] * 255) << 24 | 0xFFFFFF;
				float[][] corners = {{-cos + sin, -sin - cos, 0.5f, 0f}, {cos + sin, sin - cos, 1f, 0f}, {cos - sin, sin + cos, 1f, 1f}, {-cos - sin, -sin + cos, 0.5f, 1f}};
				// Both windings, so it shows from either side whatever the render type culls.
				for (int n = 0; n < 8; n++) {
					float[] q = corners[n < 4 ? n : 7 - n];
					position.transformPosition(g[i] + q[0], g[i + 1] + q[1], g[i + 2] + 0.03f, p);
					sink.accept(p.x, p.y, p.z, c, q[2], q[3]);
				}
			}
		}
	}

	@FunctionalInterface
	private interface VertexSink {
		void accept(float x, float y, float z, float u, float v, float nx, float ny, float nz);
	}

	private static float[][] copy(float[][] source) {
		float[][] out = new float[source.length][];
		for (int i = 0; i < source.length; i++) {
			out[i] = source[i].clone();
		}
		return out;
	}

	/** Both halves as quads (paired triangles, {@link MeshLod#quadIndices}), transformed by the pose. */
	private static void emit(WingModel model, float[][] geometry, Matrix4f position, Matrix3f normal, VertexSink sink) {
		Vector3f p = new Vector3f();
		Vector3f n = new Vector3f();
		for (int h = 0; h < model.halves.length; h++) {
			WingModel.Half half = model.halves[h];
			float[] g = geometry[h];
			int[] quads = MeshLod.quadIndices(half.triangles);
			for (int k = 0; k < quads.length; k++) {
				int v = quads[k];
				int i = v * 6;
				position.transformPosition(g[i], g[i + 1], g[i + 2], p);
				normal.transform(g[i + 3], g[i + 4], g[i + 5], n);
				float length = n.length();
				if (length > 1e-6f) {
					n.div(length);
				}
				sink.accept(p.x, p.y, p.z, half.uv[v * 2], half.uv[v * 2 + 1], n.x, n.y, n.z);
			}
		}
	}
}
