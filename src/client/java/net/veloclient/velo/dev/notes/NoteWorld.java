package net.veloclient.velo.dev.notes;

import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * World Notes as real things in the world, like signs or text displays: each card is recorded once per frame through
 * {@link NoteCard} (the same layout the editor and list show) and drawn as geometry - shapes and pictures as quads,
 * text the way signs draw theirs - in the world pass. Everything goes through the text render type: it blends like the
 * translucent entity types but keeps the order things were drawn in (those sort every quad by distance, which put a
 * card's background over its own whiteboard). The game's own camera then does perspective, FOV, view bobbing
 * and depth, so a card never slides or skews while you turn, and walls hide it like any block.
 *
 * <p>Before, cards were projected onto the HUD and fitted with a 2D transform: no real perspective (wall cards sheared
 * at an angle), a slightly different camera than the world's (they swam when sprinting or bobbing), and tiny far text
 * snapping between pixels as you turned.
 */
final class NoteWorld {

	private static final Identifier WHITE = Identifier.of("velo-client", "textures/notes/white.png");
	private static final int FULL_BRIGHT = 0xF000F0;

	/** One recorded draw call, in card units (0,0 = top left, {@link NoteCard#W} wide, y down). */
	private sealed interface Op {
	}

	private record Shapes(float[] xy, int[] colors, int count) implements Op {
	}

	private record Label(Text text, int x, int y, int color, boolean shadow) implements Op {
	}

	private record Picture(Identifier texture, int x, int y, int w, int h, int alpha) implements Op {
	}

	private static List<Op> recording;
	private static final Map<String, Identifier> ITEM_TEXTURES = new HashMap<>();

	private NoteWorld() {
	}

	static boolean recording() {
		return recording != null;
	}

	static void text(Text text, int x, int y, int color, boolean shadow) {
		recording.add(new Label(text, x, y, color, shadow));
	}

	static void texture(Identifier texture, int x, int y, int w, int h, int alpha) {
		recording.add(new Picture(texture, x, y, w, h, alpha));
	}

	/** The note's item icon: the item's own texture as a flat picture (block items without one are left out). */
	static void item(String itemId, int x, int y, int alpha) {
		Identifier texture = ITEM_TEXTURES.computeIfAbsent(itemId == null ? "" : itemId, NoteWorld::itemTexture);
		if (texture != null) {
			recording.add(new Picture(texture, x + 1, y + 1, 14, 14, alpha));
		}
	}

	private static Identifier itemTexture(String itemId) {
		try {
			String full = itemId.contains(":") ? itemId : "minecraft:" + itemId;
			//? if <26.1 {
			Identifier id = Identifier.of(full);
			//?} else {
			/*Identifier id = Identifier.parse(full);
			*///?}
			Identifier texture = Identifier.of(id.getNamespace(), "textures/item/" + id.getPath() + ".png");
			//? if <26.1 {
			boolean exists = net.minecraft.client.MinecraftClient.getInstance().getResourceManager().getResource(texture).isPresent();
			//?} else {
			/*boolean exists = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
			*///?}
			return exists ? texture : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** A card ready to draw: its recorded parts and where it stands. */
	record Card(Note note, List<Op> ops, int height, double cx, double cy, double cz, double rx, double rz, double scale, double distance) {
	}

	/** A card's recorded parts, kept while nothing that changes its look changes (see {@link #build}). */
	private record Recorded(long fingerprint, int alpha, int detail, List<Op> ops, int height) {
	}

	private static final Map<Note, Recorded> RECORDED = new java.util.IdentityHashMap<>();

	/**
	 * Records {@code note} as the card the world pass will draw ({@code glow}: the aimed-at / unread outline).
	 *
	 * <p>Recording a card (layout, text, every rounded shape tessellated) used to happen for every card every frame -
	 * about 10% of the render thread with a few cards in view (JFR, 2026-11-06). Now a card is recorded again only when
	 * its content, fade or detail level changes; where it stands and its bobbing are only the matrix. Corners get as many
	 * segments as the card's size on screen needs. Neon cards pulse, so they stay live; the glow outline pulses too and
	 * is recorded on top each frame.
	 */
	static Card build(Note note, double cx, double cy, double cz, double rx, double rz, double scale, double distance, int alpha, boolean glow) {
		// Screen pixels per card unit: card unit = scale blocks; at 70 degrees FOV one block at distance d spans
		// about height * 0.714 / d pixels. Rounded to powers of two so walking doesn't re-record all the time.
		double height = windowHeight();
		double pixelsPerUnit = scale * height * 0.714 / Math.max(0.5, distance);
		int detail = (int) Math.max(0, Math.min(5, Math.round(Math.log(Math.max(0.125, pixelsPerUnit) * 8) / Math.log(2))));
		float segmentScale = (float) Math.pow(2, detail) / 8f;
		long fingerprint = fingerprint(note);
		boolean live = "neon".equals(note.style);
		Recorded cached = live ? null : RECORDED.get(note);
		List<Op> ops;
		int h;
		if (cached != null && cached.fingerprint() == fingerprint && cached.alpha() == alpha && cached.detail() == detail) {
			ops = cached.ops();
			h = cached.height();
		} else {
			ops = new ArrayList<>();
			h = record(ops, segmentScale, () -> NoteCard.record(note, alpha));
			if (!live) {
				if (RECORDED.size() > 128) {
					RECORDED.clear();
				}
				RECORDED.put(note, new Recorded(fingerprint, alpha, detail, ops, h));
			}
		}
		if (glow) {
			List<Op> withGlow = new ArrayList<>(ops);
			int cardHeight = h;
			record(withGlow, segmentScale, () -> {
				NoteCard.glow(null, note, cardHeight, alpha);
				return cardHeight;
			});
			ops = withGlow;
		}
		return new Card(note, ops, h, cx, cy, cz, rx, rz, scale, distance);
	}

	private static int record(List<Op> into, float pixelsPerUnit, java.util.function.IntSupplier body) {
		recording = into;
		VeloDraw.capture((xy, colors, count) -> into.add(new Shapes(java.util.Arrays.copyOf(xy, count * 8), java.util.Arrays.copyOf(colors, count * 4), count)),
				0.45f, pixelsPerUnit);
		try {
			return body.getAsInt();
		} finally {
			VeloDraw.capture(null, 0.5f);
			recording = null;
		}
	}

	/** Everything a card's look depends on (besides fade and detail), hashed. Strings cache their hash, so this is cheap. */
	private static long fingerprint(Note note) {
		long h = note.version * 31L + note.updated;
		h = h * 31 + java.util.Objects.hashCode(note.title);
		h = h * 31 + java.util.Objects.hashCode(note.text);
		h = h * 31 + java.util.Objects.hashCode(note.style);
		h = h * 31 + note.color;
		h = h * 31 + java.util.Objects.hashCode(note.icon);
		h = h * 31 + java.util.Objects.hashCode(note.ownerName);
		h = h * 31 + java.util.Objects.hashCode(note.editorName);
		h = h * 31 + java.util.Objects.hashCode(note.share);
		h = h * 31 + note.people.size();
		h = h * 31 + note.image;
		h = h * 31 + java.util.Objects.hashCode(note.png);
		h = h * 31 + System.identityHashCode(note.board) * 7L + note.board.size();
		for (Note.Check check : note.checks) {
			h = h * 31 + java.util.Objects.hashCode(check.text) * 2L + (check.done ? 1 : 0);
		}
		h = h * 31 + (Note.imagesHere() ? 1 : 0) + (Note.whiteboardsHere() ? 2 : 0);
		return h;
	}

	private static double windowHeight() {
		//? if <26.1 {
		return Math.max(1, net.minecraft.client.MinecraftClient.getInstance().getWindow().getFramebufferHeight());
		//?} else {
		/*return Math.max(1, net.minecraft.client.Minecraft.getInstance().getWindow().getHeight());
		*///?}
	}

	/**
	 * Where the view ray from {@code eye} along {@code dir} meets the card, in card units ({x, y}), or null when it
	 * misses (behind you, parallel, or off the card).
	 */
	static float[] hit(Card card, double[] eye, double[] dir) {
		double nx = -card.rz();
		double nz = card.rx();
		double denom = dir[0] * nx + dir[2] * nz;
		if (Math.abs(denom) < 1e-6) {
			return null;
		}
		double t = ((card.cx() - eye[0]) * nx + (card.cz() - eye[2]) * nz) / denom;
		if (t <= 0) {
			return null;
		}
		double px = eye[0] + dir[0] * t - card.cx();
		double py = eye[1] + dir[1] * t - card.cy();
		double pz = eye[2] + dir[2] * t - card.cz();
		float lx = (float) ((px * card.rx() + pz * card.rz()) / card.scale() + NoteCard.W / 2.0);
		float ly = (float) (-py / card.scale() + card.height() / 2.0);
		return lx >= 0 && lx <= NoteCard.W && ly >= 0 && ly <= card.height() ? new float[] {lx, ly} : null;
	}

	// ---------------------------------------------------------------- the world pass

	/** Card-to-camera transform: camera-relative position, turned so its right side is (rx, rz), y down, card units. */
	private static Matrix4f cardMatrix(Card card, double[] camera) {
		float theta = (float) Math.atan2(-card.rz(), card.rx());
		float s = (float) card.scale();
		return new Matrix4f()
				.translate((float) (card.cx() - camera[0]), (float) (card.cy() - camera[1]), (float) (card.cz() - camera[2]))
				.rotateY(theta)
				.scale(s, -s, s)
				.translate(-NoteCard.W / 2f, -card.height() / 2f, 0f);
	}

	/**
	 * Gap between stacked parts of one card, in card units - large enough for the depth buffer at that distance
	 * (about 2 mm close up, more far away), small enough that the card still looks flat.
	 */
	private static float layerGap(Card card) {
		double world = Math.max(0.0015, card.distance() * 0.00012);
		return (float) (world / card.scale());
	}

	@FunctionalInterface
	private interface Corner {
		void accept(float x, float y, float z, int color, float u, float v);
	}

	private static void emitShapes(Shapes shapes, Matrix4f pose, float z, Corner out) {
		Vector3f p = new Vector3f();
		for (int q = 0; q < shapes.count(); q++) {
			for (int k = 0; k < 4; k++) {
				int i = q * 4 + k;
				pose.transformPosition(shapes.xy()[i * 2], shapes.xy()[i * 2 + 1], z, p);
				out.accept(p.x, p.y, p.z, shapes.colors()[i], 0.5f, 0.5f);
			}
		}
	}

	private static void emitPicture(Picture picture, Matrix4f pose, float z, Corner out) {
		Vector3f p = new Vector3f();
		int color = (Math.max(0, Math.min(255, picture.alpha())) << 24) | 0xFFFFFF;
		float x0 = picture.x();
		float y0 = picture.y();
		float x1 = x0 + picture.w();
		float y1 = y0 + picture.h();
		float[][] corners = {{x0, y0, 0, 0}, {x0, y1, 0, 1}, {x1, y1, 1, 1}, {x1, y0, 1, 0}};
		for (float[] c : corners) {
			pose.transformPosition(c[0], c[1], z, p);
			out.accept(p.x, p.y, p.z, color, c[2], c[3]);
		}
	}

	static double[] cameraPosition() {
		//? if <26.1 {
		var position = net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
		//?} else if <26.2 {
		/*var position = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().position();
		*///?} else {
		/*var position = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position();
		*///?}
		return new double[] {position.x, position.y, position.z};
	}

	//? if <26.1 {
	static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			if (failed) {
				return;
			}
			try {
				submit(context.matrices(), context.commandQueue());
			} catch (RuntimeException | LinkageError e) {
				fail(e);
			}
		});
	}

	private static void submit(net.minecraft.client.util.math.MatrixStack matrices, net.minecraft.client.render.command.OrderedRenderCommandQueue queue) {
		List<Card> cards = NotesRenderer.worldCards();
		if (cards.isEmpty()) {
			return;
		}
		double[] camera = cameraPosition();
		int overlay = net.minecraft.client.render.OverlayTexture.DEFAULT_UV;
		for (Card card : cards) {
			Matrix4f local = cardMatrix(card, camera);
			float gap = layerGap(card);
			int layer = 0;
			for (Op op : card.ops()) {
				float z = gap * layer++;
				switch (op) {
					case Shapes shapes -> queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.text(WHITE),
							(entry, consumer) -> {
								Matrix4f pose = new Matrix4f(entry.getPositionMatrix()).mul(local);
								emitShapes(shapes, pose, z, (x, y, zz, color, u, v) -> consumer.vertex(x, y, zz, color, u, v, overlay, FULL_BRIGHT, 0, 1, 0));
							});
					case Picture picture -> queue.submitCustom(matrices, net.minecraft.client.render.RenderLayers.text(picture.texture()),
							(entry, consumer) -> {
								Matrix4f pose = new Matrix4f(entry.getPositionMatrix()).mul(local);
								emitPicture(picture, pose, z, (x, y, zz, color, u, v) -> consumer.vertex(x, y, zz, color, u, v, overlay, FULL_BRIGHT, 0, 1, 0));
							});
					case Label label -> {
						matrices.push();
						matrices.multiplyPositionMatrix(new Matrix4f(local).translate(0f, 0f, z));
						queue.submitText(matrices, label.x(), label.y(), label.text().asOrderedText(), label.shadow(),
								net.minecraft.client.font.TextRenderer.TextLayerType.POLYGON_OFFSET, FULL_BRIGHT, label.color(), 0, 0);
						matrices.pop();
					}
				}
			}
		}
	}
	//?} else {
	/*static void register() {
		net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (failed) {
				return;
			}
			try {
				submit(context.poseStack(), context.submitNodeCollector());
			} catch (RuntimeException | LinkageError e) {
				fail(e);
			}
		});
	}

	private static void submit(com.mojang.blaze3d.vertex.PoseStack matrices, net.minecraft.client.renderer.SubmitNodeCollector queue) {
		List<Card> cards = NotesRenderer.worldCards();
		if (cards.isEmpty()) {
			return;
		}
		double[] camera = cameraPosition();
		int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;
		for (Card card : cards) {
			Matrix4f local = cardMatrix(card, camera);
			float gap = layerGap(card);
			int layer = 0;
			for (Op op : card.ops()) {
				float z = gap * layer++;
				switch (op) {
					case Shapes shapes -> queue.submitCustomGeometry(matrices,
							net.minecraft.client.renderer.rendertype.RenderTypes.text(WHITE),
							(pose2, consumer) -> {
								Matrix4f pose = new Matrix4f(pose2.pose()).mul(local);
								emitShapes(shapes, pose, z, (x, y, zz, color, u, v) -> consumer.addVertex(x, y, zz, color, u, v, overlay, FULL_BRIGHT, 0, 1, 0));
							});
					case Picture picture -> queue.submitCustomGeometry(matrices,
							net.minecraft.client.renderer.rendertype.RenderTypes.text(picture.texture()),
							(pose2, consumer) -> {
								Matrix4f pose = new Matrix4f(pose2.pose()).mul(local);
								emitPicture(picture, pose, z, (x, y, zz, color, u, v) -> consumer.addVertex(x, y, zz, color, u, v, overlay, FULL_BRIGHT, 0, 1, 0));
							});
					case Label label -> {
						matrices.pushPose();
						matrices.mulPose(new Matrix4f(local).translate(0f, 0f, z));
						queue.submitText(matrices, label.x(), label.y(), label.text().getVisualOrderText(), label.shadow(),
								net.minecraft.client.gui.Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, label.color(), 0, 0);
						matrices.popPose();
					}
				}
			}
		}
	}
	*///?}

	private static boolean failed;

	/** A drawing error turns the world cards off for the session instead of breaking every frame. */
	private static void fail(Throwable t) {
		if (!failed) {
			failed = true;
			net.veloclient.velo.VeloClient.LOGGER.error("World Notes couldn't draw in the world - back to the HUD cards for this session", t);
		}
	}

	static boolean broken() {
		return failed;
	}
}
