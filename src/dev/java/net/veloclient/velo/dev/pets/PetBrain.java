package net.veloclient.velo.dev.pets;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * What a pet is doing and where. Following: a critically damped spring chases a point diagonally behind you with that
 * point's own velocity fed forward (a plain spring trails a few blocks behind at a sprint), the pose blending from the
 * idle loop into the move loop with speed. Standing still it plays its trick, then settles on your shoulder and falls
 * asleep when you go AFK; a kill or a hit play their scenes. Scenes are baked round the player, so they move with you.
 *
 * <p>All positions are relative to the player's render position, in blocks; the reel's pixels are scaled by
 * {@link #PIXEL} (a player-model pixel).
 */
final class PetBrain {

	static final float PIXEL = 0.9375f / 16f;
	private static final float FADE = 0.35f;
	private static final float SEAM = 0.5f;

	final PetModel model;
	final PetPose out;
	private final PetPose from;
	private final PetPose a;
	private final PetPose b;

	private String scene;
	private float sceneT;
	private float fade = 1;
	private float loopT;

	// the follow spring, world coordinates
	private double x;
	private double y;
	private double z;
	private double vx;
	private double vy;
	private double vz;
	private double goalX = Double.NaN;
	private double goalY;
	private double goalZ;
	private float yaw;
	private float yawRate;
	private float roll;

	// the player's motion
	private double lastPx = Double.NaN;
	private double lastPy;
	private double lastPz;
	private double speed;
	private float lastYaw;
	private float still;
	private float afk;
	private float sinceSpecial = 999;
	private boolean riding;

	private final Matrix4f frame = new Matrix4f();
	private final Matrix4f rel = new Matrix4f();
	private final Vector3f vt = new Vector3f();
	private final Quaternionf qt = new Quaternionf();
	private final Vector3f st = new Vector3f();

	PetBrain(PetModel model) {
		this.model = model;
		out = new PetPose(model);
		from = new PetPose(model);
		a = new PetPose(model);
		b = new PetPose(model);
	}

	/**
	 * One render frame. {@code px/py/pz} is the player's render position, {@code bodyYaw} Minecraft degrees. {@code ride}
	 * above 0: you're riding it - it stands under you, facing your way, that many times its size, idling or walking with you.
	 */
	void update(float dt, double px, double py, double pz, float bodyYaw, float headYaw, float size, double ground, PetsSettings settings,
			float ride) {
		dt = Math.max(0.0005f, Math.min(0.1f, dt));
		float playerA = (float) -Math.toRadians(bodyYaw);
		trackPlayer(dt, px, py, pz, headYaw);
		float unit = PIXEL * size;
		if (ride > 0 != riding) {   // getting on or off blends like any other state change
			riding = ride > 0;
			from.copyFrom(out);
			fade = 0;
		}
		if (ride > 0) {
			scene = null;
			goalX = Double.NaN;   // back to following from wherever you get off
			yaw = playerA;
			roll = 0;
			loopT += dt;
			sampleLoop(a, "idle", loopT);
			sampleLoop(b, "move", loopT);
			float k = smooth(((float) speed - 1f) / 4f);
			a.blendTowards(b, k);
			frame.identity().rotateY(playerA).scale(unit * ride);
			fx.clear();
			addFx("idle", loopFrame("idle"), 1 - k);
			addFx("move", loopFrame("move"), k);
			finishPose(dt);
			return;
		}

		// ---- state changes ----
		boolean moving = speed > 0.6;
		if (scene != null) {
			sceneT += dt;
			PetModel.Clip clip = model.clips.get(scene);
			float length = clip.seconds(model.fps);
			boolean holds = "perch".equals(scene) || "sleep".equals(scene);
			if (moving && !"kill".equals(scene) && !"hit".equals(scene)) {
				startFollow(px, py, pz);
			} else if (!holds && sceneT >= length) {
				startFollow(px, py, pz);
			} else if ("perch".equals(scene) && afk > settings.sleepAfter()) {
				startScene("sleep");
			} else if ("sleep".equals(scene) && afk < 0.05f) {
				startScene("wake");
			}
		} else {
			sinceSpecial += dt;
			if (settings.tricks() && still > 5 && sinceSpecial > 25) {
				startScene("special");
				sinceSpecial = 0;
			} else if (settings.tricks() && still > 14) {
				startScene("perch");
			}
		}

		// ---- the follow spring (kept running so leaving a scene is seamless) ----
		double gx = px;
		double gy = py;
		double gz = pz;
		{
			float fx = model.follow[0] * unit;
			float fz = model.follow[2] * unit;
			gx += fx * Math.cos(playerA) + fz * Math.sin(playerA);
			gz += -fx * Math.sin(playerA) + fz * Math.cos(playerA);
			gy = model.flies() ? py + model.follow[1] * unit : (Double.isNaN(ground) ? py : ground);
		}
		if (Double.isNaN(goalX) || Double.isNaN(x) || dist(x, y, z, gx, gy, gz) > 24) {
			x = gx;
			y = gy;
			z = gz;
			vx = vy = vz = 0;
			goalX = gx;
			goalY = gy;
			goalZ = gz;
		}
		double w = 3.4;
		double gvx = (gx - goalX) / dt;
		double gvy = (gy - goalY) / dt;
		double gvz = (gz - goalZ) / dt;
		if (gvx * gvx + gvy * gvy + gvz * gvz > 400) {
			gvx = gvy = gvz = 0;
		}
		goalX = gx;
		goalY = gy;
		goalZ = gz;
		vx += ((gx - x) * w * w + (gvx - vx) * 2 * w) * dt;
		vy += ((gy - y) * w * w + (gvy - vy) * 2 * w) * dt;
		vz += ((gz - z) * w * w + (gvz - vz) * 2 * w) * dt;
		x += vx * dt;
		y += vy * dt;
		z += vz * dt;
		if (!model.flies()) {
			y = gy;
			vy = 0;
		}
		double sp = Math.sqrt(vx * vx + vz * vz + (model.flies() ? vy * vy : 0));
		float spPx = (float) (sp / unit);
		float face = spPx > (model.flies() ? 26 : 10) ? (float) Math.atan2(vx, vz) : playerA + 0.2f;
		float dy = angDiff(yaw, face) * (1 - (float) Math.exp(-dt * (model.flies() ? 4.5 : 7)));
		yaw += dy;
		yawRate += (dy / dt - yawRate) * (1 - (float) Math.exp(-dt * 4));
		roll = model.flies() ? clamp(-yawRate * spPx * 0.006f, -0.85f, 0.85f) : 0;

		// ---- this frame's pose ----
		if (scene == null) {
			loopT += dt;
			sampleLoop(a, "idle", loopT);
			sampleLoop(b, "move", loopT);
			float k = model.flies() ? smooth((spPx - 22) / 40) : smooth((spPx - 6) / 18);
			a.blendTowards(b, k);
			frame.identity().translate((float) (x - px), (float) (y - py), (float) (z - pz)).rotateY(yaw).rotateZ(roll).scale(unit);
			fx.clear();
			addFx("idle", loopFrame("idle"), 1 - k);
			addFx("move", loopFrame("move"), k);
		} else {
			PetModel.Clip clip = model.clips.get(scene);
			float length = clip.seconds(model.fps);
			float t = sceneT;
			if ("perch".equals(scene)) {
				t = tail(t, length, 2.6f);
			} else if ("sleep".equals(scene)) {
				t = tail(t, length, 2.0f);
			}
			sampleScene(a, clip, t, length, "perch".equals(scene) || "sleep".equals(scene) ? ("perch".equals(scene) ? 2.6f : 2.0f) : -1);
			frame.identity().rotateY(playerA).scale(unit);
			fx.clear();
			addFx(scene, (int) (t * model.fps), 1);
		}
		finishPose(dt);
	}

	/** Effects fade, the root into player-relative space and the blend from the previous state. */
	private void finishPose(float dt) {
		// effects fade in with their state
		float fxFade = smooth(Math.min(1, fade + dt / FADE));
		for (FxSource source : fx) {
			source.weight *= fxFade;
		}
		// the root into the player-relative space, so poses from different frames blend
		rel.translationRotateScale(a.rootP[0], a.rootP[1], a.rootP[2], a.rootQ[0], a.rootQ[1], a.rootQ[2], a.rootQ[3], a.rootS[0], a.rootS[1], a.rootS[2]);
		frame.mul(rel, rel);
		rel.getTranslation(vt);
		rel.getUnnormalizedRotation(qt).normalize();
		rel.getScale(st);
		a.rootP[0] = vt.x;
		a.rootP[1] = vt.y;
		a.rootP[2] = vt.z;
		a.rootQ[0] = qt.x;
		a.rootQ[1] = qt.y;
		a.rootQ[2] = qt.z;
		a.rootQ[3] = qt.w;
		a.rootS[0] = st.x;
		a.rootS[1] = st.y;
		a.rootS[2] = st.z;

		fade = Math.min(1, fade + dt / FADE);
		if (fade < 1) {
			from.blendTowards(a, smooth(fade));
			out.copyFrom(from);
		} else {
			out.copyFrom(a);
		}
	}

	/** One clip frame's baked effects, drawn in {@link #frame}'s space (player-relative blocks) at {@code weight}. */
	static final class FxSource {
		final PetModel.Clip clip;
		final int frame;
		float weight;
		final Matrix4f space;

		FxSource(PetModel.Clip clip, int frame, float weight, Matrix4f space) {
			this.clip = clip;
			this.frame = frame;
			this.weight = weight;
			this.space = space;
		}
	}

	/** This frame's effects. */
	final java.util.List<FxSource> fx = new java.util.ArrayList<>();

	private void addFx(String clipName, int frameIndex, float weight) {
		PetModel.Clip clip = model.clips.get(clipName);
		if (clip != null && weight > 0.01f && clip.hasFx()) {
			fx.add(new FxSource(clip, Math.max(0, Math.min(clip.frames - 1, frameIndex)), weight, new Matrix4f(frame)));
		}
	}

	private int loopFrame(String name) {
		PetModel.Clip clip = model.clips.get(name);
		float period = clip.seconds(model.fps) - SEAM;
		return (int) ((loopT % period) * model.fps);
	}

	/** Plays scene {@code name} if the pet has it (never interrupts a kill with a hit or the other way round mid-way). */
	void play(String name) {
		if (model.clips.containsKey(name)) {
			startScene(name);
		}
	}

	boolean busyWith(String name) {
		return name.equals(scene);
	}

	private void startScene(String name) {
		from.copyFrom(out);
		fade = 0;
		scene = name;
		sceneT = 0;
	}

	private void startFollow(double px, double py, double pz) {
		from.copyFrom(out);
		fade = 0;
		scene = null;
		// pick the spring up where the pet is now
		x = px + out.rootP[0];
		y = model.flies() ? py + out.rootP[1] : y;
		z = pz + out.rootP[2];
		vx = vy = vz = 0;
		still = 0;
	}

	private void trackPlayer(float dt, double px, double py, double pz, float headYaw) {
		if (!Double.isNaN(lastPx)) {
			double d = Math.sqrt((px - lastPx) * (px - lastPx) + (pz - lastPz) * (pz - lastPz) + (py - lastPy) * (py - lastPy) * 0.3) / dt;
			speed += (d - speed) * (1 - Math.exp(-dt * 8));
		}
		boolean turned = Math.abs(angDiff((float) Math.toRadians(lastYaw), (float) Math.toRadians(headYaw))) > 0.02f;
		still = speed > 0.6 ? 0 : still + dt;
		afk = speed > 0.6 || turned ? 0 : afk + dt;
		lastPx = px;
		lastPy = py;
		lastPz = pz;
		lastYaw = headYaw;
	}

	private void sampleLoop(PetPose into, String name, float t) {
		PetModel.Clip clip = model.clips.get(name);
		float length = clip.seconds(model.fps);
		float period = length - SEAM;
		float u = t % period;
		into.sample(model, clip, u * model.fps);
		if (u < SEAM) {   // the seam: the loop's start fades in over its end
			b2().sample(model, clip, (u + period) * model.fps);
			b2().blendTowards(into, u / SEAM);
			into.copyFrom(b2());
		}
	}

	private void sampleScene(PetPose into, PetModel.Clip clip, float t, float length, float tailStart) {
		into.sample(model, clip, t * model.fps);
		if (tailStart >= 0 && sceneT > length) {
			float period = length - tailStart - SEAM;
			float u = (sceneT - length) % period;
			if (u < SEAM) {
				b2().sample(model, clip, (tailStart + u + period) * model.fps);
				b2().blendTowards(into, u / SEAM);
				into.copyFrom(b2());
			}
		}
	}

	private PetPose seam;

	private PetPose b2() {
		if (seam == null) {
			seam = new PetPose(model);
		}
		return seam;
	}

	/** Scene time for a clip that holds: plays through once, then loops its end from {@code start}. */
	private float tail(float t, float length, float start) {
		if (t < length) {
			return t;
		}
		float period = length - start - SEAM;
		return start + ((t - length) % period);
	}

	private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
		return Math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by) + (az - bz) * (az - bz));
	}

	private static float angDiff(float from, float to) {
		float d = (float) ((to - from) % (Math.PI * 2));
		if (d > Math.PI) {
			d -= (float) (Math.PI * 2);
		}
		if (d < -Math.PI) {
			d += (float) (Math.PI * 2);
		}
		return d;
	}

	private static float smooth(float t) {
		t = clamp(t, 0, 1);
		return t * t * (3 - 2 * t);
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
