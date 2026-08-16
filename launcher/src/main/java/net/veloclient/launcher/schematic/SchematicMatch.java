package net.veloclient.launcher.schematic;

/** A best-effort PhantomMarket identification for a local schematic file, keyed by filename in {@link SchematicMatchCache}. */
public record SchematicMatch(String title, String thumbnailUrl, String postUrl, boolean found) {

	public static SchematicMatch none() {
		return new SchematicMatch(null, null, null, false);
	}
}
