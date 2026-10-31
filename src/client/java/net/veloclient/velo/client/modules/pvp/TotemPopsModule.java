package net.veloclient.velo.client.modules.pvp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudLook;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.pvp.TotemPopTracker;
import net.veloclient.velo.client.social.VeloChat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Totem Pop Counter: a card with the number that matters, big - either how many totems your opponent popped this
 * life (with the other players you fought below it) or how many you popped and how many you still carry. A fresh pop
 * flashes and bounces the totem; a death shows the final count for a few seconds. Counting lives in
 * {@link TotemPopTracker}.
 */
public final class TotemPopsModule extends AbstractModule implements HudModule, Configurable {

	private static final int ROW = 18;
	private static final int CARD_H = 46;
	private static final String ENEMY = "Enemy pops";
	private static final String MINE = "My pops";
	private static final List<String> MODES = List.of(ENEMY, MINE);
	private static final List<String> RESET_AFTER = List.of("1 min", "2 min", "5 min", "Never");

	// Created on first render: item components aren't bound yet while modules register.
	private static ItemStack totem;

	private final HudPosition position = new HudPosition(0.02f, 0.30f);
	private String mode = ENEMY;
	private int maxRows = 4;
	private boolean showBackground = true;
	private boolean chatNotifications = false;
	private String resetAfter = "2 min";
	private int lastWidth = 150;
	private int lastHeight = CARD_H;

	public TotemPopsModule() {
		super("totem-pops", "Totem Pop Counter",
				"How many totems your opponent popped this life - or how many you popped and have left. Resets on death.",
				ModuleCategory.PVP, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		syncTracker();
	}

	@Override
	public void onDisable() {
		TotemPopTracker.configure(false, 0, null);
	}

	private void syncTracker() {
		if (!isEnabled()) {
			return;
		}
		long reset = switch (resetAfter) {
			case "1 min" -> 60_000L;
			case "5 min" -> 300_000L;
			case "Never" -> Long.MAX_VALUE;
			default -> 120_000L;
		};
		TotemPopTracker.configure(true, reset, this::onChange);
	}

	private void onChange(TotemPopTracker.Entry e, boolean death) {
		if (!chatNotifications || e.self) {
			return;
		}
		String totems = e.pops == 1 ? "1 totem" : e.pops + " totems";
		VeloChat.info(death ? e.name + " died after popping " + totems + "." : e.name + " popped " + totems + ".");
	}

	@Override
	public HudPosition position() {
		return position;
	}

	/** One player's count as drawn: flash 1 -> 0 after a pop, fade 1 -> 0 as a finished count leaves. */
	private record Row(String name, int pops, boolean dead, boolean opponent, float flash, float fade, long lastPop) {
	}

	private static Row row(TotemPopTracker.Entry e, long now) {
		float flash = Math.max(0f, 1f - (now - e.lastPopMillis) / 700f);
		float fade = e.dead() ? Math.max(0f, Math.min(1f, (TotemPopTracker.DEAD_SHOWN_MILLIS - (now - e.diedMillis)) / 800f)) : 1f;
		return new Row(e.name, e.pops, e.dead(), TotemPopTracker.isOpponent(e), flash, fade, e.lastPopMillis);
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		boolean editing = net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		if (totem == null) {
			totem = new ItemStack(Items.TOTEM_OF_UNDYING);
		}
		long now = System.currentTimeMillis();
		if (MINE.equals(mode)) {
			renderMine(context, x, y, now, editing);
		} else {
			renderEnemies(context, x, y, now, editing);
		}
	}

	private void renderEnemies(DrawContext context, int x, int y, long now, boolean editing) {
		List<Row> rows = new java.util.ArrayList<>();
		for (TotemPopTracker.Entry e : TotemPopTracker.visible(maxRows, false)) {
			rows.add(row(e, now));
		}
		if (rows.isEmpty()) {
			if (!editing) {
				lastHeight = 0;
				return;
			}
			rows.add(new Row("Opponent", 3, false, true, 0f, 1f, now - 4000));
			rows.add(new Row("Teammate", 1, false, false, 0f, 1f, now - 20000));
		}
		Row top = rows.get(0);
		String sub = top.dead ? "died after " + plural(top.pops) : "last pop " + ago(now - top.lastPop);
		int w = cardWidth(top.name, sub);
		for (Row r : rows.subList(1, rows.size())) {
			w = Math.max(w, 24 + HudLook.measure(r.name) + 10 + HudLook.measure("×" + r.pops) + 8);
		}
		w = Math.min(220, w);
		int h = CARD_H + (rows.size() - 1) * ROW + (rows.size() > 1 ? 4 : 0);
		lastWidth = w;
		lastHeight = h;
		background(context, x, y, w, h);
		card(context, x, y, w, top.name, top.pops, top.dead ? "DIED" : top.opponent ? "OPPONENT" : "POPS",
				sub, top.dead ? 0xFFFF6B6B : 0xFF9A9AA6, top.flash, top.fade, top.dead);
		int ry = y + CARD_H;
		if (rows.size() > 1) {
			VeloDraw.fillRounded(context, x + 8, ry, w - 16, 1, 0, 0x22FFFFFF);
			ry += 3;
		}
		for (Row r : rows.subList(1, rows.size())) {
			smallRow(context, x, ry, w, r);
			ry += ROW;
		}
	}

	private void renderMine(DrawContext context, int x, int y, long now, boolean editing) {
		TotemPopTracker.Entry mine = null;
		for (TotemPopTracker.Entry e : TotemPopTracker.visible(16, true)) {
			if (e.self) {
				mine = e;
				break;
			}
		}
		int carried = net.veloclient.velo.client.pvp.PvpCompat.totemsCarried();
		if (carried < 0 && !editing) {
			lastHeight = 0;
			return;
		}
		if (editing && mine == null && carried <= 0) {
			carried = 4;
		}
		Row r = mine != null ? row(mine, now) : new Row("You", 0, false, false, 0f, 1f, 0);
		if (mine == null && carried == 0 && !editing) {
			// Nothing to say: no totems on you and none popped.
			lastHeight = 0;
			return;
		}
		String left = carried == 1 ? "1 totem left" : carried + " totems left";
		int subColor = carried == 0 ? 0xFFFF5C5C : carried == 1 ? 0xFFFFA94D : 0xFF9A9AA6;
		String sub = r.dead ? "died after " + plural(r.pops) : left;
		int w = Math.min(220, cardWidth("Your pops", sub));
		lastWidth = w;
		lastHeight = CARD_H;
		background(context, x, y, w, CARD_H);
		card(context, x, y, w, "Your pops", r.pops, r.dead ? "DIED" : "THIS LIFE", sub, r.dead ? 0xFFFF6B6B : subColor, r.flash, 1f, r.dead);
	}

	private static int cardWidth(String name, String sub) {
		// Name + badge on one line, the line under the count, or the count with its pips - whichever is widest.
		int top = HudLook.measure(name) + Math.round(HudLook.measure("OPPONENT") * 0.7f) + 18;
		int text = Math.max(top, Math.max(HudLook.measure(sub), 90));
		return Math.max(150, 46 + text + 7);
	}

	private void background(DrawContext context, int x, int y, int w, int h) {
		if (HudLook.on() && HudLook.hasBackground()) {
			HudLook.panel(context, x, y, w, h);
		} else if (showBackground) {
			VeloDraw.fillRounded(context, x, y, w, h, 8, 0xA00E0E12);
			VeloDraw.strokeRounded(context, x, y, w, h, 8, 0x18FFFFFF);
		}
	}

	/**
	 * The big card: a glowing totem tile (bouncing on a fresh pop), the name and a small badge on top, the count big
	 * underneath with one pip per pop, and a line under it.
	 */
	private static void card(DrawContext context, int x, int y, int w, String name, int pops, String badge, String sub, int subColor,
			float flash, float fade, boolean dead) {
		int alpha = Math.round(255 * fade);
		if (alpha <= 4) {
			return;
		}
		int gold = 0xFFFFC53D;
		int tone = dead ? 0xFFFF6B6B : colorFor(pops);
		// Totem tile.
		int tx = x + 7, ty = y + 7, ts = 32;
		VeloDraw.fillRounded(context, tx, ty, ts, ts, 8, withAlpha(gold, Math.round((38 + 90 * flash) * fade)));
		VeloDraw.strokeRounded(context, tx, ty, ts, ts, 8, withAlpha(gold, Math.round((70 + 120 * flash) * fade)));
		float bounce = 1.5f + 0.35f * flash * flash;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(tx + ts / 2f, ty + ts / 2f);
		context.getMatrices().scale(bounce, bounce);
		context.getMatrices().translate(-8f, -8f);
		context.drawItemWithoutEntity(totem, 0, 0);
		context.getMatrices().popMatrix();
		// Name and badge.
		int textX = x + 46;
		HudLook.draw(context, name, textX, y + 6, withAlpha(dead ? 0xFF8A8A94 : 0xFFFFFFFF, alpha));
		int bw = Math.round(HudLook.measure(badge) * 0.7f) + 8;
		int bx = x + w - 7 - bw;
		VeloDraw.fillRounded(context, bx, y + 5, bw, 11, 5, withAlpha(dead ? 0xFFFF6B6B : gold, Math.round(46 * fade)));
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(bx + 4, y + 7.5f);
		context.getMatrices().scale(0.7f, 0.7f);
		HudLook.draw(context, badge, 0, 0, withAlpha(dead ? 0xFFFF8A8A : 0xFFFFE08A, alpha));
		context.getMatrices().popMatrix();
		// The count, big.
		String count = String.valueOf(pops);
		float scale = 2f + 0.25f * flash;
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(textX, y + 17);
		context.getMatrices().scale(scale, scale);
		HudLook.draw(context, count, 0, 0, withAlpha(flash > 0f ? 0xFFFFE08A : tone, alpha));
		context.getMatrices().popMatrix();
		// One pip per pop (up to 10), next to the number.
		int px = textX + Math.round(HudLook.measure(count) * 2f) + 6;
		int shown = Math.min(10, pops);
		for (int i = 0; i < shown; i++) {
			VeloDraw.fillRounded(context, px + i * 6, y + 23, 4, 7, 2, withAlpha(tone, alpha));
		}
		if (pops > 10) {
			HudLook.draw(context, "+" + (pops - 10), px + 62, y + 22, withAlpha(tone, alpha));
		}
		HudLook.draw(context, sub, textX, y + 35, withAlpha(subColor, alpha));
	}

	private static void smallRow(DrawContext context, int x, int ry, int w, Row r) {
		int alpha = Math.round(255 * r.fade);
		if (alpha <= 4) {
			return;
		}
		if (r.flash > 0f) {
			VeloDraw.fillRounded(context, x + 3, ry, w - 6, ROW, 4, withAlpha(0xFFFFC53D, Math.round(80 * r.flash)));
		}
		context.drawItemWithoutEntity(totem, x + 6, ry + 1);
		HudLook.draw(context, r.name, x + 25, ry + 5, withAlpha(r.dead ? 0xFF8A8A94 : 0xFFD8D8E0, alpha));
		String count = r.dead ? "✖ " + r.pops : "×" + r.pops;
		int color = r.dead ? 0xFFFF6B6B : r.flash > 0f ? 0xFFFFE08A : colorFor(r.pops);
		HudLook.draw(context, count, x + w - 8 - HudLook.measure(count), ry + 5, withAlpha(color, alpha));
	}

	private static String plural(int pops) {
		return pops == 1 ? "1 pop" : pops + " pops";
	}

	private static String ago(long millis) {
		long s = Math.max(0, millis / 1000);
		return s < 60 ? s + "s ago" : s / 60 + "m ago";
	}

	/** Green for a pop or two, amber, then red - how deep into their stack they are. */
	private static int colorFor(int pops) {
		return pops >= 5 ? 0xFFFF5C5C : pops >= 3 ? 0xFFFFA94D : 0xFF6FE39A;
	}

	private static int withAlpha(int argb, int alpha) {
		int a = Math.round(((argb >>> 24) & 0xFF) * (alpha / 255f));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	@Override
	public int width() {
		return lastWidth;
	}

	@Override
	public int height() {
		return Math.max(CARD_H, lastHeight);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Count", MODES, () -> mode, v -> mode = v),
				new ConfigField.SliderField("Max Players", 1, 8, () -> maxRows, v -> maxRows = (int) Math.round(v), v -> String.valueOf(Math.round(v))),
				new ConfigField.ChoiceField("Reset After No Pops", RESET_AFTER, () -> resetAfter, v -> {
					resetAfter = v;
					syncTracker();
				}),
				new ConfigField.ToggleField("Chat Notifications", () -> chatNotifications, v -> chatNotifications = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
