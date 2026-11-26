package net.veloclient.velo.client.tiers;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.client.modules.pvp.TierTaggerModule;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.client.util.ClientCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tier Tagger's "all tiers" view (2026-11-25): hold the key while looking at a player and their tiers open up around
 * them in the world - a ring around the body, every game mode as a chip (icon, mode, HT1..LT5 in the tier's colour)
 * spread on an ellipse around them with a thin line to the body, their name and best tier on top. The chips burst out
 * from the body one after another and fold back in when the key is let go. The player stays locked while the key is
 * held, so the view doesn't jump to whoever walks through the crosshair.
 */
public final class TierRing {

	private static final double RANGE = 64;
	/** Look within this angle of a player to pick them. */
	private static final double PICK_DEGREES = 10;
	private static final long OPEN_NS = 320_000_000L;
	private static final long CLOSE_NS = 180_000_000L;
	private static final long STAGGER_NS = 28_000_000L;

	private static final Map<String, String> NAMES = Map.ofEntries(
			Map.entry("sword", "Sword"), Map.entry("axe", "Axe"), Map.entry("mace", "Mace"), Map.entry("crystal", "Crystal"),
			Map.entry("pot", "Pot"), Map.entry("neth_pot", "Neth Pot"), Map.entry("smp", "SMP"), Map.entry("uhc", "UHC"),
			Map.entry("minecart", "Minecart"), Map.entry("dia_smp", "Dia SMP"), Map.entry("og_vanilla", "OG Vanilla"),
			Map.entry("bed", "Bed"), Map.entry("bow", "Bow"), Map.entry("speed", "Speed"), Map.entry("debuff", "Debuff"),
			Map.entry("creeper", "Creeper"), Map.entry("trident", "Trident"), Map.entry("manhunt", "Manhunt"),
			Map.entry("elytra", "Elytra"), Map.entry("dia_crystal", "Dia Crystal"));

	private static int lockedId = -1;
	private static long openedAt;
	private static long closedAt;
	private static boolean open;
	/** What was last shown (kept for the closing animation). */
	private static Object lastTarget;

	private TierRing() {
	}

	/** One frame's layout: worked out in {@link #prepare} (so the glass blur can go behind the chips), painted later. */
	private record Item(int x, int y, int w, int h, Text text, int accent, int alpha, boolean header) {
	}

	private record Layout(List<Item> items, float cx, float cy, float rx, float ry, float open, int alpha, List<float[]> lines) {
	}

	private static Layout current;

	/**
	 * Called by the HUD before anything is drawn: updates the key / target state and lays the ring out. Returns the
	 * chips' rects {x, y, w, h, radius} for the glass blur (empty when the ring is closed).
	 */
	public static List<int[]> prepare(int width, int height) {
		current = null;
		TierTaggerModule m = TierTaggerModule.active();
		long now = System.nanoTime();
		// the key only does something while Tier Tagger is on (it's off by default)
		boolean held = m != null && TierTaggerModule.ALL_TIERS_KEY.isPressed()
				&& net.veloclient.velo.client.util.ClientCompat.currentScreen() == null;
		if (held) {
			Object target = lockedId >= 0 ? PvpCompat.entityById(lockedId) : null;
			if (target == null || !open) {
				target = pick();
				lockedId = target == null ? -1 : ((net.minecraft.entity.Entity) target).getId();
				if (target != null && !open) {
					open = true;
					openedAt = now;
				}
			}
			if (target != null) {
				lastTarget = target;
			}
		} else if (open) {
			open = false;
			closedAt = now;
			lockedId = -1;
		}
		float fade;
		if (open) {
			fade = 1;
		} else {
			long since = now - closedAt;
			if (lastTarget == null || since > CLOSE_NS) {
				lastTarget = null;
				return List.of();
			}
			fade = 1 - since / (float) CLOSE_NS;
		}
		if (lastTarget == null || m == null) {
			return List.of();
		}
		current = layout(width, height, (net.minecraft.entity.Entity) lastTarget, m, open ? now - openedAt : Long.MAX_VALUE / 2, fade);
		if (current == null) {
			return List.of();
		}
		List<int[]> rects = new ArrayList<>();
		for (Item item : current.items()) {
			if (item.alpha() > 40) {
				rects.add(new int[] {item.x(), item.y(), item.w(), item.h(), radius(item.h())});
			}
		}
		return rects;
	}

	/** Paints the prepared ring (glass chips over the blur the HUD put behind them). */
	public static void renderHud(DrawContext context, int width, int height) {
		Layout l = current;
		current = null;
		if (l == null) {
			return;
		}
		int accent = net.veloclient.velo.client.gui.glass.GlassPrefs.accent();
		ellipse(context, l.cx(), l.cy(), l.rx() * 0.42f * l.open(), l.ry() * 0.62f * l.open(), withAlpha(accent, (int) (l.alpha() * 0.6f)), 1.5f);
		ellipse(context, l.cx(), l.cy(), l.rx() * 0.47f * l.open(), l.ry() * 0.68f * l.open(), withAlpha(0xFFFFFF, (int) (l.alpha() * 0.16f)), 1f);
		for (float[] line : l.lines()) {
			VeloDraw.line(context, line[0], line[1], line[2], line[3], 1f, withAlpha((int) line[4], (int) line[5]));
		}
		for (Item item : l.items()) {
			glassChip(context, item);
		}
	}

	private static int radius(int h) {
		return Math.max(3, Math.min(h / 2, Math.round(net.veloclient.velo.client.gui.glass.GlassPrefs.radius(h / 2f))));
	}

	/** The player nearest the middle of the view (within a few degrees and {@link #RANGE} blocks). */
	private static Object pick() {
		var crosshair = PvpCompat.crosshairPlayer();
		if (crosshair != null) {
			return crosshair;
		}
		double[] cam = PvpCompat.cameraPos();
		float[] rot = PvpCompat.cameraRotation();
		double yaw = Math.toRadians(rot[0]);
		double pitch = Math.toRadians(rot[1]);
		double fx = -Math.sin(yaw) * Math.cos(pitch);
		double fy = -Math.sin(pitch);
		double fz = Math.cos(yaw) * Math.cos(pitch);
		Object best = null;
		double bestAngle = PICK_DEGREES;
		for (var player : PvpCompat.otherPlayers()) {
			double[] p = PvpCompat.renderPos(player);
			double dx = p[0] - cam[0];
			double dy = p[1] + 1.0 - cam[1];
			double dz = p[2] - cam[2];
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (dist < 0.5 || dist > RANGE) {
				continue;
			}
			double cos = (dx * fx + dy * fy + dz * fz) / dist;
			double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
			if (angle < bestAngle) {
				bestAngle = angle;
				best = player;
			}
		}
		return best;
	}

	private record Chip(Text text, int width, int accent) {
	}

	private static Layout layout(int width, int height, net.minecraft.entity.Entity target, TierTaggerModule m, long sinceOpen, float fade) {
		double[] p = PvpCompat.renderPos(target);
		float[] feet = ClientCompat.project(ClientCompat.cameraView(p[0], p[1], p[2]), width, height);
		float[] head = ClientCompat.project(ClientCompat.cameraView(p[0], p[1] + 1.95, p[2]), width, height);
		if (feet == null || head == null) {
			return null;
		}
		float cx = (feet[0] + head[0]) / 2;
		float cy = (feet[1] + head[1]) / 2;
		float body = Math.max(24, feet[1] - head[1]);
		// the ring sizes itself to the player on screen, within sane limits
		float rx = Math.max(80, Math.min(240, body * 1.1f));
		float ry = Math.max(54, Math.min(150, body * 0.74f));

		UUID uuid = PvpCompat.uuid(target);
		TierData.Player data = uuid == null ? null : TierData.get(uuid);
		Map<String, TierData.Tier> list = data == null ? null : m.subTiers() ? data.subtiers() : data.pvptiers();
		List<TierData.Tier> tiers = new ArrayList<>();
		if (list != null) {
			list.values().stream().filter(tier -> m.retired() || !tier.retired())
					.sorted(java.util.Comparator.comparingInt(TierData.Tier::score)).forEach(tiers::add);
		}
		float open = ease(clamp01(sinceOpen / (float) OPEN_NS)) * fade;
		int alpha = Math.round(255 * clamp01(fade));
		int accent = net.veloclient.velo.client.gui.glass.GlassPrefs.accent();
		List<Item> items = new ArrayList<>();
		List<float[]> lines = new ArrayList<>();

		// header above the head: name, best tier, which list
		MutableText header = Text.empty().append(Text.literal(PvpCompat.name(target)).setStyle(Style.EMPTY.withBold(true)));
		if (!tiers.isEmpty()) {
			header.append(Text.literal("  ")).append(TierTags.label(tiers.getFirst()));
		}
		header.append(Text.literal("  " + (m.subTiers() ? "SubTiers" : "PvPTiers")).setStyle(Style.EMPTY.withColor(0x9A9AA2)));
		int hw = HudLook.width(header) + 22;
		float hy = head[1] - 34 - 10 * open;
		items.add(new Item(Math.round(cx - hw / 2f), Math.round(hy), hw, 22, header, accent, (int) (alpha * clamp01(open * 1.3f)), true));

		if (tiers.isEmpty()) {
			Text none = Text.literal(data == null ? "Loading tiers..." : "Not on " + (m.subTiers() ? "SubTiers" : "PvPTiers"))
					.setStyle(Style.EMPTY.withColor(0xC9C9D1));
			int w = HudLook.width(none) + 18;
			items.add(new Item(Math.round(cx - w / 2f), Math.round(cy + ry * 0.75f * open), w, 20, none, 0x8A8A90, alpha, false));
			return new Layout(items, cx, cy, rx, ry, open, alpha, lines);
		}

		List<Chip> chips = new ArrayList<>();
		for (TierData.Tier tier : tiers) {
			MutableText text = Text.empty();
			if (m.icon()) {
				text.append(TierTags.icon(tier.mode())).append(Text.literal(" "));
			}
			text.append(Text.literal(NAMES.getOrDefault(tier.mode(), tier.mode()) + "  ").setStyle(Style.EMPTY.withColor(0xE4E4E8)));
			text.append(TierTags.label(tier));
			chips.add(new Chip(text, HudLook.width(text) + 18, TierTags.color(tier)));
		}
		// on an ellipse: from the top right, clockwise, leaving the spot under the header free
		int n = chips.size();
		double startAngle = -Math.PI / 2 + Math.PI / Math.max(4, n + 1);
		double span = 2 * Math.PI - 2 * Math.PI / Math.max(4, n + 1);
		for (int i = 0; i < n; i++) {
			Chip chip = chips.get(i);
			double a = n == 1 ? Math.PI / 2 : startAngle + span * i / (n - 1);
			// each chip bursts out a moment after the one before
			float own = ease(clamp01((sinceOpen - STAGGER_NS * i) / (float) OPEN_NS)) * fade;
			float tx = (float) (cx + Math.cos(a) * rx * own);
			float ty = (float) (cy + Math.sin(a) * ry * own);
			lines.add(new float[] {cx + (tx - cx) * 0.5f, cy + (ty - cy) * 0.5f, tx, ty, chip.accent(), alpha * 0.4f * own});
			items.add(new Item(Math.round(tx - chip.width() / 2f), Math.round(ty - 10), chip.width(), 20, chip.text(), chip.accent(),
					(int) (alpha * clamp01(own * 1.4f)), false));
		}
		return new Layout(items, cx, cy, rx, ry, open, alpha, lines);
	}

	/** A glass chip: the in-game glass tint over the blur behind it, a light edge, a tier-coloured dot. */
	private static void glassChip(DrawContext context, Item item) {
		int a = item.alpha();
		if (a <= 4) {
			return;
		}
		int r = radius(item.h());
		int tint = net.veloclient.velo.client.gui.glass.GlassPrefs.tint(0x8C0C0C10);
		net.veloclient.velo.client.gui.glass.HudFrost.behind(context, item.x(), item.y(), item.w(), item.h(), r);
		VeloDraw.fillRounded(context, item.x(), item.y(), item.w(), item.h(), r, withAlpha(tint, (int) (((tint >>> 24) & 0xFF) * a / 255f)));
		// soft top sheen
		VeloDraw.fillRounded(context, item.x() + 1, item.y() + 1, item.w() - 2, item.h() / 2 - 1, Math.max(2, r - 1), withAlpha(0xFFFFFF, (int) (a * 0.05f)));
		VeloDraw.strokeRounded(context, item.x(), item.y(), item.w(), item.h(), r, withAlpha(0xFFFFFF, (int) (a * 0.22f)));
		if (item.header()) {
			VeloDraw.fillRounded(context, item.x() + item.w() / 2 - 10, item.y() + item.h() - 2, 20, 2, 1, withAlpha(item.accent(), a));
		} else {
			VeloDraw.fillRounded(context, item.x() + item.w() - 7, item.y() + item.h() / 2 - 2, 4, 4, 2, withAlpha(item.accent(), a));
		}
		HudLook.text(context, item.text(), item.x() + 9, item.y() + (item.h() - 8) / 2, withAlpha(0xFFFFFF, a));
	}

	private static void ellipse(DrawContext context, float cx, float cy, float rx, float ry, int color, float thickness) {
		if (rx < 2 || ry < 2 || (color >>> 24) < 4) {
			return;
		}
		int segments = 48;
		float px = cx + rx;
		float py = cy;
		for (int i = 1; i <= segments; i++) {
			double a = 2 * Math.PI * i / segments;
			float x = (float) (cx + Math.cos(a) * rx);
			float y = (float) (cy + Math.sin(a) * ry);
			VeloDraw.line(context, px, py, x, y, thickness, color);
			px = x;
			py = y;
		}
	}

	/** Back-out ease: overshoots a little, then settles. */
	private static float ease(float t) {
		float c1 = 1.5f;
		float c3 = c1 + 1;
		return (float) (1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2));
	}

	private static float clamp01(float v) {
		return Math.max(0, Math.min(1, v));
	}

	private static int withAlpha(int rgb, int alpha) {
		return Math.max(0, Math.min(255, alpha)) << 24 | (rgb & 0xFFFFFF);
	}
}
