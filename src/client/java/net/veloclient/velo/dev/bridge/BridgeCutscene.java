package net.veloclient.velo.dev.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * A server cutscene: the camera flies a smooth Catmull-Rom curve through the server's points (the same idea as Velo
 * Rewind's camera keyframes), eased in and out, timed by the wall clock so it's smooth at any frame rate. Only the view
 * moves - the player's real position and rotation never change. {@code BridgeShakeMixin} applies {@link #camera()} after
 * vanilla set up the camera; {@link BridgeHud} draws the letterbox and title.
 */
public final class BridgeCutscene {

	record Scene(String id, double[][] points, long start, long millis, boolean letterbox, boolean lock, String title, String subtitle) {
	}

	static volatile Scene scene;
	/** When the last one ended (letterbox bars slide out). */
	static volatile long endedAt;
	static volatile Scene last;

	private BridgeCutscene() {
	}

	static void start(JsonObject m) {
		if (!ServerHudModule.cutscenes()) {
			return;
		}
		JsonArray pts = m.has("points") && m.get("points").isJsonArray() ? m.getAsJsonArray("points") : new JsonArray();
		if (pts.size() < 2 || pts.size() > 200) {
			return;
		}
		double[][] p = new double[pts.size()][5];
		for (int i = 0; i < pts.size(); i++) {
			JsonArray a = pts.get(i).getAsJsonArray();
			for (int k = 0; k < 5; k++) {
				p[i][k] = a.get(k).getAsDouble();
			}
		}
		// unwrap yaw so the camera turns the short way round
		for (int i = 1; i < p.length; i++) {
			double d = p[i][3] - p[i - 1][3];
			while (d > 180) {
				p[i][3] -= 360;
				d -= 360;
			}
			while (d < -180) {
				p[i][3] += 360;
				d += 360;
			}
		}
		long millis = (long) Math.max(1000, Math.min(600_000, m.has("duration") ? m.get("duration").getAsDouble() : 10_000));
		scene = new Scene(str(m, "id"), p, System.currentTimeMillis(), millis, !m.has("letterbox") || m.get("letterbox").getAsBoolean(),
				!m.has("lock") || m.get("lock").getAsBoolean(), str(m, "title"), str(m, "subtitle"));
	}

	static void stop(boolean tellServer) {
		Scene s = scene;
		if (s == null) {
			return;
		}
		scene = null;
		last = s;
		endedAt = System.currentTimeMillis();
		if (tellServer) {
			JsonObject done = new JsonObject();
			done.addProperty("t", "cutscene_done");
			done.addProperty("id", s.id());
			BridgeNet.send(done.toString());
		}
	}

	/** Called every tick: finishes the flight, holds the movement keys while the server locks the player. */
	static void tick() {
		Scene s = scene;
		if (s == null) {
			return;
		}
		if (System.currentTimeMillis() - s.start() >= s.millis()) {
			stop(true);
			return;
		}
		if (s.lock()) {
			//? if <26.1 {
			net.minecraft.client.option.KeyBinding.unpressAll();
			//?} else {
			/*net.minecraft.client.KeyMapping.releaseAll();
			*///?}
		}
	}

	/** 0..1 through the current scene, or -1. */
	static float progress() {
		Scene s = scene;
		return s == null ? -1 : Math.min(1f, (System.currentTimeMillis() - s.start()) / (float) s.millis());
	}

	/** {x, y, z, yaw, pitch} for this frame, or null when no cutscene runs. */
	public static double[] camera() {
		Scene s = scene;
		if (s == null) {
			return null;
		}
		double t = Math.min(1, (System.currentTimeMillis() - s.start()) / (double) s.millis());
		t = t * t * (3 - 2 * t); // ease in and out
		double[][] p = s.points();
		double f = t * (p.length - 1);
		int i = Math.min(p.length - 2, (int) Math.floor(f));
		double u = f - i;
		double[] p0 = p[Math.max(0, i - 1)], p1 = p[i], p2 = p[i + 1], p3 = p[Math.min(p.length - 1, i + 2)];
		double[] out = new double[5];
		for (int k = 0; k < 5; k++) {
			out[k] = catmull(p0[k], p1[k], p2[k], p3[k], u);
		}
		out[4] = Math.max(-90, Math.min(90, out[4]));
		return out;
	}

	private static double catmull(double a, double b, double c, double d, double u) {
		double u2 = u * u, u3 = u2 * u;
		return 0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u2 + (-a + 3 * b - 3 * c + d) * u3);
	}

	private static String str(JsonObject m, String k) {
		return m.has(k) && m.get(k).isJsonPrimitive() ? m.get(k).getAsString() : "";
	}
}
