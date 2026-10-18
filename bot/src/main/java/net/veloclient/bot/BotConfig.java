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
	/** The channel where players paste one-time Velo Client link codes. */
	String linkChannelId;
	/** Given to members who linked their Minecraft account (optional). */
	String linkedRoleId;
	/** The VIP role of Velo+ members - kept in step automatically (given, and taken away when Velo+ ends). */
	String vipRoleId;
	/** Also take the VIP role from anyone holding it without an active Velo+ (false: only from linked members whose Velo+ ended). */
	boolean vipStrict = true;
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
	/** Default ticket types already offered once - a type you delete from the file isn't put back. */
	List<String> seededTypes = new ArrayList<>();
	/** Discord roles that make someone Velo staff: role id to "helper", "mod" or "admin" (synced to the Velo server). */
	Map<String, String> staffRoles = new LinkedHashMap<>();
	/** Where the welcome card for new members is posted (null: no welcome messages). */
	String welcomeChannelId;
	/** A channel the welcome points new members to (e.g. the rules), optional. */
	String welcomeRulesChannelId;
	/** Where boost thank-yous go (falls back to the server's system channel). */
	String boostChannelId;
	/** The bot trap: anyone who isn't staff writing here is removed. */
	String honeypotChannelId;
	/** "softban" (kick and wipe their last day of messages - they can rejoin) or "ban". */
	String honeypotAction = "softban";
	/** Where honeypot catches are logged (falls back to the ticket log). */
	String modLogChannelId;
	/** Giveaways by id, running and ended (ended ones are kept for /giveaway reroll). */
	Map<String, Giveaway> giveaways = new LinkedHashMap<>();

	/** A giveaway: a post with an Enter button; the prize goes straight to the winners' Velo accounts. */
	static final class Giveaway {
		String id;
		String guildId;
		String channelId;
		String messageId;
		String hostId;
		String title;
		String description;
		/** "cosmetic" (value: item id), "coins" (value: amount) or "plus" (value: days). */
		String prizeType;
		String prizeValue;
		String prizeName;
		int winners = 1;
		long endsAt;
		boolean ended;
		/** Only members with this role may enter (optional). */
		String requiredRoleId;
		/** The post carries a picture (giveaway.png). */
		boolean hasImage;
		List<String> entrants = new ArrayList<>();
		List<String> winnerIds = new ArrayList<>();
	}

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
		if (config.staffRoles == null) {
			config.staffRoles = new LinkedHashMap<>();
		}
		if (config.giveaways == null) {
			config.giveaways = new LinkedHashMap<>();
		}
		if (config.seededTypes == null) {
			config.seededTypes = new ArrayList<>();
		}
		config.file = file;
		boolean changed = false;
		if (config.ticketTypes == null || config.ticketTypes.isEmpty()) {
			config.ticketTypes = defaultTypes();
			changed = true;
		}
		// New default types (like staff applications) reach existing configs once.
		for (TicketType type : defaultTypes()) {
			if (!config.seededTypes.contains(type.id)) {
				config.seededTypes.add(type.id);
				if (config.ticketTypes.stream().noneMatch(t -> t.id.equals(type.id))) {
					config.ticketTypes.add(type);
				}
				changed = true;
			}
		}
		if (changed) {
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
		this.linkChannelId = fresh.linkChannelId;
		this.linkedRoleId = fresh.linkedRoleId;
		this.vipRoleId = fresh.vipRoleId;
		this.vipStrict = fresh.vipStrict;
		this.maxOpenTicketsPerUser = fresh.maxOpenTicketsPerUser;
		this.ticketPanelTitle = fresh.ticketPanelTitle;
		this.ticketPanelText = fresh.ticketPanelText;
		this.rolesPanelTitle = fresh.rolesPanelTitle;
		this.rolesPanelText = fresh.rolesPanelText;
		this.staffRoles = fresh.staffRoles;
		this.boostChannelId = fresh.boostChannelId;
		this.welcomeChannelId = fresh.welcomeChannelId;
		this.welcomeRulesChannelId = fresh.welcomeRulesChannelId;
		this.honeypotChannelId = fresh.honeypotChannelId;
		this.honeypotAction = fresh.honeypotAction;
		this.modLogChannelId = fresh.modLogChannelId;
	}

	TicketType type(String id) {
		return ticketTypes.stream().filter(t -> t.id.equals(id)).findFirst().orElse(ticketTypes.get(0));
	}

	/** The kinds a fresh bot starts with - change, add and remove them in bot-config.json. */
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
		TicketType other = type("other", "Other", "💬", "Anything else - questions, ideas, feedback.", "secondary", "#99aab5",
				new Question("message", "How can we help?", "paragraph", "Tell us more", true, 1500));
		TicketType staff = type("staff", "Staff application", "🛡", "Want to help run the community? Apply for the team.", "primary", "#ff4d4d",
				new Question("about", "Name, age & timezone", "short", "e.g. Alex, 17, CET", true, 100),
				new Question("why", "Why do you want to join the team?", "paragraph", "What would you bring?", true, 1200),
				new Question("experience", "Moderation / community experience", "paragraph", "Servers you helped run, what you did there", true, 1200),
				new Question("time", "How much time can you give?", "short", "e.g. 1-2 hours a day, weekends", true, 100),
				new Question("mc", "Minecraft username", "short", "Your Velo Client account", true, 16));
		staff.welcome = "Thanks for applying! The team reads every application - please be patient, and don't ping staff about it.";
		staff.maxOpen = 1;
		TicketType partner = type("partner", "Partner inquiry", "🤝", "Servers, creators and projects that want to work with us.", "success", "#f5a623",
				new Question("who", "Who are you / what's your project?", "short", "Server, channel or project name", true, 120),
				new Question("link", "Link & size", "short", "Invite / channel link, members or subscribers", true, 200),
				new Question("idea", "What do you have in mind?", "paragraph", "What kind of partnership, and what's in it for both sides?", true, 1500));
		partner.welcome = "Thanks for reaching out! Someone from the team will get back to you here.";
		return new ArrayList<>(List.of(support, bug, purchase, staff, partner, other));
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
