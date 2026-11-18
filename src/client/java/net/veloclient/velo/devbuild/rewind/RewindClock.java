package net.veloclient.velo.devbuild.rewind;

/** The clip position of the open Rewind replay, for tools in other packages (Lyrics' video mode). */
public final class RewindClock {

	private RewindClock() {
	}

	/** Milliseconds into the selected clip, or -1 when no replay is open (or before 26.2). */
	public static long clipMillis() {
		return RewindPlayer.clipMillis();
	}
}
