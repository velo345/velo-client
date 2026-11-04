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
 * <li>{@code GET /v1/store/wallet}, {@code POST /v1/store/buy}, {@code POST /v1/store/welcome-gift}, {@code POST /v1/store/checkout},
 * {@code GET /v1/store/order} - signed-in player</li>
 * <li>{@code POST /v1/store/webhook/tebex} - Tebex payment events (signature-checked)</li>
 * <li>{@code POST /v1/admin/store/{lookup,coins,item}} - owner only</li>
 * <li>{@code POST /v1/store/gift}, {@code POST /v1/store/wishlist}, {@code GET /v1/store/wishlist/list} - signed-in player</li>
 * <li>{@code GET /v1/referral}, {@code POST /v1/referral/redeem} - invite friends, enter a friend's code</li>
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

	private record CheckoutRequest(String packId, String code) {
	}

	private record PartnerCodeRequest(String action, String previous, net.veloclient.server.store.PartnerCodes.Code code) {
	}

	private static Map<String, Object> partnerCodesView(StoreService store) throws Exception {
		var stats = store.database().partnerCodeStats();
		java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
		for (var c : store.partnerCodes().all()) {
			Map<String, Object> row = new java.util.LinkedHashMap<>();
			row.put("code", c.code());
			row.put("partner", c.partner());
			row.put("partnerId", c.partnerId());
			row.put("active", c.active());
			row.put("note", c.note());
			row.put("type", c.creator() ? "creator" : "coupon");
			row.put("tebexCode", c.tebexCode());
			row.put("tebexName", c.tebexName());
			row.put("tebexOk", c.tebexOk());
			row.put("percent", c.percent());
			row.put("checkedAt", c.checkedAt());
			row.put("usable", c.usable());
			row.put("createdAt", c.createdAt());
			row.put("stats", stats.getOrDefault(c.code(), Map.of("orders", 0, "revenue", 0, "coins", 0, "buyers", 0, "lastAt", 0, "orders30d", 0)));
			rows.add(row);
		}
		return Map.of("codes", rows, "live", store.partnerCodesLive(),
				"currency", store.config().currency);
	}

	private record GiftRequest(String itemId, String to, String note) {
	}

	private record WishlistRequest(String itemId, boolean on) {
	}

	private record RedeemRequest(String code) {
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
		route(server, registry, "/v1/store/welcome-gift", "POST", (ex, s) ->
				JsonHttp.writeJson(ex, 200, store.claimWelcomeGift(s.uuid(), s.username(), JsonHttp.readBody(ex, BuyRequest.class).itemId())));
		route(server, registry, "/v1/store/checkout", "POST", (ex, s) -> {
			CheckoutRequest r = JsonHttp.readBody(ex, CheckoutRequest.class);
			JsonHttp.writeJson(ex, 200, store.checkout(s.uuid(), s.username(), r.packId(), r.code()));
		});
		// GET /v1/store/code?c=<code> - a partner code's partner and discount (the Store shows it before checkout).
		server.createContext("/v1/store/code", ex -> VeloServerApp.handle(ex, "GET", e -> JsonHttp.writeJson(e, 200, store.partnerCode(query(e).get("c")))));
		// Owner: partner codes with their stats; POST {action: "save", previous, code: {...}} or {action: "delete", previous}.
		route(server, registry, "/v1/admin/partner-codes", null, (ex, s) -> {
			if (!store.isOwner(s.uuid(), s.username())) {
				JsonHttp.writeError(ex, 403, "Only the server owner can do that");
				return;
			}
			if ("POST".equals(ex.getRequestMethod())) {
				PartnerCodeRequest r = JsonHttp.readBody(ex, PartnerCodeRequest.class);
				var codes = store.partnerCodes();
				if ("delete".equals(r.action())) {
					codes.delete(r.previous());
				} else if ("check".equals(r.action())) {
					store.checkPartnerCode(net.veloclient.server.store.PartnerCodes.normalize(r.previous()));
				} else {
					var saved = codes.save(r.previous(), r.code());
					String prev = net.veloclient.server.store.PartnerCodes.normalize(r.previous());
					if (prev != null && !prev.equals(saved.code())) {
						store.database().renamePartnerCode(prev, saved.code());
					}
					store.checkPartnerCode(saved.code());
				}
				System.out.println("[store] " + s.username() + " " + ("delete".equals(r.action()) ? "deleted" : "saved") + " partner code "
						+ (r.code() != null ? r.code().code() : r.previous()));
			}
			JsonHttp.writeJson(ex, 200, partnerCodesView(store));
		});
		route(server, registry, "/v1/store/gift", "POST", (ex, s) -> {
			GiftRequest r = JsonHttp.readBody(ex, GiftRequest.class);
			JsonHttp.writeJson(ex, 200, store.gift(s.uuid(), s.username(), r.itemId(), r.to()));
		});
		route(server, registry, "/v1/store/wishlist/list", "GET", (ex, s) -> JsonHttp.writeJson(ex, 200, Map.of("items", store.wishlist(s.uuid()))));
		route(server, registry, "/v1/store/wishlist", "POST", (ex, s) -> {
			WishlistRequest r = JsonHttp.readBody(ex, WishlistRequest.class);
			JsonHttp.writeJson(ex, 200, Map.of("items", store.setWishlisted(s.uuid(), r.itemId(), r.on())));
		});
		route(server, registry, "/v1/referral", "GET", (ex, s) -> JsonHttp.writeJson(ex, 200, store.referral(s.uuid(), s.username())));
		route(server, registry, "/v1/referral/redeem", "POST", (ex, s) ->
				JsonHttp.writeJson(ex, 200, store.redeemReferral(s.uuid(), s.username(), JsonHttp.readBody(ex, RedeemRequest.class).code())));
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

		route(server, registry, "/v1/admin/store/grant-all", "POST", (ex, s) ->
				JsonHttp.writeJson(ex, 200, store.adminGrantAll(s.uuid(), s.username(), JsonHttp.readBody(ex, AdminLookupRequest.class).target())));

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

		server.createContext("/store/done/status", ex -> VeloServerApp.handle(ex, "GET", e -> {
			StoreService.OrderSummary order = store.orderSummary(query(e).get("order"));
			if (order == null) {
				JsonHttp.writeError(e, 404, "Unknown order");
				return;
			}
			JsonHttp.writeJson(e, 200, Map.of("status", order.status(), "coins", order.coins()));
		}));
		server.createContext("/store/done.js", ex -> VeloServerApp.handle(ex, "GET", e ->
				JsonHttp.writeBytes(e, 200, "text/javascript; charset=utf-8", THANKS_JS.getBytes(StandardCharsets.UTF_8), "max-age=300")));
		server.createContext("/store/done", ex -> VeloServerApp.handle(ex, "GET", e -> {
			Map<String, String> q = query(e);
			if (q.containsKey("cancelled")) {
				html(e, "Payment cancelled", "Nothing was charged. You can close this tab.");
				return;
			}
			StoreService.OrderSummary order = q.containsKey("order") ? store.orderSummary(q.get("order")) : null;
			thanksPage(e, q.containsKey("plus"), order, q.get("order"));
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
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method == null ? exchange.getRequestMethod() : method, ex -> {
			String token = JsonHttp.bearerToken(ex);
			SessionRegistry.Session session = registry.session(token);
			if (session == null && path.startsWith("/v1/admin/")) {
				// The owner panel (panel.velo-client.com) has no Minecraft session: it acts as the first owner with the admin token.
				session = panelSession(ex);
			}
			if (session == null) {
				JsonHttp.writeError(ex, 401, "Unknown or expired session - re-authenticate");
				return;
			}
			registry.touch(token);
			handler.handle(ex, session);
		}));
	}

	private static final String ADMIN_TOKEN = System.getenv("VELO_ADMIN_TOKEN") == null || System.getenv("VELO_ADMIN_TOKEN").isBlank()
			? null : System.getenv("VELO_ADMIN_TOKEN").trim();
	private static volatile String panelOwner;

	/** Set by the server app: the uuid the owner panel acts as (the first VELO_OWNERS entry). */
	static void setPanelOwner(String uuid) {
		panelOwner = uuid;
	}

	static SessionRegistry.Session panelSession(HttpExchange exchange) {
		String header = exchange.getRequestHeaders().getFirst("X-Velo-Admin-Token");
		if (ADMIN_TOKEN == null || header == null || panelOwner == null || !java.security.MessageDigest.isEqual(
				ADMIN_TOKEN.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8))) {
			return null;
		}
		return new SessionRegistry.Session(panelOwner, "Owner panel", SessionRegistry.KIND_WEB, null, null, null, null, Long.MAX_VALUE);
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

	/**
	 * The thank-you page Tebex sends the buyer to: confetti, their head, the coins counting up - and a live status that
	 * turns green once the coins are really in their wallet (it asks every 2 s until the webhook has landed). Velo+ gets
	 * its own welcome.
	 */
	private static void thanksPage(HttpExchange exchange, boolean plus, StoreService.OrderSummary order, String orderId) throws java.io.IOException {
		String head = order == null ? "" : "<img class=head src='/v1/skins/" + escape(order.uuid()) + "/head.png' alt=''>";
		String title = plus ? "Welcome to Velo+" : "Thanks for your purchase!";
		String amount = plus ? "<div class=plus>VELO<b>+</b></div>"
				: order == null ? "<div class=coin>V</div>" : "<div class=amount><span class=coin>V</span><span id=n>" + String.format(java.util.Locale.ROOT, "%,d", order.coins())
						+ "</span></div><div class=unit>Velo Coins</div>";
		String sub = plus ? "World Hosting, Cloud Sync, Clips Pro, Name Styles, your own Home background and monthly coins - all switched on."
				: "Your coins are on their way to your Velo account.";
		String page = """
				<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>
				<title>%s - Velo Client</title><style>
				:root{color-scheme:dark}*{box-sizing:border-box}
				body{margin:0;min-height:100vh;display:grid;place-items:center;overflow:hidden;font-family:Inter,system-ui,-apple-system,Segoe UI,sans-serif;
				color:#f4f2f8;background:radial-gradient(1200px 600px at 50%% -10%%,#5c1414 0%%,#1a0a0a 45%%,#070505 100%%)}
				canvas{position:fixed;inset:0;pointer-events:none}
				.card{position:relative;width:min(460px,calc(100vw - 32px));padding:38px 34px 30px;border-radius:24px;text-align:center;
				background:linear-gradient(180deg,#1d1111ee,#120a0aee);border:1px solid #ffffff1c;box-shadow:0 30px 90px #000a,0 0 0 1px #ff3b3b14 inset;
				animation:pop .7s cubic-bezier(.2,1.4,.4,1) both}
				@keyframes pop{from{transform:translateY(24px) scale(.94);opacity:0}}
				.logo{font-weight:900;letter-spacing:.18em;font-size:13px;color:#ff5a5a;margin-bottom:18px}
				.head{width:72px;height:72px;image-rendering:pixelated;border-radius:14px;box-shadow:0 10px 30px #0009;margin-bottom:14px;
				animation:pop .7s .15s cubic-bezier(.2,1.4,.4,1) both}
				h1{font-size:26px;margin:0 0 6px;letter-spacing:-.02em}
				.sub{color:#b9b3c6;line-height:1.5;margin:0 0 22px;font-size:15px}
				.amount{display:flex;align-items:center;justify-content:center;gap:12px;font-size:44px;font-weight:900;letter-spacing:-.03em}
				.coin{width:46px;height:46px;border-radius:50%%;display:inline-grid;place-items:center;font-size:24px;font-weight:900;color:#7a4a06;
				background:linear-gradient(135deg,#ffe38a,#f4b82e 55%%,#c9861a);box-shadow:0 6px 24px #f4b82e66;animation:spin 1.6s ease-out both}
				@keyframes spin{from{transform:rotateY(720deg) scale(.4)}}
				.unit{color:#f4b82e;font-weight:700;font-size:14px;letter-spacing:.08em;text-transform:uppercase;margin-top:2px}
				.plus{font-size:46px;font-weight:900;letter-spacing:.04em}.plus b{color:#ff3b3b;text-shadow:0 0 24px #ff3b3baa}
				.status{display:inline-flex;align-items:center;gap:10px;margin-top:24px;padding:10px 16px;border-radius:999px;font-size:14px;font-weight:600;
				background:#ffffff0d;border:1px solid #ffffff1a;color:#cfc9da;transition:all .4s}
				.status.ok{background:#1f3d2b;border-color:#3ddc9755;color:#8ff0bd}
				.dot{width:9px;height:9px;border-radius:50%%;background:#f4b82e;box-shadow:0 0 12px #f4b82e;animation:pulse 1s infinite alternate}
				.ok .dot{background:#3ddc97;box-shadow:0 0 12px #3ddc97;animation:none}
				@keyframes pulse{to{opacity:.35}}
				.close{margin-top:18px;font-size:13px;color:#8a8398}
				</style></head><body data-order="%s" data-coins="%d"><canvas id=c></canvas><div class=card>
				<div class=logo>VELO CLIENT</div>%s<h1>%s</h1><p class=sub>%s</p>%s
				<div><div class="status%s" id=st><span class=dot></span><span id=stt>%s</span></div></div>
				<div class=close>You can close this tab - Velo Client updates by itself.</div></div>
				<script src="/store/done.js" defer></script></body></html>
				""".formatted(escape(title), orderId == null || plus ? "" : escape(orderId), order == null ? 0 : order.coins(), head, escape(title), escape(sub), amount,
				plus || order != null && "paid".equals(order.status()) ? " ok" : "",
				plus ? "Velo+ is active on your account" : order != null && "paid".equals(order.status()) ? "In your wallet - enjoy!" : "Confirming your payment...");
		JsonHttp.writeBytes(exchange, 200, "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8), "no-store");
	}

	/**
	 * The thank-you page's script, served as a file: velo-client.com's Content-Security-Policy blocks inline scripts
	 * (an inline one left the page at "0 coins - Confirming your payment..." for good).
	 */
	private static final String THANKS_JS = """
				const ORDER=document.body.dataset.order||null, COINS=+document.body.dataset.coins||0;
				// Confetti: Velo red, white and gold, two bursts from the bottom corners, then a gentle rain.
				const c=document.getElementById('c'),x=c.getContext('2d');let W,H;const P=[];
				const size=()=>{W=c.width=innerWidth*devicePixelRatio;H=c.height=innerHeight*devicePixelRatio};size();addEventListener('resize',size);
				const cols=['#ff3b3b','#ff7a3d','#ffffff','#f4b82e','#ffe38a','#ff5a8a'];
				const burst=(sx,dir,n)=>{for(let i=0;i<n;i++){const a=-Math.PI/2+dir*(Math.random()*.9+.1),s=(10+Math.random()*14)*devicePixelRatio;
				P.push({x:sx,y:H,vx:Math.cos(a)*s*.55,vy:Math.sin(a)*s,r:Math.random()*6.28,vr:(Math.random()-.5)*.4,w:(6+Math.random()*7)*devicePixelRatio,
				h:(4+Math.random()*5)*devicePixelRatio,c:cols[i%cols.length],l:1})}};
				burst(W*.08,1,140);burst(W*.92,-1,140);setTimeout(()=>{burst(W*.5,(Math.random()-.5)*.4,90)},450);
				let rain=0;const tick=()=>{x.clearRect(0,0,W,H);if(rain<220&&Math.random()<.5){rain++;P.push({x:Math.random()*W,y:-20,vx:0,vy:2*devicePixelRatio,
				r:Math.random()*6.28,vr:(Math.random()-.5)*.2,w:7*devicePixelRatio,h:4*devicePixelRatio,c:cols[rain%cols.length],l:1})}
				for(let i=P.length-1;i>=0;i--){const p=P[i];p.vy+=.32*devicePixelRatio;p.vx*=.985;p.vy=Math.min(p.vy,7*devicePixelRatio);p.x+=p.vx;p.y+=p.vy;
				p.r+=p.vr;if(p.y>H+40){P.splice(i,1);continue}x.save();x.translate(p.x,p.y);x.rotate(p.r);x.scale(1,Math.cos(p.r*2));x.fillStyle=p.c;
				x.fillRect(-p.w/2,-p.h/2,p.w,p.h);x.restore()}requestAnimationFrame(tick)};tick();
				// The coins counting up.
				const n=document.getElementById('n');if(n&&COINS){const t0=performance.now();const step=t=>{const k=Math.min(1,(t-t0)/1400),e=1-Math.pow(1-k,3);
				n.textContent=Math.round(COINS*e).toLocaleString();if(k<1)requestAnimationFrame(step)};requestAnimationFrame(step)}
				// Live: green once the coins are really in the wallet.
				const st=document.getElementById('st'),stt=document.getElementById('stt');
				const done=()=>{st.classList.add('ok');stt.textContent='In your wallet - enjoy!'};
				if(ORDER&&!st.classList.contains('ok')){const poll=async()=>{try{const r=await fetch('/store/done/status?order='+encodeURIComponent(ORDER));
				if(r.ok){const j=await r.json();if(j.status==='paid'){done();return}}}catch(e){}setTimeout(poll,2000)};poll()}
				""";

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
