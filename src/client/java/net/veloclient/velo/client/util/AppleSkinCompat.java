package net.veloclient.velo.client.util;

import net.fabricmc.loader.api.FabricLoader;
import net.veloclient.velo.VeloClient;

import java.lang.reflect.Method;

/**
 * AppleSkin draws its held-food preview (hunger/saturation/health restored) when vanilla's food and
 * heart methods <em>return</em>. Our Hearts and Hunger modules take those methods over (cancelling at
 * the head), so AppleSkin would never get to draw. After drawing our own rows we call the same AppleSkin
 * handlers it would have called - looked up by reflection so nothing is required, and nothing breaks if
 * AppleSkin changes: the first failure switches this off quietly.
 */
public final class AppleSkinCompat {

	private static boolean resolved;
	private static Object handler;
	private static Method food;
	private static Method health;

	private AppleSkinCompat() {
	}

	/** AppleSkin is installed (its overlays may be drawing). */
	public static boolean present() {
		return ready();
	}

	/**
	 * Cancels AppleSkin's food-bar overlays (hunger restored, saturation, exhaustion) while {@code veto} says so - our
	 * non-vanilla food icons have a different shape, and its overlays would be drawn over them in vanilla's. Through
	 * AppleSkin's own API events (each has a public {@code isCanceled}), registered by reflection so nothing is required.
	 */
	public static void vetoFoodOverlays(java.util.function.BooleanSupplier veto) {
		if (!FabricLoader.getInstance().isModLoaded("appleskin")) {
			return;
		}
		for (String name : new String[] {"HungerRestored", "Saturation", "Exhaustion"}) {
			try {
				Class<?> event = Class.forName("squeek.appleskin.api.event.HUDOverlayEvent$" + name);
				Class<?> handlerType = Class.forName("squeek.appleskin.api.handler.EventHandler");
				java.lang.reflect.Field canceled = event.getField("isCanceled");
				Object listener = java.lang.reflect.Proxy.newProxyInstance(handlerType.getClassLoader(), new Class<?>[] {handlerType},
						(proxy, method, args) -> {
							if (method.getName().equals("interact") && args != null && args.length == 1 && veto.getAsBoolean()) {
								canceled.setBoolean(args[0], true);
							}
							return switch (method.getName()) {
								case "hashCode" -> System.identityHashCode(proxy);
								case "equals" -> proxy == args[0];
								case "toString" -> "VeloAppleSkinVeto";
								default -> null;
							};
						});
				Object fabricEvent = event.getField("EVENT").get(null);
				fabricEvent.getClass().getMethod("register", Object.class).invoke(fabricEvent, listener);
			} catch (ReflectiveOperationException | RuntimeException e) {
				VeloClient.LOGGER.info("Couldn't hook AppleSkin's {} overlay: {}", name, e.toString());
			}
		}
	}

	private static synchronized boolean ready() {
		if (!resolved) {
			resolved = true;
			try {
				if (FabricLoader.getInstance().isModLoaded("appleskin")) {
					Class<?> type = Class.forName("squeek.appleskin.client.HUDOverlayHandler");
					handler = type.getField("INSTANCE").get(null);
					for (Method method : type.getMethods()) {
						if (method.getName().equals("onRenderFood") && method.getParameterCount() == 4) {
							food = method;
						} else if (method.getName().equals("onRenderHealth") && method.getParameterCount() == 11) {
							health = method;
						}
					}
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				VeloClient.LOGGER.info("AppleSkin found but its HUD hooks couldn't be resolved: {}", e.toString());
				handler = null;
			}
		}
		return handler != null;
	}

	/** Same arguments vanilla's food method got: (context, player, top, right). */
	public static void afterFood(Object context, Object player, int top, int right) {
		if (!ready() || food == null) {
			return;
		}
		try {
			food.invoke(handler, context, player, top, right);
		} catch (ReflectiveOperationException | RuntimeException e) {
			food = null;
			VeloClient.LOGGER.info("AppleSkin food overlay disabled: {}", e.toString());
		}
	}

	/** Same arguments vanilla's heart method got. */
	public static void afterHealth(Object context, Object player, int x, int y, int lines, int regeneratingHeartIndex, float maxHealth,
			int lastHealth, int health, int absorption, boolean blinking) {
		if (!ready() || AppleSkinCompat.health == null) {
			return;
		}
		try {
			AppleSkinCompat.health.invoke(handler, context, player, x, y, lines, regeneratingHeartIndex, maxHealth, lastHealth, health, absorption,
					blinking);
		} catch (ReflectiveOperationException | RuntimeException e) {
			AppleSkinCompat.health = null;
			VeloClient.LOGGER.info("AppleSkin health overlay disabled: {}", e.toString());
		}
	}
}
