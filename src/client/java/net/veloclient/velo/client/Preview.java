package net.veloclient.velo.client;

/**
 * Unreleased features that need hooks in existing code (interface sounds, the title track, the first-start guide, the
 * one-window Velo menu, new module settings) stay switched off until they ship: {@link #ON} is only true in a dev build,
 * which packs the marker class from src/dev. Flip a feature to always-on when it moves to production.
 */
public final class Preview {

	public static final boolean ON = present("net.veloclient.velo.devbuild.DevBuild");

	private Preview() {
	}

	private static boolean present(String name) {
		try {
			Class.forName(name, false, Preview.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}
}
