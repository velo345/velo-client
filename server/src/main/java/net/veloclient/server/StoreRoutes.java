package net.veloclient.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.StoreService;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * HTTP surface of the Velo Coins economy ({@link StoreService}):
 * <ul>
 * <li>{@code GET /v1/store/catalog} (public) - prices, coin packs, what's enabled</li>
 * <li>{@code GET /v1/store/wallet}, {@code POST /v1/store/buy}, {@code POST /v1/store/checkout},
 * {@code GET /v1/store/order} - signed-in player</li>
 * <li>{@code POST /v1/store/webhook/tebex} - Tebex payment events (signature-checked)</li>
 * <li>{@code POST /v1/admin/store/{lookup,coins,item}} - owner only</li>
 * <li>{@code GET /v1/rewards}, {@code POST /v1/rewards/{login,quest,progress,ad}} - free coins</li>
 * <li>{@code GET /v1/rewards/ayet} - ayeT-Studios' rewarded-video callback (HMAC-checked)</li>
 * <li>{@code GET /ads.txt} - the ads.txt the ad network requires (from {@code data/ads.txt})</li>
 * <li>{@code GET /store/done}, {@code GET /rewards/ad} - small pages opened in the player's browser</li>
 * </ul>
 */
final class StoreRoutes {

	private StoreRoutes() {
	}

	@FunctionalInterface
	private interface Authed {
		void handle(HttpExchange exchange, SessionRegistry.Session session) throws Exception;
	}

	private record BuyRequest(String itemId) {
	}

	private record CheckoutRequest(String packId) {
	}

	private record QuestRequest(String questId) {
	}

	private record ProgressRequest(Map<String, Long> stats) {
	}

	private record AdminLookupRequest(String target) {
	}

	private record AdminCoinsRequest(String target, int amount, String note) {
	}

	private record AdminItemRequest(String target, String itemId, boolean grant) {
	}

	static void register(HttpServer server, SessionRegistry registry, StoreService store, java.nio.file.Path dataDir) {
		server.createContext("/v1/store/catalog", ex -> VeloServerApp.handle(ex, "GET", e -> JsonHttp.writeJson(e, 200, store.catalog())));
		route(server, registry, "/v1/store/wallet", "GET", (ex, s) -> JsonHttp.writeJson(ex, 200, store.wallet(s.uuid(), s.username())));
		route(server, registry, "/v1/store/buy", "POST", (ex, s) ->
				JsonHttp.writeJson(ex, 200, store.buy(s.uuid(), s.username(), JsonHttp.readBody(ex, BuyRequest.class).itemId())));
		route(server, registry, "/v1/store/checkout", "POST", (ex, s) ->
				JsonHttp.writeJson(ex, 200, store.checkout(s.uuid(), s.username(), JsonHttp.readBody(ex, CheckoutRequest.class).packId())));
		route(server, registry, "/v1/store/order", "GET", (ex, s) -> JsonHttp.writeJson(ex, 200, store.order(s.uuid(), query(ex).get("id"))));

		server.createContext("/v1/store/webhook/tebex", ex -> VeloServerApp.handle(ex, "POST", e -> {
			byte[] body = JsonHttp.readRawBody(e, 512 * 1024);
			if (!store.verifyTebexSignature(body, e.getRequestHeaders().getFirst("X-Signature"))) {
				JsonHttp.writeError(e, 403, "Bad signature");
				return;
			}
			JsonObject event = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonHttp.writeJson(e, 200, store.tebexWebhook(event));
		}));

		route(server, registry, "/v1/admin/store/lookup", "POST", (ex, s) ->
				JsonHttp.writeJson(ex, 200, store.adminLookup(s.uuid(), s.username(), JsonHttp.readBody(ex, AdminLookupRequest.class).target())));
		route(server, registry, "/v1/admin/store/coins", "POST", (ex, s) -> {
			AdminCoinsRequest r = JsonHttp.readBody(ex, AdminCoinsRequest.class);
			JsonHttp.writeJson(ex, 200, store.adminCoins(s.uuid(), s.username(), r.target(), r.amount(), r.note()));
		});
		route(server, registry, "/v1/admin/store/item", "POST", (ex, s) -> {
			AdminItemRequest r = JsonHttp.readBody(ex, AdminItemRequest.class);
			JsonHttp.writeJson(ex, 200, store.adminItem(s.uuid(), s.username(), r.target(), r.itemId(), r.grant()));
		});

		route(server, registry, "/v1/rewards", "GET", (ex, s) -> JsonHttp.writeJson(ex, 200, store.rewards(s.uuid())));
		route(server, registry, "/v1/rewards/login", "POST", (ex, s) -> JsonHttp.writeJson(ex, 200, store.claimLogin(s.uuid())));
		route(server, registry, "/v1/rewards/quest", "POST", (ex, s) ->
				JsonHttp.writeJson(ex, 200, store.claimQuest(s.uuid(), JsonHttp.readBody(ex, QuestRequest.class).questId())));
		route(server, registry, "/v1/rewards/progress", "POST", (ex, s) -> {
			if (!"game".equals(s.kind())) {
				JsonHttp.writeError(ex, 403, "Progress comes from the game only");
				return;
			}
			ProgressRequest r = JsonHttp.readBody(ex, ProgressRequest.class);
			store.reportProgress(s.uuid(), r.stats() == null ? Map.of() : r.stats());
			JsonHttp.writeJson(ex, 200, Map.of("ok", true));
		});
		route(server, registry, "/v1/rewards/ad", "POST", (ex, s) -> JsonHttp.writeJson(ex, 200, store.startAd(s.uuid())));

		server.createContext("/v1/rewards/ayet", ex -> VeloServerApp.handle(ex, "GET", e -> {
			String result = store.ayetCallback(query(e), e.getRequestHeaders().getFirst("X-Ayetstudios-Security-Hash"));
			if (result == null) {
				JsonHttp.writeBytes(e, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8), "no-store");
				return;
			}
			JsonHttp.writeBytes(e, 200, "text/plain", result.getBytes(StandardCharsets.UTF_8), "no-store");
		}));
		server.createContext("/v1/rewards/ad/status", ex -> VeloServerApp.handle(ex, "GET", e -> {
			var view = store.adStatus(query(e).get("t"));
			if (view == null) {
				JsonHttp.writeError(e, 404, "Expired - start again from Velo Client");
				return;
			}
			JsonHttp.writeJson(e, 200, Map.of("adsWatched", view.adsWatched(), "adsPerDay", view.adsPerDay(),
					"adCoins", view.adCoins(), "balance", view.balance()));
		}));

		server.createContext("/store/done", ex -> VeloServerApp.handle(ex, "GET", e -> {
			boolean cancelled = query(e).containsKey("cancelled");
			html(e, cancelled ? "Payment cancelled" : "Thanks for your purchase!",
					cancelled ? "Nothing was charged. You can close this tab."
							: "Your Velo Coins arrive in a few seconds - Velo Client updates by itself. You can close this tab.");
		}));
		// ads.txt lists the ad sellers allowed on this domain; paste the lines from the ayeT dashboard
		// (Placements -> your placement -> ads.txt) into data/ads.txt. Read per request, no restart needed.
		server.createContext("/ads.txt", ex -> VeloServerApp.handle(ex, "GET", e -> {
			java.nio.file.Path file = dataDir.resolve("ads.txt");
			byte[] body = java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllBytes(file) : new byte[0];
			JsonHttp.writeBytes(e, 200, "text/plain; charset=utf-8", body, "max-age=3600");
		}));
		server.createContext("/rewards/ad", ex -> VeloServerApp.handle(ex, "GET", e -> adPage(e, store, query(e).get("t"))));
	}

	private static void route(HttpServer server, SessionRegistry registry, String path, String method, Authed handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			String token = JsonHttp.bearerToken(ex);
			SessionRegistry.Session session = registry.session(token);
			if (session == null) {
				JsonHttp.writeError(ex, 401, "Unknown or expired session - re-authenticate");
				return;
			}
			registry.touch(token);
			handler.handle(ex, session);
		}));
	}

	static Map<String, String> query(HttpExchange exchange) {
		Map<String, String> out = new HashMap<>();
		String raw = exchange.getRequestURI().getRawQuery();
		if (raw == null) {
			return out;
		}
		for (String part : raw.split("&")) {
			int eq = part.indexOf('=');
			if (eq > 0) {
				out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
			} else if (!part.isEmpty()) {
				out.put(URLDecoder.decode(part, StandardCharsets.UTF_8), "");
			}
		}
		return out;
	}

	private static final String PAGE_STYLE = """
			<style>
			:root{color-scheme:dark}
			body{margin:0;min-height:100vh;display:grid;place-items:center;background:radial-gradient(circle at 50% 30%,#3a1010,#0b0707 70%);
			font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif;color:#f2f0f7}
			.card{width:min(440px,calc(100vw - 32px));padding:32px;border-radius:20px;background:#170e0e;border:1px solid #ffffff1a;
			box-shadow:0 20px 60px #0008;text-align:center}
			h1{font-size:22px;margin:0 0 8px}p{color:#b9b3c6;line-height:1.5;margin:0 0 20px}
			.coin{width:56px;height:56px;border-radius:50%;margin:0 auto 16px;background:linear-gradient(135deg,#ffe38a,#f4b82e 55%,#c9861a);
			display:grid;place-items:center;font-weight:900;font-size:28px;color:#7a4a06;box-shadow:0 6px 24px #f4b82e55}
			button{font:inherit;font-weight:700;font-size:16px;padding:14px 28px;border:0;border-radius:12px;cursor:pointer;color:#fff;
			background:linear-gradient(100deg,#ff2a2a,#ff7a3d)}button:disabled{opacity:.5;cursor:default}
			.muted{font-size:13px;color:#8a8398;margin-top:14px}
			</style>""";

	private static void html(HttpExchange exchange, String title, String message) throws java.io.IOException {
		String page = "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
				+ "<title>" + escape(title) + " - Velo Client</title>" + PAGE_STYLE + "</head><body><div class=card><div class=coin>V</div><h1>"
				+ escape(title) + "</h1><p>" + escape(message) + "</p></div></body></html>";
		JsonHttp.writeBytes(exchange, 200, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8), "no-store");
	}

	/** The rewarded-ad page: ayeT-Studios' video player in a Velo-styled card; the reward is credited by ayeT's signed callback. */
	private static void adPage(HttpExchange exchange, StoreService store, String token) throws java.io.IOException {
		String uuid = store.adsEnabled() ? store.adTokenUuid(token) : null;
		if (uuid == null) {
			html(exchange, "Link expired", "Start a new ad from the Velo Client launcher or the in-game Velo Coins screen.");
			return;
		}
		StoreService.AdConfig ads = store.adConfig();
		String page = """
				<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>
				<title>Watch an ad - Velo Client</title>%s
				<script src="https://cdn.ayet.io/offerwall/js/ayetvideosdk.min.js"></script></head><body>
				<div class=card><div class=coin>V</div><h1>Earn free Velo Coins</h1>
				<p id=msg>Watch a short video to get <b id=coins>?</b> coins.</p>
				<button id=watch disabled>Loading ad...</button><div class=muted id=count></div></div>
				<div id=player></div>
				<script>
				const T=%s, PLACEMENT=%s, SLOT=%s, UID=%s;
				const btn=document.getElementById('watch'), msg=document.getElementById('msg'), count=document.getElementById('count');
				let left=0, ready=false;
				async function refresh(){try{const r=await fetch('/v1/rewards/ad/status?t='+T);if(!r.ok){msg.textContent='This link expired - start again from Velo Client.';btn.disabled=true;return}
				const s=await r.json();left=s.adsPerDay-s.adsWatched;document.getElementById('coins').textContent=s.adCoins;
				count.textContent=s.adsWatched+' of '+s.adsPerDay+' watched today - balance '+s.balance+' coins';
				if(left<=0){btn.disabled=true;btn.textContent='All done for today';msg.textContent='That was the last one for today - come back tomorrow!'}}catch(e){}}
				function load(){if(left<=0)return;btn.disabled=true;btn.textContent='Loading ad...';
				AyetVideoSdk.requestAd(SLOT,()=>{ready=true;btn.disabled=false;btn.textContent='Watch ad'},
				()=>{btn.disabled=false;btn.textContent='Try again';msg.textContent='No ad available right now - try again in a minute.'})}
				window.callbackRewarded=()=>{msg.textContent='Nice! Your coins arrive in a few seconds.';setTimeout(()=>refresh().then(load),5000)};
				window.callbackError=()=>{msg.textContent='The ad stopped early - no reward this time.';setTimeout(load,2000)};
				btn.onclick=()=>{if(typeof AyetVideoSdk==='undefined'){msg.textContent='The ad player was blocked (ad blocker?). Allow ads on this page and reload.';return}
				if(!ready){load();return}ready=false;btn.disabled=true;AyetVideoSdk.playFullsizeAd()};
				(async()=>{await refresh();if(typeof AyetVideoSdk==='undefined'){msg.textContent='The ad player was blocked (ad blocker?). Allow ads on this page and reload.';btn.textContent='Blocked';return}
				try{await AyetVideoSdk.init(Number(PLACEMENT),UID);load()}catch(e){msg.textContent='Ads could not start - try again later.'}})();
				</script></body></html>""".formatted(PAGE_STYLE, jsString(token), jsString(ads.placementId()), jsString(ads.adslot()), jsString(uuid));
		JsonHttp.writeBytes(exchange, 200, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8), "no-store");
	}

	private static String escape(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String jsString(String value) {
		return new com.google.gson.Gson().toJson(value == null ? "" : value);
	}
}
