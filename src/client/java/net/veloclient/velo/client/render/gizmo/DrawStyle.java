package net.veloclient.velo.client.render.gizmo;

//? if <1.21.11 {
/*/^* 1.21.10 stand-in for 1.21.11's DrawStyle: an outline colour (+ width), a fill colour, or both. ^/
public record DrawStyle(int stroke, float width, int fill) {

	public static DrawStyle stroked(int color) {
		return new DrawStyle(color, 1f, 0);
	}

	public static DrawStyle stroked(int color, float width) {
		return new DrawStyle(color, width, 0);
	}

	public static DrawStyle filled(int color) {
		return new DrawStyle(0, 0f, color);
	}

	public static DrawStyle filledAndStroked(int stroke, float width, int fill) {
		return new DrawStyle(stroke, width, fill);
	}
}
*///?}
