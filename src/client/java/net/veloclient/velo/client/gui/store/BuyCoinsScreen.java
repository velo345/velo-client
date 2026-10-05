package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.title.TitleScreenTheme;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Velo Coins: buy a coin pack (real payment through Tebex, opened in the browser) on the left,
 * earn free coins on the right (daily reward with streak, today's quests, rewarded ads). The
 * server owns every number shown here; this screen only displays and asks.
 */
public final class BuyCoinsScreen extends VeloWindow {

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private Text status = Text.literal("");
	private int statusColor = 0;
	private String pendingOrder;
	private long nextOrderPoll;
	private TextFieldWidget adminTarget;
	private TextFieldWidget adminAmount;
	private boolean showOwner;

	public BuyCoinsScreen(Screen parent) {
		super(Text.literal("Velo Coins"), 620, 420);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		StoreClient.refresh(true, null);
		if (StoreClient.isOwner() && showOwner) {
			int width = contentWidth();
			int rightX = contentX() + (width - 14) * 52 / 100 + 14;
			int rightWidth = contentX() + width - rightX;
			adminTarget = new TextFieldWidget(this.textRenderer, rightX, contentY() + 34, rightWidth, 18, Text.literal("Player"));
			adminTarget.setPlaceholder(Text.literal("Player name or UUID"));
			adminAmount = new TextFieldWidget(this.textRenderer, rightX, contentY() + 58, rightWidth, 18, Text.literal("Amount"));
			adminAmount.setPlaceholder(Text.literal("Amount, e.g. 500 or -200"));
			addDrawableChild(adminTarget);
			addDrawableChild(adminAmount);
		} else {
			adminTarget = null;
			adminAmount = null;
		}
	}

	private void setStatus(String text, boolean error) {
		status = Text.literal(text);
		statusColor = error ? 0xFFFF7B7B : 0xFF6FE0A4;
	}

	@Override
	public void tick() {
		super.tick();
		if (pendingOrder != null && System.currentTimeMillis() > nextOrderPoll) {
			nextOrderPoll = System.currentTimeMillis() + 4000;
			String order = pendingOrder;
			StoreClient.orderStatus(order, state -> {
				switch (state) {
					case "paid" -> {
						pendingOrder = null;
						setStatus("Payment received - coins added! Thank you <3", false);
						StoreClient.refresh(true, null);
					}
					case "review" -> {
						pendingOrder = null;
						setStatus("Your payment is being checked - coins arrive once it's confirmed.", true);
					}
					case "refunded", "chargeback" -> pendingOrder = null;
					default -> {
					}
				}
			});
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		hits.clear();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		int leftWidth = (width - 14) * 52 / 100;
		int rightX = x + leftWidth + 14;
		int rightWidth = x + width - rightX;

		// Balance header.
		drawCoin(context, x + 9, y + 9, 9);
		int balance = StoreClient.balance();
		String balanceText = balance < 0 ? "..." : String.format(Locale.ROOT, "%,d", balance);
		scaledText(context, Text.literal(balanceText), x + 24, y + 2, 1.5f, 0xFFFFD56A);
		context.drawTextWithShadow(this.textRenderer, "Velo Coins", x + 26 + Math.round(this.textRenderer.getWidth(balanceText) * 1.5f),
				y + 6, VeloStyle.textMuted());

		drawPacks(context, x, y + 26, leftWidth, mouseX, mouseY);
		if (StoreClient.isOwner()) {
			hits.add(VeloUi.pill(context, rightX + rightWidth - 54, y + 2, 54, 14, showOwner ? "Rewards" : "Owner", 0, mouseX, mouseY, () -> {
				showOwner = !showOwner;
				layoutContent();
			}));
		}
		if (showOwner && StoreClient.isOwner()) {
			drawOwner(context, rightX, y, rightWidth, mouseX, mouseY);
		} else {
			drawRewards(context, rightX, y, rightWidth, mouseX, mouseY);
		}
		if (!status.getString().isEmpty()) {
			for (String line : VeloUi.wrap(status.getString(), leftWidth)) {
				context.drawTextWithShadow(this.textRenderer, line, x, contentBottom() - 9, statusColor == 0 ? VeloStyle.textMuted() : statusColor);
				break;
			}
		}
	}

	private void drawOwner(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont("Owner tools"), x, y + 6, VeloStyle.accent());
		context.drawTextWithShadow(this.textRenderer, "Give (or take with -) coins to any player.", x, y + 20, VeloStyle.textFaint());
		hits.add(VeloUi.pill(context, x, y + 82, width, 18, "Apply coins", 1, mouseX, mouseY, this::giveCoins));
		context.drawTextWithShadow(this.textRenderer, "Items, lookups and the ledger: launcher > Velo Coins.", x, y + 108, VeloStyle.textFaint());
	}

	// ---- Coin packs ----

	private void drawPacks(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont("Get coins"), x, y, VeloStyle.text());
		List<StoreClient.CoinPack> packs = StoreClient.packs();
		if (packs.isEmpty()) {
			String why = StoreClient.lastError() != null ? StoreClient.lastError()
					: StoreClient.connected() ? "Loading..." : "Connecting to the Velo server...";
			for (String line : VeloUi.wrap(why, width)) {
				context.drawTextWithShadow(this.textRenderer, line, x, y + 16, VeloStyle.textMuted());
				y += 11;
			}
			return;
		}
		int rowH = 34;
		int gap = 5;
		int bestIndex = Math.min(2, packs.size() - 1);
		int cy = y + 13;
		for (int i = 0; i < packs.size(); i++) {
			StoreClient.CoinPack pack = packs.get(i);
			boolean hovered = VeloUi.inside(mouseX, mouseY, x, cy, width, rowH);
			int card = VeloAnim.lerpArgb(VeloStyle.card(), VeloStyle.cardHover(), hovered ? 1 : 0);
			VeloDraw.fillRoundedGradient(context, x, cy, width, rowH, 8, VeloAnim.lerpArgb(card, 0xFFF4B82E, 0.10f), card);
			VeloDraw.strokeRounded(context, x, cy, width, rowH, 8, i == bestIndex ? 0xC0F4B82E : VeloStyle.border());
			// A pile of coins - bigger packs, bigger pile.
			drawCoinPile(context, x + 20, cy + rowH / 2f + 2, Math.min(5, 1 + i));
			String total = String.format(Locale.ROOT, "%,d", pack.total());
			scaledText(context, Text.literal(total), x + 42, cy + 6, 1.25f, 0xFFFFD56A);
			if (pack.bonus() > 0) {
				context.drawTextWithShadow(this.textRenderer, "+" + String.format(Locale.ROOT, "%,d", pack.bonus()) + " bonus",
						x + 42, cy + 21, 0xFF6FE0A4);
			} else {
				context.drawTextWithShadow(this.textRenderer, "coins", x + 42, cy + 21, VeloStyle.textFaint());
			}
			if (i == bestIndex) {
				int tagW = this.textRenderer.getWidth("BEST") + 8;
				int tagX = x + 46 + Math.round(this.textRenderer.getWidth(total) * 1.25f);
				VeloDraw.fillRounded(context, tagX, cy + 7, tagW, 11, 5, 0xFFF4B82E);
				context.drawTextWithShadow(this.textRenderer, "BEST", tagX + 4, cy + 9, 0xFF3A2400);
			}
			boolean busy = pendingOrder != null;
			int buttonW = 72;
			hits.add(VeloUi.pill(context, x + width - buttonW - 8, cy + 8, buttonW, 18, busy ? "Waiting..." : formatPrice(pack.price()),
					busy ? 0 : 1, mouseX, mouseY, () -> buyPack(pack)));
			cy += rowH + gap;
		}
		String note = StoreClient.checkoutEnabled() ? "Secure checkout by Tebex opens in your browser." : "Buying is not open yet on this server.";
		for (String line : VeloUi.wrap(note, width)) {
			context.drawTextWithShadow(this.textRenderer, line, x, cy + 2, VeloStyle.textFaint());
			cy += 10;
		}
	}

	private void buyPack(StoreClient.CoinPack pack) {
		if (pendingOrder != null) {
			return;
		}
		setStatus("Opening checkout...", false);
		StoreClient.checkout(pack.id(), (url, orderId) -> {
			pendingOrder = orderId;
			nextOrderPoll = System.currentTimeMillis() + 6000;
			VeloUi.openUrl(url);
			setStatus("Finish the payment in your browser - this updates by itself.", false);
		}, error -> setStatus(error, true));
	}

	private String formatPrice(double price) {
		String symbol = switch (StoreClient.currency()) {
			case "EUR" -> "€";
			case "USD" -> "$";
			case "GBP" -> "£";
			default -> StoreClient.currency() + " ";
		};
		return symbol + String.format(Locale.ROOT, "%.2f", price);
	}

	// ---- Free coins ----

	private void drawRewards(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont("Free coins"), x, y + 6, VeloStyle.text());
		StoreClient.Rewards rewards = StoreClient.rewards();
		if (rewards == null) {
			String why = StoreClient.lastError() != null ? StoreClient.lastError()
					: StoreClient.connected() ? "Loading..." : "Sign in to the Velo server to earn free coins.";
			for (String line : VeloUi.wrap(why, width)) {
				context.drawTextWithShadow(this.textRenderer, line, x, y + 22, VeloStyle.textMuted());
				y += 11;
			}
			return;
		}
		long hours = rewards.resetsInSeconds() / 3600;
		long minutes = (rewards.resetsInSeconds() % 3600) / 60;
		String reset = "New in " + hours + "h " + minutes + "m";
		// Leave room for the owner toggle pill in the top-right corner.
		int resetRight = x + width - (StoreClient.isOwner() ? 60 : 0);
		context.drawTextWithShadow(this.textRenderer, reset, resetRight - this.textRenderer.getWidth(reset), y + 6, VeloStyle.textFaint());

		// Daily reward with a 7-day streak row.
		int cy = y + 20;
		int cardH = 38;
		VeloDraw.fillRounded(context, x, cy, width, cardH, 8, VeloStyle.card());
		VeloDraw.strokeRounded(context, x, cy, width, cardH, 8, VeloStyle.border());
		context.drawTextWithShadow(this.textRenderer, "Daily reward", x + 8, cy + 7, VeloStyle.text());
		drawCoin(context, x + 8 + this.textRenderer.getWidth("Daily reward") + 10, cy + 11, 4);
		context.drawTextWithShadow(this.textRenderer, "+" + rewards.loginCoins(), x + this.textRenderer.getWidth("Daily reward") + 26, cy + 7, 0xFFFFD56A);
		int days = rewards.streak() + (rewards.loginClaimed() ? 1 : 0);
		int litInWeek = days == 0 ? 0 : (days - 1) % 7 + 1;
		for (int d = 0; d < 7; d++) {
			boolean lit = d < litInWeek;
			VeloDraw.fillRounded(context, x + 8 + d * 11, cy + 22, 8, 8, 2, lit ? 0xFFF4B82E : VeloStyle.sunken());
		}
		// The 7th dot is the weekly bonus day.
		VeloDraw.strokeRounded(context, x + 8 + 6 * 11 - 1, cy + 21, 10, 10, 3, 0xFF6FE0A4);
		if (rewards.weeklyBonus() > 0) {
			String week = rewards.weeklyBonusToday() ? "Week bonus!" : "Day 7 +" + rewards.weeklyBonus();
			context.drawTextWithShadow(this.textRenderer, week, x + 92, cy + 22, 0xFF6FE0A4);
		}
		String loginLabel = rewards.loginClaimed() ? "Claimed" : rewards.loginClaimable() ? "Claim" : "Play 5 min";
		int tone = rewards.loginClaimable() ? 1 : 0;
		hits.add(VeloUi.pill(context, x + width - 66, cy + 10, 58, 18, loginLabel, tone, mouseX, mouseY, () -> {
			if (rewards.loginClaimable()) {
				StoreClient.claimLogin(() -> setStatus("Daily reward claimed!", false), e -> setStatus(e, true));
			}
		}));

		// Today's quests.
		int qy = cy + cardH + 5;
		for (StoreClient.Quest quest : rewards.quests()) {
			int qh = 28;
			VeloDraw.fillRounded(context, x, qy, width, qh, 7, VeloStyle.card());
			VeloDraw.strokeRounded(context, x, qy, width, qh, 7, quest.claimable() ? 0xA06FE0A4 : VeloStyle.border());
			int barW = width - 82;
			String progress = quest.hint() != null ? "" : compact(quest.progress()) + "/" + compact(quest.target());
			int progressW = this.textRenderer.getWidth(progress);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(quest.title(), barW - progressW - 6), x + 8, qy + 5,
					quest.claimed() ? VeloStyle.textFaint() : VeloStyle.text());
			context.drawTextWithShadow(this.textRenderer, progress, x + 8 + barW - progressW, qy + 5, VeloStyle.textFaint());
			float fraction = quest.target() <= 0 ? 1 : Math.min(1f, quest.progress() / (float) quest.target());
			VeloDraw.fillRounded(context, x + 8, qy + 18, barW, 4, 2, VeloStyle.sunken());
			if (fraction > 0) {
				VeloDraw.fillRounded(context, (float) x + 8, qy + 18, Math.max(4, barW * fraction), 4, 2,
						quest.claimed() ? 0xFF6FE0A4 : VeloStyle.accent());
			}
			String label = quest.claimed() ? "Done" : "+" + quest.coins();
			hits.add(VeloUi.pill(context, x + width - 66, qy + 5, 58, 18, label, quest.claimable() ? 2 : 0, mouseX, mouseY, () -> {
				if (quest.claimable()) {
					StoreClient.claimQuest(quest.id(), () -> setStatus("Quest reward claimed!", false), e -> setStatus(e, true));
				} else if (quest.hint() != null) {
					setStatus(quest.hint(), true);
				}
			}));
			qy += qh + 4;
		}

		// Rewarded ads - only shown once the server has an ad network set up.
		if (!rewards.adsEnabled()) {
			return;
		}
		int ay = qy + 1;
		VeloDraw.fillRounded(context, x, ay, width, 28, 7, VeloStyle.card());
		VeloDraw.strokeRounded(context, x, ay, width, 28, 7, VeloStyle.border());
		context.drawTextWithShadow(this.textRenderer, "Watch an ad  +" + rewards.adCoins(), x + 8, ay + 5, VeloStyle.text());
		context.drawTextWithShadow(this.textRenderer, rewards.adsWatched() + " / " + rewards.adsPerDay() + " today", x + 8, ay + 16,
				VeloStyle.textFaint());
		boolean adsLeft = rewards.adsEnabled() && rewards.adsWatched() < rewards.adsPerDay();
		hits.add(VeloUi.pill(context, x + width - 66, ay + 5, 58, 18, adsLeft ? "Watch" : "Done",
				adsLeft ? 1 : 0, mouseX, mouseY, () -> {
					if (adsLeft) {
						StoreClient.startAd(url -> {
							VeloUi.openUrl(url);
							setStatus("The ad opened in your browser - coins arrive right after it ends.", false);
						}, e -> setStatus(e, true));
					}
				}));
	}

	private void giveCoins() {
		if (adminTarget == null) {
			return;
		}
		int amount;
		try {
			amount = Integer.parseInt(adminAmount.getText().trim().replace("+", ""));
		} catch (NumberFormatException e) {
			setStatus("Enter an amount like 500 or -200", true);
			return;
		}
		StoreClient.adminCoins(adminTarget.getText().trim(), amount, "in-game", view -> {
			setStatus("Done - " + view.get("username").getAsString() + " now has " + view.get("balance").getAsInt() + " coins", false);
			StoreClient.refresh(true, null);
		}, e -> setStatus(e, true));
	}

	// ---- Drawing helpers ----

	private static String compact(long n) {
		return n >= 10_000 ? String.format(Locale.ROOT, "%.1fk", n / 1000.0) : String.valueOf(n);
	}

	private void scaledText(DrawContext context, Text text, int x, int y, float scale, int color) {
		context.getMatrices().pushMatrix();
		context.getMatrices().translate(x, y);
		context.getMatrices().scale(scale, scale);
		context.drawTextWithShadow(this.textRenderer, TitleScreenTheme.tileFont(text.getString()), 0, 0, color);
		context.getMatrices().popMatrix();
	}

	/** A gold coin (rim, face, shine). */
	static void drawCoin(DrawContext context, float cx, float cy, float r) {
		VeloDraw.fillCircle(context, cx, cy + r * 0.12f, r, 0xFF9A6410);
		VeloDraw.fillCircle(context, cx, cy, r, 0xFFE3A21F);
		VeloDraw.fillCircle(context, cx, cy, r * 0.74f, 0xFFFFCF4D);
		VeloDraw.fillCircle(context, cx - r * 0.25f, cy - r * 0.28f, r * 0.22f, 0xB0FFF4C8);
	}

	/** A small pile: a few coins stacked, one leaning on top. */
	private static void drawCoinPile(DrawContext context, float cx, float baseY, int coins) {
		float r = 7;
		for (int i = 0; i < coins; i++) {
			float x = cx + ((i % 3) - 1) * 6;
			float y = baseY - (i / 3) * 5 - (i % 2) * 2;
			drawCoin(context, x, y, r);
		}
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}
}
