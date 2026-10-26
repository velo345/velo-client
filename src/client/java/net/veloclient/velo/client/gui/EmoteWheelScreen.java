package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.emotes.EmoteCatalog;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.modules.cosmetics.EmotesModule;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.client.util.Keys;

/**
 * The emote wheel: eight slots in a ring around the cursor. Point at one and let go of the emote key (hold style),
 * or click it, or press its number 1-8. Tapping the key leaves the wheel open; tapping it again or Escape closes it.
 * The game keeps running behind it.
 */
public final class EmoteWheelScreen extends Screen {

	private static final float INNER = 34f;
	private static final float OUTER = 98f;
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

	private int slotAt(double mouseX, double mouseY) {
		double dx = mouseX - this.width / 2.0;
		double dy = mouseY - this.height / 2.0;
		double dist = Math.sqrt(dx * dx + dy * dy);
		if (dist < INNER * 0.6) {
			return -1;
		}
		// 0 degrees = straight up, clockwise - the same as VeloDraw's arcs; slot 0 is centred at the top
		double deg = Math.toDegrees(Math.atan2(dx, -dy));
		deg = (deg + 360 + 180.0 / EmotesModule.SLOTS) % 360;
		int slot = (int) (deg / (360.0 / EmotesModule.SLOTS));
		return module.slot(slot) == null ? -1 : slot;
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		long now = System.nanoTime();
		float dt = lastNanos < 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		hovered = slotAt(mouseX, mouseY);
		float open = Math.min(1f, (now - openedAt) / 120_000_000f);
		float ease = 1f - (1f - open) * (1f - open);
		float cx = this.width / 2f;
		float cy = this.height / 2f;
		int accent = ThemeManager.active().accentStart() | 0xFF000000;

		context.fill(0, 0, this.width, this.height, VeloUi.withAlpha(0xFF000000, Math.round(0x40 * ease)));
		float scale = 0.85f + 0.15f * ease;
		float inner = INNER * scale;
		float outer = OUTER * scale;
		float sweep = 360f / EmotesModule.SLOTS;
		// soft shadow ring, then the slots
		VeloDraw.strokeArc(context, cx, cy, (inner + outer) / 2f, outer - inner + 10f, 0f, 360f, VeloUi.withAlpha(0xFF000000, Math.round(0x50 * ease)));
		for (int i = 0; i < EmotesModule.SLOTS; i++) {
			hoverAnim[i] += ((i == hovered ? 1f : 0f) - hoverAnim[i]) * Math.min(1f, dt * 14f);
			boolean empty = module.slot(i) == null;
			float grow = hoverAnim[i] * 6f;
			int base = empty ? 0xA0141418 : 0xC81B1B22;
			int fill = mix(base, VeloUi.withAlpha(accent, 0xE0), hoverAnim[i]);
			float start = i * sweep - sweep / 2f + 1.2f;
			VeloDraw.strokeArc(context, cx, cy, (inner + outer + grow) / 2f, outer - inner + grow, start, sweep - 2.4f,
					VeloUi.withAlpha(fill, Math.round(((fill >>> 24) & 0xFF) * ease)));
		}
		// labels: the number and the emote's name in the middle of each slot
		for (int i = 0; i < EmotesModule.SLOTS; i++) {
			double angle = Math.toRadians(i * sweep - 90);
			float r = (inner + outer) / 2f + hoverAnim[i] * 3f;
			int lx = Math.round(cx + (float) Math.cos(angle) * r);
			int ly = Math.round(cy + (float) Math.sin(angle) * r);
			String id = module.slot(i);
			int alpha = Math.round(255 * ease);
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(String.valueOf(i + 1)), lx, ly - 10,
					VeloUi.withAlpha(i == hovered ? 0xFFFFFFFF : 0xFF9A9AA6, alpha));
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(id == null ? "-" : EmoteCatalog.name(id)), lx, ly + 1,
					VeloUi.withAlpha(id == null ? 0xFF5E5E68 : 0xFFFFFFFF, alpha));
		}
		// centre: the hovered emote, playing small - or the hint
		VeloDraw.fillCircle(context, cx, cy, inner - 6f, VeloUi.withAlpha(0xFF101014, Math.round(0xD0 * ease)));
		if (hovered >= 0) {
			String id = module.slot(hovered);
			context.enableScissor(Math.round(cx - inner + 6), Math.round(cy - inner + 6), Math.round(cx + inner - 6), Math.round(cy + inner - 6));
			net.veloclient.velo.client.gui.widget.EmoteFigure.draw(context, centreFigure, id, cx, cy + inner - 12f, (inner * 2 - 22f) / 34f,
					EmoteCatalog.accent(id));
			context.disableScissor();
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(EmoteCatalog.name(id)), Math.round(cx), Math.round(cy + outer + 10),
					VeloUi.withAlpha(accent, Math.round(255 * ease)));
		} else {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Emotes"), Math.round(cx), Math.round(cy) - 4,
					VeloUi.withAlpha(0xFFD8D8E0, Math.round(255 * ease)));
		}
		if (empty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No emotes yet - click here to get some in the Store"),
					Math.round(cx), Math.round(cy + outer + 24), VeloUi.withAlpha(0xFFFFD24A, Math.round(255 * ease)));
		}
		super.render(context, mouseX, mouseY, delta);
	}

	private final Object centreFigure = new Object();

	private boolean empty() {
		for (int i = 0; i < EmotesModule.SLOTS; i++) {
			if (module.slot(i) != null) {
				return false;
			}
		}
		return true;
	}

	private static int mix(int a, int b, float t) {
		int out = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			int ca = (a >>> shift) & 0xFF;
			int cb = (b >>> shift) & 0xFF;
			out |= Math.round(ca + (cb - ca) * t) << shift;
		}
		return out;
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
			// Holding the key sends repeat presses - only a new press after a release closes the wheel.
			if (!wheelKeyDown && System.nanoTime() - openedAt > TAP_NANOS) {
				this.close();
			}
			wheelKeyDown = true;
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean keyReleased(net.minecraft.client.input.KeyInput input) {
		if (input.key() == wheelKey) {
			wheelKeyDown = false;
			if (hovered >= 0) {
				play(hovered);
				return true;
			}
			if (System.nanoTime() - openedAt > TAP_NANOS) {
				// held and let go over the centre: nothing chosen
				this.close();
				return true;
			}
		}
		return super.keyReleased(input);
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
		return super.mouseClicked(click, doubled);
	}
}
