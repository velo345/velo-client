package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.modules.utility.CommandKeybindEntry;
import net.veloclient.velo.client.modules.utility.CommandKeybindsModule;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Create/edit/delete screen for {@link CommandKeybindsModule}'s command
 * list - each entry is a command (e.g. "/spawn") plus the key that runs it.
 * Mirrors {@link ProfileScreen}'s list-plus-shared-edit-fields layout: click
 * a row to load it into the fields below for editing, or leave nothing
 * selected and fill the fields to create a new one.
 */
public final class CommandKeybindsScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 24;

	private VeloScrollRegion scrollRegion;
	private TextFieldWidget commandBox;
	private Text status = Text.literal("");
	private int selectedIndex = -1;
	private int pendingKeyCode = GLFW.GLFW_KEY_UNKNOWN;
	private boolean listeningForKey;

	public CommandKeybindsScreen(Screen parent) {
		super(Text.literal("Command Keybinds"), 360, 400);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		List<CommandKeybindEntry> entries = CommandKeybindsModule.entries();

		int doneY = contentBottom() - 20;
		// A dedicated 16px gap between the button rows and Done, so the
		// status line (drawn in render()) has somewhere to go that isn't on
		// top of a button - exactly the bug just fixed in the log viewer.
		int addSaveY = doneY - 24 - 16;
		int keyY = addSaveY - 24;
		int commandY = keyY - 24;

		int y = contentY();
		int listHeight = Math.max(40, commandY - 8 - y);
		scrollRegion = new VeloScrollRegion(contentX(), y, contentWidth(), listHeight);
		for (int i = 0; i < entries.size(); i++) {
			CommandKeybindEntry entry = entries.get(i);
			int index = i;
			String keyLabel = entry.keyCode() == GLFW.GLFW_KEY_UNKNOWN ? "Unbound" : keyName(entry.keyCode());
			VeloButton row = new VeloButton(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 4,
					Text.literal(entry.command() + "  —  " + keyLabel),
					b -> select(index));
			if (i == selectedIndex) {
				row.selected(true);
			}
			addDrawableChild(row);
			scrollRegion.addRow(row);
		}
		scrollRegion.layout(ROW_HEIGHT, 2);

		commandBox = new TextFieldWidget(this.textRenderer, contentX(), commandY, contentWidth(), 18, Text.literal("Command"));
		commandBox.setPlaceholder(Text.literal("/spawn"));
		commandBox.setDrawsBackground(false);
		if (selectedIndex >= 0 && selectedIndex < entries.size()) {
			commandBox.setText(entries.get(selectedIndex).command());
		}
		addDrawableChild(commandBox);

		Text keyButtonText = Text.literal(listeningForKey
				? "> press a key (Esc to cancel) <"
				: "Keybind: " + (pendingKeyCode == GLFW.GLFW_KEY_UNKNOWN ? "Unbound" : keyName(pendingKeyCode)));
		addDrawableChild(new VeloButton(contentX(), keyY, contentWidth(), 20, keyButtonText, b -> {
			listeningForKey = true;
			layoutContent();
		}));

		String addSaveLabel = selectedIndex >= 0 ? "Save Changes" : "Add New";
		addDrawableChild(new VeloButton(contentX(), addSaveY, contentWidth() / 2 - 4, 20,
				Text.literal(addSaveLabel), b -> addOrSave()).primary());
		addDrawableChild(new VeloButton(contentX() + contentWidth() / 2 + 4, addSaveY, contentWidth() / 2 - 4, 20,
				Text.literal("Delete Selected"), b -> deleteSelected()));

		addDrawableChild(new VeloButton(contentX(), doneY, contentWidth(), 20, Text.literal("Done"), b -> requestClose()));
	}

	private void select(int index) {
		selectedIndex = index;
		List<CommandKeybindEntry> entries = CommandKeybindsModule.entries();
		pendingKeyCode = index < entries.size() ? entries.get(index).keyCode() : GLFW.GLFW_KEY_UNKNOWN;
		layoutContent();
	}

	private void addOrSave() {
		String command = commandBox.getText().trim();
		if (command.isEmpty()) {
			status = Text.literal("Type a command first, e.g. /spawn");
			return;
		}
		List<CommandKeybindEntry> entries = new ArrayList<>(CommandKeybindsModule.entries());
		CommandKeybindEntry entry = new CommandKeybindEntry(command, pendingKeyCode);
		if (selectedIndex >= 0 && selectedIndex < entries.size()) {
			entries.set(selectedIndex, entry);
			status = Text.literal("Saved \"" + command + "\"");
		} else {
			entries.add(entry);
			selectedIndex = entries.size() - 1;
			status = Text.literal("Added \"" + command + "\"");
		}
		CommandKeybindsModule.setEntries(entries);
		layoutContent();
	}

	private void deleteSelected() {
		List<CommandKeybindEntry> entries = new ArrayList<>(CommandKeybindsModule.entries());
		if (selectedIndex < 0 || selectedIndex >= entries.size()) {
			status = Text.literal("Select a row first.");
			return;
		}
		CommandKeybindEntry removed = entries.remove(selectedIndex);
		CommandKeybindsModule.setEntries(entries);
		status = Text.literal("Deleted \"" + removed.command() + "\"");
		selectedIndex = -1;
		pendingKeyCode = GLFW.GLFW_KEY_UNKNOWN;
		commandBox.setText("");
		layoutContent();
	}

	private static String keyName(int keyCode) {
		return InputUtil.Type.KEYSYM.createFromCode(keyCode).getLocalizedText().getString();
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (listeningForKey) {
			listeningForKey = false;
			if (input.key() != GLFW.GLFW_KEY_ESCAPE) {
				pendingKeyCode = input.key();
			}
			layoutContent();
			return true;
		}
		return super.keyPressed(input);
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
		// Sits in the 16px gap reserved above Done in layoutContent().
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 30, theme.text());
	}
}
