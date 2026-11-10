package net.veloclient.launcher.sandbox;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import net.veloclient.launcher.sandbox.Actors.Mob;
import net.veloclient.launcher.sandbox.Actors.Proj;

/**
 * Pixel-drawn monsters, bosses and projectiles (drawn live with rectangles on the screen's pixel grid, so they can
 * squash, flap, glow and telegraph their moves).
 */
final class Sprites {

	private Sprites() {
	}

	private static void px(GraphicsContext g, double x, double y, double w, double h, double s) {
		g.fillRect(Math.round(x * s), Math.round(y * s), Math.ceil(w * s), Math.ceil(h * s));
	}

	static void glow(GraphicsContext g, double cx, double cy, double r, Color color) {
		// plain alpha blending: SCREEN on a JavaFX canvas made the glow see-through (the launcher showed in it)
		g.setGlobalBlendMode(BlendMode.SRC_OVER);
		g.setFill(new RadialGradient(0, 0, cx, cy, r, false, CycleMethod.NO_CYCLE, new Stop(0, color), new Stop(1, Color.TRANSPARENT)));
		g.fillRect(cx - r, cy - r, r * 2, r * 2);
		g.setGlobalBlendMode(BlendMode.SRC_OVER);
	}

	/** Draws a monster with its feet at screen (x, y); {@code s} = screen pixels per art pixel. */
	static void mob(GraphicsContext g, Mob m, double x, double y, double s, double t) {
		boolean white = m.hurt > 0;
		g.save();
		g.translate(Math.round(x), Math.round(y));
		if (m.facing < 0) {
			g.scale(-1, 1);
		}
		g.setFill(Color.rgb(0, 0, 0, 0.25));
		g.fillOval(-m.halfW() * 16 * s, -1.5 * s, m.halfW() * 32 * s, 3 * s);
		switch (m.kind) {
			case Actors.WARDEN, Actors.GOLEM, Actors.HERALD -> {
			}
			case Actors.VOID_CRYSTAL -> {
				crystal(g, m, s, white, t);
				g.restore();
				return;
			}
			default -> {
				art(g, m, s, white, t);
				g.restore();
				return;
			}
		}
		switch (m.kind) {
			case Actors.WARDEN -> warden(g, m, s, white, t);
			case Actors.GOLEM -> golem(g, m, s, white, t);
			case Actors.HERALD -> herald(g, m, s, white, t);
			default -> {
			}
		}
		g.restore();
	}

	/** The shaded pixel-art monsters (MobArt), picking the animation frame from what the monster is doing. */
	private static void art(GraphicsContext g, Mob m, double s, boolean white, double t) {
		int frame;
		switch (m.kind) {
			case Actors.SLIME, Actors.CRYPT_SLIME, Actors.MAGMA_SLIME -> frame = !m.onGround ? 2 : m.timer < 0.3 ? 0 : 1;
			case Actors.REVENANT, Actors.SKELETON, Actors.STALKER, Actors.BANDIT, Actors.BANDIT_ARCHER ->
					frame = Math.abs(m.vx) > 0.3 ? 1 + ((int) (m.anim * 7)) % 2 : 0;
			case Actors.VILLAGER -> frame = (Math.abs(m.vx) > 0.2 ? 1 + ((int) (m.anim * 6)) % 2 : 0) + Math.floorMod(m.id, 3) * 10;
			case Actors.SCUTTLER -> frame = Math.abs(m.vx) > 0.3 ? ((int) (m.anim * 12)) % 2 : 0;
			case Actors.TRADER -> frame = ((int) (m.anim * 2.5)) % 2;
			default -> frame = ((int) (m.anim * 9)) % 2;
		}
		switch (m.kind) {
			case Actors.MAGMA_SLIME, Actors.IMP -> glow(g, 0, -10 * s, 18 * s, Color.rgb(255, 120, 40, 0.28));
			case Actors.FROST_WISP -> glow(g, 0, -12 * s, 16 * s, Color.rgb(160, 220, 255, 0.35));
			case Actors.VOID_WISP, Actors.STALKER -> glow(g, 0, -14 * s, 20 * s, Color.rgb(150, 90, 255, 0.32));
			case Actors.TRADER -> glow(g, 7 * s, -14 * s, 26 * s, Color.rgb(255, 190, 110, 0.35 + 0.05 * Math.sin(t * 6)));
			default -> {
			}
		}
		Image img = MobArt.frame(m.kind, frame, false);
		double w = img.getWidth() * s;
		double h = img.getHeight() * s;
		double lift = switch (m.kind) {
			case Actors.BAT, Actors.FROST_WISP, Actors.VOID_WISP, Actors.IMP -> -Math.round(Math.sin(m.anim * 4) * 1.5) * s;
			default -> 0;
		};
		if (m.kind == Actors.STALKER && m.tele > 0) {
			g.setGlobalAlpha(0.4);
		}
		g.drawImage(img, Math.round(-w / 2), Math.round(-h + lift), w, h);
		if (white) {
			g.setGlobalAlpha(0.85);
			g.drawImage(MobArt.frame(m.kind, frame, true), Math.round(-w / 2), Math.round(-h + lift), w, h);
		}
		g.setGlobalAlpha(1);
	}

	private static void crystal(GraphicsContext g, Mob m, double s, boolean white, double t) {
		glow(g, 0, -10 * s, 22 * s, Color.rgb(160, 110, 255, 0.4 + 0.15 * Math.sin(t * 5 + m.id)));
		double[][] shards = {{0, 22, 4}, {-4, 14, 3}, {4, 16, 3}};
		for (double[] sh : shards) {
			double h = sh[1];
			double w = sh[2];
			for (int y = 0; y < h; y++) {
				double half = w * (1 - Math.abs(y - h * 0.55) / (h * 0.55));
				for (int x = (int) -Math.ceil(half); x <= (int) Math.ceil(half); x++) {
					Color c = white ? Color.WHITE : x < 0 ? Color.web("#EADCFF") : x == 0 ? Color.web("#B48CFF") : Color.web("#6E3FD0");
					g.setFill(c);
					g.fillRect(Math.round((sh[0] + x) * s), Math.round((-y - 2) * s), Math.ceil(s), Math.ceil(s));
				}
			}
		}
		g.setFill(Color.web("#FFFFFF"));
		g.fillRect(Math.round(-1 * s), Math.round(-16 * s), s, 2 * s);
	}

	/** A shaded block for the bosses' armour and bodies: outline, lit top/left, shadowed bottom/right. */
	private static void box(GraphicsContext g, double x, double y, double w, double h, double s) {
		javafx.scene.paint.Paint fill = g.getFill();
		Color c = fill instanceof Color col ? col : Color.GRAY;
		g.setFill(c.deriveColor(0, 1, 0.35, 1));
		g.fillRect(Math.round((x - 1) * s), Math.round((y - 1) * s), Math.ceil((w + 2) * s), Math.ceil((h + 2) * s));
		g.setFill(c);
		g.fillRect(Math.round(x * s), Math.round(y * s), Math.ceil(w * s), Math.ceil(h * s));
		g.setFill(c.deriveColor(0, 0.9, 1.3, 1));
		g.fillRect(Math.round(x * s), Math.round(y * s), Math.ceil(w * s), Math.ceil(s));
		g.fillRect(Math.round(x * s), Math.round(y * s), Math.ceil(s), Math.ceil(h * s));
		g.setFill(c.deriveColor(0, 1, 0.7, 1));
		g.fillRect(Math.round(x * s), Math.round((y + h - 1) * s), Math.ceil(w * s), Math.ceil(s));
		g.fillRect(Math.round((x + w - 1) * s), Math.round(y * s), Math.ceil(s), Math.ceil(h * s));
		// a little dither texture so plates aren't flat
		g.setFill(c.deriveColor(0, 1, 0.85, 1));
		for (int j = 2; j < h - 1; j += 3) {
			for (int i = 2 + (j % 2); i < w - 1; i += 4) {
				g.fillRect(Math.round((x + i) * s), Math.round((y + j) * s), Math.ceil(s), Math.ceil(s));
			}
		}
		g.setFill(c);
	}

	private static Color c(String hex, boolean white) {
		return white ? Color.WHITE : Color.web(hex);
	}

	private static void slime(GraphicsContext g, Mob m, double s, boolean white, double t) {
		double stretch = m.onGround ? 1 - Math.max(0, 0.3 - m.timer) * 0.6 : 1.12;
		String base = m.kind == Actors.MAGMA_SLIME ? "#E0501E" : m.kind == Actors.CRYPT_SLIME ? "#CFC6B2" : "#B3232B";
		String light = m.kind == Actors.MAGMA_SLIME ? "#FFC27A" : m.kind == Actors.CRYPT_SLIME ? "#F4EBDA" : "#FF787D";
		double w = 14 / Math.sqrt(stretch) * (m.kind == Actors.MAGMA_SLIME ? 1.15 : 1);
		double h = 11 * stretch * (m.kind == Actors.MAGMA_SLIME ? 1.1 : 1);
		g.setFill(c(base, white));
		px(g, -w / 2 + 2, -h, w - 4, h, s);
		px(g, -w / 2, -h + 2, w, h - 2, s);
		g.setFill(c(light, white));
		px(g, -w / 2 + 2, -h + 2, 3, 2, s);
		g.setFill(Color.web("#2A0A0D"));
		px(g, -2, -h * 0.55, 2, 2, s);
		px(g, 2, -h * 0.55, 2, 2, s);
		if (m.kind == Actors.MAGMA_SLIME && !white) {
			g.setFill(Color.web("#FFE08A"));
			px(g, -3, -h * 0.25, 1, 1, s);
			px(g, 3, -h * 0.35, 1, 1, s);
			glow(g, 0, -h * s / 2, 14 * s, Color.rgb(255, 120, 40, 0.25));
		}
		if (m.kind == Actors.CRYPT_SLIME && !white) {
			// a little skull floats inside
			g.setFill(Color.web("#8A7E68"));
			px(g, -2, -h * 0.4, 4, 3, s);
		}
	}

	private static void flyer(GraphicsContext g, Mob m, double s, boolean white, double t) {
		boolean up = ((int) (m.anim * 10)) % 2 == 0;
		String body = m.kind == Actors.BAT ? "#2E2233" : m.kind == Actors.FROST_WISP ? "#9CD4F0" : "#3A1E6A";
		String wing = m.kind == Actors.BAT ? "#4A3550" : m.kind == Actors.FROST_WISP ? "#E4F4FF" : "#8A5AE0";
		if (m.kind != Actors.BAT && !white) {
			glow(g, 0, -9 * s, 12 * s, m.kind == Actors.FROST_WISP ? Color.rgb(160, 220, 255, 0.35) : Color.rgb(150, 90, 255, 0.4));
		}
		g.setFill(c(wing, white));
		double wy = up ? -12 : -7;
		px(g, -8, wy, 5, 2, s);
		px(g, -6, up ? -10 : -9, 3, 2, s);
		px(g, 3, wy, 5, 2, s);
		px(g, 3, up ? -10 : -9, 3, 2, s);
		g.setFill(c(body, white));
		px(g, -3, -11, 6, 6, s);
		if (m.kind == Actors.BAT) {
			px(g, -3, -12, 1, 1, s);
			px(g, 2, -12, 1, 1, s);
		} else {
			// wisps trail off below
			px(g, -2, -5, 4, 2, s);
			px(g, -1, -3 + Math.sin(t * 8) , 2, 2, s);
		}
		g.setFill(Color.web(m.kind == Actors.FROST_WISP ? "#1E3442" : "#FF4A52"));
		px(g, -2, -9, 1, 1, s);
		px(g, 1, -9, 1, 1, s);
	}

	private static void imp(GraphicsContext g, Mob m, double s, boolean white, double t) {
		boolean up = ((int) (m.anim * 8)) % 2 == 0;
		if (!white) {
			glow(g, 0, -12 * s, 14 * s, Color.rgb(255, 120, 40, 0.3));
		}
		g.setFill(c("#5A1E10", white));
		px(g, -9, up ? -16 : -12, 5, 3, s);
		px(g, 4, up ? -16 : -12, 5, 3, s);
		g.setFill(c("#C8401A", white));
		px(g, -3, -17, 6, 6, s);
		px(g, -2, -11, 4, 6, s);
		g.setFill(c("#3A140A", white));
		px(g, -3, -19, 1, 2, s);
		px(g, 2, -19, 1, 2, s);
		px(g, 0, -5, 1, 3 + Math.sin(t * 6), s);
		g.setFill(Color.web("#FFE08A"));
		px(g, -2, -15, 1, 1, s);
		px(g, 1, -15, 1, 1, s);
	}

	private static void scuttler(GraphicsContext g, Mob m, double s, boolean white, double t) {
		double step = Math.sin(m.anim * 18) * (Math.abs(m.vx) > 0.5 ? 1 : 0);
		g.setFill(c("#6E5630", white));
		for (int i = 0; i < 3; i++) {
			px(g, -7 + i * 4, -3 + (i % 2 == 0 ? step : -step), 1, 3, s);
			px(g, -6 + i * 4, -3 + (i % 2 == 0 ? -step : step), 1, 3, s);
		}
		g.setFill(c("#C9A46A", white));
		px(g, -8, -7, 14, 4, s);
		px(g, -6, -8, 10, 1, s);
		g.setFill(c("#8E6A3E", white));
		px(g, -10, -11, 3, 2, s);
		px(g, -11, -13, 2, 2, s);
		px(g, -9, -13 - Math.abs(step), 2, 2, s);
		g.setFill(c("#E8C88A", white));
		px(g, 6, -9, 3, 3, s);
		px(g, 8, -8, 2, 2, s);
		g.setFill(Color.web("#1A1010"));
		px(g, 4, -7, 1, 1, s);
	}

	private static void humanoid(GraphicsContext g, Mob m, double s, boolean white, double t) {
		double swing = Math.sin(m.anim * 9) * 25 * Math.min(1, Math.abs(m.vx) / 2);
		String skin;
		String cloth;
		if (m.kind == Actors.REVENANT) {
			skin = "#6E8A5A";
			cloth = "#3A3540";
		} else if (m.kind == Actors.SKELETON) {
			skin = "#E8DEC8";
			cloth = "#8A7E68";
		} else {
			skin = "#2A1450";
			cloth = "#120A20";
		}
		if (m.kind == Actors.STALKER) {
			g.setGlobalAlpha(m.tele > 0 ? 0.4 : 0.92);
			if (!white) {
				glow(g, 0, -20 * s, 22 * s, Color.rgb(120, 70, 255, 0.3));
			}
		}
		limb(g, -1, -12, 3, 12, -swing, cloth, white, s);
		limb(g, -1, -22, 3, 11, swing * 0.8, skin, white, s);
		g.setFill(c(cloth, white));
		px(g, -2, -24, 5, 12, s);
		g.setFill(c(skin, white));
		px(g, -3, -31, 7, 7, s);
		limb(g, -1, -12, 3, 12, swing, cloth, white, s);
		if (m.kind == Actors.SKELETON) {
			g.setFill(c("#8A7E68", white));
			px(g, -1, -20, 4, 1, s);
			px(g, -1, -18, 4, 1, s);
			// bow in hand
			g.setStroke(Color.web("#8A5A3A"));
			g.setLineWidth(s);
			g.strokeArc(1 * s, -26 * s, 7 * s, 12 * s, -80, 160, javafx.scene.shape.ArcType.OPEN);
		}
		limb(g, -1, -22, 3, 11, -swing * 0.8 - (m.kind == Actors.REVENANT ? 70 : 0), skin, white, s);
		g.setFill(Color.web(m.kind == Actors.STALKER ? "#C9A8FF" : m.kind == Actors.SKELETON ? "#1A1010" : "#E0333A"));
		px(g, 1, -29, 2, 1, s);
		if (m.kind != Actors.SKELETON) {
			px(g, -2, -29, 1, 1, s);
		}
		g.setGlobalAlpha(1);
	}

	private static void limb(GraphicsContext g, double px0, double py0, double w, double h, double angle, String color, boolean white,
			double s) {
		g.save();
		g.translate((px0 + w / 2) * s, (py0 + 1) * s);
		g.rotate(angle);
		g.setFill(c(color, white));
		g.fillRect(-w / 2 * s, -1 * s, w * s, h * s);
		g.restore();
	}

	private static void trader(GraphicsContext g, Mob m, double s, double t) {
		// hooded wanderer with a lantern and a fat map satchel
		glow(g, 7 * s, -14 * s, 26 * s, Color.rgb(255, 190, 110, 0.35 + 0.05 * Math.sin(t * 6)));
		g.setFill(Color.web("#3A2A20"));
		px(g, -5, -26, 10, 26, s);
		px(g, -6, -10, 12, 10, s);
		g.setFill(Color.web("#5A3E2A"));
		px(g, -4, -32, 8, 7, s);
		px(g, -5, -30, 10, 4, s);
		g.setFill(Color.web("#1A1010"));
		px(g, -3, -28, 6, 3, s);
		g.setFill(Color.web("#FFD27A"));
		px(g, 1, -27, 1, 1, s);
		g.setFill(Color.web("#C9A46A"));
		px(g, -7, -18, 5, 7, s);
		g.setFill(Color.web("#E6D6B4"));
		px(g, -7, -20, 4, 2, s);
		g.setFill(Color.web("#8A8590"));
		px(g, 6, -21, 1, 6, s);
		g.setFill(Color.web("#FFC04A"));
		px(g, 5, -15, 3, 4, s);
		g.setFill(Color.web("#FFE9B0"));
		px(g, 6, -14, 1, 2, s);
	}

	// ------------------------------------------------------------------ bosses

	private static void image(GraphicsContext g, Image img, Image whiteImg, double x, double y, double s, boolean white) {
		double w = img.getWidth() * s;
		double h = img.getHeight() * s;
		g.drawImage(img, Math.round(x), Math.round(y), w, h);
		if (white) {
			g.setGlobalAlpha(0.8);
			g.drawImage(whiteImg, Math.round(x), Math.round(y), w, h);
			g.setGlobalAlpha(1);
		}
	}

	private static void warden(GraphicsContext g, Mob m, double s, boolean white, double t) {
		boolean tele = m.tele > 0 || (m.move == 1 && m.moveT < 0.7);
		boolean rage = m.phase > 1;
		boolean walking = Math.abs(m.vx) > 0.4;
		int frame = tele || (m.move == 2 && m.moveT < 0.5) ? 3 : walking ? 1 + ((int) (m.anim * 6)) % 2 : 0;
		double lean = m.move == 1 && m.moveT >= 0.7 ? 8 : 0;
		if (rage) {
			glow(g, 0, -40 * s, 56 * s, Color.rgb(255, 40, 50, 0.22 + 0.1 * Math.sin(t * 8)));
		}
		g.save();
		g.rotate(lean);
		// the greatsword behind the body when idle, raised for the volley, levelled for the charge
		double arm = m.move == 3 ? 200 - Math.min(1, m.moveT / 0.5) * 60 : m.move == 1 ? -80 : 160 + Math.sin(t * 2) * 6;
		g.save();
		g.translate(14 * s, -44 * s);
		g.rotate(arm);
		image(g, BossArt.get("warden_sword", 0, false), BossArt.get("warden_sword", 0, true), -6 * s, -4 * s, s, white);
		g.restore();
		int wf = frame + (rage ? 10 : 0);
		image(g, BossArt.get("warden", wf, false), BossArt.get("warden", wf, true), -22 * s, -74 * s, s, white);
		// glowing visor: brighter while winding up and in the second phase
		double crouch = frame == 3 ? 3 : 0;
		Color eye = tele || rage ? Color.web("#FF3B44") : Color.web("#B3232B");
		g.setFill(eye);
		g.fillRect(Math.round(-5 * s), Math.round((-74 + 19 + crouch) * s), 3 * s, s);
		g.fillRect(Math.round(1 * s), Math.round((-74 + 19 + crouch) * s), 3 * s, s);
		glow(g, 0, (-55 + crouch) * s, (tele ? 26 : 16) * s, eye.deriveColor(0, 1, 1, tele ? 0.7 : 0.45));
		if (rage) {
			flames(g, s, t, 40, 70, Color.web("#D7262D"), Color.web("#FFB060"));
			// molten cracks in the armour
			g.setFill(Color.web("#FF5A3A"));
			int[][] cracks = {{-6, -44}, {-5, -43}, {-4, -41}, {3, -38}, {4, -37}, {5, -35}, {-2, -32}, {-1, -31}};
			for (int[] c : cracks) {
				g.fillRect(Math.round(c[0] * s), Math.round((c[1] + crouch) * s), s, s);
			}
		}
		g.restore();
	}

	/** Phase two: flickering flame pixels licking up around the boss. */
	private static void flames(GraphicsContext g, double s, double t, double width, double height, Color hot, Color core) {
		for (int i = 0; i < 18; i++) {
			double seed = i * 1.7;
			double life = (t * 1.6 + seed) % 1;
			double fx = Math.sin(seed * 3.1) * width / 2;
			double fy = -height * (0.15 + 0.85 * ((seed * 0.37) % 1)) - life * 18;
			double size = Math.max(1, Math.round((1 - life) * 3));
			g.setFill((life < 0.4 ? core : hot).deriveColor(0, 1, 1, 1 - life));
			g.fillRect(Math.round(fx * s), Math.round(fy * s), size * s, size * s);
		}
	}

	private static void golem(GraphicsContext g, Mob m, double s, boolean white, double t) {
		boolean tele = m.tele > 0;
		boolean rage = m.phase > 1;
		double core = 0.5 + 0.5 * Math.sin(t * (rage ? 10 : 4));
		double armsUp = m.move == 5 ? Math.min(1, m.moveT / 0.6) : m.move == 6 ? 0.5 : 0;
		boolean walking = Math.abs(m.vx) > 0.3;
		int frame = walking ? 1 + ((int) (m.anim * 4)) % 2 : 0;
		glow(g, 0, -44 * s, (rage ? 70 : 52) * s, Color.rgb(255, 110, 40, 0.16 + 0.12 * core));
		for (int side = -1; side <= 1; side += 2) {
			if (side == 1) {
				continue;
			}
			arm(g, side, armsUp, s, white);
		}
		int gf = frame + (rage ? 10 : 0);
		image(g, BossArt.get("golem", gf, false), BossArt.get("golem", gf, true), -28 * s, -84 * s, s, white);
		// the furnace core behind its grate
		Color hot = m.vent > 0 ? Color.web("#FFFFFF") : rage ? Color.web("#FFE6A0") : Color.web("#FF8A2E");
		glow(g, 0, -40 * s, (16 + core * 8) * s, hot.deriveColor(0, 1, 1, 0.55 + 0.25 * core));
		g.setFill(hot.deriveColor(0, 1, 1, 0.65 + 0.3 * core));
		for (int x = -6; x < 7; x += 3) {
			g.fillRect(Math.round((x + 0.5) * s), Math.round(-44 * s), 2 * s, 10 * s);
		}
		// eyes flare white while winding up
		g.setFill(Color.web(tele ? "#FFFFFF" : "#FFE6A0"));
		g.fillRect(Math.round(-6 * s), Math.round(-66 * s), 4 * s, 2 * s);
		g.fillRect(Math.round(3 * s), Math.round(-66 * s), 4 * s, 2 * s);
		if (tele) {
			glow(g, 0, -65 * s, 20 * s, Color.rgb(255, 230, 160, 0.6));
		}
		arm(g, 1, armsUp, s, white);
		if (rage) {
			flames(g, s, t, 54, 84, Color.web("#FF6A2A"), Color.web("#FFE6A0"));
		}
	}

	private static void arm(GraphicsContext g, int side, double up, double s, boolean white) {
		g.save();
		g.translate(side * 21 * s, -56 * s);
		g.rotate(side * (8 + up * 155));
		image(g, BossArt.get("golem_arm", 0, false), BossArt.get("golem_arm", 0, true), -9 * s, -2 * s, s, white);
		g.restore();
	}

	private static void herald(GraphicsContext g, Mob m, double s, boolean white, double t) {
		double alpha = m.move == 7 ? Math.abs(Math.cos(Math.min(1, m.moveT / 0.9) * Math.PI)) : 1;
		g.setGlobalAlpha(Math.max(0.15, alpha));
		boolean rage = m.phase > 1;
		double bob = Math.round(Math.sin(t * 2.4) * 2);
		glow(g, 0, (-32 + bob) * s, (rage ? 70 : 54) * s, Color.rgb(140, 80, 255, 0.28));
		int frame = ((int) (t * 4)) % 2 + (rage ? 10 : 0);
		image(g, BossArt.get("herald", frame, false), BossArt.get("herald", frame, true), -25 * s, (-68 + bob) * s, s, white);
		// the eye in the hood
		boolean tele = m.tele > 0;
		double ey = (-68 + bob + 12) * s;
		g.setFill(Color.web("#EADCFF"));
		g.fillRect(Math.round(-4 * s), Math.round(ey), 8 * s, 4 * s);
		g.setFill(Color.web(tele ? "#FF3B44" : "#6E3FD0"));
		g.fillRect(Math.round((-1 + m.facing) * s), Math.round(ey + s), 3 * s, 2 * s);
		glow(g, 0, ey + 2 * s, (tele ? 24 : 14) * s, tele ? Color.rgb(255, 60, 70, 0.6) : Color.rgb(200, 160, 255, 0.45));
		// two floating clawed hands, reaching while it casts
		double reach = m.move == 8 || m.move == 9 ? Math.min(1, m.moveT / 0.4) : 0;
		for (int side = -1; side <= 1; side += 2) {
			double hx = side * (22 + reach * 6) + Math.sin(t * 2 + side) * 2;
			double hy = -36 + bob + Math.cos(t * 2.2 + side) * 2 - reach * 6;
			image(g, BossArt.get("herald_hand", 0, false), BossArt.get("herald_hand", 0, true), (hx - 7) * s, hy * s, s, white);
			if (reach > 0) {
				glow(g, hx * s, (hy + 7) * s, 12 * s, Color.rgb(180, 130, 255, 0.5 * reach));
			}
		}
		if (rage) {
			flames(g, s, t, 46, 66, Color.web("#8A5AE0"), Color.web("#FF6B70"));
		}
		// a crown of shards orbiting behind the head
		int shards = rage ? 7 : 5;
		for (int i = 0; i < shards; i++) {
			double a = t * (rage ? 2.6 : 1.6) + i * Math.PI * 2 / shards;
			double ox = Math.cos(a) * 18;
			double oy = -60 + bob + Math.sin(a) * 5;
			g.setFill(Color.web(i % 2 == 0 ? "#B48CFF" : "#EADCFF"));
			g.fillRect(Math.round((ox - 1) * s), Math.round((oy - 3) * s), 2 * s, 5 * s);
			g.setFill(Color.web("#5B3BA8"));
			g.fillRect(Math.round((ox) * s), Math.round((oy - 3) * s), s, 5 * s);
		}
		g.setGlobalAlpha(1);
	}

	// ------------------------------------------------------------------ projectiles

	static void projectile(GraphicsContext g, Proj p, double x, double y, double block, double t) {
		double s = block / 16;
		switch (p.kind) {
			case Actors.ARROW, Actors.PLAYER_ARROW -> {
				double a = Math.atan2(p.vy, p.vx);
				g.save();
				g.translate(x, y);
				g.rotate(Math.toDegrees(a));
				g.setFill(Color.web("#8A5A3A"));
				g.fillRect(-6 * s, -0.5 * s, 10 * s, 1.5 * s);
				g.setFill(Color.web(p.kind == Actors.PLAYER_ARROW ? "#F4EFEE" : "#C9C0B8"));
				g.fillRect(4 * s, -1.5 * s, 3 * s, 3 * s);
				g.setFill(Color.web("#E8E0D0"));
				g.fillRect(-7 * s, -1.5 * s, 2 * s, 3 * s);
				g.restore();
			}
			case Actors.FIREBALL -> {
				glow(g, x, y, 16 * s, Color.rgb(255, 130, 50, 0.55));
				g.setFill(Color.web("#FF6A2A"));
				g.fillRect(x - 3 * s, y - 3 * s, 6 * s, 6 * s);
				g.setFill(Color.web("#FFE08A"));
				g.fillRect(x - 1.5 * s, y - 1.5 * s, 3 * s, 3 * s);
			}
			case Actors.ORB -> {
				glow(g, x, y, 14 * s, Color.rgb(160, 100, 255, 0.6));
				g.setFill(Color.web("#8A5AE0"));
				g.fillRect(x - 3 * s, y - 3 * s, 6 * s, 6 * s);
				g.setFill(Color.web("#EADCFF"));
				g.fillRect(x - 1 * s, y - 1 * s, 2 * s, 2 * s);
			}
			case Actors.SHARD -> {
				g.save();
				g.translate(x, y);
				g.rotate(t * 720);
				g.setFill(Color.web("#E8DEC8"));
				g.fillRect(-1 * s, -3 * s, 2 * s, 6 * s);
				g.restore();
			}
			case Actors.WAVE -> {
				double h = 14 * s;
				glow(g, x, y - h / 2, 16 * s, Color.rgb(255, 230, 210, 0.35));
				g.setFill(Color.rgb(232, 222, 200, 0.85));
				for (int i = 0; i < 4; i++) {
					double hh = h * (0.4 + 0.6 * Math.abs(Math.sin(t * 20 + i)));
					g.fillRect(x - 6 * s + i * 3 * s, y - hh, 2 * s, hh);
				}
			}
			case Actors.PILLAR -> {
				double grow = Math.min(1, p.age / 0.15);
				double h = 3.2 * block * grow;
				glow(g, x, y - h / 2, block * 1.4, Color.rgb(255, 120, 40, 0.5));
				g.setFill(Color.web("#FF6A2A"));
				g.fillRect(x - block * 0.45, y - h, block * 0.9, h);
				g.setFill(Color.web("#FFE08A"));
				g.fillRect(x - block * 0.2, y - h, block * 0.4, h);
			}
			default -> {
			}
		}
	}
}
