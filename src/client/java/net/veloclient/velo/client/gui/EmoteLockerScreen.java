package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.emotes.EmoteCatalog;
import net.veloclient.velo.client.emotes.Emotes;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.EmoteFigure;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.ArrayList;
import java.util.List;

/**
 * Locker > Emotes: your emote wheel. Pick a slot on the wheel (1-8), then click one of your emotes to put it there -
 * it plays on you in 3D (with its sounds) whenever you pick one. Emotes you don't own yet are shown dimmed and open
 * their Store page. The wheel is what holding B in game offers.
 */
public final class EmoteLockerScreen extends VeloWindow {

	private static final int TILE = 64;
	private static final int TILE_GAP = 8;

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private int selectedSlot;
	private String previewing;
	private String status = "";
	private double scroll;

	public EmoteLockerScreen(Screen parent) {
		super(Text.literal("Locker"), 940, 580);
		returnTo(parent);
		onClosed(() -> Emotes.setPreview(null));
		net.veloclient.velo.client.network.StoreClient.refresh(false, null);
		net.veloclient.velo.client.emotes.EmoteLibrary.prefetch(EmoteCatalog.owned());
	}

	@Override
	protected String[] headerTabs() {
		return CapeEquipScreen.LOCKER_TABS;
	}

	@Override
	protected int headerTab() {
		return CapeEquipScreen.EMOTES_TAB;
	}

	@Override
	protected void selectHeaderTab(int index) {
		if (index != CapeEquipScreen.EMOTES_TAB) {
			Emotes.setPreview(null);
			this.client.setScreen(CapeEquipScreen.open(returnScreen(), index));
		}
	}

	private int stageX() {
		return contentX() + CapeEquipScreen.SLOTS_W + 12;
	}

	private int panelX() {
		return stageX() + CapeEquipScreen.SHOWCASE_WIDTH + 14;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		addDrawableChild(new EntityPreviewWidget(stageX(), contentY(), CapeEquipScreen.SHOWCASE_WIDTH, contentBottom() - contentY()).spin(0f, 20f));
	}

	private void preview(String id) {
		previewing = id;
		Emotes.setPreview(id);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		CapeEquipScreen.drawSlots(context, this.textRenderer, contentX(), contentY(), CapeEquipScreen.EMOTES_TAB, mouseX, mouseY);
		CapeEquipScreen.drawLoadouts(context, this.textRenderer, contentX(), contentY(), contentBottom(), mouseX, mouseY);

		int px = panelX();
		int pw = contentX() + contentWidth() - px;
		int y = contentY();
		MenuKit.text(context, this.textRenderer, MenuKit.small("YOUR EMOTE WHEEL"), px, y, MenuKit.TEXT_3);
		MenuKit.text(context, this.textRenderer, MenuKit.ui("Hold B in game · pick a slot, then an emote"), px, y + 11, MenuKit.TEXT_2);
		drawWheel(context, px + 70, y + 98, mouseX, mouseY);

		// the selected slot's emote and what to do
		String inSlot = EmoteCatalog.slot(selectedSlot);
		int ix = px + 152;
		MenuKit.text(context, this.textRenderer, MenuKit.small("SLOT " + (selectedSlot + 1)), ix, y + 40, MenuKit.TEXT_3);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(inSlot == null ? "Empty" : EmoteCatalog.name(inSlot)), pw - 152),
				ix, y + 52, inSlot == null ? MenuKit.TEXT_2 : MenuKit.TEXT);
		if (inSlot != null) {
			button(context, ix, y + 68, 96, "Clear slot", mouseX, mouseY, () -> {
				EmoteCatalog.setSlot(selectedSlot, null);
				status = "Slot " + (selectedSlot + 1) + " cleared";
			});
		}
		button(context, ix, y + 92, 96, "Get more", mouseX, mouseY, () -> {
			Emotes.setPreview(null);
			net.veloclient.velo.client.gui.store.StoreScreen.showEmotes();
			this.client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(this));
		});
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), pw - 152), ix, y + 122, MenuKit.TEXT_2);

		// your emotes, then the ones to get
		int gy = y + 188;
		MenuKit.text(context, this.textRenderer, MenuKit.small("EMOTES"), px, gy - 14, MenuKit.TEXT_3);
		int columns = Math.max(1, (pw + TILE_GAP) / (TILE + TILE_GAP));
		List<EmoteCatalog.EmoteType> all = new ArrayList<>();
		for (var t : EmoteCatalog.catalog()) {
			if (EmoteCatalog.owns(t.id())) {
				all.add(t);
			}
		}
		for (var t : EmoteCatalog.catalog()) {
			if (!EmoteCatalog.owns(t.id()) && t.inStore()) {
				all.add(t);
			}
		}
		int rows = (all.size() + columns - 1) / columns;
		int visibleH = contentBottom() - gy;
		int maxScroll = Math.max(0, rows * (TILE + 14 + TILE_GAP) - visibleH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		context.enableScissor(px, gy, px + pw, contentBottom());
		for (int i = 0; i < all.size(); i++) {
			var t = all.get(i);
			int tx = px + (i % columns) * (TILE + TILE_GAP);
			int ty = gy + (i / columns) * (TILE + 14 + TILE_GAP) - (int) scroll;
			if (ty + TILE + 14 < gy || ty > contentBottom()) {
				continue;
			}
			drawTile(context, t, tx, ty, mouseX, mouseY, gy);
		}
		context.disableScissor();
		if (all.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("No emotes yet - get some in the Store."), px, gy + 4, MenuKit.TEXT_2);
		}
	}

	private void drawWheel(DrawContext context, int cx, int cy, int mouseX, int mouseY) {
		float inner = 22f;
		float outer = 62f;
		float sweep = 360f / EmoteCatalog.SLOTS;
		int accent = MenuKit.accent();
		double dx = mouseX - cx;
		double dy = mouseY - cy;
		double dist = Math.sqrt(dx * dx + dy * dy);
		int hover = -1;
		if (dist > inner && dist < outer + 4) {
			double deg = (Math.toDegrees(Math.atan2(dx, -dy)) + 360 + sweep / 2) % 360;
			hover = (int) (deg / sweep);
		}
		for (int i = 0; i < EmoteCatalog.SLOTS; i++) {
			int fill = i == selectedSlot ? VeloUi.withAlpha(accent, 0xD0) : i == hover ? 0xFF2A2A34 : 0xFF1A1A21;
			VeloDraw.strokeArc(context, cx, cy, (inner + outer) / 2f, outer - inner, i * sweep - sweep / 2f + 1.5f, sweep - 3f, fill);
			double a = Math.toRadians(i * sweep - 90);
			int lx = Math.round(cx + (float) Math.cos(a) * (inner + outer) / 2f);
			int ly = Math.round(cy + (float) Math.sin(a) * (inner + outer) / 2f);
			String id = EmoteCatalog.slot(i);
			String label = id == null ? String.valueOf(i + 1) : shortName(EmoteCatalog.name(id));
			int w = this.textRenderer.getWidth(label);
			context.drawTextWithShadow(this.textRenderer, label, lx - w / 2, ly - 4, id == null ? 0xFF6A6A76 : 0xFFFFFFFF);
		}
		VeloDraw.fillCircle(context, (float) cx, cy, inner - 3f, 0xFF101014);
		context.drawTextWithShadow(this.textRenderer, "B", cx - this.textRenderer.getWidth("B") / 2, cy - 4, accent);
		for (int i = 0; i < EmoteCatalog.SLOTS; i++) {
			int slot = i;
			double a = Math.toRadians(i * sweep - 90);
			int hx = Math.round(cx + (float) Math.cos(a) * (inner + outer) / 2f) - 16;
			int hy = Math.round(cy + (float) Math.sin(a) * (inner + outer) / 2f) - 12;
			hits.add(new Hit(hx, hy, 32, 24, () -> {
				selectedSlot = slot;
				String id = EmoteCatalog.slot(slot);
				if (id != null) {
					preview(id);
				}
			}));
		}
	}

	private static String shortName(String name) {
		return name.length() <= 7 ? name : name.substring(0, 6) + "…";
	}

	private void drawTile(DrawContext context, EmoteCatalog.EmoteType t, int x, int y, int mouseX, int mouseY, int clipTop) {
		boolean owned = EmoteCatalog.owns(t.id());
		boolean hovered = mouseX >= x && mouseX < x + TILE && mouseY >= y && mouseY < y + TILE + 14 && mouseY >= clipTop;
		boolean active = t.id().equals(previewing);
		int slot = EmoteCatalog.slotOf(t.id());
		VeloDraw.fillRounded(context, x, y, TILE, TILE, 8, active ? VeloUi.withAlpha(t.accent(), 0x40) : hovered ? 0xFF1E1E26 : 0xFF15151B);
		VeloDraw.strokeRounded(context, x, y, TILE, TILE, 8, active ? VeloUi.withAlpha(t.accent(), 0xC0) : 0x17FFFFFF);
		EmoteFigure.draw(context, t, t.id(), x + TILE / 2f, y + TILE - 5, (TILE - 12) / 34f, owned ? t.accent() : 0xFF55555F);
		if (slot >= 0 && owned) {
			VeloDraw.fillRounded(context, x + 3, y + 3, 12, 12, 4, VeloUi.withAlpha(MenuKit.accent(), 0xE0));
			context.drawTextWithShadow(this.textRenderer, String.valueOf(slot + 1), x + 6, y + 5, 0xFFFFFFFF);
		}
		if (!owned) {
			VeloDraw.fillRounded(context, x, y, TILE, TILE, 8, 0x66000000);
			context.drawTextWithShadow(this.textRenderer, "Store", x + TILE - 4 - this.textRenderer.getWidth("Store"), y + 4, 0xFFFFD24A);
		}
		String name = MenuKit.fit(this.textRenderer, MenuKit.ui(t.name()), TILE).getString();
		context.drawTextWithShadow(this.textRenderer, name, x + (TILE - this.textRenderer.getWidth(name)) / 2, y + TILE + 3,
				owned ? 0xFFE8E8F0 : 0xFF8A8A95);
		int top = Math.max(y, clipTop);
		hits.add(new Hit(x, top, TILE, Math.max(0, y + TILE + 14 - top), () -> {
			if (!owned) {
				Emotes.setPreview(null);
				this.client.setScreen(new net.veloclient.velo.client.gui.store.EmoteDetailScreen(this, t));
				return;
			}
			preview(t.id());
			EmoteCatalog.setSlot(selectedSlot, t.id());
			status = t.name() + " is on slot " + (selectedSlot + 1);
		}));
	}

	private void button(DrawContext context, int x, int y, int w, String label, int mouseX, int mouseY, Runnable action) {
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 18;
		VeloDraw.fillRounded(context, x, y, w, 18, 6, hovered ? 0x1AFFFFFF : 0x0DFFFFFF);
		VeloDraw.strokeRounded(context, x, y, w, 18, 6, hovered ? 0x38FFFFFF : 0x17FFFFFF);
		MenuKit.text(context, this.textRenderer, MenuKit.ui(label), x + (w - this.textRenderer.getWidth(MenuKit.ui(label))) / 2, y + 5,
				hovered ? MenuKit.TEXT : MenuKit.TEXT_2);
		hits.add(new Hit(x, y, w, 18, action));
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (CapeEquipScreen.loadoutClick(click.x(), click.y(), click.button(), contentX(), contentY(), contentBottom())) {
			return true;
		}
		int slot = CapeEquipScreen.slotAt((int) click.x(), (int) click.y(), contentX(), contentY());
		if (slot >= 0) {
			selectHeaderTab(slot);
			return true;
		}
		for (Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (mouseX >= panelX()) {
			scroll -= vertical * 24;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}
}
