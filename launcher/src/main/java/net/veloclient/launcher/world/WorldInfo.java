package net.veloclient.launcher.world;

import java.nio.file.Path;

/** One singleplayer world folder inside a profile's {@code saves/} directory, as read from its {@code level.dat}. */
public record WorldInfo(String folderName, String levelName, Gamemode gamemode, boolean hardcore, Path iconFile, long lastPlayedEpochMillis) {

	public enum Gamemode {
		SURVIVAL, CREATIVE, ADVENTURE, SPECTATOR;

		static Gamemode fromId(int id) {
			return switch (id) {
				case 1 -> CREATIVE;
				case 2 -> ADVENTURE;
				case 3 -> SPECTATOR;
				default -> SURVIVAL;
			};
		}

		public String displayName() {
			String lower = name().toLowerCase(java.util.Locale.ROOT);
			return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
		}
	}
}
