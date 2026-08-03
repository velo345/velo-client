package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.crosshair.CrosshairDefinition;
import net.veloclient.velo.client.crosshair.CrosshairManager;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloCrosshairTile;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

/**
 * Crosshair library (design spec-style companion to the cape library):
 * click an icon to equip it, the pencil to edit it, "Delete" to remove
 * it, or "+ New" to create a blank one at a chosen canvas size. Hovering an
 * icon previews its hit state.
 *
 * <p>Tiles render through {@link CrosshairManager}'s own cached GPU
 * textures (see {@link VeloCrosshairTile}) - this screen doesn't load or
 * own any native image resources itself anymore, which is what a previous
 * version of it did (and crashed doing).
 */
public final class CrosshairSelectScreen extends VeloWindow {

	private static final int TILE_SIZE = 64;
	private static final int TILE_TOTAL_HEIGHT = TILE_SIZE + 20;
	private static final int TILE_GAP = 8;

	private VeloScrollRegion scrollRegion;
	private int gridColumns = 1;
	private boolean choosingSize;
	private Text status = Text.literal("");

	public CrosshairSelectScreen(Screen parent) {
		super(Text.literal("Crosshairs"), 420, 420);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		CrosshairManager.loadLibrary();

		if (choosingSize) {
			// Without this, the grid from the last non-size-chooser layout
			// stayed in scrollRegion (never reassigned, never cleared) and
			// render() drew it right underneath the size buttons - looked
			// like both screens overlaid on top of each other.
			scrollRegion = null;
			layoutSizeChooser();
			return;
		}

		int gridTop = contentY();
		int gridHeight = contentBottom() - gridTop;
		int listWidth = contentWidth();
		gridColumns = Math.max(1, (listWidth + TILE_GAP) / (TILE_SIZE + TILE_GAP));
		scrollRegion = new VeloScrollRegion(contentX(), gridTop, listWidth, gridHeight);

		String equippedId = CrosshairManager.equipped().map(CrosshairDefinition::id).orElse(null);
		for (CrosshairDefinition definition : CrosshairManager.library().values()) {
			VeloCrosshairTile tile = new VeloCrosshairTile(0, 0, TILE_SIZE, definition,
					definition.id().equals(equippedId),
					() -> this.client.setScreen(new CrosshairEditorScreen(this, definition)),
					() -> {
						CrosshairManager.equip(definition.id());
						status = Text.literal("Equipped \"" + definition.name() + "\"");
						layoutContent();
					},
					def -> {
						CrosshairManager.delete(def.id());
						status = Text.literal("Deleted \"" + def.name() + "\"");
						layoutContent();
					});
			addSelectableChild(tile);
			scrollRegion.addRow(tile);
		}

		VeloButton newTile = new VeloButton(0, 0, TILE_SIZE, TILE_TOTAL_HEIGHT - 4, Text.literal("+ New"),
				b -> { choosingSize = true; layoutContent(); });
		addSelectableChild(newTile);
		scrollRegion.addRow(newTile);

		scrollRegion.layoutGrid(gridColumns, TILE_SIZE, TILE_TOTAL_HEIGHT, TILE_GAP);
		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, contentWidth(), 20, Text.literal("Done"), b -> requestClose()));
	}

	private void layoutSizeChooser() {
		int y = contentY() + 30;
		int w = (contentWidth() - 3 * 8) / 4;
		int x = contentX();
		for (int size : new int[] {8, 16, 32, 64}) {
			int finalSize = size;
			addDrawableChild(new VeloButton(x, y, w, 24, Text.literal(size + "x" + size), b -> {
				CrosshairDefinition created = CrosshairManager.createBlank("New Crosshair", finalSize);
				choosingSize = false;
				this.client.setScreen(new CrosshairEditorScreen(this, created));
			}));
			x += w + 8;
		}
		addDrawableChild(new VeloButton(contentX(), y + 40, contentWidth(), 20, Text.literal("Cancel"),
				b -> { choosingSize = false; layoutContent(); }));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layoutGrid(gridColumns, TILE_SIZE, TILE_TOTAL_HEIGHT, TILE_GAP);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (scrollRegion != null) {
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbarGrid(context, gridColumns, TILE_TOTAL_HEIGHT, TILE_GAP);
		}
		Theme theme = ThemeManager.active();
		if (choosingSize) {
			context.drawTextWithShadow(this.textRenderer, "Choose a canvas size:", contentX(), contentY() + 10, theme.text());
		} else {
			context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 30, theme.text());
		}
	}
}
