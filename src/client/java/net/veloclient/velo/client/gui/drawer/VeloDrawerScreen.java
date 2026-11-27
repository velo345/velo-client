package net.veloclient.velo.client.gui.drawer;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.title.QuickToggles;
import net.veloclient.velo.client.gui.widget.ModuleIcons;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloNavIcons;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.MenuShell;
import net.veloclient.velo.client.gui.window.ModuleScenes;
import net.veloclient.velo.client.hud.HudManager;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.util.Keys;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.ModuleRegistry;
import net.veloclient.velo.profile.VeloProfileManager;
import net.veloclient.velo.client.profile.VeloProfileStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * R-Shift (2026-11-24 redesign, design/rshift-drawer/): the game stays visible and the HUD is editable right away -
 * drag an element, pull its corner to scale it, click it for its settings - while a glass drawer on the right holds
 * everything else: search, pinned modules, the categories, each module's settings, profiles, and a dock for Locker,
 * Friends, Clips, World Map and Settings.
 *
 * <p>The drawer can slide away for room (and slides away by itself while you move something near it), HUD modules can be
 * dragged out of it onto the screen, and it keeps its own size: everything in it is laid out on a 540-unit-high canvas
 * scaled to the window, so Minecraft's GUI scale never makes it huge or tiny.
 */
public final class VeloDrawerScreen extends Screen implements net.veloclient.velo.client.gui.window.ModuleConfigScreen.Reopenable {

	// ---- the drawer's own coordinate system: the screen is always REF_H units high ----
	private static final float REF_H = 540f;
	private static final float DW = 300f;   // drawer width
	private static final float M = 10f;     // gap to the screen edge
	private static final float HEADER = 44f;
	private static final float PAD = 12f;
	private static final float ROW = 26f;
	// animation times (seconds), linear progress + easing curves: calm, not snappy
	private static final float OPEN_TIME = 0.34f;
	private static final float PAGE_TIME = 0.36f;
	private static final float EXPAND_TIME = 0.42f;
	private static final float HANDLE_TIME = 0.26f;

	private static final int GLASS = 0x8C141417;        // the panel, over the frosted (blurred) game behind it
	private static final int GLASS_FULL = 0x8C141418;   // the full menu
	private static final Identifier LOGO = Identifier.of("velo-client", "textures/icon/logo.png");
	private static final int TEXT_1 = 0xEBFFFFFF;
	private static final int GLASS_EDGE = 0x26FFFFFF;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int TEXT_2 = 0xB8FFFFFF;
	private static final int TEXT_3 = 0x80FFFFFF;
	private static final int HOVER = 0x14FFFFFF;
	private static final int CARD = 0x10FFFFFF;
	private static final int RED = 0xFFE5373E;

	/** A category of the drawer: some of the module categories under one friendlier name. */
	private record Cat(String name, String icon, List<ModuleCategory> categories) {
		List<Module> modules() {
			// asked for every frame: built once per category set (and again only if modules get registered later)
			java.util.Collection<Module> all = ModuleRegistry.all();
			if (all != catSnapshot) {
				CAT_MODULES.clear();
				catSnapshot = all;
			}
			return CAT_MODULES.computeIfAbsent(name, n -> {
				List<Module> out = new ArrayList<>();
				for (ModuleCategory c : categories) {
					out.addAll(ModuleRegistry.byCategory(c));
				}
				return List.copyOf(out);
			});
		}
	}

	private static final java.util.Map<String, List<Module>> CAT_MODULES = new java.util.HashMap<>();
	private static java.util.Collection<Module> catSnapshot;

	private enum Kind { HOME, CATEGORY, MODULE, FEATURES }

	/** Everything that isn't a module: always visible as tiles (panel Home, the full menu), no hidden More menu. */
	private record Feature(String id, String icon, String name, String blurb) {
	}

	private static final Feature[] FEATURES = {
			new Feature("skins", "d_shirt", "Locker", "Cloaks, wings, emotes and outfits"),
			new Feature("friends", "d_users", "Friends", "Friends, party and messages"),
			new Feature("clips", "d_video", "Clips", "Your clips and screenshots"),
			new Feature("map", "d_map", "World Map", "The whole map of your world"),
			new Feature("waypoints", "d_waypoint", "Waypoints", "Your saved places"),
			new Feature("fight_recap", "d_activity", "Fight Recap", "Replay your last fights"),
			new Feature("mods", "d_puzzle", "Mods", "Your other mods and their settings"),
			new Feature("schematics", "d_layers", "Schematics", "Build from schematics"),
			new Feature("redstone", "d_bulb", "Redstone", "Tools for redstone builds"),
			new Feature("queue", "d_clock", "Queue", "Wait in server queues in the background"),
			new Feature("party", "d_globe", "Hosting", "Open your world to friends"),
			new Feature("globe", "d_cloud", "Cloud Sync", "Your setup on every PC"),
			new Feature("profiles", "d_idcard", "Profiles", "Switch and manage profiles"),
			new Feature("settings", "d_sliders", "Settings", "Velo's own settings")};

	private List<Feature> features() {
		if (featureCache != null) {
			return featureCache;
		}
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (MenuShell.Entry e : MenuShell.entries()) {
			ids.add(e.id());
		}
		List<Feature> out = new ArrayList<>();
		for (Feature f : FEATURES) {
			if (ids.contains(f.id())) {
				out.add(f);
			}
		}
		featureCache = out;
		return out;
	}

	// ---- per-frame work kept cheap (the profile name was a disk read every frame, fit() measured letter by letter)
	private List<Feature> featureCache;
	private String profileName;
	private long profileAt;
	private final Map<String, Text> fitCache = new HashMap<>();

	private String activeProfile() {
		long now = System.currentTimeMillis();
		if (profileName == null || now - profileAt > 1000) {
			profileName = VeloProfileManager.activeName();
			profileAt = now;
		}
		return profileName;
	}

	private record Page(Kind kind, Cat cat, Module module) {
	}

	/** Something clickable this frame, in drawer units. */
	private static final class Hit {
		final float x, y, w, h;
		final Runnable click;
		java.util.function.DoubleConsumer slide;   // sliders: called with 0..1 while pressed
		Module grip;                                 // HUD rows: drag out onto the screen

		Hit(float x, float y, float w, float h, Runnable click) {
			this.x = x;
			this.y = y;
			this.w = w;
			this.h = h;
			this.click = click;
		}

		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Cat> cats = new ArrayList<>();
	private final List<Page> stack = new ArrayList<>();
	private Page fromPage;
	private float trans = 1f;
	private int transDir = 1;
	private final Map<Page, Float> scroll = new HashMap<>();
	private final Map<Object, Float> anim = new HashMap<>();
	private final List<Hit> hits = new ArrayList<>();
	private final List<Hit> overlayHits = new ArrayList<>();

	private boolean open = true;
	private float openAnim = 0f;
	private float handleAnim = 0f;
	// full menu (the panel grown into the big glass menu); what R-Shift opens is DrawerPrefs
	private boolean expanded = DrawerPrefs.startFull();
	private float expandAnim = expanded ? 1f : 0f;
	private float lastDelta;
	// closing animates out (Esc, the menu key, close()) before the screen really goes
	private boolean closing;
	private boolean closeScheduled;
	private long closingSince;
	// text: Geist at a size whose glyph atlas maps 1:1 onto screen pixels (font dr_*_n has oversample n/2)
	private int fontN = 5;
	private float textScale = 1.25f;
	// where overlays sit, set by whichever layout is live this frame
	private float[] moreAnchor = {0, 0, 0};
	private float bodyTop;
	private float bodyBottom;
	private long lastNanos = -1;
	private float dt;
	private float k = 1f;   // gui px per drawer unit

	private final StringBuilder search = new StringBuilder();
	private boolean profileMenu;
	/** The Pinned list in the panel is folded away (kept for the session). */
	private static boolean pinsFolded;
	private boolean moreMenu;
	private ConfigField.ChoiceField dropdown;
	private float[] dropdownAt;
	private ConfigField.KeybindField listening;
	private ConfigField.ChordKeybindField listeningChord;
	private final java.util.LinkedHashSet<Integer> chordHeld = new java.util.LinkedHashSet<>();
	private final java.util.LinkedHashSet<Integer> chordMax = new java.util.LinkedHashSet<>();
	private ConfigField.TextField editing;
	private final StringBuilder editBuffer = new StringBuilder();
	private long swallowCharsUntil;

	// HUD editing (gui coordinates)
	private HudModule dragging;
	private HudModule resizing;
	private HudModule selected;
	/** The element under the cursor (last frame): it shows example content, so it isn't an empty box while idle. */
	private HudModule sampled;
	private int dragOffsetX;
	private int dragOffsetY;
	private boolean dragMoved;
	private float resizeGrabX;
	private float resizeGrabY;
	// a module dragged out of the drawer
	private Module ghost;
	private boolean ghostMoved;
	// slider being dragged
	private Hit sliding;
	private double mouseU;
	private double mouseV;

	public VeloDrawerScreen() {
		super(Text.literal("Velo"));
		cats.add(new Cat("HUD", "d_layout", List.of(ModuleCategory.HUD)));
		cats.add(new Cat("PvP", "d_swords", List.of(ModuleCategory.PVP)));
		cats.add(new Cat("Visuals", "d_sparkles", List.of(ModuleCategory.RENDERING, ModuleCategory.COSMETICS)));
		cats.add(new Cat("Utility", "d_wrench", List.of(ModuleCategory.QOL)));
		cats.add(new Cat("Performance", "d_zap", List.of(ModuleCategory.PERFORMANCE)));
		cats.add(new Cat("Advanced", "d_cpu", List.of(ModuleCategory.SERVER_TOOLS, ModuleCategory.DEBUG)));
		if (net.veloclient.velo.client.gui.ModMenuScreen.addonsAvailable()) {
			cats.add(new Cat("Addons", "d_puzzle", List.of(ModuleCategory.ADDONS)));
		}
		stack.add(new Page(Kind.HOME, null, null));
		// opened over the game (not handed over from another Velo window): the menu's own opening sound
		if (net.veloclient.velo.client.util.ClientCompat.currentScreen() == null) {
			net.veloclient.velo.client.sound.VeloUiSounds.menuOpen();
		}
	}

	/** Opens straight on a module's settings (clicking a HUD element, or from elsewhere). */
	public VeloDrawerScreen openModule(Module module) {
		explicitOpen = true;
		Cat cat = catOf(module);
		stack.clear();
		stack.add(new Page(Kind.HOME, null, null));
		if (cat != null) {
			stack.add(new Page(Kind.CATEGORY, cat, null));
		}
		stack.add(new Page(Kind.MODULE, cat, module));
		return this;
	}

	@Override
	public void reopen() {
	}

	// ---- dev tour hooks (devtools/DrawerTour) ----
	public void testCategory(String name) {
		for (Cat cat : cats) {
			if (cat.name().equals(name)) {
				go(new Page(Kind.CATEGORY, cat, null));
			}
		}
	}

	public void testModule(String id) {
		ModuleRegistry.get(id).ifPresent(m -> go(new Page(Kind.MODULE, catOf(m), m)));
	}

	public void testProfileMenu(boolean show) {
		profileMenu = show;
	}

	public void testOpen(boolean show) {
		open = show;
	}

	/** Opens straight as the full menu (the pause menu's "All modules", Mod Menu's config button). */
	public VeloDrawerScreen full() {
		explicitOpen = true;
		expanded = true;
		expandAnim = 1f;
		return this;
	}

	public void testFeatures() {
		showTab(cats.size() + 1);
	}

	public void testExpanded(boolean full) {
		setExpanded(full);
	}

	public void testTourNext() {
		tourNext();
	}

	public void testHome() {
		stack.subList(1, stack.size()).clear();
		trans = 1f;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// The world stays visible: no blur or dimming from vanilla.
	// Behind the full menu the game's own blur runs (no black overlay); 1.21.1 blurs at the start of render().
	//? if >=1.21.2 && <26.1 {
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
		blurFrame = decideBlurFrame();
		if (blurOn()) {
			// the HUD first, always: on a blur frame it gets frosted behind the glass; in between the reused blurred picture
			// covers it inside the glass (drawn live on top it flickered sharp/frosted)
			hudBehind = hudUnderBlur(context, delta);
		}
		if (blurFrame) {
			net.veloclient.velo.client.gui.glass.SharpFrame.newLayer(context);   // so the blur (and the sharp copy) includes the HUD
			this.applyBlur(context);
		}
	}
	//?} else if >=26.1 {
	/*@Override
	public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		blurFrame = decideBlurFrame();
		if (PROMO_MATTE) {
			context.fill(0, 0, this.width, this.height, 0xFF000000);   // promo capture: the menu alone, over black
			hudBehind = true;
		} else if (blurOn()) {
			// the HUD first, always: on a blur frame it gets frosted behind the glass; in between the reused blurred picture
			// covers it inside the glass (drawn live on top it flickered sharp/frosted)
			hudBehind = hudUnderBlur(context, delta);
		}
		if (blurFrame) {
			net.veloclient.velo.client.gui.glass.SharpFrame.newLayer(context);   // so the blur (and the sharp copy) includes the HUD
			this.extractBlurredBackground(context);
		}
	}
	*///?} else {
	/*@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}
	*///?}

	/** Dev, promo capture ({@code -Dvelo.promoMatte}): black behind the menu instead of the game, no HUD or hint. */
	static final boolean PROMO_MATTE = System.getProperty("velo.promoMatte") != null;

	/** Promo capture: the glass right now as {x, y, w, h, radius} in gui pixels. */
	public float[] promoRect() {
		float[] r = rectNow();
		float rad = net.veloclient.velo.client.gui.glass.GlassPrefs.radius(18f + 6f * easeInOut(expandAnim));
		return new float[] {r[0] * k, r[1] * k, r[2] * k, r[3] * k, rad * k, Math.min(1f, openAnim * 1.6f)};
	}

	/** True when this frame's HUD elements were drawn under the blur (frosted behind the glass, sharp around it). */
	private boolean hudBehind;

	/**
	 * Whether this frame runs the real blur (2026-11-26, owner: "mit R-Shift droppen FPS um 1000" - the blur was ~0.5 ms
	 * a frame): only at the monitor's refresh rate; the frames in between reuse the last blurred picture behind the glass.
	 */
	private boolean blurFrame;
	private long lastBlurNanos;

	private boolean decideBlurFrame() {
		if (!blurOn()) {
			return false;
		}
		long now = System.nanoTime();
		boolean need = !net.veloclient.velo.client.gui.glass.SharpFrame.hasBlurred() || now - lastBlurNanos >= blurIntervalNanos()
				|| openAnim < 0.999f || morphT < 0.999f || pending != null;
		if (need) {
			lastBlurNanos = now;
		}
		return need;
	}

	private static long blurIntervalNanos() {
		int hz = 0;
		try {
			//? if >=26.3 {
			/*var mode = net.minecraft.client.Minecraft.getInstance().getWindow().getActiveVideoMode();
			hz = mode == null ? 0 : Math.round(mode.getRefreshRate());
			*///?} else if >=26.2 {
			/*hz = net.minecraft.client.Minecraft.getInstance().getWindow().getRefreshRate();
			*///?}
		} catch (RuntimeException | LinkageError e) {
			hz = 0;
		}
		// behind frosted glass 60 updates a second look the same as more (a blur frame costs ~2 ms)
		return 1_000_000_000L / Math.min(60, Math.max(30, hz <= 0 ? 60 : hz));
	}

	/** The HUD elements, drawn before the blur so the glass frosts them too (the edit outlines go on top later). */
	private boolean hudUnderBlur(DrawContext context, float delta) {
		if (expandAnim >= 0.5f) {
			return false;
		}
		// the HUD under the glass is cached like the menu (it was rebuilt every frame here - ~0.2 ms)
		if (dragging == null && resizing == null && hudCache.replay(context, this.width, this.height)) {
			return true;
		}
		hudCache.begin(context, this.width, this.height);
		boolean completed = false;
		try {
			drawHudUnderBlur(context, delta);
			completed = true;
		} finally {
			hudCache.end(context, completed);
		}
		return true;
	}

	private final net.veloclient.velo.client.gui.glass.GuiFrameCache hudCache = new net.veloclient.velo.client.gui.glass.GuiFrameCache();

	private void drawHudUnderBlur(DrawContext context, float delta) {
		net.veloclient.velo.client.hud.HudAutoLayout.update(this.width, this.height);
		for (HudModule hud : hudModules()) {
			drawElement(context, hud, delta);
		}
	}

	/**
	 * Every element with its example content while the menu is open, not only the hovered one (owner 2026-11-27: a widget
	 * with no data right now - an idle Media Player, no target - is an empty box you can't see or aim at). The example
	 * only replaces what would be empty; elements that have live data keep showing it.
	 */
	private void drawElement(DrawContext context, HudModule hud, float delta) {
		boolean sample = true;
		if (sample) {
			net.veloclient.velo.client.hud.HudSample.begin();
		}
		try {
			HudManager.renderScaled(context, hud, this.width, this.height, delta);
		} finally {
			if (sample) {
				net.veloclient.velo.client.hud.HudSample.end();
			}
		}
	}

	private boolean blurOn() {
		return blurRadius() > 0;
	}

	/** The menu blur while this is open: strong behind the full menu, lighter behind the panel, faded in and out. */
	/** Dev benchmark (DrawerBench): the panel without its blur, to see what the blur costs. */
	public static boolean benchNoBlur;

	public int blurRadius() {
		if (benchNoBlur) {
			return 0;
		}
		// the panel gets a lighter blur (the HUD stays crisp on top); it lifts when the panel slides away to place things
		float e = easeInOut(expandAnim);
		return Math.round(net.veloclient.velo.client.gui.glass.GlassPrefs.blurRadius(Math.round(10f + 2f * e)) * ease(openAnim));   // Settings > Theme: Blur (only behind the glass)
	}

	// ---------------------------------------------------------------- geometry

	private float W() {
		return this.width / k;
	}

	private float H() {
		return this.height / k;
	}

	/** The drawer's left edge in units (slides right when hidden). */
	/** The panel's rect in units (slides right when hidden). */
	private float[] panelRect() {
		float slide = (1f - ease(openAnim)) * (DW + M + 24f);
		return new float[] {W() - M - DW + slide, M, DW, H() - 2 * M};
	}

	/** The full menu's rect: centred, as big as the window allows (lifts in a little when opening). */
	private float[] fullRect() {
		float fw = Math.min(W() - 40f, 900f);
		float fh = Math.min(H() - 40f, 520f);
		float lift = (1f - ease(openAnim)) * 14f;
		return new float[] {(W() - fw) / 2f, (H() - fh) / 2f + lift, fw, fh};
	}

	/** The glass right now: the panel morphing into the full menu and back. */
	private float[] rectNow() {
		float[] base = baseRect();
		if (pending != null) {
			return lerpRect(handoffFrom, handoffTo, easeInOut(handoffT));   // growing into the window being opened
		}
		if (morphFrom != null && morphT < 1f) {
			return lerpRect(morphFrom, base, easeInOut(morphT));   // coming back from a window
		}
		return base;
	}

	private static float[] lerpRect(float[] a, float[] b, float t) {
		return new float[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t};
	}

	// ---- moving between the menu and a feature window (Locker, Friends...): the glass morphs, like panel <-> full menu
	private Screen pending;
	private boolean handoffDone;
	private float[] handoffFrom;
	private float[] handoffTo;
	private float handoffT;
	private float[] morphFromGui;
	private float[] morphFrom;
	private float morphT = 1f;

	/** A window closing back into this menu: the glass grows from the window's rect {x, y, w, h} (gui pixels). */
	public void morphFrom(int[] guiRect) {
		morphFromGui = new float[] {guiRect[0], guiRect[1], guiRect[2], guiRect[3]};
		morphT = 0f;
		pending = null;
		handoffDone = false;
	}

	/** How much of the menu's content shows while the glass morphs to / from a window. */
	private float contentShown() {
		if (pending != null) {
			return clamp01(1f - handoffT * 2.2f);
		}
		return morphFrom != null && morphT < 1f ? clamp01((morphT - 0.35f) / 0.65f) : 1f;
	}

	private float[] baseRect() {
		float e = easeInOut(expandAnim);
		float[] p = panelRect();
		float[] f = fullRect();
		return new float[] {p[0] + (f[0] - p[0]) * e, p[1] + (f[1] - p[1]) * e, p[2] + (f[2] - p[2]) * e, p[3] + (f[3] - p[3]) * e};
	}

	private void setExpanded(boolean full) {
		if (expanded == full) {
			return;
		}
		expanded = full;
		open = true;
		dropdown = null;
		if (full) {
			net.veloclient.velo.client.sound.VeloUiSounds.expand();
		} else {
			net.veloclient.velo.client.sound.VeloUiSounds.collapse();
		}
		profileMenu = false;
		moreMenu = false;
		DrawerPrefs.rememberFull(full);
	}

	private static float approach(float cur, float target, float step) {
		return cur < target ? Math.min(target, cur + step) : Math.max(target, cur - step);
	}

	private static float clamp01(float t) {
		return Math.max(0f, Math.min(1f, t));
	}

	private static float easeInOut(float t) {
		t = clamp01(t);
		return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
	}

	private boolean autoHidden() {
		return open && (dragging != null || resizing != null) && dragMoved && mouseU > W() - M - DW - 40f;
	}

	/** Ease-out quart: starts moving right away, settles softly. */
	private static float ease(float t) {
		t = clamp01(t);
		return 1f - (float) Math.pow(1f - t, 4);
	}

	private Page page() {
		return stack.get(stack.size() - 1);
	}

	private void go(Page next) {
		net.veloclient.velo.client.sound.VeloUiSounds.page();
		fromPage = page();
		stack.add(next);
		trans = 0f;
		transDir = 1;
		dropdown = null;
		listening = null;
		listeningChord = null;
		editing = null;
	}

	private void back() {
		if (stack.size() <= 1) {
			return;
		}
		net.veloclient.velo.client.sound.VeloUiSounds.back();
		fromPage = stack.remove(stack.size() - 1);
		trans = 0f;
		transDir = -1;
		dropdown = null;
		listening = null;
		listeningChord = null;
		editing = null;
	}

	private Cat catOf(Module module) {
		for (Cat cat : cats) {
			if (cat.categories().contains(module.category())) {
				return cat;
			}
		}
		return null;
	}

	private float animTo(Object key, float target, float speed) {
		float v = VeloAnim.step(anim.getOrDefault(key, target), target, dt * speed);
		anim.put(key, v);
		return v;
	}

	// ---------------------------------------------------------------- drawing helpers (units)

	/** Text is drawn a quarter bigger than the units around it (closer to the mockup), centred where 8-unit text would sit. */
	private static final float TS = 1.25f;

	private void text(DrawContext c, Text t, float x, float y, int color, float a) {
		float dev = devScale();
		ModuleScenes.push(c);
		// on the screen-pixel grid, at a scale whose glyph atlas maps 1:1 (crisp, not resampled)
		ModuleScenes.move(c, Math.round(x * dev) / dev, Math.round((y - (textScale - 1f) * 4f) * dev) / dev);
		ModuleScenes.scale(c, textScale, textScale);
		MenuKit.text(c, this.textRenderer, t, 0, 0, VeloUi.withAlpha(color, Math.round(((color >>> 24) & 0xFF) * a)));
		ModuleScenes.pop(c);
	}

	private Text fnt(String s, String base) {
		return net.veloclient.velo.client.gui.VeloFonts.fontsBroken() ? Text.literal(s) : MenuKit.inFont(s, base + fontN);
	}

	private Text R(String s) {
		return fnt(s, "dr_r_");
	}

	private Text B(String s) {
		return fnt(s, "dr_b_");
	}

	private Text Mo(String s) {
		return fnt(s, "dr_m_");
	}

	private int tw(Text t) {
		return Math.round(MenuKit.width(this.textRenderer, t) * textScale);
	}

	private Text fit(String s, boolean bold, float maxW) {
		String key = s + '\u0001' + bold + '\u0001' + Math.round(maxW) + '\u0001' + fontN;
		Text cached = fitCache.get(key);
		if (cached != null) {
			return cached;
		}
		Text t = bold ? B(s) : R(s);
		if (tw(t) > maxW) {
			// binary search for the longest piece that fits with the ellipsis
			int lo = 1;
			int hi = s.length();
			while (lo < hi) {
				int mid = (lo + hi + 1) / 2;
				String cut = s.substring(0, mid) + "\u2026";
				if (tw(bold ? B(cut) : R(cut)) <= maxW) {
					lo = mid;
				} else {
					hi = mid - 1;
				}
			}
			String cut = s.substring(0, Math.max(1, lo)) + "\u2026";
			t = bold ? B(cut) : R(cut);
		}
		if (fitCache.size() > 4000) {
			fitCache.clear();
		}
		fitCache.put(key, t);
		return t;
	}

	/** Word-wraps {@code s} into at most {@code maxLines} lines (the last one cut with an ellipsis). */
	private List<Text> wrap(String s, float maxW, int maxLines) {
		List<Text> out = new ArrayList<>();
		if (s == null || s.isBlank()) {
			return out;
		}
		String[] words = s.split(" ");
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			String next = line.isEmpty() ? words[i] : line + " " + words[i];
			if (line.isEmpty() || tw(R(next)) <= maxW) {
				line.setLength(0);
				line.append(next);
				continue;
			}
			if (out.size() == maxLines - 1) {
				out.add(fit(line + " " + String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)), false, maxW));
				return out;
			}
			out.add(R(line.toString()));
			line.setLength(0);
			line.append(words[i]);
		}
		out.add(fit(line.toString(), false, maxW));
		return out;
	}

	/** The accent from Settings > Theme (white by default) and the text colour that reads on it. */
	private static int acc() {
		return net.veloclient.velo.client.gui.glass.GlassPrefs.accent();
	}

	/** Icons in the accent (a coloured accent shows on every icon; with white they stay white). */
	private static int accIcon() {
		int c = acc();
		return VeloAnim.lerpArgb(c, WHITE, 0.15f);
	}

	private static int onAcc() {
		return net.veloclient.velo.client.gui.glass.GlassPrefs.onAccent();
	}

	private static int alpha(int color, float a) {
		return VeloUi.withAlpha(color, Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a))));
	}

	private void rect(DrawContext c, float x, float y, float w, float h, float r, int color, float a) {
		VeloDraw.fillRounded(c, x, y, w, h, r, alpha(color, a));
	}

	private void icon(DrawContext c, String key, float x, float y, float size, int color, float a) {
		img(c, VeloNavIcons.of(key), x, y, size, color, a);
	}

	/** A square icon drawn at exactly its on-screen pixel size (see {@link CrispIcons}). */
	private void img(DrawContext c, Identifier src, float x, float y, float size, int color, float a) {
		float dev = devScale();
		int px = Math.max(4, Math.round(size * dev));
		Identifier tex = CrispIcons.get(src, px);
		ModuleScenes.push(c);
		if (tex == null) {
			ModuleScenes.move(c, x, y);
			ModuleScenes.scale(c, size / 16f, size / 16f);
			c.drawTexture(RenderPipelines.GUI_TEXTURED, src, 0, 0, 0f, 0f, 16, 16, 16, 16, 16, 16, alpha(color, a));
		} else {
			ModuleScenes.move(c, Math.round(x * dev) / dev, Math.round(y * dev) / dev);
			ModuleScenes.scale(c, 1f / dev, 1f / dev);
			c.drawTexture(RenderPipelines.GUI_TEXTURED, tex, 0, 0, 0f, 0f, px, px, px, px, px, px, alpha(color, a));
		}
		ModuleScenes.pop(c);
	}

	private void moduleIcon(DrawContext c, Module m, float x, float y, float size, int color, float a) {
		img(c, ModuleIcons.textureFor(m.id()), x, y, size, color, a);
	}

	/** A toggle switch: white track + dark knob when on, glass track + white knob when off; the knob slides. */
	private void toggle(DrawContext c, Object key, float x, float y, boolean on, float a, boolean big) {
		float t = animTo(key, on ? 1f : 0f, 9f);
		float w = big ? 30f : 26f;
		float h = big ? 17f : 15f;
		int track = VeloAnim.lerpArgb(0x33FFFFFF, acc(), t);
		rect(c, x, y, w, h, h / 2f, track, a);
		float kn = h - 4f;
		float kx = x + 2f + (w - h) * t;
		VeloDraw.fillCircle(c, kx + kn / 2f, y + 2f + kn / 2f + 0.5f, kn / 2f, alpha(0x40000000, a));
		VeloDraw.fillCircle(c, kx + kn / 2f, y + 2f + kn / 2f, kn / 2f, alpha(VeloAnim.lerpArgb(WHITE, onAcc(), t), a));
	}

	/** Pushes the scissor for a units rect (version-safe: set with an identity pose, in gui pixels). */
	private void clip(DrawContext c, float x, float y, float w, float h) {
		ModuleScenes.pop(c);
		c.enableScissor(Math.round(x * k), Math.round(y * k), Math.round((x + w) * k), Math.round((y + h) * k));
		ModuleScenes.push(c);
		ModuleScenes.scale(c, k, k);
	}

	private void unclip(DrawContext c) {
		ModuleScenes.pop(c);
		c.disableScissor();
		ModuleScenes.push(c);
		ModuleScenes.scale(c, k, k);
	}

	// ---------------------------------------------------------------- render

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		// 2026-11-26 (owner: "mit R-Shift droppen FPS um 1000"): the menu is drawn at most twice the monitor's refresh rate
		// and replayed in between (GuiFrameCache); any input redraws it right away
		if (blurFrame) {
			// a real blur frame draws the sharp copy around the glass - never replayed in the frames after it
			frameCache.invalidate();
			renderLive(context, mouseX, mouseY, delta);
			return;
		}
		if (frameCache.replay(context, this.width, this.height)) {
			return;
		}
		frameCache.begin(context, this.width, this.height);
		boolean completed = false;
		try {
			renderLive(context, mouseX, mouseY, delta);
			completed = true;
		} finally {
			frameCache.end(context, completed);
		}
	}

	private final net.veloclient.velo.client.gui.glass.GuiFrameCache frameCache = new net.veloclient.velo.client.gui.glass.GuiFrameCache();

	private void renderLive(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		dt = lastNanos < 0 ? 0f : Math.min(1f / 30f, (now - lastNanos) / 1_000_000_000f);   // a hitch never jumps the animation
		lastNanos = now;
		lastDelta = delta;
		k = Math.max(0.2f, this.height / REF_H);
		mouseU = mouseX / k;
		mouseV = mouseY / k;
		float dev = devScale();
		fontN = Math.max(2, Math.min(16, Math.round(2f * dev * TS)));
		textScale = fontN / 2f / dev;
		openAnim = approach(openAnim, open && !autoHidden() ? 1f : 0f, dt / OPEN_TIME);
		handleAnim = approach(handleAnim, (!open || autoHidden()) && !expanded && !closing ? 1f : 0f, dt / HANDLE_TIME);
		expandAnim = approach(expandAnim, expanded ? 1f : 0f, dt / EXPAND_TIME);
		trans = Math.min(1f, trans + dt / PAGE_TIME);
		if (morphFromGui != null) {
			morphFrom = new float[] {morphFromGui[0] / k, morphFromGui[1] / k, morphFromGui[2] / k, morphFromGui[3] / k};
			morphFromGui = null;
		}
		morphT = approach(morphT, 1f, dt / 0.32f);
		if (pending != null) {
			handoffT = approach(handoffT, 1f, dt / 0.26f);
			if (handoffT >= 1f && !handoffDone) {
				handoffDone = true;
				Screen next = pending;
				this.client.execute(() -> {
					net.veloclient.velo.client.gui.window.VeloWindow.markHandoff();
					this.client.setScreen(next);
				});
			}
		}
		float glide = 1f - (float) Math.exp(-dt * 14f);
		for (Map.Entry<Page, Float> e : scrollTarget.entrySet()) {
			float cur = scroll.getOrDefault(e.getKey(), 0f);
			float target = clampScroll(e.getKey(), e.getValue());
			scroll.put(e.getKey(), Math.abs(target - cur) < 0.05f ? target : cur + (target - cur) * glide);
		}
		//? if <1.21.2 {
		/*if (blurOn()) {
			this.applyBlur(delta);
		}
		*///?}

		if (blurOn()) {
			float[] gr = rectNow();
			float gRad = net.veloclient.velo.client.gui.glass.GlassPrefs.radius(18f + 6f * easeInOut(expandAnim));
			int gx = Math.round(gr[0] * k);
			int gy = Math.round(gr[1] * k);
			int gw = Math.round(gr[2] * k);
			int gh = Math.round(gr[3] * k);
			if (blurFrame) {
				// a real blur this frame: the sharp copy around the glass (the game stays clear), the blurred one kept for later
				if (net.veloclient.velo.client.gui.glass.SharpFrame.prepareBoth()) {
					net.veloclient.velo.client.gui.glass.SharpFrame.drawOutside(context, this.width, this.height, gx, gy, gw, gh, Math.round(gRad * k));
				}
			} else {
				// in between: the live game everywhere, the last blurred picture only behind the glass
				net.veloclient.velo.client.gui.glass.SharpFrame.drawBlurredInside(context, this.width, this.height, gx, gy, gw, gh, Math.round(gRad * k));
			}
		}
		if (expandAnim < 0.5f) {
			renderHud(context, mouseX, mouseY, delta);   // the full menu covers the HUD; Edit HUD brings it back
		}

		hits.clear();
		overlayHits.clear();
		scrollbars.clear();
		float[] heldTrack = sliding != null ? sliderTrack.get(sliding) : null;
		sliderTrack.clear();   // rebuilt every frame (only the slider being dragged stays)
		if (heldTrack != null) {
			sliderTrack.put(sliding, heldTrack);
		}
		ModuleScenes.push(context);
		ModuleScenes.scale(context, k, k);
		renderHint(context);
		renderHandle(context);
		if (openAnim > 0.01f) {
			renderDrawer(context);
		}
		renderGhost(context);
		renderTour(context);
		ModuleScenes.pop(context);
		super.render(context, mouseX, mouseY, delta);
		if (closing && !closeScheduled && (openAnim <= 0.001f || System.nanoTime() - closingSince > 900_000_000L)) {
			closeScheduled = true;
			this.client.execute(() -> {
				if (net.veloclient.velo.client.util.ClientCompat.currentScreen() == this) {
					this.client.setScreen(null);
				}
			});
		}
	}

	/** Screen pixels per drawer unit. */
	private float devScale() {
		return k * net.veloclient.velo.client.util.ClientCompat.guiScale();
	}

	// ---- HUD elements, editable (gui pixels)

	private List<HudModule> hudModules() {
		List<HudModule> out = new ArrayList<>();
		for (Module module : ModuleRegistry.all()) {
			if (module instanceof HudModule hud && hud.isEnabled() && hud.width() > 0 && hud.height() > 0) {
				out.add(hud);
			}
		}
		return out;
	}

	private static int sw(HudModule hud) {
		return Math.round(hud.width() * hud.position().scale());
	}

	private static int sh(HudModule hud) {
		return Math.round(hud.height() * hud.position().scale());
	}

	private void renderHud(DrawContext context, int mouseX, int mouseY, float delta) {
		boolean elementsDrawn = hudBehind;
		hudBehind = false;
		if (!elementsDrawn) {
			net.veloclient.velo.client.hud.HudAutoLayout.update(this.width, this.height);   // (already done under the blur otherwise)
		}
		if (dragging != null && dragMoved) {
			context.fill(this.width / 2, 0, this.width / 2 + 1, this.height, 0x50FFFFFF);
			context.fill(0, this.height / 2, this.width, this.height / 2 + 1, 0x50FFFFFF);
		}
		HudModule labelled = null;
		int[] box = null;
		for (HudModule hud : hudModules()) {
			int w = sw(hud);
			int h = sh(hud);
			int x = hud.position().resolveX(this.width, w);
			int y = hud.position().resolveY(this.height, h);
			if (!elementsDrawn) {
				drawElement(context, hud, delta);
			}
			boolean active = hud == dragging || hud == resizing || hud == selected;
			boolean hover = !overDrawer(mouseX / k, mouseY / k) && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
			float t = animTo("hud:" + hud.id(), active ? 1f : hover ? 0.6f : 0f, 9f);   // own key: a HUD module's toggle animates too
			if (t > 0.02f) {
				VeloDraw.strokeRounded(context, x - 3, y - 3, w + 6, h + 6, 4, VeloUi.withAlpha(WHITE, Math.round(255 * t)));
			}
			if (active || hover) {
				labelled = hud;
				box = new int[] {x, y, w, h};
			}
			if (hud == selected || hud == resizing) {
				VeloDraw.fillRounded(context, x + w - 1, y + h - 1, 6, 6, 1.5f, WHITE);
			}
		}
		sampled = labelled;
		if (labelled != null) {
			Text name = MenuKit.uiBold(labelled.displayName());
			Text size = MenuKit.mono(Math.round(labelled.position().scale() * 100) + "%");
			int cw = MenuKit.width(this.textRenderer, name) + MenuKit.width(this.textRenderer, size) + 20;
			int cx = Math.max(2, Math.min(this.width - cw - 2, box[0] - 3));
			int cy = box[1] - 18 < 4 ? box[1] + box[3] + 6 : box[1] - 18;
			VeloDraw.fillRounded(context, cx, cy, cw, 13, 6.5f, WHITE);
			MenuKit.text(context, this.textRenderer, name, cx + 6, cy + 3, 0xFF141416);
			MenuKit.text(context, this.textRenderer, size, cx + 12 + MenuKit.width(this.textRenderer, name), cy + 4, 0xA0141416);
		}
	}

	private boolean overDrawer(double u, double v) {
		if (openAnim <= 0.5f) {
			return false;
		}
		if (expanded) {
			return true;   // the full menu owns the whole screen
		}
		float[] r = rectNow();
		return u >= r[0] && u <= r[0] + r[2] && v >= r[1] && v <= r[1] + r[3];
	}

	// ---- the glass drawer (units)

	private void renderHandle(DrawContext c) {
		float a = ease(handleAnim);
		if (a < 0.02f) {
			return;
		}
		// a glass tab on the right edge: red Velo dot over a chevron
		float w = 26f;
		float h = 84f;
		float x = W() - w + (1f - a) * 30f;
		float y = (H() - h) / 2f;
		boolean hover = mouseU >= x && mouseV >= y && mouseV <= y + h;
		VeloDraw.shadow(c, Math.round(x), Math.round(y), Math.round(w + 14), Math.round(h), 12, 12, 3, alpha(0x60000000, a));
		rect(c, x, y, w + 14f, h, 12f, hover ? 0xE8262629 : GLASS, a);
		VeloDraw.strokeRounded(c, x, y, w + 14f, h, 12f, 1f, alpha(GLASS_EDGE, a));
		img(c, LOGO, x + w / 2f - 8f, y + 14f, 16f, WHITE, a);
		icon(c, "d_chev_l", x + w / 2f - 7f, y + h / 2f + 2f, 14f, WHITE, a);
		hits.add(new Hit(x, y, w, h, () -> {
			open = true;
			net.veloclient.velo.client.sound.VeloUiSounds.expand();
		}));
	}

	/** A small glass hint above the game while the panel is out: what you can do with the HUD right here. */
	private void renderHint(DrawContext c) {
		if (PROMO_MATTE) {
			return;
		}
		boolean show = open && !closing && !expanded && dragging == null && resizing == null && ghost == null && !overlayOpen() && tourStep < 0;
		float a = animTo("hint", show && openAnim > 0.6f ? 1f : 0f, 6f) * (1f - easeInOut(expandAnim));
		if (a < 0.02f) {
			return;
		}
		float free = rectNow()[0];
		Text t = fit("Drag elements to move  \u00b7  pull a corner to resize  \u00b7  click one for its settings", false, free - 70f);
		float w = tw(t) + 38f;
		float x = (free - w) / 2f;
		float y = (this.height - 66) / k - 26f + (1f - a) * 8f;   // above the hotbar and hearts
		// one edge only: the drop shadow under it read as a second, older outline (VELO-187); a darker fill, so a bright block
		// of the world behind it can't show through as a wedge at one end
		rect(c, x, y, w, 22f, 11f, 0xD0141418, a);
		VeloDraw.strokeRounded(c, x, y, w, 22f, 11f, 1f, alpha(GLASS_EDGE, a));
		icon(c, "d_layout", x + 10f, y + 5f, 12f, TEXT_2, a);
		text(c, t, x + 28f, y + 7f, TEXT_2, a);
	}

	/** Glass: tint and a light edge (brighter along the top, like light catching it). */
	private void glass(DrawContext c, float x, float y, float w, float h, float r, int tint, float a) {
		// no drop shadow: under translucent glass it showed through as a dark band
		rect(c, x, y, w, h, r, tint, a);
		VeloDraw.strokeRounded(c, x, y, w, h, r, 1f, alpha(0x24FFFFFF, a));
		c.fill(Math.round(x + r), Math.round(y), Math.round(x + w - r), Math.round(y) + 1, alpha(0x30FFFFFF, a));
	}

	private void renderDrawer(DrawContext c) {
		float a = Math.min(1f, openAnim * 1.6f);
		float e = easeInOut(expandAnim);
		float[] r = rectNow();
		float x = r[0];
		float y = r[1];
		float w = r[2];
		float h = r[3];
		if (e > 0.01f) {
			// a light veil over the blurred game - not a black overlay
			c.fill(0, 0, (int) Math.ceil(W()) + 1, (int) Math.ceil(H()) + 1, alpha(0x1C000000, e * a));
		}
		float rad = net.veloclient.velo.client.gui.glass.GlassPrefs.radius(18f + 6f * e);
		glass(c, x, y, w, h, rad, net.veloclient.velo.client.gui.glass.GlassPrefs.tint(VeloAnim.lerpArgb(GLASS, GLASS_FULL, e)), a);
		glassHit = new Hit(x, y, w, h, () -> {
		});
		hits.add(glassHit);   // eats clicks on the glass itself

		float shown = contentShown();
		float pa = a * shown * clamp01(1f - e * 2.2f);
		float fa = a * shown * clamp01((e - 0.45f) * 2.2f);
		// the live layout is drawn last, so its overlay anchors win; the other one adds no hits
		if (expanded) {
			if (pa > 0.01f) {
				layout(c, false, x, y, w, h, pa);
			}
			if (fa > 0.01f) {
				layout(c, true, x, y, w, h, fa);
			}
		} else {
			if (fa > 0.01f) {
				layout(c, true, x, y, w, h, fa);
			}
			if (pa > 0.01f) {
				layout(c, false, x, y, w, h, pa);
			}
		}
		// dropdowns slide and fade in, and out again (the last one is kept for its fade-out)
		if (dropdown != null) {
			lastDropdown = dropdown;
			lastDropdownAt = dropdownAt;
		}
		float dd = animTo("dropdownAnim", dropdown != null ? 1f : 0f, 16f);
		if (dd > 0.02f && lastDropdown != null && lastDropdownAt != null) {
			drawingOverlay = true;
			try {
				renderDropdown(c, a * dd, dd);
			} finally {
				drawingOverlay = false;
			}
		}
		if (profileMenu) {
			drawingOverlay = true;
			try {
				renderProfileMenu(c, a);
			} finally {
				drawingOverlay = false;
			}
		}
	}

	private void layout(DrawContext c, boolean full, float x, float y, float w, float h, float a) {
		boolean live = full == expanded;
		int n = hits.size();
		clip(c, x, y, w, h);
		if (full) {
			float[] f = fullRect();
			renderFull(c, x + (w - f[2]) / 2f, y + (h - f[3]) / 2f, f[2], f[3], a, live);
		} else {
			renderPanel(c, x, y, h, a, live);
		}
		unclip(c);
		if (!live) {
			hits.subList(n, hits.size()).clear();
		}
	}

	private void renderPanel(DrawContext c, float x, float y, float h, float a, boolean live) {
		// header: red dot, Velo, profile dropdown, grow into the full menu, slide away
		float hy = y + 14f;
		img(c, LOGO, x + PAD, hy - 4f, 20f, WHITE, a);
		float bx = x + DW - PAD - 22f;
		roundButton(c, "d_collapse", bx, hy - 5f, 22f, a, () -> {
			open = false;
			net.veloclient.velo.client.sound.VeloUiSounds.collapse();
			profileMenu = false;
			moreMenu = false;
		});
		float ex = bx - 26f;
		roundButton(c, "d_expand", ex, hy - 5f, 22f, a, () -> setExpanded(true));
		float px = renderProfileButton(c, ex - 6f, hy - 5f, a);
		if (live) {
			tourAnchor.put("collapse", new float[] {bx, hy - 5f, 22f, 22f});
			tourAnchor.put("expand", new float[] {ex, hy - 5f, 22f, 22f});
			tourAnchor.put("profile", new float[] {px, hy - 5f, ex - 6f - px, 22f});
			tourAnchor.put("panel", new float[] {x, y, DW, h});
		}

		// pages
		float cy = y + HEADER;
		float ch = h - HEADER - 8f;   // no dock in the panel: the features are on Home
		if (live) {
			bodyTop = cy;
			bodyBottom = cy + ch;
		}
		clip(c, x + 1f, cy, DW - 2f, ch);
		pages(c, x, DW, cy, ch, a, live, false);
		unclip(c);

	}

	/** The current page sliding in over the one before (a short slide and a crossfade). */
	private void pages(DrawContext c, float x, float pw, float top, float height, float a, boolean live, boolean full) {
		float e = ease(trans);
		if (trans < 1f && fromPage != null) {
			float dir = transDir > 0 ? 1f : -1f;
			renderAnyPage(c, fromPage, x - dir * e * 40f, pw, top, height, a * (1f - e), false, full);
			renderAnyPage(c, page(), x + dir * (1f - e) * 40f, pw, top, height, a * e, live, full);
		} else {
			renderAnyPage(c, page(), x, pw, top, height, a, live, full);
		}
	}

	private void renderAnyPage(DrawContext c, Page p, float x, float pw, float top, float height, float a, boolean live, boolean full) {
		if (!full) {
			renderPage(c, p, x, pw, top, height, a, live);
		} else if (p.kind() == Kind.MODULE) {
			net.veloclient.velo.client.gui.window.ModulePreview.Kind kind = net.veloclient.velo.client.gui.window.ModulePreview.kind(p.module());
			boolean preview = kind == net.veloclient.velo.client.gui.window.ModulePreview.Kind.HUD
					|| kind == net.veloclient.velo.client.gui.window.ModulePreview.Kind.CROSSHAIR
					|| kind == net.veloclient.velo.client.gui.window.ModulePreview.Kind.SCENE;
			if (preview && pw >= 600f) {
				// settings on the left, a live preview of what you're changing on the right
				float gap = 24f;
				float pvW = Math.min(360f, pw * 0.4f);
				float cw = Math.min(500f, pw - 48f - pvW - gap);
				float cx = x + (pw - (cw + gap + pvW)) / 2f;
				renderPage(c, p, cx, cw, top, height, a, live);
				drawPreview(c, p.module(), kind, cx + cw + gap, top + 8f, pvW, Math.min(height - 16f, pvW * 0.72f + 14f), a);
			} else {
				float cw = Math.min(560f, pw - 48f);
				renderPage(c, p, x + (pw - cw) / 2f, cw, top, height, a, live);
			}
		} else {
			renderGrid(c, p, x, pw, top, height, a, live);
		}
	}

	/** The module's live preview (the same scenes the old settings window had), drawn in gui pixels. */
	private void drawPreview(DrawContext c, Module m, net.veloclient.velo.client.gui.window.ModulePreview.Kind kind, float x, float y, float w, float h, float a) {
		if (a < 0.4f) {
			return;
		}
		text(c, Mo("PREVIEW"), x + 2f, y, TEXT_3, a);
		y += 14f;
		h -= 14f;
		ModuleScenes.pop(c);
		int bx = Math.round(x * k);
		int by = Math.round(y * k);
		int bw = Math.round(w * k);
		int bh = Math.round(h * k);
		try {
			switch (kind) {
				case HUD -> {
					net.veloclient.velo.client.gui.window.ModulePreview.backdrop(c, bx, by, bw, bh);
					net.veloclient.velo.client.gui.window.ModulePreview.drawHud(c, (HudModule) m, bx, by, bw, bh, lastDelta);
				}
				case CROSSHAIR -> {
					net.veloclient.velo.client.gui.window.ModulePreview.backdrop(c, bx, by, bw, bh);
					net.veloclient.velo.client.gui.window.ModulePreview.drawCrosshair(c, bx, by, bw, bh);
				}
				default -> {
					ModuleScenes.drawUnder(c, m, bx, by, bw, bh);
					ModuleScenes.drawOver(c, m, bx, by, bw, bh);
				}
			}
		} catch (RuntimeException ignored) {
			// a preview never breaks the menu
		}
		VeloDraw.strokeRounded(c, bx, by, bw, bh, 8, 0x24FFFFFF);
		ModuleScenes.push(c);
		ModuleScenes.scale(c, k, k);
	}

	private void roundButton(DrawContext c, String iconKey, float x, float y, float size, float a, Runnable click) {
		boolean hover = inside(x, y, size, size);
		rect(c, x, y, size, size, size / 2f, hover ? 0x2EFFFFFF : 0x1AFFFFFF, a);
		float is = size * 0.55f;
		icon(c, iconKey, x + (size - is) / 2f, y + (size - is) / 2f, is, WHITE, a);
		hits.add(new Hit(x, y, size, size, click));
	}

	/** The search field. It's always the focused field (typing anywhere goes here), so it always shows the caret. */
	private void renderSearch(DrawContext c, float x, float y, float w, float h, float a, boolean live, float top, float height) {
		boolean focused = editing == null && listening == null && listeningChord == null;
		if (inside(x, y, w, h)) {
			net.veloclient.velo.client.util.IBeamCursor.ibeam(c);
		}
		rect(c, x, y, w, h, h / 2f, focused ? 0x22FFFFFF : 0x16FFFFFF, a);
		if (focused) {
			VeloDraw.strokeRounded(c, x, y, w, h, h / 2f, 1f, alpha(0x52FFFFFF, a));
		}
		float iy = y + (h - 12f) / 2f;
		icon(c, "d_search", x + 10f, iy, 12f, focused ? WHITE : TEXT_2, a);
		String q = search.toString();
		float tx = x + 29f;
		float ty = y + (h - 8f) / 2f;
		float caretX = tx;
		if (w < 90f) {
			// squeezed to an icon: typing still searches
		} else if (q.isEmpty()) {
			text(c, R("Search"), tx + 2f, ty, TEXT_3, a);
		} else {
			Text qt = R(q);
			while (tw(qt) > w - 58f && q.length() > 1) {
				q = q.substring(1);
				qt = R(q);
			}
			text(c, qt, tx, ty, WHITE, a);
			caretX = tx + tw(qt) + 1f;
			hit(live, top, height, x + w - 24f, y + (h - 20f) / 2f, 20f, 20f, () -> search.setLength(0));
			icon(c, "d_x", x + w - 19f, y + (h - 10f) / 2f, 10f, TEXT_2, a);
		}
		if (focused && w >= 90f && (System.currentTimeMillis() / 530) % 2 == 0) {
			rect(c, caretX, y + (h - 12f) / 2f, 1f, 12f, 0f, WHITE, a);
		}
	}

	/** Drawing a dropdown or the profile menu itself: only these may react to the mouse while one is open. */
	private boolean drawingOverlay;

	private boolean inside(float x, float y, float w, float h) {
		// with a dropdown open, what is behind it must not light up under the cursor (VELO-187)
		if (!drawingOverlay && overlayOpen()) {
			return false;
		}
		return mouseU >= x && mouseU < x + w && mouseV >= y && mouseV < y + h;
	}

	// ---- the full menu (the panel grown big): header with category pills, search and profile; card grid; Edit HUD + dock

	private void renderFull(DrawContext c, float x, float y, float w, float h, float a, boolean live) {
		float rowTop = y + 18f;
		img(c, LOGO, x + 22f, rowTop, 26f, WHITE, a);
		float sb = x + w - 22f - 26f;
		roundButton(c, "d_shrink", sb, rowTop, 26f, a, () -> setExpanded(false));
		float profX = renderProfileButton(c, sb - 8f, rowTop + 2f, a);
		float px = x + 60f;
		// one line, always: pills get tighter and the search narrower until everything fits
		float pad = 24f;
		float searchW = 210f;
		while (px + pillsWidth(pad) > profX - 8f - searchW - 12f) {
			if (searchW > 130f) {
				searchW -= 10f;
			} else if (pad > 12f) {
				pad -= 2f;
			} else if (searchW > 30f) {
				searchW = Math.max(30f, searchW - 10f);
			} else {
				break;
			}
		}
		renderSearch(c, profX - 8f - searchW, rowTop, searchW, 26f, a, live, -1e4f, 2e4f);
		renderPills(c, px, rowTop, pad, a);

		float top = rowTop + 26f + 18f;
		float bottom = y + h - 64f;
		if (live) {
			bodyTop = top;
			bodyBottom = bottom;
		}
		clip(c, x + 1f, top - 6f, w - 2f, bottom - top + 6f);
		pages(c, x, w, top, bottom - top, a, live, true);
		unclip(c);

		// footer: Edit HUD (back to the panel) and the quick dock
		float fy = y + h - 50f;
		Text eh = B("Edit HUD");
		float ew = tw(eh) + 46f;
		boolean eHover = inside(x + 22f, fy, ew, 32f);
		rect(c, x + 22f, fy, ew, 32f, 16f, eHover ? VeloAnim.lerpArgb(acc(), 0xFF000000, 0.08f) : acc(), a);
		icon(c, "d_layout", x + 36f, fy + 9f, 14f, onAcc(), a);
		text(c, eh, x + 56f, fy + 12f, onAcc(), a);
		// Edit HUD from the full menu: the whole screen for moving things - the side panel slides away too (its tab on the
		// edge brings it back; owner 2026-11-25)
		hits.add(new Hit(x + 22f, fy, ew, 32f, () -> {
			setExpanded(false);
			open = false;
			profileMenu = false;
			moreMenu = false;
		}));

		int n = DOCK_ITEMS.length;
		float size = 32f;
		float gap = 8f;
		float dx = x + w - 22f - n * size - (n - 1) * gap;
		String hoverLabel = null;
		float hoverX = 0;
		for (int i = 0; i < n; i++) {
			float bx = dx + i * (size + gap);
			boolean hover = inside(bx, fy, size, size);
			rect(c, bx, fy, size, size, size / 2f, hover ? 0x2EFFFFFF : 0x16FFFFFF, a);
			icon(c, DOCK_ITEMS[i][1], bx + 8.5f, fy + 8.5f, 15f, WHITE, a);
			if (hover) {
				hoverLabel = DOCK_ITEMS[i][2];
				hoverX = bx + size / 2f;
			}
			String id = DOCK_ITEMS[i][0];
			hits.add(new Hit(bx, fy, size, size, () -> openEntry(id)));
		}
		if (hoverLabel != null) {
			Text t = B(hoverLabel);
			float tw = tw(t) + 14f;
			float tx = Math.min(x + w - tw - 8f, hoverX - tw / 2f);
			rect(c, tx, fy - 22f, tw, 16f, 8f, WHITE, a);
			text(c, t, tx + 7f, fy - 18f, 0xFF141416, a);
		}
	}

	/** All + the categories; the white pill glides to the active one. */
	private List<String> pillLabels() {
		List<String> labels = new ArrayList<>();
		labels.add("All");
		for (Cat cat : cats) {
			labels.add(cat.name());
		}
		labels.add("Features");
		return labels;
	}

	private float pillsWidth(float pad) {
		float total = 8f;
		for (String l : pillLabels()) {
			total += tw(B(l)) + pad;
		}
		return total;
	}

	/** The tab a page belongs to: 0 All, then the categories, then Features. */
	private int tabOf(Page p) {
		if (p.kind() == Kind.FEATURES) {
			return cats.size() + 1;
		}
		return p.cat() == null ? 0 : cats.indexOf(p.cat()) + 1;
	}

	/** All + the categories + Features; the white pill glides to the active one. */
	private void renderPills(DrawContext c, float x, float y, float pad, float a) {
		List<String> labels = pillLabels();
		int active = search.isEmpty() ? tabOf(page()) : 0;
		float total = 8f;
		float[] xs = new float[labels.size()];
		float[] ws = new float[labels.size()];
		for (int i = 0; i < labels.size(); i++) {
			ws[i] = tw(B(labels.get(i))) + pad;
			xs[i] = x + total - 4f;
			total += ws[i];
		}
		rect(c, x, y, total, 26f, 13f, 0x47000000, a);
		float ax = animTo("pillX", xs[active], 14f);
		float aw = animTo("pillW", ws[active], 14f);
		rect(c, ax, y + 3f, aw, 20f, 10f, acc(), a);
		for (int i = 0; i < labels.size(); i++) {
			Text t = B(labels.get(i));
			boolean on = i == active;
			boolean hover = !on && inside(xs[i], y, ws[i], 26f);
			text(c, t, xs[i] + pad / 2f, y + 9f, on ? onAcc() : hover ? WHITE : TEXT_2, a);
			int tab = i;
			hits.add(new Hit(xs[i], y, ws[i], 26f, () -> showTab(tab)));
		}
	}

	private void showTab(int tab) {
		search.setLength(0);
		Page cur = page();
		int from = tabOf(cur);
		if (cur.kind() != Kind.MODULE && from == tab) {
			return;
		}
		fromPage = cur;
		stack.subList(1, stack.size()).clear();
		if (tab == cats.size() + 1) {
			stack.add(new Page(Kind.FEATURES, null, null));
		} else if (tab > 0) {
			stack.add(new Page(Kind.CATEGORY, cats.get(tab - 1), null));
		}
		trans = 0f;
		transDir = tab >= from ? 1 : -1;
		dropdown = null;
		setScroll(page(), 0f);
	}

	/** Module cards: icon tile, switch, name, a line about it. Searching shows the results; All shows every category. */
	private void renderGrid(DrawContext c, Page p, float x, float pw, float top, float height, float a, boolean live) {
		float s = Math.min(scroll.getOrDefault(p, 0f), scrollMax.getOrDefault(p, Float.MAX_VALUE));
		float gx = x + 22f;
		float gw = pw - 44f;
		int cols = gw >= 700f ? 4 : 3;
		float gap = 12f;
		float cw = (gw - gap * (cols - 1)) / cols;
		float chh = 84f;
		float y = top - s;
		float y0 = y;
		List<String> titles = new ArrayList<>();
		List<List<?>> groups = new ArrayList<>();
		if (!search.isEmpty()) {
			String lower = search.toString().toLowerCase(Locale.ROOT);
			List<Object> found = new ArrayList<>();
			for (Feature f : features()) {
				if (f.name().toLowerCase(Locale.ROOT).contains(lower) || f.blurb().toLowerCase(Locale.ROOT).contains(lower)) {
					found.add(f);
				}
			}
			for (Module m : ModuleRegistry.all()) {
				if (m.displayName().toLowerCase(Locale.ROOT).contains(lower)
						|| net.veloclient.velo.client.gui.widget.ModuleTags.tag(m).toLowerCase(Locale.ROOT).contains(lower)) {
					found.add(m);
				}
			}
			titles.add(found.isEmpty() ? "Nothing found" : "Results");
			groups.add(found);
		} else if (p.kind() == Kind.FEATURES) {
			titles.add(null);
			groups.add(features());
		} else if (p.kind() == Kind.CATEGORY) {
			titles.add(null);
			groups.add(p.cat().modules());
		} else {
			List<Module> pins = QuickToggles.favorites();
			if (!pins.isEmpty()) {
				titles.add("Pinned");
				groups.add(pins);
			}
			titles.add("Features");
			groups.add(features());
			for (Cat cat : cats) {
				titles.add(cat.name());
				groups.add(cat.modules());
			}
		}
		for (int g = 0; g < groups.size(); g++) {
			if (titles.get(g) != null) {
				Text tt = Mo(titles.get(g).toUpperCase(Locale.ROOT));
				text(c, tt, gx + 4f, y + 2f, TEXT_3, a);
				if (!groups.get(g).isEmpty()) {
					text(c, Mo(String.valueOf(groups.get(g).size())), gx + 12f + tw(tt), y + 2f, 0x4DFFFFFF, a);
				}
				y += 18f;
			}
			List<?> items = groups.get(g);
			for (int i = 0; i < items.size(); i++) {
				float cx = gx + (i % cols) * (cw + gap);
				float cy = y + (i / cols) * (chh + gap);
				if (cy + chh > top - 10f && cy < top + height + 10f) {
					if (items.get(i) instanceof Module m) {
						card(c, m, cx, cy, cw, chh, top, height, a, live);
					} else if (items.get(i) instanceof Feature f) {
						featureCard(c, f, cx, cy, cw, chh, top, height, a, live);
					}
				}
			}
			y += ((items.size() + cols - 1) / cols) * (chh + gap) + 12f;
		}
		scrollBar(c, p, x + pw - 8f, s, y - y0, top, height, a, live);
	}

	private void featureCard(DrawContext c, Feature f, float x, float y, float w, float h, float top, float height, float a, boolean live) {
		boolean hover = live && inside(x, y, w, h) && mouseV >= top && mouseV < top + height;
		float ht = animTo("feat:" + f.id(), hover ? 1f : 0f, 12f);
		rect(c, x, y, w, h, 18f, VeloAnim.lerpArgb(0x12FFFFFF, 0x1FFFFFFF, ht), a);
		VeloDraw.strokeRounded(c, x, y, w, h, 18f, 1f, alpha(0x17FFFFFF, a));
		rect(c, x + 14f, y + 14f, 32f, 32f, 11f, 0x1FFFFFFF, a);
		icon(c, f.icon(), x + 22f, y + 22f, 16f, accIcon(), a);
		icon(c, "d_chev_r", x + w - 28f, y + 22f, 14f, hover ? WHITE : TEXT_3, a);
		text(c, fit(f.name(), true, w - 28f), x + 14f, y + h - 32f, WHITE, a);
		text(c, fit(f.blurb(), false, w - 28f), x + 14f, y + h - 18f, TEXT_3, a);
		hit(live, top, height, x, y, w, h, () -> openEntry(f.id()));
	}

	/** Panel: the features as a grid of small tiles (icon over its name). */
	private float featureTiles(DrawContext c, float x, float y, float w, float top, float height, float a, boolean live) {
		List<Feature> list = features();
		int cols = 3;
		float gap = 6f;
		float tw = (w - gap * (cols - 1)) / cols;
		float th = 48f;
		for (int i = 0; i < list.size(); i++) {
			Feature f = list.get(i);
			float tx = x + (i % cols) * (tw + gap);
			float ty = y + (i / cols) * (th + gap);
			if (ty + th < top || ty > top + height) {
				continue;
			}
			boolean hover = live && inside(tx, ty, tw, th) && mouseV >= top && mouseV < top + height;
			rect(c, tx, ty, tw, th, 12f, hover ? 0x22FFFFFF : 0x10FFFFFF, a);
			icon(c, f.icon(), tx + (tw - 16f) / 2f, ty + 9f, 16f, accIcon(), a);
			Text name = fit(f.name(), false, tw - 6f);
			text(c, name, tx + (tw - tw(name)) / 2f, ty + 33f, TEXT_2, a);
			hit(live, top, height, tx, ty, tw, th, () -> openEntry(f.id()));
		}
		return y + ((list.size() + cols - 1) / cols) * (th + gap);
	}

	private void card(DrawContext c, Module m, float x, float y, float w, float h, float top, float height, float a, boolean live) {
		boolean on = m.isEnabled();
		boolean hover = live && inside(x, y, w, h) && mouseV >= top && mouseV < top + height;
		float ht = animTo("card:" + m.id(), hover ? 1f : 0f, 12f);
		int bg = VeloAnim.lerpArgb(on ? 0x1FFFFFFF : 0x12FFFFFF, on ? 0x2BFFFFFF : 0x1FFFFFFF, ht);
		rect(c, x, y, w, h, 18f, bg, a);
		VeloDraw.strokeRounded(c, x, y, w, h, 18f, 1f, alpha(on ? 0x33FFFFFF : 0x17FFFFFF, a));
		rect(c, x + 14f, y + 14f, 32f, 32f, 11f, on ? acc() : 0x1FFFFFFF, a);
		moduleIcon(c, m, x + 22f, y + 22f, 16f, on ? onAcc() : accIcon(), a);
		float sx = x + w - 14f - 30f;
		toggle(c, "cardsw:" + m.id(), sx, y + 14f, on, a, true);
		boolean pinned = QuickToggles.isFavorite(m.id());
		float px = sx - 24f;
		if (pinned || hover) {
			boolean ph = live && inside(px, y + 10f, 22f, 24f);
			icon(c, pinned ? "d_pin_on" : "d_pin", px + 4f, y + 15f, 13f, pinned ? (ph ? TEXT_2 : WHITE) : ph ? WHITE : TEXT_3, a);
		}
		text(c, fit(m.displayName(), true, w - 28f), x + 14f, y + h - 32f, WHITE, a);
		text(c, fit(m.description(), false, w - 28f), x + 14f, y + h - 18f, TEXT_3, a);
		hit(live, top, height, x, y, w, h, () -> go(new Page(Kind.MODULE, catOf(m), m)));
		hit(live, top, height, px, y + 8f, 24f, 28f, () -> QuickToggles.setFavorite(m.id(), !pinned));
		hit(live, top, height, sx - 4f, y + 8f, 38f, 28f, () -> QuickToggles.toggle(m));
	}

	// ---- header: profile dropdown

	private float renderProfileButton(DrawContext c, float right, float y, float a) {
		String name = activeProfile();
		Text t = fit(name == null ? "Default" : name, true, 80f);
		float w = tw(t) + 44f;
		float x = right - w;
		boolean hover = inside(x, y, w, 22f);
		rect(c, x, y, w, 22f, 11f, profileMenu ? 0x26FFFFFF : hover ? 0x1EFFFFFF : 0x10FFFFFF, a);
		VeloDraw.strokeRounded(c, x, y, w, 22f, 11f, 1f, alpha(profileMenu ? 0x66FFFFFF : 0x2EFFFFFF, a));
		icon(c, "d_user", x + 8f, y + 5f, 12f, TEXT_2, a);
		text(c, t, x + 23f, y + 7f, WHITE, a);
		float rot = animTo("profChevron", profileMenu ? 1f : 0f, 10f);
		icon(c, rot > 0.5f ? "d_chev_u" : "d_chev_d", x + w - 17f, y + 5f, 11f, TEXT_2, a);
		hits.add(new Hit(x, y, w, 22f, () -> {
			profileMenu = !profileMenu;
			moreMenu = false;
			dropdown = null;
		}));
		profileAnchor = new float[] {x + w, y + 26f};
		return x;
	}

	private float[] profileAnchor = {0, 0};

	private void renderProfileMenu(DrawContext c, float a) {
		List<String> names = new ArrayList<>(VeloProfileManager.listProfileNames());
		String active = activeProfile();
		float w = 190f;
		float rowH = 24f;
		float h = 22f + names.size() * rowH + 12f + 2 * 22f + 8f;
		float x = profileAnchor[0] - w;
		float y = profileAnchor[1];
		float t = animTo("profMenu", 1f, 12f);
		float ma = a * t;
		VeloDraw.shadow(c, Math.round(x), Math.round(y), Math.round(w), Math.round(h), 12, 16, 4, alpha(0x70000000, ma));
		rect(c, x, y, w, h, 12f, 0xF0202024, ma);
		VeloDraw.strokeRounded(c, x, y, w, h, 12f, 1f, alpha(0x26FFFFFF, ma));
		overlayHits.add(new Hit(x, y, w, h, () -> {
		}));
		text(c, Mo("PROFILES"), x + 10f, y + 9f, TEXT_3, ma);
		float ry = y + 22f;
		for (String n : names) {
			boolean on = n.equals(active);
			boolean hover = inside(x + 4f, ry, w - 8f, rowH);
			if (hover) {
				rect(c, x + 4f, ry, w - 8f, rowH, 8f, HOVER, ma);
			}
			text(c, fit(n, true, w - 40f), x + 12f, ry + 8f, WHITE, ma);
			if (on) {
				icon(c, "d_check", x + w - 24f, ry + 6f, 12f, WHITE, ma);
			}
			String target = n;
			overlayHits.add(new Hit(x + 4f, ry, w - 8f, rowH, () -> switchProfile(target)));
			ry += rowH;
		}
		ry += 4f;
		c.fill(Math.round(x + 8f), Math.round(ry), Math.round(x + w - 8f), Math.round(ry) + 1, alpha(0x1AFFFFFF, ma));
		ry += 6f;
		String[][] actions = {{"d_plus", "New profile"}, {"d_sliders", "Manage profiles"}};
		for (String[] act : actions) {
			boolean hover = inside(x + 4f, ry, w - 8f, 22f);
			if (hover) {
				rect(c, x + 4f, ry, w - 8f, 22f, 8f, HOVER, ma);
			}
			icon(c, act[0], x + 12f, ry + 5f, 12f, TEXT_2, ma);
			text(c, R(act[1]), x + 30f, ry + 7f, WHITE, ma);
			overlayHits.add(new Hit(x + 4f, ry, w - 8f, 22f, () -> {
				profileMenu = false;
				this.client.setScreen(new net.veloclient.velo.client.gui.ProfileScreen(this));
			}));
			ry += 22f;
		}
	}

	private void switchProfile(String name) {
		profileMenu = false;
		profileAt = 0;
		if (name.equals(activeProfile())) {
			return;
		}
		VeloProfileStore.saveActive();
		VeloProfileManager.loadByName(name).ifPresent(profile -> {
			VeloProfileStore.applyToLive(profile);
			VeloProfileManager.setActiveName(name);
		});
	}

	// ---- pages

	private void renderPage(DrawContext c, Page p, float x, float pw, float top, float height, float a, boolean live) {
		float s = Math.min(scroll.getOrDefault(p, 0f), scrollMax.getOrDefault(p, Float.MAX_VALUE));
		float y = top + 4f - s;
		float content = switch (p.kind()) {
			case HOME -> renderHome(c, x + PAD, y, pw - 2 * PAD, top, height, a, live);
			case CATEGORY -> renderCategory(c, p, x + PAD, y, pw - 2 * PAD, top, height, a, live);
			case MODULE -> renderModule(c, p, x + PAD, y, pw - 2 * PAD, top, height, a, live);
			case FEATURES -> {
				float y0 = y;
				float yy = backButton(c, "Home", x + PAD, y, top, height, a, live);
				text(c, B("Features"), x + PAD + 4f, yy, WHITE, a);
				yy = featureTiles(c, x + PAD, yy + 20f, pw - 2 * PAD, top, height, a, live);
				yield yy - y0;
			}
		};
		scrollBar(c, p, x + pw - 6f, s, content, top, height, a, live);
	}

	private void scrollBar(DrawContext c, Page p, float x, float s, float content, float top, float height, float a, boolean live) {
		float max = Math.max(0f, content - height + 12f);
		scrollMax.put(p, max);
		if (s > max) {
			setScroll(p, max);
		}
		if (max > 0f && live) {
			float bar = Math.max(24f, height * height / (content + 12f));
			float by = top + (height - bar) * (Math.min(s, max) / max);
			boolean active = draggingBar != null && draggingBar.page() == p;
			boolean over = active || mouseU >= x - 6f && mouseU < x + 8f && mouseV >= top && mouseV < top + height;
			float bw = over ? 4f : 2.5f;
			rect(c, x + 2.5f - bw, by, bw, bar, bw / 2f, over ? 0x80FFFFFF : 0x40FFFFFF, a);
			scrollbars.add(new Bar(p, x - 6f, top, 14f, height, bar, max));
		}
	}

	/** Adds a hit only for live pages and only when it's inside the visible content area. */
	private Hit hit(boolean live, float top, float height, float x, float y, float w, float h, Runnable click) {
		// only the visible part is clickable: a card scrolled under the header mustn't catch clicks meant for the header
		float y0 = Math.max(y, top);
		float y1 = Math.min(y + h, top + height);
		Hit hit = new Hit(x, y0, w, Math.max(0f, y1 - y0), click);
		if (live && y1 > y0) {
			hits.add(hit);
		}
		return hit;
	}

	private float renderHome(DrawContext c, float x, float y, float w, float top, float height, float a, boolean live) {
		float y0 = y;
		// search (always focused)
		renderSearch(c, x, y, w, 26f, a, live, top, height);
		if (live) {
			tourAnchor.put("search", new float[] {x, y, w, 26f});
		}
		String q = search.toString();
		y += 34f;
		if (!q.isEmpty()) {
			String lower = q.toLowerCase(Locale.ROOT);
			List<Module> found = new ArrayList<>();
			for (Module m : ModuleRegistry.all()) {
				if (m.displayName().toLowerCase(Locale.ROOT).contains(lower)
						|| net.veloclient.velo.client.gui.widget.ModuleTags.tag(m).toLowerCase(Locale.ROOT).contains(lower)) {
					found.add(m);
				}
			}
			text(c, R(found.isEmpty() ? "Nothing found" : "Results"), x + 4f, y, TEXT_3, a);
			y += 14f;
			for (Module m : found) {
				y = moduleRow(c, m, x, y, w, top, height, a, live, false);
			}
			return y - y0;
		}
		// pinned
		float modulesTop = y - 3f;
		List<Module> pins = QuickToggles.favorites();
		// the header folds the pinned list away without unpinning anything (VELO-184)
		boolean headHover = live && inside(x, y - 4f, w, 18f) && mouseV >= top && mouseV < top + height;
		text(c, R("Pinned"), x + 4f, y, headHover ? TEXT_2 : TEXT_3, a);
		icon(c, pinsFolded ? "d_chev_d" : "d_chev_u", x + w - 18f, y - 1f, 11f, headHover ? TEXT_2 : TEXT_3, a);
		hit(live, top, height, x, y - 4f, w, 18f, () -> pinsFolded = !pinsFolded);
		y += 14f;
		if (!pinsFolded) {
			if (pins.isEmpty()) {
				text(c, fit("Pin modules from any category.", false, w - 8f), x + 4f, y + 4f, TEXT_3, a);
				y += 22f;
			}
			for (Module m : pins) {
				y = moduleRow(c, m, x, y, w, top, height, a, live, true);
			}
		}
		y += 6f;
		c.fill(Math.round(x + 4f), Math.round(y), Math.round(x + w - 4f), Math.round(y) + 1, alpha(0x1AFFFFFF, a));
		y += 8f;
		for (Cat cat : cats) {
			boolean hover = live && inside(x, y, w, ROW) && mouseV >= top && mouseV < top + height;
			if (hover) {
				rect(c, x, y, w, ROW, 9f, HOVER, a);
			}
			icon(c, cat.icon(), x + 8f, y + 6f, 14f, accIcon(), a);
			text(c, R(cat.name()), x + 30f, y + 9f, WHITE, a);
			Text count = Mo(String.valueOf(cat.modules().size()));
			text(c, count, x + w - 26f - tw(count), y + 10f, TEXT_3, a);
			icon(c, "d_chev_r", x + w - 20f, y + 7f, 12f, TEXT_3, a);
			Cat target = cat;
			hit(live, top, height, x, y, w, ROW, () -> go(new Page(Kind.CATEGORY, target, null)));
			y += ROW;
		}
		if (live) {
			tourAnchor.put("modules", new float[] {x, modulesTop, w, y - modulesTop + 2f});
		}
		y += 10f;
		float featuresTop = y - 3f;
		text(c, R("Features"), x + 4f, y, TEXT_3, a);
		y += 14f;
		y = featureTiles(c, x, y, w, top, height, a, live);
		if (live) {
			tourAnchor.put("features", new float[] {x, featuresTop, w, y - featuresTop + 2f});
		}
		return y - y0;
	}

	private float renderCategory(DrawContext c, Page p, float x, float y, float w, float top, float height, float a, boolean live) {
		float y0 = y;
		y = backButton(c, "Home", x, y, top, height, a, live);
		text(c, B(p.cat().name()), x + 4f, y, WHITE, a);
		y += 20f;
		for (Module m : p.cat().modules()) {
			y = moduleRow(c, m, x, y, w, top, height, a, live, false);
		}
		return y - y0;
	}

	private float backButton(DrawContext c, String label, float x, float y, float top, float height, float a, boolean live) {
		Text t = R(label);
		float bw = tw(t) + 24f;
		boolean hover = live && inside(x, y, bw, 20f);
		if (hover) {
			rect(c, x, y, bw, 20f, 10f, HOVER, a);
		}
		icon(c, "d_chev_l", x + 4f, y + 4f, 12f, TEXT_2, a);
		text(c, t, x + 18f, y + 6f, TEXT_2, a);
		hit(live, top, height, x, y, bw, 20f, this::back);
		return y + 26f;
	}

	/** A module row: (grip for HUD modules) icon, name, pin, switch. Clicking the name opens its settings. */
	private float moduleRow(DrawContext c, Module m, float x, float y, float w, float top, float height, float a, boolean live, boolean pinnedList) {
		boolean visible = y + ROW > top && y < top + height;
		if (!visible) {
			return y + ROW;
		}
		boolean hover = live && inside(x, y, w, ROW) && mouseV >= top && mouseV < top + height;
		if (hover) {
			rect(c, x, y, w, ROW, 9f, HOVER, a);
		}
		float ix = x + 6f;
		if (m instanceof HudModule && !pinnedList) {
			icon(c, "d_grip", x + 1f, y + 7f, 12f, hover ? TEXT_2 : TEXT_3, a);
			Hit grip = hit(live, top, height, x, y, 14f, ROW, null);
			grip.grip = m;
			ix = x + 15f;
		}
		moduleIcon(c, m, ix, y + 6f, 14f, m.isEnabled() ? accIcon() : TEXT_2, a);
		float nameX = ix + 22f;
		float right = x + w - 6f;
		float sx = right - 26f;
		float px = sx - 24f;
		text(c, fit(m.displayName(), false, px - nameX - 4f), nameX, y + 9f, WHITE, a);
		boolean pinned = QuickToggles.isFavorite(m.id());
		boolean pinHover = live && inside(px, y + 4f, 20f, 18f);
		if (pinned || hover) {
			icon(c, pinned ? "d_pin_on" : "d_pin", px + 3f, y + 6f, 13f, pinned ? (pinHover ? TEXT_2 : WHITE) : pinHover ? WHITE : TEXT_3, a);
		}
		toggle(c, "row:" + m.id(), sx, y + 5.5f, m.isEnabled(), a, false);
		hit(live, top, height, x + 14f, y, px - x - 14f, ROW, () -> go(new Page(Kind.MODULE, catOf(m), m)));
		hit(live, top, height, px, y, 22f, ROW, () -> QuickToggles.setFavorite(m.id(), !pinned));
		hit(live, top, height, sx - 2f, y, 30f, ROW, () -> QuickToggles.toggle(m));
		return y + ROW;
	}

	// ---- module page

	private float renderModule(DrawContext c, Page p, float x, float y, float w, float top, float height, float a, boolean live) {
		Module m = p.module();
		float y0 = y;
		y = backButton(c, p.cat() == null ? "Home" : p.cat().name(), x, y, top, height, a, live);
		// header: icon tile, name, description (wrapped), big switch
		boolean modOn = m.isEnabled();
		rect(c, x, y, 34f, 34f, 11f, modOn ? acc() : 0x1FFFFFFF, a);
		moduleIcon(c, m, x + 8f, y + 8f, 18f, modOn ? onAcc() : accIcon(), a);
		text(c, fit(m.displayName(), true, w - 90f), x + 44f, y + 4f, WHITE, a);
		List<Text> desc = wrap(m.description(), w - 90f, 5);   // the whole description (VELO-187: it was cut after two lines)
		for (int i = 0; i < desc.size(); i++) {
			text(c, desc.get(i), x + 44f, y + 18f + i * 13f, TEXT_3, a);   // 13 px apart: at 11 the lines almost touched
		}
		toggle(c, "mod:" + m.id(), x + w - 32f, y + 8f, modOn, a, true);
		hit(live, top, height, x + w - 36f, y + 2f, 36f, 28f, () -> QuickToggles.toggle(m));
		y += Math.max(44f, 24f + desc.size() * 13f) + 4f;

		if (m instanceof net.veloclient.velo.client.gui.window.ModuleDashboard dash) {
			y = renderStats(c, m, dash, x, y, w, a);
		}
		List<ConfigField> fields = m instanceof Configurable cfg ? cfg.configFields() : List.of();
		if (fields.isEmpty()) {
			text(c, R("No settings - just switch it on or off."), x + 4f, y + 4f, TEXT_3, a);
			y += 22f;
		}
		// one glass card per header group
		List<List<ConfigField>> groups = new ArrayList<>();
		List<String> titles = new ArrayList<>();
		for (ConfigField f : fields) {
			if (f instanceof ConfigField.HeaderField hf || groups.isEmpty()) {
				groups.add(new ArrayList<>());
				titles.add(f instanceof ConfigField.HeaderField hf2 ? hf2.label() : "Settings");
				if (f instanceof ConfigField.HeaderField) {
					continue;
				}
			}
			groups.get(groups.size() - 1).add(f);
		}
		for (int g = 0; g < groups.size(); g++) {
			if (groups.get(g).isEmpty()) {
				continue;
			}
			float cardTop = y;
			float inner = w - 20f;
			// measure first
			float ch = 22f;
			for (ConfigField f : groups.get(g)) {
				ch += fieldHeight(f, inner) + 6f;
			}
			ch += 4f;
			rect(c, x, cardTop, w, ch, 12f, CARD, a);
			VeloDraw.strokeRounded(c, x, cardTop, w, ch, 12f, 1f, alpha(0x0FFFFFFF, a));
			text(c, Mo(titles.get(g).toUpperCase(Locale.ROOT)), x + 10f, cardTop + 9f, TEXT_3, a);
			float fy = cardTop + 22f;
			boolean first = true;
			for (ConfigField f : groups.get(g)) {
				if (!first) {
					c.fill(Math.round(x + 10f), Math.round(fy - 3.5f), Math.round(x + w - 10f), Math.round(fy - 3.5f) + 1, alpha(0x0DFFFFFF, a));
				}
				first = false;
				renderField(c, m, f, x + 10f, fy, inner, top, height, a, live);
				fy += fieldHeight(f, inner) + 6f;
			}
			y = cardTop + ch + 8f;
		}
		if (m instanceof net.veloclient.velo.client.gui.window.ModuleShortcut shortcut) {
			y = wideButton(c, "d_external", shortcut.shortcutLabel(), x, y, w, top, height, a, live, () -> shortcut.openShortcut(this));
		}
		if (m instanceof HudModule && !m.id().equals("hud-style")) {
			Module style = ModuleRegistry.get("hud-style").orElse(null);
			if (style != null) {
				y = wideButton(c, "d_sliders", "HUD style", x, y, w, top, height, a, live,
						() -> go(new Page(Kind.MODULE, catOf(style), style)));
			}
		}
		if (m instanceof HudModule hud) {
			y = wideButton(c, "d_reset", "Reset position", x, y, w, top, height, a, live, () -> {
				hud.position().resetToDefault();
				VeloProfileStore.saveActive();
			});
		}
		if (m instanceof net.veloclient.velo.client.gui.window.ModuleDashboard dash) {
			String tip = dash.dashboardTip();
			if (tip != null && !tip.isBlank()) {
				List<Text> lines = wrap(tip, w - 8f, 3);
				for (Text line : lines) {
					text(c, line, x + 4f, y + 2f, TEXT_3, a);
					y += 11f;
				}
				y += 6f;
			}
		}
		return y - y0;
	}

	// live numbers of dashboard modules (read a few times a second, like the big dashboard did)
	private List<net.veloclient.velo.client.gui.window.ModuleDashboard.Stat> stats = List.of();
	private Module statsOf;
	private long statsAt;

	private static List<ConfigField> fields(Module m) {
		return m instanceof Configurable cfg ? cfg.configFields() : List.of();
	}

	private float renderStats(DrawContext c, Module m, net.veloclient.velo.client.gui.window.ModuleDashboard dash, float x, float y, float w, float a) {
		long now = System.currentTimeMillis();
		if (statsOf != m || now - statsAt > 300) {
			try {
				stats = dash.dashboardStats();
			} catch (RuntimeException e) {
				stats = List.of();
			}
			statsOf = m;
			statsAt = now;
		}
		if (stats != null && fields(m).stream().anyMatch(f -> f instanceof ConfigField.ChoiceField cf && cf.label().equalsIgnoreCase("Preset"))) {
			// the Preset setting is right below - no second "Preset" tile
			stats = stats.stream().filter(st -> !st.label().equalsIgnoreCase("Preset")).toList();
		}
		if (stats == null || stats.isEmpty()) {
			return y;
		}
		int cols = w >= 420f ? 4 : 2;
		float gap = 6f;
		float sw = (w - gap * (cols - 1)) / cols;
		float sh = 34f;
		for (int i = 0; i < stats.size(); i++) {
			var st = stats.get(i);
			float sx = x + (i % cols) * (sw + gap);
			float sy = y + (i / cols) * (sh + gap);
			rect(c, sx, sy, sw, sh, 10f, CARD, a);
			VeloDraw.strokeRounded(c, sx, sy, sw, sh, 10f, 1f, alpha(0x0FFFFFFF, a));
			text(c, fit(st.label().toUpperCase(Locale.ROOT), false, sw - 16f), sx + 8f, sy + 6f, TEXT_3, a);
			Text v = fit(st.value(), true, sw - 16f);
			int col = (st.color() & 0xFF000000) == 0 ? st.color() | 0xFF000000 : st.color();
			text(c, v, sx + 8f, sy + 19f, col, a);
			if (st.note() != null && !st.note().isEmpty() && tw(v) + 14f < sw - 16f) {
				text(c, fit(st.note(), false, sw - 24f - tw(v)), sx + 14f + tw(v), sy + 19f, TEXT_3, a);
			}
			if (st.meter() >= 0f) {
				rect(c, sx + 8f, sy + sh - 5f, sw - 16f, 2f, 1f, 0x1FFFFFFF, a);
				rect(c, sx + 8f, sy + sh - 5f, (sw - 16f) * clamp01(st.meter()), 2f, 1f, col, a);
			}
		}
		return y + ((stats.size() + cols - 1) / cols) * (sh + gap) + 6f;
	}

	private float wideButton(DrawContext c, String iconKey, String label, float x, float y, float w, float top, float height, float a, boolean live,
			Runnable action) {
		boolean hover = live && inside(x, y, w, 26f);
		rect(c, x, y, w, 26f, 13f, hover ? 0x22FFFFFF : 0x12FFFFFF, a);
		Text t = fit(label, false, w - 34f);
		float tx = x + (w - tw(t) - 18f) / 2f;
		icon(c, iconKey, tx, y + 7f, 12f, TEXT_2, a);
		text(c, t, tx + 18f, y + 9f, WHITE, a);
		hit(live, top, height, x, y, w, 26f, action);
		return y + 32f;
	}

	private boolean segmented(ConfigField.ChoiceField f, float w) {
		if (f.options().size() > 4 || f.options().isEmpty()) {
			return false;
		}
		float each = w / f.options().size();
		for (String o : f.options()) {
			if (tw(R(o)) > each - 10f) {
				return false;
			}
		}
		return true;
	}

	private float fieldHeight(ConfigField f, float w) {
		if (f instanceof ConfigField.SliderField) {
			return 30f;
		}
		if (f instanceof ConfigField.ChoiceField choice) {
			return segmented(choice, w) ? 36f : 20f;
		}
		if (f instanceof ConfigField.TextField) {
			return 36f;
		}
		if (f instanceof ConfigField.ActionButtonField) {
			return 24f;
		}
		if (f instanceof ConfigField.ColorField) {
			return 36f;
		}
		return 20f;
	}

	private void renderField(DrawContext c, Module m, ConfigField f, float x, float y, float w, float top, float height, float a, boolean live) {
		if (y + fieldHeight(f, w) < top || y > top + height) {
			return;
		}
		Text label = fit(f.label(), false, w * 0.6f);
		if (f instanceof ConfigField.ToggleField t) {
			boolean on = t.get().getAsBoolean();
			text(c, label, x, y + 6f, TEXT_1, a);
			toggle(c, "f:" + m.id() + ":" + f.label(), x + w - 26f, y + 2.5f, on, a, false);
			hit(live, top, height, x, y, w, 20f, () -> {
				t.set().accept(!on);
				VeloProfileStore.saveActive();
			});
		} else if (f instanceof ConfigField.SliderField s) {
			double v = s.get().getAsDouble();
			double frac = s.max() > s.min() ? (v - s.min()) / (s.max() - s.min()) : 0;
			text(c, label, x, y + 2f, TEXT_1, a);
			Text val = Mo(s.format() != null ? s.format().apply(v) : String.format(Locale.ROOT, "%.1f", v));
			float vw = tw(val) + 10f;
			rect(c, x + w - vw, y - 1f, vw, 13f, 6.5f, 0x1AFFFFFF, a);
			text(c, val, x + w - vw + 5f, y + 2f, WHITE, a);
			float ty = y + 20f;
			float kw = w - 12f;
			float kx0 = x + 6f;
			rect(c, kx0, ty, kw, 4f, 2f, 0x24FFFFFF, a);
			rect(c, kx0, ty, (float) Math.max(4f, kw * frac), 4f, 2f, acc(), a);
			float knobX = kx0 + (float) (kw * frac);
			boolean held = sliding != null && sliderTrack.containsKey(sliding) && sliderTrack.get(sliding)[0] == kx0 && sliderTrack.get(sliding)[2] == ty;
			boolean over = live && inside(x - 4f, ty - 8f, w + 8f, 20f);
			float kr = 5.5f + animTo("knob:" + f.label() + m.id(), held ? 1.5f : over ? 0.8f : 0f, 14f);
			VeloDraw.fillCircle(c, knobX, ty + 2.6f, kr + 0.5f, alpha(0x55000000, a));
			VeloDraw.fillCircle(c, knobX, ty + 2f, kr, alpha(WHITE, a));
			Hit hit = hit(live, top, height, x - 4f, ty - 8f, w + 8f, 20f, null);
			hit.slide = t -> {
				double nv = s.min() + Math.max(0, Math.min(1, t)) * (s.max() - s.min());
				s.set().accept(nv);
			};
			sliderTrack.put(hit, new float[] {kx0, kw, ty});
		} else if (f instanceof ConfigField.ChoiceField choice) {
			String cur = choice.get().get();
			if (segmented(choice, w)) {
				text(c, label, x, y + 2f, TEXT_1, a);
				float sy = y + 13f;
				rect(c, x, sy, w, 22f, 11f, 0x33000000, a);
				float each = w / choice.options().size();
				int idx = Math.max(0, choice.options().indexOf(cur));
				float pos = animTo("seg:" + m.id() + ":" + f.label(), idx, 12f);
				rect(c, x + 2f + pos * each, sy + 2f, each - 4f, 18f, 9f, acc(), a);
				for (int i = 0; i < choice.options().size(); i++) {
					String o = choice.options().get(i);
					Text ot = B(o);
					boolean sel = i == idx;
					text(c, ot, x + i * each + (each - tw(ot)) / 2f, sy + 7f, sel ? onAcc() : TEXT_2, a);
					hit(live, top, height, x + i * each, sy, each, 22f, () -> {
						choice.set().accept(o);
						VeloProfileStore.saveActive();
					});
				}
			} else {
				text(c, label, x, y + 6f, TEXT_1, a);
				Text vt = fit(cur, true, w * 0.4f);
				float bw = tw(vt) + 26f;
				float bx = x + w - bw;
				boolean hover = live && inside(bx, y, bw, 20f);
				rect(c, bx, y, bw, 20f, 10f, dropdown == choice ? 0x30FFFFFF : hover ? 0x24FFFFFF : 0x14FFFFFF, a);
				text(c, vt, bx + 9f, y + 6f, WHITE, a);
				icon(c, "d_chev_d", bx + bw - 15f, y + 5f, 10f, TEXT_2, a);
				hit(live, top, height, bx, y, bw, 20f, () -> {
					dropdown = dropdown == choice ? null : choice;
					dropdownAt = new float[] {bx + bw, y + 22f};
					profileMenu = false;
				});
			}
		} else if (f instanceof ConfigField.ColorField col) {
			int argb = col.get().getAsInt();
			text(c, label, x, y + 2f, TEXT_1, a);
			Text hex = Mo(String.format("#%06X", argb & 0xFFFFFF));
			text(c, hex, x + w - tw(hex), y + 3f, TEXT_3, a);
			float sy = y + 23f;
			float step = 21f;
			boolean preset = false;
			for (int i = 0; i < SWATCHES.length; i++) {
				int sw = SWATCHES[i];
				float cx = x + 8f + i * step;
				boolean sel = (sw & 0xFFFFFF) == (argb & 0xFFFFFF);
				preset |= sel;
				boolean hover = live && inside(cx - 9f, sy - 9f, 18f, 18f);
				if (sel || hover) {
					VeloDraw.fillCircle(c, cx, sy, 9f, alpha(sel ? WHITE : 0x66FFFFFF, a));
					VeloDraw.fillCircle(c, cx, sy, 7.5f, alpha(0xFF1A1A1E, a));
				}
				VeloDraw.fillCircle(c, cx, sy, sel || hover ? 6f : 7f, alpha(sw, a));
				hit(live, top, height, cx - 9f, sy - 9f, 18f, 18f, () -> {
					col.set().accept((argb & 0xFF000000) | (sw & 0xFFFFFF));
					VeloProfileStore.saveActive();
				});
			}
			// custom: your own colour (shows it when it's none of the presets) - opens the full picker
			float cx = x + 8f + SWATCHES.length * step + 6f;
			boolean hover = live && inside(cx - 9f, sy - 9f, 18f, 18f);
			VeloDraw.fillCircle(c, cx, sy, 9f, alpha(!preset ? WHITE : hover ? 0x66FFFFFF : 0x33FFFFFF, a));
			VeloDraw.fillCircle(c, cx, sy, 7.5f, alpha(0xFF1A1A1E, a));
			if (!preset) {
				VeloDraw.fillCircle(c, cx, sy, 6f, alpha(argb | 0xFF000000, a));
			} else {
				icon(c, "d_plus", cx - 5f, sy - 5f, 10f, TEXT_2, a);
			}
			hit(live, top, height, cx - 10f, sy - 10f, Math.max(20f, x + w - cx + 10f), 20f, () -> this.client.setScreen(new net.veloclient.velo.client.gui.VeloColorPickerScreen(
					this, col.label(), col.get().getAsInt(), col.includeAlpha(), v -> {
						col.set().accept(v);
						VeloProfileStore.saveActive();
					})));
		} else if (f instanceof ConfigField.KeybindField kb) {
			boolean on = listening == kb;
			keycap(c, label, on ? "Press a key…" : kb.displayText().get(), on, x, y, w, top, height, a, live, () -> {
				listening = on ? null : kb;
				listeningChord = null;
			});
		} else if (f instanceof ConfigField.ChordKeybindField chord) {
			boolean on = listeningChord == chord;
			String shown = !on ? net.veloclient.velo.client.keybind.ChordKeybinds.displayText(chord.get().get())
					: chordMax.isEmpty() ? "Hold keys…" : net.veloclient.velo.client.keybind.ChordKeybinds.displayText(List.copyOf(chordMax)) + "…";
			keycap(c, label, shown, on, x, y, w, top, height, a, live, () -> {
				listeningChord = on ? null : chord;
				listening = null;
				chordHeld.clear();
				chordMax.clear();
			});
		} else if (f instanceof ConfigField.TextField tf) {
			text(c, label, x, y + 2f, TEXT_1, a);
			boolean on = editing == tf;
			if (live && inside(x, y + 13f, w, 22f)) {
				net.veloclient.velo.client.util.IBeamCursor.ibeam(c);
			}
			rect(c, x, y + 13f, w, 22f, 11f, on ? 0x26FFFFFF : 0x33000000, a);
			if (on) {
				VeloDraw.strokeRounded(c, x, y + 13f, w, 22f, 11f, 1f, alpha(0x80FFFFFF, a));
			}
			String shown = on ? editBuffer.toString() : tf.get().get();
			Text st = shown.isEmpty() && !on ? R(tf.placeholder() == null ? "" : tf.placeholder()) : fit(shown, false, w - 20f);
			text(c, st, x + 10f, y + 20f, shown.isEmpty() && !on ? TEXT_3 : WHITE, a);
			if (on && (System.currentTimeMillis() / 530) % 2 == 0) {
				rect(c, x + 11f + tw(st), y + 18f, 1f, 12f, 0f, WHITE, a);
			}
			hit(live, top, height, x, y + 13f, w, 22f, () -> {
				commitEdit();
				editing = tf;
				editBuffer.setLength(0);
				editBuffer.append(tf.get().get());
			});
		} else if (f instanceof ConfigField.ActionButtonField act) {
			boolean hover = live && inside(x, y, w, 24f);
			rect(c, x, y, w, 24f, 12f, hover ? 0x26FFFFFF : 0x14FFFFFF, a);
			VeloDraw.strokeRounded(c, x, y, w, 24f, 12f, 1f, alpha(hover ? 0x40FFFFFF : 0x1FFFFFFF, a));
			Text t = fit(act.label(), true, w - 20f);   // a long label ends in "…" instead of running past the button
			text(c, t, x + (w - tw(t)) / 2f, y + 8f, WHITE, a);
			hit(live, top, height, x, y, w, 24f, () -> act.action().run());
		}
	}

	private final Map<Hit, float[]> sliderTrack = new HashMap<>();

	/** A page's scroll bar this frame: drag (or click) it to scroll. */
	private record Bar(Page page, float x, float y, float w, float h, float bar, float max) {
		boolean contains(double u, double v) {
			return u >= x && u < x + w && v >= y && v < y + h;
		}

		float scrollAt(double v) {
			return Math.max(0f, Math.min(max, (float) ((v - y - bar / 2f) / Math.max(1f, h - bar)) * max));
		}
	}

	private final List<Bar> scrollbars = new ArrayList<>();
	/** Smooth scrolling: where each page is heading (the wheel and flings set it; the view eases there). */
	private final Map<Page, Float> scrollTarget = new HashMap<>();
	private Hit glassHit;
	// touch-style scrolling: press on an empty spot of the page and drag
	private Page touchPage;
	private double touchStartV;
	private float touchStartScroll;
	private double touchLastV;
	private long touchLastNanos;
	private float touchVelocity;

	private void setScroll(Page p, float value) {
		scroll.put(p, value);
		scrollTarget.put(p, value);
	}

	private float clampScroll(Page p, float value) {
		return Math.max(0f, Math.min(scrollMax.getOrDefault(p, Float.MAX_VALUE), value));
	}
	/** How far each page can scroll (from the last frame). */
	private final Map<Page, Float> scrollMax = new HashMap<>();
	private Bar draggingBar;
	private static final int[] SWATCHES = {0xFFFFFFFF, 0xFFE5373E, 0xFFFF9F0A, 0xFFFFD60A, 0xFF32D74B, 0xFF64D2FF, 0xFF5E5CE6, 0xFFBF5AF2, 0xFF141416};

	private void keycap(DrawContext c, Text label, String key, boolean on, float x, float y, float w, float top, float height, float a, boolean live,
			Runnable click) {
		if (on) {
			// listening: the whole row lights up and the key pulses in the accent - clearly "press a key now"
			float pulse = 0.55f + 0.45f * (float) Math.sin(System.nanoTime() / 1.6e8);
			rect(c, x - 6f, y - 3f, w + 12f, 26f, 9f, 0x1CFFFFFF, a);
			text(c, label, x, y + 6f, WHITE, a);
		} else {
			text(c, label, x, y + 6f, TEXT_1, a);
		}
		Text kt = on ? B(key) : Mo(key);
		float bw = Math.max(46f, tw(kt) + 18f);
		float bx = x + w - bw;
		if (on) {
			float pulse = 0.55f + 0.45f * (float) Math.sin(System.nanoTime() / 1.6e8);
			rect(c, bx, y, bw, 20f, 10f, acc(), a);
			VeloDraw.strokeRounded(c, bx - 2f, y - 2f, bw + 4f, 24f, 12f, 1.5f, alpha(VeloUi.withAlpha(acc(), Math.round(255 * pulse)), a));
			text(c, kt, bx + (bw - tw(kt)) / 2f, y + 7f, onAcc(), a);
		} else {
			rect(c, bx, y, bw, 20f, 10f, 0x40000000, a);
			VeloDraw.strokeRounded(c, bx, y, bw, 20f, 10f, 1f, alpha(0x2AFFFFFF, a));
			text(c, kt, bx + (bw - tw(kt)) / 2f, y + 7f, WHITE, a);
		}
		hit(live, top, height, bx, y, bw, 20f, click);
	}

	private ConfigField.ChoiceField lastDropdown;
	private float[] lastDropdownAt;

	private void renderDropdown(DrawContext c, float a, float shown) {
		ConfigField.ChoiceField f = lastDropdown;
		boolean live = dropdown != null;
		float[] dropdownAt = lastDropdownAt;
		float w = 150f;
		for (String o : f.options()) {
			w = Math.max(w, tw(R(o)) + 40f);
		}
		float rowH = 20f;
		float h = f.options().size() * rowH + 8f;
		float x = dropdownAt[0] - w;
		float y = dropdownAt[1];
		if (y + h > bodyBottom) {
			y = Math.max(bodyTop, bodyBottom - h);
		}
		y -= (1f - ease(shown)) * 8f;   // slides down into place (and back up while it fades out)
		VeloDraw.shadow(c, Math.round(x), Math.round(y), Math.round(w), Math.round(h), 10, 14, 4, alpha(0x70000000, a));
		rect(c, x, y, w, h, 12f, 0xF2202024, a);
		VeloDraw.strokeRounded(c, x, y, w, h, 12f, 1f, alpha(0x26FFFFFF, a));
		if (live) {
			overlayHits.add(new Hit(x, y, w, h, () -> {
			}));
		}
		String cur = f.get().get();
		float ry = y + 4f;
		for (String o : f.options()) {
			boolean hover = inside(x + 4f, ry, w - 8f, rowH);
			if (hover) {
				rect(c, x + 4f, ry, w - 8f, rowH, 7f, HOVER, a);
			}
			text(c, R(o), x + 12f, ry + 6f, WHITE, a);
			if (o.equals(cur)) {
				icon(c, "d_check", x + w - 22f, ry + 4f, 12f, WHITE, a);
			}
			if (live) {
				overlayHits.add(new Hit(x + 4f, ry, w - 8f, rowH, () -> {
					f.set().accept(o);
					dropdown = null;
					VeloProfileStore.saveActive();
				}));
			}
			ry += rowH;
		}
	}

	// ---- dock + More

	private static final String[][] DOCK_ITEMS = {{"skins", "d_shirt", "Locker"}, {"friends", "d_users", "Friends"}, {"clips", "d_video", "Clips"},
			{"map", "d_map", "World Map"}, {"settings", "d_sliders", "Settings"}};



	private void openEntry(String id) {
		for (MenuShell.Entry e : MenuShell.entries()) {
			if (e.id().equals(id)) {
				Screen next = e.open().apply(this);
				if (next instanceof net.veloclient.velo.client.gui.window.VeloWindow window) {
					window.returnTo(this);   // Esc / close lands back in the R-Shift menu
					// the glass grows into the window first, then the window takes over in place
					int[] r = window.plannedRect(this.width, this.height);
					handoffFrom = rectNow();
					handoffTo = new float[] {r[0] / k, r[1] / k, r[2] / k, r[3] / k};
					handoffT = 0f;
					handoffDone = false;
					pending = next;
					dropdown = null;
					profileMenu = false;
					return;
				}
				if (next != null) {
					this.client.setScreen(next);
				}
				return;
			}
		}
	}



	// ---- dragging a module out of the drawer

	private void renderGhost(DrawContext c) {
		if (ghost == null || !ghostMoved) {
			return;
		}
		boolean drop = !overDrawer(mouseU, mouseV);
		if (ghost instanceof HudModule hud && hud.width() > 0 && hud.height() > 0) {
			// the element itself, centred on the cursor, in gui pixels
			int w = sw(hud);
			int h = sh(hud);
			int gx = (int) Math.round(mouseU * k) - w / 2;
			int gy = (int) Math.round(mouseV * k) - h / 2;
			int cx = hud.position().resolveX(this.width, w);
			int cy = hud.position().resolveY(this.height, h);
			ModuleScenes.pop(c);
			ModuleScenes.push(c);
			ModuleScenes.move(c, gx - cx, gy - cy);
			net.veloclient.velo.client.hud.HudSample.begin();   // dragged out of the drawer: show what it looks like
			try {
				HudManager.renderScaled(c, hud, this.width, this.height, lastDelta);
			} finally {
				net.veloclient.velo.client.hud.HudSample.end();
			}
			ModuleScenes.pop(c);
			VeloDraw.strokeRounded(c, gx - 3, gy - 3, w + 6, h + 6, 4, VeloUi.withAlpha(WHITE, drop ? 255 : 90));
			if (!drop) {
				Text t = MenuKit.uiBold("Drop on the screen");
				int tw = MenuKit.width(this.textRenderer, t) + 12;
				VeloDraw.fillRounded(c, gx + w / 2f - tw / 2f, gy + h + 6, tw, 13, 6.5f, WHITE);
				MenuKit.text(c, this.textRenderer, t, Math.round(gx + w / 2f - tw / 2f + 6), gy + h + 9, 0xFF141416);
			}
			ModuleScenes.push(c);
			ModuleScenes.scale(c, k, k);
			return;
		}
		Text t = B(ghost.displayName());
		float w = tw(t) + 34f;
		float x = (float) mouseU - 12f;
		float y = (float) mouseV - 11f;
		VeloDraw.shadow(c, Math.round(x), Math.round(y), Math.round(w), 22, 11, 12, 3, 0x60000000);
		rect(c, x, y, w, 22f, 11f, WHITE, drop ? 1f : 0.6f);
		icon(c, "d_plus", x + 8f, y + 5f, 12f, 0xFF141416, 1f);
		text(c, t, x + 24f, y + 7f, 0xFF141416, 1f);
	}

	// ---------------------------------------------------------------- input

	// ---- first-open tour (2026-11-26): a spotlight on each part of the panel and a glass card that glides along ----

	private record TourStop(String anchor, String icon, String title, String body) {
	}

	private static final TourStop[] TOUR = {
			new TourStop("panel", "d_sparkles", "Welcome to Velo",
					"This panel is everything Velo can do. Right Shift opens it any time, Esc or Right Shift closes it again."),
			new TourStop("search", "d_search", "Just start typing",
					"The search is always active: type a module's name anywhere in the menu and it shows up right away."),
			new TourStop("modules", "d_layout", "Modules",
					"Pick a category, then flip a switch to turn a module on. Click a module's name for its settings and a live preview. Pin your favourites to the top."),
			new TourStop("hud", "d_layout", "Your HUD",
					"While this panel is open you can edit your HUD right in the game: drag elements around, pull a corner to resize, click one for its settings."),
			new TourStop("features", "d_sparkles", "Features",
					"Locker, Friends, Clips, Emotes and more - all one click away."),
			new TourStop("profile", "d_user", "Profiles",
					"Save different setups - one for PvP, one for building - and switch between them here."),
			new TourStop("expand", "d_expand", "Full menu",
					"Grows the panel into the big menu: every module with previews, plus all features in tabs."),
			new TourStop("collapse", "d_collapse", "Slide it away",
					"Tucks the panel to the side so you see the whole screen. The tab on the right edge brings it back. That's it - have fun!"),
	};

	private final Map<String, float[]> tourAnchor = new HashMap<>();
	private final List<Hit> tourHits = new ArrayList<>();
	/** Opened for something specific (a module, the full menu from the pause screen): no tour this time. */
	private boolean explicitOpen;
	private boolean tourChecked;
	private int tourStep = -1;
	private int tourShown;
	private boolean tourEnding;
	private float tourAlpha;
	private float tourText = 1f;
	private float[] tourSpot;
	private float[] tourCard;
	private long tourStartNanos;

	private boolean touring() {
		return tourStep >= 0 && !tourEnding;
	}

	private void maybeStartTour() {
		if (tourChecked || closing || openAnim < 0.95f) {
			return;
		}
		boolean forced = Boolean.getBoolean("velo.rshiftTour");
		if (!forced && (DrawerPrefs.toured() || explicitOpen || stack.size() != 1 || System.getProperty("velo.drawerTour") != null
				|| System.getProperty("velo.screenshotTour") != null || Boolean.getBoolean("velo.drawerBench"))) {
			tourChecked = true;
			return;
		}
		if (expanded) {
			setExpanded(false);   // the tour walks through the panel; it grows into the full menu once it has settled
			return;
		}
		if (expandAnim > 0.02f || !tourAnchor.containsKey("panel")) {
			return;
		}
		tourChecked = true;
		search.setLength(0);
		open = true;
		tourStep = 0;
		tourShown = 0;
		tourText = 1f;
		tourStartNanos = System.nanoTime();
		net.veloclient.velo.client.sound.VeloUiSounds.guideNext();
	}

	private void tourNext() {
		if (tourStep >= TOUR.length - 1) {
			endTour();
			net.veloclient.velo.client.sound.VeloUiSounds.guideDone();
			return;
		}
		tourStep++;
		net.veloclient.velo.client.sound.VeloUiSounds.guideNext();
	}

	private void tourBack() {
		if (tourStep > 0) {
			tourStep--;
			net.veloclient.velo.client.sound.VeloUiSounds.guideBack();
		}
	}

	private void endTour() {
		if (tourStep < 0) {
			return;
		}
		tourEnding = true;
		DrawerPrefs.setToured(true);
		if (page().kind() == Kind.HOME) {
			scrollTarget.put(page(), 0f);
		}
	}

	/** The spotlit area of a stop, in drawer units. */
	private float[] tourTarget(TourStop stop) {
		if (stop.anchor().equals("hud")) {
			float panelX = rectNow()[0];
			return new float[] {M + 8f, M + 22f, Math.max(60f, panelX - 2f * M - 16f), H() - 2f * M - 92f};
		}
		float[] r = tourAnchor.get(stop.anchor());
		if (r == null) {
			return tourAnchor.getOrDefault("panel", new float[] {W() / 2f - 50f, H() / 2f - 50f, 100f, 100f});
		}
		float pad = stop.anchor().equals("panel") ? 0f : 4f;
		float[] t = {r[0] - pad, r[1] - pad, r[2] + 2f * pad, r[3] + 2f * pad};
		// a stop further down the list scrolls it into view (and back up for the others)
		if (page().kind() == Kind.HOME && !stop.anchor().equals("panel")) {
			float cur = scroll.getOrDefault(page(), 0f);
			if (stop.anchor().equals("features") && t[1] + t[3] > bodyBottom - 6f) {
				scrollTarget.put(page(), clampScroll(page(), cur + (t[1] + t[3]) - (bodyBottom - 6f)));
			} else if (!stop.anchor().equals("features") && cur > 0f && !stop.anchor().equals("hud")) {
				scrollTarget.put(page(), 0f);
			}
		}
		if (!stop.anchor().equals("panel") && !stop.anchor().equals("collapse") && !stop.anchor().equals("expand") && !stop.anchor().equals("profile")) {
			// list parts: keep the spotlight inside the panel's body
			float top = Math.max(t[1], bodyTop - 2f);
			float bottom = Math.min(t[1] + t[3], bodyBottom + 2f);
			t[1] = top;
			t[3] = Math.max(20f, bottom - top);
		}
		return t;
	}

	private void renderTour(DrawContext c) {
		tourHits.clear();
		maybeStartTour();
		if (tourStep < 0) {
			return;
		}
		tourAlpha = approach(tourAlpha, tourEnding ? 0f : 1f, dt / (tourEnding ? 0.22f : 0.3f));
		if (tourEnding && tourAlpha <= 0.001f) {
			tourStep = -1;
			tourEnding = false;
			tourSpot = null;
			tourCard = null;
			return;
		}
		float a = ease(tourAlpha);
		float glide = 1f - (float) Math.exp(-dt * 11f);
		float[] want = tourTarget(TOUR[tourStep]);
		if (tourSpot == null) {
			tourSpot = want.clone();
		}
		for (int i = 0; i < 4; i++) {
			tourSpot[i] += (want[i] - tourSpot[i]) * glide;
		}
		float sx = tourSpot[0], sy = tourSpot[1], sw = tourSpot[2], sh = tourSpot[3];
		boolean whole = TOUR[tourStep].anchor().equals("panel");

		// dim everything but the spotlight
		int dim = alpha(0xA0000000, a);
		int W = (int) Math.ceil(W()) + 1, H = (int) Math.ceil(H()) + 1;
		c.fill(0, 0, W, Math.round(sy), dim);
		c.fill(0, Math.round(sy + sh), W, H, dim);
		c.fill(0, Math.round(sy), Math.round(sx), Math.round(sy + sh), dim);
		c.fill(Math.round(sx + sw), Math.round(sy), W, Math.round(sy + sh), dim);
		// accent ring + a soft pulse growing out of it
		float ringR = whole ? net.veloclient.velo.client.gui.glass.GlassPrefs.radius(18f) : 9f;
		VeloDraw.strokeRounded(c, sx, sy, sw, sh, ringR, 1.5f, alpha(acc(), a * 0.95f));
		float p = ((System.nanoTime() - tourStartNanos) / 1_500_000_000f) % 1f;
		float grow = 2f + p * 9f;
		VeloDraw.strokeRounded(c, sx - grow, sy - grow, sw + 2f * grow, sh + 2f * grow, ringR + grow, 1.2f, alpha(acc(), a * 0.55f * (1f - p)));

		// text cross-fade between stops
		if (tourShown != tourStep) {
			tourText = approach(tourText, 0f, dt / 0.11f);
			if (tourText <= 0f) {
				tourShown = tourStep;
			}
		} else {
			tourText = approach(tourText, 1f, dt / 0.16f);
		}
		TourStop stop = TOUR[tourShown];
		float cw = 236f;
		List<Text> lines = wrap(stop.body(), cw - 28f, 6);
		float ch = 16f + 12f + 18f + lines.size() * 11f + 14f + 22f + 14f;
		// the card sits beside the spotlight (left of it, else right, else inside at the bottom)
		float cx = sx - cw - 18f;
		float cy = sy + sh / 2f - ch / 2f;
		if (TOUR[tourShown].anchor().equals("hud")) {
			cx = sx + (sw - cw) / 2f;
			cy = sy + sh - ch - 24f;
		} else if (cx < 10f) {
			cx = sx + sw + 18f;
			if (cx + cw > W() - 10f) {
				cx = sx + (sw - cw) / 2f;
				cy = sy + sh - ch - 16f;
			}
		}
		cy = Math.max(10f, Math.min(H() - ch - 10f, cy));
		float[] cardWant = {cx, cy, cw, ch};
		if (tourCard == null) {
			tourCard = cardWant.clone();
		}
		for (int i = 0; i < 4; i++) {
			tourCard[i] += (cardWant[i] - tourCard[i]) * glide;
		}
		float x = tourCard[0], y = tourCard[1], h = tourCard[3];
		float rise = (1f - a) * 10f;
		y += rise;
		VeloDraw.shadow(c, Math.round(x), Math.round(y), Math.round(cw), Math.round(h), 14, 16, 4, alpha(0x70000000, a));
		glass(c, x, y, cw, h, net.veloclient.velo.client.gui.glass.GlassPrefs.radius(14f), 0xF0161619, a);

		float ta = a * tourText;
		float ix = x + 14f;
		float iy = y + 14f;
		// step count + skip
		text(c, R((tourShown + 1) + " of " + TOUR.length), ix, iy, TEXT_3, ta);
		if (tourShown < TOUR.length - 1) {
			Text skip = R("Skip tour");
			float skw = tw(skip);
			float skx = x + cw - 14f - skw;
			boolean hover = inside(skx - 4f, iy - 4f, skw + 8f, 16f);
			text(c, skip, skx, iy, hover ? WHITE : TEXT_2, a);
			tourHits.add(new Hit(skx - 4f, iy - 4f, skw + 8f, 16f, () -> {
				endTour();
				net.veloclient.velo.client.sound.VeloUiSounds.back();
			}));
		}
		iy += 16f;
		// icon + title
		rect(c, ix, iy - 2f, 18f, 18f, 9f, alpha(acc(), 0.22f), ta);
		icon(c, stop.icon(), ix + 3f, iy + 1f, 12f, accIcon(), ta);
		text(c, B(stop.title()), ix + 25f, iy + 3f, WHITE, ta);
		iy += 26f;
		for (Text line : lines) {
			text(c, line, ix, iy, TEXT_2, ta);
			iy += 11f;
		}
		// dots + buttons along the bottom
		float by = y + h - 14f - 22f;
		float dx = ix;
		for (int i = 0; i < TOUR.length; i++) {
			boolean on = i == tourStep;
			float dw = animTo("tourDot" + i, on ? 12f : 5f, 14f);
			rect(c, dx, by + 8.5f, dw, 5f, 2.5f, on ? acc() : 0x40FFFFFF, a);
			dx += dw + 4f;
		}
		boolean last = tourStep == TOUR.length - 1;
		Text nextLabel = B(last ? "Done" : "Next");
		float nw = tw(nextLabel) + 26f;
		float nx = x + cw - 14f - nw;
		boolean nh = inside(nx, by, nw, 22f);
		rect(c, nx, by, nw, 22f, 11f, nh ? VeloAnim.lerpArgb(acc(), 0xFFFFFFFF, 0.15f) : acc(), a);
		text(c, nextLabel, nx + 13f, by + 7f, onAcc(), a);
		tourHits.add(new Hit(nx, by, nw, 22f, this::tourNext));
		if (tourStep > 0) {
			Text backLabel = R("Back");
			float bw = tw(backLabel) + 22f;
			float bx = nx - 6f - bw;
			boolean bh = inside(bx, by, bw, 22f);
			rect(c, bx, by, bw, 22f, 11f, bh ? 0x2EFFFFFF : 0x16FFFFFF, a);
			text(c, backLabel, bx + 11f, by + 7f, WHITE, a);
			tourHits.add(new Hit(bx, by, bw, 22f, this::tourBack));
		}
	}

	/** Every press in the menu sounds (2026-11-26): its own sound if it has one (toggle, page...), else a soft click. */
	private static void runWithSound(Runnable click) {
		click.run();
		if (!net.veloclient.velo.client.sound.VeloUiSounds.playedWithin(60)) {
			net.veloclient.velo.client.sound.VeloUiSounds.click(1f);
		}
	}

	private boolean overlayOpen() {
		return dropdown != null || profileMenu || moreMenu;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		frameCache.invalidate();
		if (closing || pending != null) {
			return true;
		}
		double u = click.x() / k;
		double v = click.y() / k;
		if (touring()) {
			for (int i = tourHits.size() - 1; i >= 0; i--) {
				if (tourHits.get(i).contains(u, v)) {
					tourHits.get(i).click.run();
					break;
				}
			}
			return true;   // the menu under the tour isn't clickable meanwhile
		}
		mouseU = u;
		mouseV = v;
		commitEditIfOutside();
		if (overlayOpen()) {
			for (int i = overlayHits.size() - 1; i >= 0; i--) {
				Hit h = overlayHits.get(i);
				if (h.contains(u, v)) {
					runWithSound(h.click);
					return true;
				}
			}
			dropdown = null;
			profileMenu = false;
			moreMenu = false;
			return true;
		}
		for (Bar bar : scrollbars) {
			if (bar.contains(u, v)) {
				draggingBar = bar;
				setScroll(bar.page(), bar.scrollAt(v));
				return true;
			}
		}
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (!h.contains(u, v)) {
				continue;
			}
			if (h.slide != null) {
				sliding = h;
				slideTo(u);
				return true;
			}
			if (h.grip != null) {
				ghost = h.grip;
				ghostMoved = false;
				return true;
			}
			if (h == glassHit && v >= bodyTop && v < bodyBottom) {
				touchPage = page();
				touchStartV = v;
				touchLastV = v;
				touchLastNanos = System.nanoTime();
				touchVelocity = 0f;
				touchStartScroll = scroll.getOrDefault(touchPage, 0f);
				return true;
			}
			if (h.click != null && h != glassHit) {
				runWithSound(h.click);
			}
			return true;
		}
		if (expanded) {
			return true;
		}
		// the HUD (gui pixels)
		int mx = (int) click.x();
		int my = (int) click.y();
		List<HudModule> huds = hudModules();
		for (int i = huds.size() - 1; i >= 0; i--) {
			HudModule hud = huds.get(i);
			int w = sw(hud);
			int h = sh(hud);
			int x = hud.position().resolveX(this.width, w);
			int y = hud.position().resolveY(this.height, h);
			if ((hud == selected || hud == resizing) && Math.abs(mx - (x + w)) <= 7 && Math.abs(my - (y + h)) <= 7) {
				resizing = hud;
				resizeGrabX = x + w - mx;
				resizeGrabY = y + h - my;
				dragMoved = false;
				return true;
			}
			if (mx >= x && mx <= x + w && my >= y && my <= y + h) {
				dragging = hud;
				dragOffsetX = mx - x;
				dragOffsetY = my - y;
				dragMoved = false;
				selected = hud;
				return true;
			}
		}
		selected = null;
		return velo$superMouseClicked(click, doubled);
	}

	private void slideTo(double u) {
		float[] track = sliderTrack.get(sliding);
		if (track != null && sliding.slide != null) {
			sliding.slide.accept((u - track[0]) / track[1]);
		}
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.gui.Click click, double offsetX, double offsetY) {
		frameCache.invalidate();
		double u = click.x() / k;
		double v = click.y() / k;
		mouseU = u;
		mouseV = v;
		if (sliding != null) {
			slideTo(u);
			return true;
		}
		if (draggingBar != null) {
			setScroll(draggingBar.page(), draggingBar.scrollAt(v));
			return true;
		}
		if (touchPage != null) {
			long now = System.nanoTime();
			float secs = Math.max(0.001f, (now - touchLastNanos) / 1e9f);
			touchVelocity = touchVelocity * 0.6f + (float) ((v - touchLastV) / secs) * 0.4f;
			touchLastV = v;
			touchLastNanos = now;
			setScroll(touchPage, clampScroll(touchPage, touchStartScroll - (float) (v - touchStartV)));
			return true;
		}
		if (ghost != null) {
			ghostMoved = true;
			return true;
		}
		if (resizing != null) {
			int w = sw(resizing);
			int h = sh(resizing);
			int x = resizing.position().resolveX(this.width, w);
			int y = resizing.position().resolveY(this.height, h);
			float fromW = resizing.width() > 0 ? ((float) click.x() + resizeGrabX - x) / resizing.width() : 1f;
			float fromH = resizing.height() > 0 ? ((float) click.y() + resizeGrabY - y) / resizing.height() : 1f;
			resizing.position().setScale(Math.max(0.3f, Math.min(4f, Math.max(fromW, fromH))));
			dragMoved = true;
			return true;
		}
		if (dragging != null) {
			int w = sw(dragging);
			int h = sh(dragging);
			int nx = (int) click.x() - dragOffsetX;
			int ny = (int) click.y() - dragOffsetY;
			dragging.position().set(this.width > w ? nx / (float) (this.width - w) : 0f, this.height > h ? ny / (float) (this.height - h) : 0f);
			dragging.position().setPlaced(true);
			dragMoved = true;
			return true;
		}
		return velo$superMouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.gui.Click click) {
		frameCache.invalidate();
		if (draggingBar != null) {
			draggingBar = null;
			return true;
		}
		if (touchPage != null) {
			// a flick keeps going a little, then glides to a stop
			boolean recent = System.nanoTime() - touchLastNanos < 80_000_000L;
			float fling = recent ? -touchVelocity * 0.18f : 0f;
			scrollTarget.put(touchPage, clampScroll(touchPage, scroll.getOrDefault(touchPage, 0f) + fling));
			touchPage = null;
			return true;
		}
		if (sliding != null) {
			sliding = null;
			VeloProfileStore.saveActive();
			return true;
		}
		if (ghost != null) {
			Module m = ghost;
			boolean moved = ghostMoved;
			ghost = null;
			ghostMoved = false;
			if (moved && !overDrawer(click.x() / k, click.y() / k) && m instanceof HudModule hud) {
				if (!hud.isEnabled()) {
					QuickToggles.toggle(hud);
				}
				int w = Math.max(20, sw(hud));
				int h = Math.max(8, sh(hud));
				int nx = (int) click.x() - w / 2;
				int ny = (int) click.y() - h / 2;
				hud.position().set(this.width > w ? Math.max(0, nx) / (float) (this.width - w) : 0f, this.height > h ? Math.max(0, ny) / (float) (this.height - h) : 0f);
				hud.position().setPlaced(true);
				selected = hud;
				VeloProfileStore.saveActive();
			} else if (!moved) {
				go(new Page(Kind.MODULE, catOf(m), m));
			}
			return true;
		}
		if (dragging != null || resizing != null) {
			HudModule hud = dragging != null ? dragging : resizing;
			boolean moved = dragMoved;
			dragging = null;
			resizing = null;
			dragMoved = false;
			if (!moved) {
				// a click, not a drag: its settings in the drawer
				open = true;
				Page top = page();
				if (top.kind() != Kind.MODULE || top.module() != hud) {
					go(new Page(Kind.MODULE, catOf(hud), hud));
				}
			} else {
				VeloProfileStore.saveActive();
			}
			return true;
		}
		return velo$superMouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		frameCache.invalidate();
		if (touring()) {
			return true;
		}
		if (overDrawer(mouseX / k, mouseY / k)) {
			Page p = page();
			// clamped right here to the page's length (last frame): scrolling past the end used to draw one frame too far and jump back
			float max = scrollMax.getOrDefault(p, Float.MAX_VALUE);
			float from = scrollTarget.getOrDefault(p, scroll.getOrDefault(p, 0f));
			scrollTarget.put(p, Math.max(0f, Math.min(max, from - (float) verticalAmount * 40f)));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private void commitEditIfOutside() {
		// clicks elsewhere end text editing (the field's own click re-opens it)
		commitEdit();
	}

	private void commitEdit() {
		if (editing != null) {
			editing.set().accept(editBuffer.toString());
			editing = null;
			VeloProfileStore.saveActive();
		}
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		frameCache.invalidate();
		int key = input.key();
		if (closing) {
			return true;
		}
		if (touring() && key != net.veloclient.velo.client.keybind.VeloKeybinds.menuKeyCode()) {
			if (key == Keys.ESCAPE) {
				endTour();
			} else if (key == Keys.ENTER || key == Keys.KP_ENTER || key == Keys.SPACE || key == Keys.RIGHT) {
				tourNext();
			} else if (key == Keys.LEFT) {
				tourBack();
			}
			return true;
		}
		// the menu key (R-Shift) closes it again - screens never pass it to the key binding
		boolean printable = key >= 32 && key <= 96;
		if (key == net.veloclient.velo.client.keybind.VeloKeybinds.menuKeyCode() && listening == null && listeningChord == null && editing == null
				&& !(printable && !search.isEmpty())) {
			if (!net.veloclient.velo.client.keybind.VeloKeybinds.waitingForRelease()) {
				net.veloclient.velo.client.keybind.VeloKeybinds.markToggled();
				closeToGame();
			}
			return true;
		}
		if (listening != null) {
			ConfigField.KeybindField f = listening;
			listening = null;
			swallowCharsUntil = System.nanoTime() + 250_000_000L;
			f.onKeyCodeChosen().accept(key == Keys.ESCAPE ? Keys.UNKNOWN : key);
			VeloProfileStore.saveActive();
			return true;
		}
		if (listeningChord != null) {
			if (key == Keys.ESCAPE) {
				listeningChord.set().accept(List.of());
				listeningChord = null;
				chordHeld.clear();
				chordMax.clear();
				return true;
			}
			chordHeld.add(key);
			chordMax.add(key);
			return true;
		}
		if (editing != null) {
			if (key == Keys.ESCAPE) {
				editing = null;
			} else if (key == Keys.ENTER || key == Keys.KP_ENTER) {
				commitEdit();
			} else if (key == Keys.BACKSPACE && editBuffer.length() > 0) {
				editBuffer.setLength(editBuffer.length() - 1);
			}
			return true;
		}
		if (key == Keys.ESCAPE) {
			// Esc steps back through the menu (module -> category -> home), and closes only from the start;
			// the menu key (R-Shift) closes right away from anywhere
			if (overlayOpen()) {
				dropdown = null;
				profileMenu = false;
				moreMenu = false;
			} else if (stack.size() > 1) {
				search.setLength(0);
				back();
			} else {
				closeToGame();
			}
			return true;
		}
		if (key == Keys.BACKSPACE && !search.isEmpty()) {
			search.setLength(search.length() - 1);
			return true;
		}
		return velo$superKeyPressed(input);
	}

	@Override
	public boolean keyReleased(net.minecraft.client.input.KeyInput input) {
		frameCache.invalidate();
		if (listeningChord != null) {
			swallowCharsUntil = System.nanoTime() + 250_000_000L;
			chordHeld.remove(input.key());
			if (chordHeld.isEmpty() && !chordMax.isEmpty()) {
				listeningChord.set().accept(List.copyOf(chordMax));
				listeningChord = null;
				chordMax.clear();
				VeloProfileStore.saveActive();
			}
			return true;
		}
		return velo$superKeyReleased(input);
	}

	/** Typing goes into the open text field, otherwise into the search (and jumps to it). */
	private boolean typed(int codepoint) {
		frameCache.invalidate();
		if (closing || touring()) {
			return true;
		}
		if (listening != null || listeningChord != null || System.nanoTime() < swallowCharsUntil || codepoint < 32 || codepoint == 127) {
			return listening != null || listeningChord != null;
		}
		String s = new String(Character.toChars(codepoint));
		if (editing != null) {
			editBuffer.append(s);
			return true;
		}
		if (search.isEmpty() && s.isBlank()) {
			return true;
		}
		search.append(s);
		if (page().kind() != Kind.HOME) {
			fromPage = page();
			stack.subList(1, stack.size()).clear();
			trans = 0f;
			transDir = -1;
		}
		open = true;
		setScroll(page(), 0f);
		return true;
	}

	// (1.21.1 types through charTyped(char, int) at the bottom)
	//? if >=1.21.2 && <26.1 {
	@Override
	public boolean charTyped(net.minecraft.client.input.CharInput input) {
		return typed(input.codepoint()) || super.charTyped(input);
	}
	//?} else if >=26.1 {
	/*@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent input) {
		return typed(input.codepoint()) || super.charTyped(input);
	}
	*///?}

	@Override
	public void close() {
		closeToGame();
	}

	/** Saves everything and goes back to the game (also what R-Shift does while this is open). */
	public void closeToGame() {
		if (tourStep >= 0) {
			DrawerPrefs.setToured(true);   // closed mid-tour: it has been seen
		}
		if (closing) {
			return;
		}
		commitEdit();
		VeloProfileStore.saveActive();
		closing = true;
		closingSince = System.nanoTime();
		net.veloclient.velo.client.sound.VeloUiSounds.menuClose();
		open = false;
		dropdown = null;
		profileMenu = false;
		moreMenu = false;
		ghost = null;
		dragging = null;
		resizing = null;
		sliding = null;
	}

	// ---- 1.21.1: vanilla input still comes as numbers; the screen uses 1.21.9's event objects ----
	private boolean velo$superKeyPressed(net.minecraft.client.input.KeyInput c) {
		//? if <1.21.2 {
		/*return super.keyPressed(c.key(), c.scancode(), c.modifiers());
		*///?} else {
		return super.keyPressed(c);
		//?}
	}

	private boolean velo$superKeyReleased(net.minecraft.client.input.KeyInput c) {
		//? if <1.21.2 {
		/*return super.keyReleased(c.key(), c.scancode(), c.modifiers());
		*///?} else {
		return super.keyReleased(c);
		//?}
	}

	private boolean velo$superMouseClicked(net.minecraft.client.gui.Click c, boolean d) {
		//? if <1.21.2 {
		/*return super.mouseClicked(c.x(), c.y(), c.button());
		*///?} else {
		return super.mouseClicked(c, d);
		//?}
	}

	private boolean velo$superMouseDragged(net.minecraft.client.gui.Click c, double dx, double dy) {
		//? if <1.21.2 {
		/*return super.mouseDragged(c.x(), c.y(), c.button(), dx, dy);
		*///?} else {
		return super.mouseDragged(c, dx, dy);
		//?}
	}

	private boolean velo$superMouseReleased(net.minecraft.client.gui.Click c) {
		//? if <1.21.2 {
		/*return super.mouseReleased(c.x(), c.y(), c.button());
		*///?} else {
		return super.mouseReleased(c);
		//?}
	}

	//? if <1.21.2 {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return mouseClicked(new net.veloclient.velo.client.compat.Click(x, y, button), net.veloclient.velo.client.compat.Click.doubled(x, y, button));
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return mouseReleased(new net.veloclient.velo.client.compat.Click(x, y, button));
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return mouseDragged(new net.veloclient.velo.client.compat.Click(x, y, button), dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return keyPressed(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}

	@Override
	public boolean keyReleased(int key, int scancode, int modifiers) {
		return keyReleased(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		return typed(chr) || super.charTyped(chr, modifiers);
	}
	*///?}
}
