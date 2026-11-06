package net.veloclient.velo.dev.notes;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.dev.storage.StorageMc;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a note as a card in one of five looks - paper, sticky note, frosted glass, wooden sign, neon - at the current
 * pose (0,0 = top left, {@link #W} wide). The same card floats in the world, sits in the notes list and previews in
 * the editor, so what you design is what everyone sees.
 */
public final class NoteCard {

	public static final int W = 132;
	private static final int PAD = 8;

	/** The colours of one look. */
	private record Look(int bg, int edge, int title, int text, int muted, boolean shadow) {
	}

	private NoteCard() {
	}

	private static Look look(Note note) {
		int c = note.color | 0xFF000000;
		return switch (note.style) {
			case "sticky" -> new Look(mix(c, 0xFFFFFFFF, 0.18f), mix(c, 0xFF000000, 0.25f), 0xFF2A2116, 0xFF2E261C, 0xFF5E5240, false);
			case "glass" -> new Look(0xC0121620, withAlpha(c, 0xB0), mix(c, 0xFFFFFFFF, 0.35f), 0xFFEFF2F7, 0xFFAEB7C4, true);
			case "sign" -> new Look(0xFF8C6A45, 0xFF5B4128, 0xFFFFF3DA, 0xFFF6E7C8, 0xFFD9C29A, true);
			case "neon" -> new Look(0xE60A0B12, c, mix(c, 0xFFFFFFFF, 0.2f), 0xFFE9EBF2, 0xFF9AA2B4, true);
			default -> new Look(mix(0xFFF8F3E6, c, 0.12f), 0x33000000, 0xFF23211D, 0xFF2D2A25, 0xFF746E63, false);
		};
	}

	/** Height of the card for this note ({@code full}: the whole text and list, as in the editor preview). */
	public static int height(Note note, boolean full) {
		return layout(note, full, null, false, 0, 0, 0xFF);
	}

	/** Draws the card; {@code alpha} fades it (0..255), {@code glow} outlines it (aimed at / unread). Returns its height. */
	public static int draw(DrawContext context, Note note, boolean full, boolean glow, int alpha) {
		return layout(note, full, context, true, 0, 0, alpha);
	}

	/** Draws the card into {@link NoteWorld}'s recorder (real geometry in the world) instead of the GUI. */
	static int record(Note note, int alpha) {
		return layout(note, false, null, true, 0, 0, alpha);
	}

	/** One pass that measures ({@code draw} false) or draws - into the GUI, or (context null) into {@link NoteWorld}. */
	private static int layout(Note note, boolean full, DrawContext context, boolean draw, int ox, int oy, int alpha) {
		var renderer = MinecraftClient.getInstance().textRenderer;
		Look look = look(note);
		int inner = W - PAD * 2;
		int y = PAD;
		List<Runnable> body = new ArrayList<>();

		boolean images = Note.imagesHere();
		boolean boards = Note.whiteboardsHere();
		boolean plain = !note.isImage() && !note.isBoard();
		boolean titled = note.title != null && !note.title.isBlank();
		if (!plain && !titled) {
			y -= 2;
		}
		// Header: icon + title (picture and whiteboard notes: only when they have a title).
		int titleX = PAD;
		if (note.icon != null && (plain || titled)) {
			int iy = y - 3;
			body.add(() -> {
				if (NoteWorld.recording()) {
					NoteWorld.item(note.icon, ox + PAD - 1, oy + iy, alpha);
				} else {
					StorageMc.drawItem(context, StorageMc.stack(note.icon, 1), ox + PAD - 1, oy + iy, "");
				}
			});
			titleX += 17;
		}
		if (plain || titled) {
			Text title = MenuKit.fit(renderer, MenuKit.uiBold(note.displayTitle()), W - titleX - PAD);
			int ty = y;
			int tx = titleX;
			body.add(() -> text(context, renderer, title, ox + tx, oy + ty, fade(look.title, alpha), look.shadow));
			y += 13;
		}

		// Text.
		// Without a title the text's first line is the title, so the body is the rest.
		String bodyText = note.text == null ? null : note.title != null && !note.title.isBlank() ? note.text
				: note.text.strip().contains("\n") ? note.text.strip().substring(note.text.strip().indexOf('\n') + 1) : null;
		if (plain && bodyText != null && !bodyText.isBlank()) {
			List<String> lines = wrapped(bodyText, inner);
			int max = full ? 40 : 5;
			for (int i = 0; i < lines.size() && i < max; i++) {
				String line = i == max - 1 && lines.size() > max ? VeloUi.trim(lines.get(i) + " ...", inner) : lines.get(i);
				int ly = y;
				body.add(() -> text(context, renderer, MenuKit.ui(line), ox + PAD, oy + ly, fade(look.text, alpha), look.shadow));
				y += 10;
			}
			y += 3;
		}

		// Checklist.
		if (plain && !note.checks.isEmpty()) {
			int done = note.done();
			int total = note.checks.size();
			int by = y + 2;
			body.add(() -> {
				VeloDraw.fillRounded(context, ox + PAD, oy + by, inner - 26, 4, 2, fade(withAlpha(look.muted, 0x50), alpha));
				int fill = Math.round((inner - 26) * (done / (float) total));
				if (fill > 0) {
					VeloDraw.fillRounded(context, ox + PAD, oy + by, fill, 4, 2, fade(done == total ? 0xFF4CC38A : note.color | 0xFF000000, alpha));
				}
				String count = done + "/" + total;
				text(context, renderer, MenuKit.ui(count), ox + PAD + inner - renderer.getWidth(MenuKit.ui(count)), oy + by - 2, fade(look.muted, alpha), look.shadow);
			});
			y += 10;
			int max = full ? 60 : 4;
			for (int i = 0; i < total && i < max; i++) {
				Note.Check check = note.checks.get(i);
				int cy = y;
				Text line = MenuKit.fit(renderer, MenuKit.ui(check.text), inner - 12);
				body.add(() -> {
					int box = fade(check.done ? 0xFF4CC38A : look.muted, alpha);
					VeloDraw.strokeRounded(context, ox + PAD, oy + cy + 1, 7, 7, 2, box);
					if (check.done) {
						VeloDraw.fillRounded(context, ox + PAD + 2, oy + cy + 3, 3, 3, 1, box);
					}
					int color = fade(check.done ? look.muted : look.text, alpha);
					text(context, renderer, line, ox + PAD + 11, oy + cy, color, look.shadow);
					if (check.done) {
						fill(context, ox + PAD + 11, oy + cy + 4, ox + PAD + 11 + renderer.getWidth(line), oy + cy + 5, color);
					}
				});
				y += 10;
			}
			if (total > max) {
				int my = y;
				body.add(() -> text(context, renderer, MenuKit.ui("+ " + (total - max) + " more"), ox + PAD + 11, oy + my, fade(look.muted, alpha), look.shadow));
				y += 10;
			}
			y += 3;
		}

		// Whiteboard (a whiteboard note is the board, even while it's still empty).
		if (boards && (note.isBoard() || plain && !note.board.isEmpty())) {
			int bh = inner * Note.BOARD_H / Note.BOARD_W;
			int by = y;
			body.add(() -> drawBoard(context, note.board, ox + PAD, oy + by, inner, bh, alpha, full ? Integer.MAX_VALUE : 160));
			y += bh + 5;
		}

		// Picture.
		Identifier picture = draw ? NoteStore.texture(note) : null;
		if (images && (note.image > 0 && !note.isBoard() || note.isImage())) {
			float aspect = NoteImages.aspect(note);
			int ph = Math.max(20, Math.min(inner, Math.round(inner / Math.max(0.3f, aspect))));
			int py = y;
			body.add(() -> {
				if (picture != null && note.image > 0) {
					if (NoteWorld.recording()) {
						NoteWorld.texture(picture, ox + PAD, oy + py, inner, ph, alpha);
					} else {
						context.drawTexture(RenderPipelines.GUI_TEXTURED, picture, ox + PAD, oy + py, 0f, 0f, inner, ph, inner, ph);
					}
				} else {
					VeloDraw.fillRounded(context, ox + PAD, oy + py, inner, ph, 3, fade(withAlpha(look.muted, 0x30), alpha));
					text(context, renderer, MenuKit.ui(note.image == 0 ? "No picture yet" : note.mine || NotesModule.othersPictures() ? "Picture..." : "Picture hidden"), ox + PAD + 4, oy + py + 4,
							fade(look.muted, alpha), look.shadow);
				}
			});
			y += ph + 5;
		}

		// Footer: who, mention badge, shared mark.
		String by = note.mine ? (note.local ? "Private" : sharedLabel(note)) : "by " + note.ownerName;
		int fy = y;
		body.add(() -> {
			text(context, renderer, MenuKit.fit(renderer, MenuKit.ui(by), inner - 30), ox + PAD, oy + fy, fade(look.muted, alpha), look.shadow);
			if (note.unread) {
				String at = "@you";
				int w = renderer.getWidth(MenuKit.uiBold(at)) + 6;
				VeloDraw.fillRounded(context, ox + W - PAD - w, oy + fy - 1, w, 10, 3, fade(0xFFFF5D73, alpha));
				text(context, renderer, MenuKit.uiBold(at), ox + W - PAD - w + 3, oy + fy, fade(0xFFFFFFFF, alpha), false);
			}
		});
		y += 10 + PAD - 2;

		int h = y;
		if (draw) {
			background(context, note, look, ox, oy, h, alpha);
			for (Runnable part : body) {
				part.run();
			}
		}
		return h;
	}

	// Cards are laid out every frame (the world cards are re-recorded each frame): wrapping their text measured every
	// word with the font each time - 2.5% of the render thread with a few notes in view (JFR, 2026-11-06).
	private static final java.util.Map<String, List<String>> WRAPPED = new java.util.HashMap<>();

	private static List<String> wrapped(String bodyText, int inner) {
		String key = inner + "\u0000" + bodyText;
		List<String> cached = WRAPPED.get(key);
		if (cached != null) {
			return cached;
		}
		List<String> lines = new ArrayList<>();
		for (String paragraph : bodyText.split("\n", -1)) {
			if (paragraph.isBlank()) {
				lines.add("");
				continue;
			}
			lines.addAll(VeloUi.wrap(paragraph, inner));
		}
		if (WRAPPED.size() > 256) {
			WRAPPED.clear();
		}
		lines = List.copyOf(lines);
		WRAPPED.put(key, lines);
		return lines;
	}

	private static String sharedLabel(Note note) {
		if ("friends".equals(note.share)) {
			return "Shared with friends";
		}
		int n = note.people.size();
		return n == 0 ? "Shared (nobody yet)" : "Shared with " + n + (n == 1 ? " person" : " people");
	}

	private static void background(DrawContext context, Note note, Look look, int x, int y, int h, int alpha) {
		int c = note.color | 0xFF000000;
		switch (note.style) {
			case "sticky" -> {
				VeloDraw.shadow(context, x, y, W, h, 2, 6, 2, fade(0x40000000, alpha));
				fill(context, x, y, x + W, y + h - 10, fade(look.bg, alpha));
				fill(context, x, y + h - 10, x + W - 10, y + h, fade(look.bg, alpha));
				// Folded corner.
				VeloDraw.fillQuad(context, x + W - 10, y + h - 10, x + W, y + h - 10, x + W - 10, y + h, x + W - 10, y + h, fade(look.edge, alpha));
				fill(context, x, y, x + W, y + 3, fade(mix(c, 0xFF000000, 0.1f), alpha));
			}
			case "glass" -> {
				VeloDraw.shadow(context, x, y, W, h, 8, 8, 3, fade(0x50000000, alpha));
				VeloDraw.fillRounded(context, x, y, W, h, 8, fade(look.bg, alpha));
				VeloDraw.fillRoundedTop(context, x, y, W, 3, 8, fade(withAlpha(c, 0xC0), alpha));
				VeloDraw.strokeRounded(context, x, y, W, h, 8, fade(withAlpha(0xFFFFFFFF, 0x28), alpha));
			}
			case "sign" -> {
				VeloDraw.shadow(context, x, y, W, h, 3, 6, 2, fade(0x50000000, alpha));
				VeloDraw.fillRounded(context, x, y, W, h, 3, fade(look.edge, alpha));
				VeloDraw.fillRounded(context, x + 2, y + 2, W - 4, h - 4, 2, fade(look.bg, alpha));
				for (int py = y + 14; py < y + h - 4; py += 14) {
					fill(context, x + 3, py, x + W - 3, py + 1, fade(0x22000000, alpha));
				}
			}
			case "neon" -> {
				float pulse = 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 500.0);
				VeloDraw.shadow(context, x, y, W, h, 8, 10, 0, fade(withAlpha(c, (int) (0x60 * pulse)), alpha));
				VeloDraw.fillRounded(context, x, y, W, h, 8, fade(look.bg, alpha));
				VeloDraw.strokeRounded(context, x, y, W, h, 8, fade(withAlpha(c, (int) (0xFF * pulse)), alpha));
				VeloDraw.strokeRounded(context, x + 1, y + 1, W - 2, h - 2, 7, fade(withAlpha(c, (int) (0x70 * pulse)), alpha));
			}
			default -> {
				VeloDraw.shadow(context, x, y, W, h, 4, 6, 2, fade(0x38000000, alpha));
				VeloDraw.fillRounded(context, x, y, W, h, 4, fade(look.bg, alpha));
				VeloDraw.fillRoundedTop(context, x, y, W, 4, 4, fade(c, alpha));
				VeloDraw.strokeRounded(context, x, y, W, h, 4, fade(look.edge, alpha));
			}
		}
	}

	/** Outline around the card (aimed at, or unread mention). */
	public static void glow(DrawContext context, Note note, int h, int alpha) {
		float pulse = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() / 220.0);
		int color = note.unread ? 0xFFFF5D73 : 0xFFFFFFFF;
		VeloDraw.strokeRounded(context, -2, -2, W + 4, h + 4, 7, fade(withAlpha(color, (int) (0xE0 * pulse)), alpha));
	}

	/** The whiteboard strokes scaled into a box (paper background). */
	public static void drawBoard(DrawContext context, List<Note.Stroke> strokes, int x, int y, int w, int h, int alpha, int maxStrokes) {
		int paper = fade(0xFFF7F4EA, alpha);
		VeloDraw.fillRounded(context, x, y, w, h, 3, paper);
		float sx = w / (float) Note.BOARD_W;
		float sy = h / (float) Note.BOARD_H;
		int drawn = 0;
		for (Note.Stroke stroke : strokes) {
			if (stroke.p == null || drawn++ > maxStrokes) {
				break;
			}
			int color = (stroke.c >>> 24) == 0 ? paper : fade(stroke.c | 0xFF000000, alpha);
			float width = Math.max(0.6f, stroke.w * Math.min(sx, sy));
			if (stroke.p.length == 2) {
				VeloDraw.fillCircle(context, x + stroke.p[0] * sx, y + stroke.p[1] * sy, width / 2, color);
				continue;
			}
			for (int i = 2; i + 1 < stroke.p.length; i += 2) {
				VeloDraw.line(context, x + stroke.p[i - 2] * sx, y + stroke.p[i - 1] * sy, x + stroke.p[i] * sx, y + stroke.p[i + 1] * sy, width, color);
			}
		}
	}

	private static void text(DrawContext context, net.minecraft.client.font.TextRenderer renderer, Text value, int x, int y, int color, boolean shadow) {
		if (((color >>> 24) & 0xFF) < 8) {
			return;
		}
		if (NoteWorld.recording()) {
			NoteWorld.text(value, x, y, color, shadow);
			return;
		}
		context.drawText(renderer, value, x, y, color, shadow);
	}

	/** A plain rectangle - in the world it goes through the same shape recorder as the rounded ones. */
	private static void fill(DrawContext context, int x1, int y1, int x2, int y2, int color) {
		if (NoteWorld.recording()) {
			VeloDraw.fillRounded(null, x1, y1, x2 - x1, y2 - y1, 0, color);
			return;
		}
		context.fill(x1, y1, x2, y2, color);
	}

	static int fade(int argb, int alpha) {
		int a = ((argb >>> 24) & 0xFF) * alpha / 255;
		return (a << 24) | (argb & 0xFFFFFF);
	}

	static int withAlpha(int argb, int alpha) {
		return (alpha << 24) | (argb & 0xFFFFFF);
	}

	static int mix(int a, int b, float t) {
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		int r = Math.round(ar + (br - ar) * t);
		int g = Math.round(ag + (bg - ag) * t);
		int bl = Math.round(ab + (bb - ab) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}
}
