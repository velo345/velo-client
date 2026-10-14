package net.veloclient.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.veloclient.server.store.CosmeticsService;
import net.veloclient.server.store.StoreService;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The cosmetics catalog ({@link CosmeticsService}):
 * <ul>
 * <li>{@code GET /v1/cosmetics/catalog} - what's in the store, plus what you own (signed in); owners get everything</li>
 * <li>{@code GET /v1/cosmetics/item?id=} - what's needed to draw any item on another player</li>
 * <li>{@code GET /v1/cosmetics/file/<sha256>} - art (meshes, textures, GIFs), cacheable forever</li>
 * <li>{@code GET /v1/admin/cosmetics}, {@code POST /v1/admin/cosmetics/{item,file,collection,featured}} - owner only (an
 * owner session, or the {@code X-Velo-Admin-Token} header for scripts like tools/cosmetics/publish_wings.py)</li>
 * </ul>
 */
final class CosmeticsRoutes {

	private CosmeticsRoutes() {
	}

	@FunctionalInterface
	private interface Admin {
		void handle(HttpExchange exchange) throws Exception;
	}

	/** An item change; {@code clearWindow} removes the "available from/until" window. */
	private record ItemRequest(CosmeticsService.Item item, boolean clearWindow) {
	}

	private record FeaturedRequest(String collection) {
	}

	static void register(HttpServer server, SessionRegistry registry, StoreService store, CosmeticsService cosmetics, String adminToken) {
		server.createContext("/v1/cosmetics/catalog", ex -> VeloServerApp.handle(ex, "GET", e -> {
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(e));
			Set<String> owned = new HashSet<>();
			boolean owner = false;
			if (session != null) {
				owned.addAll(store.wallet(session.uuid(), session.username()).owned());
				owner = store.isOwner(session.uuid(), session.username());
			}
			JsonHttp.writeJson(e, 200, cosmetics.catalog(owned, owner));
		}));
		server.createContext("/v1/cosmetics/item", ex -> VeloServerApp.handle(ex, "GET", e -> {
			CosmeticsService.ItemView view = cosmetics.renderInfo(StoreRoutes.query(e).get("id"));
			if (view == null) {
				JsonHttp.writeError(e, 404, "No such item");
				return;
			}
			JsonHttp.writeJson(e, 200, view);
		}));
		server.createContext("/v1/cosmetics/file/", ex -> VeloServerApp.handle(ex, "GET", e -> {
			String sha = e.getRequestURI().getPath().substring("/v1/cosmetics/file/".length());
			byte[] bytes = cosmetics.file(sha);
			if (bytes == null) {
				JsonHttp.writeError(e, 404, "No such file");
				return;
			}
			JsonHttp.writeBytes(e, 200, CosmeticsService.contentType(bytes), bytes, "public, max-age=31536000, immutable");
		}));

		admin(server, registry, store, adminToken, "/v1/admin/cosmetics", "GET", e -> JsonHttp.writeJson(e, 200, cosmetics.admin()));
		admin(server, registry, store, adminToken, "/v1/admin/cosmetics/item", "POST", e -> {
			ItemRequest r = JsonHttp.readBody(e, ItemRequest.class, 64 * 1024);
			JsonHttp.writeJson(e, 200, cosmetics.upsert(r.item(), r.clearWindow()));
		});
		admin(server, registry, store, adminToken, "/v1/admin/cosmetics/file", "POST", e ->
				JsonHttp.writeJson(e, 200, Map.of("sha256", cosmetics.storeFile(JsonHttp.readRawBody(e, CosmeticsService.MAX_FILE_BYTES)))));
		admin(server, registry, store, adminToken, "/v1/admin/cosmetics/collection", "POST", e ->
				JsonHttp.writeJson(e, 200, cosmetics.upsertCollection(JsonHttp.readBody(e, CosmeticsService.Collection.class))));
		admin(server, registry, store, adminToken, "/v1/admin/cosmetics/featured", "POST", e -> {
			cosmetics.setFeatured(JsonHttp.readBody(e, FeaturedRequest.class).collection());
			JsonHttp.writeJson(e, 200, Map.of("ok", true));
		});
	}

	private static void admin(HttpServer server, SessionRegistry registry, StoreService store, String adminToken, String path, String method,
			Admin handler) {
		server.createContext(path, exchange -> VeloServerApp.handle(exchange, method, ex -> {
			String header = ex.getRequestHeaders().getFirst("X-Velo-Admin-Token");
			if (adminToken != null && header != null && java.security.MessageDigest.isEqual(
					adminToken.getBytes(java.nio.charset.StandardCharsets.UTF_8), header.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
				handler.handle(ex);
				return;
			}
			SessionRegistry.Session session = registry.session(JsonHttp.bearerToken(ex));
			if (session == null) {
				JsonHttp.writeError(ex, 401, "Unknown or expired session - re-authenticate");
				return;
			}
			if (!store.isOwner(session.uuid(), session.username())) {
				JsonHttp.writeError(ex, 403, "Only the server owner can do that");
				return;
			}
			handler.handle(ex);
		}));
	}
}
