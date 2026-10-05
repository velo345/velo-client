package net.veloclient.server.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Talks to the Tebex Checkout API (https://docs.tebex.io/developers/checkout-api/overview) - the
 * server-side flavour of Tebex where WE define the item and its price per checkout, so there's no
 * webstore or package setup to keep in sync. Tebex is the merchant of record: it takes the
 * payment (cards, PayPal, paysafecard, ...), handles VAT/sales tax, refunds and chargebacks, and
 * pays out to you.
 */
public final class TebexCheckout {

	private static final String API = "https://checkout.tebex.io/api";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final String projectId;
	private final String privateKey;
	private final String webhookSecret;

	public record Basket(String ident, String checkoutUrl) {
	}

	public TebexCheckout(String projectId, String privateKey, String webhookSecret) {
		this.projectId = projectId;
		this.privateKey = privateKey;
		this.webhookSecret = webhookSecret;
	}

	public boolean canCheckout() {
		return projectId != null && privateKey != null;
	}

	public boolean canVerifyWebhooks() {
		return webhookSecret != null;
	}

	/**
	 * Creates a basket holding one coin pack and returns where to send the player. Our order id and
	 * the player's uuid ride along in the item's {@code custom} map and come back in the webhook.
	 */
	public Basket createCheckout(String orderId, String uuid, String username, String itemName, double price,
			String returnUrl, String completeUrl) throws Exception {
		JsonObject custom = new JsonObject();
		custom.addProperty("velo_order", orderId);
		custom.addProperty("velo_uuid", uuid);
		custom.addProperty("velo_username", username);

		JsonObject pack = new JsonObject();
		pack.addProperty("name", itemName);
		pack.addProperty("price", price);
		pack.addProperty("type", "single");
		pack.add("custom", custom);
		JsonObject item = new JsonObject();
		item.add("package", pack);
		item.addProperty("qty", 1);
		JsonArray items = new JsonArray();
		items.add(item);

		JsonObject basket = new JsonObject();
		basket.addProperty("return_url", returnUrl);
		basket.addProperty("complete_url", completeUrl);
		basket.add("custom", custom.deepCopy());
		JsonObject body = new JsonObject();
		body.add("basket", basket);
		body.add("items", items);

		String auth = Base64.getEncoder().encodeToString((projectId + ":" + privateKey).getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/checkout"))
				.header("Authorization", "Basic " + auth)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(20))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IllegalStateException("Tebex checkout failed (HTTP " + response.statusCode() + "): " + response.body());
		}
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		if (json.has("data") && json.get("data").isJsonObject()) {
			json = json.getAsJsonObject("data");
		}
		String ident = json.get("ident").getAsString();
		String checkout = json.has("links") && json.getAsJsonObject("links").has("checkout")
				? json.getAsJsonObject("links").get("checkout").getAsString()
				: "https://pay.tebex.io/" + ident;
		return new Basket(ident, checkout);
	}

	/**
	 * Tebex signs each webhook as {@code HMAC-SHA256(key = secret, data = hex(SHA256(raw body)))},
	 * hex-encoded, in the {@code X-Signature} header. Compared in constant time.
	 */
	public boolean verifySignature(byte[] rawBody, String signatureHeader) {
		if (webhookSecret == null || signatureHeader == null) {
			return false;
		}
		try {
			String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			String expected = HexFormat.of().formatHex(mac.doFinal(bodyHash.getBytes(StandardCharsets.UTF_8)));
			return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
					signatureHeader.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			return false;
		}
	}
}
