package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.emotes.EmoteCatalog;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.modules.cosmetics.EmotesModule;
import net.veloclient.velo.client.util.Keys;

/**
 * The emote wheel: eight slots in a ring around the cursor. Point at one and let go of the emote key (hold style),
 * or click it, or press its number 1-8. Tapping the key leaves the wheel open; tapping it again or Escape closes it.
 * The game keeps running behind it.
 */
public final class EmoteWheelScreen extends Screen {

	/** Released sooner than this after opening = a tap: the wheel stays open. */
	private static final long TAP_NANOS = 220_000_000L;

	private final EmotesModule module;
	private final int wheelKey;
	private final long openedAt = System.nanoTime();
	private int hovered = -1;
	private final float[] hoverAnim = new float[EmotesModule.SLOTS];
	private long lastNanos = -1;
	/** The wheel opens on a key press, so the key starts out held. */
	private boolean wheelKeyDown = true;

	public EmoteWheelScreen(EmotesModule module, int wheelKey) {
		super(Text.literal("Emotes"));
		this.module = module;
		this.wheelKey = wheelKey;
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	// The world stays visible: no blur or dimming from vanilla.
	//? if <26.1 {
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}
	//?} else {
	/*@Override
	public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
	}
	*///?}

	/** The disc: segments run from INNER to OUTER (gui px from the centre); GAP_DEG keeps them apart. */
	private static final float OUTER = 98f;
	private static final float INNER = 40f;
	private static final float GAP_DEG = 1.4f;
	/** Dev (screenshot tour): -Dvelo.tourWheelHover=<slot> shows that slot hovered. */
	private static final int TOUR_HOVER = Integer.getInteger("velo.tourWheelHover", -1);

	private int slotAt(double mouseX, double mouseY) {
		double dx = mouseX - this.width / 2.0;
		double dy = mouseY - this.height / 2.0;
		double dist = Math.sqrt(dx * dx + dy * dy);
		// the direction is enough (owner 2026-11-26): a small nudge from the centre picks that segment - on the wheel or
		// far outside it; only a tiny dead zone in the middle picks none
		if (dist < 10) {
			return -1;
		}
		// 0 degrees = straight up, clockwise; slot 0 is at the top
		double deg = Math.toDegrees(Math.atan2(dx, -dy));
		deg = (deg + 360 + 180.0 / EmotesModule.SLOTS) % 360;
		int slot = (int) (deg / (360.0 / EmotesModule.SLOTS));
		return module.slot(slot) == null ? -1 : slot;
	}

	/**
	 * Glass wheel (owner 2026-11-25: "no weird circles, no text spilling out"): one blurred glass disc cut into eight
	 * segments, each showing its emote moving; the hovered segment fills with the accent and its name sits in the hub.
	 * Accent / darkness: GlassPrefs.
	 */
	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		float dt = lastNanos < 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		hovered = TOUR_HOVER >= 0 && module.slot(TOUR_HOVER) != null ? TOUR_HOVER : slotAt(mouseX, mouseY);
		// a tap shorter than a tick: the release came before this screen did - the wheel is hold-only, so it closes
		if (TOUR_HOVER < 0 && wheelKey > 0 && now - openedAt > TAP_NANOS && !Keys.isKeyDown(wheelKey) && this.client != null
				&& net.veloclient.velo.client.util.ClientCompat.currentScreen() == this) {
			this.close();
		}
		float open = Math.min(1f, (now - openedAt) / 160_000_000f);
		float ease = 1f - (1f - open) * (1f - open) * (1f - open);
		float cx = this.width / 2f;
		float cy = this.height / 2f;
		int accent = net.veloclient.velo.client.gui.glass.GlassPrefs.accent() | 0xFF000000;
		int slots = EmotesModule.SLOTS;
		float scale = 0.86f + 0.14f * ease;
		float outer = OUTER * scale;
		float inner = INNER * scale;
		for (int i = 0; i < slots; i++) {
			hoverAnim[i] += ((i == hovered ? 1f : 0f) - hoverAnim[i]) * Math.min(1f, dt * 14f);
		}

		context.fill(0, 0, this.width, this.height, VeloUi.withAlpha(0xFF000000, Math.round(0x30 * ease)));
		// real blur behind the disc only (the game around it stays sharp)
		if (ease > 0.2f && net.veloclient.velo.client.gui.glass.SharpFrame.prepareBoth()) {
			int r = Math.round(outer + 4);
			java.util.List<int[]> rects = new java.util.ArrayList<>();
			rects.add(new int[] {Math.round(cx) - r, Math.round(cy) - r, r * 2, r * 2, r});
			net.veloclient.velo.client.gui.glass.SharpFrame.blurHere(context);
			net.veloclient.velo.client.gui.glass.SharpFrame.drawSharpExcept(context, this.width, this.height, rects);
		}
		int alpha = Math.round(255 * ease);
		int tint = net.veloclient.velo.client.gui.glass.GlassPrefs.tint(0xB0101014);
		int tintA = (tint >>> 24) & 0xFF;
		float sweep = 360f / slots;
		float mid = (inner + outer) / 2f;
		float thick = outer - inner;
		for (int i = 0; i < slots; i++) {
			String id = module.slot(i);
			float h = hoverAnim[i];
			float start = i * sweep - sweep / 2f + GAP_DEG;
			float arc = sweep - GAP_DEG * 2f;
			float grow = h * 4f;
			VeloDraw.strokeArc(context, cx, cy, mid + grow / 2f, thick + grow, start, arc,
					VeloUi.withAlpha(tint, Math.round(tintA * ease * (id == null ? 0.7f : 1f))));
			if (h > 0.01f) {
				VeloDraw.strokeArc(context, cx, cy, mid + grow / 2f, thick + grow, start, arc, VeloUi.withAlpha(accent, Math.round(0x50 * h * ease)));
				VeloDraw.strokeArc(context, cx, cy, outer + grow - 1f, 2f, start, arc, VeloUi.withAlpha(accent, Math.round(255 * h * ease)));
			}
			double angle = Math.toRadians(i * sweep - 90);
			float fx = cx + (float) Math.cos(angle) * (mid + grow / 2f);
			float fy = cy + (float) Math.sin(angle) * (mid + grow / 2f);
			if (id != null) {
				float figScale = (thick - 16f) / 34f * (1f + 0.08f * h);
				net.veloclient.velo.client.gui.widget.EmoteFigure.draw(context, slotFigures[i], id, fx, fy + 34f * figScale / 2f, figScale,
						EmoteCatalog.accent(id));
			} else {
				Text plus = Text.literal("+");
				net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, plus, Math.round(fx - this.textRenderer.getWidth(plus) / 2f),
						Math.round(fy - 4), VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x40 * ease)));
			}
		}
		// the hub: the hovered emote's name, or "Emotes"
		VeloDraw.fillCircle(context, cx, cy, inner - 3f, VeloUi.withAlpha(tint, Math.round(Math.min(255, tintA * 1.15f) * ease)));
		VeloDraw.strokeArc(context, cx, cy, inner - 3.5f, 1f, 0f, 360f, VeloUi.withAlpha(0xFFFFFFFF, Math.round(0x2E * ease)));
		String label = hovered >= 0 ? EmoteCatalog.name(module.slot(hovered)) : "Emotes";
		int maxW = Math.round((inner - 6f) * 2f);
		String shown = label;
		if (net.veloclient.velo.client.gui.title.MenuKit.width(this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.uiBold(shown)) > maxW) {
			while (shown.length() > 1 && net.veloclient.velo.client.gui.title.MenuKit.width(this.textRenderer, net.veloclient.velo.client.gui.title.MenuKit.uiBold(shown + "…")) > maxW) {
				shown = shown.substring(0, shown.length() - 1);
			}
			shown = shown.trim() + "…";
		}
		Text title = net.veloclient.velo.client.gui.title.MenuKit.uiBold(shown);
		net.veloclient.velo.client.gui.title.MenuKit.text(context, this.textRenderer, title, Math.round(cx - net.veloclient.velo.client.gui.title.MenuKit.width(this.textRenderer, title) / 2f),
				Math.round(cy) - 4, VeloUi.withAlpha(hovered >= 0 ? 0xFFFFFFFF : 0xFFEDEDF2, alpha));
		if (empty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No emotes yet - click here to get some in the Store"),
					Math.round(cx), Math.round(cy + outer + 12), VeloUi.withAlpha(0xFFFFD24A, alpha));
		}
		super.render(context, mouseX, mouseY, delta);
	}

	private final Object[] slotFigures = new Object[] {new Object(), new Object(), new Object(), new Object(), new Object(), new Object(), new Object(), new Object()};

	private boolean empty() {
		for (int i = 0; i < EmotesModule.SLOTS; i++) {
			if (module.slot(i) != null) {
				return false;
			}
		}
		return true;
	}

	private void play(int slot) {
		String id = slot >= 0 ? module.slot(slot) : null;
		this.close();
		if (id != null) {
			module.play(id);
		}
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		for (int n = 1; n <= EmotesModule.SLOTS; n++) {
			if (input.key() == Keys.digit(n)) {
				play(n - 1);
				return true;
			}
		}
		if (input.key() == wheelKey) {
			wheelKeyDown = true;   // held: the repeats keep it open
			return true;
		}
		return velo$superKeyPressed(input);
	}

	@Override
	public boolean keyReleased(net.minecraft.client.input.KeyInput input) {
		if (input.key() == wheelKey) {
			// hold to pick (owner 2026-11-26): letting go plays the emote you point at, or just closes the wheel
			wheelKeyDown = false;
			if (hovered >= 0) {
				play(hovered);
			} else {
				this.close();
			}
			return true;
		}
		return velo$superKeyReleased(input);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		int slot = slotAt(click.x(), click.y());
		if (slot >= 0) {
			play(slot);
			return true;
		}
		if (empty()) {
			net.veloclient.velo.client.gui.store.StoreScreen.showEmotes();
			this.client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(null));
			return true;
		}
		return velo$superMouseClicked(click, doubled);
	}

	// ---- 1.21.1: vanilla input still comes as numbers; the screens use 1.21.9's event objects ----
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

	//? if <1.21.2 {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return mouseClicked(new net.veloclient.velo.client.compat.Click(x, y, button), net.veloclient.velo.client.compat.Click.doubled(x, y, button));
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return keyPressed(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}

	@Override
	public boolean keyReleased(int key, int scancode, int modifiers) {
		return keyReleased(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}
	*///?}
}
