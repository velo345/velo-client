package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.TranslatableTextContent;
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
*///?}
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.PauseMenu;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The 0.9.7 pause menu - drawn by {@link PauseMenu}; this mixin is the per-version glue: Velo's own entries, which
 * buttons are vanilla's (real translation keys, plus "Open to LAN" by its label, whose key didn't match in
 * practice), and stopping vanilla from painting what the menu paints. Vanilla's "Game Menu" heading (a real text
 * widget child) is removed - the menu has its own. Vanilla's buttons themselves stay in the screen (other mods
 * anchor to them), only moved and painted over.
 */
//? if <26.1 {
@Mixin(GameMenuScreen.class)
public abstract class EscapeMenuMixin {

	@Inject(method = "init", at = @At("RETURN"))
	private void velo$restyle(CallbackInfo ci) {
		GameMenuScreen self = (GameMenuScreen) (Object) this;
		List<Drawable> drawables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		@SuppressWarnings("unchecked")
		List<Element> children = (List<Element>) self.children();
		TextWidget vanillaTitle = null;
		for (var child : self.children()) {
			if (child instanceof ButtonWidget widget && velo$canonicalKey(widget) != null) {
				PauseMenu.adopt(widget);
			} else if (child instanceof TextWidget widget) {
				vanillaTitle = widget;
			}
		}
		if (vanillaTitle != null) {
			children.remove(vanillaTitle);
			drawables.remove(vanillaTitle);
		}
		children.addAll(PauseMenu.createHotspots(self));
	}

	@Unique
	private static String velo$keyOf(Text text) {
		return text.getContent() instanceof TranslatableTextContent t ? t.getKey() : null;
	}

	@Unique
	private static String velo$canonicalKey(ButtonWidget widget) {
		String key = velo$keyOf(widget.getMessage());
		if (key != null && TitleScreenTheme.ESCAPE_KEY_ORDER.contains(key)) {
			return key;
		}
		if ("Open to LAN".equalsIgnoreCase(widget.getMessage().getString())) {
			return "menu.shareToLan";
		}
		return null;
	}

	@Unique
	private static ButtonWidget velo$ukulibButton(GameMenuScreen self) {
		try {
			var field = self.getClass().getDeclaredField("ukulibButton");
			field.setAccessible(true);
			return field.get(self) instanceof ButtonWidget widget ? widget : null;
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void velo$beforeVanilla(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		GameMenuScreen self = (GameMenuScreen) (Object) this;
		PauseMenu.beforeVanilla(context, self.width, self.height);
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void velo$overlay(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		GameMenuScreen self = (GameMenuScreen) (Object) this;
		List<Drawable> drawables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		PauseMenu.render(context, self.width, self.height, mouseX, mouseY, new PauseMenu.Env() {
			@Override
			public List<ClickableWidget> buttons() {
				List<ClickableWidget> out = new ArrayList<>();
				for (var child : self.children()) {
					if (child instanceof ButtonWidget widget) {
						out.add(widget);
					}
				}
				return out;
			}

			@Override
			public String keyOf(ClickableWidget widget) {
				return widget instanceof ButtonWidget button ? velo$canonicalKey(button) : null;
			}

			@Override
			public void undraw(ClickableWidget widget) {
				drawables.remove(widget);
			}

			@Override
			public ClickableWidget ukulib() {
				return velo$ukulibButton(self);
			}
		});
	}
}
//?} else {
/*@Mixin(PauseScreen.class)
public abstract class EscapeMenuMixin {

	@Inject(method = "init", at = @At("RETURN"))
	private void velo$restyle(CallbackInfo ci) {
		PauseScreen self = (PauseScreen) (Object) this;
		List<Renderable> renderables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		@SuppressWarnings("unchecked")
		List<GuiEventListener> children = (List<GuiEventListener>) self.children();
		StringWidget vanillaTitle = null;
		for (var child : self.children()) {
			if (child instanceof Button widget && velo$canonicalKey(widget) != null) {
				PauseMenu.adopt(widget);
			} else if (child instanceof StringWidget widget) {
				vanillaTitle = widget;
			}
		}
		if (vanillaTitle != null) {
			children.remove(vanillaTitle);
			renderables.remove(vanillaTitle);
		}
		children.addAll(PauseMenu.createHotspots(self));
	}

	@Unique
	private static String velo$keyOf(Text text) {
		return text.getContents() instanceof TranslatableContents t ? t.getKey() : null;
	}

	@Unique
	private static String velo$canonicalKey(Button widget) {
		String key = velo$keyOf(widget.getMessage());
		if (key != null && TitleScreenTheme.ESCAPE_KEY_ORDER.contains(key)) {
			return key;
		}
		if ("Open to LAN".equalsIgnoreCase(widget.getMessage().getString())) {
			return "menu.shareToLan";
		}
		return null;
	}

	@Unique
	private static Button velo$ukulibButton(PauseScreen self) {
		try {
			var field = self.getClass().getDeclaredField("ukulibButton");
			field.setAccessible(true);
			return field.get(self) instanceof Button widget ? widget : null;
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void velo$beforeVanilla(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		PauseScreen self = (PauseScreen) (Object) this;
		PauseMenu.beforeVanilla(context, self.width, self.height);
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void velo$overlay(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		PauseScreen self = (PauseScreen) (Object) this;
		List<Renderable> renderables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		PauseMenu.render(context, self.width, self.height, mouseX, mouseY, new PauseMenu.Env() {
			@Override
			public List<AbstractWidget> buttons() {
				List<AbstractWidget> out = new ArrayList<>();
				for (var child : self.children()) {
					if (child instanceof Button widget) {
						out.add(widget);
					}
				}
				return out;
			}

			@Override
			public String keyOf(AbstractWidget widget) {
				return widget instanceof Button button ? velo$canonicalKey(button) : null;
			}

			@Override
			public void undraw(AbstractWidget widget) {
				renderables.remove(widget);
			}

			@Override
			public AbstractWidget ukulib() {
				return velo$ukulibButton(self);
			}
		});
	}
}
*///?}
