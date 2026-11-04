package net.veloclient.server.store;

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
import java.util.HexFormat;

/**
 * Talks to the Tebex Headless API (https://docs.tebex.io/developers/headless-api/overview): the packages (coin packs,
 * Velo+ monthly / yearly) are set up in the Tebex webstore with their prices; we open a basket, put the package in and
 * send the player to Tebex's checkout. Tebex is the merchant of record: it takes the payment (cards, PayPal,
 * paysafecard, ...), handles VAT/sales tax, refunds and chargebacks, and pays out.
 *
 * <p>Our order id and the player's uuid ride along in the basket's {@code custom} data and come back in the webhook.
 * (Tebex's Checkout API, where we set item and price per checkout, is only for registered companies.)
 */
public final class TebexCheckout {

	private static final String API = "https://headless.tebex.io/api";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private final String publicToken;
	private final String webhookSecret;

	public record Basket(String ident, String checkoutUrl) {
	}

	/** {@code publicToken}: the webstore's public token (Tebex panel - Integrations - API keys). */
	public TebexCheckout(String publicToken, String webhookSecret) {
		this.publicToken = publicToken;
		this.webhookSecret = webhookSecret;
	}

	public boolean canCheckout() {
		return publicToken != null;
	}

	public boolean canVerifyWebhooks() {
		return webhookSecret != null;
	}

	/** A basket holding coin pack {@code packageId}, for order {@code orderId}. */
	public Basket createCheckout(String orderId, String uuid, String username, long packageId, String returnUrl, String completeUrl) throws Exception {
		return createCheckout(orderId, uuid, username, packageId, returnUrl, completeUrl, null, null);
	}

	/**
	 * Like {@link #createCheckout(String, String, String, long, String, String)}; with a {@code coupon} (the partner code
	 * discount) it's put on the basket too, and the checkout fails if Tebex doesn't take it - nobody pays full price
	 * believing their code worked.
	 */
	public Basket createCheckout(String orderId, String uuid, String username, long packageId, String returnUrl, String completeUrl,
			String coupon, String partnerCode) throws Exception {
		JsonObject custom = new JsonObject();
		custom.addProperty("velo_order", orderId);
		custom.addProperty("velo_uuid", uuid);
		custom.addProperty("velo_username", username);
		if (partnerCode != null) {
			custom.addProperty("velo_code", partnerCode);
		}
		Basket basket = basket(custom, packageId, returnUrl, completeUrl);
		if (coupon != null) {
			JsonObject body = new JsonObject();
			body.addProperty("coupon_code", coupon);
			JsonObject after = data(send("/accounts/" + publicToken + "/baskets/" + basket.ident() + "/coupons", body));
			if (!after.has("coupons") || !after.get("coupons").isJsonArray() || after.getAsJsonArray("coupons").isEmpty()) {
				throw new IllegalStateException("Tebex didn't apply the partner coupon");
			}
		}
		return basket;
	}

	/** A basket holding the Velo+ subscription package {@code packageId} (monthly, or yearly when {@code yearly}). */
	public Basket createSubscriptionCheckout(String uuid, String username, long packageId, String returnUrl, String completeUrl, boolean yearly)
			throws Exception {
		JsonObject custom = new JsonObject();
		custom.addProperty("velo_plus", true);
		if (yearly) {
			custom.addProperty("velo_plus_period", "year");
		}
		custom.addProperty("velo_uuid", uuid);
		custom.addProperty("velo_username", username);
		return basket(custom, packageId, returnUrl, completeUrl);
	}

	private Basket basket(JsonObject custom, long packageId, String returnUrl, String completeUrl)
			throws Exception {
		JsonObject create = new JsonObject();
		create.addProperty("complete_url", completeUrl);
		create.addProperty("cancel_url", returnUrl);
		create.addProperty("complete_auto_redirect", true);
		create.add("custom", custom.deepCopy());
		JsonObject basket = data(send("/accounts/" + publicToken + "/baskets", create));
		String ident = basket.get("ident").getAsString();
		JsonObject add = new JsonObject();
		add.addProperty("package_id", packageId);
		add.addProperty("quantity", 1);
		// on the package too: the webhook reports it per product (where the handler reads it first)
		add.add("custom", custom.deepCopy());
		JsonObject filled = data(send("/baskets/" + ident + "/packages", add));
		String checkout = checkoutLink(filled);
		if (checkout == null) {
			checkout = checkoutLink(basket);
		}
		return new Basket(ident, checkout != null ? checkout : "https://pay.tebex.io/" + ident);
	}

	private static String checkoutLink(JsonObject basket) {
		return basket.has("links") && basket.get("links").isJsonObject() && basket.getAsJsonObject("links").has("checkout")
				? basket.getAsJsonObject("links").get("checkout").getAsString() : null;
	}

	private static JsonObject data(JsonObject json) {
		return json.has("data") && json.get("data").isJsonObject() ? json.getAsJsonObject("data") : json;
	}

	private static JsonObject send(String path, JsonObject body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + path))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(20))
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IllegalStateException("Tebex " + path.replaceAll("/accounts/[^/]+", "/accounts/*") + " failed (HTTP " + response.statusCode() + "): "
					+ response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
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
