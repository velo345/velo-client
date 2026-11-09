package net.veloclient.bridge.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.veloclient.bridge.api.VeloMenu;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Menus: Velo players get the window ({@code menu} message, clicks come back as {@code menu_action}); everyone else
 * gets a chest with the buttons and entries (text becomes a paper item, fields are left out). Config menus
 * ({@code menus:} in config.yml) are built here too and open with /velomenu &lt;id&gt; or their own command.
 */
public final class Menus implements Listener {

	private static final Map<UUID, VeloMenu> OPEN = new ConcurrentHashMap<>();

	/** Marks our chest inventories. */
	private static final class Holder implements InventoryHolder {
		final VeloMenu menu;
		final Map<Integer, String> slots = new HashMap<>();
		Inventory inventory;

		Holder(VeloMenu menu) {
			this.menu = menu;
		}

		@Override
		public Inventory getInventory() {
			return inventory;
		}
	}

	public static void open(Player p, VeloMenu menu) {
		OPEN.put(p.getUniqueId(), menu);
		if (Players.isVelo(p.getUniqueId())) {
			Protocol.send(p, menu.json());
			return;
		}
		Tasks.forPlayer(p, () -> p.openInventory(chest(menu)));
	}

	public static void close(Player p) {
		VeloMenu m = OPEN.remove(p.getUniqueId());
		if (m == null) {
			return;
		}
		if (Players.isVelo(p.getUniqueId())) {
			JsonObject c = Protocol.message("menu_close");
			c.addProperty("id", m.id());
			Protocol.send(p, c);
		} else {
			Tasks.forPlayer(p, () -> {
				if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
					p.closeInventory();
				}
			});
		}
	}

	/** {@code menu_action} from a Velo player. */
	public static void action(Player p, JsonObject m) {
		VeloMenu menu = OPEN.get(p.getUniqueId());
		if (menu == null || !menu.id().equals(str(m, "menu"))) {
			return;
		}
		Map<String, String> values = new HashMap<>();
		if (m.has("values") && m.get("values").isJsonObject()) {
			for (var e : m.getAsJsonObject("values").entrySet()) {
				if (values.size() < 40 && e.getValue().isJsonPrimitive()) {
					String v = e.getValue().getAsString();
					values.put(e.getKey(), v.length() > 256 ? v.substring(0, 256) : v);
				}
			}
		}
		var handler = menu.handlers().get(str(m, "id"));
		if (handler != null) {
			Tasks.forPlayer(p, () -> handler.accept(new VeloMenu.Click(p, str(m, "id"), values)));
		}
	}

	public static void closed(Player p, JsonObject m) {
		VeloMenu menu = OPEN.get(p.getUniqueId());
		if (menu != null && menu.id().equals(str(m, "menu"))) {
			OPEN.remove(p.getUniqueId());
			if (menu.closeHandler() != null) {
				Tasks.forPlayer(p, () -> menu.closeHandler().accept(p));
			}
		}
	}

	public static void forget(UUID uuid) {
		OPEN.remove(uuid);
	}

	// ---- chest fallback

	private static Inventory chest(VeloMenu menu) {
		Holder holder = new Holder(menu);
		List<JsonObject> shown = new ArrayList<>();
		for (JsonElement e : menu.json().getAsJsonArray("items")) {
			JsonObject o = e.getAsJsonObject();
			String type = str(o, "type");
			if (type.equals("button") || type.equals("entry") || type.equals("text")) {
				shown.add(o);
			}
		}
		int rows = Math.max(1, Math.min(6, (shown.size() + 8) / 9));
		String title = ChatColor.translateAlternateColorCodes('&', str(menu.json(), "title"));
		Inventory inv = Bukkit.createInventory(holder, rows * 9, title.length() > 32 ? title.substring(0, 32) : title);
		holder.inventory = inv;
		int slot = 0;
		for (JsonObject o : shown) {
			if (slot >= rows * 9) {
				break;
			}
			String type = str(o, "type");
			Material mat = material(str(o, "icon"), type.equals("text") ? Material.PAPER : Material.STONE_BUTTON);
			ItemStack item = new ItemStack(mat);
			ItemMeta meta = item.getItemMeta();
			if (meta != null) {
				String label = type.equals("text") ? str(o, "text") : str(o, "label");
				String[] lines = ChatColor.translateAlternateColorCodes('&', label).split("\n");
				meta.setDisplayName(ChatColor.RESET + lines[0]);
				List<String> lore = new ArrayList<>();
				for (int i = 1; i < lines.length; i++) {
					lore.add(ChatColor.GRAY + lines[i]);
				}
				if (o.has("detail")) {
					lore.add(ChatColor.GRAY + ChatColor.translateAlternateColorCodes('&', str(o, "detail")));
				}
				meta.setLore(lore);
				item.setItemMeta(meta);
			}
			inv.setItem(slot, item);
			if (!type.equals("text")) {
				holder.slots.put(slot, str(o, "id"));
			}
			slot++;
		}
		return inv;
	}

	private static Material material(String id, Material fallback) {
		if (id == null || id.isBlank()) {
			return fallback;
		}
		Material m = Material.matchMaterial(id);
		return m == null || !m.isItem() ? fallback : m;
	}

	@EventHandler
	public void onClick(InventoryClickEvent event) {
		if (!(event.getInventory().getHolder() instanceof Holder holder) || !(event.getWhoClicked() instanceof Player p)) {
			return;
		}
		event.setCancelled(true);
		if (event.getClickedInventory() != event.getInventory()) {
			return;
		}
		String id = holder.slots.get(event.getSlot());
		var handler = id == null ? null : holder.menu.handlers().get(id);
		if (handler != null) {
			handler.accept(new VeloMenu.Click(p, id, Map.of()));
		}
	}

	@EventHandler
	public void onDrag(InventoryDragEvent event) {
		if (event.getInventory().getHolder() instanceof Holder) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onClose(InventoryCloseEvent event) {
		if (event.getInventory().getHolder() instanceof Holder holder && event.getPlayer() instanceof Player p) {
			VeloMenu current = OPEN.get(p.getUniqueId());
			if (current == holder.menu) {
				OPEN.remove(p.getUniqueId());
				if (holder.menu.closeHandler() != null) {
					holder.menu.closeHandler().accept(p);
				}
			}
		}
	}

	// ---- config menus

	/**
	 * <pre>
	 * menus:
	 *   warps:
	 *     title: "&bWarps"
	 *     subtitle: "Where to?"
	 *     command: warps            # optional: /warps opens it
	 *     permission: ""            # optional
	 *     items:
	 *       - { type: text, text: "Pick a place" }
	 *       - { type: button, id: spawn, label: "Spawn", icon: compass, style: primary, action: "command:/spawn ; close" }
	 *       - { type: entry, id: shop, label: "Shop", detail: "Buy and sell", icon: emerald, action: "menu:shop" }
	 *       - { type: input, id: name, label: "Home name", placeholder: "base" }
	 *       - { type: button, id: sethome, label: "Set home", action: "command:/sethome {name}" }
	 * </pre>
	 */
	public static VeloMenu fromConfig(String id, ConfigurationSection s, Player p, String serverName) {
		VeloMenu menu = VeloMenu.create(id, Placeholders.apply(s.getString("title", id), p, serverName));
		if (s.contains("subtitle")) {
			menu.subtitle(Placeholders.apply(s.getString("subtitle"), p, serverName));
		}
		if (s.contains("color")) {
			menu.color(s.getString("color"));
		}
		if (s.contains("width")) {
			menu.width(s.getInt("width"));
		}
		for (Map<?, ?> raw : s.getMapList("items")) {
			Map<String, Object> it = new HashMap<>();
			raw.forEach((k, v) -> it.put(String.valueOf(k), v));
			String type = String.valueOf(it.getOrDefault("type", "button"));
			String itemId = String.valueOf(it.getOrDefault("id", "i" + it.hashCode()));
			String label = Placeholders.apply(String.valueOf(it.getOrDefault("label", "")), p, serverName);
			String icon = it.containsKey("icon") ? String.valueOf(it.get("icon")) : null;
			String action = it.containsKey("action") ? String.valueOf(it.get("action")) : "";
			String perm = it.containsKey("permission") ? String.valueOf(it.get("permission")) : null;
			if (perm != null && !perm.isBlank() && !p.hasPermission(perm)) {
				continue;
			}
			switch (type) {
				case "text" -> menu.text(Placeholders.apply(String.valueOf(it.getOrDefault("text", "")), p, serverName));
				case "divider" -> menu.divider();
				case "entry" -> menu.entry(itemId, label, it.containsKey("detail") ? Placeholders.apply(String.valueOf(it.get("detail")), p, serverName) : null, icon,
						click -> Actions.run(click.player(), action, click.values()));
				case "input" -> menu.input(itemId, label, it.containsKey("placeholder") ? String.valueOf(it.get("placeholder")) : null);
				case "toggle" -> menu.toggle(itemId, label, Boolean.parseBoolean(String.valueOf(it.getOrDefault("value", "false"))));
				case "slider" -> menu.slider(itemId, label, num(it.get("min"), 0), num(it.get("max"), 10), num(it.get("step"), 1), num(it.get("value"), 0));
				default -> menu.button(itemId, label, icon, it.containsKey("style") ? String.valueOf(it.get("style")) : null,
						click -> Actions.run(click.player(), action, click.values()));
			}
		}
		return menu;
	}

	private static double num(Object o, double fallback) {
		try {
			return o == null ? fallback : Double.parseDouble(String.valueOf(o));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static String str(JsonObject o, String k) {
		return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
	}
}
