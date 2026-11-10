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
	private boolean openAnimation = true;
	// open/close animation: how far the list is rolled down (0 = shut, 1 = open), whether Tab is held, and when we last
	// heard that from the Hud hook (no word from it = the hook didn't apply: then only the opening animates)
	private static float open;
	private static boolean held;
	private static long heldSeen;
	private static long lastFrame;
	private static boolean pushed;
	private static float alpha = 1f;

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

	/**
	 * The Hud asks "is Tab held?" before drawing the list: while the list is still rolling up after you let go, the
	 * answer stays yes so it can finish closing.
	 */
	public static boolean holdOpen(boolean down) {
		held = down;
		heldSeen = System.nanoTime();
		BetterTabListModule m = on();
		if (m == null || !m.openAnimation) {
			return down;
		}
		return down || open > 0.001f;
	}

	/**
	 * Before the tab list draws: it rolls down from the top like a shutter (vertical scale, pivot at its top edge) and
	 * fades in; let go of Tab and it rolls back up and fades out. ~0.18 s each way.
	 */
	public static void beginOpen(DrawContext context, int screenWidth) {
		BetterTabListModule m = on();
		long now = System.nanoTime();
		boolean down = now - heldSeen > 100_000_000L || held;
		if (now - lastFrame > 150_000_000L) {
			open = 0f; // wasn't drawn for a while: start shut
		}
		float dt = Math.min(0.05f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		pushed = false;
		alpha = 1f;
		if (m == null || !m.openAnimation) {
			open = down ? 1f : 0f;
			return;
		}
		open = down ? Math.min(1f, open + dt / 0.18f) : Math.max(0f, open - dt / 0.15f);
		if (open >= 1f) {
			return;
		}
		float e = 1f - (1f - open) * (1f - open) * (1f - open);
		alpha = Math.min(1f, e * 1.3f);
		float pivotY = 10f;
		net.veloclient.velo.client.gui.window.ModuleScenes.push(context);
		net.veloclient.velo.client.gui.window.ModuleScenes.move(context, 0f, pivotY);
		net.veloclient.velo.client.gui.window.ModuleScenes.scale(context, 1f, Math.max(0.001f, e));
		net.veloclient.velo.client.gui.window.ModuleScenes.move(context, 0f, -pivotY);
		pushed = true;
	}

	public static void endOpen(DrawContext context) {
		alpha = 1f;
		if (pushed) {
			pushed = false;
			net.veloclient.velo.client.gui.window.ModuleScenes.pop(context);
		}
	}

	/** A fill colour of the list, faded with the animation. */
	public static int fade(int argb) {
		if (alpha >= 1f) {
			return argb;
		}
		int a = Math.round((argb >>> 24) * alpha);
		return (argb & 0x00FFFFFF) | a << 24;
	}

	/** A text colour of the list, faded (old versions treat alpha 0 as opaque; nearly clear text would pop opaque). */
	public static int fadeText(int argb) {
		if (alpha >= 1f) {
			return argb;
		}
		int a0 = argb >>> 24;
		int a = Math.max(5, Math.round((a0 == 0 ? 255 : a0) * alpha));
		return (argb & 0x00FFFFFF) | a << 24;
	}

	public static boolean heads(boolean vanilla) {
		BetterTabListModule m = on();
		return vanilla || m != null && m.alwaysHeads;
	}

	private static long hiddenCheckedAt;
	private static boolean serverHidesPings;

	/**
	 * The ping to show for one row: your own row gets Velo's real measurement ({@link OwnPing}); for the others the
	 * server's number - unless the server hides them (everyone at 0-1 ms on a real server while your own measured ping is
	 * clearly higher), then -1 ("?") instead of a fake 1 ms. The check runs at most once a second.
	 */
	public static int shownLatency(java.util.UUID id, int server) {
		int own = net.veloclient.velo.client.util.OwnPing.ms();
		//? if <26.1 {
		var client = MinecraftClient.getInstance();
		java.util.UUID me = client.player == null ? null : client.player.getUuid();
		//?} else {
		/*var client = net.minecraft.client.Minecraft.getInstance();
		java.util.UUID me = client.player == null ? null : client.player.getUUID();
		*///?}
		if (own >= 0 && id != null && id.equals(me)) {
			return own;
		}
		long now = System.currentTimeMillis();
		if (now - hiddenCheckedAt > 1000) {
			hiddenCheckedAt = now;
			boolean allTiny = true;
			int count = 0;
			//? if <26.1 {
			var handler = client.getNetworkHandler();
			if (handler != null) {
				for (var entry : handler.getPlayerList()) {
					count++;
					allTiny &= entry.getLatency() <= 1;
				}
			}
			//?} else {
			/*var connection = client.getConnection();
			if (connection != null) {
				for (var entry : connection.getOnlinePlayers()) {
					count++;
					allTiny &= entry.getLatency() <= 1;
				}
			}
			*///?}
			serverHidesPings = count > 1 && allTiny && own > 8 && !net.veloclient.velo.client.util.ClientCompat.isSingleplayer();
		}
		return serverHidesPings ? -1 : server;
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
				new ConfigField.ToggleField("Open Animation (slides down from the top)", () -> openAnimation, v -> openAnimation = v),
				new ConfigField.ChoiceField("Ping", PING_STYLES, () -> pingStyle, v -> pingStyle = v),
				new ConfigField.ToggleField("Color The Ping (green = good, red = bad)", () -> coloredPing, v -> coloredPing = v),
				new ConfigField.ToggleField("Show \"ms\"", () -> showMs, v -> showMs = v),
				new ConfigField.ToggleField("Player Heads On Every Server", () -> alwaysHeads, v -> alwaysHeads = v),
				new ConfigField.SliderField("Rows Per Column", 20, 40, () -> rows, v -> rows = Math.round(v), v -> (int) Math.round(v) + ""));
	}
}
