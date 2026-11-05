package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*/^* 1.21.1 has no render pipelines: the names the GUI code passes, only telling {@link GuiCompat} how to blend. ^/
public final class RenderPipelines {
	public static final Object GUI_TEXTURED = "gui_textured";
	public static final Object GUI_TEXTURED_PREMULTIPLIED_ALPHA = "gui_textured_premultiplied";
	public static final Object GUI = "gui";
	public static final Object CROSSHAIR = "crosshair";
	public static final Object TRACY_BLIT = "tracy_blit";

	private RenderPipelines() {
	}
}
*///?}
