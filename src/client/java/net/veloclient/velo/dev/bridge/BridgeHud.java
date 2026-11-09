package net.veloclient.velo.dev.bridge;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.util.ClientCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everything a Velo Bridge server puts on screen, in Velo's fonts (Inter, Sora for the countdown digits). Toasts and
 * the first progress bar carry the server's name in its colour, so their text can't pass for Velo's; the countdown and
 * the markers speak for themselves and stay clean.
 * <ul>
 * <li>Toasts glide in from the right and fade, show their remaining time as a draining hairline, and the stack re-flows
 * smoothly when one leaves.</li>
 * <li>The countdown's digit settles in each second inside a ring that empties, then "GO" grows and fades.</li>
 * <li>Progress bars fade in and ease toward their value with a soft light gliding across them.</li>
 * <li>Teammates and waypoints get markers in the world (pulsing ring, name, distance) and an arrow at the screen edge
 * when they're off-screen.</li>
 * <li>Vignette and screen tint fade in and out.</li>
 * </ul>
 */
final class BridgeHud {

	private static final Identifier EFFECTS = Identifier.of("velo-client", "bridge_effects");
	private static final Identifier UI = Identifier.of("velo-client", "bridge_ui");
	/** Smoothly animated y position per toast (so the stack re-flows instead of jumping). */
	private static final Map<Long, Float> TOAST_Y = new HashMap<>();
	private static final Map<String, Float> SHOWN_PROGRESS = new HashMap<>();
	/** When each progress bar first showed (it fades in). */
	private static final Map<String, Long> PROGRESS_SINCE = new HashMap<>();
	private static long lastFrame = System.nanoTime();
	/** Bottom edge and right edge of the progress bars drawn this frame (toasts move below them if they'd touch). */
	private static int progressBottom;
	private static int progressRight;

	private BridgeHud() {
	}

	static void register() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR, EFFECTS, (context, tick) -> effects(context));
		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, UI, (context, tick) -> ui(context));
	}

	// ---- Vignette & tint ----

	private static void effects(DrawContext context) {
		int w = context.getScaledWindowWidth();
		int h = context.getScaledWindowHeight();
		float tint = strength(VeloBridgeClient.tint);
		if (tint > 0) {
			context.fill(0, 0, w, h, withAlpha(VeloBridgeClient.tint.color(), tint * 0.6f));
		}
		float vignette = strength(VeloBridgeClient.vignette);
		if (vignette > 0) {
			int c = VeloBridgeClient.vignette.color();
			int edge = withAlpha(c, vignette * 0.85f);
			int clear = withAlpha(c, 0);
			int band = Math.max(40, Math.min(w, h) / 3);
			VeloDraw.fillGradient(context, 0, 0, w, band, edge, clear);
			VeloDraw.fillGradient(context, 0, h - band, w, band, clear, edge);
			net.veloclient.velo.client.gui.title.MenuKit.hGradient(context, 0, 0, band, h, edge, clear);
			net.veloclient.velo.client.gui.title.MenuKit.hGradient(context, w - band, 0, band, h, clear, edge);
		}
	}

	/** 0..1: fades in over 250 ms, holds, fades out over the last 600 ms ({@code millis} 0 = until cleared). */
	static float strength(VeloBridgeClient.Effect e) {
		if (e == null) {
			return 0;
		}
		long age = System.currentTimeMillis() - e.startedAt();
		float in = Math.min(1f, age / 250f);
		float out = 1f;
		if (e.millis() > 0) {
			long left = e.millis() - age;
			if (left <= 0) {
				return 0;
			}
			out = Math.min(1f, left / 600f);
		}
		return (float) (e.strength() * ease(in) * out);
	}

	// ---- Cards, countdown, bars, markers ----

	private static void ui(DrawContext context) {
		if (!BridgeMc.inWorld()) {
			return;
		}
		long nanos = System.nanoTime();
		float dt = Math.min(0.1f, (nanos - lastFrame) / 1e9f);
		lastFrame = nanos;
		int w = context.getScaledWindowWidth();
		int h = context.getScaledWindowHeight();
		if (letterbox(context, w, h)) {
			return; // a cutscene: only the bars and its title
		}
		markers(context, w, h);
		progressBars(context, w, dt);
		countdown(context, w, h);
		toasts(context, w, dt);
	}

	private static void toasts(DrawContext context, int screenW, float dt) {
		long now = System.currentTimeMillis();
		int width = 212;
		int pad = 12;
		boolean clash = progressRight > screenW - width - 12 - 8;
		float y = clash ? progressBottom + 8 : 12;
		List<Long> alive = new ArrayList<>();
		for (VeloBridgeClient.Toast t : VeloBridgeClient.TOASTS) {
			List<String> lines = wrap(t.text(), width - pad * 2);
			// tag 8 + gap 5 + title 8 + gap 5 + lines + bottom padding
			int height = pad + 8 + 5 + MenuKit.H_UI + (lines.isEmpty() ? 0 : 5 + lines.size() * 11 - 3) + pad + 2;
			long key = t.shownAt();
			alive.add(key);
			float shownY = TOAST_Y.getOrDefault(key, y);
			shownY += (y - shownY) * (1f - (float) Math.exp(-dt * 14f));
			TOAST_Y.put(key, shownY);
			// Calm in (ease-out, short slide + fade), quicker out.
			float enter = expoOut(Math.min(1f, (now - t.shownAt()) / 420f));
			float exit = t.until() < now ? cubicIn(Math.min(1f, (now - t.until()) / 260f)) : 0f;
			float alpha = enter * (1f - exit);
			int x = Math.round(screenW - width - 12 + (1f - enter) * 20 + exit * 14);
			int top = Math.round(shownY);
			card(context, x, top, width, height, alpha);
			VeloDraw.fillRounded(context, x, top + pad, 2, height - pad * 2, 1, withAlpha(VeloBridgeClient.serverColor(), alpha));
			serverTag(context, x + pad, top + pad, alpha);
			text(context, MenuKit.uiBold(trim(t.title(), width - pad * 2, true)), x + pad, top + pad + 13, withAlpha(0xFFFFFFFF, alpha));
			for (int i = 0; i < lines.size(); i++) {
				text(context, MenuKit.ui(lines.get(i)), x + pad, top + pad + 26 + i * 11, withAlpha(0xFFB4B7C0, alpha));
			}
			// Time left: a hairline that drains from right to left.
			float left = Math.max(0f, Math.min(1f, (t.until() - now) / (float) Math.max(1, t.until() - t.shownAt())));
			context.fill(x + pad, top + height - 4, x + pad + Math.round((width - pad * 2) * left), top + height - 3,
					withAlpha(VeloBridgeClient.serverColor(), 0.7f * alpha));
			y += (height + 8) * (1f - exit);
		}
		TOAST_Y.keySet().retainAll(alive);
	}

	/** Every server panel: dark, a hairline edge, a soft shadow. */
	private static void card(DrawContext context, int x, int y, int w, int h, float alpha) {
		VeloDraw.shadow(context, x, y, w, h, 6, 12, 4, withAlpha(0xFF000000, 0.4f * alpha));
		VeloDraw.fillRounded(context, x, y, w, h, 6, withAlpha(0xFF0F1014, 0.96f * alpha));
		VeloDraw.strokeRounded(context, x, y, w, h, 6, withAlpha(0xFFFFFFFF, 0.07f * alpha));
	}

	/** "● SERVER NAME" in the server's colour - on toasts and the first progress bar, where text could pass for Velo's. */
	private static void serverTag(DrawContext context, int x, int y, float alpha) {
		var tag = MenuKit.small(trim(VeloBridgeClient.server().toUpperCase(Locale.ROOT), 170, false));
		VeloDraw.fillCircle(context, x + 2f, y + 3.5f, 2f, withAlpha(VeloBridgeClient.serverColor(), alpha));
		text(context, tag, x + 7, y, withAlpha(VeloBridgeClient.serverColor(), 0.95f * alpha));
	}

	private static void countdown(DrawContext context, int w, int h) {
		VeloBridgeClient.Countdown c = VeloBridgeClient.countdown;
		if (c == null) {
			return;
		}
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		long now = System.currentTimeMillis();
		long left = c.endsAt() - now;
		int cx = w / 2;
		int cy = Math.round(h * 0.36f);
		int radius = 26;
		if (left <= 0) {
			// "GO" grows a little and fades.
			float t = Math.min(1f, -left / 800f);
			if (t >= 1f) {
				return;
			}
			float alpha = 1f - cubicIn(t);
			centered(context, MenuKit.hero("GO"), cx, cy, 1f + expoOut(t) * 0.5f, MenuKit.H_HERO, withAlpha(VeloBridgeClient.serverColor(), alpha));
			return;
		}
		int seconds = (int) Math.ceil(left / 1000.0);
		float within = 1f - (left % 1000) / 1000f; // 0 right after the tick, 1 just before the next
		// Each new digit settles in from a touch larger, fading up.
		float settle = expoOut(Math.min(1f, within * 3.2f));
		VeloDraw.shadow(context, cx - radius - 6, cy - radius - 6, (radius + 6) * 2, (radius + 6) * 2, radius + 6, 14, 4, 0x66000000);
		VeloDraw.fillCircle(context, (float) cx, (float) cy, radius + 6, 0xFF0F1014);
		VeloDraw.strokeArc(context, cx, cy, radius, 2f, -90f, 360f, 0x1FFFFFFF);
		VeloDraw.strokeArc(context, cx, cy, radius, 2f, -90f, 360f * (1f - within), VeloBridgeClient.serverColor());
		centered(context, MenuKit.hero(String.valueOf(seconds)), cx, cy, 1.12f - 0.12f * settle, MenuKit.H_HERO, withAlpha(0xFFFFFFFF, 0.25f + 0.75f * settle));
		var label = MenuKit.uiBold(trim(c.label(), 220, true));
		int lw = textRenderer.getWidth(label);
		int ly = cy + radius + 14;
		VeloDraw.fillRounded(context, cx - lw / 2 - 9, ly - 5, lw + 18, MenuKit.H_UI + 10, 6, 0xC80F1014);
		text(context, label, cx - lw / 2, ly, 0xFFEDEEF2);
	}

	/** Cutscene letterbox bars (slide in and out) and the title, fading in and out. True while a cutscene runs. */
	private static boolean letterbox(DrawContext context, int w, int h) {
		BridgeCutscene.Scene s = BridgeCutscene.scene;
		long now = System.currentTimeMillis();
		float in;
		BridgeCutscene.Scene shown = s;
		if (s != null) {
			in = Math.min(1f, (now - s.start()) / 600f);
		} else {
			shown = BridgeCutscene.last;
			if (shown == null || now - BridgeCutscene.endedAt > 600) {
				return false;
			}
			in = 1f - (now - BridgeCutscene.endedAt) / 600f;
		}
		float e = ease(in);
		if (shown.letterbox()) {
			int bar = Math.round(h * 0.11f * e);
			context.fill(0, 0, w, bar, 0xFF000000);
			context.fill(0, h - bar, w, h, 0xFF000000);
		}
		if (s != null && !(s.title().isEmpty() && s.subtitle().isEmpty())) {
			float p = BridgeCutscene.progress();
			float a = Math.min(1f, Math.min(p / 0.12f, (1f - p) / 0.12f));
			if (a > 0.02f) {
				String title = s.title().replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
				String sub = s.subtitle().replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
				int cy = h - Math.round(h * 0.11f) - 34;
				if (!title.isEmpty()) {
					centered(context, MenuKit.hero(title), w / 2, cy, 0.7f, MenuKit.H_HERO, withAlpha(0xFFFFFFFF, a));
				}
				if (!sub.isEmpty()) {
					var t = MenuKit.ui(sub);
					int tw = MinecraftClient.getInstance().textRenderer.getWidth(t);
					text(context, t, w / 2 - tw / 2, cy + 16, withAlpha(0xFFD0D3DA, a));
				}
			}
		}
		return s != null;
	}

	/** Text centred on (cx, cy) at {@code scale}; {@code lineHeight} is the font's height (MenuKit.H_*). */
	private static void centered(DrawContext context, net.minecraft.text.Text t, int cx, int cy, float scale, int lineHeight, int color) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(cx, cy);
		context.getMatrices().scale(scale, scale);
		context.drawTextWithShadow(textRenderer, t, -textRenderer.getWidth(t) / 2, -lineHeight / 2, color);
		context.getMatrices().popMatrix();
	}

	private static void progressBars(DrawContext context, int w, float dt) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		int width = Math.min(250, w - 40);
		int pad = 10;
		int x = (w - width) / 2;
		int y = 12;
		long now = System.currentTimeMillis();
		boolean first = true;
		progressBottom = 0;
		progressRight = 0;
		for (Map.Entry<String, VeloBridgeClient.Progress> e : VeloBridgeClient.PROGRESS.entrySet()) {
			VeloBridgeClient.Progress p = e.getValue();
			float shown = SHOWN_PROGRESS.getOrDefault(e.getKey(), 0f);
			shown = VeloAnim.step(shown, (float) p.value(), dt * 0.9f);
			SHOWN_PROGRESS.put(e.getKey(), shown);
			float alpha = expoOut(Math.min(1f, (now - PROGRESS_SINCE.computeIfAbsent(e.getKey(), k -> now)) / 400f));
			int tagH = first ? 13 : 0;
			int height = pad + tagH + MenuKit.H_UI + 7 + 4 + pad;
			int top = y + Math.round((1f - alpha) * -8);
			card(context, x, top, width, height, alpha);
			if (first) {
				serverTag(context, x + pad, top + pad, alpha);
			}
			int row = top + pad + tagH;
			var pct = MenuKit.ui(Math.round(shown * 100) + "%");
			text(context, MenuKit.uiBold(trim(p.label(), width - pad * 2 - 34, true)), x + pad, row, withAlpha(0xFFFFFFFF, alpha));
			text(context, pct, x + width - pad - textRenderer.getWidth(pct), row, withAlpha(0xFFB4B7C0, alpha));
			int barX = x + pad;
			int barW = width - pad * 2;
			int barY = row + MenuKit.H_UI + 7;
			VeloDraw.fillRounded(context, barX, barY, barW, 4, 2, withAlpha(0xFFFFFFFF, 0.12f * alpha));
			int fillW = Math.max(4, Math.round(barW * shown));
			VeloDraw.fillRounded(context, barX, barY, fillW, 4, 2, withAlpha(VeloBridgeClient.serverColor(), alpha));
			// A soft light gliding across the filled part.
			float sweep = (now % 2400) / 2400f;
			int sx = barX + Math.round((fillW + 40) * sweep) - 40;
			for (int i = 0; i < 40; i++) {
				int px = sx + i;
				if (px >= barX + 1 && px < barX + fillW - 1) {
					float a = 1f - Math.abs(i - 20) / 20f;
					context.fill(px, barY, px + 1, barY + 4, withAlpha(0xFFFFFFFF, 0.22f * a * a * alpha));
				}
			}
			progressBottom = top + height;
			progressRight = x + width;
			y += height + 6;
			first = false;
		}
		SHOWN_PROGRESS.keySet().retainAll(VeloBridgeClient.PROGRESS.keySet());
		PROGRESS_SINCE.keySet().retainAll(VeloBridgeClient.PROGRESS.keySet());
	}

	private static void markers(DrawContext context, int w, int h) {
		double[] self = BridgeMc.self();
		if (self == null) {
			return;
		}
		for (Map.Entry<UUID, String> mate : VeloBridgeClient.teammates().entrySet()) {
			double[] pos = BridgeMc.playerPos(mate.getKey());
			if (pos != null) {
				marker(context, w, h, pos, mate.getValue(), VeloBridgeClient.teammateArgb(), self, false);
			}
		}
		for (VeloBridgeClient.Waypoint wp : VeloBridgeClient.WAYPOINTS.values()) {
			marker(context, w, h, new double[] {wp.x(), wp.y() + 1.2, wp.z()}, wp.label(), wp.color(), self, true);
		}
	}

	private static void marker(DrawContext context, int w, int h, double[] pos, String label, int color, double[] self, boolean waypoint) {
		var textRenderer = MinecraftClient.getInstance().textRenderer;
		float[] view = ClientCompat.cameraView(pos[0], pos[1], pos[2]);
		float[] screen = ClientCompat.project(view, w, h);
		double distance = Math.sqrt(Math.pow(pos[0] - self[0], 2) + Math.pow(pos[1] - self[1], 2) + Math.pow(pos[2] - self[2], 2));
		String dist = distance < 10 ? String.format(Locale.ROOT, "%.1fm", distance) : Math.round(distance) + "m";
		int margin = 28;
		boolean onScreen = screen != null && screen[0] > margin && screen[0] < w - margin && screen[1] > margin && screen[1] < h - margin;
		long now = System.currentTimeMillis();
		var name = MenuKit.uiBold(trim(label, 140, true));
		var far = MenuKit.ui(dist);
		int nameW = textRenderer.getWidth(name);
		int farW = textRenderer.getWidth(far);
		int pillW = 8 + nameW + 6 + farW + 8;
		int pillH = MenuKit.H_UI + 8;
		if (onScreen) {
			float sx = screen[0];
			float sy = screen[1];
			if (waypoint) {
				// A diamond with one slow ring breathing outward.
				float pulse = expoOut((now % 2200) / 2200f);
				VeloDraw.strokeArc(context, sx, sy, 5 + pulse * 9, 1.2f, 0, 360, withAlpha(color, 0.55f * (1f - pulse)));
				VeloDraw.fillQuad(context, sx, sy - 5, sx + 5, sy, sx, sy + 5, sx - 5, sy, color);
				VeloDraw.fillQuad(context, sx, sy - 2.5f, sx + 2.5f, sy, sx, sy + 2.5f, sx - 2.5f, sy, 0xFFFFFFFF);
			} else {
				VeloDraw.fillCircle(context, sx, sy, 3f, color);
			}
			int px = Math.round(sx - pillW / 2f);
			int py = Math.round(sy) - (waypoint ? 13 : 9) - pillH;
			if (underCountdown(w, h, px, py, pillW, pillH)) {
				return;
			}
			VeloDraw.fillRounded(context, px, py, pillW, pillH, pillH / 2, 0xD20F1014);
			VeloDraw.fillCircle(context, px + 5f, py + pillH / 2f, 1.5f, color);
			text(context, name, px + 9, py + 4, 0xFFFFFFFF);
			text(context, far, px + pillW - 8 - farW, py + 4, 0xFFA9ACB6);
			return;
		}
		// Off-screen: an arrow on an ellipse near the screen edge, pointing at it.
		float dx = view[0];
		float dy = -view[1];
		if (view[2] > 0) {
			dx = -dx;
			dy = -dy;
		}
		double angle = Math.atan2(dy, dx);
		float ex = (float) (w / 2.0 + Math.cos(angle) * (w / 2.0 - margin));
		float ey = (float) (h / 2.0 + Math.sin(angle) * (h / 2.0 - margin));
		float bob = (float) Math.sin(now / 320.0) * 1.2f;
		float tipX = (float) (ex + Math.cos(angle) * (7 + bob));
		float tipY = (float) (ey + Math.sin(angle) * (7 + bob));
		float backX = (float) (ex - Math.cos(angle) * 4);
		float backY = (float) (ey - Math.sin(angle) * 4);
		float px = (float) -Math.sin(angle) * 5.5f;
		float py = (float) Math.cos(angle) * 5.5f;
		VeloDraw.fillCircle(context, ex, ey, 9, 0xD20F1014);
		VeloDraw.fillQuad(context, tipX, tipY, backX + px, backY + py, ex, ey, backX - px, backY - py, color);
		int tx = Math.round(Math.max(4, Math.min(w - pillW - 4, ex - pillW / 2f)));
		int ty = Math.round(ey + (ey > h / 2f ? -16 - pillH : 14));
		VeloDraw.fillRounded(context, tx, ty, pillW, pillH, pillH / 2, 0xD20F1014);
		text(context, name, tx + 8, ty + 4, 0xFFFFFFFF);
		text(context, far, tx + pillW - 8 - farW, ty + 4, 0xFFA9ACB6);
	}

	/** True while a countdown runs and the rectangle would sit behind its ring or label (it'd peek out at the sides). */
	private static boolean underCountdown(int w, int h, int x, int y, int rw, int rh) {
		if (VeloBridgeClient.countdown == null || VeloBridgeClient.countdown.endsAt() < System.currentTimeMillis()) {
			return false;
		}
		int cx = w / 2;
		int cy = Math.round(h * 0.36f);
		return x < cx + 70 && x + rw > cx - 70 && y < cy + 56 && y + rh > cy - 36;
	}

	private static void text(DrawContext context, net.minecraft.text.Text t, int x, int y, int color) {
		context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, t, x, y, color);
	}

	// ---- Helpers ----

	private static List<String> wrap(String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : (text == null ? "" : text).split("\\s+")) {
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (inter(candidate, false) > width && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
				if (lines.size() == 3) {
					break;
				}
			} else {
				line = new StringBuilder(candidate);
			}
		}
		if (!line.isEmpty() && lines.size() < 4) {
			lines.add(line.toString());
		}
		return lines;
	}

	/** Cut to {@code width} as measured in Inter ({@code bold}: the semibold cut). */
	private static String trim(String text, int width, boolean bold) {
		String t = text == null ? "" : text;
		if (inter(t, bold) <= width) {
			return t;
		}
		while (!t.isEmpty() && inter(t + "…", bold) > width) {
			t = t.substring(0, t.length() - 1);
		}
		return t + "…";
	}

	private static int inter(String s, boolean bold) {
		return MinecraftClient.getInstance().textRenderer.getWidth(bold ? MenuKit.uiBold(s) : MenuKit.ui(s));
	}

	static int withAlpha(int argb, float alpha) {
		int a = Math.round(Math.max(0, Math.min(1, alpha)) * ((argb >>> 24) == 0 ? 255 : (argb >>> 24)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	private static float expoOut(float t) {
		return t >= 1f ? 1f : 1f - (float) Math.pow(2, -10 * t);
	}

	private static float cubicIn(float t) {
		return t * t * t;
	}

	private static float ease(float t) {
		return 1f - (1f - t) * (1f - t) * (1f - t);
	}
}
