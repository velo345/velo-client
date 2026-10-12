package net.veloclient.velo.client.serverpacks;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
*///?}

import java.util.ArrayList;
import java.util.List;

/**
 * What a server shows you, as plain text: the sidebar scoreboard, the tab list (with each
 * player's prefix - levels, stars, ranks, ELO) and floating holograms (generator timers, NPC
 * labels). Server packs read everything through here, so the version differences stay in one place.
 */
public final class ServerData {

	/** A tab list row: the player's real name, what the tab list shows for them, and their ping. */
	public record TabEntry(String name, String shown, int ping) {
	}

	/** A floating label (named armor stand or text display) at a position. */
	public record Hologram(double x, double y, double z, String text) {
	}

	private ServerData() {
	}

	/** Strips legacy {@code §x} color codes some servers still embed in plain strings. */
	public static String plain(String text) {
		return text == null ? "" : text.replaceAll("§[0-9a-fk-orA-FK-OR]", "").strip();
	}

	public static String localName() {
		//? if <26.1 {
		var player = MinecraftClient.getInstance().player;
		//?} else {
		/*var player = Minecraft.getInstance().player;
		*///?}
		return player == null ? "" : player.getGameProfile().name();
	}

	/** {@code [title, line1, line2, ...]} of the sidebar, top to bottom; empty when there's none. */
	public static List<String> sidebar() {
		List<String> lines = new ArrayList<>();
		try {
			//? if <26.1 {
			var world = MinecraftClient.getInstance().world;
			if (world == null) {
				return lines;
			}
			var scoreboard = world.getScoreboard();
			//?} else {
			/*var level = Minecraft.getInstance().level;
			if (level == null) {
				return lines;
			}
			var scoreboard = level.getScoreboard();
			*///?}
			var title = net.veloclient.velo.client.modules.hud.ScoreboardHudModule.sidebarTitle(scoreboard);
			if (title == null) {
				return lines;
			}
			lines.add(plain(title.getString()));
			for (var row : net.veloclient.velo.client.modules.hud.ScoreboardHudModule.sidebarRows(scoreboard)) {
				lines.add(plain(row[0].getString()));
			}
		} catch (RuntimeException ignored) {
			// Scoreboard changing under us - try again next time.
		}
		return lines;
	}

	public static List<TabEntry> tabList() {
		List<TabEntry> entries = new ArrayList<>();
		try {
			//? if <26.1 {
			var handler = MinecraftClient.getInstance().getNetworkHandler();
			if (handler == null) {
				return entries;
			}
			for (var entry : handler.getPlayerList()) {
				String name = entry.getProfile().name();
				var display = entry.getDisplayName();
				String shown = display != null ? display.getString()
						: net.minecraft.scoreboard.Team.decorateName(entry.getScoreboardTeam(), net.minecraft.text.Text.literal(name)).getString();
				entries.add(new TabEntry(name, plain(shown), entry.getLatency()));
			}
			//?} else {
			/*var connection = Minecraft.getInstance().getConnection();
			if (connection == null) {
				return entries;
			}
			for (var info : connection.getOnlinePlayers()) {
				String name = info.getProfile().name();
				var display = info.getTabListDisplayName();
				String shown = display != null ? display.getString()
						: net.minecraft.world.scores.PlayerTeam.formatNameForTeam(info.getTeam(), net.minecraft.network.chat.Component.literal(name)).getString();
				entries.add(new TabEntry(name, plain(shown), info.getLatency()));
			}
			*///?}
		} catch (RuntimeException ignored) {
			// List changing under us.
		}
		return entries;
	}

	/** Named armor stands and text displays within {@code radius} blocks of you. */
	public static List<Hologram> holograms(double radius) {
		List<Hologram> holograms = new ArrayList<>();
		try {
			//? if <26.1 {
			var client = MinecraftClient.getInstance();
			var world = client.world;
			var player = client.player;
			if (world == null || player == null) {
				return holograms;
			}
			var area = player.getBoundingBox().expand(radius);
			for (Entity e : world.getOtherEntities(player, area, e -> !(e instanceof LivingEntity) || e.hasCustomName())) {
				String text = null;
				if (e instanceof net.minecraft.entity.decoration.DisplayEntity.TextDisplayEntity) {
					text = ((net.veloclient.velo.client.mixin.HologramAccessor) e).velo$text().getString();
				} else if (e.hasCustomName() && e.getCustomName() != null) {
					text = e.getCustomName().getString();
				}
				if (text != null && !text.isBlank()) {
					holograms.add(new Hologram(e.getX(), e.getY(), e.getZ(), plain(text)));
				}
			}
			//?} else {
			/*var minecraft = Minecraft.getInstance();
			var level = minecraft.level;
			var player = minecraft.player;
			if (level == null || player == null) {
				return holograms;
			}
			var area = player.getBoundingBox().inflate(radius);
			for (Entity e : level.getEntities(player, area, e -> !(e instanceof LivingEntity) || e.hasCustomName())) {
				String text = null;
				if (e instanceof net.minecraft.world.entity.Display.TextDisplay) {
					text = ((net.veloclient.velo.client.mixin.HologramAccessor) e).velo$text().getString();
				} else if (e.hasCustomName() && e.getCustomName() != null) {
					text = e.getCustomName().getString();
				}
				if (text != null && !text.isBlank()) {
					holograms.add(new Hologram(e.getX(), e.getY(), e.getZ(), plain(text)));
				}
			}
			*///?}
		} catch (RuntimeException | LinkageError ignored) {
			// Accessor not applied - no holograms.
		}
		return holograms;
	}
}
