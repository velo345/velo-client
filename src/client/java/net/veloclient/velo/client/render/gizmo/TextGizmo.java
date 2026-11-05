package net.veloclient.velo.client.render.gizmo;

//? if <1.21.11 {
/*/^* 1.21.10 stand-in for 1.21.11's TextGizmo (only its Style is used). ^/
public final class TextGizmo {

	private TextGizmo() {
	}

	public record Style(int color, float scale) {

		public static Style centered(int color) {
			return new Style(color, 1f);
		}

		public Style scaled(float scale) {
			return new Style(color, scale);
		}
	}
}
*///?}
