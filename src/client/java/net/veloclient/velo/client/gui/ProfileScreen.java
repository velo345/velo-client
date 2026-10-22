package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.client.gui.Click;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.profile.VeloProfileStore;
import net.veloclient.velo.profile.VeloProfile;
import net.veloclient.velo.profile.VeloProfileManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Your profiles ({@link VeloProfileManager}): each one bundles every module's settings plus the HUD
 * layout. Click a card to switch (the outgoing profile is saved first); Rename / Delete show on hover;
 * the box at the bottom creates a new one from your current setup.
 */
public final class ProfileScreen extends VeloWindow {

	private static final int CARD_H = 42;
	private static final int CARD_GAP = 6;

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget nameBox;
	private Text status = Text.literal("");
	private String selectedProfile;
	/** The profile being renamed (the box then renames instead of creating), or null. */
	private String renaming;
	private float scroll;

	public ProfileScreen(Screen parent) {
		super(Text.literal("Profiles"), 400, 420);
		returnTo(parent);
	}

	private int fieldY() {
		return contentBottom() - 44;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		if (selectedProfile == null) {
			selectedProfile = VeloProfileManager.activeName();
		}
		String previous = nameBox != null ? nameBox.getText() : "";
		nameBox = new TextFieldWidget(this.textRenderer, contentX() + 10, fieldY() + 7, contentWidth() - 100, 12, Text.literal("Name"));
		nameBox.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder(renaming != null ? "New name for " + renaming : "New profile name"));
		nameBox.setDrawsBackground(false);
		nameBox.setText(previous);
		addDrawableChild(nameBox);
	}

	private List<String> names() {
		String active = VeloProfileManager.activeName();
		List<String> names = new ArrayList<>(VeloProfileManager.listProfileNames());
		if (!names.contains(active)) {
			names.add(0, active);
		}
		return names;
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int w = contentWidth();
		int y = contentY();
		MenuKit.text(context, this.textRenderer, MenuKit.ui("Each profile keeps its own modules, settings and HUD layout."), x, y, MenuKit.TEXT_3);
		String active = VeloProfileManager.activeName();
		List<String> names = names();
		int top = y + 18;
		int bottom = fieldY() - 22;
		float max = Math.max(0, names.size() * (CARD_H + CARD_GAP) - CARD_GAP - (bottom - top));
		scroll = Math.clamp(scroll, 0, max);
		context.enableScissor(x, top, x + w, bottom);
		for (int i = 0; i < names.size(); i++) {
			String name = names.get(i);
			int cy = top + i * (CARD_H + CARD_GAP) - Math.round(scroll);
			if (cy + CARD_H < top || cy > bottom) {
				continue;
			}
			boolean isActive = name.equals(active);
			boolean hovered = MenuKit.inside(mouseX, mouseY, x, cy, w, CARD_H) && mouseY >= top && mouseY < bottom;
			float t = MenuKit.hover("profile:" + name, hovered);
			VeloDraw.fillRounded(context, x, cy, w, CARD_H, 9, VeloAnim.lerpArgb(0xFF131318, 0xFF1A1A21, t));
			if (isActive) {
				VeloDraw.fillRoundedGradient(context, x, cy, w, CARD_H, 9, VeloUi.withAlpha(MenuKit.accent(), 0x22), 0x00131318);
			}
			VeloDraw.strokeRounded(context, x, cy, w, CARD_H, 9, isActive ? VeloUi.withAlpha(MenuKit.accent(), 0x90)
					: VeloAnim.lerpArgb(0x17FFFFFF, 0x38FFFFFF, t));
			// Monogram tile.
			VeloDraw.fillRounded(context, x + 9, cy + 9, 24, 24, 7, isActive ? MenuKit.accent() : 0x14FFFFFF);
			Text letter = MenuKit.uiBold(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
			MenuKit.text(context, this.textRenderer, letter, x + 21 - this.textRenderer.getWidth(letter) / 2, cy + 17, isActive ? MenuKit.onAccent() : MenuKit.TEXT);
			int actionsW = hovered ? 118 : isActive ? 56 : 0;
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(name), w - 52 - actionsW), x + 42, cy + 11, MenuKit.TEXT);
			MenuKit.text(context, this.textRenderer, MenuKit.ui(isActive ? "In use" : "Click to switch"), x + 42, cy + 23, isActive ? MenuKit.accent() : MenuKit.TEXT_3);
			int ay = Math.max(cy, top);
			int ah = Math.min(cy + CARD_H, bottom) - ay;
			if (hovered) {
				int bx = x + w - 10;
				if (!isActive) {
					bx -= 52;
					hits.add(VeloUi.pill(context, bx, cy + 12, 52, 18, "Delete", 0, mouseX, mouseY, () -> delete(name)));
					bx -= 6;
				}
				bx -= 58;
				hits.add(VeloUi.pill(context, bx, cy + 12, 58, 18, "Rename", 0, mouseX, mouseY, () -> {
					renaming = name;
					nameBox.setText(name);
					layoutContent();
					setFocused(nameBox);
					nameBox.setFocused(true);
				}));
			} else if (isActive) {
				Text tag = MenuKit.small("ACTIVE");
				int tw = this.textRenderer.getWidth(tag) + 12;
				VeloDraw.fillRounded(context, x + w - tw - 10, cy + 15, tw, 13, 4, VeloUi.withAlpha(MenuKit.accent(), 0x30));
				MenuKit.text(context, this.textRenderer, tag, x + w - tw - 4, cy + 18, MenuKit.accent());
			}
			hits.add(new VeloUi.Hit(x, ay, w, ah, () -> selectAndSwitch(name)));
		}
		context.disableScissor();

		// Create (or rename) row.
		int fy = fieldY();
		MenuKit.text(context, this.textRenderer, MenuKit.small(renaming != null ? "RENAME \"" + renaming.toUpperCase(java.util.Locale.ROOT) + "\"" : "NEW PROFILE"),
				x, fy - 13, MenuKit.TEXT_3);
		boolean focused = nameBox != null && nameBox.isFocused();
		VeloStyle.drawInputField(context, x, fy, w - 92, 24, focused, MenuKit.inside(mouseX, mouseY, x, fy, w - 92, 24));
		hits.add(VeloUi.pill(context, x + w - 86, fy, 86, 24, renaming != null ? "Rename" : "Create", 1, mouseX, mouseY,
				renaming != null ? this::renameSelected : this::createFromCurrent));
		Text note = renaming != null ? MenuKit.ui("Esc to cancel") : MenuKit.ui("Starts as a copy of your current setup.");
		MenuKit.text(context, this.textRenderer, status.getString().isEmpty() ? note : MenuKit.ui(status.getString()), x, contentBottom() - 10, MenuKit.TEXT_3);
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
		VeloProfileManager.rename(renaming, newName);
		selectedProfile = newName;
		renaming = null;
		status = Text.literal("Renamed to \"" + newName + "\"");
		nameBox.setText("");
		layoutContent();
	}

	private void delete(String name) {
		if (name.equals(VeloProfileManager.activeName())) {
			status = Text.literal("Can't delete the active profile - switch to another one first.");
			return;
		}
		this.client.setScreen(new VeloConfirmScreen(this, "Delete \"" + name + "\"?",
				"Its modules, settings and HUD layout are removed. Your other profiles stay.", "Delete", () -> {
					VeloProfileManager.delete(name);
					status = Text.literal("Deleted \"" + name + "\"");
					selectedProfile = VeloProfileManager.activeName();
				}));
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (nameBox != null && nameBox.isFocused()) {
			if (input.key() == net.veloclient.velo.client.util.Keys.ENTER || input.key() == net.veloclient.velo.client.util.Keys.KP_ENTER) {
				if (renaming != null) {
					renameSelected();
				} else {
					createFromCurrent();
				}
				return true;
			}
			if (input.key() == net.veloclient.velo.client.util.Keys.ESCAPE && renaming != null) {
				renaming = null;
				nameBox.setText("");
				layoutContent();
				return true;
			}
		}
		return super.keyPressed(input);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= (float) verticalAmount * 30;
		return true;
	}
}
