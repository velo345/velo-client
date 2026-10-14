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
	/** Showing the 3D wings instead of the capes. */
	private boolean wingsTab;
	/** The wings shown on the player preview ("" = none), null = the equipped pair. */
	private String selectedWings;
	/** Showing the auras. */
	private boolean aurasTab;
	/** The aura shown on the player preview ("" = none), null = the equipped one. */
	private String selectedAura;

	public CapeEquipScreen(net.minecraft.client.gui.screen.Screen parent) {
		super(Text.literal("Cosmetics"), 620, 420);
		returnTo(parent);
		onClosed(() -> {
			CapeManager.clearPreviewOverride();
			net.veloclient.velo.client.cosmetics.wings.WingsManager.clearPreview();
			net.veloclient.velo.client.cosmetics.aura.AuraManager.clearPreview();
		});
	}

	/** The Capes / Wings switch above the collection. */
	private void addTabs(int x) {
		VeloButton capes = new VeloButton(x, contentY() - 2, 54, 14, Text.literal("Capes"), b -> {
			wingsTab = false;
			aurasTab = false;
			net.veloclient.velo.client.cosmetics.wings.WingsManager.clearPreview();
			net.veloclient.velo.client.cosmetics.aura.AuraManager.clearPreview();
			layoutContent();
		});
		VeloButton wings = new VeloButton(x + 58, contentY() - 2, 54, 14, Text.literal("Wings"), b -> {
			wingsTab = true;
			aurasTab = false;
			net.veloclient.velo.client.cosmetics.aura.AuraManager.clearPreview();
			layoutContent();
		});
		VeloButton auras = new VeloButton(x + 116, contentY() - 2, 54, 14, Text.literal("Auras"), b -> {
			aurasTab = true;
			wingsTab = false;
			net.veloclient.velo.client.cosmetics.wings.WingsManager.clearPreview();
			layoutContent();
		});
		(aurasTab ? auras : wingsTab ? wings : capes).primary();
		addDrawableChild(capes);
		addDrawableChild(wings);
		addDrawableChild(auras);
		// Straight to the Store (wings and capes for sale).
		addDrawableChild(new VeloButton(contentX() + contentWidth() - 70, contentY() - 2, 70, 14, Text.literal("✦ Store"),
				b -> this.client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(this))));
	}

	/** The Auras tab: pick one (shown on you right away), equip it; Customize opens its settings. */
	private void layoutAuras() {
		String equipped = net.veloclient.velo.client.cosmetics.aura.AuraManager.equippedId();
		if (selectedAura == null) {
			selectedAura = equipped == null ? "" : equipped;
		}
		net.veloclient.velo.client.cosmetics.aura.AuraManager.setPreview(selectedAura.isEmpty() ? null : selectedAura);
		int showcaseX = contentX();
		addDrawableChild(new EntityPreviewWidget(showcaseX, contentY(), SHOWCASE_WIDTH, contentBottom() - contentY() - 30));
		boolean isEquipped = selectedAura.isEmpty() ? equipped == null : selectedAura.equals(equipped);
		boolean owned = selectedAura.isEmpty() || net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(selectedAura);
		VeloButton equip = new VeloButton(showcaseX, contentBottom() - 20, SHOWCASE_WIDTH, 20,
				Text.literal(selectedAura.isEmpty() ? (equipped == null ? "No aura" : "Take aura off") : isEquipped ? "Unequip"
						: owned ? "Equip" : "Get in the Store"), b -> {
					var type = net.veloclient.velo.client.cosmetics.aura.AuraManager.type(selectedAura);
					if (!owned) {
						type.ifPresent(t -> this.client.setScreen(new net.veloclient.velo.client.gui.store.AuraDetailScreen(this, t)));
						return;
					}
					if (selectedAura.isEmpty() || isEquipped) {
						net.veloclient.velo.client.cosmetics.aura.AuraManager.equip(null);
						selectedAura = "";
						status = Text.literal("Aura off");
					} else {
						net.veloclient.velo.client.cosmetics.aura.AuraManager.equip(selectedAura);
						status = Text.literal("Equipped " + type.map(net.veloclient.velo.client.cosmetics.aura.AuraManager.AuraType::name).orElse(selectedAura));
					}
					layoutContent();
				});
		if (!isEquipped && !selectedAura.isEmpty()) {
			equip.primary();
		}
		addDrawableChild(equip);

		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		addTabs(gridX);
		int y = contentY() + 18;
		int columns = 3;
		int tileW = (listWidth - (columns - 1) * 6) / columns;
		java.util.List<String[]> tiles = new java.util.ArrayList<>();
		tiles.add(new String[] {"", "None"});
		for (var type : net.veloclient.velo.client.cosmetics.aura.AuraManager.catalog()) {
			if (net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(type.id()) || type.inStore()) {
				tiles.add(new String[] {type.id(), type.name()});
			}
		}
		for (int i = 0; i < tiles.size(); i++) {
			String id = tiles.get(i)[0];
			String label = tiles.get(i)[1] + (id.equals(equipped == null ? "" : equipped) ? "  ✔"
					: !id.isEmpty() && !net.veloclient.velo.client.cosmetics.aura.AuraManager.owns(id) ? "  (Store)" : "");
			VeloButton tile = new VeloButton(gridX + (i % columns) * (tileW + 6), y + (i / columns) * 24, tileW, 20, Text.literal(label), b -> {
				selectedAura = id;
				layoutContent();
			});
			if (id.equals(selectedAura)) {
				tile.primary();
			}
			addDrawableChild(tile);
		}
		y += ((tiles.size() + columns - 1) / columns) * 24 + 10;
		if (!selectedAura.isEmpty()) {
			net.veloclient.velo.client.cosmetics.aura.AuraManager.type(selectedAura).ifPresent(type -> addDrawableChild(new VeloButton(gridX, contentY() + 18 + ((tiles.size() + 2) / 3) * 24 + 10,
					Math.min(200, listWidth), 20, Text.literal("Customize: size, speed, colour..."),
					b -> this.client.setScreen(new net.veloclient.velo.client.gui.store.AuraDetailScreen(this, type)))));
		}
	}

	/** The Wings tab: pick a pair (shown on you right away), equip it, and tune how you wear it. */
	private void layoutWings() {
		String equipped = net.veloclient.velo.client.cosmetics.wings.WingsManager.equippedId();
		if (selectedWings == null) {
			selectedWings = equipped == null ? "" : equipped;
		}
		net.veloclient.velo.client.cosmetics.wings.WingsManager.setPreview(selectedWings.isEmpty() ? null : selectedWings);
		int showcaseX = contentX();
		addDrawableChild(new EntityPreviewWidget(showcaseX, contentY(), SHOWCASE_WIDTH, contentBottom() - contentY() - 30));
		boolean isEquipped = selectedWings.isEmpty() ? equipped == null : selectedWings.equals(equipped);
		boolean owned = selectedWings.isEmpty() || net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(selectedWings);
		VeloButton equip = new VeloButton(showcaseX, contentBottom() - 20, SHOWCASE_WIDTH, 20,
				Text.literal(selectedWings.isEmpty() ? (equipped == null ? "No wings" : "Take wings off") : isEquipped ? "Unequip"
						: owned ? "Equip" : "Get in the Store"), b -> {
					if (!owned) {
						net.veloclient.velo.client.cosmetics.wings.WingsManager.type(selectedWings).ifPresent(type ->
								this.client.setScreen(new net.veloclient.velo.client.gui.store.WingDetailScreen(this, type)));
						return;
					}
					if (selectedWings.isEmpty() || isEquipped) {
						net.veloclient.velo.client.cosmetics.wings.WingsManager.equip(null);
						selectedWings = "";
						status = Text.literal("Wings off");
					} else {
						net.veloclient.velo.client.cosmetics.wings.WingsManager.equip(selectedWings);
						status = Text.literal("Equipped " + net.veloclient.velo.client.cosmetics.wings.WingsManager.type(selectedWings)
								.map(net.veloclient.velo.client.cosmetics.wings.WingsManager.WingType::name).orElse(selectedWings));
					}
					layoutContent();
				});
		if (!isEquipped && !selectedWings.isEmpty()) {
			equip.primary();
		}
		addDrawableChild(equip);

		int gridX = showcaseX + SHOWCASE_WIDTH + 14;
		int listWidth = contentX() + contentWidth() - gridX;
		addTabs(gridX);
		int y = contentY() + 18;
		int columns = 3;
		int tileW = (listWidth - (columns - 1) * 6) / columns;
		java.util.List<String[]> tiles = new java.util.ArrayList<>();
		tiles.add(new String[] {"", "None"});
		// What you own first, then what the Store sells (to try on).
		var wingTypes = net.veloclient.velo.client.cosmetics.wings.WingsManager.catalog();
		for (var type : wingTypes) {
			if (net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(type.id())) {
				tiles.add(new String[] {type.id(), type.name()});
			}
		}
		for (var type : wingTypes) {
			if (type.inStore() && !net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(type.id())) {
				tiles.add(new String[] {type.id(), type.name()});
			}
		}
		for (int i = 0; i < tiles.size(); i++) {
			String id = tiles.get(i)[0];
			String label = tiles.get(i)[1] + (id.equals(equipped == null ? "" : equipped) ? "  ✔"
					: !id.isEmpty() && !net.veloclient.velo.client.cosmetics.wings.WingsManager.owns(id) ? "  (Store)" : "");
			VeloButton tile = new VeloButton(gridX + (i % columns) * (tileW + 6), y + (i / columns) * 24, tileW, 20, Text.literal(label), b -> {
				selectedWings = id;
				layoutContent();
			});
			if (id.equals(selectedWings)) {
				tile.primary();
			}
			addDrawableChild(tile);
		}
		y += ((tiles.size() + columns - 1) / columns) * 24 + 10;
		int half = (listWidth - 8) / 2;
		java.util.List<Object[]> sliders = java.util.List.of(
				new Object[] {"Size", 0.4, 2.0, 0, (java.util.function.DoubleFunction<String>) v -> Math.round(v * 100) + "%"},
				new Object[] {"Height", -0.5, 0.5, 1, (java.util.function.DoubleFunction<String>) v -> String.format(java.util.Locale.ROOT, "%+d px", Math.round(v * 16))},
				new Object[] {"Distance from back", -0.2, 0.5, 2, (java.util.function.DoubleFunction<String>) v -> String.format(java.util.Locale.ROOT, "%+d px", Math.round(v * 16))},
				new Object[] {"Spread", -30.0, 60.0, 3, (java.util.function.DoubleFunction<String>) v -> Math.round(v) + "°"},
				new Object[] {"Flap speed", 0.25, 3.0, 4, (java.util.function.DoubleFunction<String>) v -> Math.round(v * 100) + "%"},
				new Object[] {"Flap strength", 0.0, 2.0, 5, (java.util.function.DoubleFunction<String>) v -> Math.round(v * 100) + "%"});
		for (int i = 0; i < sliders.size(); i++) {
			Object[] row = sliders.get(i);
			int field = (int) row[3];
			@SuppressWarnings("unchecked")
			java.util.function.DoubleFunction<String> format = (java.util.function.DoubleFunction<String>) row[4];
			addDrawableChild(new net.veloclient.velo.client.gui.widget.VeloSlider(gridX + (i % 2) * (half + 8), y + (i / 2) * 26, half, 20,
					(String) row[0], (double) row[1], (double) row[2], () -> wingSetting(field),
					v -> setWingSetting(field, (float) v), v -> Math.round(v * 100) / 100.0, format));
		}
		y += ((sliders.size() + 1) / 2) * 26 + 2;
		addDrawableChild(new VeloButton(gridX, y, half, 18, Text.literal("Reset settings"), b -> {
			net.veloclient.velo.client.cosmetics.wings.WingsManager.setSettings(selectedWings, net.veloclient.velo.client.cosmetics.wings.WingsManager.Settings.DEFAULT);
			layoutContent();
		}));
	}

	private double wingSetting(int field) {
		var s = net.veloclient.velo.client.cosmetics.wings.WingsManager.settings(selectedWings);
		return switch (field) {
			case 0 -> s.scale();
			case 1 -> -s.offsetY();
			case 2 -> s.offsetZ();
			case 3 -> s.spread();
			case 4 -> s.flapSpeed();
			default -> s.flapStrength();
		};
	}

	/** Height is shown "up = +", the body's y axis points down. */
	private void setWingSetting(int field, float v) {
		if (selectedWings == null || selectedWings.isEmpty()) {
			return;
		}
		var s = net.veloclient.velo.client.cosmetics.wings.WingsManager.settings(selectedWings);
		net.veloclient.velo.client.cosmetics.wings.WingsManager.setSettings(selectedWings, new net.veloclient.velo.client.cosmetics.wings.WingsManager.Settings(
				field == 0 ? v : s.scale(), field == 1 ? -v : s.offsetY(), field == 2 ? v : s.offsetZ(), field == 3 ? v : s.spread(),
				field == 4 ? v : s.flapSpeed(), field == 5 ? v : s.flapStrength()));
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		scrollRegion = null;
		if (aurasTab) {
			CapeManager.clearPreviewOverride();
			layoutAuras();
			return;
		}
		if (wingsTab) {
			CapeManager.clearPreviewOverride();
			layoutWings();
			return;
		}
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
		addTabs(gridX);

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
		if (aurasTab) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Click one to try it on", contentX() + contentWidth() - 80 - gridX - 180),
					gridX + 180, contentY() + 1, VeloStyle.textFaint());
			context.drawTextWithShadow(this.textRenderer, status, gridX, contentBottom() - 9, VeloStyle.textMuted());
			return;
		}
		if (wingsTab) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Click a pair to try it on", contentX() + contentWidth() - 80 - gridX - 130),
					gridX + 180, contentY() + 1, VeloStyle.textFaint());
			context.drawTextWithShadow(this.textRenderer, status, gridX, contentBottom() - 9, VeloStyle.textMuted());
			return;
		}
		int count = CapeManager.library().size();
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(count + (count == 1 ? " cape" : " capes") + "  ·  click one to preview it on you",
				contentX() + contentWidth() - 80 - gridX - 180), gridX + 180, contentY() + 1, VeloStyle.textFaint());
		CapeDefinition selected = selectedId == null ? null : CapeManager.library().get(selectedId);
		if (selected != null) {
			String name = VeloUi.trim(selected.name(), SHOWCASE_WIDTH);
			context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont(name), contentX(), contentBottom() - 62, VeloStyle.text());
		}
		context.drawTextWithShadow(this.textRenderer, status, gridX, contentBottom() - 9, VeloStyle.textMuted());
	}
}
