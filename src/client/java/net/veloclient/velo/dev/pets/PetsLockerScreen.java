package net.veloclient.velo.dev.pets;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.CapeEquipScreen;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.ArrayList;
import java.util.List;

/**
 * Locker > Pets (dev build): pick the pet that follows you. The pick is saved where the dev launcher's Locker reads it
 * and goes into the look you have on, like every other slot. Its settings (size, tricks, hit reactions) are the Pets
 * module's.
 */
public final class PetsLockerScreen extends VeloWindow {

	private static final int TILE = 64;
	private static final int TILE_GAP = 8;

	/** This tab's index in the Locker (set when it registers). */
	static int tabIndex = -1;

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	/** The pet on the stage ("" = none). */
	private String selected;
	private String status = "";
	private double scroll;

	public PetsLockerScreen(Screen parent) {
		super(Text.literal("Locker"), 940, 580);
		returnTo(parent);
		selected = PetsModule.pet();
	}

	/** Adds the Pets tab to the Locker. */
	public static void register() {
		// Pets in the Store: their own tab, every card a turntable preview.
		net.veloclient.velo.client.gui.store.StoreScreen.registerPage("Pets", "NEW", (store, w, h) -> {
			java.util.List<net.minecraft.client.gui.widget.ClickableWidget> cards = new ArrayList<>();
			for (var item : PetStore.shown()) {
				cards.add(new VeloPetCard(w, h, item, () -> MinecraftClient.getInstance().setScreen(new PetDetailScreen(store, item))));
			}
			return cards;
		});
		tabIndex = CapeEquipScreen.registerTab(new CapeEquipScreen.ExtraTab("Pets", "PET", "paw",
				() -> PetsModule.pet().isEmpty() ? "None" : PetsModule.PETS.getOrDefault(PetsModule.pet(), "None"), PetsLockerScreen::new));
	}

	@Override
	protected String[] headerTabs() {
		return CapeEquipScreen.LOCKER_TABS;
	}

	@Override
	protected int headerTab() {
		return tabIndex;
	}

	@Override
	protected void selectHeaderTab(int index) {
		if (index != tabIndex) {
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
	}

	private static Identifier thumb(String id) {
		return Identifier.of("velo-client", "textures/pets/thumb/" + id + ".png");
	}

	private static String name(String id) {
		return id.isEmpty() ? "None" : PetsModule.PETS.getOrDefault(id, id);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		CapeEquipScreen.drawSlots(context, this.textRenderer, contentX(), contentY(), tabIndex, mouseX, mouseY);
		CapeEquipScreen.drawLoadouts(context, this.textRenderer, contentX(), contentY(), contentBottom(), mouseX, mouseY);
		renderStage(context);

		int px = panelX();
		int pw = contentX() + contentWidth() - px;
		int y = contentY();
		String equipped = PetsModule.pet();
		boolean isEquipped = selected.equals(equipped);
		boolean owned = selected.isEmpty() || PetsModule.owns(selected);
		MenuKit.text(context, this.textRenderer, MenuKit.small("PET"), px, y, MenuKit.TEXT_3);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(name(selected)), pw), px, y + 11, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(selected.isEmpty() ? "No pet follows you" : owned ? "Follows you around" : "In the Store"), pw),
				px, y + 23, MenuKit.TEXT_2);
		String action = isEquipped ? (selected.isEmpty() ? "No pet" : "Put it away") : selected.isEmpty() ? "No pet" : owned ? "Take this one" : "See it in the Store";
		button(context, px, y + 40, 112, action, mouseX, mouseY, () -> {
			if (!owned) {
				PetStore.item(selected).ifPresent(item -> MinecraftClient.getInstance().setScreen(new PetDetailScreen(this, item)));
				return;
			}
			if (isEquipped) {
				PetsModule.setPet("");
				status = "Pet put away";
			} else {
				PetsModule.setPet(selected);
				status = selected.isEmpty() ? "Pet put away" : name(selected) + " follows you now";
			}
		});
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui("Size, tricks and hit reactions: the Pets module."), pw),
				px, y + 66, MenuKit.TEXT_3);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), pw), px, y + 80, MenuKit.TEXT_2);
		renderGrid(context, px, pw, y + 100, mouseX, mouseY);
	}

	private void renderStage(DrawContext context) {
		int x = stageX();
		int y = contentY();
		int w = CapeEquipScreen.SHOWCASE_WIDTH;
		int h = contentBottom() - y;
		VeloDraw.fillRounded(context, x, y, w, h, 10, 0xFF0E0E14);
		VeloDraw.strokeRounded(context, x, y, w, h, 10, 0x17FFFFFF);
		if (selected.isEmpty()) {
			String line = "No pet";
			String hint = "Pick one on the right";
			MenuKit.icon(context, "paw", x + w / 2 - 12, y + h / 2 - 30, 24, MenuKit.TEXT_3);
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(line), x + (w - this.textRenderer.getWidth(MenuKit.uiBold(line))) / 2, y + h / 2 + 2, MenuKit.TEXT_2);
			MenuKit.text(context, this.textRenderer, MenuKit.ui(hint), x + (w - this.textRenderer.getWidth(MenuKit.ui(hint))) / 2, y + h / 2 + 14, MenuKit.TEXT_3);
			return;
		}
		int size = Math.min(w - 20, h - 40);
		// The turntable once its sheet is in (the still picture until then).
		if (!PetPreviews.draw(context, selected, x + (w - size) / 2, y + (h - size) / 2 - 8, size)) {
			MenuKit.texture(context, thumb(selected), x + (w - size) / 2, y + (h - size) / 2 - 8, size, 256);
		}
		String label = name(selected);
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold(label), x + (w - this.textRenderer.getWidth(MenuKit.uiBold(label))) / 2, y + h - 22, MenuKit.TEXT);
	}

	private void renderGrid(DrawContext context, int px, int pw, int gy, int mouseX, int mouseY) {
		List<String> ids = new ArrayList<>();
		ids.add("");
		ids.addAll(PetsModule.PETS.keySet());
		int columns = Math.max(1, (pw + TILE_GAP) / (TILE + TILE_GAP));
		int rows = (ids.size() + columns - 1) / columns;
		int maxScroll = Math.max(0, rows * (TILE + 14 + TILE_GAP) - (contentBottom() - gy));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		context.enableScissor(px, gy, px + pw, contentBottom());
		for (int i = 0; i < ids.size(); i++) {
			int tx = px + (i % columns) * (TILE + TILE_GAP);
			int ty = gy + (i / columns) * (TILE + 14 + TILE_GAP) - (int) scroll;
			if (ty + TILE + 14 < gy || ty > contentBottom()) {
				continue;
			}
			drawTile(context, ids.get(i), tx, ty, mouseX, mouseY, gy);
		}
		context.disableScissor();
	}

	private void drawTile(DrawContext context, String id, int x, int y, int mouseX, int mouseY, int clipTop) {
		boolean hovered = mouseX >= x && mouseX < x + TILE && mouseY >= y && mouseY < y + TILE + 14 && mouseY >= clipTop;
		boolean active = id.equals(selected);
		boolean worn = id.equals(PetsModule.pet());
		int accent = MenuKit.accent();
		VeloDraw.fillRounded(context, x, y, TILE, TILE, 8, active ? VeloUi.withAlpha(accent, 0x40) : hovered ? 0xFF1E1E26 : 0xFF15151B);
		if (id.isEmpty()) {
			MenuKit.icon(context, "close", x + TILE / 2 - 9, y + TILE / 2 - 9, 18, MenuKit.TEXT_3);
		} else {
			MenuKit.texture(context, thumb(id), x + 2, y + 2, TILE - 4, 256);
		}
		VeloDraw.strokeRounded(context, x, y, TILE, TILE, 8, active ? VeloUi.withAlpha(accent, 0xC0) : 0x17FFFFFF);
		if (worn) {
			VeloDraw.fillCircle(context, x + 8f, y + 8f, 3f, MenuKit.GREEN);
		}
		if (!id.isEmpty() && !PetsModule.owns(id)) {
			// Not yours yet: dimmed, with what it costs.
			VeloDraw.fillRounded(context, x, y, TILE, TILE, 8, 0x8C0B0B10);
			int price = PetStore.item(id).map(PetStore::price).orElse(0);
			String cost = price > 0 ? net.veloclient.velo.client.gui.widget.VeloUi.trim(String.format(java.util.Locale.ROOT, "%,d", price), TILE) : "Store";
			int cw = this.textRenderer.getWidth(cost) + 8;
			VeloDraw.fillRounded(context, x + (TILE - cw) / 2, y + TILE - 14, cw, 11, 4, 0xE0101016);
			context.drawTextWithShadow(this.textRenderer, cost, x + (TILE - cw) / 2 + 4, y + TILE - 12, 0xFFF4C542);
		}
		String label = MenuKit.fit(this.textRenderer, MenuKit.ui(name(id)), TILE).getString();
		context.drawTextWithShadow(this.textRenderer, label, x + (TILE - this.textRenderer.getWidth(label)) / 2, y + TILE + 3, 0xFFE8E8F0);
		int top = Math.max(y, clipTop);
		hits.add(new Hit(x, top, TILE, Math.max(0, y + TILE + 14 - top), () -> selected = id));
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
