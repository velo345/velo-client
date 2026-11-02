package net.veloclient.bridge.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A player using Velo Client joined and said hello - from here on every {@link VeloBridge} call reaches them. */
public final class VeloPlayerJoinEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();
	private final Player player;
	private final VeloPlayer velo;

	public VeloPlayerJoinEvent(Player player, VeloPlayer velo) {
		super(!org.bukkit.Bukkit.isPrimaryThread());
		this.player = player;
		this.velo = velo;
	}

	public Player getPlayer() {
		return player;
	}

	public VeloPlayer getVelo() {
		return velo;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}
