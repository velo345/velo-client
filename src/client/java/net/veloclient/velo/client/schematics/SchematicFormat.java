package net.veloclient.velo.client.schematics;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * The two schematic ecosystems this feature manages: Litematica's own
 * {@code .litematic} format and WorldEdit/Sponge's {@code .schem}. Each
 * carries its own default on-disk folder (fixed by the owning mod, not
 * user-configurable) and Fabric mod id used to detect whether that mod is
 * actually present.
 */
public enum SchematicFormat {

	LITEMATICA("Litematica", "litematica", ".litematic") {
		@Override
		public Path folder() {
			return FabricLoader.getInstance().getGameDir().resolve("schematics");
		}
	},
	WORLDEDIT("WorldEdit", "worldedit", ".schem") {
		@Override
		public Path folder() {
			return FabricLoader.getInstance().getGameDir().resolve("config").resolve("worldedit").resolve("schematics");
		}
	};

	private final String displayName;
	private final String modId;
	private final String extension;

	SchematicFormat(String displayName, String modId, String extension) {
		this.displayName = displayName;
		this.modId = modId;
		this.extension = extension;
	}

	public abstract Path folder();

	public String displayName() {
		return displayName;
	}

	public String modId() {
		return modId;
	}

	/** File extension including the leading dot, e.g. {@code ".litematic"}. */
	public String extension() {
		return extension;
	}

	public boolean modInstalled() {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	/** Whichever format's mod is actually installed in this run - prefers Litematica when both (or neither) are present, per the mod's own load order in the sidebar. */
	public static SchematicFormat defaultFormat() {
		if (LITEMATICA.modInstalled()) {
			return LITEMATICA;
		}
		if (WORLDEDIT.modInstalled()) {
			return WORLDEDIT;
		}
		return LITEMATICA;
	}
}
