package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloCapeTile;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Cape library/equip menu (design spec section 6.5): a grid of real cape
 * previews (see {@link VeloCapeTile}) instead of a plain text list. Click a
 * preview to equip it, click the already-equipped one to unequip, the pencil
 * to rename/tune its physics ({@link CapeEditScreen}), "✕" to delete it, or
 * "+ Import" to add a new PNG straight from a native file picker - no
 * separate name field, the filename becomes the cape's name (rename it
 * afterward if you want something else).
 */
public final class CapeEquipScreen extends VeloWindow {

	private static final int TILE_WIDTH = 64;
	private static final int TILE_ICON_HEIGHT = 96;
	private static final int TILE_TOTAL_HEIGHT = TILE_ICON_HEIGHT + 20;
	private static final int TILE_GAP = 8;

	private VeloScrollRegion scrollRegion;
	private int gridColumns = 1;
	private Text status = Text.literal("");

	public CapeEquipScreen(net.minecraft.client.gui.screen.Screen parent) {
		super(Text.literal("Cape Library"), 420, 420);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		CapeManager.loadLibrary();

		int gridTop = contentY();
		// 20px for Done plus a real gap above it for the status line - it
		// used to sit at contentBottom()-10, which is inside the Done
		// button's own row (y from contentBottom()-20 to contentBottom()).
		int gridHeight = contentBottom() - gridTop - 36;
		int listWidth = contentWidth();
		gridColumns = Math.max(1, (listWidth + TILE_GAP) / (TILE_WIDTH + TILE_GAP));
		scrollRegion = new VeloScrollRegion(contentX(), gridTop, listWidth, gridHeight);

		String equippedId = CapeManager.equipped().map(CapeDefinition::id).orElse(null);
		for (CapeDefinition definition : CapeManager.library().values()) {
			boolean equipped = definition.id().equals(equippedId);
			VeloCapeTile tile = new VeloCapeTile(0, 0, TILE_WIDTH, TILE_ICON_HEIGHT, definition, equipped,
					() -> this.client.setScreen(new CapeEditScreen(this, definition)),
					() -> {
						boolean isEquipped = definition.id().equals(CapeManager.equipped().map(CapeDefinition::id).orElse(null));
						if (isEquipped) {
							CapeManager.unequip();
							status = Text.literal("Unequipped \"" + definition.name() + "\"");
						} else {
							CapeManager.equip(definition.id());
							status = Text.literal("Equipped \"" + definition.name() + "\"");
						}
						layoutContent();
					},
					def -> {
						CapeManager.delete(def.id());
						status = Text.literal("Deleted \"" + def.name() + "\"");
						layoutContent();
					});
			addSelectableChild(tile);
			scrollRegion.addRow(tile);
		}

		VeloButton importTile = new VeloButton(0, 0, TILE_WIDTH, TILE_TOTAL_HEIGHT - 4, Text.literal("+ Import"), b -> pickFile());
		addSelectableChild(importTile);
		scrollRegion.addRow(importTile);

		scrollRegion.layoutGrid(gridColumns, TILE_WIDTH, TILE_TOTAL_HEIGHT, TILE_GAP);
		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, contentWidth(), 20, Text.literal("Done"), b -> requestClose()));
	}

	private void pickFile() {
		status = Text.literal("Opening file picker...");
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				Path file = NativeFileDialog.pickPngFile("Choose a cape PNG");
				if (file == null) {
					MinecraftClient.getInstance().execute(() -> status = Text.literal(""));
					return;
				}
				String fileName = file.getFileName().toString();
				int dot = fileName.lastIndexOf('.');
				String name = dot > 0 ? fileName.substring(0, dot) : fileName;
				MinecraftClient.getInstance().execute(() -> {
					try {
						CapeManager.importCape(name, file, CapePhysicsPreset.defaults());
						status = Text.literal("Imported \"" + name + "\"");
						layoutContent();
					} catch (IOException e) {
						status = Text.literal("Import failed: " + e.getMessage());
					}
				});
			} catch (Throwable t) {
				net.veloclient.velo.VeloClient.LOGGER.error("Cape file picker failed to open", t);
				String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
				MinecraftClient.getInstance().execute(() -> status = Text.literal("File picker failed: " + message));
			}
		});
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layoutGrid(gridColumns, TILE_WIDTH, TILE_TOTAL_HEIGHT, TILE_GAP);
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
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 32, theme.text());
	}
}
