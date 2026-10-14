package net.veloclient.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything the server owner sets up with commands - roles panel, tickets, log channels - saved in {@code bot-config.json}. */
final class BotConfig {

	/** A self-assignable ping role on the roles panel. */
	static final class PanelRole {
		String roleId;
		String label;
		String emoji;
		String description;
	}

	/** One question of a ticket type's form (a Discord form has at most 5). */
	static final class Question {
		String id;
		String label;
		/** "short" (one line) or "paragraph". */
		String style = "short";
		String placeholder;
		boolean required = true;
		int maxLength = 200;

		Question() {
		}

		Question(String id, String label, String style, String placeholder, boolean required, int maxLength) {
			this.id = id;
			this.label = label;
			this.style = style;
			this.placeholder = placeholder;
			this.required = required;
			this.maxLength = maxLength;
		}
	}

	/**
	 * A kind of ticket - everything about it is set here (or in the file): its button, form, where the
	 * channel goes, who sees it and who gets pinged.
	 */
	static final class TicketType {
		String id;
		String label;
		String emoji;
		/** One line on the panel. */
		String description;
		/** Button colour: "primary" (blurple), "secondary" (grey), "success" (green) or "danger" (red). */
		String buttonStyle = "primary";
		/** Embed colour in the ticket, "#rrggbb". */
		String color = "#ff4d4d";
		/** Where this type's channels go (falls back to the general ticket category). */
		String categoryId;
		/** Roles that see and handle this type (the general support role too, if set). */
		List<String> staffRoleIds = new ArrayList<>();
		/** Roles pinged when a ticket of this type opens (default: the staff roles). */
		List<String> pingRoleIds = new ArrayList<>();
		/** The channel name: {number}, {user}, {type}. */
		String channelName = "{type}-{number}-{user}";
		/** Open tickets of this type per member (0 = the general limit). */
		int maxOpen;
		/** Message shown in the ticket under the answers. */
		String welcome = "The team will be with you soon.";
		List<Question> questions = new ArrayList<>();
	}

	/** An open ticket. */
	static final class Ticket {
		int number;
		String channelId;
		String userId;
		String userName;
		String type;
		String subject;
		String claimedBy;
		long createdAt;
		Map<String, String> answers = new LinkedHashMap<>();
	}

	String ticketCategoryId;
	String ticketLogChannelId;
	String supportRoleId;
	/** Given to members who linked their Minecraft account (optional). */
	String linkedRoleId;
	int ticketCounter;
	int maxOpenTicketsPerUser = 2;
	String ticketPanelTitle = "Need help?";
	String ticketPanelText = "Open a ticket and the team will help you. Pick what fits best.";
	List<TicketType> ticketTypes = new ArrayList<>();
	List<PanelRole> roles = new ArrayList<>();
	String rolesPanelChannelId;
	String rolesPanelMessageId;
	String rolesPanelTitle = "Pick your pings";
	String rolesPanelText = "Press a button to get - or drop - a ping role. You only get pinged for what you pick.";
	Map<String, Ticket> tickets = new LinkedHashMap<>();

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private transient Path file;

	static BotConfig load(Path dir) throws IOException {
		Files.createDirectories(dir);
		Path file = dir.resolve("bot-config.json");
		BotConfig config = Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), BotConfig.class) : new BotConfig();
		if (config == null) {
			config = new BotConfig();
		}
		if (config.roles == null) {
			config.roles = new ArrayList<>();
		}
		if (config.tickets == null) {
			config.tickets = new LinkedHashMap<>();
		}
		config.file = file;
		if (config.ticketTypes == null || config.ticketTypes.isEmpty()) {
			config.ticketTypes = defaultTypes();
			config.save();
		}
		return config;
	}

	/** Re-reads the file (after editing it by hand); keeps the open tickets and counter if they're not in the file. */
	synchronized void reload() throws IOException {
		BotConfig fresh = load(file.getParent());
		this.ticketTypes = fresh.ticketTypes;
		this.roles = fresh.roles;
		this.ticketCategoryId = fresh.ticketCategoryId;
		this.ticketLogChannelId = fresh.ticketLogChannelId;
		this.supportRoleId = fresh.supportRoleId;
		this.linkedRoleId = fresh.linkedRoleId;
		this.maxOpenTicketsPerUser = fresh.maxOpenTicketsPerUser;
		this.ticketPanelTitle = fresh.ticketPanelTitle;
		this.ticketPanelText = fresh.ticketPanelText;
		this.rolesPanelTitle = fresh.rolesPanelTitle;
		this.rolesPanelText = fresh.rolesPanelText;
	}

	TicketType type(String id) {
		return ticketTypes.stream().filter(t -> t.id.equals(id)).findFirst().orElse(ticketTypes.get(0));
	}

	/** The four kinds a fresh bot starts with - change, add and remove them in bot-config.json. */
	static List<TicketType> defaultTypes() {
		TicketType support = type("support", "Support", "🛠", "Something isn't working or you need help.", "primary", "#5865f2",
				new Question("issue", "What's wrong?", "paragraph", "What happened, and what did you expect?", true, 1500),
				new Question("versions", "Velo / Minecraft version", "short", "e.g. Velo 0.9.0 on Minecraft 26.2", false, 100));
		TicketType bug = type("bug", "Bug report", "🐞", "Report a bug - what happened and how to repeat it.", "danger", "#ed4245",
				new Question("what", "What happened?", "paragraph", "Describe the bug", true, 1200),
				new Question("steps", "How can we repeat it?", "paragraph", "1. ... 2. ... 3. ...", false, 1000),
				new Question("versions", "Velo / Minecraft version", "short", "e.g. Velo 0.9.0 on Minecraft 26.2", true, 100),
				new Question("report", "Bug report id (optional)", "short", "From the in-game / launcher bug report", false, 40));
		TicketType purchase = type("purchase", "Purchase / coins", "💎", "A problem with a purchase, coins or cosmetics.", "success", "#57f287",
				new Question("name", "Minecraft username", "short", "The account you bought it on", true, 16),
				new Question("what", "What went wrong?", "paragraph", "What did you buy, when, and what happened?", true, 1200),
				new Question("order", "Order / transaction id", "short", "From your Tebex receipt (optional)", false, 80));
		TicketType other = type("other", "Other", "💬", "Anything else - questions, ideas, partnerships.", "secondary", "#99aab5",
				new Question("message", "How can we help?", "paragraph", "Tell us more", true, 1500));
		return new ArrayList<>(List.of(support, bug, purchase, other));
	}

	private static TicketType type(String id, String label, String emoji, String description, String style, String color, Question... questions) {
		TicketType type = new TicketType();
		type.id = id;
		type.label = label;
		type.emoji = emoji;
		type.description = description;
		type.buttonStyle = style;
		type.color = color;
		type.questions = new ArrayList<>(List.of(questions));
		return type;
	}

	synchronized void save() {
		try {
			Path tmp = file.resolveSibling("bot-config.json.tmp");
			Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			System.err.println("Couldn't save bot-config.json: " + e.getMessage());
		}
	}
}
