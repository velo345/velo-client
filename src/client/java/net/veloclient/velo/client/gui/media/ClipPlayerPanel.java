package net.veloclient.velo.client.gui.media;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.clips.core.VideoPlayer;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.util.Keys;

import java.util.Locale;

/**
 * The in-game clip player: the video (with its sound) and one control row under it - play/pause, the time, a timeline
 * and the volume. Trimming is part of the timeline: drag the handle at either end inward and only that part plays;
 * {@link #trimmed()} tells the screen to offer "Save trimmed". Click or drag the timeline to seek; Space plays/pauses,
 * the arrow keys jump 5 seconds, M mutes.
 */
public final class ClipPlayerPanel {

	private static final int BAR_H = 30;
	private static final int HANDLE_W = 6;

	private final VideoView view;
	private double trimFrom;
	private double trimTo = -1;
	private int dragging;
	private boolean draggingVolume;
	private final float[] hover = new float[4];
	private long lastNanos;
	// Where the timeline / volume were drawn last frame (for the mouse).
	private int lineX;
	private int lineY;
	private int lineW;
	private int volX;
	private int volW;
	private int barY;
	private int playX;
	private int muteX;
	private int areaX;
	private int areaY;
	private int areaW;
	private int areaH;

	public ClipPlayerPanel(String source, boolean autoplay) {
		this.view = new VideoView(source, autoplay);
	}

	public VideoPlayer player() {
		return view.player();
	}

	/** Whether the handles were moved in from the ends. */
	public boolean trimmed() {
		VideoPlayer p = view.player();
		return p != null && trimTo > 0 && (trimFrom > 0.05 || trimTo < p.duration() - 0.05);
	}

	public double trimFrom() {
		return trimFrom;
	}

	public double trimTo() {
		VideoPlayer p = view.player();
		return trimTo > 0 ? trimTo : p == null ? 0 : p.duration();
	}

	public void resetTrim() {
		VideoPlayer p = view.player();
		if (p != null) {
			trimFrom = 0;
			trimTo = p.duration();
			p.setRange(0, p.duration());
		}
	}

	public void release() {
		view.release();
	}

	/** Draws the player into the box (video on top, controls in the last {@value #BAR_H} units). */
	public void render(DrawContext context, TextRenderer textRenderer, int x, int y, int w, int h, int mouseX, int mouseY) {
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		VideoPlayer p = view.player();
		if (p != null && trimTo < 0) {
			trimTo = p.duration();
		}
		areaX = x;
		areaY = y;
		areaW = w;
		areaH = h - BAR_H - 6;
		VeloDraw.fillRounded(context, areaX, areaY, areaW, areaH, 8, 0xFF07070A);
		MediaTextures.Tex tex = view.texture();
		if (tex != null) {
			MediaTextures.drawFit(context, tex, areaX + 2, areaY + 2, areaW - 4, areaH - 4);
		}
		String error = view.error();
		boolean overVideo = VeloUi.inside(mouseX, mouseY, areaX, areaY, areaW, areaH);
		if (error != null) {
			context.drawCenteredTextWithShadow(textRenderer, VeloUi.trim(error, areaW - 20), areaX + areaW / 2, areaY + areaH / 2 - 4, 0xFFFF7A7A);
		} else if (p == null || tex == null) {
			context.drawCenteredTextWithShadow(textRenderer, view.status(), areaX + areaW / 2, areaY + areaH / 2 - 4, VeloUi.muted());
		} else if (!p.playing()) {
			// Big play button over a paused picture.
			float a = hover[0] = VeloAnim.step(hover[0], overVideo ? 1 : 0, dt * 8);
			int r = 20 + Math.round(a * 2);
			VeloDraw.fillCircle(context, areaX + areaW / 2, areaY + areaH / 2, r, VeloAnim.lerpArgb(0xB0000000, MenuKit.accent(), a));
			MenuKit.icon(context, "play", areaX + areaW / 2 - 9, areaY + areaH / 2 - 10, 20, 0xFFFFFFFF);
		}

		// ---- Control row ----
		barY = y + h - BAR_H;
		VeloDraw.fillRounded(context, x, barY, w, BAR_H, 8, 0xE6141419);
		VeloDraw.strokeRounded(context, x, barY, w, BAR_H, 8, 0x1FFFFFFF);
		int cy = barY + BAR_H / 2;
		playX = x + 6;
		boolean overPlay = VeloUi.inside(mouseX, mouseY, playX, barY + 4, 22, 22);
		hover[1] = VeloAnim.step(hover[1], overPlay ? 1 : 0, dt * 10);
		VeloDraw.fillCircle(context, playX + 11, cy, 11, VeloAnim.lerpArgb(MenuKit.accent() & 0xD0FFFFFF, MenuKit.accent(), hover[1]));
		MenuKit.icon(context, p != null && p.playing() ? "pause" : "play", playX + 5, cy - 6, 12, 0xFFFFFFFF);

		double duration = p == null ? 0 : p.duration();
		double position = p == null ? 0 : p.position();
		String time = clock(position) + " / " + clock(duration);
		int timeW = textRenderer.getWidth(time);
		context.drawTextWithShadow(textRenderer, time, playX + 30, cy - 4, 0xFFE4E4EA);

		volW = 46;
		volX = x + w - 8 - volW;
		muteX = volX - 22;
		lineX = playX + 30 + timeW + 12;
		lineW = Math.max(40, muteX - 12 - lineX);
		lineY = cy;

		// Timeline: the whole clip, the kept part, what's played, the playhead and the two trim handles.
		boolean overLine = VeloUi.inside(mouseX, mouseY, lineX - 4, barY + 2, lineW + 8, BAR_H - 4);
		hover[2] = VeloAnim.step(hover[2], overLine || dragging != 0 ? 1 : 0, dt * 10);
		int trackH = 4 + Math.round(hover[2] * 2);
		VeloDraw.fillRounded(context, lineX, lineY - trackH / 2f, lineW, trackH, trackH / 2f, 0xFF2A2A33);
		if (duration > 0) {
			float from = (float) (lineX + lineW * trimFrom / duration);
			float to = (float) (lineX + lineW * trimTo() / duration);
			float head = (float) (lineX + lineW * Math.min(1, position / duration));
			VeloDraw.fillRounded(context, from, lineY - trackH / 2f, Math.max(2, to - from), trackH, trackH / 2f, 0xFF4A4A57);
			VeloDraw.fillRounded(context, from, lineY - trackH / 2f, Math.max(0, Math.min(head, to) - from), trackH, trackH / 2f, MenuKit.accent());
			if (trimmed()) {
				// Dim the cut-off ends a little more so the kept part reads at a glance.
				VeloDraw.fillRounded(context, lineX, lineY - 7, from - lineX, 14, 3, 0x40000000);
				VeloDraw.fillRounded(context, to, lineY - 7, lineX + lineW - to, 14, 3, 0x40000000);
			}
			drawHandle(context, from - HANDLE_W / 2f, overHandle(mouseX, mouseY, from) || dragging == 1);
			drawHandle(context, to - HANDLE_W / 2f, overHandle(mouseX, mouseY, to) || dragging == 2);
			VeloDraw.fillCircle(context, head, lineY, 4 + hover[2] * 1.5f, 0xFFFFFFFF);
			if (overLine && dragging == 0) {
				double at = (mouseX - lineX) / (double) lineW * duration;
				String tip = clock(Math.max(0, Math.min(duration, at)));
				int tw = textRenderer.getWidth(tip) + 8;
				int tx = Math.max(x, Math.min(x + w - tw, mouseX - tw / 2));
				VeloDraw.fillRounded(context, tx, barY - 16, tw, 13, 4, 0xF0101014);
				context.drawTextWithShadow(textRenderer, tip, tx + 4, barY - 13, 0xFFFFFFFF);
			} else if (dragging == 1 || dragging == 2) {
				String tip = (dragging == 1 ? "Start " : "End ") + clock(dragging == 1 ? trimFrom : trimTo()) + "  ·  " + clock(trimTo() - trimFrom);
				int tw = textRenderer.getWidth(tip) + 8;
				float hx = dragging == 1 ? from : to;
				int tx = Math.max(x, Math.min(x + w - tw, Math.round(hx) - tw / 2));
				VeloDraw.fillRounded(context, tx, barY - 16, tw, 13, 4, MenuKit.accent());
				context.drawTextWithShadow(textRenderer, tip, tx + 4, barY - 13, 0xFFFFFFFF);
			}
		}

		// Volume: the speaker mutes, the little bar sets it.
		boolean muted = p != null && p.muted();
		float volume = p == null ? 0.8f : p.volume();
		boolean overMute = VeloUi.inside(mouseX, mouseY, muteX, barY + 6, 18, 18);
		MenuKit.icon(context, muted || volume <= 0.001f ? "volume_off" : "volume", muteX + 2, cy - 7, 14, overMute ? 0xFFFFFFFF : 0xFFB8B8C2);
		VeloDraw.fillRounded(context, volX, cy - 2, volW, 4, 2, 0xFF2A2A33);
		VeloDraw.fillRounded(context, volX, cy - 2, muted ? 0 : volW * volume, 4, 2, 0xFFE4E4EA);
		VeloDraw.fillCircle(context, volX + (muted ? 0 : volW * volume), cy, 3.5f, 0xFFFFFFFF);
	}

	private void drawHandle(DrawContext context, float hx, boolean hot) {
		VeloDraw.fillRounded(context, hx, lineY - 9, HANDLE_W, 18, 3, hot ? 0xFFFFFFFF : 0xFFE4E4EA);
		VeloDraw.fillRounded(context, hx + 2, lineY - 4, 2, 8, 1, 0xFF6A6A78);
	}

	private boolean overHandle(double mouseX, double mouseY, float hx) {
		return Math.abs(mouseX - hx) <= HANDLE_W && Math.abs(mouseY - lineY) <= 10;
	}

	private static String clock(double seconds) {
		int s = (int) Math.floor(Math.max(0, seconds));
		return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	// ---- Input ----

	public boolean mouseClicked(double mouseX, double mouseY) {
		VideoPlayer p = view.player();
		if (p == null) {
			return VeloUi.inside(mouseX, mouseY, areaX, areaY, areaW, areaH + BAR_H + 6);
		}
		double duration = p.duration();
		if (duration > 0) {
			float from = (float) (lineX + lineW * trimFrom / duration);
			float to = (float) (lineX + lineW * trimTo() / duration);
			// The nearer handle wins when they're close together.
			boolean onFrom = overHandle(mouseX, mouseY, from);
			boolean onTo = overHandle(mouseX, mouseY, to);
			if (onFrom || onTo) {
				dragging = onFrom && onTo ? (Math.abs(mouseX - from) < Math.abs(mouseX - to) ? 1 : 2) : onFrom ? 1 : 2;
				p.pause();
				return true;
			}
			if (VeloUi.inside(mouseX, mouseY, lineX - 4, barY + 2, lineW + 8, BAR_H - 4)) {
				dragging = 3;
				p.seek(at(mouseX, duration));
				return true;
			}
		}
		if (VeloUi.inside(mouseX, mouseY, playX, barY + 4, 22, 22) || VeloUi.inside(mouseX, mouseY, areaX, areaY, areaW, areaH)) {
			p.toggle();
			return true;
		}
		if (VeloUi.inside(mouseX, mouseY, muteX, barY + 4, 20, 22)) {
			p.setMuted(!p.muted());
			return true;
		}
		if (VeloUi.inside(mouseX, mouseY, volX - 4, barY + 4, volW + 8, 22)) {
			draggingVolume = true;
			p.setMuted(false);
			p.setVolume((float) ((mouseX - volX) / volW));
			return true;
		}
		return VeloUi.inside(mouseX, mouseY, areaX, barY, areaW, BAR_H);
	}

	private double at(double mouseX, double duration) {
		return Math.max(0, Math.min(1, (mouseX - lineX) / Math.max(1, lineW))) * duration;
	}

	public boolean mouseDragged(double mouseX, double mouseY) {
		VideoPlayer p = view.player();
		if (p == null) {
			return false;
		}
		if (draggingVolume) {
			p.setVolume((float) ((mouseX - volX) / volW));
			return true;
		}
		double duration = p.duration();
		if (dragging == 1) {
			trimFrom = Math.max(0, Math.min(trimTo() - 0.5, at(mouseX, duration)));
			return true;
		}
		if (dragging == 2) {
			trimTo = Math.min(duration, Math.max(trimFrom + 0.5, at(mouseX, duration)));
			return true;
		}
		if (dragging == 3) {
			p.seek(at(mouseX, duration));
			return true;
		}
		return false;
	}

	public boolean mouseReleased() {
		VideoPlayer p = view.player();
		boolean was = dragging != 0 || draggingVolume;
		if (p != null && (dragging == 1 || dragging == 2)) {
			p.setRange(trimFrom, trimTo());
			// Show where the handle was dropped: the start of the kept part, or the last second before the end.
			p.seek(dragging == 1 ? trimFrom : Math.max(trimFrom, trimTo() - 1));
		}
		dragging = 0;
		draggingVolume = false;
		return was;
	}

	public boolean keyPressed(int key) {
		VideoPlayer p = view.player();
		if (p == null) {
			return false;
		}
		if (key == Keys.SPACE) {
			p.toggle();
			return true;
		}
		if (key == Keys.LEFT || key == Keys.RIGHT) {
			p.seek(p.position() + (key == Keys.LEFT ? -5 : 5));
			return true;
		}
		if (key == Keys.M) {
			p.setMuted(!p.muted());
			return true;
		}
		return false;
	}
}
