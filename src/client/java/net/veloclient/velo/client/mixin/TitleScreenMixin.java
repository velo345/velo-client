package net.veloclient.velo.client.mixin;

//? if <26.1 {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.TranslatableTextContent;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fully reskins the vanilla title screen (design spec section 5): a slowly
 * panning single-image panorama (see {@link TitleScreenTheme#drawPanorama},
 * actually applied via {@link TitlePanoramaMixin}), glass-styled buttons
 * instead of vanilla's beveled gray texture, the Velo logo/wordmark/version
 * (vanilla's own logo/splash text cancelled in {@link TitleLogoMixin}/{@link
 * TitleSplashMixin}), and a new "Store" entry.
 *
 * <p>Buttons are matched by real vanilla translation key (see {@link
 * TitleScreenTheme#TITLE_KEY_ORDER}, verified against the actual decompiled
 * class's own string constant pool), snapshotted once right after vanilla's
 * own {@code init()} completes - only those specific buttons are touched at
 * all. Everything else - vanilla's own small Accessibility/Language corner
 * icons, and anything any other mod adds to this screen (Essential,
 * ModMenu, ...) - is left completely alone, in its own original position
 * and style; a previous version of this hid anything not explicitly ours,
 * which broke other mods' own UI outright (Essential's title-screen panel
 * in particular only partially renders once one of its own pieces gets
 * hidden this way) instead of just leaving it be.
 */
//? if <26.1 {
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

	@Inject(method = "init", at = @At("RETURN"))
	private void velo$restyle(CallbackInfo ci) {
		TitleScreen self = (TitleScreen) (Object) this;
		List<Drawable> drawables = ((ScreenAccessMixin) (Object) this).velo$drawables();

		Map<String, ButtonWidget> byKey = new HashMap<>();
		for (var child : self.children()) {
			if (child instanceof ButtonWidget widget) {
				String key = velo$keyOf(widget.getMessage());
				if (key != null && TitleScreenTheme.TITLE_KEY_ORDER.contains(key)) {
					byKey.put(key, widget);
					continue;
				}
				String text = widget.getMessage().getString();
				if (TitleScreenTheme.matchesAny(TitleScreenTheme.TITLE_HIDE_ONLY_NEEDLES, text)) {
					// Not incorporated anywhere - just moved off-screen. A
					// "News" popup button isn't worth a permanent icon slot.
					velo$position(widget, new TitleScreenTheme.Layout(-10000, -10000, 1, 1));
				}
				// The below-stack row and side column (Essential/ModMenu/
				// Flashback/Replay Mod buttons) are matched and positioned
				// every frame in velo$overlay instead of here - see its doc
				// comment for why a one-shot pass at init isn't enough.
			}
		}

		GlassMenuButton storeButton = new GlassMenuButton(0, 0, 1, 1, Text.literal("Store"),
				b -> MinecraftClient.getInstance().setScreen(new StoreScreen(self)));
		@SuppressWarnings("unchecked")
		List<Element> children = (List<Element>) self.children();
		children.add(storeButton);
		drawables.add(storeButton);

		int rowCount = 0;
		for (String key : TitleScreenTheme.TITLE_KEY_ORDER) {
			if (key.equals("$store") || byKey.containsKey(key)) {
				rowCount++;
			}
		}
		List<TitleScreenTheme.Layout> layout = TitleScreenTheme.stackLayout(self.width, self.height, rowCount);
		int i = 0;
		for (String key : TitleScreenTheme.TITLE_KEY_ORDER) {
			if (key.equals("$store")) {
				velo$position(storeButton, layout.get(i++));
			} else if (byKey.containsKey(key)) {
				ButtonWidget widget = byKey.get(key);
				drawables.remove(widget);
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

	@Inject(method = "render", at = @At("RETURN"))
	private void velo$overlay(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		TitleScreen self = (TitleScreen) (Object) this;
		List<Drawable> drawables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		int rowCount = 0;
		for (var child : self.children()) {
			if (child instanceof ButtonWidget widget) {
				String key = velo$keyOf(widget.getMessage());
				if (key != null && TitleScreenTheme.TITLE_KEY_ORDER.contains(key)) {
					rowCount++;
				}
			}
		}
		TitleScreenTheme.drawBranding(context, MinecraftClient.getInstance().textRenderer, self.width,
				TitleScreenTheme.brandingTop(self.width, self.height, rowCount));
		List<ButtonWidget> ordered = new java.util.ArrayList<>();
		for (var child : self.children()) {
			if (!(child instanceof ButtonWidget widget)) {
				continue;
			}
			String key = velo$keyOf(widget.getMessage());
			if (key == null || !TitleScreenTheme.TITLE_KEY_ORDER.contains(key)) {
				continue;
			}
			ordered.add(widget);
		}
		ordered.sort(java.util.Comparator.comparingInt(ButtonWidget::getY));
		for (ButtonWidget widget : ordered) {
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawGlassButton(context, MinecraftClient.getInstance().textRenderer,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					widget.getMessage(), hovered, widget.active);
		}
		// Recomputed and repositioned every frame, not just once at init -
		// Flashback's own title-screen button (and likely Replay Mod's) is
		// created/positioned lazily during ITS OWN render-time hook, not
		// during init(), so it doesn't exist yet when our one-shot init
		// injection runs; confirmed by dumping every child widget with both
		// mods actually loaded - "Open Replays"/"Replay Viewer" were absent
		// at init time. Re-checking here means whenever it does appear, it
		// gets caught and repositioned instead of silently staying at
		// whatever position the other mod chose for it.
		List<ButtonWidget> extraOrdered = new java.util.ArrayList<>();
		List<String> extraKeys = new java.util.ArrayList<>();
		List<ButtonWidget> sideOrdered = new java.util.ArrayList<>();
		List<String> sideKeys = new java.util.ArrayList<>();
		for (var child : self.children()) {
			if (!(child instanceof ButtonWidget widget)) {
				continue;
			}
			String key = velo$keyOf(widget.getMessage());
			if (key != null && TitleScreenTheme.TITLE_KEY_ORDER.contains(key)) {
				continue;
			}
			String text = widget.getMessage().getString();
			if (TitleScreenTheme.matchesAny(TitleScreenTheme.TITLE_HIDE_ONLY_NEEDLES, text)) {
				continue;
			}
			String sideIconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_SIDE_ICONS, text);
			if (sideIconKey == null) {
				sideIconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_SIDE_ICON_CLASSES, widget.getClass().getName());
			}
			if (sideIconKey != null) {
				sideOrdered.add(widget);
				sideKeys.add(sideIconKey);
				continue;
			}
			String iconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_EXTRA_ICONS, text);
			if (iconKey != null) {
				extraOrdered.add(widget);
				extraKeys.add(iconKey);
			}
		}
		if (!extraOrdered.isEmpty() && !ordered.isEmpty()) {
			ButtonWidget lastMain = ordered.get(ordered.size() - 1);
			int iconRowY = Math.min(lastMain.getY() + lastMain.getHeight() + 10, self.height - 30);
			List<TitleScreenTheme.Layout> iconRow = TitleScreenTheme.iconRowLayout(self.width, iconRowY, extraOrdered.size());
			for (int idx = 0; idx < extraOrdered.size(); idx++) {
				ButtonWidget widget = extraOrdered.get(idx);
				// Removed from drawables every frame, not just once - a
				// widget the owning mod (re)creates or repositions lazily
				// (Flashback's title button in particular) would otherwise
				// get vanilla-drawn at its own chosen spot underneath our
				// icon the instant it appears, since init() already ran.
				drawables.remove(widget);
				velo$position(widget, iconRow.get(idx));
			}
		}
		if (!sideOrdered.isEmpty() && !ordered.isEmpty()) {
			List<TitleScreenTheme.Layout> mainStack = new java.util.ArrayList<>();
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
					VeloNavIcons.of(extraKeys.get(idx)), hovered, widget.active);
		}
		for (int idx = 0; idx < sideOrdered.size(); idx++) {
			ButtonWidget widget = sideOrdered.get(idx);
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawIconSquare(context,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					VeloNavIcons.of(sideKeys.get(idx)), hovered, widget.active);
		}
	}
}
//?} else {
/*@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin {

	@Inject(method = "init", at = @At("RETURN"))
	private void velo$restyle(CallbackInfo ci) {
		TitleScreen self = (TitleScreen) (Object) this;
		List<Renderable> renderables = ((ScreenAccessMixin) (Object) this).velo$drawables();

		Map<String, Button> byKey = new HashMap<>();
		for (var child : self.children()) {
			if (child instanceof Button widget) {
				String key = velo$keyOf(widget.getMessage());
				if (key != null && TitleScreenTheme.TITLE_KEY_ORDER.contains(key)) {
					byKey.put(key, widget);
					continue;
				}
				String text = widget.getMessage().getString();
				if (TitleScreenTheme.matchesAny(TitleScreenTheme.TITLE_HIDE_ONLY_NEEDLES, text)) {
					velo$position(widget, new TitleScreenTheme.Layout(-10000, -10000, 1, 1));
				}
				// The below-stack row and side column are matched and
				// positioned every frame in velo$overlay instead of here -
				// see its doc comment for why a one-shot pass isn't enough.
			}
		}

		GlassMenuButton storeButton = new GlassMenuButton(0, 0, 1, 1, Text.literal("Store"),
				b -> Minecraft.getInstance().setScreen(new StoreScreen(self)));
		@SuppressWarnings("unchecked")
		List<GuiEventListener> children = (List<GuiEventListener>) self.children();
		children.add(storeButton);
		renderables.add(storeButton);

		int rowCount = 0;
		for (String key : TitleScreenTheme.TITLE_KEY_ORDER) {
			if (key.equals("$store") || byKey.containsKey(key)) {
				rowCount++;
			}
		}
		List<TitleScreenTheme.Layout> layout = TitleScreenTheme.stackLayout(self.width, self.height, rowCount);
		int i = 0;
		for (String key : TitleScreenTheme.TITLE_KEY_ORDER) {
			if (key.equals("$store")) {
				velo$position(storeButton, layout.get(i++));
			} else if (byKey.containsKey(key)) {
				Button widget = byKey.get(key);
				renderables.remove(widget);
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

	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void velo$overlay(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		TitleScreen self = (TitleScreen) (Object) this;
		List<Renderable> renderables = ((ScreenAccessMixin) (Object) this).velo$drawables();
		int rowCount = 0;
		for (var child : self.children()) {
			if (child instanceof Button widget) {
				String key = velo$keyOf(widget.getMessage());
				if (key != null && TitleScreenTheme.TITLE_KEY_ORDER.contains(key)) {
					rowCount++;
				}
			}
		}
		TitleScreenTheme.drawBranding(context, Minecraft.getInstance().font, self.width,
				TitleScreenTheme.brandingTop(self.width, self.height, rowCount));
		List<Button> ordered = new java.util.ArrayList<>();
		for (var child : self.children()) {
			if (!(child instanceof Button widget)) {
				continue;
			}
			String key = velo$keyOf(widget.getMessage());
			if (key == null || !TitleScreenTheme.TITLE_KEY_ORDER.contains(key)) {
				continue;
			}
			ordered.add(widget);
		}
		ordered.sort(java.util.Comparator.comparingInt(Button::getY));
		for (Button widget : ordered) {
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawGlassButton(context, Minecraft.getInstance().font,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					widget.getMessage(), hovered, widget.active);
		}
		List<Button> extraOrdered = new java.util.ArrayList<>();
		List<String> extraKeys = new java.util.ArrayList<>();
		List<Button> sideOrdered = new java.util.ArrayList<>();
		List<String> sideKeys = new java.util.ArrayList<>();
		for (var child : self.children()) {
			if (!(child instanceof Button widget)) {
				continue;
			}
			String key = velo$keyOf(widget.getMessage());
			if (key != null && TitleScreenTheme.TITLE_KEY_ORDER.contains(key)) {
				continue;
			}
			String text = widget.getMessage().getString();
			if (TitleScreenTheme.matchesAny(TitleScreenTheme.TITLE_HIDE_ONLY_NEEDLES, text)) {
				continue;
			}
			String sideIconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_SIDE_ICONS, text);
			if (sideIconKey == null) {
				sideIconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_SIDE_ICON_CLASSES, widget.getClass().getName());
			}
			if (sideIconKey != null) {
				sideOrdered.add(widget);
				sideKeys.add(sideIconKey);
				continue;
			}
			String iconKey = TitleScreenTheme.matchExtraIcon(TitleScreenTheme.TITLE_EXTRA_ICONS, text);
			if (iconKey != null) {
				extraOrdered.add(widget);
				extraKeys.add(iconKey);
			}
		}
		if (!extraOrdered.isEmpty() && !ordered.isEmpty()) {
			Button lastMain = ordered.get(ordered.size() - 1);
			int iconRowY = Math.min(lastMain.getY() + lastMain.getHeight() + 10, self.height - 30);
			List<TitleScreenTheme.Layout> iconRow = TitleScreenTheme.iconRowLayout(self.width, iconRowY, extraOrdered.size());
			for (int idx = 0; idx < extraOrdered.size(); idx++) {
				Button widget = extraOrdered.get(idx);
				renderables.remove(widget);
				velo$position(widget, iconRow.get(idx));
			}
		}
		if (!sideOrdered.isEmpty() && !ordered.isEmpty()) {
			List<TitleScreenTheme.Layout> mainStack = new java.util.ArrayList<>();
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
					VeloNavIcons.of(extraKeys.get(idx)), hovered, widget.active);
		}
		for (int idx = 0; idx < sideOrdered.size(); idx++) {
			Button widget = sideOrdered.get(idx);
			boolean hovered = mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
					&& mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight();
			TitleScreenTheme.drawIconSquare(context,
					new TitleScreenTheme.Layout(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()),
					VeloNavIcons.of(sideKeys.get(idx)), hovered, widget.active);
		}
	}
}
*///?}
