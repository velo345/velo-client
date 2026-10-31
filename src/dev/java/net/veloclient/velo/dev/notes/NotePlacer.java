package net.veloclient.velo.dev.notes;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.util.Keys;

import java.util.function.Consumer;

/**
 * Moving a note in the world, like holding it in your hand: it follows your crosshair (onto walls, where a fixed note
 * turns flat against the wall, or floating in front of the block you look at), Up / Down makes it bigger or smaller,
 * Left / Right turns it, G switches between "turns to face you" and "fixed", right click puts it down, left click /
 * Backspace cancels. Vanilla never sees those clicks while you hold a note.
 */
public final class NotePlacer {

	private static Note moving;
	private static Note original;
	private static Consumer<Note> placed;
	private static Runnable cancelled;
	private static final boolean[] WAS = new boolean[8];

	private NotePlacer() {
	}

	public static boolean active() {
		return moving != null;
	}

	/** The note being moved (drawn at its live position), or null. */
	public static Note moving() {
		return moving;
	}

	/** Picks the note up; {@code onPlaced} gets it with its new spot, {@code onCancel} runs when you cancel. */
	public static void start(Note note, Consumer<Note> onPlaced, Runnable onCancel) {
		original = note;
		moving = note.copy();
		placed = onPlaced;
		cancelled = onCancel;
		for (int i = 0; i < WAS.length; i++) {
			WAS[i] = true;
		}
		MinecraftClient.getInstance().setScreen(null);
	}

	static void tick() {
		if (moving == null) {
			return;
		}
		if (ClientCompat.currentScreen() != null || MinecraftClient.getInstance().player == null) {
			// Esc (pause menu) or anything else that opens: cancel.
			cancel();
			return;
		}
		follow();
		if (pressed(0, Keys.UP)) {
			moving.size = Math.min(2.5, Math.round((moving.size + 0.1) * 10) / 10.0);
		}
		if (pressed(1, Keys.DOWN)) {
			moving.size = Math.max(0.4, Math.round((moving.size - 0.1) * 10) / 10.0);
		}
		if (pressed(2, Keys.LEFT)) {
			turn(-15);
		}
		if (pressed(3, Keys.RIGHT)) {
			turn(15);
		}
		if (pressed(4, Keys.G)) {
			moving.face = !moving.face;
			if (!moving.face) {
				moving.yaw = playerYaw();
			}
		}
		if (pressed(5, Keys.ENTER) | NotesMc.consumeUse()) {
			Note done = moving;
			moving = null;
			if (placed != null) {
				placed.accept(done);
			}
			return;
		}
		if (pressed(6, Keys.BACKSPACE) | NotesMc.consumeAttack()) {
			cancel();
		}
	}

	private static void cancel() {
		moving = null;
		if (cancelled != null) {
			Runnable run = cancelled;
			cancelled = null;
			MinecraftClient.getInstance().execute(run);
		}
	}

	private static void turn(double degrees) {
		if (moving.face) {
			moving.face = false;
			moving.yaw = playerYaw();
		}
		moving.yaw = ((moving.yaw + degrees) % 360 + 360) % 360;
	}

	private static double playerYaw() {
		double[] pos = net.veloclient.velo.dev.storage.StorageMc.playerPos();
		return pos == null ? 0 : pos[3];
	}

	/** Puts the note where you look. */
	private static void follow() {
		double[] eye = NotesMc.eye();
		if (eye == null) {
			return;
		}
		NotesMc.Aim aim = NotesMc.aim(7);
		if (aim == null) {
			moving.x = eye[0] + eye[3] * 3;
			moving.y = eye[1] + eye[4] * 3;
			moving.z = eye[2] + eye[5] * 3;
			return;
		}
		boolean wall = aim.ny() == 0;
		if (!moving.face && wall) {
			// Flat against the wall, facing out of it.
			moving.x = aim.x() + aim.nx() * 0.04;
			moving.y = aim.y();
			moving.z = aim.z() + aim.nz() * 0.04;
			moving.yaw = Math.toDegrees(Math.atan2(aim.nx(), -aim.nz()));
			return;
		}
		double lift = aim.ny() > 0 ? 0.75 * moving.size : 0;
		moving.x = aim.x() + aim.nx() * 0.55;
		moving.y = aim.y() + aim.ny() * 0.2 + lift;
		moving.z = aim.z() + aim.nz() * 0.55;
	}

	private static boolean pressed(int slot, int key) {
		boolean down = Keys.isKeyDown(key);
		boolean edge = down && !WAS[slot];
		WAS[slot] = down;
		return edge;
	}

	/** The controls, under the crosshair while you hold a note. */
	static void renderHud(DrawContext context) {
		if (moving == null) {
			return;
		}
		var renderer = MinecraftClient.getInstance().textRenderer;
		int sw = context.getScaledWindowWidth();
		int sh = context.getScaledWindowHeight();
		String[] lines = {
				"Right click: put it here   -   Left click: cancel",
				"Up / Down: size " + Math.round(moving.size * 100) + "%   -   Left / Right: turn   -   G: " + (moving.face ? "faces you" : "fixed")};
		int w = 0;
		for (String line : lines) {
			w = Math.max(w, renderer.getWidth(MenuKit.ui(line)));
		}
		int x = sw / 2 - w / 2 - 8;
		int y = sh - 86;
		VeloDraw.fillRounded(context, x, y, w + 16, 30, 8, 0xC0101218);
		for (int i = 0; i < lines.length; i++) {
			var text = MenuKit.ui(lines[i]);
			MenuKit.text(context, renderer, text, sw / 2 - renderer.getWidth(text) / 2, y + 6 + i * 11, i == 0 ? 0xFFFFFFFF : 0xFFB8C0CC);
		}
	}

	static Note original() {
		return original;
	}
}
