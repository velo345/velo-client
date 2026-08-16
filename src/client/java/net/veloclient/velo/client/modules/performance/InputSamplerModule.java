package net.veloclient.velo.client.modules.performance;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.keybind.KeybindConfig;
import net.veloclient.velo.client.keybind.VeloKeybinds;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Reduces client-side input-to-action latency independent of frame rate.
 *
 * <p><b>Honest scope</b>: this cannot beat the server's own tick rate or
 * influence server-side hit registration/lag compensation - no client mod
 * can. What it actually does is remove client-side scheduling slop between
 * a physical click and when the game notices it. Vanilla polls raw input
 * once per render frame ({@code glfwPollEvents()} in the render loop); when
 * FPS drops, that polling gets sparse and a click sitting between two
 * increasingly-far-apart polls is delayed by however long the stall lasted -
 * the "ghost hit" case where you clicked in time but the game noticed late.
 * {@link InputSamplerThread} independently timestamps the raw button-press
 * edge at up to a few thousand Hz regardless of FPS, and (when "early
 * dispatch" is on) this module proactively calls {@code glfwPollEvents()}
 * again at the start of every client *tick* - which keeps happening at a
 * roughly steady cadence even during a render stall - instead of waiting for
 * the next full (possibly very late) render frame to notice the click.
 */
public final class InputSamplerModule extends AbstractModule implements Configurable, HudModule {

	public static final KeyBinding RESET_STATS_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.velo-client.input-sampler-reset", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, VeloKeybinds.CATEGORY));

	private static final int ROLLING_WINDOW = 64;

	private final HudPosition position = new HudPosition(0.02f, 0.5f);
	private InputSamplerThread thread;

	private int sampleHz = 1000;
	private boolean earlyDispatch = false;
	private boolean showHud = true;
	private boolean trackGhostHits = true;
	private int hudColor = 0xFF55FFAA;

	private final double[] rollingLatenciesMs = new double[ROLLING_WINDOW];
	private int rollingIndex;
	private int rollingCount;
	private double lastLatencyMs;
	private long ghostHitCount;
	private boolean resetKeyWasDown;

	public InputSamplerModule() {
		super("input-sampler", "Input Latency Sub-Tick Sampling",
				"Timestamps mouse clicks off the main thread so latency doesn't depend on frame rate, and (optionally) polls input again at the start of every tick to notice clicks sooner during FPS stalls.",
				ModuleCategory.PERFORMANCE, SafetyTag.ALWAYS_SAFE, false);
		ClientTickEvents.START_CLIENT_TICK.register(this::onTickStart);
	}

	@Override
	public void onEnable() {
		thread = new InputSamplerThread();
		thread.setSampleHz(sampleHz);
		thread.start();
		resetStats();
	}

	@Override
	public void onDisable() {
		if (thread != null) {
			thread.shutdown();
			thread = null;
		}
	}

	private void resetStats() {
		java.util.Arrays.fill(rollingLatenciesMs, 0);
		rollingIndex = 0;
		rollingCount = 0;
		lastLatencyMs = 0;
		ghostHitCount = 0;
	}

	private void onTickStart(MinecraftClient client) {
		if (!isEnabled() || thread == null) {
			return;
		}
		boolean resetPressed = RESET_STATS_KEY.isPressed();
		if (resetPressed && !resetKeyWasDown) {
			resetStats();
		}
		resetKeyWasDown = resetPressed;

		long now = System.nanoTime();
		// Expected frame budget at the current FPS, used only to flag an
		// unusually stale edge as a likely "ghost hit" - never to change
		// what actually happens to the click itself.
		double frameMillis = 1000.0 / Math.max(1, client.getCurrentFps());

		List<Long> edges = new java.util.ArrayList<>(thread.drainAttackEdges());
		edges.addAll(thread.drainUseEdges());
		boolean hadEdge = false;
		for (long edgeNanos : edges) {
			hadEdge = true;
			double latencyMs = (now - edgeNanos) / 1_000_000.0;
			recordLatency(latencyMs);
			if (trackGhostHits && latencyMs > frameMillis * 2.0) {
				ghostHitCount++;
			}
		}
		if (earlyDispatch && hadEdge) {
			// Safe to call anytime from the main thread - this is exactly
			// what vanilla's own render loop calls once per frame; calling
			// it again here just means input gets noticed at the next tick
			// boundary instead of waiting for the next (possibly stalled)
			// render frame.
			GLFW.glfwPollEvents();
		}
	}

	private void recordLatency(double latencyMs) {
		lastLatencyMs = latencyMs;
		rollingLatenciesMs[rollingIndex] = latencyMs;
		rollingIndex = (rollingIndex + 1) % ROLLING_WINDOW;
		rollingCount = Math.min(ROLLING_WINDOW, rollingCount + 1);
	}

	private double averageLatencyMs() {
		if (rollingCount == 0) {
			return 0;
		}
		double sum = 0;
		for (int i = 0; i < rollingCount; i++) {
			sum += rollingLatenciesMs[i];
		}
		return sum / rollingCount;
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		if (!showHud) {
			return;
		}
		TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
		Text line1 = Text.literal(String.format(java.util.Locale.ROOT, "Input: %.1fms (avg %.1fms)", lastLatencyMs, averageLatencyMs()));
		Text line2 = Text.literal(trackGhostHits ? "Ghost hits: " + ghostHitCount : "");
		context.drawTextWithShadow(renderer, line1, x, y, hudColor);
		if (trackGhostHits) {
			context.drawTextWithShadow(renderer, line2, x, y + renderer.fontHeight + 2, hudColor);
		}
	}

	@Override
	public int width() {
		if (!showHud) {
			return 0;
		}
		TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
		int w = renderer.getWidth(String.format(java.util.Locale.ROOT, "Input: %.1fms (avg %.1fms)", lastLatencyMs, averageLatencyMs()));
		if (trackGhostHits) {
			w = Math.max(w, renderer.getWidth("Ghost hits: " + ghostHitCount));
		}
		return w;
	}

	@Override
	public int height() {
		if (!showHud) {
			return 0;
		}
		int lines = trackGhostHits ? 2 : 1;
		return MinecraftClient.getInstance().textRenderer.fontHeight * lines + 2;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Sample Rate", 250, 4000, () -> sampleHz, v -> {
					sampleHz = (int) v;
					if (thread != null) {
						thread.setSampleHz(sampleHz);
					}
				}, v -> (int) v + "Hz"),
				new ConfigField.ToggleField("Early Dispatch (experimental)", () -> earlyDispatch, v -> earlyDispatch = v),
				new ConfigField.ToggleField("Show HUD", () -> showHud, v -> showHud = v),
				new ConfigField.ColorField("HUD Color", () -> hudColor, v -> hudColor = v, true),
				new ConfigField.ToggleField("Track Ghost Hits", () -> trackGhostHits, v -> trackGhostHits = v),
				KeybindConfig.field("Reset Stats", RESET_STATS_KEY));
	}
}
