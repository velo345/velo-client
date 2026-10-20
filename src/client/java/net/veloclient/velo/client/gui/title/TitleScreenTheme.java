package net.veloclient.velo.client.gui.title;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
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
	/**
	 * Pause menu rows, top to bottom. Includes the buttons servers can add (Custom Options... from
	 * server dialogs, Server Links..., Quick Actions...) - before, those kept vanilla's style and
	 * position and ended up drawn behind this column. Report Bugs / Feedback / Player Reporting stay
	 * small icon buttons below the column ({@link #ESCAPE_EXTRA_ICONS}).
	 */
	public static final List<String> ESCAPE_KEY_ORDER = List.of(
			"menu.returnToGame", "gui.advancements", "gui.stats", "$store", "menu.options",
			"menu.custom_options", "menu.server_links", "menu.quick_actions",
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
			new ExtraIcon("report_player", List.of("player reporting")),
			new ExtraIcon("bugs", List.of("bug", "report")),
			new ExtraIcon("feedback", List.of("feedback")),
			new ExtraIcon("friends", List.of("friend")),
			new ExtraIcon("teleport", List.of("teleport", "warp")),
			new ExtraIcon("mods", List.of("mods")));

	/** Same idea for the title screen's own corner buttons (language/accessibility) plus Essential/ModMenu additions. */
	public static final List<ExtraIcon> TITLE_EXTRA_ICONS = List.of(
			new ExtraIcon("friends", List.of("friend")),
			new ExtraIcon("language", List.of("language")),
			new ExtraIcon("accessibility", List.of("accessibility")));

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
	/** Large Inter SemiBold (15px) rasterized per GUI scale - for big numbers, crisp instead of a scaled-up small font. */
	public static final Identifier BIG_FONT = Identifier.of("velo-client", "big");

	private static final Identifier LOGO_TEXTURE = Identifier.of("velo-client", "textures/icon/logo.png");
	private static final int LOGO_SOURCE_SIZE = 500;
	/** The Velo brand red, sampled straight from logo.png - the "VELO CLIENT" wordmark always uses this, never the active theme's accent color, so it stays recognizable as the Velo brand regardless of what theme is selected. */
	private static final int LOGO_RED = 0xFFF24759;
	/*
	 * The panorama is a path-traced 360-degree central-cylindrical render (tools/panorama), so
	 * panning it sideways is distortion-free and its two ends meet seamlessly. Four textures:
	 * the scene itself, the theme-glow layer (white light from the crystals/portal/lanterns,
	 * added on top in the theme accent color), and the looping duel as frame atlases of the
	 * same two layers. Glow layers are soft light, so they are stored at half resolution and
	 * scaled back up by the linear filter. Glow textures have alpha 1/255 - vanilla's GUI shader discards alpha 0 -
	 * so the premultiplied pipeline adds them almost exactly additively.
	 */
	private static final Identifier PANO_BASE = Identifier.of("velo-client", "textures/gui/title/pano_base.png");
	private static final Identifier PANO_GLOW = Identifier.of("velo-client", "textures/gui/title/pano_glow.png");
	private static final Identifier DUEL_BASE = Identifier.of("velo-client", "textures/gui/title/pano_duel.png");
	private static final Identifier DUEL_GLOW = Identifier.of("velo-client", "textures/gui/title/pano_duel_glow.png");
	private static final int PANO_W = 6144;
	private static final int PANO_H = 1152;
	/** Where the duel's animated crop sits in the panorama, and its frame atlas layout. */
	private static final int DUEL_X = 2776;
	private static final int DUEL_Y = 372;
	private static final int DUEL_FRAME_W = 640;
	private static final int DUEL_FRAME_H = 432;
	private static final int DUEL_COLUMNS = 6;
	private static final int DUEL_FRAMES = 24;
	private static final float DUEL_FPS = 12f;
	/** Panorama pixels scrolled per second - one full turn takes about four minutes. */
	private static final double PAN_PIXELS_PER_SECOND = 25.0;
	private static final int EMBERS = 34;
	/** First panorama draw - the pan starts on the duel instead of wherever the clock happens to be. */
	private static long panoramaStart;
	/** Vanilla's own main-menu button width (its {@code ButtonWidget.DEFAULT_WIDTH}) - matched instead of stretching toward the screen edges, which is what made buttons look oversized at high/Auto GUI Scale (a small scaled screen has little margin, so "screenWidth - 40" was nearly the full width). */
	private static final int BUTTON_WIDTH = 200;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 6;
	/** A modern, subtle rounding rather than the near-pill look a taller radius gives a 20px-high button. */
	private static final int BUTTON_CORNER_RADIUS = 7;
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

	public static Text bigFont(String text) {
		return Text.literal(text).setStyle(Style.EMPTY.withFont(new net.minecraft.text.StyleSpriteSource.Font(BIG_FONT)));
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

	public static Text bigFont(String text) {
		return Text.literal(text).setStyle(Style.EMPTY.withFont(new net.minecraft.network.chat.FontDescription.Resource(BIG_FONT)));
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

	/** Gap between the button stack and the small icon row under it - the same gap as between the buttons, so it all reads as one column. */
	public static int iconRowGap(int screenHeight, int count) {
		int rowHeight = Math.min(BUTTON_HEIGHT, Math.max(12, (screenHeight - 40) / Math.max(1, count + 3)));
		return Math.min(BUTTON_GAP, Math.max(2, rowHeight / 4)) + 2;
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
	 * The slowly turning title panorama: the scene, the looping sword duel composited into it,
	 * the theme-colored glow, drifting embers, and a soft scrim behind the menu column.
	 *
	 * <p>Everything is drawn in panorama-pixel space under one scale + sub-pixel translate, so
	 * the pan is smooth instead of stepping a whole GUI pixel at a time. The strip is drawn
	 * twice (at 0 and one width further) which covers every wrap position. Time is measured
	 * with {@code nanoTime} deltas as doubles - a float of epoch millis loses all precision.
	 */
	public static void drawPanorama(DrawContext context, int screenWidth, int screenHeight) {
		Theme theme = ThemeManager.active();
		long now = System.nanoTime();
		if (panoramaStart == 0) {
			panoramaStart = now;
		}
		double seconds = (now - panoramaStart) / 1_000_000_000.0;

		float scale = Math.max(screenHeight / (float) PANO_H, screenWidth / (float) PANO_W);
		float viewWidth = screenWidth / scale;
		float viewTop = (PANO_H - screenHeight / scale) / 2f;
		// Opens with the duel on the right, clear of the button column, then lets it drift across.
		double offset = (DUEL_X + DUEL_FRAME_W / 2.0 - viewWidth * 0.8 + seconds * PAN_PIXELS_PER_SECOND) % PANO_W;
		if (offset < 0) {
			offset += PANO_W;
		}

		float framePos = (float) ((seconds * DUEL_FPS) % DUEL_FRAMES);
		int frame = (int) framePos;
		int nextFrame = (frame + 1) % DUEL_FRAMES;
		float blend = framePos - frame;
		int accent = theme.accentStart() | 0xFF000000;

		context.getMatrices().pushMatrix();
		context.getMatrices().scale(scale, scale);
		context.getMatrices().translate((float) -offset, -viewTop);
		for (int copy = 0; copy < 2; copy++) {
			int x = copy * PANO_W;
			context.drawTexture(RenderPipelines.GUI_TEXTURED, PANO_BASE, x, 0, 0f, 0f, PANO_W, PANO_H,
					PANO_W, PANO_H, PANO_W, PANO_H, 0xFFFFFFFF);
			// Duel frames carry a feathered alpha edge, so they melt into the static render.
			drawDuelFrame(context, RenderPipelines.GUI_TEXTURED, DUEL_BASE, x, frame, 0xFFFFFFFF);
			drawDuelFrame(context, RenderPipelines.GUI_TEXTURED, DUEL_BASE, x, nextFrame,
					(Math.round(blend * 255) << 24) | 0xFFFFFF);
			context.drawTexture(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, PANO_GLOW, x, 0, 0f, 0f, PANO_W, PANO_H,
					PANO_W / 2, PANO_H / 2, PANO_W / 2, PANO_H / 2, accent);
			drawDuelFrame(context, RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, DUEL_GLOW, x, frame, scaleRgb(accent, 1f - blend));
			drawDuelFrame(context, RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, DUEL_GLOW, x, nextFrame, scaleRgb(accent, blend));
		}
		context.getMatrices().popMatrix();

		drawEmbers(context, screenWidth, screenHeight, seconds, scale, accent);

		// A light grade toward the theme background, edge fades, and a soft scrim behind the
		// logo + button column so the wordmark never sits on bright sky unprotected.
		context.fill(0, 0, screenWidth, screenHeight, (theme.background() & 0x00FFFFFF) | 0x1E000000);
		context.fillGradient(0, 0, screenWidth, 48, 0x55000000, 0x00000000);
		context.fillGradient(0, screenHeight - 120, screenWidth, screenHeight, 0x00000000, 0xAA000000);
		// The menu column lives on the left (TitleMenu): the scene fades to the theme's dark there.
		int dark = theme.background() & 0x00FFFFFF;
		int solid = Math.round(screenWidth * 0.22f);
		context.fill(0, 0, solid, screenHeight, dark | 0xD8000000);
		MenuKit.hGradient(context, solid, 0, Math.round(screenWidth * 0.36f), screenHeight, dark | 0xD8000000, dark);
	}

	private static void drawDuelFrame(DrawContext context, com.mojang.blaze3d.pipeline.RenderPipeline pipeline, Identifier texture,
			int copyX, int frame, int color) {
		// nothing to add: a fully transparent base frame, or a black (zero-brightness) glow frame
		boolean additive = pipeline != RenderPipelines.GUI_TEXTURED;
		if (additive ? (color & 0xFFFFFF) == 0 : (color >>> 24) == 0) {
			return;
		}
		int div = additive ? 2 : 1; // the glow atlas is half resolution
		int frameW = DUEL_FRAME_W / div;
		int frameH = DUEL_FRAME_H / div;
		float u = (frame % DUEL_COLUMNS) * frameW;
		float v = (frame / DUEL_COLUMNS) * frameH;
		context.drawTexture(pipeline, texture, copyX + DUEL_X, DUEL_Y, u, v, DUEL_FRAME_W, DUEL_FRAME_H,
				frameW, frameH, frameW * DUEL_COLUMNS, frameH * (DUEL_FRAMES / DUEL_COLUMNS), color);
	}

	/** Multiplies an opaque color's RGB by {@code factor} - for additive layers, that's their brightness. */
	private static int scaleRgb(int argb, float factor) {
		int r = Math.round(((argb >> 16) & 0xFF) * factor);
		int g = Math.round(((argb >> 8) & 0xFF) * factor);
		int b = Math.round((argb & 0xFF) * factor);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	/** Theme-colored embers drifting up through the scene, a little faster than the pan (parallax). */
	private static void drawEmbers(DrawContext context, int screenWidth, int screenHeight, double seconds, float scale, int accent) {
		int span = screenWidth + 60;
		int core = VeloAnim.lerpArgb(accent, 0xFFFFFFFF, 0.45f);
		for (int i = 0; i < EMBERS; i++) {
			double r1 = hash(i * 3 + 1), r2 = hash(i * 3 + 2), r3 = hash(i * 3 + 3);
			double life = (seconds * (0.035 + 0.035 * r2) + r1) % 1.0;
			double drift = seconds * PAN_PIXELS_PER_SECOND * scale * (1.25 + 0.5 * r3);
			float x = (float) (((r3 * span - drift) % span + span) % span) - 30
					+ (float) Math.sin(seconds * 0.7 + i) * 6f;
			float y = (float) (screenHeight * (1.02 - life * 0.85));
			float fade = (float) Math.sin(life * Math.PI);
			float size = 0.6f + (float) r2 * 0.9f;
			VeloDraw.fillCircle(context, x, y, size * 3.2f, VeloUi.withAlpha(accent, Math.round(0x2A * fade)));
			VeloDraw.fillCircle(context, x, y, size, VeloUi.withAlpha(core, Math.round(0xC8 * fade)));
		}
	}

	private static double hash(int n) {
		double s = Math.sin(n * 12.9898) * 43758.5453;
		return s - Math.floor(s);
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
		float hover = hoverProgress(x, y, glow);

		VeloDraw.shadow(context, x, y, w, h, radius, 8, 1, VeloUi.withAlpha(0xFF000000, 0x38 + Math.round(0x20 * hover)));
		int base = solidify(theme.surfaceWithOpacity(), MIN_BUTTON_ALPHA);
		int top = VeloAnim.lerpArgb(VeloAnim.lerpArgb(base, 0xFFFFFFFF, 0.07f),
				VeloAnim.lerpArgb(base, theme.accentStart() | 0xFF000000, 0.28f), hover);
		int bottom = VeloAnim.lerpArgb(base, VeloAnim.lerpArgb(base, theme.accentStart() | 0xFF000000, 0.12f), hover);
		VeloDraw.fillRoundedGradient(context, x, y, w, h, radius, top, bottom);
		if (hover > 0.01f) {
			drawSpotlight(context, x + 1, y + 1, w - 2, h - 2, mouseX, mouseY, h * 3,
					theme.accentStart() & 0xFFFFFF);
		}
		int border = VeloAnim.lerpArgb(VeloUi.withAlpha(theme.text(), 0x2A), theme.accentStart() | 0xFF000000, hover);
		VeloDraw.strokeRounded(context, x, y, w, h, radius, border);
		// A one-pixel top highlight sells the "glass" without looking beveled.
		context.fill(x + radius, y + 1, x + w - radius, y + 2, VeloUi.withAlpha(0xFFFFFFFF, 0x1C + Math.round(0x18 * hover)));

		if (pressFlash > 0f) {
			int flashAlpha = Math.round(0x70 * Math.min(1f, pressFlash));
			VeloDraw.fillRounded(context, x, y, w, h, radius, (flashAlpha << 24) | 0xFFFFFF);
		}

		int textColor = active ? VeloAnim.lerpArgb(theme.text() | 0xFF000000, 0xFFFFFFFF, hover) : 0xFF888888;
		context.drawCenteredTextWithShadow(textRenderer, bodyFont(label), x + w / 2, y + (h - 8) / 2, textColor);
	}

	/** Per-button hover fade, keyed by position (the static drawer has no widget to keep state on). */
	private static final java.util.Map<Long, float[]> HOVER = new java.util.HashMap<>();

	private static float hoverProgress(int x, int y, boolean hovered) {
		long key = ((long) x << 32) ^ (y & 0xFFFFFFFFL);
		float[] state = HOVER.computeIfAbsent(key, k -> new float[] {0f, 0f});
		long now = System.nanoTime();
		float dt = state[1] == 0f ? 0f : Math.min(0.1f, (now / 1_000_000L - state[1]) / 1000f);
		state[1] = now / 1_000_000L;
		state[0] = VeloAnim.step(state[0], hovered ? 1f : 0f, dt);
		if (HOVER.size() > 256) {
			HOVER.clear();
		}
		return state[0];
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
