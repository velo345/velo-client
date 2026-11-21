package net.veloclient.velo.client.util;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Lets go of Attack / Use when the mouse button behind them is physically up.
 *
 * <p>Windows players reported "holding left click, or doing something with the window, starts spamming left click
 * until I click again": the button's release never reached the game (lost while Windows held the window in a
 * move/resize loop, or while input polling ran on another thread), so the binding stayed held and the game kept
 * attacking. Windows itself knows the real button state ({@link WindowFront#mouseButtonDown}); after two ticks of
 * disagreement the binding is released, exactly as if the release had arrived.
 *
 * <p>Bindings held on purpose by code (Velo+ helpers, the Rewind AI API) register via {@link #external} and are left
 * alone. Other systems keep their own input handling.
 */
public final class StuckButtons {

	private static final Set<KeyBinding> EXTERNAL = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	private static final int[] mismatch = new int[2];

	private StuckButtons() {
	}

	/** Code holds (or lets go of) {@code binding} itself - don't treat it as a stuck mouse button while held. */
	public static void external(KeyBinding binding, boolean held) {
		if (held) {
			EXTERNAL.add(binding);
		} else {
			EXTERNAL.remove(binding);
		}
	}

	public static void register() {
		if (!WindowFront.windows() || Boolean.getBoolean("velo.noStuckButtonFix")) {
			return;
		}
		ClientTickEvents.START_CLIENT_TICK.register(StuckButtons::tick);
	}

	private static void tick(MinecraftClient client) {
		WindowFront.tickWaitingGrab(client);
		if (client.options == null) {
			return;
		}
		check(0, client.options.attackKey);
		check(1, client.options.useKey);
	}

	private static void check(int slot, KeyBinding binding) {
		// cheapest first: nothing else runs unless the binding is held
		int button;
		if (!pressed(binding) || EXTERNAL.contains(binding) || (button = mouseButton(binding)) < 0) {
			mismatch[slot] = 0;
			return;
		}
		Boolean down = WindowFront.mouseButtonDown(button);
		if (down == null || down) {
			mismatch[slot] = 0;
			return;
		}
		if (++mismatch[slot] >= 2) {
			mismatch[slot] = 0;
			//? if <26.1 {
			binding.setPressed(false);
			//?} else {
			/*binding.setDown(false);
			*///?}
		}
	}

	private static boolean pressed(KeyBinding binding) {
		//? if <26.1 {
		return binding.isPressed();
		//?} else {
		/*return binding.isDown();
		*///?}
	}

	/** 0 left, 1 right, 2 middle for a binding on a mouse button, else -1. */
	private static int mouseButton(KeyBinding binding) {
		var key = KeyBindingHelper.getBoundKeyOf(binding);
		//? if <26.1 {
		if (key.getCategory() != net.minecraft.client.util.InputUtil.Type.MOUSE) {
			return -1;
		}
		int code = key.getCode();
		//?} else {
		/*if (key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
			return -1;
		}
		int code = key.getValue();
		*///?}
		return code == Keys.MOUSE_LEFT ? 0 : code == Keys.MOUSE_RIGHT ? 1 : code == Keys.MOUSE_MIDDLE ? 2 : -1;
	}
}
