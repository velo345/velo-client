package net.veloclient.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.veloclient.server.store.CosmeticsService;
import net.veloclient.server.store.StoreDatabase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Messages to the Velo Discord through channel webhooks (no bot needed): a nice embed for every
 * coin-pack purchase (the player's skin, what they're wearing, what they own) and one for every bug or
 * crash report. Each webhook URL is optional - without it that kind of message is simply not sent.
 */
final class DiscordAnnouncer {

	private static final int RED = 0xFF4D4D;
	private static final int ORANGE = 0xFF8A1F;

	private final String donationsWebhook;
	private final String reportsWebhook;
	private final String publicUrl;
	private final CosmeticsService cosmetics;
	private final SessionRegistry registry;
	private final StoreDatabase db;

	DiscordAnnouncer(String donationsWebhook, String reportsWebhook, String publicUrl, CosmeticsService cosmetics, SessionRegistry registry,
			StoreDatabase db) {
		this.donationsWebhook = donationsWebhook;
		this.reportsWebhook = reportsWebhook;
		this.publicUrl = publicUrl == null ? null : publicUrl.replaceAll("/+$", "");
		this.cosmetics = cosmetics;
		this.registry = registry;
		this.db = db;
	}

	boolean donationsEnabled() {
		return DiscordWebhook.valid(donationsWebhook);
	}

	boolean reportsEnabled() {
		return DiscordWebhook.valid(reportsWebhook);
	}

	private String name(String itemId) {
		CosmeticsService.Item item = cosmetics.item(itemId);
		return item == null ? itemId : item.name;
	}

	/** A coin pack was paid: who, how much, what they wear and own. */
	void donation(StoreDatabase.Order order, String username, int balance) {
		if (!donationsEnabled()) {
			return;
		}
		String player = username != null ? username : "A Velo player";
		String uuid = order.uuid();
		List<String> owned = new ArrayList<>();
		try {
			owned = db.ownedItems(uuid);
		} catch (Exception ignored) {
			// Shown without it.
		}
		int wings = 0;
		int capes = 0;
		int auras = 0;
		for (String id : owned) {
			if (id.startsWith("wings_")) {
				wings++;
			} else if (id.startsWith("aura_")) {
				auras++;
			} else if (id.startsWith("cape_")) {
				capes++;
			}
		}
		// What they're wearing right now (only known while they're in the game).
		SessionRegistry.OnlineUser online = registry.onlineUsers().stream().filter(u -> u.uuid().equals(uuid)).findFirst().orElse(null);
		List<String> wearing = new ArrayList<>();
		String wingsPreview = null;
		if (online != null) {
			if (online.wingsId() != null) {
				String id = "wings_" + online.wingsId().split(";", 2)[0];
				wearing.add("🪽  " + name(id));
				CosmeticsService.ItemView view = cosmetics.renderInfo(id);
				if (view != null && view.files().get("preview") != null && publicUrl != null) {
					wingsPreview = publicUrl + "/v1/cosmetics/file/" + view.files().get("preview");
				}
			}
			if (online.capeId() != null && !online.capeId().startsWith("custom:")) {
				wearing.add("🧣  " + name(online.capeId()));
			} else if (online.capeId() != null) {
				wearing.add("🧣  Custom cape");
			}
			if (online.auraId() != null) {
				wearing.add("✨  " + name("aura_" + online.auraId().split(";", 2)[0]));
			}
		}
		JsonObject embed = new JsonObject();
		embed.addProperty("title", "💎  " + player + " supports Velo Client!");
		embed.addProperty("description", "**" + player + "** just got **" + String.format(Locale.ROOT, "%,d", order.coins()) + " Velo Coins**"
				+ "  ·  " + String.format(Locale.ROOT, "%.2f", order.price()) + " " + order.currency() + "\nThank you for keeping the project going! ❤");
		embed.addProperty("color", RED);
		embed.addProperty("timestamp", Instant.now().toString());
		JsonObject thumb = new JsonObject();
		thumb.addProperty("url", "https://mc-heads.net/body/" + uuid + "/200");
		embed.add("thumbnail", thumb);
		JsonArray fields = new JsonArray();
		fields.add(field("Wearing", wearing.isEmpty() ? "_nothing equipped (or offline)_" : String.join("\n", wearing), true));
		fields.add(field("Collection", wings + " wings  ·  " + capes + " capes  ·  " + auras + " auras", true));
		fields.add(field("Balance", String.format(Locale.ROOT, "%,d coins", balance), true));
		embed.add("fields", fields);
		if (wingsPreview != null) {
			JsonObject image = new JsonObject();
			image.addProperty("url", wingsPreview);
			embed.add("image", image);
		}
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Velo Client");
		embed.add("footer", footer);
		post(donationsWebhook, embed);
	}

	/** A bug or crash report arrived. */
	void report(ReportService.Summary summary, ReportService.Report report) {
		if (!reportsEnabled()) {
			return;
		}
		boolean crash = "crash".equals(summary.kind());
		JsonObject embed = new JsonObject();
		embed.addProperty("title", (crash ? "💥  Crash report" : "🐞  Bug report") + (summary.auto() ? " (automatic)" : ""));
		String title = summary.title() == null || summary.title().isBlank() ? "(no title)" : summary.title();
		StringBuilder description = new StringBuilder("**" + clip(title, 200) + "**");
		if (report.message != null && !report.message.isBlank()) {
			description.append("\n").append(clip(report.message, 900));
		}
		embed.addProperty("description", description.toString());
		embed.addProperty("color", crash ? 0xE23B3B : ORANGE);
		embed.addProperty("timestamp", Instant.ofEpochMilli(summary.createdAt()).toString());
		JsonArray fields = new JsonArray();
		fields.add(field("Player", summary.username() == null ? "unknown" : summary.username(), true));
		fields.add(field("From", summary.source(), true));
		fields.add(field("Velo / Minecraft", (summary.version() == null ? "?" : summary.version()) + " / " + (summary.minecraft() == null ? "?" : summary.minecraft()), true));
		if (summary.groupLabel() != null && !summary.groupLabel().isBlank()) {
			fields.add(field("Looks like", "`" + clip(summary.groupLabel(), 180) + "`", false));
		}
		if (report.mods != null && !report.mods.isEmpty()) {
			fields.add(field("Mods", report.mods.size() + " installed", true));
		}
		fields.add(field("Report id", "`" + summary.id() + "`", true));
		embed.add("fields", fields);
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "Read it in the launcher: News > Bug reports");
		embed.add("footer", footer);
		post(reportsWebhook, embed);
	}

	private static JsonObject field(String name, String value, boolean inline) {
		JsonObject f = new JsonObject();
		f.addProperty("name", name);
		f.addProperty("value", value.isBlank() ? "-" : clip(value, 1000));
		f.addProperty("inline", inline);
		return f;
	}

	private static String clip(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max - 1) + "…";
	}

	private static void post(String url, JsonObject embed) {
		JsonObject payload = new JsonObject();
		payload.addProperty("username", "Velo Client");
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		payload.add("embeds", embeds);
		DiscordWebhook.send(url, payload);
	}
}
