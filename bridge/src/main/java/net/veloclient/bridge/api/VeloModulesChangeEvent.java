package net.veloclient.bridge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Set;

/** A Velo player switched modules on or off (e.g. turned on Freelook) - anti-cheat plugins can react to exactly that. */
public final class VeloModulesChangeEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();
	private final Player player;
	private final Set<String> before;
	private final Set<String> now;

	public VeloModulesChangeEvent(Player player, Set<String> before, Set<String> now) {
		super(!org.bukkit.Bukkit.isPrimaryThread());
		this.player = player;
		this.before = before;
		this.now = now;
	}

	public Player getPlayer() {
		return player;
	}

	public Set<String> getBefore() {
		return before;
	}

	public Set<String> getActive() {
		return now;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
