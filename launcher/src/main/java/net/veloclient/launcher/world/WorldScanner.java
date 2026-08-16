package net.veloclient.launcher.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Lists the singleplayer worlds in a profile's {@code saves/} folder, newest-played first, for the Datapacks tab's world picker. */
public final class WorldScanner {

	private WorldScanner() {
	}

	public static List<WorldInfo> list(Path savesDir) {
		List<WorldInfo> worlds = new ArrayList<>();
		if (!Files.isDirectory(savesDir)) {
			return worlds;
		}
		try (Stream<Path> stream = Files.list(savesDir)) {
			for (Path worldDir : stream.filter(Files::isDirectory).toList()) {
				readWorld(worldDir).ifPresent(worlds::add);
			}
		} catch (IOException ignored) {
			return worlds;
		}
		worlds.sort(Comparator.comparingLong(WorldInfo::lastPlayedEpochMillis).reversed());
		return worlds;
	}

	private static java.util.Optional<WorldInfo> readWorld(Path worldDir) {
		Path levelDat = worldDir.resolve("level.dat");
		if (!Files.isRegularFile(levelDat)) {
			return java.util.Optional.empty();
		}
		try (var in = Files.newInputStream(levelDat)) {
			Map<String, Object> root = NbtReader.readGzipCompound(in);
			Map<String, Object> data = NbtReader.compound(root, "Data");
			String folderName = worldDir.getFileName().toString();
			String levelName = NbtReader.string(data, "LevelName", folderName);
			WorldInfo.Gamemode gamemode = WorldInfo.Gamemode.fromId(NbtReader.intValue(data, "GameType", 0));
			boolean hardcore = NbtReader.byteAsBoolean(data, "hardcore", false);
			long lastPlayed = data.get("LastPlayed") instanceof Number n ? n.longValue() : 0L;
			Path icon = worldDir.resolve("icon.png");
			return java.util.Optional.of(new WorldInfo(folderName, levelName, gamemode, hardcore,
					Files.isRegularFile(icon) ? icon : null, lastPlayed));
		} catch (Exception e) {
			// A corrupt/unreadable level.dat just means this one world doesn't show up - not worth failing the whole picker over.
			return java.util.Optional.empty();
		}
	}

	public static Path datapacksDir(Path worldDir) {
		return worldDir.resolve("datapacks");
	}
}
