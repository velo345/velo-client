package net.veloclient.velo.client.gui.title;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared (version-independent) drawing/layout logic for the reskinned title
 * screen and, for the branding header only, the escape menu - kept out of
 * the two Screen mixins themselves so the actual per-version code in {@code
 * TitleScreenMixin}/{@code EscapeMenuMixin} stays to the one or two lines
 * that genuinely need branching (the real vanilla button class name differs
 * between Yarn and Mojang mappings; everything else here doesn't touch a
 * single Minecraft type that isn't already stable across all 3 targeted
 * versions).
 */
public final class TitleScreenTheme {

	/**
	 * Real vanilla title-screen button translation keys (verified against
	 * the actual decompiled class's own string constant pool, not guessed) -
	 * matched instead of a "any wide button" width heuristic, so mods that
	 * add their own title-screen buttons (Essential, ModMenu, ...) are never
	 * swept into the restyled stack. Order here is also the stack's display
	 * order; {@code "$store"} marks where the Store button is inserted.
	 */
	public static final List<String> TITLE_KEY_ORDER = List.of(
			"menu.singleplayer", "menu.multiplayer", "menu.online", "$store", "menu.options", "menu.quit");

	/** Same idea for the escape menu - real vanilla keys only, Essential's own pause-menu buttons (it already has its own dedicated right-side panel for those) are left completely alone. */
	public static final List<String> ESCAPE_KEY_ORDER = List.of(
			"menu.returnToGame", "gui.advancements", "gui.stats", "$store", "menu.options",
			"menu.shareToLan", "menu.returnToMenu", "menu.disconnect");

	/**
	 * A known mod button we don't have (and, unlike vanilla's own buttons,
	 * can't get) a verified translation key for, matched instead by its
	 * rendered label text containing any of {@code needles} (case-insensitive)
	 * - only applied to buttons that already fell through the real
	 * translation-key allowlist above, so it never competes with or
	 * re-matches a genuine vanilla button.
	 */
	public record ExtraIcon(String iconKey, List<String> needles) {
	}

	/** Essential/ModMenu pause-menu buttons repositioned into their own small icon row instead of left overlapping the restyled stack. */
	public static final List<ExtraIcon> ESCAPE_EXTRA_ICONS = List.of(
			new ExtraIcon("bugs", List.of("bug", "report")),
			new ExtraIcon("feedback", List.of("feedback")),
			new ExtraIcon("friends", List.of("friend")),
			new ExtraIcon("teleport", List.of("teleport", "warp")),
			new ExtraIcon("mods", List.of("mods")));

	/** Same idea for the title screen's own corner buttons (language/accessibility) plus Essential/ModMenu additions. */
	public static final List<ExtraIcon> TITLE_EXTRA_ICONS = List.of(
			new ExtraIcon("friends", List.of("friend")),
			new ExtraIcon("language", List.of("language")),
			new ExtraIcon("accessibility", List.of("accessibility")),
			new ExtraIcon("mods", List.of("mods")));

	/** Buttons that get hidden outright rather than incorporated - a "News" popup button isn't something worth a permanent icon slot. */
	public static final List<String> TITLE_HIDE_ONLY_NEEDLES = List.of("news");

	/**
	 * Flashback's and Replay Mod's own pause-menu recording controls,
	 * matched against their real, verified button labels (Flashback:
	 * "Start/Pause/Unpause/Finish Recording"; Replay Mod: "Start/Pause/
	 * Resume/Stop Recording" - both mods' actual {@code en_us} lang files
	 * were checked, not guessed). "record_start"'s needles must stay ahead
	 * of "record_pause"'s in this list - "Unpause Recording" contains
	 * "pause recording" as a literal substring, so checking the shorter,
	 * less specific needle first would misfire on the resume button.
	 * Rendered in their own column beside the main stack (not the below-
	 * stack row) since these are direct actions, not "open a menu" links.
	 */
	public static final List<ExtraIcon> RECORDING_SIDE_ICONS = List.of(
			new ExtraIcon("record_start", List.of("start recording", "unpause recording", "resume recording")),
			new ExtraIcon("record_pause", List.of("pause recording")),
			new ExtraIcon("record_stop", List.of("stop recording", "finish recording")),
			new ExtraIcon("record_cancel", List.of("cancel recording")));

	/** Flashback's "Open Replays" / Replay Mod's "Replay Viewer" title-screen buttons - real verified labels, same source as {@link #RECORDING_SIDE_ICONS}. */
	public static final List<ExtraIcon> TITLE_SIDE_ICONS = List.of(
			new ExtraIcon("replay_editor", List.of("open replays", "replay viewer")));

	/**
	 * Fallback for {@link #TITLE_SIDE_ICONS}, matched against the widget's
	 * own class name instead of its label text. Confirmed necessary and
	 * correct by loading the real Replay Mod jar in a dev client and
	 * dumping every title-screen child widget: its button ({@code
	 * com.replaymod.replay.handler.GuiHandler$1}) is a real title-screen
	 * button, but its message text is empty - an icon-only button with
	 * nothing for text matching to find. Flashback's own button never
	 * appeared at all in that same test (18+ seconds sitting at the title
	 * screen) so this exact mechanism was never actually observed working
	 * for it - a "com.moulberry.flashback." entry here once caused Flashback's
	 * real title button to go missing entirely for a live user, so it's
	 * deliberately NOT included; Flashback's button is real-text ("Open
	 * Replays", verified via its own lang file) and gets caught by {@link
	 * #TITLE_SIDE_ICONS} on its own without needing this fallback at all.
	 */
	public static final List<ExtraIcon> TITLE_SIDE_ICON_CLASSES = List.of(
			new ExtraIcon("replay_editor", List.of("com.replaymod.")));

	public static String matchExtraIcon(List<ExtraIcon> candidates, String text) {
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		for (ExtraIcon candidate : candidates) {
			for (String needle : candidate.needles()) {
				if (lower.contains(needle)) {
					return candidate.iconKey();
				}
			}
		}
		return null;
	}

	public static boolean matchesAny(List<String> needles, String text) {
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		for (String needle : needles) {
			if (lower.contains(needle)) {
				return true;
			}
		}
		return false;
	}

	private static final int ICON_SQUARE_SIZE = 22;
	private static final int ICON_SQUARE_GAP = 6;

	/** A centered horizontal row of {@code count} small square icon buttons, top-anchored at {@code y}. */
	public static List<Layout> iconRowLayout(int screenWidth, int y, int count) {
		int totalWidth = count * ICON_SQUARE_SIZE + Math.max(0, count - 1) * ICON_SQUARE_GAP;
		int x = (screenWidth - totalWidth) / 2;
		List<Layout> row = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			row.add(new Layout(x + i * (ICON_SQUARE_SIZE + ICON_SQUARE_GAP), y, ICON_SQUARE_SIZE, ICON_SQUARE_SIZE));
		}
		return row;
	}

	/**
	 * A vertical column of {@code count} small square icon buttons just to
	 * the right of the main button stack, vertically centered alongside it -
	 * "next to our normal singleplayer/resume buttons" rather than a row
	 * guessed to line up with wherever a third-party mod happens to draw its
	 * own equivalent, which this codebase has no reliable way to detect.
	 * Purely a function of our own stack's geometry, so it never depends on
	 * any other mod's layout.
	 */
	public static List<Layout> sideIconColumnLayout(List<Layout> mainStack, int count) {
		Layout first = mainStack.get(0);
		Layout last = mainStack.get(mainStack.size() - 1);
		int x = first.x() + first.width() + 14;
		int stackTop = first.y();
		int stackBottom = last.y() + last.height();
		int totalHeight = count * ICON_SQUARE_SIZE + Math.max(0, count - 1) * ICON_SQUARE_GAP;
		int y = stackTop + Math.max(0, (stackBottom - stackTop - totalHeight) / 2);
		List<Layout> column = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			column.add(new Layout(x, y + i * (ICON_SQUARE_SIZE + ICON_SQUARE_GAP), ICON_SQUARE_SIZE, ICON_SQUARE_SIZE));
		}
		return column;
	}

	/** Same glass treatment as {@link #drawGlassButton}, just a small icon-centered square instead of a full-width text row - for the incorporated Essential/ModMenu/vanilla-corner buttons. */
	public static void drawIconSquare(DrawContext context, Layout layout, Identifier icon, boolean hovered, boolean active, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		int radius = 6;
		int x = layout.x(), y = layout.y(), w = layout.width(), h = layout.height();
		boolean glow = hovered && active;

		VeloDraw.fillRounded(context, x, y + 1, w, h, radius, 0x33000000);
		int base = solidify(theme.surfaceWithOpacity(), MIN_BUTTON_ALPHA);
		VeloDraw.fillRounded(context, x, y, w, h, radius, base);
		VeloDraw.strokeRounded(context, x, y, w, h, radius,
				glow ? theme.accentStart() : ((theme.text() & 0x00FFFFFF) | 0x30000000));

		if (glow) {
			drawSpotlight(context, x + 1, y + 1, w - 2, h - 2, mouseX, mouseY, Math.max(w, h), theme.accentStart());
		}

		int iconSize = Math.round(w * 0.55f);
		int iconX = x + (w - iconSize) / 2;
		int iconY = y + (h - iconSize) / 2;
		int tint = active ? (0xFF000000 | (theme.text() & 0xFFFFFF)) : 0xFF888888;
		context.drawTexture(RenderPipelines.GUI_TEXTURED, icon, iconX, iconY, 0f, 0f, iconSize, iconSize, 1024, 1024, 1024, 1024, tint);
	}

	/** The custom title-font identifier (Audiowide) - matches {@code assets/velo-client/font/title.json}. */
	public static final Identifier TITLE_FONT = Identifier.of("velo-client", "title");
	/** The custom body-font identifier (Poppins) - matches {@code assets/velo-client/font/body.json}. */
	public static final Identifier BODY_FONT = Identifier.of("velo-client", "body");
	/** The custom tile-title-font identifier (Anta) - matches {@code assets/velo-client/font/tile.json}; used for module tile names in {@code ModMenuScreen}. */
	public static final Identifier TILE_FONT = Identifier.of("velo-client", "tile");

	private static final Identifier LOGO_TEXTURE = Identifier.of("velo-client", "textures/icon/logo.png");
	private static final int LOGO_SOURCE_SIZE = 500;
	/** The Velo brand red, sampled straight from logo.png - the "VELO CLIENT" wordmark always uses this, never the active theme's accent color, so it stays recognizable as the Velo brand regardless of what theme is selected. */
	private static final int LOGO_RED = 0xFFF24759;
	private static final Identifier PANORAMA_TEXTURE = Identifier.of("velo-client", "textures/gui/title/panorama.png");
	private static final int PANORAMA_WIDTH = 2048;
	private static final int PANORAMA_HEIGHT = 1024;
	/** Pixels of the (wrapped) panorama texture scrolled per second. */
	private static final float PAN_PIXELS_PER_SECOND = 28f;
	/** Vanilla's own main-menu button width (its {@code ButtonWidget.DEFAULT_WIDTH}) - matched instead of stretching toward the screen edges, which is what made buttons look oversized at high/Auto GUI Scale (a small scaled screen has little margin, so "screenWidth - 40" was nearly the full width). */
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 6;
	/** A modern, subtle rounding rather than the near-pill look a taller radius gives a 20px-high button. */
	private static final int BUTTON_CORNER_RADIUS = 5;
	/** However translucent the active theme's own panel opacity is configured, these buttons never drop below this alpha - they sit over a busy game world/panorama and must fully hide whatever vanilla button is still positioned underneath, not partially show it through. */
	private static final int MIN_BUTTON_ALPHA = 0xE8;

	private static final String VERSION = FabricLoader.getInstance()
			.getModContainer("velo-client")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");

	private TitleScreenTheme() {
	}

	/** Raises {@code argb}'s alpha to at least {@code minAlpha}, leaving its color and any higher alpha untouched. */
	private static int solidify(int argb, int minAlpha) {
		int alpha = (argb >>> 24) & 0xFF;
		return alpha >= minAlpha ? argb : (minAlpha << 24) | (argb & 0x00FFFFFF);
	}

	//? if <26.1 {
	public static Text titleFont(String text) {
		return Text.literal(text).setStyle(Style.EMPTY.withFont(new net.minecraft.text.StyleSpriteSource.Font(TITLE_FONT)));
	}

	public static Text bodyFont(Text text) {
		return text.copy().setStyle(text.getStyle().withFont(new net.minecraft.text.StyleSpriteSource.Font(BODY_FONT)));
	}

	public static Text tileFont(String text) {
		return Text.literal(text).setStyle(Style.EMPTY.withFont(new net.minecraft.text.StyleSpriteSource.Font(TILE_FONT)));
	}
	//?} else {
	/*public static Text titleFont(String text) {
		return Text.literal(text).setStyle(Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(TITLE_FONT)));
	}

	public static Text bodyFont(Text text) {
		return text.copy().setStyle(text.getStyle().withFont(new net.minecraft.network.chat.FontDescription.Resource(BODY_FONT)));
	}

	public static Text tileFont(String text) {
		return Text.literal(text).setStyle(Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(TILE_FONT)));
	}
	*///?}

	public record Layout(int x, int y, int width, int height) {
	}

	/** Total pixel height of {@link #drawBranding}'s logo+wordmark block, so callers can stack the button stack directly beneath it without guessing. */
	public static int brandingHeight() {
		return 34;
	}

	/**
	 * Arranges whichever of {@code keyOrder}'s real vanilla entries actually
	 * exist this session (plus the always-present {@code "$store"} marker)
	 * into one centered vertical stack, sized/positioned from the screen's
	 * *actual* current dimensions every call - not a fixed pixel size, so it
	 * shrinks and grows correctly across GUI Scale settings including Auto -
	 * and stacked directly beneath {@link #brandingHeight()} rather than two
	 * independently-guessed percentages of screen height, which could
	 * overlap on a short/high-GUI-Scale screen.
	 */
	public static List<Layout> orderedStackLayout(int screenWidth, int screenHeight, List<String> keyOrder, Set<String> presentKeys) {
		int rowCount = 0;
		for (String key : keyOrder) {
			if (key.equals("$store") || presentKeys.contains(key)) {
				rowCount++;
			}
		}
		return stackLayout(screenWidth, screenHeight, rowCount);
	}

	/** A centered vertical stack of {@code count} equal-width rows, the whole branding+stack block vertically centered as one unit and clamped to always fit on-screen. */
	public static List<Layout> stackLayout(int screenWidth, int screenHeight, int count) {
		int width = Math.min(BUTTON_WIDTH, Math.max(80, screenWidth - 24));
		int rowHeight = Math.min(BUTTON_HEIGHT, Math.max(12, (screenHeight - 40) / Math.max(1, count + 3)));
		int gap = Math.min(BUTTON_GAP, Math.max(2, rowHeight / 4));
		int totalHeight = count * rowHeight + Math.max(0, count - 1) * gap;

		int blockHeight = brandingHeight() + 14 + totalHeight;
		int blockTop = Math.max(8, (screenHeight - blockHeight) / 2);
		int stackTop = blockTop + brandingHeight() + 14;
		// Never let the stack's own bottom row run off-screen even if the
		// centered math above would put it close - clamp as a last resort.
		stackTop = Math.min(stackTop, screenHeight - totalHeight - 8);

		int x = (screenWidth - width) / 2;
		List<Layout> rows = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			rows.add(new Layout(x, stackTop + i * (rowHeight + gap), width, rowHeight));
		}
		return rows;
	}

	/** Same row-width/height/gap scaling as {@link #stackLayout}, but anchored to a fixed {@code topY} (right below a header band) instead of centering the whole branding+stack block - what the escape menu uses, since its header is a fixed top band rather than something to center alongside the buttons. */
	public static List<Layout> stackLayoutFrom(int screenWidth, int screenHeight, int topY, int count) {
		int width = Math.min(BUTTON_WIDTH, Math.max(80, screenWidth - 24));
		int rowHeight = Math.min(BUTTON_HEIGHT, Math.max(12, (screenHeight - 40) / Math.max(1, count + 3)));
		int gap = Math.min(BUTTON_GAP, Math.max(2, rowHeight / 4));
		int totalHeight = count * rowHeight + Math.max(0, count - 1) * gap;
		int stackTop = Math.min(topY, screenHeight - totalHeight - 8);

		int x = (screenWidth - width) / 2;
		List<Layout> rows = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			rows.add(new Layout(x, stackTop + i * (rowHeight + gap), width, rowHeight));
		}
		return rows;
	}

	/** Where {@link #drawCompactBranding}'s header should be top-anchored so the header+button column together are vertically centered as one block (not the header pinned to the very top with empty space left dangling below the buttons) - the escape-menu counterpart of {@link #brandingTop}. */
	public static int compactHeaderTop(int screenWidth, int screenHeight, int rowCount) {
		int rowHeight = Math.min(BUTTON_HEIGHT, Math.max(12, (screenHeight - 40) / Math.max(1, rowCount + 3)));
		int gap = Math.min(BUTTON_GAP, Math.max(2, rowHeight / 4));
		int totalHeight = rowCount * rowHeight + Math.max(0, rowCount - 1) * gap;
		int blockHeight = compactBrandingHeight() + 14 + totalHeight;
		return Math.max(4, (screenHeight - blockHeight) / 2);
	}

	/** Where {@link #drawBranding} should be top-anchored so it sits directly above whatever {@link #stackLayout}/{@link #orderedStackLayout} just computed for the same screen size - the two are always computed as one stacked block, never independently guessed percentages that can overlap. */
	public static int brandingTop(int screenWidth, int screenHeight, int rowCount) {
		int rowHeight = Math.min(BUTTON_HEIGHT, Math.max(12, (screenHeight - 40) / Math.max(1, rowCount + 3)));
		int gap = Math.min(BUTTON_GAP, Math.max(2, rowHeight / 4));
		int totalHeight = rowCount * rowHeight + Math.max(0, rowCount - 1) * gap;
		int blockHeight = brandingHeight() + 14 + totalHeight;
		return Math.max(8, (screenHeight - blockHeight) / 2);
	}

	/**
	 * Draws a fixed-size "window" onto the panorama image, sliding sideways
	 * through it over time (wrapping around) - the "rotate slowly like
	 * vanilla's panorama" look from one flat equirectangular-style image
	 * instead of a 6-face cubemap.
	 *
	 * <p>The elapsed-time math is deliberately {@code double}, not {@code
	 * float}: {@link System#currentTimeMillis()} is a huge epoch-based
	 * number (currently on the order of 1.7 trillion), and a 32-bit float
	 * only has about 7 significant decimal digits of precision - dividing
	 * that straight into a {@code float} silently collapsed every call
	 * within the same session to the *same* rounded value (confirmed with
	 * real debug output: {@code scrollPx} was frozen at a single constant
	 * number the entire time), which is why the panorama never visibly
	 * panned at all, not just "slowly". {@code double} has ~15-17 digits of
	 * precision, comfortably enough to keep millisecond resolution at this
	 * magnitude.
	 */
	public static void drawPanorama(DrawContext context, int screenWidth, int screenHeight) {
		Theme theme = ThemeManager.active();

		float screenAspect = screenWidth / (float) screenHeight;
		int viewportWidth = Math.min(PANORAMA_WIDTH, Math.max(500, PANORAMA_WIDTH / 3));
		int viewportHeight = Math.min(PANORAMA_HEIGHT, Math.round(viewportWidth / screenAspect));
		int vOffset = (PANORAMA_HEIGHT - viewportHeight) / 2;

		double seconds = System.currentTimeMillis() / 1000.0;
		long rounded = Math.round(seconds * PAN_PIXELS_PER_SECOND);
		int scrollPx = (int) Math.floorMod(rounded, (long) PANORAMA_WIDTH);

		// Two draws only when the sliding window itself wraps past the
		// texture's right edge (not "whenever screenWidth is smaller than
		// what's left of the texture", the earlier bug's condition).
		int firstSourceWidth = Math.min(viewportWidth, PANORAMA_WIDTH - scrollPx);
		int firstDrawWidth = Math.round(screenWidth * (firstSourceWidth / (float) viewportWidth));
		context.drawTexture(RenderPipelines.GUI_TEXTURED, PANORAMA_TEXTURE, 0, 0, scrollPx, vOffset,
				firstDrawWidth, screenHeight, firstSourceWidth, viewportHeight, PANORAMA_WIDTH, PANORAMA_HEIGHT);
		if (firstSourceWidth < viewportWidth) {
			int secondSourceWidth = viewportWidth - firstSourceWidth;
			int secondDrawWidth = screenWidth - firstDrawWidth;
			context.drawTexture(RenderPipelines.GUI_TEXTURED, PANORAMA_TEXTURE, firstDrawWidth, 0, 0, vOffset,
					secondDrawWidth, screenHeight, secondSourceWidth, viewportHeight, PANORAMA_WIDTH, PANORAMA_HEIGHT);
		}
		// A soft themed tint + vignette so vanilla's own white button/logo
		// text and this theme's accent colors both stay readable over
		// whatever's in the source photo, matching the glass panels used
		// everywhere else in the mod rather than looking like a bare photo
		// pasted behind vanilla's UI.
		context.fill(0, 0, screenWidth, screenHeight, (theme.background() & 0x00FFFFFF) | 0x4D000000);
		context.fillGradient(0, screenHeight - 100, screenWidth, screenHeight, 0x00000000, 0x99000000);
	}

	/**
	 * A modern "frosted glass" pill with a HUD-style futuristic treatment: a
	 * soft drop shadow for depth, the theme's translucent surface fill, a
	 * bright top hairline (the edge real glass catches light on) inset by
	 * the corner radius so it never overhangs the rounded corners, detached
	 * corner brackets (a targeting-reticle look, common in sci-fi UIs) that
	 * extend and brighten on hover, and - while hovered - a pulsing
	 * accent-colored border, and a soft themed spotlight centered on the
	 * mouse cursor (see {@link #drawSpotlight}) - as if the cursor were a
	 * light slightly lighting up the glass beneath it, clipped to this
	 * button's own bounds so it never spills onto neighboring buttons.
	 *
	 * <p>The border is a rounded *ring*, not {@link VeloDraw#strokeRect} (a
	 * plain 4-straight-edge rectangle outline) - a square outline drawn
	 * around an already-rounded fill looked like it belonged to a different,
	 * squared-off button underneath. It's drawn as two nested {@link
	 * VeloDraw#fillRounded} calls instead: a full border-colored rounded
	 * rect, then a slightly smaller one in the fill color on top, leaving
	 * only a rounded ring of the border color showing around the edge.
	 *
	 * <p>Used for both the repositioned real vanilla buttons and (via
	 * {@link GlassMenuButton}) the new Store button, so every button in the
	 * stack - vanilla-sourced or new - renders pixel-identically instead of
	 * the Store button using a completely different widget's own styling.
	 */
	public static void drawGlassButton(DrawContext context, TextRenderer textRenderer, Layout layout, Text label,
			boolean hovered, boolean active, int mouseX, int mouseY) {
		drawGlassButton(context, textRenderer, layout, label, hovered, active, 0f, mouseX, mouseY);
	}

	/**
	 * Same as {@link #drawGlassButton(DrawContext, TextRenderer, Layout, Text, boolean, boolean, int, int)},
	 * plus a bright press-flash overlay that fades from {@code pressFlash ==
	 * 1} (the instant a real click landed) to {@code 0} - only {@link
	 * GlassMenuButton} tracks its own click time to drive this; the
	 * repositioned real vanilla buttons have no press-state hook reachable
	 * from this static drawer, so they're always drawn with {@code
	 * pressFlash == 0} via the overload above.
	 */
	public static void drawGlassButton(DrawContext context, TextRenderer textRenderer, Layout layout, Text label,
			boolean hovered, boolean active, float pressFlash, int mouseX, int mouseY) {
		Theme theme = ThemeManager.active();
		int radius = BUTTON_CORNER_RADIUS;
		int x = layout.x(), y = layout.y(), w = layout.width(), h = layout.height();
		boolean glow = hovered && active;

		VeloDraw.fillRounded(context, x, y + 2, w, h, radius, 0x33000000);

		int base = solidify(theme.surfaceWithOpacity(), MIN_BUTTON_ALPHA);
		int borderColor = glow ? theme.accentStart() : ((theme.text() & 0x00FFFFFF) | 0x30000000);
		int borderWidth = glow ? 2 : 1;

		VeloDraw.fillRounded(context, x, y, w, h, radius, borderColor);
		VeloDraw.fillRounded(context, x + borderWidth, y + borderWidth, w - borderWidth * 2, h - borderWidth * 2,
				Math.max(0, radius - borderWidth), base);

		if (glow) {
			// Radius off the button's own height, not max(w, h) - the wide
			// main buttons (200x20) made that a ~200px radius, which at a
			// 20px height just washes the whole button evenly regardless of
			// cursor position instead of reading as a light actually
			// following the mouse. Scaled off height keeps it a genuinely
			// localized, visibly moving highlight on both this (wide, short)
			// shape and the icon squares' (roughly square) one below.
			drawSpotlight(context, x + borderWidth, y + borderWidth, w - borderWidth * 2, h - borderWidth * 2,
					mouseX, mouseY, h * 3, theme.accentStart());
		}

		int highlightAlpha = glow ? 0x60 : 0x40;
		context.fill(x + radius, y + borderWidth, x + w - radius, y + borderWidth + 1, (highlightAlpha << 24) | 0xFFFFFF);

		if (pressFlash > 0f) {
			int flashAlpha = Math.round(0x90 * Math.min(1f, pressFlash));
			VeloDraw.fillRounded(context, x, y, w, h, radius, (flashAlpha << 24) | 0xFFFFFF);
		}

		// Always the theme's own text color (never forced white on hover) -
		// the fill only ever brightens/tints slightly, so this stays legible
		// against it either way instead of risking a light-on-light or
		// light-accent-on-white combination the theme never actually chose.
		int textColor = active ? theme.text() : 0xFF888888;
		context.drawCenteredTextWithShadow(textRenderer, bodyFont(label), x + w / 2, y + (h - 8) / 2, textColor);
	}

	/**
	 * A soft themed light centered on {@code (mouseX, mouseY)}, as if the
	 * cursor itself were a light slightly brightening the glass beneath it,
	 * clipped to {@code [x, x+w) x [y, y+h)} row by row so it never spills
	 * past this button's own bounds onto whatever's next to it. Kept
	 * deliberately dim (low peak alpha) - a highlight, not a floodlight.
	 *
	 * <p>Every fillable strip's alpha is computed directly, once, from its
	 * own true 2D distance to the cursor (a quadratic falloff that reaches
	 * exactly zero at {@code radius}, not a hard-edged cutoff) - not layered
	 * from several overlapping same-alpha rings the way an earlier version
	 * of this did. That approach compounds: alpha-blending the same low
	 * value over itself many times (to get a smooth many-ring gradient)
	 * pushes the actually-composited alpha at the center well past the
	 * per-ring value, *and* individual rings were still visible as faint
	 * concentric edges since 8-bit alpha can't represent the tiny per-ring
	 * increment a really smooth many-ring version would need. Computing the
	 * real alpha once per strip has neither problem - what {@code peakAlpha}
	 * says is the max is the actual max, and there are no ring edges to see
	 * because nothing is drawn twice at the same spot.
	 */
	private static void drawSpotlight(DrawContext context, int x, int y, int w, int h, int centerX, int centerY, int radius, int rgb) {
		int peakAlpha = 0x38;
		int top = Math.max(y, centerY - radius);
		int bottom = Math.min(y + h, centerY + radius + 1);
		for (int py = top; py < bottom; py++) {
			int dy = py - centerY;
			int halfWidth = (int) Math.round(Math.sqrt(Math.max(0, (double) radius * radius - (double) dy * dy)));
			int left = Math.max(x, centerX - halfWidth);
			int right = Math.min(x + w, centerX + halfWidth);
			if (right <= left) {
				continue;
			}
			int step = Math.max(1, (right - left) / 32);
			for (int px = left; px < right; px += step) {
				int segEnd = Math.min(right, px + step);
				double dx = (px + segEnd) / 2.0 - centerX;
				float t = (float) Math.min(1.0, Math.sqrt(dx * dx + (double) dy * dy) / radius);
				float falloff = 1f - t;
				int alpha = Math.round(peakAlpha * falloff * falloff);
				if (alpha > 0) {
					context.fill(px, py, segEnd, py + 1, (alpha << 24) | (rgb & 0xFFFFFF));
				}
			}
		}
	}

	/** Logo + "VELO CLIENT" wordmark (custom title font) + version pill, top-anchored at {@code topY} - see {@link #brandingTop} for how that's kept in sync with the button stack beneath it. */
	public static void drawBranding(DrawContext context, TextRenderer textRenderer, int screenWidth, int topY) {
		Theme theme = ThemeManager.active();
		Text title = titleFont("VELO CLIENT");
		int scale = 2;
		int titleWidth = textRenderer.getWidth(title) * scale;
		int logoSize = 28;
		int totalWidth = logoSize + 10 + titleWidth;
		int x = (screenWidth - totalWidth) / 2;
		int y = topY;

		context.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, x, y, 0f, 0f, logoSize, logoSize, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE);

		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x + logoSize + 10, y + (logoSize - 8 * scale) / 2f);
		context.getMatrices().scale(scale, scale);
		context.drawTextWithShadow(textRenderer, title, 0, 0, LOGO_RED);
		context.getMatrices().popMatrix();

		String versionLabel = "v" + VERSION;
		int versionWidth = textRenderer.getWidth(versionLabel);
		context.drawTextWithShadow(textRenderer, versionLabel, screenWidth - versionWidth - 8, 8, theme.text());
	}

	/**
	 * The escape menu header: the Velo logo/wordmark, at real title
	 * size/weight (matching {@link #drawBranding}'s treatment, not a small
	 * watermark line) so it actually reads as this screen's title.
	 *
	 * <p>No backing of any kind is drawn behind it - fully transparent,
	 * straight over the blurred gameplay background. An older version drew a
	 * solid-fading-to-transparent band here to cover vanilla's own "Game
	 * Menu"/"Paused" heading, which sat in the same spot; that heading is now
	 * genuinely removed at the source (see {@link
	 * net.veloclient.velo.client.mixin.EscapeMenuMixin}'s class doc), so
	 * there is nothing left that needs covering.
	 */
	public static void drawCompactBranding(DrawContext context, TextRenderer textRenderer, int screenWidth, int y) {
		Text title = titleFont("VELO CLIENT");
		int scale = 2;
		int titleWidth = textRenderer.getWidth(title) * scale;
		int logoSize = 24;
		int totalWidth = logoSize + 10 + titleWidth;
		int x = (screenWidth - totalWidth) / 2;

		context.drawTexture(RenderPipelines.GUI_TEXTURED, LOGO_TEXTURE, x, y, 0f, 0f, logoSize, logoSize, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE, LOGO_SOURCE_SIZE);

		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x + logoSize + 10, y + (logoSize - 8 * scale) / 2f);
		context.getMatrices().scale(scale, scale);
		context.drawTextWithShadow(textRenderer, title, 0, 0, LOGO_RED);
		context.getMatrices().popMatrix();
	}

	/** The version label, bottom-left of the screen - the escape menu's own corner, out of the way of the centered header/button stack, instead of crowding the header row like {@link #drawBranding}'s copy still does on the title screen. */
	public static void drawVersionCorner(DrawContext context, TextRenderer textRenderer, int screenHeight) {
		Theme theme = ThemeManager.active();
		String versionLabel = "v" + VERSION;
		context.drawTextWithShadow(textRenderer, versionLabel, 8, screenHeight - 14, theme.text());
	}

	/** Total pixel height {@link #drawCompactBranding} occupies, so the escape menu can stack its button column directly beneath it. */
	public static int compactBrandingHeight() {
		return 34;
	}
}
