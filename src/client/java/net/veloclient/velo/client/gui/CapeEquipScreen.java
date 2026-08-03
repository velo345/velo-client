package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.CapeDefinition;
import net.veloclient.velo.client.cosmetics.CapeManager;
import net.veloclient.velo.client.cosmetics.CapePhysicsPreset;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cape library/equip menu (design spec section 6.5). Importing uses a real
 * native file picker (Swing's {@code JFileChooser}, run off the render
 * thread) instead of typing a filesystem path by hand, then one click to
 * equip - no separate "apply" step.
 */
public final class CapeEquipScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 24;

	private VeloScrollRegion scrollRegion;
	private TextFieldWidget nameBox;
	private Text status = Text.literal("");
	private final AtomicReference<Path> pendingPngFile = new AtomicReference<>();

	public CapeEquipScreen(net.minecraft.client.gui.screen.Screen parent) {
		super(Text.literal("Cape Library"), 420, 400);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		CapeManager.loadLibrary();

		// The bottom controls (PNG picker, name box, Import/Done) are
		// anchored to contentBottom() and the cape list fills whatever
		// space is actually left above them, instead of everything using
		// fixed heights added downward from the top - at a high GUI Scale
		// the window gets clamped shorter than its requested size, and a
		// fixed layout pushed "Choose PNG..." (and everything below it)
		// below the visible window entirely, unreachable no matter what
		// was wrong with the file picker itself.
		int doneY = contentBottom() - 20;
		int importY = doneY - 24;
		int nameY = importY - 24;
		int pngY = nameY - 24;

		int y = contentY();
		addDrawableChild(new VeloButton(contentX(), y, contentWidth(), 20, Text.literal("Unequip"), b -> {
			CapeManager.unequip();
			layoutContent();
		}));
		y += 26;

		int listHeight = Math.max(40, pngY - 8 - y);
		scrollRegion = new VeloScrollRegion(contentX(), y, contentWidth(), listHeight);
		List<CapeDefinition> capes = List.copyOf(CapeManager.library().values());
		String equippedId = CapeManager.equipped().map(CapeDefinition::id).orElse(null);
		for (CapeDefinition cape : capes) {
			boolean equipped = cape.id().equals(equippedId);
			VeloButton row = new VeloButton(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 4,
					Text.literal(cape.name() + (equipped ? "  ✓ equipped" : "")),
					b -> {
						CapeManager.equip(cape.id());
						layoutContent();
					});
			if (equipped) {
				row.selected(true);
			}
			addDrawableChild(row);
			scrollRegion.addRow(row);
		}
		scrollRegion.layout(ROW_HEIGHT, 2);

		addDrawableChild(new VeloButton(contentX(), pngY, 160, 20, Text.literal("Choose PNG..."), b -> pickFile()));

		nameBox = new TextFieldWidget(this.textRenderer, contentX(), nameY, contentWidth(), 18, Text.literal("Name"));
		nameBox.setPlaceholder(Text.literal("Cape name..."));
		nameBox.setDrawsBackground(false);
		addDrawableChild(nameBox);

		addDrawableChild(new VeloButton(contentX(), importY, 120, 20, Text.literal("Import"), b -> doImport()).primary());
		addDrawableChild(new VeloButton(contentX() + contentWidth() - 100, importY, 100, 20, Text.literal("Done"), b -> requestClose()));
	}

	private void pickFile() {
		status = Text.literal("Opening file picker...");
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				Path file = NativeFileDialog.pickPngFile("Choose a cape PNG");
				if (file != null) {
					pendingPngFile.set(file);
					MinecraftClient.getInstance().execute(() -> {
						status = Text.literal("Selected: " + file.getFileName());
						if (nameBox != null && nameBox.getText().isBlank()) {
							String fileName = file.getFileName().toString();
							int dot = fileName.lastIndexOf('.');
							nameBox.setText(dot > 0 ? fileName.substring(0, dot) : fileName);
						}
					});
				} else {
					MinecraftClient.getInstance().execute(() -> status = Text.literal(""));
				}
			} catch (Throwable t) {
				net.veloclient.velo.VeloClient.LOGGER.error("Cape file picker failed to open", t);
				String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
				MinecraftClient.getInstance().execute(() -> status = Text.literal("File picker failed: " + message));
			}
		});
	}

	private void doImport() {
		Path file = pendingPngFile.get();
		if (file == null) {
			status = Text.literal("Choose a PNG file first.");
			return;
		}
		try {
			String name = nameBox.getText().trim().isEmpty() ? "Cape" : nameBox.getText().trim();
			CapeManager.importCape(name, file, CapePhysicsPreset.defaults());
			status = Text.literal("Imported \"" + name + "\"");
			pendingPngFile.set(null);
			layoutContent();
		} catch (IOException e) {
			status = Text.literal("Import failed: " + e.getMessage());
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
			scrollRegion.renderScrollbar(context, ROW_HEIGHT, 2);
		}
		Theme theme = ThemeManager.active();
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 10, theme.text());
	}
}
