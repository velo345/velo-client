package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.TranslatableTextContent;
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
*///?}
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.TitleMenu;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The 0.9.7 title screen - drawn by {@link TitleMenu}; this mixin is only the per-version glue: it adds Velo's own
 * clickable entries, tells the menu which buttons are vanilla's (by real translation key) and lets it stop vanilla
 * from painting what the menu paints.
 *
 * <p>Vanilla's buttons are never removed from the screen, only moved and painted over: Flashback (and others) find
 * their own place by looking for vanilla's buttons in the screen's drawables, and never show up if those are gone.
 * Vanilla's logo and splash are cancelled in {@link TitleLogoMixin} / {@link TitleSplashMixin}, the panorama is
 * Velo's ({@link TitlePanoramaMixin}).
 */
//? if <26.1 {
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

	@Inject(method = "init", at = @At("RETURN"))
	private void velo$restyle(CallbackInfo ci) {
		TitleScreen self = (TitleScreen) (Object) this;
		@SuppressWarnings("unchecked")
		List<Element> children = (List<Element>) self.children();
		children.addAll(TitleMenu.createHotspots(self));
		for (var child : self.children()) {
			if (child instanceof ButtonWidget widget && TitleScreenTheme.TITLE_KEY_ORDER.contains(String.valueOf(velo$keyOf(widget.getMessage())))) {
				TitleMenu.adopt(widget);
			}
		}
	}

	@Unique
	private static String velo$keyOf(Text text) {
		return text.getContent() instanceof TranslatableTextContent t ? t.getKey() : null;
	}

	/** ukulib's icon-only button (empty label, so nothing to match by text) - kept by ukulib in a field of this name. */
	@Unique
	private static ButtonWidget velo$ukulibButton(TitleScreen self) {
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
		TitleMenu.beforeVanilla();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void velo$overlay(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		TitleScreen self = (TitleScreen) (Object) this;
		List<Drawable> drawables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		TitleMenu.render(context, self.width, self.height, mouseX, mouseY, new TitleMenu.Env() {
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
				return velo$keyOf(widget.getMessage());
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
/*@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

	@Inject(method = "init", at = @At("RETURN"))
	private void velo$restyle(CallbackInfo ci) {
		TitleScreen self = (TitleScreen) (Object) this;
		@SuppressWarnings("unchecked")
		List<GuiEventListener> children = (List<GuiEventListener>) self.children();
		children.addAll(TitleMenu.createHotspots(self));
		for (var child : self.children()) {
			if (child instanceof Button widget && TitleScreenTheme.TITLE_KEY_ORDER.contains(String.valueOf(velo$keyOf(widget.getMessage())))) {
				TitleMenu.adopt(widget);
			}
		}
	}

	@Unique
	private static String velo$keyOf(Text text) {
		return text.getContents() instanceof TranslatableContents t ? t.getKey() : null;
	}

	@Unique
	private static Button velo$ukulibButton(TitleScreen self) {
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
		TitleMenu.beforeVanilla();
	}

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void velo$overlay(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		TitleScreen self = (TitleScreen) (Object) this;
		List<Renderable> renderables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		TitleMenu.render(context, self.width, self.height, mouseX, mouseY, new TitleMenu.Env() {
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
				return velo$keyOf(widget.getMessage());
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
