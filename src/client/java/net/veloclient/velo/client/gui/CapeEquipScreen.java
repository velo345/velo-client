package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
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

	private static final int TILE_WIDTH = 86;
	private static final int TILE_ICON_HEIGHT = 112;
	private static final int TILE_TOTAL_HEIGHT = TILE_ICON_HEIGHT + 20;
	private static final int TILE_GAP = 8;
	private static final int SHOWCASE_WIDTH = 160;

	private VeloScrollRegion scrollRegion;
	private int gridColumns = 1;
	private Text status = Text.literal("");
	/** The cape shown on the player preview (not necessarily the equipped one). */
	private String selectedId;

	public CapeEquipScreen(net.minecraft.client.gui.screen.Screen parent) {
		super(Text.literal("Cosmetics"), 620, 420);
		returnTo(parent);
		onClosed(CapeManager::clearPreviewOverride);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		CapeManager.loadLibrary();
		String equippedId = CapeManager.equipped().map(CapeDefinition::id).orElse(null);
		if (selectedId == null || !CapeManager.library().containsKey(selectedId)) {
			selectedId = equippedId;
		}
		CapeDefinition selected = selectedId == null ? null : CapeManager.library().get(selectedId);
		// Preview the selected cape on your own player (cleared when the screen closes).
		if (selected != null && !selected.id().equals(equippedId)) {
			CapeManager.setPreviewOverride(selected);
		} else {
			CapeManager.clearPreviewOverride();
		}

		// Left: live preview + actions.
		int showcaseX = contentX();
		int actionsHeight = selected == null ? 0 : 20 + 6 + 20;
		int previewHeight = contentBottom() - contentY() - actionsHeight - (selected == null ? 0 : 22);
		addDrawableChild(new EntityPreviewWidget(showcaseX, contentY(), SHOWCASE_WIDTH, previewHeight));
		if (selected != null) {
			boolean isEquipped = selected.id().equals(equippedId);
			int buttonsY = contentBottom() - 46;
			VeloButton equip = new VeloButton(showcaseX, buttonsY, SHOWCASE_WIDTH, 20,
					Text.literal(isEquipped ? "Unequip" : "Equip"), b -> {
						if (isEquipped) {
							CapeManager.unequip();
							status = Text.literal("Unequipped \"" + selected.name() + "\"");
						} else {
							CapeManager.equip(selected.id());
							status = Text.literal("Equipped \"" + selected.name() + "\"");
						}
						layoutContent();
					});
			if (!isEquipped) {
				equip.primary();
			}
			addDrawableChild(equip);
			int half = (SHOWCASE_WIDTH - 6) / 2;
			addDrawableChild(new VeloButton(showcaseX, buttonsY + 26, half, 20, Text.literal("Edit"),
					b -> this.client.setScreen(new CapeEditScreen(this, selected))));
			addDrawableChild(new VeloButton(showcaseX + half + 6, buttonsY + 26, half, 20, Text.literal("Delete"),
					b -> confirmDelete(selected)));
		}

		// Right: the collection.
		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		int gridTop = contentY() + 16;
		int gridHeight = contentBottom() - gridTop - 14;
		gridColumns = Math.max(1, (listWidth - 8 + TILE_GAP) / (TILE_WIDTH + TILE_GAP));
		scrollRegion = new VeloScrollRegion(gridX, gridTop, listWidth, gridHeight);

		for (CapeDefinition definition : CapeManager.library().values()) {
			boolean equipped = definition.id().equals(equippedId);
			VeloCapeTile tile = new VeloCapeTile(0, 0, TILE_WIDTH, TILE_ICON_HEIGHT, definition, equipped,
					() -> this.client.setScreen(new CapeEditScreen(this, definition)),
					() -> {
						selectedId = definition.id();
						layoutContent();
					},
					this::confirmDelete).selected(definition.id().equals(selectedId));
			addSelectableChild(tile);
			scrollRegion.addRow(tile);
		}

		VeloButton importTile = new VeloButton(0, 0, TILE_WIDTH, TILE_TOTAL_HEIGHT - 4, Text.literal("+ Import"), b -> pickFile());
		addSelectableChild(importTile);
		scrollRegion.addRow(importTile);

		scrollRegion.layoutGrid(gridColumns, TILE_WIDTH, TILE_TOTAL_HEIGHT, TILE_GAP);
	}

	private void confirmDelete(CapeDefinition definition) {
		this.client.setScreen(new VeloConfirmScreen(this, "Delete cape",
				"Delete \"" + definition.name() + "\" from your cape library?", "Delete", () -> {
					CapeManager.delete(definition.id());
					status = Text.literal("Deleted \"" + definition.name() + "\"");
				}));
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
		int gridX = contentX() + SHOWCASE_WIDTH + 14;
		int count = CapeManager.library().size();
		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont("Capes"), gridX, contentY() + 2, VeloStyle.text());
		context.drawTextWithShadow(this.textRenderer, count + (count == 1 ? " cape" : " capes") + "  ·  click one to preview it on you",
				gridX + this.textRenderer.getWidth(TitleScreenTheme.tileFont("Capes")) + 6, contentY() + 2, VeloStyle.textFaint());
		CapeDefinition selected = selectedId == null ? null : CapeManager.library().get(selectedId);
		if (selected != null) {
			String name = VeloUi.trim(selected.name(), SHOWCASE_WIDTH);
			context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont(name), contentX(), contentBottom() - 62, VeloStyle.text());
		}
		context.drawTextWithShadow(this.textRenderer, status, gridX, contentBottom() - 9, VeloStyle.textMuted());
	}
}
