package net.veloclient.velo.dev.tweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Better Tab List (dev build): everyone's ping as a colored number instead of (or next to) the signal bars, player heads
 * on every server (vanilla only draws them on online-mode servers), and more rows before the list splits into columns.
 */
public final class BetterTabListModule extends AbstractModule implements Configurable {

	private static final List<String> PING_STYLES = List.of("Number", "Number + Bars", "Bars");
	private static final String WIDEST = "999ms";

	private static BetterTabListModule instance;

	private String pingStyle = "Number";
	private boolean coloredPing = true;
	private boolean showMs = true;
	private boolean alwaysHeads = true;
	private double rows = 20;

	public BetterTabListModule() {
		super("better-tab-list", "Better Tab List", "Ping as a colored number, player heads on every server and more rows in the tab list.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, false);
		instance = this;
	}

	private static BetterTabListModule on() {
		BetterTabListModule m = instance;
		return m != null && m.isEnabled() ? m : null;
	}

	/** Vanilla's 13px ping column, widened for the number. */
	public static int pingColumn(int vanilla) {
		BetterTabListModule m = on();
		if (m == null || m.pingStyle.equals("Bars")) {
			return vanilla;
		}
		int text = MinecraftClient.getInstance().textRenderer.getWidth(m.showMs ? WIDEST : "999");
		return m.pingStyle.equals("Number") ? text + 3 : vanilla + text + 2;
	}

	/** Rows per column before vanilla starts another column (vanilla: 20). */
	public static int rows(int vanilla) {
		BetterTabListModule m = on();
		return m == null ? vanilla : (int) m.rows;
	}

	public static boolean heads(boolean vanilla) {
		BetterTabListModule m = on();
		return vanilla || m != null && m.alwaysHeads;
	}

	/**
	 * Draws the ping where vanilla puts its signal bars ({@code x + slotWidth - 11}).
	 *
	 * @return true if vanilla's bars should be skipped
	 */
	public static boolean drawPing(DrawContext context, int slotWidth, int x, int y, int latency) {
		BetterTabListModule m = on();
		if (m == null || m.pingStyle.equals("Bars")) {
			return false;
		}
		boolean bars = m.pingStyle.equals("Number + Bars");
		String label = latency < 0 ? "?" : Math.min(latency, 9999) + (m.showMs ? "ms" : "");
		int color = !m.coloredPing ? 0xFFE8E8F0 : latency < 0 ? 0xFFA0A0A8 : latency < 60 ? 0xFF55FF7A : latency < 120 ? 0xFFB6FF55
				: latency < 200 ? 0xFFFFE155 : latency < 350 ? 0xFFFFA040 : 0xFFFF5555;
		var renderer = MinecraftClient.getInstance().textRenderer;
		int right = x + slotWidth - 1 - (bars ? 12 : 0);
		context.drawTextWithShadow(renderer, label, right - renderer.getWidth(label), y, color);
		return !bars;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Ping", PING_STYLES, () -> pingStyle, v -> pingStyle = v),
				new ConfigField.ToggleField("Color The Ping (green = good, red = bad)", () -> coloredPing, v -> coloredPing = v),
				new ConfigField.ToggleField("Show \"ms\"", () -> showMs, v -> showMs = v),
				new ConfigField.ToggleField("Player Heads On Every Server", () -> alwaysHeads, v -> alwaysHeads = v),
				new ConfigField.SliderField("Rows Per Column", 20, 40, () -> rows, v -> rows = Math.round(v), v -> (int) Math.round(v) + ""));
	}
}
