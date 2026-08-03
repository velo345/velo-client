package net.veloclient.launcher.instance;

/**
 * A profile's icon: either one of {@link BuiltinIcons}' fixed glyph ids, or a
 * custom image the user picked (stored as {@code icon.png} next to the
 * profile's own {@code instance.json}, so {@code value} is unused for
 * {@code CUSTOM} - the file's presence is the source of truth).
 */
public record InstanceIcon(Kind kind, String value) {

	public enum Kind {
		BUILTIN, CUSTOM
	}

	public static InstanceIcon builtin(String id) {
		return new InstanceIcon(Kind.BUILTIN, id);
	}

	public static InstanceIcon custom() {
		return new InstanceIcon(Kind.CUSTOM, "");
	}
}
