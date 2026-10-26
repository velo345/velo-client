package net.veloclient.velo.client.gui.store;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.aura.AuraManager;
import net.veloclient.velo.client.cosmetics.wings.WingsManager;
import net.veloclient.velo.client.gui.widget.AuraGuiRenderer;
import net.veloclient.velo.client.gui.widget.VeloAuraCard;
import net.veloclient.velo.client.gui.widget.VeloCapeRender;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStoreItemTile;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.widget.VeloWingCard;
import net.veloclient.velo.client.network.StoreClient;
import net.veloclient.velo.client.cosmetics.AnimatedCapeAsset;
import net.veloclient.velo.client.store.StoreAssets;
import net.veloclient.velo.client.store.StoreCatalog;
import net.veloclient.velo.client.store.StoreItem;
import net.veloclient.velo.client.store.StoreOwnership;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/** Any Store item by its id ("wings_..", "aura_..", "emote_..", or a cape id): its card, picture, price and whether you own it. */
final class StoreItems {

	private StoreItems() {
	}

	private static Optional<WingsManager.WingType> wings(String itemId) {
		return WingsManager.catalog().stream().filter(w -> w.itemId().equals(itemId)).findFirst();
	}

	private static Optional<AuraManager.AuraType> aura(String itemId) {
		return AuraManager.catalog().stream().filter(a -> a.itemId().equals(itemId)).findFirst();
	}

	private static Optional<net.veloclient.velo.client.emotes.EmoteCatalog.EmoteType> emote(String itemId) {
		if (itemId == null || !itemId.startsWith(net.veloclient.velo.client.emotes.EmoteCatalog.ITEM_PREFIX)) {
			return Optional.empty();
		}
		return net.veloclient.velo.client.emotes.EmoteCatalog.type(itemId.substring(net.veloclient.velo.client.emotes.EmoteCatalog.ITEM_PREFIX.length()));
	}

	/** The item's Store card (opens its page), or null if it's unknown here. */
	static ClickableWidget card(Screen parent, String itemId, int width, int height) {
		MinecraftClient client = MinecraftClient.getInstance();
		var wing = wings(itemId);
		if (wing.isPresent()) {
			return new VeloWingCard(width, height, wing.get(), () -> client.setScreen(new WingDetailScreen(parent, wing.get())));
		}
		var aura = aura(itemId);
		if (aura.isPresent()) {
			return new VeloAuraCard(width, height, aura.get(), () -> client.setScreen(new AuraDetailScreen(parent, aura.get())));
		}
		var emote = emote(itemId);
		if (emote.isPresent()) {
			return new net.veloclient.velo.client.gui.widget.VeloEmoteCard(width, height, emote.get(),
					() -> client.setScreen(new EmoteDetailScreen(parent, emote.get())));
		}
		return StoreCatalog.byId(itemId).<ClickableWidget>map(item -> new VeloStoreItemTile(0, 0, width, height - net.veloclient.velo.client.gui.widget.StoreCard.INFO_H,
				item, () -> client.setScreen(new StoreItemDetailScreen(parent, item)))).orElse(null);
	}

	/** What the item costs today (the server's price once the catalog is in). */
	static int price(String itemId) {
		var wing = wings(itemId);
		if (wing.isPresent()) {
			return StoreClient.price(itemId, wing.get().price());
		}
		var aura = aura(itemId);
		if (aura.isPresent()) {
			return StoreClient.price(itemId, aura.get().price());
		}
		var emote = emote(itemId);
		if (emote.isPresent()) {
			return StoreClient.price(itemId, emote.get().price());
		}
		return StoreCatalog.byId(itemId).map(i -> StoreClient.price(itemId, i.priceCoins())).orElse(0);
	}

	/** Its usual price - what it costs when it isn't one of today's deals. */
	static int regularPrice(String itemId) {
		Integer regular = StoreClient.regularPrice(itemId);
		return regular != null ? regular : price(itemId);
	}

	static boolean owns(String itemId) {
		var wing = wings(itemId);
		if (wing.isPresent()) {
			return WingsManager.owns(wing.get().id());
		}
		var aura = aura(itemId);
		if (aura.isPresent()) {
			return AuraManager.owns(aura.get().id());
		}
		if (emote(itemId).isPresent()) {
			return StoreClient.owns(itemId);
		}
		return StoreOwnership.owns(itemId);
	}

	static String name(String itemId) {
		return wings(itemId).map(WingsManager.WingType::name)
				.or(() -> aura(itemId).map(AuraManager.AuraType::name))
				.or(() -> emote(itemId).map(net.veloclient.velo.client.emotes.EmoteCatalog.EmoteType::name))
				.or(() -> StoreCatalog.byId(itemId).map(StoreItem::name)).orElse(itemId);
	}

	static int accent(String itemId) {
		return wings(itemId).map(WingsManager.WingType::accent)
				.or(() -> aura(itemId).map(AuraManager.AuraType::accent))
				.or(() -> emote(itemId).map(net.veloclient.velo.client.emotes.EmoteCatalog.EmoteType::accent)).orElse(0xFF8B7CF6);
	}

	/** A small square picture of the item on a glow in its colour. */
	static void drawArt(DrawContext context, String itemId, int x, int y, int size) {
		int accent = accent(itemId);
		VeloDraw.fillRounded(context, x, y, size, size, 6, 0xFF1C1C23);
		VeloDraw.fillRoundedGradient(context, x, y, size, size, 6, VeloUi.withAlpha(accent, 0x38), 0x001C1C23);
		var wing = wings(itemId);
		if (wing.isPresent()) {
			Identifier picture = VeloWingCard.previewImage(wing.get().id());
			if (picture != null) {
				int pw = size - 4;
				int ph = pw * 3 / 4;
				context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, picture, x + 2, y + (size - ph) / 2, 0f, 0f, pw, ph, pw, ph, pw, ph);
			}
			return;
		}
		var aura = aura(itemId);
		if (aura.isPresent()) {
			context.enableScissor(x, y, x + size, y + size);
			AuraGuiRenderer.draw(context, AuraManager.emitter("store-art:" + itemId, aura.get().kind()), AuraEffects.Settings.DEFAULT,
					x + size / 2f, y + size - 3, size / 2.8f);
			context.disableScissor();
			return;
		}
		var emote = emote(itemId);
		if (emote.isPresent()) {
			context.enableScissor(x, y, x + size, y + size);
			net.veloclient.velo.client.gui.widget.EmoteFigure.draw(context, ("store-art:" + itemId).intern(), emote.get().id(), x + size / 2f, y + size - 3,
					(size - 6) / 34f, emote.get().accent());
			context.disableScissor();
			return;
		}
		StoreCatalog.byId(itemId).ifPresent(item -> {
			AnimatedCapeAsset preview = StoreAssets.preview(item);
			if (preview != null) {
				VeloCapeRender.draw(context, preview.identifier(), preview.width(), preview.height(), x + size / 2f, y + size - 3, size - 8,
						VeloCapeRender.idleYaw(0f, 0f));
			}
		});
	}

	/**
	 * Today's four picks: the server's when it sent them, else the same four the launcher shows - every item for sale,
	 * sorted, shuffled with a seed from today's date.
	 */
	static List<String> dailyPicks() {
		List<String> ids = new ArrayList<>(StoreClient.dailyIds());
		if (ids.isEmpty()) {
			List<String> pool = new ArrayList<>();
			WingsManager.forSale().forEach(w -> pool.add(w.itemId()));
			StoreCatalog.all().stream().filter(StoreItem::inStore).forEach(i -> pool.add(i.id()));
			AuraManager.forSale().forEach(a -> pool.add(a.itemId()));
			net.veloclient.velo.client.emotes.EmoteCatalog.forSale().forEach(e -> pool.add(e.itemId()));
			Collections.sort(pool);
			Collections.shuffle(pool, new Random(LocalDate.now(ZoneOffset.UTC).toEpochDay() * 31L + 7));
			ids.addAll(pool.subList(0, Math.min(4, pool.size())));
		}
		return ids;
	}

	/** When today's picks change: the server's time, else the next midnight UTC. */
	static long dailyEndsAt() {
		long ends = StoreClient.dailyEndsAt();
		return ends > 0 ? ends : LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
	}

	/** The bundles on offer: the server's, else each collection as one (no price - its items' total is shown). */
	static List<StoreClient.Bundle> bundles() {
		if (!StoreClient.bundles().isEmpty()) {
			return StoreClient.bundles();
		}
		List<StoreClient.Bundle> out = new ArrayList<>();
		for (var collection : WingsManager.collections()) {
			var pairs = WingsManager.inCollection(collection.id()).stream().filter(WingsManager.WingType::inStore).toList();
			if (pairs.size() > 1) {
				out.add(new StoreClient.Bundle(collection.id(), collection.name(), collection.description(),
						pairs.stream().map(WingsManager.WingType::itemId).toList(), -1));
			}
		}
		return out;
	}

	/** What a bundle's items cost one by one. */
	static int separatePrice(StoreClient.Bundle bundle) {
		int total = 0;
		for (String id : bundle.items()) {
			total += regularPrice(id);
		}
		return total;
	}

	static int ownedCount(StoreClient.Bundle bundle) {
		int n = 0;
		for (String id : bundle.items()) {
			if (owns(id)) {
				n++;
			}
		}
		return n;
	}

	static String countdown(long untilMillis) {
		long minutes = Math.max(0, (untilMillis - System.currentTimeMillis()) / 60_000);
		long days = minutes / 1440;
		long hours = minutes % 1440 / 60;
		long mins = minutes % 60;
		return days > 0 ? days + "d " + hours + "h" : hours + "h " + mins + "m";
	}
}
