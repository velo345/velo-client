package net.veloclient.velo.dev.notes;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.dev.storage.StorageMc;
import org.joml.Matrix3x2f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes notes float in the world: each card is projected from its spot to the screen - perspective size, a slow
 * hover, turned to face you or fixed like a sign on a wall (then it's drawn skewed with the wall). Only notes you can
 * actually see are drawn (a ray from your eyes, refreshed a few times a second) - nothing shows through walls. Far
 * away nothing shows unless "far notes as pins" is on; the one under your crosshair gets an outline and an "O" hint.
 */
public final class NotesRenderer {

	/** Card width in blocks at size 1. */
	private static final double WORLD_W = 1.6;
	private static final double PIN_DISTANCE = 22;
	private static final Map<String, long[]> SIGHT = new HashMap<>();
	private static Note aimed;

	private NotesRenderer() {
	}

	static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, Identifier.of("velo-client", "dev_world_notes"),
				(context, tickCounter) -> render(context));
	}

	/** The note under your crosshair (within reach), or null. */
	public static Note aimed() {
		return aimed;
	}

	private record Placed(Note note, double distance) {
	}

	private static void render(DrawContext context) {
		aimed = null;
		if (!NotesModule.active() || !StorageMc.inWorld()) {
			return;
		}
		NotePlacer.renderHud(context);
		if (!NotesModule.showInWorld() && !NotePlacer.active()) {
			return;
		}
		double[] eye = NotesMc.eye();
		if (eye == null) {
			return;
		}
		List<Placed> placed = new ArrayList<>();
		Note moving = NotePlacer.moving();
		List<Note> notes = new ArrayList<>(NoteStore.here());
		if (moving != null) {
			notes.removeIf(n -> n.id.equals(moving.id));
			notes.add(moving);
		}
		double max = NotesModule.distance();
		for (Note note : notes) {
			if (!note.mine && !NotesModule.showOthers()) {
				continue;
			}
			double dx = note.x - eye[0];
			double dy = note.y - eye[1];
			double dz = note.z - eye[2];
			double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (distance > max || distance < 0.4 || distance > PIN_DISTANCE && !NotesModule.farPins() && note != moving) {
				continue;
			}
			if (note != moving && (!note.showsHere() || !visible(note))) {
				continue;
			}
			placed.add(new Placed(note, distance));
		}
		// Far ones first so near cards cover them.
		placed.sort(Comparator.comparingDouble(Placed::distance).reversed());
		int sw = context.getScaledWindowWidth();
		int sh = context.getScaledWindowHeight();
		Note best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Placed p : placed) {
			boolean hit = p.distance() > PIN_DISTANCE ? drawPin(context, p.note(), p.distance(), sw, sh) : drawCard(context, p.note(), p.distance(), sw, sh);
			if (hit && p.distance() < 10 && p.distance() < bestDistance && p.note() != moving) {
				best = p.note();
				bestDistance = p.distance();
			}
		}
		aimed = best;
	}

	/** Line of sight to the note's centre, cached ~200 ms per note. */
	private static boolean visible(Note note) {
		long now = System.currentTimeMillis();
		long[] cached = SIGHT.get(note.id);
		if (cached != null && now - cached[0] < 200) {
			return cached[1] == 1;
		}
		boolean sees = NotesMc.canSee(note.x, note.y + bob(note), note.z);
		SIGHT.put(note.id, new long[] {now, sees ? 1 : 0});
		if (SIGHT.size() > 512) {
			SIGHT.clear();
		}
		return sees;
	}

	private static double bob(Note note) {
		if (!note.face) {
			return 0;
		}
		return Math.sin(System.currentTimeMillis() / 900.0 + (note.id.hashCode() & 0xFF)) * 0.05;
	}

	/** Draws the full card; true when the crosshair is on it. */
	private static boolean drawCard(DrawContext context, Note note, double distance, int sw, int sh) {
		int h = NoteCard.height(note, false);
		double size = WORLD_W * note.size * NotesModule.scale();
		double worldH = size * h / NoteCard.W;
		double cy = note.y + bob(note);
		// The card's right / up directions in the world.
		double rx, rz;
		if (note.face) {
			double[] eye = NotesMc.eye();
			double dx = note.x - eye[0];
			double dz = note.z - eye[2];
			double len = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
			// Your right-hand side when looking at it.
			rx = -dz / len;
			rz = dx / len;
		} else {
			double yaw = Math.toRadians(note.yaw);
			rx = -Math.cos(yaw);
			rz = -Math.sin(yaw);
			// From behind, mirror it so it still reads (a note, not a one-sided sign).
			double[] eye = NotesMc.eye();
			double nx = Math.sin(yaw);
			double nz = -Math.cos(yaw);
			if ((eye[0] - note.x) * nx + (eye[2] - note.z) * nz < 0) {
				rx = -rx;
				rz = -rz;
			}
		}
		double hw = size / 2;
		double hh = worldH / 2;
		// The card's centre and edge midpoints, projected. A 2D transform can't do perspective, so it's fitted through
		// the centre (not a corner): up close the error spreads evenly instead of shearing the far corner sideways.
		float[] c = screen(note.x, cy, note.z, sw, sh);
		float[] l = screen(note.x - rx * hw, cy, note.z - rz * hw, sw, sh);
		float[] r = screen(note.x + rx * hw, cy, note.z + rz * hw, sw, sh);
		float[] t = screen(note.x, cy + hh, note.z, sw, sh);
		float[] b = screen(note.x, cy - hh, note.z, sw, sh);
		if (c == null || l == null || r == null || t == null || b == null) {
			return false;
		}
		// Local card space (0..W, 0..h) onto the projected card.
		float ax = (r[0] - l[0]) / NoteCard.W;
		float ay = (r[1] - l[1]) / NoteCard.W;
		float bx = (b[0] - t[0]) / h;
		float by = (b[1] - t[1]) / h;
		if (Math.abs(ax) + Math.abs(ay) > 40 || Math.abs(ax * by - ay * bx) < 1e-5f) {
			return false;
		}
		float[] tl = {c[0] - ax * NoteCard.W / 2f - bx * h / 2f, c[1] - ay * NoteCard.W / 2f - by * h / 2f};
		int alpha = fadeFor(distance);
		var matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.mul(new Matrix3x2f(ax, ay, bx, by, tl[0], tl[1]));
		NoteCard.draw(context, note, false, false, alpha);
		// Crosshair inside the card?
		float det = ax * by - ay * bx;
		float px = sw / 2f - tl[0];
		float py = sh / 2f - tl[1];
		float lx = (px * by - py * bx) / det;
		float ly = (ax * py - ay * px) / det;
		boolean hit = lx >= 0 && lx <= NoteCard.W && ly >= 0 && ly <= h;
		if (hit && distance < 10 || note.unread) {
			NoteCard.glow(context, note, h, alpha);
		}
		matrices.popMatrix();
		if (hit && distance < 10 && note != NotePlacer.moving()) {
			hint(context, c[0], c[1] + Math.abs(by) * h / 2f + Math.abs(ay) * NoteCard.W / 2f + 6, note);
		}
		return hit;
	}

	/** Far away: a small pin with the note's colour, icon and title. */
	private static boolean drawPin(DrawContext context, Note note, double distance, int sw, int sh) {
		float[] at = screen(note.x, note.y + bob(note), note.z, sw, sh);
		if (at == null) {
			return false;
		}
		var renderer = MinecraftClient.getInstance().textRenderer;
		int alpha = fadeFor(distance);
		var title = MenuKit.fit(renderer, MenuKit.uiBold(note.displayTitle()), 90);
		int w = renderer.getWidth(title) + (note.icon != null ? 30 : 16);
		int x = Math.round(at[0]) - w / 2;
		int y = Math.round(at[1]) - 7;
		VeloDraw.fillRounded(context, x, y, w, 14, 7, NoteCard.fade(0xC0101218, alpha));
		VeloDraw.fillCircle(context, x + 7, y + 7, 3, NoteCard.fade(note.color | 0xFF000000, alpha));
		int tx = x + 13;
		if (note.icon != null) {
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(tx, y + 1);
			context.getMatrices().scale(0.75f, 0.75f);
			StorageMc.drawItem(context, StorageMc.stack(note.icon, 1), 0, 0, "");
			context.getMatrices().popMatrix();
			tx += 14;
		}
		MenuKit.text(context, renderer, title, tx, y + 3, NoteCard.fade(0xFFF2F4F8, alpha));
		if (note.unread) {
			VeloDraw.fillCircle(context, x + w - 2, y + 2, 3, NoteCard.fade(0xFFFF5D73, alpha));
		}
		return false;
	}

	private static void hint(DrawContext context, float cx, float y, Note note) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		String key = NotesModule.OPEN_KEY.getBoundKeyLocalizedText().getString();
		var label = MenuKit.ui("[" + key + "] " + (note.canEdit ? "Open" : "Read"));
		int w = renderer.getWidth(label) + 10;
		int x = Math.round(cx) - w / 2;
		int top = Math.round(y);
		VeloDraw.fillRounded(context, x, top, w, 13, 6, 0xC0101218);
		MenuKit.text(context, renderer, label, x + 5, top + 3, 0xFFF2F4F8);
	}

	/** Fades in at the edge of the draw distance. */
	private static int fadeFor(double distance) {
		// Without far pins the cards fade out before they'd turn into pins.
		double max = NotesModule.farPins() ? NotesModule.distance() : Math.min(NotesModule.distance(), PIN_DISTANCE);
		double t = (max - distance) / Math.max(1, max * 0.25);
		return (int) Math.round(255 * Math.max(0.0, Math.min(1, t)));
	}

	private static float[] screen(double x, double y, double z, int sw, int sh) {
		float[] view = ClientCompat.cameraView(x, y, z);
		return ClientCompat.project(view, sw, sh);
	}
}
