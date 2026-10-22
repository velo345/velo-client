package net.veloclient.launcher.ui;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import net.veloclient.velo.client.namestyle.NameStyle;
import net.veloclient.velo.client.namestyle.NameStyleEffects;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * A name drawn with a {@link NameStyle}, animated exactly like in game (same {@link NameStyleEffects}). One shared
 * timer redraws every preview that is on screen; a preview that leaves the scene stops costing anything.
 */
final class NamePreview extends Canvas {

	private static final Set<NamePreview> LIVE = Collections.newSetFromMap(new WeakHashMap<>());
	private static final long START = System.nanoTime();
	private static final String GLITCH_CHARS = "#$%&@!?*+=<>/\\|~";
	private static final AnimationTimer TIMER = new AnimationTimer() {
		@Override
		public void handle(long now) {
			for (NamePreview preview : java.util.List.copyOf(LIVE)) {
				preview.draw();
			}
		}
	};
	private static boolean fontsLoaded;
	private static final String[] FAMILIES = new String[NameStyle.FONTS.length];

	private final Supplier<NameStyle> style;
	private final String name;
	private final double size;

	/** {@code style} is asked every frame, so the preview follows edits on its own. */
	NamePreview(String name, Supplier<NameStyle> style, double size, double width) {
		super(width, Math.ceil(size * 1.6));
		this.name = name;
		this.style = style;
		this.size = size;
		loadFonts();
		sceneProperty().addListener((obs, old, scene) -> {
			if (scene != null) {
				LIVE.add(this);
				TIMER.start();
			} else {
				LIVE.remove(this);
			}
		});
		draw();
	}

	/** The JavaFX family for each {@link NameStyle#FONTS} entry; the bundled TTFs, a blocky mono for the Minecraft fonts. */
	private static synchronized void loadFonts() {
		if (fontsLoaded) {
			return;
		}
		fontsLoaded = true;
		String[] files = {null, "Sora-Bold", "SpaceGrotesk-Bold", "Poppins-Regular", "IBMPlexSans-SemiBold", "JetBrainsMono-Bold",
				"Audiowide-Regular", "Inter-SemiBold", null};
		for (int i = 0; i < FAMILIES.length; i++) {
			String family = "JetBrains Mono";
			if (i < files.length && files[i] != null) {
				Font loaded = Font.loadFont(NamePreview.class.getResourceAsStream("/net/veloclient/launcher/fonts/" + files[i] + ".ttf"), 12);
				if (loaded != null) {
					family = loaded.getFamily();
				}
			}
			FAMILIES[i] = family;
		}
	}

	static String family(int font) {
		loadFonts();
		return FAMILIES[Math.max(0, Math.min(FAMILIES.length - 1, font))];
	}

	private void draw() {
		NameStyle s = style.get();
		GraphicsContext g = getGraphicsContext2D();
		g.clearRect(0, 0, getWidth(), getHeight());
		if (s == null) {
			return;
		}
		String letters = (s.iconText().isEmpty() ? "" : s.iconText() + " ") + name;
		Font font = Font.font(family(s.font()), s.bold() ? FontWeight.BOLD : FontWeight.NORMAL, s.italic() ? FontPosture.ITALIC : FontPosture.REGULAR, size);
		g.setFont(font);
		// Most bundled fonts have no italic or bold face: then slant / thicken the letters ourselves (Minecraft does the same).
		String face = font.getStyle().toLowerCase(java.util.Locale.ROOT);
		boolean fakeItalic = s.italic() && !face.contains("italic") && !face.contains("oblique");
		boolean fakeBold = s.bold() && !face.contains("bold") && !face.contains("black") && !face.contains("heavy");
		double seconds = (System.nanoTime() - START) / 1e9;
		double total = width(letters, font);
		double scale = total > getWidth() - 4 ? (getWidth() - 4) / total : 1;
		g.save();
		g.scale(scale, scale);
		double x = 2;
		double baseline = size * 1.15;
		for (int i = 0; i < letters.length(); i++) {
			char c = letters.charAt(i);
			int rgb = NameStyleEffects.color(s, i, letters.length(), seconds);
			Color color = Color.rgb(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF);
			String glyph = NameStyleEffects.scrambled(s, i, seconds) ? String.valueOf(GLITCH_CHARS.charAt((int) (seconds * 40 + i * 7) % GLITCH_CHARS.length()))
					: String.valueOf(c);
			if (s.glow()) {
				int glow = NameStyleEffects.glow(rgb);
				g.setEffect(new DropShadow(size * 0.45, Color.rgb(glow >> 16 & 0xFF, glow >> 8 & 0xFF, glow & 0xFF, 0.9)));
			} else {
				g.setEffect(new DropShadow(0, size * 0.08, size * 0.08, Color.rgb(0, 0, 0, 0.55)));
			}
			g.setFill(color);
			if (fakeItalic) {
				g.save();
				// Shear around the baseline so the letter leans right without moving.
				g.transform(1, 0, -0.21, 1, baseline * 0.21, 0);
			}
			g.fillText(glyph, x, baseline);
			if (fakeBold) {
				g.setEffect(null);
				g.fillText(glyph, x + Math.max(0.8, size * 0.06), baseline);
			}
			if (fakeItalic) {
				g.restore();
			}
			double w = width(String.valueOf(c), font) + (fakeBold ? Math.max(0.8, size * 0.06) : 0);
			if (s.underline()) {
				g.setEffect(null);
				g.fillRect(x, baseline + size * 0.12, w, Math.max(1, size * 0.08));
			}
			x += w;
		}
		g.restore();
		g.setEffect(null);
	}

	private static final Text MEASURE = new Text();

	static double width(String text, Font font) {
		MEASURE.setFont(font);
		MEASURE.setText(text);
		MEASURE.setBoundsType(javafx.scene.text.TextBoundsType.LOGICAL);
		return MEASURE.getLayoutBounds().getWidth();
	}
}
