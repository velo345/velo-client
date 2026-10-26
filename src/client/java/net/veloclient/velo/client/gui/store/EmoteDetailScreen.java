package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.emotes.EmoteCatalog;
import net.veloclient.velo.client.emotes.EmoteLibrary;
import net.veloclient.velo.client.emotes.Emotes;
import net.veloclient.velo.client.gui.widget.EntityPreviewWidget;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.util.List;

/**
 * One emote: you doing it in 3D on a loop (with its sounds and particles), the price and Buy (Velo Coins) - and once
 * it's yours, which wheel slot it sits in (1-8) and "Play now".
 */
public final class EmoteDetailScreen extends VeloWindow {

	private static final int PREVIEW_WIDTH = 230;

	private final EmoteCatalog.EmoteType type;
	private String status = "";
	private boolean busy;

	public EmoteDetailScreen(Screen parent, EmoteCatalog.EmoteType type) {
		super(Text.literal(type.name()), 600, 400);
		this.type = type;
		returnTo(parent);
		Emotes.setPreview(type.id());
		onClosed(() -> Emotes.setPreview(null));
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int x = contentX();
		addDrawableChild(new EntityPreviewWidget(x, contentY(), PREVIEW_WIDTH, contentBottom() - contentY()).spin(0f, 20f));

		int ix = x + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		boolean owned = EmoteCatalog.owns(type.id());
		int price = StoreClient.price(type.itemId(), type.price());
		if (!owned) {
			VeloButton buy = new VeloButton(ix, contentY() + 70, iw, 20,
					Text.literal(busy ? "..." : String.format(java.util.Locale.ROOT, "Buy for %,d Velo Coins", price)), b -> buy());
			buy.primary();
			addDrawableChild(buy);
			return;
		}
		boolean inWorld = net.minecraft.client.MinecraftClient.getInstance().player != null;
		VeloButton play = new VeloButton(ix, contentY() + 70, iw, 20, Text.literal(inWorld ? "Play now" : "Play now (join a world)"), b -> playNow());
		play.primary();
		play.active = inWorld;
		addDrawableChild(play);
		// wheel slots 1-8
		int slot = EmoteCatalog.slotOf(type.id());
		int size = Math.min(26, (iw - 7 * 4) / 8);
		for (int i = 0; i < EmoteCatalog.SLOTS; i++) {
			int index = i;
			VeloButton b = new VeloButton(ix + i * (size + 4), contentY() + 122, size, 20, Text.literal(String.valueOf(i + 1)), btn -> {
				EmoteCatalog.setSlot(index, slot == index ? null : type.id());
				status = slot == index ? "Taken off the wheel" : "On your wheel at " + (index + 1);
				layoutContent();
			});
			if (i == slot) {
				b.primary();
			}
			addDrawableChild(b);
		}
	}

	private void playNow() {
		var module = net.veloclient.velo.client.modules.cosmetics.EmotesModule.get();
		if (module == null) {
			return;
		}
		Emotes.setPreview(null);
		this.client.setScreen(null);
		module.play(type.id());
	}

	private void buy() {
		if (busy) {
			return;
		}
		if (!StoreClient.connected()) {
			status = "Connect to Velo Network first (in a world with a Microsoft account)";
			return;
		}
		busy = true;
		status = "Buying...";
		layoutContent();
		StoreClient.buy(type.itemId(), () -> {
			busy = false;
			int slot = EmoteCatalog.slot(0) == null ? 0 : EmoteCatalog.slotOf(type.id());
			status = slot >= 0 ? "It's yours - on your emote wheel (hold B)" : "It's yours - pick a wheel slot below";
			layoutContent();
		}, error -> {
			busy = false;
			status = error;
			layoutContent();
		});
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		// (the preview draws the emote's effects and props itself, in 3D)
		int ix = contentX() + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		int y = contentY();
		int accent = type.accent();
		String tag = "EMOTE";
		VeloDraw.fillRounded(context, ix, y, this.textRenderer.getWidth(tag) + 12, 12, 4, VeloUi.withAlpha(accent, 0xD0));
		context.drawTextWithShadow(this.textRenderer, tag, ix + 5, y + 2, 0xFF101014);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(type.name(), iw), ix, y + 18, VeloStyle.text());
		List<String> lines = VeloUi.wrap(type.description(), iw);
		for (int i = 0; i < Math.min(3, lines.size()); i++) {
			context.drawTextWithShadow(this.textRenderer, lines.get(i), ix, y + 32 + i * 10, VeloStyle.textMuted());
		}
		EmoteLibrary.Emote emote = EmoteLibrary.get(type.id());
		String facts = emote == null ? "Loading..." : String.format(java.util.Locale.ROOT, "%s  ·  %.1f s",
				emote.loop() ? "Loops until you move" : "Plays once", emote.durationSeconds());
		if (emote != null && emote.credit() != null) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("♪ " + emote.credit(), iw), ix, y + 84, VeloUi.withAlpha(accent, 0xFF));
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(facts, iw), ix, y + 96, VeloStyle.textMuted());
		if (EmoteCatalog.owns(type.id())) {
			context.drawTextWithShadow(this.textRenderer, "Emote wheel slot (hold B in game)", ix, y + 110, VeloStyle.textMuted());
			// what's in the slot under the mouse
			int size = Math.min(26, (iw - 7 * 4) / 8);
			for (int i = 0; i < EmoteCatalog.SLOTS; i++) {
				int bx = ix + i * (size + 4);
				if (mouseX >= bx && mouseX < bx + size && mouseY >= y + 122 && mouseY < y + 142) {
					String holder = EmoteCatalog.slot(i);
					String hint = holder == null ? "Slot " + (i + 1) + ": empty" : holder.equals(type.id()) ? "Click to take it off the wheel"
							: "Slot " + (i + 1) + ": replaces " + EmoteCatalog.name(holder);
					context.drawTextWithShadow(this.textRenderer, VeloUi.trim(hint, iw), ix, y + 148, VeloStyle.textMuted());
				}
			}
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, iw), ix, contentBottom() - 12, VeloStyle.text());
	}
}
