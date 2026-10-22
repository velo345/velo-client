package net.veloclient.velo.client.modules.rendering;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.ArrayList;
import java.util.List;

/**
 * Container Animations: chests, inventories and every other container screen (its window texture included) pop, slide
 * or zoom in when opened and ease out when closed, and items fly from slot to slot when they move - shift-clicks,
 * hotbar-key swaps, Mouse Tweaks drags and scrolls alike. ContainerAnimationsMixin / ContainerBackgroundMixin draw it;
 * the timing and the slot bookkeeping live here.
 *
 * <p>Moves are found by comparing the slots with the last frame instead of hooking each kind of click: a slot that
 * lost an item and another that gained the same item in the same frame is a move, whoever made it.
 */
public final class ContainerAnimationsModule extends AbstractModule implements Configurable {

	/** An item on its way from one slot to another (screen coordinates); {@code toSlot} is hidden until it lands when {@code hide}. */
	public record Flight(Object stack, int fromX, int fromY, int toX, int toY, int toSlot, boolean hide, long start) {
	}

	private static ContainerAnimationsModule instance;
	private String style = "Pop";
	private int durationMs = 180;
	private boolean openAnimation = true;
	private boolean closeAnimation = true;
	private boolean itemMovement = true;
	private int flightMs = 200;
	private int arc = 10;
	private boolean hideUntilLanded = true;

	// Per-screen state (only one container screen is open at a time).
	private static Object screen;
	private static long openedAt;
	private static long closingAt;
	private static long frameAt;
	private static final float[] FRAME = {1f, 0f, 0f};
	public static final List<Flight> FLIGHTS = new ArrayList<>();

	// Last frame's slots: item (identity), count and a copy of the stack (made only when the slot changed).
	private static Object[] lastItems = new Object[0];
	private static int[] lastCounts = new int[0];
	private static Object[] lastStacks = new Object[0];
	private static boolean tracked;

	public ContainerAnimationsModule() {
		super("container-animations", "Container Animations",
				"Containers pop, slide or zoom in when opened and ease out when closed, and items fly to where they land - shift-clicks, hotbar keys and Mouse Tweaks too.",
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
		return Math.max(60, instance == null ? 200 : instance.flightMs);
	}

	public static int arc() {
		return instance == null ? 10 : instance.arc;
	}

	/**
	 * This frame's {scale, offsetX, offsetY} for the screen; computed once per frame (the window texture and the rest
	 * of the screen are drawn in separate passes and must move together).
	 */
	public static float[] frame(Object current) {
		long now = System.nanoTime();
		if (current != screen) {
			screen = current;
			openedAt = now;
			closingAt = 0;
			frameAt = 0;
			FLIGHTS.clear();
			tracked = false;
		}
		if (now - frameAt < 2_000_000L) {
			return FRAME;
		}
		frameAt = now;
		float duration = Math.max(1, instance.durationMs) * 1_000_000f;
		float p = instance.openAnimation ? Math.min(1f, (now - openedAt) / duration) : 1f;
		boolean closing = closingAt != 0;
		if (closing) {
			p = Math.max(0f, 1f - (now - closingAt) / (duration * 0.8f));
		}
		float eased = closing ? p * p * (3 - 2 * p) : instance.style.equals("Zoom") ? cubicOut(p) : backOut(p);
		float scale = 1f;
		float dy = 0f;
		switch (instance.style) {
			case "Slide" -> dy = (1f - eased) * 28f;
			case "Drop" -> dy = -(1f - eased) * 28f;
			case "Zoom" -> scale = 0.6f + 0.4f * eased;
			default -> scale = 0.86f + 0.14f * eased;
		}
		FRAME[0] = scale;
		FRAME[1] = 0f;
		FRAME[2] = dy;
		return FRAME;
	}

	/** Whether the screen is mid-animation (anything to transform at all). */
	public static boolean animating(float[] frame) {
		return frame[0] != 1f || frame[1] != 0f || frame[2] != 0f;
	}

	private static float backOut(float t) {
		float c1 = 1.70158f;
		float c3 = c1 + 1;
		return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
	}

	private static float cubicOut(float t) {
		return 1 - (float) Math.pow(1 - t, 3);
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

	/** A container screen (26.x): moves the pose for this frame's animation; false when there's nothing to move. */
	public interface Window {
		boolean velo$push(Object graphics);
	}

	// ---- Moves ----

	/** One slot as seen this frame, for {@link #track}. */
	public interface SlotView {
		int size();

		/** Whether slot {@code i} counts (creative's item grid scrolls - only your own inventory's slots do there). */
		boolean tracked(int i);

		/** The slot's item (any identity-comparable object), null when empty. */
		Object item(int i);

		int count(int i);

		Object copy(int i);

		boolean sameStack(Object a, int i);

		Object withCount(Object stack, int count);

		int x(int i);

		int y(int i);
	}

	/** Compares the slots with last frame and starts a flight for every item that went from one slot to another. */
	public static void track(SlotView slots, long now) {
		int n = slots.size();
		if (!tracked || lastItems.length != n) {
			lastItems = new Object[n];
			lastCounts = new int[n];
			lastStacks = new Object[n];
			for (int i = 0; i < n; i++) {
				remember(slots, i);
			}
			tracked = true;
			return;
		}
		List<int[]> lost = null;
		List<int[]> gained = null;
		for (int i = 0; i < n; i++) {
			if (!slots.tracked(i)) {
				continue;
			}
			Object item = slots.item(i);
			int count = slots.count(i);
			if (item == lastItems[i] && count == lastCounts[i]) {
				continue;
			}
			boolean sameItem = item == lastItems[i];
			if (lastItems[i] != null && (!sameItem || count < lastCounts[i])) {
				lost = lost == null ? new ArrayList<>() : lost;
				lost.add(new int[] {i, sameItem ? lastCounts[i] - count : lastCounts[i]});
			}
			if (item != null && (!sameItem || count > lastCounts[i])) {
				gained = gained == null ? new ArrayList<>() : gained;
				gained.add(new int[] {i, sameItem ? count - lastCounts[i] : count, sameItem ? 0 : 1});
			}
			if (lost == null || lost.get(lost.size() - 1)[0] != i) {
				remember(slots, i);
			}
		}
		if (lost != null && gained != null && FLIGHTS.size() < 128) {
			for (int[] g : gained) {
				for (int[] l : lost) {
					if (l[1] > 0 && slots.sameStack(lastStacks[l[0]], g[0])) {
						Object stack = slots.withCount(slots.copy(g[0]), g[1]);
						FLIGHTS.add(new Flight(stack, slots.x(l[0]), slots.y(l[0]), slots.x(g[0]), slots.y(g[0]), g[0],
								g[2] == 1 && instance.hideUntilLanded, now));
						l[1] -= g[1];
						break;
					}
				}
			}
		}
		if (lost != null) {
			for (int[] l : lost) {
				remember(slots, l[0]);
			}
		}
	}

	private static void remember(SlotView slots, int i) {
		Object item = slots.item(i);
		lastItems[i] = item;
		lastCounts[i] = slots.count(i);
		lastStacks[i] = item == null ? null : slots.copy(i);
	}

	/** Whether slot {@code index} has an item flying into it that should only appear when it lands. */
	public static boolean hiddenUntilLanded(int index) {
		if (FLIGHTS.isEmpty()) {
			return false;
		}
		for (Flight f : FLIGHTS) {
			if (f.toSlot() == index && f.hide()) {
				return true;
			}
		}
		return false;
	}

	/** Where a flight is now: {x, y}, or null once it has landed. */
	public static int[] flightPosition(Flight f, long now) {
		long length = flightMillis() * 1_000_000L;
		if (now - f.start() > length) {
			return null;
		}
		float t = (now - f.start()) / (float) length;
		float e = t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
		int x = Math.round(f.fromX() + (f.toX() - f.fromX()) * e);
		int y = Math.round(f.fromY() + (f.toY() - f.fromY()) * e - (float) Math.sin(Math.PI * e) * arc());
		return new int[] {x, y};
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Opening style", List.of("Pop", "Slide", "Drop", "Zoom"), () -> style, v -> style = v),
				new ConfigField.ToggleField("Animate opening", () -> openAnimation, v -> openAnimation = v),
				new ConfigField.ToggleField("Animate closing", () -> closeAnimation, v -> closeAnimation = v),
				new ConfigField.SliderField("Open / close speed", 60, 500, () -> durationMs, v -> durationMs = (int) v, v -> (int) v + " ms"),
				new ConfigField.ToggleField("Items fly when moved", () -> itemMovement, v -> itemMovement = v),
				new ConfigField.SliderField("Item flight speed", 60, 500, () -> flightMs, v -> flightMs = (int) v, v -> (int) v + " ms"),
				new ConfigField.SliderField("Flight arc", 0, 24, () -> arc, v -> arc = (int) v, v -> (int) v + " px"),
				new ConfigField.ToggleField("Show item only when it lands", () -> hideUntilLanded, v -> hideUntilLanded = v));
	}
}
