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
 * Totem Pops: how many Totems of Undying the players around you popped this life, one clean row each - totem icon,
 * name, count. The player you're fighting sits on top with an accent bar; a fresh pop flashes the row; a death
 * shows the final count for a few seconds. Counting lives in {@link TotemPopTracker}.
 */
public final class TotemPopsModule extends AbstractModule implements HudModule, Configurable {

	private static final int ROW = 18;
	private static final List<String> RESET_AFTER = List.of("1 min", "2 min", "5 min", "Never");

	// Created on first render: item components aren't bound yet while modules register.
	private static ItemStack totem;

	private final HudPosition position = new HudPosition(0.02f, 0.30f);
	private int maxRows = 4;
	private boolean includeSelf = true;
	private boolean showBackground = true;
	private boolean chatNotifications = false;
	private String resetAfter = "2 min";
	private int lastWidth = 110;
	private int lastRows = 2;

	public TotemPopsModule() {
		super("totem-pops", "Totem Pop Counter",
				"Counts how many totems each enemy popped this life - your opponent on top, resets on death.",
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

	private record Row(String name, int pops, boolean dead, boolean opponent, float flash, float fade) {
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		boolean editing = net.veloclient.velo.client.util.ClientCompat.currentScreen() instanceof net.veloclient.velo.client.gui.HudEditScreen;
		long now = System.currentTimeMillis();
		List<Row> rows = new java.util.ArrayList<>();
		for (TotemPopTracker.Entry e : TotemPopTracker.visible(maxRows, includeSelf)) {
			float flash = Math.max(0f, 1f - (now - e.lastPopMillis) / 700f);
			float fade = e.dead() ? Math.max(0f, Math.min(1f, (TotemPopTracker.DEAD_SHOWN_MILLIS - (now - e.diedMillis)) / 800f)) : 1f;
			rows.add(new Row(e.name, e.pops, e.dead(), TotemPopTracker.isOpponent(e), flash, fade));
		}
		if (rows.isEmpty()) {
			if (!editing) {
				return;
			}
			rows.add(new Row("Opponent", 3, false, true, 0f, 1f));
			rows.add(new Row("Teammate", 1, false, false, 0f, 1f));
		}
		if (totem == null) {
			totem = new ItemStack(Items.TOTEM_OF_UNDYING);
		}
		int w = 0;
		for (Row r : rows) {
			w = Math.max(w, 24 + HudLook.measure(r.name) + 10 + HudLook.measure("×" + r.pops) + 6);
		}
		w = Math.max(96, Math.min(200, w));
		lastWidth = w;
		lastRows = rows.size();
		int h = rows.size() * ROW + 4;
		if (HudLook.on() && HudLook.hasBackground()) {
			HudLook.panel(context, x, y, w, h);
		} else if (showBackground) {
			VeloDraw.fillRounded(context, x, y, w, h, 6, 0x90101014);
		}
		int ry = y + 2;
		int accent = HudLook.on() ? HudLook.accent() : 0xFFFFC53D;
		for (Row r : rows) {
			int alpha = Math.round(255 * r.fade);
			if (alpha <= 4) {
				ry += ROW;
				continue;
			}
			if (r.flash > 0f) {
				VeloDraw.fillRounded(context, x + 2, ry, w - 4, ROW, 4, withAlpha(0xFFFFC53D, Math.round(90 * r.flash)));
			}
			if (r.opponent) {
				VeloDraw.fillRounded(context, x + 2, ry + 3, 2, ROW - 6, 1, accent);
			}
			context.drawItemWithoutEntity(totem, x + 5, ry + 1);
			int nameColor = r.dead ? 0xFF8A8A94 : r.opponent ? 0xFFFFFFFF : 0xFFD8D8E0;
			HudLook.draw(context, r.name, x + 24, ry + 5, withAlpha(nameColor, alpha));
			String count = r.dead ? "✖ " + r.pops : "×" + r.pops;
			int countColor = r.dead ? 0xFFFF6B6B : r.flash > 0f ? 0xFFFFE08A : colorFor(r.pops);
			HudLook.draw(context, count, x + w - 6 - HudLook.measure(count), ry + 5, withAlpha(countColor, alpha));
			ry += ROW;
		}
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
		return lastRows * ROW + 4;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Max Players", 1, 8, () -> maxRows, v -> maxRows = (int) Math.round(v), v -> String.valueOf(Math.round(v))),
				new ConfigField.ToggleField("Count Your Own Pops", () -> includeSelf, v -> includeSelf = v),
				new ConfigField.ChoiceField("Reset After No Pops", RESET_AFTER, () -> resetAfter, v -> {
					resetAfter = v;
					syncTracker();
				}),
				new ConfigField.ToggleField("Chat Notifications", () -> chatNotifications, v -> chatNotifications = v),
				new ConfigField.ToggleField("Show Background", () -> showBackground, v -> showBackground = v));
	}
}
