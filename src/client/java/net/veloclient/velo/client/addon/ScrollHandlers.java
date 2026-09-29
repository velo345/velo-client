package net.veloclient.velo.client.addon;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/**
 * Lets modules (including add-on ones) claim the scroll wheel while they're active, instead of it
 * also changing the hotbar slot - see {@code MouseScrollMixin}. The first active handler wins.
 */
public final class ScrollHandlers {

	private record Handler(BooleanSupplier active, DoubleConsumer onScroll) {
	}

	private static final List<Handler> HANDLERS = new CopyOnWriteArrayList<>();

	private ScrollHandlers() {
	}

	public static void register(BooleanSupplier active, DoubleConsumer onScroll) {
		HANDLERS.add(new Handler(active, onScroll));
	}

	/** True if some handler consumed this scroll. */
	public static boolean dispatch(double vertical) {
		for (Handler handler : HANDLERS) {
			if (handler.active().getAsBoolean()) {
				handler.onScroll().accept(vertical);
				return true;
			}
		}
		return false;
	}
}
