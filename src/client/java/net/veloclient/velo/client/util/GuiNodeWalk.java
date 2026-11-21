package net.veloclient.velo.client.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;

/**
 * Field access into vanilla's GUI layer tree (26.x {@code GuiRenderState.Node}, a private class a mixin can't name) for
 * {@code GuiLayerIntersectMixin}'s layer walk. 26.x runs with Mojang's own names, so the fields are found by name;
 * anything missing leaves {@link #READY} false and vanilla's walk runs unchanged.
 */
public final class GuiNodeWalk {

	public static final boolean READY;
	private static final VarHandle UP;
	private static final VarHandle PARENT;
	private static final VarHandle ELEMENTS;
	private static final VarHandle ITEMS;
	private static final VarHandle TEXTS;
	private static final VarHandle PICTURES;
	private static final VarHandle CURRENT;

	static {
		VarHandle up = null, parent = null, elements = null, items = null, texts = null, pictures = null, current = null;
		boolean ready = false;
		try {
			ClassLoader loader = GuiNodeWalk.class.getClassLoader();
			Class<?> state = Class.forName("net.minecraft.client.renderer.state.gui.GuiRenderState", false, loader);
			Class<?> node = Class.forName("net.minecraft.client.renderer.state.gui.GuiRenderState$Node", false, loader);
			MethodHandles.Lookup nodes = MethodHandles.privateLookupIn(node, MethodHandles.lookup());
			up = nodes.findVarHandle(node, "up", node);
			parent = nodes.findVarHandle(node, "parent", node);
			elements = nodes.findVarHandle(node, "elementStates", List.class);
			items = nodes.findVarHandle(node, "itemStates", List.class);
			texts = nodes.findVarHandle(node, "textStates", List.class);
			pictures = nodes.findVarHandle(node, "picturesInPictureStates", List.class);
			current = MethodHandles.privateLookupIn(state, MethodHandles.lookup()).findVarHandle(state, "current", node);
			ready = !Boolean.getBoolean("velo.noGuiNodeWalk");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			// another version / mapping: vanilla's walk
		}
		UP = up;
		PARENT = parent;
		ELEMENTS = elements;
		ITEMS = items;
		TEXTS = texts;
		PICTURES = pictures;
		CURRENT = current;
		READY = ready;
	}

	private GuiNodeWalk() {
	}

	public static Object up(Object node) {
		return UP.get(node);
	}

	public static Object parent(Object node) {
		return PARENT.get(node);
	}

	public static List<?> elements(Object node) {
		return (List<?>) ELEMENTS.get(node);
	}

	public static List<?> items(Object node) {
		return (List<?>) ITEMS.get(node);
	}

	public static List<?> texts(Object node) {
		return (List<?>) TEXTS.get(node);
	}

	public static List<?> pictures(Object node) {
		return (List<?>) PICTURES.get(node);
	}

	public static void setCurrent(Object state, Object node) {
		CURRENT.set(state, node);
	}
}
