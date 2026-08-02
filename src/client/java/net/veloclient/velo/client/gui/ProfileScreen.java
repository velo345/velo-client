package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.profile.VeloProfileStore;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.profile.VeloProfile;
import net.veloclient.velo.profile.VeloProfileManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Create/choose/rename/delete screen for {@link VeloProfileManager}'s named
 * profiles - each one bundles every module's settings plus the HUD layout,
 * completely independent from every other profile (design spec section
 * 5/8's profile switcher). Clicking a profile switches to it immediately
 * (saving whatever was active first, so nothing made in it is lost) and
 * selects it for the Rename/Delete buttons below.
 */
public final class ProfileScreen extends VeloWindow {

	private static final int ROW_HEIGHT = 24;

	private VeloScrollRegion scrollRegion;
	private TextFieldWidget nameBox;
	private Text status = Text.literal("");
	private String selectedProfile;

	public ProfileScreen(Screen parent) {
		super(Text.literal("Profiles"), 360, 400);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		String active = VeloProfileManager.activeName();
		if (selectedProfile == null) {
			selectedProfile = active;
		}

		// Bottom controls are anchored to contentBottom() and the list fills
		// whatever's left above them, rather than fixed heights cascading
		// down from the top - a fixed layout can push the lower controls
		// below the actual (GUI-Scale-clamped) window bounds, which turned
		// out to be a real bug in the Cape Library screen's old layout.
		int doneY = contentBottom() - 20;
		// A dedicated 16px gap between the button rows and Done, for the
		// status line drawn in render() - it used to sit directly on top of
		// a bottom-anchored button's hitbox, the same bug just fixed in the
		// log viewer.
		int renameDeleteY = doneY - 26 - 16;
		int createY = renameDeleteY - 24;
		int nameY = createY - 24;

		int y = contentY();
		int listHeight = Math.max(40, nameY - 12 - y);
		scrollRegion = new VeloScrollRegion(contentX(), y, contentWidth(), listHeight);
		List<String> names = VeloProfileManager.listProfileNames();
		if (!names.contains(active)) {
			names = new ArrayList<>(names);
			names.add(0, active);
		}
		for (String name : names) {
			boolean isActive = name.equals(active);
			VeloButton row = new VeloButton(scrollRegion.x(), 0, scrollRegion.viewportWidth(), ROW_HEIGHT - 4,
					Text.literal(name + (isActive ? "  ✓ active" : "")),
					b -> selectAndSwitch(name));
			if (name.equals(selectedProfile)) {
				row.selected(true);
			}
			addDrawableChild(row);
			scrollRegion.addRow(row);
		}
		scrollRegion.layout(ROW_HEIGHT, 2);

		nameBox = new TextFieldWidget(this.textRenderer, contentX(), nameY, contentWidth(), 18, Text.literal("Name"));
		nameBox.setPlaceholder(Text.literal("New profile name..."));
		nameBox.setDrawsBackground(false);
		addDrawableChild(nameBox);

		addDrawableChild(new VeloButton(contentX(), createY, contentWidth(), 20,
				Text.literal("Create From Current Setup"), b -> createFromCurrent()).primary());

		addDrawableChild(new VeloButton(contentX(), renameDeleteY, contentWidth() / 2 - 4, 20,
				Text.literal("Rename Selected"), b -> renameSelected()));
		addDrawableChild(new VeloButton(contentX() + contentWidth() / 2 + 4, renameDeleteY, contentWidth() / 2 - 4, 20,
				Text.literal("Delete Selected"), b -> deleteSelected()));

		addDrawableChild(new VeloButton(contentX(), doneY, contentWidth(), 20, Text.literal("Done"), b -> requestClose()));
	}

	private void selectAndSwitch(String name) {
		selectedProfile = name;
		if (!name.equals(VeloProfileManager.activeName())) {
			// Save the outgoing profile's current live state before
			// switching, same as leaving any other settings screen -
			// otherwise anything changed since it was last saved would be
			// silently lost.
			VeloProfileStore.saveActive();
			VeloProfileManager.loadByName(name).ifPresentOrElse(profile -> {
				VeloProfileStore.applyToLive(profile);
				VeloProfileManager.setActiveName(name);
				status = Text.literal("Switched to \"" + name + "\"");
			}, () -> status = Text.literal("Couldn't load \"" + name + "\""));
		}
		layoutContent();
	}

	private void createFromCurrent() {
		String name = nameBox.getText().trim();
		if (name.isEmpty()) {
			status = Text.literal("Type a name first.");
			return;
		}
		VeloProfile profile = VeloProfileStore.captureCurrent(name);
		VeloProfileManager.save(profile);
		VeloProfileManager.setActiveName(name);
		selectedProfile = name;
		status = Text.literal("Created \"" + name + "\" from the current setup");
		nameBox.setText("");
		layoutContent();
	}

	private void renameSelected() {
		String newName = nameBox.getText().trim();
		if (newName.isEmpty()) {
			status = Text.literal("Type the new name in the box first.");
			return;
		}
		VeloProfileManager.rename(selectedProfile, newName);
		selectedProfile = newName;
		status = Text.literal("Renamed to \"" + newName + "\"");
		nameBox.setText("");
		layoutContent();
	}

	private void deleteSelected() {
		if (selectedProfile.equals(VeloProfileManager.activeName())) {
			status = Text.literal("Can't delete the active profile - switch to another one first.");
			return;
		}
		VeloProfileManager.delete(selectedProfile);
		status = Text.literal("Deleted \"" + selectedProfile + "\"");
		selectedProfile = VeloProfileManager.activeName();
		layoutContent();
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
