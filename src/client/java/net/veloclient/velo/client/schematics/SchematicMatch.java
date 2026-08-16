package net.veloclient.velo.client.schematics;

/** In-game counterpart to the launcher's identically-named record - a best-effort PhantomMarket identification for a local schematic file. */
public record SchematicMatch(String title, String thumbnailUrl, String postUrl, boolean found) {

	public static SchematicMatch none() {
		return new SchematicMatch(null, null, null, false);
	}
}
