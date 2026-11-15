package net.veloclient.velo.client.youtube;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.util.Keys;

/**
 * The YouTube overlay made interactive: the mouse is free, the game keeps running behind (no pause, no dimming). Escape
 * closes it (the video goes on as picture-in-picture) - or leaves fullscreen first.
 */
public final class YouTubeScreen extends Screen {

	private static YouTubeScreen open;
	private TextFieldWidget searchField;

	public YouTubeScreen() {
		super(Text.literal("YouTube"));
	}

	private int placeholderMode = -1;

	private static int mode() {
		return YouTubeOverlay.twitch ? 2 : YouTubeOverlay.shorts ? 1 : 0;
	}

	private static String placeholder(int mode) {
		return switch (mode) {
			case 1 -> "Shorts topic";
			case 2 -> "Channel or link";
			default -> "Search or paste a link";
		};
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	protected void init() {
		open = this;
		String keep = searchField == null ? "" : searchField.getText();
		searchField = new TextFieldWidget(this.textRenderer, 0, 0, 100, 16, Text.literal("Search"));
		placeholderMode = mode();
		searchField.setPlaceholder(VeloUi.placeholder(placeholder(placeholderMode)));
		searchField.setMaxLength(300);
		searchField.setDrawsBackground(false);
		searchField.setText(keep);
		addDrawableChild(searchField);
		if (!YouTubeOverlay.active()) {
			YouTubeOverlay.showResults = true;
			setInitialFocus(searchField);
		}
	}

	@Override
	public void removed() {
		if (open == this) {
			open = null;
		}
		YouTubeModule.saveSoon();
		super.removed();
	}

	static boolean fieldFocused() {
		YouTubeScreen s = open;
		return s != null && s.searchField != null && s.searchField.isFocused();
	}

	static String fieldText() {
		YouTubeScreen s = open;
		return s == null || s.searchField == null ? "" : s.searchField.getText();
	}

	/** A click on the drawn search box. */
	static void focusField() {
		YouTubeScreen s = open;
		if (s != null && s.searchField != null) {
			s.setFocused(s.searchField);
		}
	}

	/** The "Search" button / Enter: searches, or plays a pasted link. */
	static void submitSearch() {
		YouTubeScreen s = open;
		if (s != null && s.searchField != null && !s.searchField.getText().isBlank()) {
			YouTubeOverlay.search(s.searchField.getText());
			s.setFocused(null);
		} else {
			YouTubeOverlay.showResults = true;
		}
	}

	// Transparent: the game stays visible behind the window.
	//? if <26.1 {
	@Override
	public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
	}
	//?} else {
	/*@Override
	public void extractBackground(net.minecraft.client.gui.GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
	}
	*///?}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		YouTubeOverlay.render(context, this.width, this.height, mouseX, mouseY, true);
		// the text field only takes the keys - it sits off screen, the player draws the box and the text in the Velo font
		if (searchField != null) {
			searchField.visible = true;
			searchField.setX(-5000);
			searchField.setY(-5000);
			if (YouTubeOverlay.searchFieldRect == null && searchField.isFocused()) {
				setFocused(null);
			}
		}
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (searchField != null && searchField.visible && searchField.isMouseOver(click.x(), click.y())) {
			return velo$superMouseClicked(click, doubled);
		}
		if (searchField != null) {
			// through the screen: unfocusing only the field left the screen thinking it was still focused, so the next click
			// into the field never focused it again (e.g. after switching YouTube / Twitch)
			this.setFocused(null);
		}
		return YouTubeOverlay.mouseClicked(click.x(), click.y(), this.width, this.height) || velo$superMouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		return YouTubeOverlay.mouseDragged(click.x(), click.y(), this.width, this.height) || velo$superMouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		return YouTubeOverlay.mouseReleased() || velo$superMouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		return YouTubeOverlay.mouseScrolled(mouseX, mouseY, vertical) || super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		int key = input.key();
		if (searchField != null && searchField.isFocused()) {
			if (key == Keys.ENTER || key == Keys.KP_ENTER) {
				submitSearch();
				return true;
			}
			if (key == Keys.ESCAPE) {
				searchField.setFocused(false);
				return true;
			}
			return velo$superKeyPressed(input);
		}
		if (YouTubeOverlay.keyPressed(key)) {
			return true;
		}
		// the open key closes the window again
		if (key == net.veloclient.velo.client.keybind.KeybindConfig.currentKeyCode(YouTubeModule.OPEN_KEY)) {
			MinecraftClient.getInstance().setScreen(null);
			return true;
		}
		return velo$superKeyPressed(input);
	}

	// ---- 1.21.1: vanilla input still comes as numbers; the screens use 1.21.9's event objects ----
	private boolean velo$superKeyPressed(net.minecraft.client.input.KeyInput c) {
		//? if <1.21.2 {
		/*return super.keyPressed(c.key(), c.scancode(), c.modifiers());
		*///?} else {
		return super.keyPressed(c);
		//?}
	}

	private boolean velo$superMouseClicked(net.minecraft.client.gui.Click c, boolean d) {
		//? if <1.21.2 {
		/*return super.mouseClicked(c.x(), c.y(), c.button());
		*///?} else {
		return super.mouseClicked(c, d);
		//?}
	}

	private boolean velo$superMouseDragged(net.minecraft.client.gui.Click c, double dx, double dy) {
		//? if <1.21.2 {
		/*return super.mouseDragged(c.x(), c.y(), c.button(), dx, dy);
		*///?} else {
		return super.mouseDragged(c, dx, dy);
		//?}
	}

	private boolean velo$superMouseReleased(net.minecraft.client.gui.Click c) {
		//? if <1.21.2 {
		/*return super.mouseReleased(c.x(), c.y(), c.button());
		*///?} else {
		return super.mouseReleased(c);
		//?}
	}

	//? if <1.21.2 {
	/*@Override
	public boolean mouseClicked(double x, double y, int button) {
		return mouseClicked(new net.veloclient.velo.client.compat.Click(x, y, button), net.veloclient.velo.client.compat.Click.doubled(x, y, button));
	}

	@Override
	public boolean mouseReleased(double x, double y, int button) {
		return mouseReleased(new net.veloclient.velo.client.compat.Click(x, y, button));
	}

	@Override
	public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		return mouseDragged(new net.veloclient.velo.client.compat.Click(x, y, button), dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scancode, int modifiers) {
		return keyPressed(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}
	*///?}
}
