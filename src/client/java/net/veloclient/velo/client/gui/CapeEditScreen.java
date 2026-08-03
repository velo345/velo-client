package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import net.veloclient.velo.client.cosmetics.WaveyCapesCompat;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloLabel;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.io.IOException;

/**
 * Renames a library cape and tunes its cloth simulation (design spec section
 * 6.5's per-cape physics preset) - the texture itself isn't edited here, only
 * name and physics; re-import a new PNG to change the artwork.
 */
public final class CapeEditScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 26;

	private final CapeDefinition definition;
	private VeloScrollRegion scrollRegion;
	private TextFieldWidget nameBox;
	private CapePhysicsPreset preset;
	private Text status = Text.literal("");

	public CapeEditScreen(Screen parent, CapeDefinition definition) {
		super(Text.literal("Edit Cape"), 380, 340);
		this.definition = definition;
		this.preset = definition.physicsPreset();
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();

		int doneY = contentBottom() - 20;
		int listBottom = doneY - 16;
		int y = contentY();
		scrollRegion = new VeloScrollRegion(contentX(), y, contentWidth(), Math.max(0, listBottom - y));

		// The name field always works and saves regardless of Wavey Capes -
		// only the physics sliders below are affected by it, so the name row
		// itself is unconditional here, and the caveat (as its own label row,
		// not overlaid text) sits directly above just the sliders it's
		// actually about.
		nameBox = new TextFieldWidget(this.textRenderer, scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 8, Text.literal("Name"));
		nameBox.setText(definition.name());
		addRow(nameBox);

		if (WaveyCapesCompat.isLoaded()) {
			addRow(new VeloLabel(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT,
					"Physics below: Wavey Capes is installed and renders this cape itself, so these only take effect once it's removed.",
					0xFFFFAA55));
		}

		addSliderRow("Stiffness", 0, 1, () -> preset.stiffness(),
				v -> preset = new CapePhysicsPreset((float) v, preset.gravityMultiplier(), preset.damping(), preset.windResponsiveness(), preset.segments()),
				v -> String.format("%.0f%%", v * 100));
		addSliderRow("Gravity", 0, 3, () -> preset.gravityMultiplier(),
				v -> preset = new CapePhysicsPreset(preset.stiffness(), (float) v, preset.damping(), preset.windResponsiveness(), preset.segments()),
				v -> String.format("%.1fx", v));
		addSliderRow("Damping", 0, 1, () -> preset.damping(),
				v -> preset = new CapePhysicsPreset(preset.stiffness(), preset.gravityMultiplier(), (float) v, preset.windResponsiveness(), preset.segments()),
				v -> String.format("%.0f%%", v * 100));
		addSliderRow("Wind Responsiveness", 0, 2, () -> preset.windResponsiveness(),
				v -> preset = new CapePhysicsPreset(preset.stiffness(), preset.gravityMultiplier(), preset.damping(), (float) v, preset.segments()),
				v -> String.format("%.1fx", v));
		addSliderRow("Segments", 2, 16, () -> preset.segments(),
				v -> preset = new CapePhysicsPreset(preset.stiffness(), preset.gravityMultiplier(), preset.damping(), preset.windResponsiveness(), (int) Math.round(v)),
				v -> String.valueOf(Math.round(v)));

		scrollRegion.layout(ROW_HEIGHT, 2);
		addDrawableChild(new VeloButton(contentX(), doneY, contentWidth() / 2 - 4, 20, Text.literal("Cancel"), b -> requestClose()));
		addDrawableChild(new VeloButton(contentX() + contentWidth() / 2 + 4, doneY, contentWidth() / 2 - 4, 20, Text.literal("Save"), b -> save()).primary());
	}

	private void addRow(net.minecraft.client.gui.widget.ClickableWidget widget) {
		addSelectableChild(widget);
		scrollRegion.addRow(widget);
	}

	private void addSliderRow(String label, double min, double max, java.util.function.DoubleSupplier get,
			java.util.function.DoubleConsumer set, java.util.function.DoubleFunction<String> format) {
		VeloSlider slider = new VeloSlider(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 10, label,
				min, max, get, set, null, format);
		addRow(slider);
	}

	private void save() {
		String name = nameBox.getText().trim();
		if (name.isEmpty()) {
			status = Text.literal("Type a name first.");
			return;
		}
		try {
			CapeManager.update(definition.id(), name, preset);
			requestClose();
		} catch (IOException e) {
			status = Text.literal("Save failed: " + e.getMessage());
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layout(ROW_HEIGHT, 2);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (scrollRegion != null) {
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbar(context, ROW_HEIGHT, 2);
		}
		Theme theme = ThemeManager.active();
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 32, theme.text());
	}
}
