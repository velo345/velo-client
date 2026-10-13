package net.veloclient.velo.client.redstone;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

/**
 * Redstone Mode: Velo's redstone editor - its own mode, not a module. Enter with R (or the button
 * at the bottom left of the Velo menu), leave the same way. While it's on the normal Velo HUD and
 * every Velo keybind are switched off and only the Redstone overlay shows; the redstone tools work
 * while the Redstone slot (hotbar slot 9 by default) is selected, so every other slot builds and
 * plays normally. Nothing is frozen unless you freeze it (F7) or open a timeline replay.
 */
public final class RedstoneMode {

	private static boolean active;
	private static boolean overlayHidden;

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
		// Undo history, recordings and running jobs belong to one world session.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			Timeline.discardAll();
			RmJobs.cancelAll();
			RmOps.reset();
			TickControls.exit();
			Placements.clear();
			active = false;
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
		RmState.info(RmWorld.singleplayer()
				? "Redstone Mode - select hotbar slot " + (RmSettings.get().toolSlot + 1) + " for the tools, tap Alt for the menu"
				: "Redstone Mode (server) - analysis and schematic previews; editing needs singleplayer");
	}

	public static void exit() {
		active = false;
		RmState.resetTransforms();
		Wiring.clearPreview();
		Timeline.closeReplay();
		TickControls.exit();
		RmSettings.save();
	}

	/** Whether the Redstone slot is the selected hotbar slot (the tools only work then). */
	public static boolean toolHeld() {
		return active && RmWorld.selectedSlot() == RmSettings.get().toolSlot;
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
		RmWorld.setSelectedSlot(RmSettings.get().toolSlot);
	}
}
