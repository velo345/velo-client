package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.TranslatableTextContent;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.contents.TranslatableContents;
*///?}
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.store.StoreScreen;
import net.veloclient.velo.client.gui.title.GlassMenuButton;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.widget.VeloNavIcons;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Themes the escape (pause) menu to match the rest of Velo's UI (design
 * spec section 5): every real vanilla button restacked into one centered
 * glass column directly below a fully transparent Velo-branded header, and
 * a new "Store" button. Vanilla's own "Game Menu"/"Paused" heading isn't
 * covered by anything - it's a real {@link TextWidget} child added via
 * {@code addDrawableChild} (found by dumping GameMenuScreen's own
 * constructor bytecode: {@code super(showMenu ? GAME_TEXT : PAUSED_TEXT)},
 * then a {@code TextWidget} built from that same title and added as a
 * genuine child/drawable, not just narrated), so it's found the same way
 * the real vanilla buttons are and removed from both {@code children()} and
 * {@code drawables} outright - actually gone, not hidden underneath
 * anything or shoved off-screen. Same real-translation-key
 * matching as {@link TitleScreenMixin} - see its class doc for why only
 * those specific buttons are touched at all, and everything else (Essential
 * or ModMenu's own buttons included) is left completely alone rather than
 * hidden. Unlike the title screen, the panorama isn't used here - vanilla's
 * own blurred in-world background stays, since replacing it with a
 * menu-screen skybox while actually mid-game would look wrong.
 */
//? if <26.1 {
@Mixin(GameMenuScreen.class)
public abstract class EscapeMenuMixin {

	@Inject(method = "init", at = @At("RETURN"))
	private void velo$restyle(CallbackInfo ci) {
		GameMenuScreen self = (GameMenuScreen) (Object) this;
		List<Drawable> drawables = ((ScreenAccessMixin) (Object) this).velo$drawables();

		// Matched by real vanilla translation key only - NOT "every
		// ButtonWidget child", which was tried once and immediately swept
		// Essential's own pause-menu buttons into this styled column
		// (Essential adds them as real children of this same screen, not a
		// separate panel as previously assumed). Only these specific known
		// vanilla buttons are ever touched; everything else - Essential's
		// included - is left completely alone at its own position/style.
		Map<String, ButtonWidget> byKey = new HashMap<>();
		TextWidget vanillaTitle = null;
		for (var child : self.children()) {
			if (child instanceof ButtonWidget widget) {
				String key = velo$canonicalKey(widget);
				if (key != null) {
					byKey.put(key, widget);
				}
				// The below-stack row and side column (Essential/ModMenu/
				// Flashback/Replay Mod buttons) are matched and positioned
				// every frame in velo$overlay instead of here - see its doc
				// comment for why a one-shot pass at init isn't enough.
			} else if (child instanceof TextWidget widget) {
				// Vanilla's real "Game Menu"/"Paused" heading - see class doc.
				vanillaTitle = widget;
			}
		}

		GlassMenuButton storeButton = new GlassMenuButton(0, 0, 1, 1, Text.literal("Store"),
				b -> MinecraftClient.getInstance().setScreen(new StoreScreen(self)));
		@SuppressWarnings("unchecked")
		List<Element> children = (List<Element>) self.children();
		children.add(storeButton);
		drawables.add(storeButton);

		if (vanillaTitle != null) {
			children.remove(vanillaTitle);
			drawables.remove(vanillaTitle);
		}

		int rowCount = 0;
		for (String key : TitleScreenTheme.ESCAPE_KEY_ORDER) {
			if (key.equals("$store") || byKey.containsKey(key)) {
				rowCount++;
			}
		}
		int headerTop = TitleScreenTheme.compactHeaderTop(self.width, self.height, rowCount);
		List<TitleScreenTheme.Layout> layout = TitleScreenTheme.stackLayoutFrom(
				self.width, self.height, headerTop + TitleScreenTheme.compactBrandingHeight() + 14, rowCount);
		int i = 0;
		for (String key : TitleScreenTheme.ESCAPE_KEY_ORDER) {
			if (key.equals("$store")) {
				velo$position(storeButton, layout.get(i++));
			} else if (byKey.containsKey(key)) {
				ButtonWidget widget = byKey.get(key);
				// Deliberately left in `drawables` - see TitleScreenMixin's
				// doc comment for why (a mod anchoring its own button search
				// against still-present real vanilla buttons, e.g. Flashback,
				// never places its button at all once they're removed from
				// here). Our own overlay below draws the glass version fully
				// opaque on top of this exact rect regardless.
				velo$position(widget, layout.get(i++));
			}
		}
	}

	@Unique
	private static void velo$position(ClickableWidget widget, TitleScreenTheme.Layout l) {
		widget.setX(l.x());
		widget.setY(l.y());
		widget.setWidth(l.width());
		widget.setHeight(l.height());
	}

	@Unique
	private static String velo$keyOf(Text text) {
		return text.getContent() instanceof TranslatableTextContent t ? t.getKey() : null;
	}

	/**
	 * A real vanilla key match, OR - specifically and only for the singleplayer
	 * "Open to LAN" button - an exact label-text match as a fallback, since
	 * its actual translation key wasn't matching {@code "menu.shareToLan"} in
	 * practice and it was being left behind in vanilla's own plain gray
	 * button style, overlapping this stack ("its minecraft styled by default
	 * and wont fit rest of gui"). Nothing else gets a text-based fallback -
	 * this must stay narrow, or it risks catching a mod's own button by an
	 * incidental label match the way capturing every child once already did.
	 */
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

	/** See {@link TitleScreenMixin}'s doc comment on its own copy of this method - same field, same reasoning, just read off {@link GameMenuScreen} instead of the title screen. */
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

	@Inject(method = "render", at = @At("RETURN"))
	private void velo$overlay(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		GameMenuScreen self = (GameMenuScreen) (Object) this;
		List<Drawable> drawables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		List<ButtonWidget> ordered = new ArrayList<>();
		for (var child : self.children()) {
			if (child instanceof ButtonWidget widget) {
				if (velo$canonicalKey(widget) != null) {
					ordered.add(widget);
				}
			}
		}
		ordered.sort(java.util.Comparator.comparingInt(ButtonWidget::getY));
		TitleScreenTheme.drawCompactBranding(context, MinecraftClient.getInstance().textRenderer, self.width,
				TitleScreenTheme.compactHeaderTop(self.width, self.height, ordered.size()));
		TitleScreenTheme.drawVersionCorner(context, MinecraftClient.getInstance().textRenderer, self.height);
		for (ButtonWidget widget : ordered) {
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawGlassButton(context, MinecraftClient.getInstance().textRenderer,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					widget.getMessage(), hovered, widget.active, mouseX, mouseY);
		}

		// Recomputed and repositioned every frame, not just once at init -
		// Flashback's own recording-control buttons (and likely Replay
		// Mod's) are created/positioned lazily during their own render-time
		// hook, not during init(), so they don't exist yet when our
		// one-shot init injection runs. Confirmed by dumping every child
		// widget with both mods actually loaded on the title screen (same
		// lazy-creation pattern there); re-checking here means whenever
		// they do appear, they get caught and repositioned instead of
		// silently staying at whatever position the other mod chose.
		List<ButtonWidget> extraOrdered = new ArrayList<>();
		List<String> extraKeys = new ArrayList<>();
		List<ButtonWidget> sideOrdered = new ArrayList<>();
		List<String> sideKeys = new ArrayList<>();
		ButtonWidget ukulibButton = velo$ukulibButton(self);
		for (var child : self.children()) {
			if (!(child instanceof ButtonWidget widget) || velo$canonicalKey(widget) != null) {
				continue;
			}
			if (widget == ukulibButton) {
				extraOrdered.add(widget);
				extraKeys.add("extensions");
				continue;
			}
			String text = widget.getMessage().getString();
			String sideIconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.RECORDING_SIDE_ICONS, text);
			if (sideIconKey != null) {
				sideOrdered.add(widget);
				sideKeys.add(sideIconKey);
				continue;
			}
			String iconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.ESCAPE_EXTRA_ICONS, text);
			if (iconKey != null) {
				extraOrdered.add(widget);
				extraKeys.add(iconKey);
			}
		}
		if (!extraOrdered.isEmpty() && !ordered.isEmpty()) {
			ButtonWidget lastRow = ordered.get(ordered.size() - 1);
			int iconRowY = Math.min(lastRow.getY() + lastRow.getHeight() + 10, self.height - 30);
			List<TitleScreenTheme.Layout> iconRow = TitleScreenTheme.iconRowLayout(self.width, iconRowY, extraOrdered.size());
			for (int idx = 0; idx < extraOrdered.size(); idx++) {
				ButtonWidget widget = extraOrdered.get(idx);
				drawables.remove(widget);
				velo$position(widget, iconRow.get(idx));
			}
		}
		if (!sideOrdered.isEmpty() && !ordered.isEmpty()) {
			List<TitleScreenTheme.Layout> mainStack = new ArrayList<>();
			for (ButtonWidget widget : ordered) {
				mainStack.add(new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
			}
			List<TitleScreenTheme.Layout> sideColumn = TitleScreenTheme.sideIconColumnLayout(mainStack, sideOrdered.size());
			for (int idx = 0; idx < sideOrdered.size(); idx++) {
				ButtonWidget widget = sideOrdered.get(idx);
				drawables.remove(widget);
				velo$position(widget, sideColumn.get(idx));
			}
		}
		for (int idx = 0; idx < extraOrdered.size(); idx++) {
			ButtonWidget widget = extraOrdered.get(idx);
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawIconSquare(context,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					VeloNavIcons.of(extraKeys.get(idx)), hovered, widget.active, mouseX, mouseY);
		}
		for (int idx = 0; idx < sideOrdered.size(); idx++) {
			ButtonWidget widget = sideOrdered.get(idx);
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawIconSquare(context,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					VeloNavIcons.of(sideKeys.get(idx)), hovered, widget.active, mouseX, mouseY);
		}
	}
}
//?} else {
/*@Mixin(PauseScreen.class)
public abstract class EscapeMenuMixin {

	@Inject(method = "init", at = @At("RETURN"))
	private void velo$restyle(CallbackInfo ci) {
		PauseScreen self = (PauseScreen) (Object) this;
		List<Renderable> renderables = ((ScreenAccessMixin) (Object) this).velo$drawables();

		// Matched by real vanilla translation key only - see the Yarn branch's
		// doc comment above for why "every real button" was tried once and
		// reverted (it swept Essential's own pause-menu buttons into this
		// column instead of leaving them alone).
		Map<String, Button> byKey = new HashMap<>();
		StringWidget vanillaTitle = null;
		for (var child : self.children()) {
			if (child instanceof Button widget) {
				String key = velo$canonicalKey(widget);
				if (key != null) {
					byKey.put(key, widget);
				}
				// The below-stack row and side column are matched and
				// positioned every frame in velo$overlay instead of here.
			} else if (child instanceof StringWidget widget) {
				// Vanilla's real "Game Menu"/"Paused" heading - see class doc.
				vanillaTitle = widget;
			}
		}

		GlassMenuButton storeButton = new GlassMenuButton(0, 0, 1, 1, Text.literal("Store"),
				b -> Minecraft.getInstance().setScreen(new StoreScreen(self)));
		@SuppressWarnings("unchecked")
		List<GuiEventListener> children = (List<GuiEventListener>) self.children();
		children.add(storeButton);
		renderables.add(storeButton);

		if (vanillaTitle != null) {
			children.remove(vanillaTitle);
			renderables.remove(vanillaTitle);
		}

		int rowCount = 0;
		for (String key : TitleScreenTheme.ESCAPE_KEY_ORDER) {
			if (key.equals("$store") || byKey.containsKey(key)) {
				rowCount++;
			}
		}
		int headerTop = TitleScreenTheme.compactHeaderTop(self.width, self.height, rowCount);
		List<TitleScreenTheme.Layout> layout = TitleScreenTheme.stackLayoutFrom(
				self.width, self.height, headerTop + TitleScreenTheme.compactBrandingHeight() + 14, rowCount);
		int i = 0;
		for (String key : TitleScreenTheme.ESCAPE_KEY_ORDER) {
			if (key.equals("$store")) {
				velo$position(storeButton, layout.get(i++));
			} else if (byKey.containsKey(key)) {
				Button widget = byKey.get(key);
				// Deliberately left in `renderables` - see the Yarn branch's
				// doc comment above.
				velo$position(widget, layout.get(i++));
			}
		}
	}

	@Unique
	private static void velo$position(AbstractWidget widget, TitleScreenTheme.Layout l) {
		widget.setX(l.x());
		widget.setY(l.y());
		widget.setWidth(l.width());
		widget.setHeight(l.height());
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

	// See TitleScreenMixin's doc comment on its own copy of this method.
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

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void velo$overlay(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		PauseScreen self = (PauseScreen) (Object) this;
		List<Renderable> renderables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		List<Button> ordered = new ArrayList<>();
		for (var child : self.children()) {
			if (child instanceof Button widget) {
				if (velo$canonicalKey(widget) != null) {
					ordered.add(widget);
				}
			}
		}
		ordered.sort(java.util.Comparator.comparingInt(Button::getY));
		TitleScreenTheme.drawCompactBranding(context, Minecraft.getInstance().font, self.width,
				TitleScreenTheme.compactHeaderTop(self.width, self.height, ordered.size()));
		TitleScreenTheme.drawVersionCorner(context, Minecraft.getInstance().font, self.height);
		for (Button widget : ordered) {
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawGlassButton(context, Minecraft.getInstance().font,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					widget.getMessage(), hovered, widget.active, mouseX, mouseY);
		}

		List<Button> extraOrdered = new ArrayList<>();
		List<String> extraKeys = new ArrayList<>();
		List<Button> sideOrdered = new ArrayList<>();
		List<String> sideKeys = new ArrayList<>();
		Button ukulibButton = velo$ukulibButton(self);
		for (var child : self.children()) {
			if (!(child instanceof Button widget) || velo$canonicalKey(widget) != null) {
				continue;
			}
			if (widget == ukulibButton) {
				extraOrdered.add(widget);
				extraKeys.add("extensions");
				continue;
			}
			String text = widget.getMessage().getString();
			String sideIconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.RECORDING_SIDE_ICONS, text);
			if (sideIconKey != null) {
				sideOrdered.add(widget);
				sideKeys.add(sideIconKey);
				continue;
			}
			String iconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.ESCAPE_EXTRA_ICONS, text);
			if (iconKey != null) {
				extraOrdered.add(widget);
				extraKeys.add(iconKey);
			}
		}
		if (!extraOrdered.isEmpty() && !ordered.isEmpty()) {
			Button lastRow = ordered.get(ordered.size() - 1);
			int iconRowY = Math.min(lastRow.getY() + lastRow.getHeight() + 10, self.height - 30);
			List<TitleScreenTheme.Layout> iconRow = TitleScreenTheme.iconRowLayout(self.width, iconRowY, extraOrdered.size());
			for (int idx = 0; idx < extraOrdered.size(); idx++) {
				Button widget = extraOrdered.get(idx);
				renderables.remove(widget);
				velo$position(widget, iconRow.get(idx));
			}
		}
		if (!sideOrdered.isEmpty() && !ordered.isEmpty()) {
			List<TitleScreenTheme.Layout> mainStack = new ArrayList<>();
			for (Button widget : ordered) {
				mainStack.add(new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
			}
			List<TitleScreenTheme.Layout> sideColumn = TitleScreenTheme.sideIconColumnLayout(mainStack, sideOrdered.size());
			for (int idx = 0; idx < sideOrdered.size(); idx++) {
				Button widget = sideOrdered.get(idx);
				renderables.remove(widget);
				velo$position(widget, sideColumn.get(idx));
			}
		}
		for (int idx = 0; idx < extraOrdered.size(); idx++) {
			Button widget = extraOrdered.get(idx);
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawIconSquare(context,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					VeloNavIcons.of(extraKeys.get(idx)), hovered, widget.active, mouseX, mouseY);
		}
		for (int idx = 0; idx < sideOrdered.size(); idx++) {
			Button widget = sideOrdered.get(idx);
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawIconSquare(context,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					VeloNavIcons.of(sideKeys.get(idx)), hovered, widget.active, mouseX, mouseY);
		}
	}
}
*///?}
