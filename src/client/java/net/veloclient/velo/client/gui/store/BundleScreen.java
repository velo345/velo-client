package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.StoreCard;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.util.List;
import java.util.Locale;

/**
 * One bundle, as in the launcher: its name and what it is on the left with the price (struck-through total of the
 * items one by one, and what you save) and Buy; every item in it as a Store card on the right (click for its page).
 * Owning part of a bundle lowers its price - the server works out "the rest".
 */
public final class BundleScreen extends VeloWindow {

	private static final int GAP = 10;

	private final StoreClient.Bundle bundle;
	private String status = "";
	private boolean statusError;
	private boolean busy;

	public BundleScreen(Screen parent, StoreClient.Bundle bundle) {
		super(Text.literal(bundle.name()), 760, 440);
		this.bundle = bundle;
		returnTo(parent);
	}

	private int infoWidth() {
		return Math.min(250, contentWidth() * 2 / 5);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int cx = contentX();
		int y = contentY();
		int infoW = infoWidth();
		int owned = StoreItems.ownedCount(bundle);
		boolean all = owned == bundle.items().size();
		if (bundle.price() >= 0) {
			Text label = Text.literal(all ? "Owned" : owned > 0 ? "Buy the rest" : "Buy bundle");
			VeloButton buy = new VeloButton(cx, contentBottom() - 24, infoW, 24, label, b -> buy());
			buy.active = !all && !busy;
			if (!all) {
				buy.primary();
			}
			addDrawableChild(buy);
		}
		// The items, as cards.
		int gx = cx + infoW + 20;
		int gw = cx + contentWidth() - gx;
		int columns = Math.max(1, Math.min(3, (gw + GAP) / (130 + GAP)));
		int cardW = (gw - (columns - 1) * GAP) / columns;
		int cardH = Math.min((contentBottom() - y - GAP) / 2, cardW * 3 / 4 + StoreCard.INFO_H);
		List<String> items = bundle.items();
		for (int i = 0; i < items.size(); i++) {
			ClickableWidget card = StoreItems.card(this, items.get(i), cardW, cardH);
			int row = i / columns;
			int cardY = y + row * (cardH + GAP);
			if (card == null || cardY + cardH > contentBottom()) {
				continue;
			}
			card.setX(gx + i % columns * (cardW + GAP));
			card.setY(cardY);
			addDrawableChild(card);
		}
	}

	private void buy() {
		if (busy) {
			return;
		}
		busy = true;
		status = "Buying...";
		statusError = false;
		layoutContent();
		StoreClient.buy("bundle:" + bundle.id(), () -> {
			busy = false;
			status = bundle.name() + " is in your Locker";
			StoreClient.refresh(true, this::layoutContent);
			layoutContent();
		}, error -> {
			busy = false;
			status = error;
			statusError = true;
			layoutContent();
		});
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		int x = contentX();
		int y = contentY();
		int w = infoWidth();
		Text tag = MenuKit.small("BUNDLE  ·  " + bundle.items().size() + " ITEMS");
		int tagW = this.textRenderer.getWidth(tag) + 10;
		VeloDraw.fillRounded(context, x, y, tagW, 11, 3, (MenuKit.accent() & 0xFFFFFF) | 0x40000000);
		MenuKit.text(context, this.textRenderer, tag, x + 5, y + 3, MenuKit.accentText());
		y += 20;
		Text title = MenuKit.displayLg(bundle.name());
		if (this.textRenderer.getWidth(title) > w) {
			title = MenuKit.display(bundle.name());
		}
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, title, w), x, y, MenuKit.TEXT);
		y += MenuKit.H_DISPLAY_LG + 10;
		List<Text> lines = MenuKit.wrap(this.textRenderer, bundle.description(), MenuKit::ui, w);
		for (int i = 0; i < Math.min(6, lines.size()); i++) {
			MenuKit.text(context, this.textRenderer, lines.get(i), x, y, MenuKit.TEXT_2);
			y += 11;
		}
		y += 10;
		// What's in it: a picture per item, ticked when you own it.
		int size = 26;
		int ix = x;
		for (String id : bundle.items()) {
			if (ix + size > x + w) {
				break;
			}
			StoreItems.drawArt(context, id, ix, y, size);
			if (StoreItems.owns(id)) {
				VeloDraw.fillCircle(context, ix + size - 4f, y + 4f, 4f, MenuKit.GREEN);
			}
			ix += size + 5;
		}
		y += size + 14;
		// Price: today's bundle price, the items' total struck through, and the saving.
		int separate = StoreItems.separatePrice(bundle);
		int py = contentBottom() - 24 - 22;
		if (bundle.price() >= 0) {
			StoreCard.coin(context, x + 5f, py + 4.5f, 5f);
			Text price = MenuKit.displaySm(String.format(Locale.ROOT, "%,d", bundle.price()));
			MenuKit.text(context, this.textRenderer, price, x + 14, py, 0xFFFFD56A);
			int after = x + 14 + this.textRenderer.getWidth(price) + 8;
			if (separate > bundle.price()) {
				Text was = MenuKit.ui(String.format(Locale.ROOT, "%,d", separate));
				MenuKit.text(context, this.textRenderer, was, after, py + 1, MenuKit.TEXT_3);
				context.fill(after - 1, py + 4, after + this.textRenderer.getWidth(was) + 1, py + 5, MenuKit.TEXT_3);
				after += this.textRenderer.getWidth(was) + 8;
				Text save = MenuKit.small("SAVE " + Math.round(100.0 * (separate - bundle.price()) / separate) + "%");
				int sw = this.textRenderer.getWidth(save) + 10;
				VeloDraw.fillRounded(context, after, py, sw, 11, 3, 0x333DDC97);
				MenuKit.text(context, this.textRenderer, save, after + 5, py + 3, MenuKit.GREEN);
			}
		} else {
			MenuKit.text(context, this.textRenderer, MenuKit.ui(String.format(Locale.ROOT, "%,d coins for all of it, one by one", separate)), x, py, MenuKit.TEXT_2);
		}
		if (!status.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), w), x, py - 14,
					statusError ? MenuKit.RED_TEXT : MenuKit.GREEN);
		}
	}
}
