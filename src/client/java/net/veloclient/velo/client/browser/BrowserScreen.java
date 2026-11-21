package net.veloclient.velo.client.browser;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.util.Keys;

/**
 * The Velo Browser on screen: the mouse is free, the game keeps running behind (no pause, no dimming). Keys go to the
 * page (or to the address field while it's focused); Esc leaves the address field first, then closes the window -
 * the page keeps running as the player chose (picture-in-picture, hidden, or closed).
 */
public final class BrowserScreen extends Screen {

	private static BrowserScreen open;
	private TextFieldWidget address;

	public BrowserScreen() {
		super(Text.literal("Velo Browser"));
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	@Override
	protected void init() {
		open = this;
		BrowserWindow.opened();
		String keep = address == null ? "" : address.getText();
		address = new TextFieldWidget(this.textRenderer, -5000, -5000, 100, 16, Text.literal("Address"));
		address.setMaxLength(2048);
		address.setDrawsBackground(false);
		address.setText(keep);
		addDrawableChild(address);
		if (BrowserWindow.view != null) {
			BrowserWindow.view.focus(true);
		}
		if (!BrowserWindow.addressFocused) {
			pageTextInput(true);
		}
	}

	/**
	 * 26.3 (SDL) only delivers typed text while someone has switched text input on - vanilla does that for its own text
	 * fields only, so a page never got a single letter. The page claims it whenever it has the keyboard.
	 */
	private void pageTextInput(boolean on) {
		//? if >=26.3 {
		/*// by name: Stonecutter's Text->Component rule would rename the methods (start/stop "T" + "extInput")
		try {
			Object manager = net.minecraft.client.Minecraft.class.getMethod("t" + "extInputManager").invoke(net.minecraft.client.Minecraft.getInstance());
			manager.getClass().getMethod((on ? "start" : "stop") + "T" + "extInput", Object.class).invoke(manager, this);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// no text input then
		}
		*///?}
	}

	@Override
	public void removed() {
		if (open == this) {
			open = null;
		}
		BrowserWindow.addressFocused = false;
		pageTextInput(false);
		if (BrowserWindow.view != null) {
			BrowserWindow.view.focus(false);
			if (!BrowserWindow.hidden && BrowserWindow.away == BrowserWindow.Away.CLOSE) {
				BrowserWindow.closePage();
			}
		}
		BrowserModule.saveSoon();
		super.removed();
	}

	static String fieldText() {
		BrowserScreen s = open;
		return s == null || s.address == null ? "" : s.address.getText();
	}

	/** The address field's "go" button: same as pressing Enter in it. */
	static void submitTyped() {
		BrowserScreen s = open;
		if (s != null && s.address != null) {
			BrowserWindow.submitAddress(s.address.getText());
			unfocusAddress();
		}
	}

	static void focusAddress(String current) {
		BrowserScreen s = open;
		if (s != null && s.address != null) {
			// starts empty: typing replaces the address (like a browser's selected address bar), the current one is shown until then
			s.address.setText("");
			s.setFocused(s.address);
			if (BrowserWindow.view != null) {
				BrowserWindow.view.focus(false);
			}
		}
	}

	static void unfocusAddress() {
		BrowserScreen s = open;
		if (s != null) {
			s.setFocused(null);
			if (BrowserWindow.view != null) {
				BrowserWindow.view.focus(true);
			}
			s.pageTextInput(true);
		}
	}

	/** Mouse button as 0 left, 1 right, 2 middle (26.3 numbers them SDL's way: 1 left, 2 middle, 3 right). */
	private static int button(int raw) {
		if (Keys.SDL) {
			return raw == 1 ? 0 : raw == 3 ? 1 : raw == 2 ? 2 : raw;
		}
		return raw;
	}

	private void leave() {
		BrowserWindow.animateClose(() -> MinecraftClient.getInstance().setScreen(null));
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
		BrowserWindow.render(context, this.width, this.height, mouseX, mouseY);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void mouseMoved(double mouseX, double mouseY) {
		BrowserWindow.mouseMoved(mouseX, mouseY, this.width, this.height);
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (!BrowserWindow.mouseClicked(click.x(), click.y(), button(click.button()), this.width, this.height)) {
			// a click outside the window: back to the game, the page keeps running
			leave();
		}
		return true;
	}

	@Override
	public boolean mouseReleased(Click click) {
		return BrowserWindow.mouseReleased(click.x(), click.y(), button(click.button()), this.width, this.height) || velo$superMouseReleased(click);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		return BrowserWindow.mouseDragged(click.x(), click.y(), this.width, this.height) || velo$superMouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		return BrowserWindow.mouseScrolled(mouseX, mouseY, vertical, this.width, this.height) || super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		int key = input.key();
		if (BrowserWindow.addressFocused && address != null) {
			if (key == Keys.ENTER || key == Keys.KP_ENTER) {
				BrowserWindow.submitAddress(address.getText());
				unfocusAddress();
				return true;
			}
			if (key == Keys.ESCAPE) {
				BrowserWindow.addressFocused = false;
				unfocusAddress();
				return true;
			}
			return velo$superKeyPressed(input);
		}
		if (key == Keys.ESCAPE) {
			if (BrowserWindow.fullscreen) {
				BrowserWindow.fullscreen = false;
				return true;
			}
			leave();
			return true;
		}
		// the open key closes the window again (only when the page isn't typing - it gets letters otherwise)
		if (key == net.veloclient.velo.client.keybind.KeybindConfig.currentKeyCode(BrowserModule.OPEN_KEY) && Keys.isKeyDown(Keys.LEFT_CONTROL)) {
			leave();
			return true;
		}
		if (BrowserWindow.view != null) {
			int glfw = BrowserView.glfw(key);
			if (glfw >= 0) {
				BrowserWindow.view.key(glfw, true);
			}
			return true;
		}
		return velo$superKeyPressed(input);
	}

	@Override
	public boolean keyReleased(net.minecraft.client.input.KeyInput input) {
		if (!BrowserWindow.addressFocused && BrowserWindow.view != null) {
			int glfw = BrowserView.glfw(input.key());
			if (glfw >= 0) {
				BrowserWindow.view.key(glfw, false);
			}
			return true;
		}
		return velo$superKeyReleased(input);
	}

	//? if >=1.21.2 && <26.1 {
	@Override
	public boolean charTyped(net.minecraft.client.input.CharInput input) {
		return typed(input.codepoint()) || super.charTyped(input);
	}
	//?} else if >=26.1 {
	/*@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent input) {
		return typed(input.codepoint()) || super.charTyped(input);
	}
	*///?}

	private boolean typed(int codepoint) {
		if (BrowserWindow.addressFocused || BrowserWindow.view == null) {
			return false;
		}
		for (char c : Character.toChars(codepoint)) {
			BrowserWindow.view.typed(c);
		}
		return true;
	}

	// ---- 1.21.1: vanilla input still comes as numbers; the screens use 1.21.9's event objects ----
	private boolean velo$superKeyPressed(net.minecraft.client.input.KeyInput c) {
		//? if <1.21.2 {
		/*return super.keyPressed(c.key(), c.scancode(), c.modifiers());
		*///?} else {
		return super.keyPressed(c);
		//?}
	}

	private boolean velo$superKeyReleased(net.minecraft.client.input.KeyInput c) {
		//? if <1.21.2 {
		/*return super.keyReleased(c.key(), c.scancode(), c.modifiers());
		*///?} else {
		return super.keyReleased(c);
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

	@Override
	public boolean keyReleased(int key, int scancode, int modifiers) {
		return keyReleased(new net.veloclient.velo.client.compat.KeyInput(key, scancode, modifiers));
	}

	@Override
	public boolean charTyped(char chr, int modifiers) {
		return typed(chr) || super.charTyped(chr, modifiers);
	}
	*///?}
}
