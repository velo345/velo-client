package net.veloclient.bridge.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A menu that Velo players see as a native Velo window (buttons, lists, text fields, toggles, sliders) and everyone
 * else as a chest menu with the same buttons. Build it, then {@link VeloBridge#openMenu(Player, VeloMenu)}.
 *
 * <pre>{@code
 * VeloMenu menu = VeloMenu.create("warps", "Warps").subtitle("Where to?")
 *     .button("spawn", "Spawn", "compass", click -> click.player().performCommand("spawn"))
 *     .input("name", "Home name", "base")
 *     .button("sethome", "Set home", "red_bed", click -> click.player().performCommand("sethome " + click.value("name")));
 * VeloBridge.openMenu(player, menu);
 * }</pre>
 */
public final class VeloMenu {

	/** A click: who, which component, and every field's value in the menu (inputs, toggles, sliders). */
	public record Click(Player player, String id, Map<String, String> values) {
		public String value(String field) {
			return values.getOrDefault(field, "");
		}
	}

	private final String id;
	private final JsonObject json = new JsonObject();
	private final JsonArray items = new JsonArray();
	private final Map<String, Consumer<Click>> handlers = new LinkedHashMap<>();
	private Consumer<Player> onClose;

	private VeloMenu(String id, String title) {
		this.id = id;
		json.addProperty("t", "menu");
		json.addProperty("id", id);
		json.addProperty("title", title);
		json.add("items", items);
	}

	public static VeloMenu create(String id, String title) {
		return new VeloMenu(id, title);
	}

	public VeloMenu subtitle(String text) {
		json.addProperty("subtitle", text);
		return this;
	}

	/** Accent colour of the window, like {@code "#5aa9ff"}. */
	public VeloMenu color(String hex) {
		json.addProperty("color", hex);
		return this;
	}

	/** Window width in GUI pixels (240-480). */
	public VeloMenu width(int px) {
		json.addProperty("width", Math.max(240, Math.min(480, px)));
		return this;
	}

	public VeloMenu text(String text) {
		JsonObject o = item("text");
		o.addProperty("text", text);
		return this;
	}

	public VeloMenu divider() {
		item("divider");
		return this;
	}

	/** A button; {@code icon} = an item id ("diamond_sword") or null. Style: "primary", "danger" or null. */
	public VeloMenu button(String buttonId, String label, String icon, Consumer<Click> onClick) {
		return button(buttonId, label, icon, null, onClick);
	}

	public VeloMenu button(String buttonId, String label, String icon, String style, Consumer<Click> onClick) {
		JsonObject o = item("button");
		o.addProperty("id", buttonId);
		o.addProperty("label", label);
		if (icon != null) {
			o.addProperty("icon", icon);
		}
		if (style != null) {
			o.addProperty("style", style);
		}
		handlers.put(buttonId, onClick);
		return this;
	}

	/** A row entry with a second line (shops, warps, players...). */
	public VeloMenu entry(String entryId, String label, String detail, String icon, Consumer<Click> onClick) {
		JsonObject o = item("entry");
		o.addProperty("id", entryId);
		o.addProperty("label", label);
		if (detail != null) {
			o.addProperty("detail", detail);
		}
		if (icon != null) {
			o.addProperty("icon", icon);
		}
		handlers.put(entryId, onClick);
		return this;
	}

	public VeloMenu input(String fieldId, String label, String placeholder) {
		JsonObject o = item("input");
		o.addProperty("id", fieldId);
		o.addProperty("label", label);
		if (placeholder != null) {
			o.addProperty("placeholder", placeholder);
		}
		return this;
	}

	public VeloMenu toggle(String fieldId, String label, boolean value) {
		JsonObject o = item("toggle");
		o.addProperty("id", fieldId);
		o.addProperty("label", label);
		o.addProperty("value", value);
		return this;
	}

	public VeloMenu slider(String fieldId, String label, double min, double max, double step, double value) {
		JsonObject o = item("slider");
		o.addProperty("id", fieldId);
		o.addProperty("label", label);
		o.addProperty("min", min);
		o.addProperty("max", max);
		o.addProperty("step", step);
		o.addProperty("value", value);
		return this;
	}

	public VeloMenu onClose(Consumer<Player> handler) {
		this.onClose = handler;
		return this;
	}

	private JsonObject item(String type) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		items.add(o);
		return o;
	}

	// ---- for the plugin

	public String id() {
		return id;
	}

	public JsonObject json() {
		return json;
	}

	public Map<String, Consumer<Click>> handlers() {
		return handlers;
	}

	public Consumer<Player> closeHandler() {
		return onClose;
	}
}
