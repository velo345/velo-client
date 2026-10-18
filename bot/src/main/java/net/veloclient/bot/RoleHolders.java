package net.veloclient.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dv8tion.jda.api.JDA;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Who holds which roles, read from Discord's REST member list (1000 members per page). JDA's {@code findMembersWithRoles}
 * asks the gateway for member chunks instead, and with the member cache off those requests time out
 * ("Encountered exception trying to handle member chunk response: TimeoutException") - which made the staff and VIP
 * syncs fail every time. Needs the Server Members intent (already switched on for the bot).
 */
final class RoleHolders {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	private RoleHolders() {
	}

	/** Member id -> the subset of {@code roleIds} they hold, for every (non-bot) member holding at least one of them. */
	static Map<String, Set<String>> of(JDA jda, String guildId, Set<String> roleIds) throws IOException, InterruptedException {
		Map<String, Set<String>> out = new LinkedHashMap<>();
		String token = jda.getToken();
		if (!token.startsWith("Bot ")) {
			token = "Bot " + token;
		}
		String after = "0";
		for (int page = 0; page < 500; page++) {
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://discord.com/api/v10/guilds/" + guildId + "/members?limit=1000&after=" + after))
					.header("Authorization", token).header("User-Agent", "DiscordBot (https://velo-client-flame.vercel.app, 1.0)")
					.timeout(Duration.ofSeconds(20)).GET().build();
			HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() == 429) {
				double wait = 1;
				try {
					wait = JsonParser.parseString(response.body()).getAsJsonObject().get("retry_after").getAsDouble();
				} catch (RuntimeException ignored) {
					// default
				}
				Thread.sleep((long) (Math.max(0.2, wait) * 1000) + 100);
				page--;
				continue;
			}
			if (response.statusCode() != 200) {
				// A failed page throws, so a Discord hiccup never looks like "nobody has the role" (which would strip everyone).
				throw new IOException("Discord member list: HTTP " + response.statusCode() + " " + response.body());
			}
			JsonArray members = JsonParser.parseString(response.body()).getAsJsonArray();
			for (JsonElement e : members) {
				JsonObject member = e.getAsJsonObject();
				JsonObject user = member.getAsJsonObject("user");
				String id = user.get("id").getAsString();
				after = id;
				if (user.has("bot") && user.get("bot").getAsBoolean()) {
					continue;
				}
				for (JsonElement role : member.getAsJsonArray("roles")) {
					if (roleIds.contains(role.getAsString())) {
						out.computeIfAbsent(id, k -> new java.util.HashSet<>()).add(role.getAsString());
					}
				}
			}
			if (members.size() < 1000) {
				return out;
			}
		}
		return out;
	}
}
