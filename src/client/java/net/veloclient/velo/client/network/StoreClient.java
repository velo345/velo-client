package net.veloclient.velo.client.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The game's view of the Velo Coins economy, which lives on the Velo server (balances, owned
 * cosmetics, coin packs, daily rewards). Everything here is a cache of the server's answer -
 * nothing stored on this computer can create coins or items. Calls run off the render thread and
 * report back on the client thread.
 */
public final class StoreClient {

	public record CoinPack(String id, int coins, int bonus, double price) {
		public int total() {
			return coins + bonus;
		}
	}

	public record Quest(String id, String title, long progress, long target, int coins, boolean claimed, boolean claimable, String hint) {
	}

	public record Rewards(int onlineMinutes, boolean loginClaimed, boolean loginClaimable, int loginCoins, int streak,
			List<Quest> quests, int adsWatched, int adsPerDay, int adCoins, boolean adsEnabled, long resetsInSeconds,
			int weeklyBonus, boolean weeklyBonusToday) {
	}

	private static volatile int balance = -1;
	private static volatile Set<String> owned = Set.of();
	private static volatile boolean owner;
	private static volatile String currency = "EUR";
	private static volatile List<CoinPack> packs = List.of();
	private static volatile boolean checkoutEnabled;
	private static volatile Rewards rewards;
	private static volatile long lastWalletRefresh;
	private static volatile String lastError;

	private StoreClient() {
	}

	/** Last known balance, or -1 while unknown (not connected yet). */
	public static int balance() {
		return balance;
	}

	public static boolean owns(String itemId) {
		return owned.contains(itemId);
	}

	public static Set<String> owned() {
		return owned;
	}

	public static boolean isOwner() {
		return owner;
	}

	public static List<CoinPack> packs() {
		return packs;
	}

	public static String currency() {
		return currency;
	}

	public static boolean checkoutEnabled() {
		return checkoutEnabled;
	}

	public static Rewards rewards() {
		return rewards;
	}

	/** Why the last refresh failed (shown instead of an endless "Loading..."), or null. */
	public static String lastError() {
		return lastError;
	}

	public static boolean connected() {
		return VeloServerClient.sessionToken() != null && VeloServerClient.serverBaseUrl() != null;
	}

	private static volatile boolean demo;

	/** Dev-only (screenshot tour): sample numbers so the screen can be checked without a server. */
	public static void loadDemo() {
		demo = true;
		balance = 1340;
		currency = "EUR";
		checkoutEnabled = true;
		packs = List.of(new CoinPack("coins_500", 500, 0, 4.99), new CoinPack("coins_1200", 1200, 140, 9.99),
				new CoinPack("coins_2600", 2600, 400, 19.99), new CoinPack("coins_6500", 6500, 1500, 39.99));
		rewards = new Rewards(42, false, true, 12, 3, List.of(
				new Quest("kill_100", "Defeat 100 mobs", 64, 100, 10, false, false, null),
				new Quest("play_60", "Play for 1 hour", 60, 60, 8, false, true, null),
				new Quest("walk_5k", "Travel 5 km", 5000, 5000, 8, true, false, null)), 1, 3, 6, false, 5 * 3600 + 720, 40, false);
		owner = true;
	}

	// ---- Refresh ----

	/** Refreshes wallet + catalog + rewards (at most every few seconds unless {@code force}). */
	public static void refresh(boolean force, Runnable onDone) {
		if (demo) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		long now = System.currentTimeMillis();
		if (!force && now - lastWalletRefresh < 5_000) {
			if (onDone != null) {
				onDone.run();
			}
			return;
		}
		lastWalletRefresh = now;
		async(() -> {
			// The catalog is public; wallet and rewards need the Velo session.
			applyCatalog(get("/v1/store/catalog"));
			if (VeloServerClient.sessionToken() == null) {
				throw new java.io.IOException("Not connected");
			}
			applyWallet(get("/v1/store/wallet"));
			applyRewards(get("/v1/rewards"));
			return null;
		}, ok -> {
			lastError = null;
			net.veloclient.velo.client.store.StoreRestore.restoreMissing();
			if (onDone != null) {
				onDone.run();
			}
		}, error -> {
			lastError = error;
			if (onDone != null) {
				onDone.run();
			}
		});
	}

	// ---- Actions (results on the client thread; errors carry the server's message) ----

	public static void buy(String itemId, Runnable onOk, Consumer<String> onError) {
		JsonObject body = new JsonObject();
		body.addProperty("itemId", itemId);
		async(() -> post("/v1/store/buy", body), wallet -> {
			applyWallet(wallet);
			onOk.run();
		}, onError);
	}

	/** Starts a coin-pack checkout; {@code onUrl} gets the Tebex payment page + order id. */
	public static void checkout(String packId, java.util.function.BiConsumer<String, String> onUrl, Consumer<String> onError) {
		JsonObject body = new JsonObject();
		body.addProperty("packId", packId);
		async(() -> post("/v1/store/checkout", body),
				result -> onUrl.accept(result.get("checkoutUrl").getAsString(), result.get("orderId").getAsString()), onError);
	}

	/** Order status: pending / paid / review / refunded ... */
	public static void orderStatus(String orderId, Consumer<String> onStatus) {
		async(() -> get("/v1/store/order?id=" + java.net.URLEncoder.encode(orderId, java.nio.charset.StandardCharsets.UTF_8)), result -> {
			balance = result.get("balance").getAsInt();
			onStatus.accept(result.get("status").getAsString());
		}, error -> { });
	}

	public static void claimLogin(Runnable onOk, Consumer<String> onError) {
		async(() -> post("/v1/rewards/login", new JsonObject()), r -> {
			applyRewards(r);
			refresh(true, null);
			onOk.run();
		}, onError);
	}

	public static void claimQuest(String questId, Runnable onOk, Consumer<String> onError) {
		JsonObject body = new JsonObject();
		body.addProperty("questId", questId);
		async(() -> post("/v1/rewards/quest", body), r -> {
			applyRewards(r);
			refresh(true, null);
			onOk.run();
		}, onError);
	}

	/** Opens the rewarded-ad page; {@code onUrl} receives its address. */
	public static void startAd(Consumer<String> onUrl, Consumer<String> onError) {
		async(() -> post("/v1/rewards/ad", new JsonObject()), r -> onUrl.accept(r.get("url").getAsString()), onError);
	}

	/** Server-tracked game statistics increases for today's quests (see RewardsReporter). */
	public static void reportProgress(JsonObject stats) {
		JsonObject body = new JsonObject();
		body.add("stats", stats);
		async(() -> post("/v1/rewards/progress", body), r -> { }, e -> { });
	}

	// ---- Owner tools ----

	public static void adminCoins(String target, int amount, String note, Consumer<JsonObject> onOk, Consumer<String> onError) {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		body.addProperty("amount", amount);
		body.addProperty("note", note);
		async(() -> post("/v1/admin/store/coins", body), onOk, onError);
	}

	// ---- Parsing ----

	private static void applyWallet(JsonObject wallet) {
		balance = wallet.get("balance").getAsInt();
		owner = wallet.has("owner") && wallet.get("owner").getAsBoolean();
		java.util.Set<String> items = new java.util.HashSet<>();
		for (var element : wallet.getAsJsonArray("owned")) {
			items.add(element.getAsString());
		}
		owned = Set.copyOf(items);
	}

	private static void applyCatalog(JsonObject catalog) {
		currency = catalog.get("currency").getAsString();
		checkoutEnabled = catalog.get("checkoutEnabled").getAsBoolean();
		List<CoinPack> list = new ArrayList<>();
		for (var element : catalog.getAsJsonArray("coinPacks")) {
			JsonObject p = element.getAsJsonObject();
			list.add(new CoinPack(p.get("id").getAsString(), p.get("coins").getAsInt(), p.get("bonus").getAsInt(), p.get("price").getAsDouble()));
		}
		packs = List.copyOf(list);
	}

	private static void applyRewards(JsonObject r) {
		List<Quest> quests = new ArrayList<>();
		JsonArray array = r.getAsJsonArray("quests");
		for (var element : array) {
			JsonObject q = element.getAsJsonObject();
			quests.add(new Quest(q.get("id").getAsString(), q.get("title").getAsString(), q.get("progress").getAsLong(),
					q.get("target").getAsLong(), q.get("coins").getAsInt(), q.get("claimed").getAsBoolean(),
					q.get("claimable").getAsBoolean(), q.has("hint") && !q.get("hint").isJsonNull() ? q.get("hint").getAsString() : null));
		}
		rewards = new Rewards(r.get("onlineMinutes").getAsInt(), r.get("loginClaimed").getAsBoolean(), r.get("loginClaimable").getAsBoolean(),
				r.get("loginCoins").getAsInt(), r.get("streak").getAsInt(), List.copyOf(quests), r.get("adsWatched").getAsInt(),
				r.get("adsPerDay").getAsInt(), r.get("adCoins").getAsInt(), r.get("adsEnabled").getAsBoolean(),
				r.get("resetsInSeconds").getAsLong(), r.has("weeklyBonus") ? r.get("weeklyBonus").getAsInt() : 0,
				r.has("weeklyBonusToday") && r.get("weeklyBonusToday").getAsBoolean());
		if (r.has("balance")) {
			balance = r.get("balance").getAsInt();
		}
	}

	// ---- HTTP ----

	@FunctionalInterface
	private interface Call {
		JsonObject run() throws Exception;
	}

	private static void async(Call call, Consumer<JsonObject> onOk, Consumer<String> onError) {
		if (call == null) {
			return;
		}
		CompletableFuture.supplyAsync(() -> {
			try {
				return (Object) call.run();
			} catch (Exception e) {
				return e;
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).thenAccept(result -> MinecraftClient.getInstance().execute(() -> {
			if (result instanceof Exception e) {
				onError.accept(message(e));
			} else {
				onOk.accept((JsonObject) result);
			}
		}));
	}

	private static String message(Exception e) {
		if (e instanceof VeloServerClient.HttpStatusException status) {
			try {
				JsonObject body = JsonParser.parseString(status.getMessage()).getAsJsonObject();
				if (body.has("error")) {
					return body.get("error").getAsString();
				}
			} catch (Exception ignored) {
				// Not JSON.
			}
			return status.status >= 500 ? "The Velo server is unavailable right now" : "Request failed (HTTP " + status.status + ")";
		}
		return connected() ? "Couldn't reach the Velo server" : "Not connected to the Velo server yet";
	}

	private static JsonObject get(String path) throws Exception {
		return send(builder(path).GET().build());
	}

	private static JsonObject post(String path, JsonObject body) throws Exception {
		return send(builder(path).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
	}

	private static HttpRequest.Builder builder(String path) throws Exception {
		String token = VeloServerClient.sessionToken();
		String base = VeloServerClient.serverBaseUrl();
		if (base == null) {
			throw new java.io.IOException("Not connected");
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", "application/json")
				.timeout(Duration.ofSeconds(20));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return builder;
	}

	private static JsonObject send(HttpRequest request) throws Exception {
		HttpResponse<String> response = VeloServerClient.HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new VeloServerClient.HttpStatusException(response.statusCode(), response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}
}
