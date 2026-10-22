package net.veloclient.velo.client.modules.rendering;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Container Animations: chests, inventories and every other container screen pop (or slide) in when opened and ease
 * out when closed, and shift-clicked items fly from their slot to where they land. ContainerAnimationsMixin draws it;
 * the timing lives here.
 */
public final class ContainerAnimationsModule extends AbstractModule implements Configurable {

	/** An item on its way from one slot to another (screen coordinates). */
	public record Flight(Object stack, int fromX, int fromY, int toX, int toY, long start) {
	}

	private static ContainerAnimationsModule instance;
	private String style = "Pop";
	private int durationMs = 180;
	private boolean closeAnimation = true;
	private boolean itemMovement = true;

	// Per-screen state (only one container screen is open at a time).
	private static Object screen;
	private static long openedAt;
	private static long closingAt;
	public static final List<Flight> FLIGHTS = new ArrayList<>();

	public ContainerAnimationsModule() {
		super("container-animations", "Container Animations",
				"Containers pop or slide in when opened and ease out when closed, and shift-clicked items fly to where they land.",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	public static boolean active() {
		return instance != null && instance.isEnabled();
	}

	public static boolean closeAnimation() {
		return active() && instance.closeAnimation;
	}

	public static boolean itemMovement() {
		return active() && instance.itemMovement;
	}

	public static long flightMillis() {
		return Math.max(120, instance == null ? 180 : instance.durationMs);
	}

	/** Called at the start of every frame of a container screen; returns {scale, offsetY} for this frame. */
	public static float[] frame(Object current) {
		long now = System.nanoTime();
		if (current != screen) {
			screen = current;
			openedAt = now;
			closingAt = 0;
			FLIGHTS.clear();
		}
		float duration = Math.max(1, instance.durationMs) * 1_000_000f;
		float p = Math.min(1f, (now - openedAt) / duration);
		if (closingAt != 0) {
			p = Math.max(0f, 1f - (now - closingAt) / (duration * 0.8f));
		}
		boolean pop = !"Slide".equals(instance.style);
		float eased = closingAt != 0 ? p * p : backOut(p);
		float scale = pop ? 0.86f + 0.14f * eased : 1f;
		float offset = pop ? 0f : (1f - eased) * 26f;
		return new float[] {scale, offset};
	}

	private static float backOut(float t) {
		float c1 = 1.70158f;
		float c3 = c1 + 1;
		return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
	}

	/** Starts the close animation; true when the caller should wait (and close later via {@link #closeDue}). */
	public static boolean beginClose(Object current) {
		if (current != screen || closingAt != 0) {
			return false;
		}
		closingAt = System.nanoTime();
		return true;
	}

	/** Whether the close animation of this screen has played and it should really close now. */
	public static boolean closeDue(Object current) {
		return current == screen && closingAt != 0 && System.nanoTime() - closingAt > Math.max(1, instance.durationMs) * 800_000L;
	}

	public static boolean closing(Object current) {
		return current == screen && closingAt != 0;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Opening style", List.of("Pop", "Slide"), () -> style, v -> style = v),
				new ConfigField.SliderField("Speed", 100, 400, () -> durationMs, v -> durationMs = (int) v, v -> (int) v + " ms"),
				new ConfigField.ToggleField("Animate closing", () -> closeAnimation, v -> closeAnimation = v),
				new ConfigField.ToggleField("Items fly when shift-clicked", () -> itemMovement, v -> itemMovement = v));
	}
}
