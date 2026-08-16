package net.veloclient.launcher.modpack;

import java.util.List;
import java.util.Map;

/**
 * The {@code modrinth.index.json} manifest at the root of every {@code
 * .mrpack} file (Modrinth's open modpack format - see
 * https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack).
 * Field names match the manifest's own JSON keys exactly so Gson can parse
 * it with no {@code @SerializedName} annotations needed.
 */
public record MrpackIndex(
		String formatVersion,
		String game,
		String versionId,
		String name,
		String summary,
		List<MrpackFile> files,
		Map<String, String> dependencies) {

	public record MrpackFile(
			String path,
			Map<String, String> hashes,
			Map<String, String> env,
			List<String> downloads,
			long fileSize) {

		/** {@code env.client} of "unsupported" means this file is server-only (e.g. a server-side-only mod) - skip it when installing for this client-only launcher. */
		public boolean clientSupported() {
			return env == null || !"unsupported".equals(env.get("client"));
		}
	}

	/** Modrinth's own dependency key for the Minecraft version a pack targets - present on every valid manifest. */
	public String minecraftVersion() {
		return dependencies == null ? null : dependencies.get("minecraft");
	}
}
