package net.veloclient.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** The Velo server's Discord endpoints (admin token): link codes, unlinking, boosts, giveaway prizes and the staff list. */
final class ServerApi {

	/** An answer to show the Discord user as it is. */
	static final class ApiException extends Exception {
		ApiException(String message) {
			super(message);
		}
	}

	record Linked(String uuid, String username) {
	}

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final String baseUrl;
	private final String adminToken;

	ServerApi(String baseUrl, String adminToken) {
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.adminToken = adminToken;
	}

	Linked link(String code, String discordId, String discordName) throws ApiException {
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		body.addProperty("discordId", discordId);
		body.addProperty("discordName", discordName);
		JsonObject answer = post("/v1/admin/discord/link", body);
		return new Linked(answer.get("uuid").getAsString(), answer.get("username").getAsString());
	}

	/** A linked Discord account: whether its Minecraft account has an active Velo+ right now. */
	record LinkInfo(String discordId, String uuid, String username, boolean plus) {
	}

	java.util.List<LinkInfo> links() throws ApiException {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/admin/discord/links")).timeout(Duration.ofSeconds(15))
					.header("X-Velo-Admin-Token", adminToken).GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				throw new ApiException(errorMessage(response.statusCode()));
			}
			java.util.List<LinkInfo> out = new java.util.ArrayList<>();
			for (var element : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("links")) {
				JsonObject o = element.getAsJsonObject();
				out.add(new LinkInfo(o.get("discordId").getAsString(), o.get("uuid").getAsString(), o.get("username").getAsString(), o.get("plus").getAsBoolean()));
			}
			return out;
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("Couldn't reach the Velo server");
		}
	}

	boolean unlink(String discordId) throws ApiException {
		JsonObject body = new JsonObject();
		body.addProperty("discordId", discordId);
		return post("/v1/admin/discord/unlink", body).get("removed").getAsBoolean();
	}

	/** What a boost claim did: {@code linked} false means the member has to link first. */
	record BoostClaim(boolean linked, boolean granted, boolean alreadyClaimed, String username, long plusUntil, boolean newCape, int days) {
	}

	/** What recording one announced boost did: given right away (linked) with how many days, or waiting for the Claim button. */
	record BoostCredit(boolean linked, boolean granted, String username, int days, long plusUntil, boolean newCape) {
	}

	BoostCredit creditBoost(String discordId, String boostId, int count) throws ApiException {
		JsonObject body = new JsonObject();
		body.addProperty("discordId", discordId);
		body.addProperty("boostId", boostId);
		body.addProperty("count", count);
		JsonObject a = post("/v1/admin/discord/boost-credit", body);
		return new BoostCredit(bool(a, "linked"), bool(a, "granted"), a.has("username") ? a.get("username").getAsString() : null,
				a.has("days") ? a.get("days").getAsInt() : 0, a.has("plusUntil") ? a.get("plusUntil").getAsLong() : 0, bool(a, "newCape"));
	}

	BoostClaim claimBoost(String discordId, long boostedAt) throws ApiException {
		JsonObject body = new JsonObject();
		body.addProperty("discordId", discordId);
		body.addProperty("boostedAt", boostedAt);
		JsonObject a = post("/v1/admin/discord/booster", body);
		return new BoostClaim(bool(a, "linked"), bool(a, "granted"), bool(a, "alreadyClaimed"),
				a.has("username") ? a.get("username").getAsString() : null, a.has("plusUntil") ? a.get("plusUntil").getAsLong() : 0, bool(a, "newCape"),
				a.has("days") ? a.get("days").getAsInt() : 0);
	}

	/** Gives a linked member coins, Velo+ days or an item; returns their Minecraft name, or null if they aren't linked. */
	String grant(String discordId, String type, String value, String note) throws ApiException {
		JsonObject body = new JsonObject();
		body.addProperty("discordId", discordId);
		body.addProperty("type", type);
		body.addProperty("value", value);
		body.addProperty("note", note);
		JsonObject a = post("/v1/admin/discord/grant", body);
		return bool(a, "linked") ? a.get("username").getAsString() : null;
	}

	/** Sends the full staff list (Discord id to "mod"/"admin"); the server hands out or takes back the staff cape and Velo+. */
	JsonObject syncStaff(java.util.Map<String, String> levels) throws ApiException {
		com.google.gson.JsonArray members = new com.google.gson.JsonArray();
		levels.forEach((id, level) -> {
			JsonObject m = new JsonObject();
			m.addProperty("discordId", id);
			m.addProperty("level", level);
			members.add(m);
		});
		JsonObject body = new JsonObject();
		body.add("members", members);
		return post("/v1/admin/staff/sync", body);
	}

	private volatile java.util.Map<String, String> items = java.util.Map.of();
	private volatile long itemsAt;

	/** Every cosmetic the server knows (id to name) - the Store's items and the uploaded wings/capes; cached for 5 minutes. */
	java.util.Map<String, String> items() {
		if (System.currentTimeMillis() - itemsAt < 300_000 && !items.isEmpty()) {
			return items;
		}
		java.util.Map<String, String> fresh = new java.util.TreeMap<>();
		for (String path : new String[] {"/v1/store/catalog", "/v1/cosmetics/catalog"}) {
			try {
				HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10)).GET().build();
				HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() != 200) {
					continue;
				}
				for (var element : JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("items")) {
					JsonObject o = element.getAsJsonObject();
					if (o.has("id") && o.has("name") && !o.get("name").isJsonNull()) {
						fresh.put(o.get("id").getAsString(), o.get("name").getAsString());
					}
				}
			} catch (Exception ignored) {
			}
		}
		if (!fresh.isEmpty()) {
			items = fresh;
			itemsAt = System.currentTimeMillis();
		}
		return items;
	}

	/** A cosmetic's display name (falls back to the id when the server doesn't know it). */
	String itemName(String itemId) {
		return items().getOrDefault(itemId, itemId);
	}

	/** The picture of a cosmetic as PNG bytes, or null if the server has none. */
	byte[] card(String itemId) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(cardLink(itemId))).timeout(Duration.ofSeconds(15)).GET().build();
			HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
			return response.statusCode() == 200 ? response.body() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** Downloads a picture (a Discord attachment) so it can be re-attached for good - attachment links expire. */
	static byte[] download(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
			HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
			return response.statusCode() == 200 ? response.body() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private String cardLink(String itemId) {
		return baseUrl + "/v1/cosmetics/card?id=" + java.net.URLEncoder.encode(itemId, java.nio.charset.StandardCharsets.UTF_8);
	}

	private static boolean bool(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
	}

	private JsonObject post(String path, JsonObject body) throws ApiException {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
					.header("Content-Type", "application/json").header("X-Velo-Admin-Token", adminToken)
					.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			JsonObject json;
			try {
				json = JsonParser.parseString(response.body()).getAsJsonObject();
			} catch (RuntimeException e) {
				json = new JsonObject();
			}
			if (response.statusCode() / 100 != 2) {
				throw new ApiException(response.statusCode() == 403 ? errorMessage(403)
						: json.has("error") ? json.get("error").getAsString() : errorMessage(response.statusCode()));
			}
			return json;
		} catch (ApiException e) {
			throw e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException("Interrupted");
		} catch (Exception e) {
			throw new ApiException("Couldn't reach the Velo server");
		}
	}

	private static String errorMessage(int status) {
		if (status == 403) {
			return "The Velo server rejected the bot's admin token (HTTP 403). Check that VELO_ADMIN_TOKEN matches on the bot and server.";
		}
		return "The Velo server answered HTTP " + status;
	}
}
