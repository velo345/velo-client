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
import java.nio.charset.StandardCharsets;
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
				Path file = pickFileViaNativeDialog();
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

	/**
	 * Runs a native OS file-picker dialog as a separate process instead of
	 * Swing's {@code JFileChooser}. AWT genuinely doesn't work reliably
	 * inside this JVM - something in Minecraft/LWJGL's own startup forces
	 * {@code java.awt.headless} back to {@code true} even after this mod's
	 * own JVM launch argument sets it false (confirmed: the flag is present
	 * on the process's own command line, and {@code JFrame} construction
	 * still threw {@code HeadlessException}). {@code GraphicsEnvironment}
	 * caches that flag the first time anything asks, so nothing done later
	 * in Java code can undo it for the rest of the process - a subprocess
	 * with its own native dialog sidesteps AWT entirely instead of trying to
	 * out-race whatever sets it.
	 */
	private static Path pickFileViaNativeDialog() throws IOException, InterruptedException {
		String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		if (osName.contains("win")) {
			return pickFileWindows();
		}
		if (osName.contains("mac") || osName.contains("darwin")) {
			return pickFileMac();
		}
		return pickFileLinux();
	}

	/**
	 * Windows has no kdialog/zenity equivalent preinstalled, so this shells
	 * out to PowerShell (present on every supported Windows version) to drive
	 * WinForms' own {@code OpenFileDialog} - a real native dialog, still
	 * without touching AWT/Swing.
	 */
	private static Path pickFileWindows() throws IOException, InterruptedException {
		String home = System.getProperty("user.home", ".").replace("'", "''");
		String script = "Add-Type -AssemblyName System.Windows.Forms; " +
				"$f = New-Object System.Windows.Forms.OpenFileDialog; " +
				"$f.InitialDirectory = '" + home + "'; " +
				"$f.Filter = 'PNG images (*.png)|*.png'; " +
				"if ($f.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) { Write-Output $f.FileName }";
		Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
				.redirectErrorStream(false).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		process.waitFor();
		return output.isBlank() ? null : Path.of(output);
	}

	private static Path pickFileMac() throws IOException, InterruptedException {
		String script = "try\nPOSIX path of (choose file with prompt \"Choose a cape PNG\" of type {\"png\"})\non error\nreturn \"\"\nend try";
		Process process = new ProcessBuilder("osascript", "-e", script).redirectErrorStream(false).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		process.waitFor();
		return output.isBlank() ? null : Path.of(output);
	}

	private static Path pickFileLinux() throws IOException, InterruptedException {
		String home = System.getProperty("user.home", ".");
		String[][] candidates = {
				{"kdialog", "--getopenfilename", home, "*.png|PNG images"},
				{"zenity", "--file-selection", "--file-filter=PNG images | *.png"},
		};
		IOException lastMissing = null;
		for (String[] command : candidates) {
			Process process;
			try {
				process = new ProcessBuilder(command).redirectErrorStream(false).start();
			} catch (IOException notInstalled) {
				lastMissing = notInstalled;
				continue;
			}
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
			int exitCode = process.waitFor();
			if (exitCode == 0 && !output.isBlank()) {
				return Path.of(output);
			}
			// Non-zero exit from a dialog that DID launch means the user
			// cancelled it - a real, final answer, not a reason to try the
			// next candidate tool.
			return null;
		}
		throw new IOException("No native file picker found (tried kdialog, zenity)", lastMissing);
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
