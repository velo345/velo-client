package net.veloclient.velo.client.redstone;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

/**
 * Redstone Mode: Velo's redstone editor - its own mode, not a module. Enter with R (or the button
 * at the bottom left of the Velo menu), leave the same way. While it's on the normal Velo HUD and
 * every Velo keybind are switched off and only the Redstone overlay shows; the redstone tools work
 * while the Redstone tool slot is selected - an extra "slot 0" right of the hotbar, picked with 0
 * or by scrolling past either end of the hotbar (like Axiom's tool slot, which is switched off
 * while the mode is on) - so every normal slot builds and plays normally. Nothing is frozen unless
 * you freeze it (F7) or open a timeline replay.
 */
public final class RedstoneMode {

	private static boolean active;
	private static boolean overlayHidden;
	/** The Redstone tool slot (slot 0, right of the hotbar) is selected instead of a hotbar slot. */
	private static boolean toolSlot;
	private static int lastHotbarSlot = -1;
	private static int lastHotbarKeys;

	private RedstoneMode() {
	}

	public static void init() {
		RmKeys.init();
		RmSettings.get();
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (RmKeys.TOGGLE.wasPressed()) {
				toggle();
			}
			RmInput.tick();
		});
		WorldRenderEvents.BEFORE_DEBUG_RENDER.register(context -> RmRender.world());
		RmBlockRender.register();
		// Undo history, recordings and running jobs belong to one world session.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			Timeline.discardAll();
			RmJobs.cancelAll();
			RmOps.reset();
			TickControls.exit();
			Placements.clear();
			active = false;
			toolSlot = false;
		});
	}

	public static boolean active() {
		return active;
	}

	/** F9: hide the Redstone overlay panels (previews and the selection stay). */
	public static boolean overlayHidden() {
		return overlayHidden;
	}

	public static void toggleOverlay() {
		overlayHidden = !overlayHidden;
	}

	public static void toggle() {
		if (active) {
			exit();
		} else {
			enter();
		}
	}

	public static void enter() {
		active = true;
		overlayHidden = false;
		RmState.resetTransforms();
		toolSlot = true;
		lastHotbarSlot = RmWorld.selectedSlot();
		AxiomCompat.leaveToolSlot();
		RmState.info(RmWorld.singleplayer()
				? "Redstone Mode - tools are in slot 0 (press 0 or scroll past the hotbar), tap Alt for the menu"
				: "Redstone Mode (server) - analysis and schematic previews; editing needs singleplayer");
	}

	public static void exit() {
		active = false;
		toolSlot = false;
		RmState.resetTransforms();
		Wiring.clearPreview();
		Timeline.closeReplay();
		TickControls.exit();
		RmSettings.save();
	}

	/** Whether the Redstone tool slot is selected (the tools only work then). */
	public static boolean toolHeld() {
		return active && toolSlot;
	}

	/** Selects the tool slot ({@code true}) or goes back to the normal hotbar slot. */
	public static void setToolSlot(boolean selected) {
		if (selected && !toolSlot) {
			AxiomCompat.leaveToolSlot();
		}
		toolSlot = selected && active;
		lastHotbarSlot = RmWorld.selectedSlot();
	}

	/**
	 * Client tick: 0 selects the tool slot, a hotbar key (1-9) or any other change of the selected
	 * hotbar slot (pick block, another mod) leaves it.
	 */
	static void tickSlot() {
		boolean zero = false;
		while (RmKeys.TOOL_SLOT.wasPressed()) {
			zero = true;
		}
		if (!active) {
			return;
		}
		if (zero) {
			setToolSlot(true);
		}
		int current = RmWorld.selectedSlot();
		int keys = RmWorld.hotbarKeysDown();
		boolean pressed = (keys & ~lastHotbarKeys) != 0;
		lastHotbarKeys = keys;
		if (pressed || current != lastHotbarSlot) {
			if (toolSlot && !zero) {
				toolSlot = false;
			}
		}
		lastHotbarSlot = current;
	}

	/**
	 * Scrolling with the tool slot in the cycle: slot 9 -&gt; tool slot -&gt; slot 1 and back. True when
	 * this scroll was used (it moved into, out of or past the tool slot).
	 */
	static boolean scrollHotbar(double amount) {
		int current = RmWorld.selectedSlot();
		boolean right = amount < 0; // vanilla: scrolling down moves right
		if (toolSlot) {
			toolSlot = false;
			RmWorld.setSelectedSlot(right ? 0 : 8);
			lastHotbarSlot = RmWorld.selectedSlot();
			return true;
		}
		if (right && current == 8 || !right && current == 0) {
			setToolSlot(true);
			return true;
		}
		return false;
	}

	/** Whether a scroll would touch the tool slot (so the hotbar scroll must go through {@link #scrollHotbar}). */
	static boolean scrollReachesToolSlot() {
		int current = RmWorld.selectedSlot();
		return toolSlot || current == 0 || current == 8;
	}

	/** Picks a tool and jumps to the Redstone slot. */
	public static void selectTool(RmState.Tool tool) {
		if (RmState.tool != tool) {
			RmState.tool = tool;
			RmState.resetTransforms();
			Wiring.clearPreview();
			RmState.wireStart = null;
			RmState.wireEnd = null;
		}
		setToolSlot(true);
	}
}
